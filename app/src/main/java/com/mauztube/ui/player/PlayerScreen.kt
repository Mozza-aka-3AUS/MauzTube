package com.mauztube.ui.player

import androidx.compose.animation.togetherWith
import com.mauztube.ui.components.popOnChange
import com.mauztube.util.tr

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.annotation.OptIn
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import com.mauztube.player.PlayerUiState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.fromHtml
import com.mauztube.util.DeepLink
import com.mauztube.util.LinkParser
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import com.mauztube.ui.components.Avatar
import com.mauztube.ui.theme.AppIcons
import com.mauztube.util.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.mauztube.data.model.VideoDetails
import com.mauztube.data.model.VideoPreviews
import com.mauztube.data.repository.SponsorSegment
import com.mauztube.ui.components.LoadingView
import com.mauztube.ui.components.MessageView
import com.mauztube.ui.components.VideoCard
import com.mauztube.util.Format
import kotlinx.coroutines.launch

@Composable
fun PlayerScreen(
    onBack: () -> Unit,
    onVideo: (String) -> Unit,
    onChannel: (String) -> Unit,
    onOpenQueue: () -> Unit,
    vm: PlayerViewModel = hiltViewModel(),
) {
    val queue by vm.queue.collectAsStateWithLifecycle()
    val autoplay by vm.autoplay.collectAsStateWithLifecycle()
    val loop by vm.loop.collectAsStateWithLifecycle()
    val nowPlaying by vm.nowPlaying.collectAsStateWithLifecycle()
    val fullscreen by vm.fullscreen.collectAsStateWithLifecycle()
    val subscribed by vm.subscribed.collectAsStateWithLifecycle()
    val inWatchLater by vm.inWatchLater.collectAsStateWithLifecycle()
    val audioOnly by vm.audioOnly.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Il video mostrato è sempre quello caricato nel PlaybackManager.
    val state = nowPlaying?.takeIf { it.videoId == vm.videoId }?.state ?: PlayerUiState.Loading
    val ready = state as? PlayerUiState.Ready

    // A schermo intero "indietro" esce dallo schermo intero. Altrimenti ci pensa la navigazione
    // con il back predittivo: il video si riduce nel mini player (continua a suonare), e lo
    // stesso meccanismo segue il dito quando lo si trascina verso il basso.
    BackHandler(enabled = fullscreen) { vm.setFullscreen(false) }

    FullscreenEffect(fullscreen)
    val rating by vm.rating.collectAsStateWithLifecycle()
    val votes by vm.votes.collectAsStateWithLifecycle()
    val activity = LocalActivity.current
    var downloadOpen by remember { mutableStateOf(false) }
    var commentsOpen by remember { mutableStateOf(false) }
    val commentsVm: com.mauztube.ui.comments.CommentsViewModel = hiltViewModel()
    val readyDetails = (nowPlaying?.takeIf { it.videoId == vm.videoId }?.state as? PlayerUiState.Ready)?.details
    if (commentsOpen && readyDetails != null) {
        com.mauztube.ui.comments.CommentsSheet(commentsVm, readyDetails.title, readyDetails.thumbnailUrl, onDismiss = { commentsOpen = false })
    }
    if (downloadOpen && readyDetails != null) {
        com.mauztube.ui.downloads.DownloadSheet(readyDetails, onDownload = vm::download, onDismiss = { downloadOpen = false })
    }

    // ---- Notifica skip SponsorBlock con "Annulla" ----
    LaunchedEffect(vm) {
        vm.skipEvents.collect { event ->
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                val result = snackbar.showSnackbar(
                    message = tr("Skipped: ${PlayerViewModel.categoryLabel(event.segment.category)}", "Saltato: ${PlayerViewModel.categoryLabel(event.segment.category)}"),
                    actionLabel = tr("Cancel", "Annulla"),
                    duration = SnackbarDuration.Short,
                )
                if (result == SnackbarResult.ActionPerformed) vm.manager.undoSkip(event)
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = if (fullscreen) Color.Black else MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        // Schermo largo e basso (telefono in orizzontale, tablet): video a sinistra e dettagli
        // a destra, invece di un video più alto dello schermo.
        val window = com.mauztube.ui.components.windowSize()
        val sideBySide = !fullscreen && window.landscape && window.wide
        val playerView: @Composable (Modifier) -> Unit = { playerModifier ->
            PlayerWithControls(
                manager = vm.manager,
                segments = ready?.segments.orEmpty(),
                fullscreen = fullscreen,
                audioOnly = audioOnly,
                coverUrl = ready?.details?.thumbnailUrl ?: vm.preview?.thumbnailUrl,
                loading = state is PlayerUiState.Loading,
                onSetFullscreen = vm::setFullscreen,
                onMinimize = onBack,
                onToggleAudioOnly = vm::toggleAudioOnly,
                queueSize = queue.size,
                onOpenQueue = onOpenQueue,
                storyboard = ready?.details?.storyboard,
                onPip = { (activity as? com.mauztube.MainActivity)?.enterPip() },
                autoplay = autoplay,
                loop = loop,
                onToggleAutoplay = vm::toggleAutoplay,
                onToggleLoop = vm::toggleLoop,
                related = ready?.details?.related.orEmpty(),
                onOpenRelated = { v -> VideoPreviews.put(v); onVideo(v.id) },
                modifier = playerModifier,
            )
        }
        val details: @Composable () -> Unit = {
            when (state) {
                PlayerUiState.Loading -> LoadingPreview(vm)
                is PlayerUiState.Error -> MessageView(
                    state.message,
                    title = tr("Can't play this video", "Impossibile riprodurre"),
                    onAction = if (state.retryable) vm::retry else null,
                )
                is PlayerUiState.Ready -> Details(
                    s = state,
                    subscribed = subscribed,
                    inWatchLater = inWatchLater,
                    audioOnly = audioOnly,
                    onToggleSubscribe = vm::toggleSubscription,
                    onToggleWatchLater = vm::toggleWatchLater,
                    onToggleAudioOnly = vm::toggleAudioOnly,
                    rating = rating,
                    likes = votes?.likes?.takeIf { it >= 0 } ?: state.details.likeCount,
                    dislikes = votes?.dislikes ?: -1,
                    onRate = vm::rate,
                    onPip = { (activity as? com.mauztube.MainActivity)?.enterPip() },
                    onDownload = { downloadOpen = true },
                    commentsSlot = { com.mauztube.ui.comments.CommentsPreview(commentsVm, onOpen = { commentsOpen = true }) },
                    onShare = { fromNow ->
                        Share.video(
                            context, vm.shareTitle(), vm.videoId,
                            if (fromNow) vm.manager.player.currentPosition / 1000 else null,
                        )
                    },
                    onVideo = onVideo,
                    onChannel = onChannel,
                    onSeekTo = { ms -> vm.manager.player.seekTo(ms) },
                )
            }
        }
        // Un solo punto di composizione per il player: cambiando disposizione (schermo intero,
        // affiancato) il video non viene ricreato e non lampeggia.
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            val playerHeight = maxWidth * 9f / 16f
            playerView(
                when {
                    fullscreen -> Modifier.fillMaxSize()
                    sideBySide -> Modifier.fillMaxWidth(0.6f).fillMaxHeight()
                    else -> Modifier.fillMaxWidth().height(playerHeight)
                }
            )
            if (!fullscreen) {
                Column(
                    if (sideBySide) Modifier.fillMaxWidth(0.4f).fillMaxHeight().align(Alignment.TopEnd)
                    else Modifier.fillMaxSize().padding(top = playerHeight)
                ) { details() }
            }
        }
    }
}

