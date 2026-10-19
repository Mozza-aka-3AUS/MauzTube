package com.mauztube.data.extractor

import okhttp3.OkHttpClient
import okhttp3.Request.Builder
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ponte tra NewPipeExtractor e OkHttp: tutte le richieste verso YouTube (pagine, API
 * InnerTube, player JS) passano da qui. Nessun SDK Google viene mai caricato.
 */
@Singleton
class NewPipeDownloader @Inject constructor(
    base: OkHttpClient,
    @com.mauztube.di.MediaHttp private val media: OkHttpClient,
) : Downloader() {

    /**
     * Le risposte dell'API sono piccole: 7 s senza ricevere dati indicano una connessione
     * bloccata (es. rimasta aperta su una rete non più valida). Invece di aspettare si
     * riprova UNA volta su una connessione nuova.
     */
    private val client = base.newBuilder().readTimeout(7, java.util.concurrent.TimeUnit.SECONDS).build()

    override fun execute(request: Request): Response {
        val visitorKey = visitorKey(request)
        if (visitorKey != null) {
            visitorCache[visitorKey]?.takeIf { System.currentTimeMillis() - it.first < VISITOR_TTL_MS }
                ?.let { return it.second }
        }
        return executeWithRetry(request).also {
            if (visitorKey != null && it.responseCode() == 200) {
                if (visitorCache.size > 16) visitorCache.clear()
                visitorCache[visitorKey] = System.currentTimeMillis() to it
            }
        }
    }

    // ---- visitor_id: un browser lo riceve una volta (cookie) e lo riusa; NewPipeExtractor ne
    // chiede uno nuovo per ogni client e per ogni video (3 richieste in sequenza a video).
    // Si riusa quello ottenuto negli ultimi 30 minuti per lo stesso client.
    private val visitorCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Response>>()

    private fun visitorKey(request: Request): String? {
        if (!request.url().contains("/visitor_id")) return null
        val body = request.dataToSend()?.toString(Charsets.UTF_8) ?: return null
        val name = CLIENT_NAME.find(body)?.groupValues?.get(1) ?: return null
        val version = CLIENT_VERSION.find(body)?.groupValues?.get(1).orEmpty()
        return request.url().substringBefore('?') + "|" + name + "|" + version
    }

    private fun executeWithRetry(request: Request): Response = try {
        executeOnce(request)
    } catch (e: java.io.InterruptedIOException) {
        if (Thread.currentThread().isInterrupted) throw e
        client.connectionPool.evictAll()
        executeOnce(request)
    } catch (e: java.net.ConnectException) {
        client.connectionPool.evictAll()
        executeOnce(request)
    }

    private fun executeOnce(request: Request): Response {
        val body = request.dataToSend()?.toRequestBody()
        val builder = Builder()
            .url(request.url())
            .method(request.httpMethod(), body)
            .header("User-Agent", USER_AGENT)

        request.headers().forEach { (name, values) ->
            builder.removeHeader(name)
            values.forEach { builder.addHeader(name, it) }
        }

        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 429) {
                throw ReCaptchaException("reCaptcha Challenge requested", request.url())
            }
            val body = response.body.string()
            val url = request.url()
            // Risposta del client visionOS: è quella da cui NewPipeExtractor prende gli URL degli
            // stream (il client Android oggi restituisce solo un URL SABR senza formati diretti).
            if (response.code == 200 && url.startsWith(GAPIS_PLAYER)) {
                val sent = request.dataToSend()?.toString(Charsets.UTF_8)
                // Solo per il video che l'utente sta aprendo (non per i precaricamenti): le
                // connessioni aperte e poi inutilizzate invecchierebbero nel pool.
                if (sent != null && "VISIONOS" in sent && EarlyPlayerHub.offer(url, sent, body)) {
                    preconnectVideoServer(body)
                }
            }
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                body,
                response.request.url.toString(),
            )
        }
    }

    private val warmed = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Appena arriva la risposta `player` con gli stream (prima delle altre richieste) si
     * aprono due connessioni verso il server video indicato, come fa il player di YouTube con
     * `generate_204`: quando ExoPlayer chiede audio e video l'handshake TLS è già fatto.
     */
    private fun preconnectVideoServer(body: String) {
        val host = VIDEO_HOST.find(body)?.value ?: return
        val now = System.currentTimeMillis()
        val last = warmed[host]
        if (last != null && now - last < 60_000) return
        if (warmed.size > 32) warmed.clear()
        warmed[host] = now
        val request = Builder().url("https://$host/generate_204").header("User-Agent", USER_AGENT).build()
        repeat(2) {
            media.newCall(request).enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) = Unit
                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) = response.close()
            })
        }
    }

    companion object {
        private const val VISITOR_TTL_MS = 30 * 60_000L
        private const val GAPIS_PLAYER = "https://youtubei.googleapis.com/youtubei/v1/player"
        private val CLIENT_NAME = Regex("""clientName"\s*:\s*"([^"]+)""")
        private val CLIENT_VERSION = Regex("""clientVersion"\s*:\s*"([^"]+)""")
        private val VIDEO_HOST = Regex("""rr\d+---sn-[a-z0-9-]+\.googlevideo\.com""")
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
    }
}
