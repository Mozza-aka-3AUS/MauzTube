package com.mauztube.data.repository

import com.mauztube.util.tr

import android.content.Context
import android.util.Log
import com.mauztube.data.account.AccountManager
import com.mauztube.data.innertube.InnerTubeClient
import com.mauztube.data.innertube.InnerTubeParser
import com.mauztube.data.local.PlaylistDao
import com.mauztube.data.local.PlaylistEntity
import com.mauztube.data.local.PlaylistItemEntity
import com.mauztube.data.local.PlaylistSummary
import com.mauztube.data.model.Paged
import com.mauztube.data.model.VideoItem
import com.mauztube.util.Feedback
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Playlist del telefono (Room) e playlist dell'account Google.
 *
 * SINCRONIZZAZIONE con l'account collegato:
 *  - ogni playlist del telefono ha una copia privata nell'account (creata al collegamento
 *    o appena la playlist nasce); aggiunte, rimozioni, cambio nome ed eliminazione vengono
 *    ripetuti sulla copia. Il collegamento locale ⇄ account è salvato in [links];
 *  - le altre playlist dell'account (create su YouTube) si possono usare direttamente:
 *    lo stato "video già presente" arriva dall'account stesso.
 * "Guarda più tardi" è una playlist speciale gestita da [LibraryRepository].
 */
