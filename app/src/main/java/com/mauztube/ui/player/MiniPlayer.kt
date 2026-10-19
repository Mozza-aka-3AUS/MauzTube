package com.mauztube.ui.player

import com.mauztube.util.tr

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.mauztube.player.PlaybackManager
import com.mauztube.player.PlayerUiState
import com.mauztube.ui.theme.AppIcons
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * Mini player flottante in basso a destra: il video continua mentre si naviga nell'app.
 *  - tocco: riapre il video a schermo;
 *  - trascinamento verso il basso o verso destra oltre la soglia: chiude il video.
 */
@Composable
fun MiniPlayer(
    manager: PlaybackManager,
    onExpand: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val nowPlaying by manager.nowPlaying.collectAsStateWithLifecycle()
    val audioOnly by manager.audioOnly.collectAsStateWithLifecycle()
    val np = nowPlaying ?: return
    val snap = rememberPlayerSnapshot(manager.player, intervalMs = 1_000)
    val scope = rememberCoroutineScope()
    val dx = remember(np.videoId) { Animatable(0f) }
    val dy = remember(np.videoId) { Animatable(0f) }
    val threshold = with(LocalDensity.current) { 90.dp.toPx() }
    val cover = (np.state as? PlayerUiState.Ready)?.details?.thumbnailUrl ?: np.preview?.thumbnailUrl
    // Larghezza proporzionale allo schermo: ~45% su un telefono, mai enorme sui tablet e mai
    // così grande da coprire metà dei contenuti sugli schermi piccoli.
    val screenW = com.mauztube.ui.components.windowSize().widthDp
    val width = (screenW * 0.45f).coerceIn(150f, 260f).dp
    // Entrata: sale dal basso ingrandendosi leggermente.
    val enter = remember(np.videoId) { Animatable(0f) }
    LaunchedEffect(np.videoId) { enter.animateTo(1f, tween(220, easing = androidx.compose.animation.core.FastOutSlowInEasing)) }

    Box(
        modifier
            // Posizione e trasparenza lette in fase di disegno: il trascinamento non ricompone.
            .graphicsLayer {
                val e = enter.value
                translationX = dx.value
                translationY = dy.value + (1f - e) * 48.dp.toPx()
                val s = 0.9f + 0.1f * e
                scaleX = s; scaleY = s
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 1f)
                // Più lo si trascina verso l'uscita, più diventa trasparente.
                alpha = e * (1f - (max(dx.value, dy.value) / (threshold * 2)).coerceIn(0f, 0.7f))
            }
            .width(width)
            .aspectRatio(16f / 9f)
            .shadow(12.dp, RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black)
            .pointerInput(np.videoId) {
                detectTapGestures(onTap = { onExpand(np.videoId) })
            }
            .pointerInput(np.videoId) {
                detectDragGestures(
                    onDragEnd = {
                        scope.launch {
                            if (dy.value > threshold || dx.value > threshold) {
                                // Esce dallo schermo nella direzione del gesto, poi chiude.
                                if (dy.value > dx.value) dy.animateTo(dy.value + threshold * 3, tween(180))
                                else dx.animateTo(dx.value + threshold * 3, tween(180))
                                manager.close()
                            } else {
                                launch { dx.animateTo(0f) }
                                dy.animateTo(0f)
                            }
                        }
                    },
                    onDrag = { change, drag ->
                        change.consume()
                        scope.launch {
                            // Solo verso il basso / destra (le direzioni che chiudono), con un po' di gioco.
                            dx.snapTo((dx.value + drag.x).coerceAtLeast(-24f))
                            dy.snapTo((dy.value + drag.y).coerceAtLeast(-24f))
                        }
                    },
                )
            }
    ) {
        VideoView(manager.player, Modifier.fillMaxSize())
        if (audioOnly || np.state !is PlayerUiState.Ready) {
            AsyncImage(model = cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        // Cerchio sottile attorno all'icona: abbastanza contrasto sul video senza un alone pesante.
        Box(
            Modifier.align(Alignment.BottomStart).padding(6.dp).size(28.dp)
                .clip(CircleShape).background(Color.Black.copy(alpha = 0.4f))
                .clickable { if (snap.isPlaying) manager.player.pause() else manager.player.play() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (snap.isPlaying) AppIcons.Pause else Icons.Default.PlayArrow,
                contentDescription = tr("Play/Pause", "Riproduci/Pausa"),
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
        // Barra di avanzamento sottile.
        val progress = if (snap.duration > 0) snap.position.toFloat() / snap.duration else 0f
        Box(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp)
                .background(Color.White.copy(alpha = 0.25f))
        ) {
            Box(
                Modifier.fillMaxWidth(progress.coerceIn(0f, 1f)).fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}
