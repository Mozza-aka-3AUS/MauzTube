package com.mauztube.data.repository

import com.mauztube.data.local.CachedVideoEntity
import com.mauztube.data.local.VideoCacheDao
import com.mauztube.data.model.VideoItem
import javax.inject.Inject
import javax.inject.Singleton

/** Liste di video persistite in Room (correlati dei video visti, ultimi video dei canali). */
@Singleton
class VideoCacheRepository @Inject constructor(private val dao: VideoCacheDao) {

    suspend fun saveRelated(videoId: String, videos: List<VideoItem>) = save(relatedKey(videoId), videos)
    suspend fun saveChannel(channelUrl: String, videos: List<VideoItem>) = save(channelKey(channelUrl), videos)

    suspend fun related(videoIds: List<String>): Map<String, List<VideoItem>> =
        lists(videoIds.map(::relatedKey)).mapKeys { it.key.removePrefix(REL) }

    suspend fun channels(urls: List<String>): Map<String, List<VideoItem>> =
        lists(urls.map(::channelKey)).mapKeys { it.key.removePrefix(CH) }

    suspend fun channelFetchedAt(url: String): Long? = dao.fetchedAt(channelKey(url))
    suspend fun hasRelated(videoId: String): Boolean = dao.fetchedAt(relatedKey(videoId)) != null

    suspend fun prune(maxAgeMs: Long) = dao.prune(System.currentTimeMillis() - maxAgeMs)

    private suspend fun lists(keys: List<String>): Map<String, List<VideoItem>> {
        if (keys.isEmpty()) return emptyMap()
        // SQLite limita il numero di parametri: query a blocchi.
        return keys.chunked(400).flatMap { dao.lists(it) }
            .groupBy({ it.listKey }, { it.toVideo() })
    }

    private suspend fun save(key: String, videos: List<VideoItem>) {
        val now = System.currentTimeMillis()
        dao.replaceList(key, videos.distinctBy { it.id }.mapIndexed { i, v -> v.toEntity(key, i, now) })
    }

    private fun relatedKey(id: String) = REL + id
    private fun channelKey(url: String) = CH + url

    private companion object {
        const val REL = "rel:"
        const val CH = "ch:"
    }
}

fun VideoItem.toEntity(key: String, position: Int, now: Long) = CachedVideoEntity(
    listKey = key,
    videoId = id,
    position = position,
    title = title,
    uploaderName = uploaderName,
    uploaderUrl = uploaderUrl,
    uploaderAvatarUrl = uploaderAvatarUrl,
    thumbnailUrl = thumbnailUrl,
    durationSeconds = durationSeconds,
    viewText = viewText ?: viewCount.takeIf { it >= 0 }?.let { com.mauztube.util.Format.views(it) },
    textualUploadDate = textualUploadDate,
    uploadedAtMillis = uploadedAtMillis,
    isLive = isLive,
    fetchedAt = now,
)

fun CachedVideoEntity.toVideo() = VideoItem(
    id = videoId,
    url = "https://www.youtube.com/watch?v=$videoId",
    title = title,
    uploaderName = uploaderName,
    uploaderUrl = uploaderUrl,
    thumbnailUrl = thumbnailUrl,
    durationSeconds = durationSeconds,
    viewCount = -1,
    textualUploadDate = textualUploadDate,
    uploadedAtMillis = uploadedAtMillis,
    isLive = isLive,
    viewText = viewText,
    uploaderAvatarUrl = uploaderAvatarUrl,
)
