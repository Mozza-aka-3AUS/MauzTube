package com.mauztube.util

import com.mauztube.util.tr

import android.content.Context
import android.content.Intent

object Share {
    /** Condivide il link del video; con [startSeconds] il link parte da quel punto. */
    fun video(context: Context, title: String, videoId: String, startSeconds: Long? = null) {
        val link = "https://youtu.be/$videoId" + (startSeconds?.takeIf { it > 0 }?.let { "?t=$it" } ?: "")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, link)
        }
        context.startActivity(Intent.createChooser(intent, tr("Share video", "Condividi video")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
