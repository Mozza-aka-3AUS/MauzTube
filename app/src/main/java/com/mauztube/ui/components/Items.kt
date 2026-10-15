package com.mauztube.ui.components

import com.mauztube.util.tr

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import kotlinx.coroutines.delay
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.mauztube.data.model.ChannelItem
import com.mauztube.data.model.ContentItem
import com.mauztube.data.model.VideoItem
import com.mauztube.data.model.VideoPreviews
import com.mauztube.ui.theme.AppIcons
import com.mauztube.ui.theme.BrandRed
import com.mauztube.util.Format
import com.mauztube.util.Share

private val ThumbShape = RoundedCornerShape(12.dp)

@Composable
fun VideoCard(
    video: VideoItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    progress: Float? = null,
    extraLine: String? = null,
    /** Mostra l'icona "Aggiungi in coda" direttamente sulla card (es. "Prossimi video"). */
    showQueueButton: Boolean = false,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(ThumbShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            // Decodifica a 640×360: le miniature sorgente sono ≤ 480 px di altezza, quindi
            // ingrandirle alla larghezza dello schermo non aggiunge dettaglio ma costa memoria
            // e tempo di caricamento sulla GPU durante lo scorrimento.
            val context = LocalContext.current
            val request = remember(video.thumbnailUrl) {
                coil3.request.ImageRequest.Builder(context).data(video.thumbnailUrl).size(640, 360).build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            val badge = if (video.isLive) "LIVE" else Format.duration(video.durationSeconds)
            if (badge.isNotEmpty()) {
                Text(
                    badge,
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .background(
                            if (video.isLive) BrandRed else Color.Black.copy(alpha = 0.78f),
                            RoundedCornerShape(6.dp),
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            if (progress != null && progress > 0f) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color.Gray.copy(alpha = 0.6f)))
                Box(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth(progress.coerceIn(0f, 1f))
                        .height(4.dp).background(BrandRed)
                )
            }
        }
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Top) {
            Avatar(video.uploaderAvatarUrl, video.uploaderName, 36)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    video.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val views = video.viewText ?: Format.views(video.viewCount)
                val meta = listOf(video.uploaderName, views, video.textualUploadDate.orEmpty())
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                Text(
                    meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
                if (extraLine != null) {
                    Text(
                        extraLine,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            if (showQueueButton) {
                val actions = LocalVideoActions.current
                IconButton(onClick = { actions?.enqueue(video) }, modifier = Modifier.size(36.dp)) {
                    Icon(AppIcons.AddToQueue, tr("Add to queue", "Aggiungi in coda"), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            VideoMenu(video)
        }
    }
}

/** Menu ⋮ della card: Guarda più tardi e Condividi. */
@Composable
fun VideoMenu(video: VideoItem) {
    val actions = LocalVideoActions.current
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.MoreVert, tr("More", "Altro"), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (actions != null) {
                val saved = video.id in actions.watchLaterIds.collectAsState().value
                DropdownMenuItem(
                    text = { Text(if (saved) tr("Remove from Watch later", "Rimuovi da Guarda più tardi") else tr("Save to Watch later", "Salva in Guarda più tardi")) },
                    leadingIcon = { Icon(AppIcons.WatchLater, null) },
                    onClick = { open = false; actions.toggleWatchLater(video) },
                )
            }
            if (actions != null) {
                DropdownMenuItem(
                    text = { Text(tr("Add to queue", "Aggiungi alla coda")) },
                    leadingIcon = { Icon(AppIcons.AddToQueue, null) },
                    onClick = { open = false; actions.enqueue(video) },
                )
                DropdownMenuItem(
                    text = { Text(tr("Save to playlist", "Salva in playlist")) },
                    leadingIcon = { Icon(AppIcons.PlaylistAdd, null) },
                    onClick = { open = false; actions.saveToPlaylist(video) },
                )
            }
            DropdownMenuItem(
                text = { Text(tr("Share", "Condividi")) },
                leadingIcon = { Icon(Icons.Default.Share, null) },
                onClick = { open = false; Share.video(context, video.title, video.id) },
            )
        }
    }
}

@Composable
fun Avatar(url: String?, name: String, sizeDp: Int) {
    Box(
        Modifier.size(sizeDp.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().take(1).uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        if (url != null) {
            // Gli avatar sono piccoli: decodifica alla loro dimensione reale.
            val context = LocalContext.current
            val px = with(androidx.compose.ui.platform.LocalDensity.current) { sizeDp.dp.roundToPx() }
            val request = remember(url, px) { coil3.request.ImageRequest.Builder(context).data(url).size(px, px).build() }
            AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
fun ChannelRow(channel: ChannelItem, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(channel.avatarUrl, channel.name, 64)
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(channel.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            if (channel.subscriberCount >= 0) {
                Text(
                    tr("${Format.count(channel.subscriberCount)} subscribers", "${Format.count(channel.subscriberCount)} iscritti"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------------- Stati di caricamento ----------------

/** Scheletro animato: dà subito la forma della pagina mentre i dati arrivano. */
@Composable
fun SkeletonList(modifier: Modifier = Modifier, count: Int = 4) {
    val transition = rememberInfiniteTransition(label = "skeleton")
    val alpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "alpha",
    )
    val color = MaterialTheme.colorScheme.surfaceVariant
    Column(modifier.fillMaxSize().alpha(alpha)) {
        repeat(count) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(ThumbShape).background(color))
                Row(Modifier.padding(top = 10.dp)) {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(color))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Box(Modifier.fillMaxWidth(0.9f).height(14.dp).clip(RoundedCornerShape(4.dp)).background(color))
                        Spacer(Modifier.height(6.dp))
                        Box(Modifier.fillMaxWidth(0.55f).height(12.dp).clip(RoundedCornerShape(4.dp)).background(color))
                    }
                }
            }
        }
    }
}

@Composable
fun LoadingView(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

@Composable
fun MessageView(
    message: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    actionLabel: String = tr("Retry", "Riprova"),
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (title != null) {
            Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
        }
        Text(
            message,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onAction != null) {
            Spacer(Modifier.height(20.dp))
            FilledTonalButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/** Lista di contenuti con paginazione infinita e stati di caricamento/errore. */
@Composable
fun ContentList(
    state: ListUiState<out ContentItem>,
    onVideo: (VideoItem) -> Unit,
    onChannel: (ChannelItem) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    emptyMessage: String = tr("Nothing here", "Nessun contenuto"),
    header: (LazyListScope.() -> Unit)? = null,
    listState: LazyListState = rememberLazyListState(),
) {
    if (state.items.isEmpty()) {
        when {
            state.loading -> if (header != null) {
                LazyColumn(modifier.fillMaxSize()) {
                    header()
                    item { SkeletonList(Modifier.height(900.dp), count = 3) }
                }
            } else SkeletonList(modifier)
            state.error != null -> MessageView(tr("Error: ${state.error}", "Errore: ${state.error}"), modifier, onAction = onRetry)
            header != null -> LazyColumn(modifier.fillMaxSize()) {
                header()
                item { MessageView(emptyMessage, Modifier.height(240.dp)) }
            }
            else -> MessageView(emptyMessage, modifier)
        }
        return
    }

    // Prefetch "a riposo": se lo scroll si ferma e dei video restano visibili per 1,2 s,
    // vengono estratti in anticipo (max 2, solo su Wi-Fi): toccarli li apre subito.
    val actions = LocalVideoActions.current
    LaunchedEffect(listState.isScrollInProgress, state.items.size) {
        if (listState.isScrollInProgress || actions == null) return@LaunchedEffect
        delay(1_200)
        val visible = listState.layoutInfo.visibleItemsInfo
            .filter { it.offset >= 0 && it.offset + it.size <= listState.layoutInfo.viewportEndOffset }
            .mapNotNull { info -> state.items.firstOrNull { it.url == info.key } as? VideoItem }
        if (visible.isNotEmpty()) actions.prefetchVisible(visible.map { it.id })
    }
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 4
        }
    }
    LaunchedEffect(nearEnd, state.canLoadMore, state.items.size) {
        if (nearEnd && state.canLoadMore && !state.loading && state.error == null) onLoadMore()
    }

    LazyColumn(modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
        header?.invoke(this)
        items(state.items, key = { it.url }, contentType = { if (it is VideoItem) 0 else 1 }) { item ->
            when (item) {
                is VideoItem -> VideoCard(item, onClick = { VideoPreviews.put(item); onVideo(item) })
                is ChannelItem -> ChannelRow(item, onClick = { onChannel(item) })
            }
        }
        if (state.loading) {
            item { Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) { CircularProgressIndicator() } }
        }
        if (state.error != null) {
            item {
                Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(tr("Error: ${state.error}", "Errore: ${state.error}"), color = MaterialTheme.colorScheme.error)
                    FilledTonalButton(onClick = onLoadMore) { Text(tr("Retry", "Riprova")) }
                }
            }
        }
    }
}