@Composable
private fun FullscreenEffect(fullscreen: Boolean) {
    val activity: Activity = LocalActivity.current ?: return
    DisposableEffect(fullscreen) {
        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (fullscreen) {
            // Come il client YouTube: il video (e le sue bande nere) occupa tutto lo schermo,
            // anche la zona del foro/notch della fotocamera; i controlli restano fuori da lì.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
            } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                window.attributes = window.attributes.apply {
                    layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }
}

@Composable
private fun Details(
    s: PlayerUiState.Ready,
    subscribed: Boolean,
    inWatchLater: Boolean,
    audioOnly: Boolean,
    onToggleSubscribe: () -> Unit,
    onToggleWatchLater: () -> Unit,
    onToggleAudioOnly: () -> Unit,
    rating: Int,
    likes: Long,
    dislikes: Long,
    onRate: (Int) -> Unit,
    onPip: () -> Unit,
    onDownload: () -> Unit,
    commentsSlot: @Composable () -> Unit,
    onShare: (fromCurrentTime: Boolean) -> Unit,
    onVideo: (String) -> Unit,
    onChannel: (String) -> Unit,
    onSeekTo: (Long) -> Unit,
) {
    val d = s.details
    var expanded by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "info") {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Text(d.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    listOf(Format.views(d.viewCount), d.uploadedAtMillis?.let(Format::date) ?: d.textualUploadDate.orEmpty())
                        .filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        item(key = "channel") {
            ChannelBar(d, subscribed, onToggleSubscribe, onChannel)
        }
        item(key = "actions") {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { RatingPill(rating, likes, dislikes, onRate) }
                item {
                    ActionPill(
                        icon = AppIcons.Headphones,
                        label = tr("Audio only", "Solo audio"),
                        selected = audioOnly,
                        onClick = onToggleAudioOnly,
                    )
                }
                item { ActionPill(AppIcons.Download, tr("Download", "Scarica"), onClick = onDownload) }
                item {
                    ActionPill(
                        icon = AppIcons.WatchLater,
                        label = if (inWatchLater) tr("Saved", "Salvato") else tr("Watch later", "Guarda più tardi"),
                        selected = inWatchLater,
                        onClick = onToggleWatchLater,
                    )
                }
                // Salva in qualsiasi playlist (del telefono e dell'account).
                item {
                    val actions = com.mauztube.ui.components.LocalVideoActions.current
                    ActionPill(AppIcons.PlaylistAdd, tr("Save", "Salva"), onClick = {
                        actions?.saveToPlaylist(
                            com.mauztube.data.model.VideoItem(
                                id = d.id, url = d.url, title = d.title, uploaderName = d.uploaderName,
                                uploaderUrl = d.uploaderUrl, thumbnailUrl = d.thumbnailUrl,
                                durationSeconds = d.durationSeconds, viewCount = d.viewCount,
                                textualUploadDate = d.textualUploadDate, uploadedAtMillis = d.uploadedAtMillis,
                                isLive = d.isLive,
                            )
                        )
                    })
                }
                item { ActionPill(AppIcons.PictureInPicture, tr("Pop-up", "Popup"), onClick = onPip) }
                item { ActionPill(Icons.Default.Share, tr("Share", "Condividi"), onClick = { onShare(false) }) }
                item { ActionPill(Icons.Default.Share, tr("From current time", "Dal minuto attuale"), onClick = { onShare(true) }) }
                if (s.segments.isNotEmpty()) {
                    item { ActionPill(Icons.Default.Check, tr("SponsorBlock · ${s.segments.size}", "SponsorBlock · ${s.segments.size}"), onClick = {}) }
                }
                if (s.resumedFromMs > 0) {
                    item { ActionPill(AppIcons.History, tr("Resumed at ${Format.durationMs(s.resumedFromMs)}", "Ripreso da ${Format.durationMs(s.resumedFromMs)}"), onClick = {}) }
                }
            }
        }
        item(key = "description") {
            if (d.description.isNotBlank()) {
                Column(
                    Modifier
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { expanded = !expanded }
                        .animateContentSize()
                        .padding(12.dp)
                ) {
                    Text(tr("Description", "Descrizione"), style = MaterialTheme.typography.labelLarge)
                    DescriptionText(d, expanded, onSeekTo, onVideo)
                    Text(
                        if (expanded) tr("Show less", "Mostra meno") else tr("Show more", "Mostra altro"),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
        if (!d.isLive) item(key = "comments") { commentsSlot() }
        if (d.related.isNotEmpty()) {
            item(key = "next-title") {
                Text(
                    tr("Up next", "Prossimi video"),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 12.dp, top = 12.dp, bottom = 4.dp),
                )
            }
        }
        // YouTube a volte ripete un video tra i correlati: chiavi duplicate farebbero chiudere l'app.
        items(d.related.distinctBy { it.id }, key = { "rel-" + it.id }) { video ->
            VideoCard(video, onClick = { VideoPreviews.put(video); onVideo(video.id) }, showQueueButton = true)
        }
    }
}

@Composable
private fun ActionPill(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    selected: Boolean = false,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg)
    }
}

@Composable
private fun ChannelBar(
    d: VideoDetails,
    subscribed: Boolean,
    onToggleSubscribe: () -> Unit,
    onChannel: (String) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = d.uploaderUrl != null) { d.uploaderUrl?.let(onChannel) }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(d.uploaderAvatarUrl, d.uploaderName, 40)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(d.uploaderName, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            if (d.uploaderSubscriberCount >= 0) {
                Text(
                    tr("${Format.count(d.uploaderSubscriberCount)} subscribers", "${Format.count(d.uploaderSubscriberCount)} iscritti"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (d.uploaderUrl != null) {
            // Iscriviti ⇄ Iscritto: colore e testo cambiano con una transizione morbida.
            val container by androidx.compose.animation.animateColorAsState(
                if (subscribed) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primary,
                androidx.compose.animation.core.tween(220), label = "subBg",
            )
            val content by androidx.compose.animation.animateColorAsState(
                if (subscribed) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimary,
                androidx.compose.animation.core.tween(220), label = "subFg",
            )
            Button(
                onClick = onToggleSubscribe,
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
                modifier = Modifier.popOnChange(subscribed).animateContentSize(),
            ) {
                androidx.compose.animation.AnimatedContent(
                    subscribed,
                    transitionSpec = {
                        (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(160)) +
                            androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(200)) { it / 2 })
                            .togetherWith(androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(100)))
                    },
                    label = "subText",
                ) { sub ->
                    Text(if (sub) tr("Subscribed", "Iscritto") else tr("Subscribe", "Iscriviti"), maxLines = 1)
                }
            }
        }
    }
}

