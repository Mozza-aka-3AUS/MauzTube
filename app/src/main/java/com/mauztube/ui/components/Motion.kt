package com.mauztube.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer

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
