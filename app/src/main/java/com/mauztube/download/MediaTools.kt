package com.mauztube.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.mauztube.player.YouTubeStreamRequest
import de.sciss.jump3r.lowlevel.LameEncoder
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import javax.sound.sampled.AudioFormat

/**
 * Scarica uno stream googlevideo a blocchi da [CHUNK] byte con richieste "Range" limitate:
 * YouTube rallenta molto le richieste aperte, mentre i blocchi viaggiano a piena velocità.
 * Supporta la ripresa (riparte dalla dimensione già scritta).
 */
class StreamDownloader(private val http: OkHttpClient) {

    suspend fun download(url: String, totalSize: Long, out: File, onProgress: (Long, Long) -> Unit) {
        val req = YouTubeStreamRequest.forUrl(url)
        RandomAccessFile(out, "rw").use { raf ->
            var pos = raf.length()
            raf.seek(pos)
            var total = totalSize
            val buffer = ByteArray(64 * 1024)
            while (total < 0 || pos < total) {
                currentCoroutineContext().ensureActive()
                val end = if (total > 0) minOf(pos + CHUNK, total) - 1 else pos + CHUNK - 1
                val builder = Request.Builder().url(url).header("Range", "bytes=$pos-$end")
                req.headers.forEach { (k, v) -> builder.header(k, v) }
                if (req.postBody != null) builder.post(req.postBody.toRequestBody())
                http.newCall(builder.build()).execute().use { r ->
                    if (r.code == 416) return // già completo
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    if (total < 0) {
                        total = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1
                    }
                    val body = r.body.byteStream()
                    var read = 0
                    var got = 0L
                    while (body.read(buffer).also { read = it } != -1) {
                        raf.write(buffer, 0, read)
                        got += read
                        pos += read
                        onProgress(pos, total)
                    }
                    if (got == 0L) return
                    if (total < 0 && got < CHUNK) return
                }
            }
        }
    }

    companion object { const val CHUNK = 8L * 1024 * 1024 }
}

/**
 * Riscrive le tracce in un MP4/M4A standard SENZA ricodifica (MediaMuxer di Android):
 * i file DASH di YouTube sono MP4 frammentati che alcuni lettori non gestiscono bene.
 * Copiare i campioni è velocissimo e quasi non usa CPU.
 */
object Muxer {
    suspend fun mux(inputs: List<File>, output: File, onProgress: (Float) -> Unit) {
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val extractors = mutableListOf<Pair<MediaExtractor, Int>>()
        try {
            var durationUs = 1L
            for (file in inputs) {
                val ex = MediaExtractor().apply { setDataSource(file.absolutePath) }
                for (t in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(t)
                    val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                        ex.selectTrack(t)
                        val dst = muxer.addTrack(f)
                        extractors += ex to dst
                        if (f.containsKey(MediaFormat.KEY_DURATION)) durationUs = maxOf(durationUs, f.getLong(MediaFormat.KEY_DURATION))
                        break
                    }
                }
            }
            if (extractors.isEmpty()) throw IOException("Nessuna traccia da unire")
            muxer.start()
            val buf = ByteBuffer.allocate(2 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            extractors.forEachIndexed { i, (ex, dst) ->
                while (true) {
                    currentCoroutineContext().ensureActive()
                    info.offset = 0
                    info.size = ex.readSampleData(buf, 0)
                    if (info.size < 0) break
                    info.presentationTimeUs = ex.sampleTime
                    info.flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    muxer.writeSampleData(dst, buf, info)
                    ex.advance()
                    onProgress(((i + info.presentationTimeUs.toFloat() / durationUs) / extractors.size).coerceIn(0f, 1f))
                }
            }
            muxer.stop()
        } finally {
            runCatching { muxer.release() }
            extractors.forEach { runCatching { it.first.release() } }
        }
    }
}

/**
 * Audio → MP3: decodifica con il decoder hardware/di sistema (MediaCodec) e codifica MP3 con
 * LAME (jump3r, Java puro). È il passaggio più pesante: gira in background a bassa priorità.
 */
