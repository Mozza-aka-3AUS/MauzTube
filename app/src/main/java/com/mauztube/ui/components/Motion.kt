package com.mauztube.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.graphics.drawscope.scale
import kotlinx.coroutines.launch

/**
 * Animazioni leggere condivise. Agiscono solo su trasparenza/scala/traslazione dentro
 * `graphicsLayer { }`: il valore viene letto in fase di disegno, quindi un fotogramma
 * dell'animazione non ricompone né rimisura nulla (lavoro quasi solo della GPU).
 */
object Motion {
    const val FAST = 160
    const val MEDIUM = 220
}

/** Comparsa in dissolvenza con un leggero scorrimento dal basso, una sola volta per [key]. */
@Composable
fun Modifier.appearIn(key: Any? = Unit, durationMs: Int = Motion.MEDIUM, offsetDp: Float = 12f): Modifier {
    val p = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { p.animateTo(1f, tween(durationMs, easing = FastOutSlowInEasing)) }
    return graphicsLayer {
        val v = p.value
        alpha = v
        translationY = (1f - v) * offsetDp * density
    }
}

/** Piccolo "rimbalzo" di scala quando [trigger] cambia (non alla prima composizione). */
@Composable
fun Modifier.popOnChange(trigger: Any?): Modifier {
    val s = remember { Animatable(1f) }
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(trigger) {
        if (first[0]) { first[0] = false; return@LaunchedEffect }
        s.snapTo(1.3f)
        s.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow))
    }
    return graphicsLayer { scaleX = s.value; scaleY = s.value }
}

/**
 * Leggero "affondamento" mentre l'elemento è premuto (feedback tattile visivo). Nelle liste
 * la pressione viene riconosciuta solo dopo un istante senza scorrimento, quindi durante lo
 * scroll non si attiva.
 */
@Composable
fun Modifier.pressScale(interaction: androidx.compose.foundation.interaction.InteractionSource, pressedScale: Float = 0.97f): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale = androidx.compose.animation.core.animateFloatAsState(
        if (pressed) pressedScale else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    return graphicsLayer { scaleX = scale.value; scaleY = scale.value }
}

/**
 * Feedback al tocco per gli elementi delle liste: ripple del tema + leggero "affondamento".
 *
 * È una Indication (non un modificatore composto): con `clickable(interactionSource = null, …)`
 * Compose la crea SOLO al primo tocco dell'elemento. Le card che scorrono sullo schermo senza
 * essere toccate non pagano nulla (niente stato, animazioni o coroutine per card), e
 * l'affondamento è applicato in fase di disegno, senza ricomporre né rimisurare.
 */
object PressScaleIndication : androidx.compose.foundation.IndicationNodeFactory {
    override fun create(interactionSource: androidx.compose.foundation.interaction.InteractionSource): androidx.compose.ui.node.DelegatableNode =
        PressScaleNode(interactionSource)

    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = 0x5ca1e
}

private class PressScaleNode(
    private val source: androidx.compose.foundation.interaction.InteractionSource,
) : androidx.compose.ui.node.DelegatingNode(), androidx.compose.ui.node.DrawModifierNode {
    private val amount = Animatable(1f)
    private var animation: kotlinx.coroutines.Job? = null
    /** Su TV: bordo attorno all'elemento selezionato col telecomando. */
    private var focusBorder = false

    init {
        delegate(androidx.compose.material3.ripple().create(source))
    }

    override fun onAttach() {
        coroutineScope.launch {
            val pressed = mutableListOf<androidx.compose.foundation.interaction.PressInteraction.Press>()
            var focused = false
            source.interactions.collect { i ->
                when (i) {
                    is androidx.compose.foundation.interaction.PressInteraction.Press -> pressed += i
                    is androidx.compose.foundation.interaction.PressInteraction.Release -> pressed -= i.press
                    is androidx.compose.foundation.interaction.PressInteraction.Cancel -> pressed -= i.press
                    // Su TV la scheda selezionata col telecomando si ingrandisce un poco.
                    is androidx.compose.foundation.interaction.FocusInteraction.Focus -> focused = com.mauztube.util.DeviceProfile.isTv
                    is androidx.compose.foundation.interaction.FocusInteraction.Unfocus -> focused = false
                }
                if (focusBorder != focused) {
                    focusBorder = focused
                    invalidateDraw()
                }
                when (i) {
                    is androidx.compose.foundation.interaction.PressInteraction.Press,
                    is androidx.compose.foundation.interaction.PressInteraction.Release,
                    is androidx.compose.foundation.interaction.PressInteraction.Cancel,
                    is androidx.compose.foundation.interaction.FocusInteraction.Focus,
                    is androidx.compose.foundation.interaction.FocusInteraction.Unfocus -> Unit
                    else -> return@collect
                }
                val target = when {
                    pressed.isNotEmpty() -> 0.97f
                    focused -> 1.02f
                    else -> 1f
                }
                animation?.cancel()
                animation = launch {
                    amount.animateTo(
                        target,
                        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
                    ) { invalidateDraw() }
                }
            }
        }
    }

    override fun androidx.compose.ui.graphics.drawscope.ContentDrawScope.draw() {
        val s = amount.value
        if (s == 1f) drawContent()
        else scale(s) { this@draw.drawContent() }
        if (focusBorder) {
            val stroke = 3.dp.toPx()
            scale(s) {
                drawRoundRect(
                    color = com.mauztube.ui.theme.BrandRed,
                    topLeft = androidx.compose.ui.geometry.Offset(stroke / 2, stroke / 2),
                    size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(14.dp.toPx()),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(stroke),
                )
            }
        }
    }
}
