package com.mauztube.data.extractor

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/**
 * PARTENZA ANTICIPATA: NewPipeExtractor esegue in sequenza ~7 richieste (visitor_id, player
 * Android, visitor_id, player visionOS, visitor_id, player web, next), ma gli URL degli stream
 * che poi usa arrivano già dalla risposta del client visionOS (il client Android oggi non ha
 * formati diretti). Le richieste successive (player web e next, le più lente) servono solo per
 * metadati, consigliati e anteprime.
 *
 * Quando l'utente apre un video, il player si registra qui; [NewPipeDownloader] consegna la
 * risposta Android appena arriva, e il video può partire mentre l'estrazione completa
 * prosegue in parallelo (come fa il client ufficiale: /player subito, /next dopo).
 */
object EarlyPlayerHub {
    /** Corpo della risposta `player` e nonce di riproduzione (cpn) della richiesta. */
    class Early(val responseBody: String, val cpn: String)

    private val waiting = ConcurrentHashMap<String, CompletableDeferred<Early>>()

    fun register(videoId: String): CompletableDeferred<Early> =
        waiting.computeIfAbsent(videoId) { CompletableDeferred() }

    fun unregister(videoId: String, deferred: CompletableDeferred<Early>) {
        waiting.remove(videoId, deferred)
    }

    /**
     * Chiamato dal downloader per ogni risposta visionOS: costa solo se qualcuno aspetta.
     * @return true se la risposta era attesa (video aperto dall'utente).
     */
    fun offer(url: String, requestBody: String?, responseBody: String): Boolean {
        if (waiting.isEmpty()) return false
        val videoId = VIDEO_ID.find(url)?.groupValues?.get(1) ?: return false
        val deferred = waiting[videoId] ?: return false
        val cpn = requestBody?.let { CPN.find(it)?.groupValues?.get(1) } ?: return false
        return deferred.complete(Early(responseBody, cpn))
    }

    private val VIDEO_ID = Regex("""[?&]id=([\w-]{11})""")
    private val CPN = Regex(""""cpn"\s*:\s*"([^"]+)"""")
}
