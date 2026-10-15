package com.mauztube.data.repository

import android.util.Log
import com.mauztube.data.local.SponsorSegmentDao
import com.mauztube.data.local.SponsorSegmentEntity
import com.mauztube.data.remote.SponsorBlockApi
import javax.inject.Inject
import javax.inject.Singleton

data class SponsorSegment(
    val uuid: String,
    val category: String,
    val startMs: Long,
    val endMs: Long,
)

/**
 * Cache-first: i segmenti di TUTTE le categorie vengono salvati in Room e filtrati in base
 * alle preferenze al momento dell'uso, così cambiare categorie non invalida la cache.
 *  - cache fresca (< [TTL_MS]) → nessuna richiesta di rete
 *  - cache scaduta → refresh; se la rete fallisce si usa comunque la cache vecchia
 */
@Singleton
class SponsorBlockRepository @Inject constructor(
    private val api: SponsorBlockApi,
    private val dao: SponsorSegmentDao,
) {
    suspend fun segmentsFor(
        videoId: String,
        server: String,
        categories: Set<String>,
    ): List<SponsorSegment> {
        val now = System.currentTimeMillis()
        val fetch = dao.fetchInfo(videoId)
        val fresh = fetch != null && now - fetch.fetchedAt < TTL_MS

        if (!fresh) {
            try {
                val remote = api.skipSegments(server, videoId).map {
                    SponsorSegmentEntity(
                        videoId = videoId,
                        uuid = it.uuid,
                        category = it.category,
                        startMs = (it.segment[0] * 1000).toLong(),
                        endMs = (it.segment[1] * 1000).toLong(),
                    )
                }
                dao.replace(videoId, remote, now)
                dao.pruneFetches(now - MAX_AGE_MS)
                dao.pruneOrphanSegments()
            } catch (e: Exception) {
                Log.w(TAG, "SponsorBlock non raggiungibile, uso la cache", e)
            }
        }

        return dao.segments(videoId)
            .filter { it.category in categories && it.endMs - it.startMs >= MIN_LENGTH_MS }
            .map { SponsorSegment(it.uuid, it.category, it.startMs, it.endMs) }
            .sortedBy { it.startMs }
    }

    private companion object {
        const val TAG = "SponsorBlockRepo"
        const val TTL_MS = 6 * 60 * 60 * 1000L          // nuovi segmenti vengono votati di continuo
        const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000 // pulizia cache vecchia
        const val MIN_LENGTH_MS = 500L
    }
}
