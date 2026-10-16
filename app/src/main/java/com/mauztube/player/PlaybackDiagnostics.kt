package com.mauztube.player

import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData

/**
 * Diagnostica leggera della riproduzione (logcat, tag "MauzTubePlayback"): blocchi per
 * buffer vuoto, cambi di qualità, fotogrammi persi. Nessun costo misurabile: scrive solo
 * quando succede qualcosa.
 */
@OptIn(UnstableApi::class)
class PlaybackDiagnostics : AnalyticsListener {
    private var stallStart = 0L
    private var wasReady = false

    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
        val now = System.currentTimeMillis()
        when (state) {
            Player.STATE_BUFFERING -> if (wasReady) {
                stallStart = now
                Log.i(TAG, "STALL start pos=${eventTime.currentPlaybackPositionMs} buffered=${eventTime.totalBufferedDurationMs}ms")
            }
            Player.STATE_READY -> {
                if (stallStart > 0) Log.i(TAG, "STALL end after ${now - stallStart}ms")
                stallStart = 0
                wasReady = true
            }
            else -> wasReady = false
        }
    }

    override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?) {
        Log.i(TAG, "VIDEO ${format.height}p ${format.sampleMimeType} ${format.bitrate / 1000}kbps reuse=${decoderReuseEvaluation?.result}")
    }

    override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
        Log.i(TAG, "DROPPED $droppedFrames frames in ${elapsedMs}ms")
    }

    override fun onLoadCompleted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
        if (loadEventInfo.loadDurationMs > 1_500) {
            Log.i(TAG, "SLOW LOAD ${loadEventInfo.loadDurationMs}ms ${loadEventInfo.bytesLoaded / 1024}KB type=${mediaLoadData.trackType}")
        }
    }

    override fun onBandwidthEstimate(eventTime: AnalyticsListener.EventTime, totalLoadTimeMs: Int, totalBytesLoaded: Long, bitrateEstimate: Long) {
        // silenzioso: solo per eventuale debug
    }

    private companion object { const val TAG = "MauzTubePlayback" }
}