object Mp3Encoder {
    suspend fun encode(input: File, output: File, bitrateKbps: Int, onProgress: (Float) -> Unit) {
        val ex = MediaExtractor().apply { setDataSource(input.absolutePath) }
        val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        ex.selectTrack(track)
        val format = ex.getTrackFormat(track)
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 1L
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, null, null, 0)
        codec.start()

        var encoder: LameEncoder? = null
        var pcmBuf = ByteArray(0)
        var mp3Buf = ByteArray(0)
        var pending = ByteArray(0)
        var pendingLen = 0
        val info = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false

        output.outputStream().buffered(256 * 1024).use { out ->
            fun encodePcm(data: ByteArray, len: Int) {
                val enc = encoder ?: return
                // L'encoder vuole blocchi di dimensione fissa: si accumula e si codifica a pezzi.
                var offset = 0
                while (offset < len) {
                    val n = minOf(pcmBuf.size - pendingLen, len - offset)
                    System.arraycopy(data, offset, pending, pendingLen, n)
                    pendingLen += n
                    offset += n
                    if (pendingLen == pcmBuf.size) {
                        val written = enc.encodeBuffer(pending, 0, pendingLen, mp3Buf)
                        if (written > 0) out.write(mp3Buf, 0, written)
                        pendingLen = 0
                    }
                }
            }
            try {
                while (!outputDone) {
                    currentCoroutineContext().ensureActive()
                    if (!inputDone) {
                        val inIdx = codec.dequeueInputBuffer(10_000)
                        if (inIdx >= 0) {
                            val inBuf = codec.getInputBuffer(inIdx)!!
                            val size = ex.readSampleData(inBuf, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIdx, 0, size, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }
                    val outIdx = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || (outIdx >= 0 && encoder == null) -> {
                            val of = codec.outputFormat
                            val rate = of.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            val channels = of.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceIn(1, 2)
                            if (encoder == null) {
                                val src = AudioFormat(AudioFormat.Encoding.PCM_SIGNED, rate.toFloat(), 16, channels, channels * 2, rate.toFloat(), false)
                                encoder = LameEncoder(
                                    src, bitrateKbps,
                                    if (channels == 1) LameEncoder.CHANNEL_MODE_MONO else LameEncoder.CHANNEL_MODE_JOINT_STEREO,
                                    LameEncoder.QUALITY_MIDDLE, false,
                                ).also {
                                    pcmBuf = ByteArray(it.getPCMBufferSize())
                                    pending = ByteArray(it.getPCMBufferSize())
                                    mp3Buf = ByteArray(it.getMP3BufferSize())
                                }
                            }
                            if (outIdx >= 0) handleOutput(codec, outIdx, info, ::encodePcm, onProgress, durationUs).also { if (it) outputDone = true }
                        }
                        outIdx >= 0 -> if (handleOutput(codec, outIdx, info, ::encodePcm, onProgress, durationUs)) outputDone = true
                    }
                }
                encoder?.let { enc ->
                    if (pendingLen > 0) {
                        val w = enc.encodeBuffer(pending, 0, pendingLen, mp3Buf)
                        if (w > 0) out.write(mp3Buf, 0, w)
                    }
                    val w = enc.encodeFinish(mp3Buf)
                    if (w > 0) out.write(mp3Buf, 0, w)
                }
            } finally {
                runCatching { encoder?.close() }
                runCatching { codec.stop() }
                codec.release()
                ex.release()
            }
        }
    }

    /** @return true a fine stream. */
    private fun handleOutput(
        codec: MediaCodec,
        index: Int,
        info: MediaCodec.BufferInfo,
        sink: (ByteArray, Int) -> Unit,
        onProgress: (Float) -> Unit,
        durationUs: Long,
    ): Boolean {
        val buf = codec.getOutputBuffer(index)!!
        if (info.size > 0) {
            val bytes = ByteArray(info.size)
            buf.position(info.offset)
            buf.get(bytes, 0, info.size)
            sink(bytes, info.size)
            onProgress((info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f))
        }
        codec.releaseOutputBuffer(index, false)
        return info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
    }
}
