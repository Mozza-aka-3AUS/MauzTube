package com.mauztube.player

import com.mauztube.data.extractor.NewPipeDownloader
import com.mauztube.data.innertube.AuthenticatedStreamFetcher
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper

/**
 * Come va richiesto un URL googlevideo: gli stream rispondono 403 se lo User-Agent non
 * corrisponde al client InnerTube (ANDROID, IOS, TV, WEB…) con cui sono stati ottenuti.
 * Usato sia dal player sia dai download.
 */
data class YouTubeStreamRequest(val headers: Map<String, String>, val postBody: ByteArray?) {
    companion object {
        fun forUrl(url: String): YouTubeStreamRequest {
            if (!url.contains("googlevideo.com")) {
                return YouTubeStreamRequest(mapOf("User-Agent" to NewPipeDownloader.USER_AGENT), null)
            }
            val ua = when {
                YoutubeParsingHelper.isAndroidStreamingUrl(url) -> YoutubeParsingHelper.getAndroidUserAgent(null)
                YoutubeParsingHelper.isIosStreamingUrl(url) -> YoutubeParsingHelper.getIosUserAgent(null)
                url.contains("c=TVHTML5") -> AuthenticatedStreamFetcher.TV_USER_AGENT
                YoutubeParsingHelper.isVisionOsStreamingUrl(url) -> YoutubeParsingHelper.getVisionOsUserAgent(null)
                else -> NewPipeDownloader.USER_AGENT
            }
            val web = YoutubeParsingHelper.isWebStreamingUrl(url) ||
                YoutubeParsingHelper.isWebEmbeddedPlayerStreamingUrl(url)
            return if (web) {
                // Come il player web: POST con corpo "x\0" evita il throttling sugli stream web.
                YouTubeStreamRequest(
                    mapOf(
                        "User-Agent" to NewPipeDownloader.USER_AGENT,
                        "Origin" to "https://www.youtube.com",
                        "Referer" to "https://www.youtube.com/",
                    ),
                    byteArrayOf(0x78, 0x00),
                )
            } else {
                YouTubeStreamRequest(mapOf("User-Agent" to ua), null)
            }
        }
    }
}
