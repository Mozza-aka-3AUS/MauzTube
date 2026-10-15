package com.mauztube.ui.queue

import com.mauztube.util.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.mauztube.data.model.VideoItem
import com.mauztube.player.PlaybackManager
import com.mauztube.ui.theme.AppIcons
import com.mauztube.util.Format

/**
 * Barra della coda: compare solo se ci sono video in coda. Si apre con un tocco o
 * trascinandola verso l'alto, da qualsiasi schermata dell'app.
 */
@Composable
fun QueueBar(manager: PlaybackManager, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val queue by manager.queue.collectAsStateWithLifecycle()
    if (queue.isEmpty()) return
    var drag by remember { mutableFloatStateOf(0f) }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = { if (drag < -40f) onOpen(); drag = 0f },
                    onDragCancel = { drag = 0f },
                    onVerticalDrag = { change, amount -> change.consume(); drag += amount },
                )
            }
            .clickable(onClick = onOpen),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            // Maniglia: suggerisce il gesto di trascinamento verso l'alto.
            Box(
                Modifier.align(Alignment.CenterHorizontally).padding(bottom = 6.dp).size(36.dp, 4.dp)
                    .clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.outline)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Queue, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        tr("Queue · ${queue.size} videos", "Coda · ${queue.size} video"),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        tr("Next: ${queue.first().title}", "Prossimo: ${queue.first().title}"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(Icons.Default.KeyboardArrowUp, tr("Open queue", "Apri la coda"))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueSheet(manager: PlaybackManager, onDismiss: () -> Unit) {
    val queue by manager.queue.collectAsStateWithLifecycle()
    // A coda esaurita il pannello si chiude da solo.
    LaunchedEffect(queue.isEmpty()) { if (queue.isEmpty()) onDismiss() }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
    ) {
        Column(Modifier.navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(tr("Queue", "Coda"), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { manager.clearQueue() }) { Text(tr("Clear", "Svuota")) }
            }
            Text(
                tr("Tap a video to play it now, move it or remove it.", "Tocca un video per riprodurlo subito, spostarlo o toglierlo."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            HorizontalDivider(Modifier.padding(top = 8.dp))
            LazyColumn(Modifier.heightIn(max = 520.dp)) {
                itemsIndexed(queue, key = { _, v -> v.id }) { index, video ->
                    QueueRow(
                        index = index,
                        video = video,
                        last = index == queue.lastIndex,
                        onPlay = { manager.playFromQueue(index) },
                        onMove = { to -> manager.moveInQueue(index, to) },
                        onRemove = { manager.removeFromQueue(index) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueRow(
    index: Int,
    video: VideoItem,
    last: Boolean,
    onPlay: () -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth().clickable { menu = true }.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${index + 1}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(24.dp),
            )
            Box(Modifier.width(120.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                AsyncImage(video.thumbnailUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(video.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(video.uploaderName, Format.duration(video.durationSeconds)).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(tr("Play now", "Riproduci ora")) },
                leadingIcon = { Icon(Icons.Default.PlayArrow, null) },
                onClick = { menu = false; onPlay() },
            )
            if (index > 0) {
                DropdownMenuItem(
                    text = { Text(tr("Move to top", "Sposta in cima")) },
                    leadingIcon = { Icon(AppIcons.MoveTop, null) },
                    onClick = { menu = false; onMove(0) },
                )
                DropdownMenuItem(
                    text = { Text(tr("Move up", "Sposta su")) },
                    leadingIcon = { Icon(AppIcons.ArrowUp, null) },
                    onClick = { menu = false; onMove(index - 1) },
                )
            }
            if (!last) {
                DropdownMenuItem(
                    text = { Text(tr("Move down", "Sposta giù")) },
                    leadingIcon = { Icon(AppIcons.ArrowDown, null) },
                    onClick = { menu = false; onMove(index + 1) },
                )
            }
            DropdownMenuItem(
                text = { Text(tr("Remove from queue", "Rimuovi dalla coda")) },
                leadingIcon = { Icon(Icons.Default.Delete, null) },
                onClick = { menu = false; onRemove() },
            )
        }
    }
}
