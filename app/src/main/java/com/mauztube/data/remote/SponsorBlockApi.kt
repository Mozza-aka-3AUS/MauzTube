package com.mauztube.data.remote

import com.mauztube.data.prefs.SponsorCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class SponsorSegmentDto(
    val category: String,
    val actionType: String = "skip",
    val segment: List<Double>,
    @SerialName("UUID") val uuid: String,
    val videoDuration: Double = 0.0,
)

@Serializable
private data class HashPrefixResultDto(
    val videoID: String,
    val segments: List<SponsorSegmentDto> = emptyList(),
)

/**
 * Client REST per https://sponsor.ajay.app.
 *
 * Usa l'endpoint "privacy-preserving" `/api/skipSegments/{sha256(videoId)[0..4]}`:
 * il server riceve solo i primi 4 caratteri dell'hash e restituisce i segmenti di tutti i
 * video con quel prefisso; il filtro sul videoId avviene in locale. Così il server non
 * sa quale video si sta guardando. Semantica identica a `/api/skipSegments?videoID=...`.
 */
@Singleton
class SponsorBlockApi @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
) {
    /** @return lista (anche vuota) dei segmenti "skip" del video; eccezione solo per errori di rete/server. */
    suspend fun skipSegments(server: String, videoId: String): List<SponsorSegmentDto> =
        withContext(Dispatchers.IO) {
            val categories = SponsorCategory.ALL_KEYS.joinToString(",", "[", "]") { "\"$it\"" }
            val url = "${server.trimEnd('/')}/api/skipSegments/${sha256(videoId).take(4)}"
                .toHttpUrl().newBuilder()
                .addQueryParameter("categories", categories)
                .addQueryParameter("actionTypes", "[\"skip\"]")
                .build()

            val request = Request.Builder().url(url).header("User-Agent", "MauzTube/1.0").build()
            client.newCall(request).execute().use { response ->
                when {
                    response.code == 404 -> emptyList() // nessun segmento per questo prefisso
                    !response.isSuccessful -> throw IOException("SponsorBlock HTTP ${response.code}")
                    else -> json.decodeFromString<List<HashPrefixResultDto>>(response.body.string())
                        .firstOrNull { it.videoID == videoId }
                        ?.segments
                        .orEmpty()
                        .filter { it.actionType == "skip" && it.segment.size == 2 }
                }
            }
        }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
