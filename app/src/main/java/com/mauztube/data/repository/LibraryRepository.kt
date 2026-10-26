package com.mauztube.data.repository

import com.mauztube.util.tr
import android.util.Log
import com.mauztube.data.account.AccountManager
import com.mauztube.data.local.WatchLaterDao
import com.mauztube.data.local.WatchLaterEntity
import com.mauztube.data.model.VideoItem
import com.mauztube.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Guarda più tardi". La lista locale è sempre la fonte per la UI (risposta immediata,
 * funziona offline e senza account). Con l'account collegato ogni modifica viene inviata
 * anche alla playlist WL di YouTube, e la playlist dell'account viene importata in locale.
 */
@Singleton
class LibraryRepository @Inject constructor(
    private val dao: WatchLaterDao,
    private val account: AccountManager,
    private val recommendations: RecommendationRepository,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    val watchLater: Flow<List<VideoItem>> = dao.observeAll().map { list -> list.map { it.toVideo() } }

    val watchLaterIds: StateFlow<Set<String>> = dao.observeIds().map { it.toSet() }
        .stateIn(appScope, SharingStarted.Eagerly, emptySet())

    fun toggleWatchLater(video: VideoItem) {
        val add = video.id !in watchLaterIds.value
        com.mauztube.util.Feedback.show(
            if (add) tr("Saved to Watch later", "Salvato in Guarda più tardi")
            else tr("Removed from Watch later", "Rimosso da Guarda più tardi")
        )
        appScope.launch {
            if (add) dao.upsert(video.toWatchLater()) else dao.delete(video.id)
            if (account.loggedIn.value) {
                runCatching {
                    if (add) recommendations.addToWatchLater(video.id)
                    else recommendations.removeFromWatchLater(video.id)
                }.onFailure {
                    Log.w(TAG, "Sincronizzazione WL fallita", it)
                    com.mauztube.util.Feedback.show(tr("Couldn't update Watch later on your Google account", "Impossibile aggiornare Guarda più tardi sull'account Google"))
                }
            }
        }
    }

    /** Importa la playlist WL dell'account (unione: nulla viene rimosso in locale). */
    suspend fun syncFromAccount() {
        if (!account.loggedIn.value) return
        runCatching { recommendations.watchLater() }
            .onSuccess { remote ->
                val now = System.currentTimeMillis()
                // Ordine di YouTube preservato: il primo della playlist risulta il più recente.
                dao.insertAll(remote.mapIndexed { i, v -> v.toWatchLater(addedAt = now - i) })
            }
            .onFailure { Log.w(TAG, "Lettura WL account fallita", it) }
    }

    private fun VideoItem.toWatchLater(addedAt: Long = System.currentTimeMillis()) = WatchLaterEntity(
        videoId = id,
        title = title,
        uploaderName = uploaderName,
        uploaderUrl = uploaderUrl,
        thumbnailUrl = thumbnailUrl,
        durationSeconds = durationSeconds,
        addedAt = addedAt,
    )

    private fun WatchLaterEntity.toVideo() = VideoItem(
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

    private companion object { const val TAG = "LibraryRepository" }
}
