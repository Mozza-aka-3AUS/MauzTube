package com.mauztube.data.account

import android.util.Log
import android.webkit.CookieManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sessione Google/YouTube basata sui cookie del WebView di login.
 *
 * Non esiste un'API pubblica per leggere la home personalizzata: l'unico modo è comportarsi
 * come www.youtube.com, cioè inviare i cookie di sessione più l'header
 * `Authorization: SAPISIDHASH …` richiesto da InnerTube per le richieste autenticate.
 * I cookie restano nel CookieManager di sistema (persistente e cifrato a riposo dal sistema).
 */
@Singleton
class AccountManager @Inject constructor() {

    private val cookies: CookieManager? by lazy {
        runCatching { CookieManager.getInstance() }
            .onFailure { Log.w(TAG, "WebView non disponibile", it) }
            .getOrNull()
    }

    /** C'è una sessione Google salvata sul dispositivo. */
    private val _hasSession = MutableStateFlow(false)
    val hasSession: StateFlow<Boolean> = _hasSession

    /**
     * MODALITÀ ANONIMO (solo per la sessione corrente): l'account non viene usato e nulla
     * viene registrato (cronologia, posizioni, ricerche, ping a YouTube).
     */
    private val _incognito = MutableStateFlow(false)
    val incognito: StateFlow<Boolean> = _incognito

    /** Account utilizzabile: sessione presente e modalità anonimo spenta. */
    private val _loggedIn = MutableStateFlow(false)
    val loggedIn: StateFlow<Boolean> = _loggedIn

    init { refresh() }

    fun refresh() {
        _hasSession.value = cookieMap()[SAPISID] != null || cookieMap()[SAPISID_3P] != null
        _loggedIn.value = _hasSession.value && !_incognito.value
    }

    fun setIncognito(enabled: Boolean) {
        _incognito.value = enabled
        _loggedIn.value = _hasSession.value && !enabled
    }

    fun cookieHeader(): String? = cookies?.getCookie(YT_URL)?.takeIf { it.isNotBlank() }

    /** Salva i Set-Cookie ricevuti da YouTube, così la sessione resta allineata. */
    fun storeResponseCookies(setCookies: List<String>) {
        val cm = cookies ?: return
        setCookies.forEach { cm.setCookie(YT_URL, it) }
        if (setCookies.isNotEmpty()) cm.flush()
    }

    /** Header di autenticazione InnerTube, o mappa vuota se non loggato. */
    fun authHeaders(origin: String = ORIGIN): Map<String, String> {
        if (_incognito.value) return emptyMap()
        val map = cookieMap()
        val cookie = cookieHeader() ?: return emptyMap()
        val ts = System.currentTimeMillis() / 1000
        val parts = buildList {
            (map[SAPISID] ?: map[SAPISID_3P])?.let { add("SAPISIDHASH ${ts}_${sha1("$ts $it $origin")}") }
            map[SAPISID_1P]?.let { add("SAPISID1PHASH ${ts}_${sha1("$ts $it $origin")}") }
            map[SAPISID_3P]?.let { add("SAPISID3PHASH ${ts}_${sha1("$ts $it $origin")}") }
        }
        if (parts.isEmpty()) return emptyMap()
        return mapOf(
            "Cookie" to cookie,
            "Authorization" to parts.joinToString(" "),
            "X-Goog-AuthUser" to "0",
            "X-Origin" to origin,
            "Origin" to origin,
        )
    }

    /**
     * Login alternativo per quando Google rifiuta il WebView: incolla l'header Cookie
     * copiato da un browser desktop (DevTools → Network → una richiesta a youtube.com).
     */
    fun importCookieHeader(raw: String): Boolean {
        val cm = cookies ?: return false
        raw.removePrefix("Cookie:").split(';')
            .map { it.trim() }
            .filter { '=' in it }
            .forEach { cm.setCookie(YT_URL, "$it; Domain=.youtube.com; Path=/; Secure") }
        cm.flush()
        refresh()
        return _hasSession.value
    }

    fun logout() {
        cookies?.removeAllCookies(null)
        cookies?.flush()
        _hasSession.value = false
        _loggedIn.value = false
    }

    private fun cookieMap(): Map<String, String> =
        cookieHeader().orEmpty().split(';').mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else it.substring(0, i).trim() to it.substring(i + 1).trim()
        }.toMap()

    private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
        .joinToString("") { "%02x".format(it) }

    companion object {
        private const val TAG = "AccountManager"
        const val YT_URL = "https://www.youtube.com"
        const val ORIGIN = "https://www.youtube.com"
        private const val SAPISID = "SAPISID"
        private const val SAPISID_1P = "__Secure-1PAPISID"
        private const val SAPISID_3P = "__Secure-3PAPISID"
    }
}
