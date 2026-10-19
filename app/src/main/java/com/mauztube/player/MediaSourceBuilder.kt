package com.mauztube.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.LoadControl
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import java.io.ByteArrayInputStream
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.mauztube.data.extractor.NewPipeDownloader
import com.mauztube.data.model.NoPlayableStreamException
import com.mauztube.data.model.StreamOption
import com.mauztube.data.innertube.AuthenticatedStreamFetcher
import com.mauztube.data.model.VideoDetails
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Costruisce la MediaSource per ExoPlayer a partire dagli URL `googlevideo.com` estratti.
 *
 * PUNTO CRITICO (blocco ads): qui non esiste alcun "ad tag", IMA SDK o VMAP. Gli annunci di
 * YouTube vengono decisi e iniettati dal player ufficiale (risposta `/player` → `adPlacements`,
 * `playerAds`) e NewPipeExtractor li ignora: ExoPlayer riceve solo i file audio/video
 * del contenuto. Non c'è niente da "bloccare" perché gli annunci non vengono mai richiesti.
 */
@OptIn(UnstableApi::class)
@Singleton
class MediaSourceBuilder @Inject constructor(
    @ApplicationContext context: Context,
    @com.mauztube.di.MediaHttp okHttpClient: OkHttpClient,
) {
    /**
     * Gli URL googlevideo restituiscono 403 se lo User-Agent non corrisponde al client
     * InnerTube (ANDROID, IOS, WEB…) con cui sono stati ottenuti: il ResolvingDataSource
     * imposta gli header giusti per ogni richiesta.
     */
    private val dataSourceFactory: DataSource.Factory = ResolvingDataSource.Factory(
        DefaultDataSource.Factory(context, OkHttpDataSource.Factory(okHttpClient)),
    ) { spec -> adaptForYouTube(spec) }

    /** Sorgente pronta + MIME video preferito (per la selezione tracce adattiva). */
    data class Built(val source: MediaSource, val preferredVideoMime: String?)

    fun build(details: VideoDetails, maxHeight: Int): Built {
        val metadata = MediaMetadata.Builder()
            .setTitle(details.title)
            .setArtist(details.uploaderName)
            .setArtworkUri(details.thumbnailUrl?.let(Uri::parse))
            .build()
        fun item(url: String) = MediaItem.Builder()
            .setMediaId(details.id)
            .setUri(url)
            .setMediaMetadata(metadata)
            .build()

        val s = details.streams

        // 1. Live → HLS
        if (details.isLive && s.hlsUrl != null) {
            return Built(HlsMediaSource.Factory(dataSourceFactory).createMediaSource(item(s.hlsUrl)), null)
        }

        var preferredMime: String? = null
        val main: MediaSource = run {
            // 2. Video adattivo (tutte le qualità) + audio separato
            val video = adaptiveVideo(s.videoOnly, maxHeight, ::item)
            val audio = pickAudio(s.audioOnly)
            if (video != null && audio != null) {
                preferredMime = video.second
                return@run MergingMediaSource(
                    /* adjustPeriodTimeOffsets = */ true,
                    /* clipDurations = */ true,
                    video.first,
                    single(audio, ::item),
                )
            }
            // 3. Stream muxed (tipicamente 360p) come ripiego
            pickVideo(s.muxed, maxHeight)?.let { return@run single(it, ::item) }
            // 4. Manifest
            s.hlsUrl?.let { return@run HlsMediaSource.Factory(dataSourceFactory).createMediaSource(item(it)) }
            s.dashMpdUrl?.let { return@run DashMediaSource.Factory(dataSourceFactory).createMediaSource(item(it)) }
            throw NoPlayableStreamException()
        }

        // I sottotitoli NON fanno parte della sorgente: vedi SubtitleLoader.
        return Built(main, preferredMime)
    }

    /**
     * QUALITÀ: tutte le risoluzioni disponibili finiscono in un unico manifest DASH, con un
     * AdaptationSet per famiglia di codec (H.264/MP4 e VP9/WebM). ExoPlayer:
     *  - in automatico sceglie e cambia la qualità in base alla banda, entro i limiti
     *    minima/massima impostati come vincoli di selezione (PlaybackManager);
     *  - dal menu Qualità del player può essere forzato su QUALSIASI risoluzione.
     * La famiglia preferita è quella della migliore qualità ≤ massima (H.264 a parità di
     * risoluzione, per la decodifica hardware anche su dispositivi vecchi). AV1 è escluso.
     * @return sorgente + MIME della famiglia preferita.
     */
    private fun adaptiveVideo(
        options: List<StreamOption>,
        maxHeight: Int,
        item: (String) -> MediaItem,
    ): Pair<MediaSource, String?>? {
        val best = pickVideo(options, maxHeight) ?: return null
        val usable = options.filter { it.dashManifest != null && codecRank(it.codec, it.mimeType) < 3 && it.height > 0 }
        if (usable.isEmpty() || best.dashManifest == null) return single(best, item) to best.mimeType

        val families = usable.groupBy { it.mimeType to codecRank(it.codec, it.mimeType) }
        val preferredKey = best.mimeType to codecRank(best.codec, best.mimeType)
        val ordered = families.entries.sortedBy { if (it.key == preferredKey) 0 else 1 }

        val sets = ordered.mapIndexedNotNull { index, (_, reps) ->
            val sorted = reps.sortedWith(compareByDescending<StreamOption> { it.height }.thenByDescending { it.fps })
                .distinctBy { it.height }
            val first = sorted.first().dashManifest!!
            val set = ADAPTATION_SET.find(first)?.value ?: return@mapIndexedNotNull null
            val extra = sorted.drop(1).mapNotNull { REPRESENTATION.find(it.dashManifest!!)?.value }
            set.replaceFirst(Regex("""id="\d+""""), "id=\"$index\"")
                .replace("</AdaptationSet>", extra.joinToString("") + "</AdaptationSet>")
        }
        if (sets.isEmpty()) return single(best, item) to best.mimeType

        val header = best.dashManifest.substringBefore("<Period>")
        val manifest = header + "<Period>" + sets.joinToString("") + "</Period></MPD>"
        val source = runCatching {
            val parsed = DashManifestParser().parse(Uri.parse(best.url), ByteArrayInputStream(manifest.toByteArray()))
            DashMediaSource.Factory(dataSourceFactory).createMediaSource(parsed, item(best.url))
        }.getOrElse {
            Log.w(TAG, "Manifest adattivo non valido, uso la qualità singola", it)
            single(best, item)
        }
        return source to best.mimeType
    }

    /**
     * Preferisce il manifest DASH sintetico (download a blocchi con range limitati,
     * avvio e seek veloci) e ripiega sul progressivo se non è stato possibile generarlo.
     */
    private fun single(option: StreamOption, item: (String) -> MediaItem): MediaSource {
        val manifest = option.dashManifest
        if (manifest != null) {
            runCatching {
                val parsed = DashManifestParser().parse(
                    Uri.parse(option.url), ByteArrayInputStream(manifest.toByteArray()),
                )
                return DashMediaSource.Factory(dataSourceFactory).createMediaSource(parsed, item(option.url))
            }.onFailure { Log.w(TAG, "Manifest DASH non valido, uso il progressivo", it) }
        }
        return ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(item(option.url))
    }

    /**
     * Buffer: avvio rapido (1,5 s di dati invece dei 2,5 s di default) ma riserva ampia
     * (30–50 s, come il default di ExoPlayer) per assorbire i cali di rete senza fermarsi.
     * Dopo un eventuale blocco si riparte con 2,5 s, così non si ferma di nuovo subito dopo.
     */
    fun loadControl(): LoadControl {
        val low = com.mauztube.util.DeviceProfile.lowMemory
        return DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ if (low) 15_000 else 30_000,
                /* maxBufferMs = */ if (low) 30_000 else 50_000,
                /* bufferForPlaybackMs = */ 1_500,
                /* bufferForPlaybackAfterRebufferMs = */ 2_500,
            )
            .apply {
                // Telefoni con poca memoria: tetto di 24 MB al buffer (il default arriva a 130 MB).
                if (low) setTargetBufferBytes(24 * 1024 * 1024).setPrioritizeTimeOverSizeThresholds(true)
            }
            .build()
    }

    /**
     * Qualità adattiva prudente: sale di qualità solo con margine di banda (65% della stima),
     * riducendo i blocchi su reti mobili che oscillano.
     */
    fun trackSelector(context: Context): androidx.media3.exoplayer.trackselection.DefaultTrackSelector =
        androidx.media3.exoplayer.trackselection.DefaultTrackSelector(
            context,
            androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection.Factory(
                /* minDurationForQualityIncreaseMs = */ 10_000,
                /* maxDurationForQualityDecreaseMs = */ 25_000,
                /* minDurationToRetainAfterDiscardMs = */ 25_000,
                /* bandwidthFraction = */ 0.65f,
            ),
        )

    /** Altezza massima ≤ maxHeight; a parità preferisce H.264 > VP9 > AV1 (compatibilità HW). */
    private fun pickVideo(options: List<StreamOption>, maxHeight: Int): StreamOption? {
        if (options.isEmpty()) return null
        val allowed = options.filter { it.height in 1..maxHeight }.ifEmpty { listOf(options.minBy { it.height }) }
        return allowed.sortedWith(
            compareByDescending<StreamOption> { it.height }
                .thenBy { codecRank(it.codec, it.mimeType) }
                .thenByDescending { it.fps }
                .thenByDescending { it.bitrate }
        ).first()
    }

    /** Traccia audio originale (non doppiata), AAC preferito a Opus (decoder HW su API 24+). */
    private fun pickAudio(options: List<StreamOption>): StreamOption? =
        options.sortedWith(
            compareByDescending<StreamOption> { it.isOriginalAudio }
                .thenBy { if (it.mimeType?.contains("mp4") == true || it.codec?.startsWith("mp4a") == true) 0 else 1 }
                .thenByDescending { it.bitrate }
        ).firstOrNull()

    private fun codecRank(codec: String?, mime: String?): Int = when {
        codec?.startsWith("avc") == true -> 0
        codec?.startsWith("vp9") == true || codec?.startsWith("vp09") == true -> 1
        codec?.startsWith("av01") == true -> 3
        mime?.contains("mp4") == true -> 0
        else -> 2
    }

    private companion object {
        const val TAG = "MediaSourceBuilder"
        val REPRESENTATION = Regex("<Representation[\\s\\S]*?</Representation>")
        val ADAPTATION_SET = Regex("<AdaptationSet[\\s\\S]*?</AdaptationSet>")
    }

    private fun adaptForYouTube(spec: DataSpec): DataSpec {
        val req = YouTubeStreamRequest.forUrl(spec.uri.toString())
        var builder = spec.buildUpon()
        if (req.postBody != null) {
            builder = builder.setHttpMethod(DataSpec.HTTP_METHOD_POST).setHttpBody(req.postBody)
        }
        return builder.build().withAdditionalHeaders(req.headers)
    }

}
