package com.mauztube.data.model

import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType

/**
 * Filtro Shorts a livello di MODELLO DATI.
 *
 * Ogni InfoItem restituito da NewPipeExtractor passa da qui (via [ContentMapper]) prima di
 * diventare un oggetto di dominio: se è uno Short viene scartato e non raggiunge mai
 * repository, cache, ViewModel o UI. Nessuna schermata deve quindi ricordarsi di filtrarli.
 *
 * Segnali usati, in ordine di affidabilità:
 *  1. `isShortFormContent` — impostato dall'estrattore quando YouTube marca l'elemento
 *     come Short (reelItemRenderer, shortsLockupViewModel, tab "Shorts"...).
 *  2. URL nel formato `/shorts/<id>`.
 *  3. (opzionale, "modalità aggressiva") durata ≤ [AGGRESSIVE_MAX_SECONDS] per video
 *     non live: intercetta gli Shorts che YouTube espone come normali video (es. feed RSS),
 *     al prezzo di nascondere anche qualche video brevissimo non-Short.
 */
object ShortsFilter {

    const val AGGRESSIVE_MAX_SECONDS = 60L

    fun isShort(item: StreamInfoItem, aggressive: Boolean): Boolean {
        if (item.isShortFormContent) return true
        if (isShortsUrl(item.url)) return true
        if (aggressive &&
            item.streamType == StreamType.VIDEO_STREAM &&
            item.duration in 1..AGGRESSIVE_MAX_SECONDS
        ) return true
        return false
    }

    fun isShort(item: InfoItem, aggressive: Boolean): Boolean =
        item is StreamInfoItem && isShort(item, aggressive)

    /** Variante per i video già nel modello di dominio (feed InnerTube). */
    fun isShort(video: VideoItem, aggressive: Boolean): Boolean {
        if (isShortsUrl(video.url)) return true
        return aggressive && !video.isLive && video.durationSeconds in 1..AGGRESSIVE_MAX_SECONDS
    }

    fun isShortsUrl(url: String?): Boolean =
        url != null && SHORTS_PATH.containsMatchIn(url)

    private val SHORTS_PATH = Regex("""youtube\.com/shorts/""", RegexOption.IGNORE_CASE)
}
