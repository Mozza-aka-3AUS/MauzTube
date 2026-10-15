package com.mauztube.data.repository


import android.util.Log
import com.mauztube.data.innertube.InnerTubeClient
import com.mauztube.data.innertube.InnerTubeParser
import com.mauztube.data.model.Paged
import com.mauztube.data.model.VideoItem
import com.mauztube.data.prefs.SettingsRepository
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** Contenuti personalizzati dall'account Google: home consigliata, correlati, cronologia. */
@Singleton
class RecommendationRepository @Inject constructor(
    private val innerTube: InnerTubeClient,
    private val settings: SettingsRepository,
) {
    /** Home "Consigliati" (browseId FEwhat_to_watch) con paginazione via continuation. */
    suspend fun home(): Paged<VideoItem> = page(innerTube.browse(browseId = "FEwhat_to_watch"))

    /** Feed iscrizioni dell'account (browseId FEsubscriptions): una sola richiesta. */
    suspend fun subscriptionsFeed(): Paged<VideoItem> = page(innerTube.browse(browseId = "FEsubscriptions"))

    /** Cronologia visualizzazioni dell'account (browseId FEhistory). */
    suspend fun history(): Paged<VideoItem> = page(innerTube.browse(browseId = "FEhistory"))

    /** Playlist "Guarda più tardi" dell'account. */
    suspend fun watchLater(): List<VideoItem> {
        val all = mutableListOf<VideoItem>()
        var p: Paged<VideoItem>? = page(innerTube.browse(browseId = "VLWL"))
        var guard = 0
        while (p != null && guard++ < 10) {
            all += p.items
            p = p.loadNext?.invoke()
        }
        return all.distinctBy { it.id }
    }

    /** Canali a cui l'account è iscritto (tutte le pagine). */
    suspend fun accountSubscriptions(): List<InnerTubeParser.ChannelRef> {
        val all = LinkedHashMap<String, InnerTubeParser.ChannelRef>()
        var (items, token) = InnerTubeParser.parseChannels(innerTube.browse(browseId = "FEchannels"))
        var guard = 0
        while (true) {
            items.forEach { all.putIfAbsent(it.channelId, it) }
            if (token == null || guard++ >= 30) break
            val next = InnerTubeParser.parseChannels(innerTube.browse(continuation = token))
            if (next.first.isEmpty()) break
            items = next.first
            token = next.second
        }
        return all.values.toList()
    }

    suspend fun subscribe(channelId: String) = innerTube.subscribe(channelId)
    suspend fun unsubscribe(channelId: String) = innerTube.unsubscribe(channelId)

    /** Playlist create/salvate nell'account. */
    suspend fun accountPlaylists(): List<InnerTubeParser.PlaylistRef> {
        val primary = InnerTubeParser.parsePlaylists(innerTube.browse(browseId = "FEplaylist_aggregation"))
        if (primary.isNotEmpty()) return primary
        return InnerTubeParser.parsePlaylists(innerTube.browse(browseId = "FElibrary"))
    }

    /** Video di una playlist dell'account. */
    suspend fun accountPlaylistVideos(playlistId: String): Paged<VideoItem> =
        page(innerTube.browse(browseId = "VL$playlistId"))

    suspend fun addToAccountPlaylist(playlistId: String, videoId: String) =
        innerTube.editPlaylist(playlistId, addVideoId = videoId)

    suspend fun addToWatchLater(videoId: String) = innerTube.editPlaylist("WL", addVideoId = videoId)
    suspend fun removeFromWatchLater(videoId: String) = innerTube.editPlaylist("WL", removeVideoId = videoId)

    private suspend fun page(response: JsonObject): Paged<VideoItem> {
        val parsed = InnerTubeParser.parse(response, settings.current().aggressiveShortsFilter)
        val token = parsed.continuation
        return Paged(parsed.videos, token?.let { t -> { page(innerTube.browse(continuation = t)) } })
    }

    /**
     * Correlati personalizzati (colonna destra di youtube.com) + valutazione dell'account sul
     * video (+1 mi piace, -1 non mi piace, 0 nessuna, null se non determinabile). Una sola
     * richiesta "next" fornisce entrambi.
     */
    suspend fun relatedAndRating(videoId: String): Pair<List<VideoItem>, Int?> {
        val response = innerTube.next(videoId)
        val related = InnerTubeParser.parse(response, settings.current().aggressiveShortsFilter, excludeId = videoId).videos
        return related to InnerTubeParser.likeStatus(response)
    }

    suspend fun related(videoId: String): List<VideoItem> = relatedAndRating(videoId).first

    suspend fun rate(videoId: String, rating: Int) = innerTube.rate(
        videoId,
        when (rating) { 1 -> "like/like"; -1 -> "like/dislike"; else -> "like/removelike" },
    )

    /** Playlist "Video piaciuti" dell'account (LL). */
    suspend fun likedVideos(): List<VideoItem> {
        val all = mutableListOf<VideoItem>()
        var p: Paged<VideoItem>? = page(innerTube.browse(browseId = "VLLL"))
        var guard = 0
        while (p != null && guard++ < 5) {
            all += p.items
            p = p.loadNext?.invoke()
        }
        return all.distinctBy { it.id }
    }

    /**
     * Segna il video come visto nella cronologia YouTube dell'account, così i consigliati
     * tengono conto di ciò che guardi in MauzTube. Stesso meccanismo del sito: ping agli URL
     * `playbackTracking` della risposta /player (tecnica usata anche da yt-dlp --mark-watched).
     */
    inner class WatchSession(private val videoId: String) {
        private val cpn = (1..16).map { CPN_ALPHABET.random() }.joinToString("")
        private var playbackUrl: String? = null
        private var watchtimeUrl: String? = null
        private var started = false

        suspend fun reportStart(positionSec: Double) {
            if (started) return
            started = true
            runCatching {
                val tracking = innerTube.player(videoId)["playbackTracking"]?.jsonObject ?: return
                playbackUrl = tracking.baseUrl("videostatsPlaybackUrl")
                watchtimeUrl = tracking.baseUrl("videostatsWatchtimeUrl")
                playbackUrl?.let { innerTube.authenticatedGet(withParams(it, positionSec, final = false)) }
            }.onFailure { Log.w(TAG, "Impossibile registrare la visualizzazione", it) }
        }

        suspend fun reportProgress(positionSec: Double) {
            val url = watchtimeUrl ?: return
            runCatching { innerTube.authenticatedGet(withParams(url, positionSec, final = true)) }
                .onFailure { Log.w(TAG, "Ping watchtime fallito", it) }
        }

        private fun withParams(base: String, positionSec: Double, final: Boolean): String {
            val pos = "%.3f".format(java.util.Locale.ROOT, positionSec)
            val b = base.toHttpUrlOrNull()?.newBuilder() ?: return base
            b.setQueryParameter("ver", "2")
                .setQueryParameter("cpn", cpn)
                .setQueryParameter("cmt", pos)
                .setQueryParameter("el", "detailpage")
            if (final) b.setQueryParameter("st", "0").setQueryParameter("et", pos)
            return b.build().toString()
        }

        private fun JsonObject.baseUrl(key: String) =
            (this[key] as? JsonObject)?.get("baseUrl")?.let { (it as? JsonPrimitive)?.contentOrNull }
    }

    private companion object {
        const val TAG = "RecommendationRepo"
        const val CPN_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    }
}
