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

    fun init(context: Context) {
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        // memoryClass = memoria massima per app in MB (512+ sui telefoni recenti, 128–192 sugli economici).
        lowMemory = am.isLowRamDevice || am.memoryClass <= 192
    }
}
