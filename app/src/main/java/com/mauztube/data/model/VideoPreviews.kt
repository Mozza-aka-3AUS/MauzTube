package com.mauztube.data.model

/**
 * Anteprime (titolo, miniatura, canale) dei video toccati in una lista: il player le mostra
 * subito mentre l'estrazione è in corso, così l'apertura risulta immediata.
 */
object VideoPreviews {
    private val map = object : LinkedHashMap<String, VideoItem>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, VideoItem>) = size > 200
    }

    @Synchronized fun put(video: VideoItem) { map[video.id] = video }
    @Synchronized fun get(videoId: String): VideoItem? = map[videoId]
}
