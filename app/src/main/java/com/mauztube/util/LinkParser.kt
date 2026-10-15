package com.mauztube.util

import android.content.Intent
import android.net.Uri
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeChannelLinkHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubePlaylistLinkHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory

sealed interface DeepLink {
    /** @param startSeconds punto di partenza dal parametro t= del link (null se assente). */
    data class Video(val videoId: String, val startSeconds: Long? = null) : DeepLink
    data class Channel(val url: String) : DeepLink
    data class Playlist(val playlistId: String) : DeepLink
    data class Search(val query: String) : DeepLink
}

/**
 * Interpreta i link YouTube arrivati via ACTION_VIEW (clic su un link) o "Condividi":
 * video (watch, youtu.be, shorts, live, embed, music, nocookie, vnd.youtube:), con eventuale
 * minutaggio t=, canali (@nome, /channel/, /c/, /user/), playlist e ricerche.
 */
object LinkParser {

    fun fromIntent(intent: Intent?): DeepLink? {
        intent ?: return null
        val raw = when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        } ?: return null
        // vnd.youtube:VIDEO_ID oppure vnd.youtube://VIDEO_ID
        if (raw.startsWith("vnd.youtube:")) {
            val id = raw.removePrefix("vnd.youtube:").trimStart('/').substringBefore('?').substringBefore('&')
            if (VIDEO_ID.matches(id)) return DeepLink.Video(id)
        }
        val url = URL_REGEX.find(raw)?.value ?: return null
        return parse(url)
    }

    fun parse(rawUrl: String): DeepLink? {
        val url = rawUrl.replace("youtube-nocookie.com", "youtube.com")
        val uri = runCatching { Uri.parse(url) }.getOrNull()

        // Ricerca: youtube.com/results?search_query=...
        if (uri?.path?.startsWith("/results") == true) {
            uri.getQueryParameter("search_query")?.takeIf { it.isNotBlank() }?.let { return DeepLink.Search(it) }
        }

        // watch?v=ID&list=...: come su YouTube si apre il video (non la playlist).
        WATCH_V.find(url)?.let { m ->
            if (url.contains("youtube.com/watch")) return DeepLink.Video(m.groupValues[1], startSeconds(url))
        }

        val stream = YoutubeStreamLinkHandlerFactory.getInstance()
        if (runCatching { stream.acceptUrl(url) }.getOrDefault(false)) {
            val id = runCatching { stream.getId(url) }.getOrNull() ?: return null
            return DeepLink.Video(id, startSeconds(url))
        }
        val playlist = YoutubePlaylistLinkHandlerFactory.getInstance()
        if (runCatching { playlist.acceptUrl(url) }.getOrDefault(false)) {
            runCatching { playlist.getId(url) }.getOrNull()?.let { return DeepLink.Playlist(it) }
        }
        val channel = YoutubeChannelLinkHandlerFactory.getInstance()
        if (runCatching { channel.acceptUrl(url) }.getOrDefault(false)) {
            return runCatching { DeepLink.Channel(channel.fromUrl(url).url) }.getOrNull()
        }
        return null
    }

    /** Parametro t= / start= : "125", "125s", "1h2m5s". */
    fun startSeconds(url: String): Long? {
        val raw = Regex("""[?&#](?:t|start)=([0-9hms]+)""").find(url)?.groupValues?.get(1) ?: return null
        raw.toLongOrNull()?.let { return it.takeIf { s -> s > 0 } }
        var total = 0L
        Regex("""(\d+)([hms])""").findAll(raw).forEach { m ->
            val n = m.groupValues[1].toLong()
            total += when (m.groupValues[2]) { "h" -> n * 3600; "m" -> n * 60; else -> n }
        }
        return total.takeIf { it > 0 }
    }

    private val URL_REGEX = Regex("""https?://\S+""")
    private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
    private val WATCH_V = Regex("[?&]v=([A-Za-z0-9_-]{11})")
}
