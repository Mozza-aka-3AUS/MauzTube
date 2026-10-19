package com.mauztube.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo

/**
 * Dimensioni REALI della finestra in dp (rotazione, multi-finestra, pieghevoli).
 * Non si usa `LocalConfiguration`: con il contesto della lingua dell'app resta quella
 * dell'avvio e non segue la rotazione.
 */
@Immutable
data class WindowSize(val widthDp: Float, val heightDp: Float, val fontScale: Float) {
    /** Orizzontale su telefono, pieghevoli aperti, tablet. */
    val wide: Boolean get() = widthDp >= 600f
    val landscape: Boolean get() = widthDp > heightDp
}

@Composable
fun windowSize(): WindowSize {
    val px = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    return WindowSize(px.width / density.density, px.height / density.density, density.fontScale)
}
