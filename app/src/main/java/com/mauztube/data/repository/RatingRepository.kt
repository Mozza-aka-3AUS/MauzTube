package com.mauztube.data.repository

import android.util.Log
import com.mauztube.data.account.AccountManager
import com.mauztube.data.local.RatingDao
import com.mauztube.data.local.RatingEntity
import com.mauztube.data.model.VideoItem
import com.mauztube.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class VoteCounts(val likes: Long = -1, val dislikes: Long = -1)

/**
 * Mi piace / Non mi piace. Salvati sempre in locale (risposta immediata, lista "Video che ti
 * piacciono" anche senza account); con l'account collegato vengono inviati a YouTube e la
 * valutazione dell'account, letta all'apertura del video, prevale su quella locale.
 *
 * I conteggi dei "non mi piace" (che YouTube non mostra più) arrivano dall'API pubblica di
 * Return YouTube Dislike, se attivata nelle impostazioni.
 */
@Singleton
class RatingRepository @Inject constructor(
    private val dao: RatingDao,
    private val account: AccountManager,
    private val recommendations: RecommendationRepository,
    private val http: OkHttpClient,
    private val json: Json,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    fun rating(videoId: String): Flow<Int> = dao.observeRating(videoId).map { it ?: 0 }

    val liked: Flow<List<VideoItem>> = dao.observeLiked().map { list -> list.map { it.toVideo() } }

    /** @param rating 1 = mi piace, -1 = non mi piace, 0 = rimuovi. */
    fun setRating(video: VideoItem, rating: Int) {
        appScope.launch {
            store(video, rating)
            if (account.loggedIn.value) {
                runCatching { recommendations.rate(video.id, rating) }
                    .onFailure { Log.w(TAG, "Valutazione sull'account fallita", it) }
            }
        }
    }

    /** Allinea il locale alla valutazione letta dall'account. */
    suspend fun applyAccountRating(video: VideoItem, rating: Int) = store(video, rating)

    /** Importa "Video piaciuti" dell'account (senza rimuovere nulla in locale). */
    suspend fun syncLikedFromAccount() {
        if (!account.loggedIn.value) return
        runCatching { recommendations.likedVideos() }.onSuccess { list ->
            val now = System.currentTimeMillis()
            dao.insertAll(list.mapIndexed { i, v -> v.toEntity(1, now - i) })
        }.onFailure { Log.w(TAG, "Lettura video piaciuti fallita", it) }
    }

    suspend fun counts(videoId: String): VoteCounts? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url("https://returnyoutubedislikeapi.com/votes?videoId=$videoId")
                .header("User-Agent", "MauzTube/1.0").build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) null else json.decodeFromString<VoteCounts>(r.body.string())
            }
        }.getOrNull()
    }

    private suspend fun store(video: VideoItem, rating: Int) {
        if (rating == 0) dao.delete(video.id) else dao.upsert(video.toEntity(rating, System.currentTimeMillis()))
    }

    private fun VideoItem.toEntity(rating: Int, now: Long) = RatingEntity(
        videoId = id, rating = rating, title = title, uploaderName = uploaderName, uploaderUrl = uploaderUrl,
        thumbnailUrl = thumbnailUrl, durationSeconds = durationSeconds, updatedAt = now,
    )

    private fun RatingEntity.toVideo() = VideoItem(
        id = videoId, url = "https://www.youtube.com/watch?v=$videoId", title = title, uploaderName = uploaderName,
        uploaderUrl = uploaderUrl, thumbnailUrl = thumbnailUrl, durationSeconds = durationSeconds, viewCount = -1,
        textualUploadDate = null, uploadedAtMillis = null, isLive = false,
    )

    private companion object { const val TAG = "RatingRepository" }
}
