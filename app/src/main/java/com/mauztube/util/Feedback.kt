package com.mauztube.util

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * Brevi messaggi di conferma ("Aggiunto a Guarda più tardi", "Rimosso da …"). Si possono
 * mostrare da qualsiasi thread; un nuovo messaggio sostituisce subito il precedente, così
 * azioni rapide di fila non accodano una serie di avvisi.
 */
object Feedback {
    private var app: Context? = null
    private val main = Handler(Looper.getMainLooper())
    private var current: Toast? = null

    fun init(context: Context) {
        app = context.applicationContext
    }

    fun show(text: String) {
        val context = app ?: return
        main.post {
            current?.cancel()
            current = Toast.makeText(context, text, Toast.LENGTH_SHORT).also { it.show() }
        }
    }
}
