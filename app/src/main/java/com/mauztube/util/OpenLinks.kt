package com.mauztube.util

import android.content.Context
import android.content.Intent
import android.content.pm.verify.domain.DomainVerificationUserState
import android.net.Uri
import android.os.Build
import android.provider.Settings

/**
 * Apertura dei link YouTube con MauzTube.
 *
 * Da Android 12 un'app apre da sola i link di un sito solo se il sito la "verifica"
 * (YouTube verifica solo l'app ufficiale). Per le altre app l'utente deve attivare i link a
 * mano: Impostazioni → App → MauzTube → Apri per impostazione predefinita → Aggiungi link.
 * Su Android 11 e precedenti compare invece il selettore "Apri con" (scegliere "Sempre").
 */
object OpenLinks {

    private val HOSTS = setOf("youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be")

    /** true/false su Android 12+, null se non determinabile (versioni precedenti). */
    fun isEnabled(context: Context): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        val manager = context.getSystemService(android.content.pm.verify.domain.DomainVerificationManager::class.java)
        val state = runCatching { manager.getDomainVerificationUserState(context.packageName) }.getOrNull() ?: return null
        return state.hostToStateMap.filterKeys { it in HOSTS }.values.any {
            it == DomainVerificationUserState.DOMAIN_STATE_SELECTED || it == DomainVerificationUserState.DOMAIN_STATE_VERIFIED
        }
    }

    /** Apre la schermata di sistema in cui attivare i link. */
    fun openSettings(context: Context) {
        val pkg = Uri.parse("package:${context.packageName}")
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, pkg)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)
        }
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
    }

    fun instructions(): String = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        tr(
            "Tap \"Add links\", select all YouTube addresses and confirm. If the official YouTube app is installed, " +
                "turn off \"Open supported links\" for it as well.",
            "Tocca \"Aggiungi link\", seleziona tutti gli indirizzi YouTube e conferma. Se hai l'app YouTube ufficiale, " +
                "disattiva anche per lei \"Apri link supportati\".",
        )
    } else {
        tr(
            "When you open a YouTube link, choose MauzTube and tap \"Always\".",
            "Quando apri un link YouTube, scegli MauzTube e tocca \"Sempre\".",
        )
    }
}
