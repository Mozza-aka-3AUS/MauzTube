package com.mauztube.util

import com.mauztube.util.tr

import java.util.Locale

object Format {
    fun duration(seconds: Long): String {
        if (seconds <= 0) return ""
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.ROOT, "%d:%02d", m, s)
    }

    fun durationMs(ms: Long) = duration(ms / 1000)

    fun count(n: Long): String = when {
        n < 0 -> ""
        n >= 1_000_000_000 -> String.format(Locale.getDefault(), tr("%.1fB", "%.1f Mrd"), n / 1e9)
        n >= 1_000_000 -> String.format(Locale.getDefault(), tr("%.1fM", "%.1f Mln"), n / 1e6)
        n >= 1_000 -> String.format(Locale.getDefault(), "%.1f K", n / 1e3)
        else -> n.toString()
    }

    fun views(n: Long) = if (n < 0) "" else tr("${count(n)} views", "${count(n)} visualizzazioni")
}
