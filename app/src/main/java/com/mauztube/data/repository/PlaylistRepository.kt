package com.mauztube.data.repository

import com.mauztube.util.tr

import com.mauztube.data.account.AccountManager
import com.mauztube.data.innertube.InnerTubeParser
import com.mauztube.data.local.PlaylistDao
import com.mauztube.data.local.PlaylistEntity
import com.mauztube.data.local.PlaylistItemEntity
import com.mauztube.data.local.PlaylistSummary
import com.mauztube.data.model.Paged
import com.mauztube.data.model.VideoItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Playlist locali (Room) + playlist dell'account Google (lette via InnerTube; si possono
 * aggiungere video). "Guarda più tardi" è una playlist speciale gestita da [LibraryRepository].
 */
@Singleton
class PlaylistRepository @Inject constructor(
    private val dao: PlaylistDao,
    private val account: AccountManager,
    private val recommendations: RecommendationRepository,
) {
    val playlists: Flow<List<PlaylistSummary>> = dao.observeSummaries()

    fun items(playlistId: Long): Flow<List<VideoItem>> = dao.observeItems(playlistId).map { list -> list.map { it.toVideo() } }

    fun playlistsContaining(videoId: String): Flow<Set<Long>> = dao.observePlaylistsContaining(videoId).map { it.toSet() }

    suspend fun get(id: Long) = dao.get(id)

    suspend fun create(name: String): Long {
        val now = System.currentTimeMillis()
        return dao.insert(PlaylistEntity(name = name.trim().ifEmpty { tr("New playlist", "Nuova playlist") }, createdAt = now, updatedAt = now))
    }

    suspend fun rename(id: Long, name: String) = dao.rename(id, name.trim(), System.currentTimeMillis())

    suspend fun delete(id: Long) {
        dao.deleteItems(id)
        dao.delete(id)
    }

    suspend fun add(playlistId: Long, video: VideoItem) {
        dao.insertItem(
            PlaylistItemEntity(
                playlistId = playlistId,
                videoId = video.id,
                position = dao.nextPosition(playlistId),
                title = video.title,
                uploaderName = video.uploaderName,
                uploaderUrl = video.uploaderUrl,
                thumbnailUrl = video.thumbnailUrl,
                durationSeconds = video.durationSeconds,
                addedAt = System.currentTimeMillis(),
            )
        )
        dao.touch(playlistId, System.currentTimeMillis())
    }

    suspend fun remove(playlistId: Long, videoId: String) {
        dao.deleteItem(playlistId, videoId)
        dao.touch(playlistId, System.currentTimeMillis())
    }

    // ---- account ----

    suspend fun accountPlaylists(): List<InnerTubeParser.PlaylistRef> =
        if (account.loggedIn.value) recommendations.accountPlaylists() else emptyList()

    suspend fun accountPlaylistVideos(id: String): Paged<VideoItem> = recommendations.accountPlaylistVideos(id)

    suspend fun addToAccountPlaylist(id: String, video: VideoItem) = recommendations.addToAccountPlaylist(id, video.id)

    private fun PlaylistItemEntity.toVideo() = VideoItem(
        id = videoId,
        url = "https://www.youtube.com/watch?v=$videoId",
        title = title,
        uploaderName = uploaderName,
        uploaderUrl = uploaderUrl,
        thumbnailUrl = thumbnailUrl,
        durationSeconds = durationSeconds,
        viewCount = -1,
        textualUploadDate = null,
        uploadedAtMillis = null,
        isLive = false,
    )
}
