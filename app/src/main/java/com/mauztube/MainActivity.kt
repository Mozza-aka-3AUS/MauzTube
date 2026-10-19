package com.mauztube

import android.content.Context
import kotlinx.coroutines.flow.combine
import com.mauztube.player.PlaybackManager
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.annotation.RequiresApi
import android.util.Rational
import android.os.Build
import android.content.pm.PackageManager
import android.app.PictureInPictureParams
import android.content.Intent
import com.mauztube.util.I18n
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import android.graphics.Color
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mauztube.ui.theme.isAppInDarkTheme
import kotlinx.coroutines.runBlocking
import androidx.lifecycle.lifecycleScope
import com.mauztube.data.prefs.SettingsRepository
import com.mauztube.data.repository.PlaybackRepository
import com.mauztube.ui.navigation.AppNavigation
import com.mauztube.ui.theme.MauzTubeTheme
import com.mauztube.util.DeepLink
import com.mauztube.util.LinkParser
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var playback: PlaybackRepository
    @Inject lateinit var settings: SettingsRepository
    @Inject lateinit var playbackManager: PlaybackManager

    /** true mentre l'app è nella finestra Picture-in-Picture (popup). */
    private val inPip = mutableStateOf(false)
    private var pipJustClosed = false
    private var autoPipSetting = true


    private val deepLinks = Channel<DeepLink>(Channel.BUFFERED)

    // Risorse di sistema e formati nella lingua scelta nell'app.
    override fun attachBaseContext(newBase: Context) {
        I18n.init(newBase)
        super.attachBaseContext(newBase)
        // Solo la lingua viene sovrascritta: il resto della configurazione (rotazione,
        // dimensioni, densità) continua a seguire il dispositivo. Con un contesto creato da
        // una copia completa della configurazione, dopo una rotazione l'interfaccia credeva
        // di essere ancora in verticale.
        applyOverrideConfiguration(android.content.res.Configuration().apply { setLocale(I18n.locale) })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            if (!com.mauztube.util.CrashLog.consumeCrashFlag()) return@launch
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                android.widget.Toast.makeText(
                    this@MainActivity,
                    com.mauztube.util.tr(
                        "MauzTube closed because of an error. You can share the report from Settings.",
                        "MauzTube si è chiuso per un errore. Puoi condividere il rapporto dalle Impostazioni.",
                    ),
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
        }

        if (savedInstanceState == null) {
            val link = LinkParser.fromIntent(intent)
            if (link != null) {
                deepLinks.trySend(link)
            } else {
                // RIPRESA ALL'AVVIO: se l'app è stata chiusa (o uccisa) durante un video,
                // lo riapre; il PlayerViewModel farà seek alla posizione salvata in Room.
                lifecycleScope.launch {
                    if (settings.current().reopenLastVideo) {
                        playback.activeVideo()?.let { deepLinks.send(DeepLink.Video(it)) }
                    }
                }
            }
        }

        // Lettura sincrona (pochi ms, file locale) per applicare subito il tema scelto
        // ed evitare un lampo del tema sbagliato all'avvio.
        val initialSettings = runBlocking { settings.current() }

        // ---- Popup (Picture-in-Picture) ----
        addOnPictureInPictureModeChangedListener { info ->
            inPip.value = info.isInPictureInPictureMode
            if (!info.isInPictureInPictureMode) {
                // Se subito dopo arriva onStop, il popup è stato chiuso dall'utente.
                pipJustClosed = true
                window.decorView.postDelayed({ pipJustClosed = false }, 1_000)
            }
        }
        // Parametri del popup aggiornati dagli stati osservabili del player (senza crearlo).
        lifecycleScope.launch {
            combine(
                settings.settings, playbackManager.nowPlaying, playbackManager.audioOnly,
                playbackManager.isPlaying, playbackManager.videoSize,
            ) { s, _, _, _, _ -> s.autoPip }
                .collect { autoPipSetting = it; updatePipParams() }
        }

        setContent {
            val appSettings by settings.settings.collectAsStateWithLifecycle(initialSettings)
            val dark = isAppInDarkTheme(appSettings.themeMode)
            // Icone della barra di stato coerenti con il tema dell'app, non con quello di sistema.
            LaunchedEffect(dark) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            MauzTubeTheme(mode = appSettings.themeMode, dynamicColor = appSettings.dynamicColor) {
                CompositionLocalProvider(LocalInPip provides inPip.value) {
                    AppNavigation(deepLinks.receiveAsFlow())
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        LinkParser.fromIntent(intent)?.let { deepLinks.trySend(it) }
    }

    // ---------------- Picture-in-Picture ----------------

    private val pipSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    /** Popup automatico solo se c'è un video (non solo audio) in riproduzione. */
    private fun canAutoPip(): Boolean =
        autoPipSetting && playbackManager.nowPlaying.value != null &&
            playbackManager.isPlaying.value && !playbackManager.audioOnly.value

    @RequiresApi(Build.VERSION_CODES.O)
    private fun pipParams(): PictureInPictureParams {
        val size = playbackManager.videoSize.value
        val ratio = if (size.width > 0 && size.height > 0) {
            // Android accetta rapporti tra 1:2,39 e 2,39:1.
            val r = size.width.toFloat() * size.pixelWidthHeightRatio / size.height
            val clamped = r.coerceIn(1f / 2.39f, 2.39f)
            Rational((clamped * 1000).toInt(), 1000)
        } else Rational(16, 9)
        val builder = PictureInPictureParams.Builder().setAspectRatio(ratio)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(canAutoPip()).setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    private fun updatePipParams() {
        if (!pipSupported) return
        runCatching { setPictureInPictureParams(pipParams()) }
    }

    /** Pulsante "Popup" nel player. */
    fun enterPip() {
        if (!pipSupported || playbackManager.nowPlaying.value == null) return
        if (playbackManager.audioOnly.value) playbackManager.setAudioOnly(false)
        runCatching { enterPictureInPictureMode(pipParams()) }
    }

    // Android 8–11: niente auto-enter di sistema, si entra quando l'utente esce (tasto Home).
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (pipSupported && Build.VERSION.SDK_INT < Build.VERSION_CODES.S && canAutoPip()) enterPip()
    }

    override fun onStop() {
        super.onStop()
        // Popup chiuso con la X: come su YouTube il video si ferma.
        if (pipJustClosed && playbackManager.nowPlaying.value != null) playbackManager.player.pause()
    }

}

/** true se l'app è nella finestra popup: si mostra solo il video. */
val LocalInPip = staticCompositionLocalOf { false }
