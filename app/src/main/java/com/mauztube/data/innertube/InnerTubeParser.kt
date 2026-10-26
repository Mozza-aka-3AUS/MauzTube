package com.mauztube.data.innertube


import com.mauztube.data.model.ShortsFilter
import com.mauztube.data.model.VideoItem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

data class FeedPage(val videos: List<VideoItem>, val continuation: String?)

/**
 * Estrae i video da una risposta InnerTube (home, correlati, continuazioni) visitando
 * l'albero JSON. È robusto ai frequenti cambi di struttura perché non dipende dal percorso
 * esatto ma dai "renderer" che descrivono un video:
 *  - `lockupViewModel` (formato attuale, contentType LOCKUP_CONTENT_TYPE_VIDEO)
 *  - `videoRenderer` / `compactVideoRenderer` / `gridVideoRenderer` (formato precedente)
 *
 * BLOCCO ADS + SHORTS: i sotto-alberi di annunci in-feed e di Shorts vengono saltati
 * interamente ([SKIPPED_KEYS]); ogni video passa poi comunque da [ShortsFilter].
 */
object InnerTubeParser {

    private val SKIPPED_KEYS = setOf(
        // annunci nel feed
        "adSlotRenderer", "promotedSparklesWebRenderer", "promotedVideoRenderer",
        "inFeedAdLayoutRenderer", "bannerPromoRenderer", "statementBannerRenderer",
        "brandVideoShelfRenderer", "brandVideoSingletonRenderer", "movieOfferModuleRenderer",
        "searchPyvRenderer", "adBreakServiceRenderer", "playerAds", "adPlacements",
        // Shorts
        "reelShelfRenderer", "reelItemRenderer", "shortsLockupViewModel",
        // duplicati nella pagina video (end-screen, pannelli)
        "playerOverlays", "engagementPanels", "frameworkUpdates",
    )

    private val DURATION = Regex("""^\d{1,2}(:\d{2}){1,2}$""")

    fun parse(root: JsonElement, aggressiveShorts: Boolean, excludeId: String? = null): FeedPage {
        val out = LinkedHashMap<String, VideoItem>()
        var continuation: String? = null

        fun walk(e: JsonElement) {
            when (e) {
                is JsonObject -> for ((k, v) in e) {
                    if (k in SKIPPED_KEYS) continue
                    val video = when (k) {
                        "lockupViewModel" -> (v as? JsonObject)?.let(::fromLockup)
                        "videoRenderer", "compactVideoRenderer", "gridVideoRenderer", "playlistVideoRenderer" ->
                            (v as? JsonObject)?.let(::fromVideoRenderer)
                        else -> null
                    }
                    if (video != null) {
                        if (video.id != excludeId && !ShortsFilter.isShort(video, aggressiveShorts)) {
                            out.putIfAbsent(video.id, video)
                        }
                        continue
                    }
                    if (k == "continuationCommand" && continuation == null) {
                        continuation = (v as? JsonObject)?.str("token")
                    }
                    walk(v)
                }
                is JsonArray -> e.forEach(::walk)
                else -> Unit
            }
        }
        walk(root)
        return FeedPage(out.values.toList(), continuation)
    }

    data class ChannelRef(val channelId: String, val name: String, val avatarUrl: String?)
    data class PlaylistRef(val playlistId: String, val title: String, val thumbnailUrl: String?, val countText: String?)

    /** Canali (pagina "Iscrizioni → Gestisci", browseId FEchannels). */
    fun parseChannels(root: JsonElement): Pair<List<ChannelRef>, String?> {
        val out = LinkedHashMap<String, ChannelRef>()
        root.findAll("channelRenderer").plus(root.findAll("gridChannelRenderer")).forEach { c ->
            val id = c.str("channelId") ?: return@forEach
            val name = c.obj("title")?.text() ?: return@forEach
            out.putIfAbsent(id, ChannelRef(id, name, bestThumb(c.obj("thumbnail")?.arr("thumbnails"))))
        }
        root.findAll("lockupViewModel").filter { it.str("contentType")?.contains("CHANNEL") == true }.forEach { l ->
            val id = l.str("contentId") ?: return@forEach
            val name = l.obj("metadata")?.obj("lockupMetadataViewModel")?.obj("title")?.str("content") ?: return@forEach
            out.putIfAbsent(id, ChannelRef(id, name, bestThumb(l.findAll("image").firstOrNull()?.arr("sources"))))
        }
        return out.values.toList() to continuationOf(root)
    }

