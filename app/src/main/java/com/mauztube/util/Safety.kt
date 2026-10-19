package com.mauztube.util

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Rete di sicurezza per i lavori in background: un errore imprevisto (rete assente, risposta
 * di YouTube cambiata, database occupato…) in un'operazione secondaria viene registrato
 * invece di chiudere l'app. Va passato a `launch(SafeHandler)` e agli scope applicativi.
 */
val SafeHandler = CoroutineExceptionHandler { _, e ->
    Log.w("MauzTube", "Errore in background (app non chiusa)", e)
    CrashLog.record(e, fatal = false)
}

/**
 * Registro locale degli errori: l'ultimo crash (e gli ultimi errori gestiti) restano in un
 * file dell'app, che l'utente può condividere dalle Impostazioni. Nessun invio automatico.
 */
object CrashLog {
    private var app: Context? = null
    // Risolti solo quando servono (accesso al disco): l'avvio dell'app non li paga.
    private val file: File? by lazy { app?.let { File(it.filesDir, "error-log.txt") } }
    private val version: String by lazy {
        app?.let { runCatching { it.packageManager.getPackageInfo(it.packageName, 0).versionName }.getOrNull() } ?: "?"
    }
    private const val MAX_BYTES = 64 * 1024

    fun install(context: Context) {
        app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { record(e, fatal = true) }
            previous?.uncaughtException(thread, e)
        }
    }

    @Synchronized
    fun record(e: Throwable, fatal: Boolean) {
        val f = file ?: return
        runCatching {
            if (f.length() > MAX_BYTES) f.writeText(f.readText().takeLast(MAX_BYTES / 2))
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
            f.appendText(
                "=== $time ${if (fatal) "CRASH" else "error"} · v$version · " +
                    "Android ${Build.VERSION.RELEASE} · ${Build.MANUFACTURER} ${Build.MODEL}\n" +
                    Log.getStackTraceString(e).lineSequence().take(40).joinToString("\n") + "\n\n"
            )
            if (fatal) File(f.parentFile, "crashed.flag").writeText("1")
        }
    }

    fun read(): String? = file?.takeIf { it.exists() && it.length() > 0 }?.readText()

    fun clear() {
        file?.delete()
        file?.parentFile?.let { File(it, "crashed.flag").delete() }
    }

    /** true una sola volta dopo un crash: per avvisare l'utente al riavvio. */
    fun consumeCrashFlag(): Boolean {
        val flag = file?.parentFile?.let { File(it, "crashed.flag") } ?: return false
        return flag.exists().also { if (it) flag.delete() }
    }
}
