package com.mauztube.data.innertube

import com.mauztube.util.tr

import android.util.Log
import com.mauztube.data.model.PlayableStreams
import com.mauztube.data.model.StreamOption
import com.mauztube.data.model.SubtitleOption
import com.mauztube.data.model.VideoDetails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

class AgeRestrictedException(message: String) : Exception(message)

/** La decifratura del player JS di YouTube (firma / parametro "n") non è riuscita. */
class PlayerJsUnsupportedException(cause: Throwable? = null) : Exception("Decifratura player JS non riuscita", cause)

/**
 * Stream per i video con limite di età (18+), ottenuti CON L'ACCOUNT dell'utente.
 *
 * NewPipeExtractor non usa account e per questi video fallisce. Qui si chiede il /player
 * presentandosi come client TV (TVHTML5) con i cookie della sessione: è il client che
 * accetta l'autenticazione via cookie e restituisce formati scaricabili senza PoToken
 * (stessa strategia di yt-dlp con --cookies). Le firme cifrate e il parametro anti-throttling
 * "n" vengono decifrati con il player JS tramite NewPipeExtractor.
 *
 * Richiede un account con età verificata: altrimenti YouTube risponde LOGIN_REQUIRED.
 */
@Singleton
class AuthenticatedStreamFetcher @Inject constructor(
    private val innerTube: InnerTubeClient,
) {
    private data class TvConfig(val version: String, val visitorData: String?, val fetchedAt: Long)

    private var tvConfig: TvConfig? = null
    private val configLock = Mutex()

    suspend fun fetch(videoId: String): VideoDetails = withContext(Dispatchers.IO) {
        val cfg = tvConfig()
        val sts = runCatching { YoutubeJavaScriptPlayerManager.getSignatureTimestamp(videoId) }.getOrNull()
        val client = InnerTubeClient.ClientSpec(
            name = "TVHTML5",
            version = cfg.version,
            nameId = "7",
            userAgent = TV_USER_AGENT,
            extraHeaders = buildMap {
                put("Referer", "https://www.youtube.com/tv")
                cfg.visitorData?.let { put("X-Goog-Visitor-Id", it) }
            },
            extraClient = buildMap { cfg.visitorData?.let { put("visitorData", it) } },
        )
        val response = innerTube.postAs(client, "player") {
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            putJsonObject("playbackContext") {
                putJsonObject("contentPlaybackContext") {
                    put("html5Preference", "HTML5_PREF_WANTS")
                    sts?.let { put("signatureTimestamp", it) }
                }
            }
        }

        val status = response.obj("playabilityStatus")
        if (status?.str("status") != "OK") {
            val reason = status?.str("reason") ?: status?.str("status") ?: "sconosciuto"
            throw AgeRestrictedException(
                tr(
                    "YouTube didn't allow playback with your account: $reason. An age-verified account is required.",
                    "YouTube non ha consentito la riproduzione con il tuo account: $reason. " +
                        "Serve un account con età verificata.",
                )
            )
        }
        var jsFailure: Throwable? = null
        PlayerResponses.toDetails(videoId, response) { f ->
            runCatching { resolveUrl(videoId, f) }
                .onFailure { if (it is PlayerJsUnsupportedException) jsFailure = it else Log.w(TAG, "URL formato non valido", it) }
                .getOrNull()
        } ?: run {
            if (jsFailure != null) {
                throw AgeRestrictedException(
                    tr(
                        "Your account can watch this video, but YouTube changed its player protection and " +
                            "NewPipeExtractor can't decode it yet (a fix is coming in the library). " +
                            "Try again after an app update.",
                        "Il tuo account può vedere questo video, ma YouTube ha cambiato la protezione del " +
                            "suo player e NewPipeExtractor non riesce ancora a decifrarla (correzione in " +
                            "arrivo nella libreria). Riprova dopo un aggiornamento dell'app.",
                    )
                )
            }
            throw IOException(tr("No playable format for this account", "Nessun formato riproducibile per l'account"))
        }
    }

    /** URL diretto, oppure "signatureCipher" (s, sp, url) da decifrare; poi parametro "n". */
    private fun resolveUrl(videoId: String, f: JsonObject): String {
        val direct = f.str("url")
        val url = direct ?: run {
            val cipher = f.str("signatureCipher") ?: return@run null
            val params = cipher.split('&').associate {
                val i = it.indexOf('=')
                java.net.URLDecoder.decode(it.substring(0, i), "UTF-8") to
                    java.net.URLDecoder.decode(it.substring(i + 1), "UTF-8")
            }
            val base = params["url"] ?: return@run null
            val sig = try {
                YoutubeJavaScriptPlayerManager.deobfuscateSignature(videoId, params["s"] ?: return@run null)
            } catch (e: Exception) {
                throw PlayerJsUnsupportedException(e)
            }
            base.toHttpUrl().newBuilder().setQueryParameter(params["sp"] ?: "signature", sig).build().toString()
        } ?: throw IOException("Formato senza URL")

        // Parametro anti-throttling "n": senza decifratura corretta googlevideo risponde 403
        // o rallenta a pochi KB/s, quindi un risultato non valido blocca il formato.
        val originalN = url.toHttpUrl().queryParameter("n") ?: return url
        val result = try {
            YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(videoId, url)
        } catch (e: Exception) {
            throw PlayerJsUnsupportedException(e)
        }
        val n = result.toHttpUrl().queryParameter("n")
        if (n == null || n == originalN || !VALID_N.matches(n)) throw PlayerJsUnsupportedException()
        return result
    }

    /** Versione corrente del client TV e visitorData, letti dalla pagina youtube.com/tv (cache 6 h). */
    private suspend fun tvConfig(): TvConfig = configLock.withLock {
        tvConfig?.takeIf { System.currentTimeMillis() - it.fetchedAt < 6 * 3600_000L }?.let { return it }
        val fresh = runCatching {
            val html = innerTube.getText("https://www.youtube.com/tv", TV_USER_AGENT)
            TvConfig(
                version = TV_VERSION.find(html)?.groupValues?.get(1) ?: FALLBACK_TV_VERSION,
                visitorData = VISITOR.find(html)?.groupValues?.get(1),
                fetchedAt = System.currentTimeMillis(),
            )
        }.getOrElse { TvConfig(FALLBACK_TV_VERSION, null, System.currentTimeMillis()) }
        tvConfig = fresh
        fresh
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
    private fun JsonObject.obj(k: String) = this[k] as? JsonObject
    private fun JsonObject.arr(k: String) = this[k] as? JsonArray

    companion object {
        private const val TAG = "AuthStreamFetcher"
        const val TV_USER_AGENT = "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version"
        private const val FALLBACK_TV_VERSION = "7.20261012.18.00"
        private val TV_VERSION = Regex(""""clientVersion":"(7\.[0-9.]+)"""")
        private val VISITOR = Regex(""""visitorData":"([^"]+)"""")
        private val CODECS = Regex("""codecs="([^"]+)"""")
        private val VALID_N = Regex("[A-Za-z0-9_-]{4,}")
    }
}