    /** Playlist dell'account (browseId FEplaylist_aggregation / FElibrary). */
    fun parsePlaylists(root: JsonElement): List<PlaylistRef> {
        val out = LinkedHashMap<String, PlaylistRef>()
        root.findAll("lockupViewModel").filter { it.str("contentType") == "LOCKUP_CONTENT_TYPE_PLAYLIST" }.forEach { l ->
            val id = l.str("contentId") ?: return@forEach
            val meta = l.obj("metadata")?.obj("lockupMetadataViewModel")
            val title = meta?.obj("title")?.str("content") ?: return@forEach
            val count = l.obj("contentImage").findAll("thumbnailBadgeViewModel").firstNotNullOfOrNull { it.str("text") }
            out.putIfAbsent(id, PlaylistRef(id, title, bestThumb(l.obj("contentImage")?.findAll("image")?.firstOrNull()?.arr("sources")), count))
        }
        root.findAll("gridPlaylistRenderer").forEach { g ->
            val id = g.str("playlistId") ?: return@forEach
            val title = g.obj("title")?.text() ?: return@forEach
            out.putIfAbsent(id, PlaylistRef(id, title, bestThumb(g.obj("thumbnail")?.arr("thumbnails")), g.obj("videoCountShortText")?.text()))
        }
        return out.values.filter { it.playlistId != "WL" && it.playlistId != "LL" }.toList()
    }

    data class PlaylistOption(val playlistId: String, val title: String, val containsVideo: Boolean)

    /** Risposta di playlist/get_add_to_playlist: playlist dell'account e presenza del video. */
    fun parseAddToPlaylist(root: JsonElement): List<PlaylistOption> =
        root.findAll("playlistAddToOptionRenderer").mapNotNull { o ->
            val id = o.str("playlistId") ?: return@mapNotNull null
            val title = o.obj("title")?.text() ?: return@mapNotNull null
            PlaylistOption(id, title, o.str("containsSelectedVideos") == "ALL")
        }.filter { it.playlistId != "WL" && it.playlistId != "LL" }.distinctBy { it.playlistId }

    /** Stato "mi piace" dell'account nella risposta /next (likeStatusEntity / likeStatus). */
    fun likeStatus(root: JsonElement): Int? {
        var found: String? = null
        fun walk(e: JsonElement) {
            if (found != null) return
            when (e) {
                is JsonObject -> for ((k, v) in e) {
                    if (k == "likeStatus" && v is JsonPrimitive) { found = v.contentOrNull; return }
                    walk(v)
                }
                is JsonArray -> e.forEach(::walk)
                else -> Unit
            }
        }
        walk(root)
        return when (found) { "LIKE" -> 1; "DISLIKE" -> -1; "INDIFFERENT" -> 0; else -> null }
    }

    private fun continuationOf(root: JsonElement): String? =
        root.findAll("continuationCommand").firstNotNullOfOrNull { it.str("token") }

    // ---------------- lockupViewModel ----------------

    private fun fromLockup(o: JsonObject): VideoItem? {
        if (o.str("contentType") != "LOCKUP_CONTENT_TYPE_VIDEO") return null
        if (o.containsDeep("reelWatchEndpoint")) return null
        val id = o.str("contentId") ?: return null
        val meta = o.obj("metadata")?.obj("lockupMetadataViewModel")
        val rows = meta?.obj("metadata")?.obj("contentMetadataViewModel")?.arr("metadataRows").orEmpty()
        // Per le visualizzazioni l'etichetta di accessibilità è completa ("11 mila visualizzazioni").
        val rowTexts = rows.map { row ->
            (row as? JsonObject)?.arr("metadataParts").orEmpty().mapNotNull { part ->
                val p = part as? JsonObject
                val text = p?.obj("text")?.str("content")
                if (p?.obj("leadingIcon") != null) p.str("accessibilityLabel") ?: text else text
            }
        }
        val badges = o.obj("contentImage").findAll("thumbnailBadgeViewModel")
        val durationText = badges.firstNotNullOfOrNull { b -> b.str("text")?.takeIf { DURATION.matches(it) } }
        val isLive = badges.any { b ->
            b.str("badgeStyle")?.contains("LIVE") == true || b.str("text")?.equals("LIVE", true) == true
        }
        val channelEndpoint = meta?.obj("image")?.findAll("browseEndpoint")?.firstOrNull()
        val avatar = meta?.obj("image")?.findAll("avatarViewModel")?.firstOrNull()
            ?.obj("image")?.arr("sources")
        val stats = rowTexts.getOrNull(1).orEmpty()
        return VideoItem(
            id = id,
            url = "https://www.youtube.com/watch?v=$id",
            title = meta?.obj("title")?.str("content") ?: return null,
            uploaderName = rowTexts.firstOrNull()?.firstOrNull().orEmpty(),
            uploaderUrl = channelEndpoint?.str("browseId")?.let { "https://www.youtube.com/channel/$it" },
            thumbnailUrl = bestThumb(o.obj("contentImage")?.findAll("image")?.firstOrNull()?.arr("sources")),
            durationSeconds = durationText?.let(::parseDuration) ?: 0,
            viewCount = -1,
            viewText = stats.firstOrNull(),
            textualUploadDate = stats.getOrNull(1),
            uploadedAtMillis = null,
            isLive = isLive,
            uploaderAvatarUrl = bestThumb(avatar),
        )
    }

