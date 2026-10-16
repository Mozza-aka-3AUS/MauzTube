package com.mauztube.data.repository

import com.mauztube.util.tr

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import com.mauztube.data.model.ChannelDetails
import com.mauztube.data.model.ContentItem
import com.mauztube.data.model.ContentMapper
import com.mauztube.data.model.ContentMapper.bestUrl
import com.mauztube.data.model.Paged
import com.mauztube.data.model.PlayableStreams
import com.mauztube.data.model.ShortsBlockedException
import com.mauztube.data.model.ShortsFilter
import com.mauztube.data.model.SubtitleOption
import com.mauztube.data.model.VideoDetails
import com.mauztube.data.model.VideoItem
import com.mauztube.data.prefs.SettingsRepository
import com.mauztube.di.ApplicationScope
import com.mauztube.data.account.AccountManager
import com.mauztube.data.innertube.AgeRestrictedException
import com.mauztube.data.innertube.AuthenticatedStreamFetcher
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ListExtractor.InfoItemsPage
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.Stream
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Accesso ai contenuti tramite NewPipeExtractor. Ogni lista passa da [ContentMapper],
 * che elimina gli Shorts PRIMA che i dati escano dal repository.
 *
 * VELOCITÀ: l'estrazione di un video richiede ~8 richieste HTTP in sequenza (≈2 s a caldo,
 * ≈5 s al primo video dopo l'avvio, misurati). Per avvicinarsi al client ufficiale:
 *  - [warmUp] all'avvio apre le connessioni TLS e ricava la versione del client WEB;
 *  - i dettagli sono in cache (30 min, gli URL degli stream durano ~6 h) e le richieste
 *    concorrenti per lo stesso video vengono unite;
 *  - [prefetch] estrae in anticipo i video che l'utente probabilmente aprirà (primi della
 *    home, primi correlati), come fa l'app ufficiale; solo su rete non a consumo.
 */
