package com.mauztube.ui.player

import com.mauztube.util.tr

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.mauztube.data.model.Storyboard
import coil3.toBitmap
import coil3.request.allowHardware
import coil3.request.SuccessResult
import coil3.request.ImageRequest
import coil3.SingletonImageLoader
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.runtime.produceState
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.animation.core.tween
import com.mauztube.ui.components.pressScale
import androidx.compose.animation.togetherWith
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.mauztube.data.repository.SponsorSegment
import com.mauztube.player.PlaybackManager
import com.mauztube.ui.theme.AppIcons
import com.mauztube.util.Format
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/** Colore dei segmenti SponsorBlock sulla barra (come l'estensione ufficiale). */
private val SponsorColor = Color(0xFF00D400)

/** Stato del player osservabile da Compose (aggiornato da listener + polling leggero). */
class PlayerUiSnapshot {
    var isPlaying by mutableStateOf(false)
    var playbackState by mutableIntStateOf(Player.STATE_IDLE)
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var buffered by mutableLongStateOf(0L)
    var speed by mutableFloatStateOf(1f)
}

@Composable
fun rememberPlayerSnapshot(player: Player, intervalMs: Long = 250): PlayerUiSnapshot {
    val snap = remember(player) { PlayerUiSnapshot() }
    DisposableEffect(player) {
        fun sync() {
            snap.isPlaying = player.isPlaying
            snap.playbackState = player.playbackState
            snap.position = player.currentPosition
            snap.duration = player.duration.coerceAtLeast(0)
            snap.buffered = player.bufferedPosition
            snap.speed = player.playbackParameters.speed
        }
        val listener = object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = sync()
        }
        player.addListener(listener)
        sync()
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(player, intervalMs) {
        while (true) {
            // In pausa la posizione non cambia: nessun aggiornamento (né ricomposizione).
            if (!snap.isPlaying) androidx.compose.runtime.snapshotFlow { snap.isPlaying }.first { it }
            snap.position = player.currentPosition
            snap.buffered = player.bufferedPosition
            snap.duration = player.duration.coerceAtLeast(0)
            delay(intervalMs)
        }
    }
    return snap
}

/** Superficie video (PlayerView senza controlli: solo video e sottotitoli). */
@OptIn(UnstableApi::class)
@Composable
fun VideoView(player: Player, modifier: Modifier = Modifier, pipSurface: Boolean = false) {
    // Lo schermo resta acceso solo durante la riproduzione (in pausa può spegnersi).
    val playing = rememberPlayerSnapshot(player, intervalMs = 5_000).isPlaying
    // Nel popup c'è una sola superficie video (quella a tutto schermo); le altre si spengono
    // e si ricollegano al player quando il popup si chiude.
    if (com.mauztube.LocalInPip.current && !pipSurface) {
        Box(modifier.background(Color.Black))
        return
    }
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                this.player = player
            }
        },
        update = { it.player = player; it.keepScreenOn = playing },
        onRelease = { it.player = null },
    )
}

