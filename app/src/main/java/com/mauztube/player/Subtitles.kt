package com.mauztube.player

import android.util.Log
import android.util.LruCache
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import com.mauztube.data.extractor.NewPipeDownloader
import com.mauztube.data.model.SubtitleOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sottotitoli caricati SEPARATAMENTE dal video.
 *
 * Prima le tracce erano unite alla sorgente di ExoPlayer: il video non partiva finché tutte
 * (fino a 4) non erano state scaricate, e il server dei sottotitoli di YouTube a volte
 * risponde dopo ~10 s. Misurato: primo fotogramma a 13 s invece di 2–3 s. Ora il video parte
 * subito; solo la traccia scelta viene scaricata, analizzata una volta e mostrata dall'interfaccia.
 */
@OptIn(UnstableApi::class)
@Singleton
class SubtitleLoader @Inject constructor(http: OkHttpClient) {
    private val client = http.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()
    private val parsers = DefaultSubtitleParserFactory()
    private val cache = LruCache<String, SubtitleTrack>(6)

    suspend fun load(sub: SubtitleOption): SubtitleTrack? {
        cache.get(sub.url)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                val bytes = client.newCall(
                    Request.Builder().url(sub.url).header("User-Agent", NewPipeDownloader.USER_AGENT).build()
                ).execute().use { r -> if (r.isSuccessful) r.body.bytes() else error("HTTP ${r.code}") }
                val format = Format.Builder().setSampleMimeType(sub.mimeType).build()
                require(parsers.supportsFormat(format)) { "Formato non supportato: ${sub.mimeType}" }
                val cues = ArrayList<CuesWithTiming>()
                parsers.create(format).parse(bytes, SubtitleParser.OutputOptions.allCues()) { cues += it }
                SubtitleTrack.from(cues)
            }.onFailure { Log.w("SubtitleLoader", "Sottotitoli non disponibili", it) }
                .getOrNull()
                ?.also { cache.put(sub.url, it) }
        }
    }
}

/** Righe di testo con i loro intervalli, ordinate: ricerca binaria per posizione. */
class SubtitleTrack private constructor(
    private val starts: LongArray,
    private val ends: LongArray,
    private val texts: Array<String>,
) {
    /** Testo visibile alla posizione [positionMs] (più righe sovrapposte unite), o null. */
    fun textAt(positionMs: Long): String? {
        val us = positionMs * 1000
        // Ultimo indice con inizio <= posizione.
        var lo = 0
        var hi = starts.size - 1
        var idx = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] <= us) { idx = mid; lo = mid + 1 } else hi = mid - 1
        }
        if (idx < 0) return null
        // Le righe automatiche si sovrappongono un po': si guardano solo le poche precedenti.
        var result: String? = null
        var i = idx
        while (i >= 0 && i > idx - MAX_OVERLAP) {
            if (ends[i] > us) result = if (result == null) texts[i] else texts[i] + "\n" + result
            i--
        }
        return result
    }

    companion object {
        private const val MAX_OVERLAP = 4

        @OptIn(UnstableApi::class)
        fun from(cues: List<CuesWithTiming>): SubtitleTrack {
            val rows = cues.mapNotNull { c ->
                val text = c.cues.mapNotNull { it.text?.toString()?.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
                if (text.isEmpty() || c.startTimeUs == androidx.media3.common.C.TIME_UNSET) null
                else Triple(c.startTimeUs, c.endTimeUs.takeIf { it != androidx.media3.common.C.TIME_UNSET } ?: (c.startTimeUs + 5_000_000), text)
            }.sortedBy { it.first }
            return SubtitleTrack(
                LongArray(rows.size) { rows[it].first },
                LongArray(rows.size) { rows[it].second },
                Array(rows.size) { rows[it].third },
            )
        }
    }
}
