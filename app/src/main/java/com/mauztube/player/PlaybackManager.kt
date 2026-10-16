package com.mauztube.player

import com.mauztube.util.tr

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.mauztube.data.account.AccountManager
import com.mauztube.data.model.ShortsBlockedException
import com.mauztube.data.model.VideoDetails
import com.mauztube.data.model.VideoItem
import com.mauztube.data.prefs.AppSettings
import com.mauztube.data.prefs.SettingsRepository
import com.mauztube.data.repository.PlaybackRepository
import com.mauztube.data.repository.RecommendationRepository
import com.mauztube.data.repository.SponsorBlockRepository
import com.mauztube.data.repository.SponsorSegment
import com.mauztube.data.repository.VideoCacheRepository
import com.mauztube.data.repository.YouTubeRepository
import com.mauztube.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

sealed interface PlayerUiState {
    data object Loading : PlayerUiState
    data class Error(val message: String, val retryable: Boolean) : PlayerUiState
    data class Ready(
        val details: VideoDetails,
        val segments: List<SponsorSegment>,
        val resumedFromMs: Long,
    ) : PlayerUiState
}

/** Video attualmente caricato nel player (aperto a schermo o nel mini player). */
data class NowPlaying(
    val videoId: String,
    val preview: VideoItem?,
    val state: PlayerUiState,
)

data class QualityOption(val height: Int, val label: String)
data class TextTrackOption(val index: Int, val label: String)

/**
 * Unico player dell'app, indipendente dalle schermate: è ciò che rende possibili il mini
 * player (si continua a navigare mentre il video va) e la riproduzione in background / a
 * schermo spento, esposta al sistema da [PlaybackService] (MediaSession + notifica).
 *
 * Coordina inoltre:
 *  - RIPRESA: posizione per videoId salvata in Room (periodicamente, in pausa, quando l'app
 *    va in background, alla chiusura); il seek alla posizione salvata avviene prima di prepare().
 *  - SPONSORBLOCK: controllo posizione ogni 200 ms durante la riproduzione.
 *  - BACKGROUND: quando l'app non è visibile la traccia video viene disattivata (si scarica e
 *    decodifica solo l'audio: meno dati e batteria) e riattivata al ritorno.
 *  - SOLO AUDIO, QUALITÀ, SOTTOTITOLI, VELOCITÀ.
 *
 * Tutti gli accessi a ExoPlayer avvengono sul main thread ([main]).
 */