/** Riga dei sottotitoli (testo bianco su fondo scuro semitrasparente, come YouTube). */
@Composable
fun SubtitleOverlay(manager: com.mauztube.player.PlaybackManager, raised: Boolean, large: Boolean, modifier: Modifier = Modifier) {
    val text by manager.subtitleText.collectAsStateWithLifecycle()
    val lift by androidx.compose.animation.core.animateDpAsState(
        if (raised) 56.dp else 12.dp, androidx.compose.animation.core.tween(220), label = "subLift",
    )
    val t = text ?: return
    Text(
        t,
        color = Color.White,
        fontSize = if (large) 20.sp else 14.sp,
        lineHeight = if (large) 26.sp else 18.sp,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = modifier
            .graphicsLayer { translationY = -lift.toPx() }
            .padding(horizontal = 24.dp)
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/**
 * Player completo con controlli personalizzati, coerenti con il tema dell'app.
 */
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerWithControls(
    manager: PlaybackManager,
    segments: List<SponsorSegment>,
    fullscreen: Boolean,
    audioOnly: Boolean,
    coverUrl: String?,
    loading: Boolean,
    onSetFullscreen: (Boolean) -> Unit,
    onMinimize: () -> Unit,
    onToggleAudioOnly: () -> Unit,
    queueSize: Int,
    onOpenQueue: () -> Unit,
    storyboard: Storyboard?,
    onPip: () -> Unit,
    autoplay: Boolean,
    loop: Boolean,
    onToggleAutoplay: () -> Unit,
    onToggleLoop: () -> Unit,
    related: List<com.mauztube.data.model.VideoItem>,
    onOpenRelated: (com.mauztube.data.model.VideoItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Pannello dei consigliati a schermo intero (si apre trascinando verso l'alto).
    var relatedOpen by remember { mutableStateOf(false) }
    if (!fullscreen && relatedOpen) relatedOpen = false
    val onToggleFullscreen = { onSetFullscreen(!fullscreen) }
    val player = manager.player
    val snap = rememberPlayerSnapshot(player)
    var visible by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }
    var scrubFraction by remember { mutableStateOf<Float?>(null) }
    var seekFeedback by remember { mutableStateOf<String?>(null) }
    // Doppio tocco ±10 s: lato, secondi accumulati, contatore che fa ripartire l'animazione.
    var tapSide by remember { mutableIntStateOf(0) }
    var tapSeconds by remember { mutableIntStateOf(0) }
    var tapTick by remember { mutableIntStateOf(0) }
    var lastSeekTapAt by remember { mutableStateOf(0L) }
    var sheetOpen by remember { mutableStateOf(false) }
    val primary = MaterialTheme.colorScheme.primary

    // Nasconde i controlli dopo 3 s di riproduzione senza interazioni.
    LaunchedEffect(visible, snap.isPlaying, interaction, scrubFraction) {
        if (visible && snap.isPlaying && scrubFraction == null) {
            delay(3_000)
            visible = false
        }
    }
    LaunchedEffect(seekFeedback) {
        if (seekFeedback != null) { delay(if ((seekFeedback?.length ?: 0) > 8) 1_400 else 700); seekFeedback = null }
    }

    Box(modifier.background(Color.Black)) {
        VideoView(player, Modifier.fillMaxSize())

        if (audioOnly || loading) {
            AsyncImage(
                model = coverUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            if (audioOnly && !loading) {
                Row(
                    Modifier.align(Alignment.Center).clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(AppIcons.Headphones, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(tr("Audio only", "Solo audio"), color = Color.White, style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        // Livello gesti:
        //  - tocco: mostra/nascondi SUBITO (nessuna attesa per distinguere il doppio tocco);
        //  - secondo tocco rapido sullo stesso lato: ±10 s;
        //  - trascinamento verticale:
        //      verticale  → giù = mini player, su = schermo intero
        //      schermo intero → su = consigliati, giù = chiudi consigliati / esci da schermo intero
        val density = androidx.compose.ui.platform.LocalDensity.current
        val swipeThreshold = with(density) { 64.dp.toPx() }
        val flingVelocityPx = with(density) { 1000.dp.toPx() }
        val backDispatcher = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
        val windowHeightPx = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height.toFloat()
        var gestureCoords by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
        Box(
            Modifier.fillMaxSize()
                .pointerInputTaps(
                    onTap = { visible = !visible; interaction++ },
                    onDoubleTap = { x, width ->
                        val side = if (x < width / 2) -1 else 1
                        val now = System.currentTimeMillis()
                        // Primo doppio tocco: annulla il cambio di visibilità del tocco precedente.
                        if (now - lastSeekTapAt > 700 || side != tapSide) visible = !visible
                        tapSeconds = if (side == tapSide && now - lastSeekTapAt < 900) tapSeconds + 10 else 10
                        lastSeekTapAt = now
                        tapSide = side
                        tapTick++
                        if (side < 0) player.seekBack() else player.seekForward()
                        interaction++
                    },
                )
                .onGloballyPositioned { gestureCoords = it }
                .pointerInput(fullscreen, backDispatcher) {
                    // Spostamento misurato in coordinate dello SCHERMO: durante la riduzione il
                    // video si muove insieme al dito, e in coordinate locali il dito sembrerebbe
                    // fermo (il trascinamento si bloccherebbe).
                    var total = 0f
                    var lastRootY = 0f
                    var collapsing = false
                    val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
                    val screenH = windowHeightPx.coerceAtLeast(1f)
                    fun rootPos(local: androidx.compose.ui.geometry.Offset) =
                        gestureCoords?.takeIf { it.isAttached }?.localToRoot(local) ?: local
                    fun backEvent(pos: androidx.compose.ui.geometry.Offset, progress: Float) =
                        androidx.activity.BackEventCompat(pos.x, pos.y, progress, androidx.activity.BackEventCompat.EDGE_NONE)
                    detectVerticalDragGestures(
                        onDragStart = { start ->
                            total = 0f
                            collapsing = false
                            lastRootY = rootPos(start).y
                            tracker.resetTracking()
                        },
                        onDragCancel = {
                            if (collapsing) backDispatcher?.dispatchOnBackCancelled()
                            collapsing = false
                        },
                        onDragEnd = {
                            if (collapsing) {
                                collapsing = false
                                // Rilascio: oltre il 15% dello schermo o con un gesto rapido verso il
                                // basso il video completa la riduzione (nel tempo che resta: più il
                                // gesto è veloce, più è rapida), altrimenti torna su.
                                val velocity = tracker.calculateVelocity().y
                                val progress = (total / screenH).coerceIn(0f, 1f)
                                if (progress > 0.15f || (velocity > flingVelocityPx && progress > 0.03f)) backDispatcher?.onBackPressed()
                                else backDispatcher?.dispatchOnBackCancelled()
                                return@detectVerticalDragGestures
                            }
                            when {
                                fullscreen && relatedOpen && total > swipeThreshold -> relatedOpen = false
                                fullscreen && total > swipeThreshold -> onSetFullscreen(false)
                                fullscreen && total < -swipeThreshold && related.isNotEmpty() -> {
                                    relatedOpen = true
                                    visible = false
                                }
                                !fullscreen && total > swipeThreshold -> onMinimize()
                                !fullscreen && total < -swipeThreshold -> onSetFullscreen(true)
                            }
                        },
                        onVerticalDrag = { change, _ ->
                            change.consume()
                            val pos = rootPos(change.position)
                            total += pos.y - lastRootY
                            lastRootY = pos.y
                            tracker.addPosition(change.uptimeMillis, pos)
                            // Verso il basso (non a schermo intero): il video segue il dito.
                            if (!fullscreen && backDispatcher != null) {
                                if (!collapsing && total > 0f) {
                                    collapsing = true
                                    backDispatcher.dispatchOnBackStarted(backEvent(pos, 0f))
                                }
                                if (collapsing) {
                                    backDispatcher.dispatchOnBackProgressed(backEvent(pos, (total / screenH).coerceIn(0f, 1f)))
                                }
                            }
                        },
                    )
                }
        )

        // Sottotitoli: sopra il video, si alzano quando compare la barra dei controlli.
        SubtitleOverlay(
            manager,
            raised = visible || scrubFraction != null,
            large = fullscreen,
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        val buffering = snap.playbackState == Player.STATE_BUFFERING || loading
        if (buffering) {
            CircularProgressIndicator(Modifier.align(Alignment.Center).size(48.dp), color = primary)
        }
        DoubleTapFlash(tapSide, tapTick, if (tapSide < 0) "−$tapSeconds s" else "+$tapSeconds s")
        seekFeedback?.let {
            Text(
                it, color = Color.White, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(50))
                    .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        AnimatedVisibility(visible = visible || scrubFraction != null, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.42f))
                    // A schermo intero i comandi evitano foro/notch della fotocamera e barre di sistema.
                    .then(if (fullscreen) Modifier.windowInsetsPadding(WindowInsets.safeDrawing) else Modifier)
            ) {
                // --- riga superiore ---
                Row(
                    Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!fullscreen) {
                        ControlButton(Icons.Default.KeyboardArrowDown, tr("Minimize", "Riduci"), onMinimize)
                    }
                    Spacer(Modifier.weight(1f))
                    if (queueSize > 0) {
                        ControlButton(AppIcons.Queue, tr("Queue ($queueSize)", "Coda ($queueSize)"), onOpenQueue, tint = primary)
                    }
                    ControlButton(
                        AppIcons.Autoplay, tr("Autoplay", "Riproduzione automatica"),
                        onClick = {
                            onToggleAutoplay()
                            seekFeedback = if (!autoplay) tr("Autoplay on", "Riproduzione automatica attiva")
                            else tr("Autoplay off", "Riproduzione automatica disattivata")
                            interaction++
                        },
                        tint = if (autoplay) primary else Color.White,
                    )
                    ControlButton(
                        AppIcons.RepeatOne, tr("Loop", "Ripeti"),
                        onClick = {
                            onToggleLoop()
                            seekFeedback = if (!loop) tr("Loop on", "Ripetizione attiva") else tr("Loop off", "Ripetizione disattivata")
                            interaction++
                        },
                        tint = if (loop) primary else Color.White,
                    )
                    ControlButton(AppIcons.PictureInPicture, tr("Pop-up", "Popup"), onPip)
                    ControlButton(
                        AppIcons.Headphones, tr("Audio only", "Solo audio"), onToggleAudioOnly,
                        tint = if (audioOnly) primary else Color.White,
                    )
                    val subtitleIndex by manager.subtitleIndex.collectAsStateWithLifecycle()
                    if (manager.textTracks().isNotEmpty()) {
                        ControlButton(
                            AppIcons.ClosedCaption, tr("Subtitles", "Sottotitoli"),
                            onClick = {
                                manager.selectTextTrack(if (subtitleIndex == null) 0 else null)
                                interaction++
                            },
                            tint = if (subtitleIndex != null) primary else Color.White,
                        )
                    }
                    ControlButton(Icons.Default.Settings, tr("Settings", "Impostazioni"), { sheetOpen = true })
                }

                // --- controlli centrali ---
                if (!buffering) {
                    Row(
                        Modifier.align(Alignment.Center),
                        horizontalArrangement = Arrangement.spacedBy(28.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ControlButton(AppIcons.Replay10, tr("Back 10 s", "Indietro 10 s"), { player.seekBack(); interaction++ }, size = 40, spin = -40f)
                        val ended = snap.playbackState == Player.STATE_ENDED
                        Box(
                            Modifier.size(64.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f))
                                .clickable {
                                    when {
                                        ended -> { player.seekTo(0); player.play() }
                                        snap.isPlaying -> player.pause()
                                        else -> player.play()
                                    }
                                    interaction++
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            // Cambio play ⇄ pausa con una piccola trasformazione (scala + dissolvenza).
                            androidx.compose.animation.AnimatedContent(
                                targetState = when { ended -> 2; snap.isPlaying -> 1; else -> 0 },
                                transitionSpec = {
                                    (androidx.compose.animation.scaleIn(tween(180), initialScale = 0.5f) + fadeIn(tween(140)))
                                        .togetherWith(androidx.compose.animation.scaleOut(tween(120), targetScale = 0.5f) + fadeOut(tween(100)))
                                },
                                label = "playPause",
                            ) { kind ->
                                Icon(
                                    when (kind) {
                                        2 -> Icons.Default.Refresh
                                        1 -> AppIcons.Pause
                                        else -> Icons.Default.PlayArrow
                                    },
                                    contentDescription = tr("Play/Pause", "Riproduci/Pausa"),
                                    tint = Color.White,
                                    modifier = Modifier.size(44.dp),
                                )
                            }
                        }
                        ControlButton(AppIcons.Forward10, tr("Forward 10 s", "Avanti 10 s"), { player.seekForward(); interaction++ }, size = 40, spin = 40f)
                    }
                }

                // --- barra inferiore ---
                Column(
                    Modifier.fillMaxWidth().align(Alignment.BottomCenter)
                        .padding(horizontal = 12.dp)
                ) {
                    // Anteprima del punto sotto il dito mentre si trascina la barra.
                    val frac = scrubFraction
                    if (frac != null && storyboard != null && snap.duration > 0) {
                        ScrubPreview(storyboard, (frac * snap.duration).toLong(), frac, fullscreen)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val shown = scrubFraction?.let { (it * snap.duration).toLong() } ?: snap.position
                        Text(
                            "${Format.durationMs(shown).ifEmpty { "0:00" }} / ${Format.durationMs(snap.duration).ifEmpty { "0:00" }}",
                            color = Color.White,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Spacer(Modifier.weight(1f))
                        ControlButton(
                            if (fullscreen) AppIcons.FullscreenExit else AppIcons.Fullscreen,
                            tr("Full screen", "Schermo intero"), onToggleFullscreen,
                        )
                    }
                    SeekBar(
                        position = snap.position,
                        duration = snap.duration,
                        buffered = snap.buffered,
                        segments = segments,
                        color = primary,
                        onScrub = { scrubFraction = it; interaction++ },
                        onSeek = { fraction ->
                            player.seekTo((fraction * snap.duration).toLong())
                            scrubFraction = null
                            interaction++
                        },
                    )
                    Spacer(Modifier.height(if (fullscreen) 12.dp else 2.dp))
                }
            }
        }
        // ---- Consigliati a schermo intero ----
        AnimatedVisibility(
            visible = relatedOpen,
            enter = androidx.compose.animation.slideInVertically { it } + fadeIn(),
            exit = androidx.compose.animation.slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            RelatedOverlay(
                related = related,
                onOpen = { relatedOpen = false; onOpenRelated(it) },
                onClose = { relatedOpen = false },
            )
        }
    }

    if (sheetOpen) {
        ModalBottomSheet(
            onDismissRequest = { sheetOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            PlayerSettingsSheet(manager, snap.speed, onDone = { sheetOpen = false })
        }
    }
}

private enum class SheetPage { ROOT, QUALITY, SPEED, SUBTITLES }

@Composable
private fun PlayerSettingsSheet(manager: PlaybackManager, speed: Float, onDone: () -> Unit) {
    var page by remember { mutableStateOf(SheetPage.ROOT) }
    Column(
        Modifier.fillMaxWidth().navigationBarsPadding()
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(bottom = 16.dp)
    ) {
        when (page) {
            SheetPage.ROOT -> {
                val selected = manager.selectedQuality()
                val current = manager.currentHeight()
                SheetRow(
                    tr("Quality", "Qualità"),
                    if (selected == null) tr("Auto", "Automatica") + (current?.let { " (${it}p)" } ?: "") else "${selected}p",
                ) { page = SheetPage.QUALITY }
                SheetRow(tr("Speed", "Velocità"), if (speed == 1f) tr("Normal", "Normale") else "${speed}x") { page = SheetPage.SPEED }
                val tracks = manager.textTracks()
                if (tracks.isNotEmpty()) {
                    val sel = manager.selectedTextTrack()
                    SheetRow(tr("Subtitles", "Sottotitoli"), sel?.let { tracks.getOrNull(it)?.label } ?: tr("Off", "Disattivati")) {
                        page = SheetPage.SUBTITLES
                    }
                }
            }
            SheetPage.QUALITY -> {
                SheetHeader(tr("Quality", "Qualità")) { page = SheetPage.ROOT }
                val selected = manager.selectedQuality()
                OptionRow(tr("Auto", "Automatica"), selected == null) { manager.selectQuality(null); onDone() }
                manager.qualities().forEach { q ->
                    OptionRow(q.label, selected == q.height) { manager.selectQuality(q.height); onDone() }
                }
            }
            SheetPage.SPEED -> {
                SheetHeader(tr("Speed", "Velocità")) { page = SheetPage.ROOT }
                listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { s ->
                    OptionRow(if (s == 1f) tr("Normal", "Normale") else "${s}x", s == speed) { manager.setSpeed(s); onDone() }
                }
            }
            SheetPage.SUBTITLES -> {
                SheetHeader(tr("Subtitles", "Sottotitoli")) { page = SheetPage.ROOT }
                val sel = manager.selectedTextTrack()
                OptionRow(tr("Off", "Disattivati"), sel == null) { manager.selectTextTrack(null); onDone() }
                manager.textTracks().forEach { t ->
                    OptionRow(t.label, sel == t.index) { manager.selectTextTrack(t.index); onDone() }
                }
            }
        }
    }
}

@Composable
private fun SheetHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, tr("Back", "Indietro")) }
        Text(title, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun SheetRow(title: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun OptionRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(24.dp)) {
            if (selected) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.width(16.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun ControlButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    tint: Color = Color.White,
    size: Int = 24,
    /** Rotazione di "scatto" al tocco (±10 s), poi ritorno elastico. */
    spin: Float = 0f,
) {
    val rotation = remember { androidx.compose.animation.core.Animatable(0f) }
    val scope = rememberCoroutineScope()
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    IconButton(
        onClick = {
            onClick()
            if (spin != 0f) scope.launch {
                rotation.snapTo(spin)
                rotation.animateTo(0f, androidx.compose.animation.core.spring(dampingRatio = 0.45f, stiffness = 300f))
            }
        },
        modifier = Modifier.size((size + 20).dp).pressScale(interaction, 0.85f),
        interactionSource = interaction,
    ) {
        Icon(icon, description, tint = tint, modifier = Modifier.size(size.dp).graphicsLayer { rotationZ = rotation.value })
    }
}

/** Arco chiaro sul lato del doppio tocco con i secondi saltati, che sfuma (come YouTube). */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.DoubleTapFlash(side: Int, tick: Int, label: String) {
    if (tick == 0 || side == 0) return
    val alpha = remember { androidx.compose.animation.core.Animatable(0f) }
    val pulse = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(tick) {
        launch { pulse.snapTo(0.85f); pulse.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = 400f)) }
        alpha.snapTo(1f)
        delay(450)
        alpha.animateTo(0f, tween(300))
    }
    val left = side < 0
    Box(
        Modifier.align(if (left) Alignment.CenterStart else Alignment.CenterEnd)
            .fillMaxHeight().fillMaxWidth(0.4f)
            .graphicsLayer { this.alpha = alpha.value }
            .clip(
                if (left) RoundedCornerShape(topEndPercent = 50, bottomEndPercent = 50)
                else RoundedCornerShape(topStartPercent = 50, bottomStartPercent = 50)
            )
            .background(Color.White.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer { scaleX = pulse.value; scaleY = pulse.value },
        ) {
            Icon(if (left) AppIcons.Replay10 else AppIcons.Forward10, null, tint = Color.White, modifier = Modifier.size(32.dp))
            Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Barra di avanzamento: buffer, avanzamento (colore del tema), segmenti SponsorBlock, cursore. */
@Composable
fun SeekBar(
    position: Long,
    duration: Long,
    buffered: Long,
    segments: List<SponsorSegment>,
    color: Color,
    onScrub: (Float) -> Unit,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var drag by remember { mutableStateOf<Float?>(null) }
    Canvas(
        modifier
            .fillMaxWidth()
            .height(28.dp)
            .pointerInput(duration) {
                detectTapGestures { offset -> if (duration > 0) onSeek((offset.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(duration) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> drag = (o.x / size.width).coerceIn(0f, 1f); onScrub(drag!!) },
                    onDragEnd = { drag?.let(onSeek); drag = null },
                    onDragCancel = { drag = null },
                    onHorizontalDrag = { change, _ ->
                        drag = (change.position.x / size.width).coerceIn(0f, 1f)
                        onScrub(drag!!)
                    },
                )
            }
    ) {
        val h = 3.dp.toPx()
        val y = size.height / 2 - h / 2
        val w = size.width
        fun x(ms: Long) = if (duration > 0) (ms.toFloat() / duration).coerceIn(0f, 1f) * w else 0f
        val radius = CornerRadius(h / 2)
        drawRoundRect(Color.White.copy(alpha = 0.3f), Offset(0f, y), Size(w, h), radius)
        drawRoundRect(Color.White.copy(alpha = 0.55f), Offset(0f, y), Size(x(buffered), h), radius)
        segments.forEach { s ->
            drawRect(SponsorColor, Offset(x(s.startMs), y), Size((x(s.endMs) - x(s.startMs)).coerceAtLeast(2f), h))
        }
        val played = drag?.let { it * w } ?: x(position)
        drawRoundRect(color, Offset(0f, y), Size(played, h), radius)
        drawCircle(color, radius = if (drag != null) 8.dp.toPx() else 6.dp.toPx(), center = Offset(played, size.height / 2))
    }
}

/**
 * Tocco singolo immediato + riconoscimento del doppio tocco: il primo tocco agisce subito;
 * se ne arriva un secondo entro 300 ms sullo stesso lato viene trattato come doppio tocco.
 */
private fun Modifier.pointerInputTaps(
    onTap: () -> Unit,
    onDoubleTap: (x: Float, width: Float) -> Unit,
): Modifier = this.then(
    Modifier.pointerInput(Unit) {
        var lastTime = 0L
        var lastX = -1f
        // Dopo un doppio tocco, ogni altro tocco rapido sullo stesso lato aggiunge 10 s
        // (come su YouTube), senza dover ripetere il doppio tocco.
        var seekUntil = 0L
        detectTapGestures(
            onTap = { o ->
                val now = System.currentTimeMillis()
                val w = size.width.toFloat()
                val sameSide = lastX >= 0 && (lastX < w / 2) == (o.x < w / 2)
                if (sameSide && (now - lastTime < 300 || now < seekUntil)) {
                    onDoubleTap(o.x, w)
                    lastTime = 0L
                    seekUntil = now + 700
                } else {
                    seekUntil = 0L
                    onTap()
                    lastTime = now
                    lastX = o.x
                }
            },
        )
    }
)

/** Cache degli sprite delle anteprime (pochi KB l'uno): niente ricaricamenti durante lo scrub. */
private object SpriteCache {
    private val cache = android.util.LruCache<String, ImageBitmap>(12)
    fun get(url: String): ImageBitmap? = cache.get(url)
    fun put(url: String, bmp: ImageBitmap) { cache.put(url, bmp) }
}

@Composable
private fun ScrubPreview(storyboard: Storyboard, positionMs: Long, fraction: Float, fullscreen: Boolean) {
    val context = LocalContext.current
    val (page, sx, sy) = storyboard.frameAt(positionMs)
    val url = storyboard.urls.getOrNull(page) ?: return
    val sprite by produceState(SpriteCache.get(url), url) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val request = ImageRequest.Builder(context).data(url).allowHardware(false).build()
                    (SingletonImageLoader.get(context).execute(request) as? SuccessResult)
                        ?.image?.toBitmap()?.asImageBitmap()
                }.getOrNull()
            }?.also { SpriteCache.put(url, it) }
        }
    }
    val width = if (fullscreen) 200.dp else 150.dp
    val ratio = storyboard.frameWidth.toFloat() / storyboard.frameHeight.coerceAtLeast(1)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val x = (maxWidth * fraction - width / 2).coerceIn(0.dp, (maxWidth - width).coerceAtLeast(0.dp))
        Column(Modifier.offset(x = x).width(width), horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(
                Modifier.fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(8.dp))
                    .background(Color.Black).border(2.dp, Color.White, RoundedCornerShape(8.dp))
            ) {
                sprite?.let {
                    drawImage(
                        it,
                        srcOffset = IntOffset(sx, sy),
                        srcSize = IntSize(storyboard.frameWidth, storyboard.frameHeight),
                        dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                    )
                }
            }
            Text(
                Format.durationMs(positionMs).ifEmpty { "0:00" },
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        }
    }
}

/** Fila di consigliati sopra il video a schermo intero, come nel client ufficiale. */
@Composable
private fun RelatedOverlay(
    related: List<com.mauztube.data.model.VideoItem>,
    onOpen: (com.mauztube.data.model.VideoItem) -> Unit,
    onClose: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth()
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))
                )
            )
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(top = 24.dp, bottom = 12.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Up next", "Consigliati"), color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            ControlButton(Icons.Default.KeyboardArrowDown, tr("Close", "Chiudi"), onClose)
        }
        androidx.compose.foundation.lazy.LazyRow(
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val unique = related.distinctBy { it.id }
            items(unique.size, key = { unique[it].id }) { i ->
                val v = unique[i]
                Column(Modifier.width(220.dp).clip(RoundedCornerShape(10.dp)).clickable { onOpen(v) }) {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Color.DarkGray)) {
                        AsyncImage(v.thumbnailUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        val d = Format.duration(v.durationSeconds)
                        if (d.isNotEmpty()) {
                            Text(
                                d, color = Color.White, style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                                    .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp),
                            )
                        }
                    }
                    Text(
                        v.title, color = Color.White, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp),
                    )
                    Text(v.uploaderName, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
    }
}
