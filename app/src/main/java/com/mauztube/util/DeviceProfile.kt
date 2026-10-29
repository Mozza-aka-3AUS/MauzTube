package com.mauztube.util

import android.app.ActivityManager
import android.content.Context

/**
 * Caratteristiche del telefono che cambiano le scelte sulle risorse: sui telefoni con poca
 * memoria buffer e cache si riducono, per evitare chiusure dell'app per memoria esaurita.
 */
object DeviceProfile {
    @Volatile var lowMemory: Boolean = false
        private set

    /** Android TV / Google TV: telecomando invece del tocco (vedi ui/components/Tv.kt). */
    @Volatile var isTv: Boolean = false
        private set

    fun init(context: Context) {
        isTv = context.getSystemService(android.app.UiModeManager::class.java)?.currentModeType ==
            android.content.res.Configuration.UI_MODE_TYPE_TELEVISION ||
            context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        // memoryClass = memoria massima per app in MB (512+ sui telefoni recenti, 128–192 sugli economici).
        lowMemory = am.isLowRamDevice || am.memoryClass <= 192
    }
}
