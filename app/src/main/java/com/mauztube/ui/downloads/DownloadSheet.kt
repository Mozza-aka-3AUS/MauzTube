package com.mauztube.ui.downloads

import android.Manifest
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mauztube.data.model.VideoDetails
import com.mauztube.download.DownloadKind
import com.mauztube.ui.theme.AppIcons
import com.mauztube.util.tr
import java.util.Locale

private data class DownloadChoice(val kind: String, val quality: Int, val label: String, val detail: String)

/** Scelta formato e qualità: video MP4 (H.264 + AAC) o audio MP3 / M4A. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadSheet(
    details: VideoDetails,
    onDownload: (kind: String, quality: Int, label: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var pending by remember { mutableStateOf<DownloadChoice?>(null) }

    fun start(c: DownloadChoice) {
        onDownload(c.kind, c.quality, c.label)
        Toast.makeText(context, tr("Download started", "Download avviato"), Toast.LENGTH_SHORT).show()
        onDismiss()
    }
    // Permessi: notifiche (Android 13+) per l'avanzamento; memoria solo su Android ≤ 9.
    val permissions = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        val storageOk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q || granted[Manifest.permission.WRITE_EXTERNAL_STORAGE] == true
        val c = pending ?: return@rememberLauncherForActivityResult
        if (storageOk) start(c)
        else Toast.makeText(context, tr("Storage permission is needed to save the file", "Serve il permesso di archiviazione per salvare il file"), Toast.LENGTH_LONG).show()
    }
    fun choose(c: DownloadChoice) {
        val missing = permissions.filter {
            androidx.core.content.ContextCompat.checkSelfPermission(context, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) start(c) else { pending = c; launcher.launch(missing.toTypedArray()) }
    }

    val videoChoices = remember(details) { videoChoices(details) }
    val audioChoices = remember(details) { audioChoices(details) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Text(tr("Download", "Scarica"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 20.dp))
            PrimaryTabRow(selectedTabIndex = tab, modifier = Modifier.padding(top = 8.dp)) {
                Tab(tab == 0, onClick = { tab = 0 }, text = { Text(tr("Video (MP4)", "Video (MP4)")) })
                Tab(tab == 1, onClick = { tab = 1 }, text = { Text(tr("Audio (MP3)", "Audio (MP3)")) })
            }
            val list = if (tab == 0) videoChoices else audioChoices
            if (list.isEmpty()) {
                Text(
                    tr("No downloadable format for this video", "Nessun formato scaricabile per questo video"),
                    modifier = Modifier.padding(20.dp),
                )
            }
            list.forEach { c ->
                Row(
                    Modifier.fillMaxWidth().clickable { choose(c) }.padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(AppIcons.Download, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.label, style = MaterialTheme.typography.bodyLarge)
                        if (c.detail.isNotEmpty()) {
                            Text(c.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Text(
                tr("Files are saved in Download/MauzTube.", "I file vengono salvati in Download/MauzTube."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
    }
}

private fun mb(bytes: Long) = if (bytes <= 0) "" else String.format(Locale.ROOT, "~%.0f MB", bytes / 1_048_576.0).let {
    if (bytes < 10 * 1_048_576) String.format(Locale.ROOT, "~%.1f MB", bytes / 1_048_576.0) else it
}

/** Qualità MP4 (H.264, fino a 1080p) + l'eventuale file unico a 360p. */
private fun videoChoices(d: VideoDetails): List<DownloadChoice> {
    val audio = d.streams.audioOnly.filter { it.mimeType?.contains("mp4") == true }.maxByOrNull { it.bitrate }
    val audioSize = audio?.contentLength?.coerceAtLeast(0) ?: 0
    val adaptive = if (audio == null) emptyList() else d.streams.videoOnly
        .filter { it.mimeType?.contains("mp4") == true && it.codec?.startsWith("avc") != false && it.height > 0 }
        .sortedWith(compareByDescending<com.mauztube.data.model.StreamOption> { it.height }.thenByDescending { it.fps })
        .distinctBy { it.height }
        .map { v ->
            val fps = if (v.fps > 30) " ${v.fps}fps" else ""
            DownloadChoice(DownloadKind.VIDEO, v.height, "${v.height}p$fps", mb(v.contentLength.takeIf { it > 0 }?.plus(audioSize) ?: -1))
        }
    val muxed = d.streams.muxed.filter { it.mimeType?.contains("mp4") == true }.maxByOrNull { it.height }?.let { m ->
        DownloadChoice(
            DownloadKind.VIDEO, -1, "${m.height}p",
            listOf(tr("single file, fastest", "file unico, più veloce"), mb(m.contentLength)).filter { it.isNotEmpty() }.joinToString(" · "),
        )
    }
    return adaptive + listOfNotNull(muxed)
}

/** MP3 a 320/192/128 kbps (ricodificati) e l'M4A originale (nessuna ricodifica). */
private fun audioChoices(d: VideoDetails): List<DownloadChoice> {
    if (d.streams.audioOnly.isEmpty()) return emptyList()
    val seconds = d.durationSeconds.coerceAtLeast(0)
    val mp3 = listOf(320, 192, 128).map { kbps ->
        DownloadChoice(DownloadKind.MP3, kbps, "MP3 · $kbps kbps", mb(seconds * kbps * 1000 / 8))
    }
    val m4a = d.streams.audioOnly.filter { it.mimeType?.contains("mp4") == true }.maxByOrNull { it.bitrate }?.let {
        DownloadChoice(
            DownloadKind.M4A, it.bitrate / 1000, "M4A · ${it.bitrate / 1000} kbps",
            listOf(tr("original quality, no re-encoding", "qualità originale, senza ricodifica"), mb(it.contentLength)).filter { s -> s.isNotEmpty() }.joinToString(" · "),
        )
    }
    return mp3 + listOfNotNull(m4a)
}