@Singleton
class YouTubeRepository @Inject constructor(
    private val settings: SettingsRepository,
    private val http: OkHttpClient,
    private val account: AccountManager,
    private val authenticated: AuthenticatedStreamFetcher,
    @ApplicationContext private val context: Context,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    private val service: StreamingService get() = ServiceList.YouTube

    private suspend fun aggressive() = settings.current().aggressiveShortsFilter

    // ---------------- Avvio rapido ----------------

    private var lastWarmUp = 0L

    fun warmUp() {
        val now = System.currentTimeMillis()
        if (now - lastWarmUp < 60_000) return
        lastWarmUp = now
        appScope.launch(Dispatchers.IO) {
            listOf("https://www.youtube.com/generate_204", "https://youtubei.googleapis.com/generate_204")
                .forEach { url ->
                    runCatching { http.newCall(Request.Builder().url(url).head().build()).execute().close() }
                }
            runCatching { YoutubeParsingHelper.getClientVersion() }
                .onFailure { Log.w(TAG, "Warm-up client version fallito", it) }
        }
    }

    // ---------------- Ricerca ----------------

    suspend fun search(query: String): Paged<ContentItem> = io {
        val handler = service.searchQHFactory.fromQuery(
            query, listOf(YoutubeSearchQueryHandlerFactory.ALL), "",
        )
        val info = SearchInfo.getInfo(service, handler)
        Paged(ContentMapper.map(info.relatedItems, aggressive()), nextLoader(info.nextPage) { page ->
            SearchInfo.getMoreItems(service, handler, page)
        })
    }

    // ---------------- Playlist pubbliche ----------------

    /** Playlist pubblica (link condivisi): nome e video, con paginazione. */
    suspend fun playlist(playlistId: String): Pair<String, Paged<VideoItem>> = io {
        val url = "https://www.youtube.com/playlist?list=$playlistId"
        val info = org.schabi.newpipe.extractor.playlist.PlaylistInfo.getInfo(service, url)
        val aggressive = aggressive()
        info.name to Paged(
            ContentMapper.mapVideos(info.relatedItems, aggressive),
            nextLoader(info.nextPage, map = { ContentMapper.mapVideos(it, aggressive) }) { page ->
                org.schabi.newpipe.extractor.playlist.PlaylistInfo.getMoreItems(service, url, page)
            },
        )
    }

    // ---------------- Canali ----------------

    suspend fun channel(url: String): ChannelDetails = io {
        val info = ChannelInfo.getInfo(service, url)
        // Si usa esclusivamente la tab "Video": la tab "Shorts" non viene mai caricata.
        val videosTab = info.tabs.firstOrNull { ChannelTabs.VIDEOS in it.contentFilters }
        ChannelDetails(
            url = info.url,
            name = info.name,
            avatarUrl = info.avatars.bestUrl(),
            bannerUrl = info.banners.bestUrl(),
            subscriberCount = info.subscriberCount,
            description = info.description,
            videos = videosTab?.let { channelTab(it) } ?: Paged(emptyList(), null),
        )
    }

    private suspend fun channelTab(tab: ListLinkHandler): Paged<VideoItem> {
        val info = ChannelTabInfo.getInfo(service, tab)
        val aggressive = aggressive()
        return Paged(ContentMapper.mapVideos(info.relatedItems, aggressive),
            nextLoader(info.nextPage, map = { ContentMapper.mapVideos(it, aggressive) }) { page ->
                ChannelTabInfo.getMoreItems(service, tab, page)
            })
    }

    /** Ultimi video di più canali, per il feed iscrizioni (massimo 4 richieste in parallelo). */
    suspend fun latestVideos(channelUrls: List<String>, perChannel: Int = 15): List<VideoItem> = io {
        val gate = Semaphore(4)
        coroutineScope {
            channelUrls.map { url ->
                async {
                    gate.withPermit {
                        runCatching { channel(url).videos.items.take(perChannel) }.getOrDefault(emptyList())
                    }
                }
            }.awaitAll().flatten()
        }.distinctBy { it.id }
            .sortedByDescending { it.uploadedAtMillis ?: 0L }
    }

    // ---------------- Video (con cache e prefetch) ----------------

    private class Entry(val result: Deferred<VideoDetails>, val createdAt: Long)

    private val cache = object : LinkedHashMap<String, Entry>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) = size > CACHE_SIZE
    }
    private val prefetchGate = Semaphore(2)

    /**
     * @param forceRefresh ignora la cache (es. dopo un errore 403: URL scaduti).
     */
    suspend fun videoDetails(videoId: String, forceRefresh: Boolean = false): VideoDetails =
        entryFor(videoId, forceRefresh, prefetch = false).await()

    /** Estrae in background i primi [max] video della lista, se la rete non è a consumo. */
    fun prefetch(videoIds: List<String>, max: Int = 3, evenIfMetered: Boolean = false) {
        if (!evenIfMetered && isMetered()) return
        videoIds.take(max).forEach { entryFor(it, forceRefresh = false, prefetch = true) }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun entryFor(videoId: String, forceRefresh: Boolean, prefetch: Boolean): Deferred<VideoDetails> =
        synchronized(cache) {
            val now = System.currentTimeMillis()
            val current = cache[videoId]
            val usable = current != null && !forceRefresh &&
                now - current.createdAt < CACHE_TTL_MS &&
                !(current.result.isCompleted && current.result.getCompletionExceptionOrNull() != null)
            if (usable) return current.result

            val job = appScope.async(Dispatchers.IO) {
                if (prefetch) prefetchGate.withPermit { extract(videoId) } else extract(videoId)
            }
            cache[videoId] = Entry(job, now)
            job
        }

    fun isMetered(): Boolean = runCatching {
        context.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered
    }.getOrDefault(true)

    private suspend fun extract(videoId: String): VideoDetails {
        val info = try {
            StreamInfo.getInfo(service, "https://www.youtube.com/watch?v=$videoId")
        } catch (e: AgeRestrictedContentException) {
            // 18+: NewPipe non usa account. Con l'account collegato si chiedono gli stream a
            // YouTube autenticandosi (età verificata dall'account).
            if (!account.loggedIn.value) {
                throw AgeRestrictedException(tr("Age-restricted video: sign in with an adult Google account.", "Video con limiti di età: accedi con un account Google maggiorenne."))
            }
            return authenticated.fetch(videoId)
        } catch (e: SignInConfirmNotBotException) {
            if (!account.loggedIn.value) throw e
            return authenticated.fetch(videoId)
        }
        if (info.isShortFormContent || ShortsFilter.isShortsUrl(info.originalUrl)) {
            throw ShortsBlockedException()
        }
        val progressive = { s: Stream -> ContentMapper.isDirectProgressive(s.isUrl, s.deliveryMethod) }
        val duration = info.duration
        return VideoDetails(
            id = videoId,
            url = info.url,
            title = info.name,
            description = info.description?.content.orEmpty(),
            descriptionIsHtml = info.description?.type == org.schabi.newpipe.extractor.stream.Description.HTML,
            uploaderName = info.uploaderName.orEmpty(),
            uploaderUrl = info.uploaderUrl,
            uploaderAvatarUrl = info.uploaderAvatars.bestUrl(),
            uploaderSubscriberCount = info.uploaderSubscriberCount,
            thumbnailUrl = info.thumbnails.bestUrl(720),
            durationSeconds = duration,
            viewCount = info.viewCount,
            likeCount = info.likeCount,
            textualUploadDate = info.textualUploadDate,
            uploadedAtMillis = runCatching { info.uploadDate?.offsetDateTime()?.toInstant()?.toEpochMilli() }.getOrNull(),
            isLive = info.streamType == StreamType.LIVE_STREAM ||
                info.streamType == StreamType.AUDIO_LIVE_STREAM,
            streams = PlayableStreams(
                videoOnly = info.videoOnlyStreams.filter(progressive).map { ContentMapper.videoOption(it, duration) },
                audioOnly = info.audioStreams.filter(progressive).map { ContentMapper.audioOption(it, duration) },
                muxed = info.videoStreams.filter(progressive).map { ContentMapper.videoOption(it, duration) },
                hlsUrl = info.hlsUrl?.takeIf { it.isNotBlank() },
                dashMpdUrl = info.dashMpdUrl?.takeIf { it.isNotBlank() },
            ),
            subtitles = selectSubtitles(info.subtitles.map(ContentMapper::subtitleOption)),
            related = ContentMapper.mapVideos(info.relatedItems, aggressive()),
            storyboard = pickStoryboard(info.previewFrames),
        )
    }

    /** Anteprime della barra: il set più nitido entro 320 px di larghezza (sprite leggeri). */
    private fun pickStoryboard(sets: List<org.schabi.newpipe.extractor.stream.Frameset>?): com.mauztube.data.model.Storyboard? {
        val best = sets.orEmpty()
            .filter { it.urls.isNotEmpty() && it.frameWidth in 1..320 && it.durationPerFrame > 0 }
            .maxByOrNull { it.frameWidth } ?: return null
        return com.mauztube.data.model.Storyboard(
            urls = best.urls,
            frameWidth = best.frameWidth,
            frameHeight = best.frameHeight,
            totalCount = best.totalCount,
            durationPerFrameMs = best.durationPerFrame,
            cols = best.framesPerPageX,
            rows = best.framesPerPageY,
        )
    }

    /**
     * Ogni traccia sottotitoli viene scaricata quando il player si prepara: se ne tengono
     * al massimo [MAX_SUBTITLES] per non rallentare l'avvio. Priorità: lingua del dispositivo,
     * inglese, poi le automatiche nella lingua originale del video.
     */
    private fun selectSubtitles(all: List<SubtitleOption>): List<SubtitleOption> {
        val device = com.mauztube.util.I18n.language.tag
        fun lang(s: SubtitleOption) = s.languageTag.substringBefore('-').lowercase()
        val ranked = all.sortedWith(
            compareBy<SubtitleOption> {
                when {
                    lang(it) == device && !it.autoGenerated -> 0
                    lang(it) == device -> 1
                    lang(it) == "en" && !it.autoGenerated -> 2
                    it.autoGenerated -> 3
                    else -> 4
                }
            }
        )
        return ranked.distinctBy { it.languageTag to it.autoGenerated }.take(MAX_SUBTITLES)
    }

    // ---------------- Helpers ----------------

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    private suspend fun <I : InfoItem> nextLoader(
        page: Page?,
        fetch: (Page) -> InfoItemsPage<I>,
    ): (suspend () -> Paged<ContentItem>)? {
        val aggressive = aggressive()
        return nextLoader(page, { ContentMapper.map(it, aggressive) }, fetch)
    }

    private fun <I : InfoItem, T> nextLoader(
        page: Page?,
        map: (List<InfoItem>) -> List<T>,
        fetch: (Page) -> InfoItemsPage<I>,
    ): (suspend () -> Paged<T>)? {
        if (!Page.isValid(page)) return null
        return {
            withContext(Dispatchers.IO) {
                val result = fetch(page!!)
                Paged(map(result.items), nextLoader(result.nextPage, map, fetch))
            }
        }
    }

    private companion object {
        const val TAG = "YouTubeRepository"
        const val CACHE_SIZE = 30
        const val CACHE_TTL_MS = 30 * 60 * 1000L
        const val MAX_SUBTITLES = 4
    }
}
