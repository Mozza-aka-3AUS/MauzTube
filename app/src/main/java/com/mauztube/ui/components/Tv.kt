package com.mauztube.ui.components

import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.mauztube.util.DeviceProfile

/** true quando l'app gira su Android TV / Google TV (si usa il telecomando). */
val LocalIsTv = staticCompositionLocalOf { false }

/**
 * Adattamenti per i televisori, attivi SOLO su TV (sui telefoni [content] viene mostrato così
 * com'è, senza nessun livello in più):
 *  - interfaccia ingrandita del 20%: si guarda dal divano, a qualche metro di distanza;
 *  - elemento selezionato dal telecomando ben visibile (alone nel colore del tema): con il
 *    tocco l'evidenziazione della selezione non serve, col telecomando è l'unico riferimento.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TvEnvironment(content: @Composable () -> Unit) {
    if (!DeviceProfile.isTv) {
        content()
        return
    }
    val d = LocalDensity.current
    CompositionLocalProvider(
        LocalIsTv provides true,
        LocalDensity provides Density(d.density * TV_SCALE, d.fontScale),
        LocalRippleConfiguration provides RippleConfiguration(
            color = MaterialTheme.colorScheme.primary,
            rippleAlpha = RippleAlpha(draggedAlpha = 0.16f, focusedAlpha = 0.38f, hoveredAlpha = 0.12f, pressedAlpha = 0.24f),
        ),
        content = content,
    )
}

private const val TV_SCALE = 1.2f
