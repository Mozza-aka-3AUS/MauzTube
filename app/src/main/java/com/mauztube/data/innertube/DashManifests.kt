package com.mauztube.data.innertube

import java.util.Locale

/**
 * Genera un manifest DASH "SegmentBase" per un singolo formato YouTube a partire dai range
 * di inizializzazione e indice (stesso formato prodotto da NewPipeExtractor, così i manifest
 * si possono unire in un unico manifest adattivo).
 */
object DashManifests {

    fun single(
        url: String,
        itag: Int,
        mimeType: String,
        codecs: String,
        bitrate: Int,
        durationMs: Long,
        initEnd: Long,
        indexStart: Long,
        indexEnd: Long,
        width: Int = 0,
        height: Int = 0,
        fps: Int = 0,
        audioSampleRate: Int = 0,
        audioChannels: Int = 0,
    ): String {
        val isVideo = mimeType.startsWith("video")
        val rep = buildString {
            append("<Representation id=\"").append(itag).append("\" codecs=\"").append(escape(codecs))
            append("\" bandwidth=\"").append(bitrate).append('"')
            if (isVideo) {
                append(" width=\"").append(width).append("\" height=\"").append(height).append('"')
                if (fps > 0) append(" frameRate=\"").append(fps).append('"')
            } else if (audioSampleRate > 0) {
                append(" audioSamplingRate=\"").append(audioSamplingRate(audioSampleRate)).append('"')
            }
            append(" startWithSAP=\"1\" maxPlayoutRate=\"1\">")
            if (!isVideo && audioChannels > 0) {
                append("<AudioChannelConfiguration schemeIdUri=\"urn:mpeg:dash:23003:3:audio_channel_configuration:2011\" value=\"")
                    .append(audioChannels).append("\"/>")
            }
            append("<BaseURL>").append(escape(url)).append("</BaseURL>")
            append("<SegmentBase indexRange=\"").append(indexStart).append('-').append(indexEnd).append("\">")
            append("<Initialization range=\"0-").append(initEnd).append("\"/></SegmentBase>")
            append("</Representation>")
        }
        val seconds = String.format(Locale.ROOT, "%.3f", durationMs / 1000.0)
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>" +
            "<MPD xmlns=\"urn:mpeg:DASH:schema:MPD:2011\" mediaPresentationDuration=\"PT${seconds}S\" " +
            "minBufferTime=\"PT1.500S\" profiles=\"urn:mpeg:dash:profile:full:2011\" type=\"static\">" +
            "<Period><AdaptationSet id=\"0\" mimeType=\"$mimeType\" subsegmentAlignment=\"true\">" +
            "<Role schemeIdUri=\"urn:mpeg:DASH:role:2011\" value=\"main\"/>" +
            rep + "</AdaptationSet></Period></MPD>"
    }

    private fun audioSamplingRate(rate: Int) = rate.toString()

    private fun escape(s: String) = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