/** Durante l'estrazione mostra subito titolo e canale noti dalla lista di provenienza. */
@Composable
private fun LoadingPreview(vm: PlayerViewModel) {
    val p = vm.preview
    if (p == null) {
        LoadingView()
        return
    }
    Column(Modifier.padding(12.dp)) {
        Text(p.title, style = MaterialTheme.typography.titleMedium)
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(p.uploaderAvatarUrl, p.uploaderName, 40)
            Spacer(Modifier.width(12.dp))
            Text(p.uploaderName, style = MaterialTheme.typography.titleSmall)
        }
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 16.dp))
    }
}

/**
 * Descrizione: YouTube (via NewPipe) la fornisce in HTML. Viene resa come testo formattato
 * con link cliccabili: i minutaggi del video stesso spostano la riproduzione, i link ad altri
 * video li aprono nell'app, gli altri si aprono nel browser.
 */
@Composable
private fun DescriptionText(
    d: VideoDetails,
    expanded: Boolean,
    onSeekTo: (Long) -> Unit,
    onVideo: (String) -> Unit,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val uriHandler = LocalUriHandler.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val seek by rememberUpdatedState(onSeekTo)
    val open by rememberUpdatedState(onVideo)
    val text = remember(d.description, d.descriptionIsHtml, linkColor) {
        if (!d.descriptionIsHtml) {
            AnnotatedString(d.description)
        } else {
            AnnotatedString.fromHtml(
                d.description,
                linkStyles = TextLinkStyles(SpanStyle(color = linkColor)),
                linkInteractionListener = { link ->
                    val url = (link as? LinkAnnotation.Url)?.url ?: return@fromHtml
                    when (val target = LinkParser.parse(url)) {
                        is DeepLink.Video -> {
                            val t = timestampSeconds(url)
                            if (target.videoId == d.id && t != null) seek(t * 1000) else open(target.videoId)
                        }
                        null -> runCatching { uriHandler.openUri(url) }
                        // Canali, playlist e ricerche YouTube: li apre l'app stessa.
                        else -> runCatching {
                            context.startActivity(
                                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                                    .setPackage(context.packageName)
                            )
                        }
                    }
                },
            )
        }
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        maxLines = if (expanded) Int.MAX_VALUE else 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 4.dp),
    )
}

