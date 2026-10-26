package com.mauztube.data.innertube

import com.mauztube.util.tr

import com.mauztube.data.account.AccountManager
import com.mauztube.data.extractor.NewPipeDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.io.IOException
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

class NotLoggedInException : Exception(tr("Sign in with Google to see your recommendations", "Accedi con Google per vedere i consigliati"))

/**
 * Client minimale per l'API interna di www.youtube.com (InnerTube, client WEB), usato SOLO
 * per le funzioni legate all'account: home consigliata, correlati personalizzati, cronologia.
 * Stream e metadati restano su NewPipeExtractor.
 */
@Singleton
class InnerTubeClient @Inject constructor(
    private val http: OkHttpClient,
    private val json: Json,
    private val account: AccountManager,
) {
    suspend fun browse(browseId: String? = null, continuation: String? = null): JsonObject =
        post("browse") {
            browseId?.let { put("browseId", it) }
            continuation?.let { put("continuation", it) }
        }

    suspend fun next(videoId: String): JsonObject = post("next") { put("videoId", videoId) }

    suspend fun player(videoId: String): JsonObject = post("player") { put("videoId", videoId) }

    /** Valutazione sull'account: "like/like", "like/dislike" o "like/removelike". */
    suspend fun rate(videoId: String, endpoint: String) =
        post(endpoint) { putJsonObject("target") { put("videoId", videoId) } }

    suspend fun subscribe(channelId: String) =
        post("subscription/subscribe") { putJsonArray("channelIds") { add(channelId) } }

    suspend fun unsubscribe(channelId: String) =
        post("subscription/unsubscribe") { putJsonArray("channelIds") { add(channelId) } }

    /** Aggiunge/rimuove un video da una playlist dell'account (es. "WL" = Guarda più tardi). */
    suspend fun editPlaylist(playlistId: String, addVideoId: String? = null, removeVideoId: String? = null) {
        post("browse/edit_playlist") {
            put("playlistId", playlistId)
            putJsonArray("actions") {
                addVideoId?.let { id ->
                    add(buildJsonObject { put("action", "ACTION_ADD_VIDEO"); put("addedVideoId", id) })
                }
                removeVideoId?.let { id ->
                    add(buildJsonObject { put("action", "ACTION_REMOVE_VIDEO_BY_VIDEO_ID"); put("removedVideoId", id) })
                }
            }
        }
    }

    /**
     * Playlist dell'account con lo stato del video (già presente o no): è la stessa richiesta
     * della finestra "Salva" del sito di YouTube.
     */
    suspend fun addToPlaylistOptions(videoId: String): JsonObject =
        post("playlist/get_add_to_playlist") {
            putJsonArray("videoIds") { add(videoId) }
            put("excludeWatchLater", true)
        }

    /** Crea una playlist privata nell'account con i video indicati; restituisce il suo ID. */
    suspend fun createPlaylist(title: String, videoIds: List<String>): String? {
        val r = post("playlist/create") {
            put("title", title)
            put("privacyStatus", "PRIVATE")
            putJsonArray("videoIds") { videoIds.forEach { add(it) } }
        }
        return (r["playlistId"] as? kotlinx.serialization.json.JsonPrimitive)?.content
    }

    suspend fun renamePlaylist(playlistId: String, name: String) {
        post("browse/edit_playlist") {
            put("playlistId", playlistId)
            putJsonArray("actions") {
                add(buildJsonObject { put("action", "ACTION_SET_PLAYLIST_NAME"); put("playlistName", name) })
            }
        }
    }

    suspend fun deletePlaylist(playlistId: String) {
        post("playlist/delete") { put("playlistId", playlistId) }
    }

    /** GET autenticato (ping di statistiche per la cronologia). */
    suspend fun authenticatedGet(url: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url)
            .header("User-Agent", NewPipeDownloader.USER_AGENT)
            .apply { account.authHeaders().forEach { (k, v) -> header(k, v) } }
            .build()
        http.newCall(request).execute().use { r ->
            account.storeResponseCookies(r.headers("Set-Cookie"))
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
        }
    }

    /** Client InnerTube con cui presentarsi (il default è il sito web desktop). */
    data class ClientSpec(
        val name: String,
        val version: String,
        val nameId: String,
        val userAgent: String,
        val extraHeaders: Map<String, String> = emptyMap(),
        val extraClient: Map<String, String> = emptyMap(),
    )

    /** Richiesta autenticata generica con il client WEB. */
    suspend fun postWeb(endpoint: String, body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): JsonObject =
        post(endpoint, body)

    private suspend fun post(
        endpoint: String,
        body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ): JsonObject = withContext(Dispatchers.IO) { postAs(webClient(), endpoint, body) }

    private fun webClient() = ClientSpec(
        name = "WEB",
        version = YoutubeParsingHelper.getClientVersion(),
        nameId = "1",
        userAgent = NewPipeDownloader.USER_AGENT,
        extraClient = mapOf("platform" to "DESKTOP"),
    )

    suspend fun postAs(
        client: ClientSpec,
        endpoint: String,
        body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ): JsonObject = withContext(Dispatchers.IO) {
        val auth = account.authHeaders()
        if (auth.isEmpty()) throw NotLoggedInException()

        val locale = NewPipe.getPreferredLocalization()
        val country = NewPipe.getPreferredContentCountry()
        val payload = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", client.name)
                    put("clientVersion", client.version)
                    put("hl", locale.localizationCode ?: Locale.getDefault().language)
                    put("gl", country.countryCode)
                    client.extraClient.forEach { (k, v) -> put(k, v) }
                }
            }
            body()
        }

        val request = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/$endpoint?prettyPrint=false")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .header("User-Agent", client.userAgent)
            .header("X-Youtube-Client-Name", client.nameId)
            .header("X-Youtube-Client-Version", client.version)
            .apply {
                auth.forEach { (k, v) -> header(k, v) }
                client.extraHeaders.forEach { (k, v) -> header(k, v) }
            }
            .build()

        http.newCall(request).execute().use { r ->
            account.storeResponseCookies(r.headers("Set-Cookie"))
            if (r.code == 401 || r.code == 403) {
                account.refresh()
                throw IOException(tr("Google session is no longer valid (HTTP ${r.code}): please sign in again", "Sessione Google non valida (HTTP ${r.code}): rifai l'accesso"))
            }
            if (!r.isSuccessful) throw IOException("InnerTube $endpoint HTTP ${r.code}")
            json.parseToJsonElement(r.body.string()).jsonObject
        }
    }

    /** GET semplice (pagine di configurazione dei client). */
    suspend fun getText(url: String, userAgent: String): String = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).header("User-Agent", userAgent).build()).execute().use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
            it.body.string()
        }
    }
}
