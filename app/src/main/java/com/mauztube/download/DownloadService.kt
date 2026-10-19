package com.mauztube.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.Process
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.FileProvider
import com.mauztube.MainActivity
import com.mauztube.data.local.DownloadDao
import com.mauztube.data.local.DownloadEntity
import com.mauztube.data.model.StreamOption
import com.mauztube.data.model.VideoDetails
import com.mauztube.data.repository.YouTubeRepository
import com.mauztube.download.DownloadRepository.Status
import com.mauztube.util.tr
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import javax.inject.Inject

/**
 * Esegue i download uno alla volta, in primo piano con notifica di avanzamento.
 * Fasi: scaricamento a blocchi → (unione MP4 | conversione MP3 | riscrittura M4A) →
 * salvataggio in Download/MauzTube. I file temporanei stanno nella cache e vengono rimossi.
 */
@AndroidEntryPoint
class DownloadService : Service() {

    @Inject lateinit var dao: DownloadDao
    @Inject lateinit var youtube: YouTubeRepository
    @Inject lateinit var http: OkHttpClient

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + com.mauztube.util.SafeHandler)
    private var worker: Job? = null
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, progressNotification(tr("Preparing download…", "Preparazione download…"), null),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        if (worker?.isActive != true) {
            worker = scope.launch {
                dao.requeueInterrupted()
                while (true) {
                    val next = dao.nextQueued() ?: break
                    process(next)
                }
                ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ---------------- Elaborazione ----------------

    private suspend fun process(item: DownloadEntity) {
        dao.upsert(item.copy(status = Status.RUNNING, progress = 0f, error = null))
        val tmp = File(cacheDir, "downloads/${item.id}").apply { mkdirs() }
        try {
            val result = runCatching { run(item, tmp, forceRefresh = false) }.recoverCatching { e ->
                // URL scaduti/negati (403): nuova estrazione e un secondo tentativo.
                if (e is IOException && e.message?.contains("403") == true) run(item, tmp, forceRefresh = true) else throw e
            }.getOrThrow()
            dao.upsert(item.copy(status = Status.DONE, progress = 1f, fileUri = result.first, fileName = result.second, sizeBytes = result.third, error = null))
            notifyDone(item, result.second)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            dao.upsert(item.copy(status = Status.FAILED, error = e.message ?: e.javaClass.simpleName))
        } finally {
            tmp.deleteRecursively()
        }
    }

    /** @return (uri, nome file, dimensione). */
    private suspend fun run(item: DownloadEntity, tmp: File, forceRefresh: Boolean): Triple<String, String, Long> {
        val d = youtube.videoDetails(item.videoId, forceRefresh)
        val downloader = StreamDownloader(http)
        val base = safeName(d.title)

        return when (item.kind) {
            DownloadKind.VIDEO -> {
                val muxed = item.quality < 0
                if (muxed) {
                    val s = d.streams.muxed.filter { it.isMp4() }.maxByOrNull { it.height } ?: throw IOException(noFormat())
                    val f = File(tmp, "muxed.mp4")
                    downloadWithProgress(item, downloader, s, f, 0f, 1f)
                    publish(f, "$base (${item.qualityLabel}).mp4", "video/mp4")
                } else {
                    val video = pickVideo(d, item.quality) ?: throw IOException(noFormat())
                    val audio = pickAudioM4a(d) ?: throw IOException(noFormat())
                    val total = (video.contentLength.coerceAtLeast(1) + audio.contentLength.coerceAtLeast(1)).toFloat()
                    val vShare = 0.9f * video.contentLength.coerceAtLeast(1) / total
                    val vf = File(tmp, "video.mp4")
                    val af = File(tmp, "audio.m4a")
                    downloadWithProgress(item, downloader, video, vf, 0f, vShare)
                    downloadWithProgress(item, downloader, audio, af, vShare, 0.9f)
                    val out = File(tmp, "out.mp4")
                    Muxer.mux(listOf(vf, af), out) { p -> progress(item, 0.9f + 0.1f * p, tr("Merging…", "Unione…")) }
                    publish(out, "$base (${item.qualityLabel}).mp4", "video/mp4")
                }
            }
            DownloadKind.M4A -> {
                val audio = pickAudioM4a(d) ?: throw IOException(noFormat())
                val af = File(tmp, "audio.m4a")
                downloadWithProgress(item, downloader, audio, af, 0f, 0.95f)
                val out = File(tmp, "out.m4a")
                Muxer.mux(listOf(af), out) { p -> progress(item, 0.95f + 0.05f * p, null) }
                publish(out, "$base.m4a", "audio/mp4")
            }
            else -> { // MP3
                val audio = pickAudioM4a(d) ?: d.streams.audioOnly.maxByOrNull { it.bitrate } ?: throw IOException(noFormat())
                val af = File(tmp, "audio.src")
                downloadWithProgress(item, downloader, audio, af, 0f, 0.4f)
                val out = File(tmp, "out.mp3")
                // La codifica MP3 usa la CPU: priorità bassa per non disturbare l'interfaccia.
                val oldPriority = Process.getThreadPriority(Process.myTid())
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                try {
                    out.outputStream().use { Id3.writeTag(it, d.title, d.uploaderName) }
                    val body = File(tmp, "body.mp3")
                    Mp3Encoder.encode(af, body, item.quality) { p -> progress(item, 0.4f + 0.6f * p, tr("Converting to MP3…", "Conversione in MP3…")) }
                    java.io.FileOutputStream(out, true).use { o -> body.inputStream().use { it.copyTo(o) } }
                } finally {
                    Process.setThreadPriority(oldPriority)
                }
                publish(out, "$base (${item.quality} kbps).mp3", "audio/mpeg")
            }
        }
    }

    private suspend fun downloadWithProgress(
        item: DownloadEntity, dl: StreamDownloader, s: StreamOption, file: File, from: Float, to: Float,
    ) {
        dl.download(s.url, s.contentLength, file) { done, total ->
            if (total > 0) progress(item, from + (to - from) * done / total, null)
        }
    }

    /** Video MP4 (H.264) della qualità scelta, o la più vicina inferiore. */
    private fun pickVideo(d: VideoDetails, height: Int): StreamOption? {
        val mp4 = d.streams.videoOnly.filter { it.isMp4() && it.codec?.startsWith("avc") != false }
        return mp4.filter { it.height <= height }.maxWithOrNull(compareBy({ it.height }, { it.fps }))
            ?: mp4.minByOrNull { it.height }
    }

    /** Audio AAC (M4A) originale con il bitrate più alto. */
    private fun pickAudioM4a(d: VideoDetails): StreamOption? =
        d.streams.audioOnly.filter { it.isMp4() }
            .sortedWith(compareByDescending<StreamOption> { it.isOriginalAudio }.thenByDescending { it.bitrate })
            .firstOrNull()

    private fun StreamOption.isMp4() = mimeType?.contains("mp4") == true

    // ---------------- Salvataggio ----------------

    /** Copia nella cartella pubblica Download/MauzTube. @return (uri, nome, dimensione). */
    private fun publish(file: File, name: String, mime: String): Triple<String, String, Long> {
        val size = file.length()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/MauzTube")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException(tr("Can't create the file", "Impossibile creare il file"))
            contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
            return Triple(uri.toString(), name, size)
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MauzTube").apply { mkdirs() }
        val target = File(dir, name)
        file.copyTo(target, overwrite = true)
        MediaScannerConnection.scanFile(this, arrayOf(target.absolutePath), arrayOf(mime), null)
        val uri = FileProvider.getUriForFile(this, "$packageName.files", target)
        return Triple(uri.toString(), name, size)
    }

    private fun safeName(title: String) = title.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), " ").trim().take(120).ifEmpty { "video" }

    private fun noFormat() = tr("No downloadable format for this video", "Nessun formato scaricabile per questo video")

    // ---------------- Notifiche ----------------

    /** Avanzamento: notifica e database aggiornati al massimo due volte al secondo. */
    private fun progress(item: DownloadEntity, p: Float, phase: String?) {
        val now = System.currentTimeMillis()
        if (now - lastNotify < 500 && p < 1f) return
        lastNotify = now
        scope.launch { dao.setProgress(item.id, p) }
        NotificationManagerCompat.from(this).runCatching {
            notify(NOTIFICATION_ID, progressNotification(phase ?: item.title, p))
        }
    }

    private fun progressNotification(text: String, progress: Float?) =
        NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(tr("Downloading", "Download in corso"))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp())
            .apply { if (progress != null) setProgress(1000, (progress * 1000).toInt(), false) else setProgress(0, 0, true) }
            .build()

    private fun notifyDone(item: DownloadEntity, name: String) {
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(tr("Download complete", "Download completato"))
            .setContentText(name)
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build()
        runCatching { NotificationManagerCompat.from(this).notify((item.id % 100_000).toInt() + 1000, n) }
    }

    private fun openApp() = PendingIntent.getActivity(
        this, 1, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, tr("Downloads", "Download"), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private companion object {
        const val CHANNEL = "downloads"
        const val NOTIFICATION_ID = 42
    }
}

/** Intestazione ID3v2.3 minima (titolo e artista) per gli MP3. */
object Id3 {
    fun writeTag(out: java.io.OutputStream, title: String, artist: String) {
        val frames = java.io.ByteArrayOutputStream()
        fun frame(id: String, text: String) {
            val body = byteArrayOf(1) + byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)
            frames.write(id.toByteArray(Charsets.ISO_8859_1))
            frames.write(java.nio.ByteBuffer.allocate(4).putInt(body.size).array())
            frames.write(byteArrayOf(0, 0))
            frames.write(body)
        }
        frame("TIT2", title)
        frame("TPE1", artist)
        val size = frames.size()
        // Dimensione "synchsafe": 7 bit per byte.
        val synch = byteArrayOf(
            ((size shr 21) and 0x7F).toByte(), ((size shr 14) and 0x7F).toByte(),
            ((size shr 7) and 0x7F).toByte(), (size and 0x7F).toByte(),
        )
        out.write("ID3".toByteArray(Charsets.ISO_8859_1))
        out.write(byteArrayOf(3, 0, 0))
        out.write(synch)
        frames.writeTo(out)
    }
}
