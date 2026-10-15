package com.mauztube.ui.downloads

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.mauztube.data.local.DownloadEntity
import com.mauztube.download.DownloadKind
import com.mauztube.download.DownloadRepository
import com.mauztube.download.DownloadRepository.Status
import com.mauztube.ui.components.MessageView
import com.mauztube.util.tr
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class DownloadsViewModel @Inject constructor(private val repo: DownloadRepository) : ViewModel() {
    val downloads: StateFlow<List<DownloadEntity>?> = repo.downloads
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    fun retry(d: DownloadEntity) = repo.retry(d)
    fun delete(d: DownloadEntity) = repo.delete(d)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(onBack: () -> Unit, vm: DownloadsViewModel = hiltViewModel()) {
    val list = vm.downloads.collectAsStateWithLifecycle().value
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr("Downloads", "Download")) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back", "Indietro")) } },
            )
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { padding ->
        when {
            list == null -> Unit
            list.isEmpty() -> MessageView(
                tr("Use \"Download\" under a video to save it as MP4 or MP3.", "Usa \"Scarica\" sotto un video per salvarlo in MP4 o MP3."),
                Modifier.padding(padding),
                title = tr("No downloads", "Nessun download"),
            )
            else -> LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                items(list, key = { it.id }) { d ->
                    DownloadRow(
                        d,
                        onOpen = {
                            d.fileUri?.let { uri ->
                                val mime = if (d.kind == DownloadKind.VIDEO) "video/mp4" else if (d.kind == DownloadKind.MP3) "audio/mpeg" else "audio/mp4"
                                runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), mime)
                                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            }
                        },
                        onShare = {
                            d.fileUri?.let { uri ->
                                val send = Intent(Intent.ACTION_SEND).setType(if (d.kind == DownloadKind.VIDEO) "video/mp4" else "audio/*")
                                    .putExtra(Intent.EXTRA_STREAM, Uri.parse(uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        },
                        onRetry = { vm.retry(d) },
                        onDelete = { vm.delete(d) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DownloadRow(d: DownloadEntity, onOpen: () -> Unit, onShare: () -> Unit, onRetry: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(112.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            AsyncImage(d.thumbnailUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(d.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val format = when (d.kind) { DownloadKind.VIDEO -> "MP4 · ${d.qualityLabel}"; else -> d.qualityLabel }
            val status = when (d.status) {
                Status.QUEUED -> tr("Waiting", "In attesa")
                Status.RUNNING -> "${(d.progress * 100).toInt()}%"
                Status.DONE -> if (d.sizeBytes > 0) String.format(Locale.ROOT, "%.1f MB", d.sizeBytes / 1_048_576.0) else tr("Done", "Completato")
                else -> tr("Failed", "Non riuscito") + (d.error?.let { ": $it" } ?: "")
            }
            Text("$format · $status", style = MaterialTheme.typography.bodySmall, color = if (d.status == Status.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            if (d.status == Status.RUNNING) {
                LinearProgressIndicator(progress = { d.progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        }
        when (d.status) {
            Status.DONE -> {
                IconButton(onClick = onOpen) { Icon(Icons.Default.PlayArrow, tr("Open", "Apri")) }
                IconButton(onClick = onShare) { Icon(Icons.Default.Share, tr("Share", "Condividi")) }
            }
            Status.FAILED -> IconButton(onClick = onRetry) { Icon(Icons.Default.Refresh, tr("Retry", "Riprova")) }
            else -> Unit
        }
        IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, tr("Delete", "Elimina")) }
    }
}
