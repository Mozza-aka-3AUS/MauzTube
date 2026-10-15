package com.mauztube.data.recommend

import com.mauztube.data.local.PlaybackDao
import com.mauztube.data.local.SubscriptionDao
import com.mauztube.data.model.VideoItem
import com.mauztube.data.repository.ChannelFeedRepository
import com.mauztube.data.repository.VideoCacheRepository
import com.mauztube.data.repository.YouTubeRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Consigli SENZA account, calcolati interamente sul telefono.
 *
 * Fonti (tutte già in cache in Room, quindi il calcolo non tocca la rete ed è istantaneo):
 *  1. correlati dei video guardati di recente — salvati ogni volta che si apre un video;
 *  2. ultimi video dei canali seguiti e dei canali guardati più spesso.
 *
 * Punteggio di un candidato:
 *  - correlati: peso del video sorgente (più recente = più peso) × posizione nella lista
 *    (i primi correlati sono i più pertinenti). Un video che compare nei correlati di più
 *    video visti accumula punteggio: è il segnale più forte;
 *  - video di un canale: affinità del canale × freschezza (decadimento esponenziale ~10 giorni);
 *  - bonus affinità del canale per ogni candidato: iscrizione = 3, ogni visione ≈ 1 (decrescente);
 *  - leggera variazione casuale ad ogni aggiornamento, così la home non è sempre identica.
 * Esclusi i video già visti; al massimo 2 video per canale nelle prime posizioni.
 */
@Singleton
class LocalRecommender @Inject constructor(
    private val playbackDao: PlaybackDao,
    private val subscriptionDao: SubscriptionDao,
    private val cache: VideoCacheRepository,
    private val channelFeed: ChannelFeedRepository,
    private val youtube: YouTubeRepository,
) {
    suspend fun hasSignals(): Boolean =
        playbackDao.recent(1).isNotEmpty() || subscriptionDao.all().isNotEmpty()

    /**
     * @param recentlyShown video mostrati nelle ultime aperture della home: vengono
     *  fortemente penalizzati, così ogni aggiornamento propone video diversi.
     */
    suspend fun recommend(seed: Int = 0, recentlyShown: Set<String> = emptySet()): List<VideoItem> {
        val history = playbackDao.recent(HISTORY_WINDOW)
        val subs = subscriptionDao.all()
        if (history.isEmpty() && subs.isEmpty()) return emptyList()
        val watched = playbackDao.allIds().toSet()
        val affinity = affinity(history.map { it.uploaderUrl }, subs.map { it.channelUrl })

        val recent = history.take(RELATED_SOURCES).map { it.videoId }
        val related = cache.related(recent)
        val channels = topChannels(affinity)
        val channelVideos = channelFeed.cached(channels)

        return withContext(Dispatchers.Default) {
            val now = System.currentTimeMillis()
            val scores = HashMap<String, Double>()
            val videos = HashMap<String, VideoItem>()
            fun add(v: VideoItem, score: Double) {
                if (v.id in watched) return
                videos.putIfAbsent(v.id, v)
                scores[v.id] = (scores[v.id] ?: 0.0) + score
            }

            recent.forEachIndexed { i, sourceId ->
                val sourceWeight = 2.0 / (1 + i * 0.35)
                related[sourceId]?.forEachIndexed { j, v -> add(v, sourceWeight / (1 + j * 0.2)) }
            }
            channelVideos.forEach { (url, list) ->
                val a = affinity[url] ?: 0.0
                list.take(12).forEach { v ->
                    val ageDays = v.uploadedAtMillis?.let { (now - it) / 86_400_000.0 } ?: 14.0
                    add(v, 0.8 * sqrt(a) * exp(-ageDays / 10.0))
                }
            }

            val random = Random(seed)
            val ranked = scores.mapValues { (id, score) ->
                val channelBonus = videos[id]?.uploaderUrl?.let { affinity[it] }?.let { 0.3 * ln(1 + it) } ?: 0.0
                val seenPenalty = if (id in recentlyShown) 0.25 else 1.0
                (score + channelBonus) * seenPenalty * (0.75 + random.nextDouble() * 0.5)
            }.entries.sortedByDescending { it.value }.map { videos.getValue(it.key) }

            diversify(ranked).take(MAX_RESULTS)
        }
    }

    /**
     * Aggiorna in background le fonti: ultimi video dei canali (se più vecchi di 3 h) e
     * correlati dei video visti che non li hanno ancora in cache (solo su Wi-Fi, max 3).
     * @return true se è cambiato qualcosa.
     */
    suspend fun refreshSources(): Boolean {
        val history = playbackDao.recent(HISTORY_WINDOW)
        val subs = subscriptionDao.all()
        val affinity = affinity(history.map { it.uploaderUrl }, subs.map { it.channelUrl })
        var changed = channelFeed.refresh(topChannels(affinity)) > 0

        if (!youtube.isMetered()) {
            history.take(8).map { it.videoId }
                .filter { !cache.hasRelated(it) }
                .take(3)
                .forEach { id ->
                    runCatching { youtube.videoDetails(id).related }
                        .onSuccess { cache.saveRelated(id, it); changed = true }
                }
        }
        cache.prune(CACHE_MAX_AGE_MS)
        return changed
    }

    private fun affinity(historyChannels: List<String?>, subscriptions: List<String>): Map<String, Double> {
        val a = HashMap<String, Double>()
        subscriptions.forEach { a[it] = (a[it] ?: 0.0) + 3.0 }
        historyChannels.forEachIndexed { i, url ->
            if (url != null) a[url] = (a[url] ?: 0.0) + 1.0 / (1 + i * 0.1)
        }
        return a
    }

    private fun topChannels(affinity: Map<String, Double>): List<String> =
        affinity.entries.sortedByDescending { it.value }.take(MAX_CHANNELS).map { it.key }

    /** Al massimo [PER_CHANNEL] video dello stesso canale nelle prime [DIVERSE_WINDOW] posizioni. */
    private fun diversify(list: List<VideoItem>): List<VideoItem> {
        val out = ArrayList<VideoItem>(list.size)
        val deferred = ArrayList<VideoItem>()
        val perChannel = HashMap<String, Int>()
        for (v in list) {
            val key = v.uploaderUrl ?: v.uploaderName
            val n = perChannel[key] ?: 0
            if (out.size < DIVERSE_WINDOW && n >= PER_CHANNEL) deferred += v
            else { out += v; perChannel[key] = n + 1 }
        }
        return out + deferred
    }

    private companion object {
        const val HISTORY_WINDOW = 60
        const val RELATED_SOURCES = 15
        const val MAX_CHANNELS = 20
        const val MAX_RESULTS = 120
        const val DIVERSE_WINDOW = 40
        const val PER_CHANNEL = 2
        const val CACHE_MAX_AGE_MS = 21L * 24 * 60 * 60 * 1000
    }
}