/** Parametro t= dei link YouTube: "125", "125s", "1h2m5s". */
private fun timestampSeconds(url: String): Long? {
    val raw = Regex("""[?&#]t=([0-9hms]+)""").find(url)?.groupValues?.get(1) ?: return null
    raw.toLongOrNull()?.let { return it }
    var total = 0L
    Regex("""(\d+)([hms])""").findAll(raw).forEach { m ->
        val n = m.groupValues[1].toLong()
        total += when (m.groupValues[2]) { "h" -> n * 3600; "m" -> n * 60; else -> n }
    }
    return total.takeIf { it > 0 }
}

/** 👍 | 👎 come su YouTube: icona piena se attiva, conteggi da YouTube / Return YouTube Dislike. */
@Composable
private fun RatingPill(rating: Int, likes: Long, dislikes: Long, onRate: (Int) -> Unit) {
    val bg = MaterialTheme.colorScheme.surfaceVariant
    val active = MaterialTheme.colorScheme.primary
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(bg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.clickable { onRate(1) }.padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (rating == 1) AppIcons.ThumbUp else AppIcons.ThumbUpOutline,
                tr("Like", "Mi piace"),
                tint = if (rating == 1) active else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.popOnChange(rating == 1).size(18.dp),
            )
            if (likes >= 0) {
                Spacer(Modifier.width(6.dp))
                Text(Format.count(likes), style = MaterialTheme.typography.labelLarge)
            }
        }
        Box(Modifier.width(1.dp).height(20.dp).background(MaterialTheme.colorScheme.outline))
        Row(
            Modifier.clickable { onRate(-1) }.padding(start = 10.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (rating == -1) AppIcons.ThumbDown else AppIcons.ThumbDownOutline,
                tr("Dislike", "Non mi piace"),
                tint = if (rating == -1) active else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.popOnChange(rating == -1).size(18.dp),
            )
            if (dislikes >= 0) {
                Spacer(Modifier.width(6.dp))
                Text(Format.count(dislikes), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}