@Singleton
class PlaylistRepository @Inject constructor(
    private val dao: PlaylistDao,
    private val account: AccountManager,
    private val recommendations: RecommendationRepository,
    private val innerTube: InnerTubeClient,
    @ApplicationContext private val context: Context,
) {
    val playlists: Flow<List<PlaylistSummary>> = dao.observeSummaries()

    fun items(playlistId: Long): Flow<List<VideoItem>> = dao.observeItems(playlistId).map { list -> list.map { it.toVideo() } }

    fun playlistsContaining(videoId: String): Flow<Set<Long>> = dao.observePlaylistsContaining(videoId).map { it.toSet() }

    suspend fun get(id: Long) = dao.get(id)

    suspend fun create(name: String): Long {
        val now = System.currentTimeMillis()
        val id = dao.insert(PlaylistEntity(name = name.trim().ifEmpty { tr("New playlist", "Nuova playlist") }, createdAt = now, updatedAt = now))
        dao.get(id)?.let { Feedback.show(tr("Playlist \"${it.name}\" created", "Playlist \"${it.name}\" creata")) }
        if (account.loggedIn.value) remote { link(id) }
        return id
    }

    suspend fun rename(id: Long, name: String) {
        dao.rename(id, name.trim(), System.currentTimeMillis())
        linkOf(id)?.let { remoteId -> remote { innerTube.renamePlaylist(remoteId, name.trim()) } }
    }

    suspend fun delete(id: Long) {
        val remoteId = linkOf(id)
        dao.deleteItems(id)
        dao.delete(id)
        if (remoteId != null) {
            unlink(id)
            remote { innerTube.deletePlaylist(remoteId) }
        }
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
        Feedback.show(tr("Added to \"${nameOf(playlistId)}\"", "Aggiunto a \"${nameOf(playlistId)}\""))
        linkOf(playlistId)?.let { remoteId -> remote { innerTube.editPlaylist(remoteId, addVideoId = video.id) } }
    }

    suspend fun remove(playlistId: Long, videoId: String) {
        dao.deleteItem(playlistId, videoId)
        dao.touch(playlistId, System.currentTimeMillis())
        Feedback.show(tr("Removed from \"${nameOf(playlistId)}\"", "Rimosso da \"${nameOf(playlistId)}\""))
        linkOf(playlistId)?.let { remoteId -> remote { innerTube.editPlaylist(remoteId, removeVideoId = videoId) } }
    }

    // ---- account ----

    /** Playlist dell'account, senza le copie di quelle del telefono (già mostrate come locali). */
    suspend fun accountPlaylists(): List<InnerTubeParser.PlaylistRef> {
        if (!account.loggedIn.value) return emptyList()
        val linked = linkedRemoteIds()
        return recommendations.accountPlaylists().filter { it.playlistId !in linked }
    }

    /** Playlist dell'account (non collegate) con lo stato del video, per "Salva in playlist". */
    suspend fun accountOptions(videoId: String): List<InnerTubeParser.PlaylistOption> {
        if (!account.loggedIn.value) return emptyList()
        val linked = linkedRemoteIds()
        return InnerTubeParser.parseAddToPlaylist(innerTube.addToPlaylistOptions(videoId))
            .filter { it.playlistId !in linked }
    }

    suspend fun accountPlaylistVideos(id: String): Paged<VideoItem> = recommendations.accountPlaylistVideos(id)

    suspend fun addToAccountPlaylist(id: String, title: String, video: VideoItem): Boolean = runCatching {
        innerTube.editPlaylist(id, addVideoId = video.id)
    }.fold(
        onSuccess = { Feedback.show(tr("Added to \"$title\"", "Aggiunto a \"$title\"")); true },
        onFailure = { Log.w(TAG, "Aggiunta alla playlist dell'account fallita", it); Feedback.show(SYNC_ERROR()); false },
    )

    suspend fun removeFromAccountPlaylist(id: String, title: String, videoId: String): Boolean = runCatching {
        innerTube.editPlaylist(id, removeVideoId = videoId)
    }.fold(
        onSuccess = { Feedback.show(tr("Removed from \"$title\"", "Rimosso da \"$title\"")); true },
        onFailure = { Log.w(TAG, "Rimozione dalla playlist dell'account fallita", it); Feedback.show(SYNC_ERROR()); false },
    )

    /**
     * Al collegamento dell'account: ogni playlist del telefono senza copia nell'account viene
     * creata lì (privata, con gli stessi video). Le successive modifiche restano allineate.
     */
    suspend fun syncLocalToAccount() {
        if (!account.loggedIn.value) return
        syncLock.withLock {
            dao.observeSummaries().first().forEach { p ->
                if (linkOf(p.id) == null) runCatching { link(p.id) }.onFailure { Log.w(TAG, "Copia della playlist nell'account fallita", it) }
            }
        }
    }

    // ---- collegamento playlist del telefono ⇄ account ----

    /** All'uscita dall'account: un altro account riceverà le proprie copie delle playlist. */
    suspend fun forgetAccountLinks() = withContext(Dispatchers.IO) { links.edit().clear().apply() }

    private val syncLock = Mutex()
    private val links by lazy { context.getSharedPreferences("playlist_links", Context.MODE_PRIVATE) }

    private suspend fun linkOf(localId: Long): String? =
        if (!account.loggedIn.value) null
        else withContext(Dispatchers.IO) { links.getString(localId.toString(), null) }

    private suspend fun linkedRemoteIds(): Set<String> = withContext(Dispatchers.IO) {
        links.all.values.mapNotNull { it as? String }.toSet()
    }

    private suspend fun unlink(localId: Long) = withContext(Dispatchers.IO) {
        links.edit().remove(localId.toString()).apply()
    }

    /** Crea la copia nell'account della playlist [localId] e ricorda il collegamento. */
    private suspend fun link(localId: Long) {
        val p = dao.get(localId) ?: return
        val videos = dao.observeItems(localId).first().map { it.videoId }
        val remoteId = innerTube.createPlaylist(p.name, videos) ?: error("Nessun ID dalla creazione della playlist")
        withContext(Dispatchers.IO) { links.edit().putString(localId.toString(), remoteId).apply() }
    }

    private suspend fun nameOf(id: Long) = dao.get(id)?.name.orEmpty()

    /** Modifica sull'account: un errore non blocca la modifica locale, ma viene segnalato. */
    private suspend fun remote(block: suspend () -> Unit) {
        runCatching { block() }.onFailure {
            Log.w(TAG, "Sincronizzazione playlist con l'account fallita", it)
            Feedback.show(SYNC_ERROR())
        }
    }

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

    private companion object {
        const val TAG = "PlaylistRepository"
        val SYNC_ERROR = { tr("Couldn't update the playlist on your Google account", "Impossibile aggiornare la playlist sull'account Google") }
    }
}
