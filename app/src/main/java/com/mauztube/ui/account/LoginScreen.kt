package com.mauztube.ui.account

import com.mauztube.util.tr

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.mauztube.data.account.AccountManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(val account: AccountManager) : ViewModel()

/**
 * Login Google in un WebView: al termine i cookie di sessione di youtube.com restano nel
 * CookieManager e vengono riusati da [com.mauztube.data.innertube.InnerTubeClient].
 *
 * Google rifiuta i login dai WebView "riconoscibili". Per questo:
 *  - lo User-Agent è quello di Chrome mobile, senza il marcatore "; wv" (i WebView
 *    aggiornati non inviano più nemmeno l'header X-Requested-With).
 * Se Google mostra comunque "Questo browser potrebbe non essere sicuro", usare
 * Impostazioni → "Importa cookie" come alternativa.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(onDone: () -> Unit, vm: LoginViewModel = hiltViewModel()) {
    var loading by remember { mutableStateOf(true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr("Sign in to Google", "Accedi a Google")) },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.Default.Close, tr("Close", "Chiudi")) } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            Box(Modifier.fillMaxSize()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.userAgentString = CHROME_UA
                            CookieManager.getInstance().setAcceptCookie(true)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                    loading = true
                                }

                                override fun onPageFinished(view: WebView, url: String) {
                                    loading = false
                                    // Login completato quando torniamo su youtube.com con i cookie di sessione.
                                    if (url.contains("youtube.com") && !url.contains("accounts.google")) {
                                        CookieManager.getInstance().flush()
                                        vm.account.refresh()
                                        if (vm.account.hasSession.value) onDone()
                                    }
                                }
                            }
                            loadUrl(LOGIN_URL)
                        }
                    },
                    onRelease = { it.destroy() },
                )
            }
        }
    }
}

private const val LOGIN_URL =
    "https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true" +
        "&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Dm" +
        "%26next%3Dhttps%253A%252F%252Fm.youtube.com%252F"

private const val CHROME_UA =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/140.0.0.0 Mobile Safari/537.36"
