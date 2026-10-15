package com.mauztube.player

import androidx.media3.common.Player
import com.mauztube.data.repository.SponsorSegment

data class SkipEvent(val segment: SponsorSegment, val fromMs: Long)

/**
 * Logica di salto dei segmenti SponsorBlock, indipendente dal ciclo di vita.
 *
 * PUNTI CRITICI:
 *  - Il controllo gira ogni [CHECK_INTERVAL_MS] ms solo durante la riproduzione (vedi
 *    PlayerViewModel); la latenza massima di skip è quindi ~200 ms.
 *  - Se l'UTENTE fa seek dentro un segmento, quel segmento NON viene saltato (rispetto della
 *    scelta esplicita), finché non esce e ci rientra riproducendo normalmente.
 *  - "Annulla" riporta indietro e marca il segmento come da non saltare.
 *  - I seek interni sono marcati per non essere scambiati per seek dell'utente.
 */
class SponsorSkipper(private val player: Player) {

    var segments: List<SponsorSegment> = emptyList()
        private set

    private val userAllowed = mutableSetOf<String>()
    private var internalSeek = false

    fun setSegments(list: List<SponsorSegment>) {
        segments = list.sortedBy { it.startMs }
        userAllowed.clear()
    }

    /** @return l'evento di skip se è stato eseguito un salto, altrimenti null. */
    fun check(): SkipEvent? {
        if (segments.isEmpty()) return null
        val pos = player.currentPosition
        val segment = segments.firstOrNull { pos >= it.startMs && pos < it.endMs - END_TOLERANCE_MS }
            ?: return null
        if (segment.uuid in userAllowed) return null

        val duration = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        internalSeek = true
        player.seekTo(segment.endMs.coerceAtMost(duration))
        return SkipEvent(segment, pos)
    }

    fun undo(event: SkipEvent) {
        userAllowed += event.segment.uuid
        internalSeek = true
        player.seekTo(event.fromMs)
    }

    /** Da chiamare da Player.Listener.onPositionDiscontinuity con reason == SEEK. */
    fun onSeek(newPositionMs: Long) {
        if (internalSeek) {
            internalSeek = false
            return
        }
        userAllowed.clear()
        segments.filter { newPositionMs >= it.startMs && newPositionMs < it.endMs }
            .forEach { userAllowed += it.uuid }
    }

    companion object {
        const val CHECK_INTERVAL_MS = 200L
        private const val END_TOLERANCE_MS = 300L
    }
}
