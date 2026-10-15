package com.mauztube.download

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import com.mauztube.data.local.DownloadDao
import com.mauztube.data.local.DownloadEntity
import com.mauztube.data.model.VideoDetails
import com.mauztube.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Formati scaricabili. */
object DownloadKind {
    const val VIDEO = "video"
    const val MP3 = "mp3"
    const val M4A = "m4a"
}

@Singleton
class DownloadRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: DownloadDao,
    @ApplicationScope private val appScope: CoroutineScope,
) {
    val downloads: Flow<List<DownloadEntity>> = dao.observeAll()

    fun enqueue(details: VideoDetails, kind: String, quality: Int, label: String) {
        appScope.launch {
            dao.insert(
                DownloadEntity(
                    videoId = details.id,
                    title = details.title,
                    uploaderName = details.uploaderName,
                    thumbnailUrl = details.thumbnailUrl,
                    kind = kind,
                    quality = quality,
                    qualityLabel = label,
                    status = Status.QUEUED,
                    progress = 0f,
                    fileUri = null,
                    fileName = null,
                    sizeBytes = -1,
                    error = null,
                    createdAt = System.currentTimeMillis(),
                )
            )
            startService()
        }
    }

    fun retry(item: DownloadEntity) = appScope.launch {
        dao.upsert(item.copy(status = Status.QUEUED, progress = 0f, error = null))
        startService()
    }

    /** Elimina la voce e il file scaricato. */
    fun delete(item: DownloadEntity) = appScope.launch {
        item.fileUri?.let { uri -> runCatching { context.contentResolver.delete(Uri.parse(uri), null, null) } }
        dao.delete(item.id)
    }

    private fun startService() {
        ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
    }

    object Status {
        const val QUEUED = "QUEUED"
        const val RUNNING = "RUNNING"
        const val DONE = "DONE"
        const val FAILED = "FAILED"
    }
}