@Singleton
class PlaybackManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: YouTubeRepository,
    private val playback: PlaybackRepository,
    private val sponsorRepo: SponsorBlockRepository,
    private val settingsRepo: SettingsRepository,
    private val mediaSourceBuilder: MediaSourceBuilder,
    private val recommendations: RecommendationRepository,
    private val account: AccountManager,
    private val videoCache: VideoCacheRepository,
    private val ratings: com.mauztube.data.repository.RatingRepository,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Coda seriale: i salvataggi vengono scritti nell'ordine in cui sono stati richiesti. */
    private val saveDispatcher = Dispatchers.IO.limitedParallelism(1)

    /**
     * Creato al primo video (non all'avvio dell'app): avvio più rapido e niente thread/
     * memoria del player finché non serve.
     */
    val player: ExoPlayer by lazy {
        buildPlayer().also {
            it.addListener(listener)
            it.addAnalyticsListener(PlaybackDiagnostics())
        }
    }

    private fun buildPlayer(): ExoPlayer = ExoPlayer.Builder(
        context,
        // Se il decoder hardware non si avvia per un formato, si ripiega su un altro decoder
        // invece di fermare la riproduzione con un errore.
        androidx.media3.exoplayer.DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setMediaCodecSelector(codecSelector),
    )
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK) // continua a schermo spento senza che la CPU/Wi-Fi dormano
        .setSeekBackIncrementMs(10_000)
        .setSeekForwardIncrementMs(10_000)
        .setLoadControl(mediaSourceBuilder.loadControl())
        .setTrackSelector(mediaSourceBuilder.trackSelector(context))
        .build()

    private val skipper by lazy { SponsorSkipper(player) }

    private val _nowPlaying = MutableStateFlow<NowPlaying?>(null)
    val nowPlaying: StateFlow<NowPlaying?> = _nowPlaying

    /** Schermo intero, condiviso con la navigazione (che toglie i margini di sistema). */
    private val _fullscreen = MutableStateFlow(false)
    val fullscreen: StateFlow<Boolean> = _fullscreen
    fun setFullscreen(value: Boolean) { _fullscreen.value = value }

    /** Conteggi mi piace / non mi piace (Return YouTube Dislike) del video corrente. */
    private val _votes = MutableStateFlow<com.mauztube.data.repository.VoteCounts?>(null)
    val votes: StateFlow<com.mauztube.data.repository.VoteCounts?> = _votes

    /** Stato di riproduzione osservabile: i cicli periodici dormono mentre il video è in pausa. */
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying

    /** Dimensioni del video corrente (rapporto della finestra popup). */
    private val _videoSize = MutableStateFlow(androidx.media3.common.VideoSize.UNKNOWN)
    val videoSize: StateFlow<androidx.media3.common.VideoSize> = _videoSize

    /** Loop del video corrente (si azzera aprendo un altro video). */
    private val _loop = MutableStateFlow(false)
    val loop: StateFlow<Boolean> = _loop

    /** Video già riprodotti in questa sessione: la riproduzione automatica non li ripropone. */
    private val playedThisSession = LinkedHashSet<String>()

    private val _audioOnly = MutableStateFlow(false)
    val audioOnly: StateFlow<Boolean> = _audioOnly

    /**
     * CODA: video in attesa dopo quello corrente (in memoria: alla chiusura dell'app sparisce).
     * Quando il video corrente finisce parte il primo della coda; a coda esaurita la sezione
     * della coda scompare dall'interfaccia.
     */
    private val _queue = MutableStateFlow<List<VideoItem>>(emptyList())
    val queue: StateFlow<List<VideoItem>> = _queue

    private val _skipEvents = MutableSharedFlow<SkipEvent>(extraBufferCapacity = 4)
    val skipEvents: SharedFlow<SkipEvent> = _skipEvents

    private var details: VideoDetails? = null
    private var settings = AppSettings()
    private var loadJob: Job? = null
    private var loopsJob: Job? = null
    private var resumeOverrideMs: Long? = null
    private var autoRetried = false
    private var preferredVideoMime: String? = null
    private var inBackground = false
    private var watchSession: RecommendationRepository.WatchSession? = null
    private var personalizedRelated: List<VideoItem>? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    /** Aperto dalla cronologia: riprende dal punto salvato anche se la ripresa automatica è spenta. */
    private var forceResume = false

    /**
     * Decoder che hanno dato errore durante la decodifica: vengono esclusi e la riproduzione
     * riprende con il successivo (tipicamente quello software). Alcuni decoder hardware sono
     * difettosi con certi profili video.
     */
    private val blockedDecoders = java.util.concurrent.CopyOnWriteArraySet<String>()
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private val codecSelector = androidx.media3.exoplayer.mediacodec.MediaCodecSelector { mime, secure, tunneling ->
        androidx.media3.exoplayer.mediacodec.MediaCodecUtil.getDecoderInfos(mime, secure, tunneling)
            .filter { it.name !in blockedDecoders }
    }

    /** @return true se l'errore era di un decoder e si è ripartiti con un altro. */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun recoverFromDecoderError(error: PlaybackException): Boolean {
        if (error.errorCode != PlaybackException.ERROR_CODE_DECODING_FAILED &&
            error.errorCode != PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
        ) return false
        var cause: Throwable? = error
        var name: String? = null
        while (cause != null && name == null) {
            name = when (cause) {
                is androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException -> cause.codecInfo?.name
                is androidx.media3.exoplayer.mediacodec.MediaCodecRenderer.DecoderInitializationException -> cause.codecInfo?.name
                else -> null
            }
            cause = cause.cause
        }
        if (name == null || !blockedDecoders.add(name)) return false
        Log.w(TAG, "Decoder $name escluso dopo un errore: si riprova con il successivo")
        val position = player.currentPosition
        player.seekTo(position)
        player.prepare()
        player.playWhenReady = true
        return true
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _isPlaying.value = isPlaying
            if (!isPlaying && player.playbackState != Player.STATE_ENDED) savePosition()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                savePosition(finished = true)
                reportWatchProgress()
                val next = _queue.value.firstOrNull()
                val autoNext = if (next == null && settingsRepo.state.value?.autoplayNext == true) {
                    details?.related?.firstOrNull { it.id !in playedThisSession && !it.isLive }
                } else null
                when {
                    next != null -> {
                        _queue.value = _queue.value.drop(1)
                        main.launch { open(next.id, next) }
                    }
                    // Riproduzione automatica: primo consigliato non ancora visto in questa sessione.
                    autoNext != null -> main.launch { open(autoNext.id, autoNext) }
                    else -> appScope.launch(saveDispatcher) { playback.clearActiveVideo() }
                }
            }
        }

        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            _videoSize.value = videoSize
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) skipper.onSeek(newPosition.positionMs)
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "Errore di riproduzione", error)
            if (recoverFromDecoderError(error)) return
            // Gli URL googlevideo scadono (~6 h): un tentativo automatico di ri-estrazione.
            if (!autoRetried) {
                autoRetried = true
                resumeOverrideMs = player.currentPosition
                load(forceRefresh = true)
            } else {
                setState(PlayerUiState.Error(tr("Playback error: ${error.errorCodeName}", "Errore di riproduzione: ${error.errorCodeName}"), retryable = true))
            }
        }
    }

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = onAppForeground()
            override fun onStop(owner: LifecycleOwner) = onAppBackground()
        })
    }

    // ---------------- Comandi ----------------

    /** Apre un video; se è già quello caricato non fa nulla (es. ritorno dal mini player). */
    /** @param startMs punto di partenza esplicito (link con t=), prevale sulla ripresa. */
    fun open(videoId: String, preview: VideoItem?, resume: Boolean = false, startMs: Long? = null) {
        if (_nowPlaying.value?.videoId == videoId) {
            startMs?.let { player.seekTo(it) }
            return
        }
        forceResume = resume
        playedThisSession += videoId
        if (playedThisSession.size > 200) playedThisSession.remove(playedThisSession.first())
        _loop.value = false
        player.repeatMode = Player.REPEAT_MODE_OFF
        // Il video precedente viene chiuso salvandone posizione e cronologia.
        if (_nowPlaying.value != null) {
            savePosition()
            reportWatchProgress()
        }
        details = null
        watchSession = null
        personalizedRelated = null
        autoRetried = false
        resumeOverrideMs = startMs
        skipper.setSegments(emptyList())
        _votes.value = null
        player.stop()
        player.clearMediaItems()
        _nowPlaying.value = NowPlaying(videoId, preview, PlayerUiState.Loading)
        connectSessionService()
        load()
    }

    fun retry() {
        autoRetried = false
        resumeOverrideMs = player.currentPosition.takeIf { it > 0 }
        load(forceRefresh = true)
    }

    /** Chiusura esplicita (mini player scartato): ferma tutto e non riapre all'avvio. */
    fun close() {
        if (_nowPlaying.value == null) return
        savePosition()
        reportWatchProgress()
        loadJob?.cancel()
        loopsJob?.cancel()
        player.stop()
        player.clearMediaItems()
        details = null
        _nowPlaying.value = null
        _queue.value = emptyList()
        _fullscreen.value = false
        appScope.launch(saveDispatcher) { playback.clearActiveVideo() }
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
    }

    fun setAudioOnly(enabled: Boolean) {
        _audioOnly.value = enabled
        applyVideoTrackState()
    }

    fun undoSkip(event: SkipEvent) = skipper.undo(event)

    /** Loop del video corrente. */
    fun setLoop(enabled: Boolean) {
        _loop.value = enabled
        player.repeatMode = if (enabled) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    /** Riproduzione automatica del prossimo consigliato (impostazione salvata). */
    val autoplay: StateFlow<Boolean> by lazy {
        settingsRepo.settings.map { it.autoplayNext }
            .stateIn(main, kotlinx.coroutines.flow.SharingStarted.Eagerly, settingsRepo.state.value?.autoplayNext ?: false)
    }

    fun setAutoplay(enabled: Boolean) {
        appScope.launch { settingsRepo.setAutoplayNext(enabled) }
    }

    // ---------------- Coda ----------------

    /** Aggiunge in fondo alla coda; se non sta suonando nulla, il video parte subito. */
    fun enqueue(video: VideoItem) {
        if (_nowPlaying.value == null) {
            open(video.id, video)
            return
        }
        if (video.id == _nowPlaying.value?.videoId || _queue.value.any { it.id == video.id }) return
        _queue.value = _queue.value + video
        prefetchNextInQueue()
    }

    fun enqueueAll(videos: List<VideoItem>, playFirst: Boolean) {
        val list = videos.distinctBy { it.id }
        if (list.isEmpty()) return
        if (playFirst) {
            _queue.value = list.drop(1)
            open(list.first().id, list.first()) // salva da sé la posizione del video precedente
            prefetchNextInQueue()
        } else {
            list.forEach(::enqueue)
        }
    }

    fun removeFromQueue(index: Int) {
        _queue.value = _queue.value.filterIndexed { i, _ -> i != index }
    }

    fun moveInQueue(from: Int, to: Int) {
        val list = _queue.value.toMutableList()
        if (from !in list.indices) return
        val item = list.removeAt(from)
        list.add(to.coerceIn(0, list.size), item)
        _queue.value = list
        prefetchNextInQueue()
    }

    /** Riproduce subito un video della coda (viene tolto dalla coda). */
    fun playFromQueue(index: Int) {
        val item = _queue.value.getOrNull(index) ?: return
        removeFromQueue(index)
        open(item.id, item)
    }

    fun clearQueue() { _queue.value = emptyList() }

    /** Estrae in anticipo il prossimo video della coda: il passaggio sarà quasi istantaneo. */
    private fun prefetchNextInQueue() {
        _queue.value.firstOrNull()?.let { repo.prefetch(listOf(it.id), max = 1, evenIfMetered = true) }
    }

    // ---------------- Qualità / sottotitoli / velocità ----------------

    fun qualities(): List<QualityOption> = videoGroups()
        .flatMap { g -> (0 until g.length).map { g.getTrackFormat(it).height } }
        .filter { it > 0 }.distinct().sortedDescending()
        .map { QualityOption(it, "${it}p") }

    /** null = automatica (entro minima/massima). */
    fun selectedQuality(): Int? {
        val override = player.trackSelectionParameters.overrides.values
            .firstOrNull { it.type == C.TRACK_TYPE_VIDEO } ?: return null
        return override.mediaTrackGroup.getFormat(override.trackIndices.firstOrNull() ?: 0).height
    }

    fun currentHeight(): Int? = player.videoFormat?.height?.takeIf { it > 0 }

    fun selectQuality(height: Int?) {
        val params = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        if (height != null) {
            // A parità di risoluzione si preferisce la famiglia di codec scelta in automatico.
            val groups = videoGroups().sortedBy { if (it.getTrackFormat(0).sampleMimeType == mimeOf(preferredVideoMime)) 0 else 1 }
            for (g in groups) {
                val index = (0 until g.length).firstOrNull { g.getTrackFormat(it).height == height } ?: continue
                params.setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, index))
                break
            }
        }
        player.trackSelectionParameters = params.build()
    }

    fun textTracks(): List<TextTrackOption> = textGroups().mapIndexed { i, g ->
        val f = g.getTrackFormat(0)
        TextTrackOption(i, f.label ?: f.language ?: tr("Track ${i + 1}", "Traccia ${i + 1}"))
    }

    fun selectedTextTrack(): Int? {
        if (player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT)) return null
        return textGroups().indexOfFirst { it.isSelected }.takeIf { it >= 0 }
    }

    fun selectTextTrack(index: Int?) {
        val params = player.trackSelectionParameters.buildUpon().clearOverridesOfType(C.TRACK_TYPE_TEXT)
        if (index == null) {
            params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            val group = textGroups().getOrNull(index) ?: return
            params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
        }
        player.trackSelectionParameters = params.build()
    }

    fun setSpeed(speed: Float) = player.setPlaybackSpeed(speed)

    private fun videoGroups(): List<Tracks.Group> =
        player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO && it.length > 0 }

    private fun textGroups(): List<Tracks.Group> =
        player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT && it.length > 0 }

    private fun mimeOf(containerMime: String?): String? = when {
        containerMime == null -> null
        containerMime.contains("webm") -> "video/x-vnd.on2.vp9"
        else -> "video/avc"
    }

    // ---------------- Caricamento ----------------

    private fun load(forceRefresh: Boolean = false) {
        val videoId = _nowPlaying.value?.videoId ?: return
        loadJob?.cancel()
        loopsJob?.cancel()
        setState(PlayerUiState.Loading)
        loadJob = main.launch {
            settings = settingsRepo.current()
            applyTrackPreferences()
            if (account.loggedIn.value && personalizedRelated == null) launch { loadPersonalizedRelated(videoId) }

            val extracted = try {
                repo.videoDetails(videoId, forceRefresh)
            } catch (e: ShortsBlockedException) {
                setState(PlayerUiState.Error(e.message.orEmpty(), retryable = false))
                return@launch
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Estrazione fallita", e)
                setState(PlayerUiState.Error(e.message ?: e.javaClass.simpleName, retryable = true))
                return@launch
            }
            val d = personalizedRelated?.let { extracted.copy(related = it) } ?: extracted
            details = d

            // RIPRESA: la posizione va impostata prima di prepare().
            val resumeMs = resumeOverrideMs
                ?: if ((settings.resumeEnabled || forceResume) && !d.isLive) playback.resumePositionMs(videoId) else 0L
            resumeOverrideMs = null

            val built = try {
                mediaSourceBuilder.build(d, settings.maxVideoHeight)
            } catch (e: Exception) {
                setState(PlayerUiState.Error(e.message ?: tr("Stream unavailable", "Stream non disponibile"), retryable = true))
                return@launch
            }
            preferredVideoMime = built.preferredVideoMime
            applyTrackPreferences()
            player.setMediaSource(built.source, resumeMs)
            player.prepare()
            player.playWhenReady = true

            setState(PlayerUiState.Ready(d, emptyList(), resumeMs))
            prefetchNextInQueue()
            if (settings.showDislikes && !d.isLive) {
                launch { ratings.counts(videoId)?.let { if (_nowPlaying.value?.videoId == videoId) _votes.value = it } }
            }
            if (personalizedRelated == null && d.related.isNotEmpty()) {
                if (!incognito) appScope.launch { videoCache.saveRelated(videoId, d.related) }
            }
            if (!incognito) appScope.launch(saveDispatcher) { playback.setActiveVideo(videoId) }
            savePosition()

            loopsJob = main.launch {
                launch { periodicSaveLoop() }
                launch { sponsorLoop(videoId, d) }
                launch { afterStartLoop(videoId) }
            }
        }
    }

    /** Vincoli di qualità automatica, codec preferito, sottotitoli, traccia video on/off. */
    private fun applyTrackPreferences() {
        val b = player.trackSelectionParameters.buildUpon()
            .setMaxVideoSize(Int.MAX_VALUE, settings.maxVideoHeight)
            .setMinVideoSize(0, settings.minVideoHeight)
            .setPreferredTextLanguage(if (settings.subtitlesAuto) com.mauztube.util.I18n.language.tag else null)
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, _audioOnly.value || inBackground)
        mimeOf(preferredVideoMime)?.let { b.setPreferredVideoMimeType(it) }
        player.trackSelectionParameters = b.build()
    }

    private fun applyVideoTrackState() {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, _audioOnly.value || inBackground)
            .build()
    }

    private suspend fun loadPersonalizedRelated(videoId: String) {
        val (relatedAll, accountRating) = runCatching { recommendations.relatedAndRating(videoId) }
            .onFailure { Log.w(TAG, "Correlati personalizzati non disponibili", it) }
            .getOrNull() ?: return
        if (_nowPlaying.value?.videoId != videoId) return
        // La valutazione dell'account (mi piace / non mi piace) prevale su quella locale.
        if (accountRating != null) {
            val item = details?.toItem() ?: _nowPlaying.value?.preview
            if (item != null) appScope.launch { ratings.applyAccountRating(item, accountRating) }
        }
        val related = relatedAll.takeIf { it.isNotEmpty() } ?: return
        personalizedRelated = related
        if (!incognito) appScope.launch { videoCache.saveRelated(videoId, related) }
        details = details?.copy(related = related)
        _nowPlaying.update { np ->
            val s = np?.state
            if (np != null && s is PlayerUiState.Ready) np.copy(state = s.copy(details = s.details.copy(related = related))) else np
        }
    }

    // ---------------- Cicli durante la riproduzione ----------------

    /** In pausa nessun risveglio periodico: si attende la ripresa della riproduzione. */
    private suspend fun awaitPlaying() { if (!_isPlaying.value) _isPlaying.first { it } }

    private suspend fun periodicSaveLoop() {
        while (currentCoroutineContext().isActive) {
            awaitPlaying()
            delay(settings.saveIntervalSeconds * 1000L)
            if (player.isPlaying) savePosition()
        }
    }

    private suspend fun sponsorLoop(videoId: String, d: VideoDetails) {
        if (!settings.sponsorBlockEnabled || d.isLive || settings.sponsorCategories.isEmpty()) return
        val segments = sponsorRepo.segmentsFor(videoId, settings.sponsorServer, settings.sponsorCategories)
        skipper.setSegments(segments)
        _nowPlaying.update { np ->
            val s = np?.state
            if (np != null && s is PlayerUiState.Ready) np.copy(state = s.copy(segments = segments)) else np
        }
        if (segments.isEmpty()) return
        while (currentCoroutineContext().isActive) {
            awaitPlaying()
            delay(SponsorSkipper.CHECK_INTERVAL_MS)
            if (!player.isPlaying) continue
            val event = skipper.check() ?: continue
            if (settings.sponsorNotify) _skipEvents.tryEmit(event)
        }
    }

    /** Prefetch dei primi correlati dopo 5 s; cronologia account dopo 10 s di visione. */
    private suspend fun afterStartLoop(videoId: String) {
        var prefetched = false
        var playedMs = 0L
        while (currentCoroutineContext().isActive) {
            awaitPlaying()
            delay(1_000)
            if (!player.isPlaying) continue
            playedMs += 1_000
            if (!prefetched && playedMs >= 5_000) {
                prefetched = true
                repo.prefetch((details?.related ?: emptyList()).map { it.id }, max = 2)
            }
            if (playedMs >= 10_000 && watchSession == null &&
                settings.reportWatchHistory && account.loggedIn.value
            ) {
                val session = recommendations.WatchSession(videoId)
                watchSession = session
                val pos = player.currentPosition / 1000.0
                appScope.launch { session.reportStart(pos) }
            }
        }
    }

    // ---------------- Background / foreground ----------------

    private fun onAppBackground() {
        inBackground = true
        if (_nowPlaying.value == null) return
        savePosition()
        reportWatchProgress()
        main.launch {
            val s = settingsRepo.current()
            if (s.backgroundPlayback) applyVideoTrackState() // continua solo audio
            else player.pause()
        }
    }

    private fun onAppForeground() {
        inBackground = false
        if (_nowPlaying.value != null) applyVideoTrackState()
    }

    /**
     * Collega un MediaController al [PlaybackService]: il servizio resta vivo e diventa
     * foreground (notifica con i controlli) quando la riproduzione continua fuori dall'app.
     */
    private fun connectSessionService() {
        if (controllerFuture != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(context, token).buildAsync()
    }

    // ---------------- Salvataggi ----------------

    /** In modalità anonimo non si registra nulla (posizioni, cronologia, cache dei consigli). */
    private val incognito: Boolean get() = account.incognito.value

    fun savePosition(finished: Boolean = false) {
        if (incognito) return
        val d = details ?: return
        if (d.isLive) return
        val position = player.currentPosition
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: (d.durationSeconds * 1000)
        appScope.launch(saveDispatcher) {
            withContext(NonCancellable) {
                playback.save(
                    videoId = d.id,
                    positionMs = position,
                    durationMs = duration,
                    title = d.title,
                    uploaderName = d.uploaderName,
                    thumbnailUrl = d.thumbnailUrl,
                    uploaderUrl = d.uploaderUrl,
                    finished = finished,
                )
            }
        }
    }

    private fun reportWatchProgress() {
        val session = watchSession ?: return
        val pos = player.currentPosition / 1000.0
        appScope.launch { session.reportProgress(pos) }
    }

    private fun setState(state: PlayerUiState) {
        _nowPlaying.update { it?.copy(state = state) }
    }

    private companion object { const val TAG = "PlaybackManager" }

    /** Il video corrente come elemento di lista (per valutazioni, playlist, ecc.). */
    fun currentItem(): VideoItem? = details?.toItem() ?: _nowPlaying.value?.preview

    private fun VideoDetails.toItem() = VideoItem(
        id = id, url = url, title = title, uploaderName = uploaderName, uploaderUrl = uploaderUrl,
        thumbnailUrl = thumbnailUrl, durationSeconds = durationSeconds, viewCount = viewCount,
        textualUploadDate = textualUploadDate, uploadedAtMillis = null, isLive = isLive,
        uploaderAvatarUrl = uploaderAvatarUrl,
    )
}
