package com.mauztube.data.repository

import com.mauztube.data.local.PlaybackDao
import com.mauztube.data.local.PlaybackStateEntity
import com.mauztube.data.prefs.SettingsRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaybackRepository @Inject constructor(
    private val dao: PlaybackDao,
    private val settings: SettingsRepository,
) {
    val history: Flow<List<PlaybackStateEntity>> = dao.observeHistory()

    /**
     * Posizione da cui riprendere, oppure 0. Un video concluso (o a meno di
     * [END_THRESHOLD_MS] dalla fine) riparte dall'inizio.
     */
    suspend fun resumePositionMs(videoId: String): Long {
        val s = dao.get(videoId) ?: return 0
        if (s.finished) return 0
        if (s.durationMs > 0 && s.positionMs >= s.durationMs - END_THRESHOLD_MS) return 0
        return s.positionMs.coerceAtLeast(0)
    }

    suspend fun save(
        videoId: String,
        positionMs: Long,
        durationMs: Long,
        title: String,
        uploaderName: String,
        thumbnailUrl: String?,
        uploaderUrl: String?,
        finished: Boolean = false,
    ) {
        dao.upsert(
            PlaybackStateEntity(
                videoId = videoId,
                positionMs = if (finished) 0 else positionMs,
                durationMs = durationMs,
                title = title,
                uploaderName = uploaderName,
                thumbnailUrl = thumbnailUrl,
                updatedAt = System.currentTimeMillis(),
                finished = finished,
                uploaderUrl = uploaderUrl,
            )
        )
    }

    suspend fun delete(videoId: String) = dao.delete(videoId)
    suspend fun clearHistory() = dao.clear()

    // Sessione attiva: serve a riaprire automaticamente il video al riavvio dell'app.
    suspend fun setActiveVideo(videoId: String) = settings.setLastSession(videoId)
    suspend fun clearActiveVideo() = settings.setLastSession(null)
    suspend fun activeVideo(): String? = settings.lastSession()?.videoId

    private companion object {
        const val END_THRESHOLD_MS = 5_000L
    }
}
