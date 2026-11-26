package com.mauztube.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * Trasmissione dello schermo a una TV senza librerie proprietarie: si apre la schermata di
 * sistema per la trasmissione (Chromecast/Google TV, Miracast…) o, sui Samsung, Smart View.
 * Il video continua a essere riprodotto dal telefono e la TV mostra lo schermo.
 */
object ScreenCast {
    fun open(context: Context) {
        val candidates = buildList {
            // Samsung: Smart View (l'azione standard spesso non c'è).
            context.packageManager.getLaunchIntentForPackage(SAMSUNG_SMART_VIEW)?.let(::add)
            add(Intent(CAST_SETTINGS))
            add(Intent(WIFI_DISPLAY_SETTINGS))
        }
        for (intent in candidates) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        Feedback.show(
            tr(
                "Screen casting isn't available on this device",
                "La trasmissione dello schermo non è disponibile su questo dispositivo",
            )
        )
    }

    private const val CAST_SETTINGS = Settings.ACTION_CAST_SETTINGS
    private const val WIFI_DISPLAY_SETTINGS = "android.settings.WIFI_DISPLAY_SETTINGS"
    private const val SAMSUNG_SMART_VIEW = "com.samsung.android.smartmirroring"
}
