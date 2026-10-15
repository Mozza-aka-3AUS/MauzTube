package com.mauztube.data.repository

import android.util.Log
import com.mauztube.data.model.VideoItem
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ultimi video dei canali con cache persistente: la UI mostra subito ciò che è in cache e
 * aggiorna in background solo i canali più vecchi di [maxAgeMs].
 */
@Singleton
class ChannelFeedRepository @Inject constructor(
    private val youtube: YouTubeRepository,
    private val cache: VideoCacheRepository,
) {
    suspend fun cached(channelUrls: List<String>): Map<String, List<VideoItem>> = cache.channels(channelUrls)

    /** Feed cronologico dalla sola cache (nessuna rete). */
    suspend fun cachedFeed(channelUrls: List<String>): List<VideoItem> =
        cache.channels(channelUrls).values.flatten()
            .distinctBy { it.id }
            .sortedByDescending { it.uploadedAtMillis ?: 0L }

    /** @return numero di canali aggiornati. */
    suspend fun refresh(channelUrls: List<String>, maxAgeMs: Long = DEFAULT_MAX_AGE_MS): Int {
        val now = System.currentTimeMillis()
        val stale = channelUrls.filter { now - (cache.channelFetchedAt(it) ?: 0L) > maxAgeMs }
        if (stale.isEmpty()) return 0
        val gate = Semaphore(3)
        return coroutineScope {
            stale.map { url ->
                async {
                    gate.withPermit {
                        runCatching { youtube.channel(url).videos.items.take(PER_CHANNEL) }
                            .onSuccess { cache.saveChannel(url, it) }
                            .onFailure { Log.w(TAG, "Aggiornamento canale fallito: $url", it) }
                            .isSuccess
                    }
                }
            }.awaitAll().count { it }
        }
    }

    companion object {
        private const val TAG = "ChannelFeedRepo"
        const val DEFAULT_MAX_AGE_MS = 3 * 60 * 60 * 1000L
        private const val PER_CHANNEL = 20
    }
}