    // ---------------- videoRenderer & co. ----------------

    private fun fromVideoRenderer(o: JsonObject): VideoItem? {
        val id = o.str("videoId") ?: return null
        if (o.obj("navigationEndpoint")?.containsKey("reelWatchEndpoint") == true) return null
        val owner = (o.obj("ownerText") ?: o.obj("longBylineText") ?: o.obj("shortBylineText"))
        val ownerRun = owner?.arr("runs")?.firstOrNull() as? JsonObject
        val lengthText = o.obj("lengthText")?.text()
        val isLive = o.toString().let { it.contains("BADGE_STYLE_TYPE_LIVE_NOW") || it.contains("\"style\":\"LIVE\"") }
        return VideoItem(
            id = id,
            url = "https://www.youtube.com/watch?v=$id",
            title = o.obj("title")?.text() ?: return null,
            uploaderName = ownerRun?.str("text").orEmpty(),
            uploaderUrl = ownerRun?.findAll("browseEndpoint")?.firstOrNull()?.str("browseId")
                ?.let { "https://www.youtube.com/channel/$it" },
            thumbnailUrl = bestThumb(o.obj("thumbnail")?.arr("thumbnails")),
            durationSeconds = lengthText?.let(::parseDuration) ?: 0,
            viewCount = -1,
            viewText = (o.obj("shortViewCountText") ?: o.obj("viewCountText"))?.text(),
            textualUploadDate = o.obj("publishedTimeText")?.text(),
            uploadedAtMillis = null,
            isLive = isLive,
            uploaderAvatarUrl = bestThumb(
                o.findAll("channelThumbnailWithLinkRenderer").firstOrNull()?.obj("thumbnail")?.arr("thumbnails")
            ),
        )
    }

    // ---------------- helpers ----------------

    private fun bestThumb(sources: JsonArray?): String? {
        val list = sources.orEmpty().mapNotNull { it as? JsonObject }
        val pick = list.filter { (it.int("height") ?: 0) <= 480 }.maxByOrNull { it.int("height") ?: 0 }
            ?: list.lastOrNull()
        return pick?.str("url")?.let { if (it.startsWith("//")) "https:$it" else it }
    }

    fun parseDuration(text: String): Long =
        text.trim().split(':').fold(0L) { acc, p -> acc * 60 + (p.toLongOrNull() ?: return 0) }

    private fun JsonObject.text(): String? =
        str("simpleText") ?: arr("runs")?.joinToString("") { (it as? JsonObject)?.str("text").orEmpty() }
            ?.takeIf { it.isNotEmpty() }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(key: String) = (this[key] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.obj(key: String) = this[key] as? JsonObject
    private fun JsonObject.arr(key: String) = this[key] as? JsonArray

    private fun JsonElement?.findAll(key: String): List<JsonObject> {
        val out = mutableListOf<JsonObject>()
        fun walk(e: JsonElement?) {
            when (e) {
                is JsonObject -> for ((k, v) in e) {
                    if (k == key && v is JsonObject) out += v
                    walk(v)
                }
                is JsonArray -> e.forEach(::walk)
                else -> Unit
            }
        }
        walk(this)
        return out
    }

    private fun JsonElement.containsDeep(key: String): Boolean = when (this) {
        is JsonObject -> containsKey(key) || values.any { it.containsDeep(key) }
        is JsonArray -> any { it.containsDeep(key) }
        else -> false
    }
}
