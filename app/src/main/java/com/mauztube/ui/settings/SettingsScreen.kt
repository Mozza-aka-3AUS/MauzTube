package com.mauztube.ui.settings

import com.mauztube.util.tr

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.TextButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mauztube.data.account.AccountManager
import com.mauztube.data.prefs.AppSettings
import com.mauztube.data.prefs.ThemeMode
import android.os.Build
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import com.mauztube.data.prefs.SettingsRepository
import com.mauztube.data.prefs.SponsorCategory
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    val repo: SettingsRepository,
    val account: AccountManager,
) : ViewModel() {
    /**
     * Stato mostrato: parte dal valore già in memoria e viene aggiornato SUBITO al tocco
     * (aggiornamento ottimistico); il salvataggio su disco avviene in background.
     */
    private val _settings = MutableStateFlow(repo.state.value)
    val settings: StateFlow<AppSettings?> = _settings

    init {
        viewModelScope.launch { repo.settings.collect { _settings.value = it } }
    }

    /** Applica [local] all'istante e poi persiste con [persist]. */
    fun set(local: AppSettings.() -> AppSettings, persist: suspend SettingsRepository.() -> Unit) {
        _settings.value = _settings.value?.local()
        viewModelScope.launch { repo.persist() }
    }

    /** All'uscita si azzera la fusione iscrizioni: un nuovo account riceverà quelle locali. */
    fun logout() {
        account.logout()
        viewModelScope.launch { repo.setSubscriptionsMerged(false) }
    }

    fun update(block: suspend SettingsRepository.() -> Unit) {
        viewModelScope.launch { repo.block() }
    }
}

@Composable
fun SettingsScreen(onLogin: () -> Unit, modifier: Modifier = Modifier, vm: SettingsViewModel = hiltViewModel()) {
    val s = vm.settings.collectAsStateWithLifecycle().value ?: return
    val loggedIn by vm.account.loggedIn.collectAsStateWithLifecycle()
    var cookieDialog by remember { mutableStateOf(false) }

    // Lista "lazy" a elementi piccoli: durante lo scorrimento si compone una riga alla volta.
    LazyColumn(modifier, contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "lang") { Section(tr("Language", "Lingua")) }
        item(key = "lang-sel") { com.mauztube.ui.onboarding.LanguageSelector(Modifier.fillMaxWidth().padding(vertical = 8.dp)) }
        divider("d-lang")

        item(key = "links") { Section(tr("YouTube links", "Link YouTube")) }
        item(key = "links-row") { OpenLinksRow() }
        divider("d-links")

        item(key = "look") { Section(tr("Appearance", "Aspetto")) }
        item(key = "theme") {
            Column {
                Text(tr("Theme", "Tema"), modifier = Modifier.padding(horizontal = 16.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    ThemeMode.entries.forEachIndexed { i, mode ->
                        SegmentedButton(
                            selected = s.themeMode == mode,
                            onClick = { vm.set({ copy(themeMode = mode) }) { setThemeMode(mode) } },
                            shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                        ) { Text(mode.label) }
                    }
                }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            item(key = "dyn") {
                SwitchRow(
                    tr("System colors (Material You)", "Colori di sistema (Material You)"),
                    tr("Turn off to use MauzTube's colors", "Disattiva per usare i colori di MauzTube"),
                    s.dynamicColor,
                ) { v -> vm.set({ copy(dynamicColor = v) }) { setDynamicColor(v) } }
            }
        }
        divider("d-look")

        item(key = "acc") { Section(tr("Google account", "Account Google")) }
        item(key = "acc-row") {
            Column {
                Text(
                    if (loggedIn) tr("Connected: home and related videos are personalized.", "Connesso: home e correlati sono personalizzati.")
                    else tr("Not connected: sign in to see your personalized recommendations.", "Non connesso: accedi per vedere i consigliati del tuo profilo."),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (loggedIn) {
                        TextButton(onClick = { vm.logout() }) { Text(tr("Sign out", "Esci")) }
                    } else {
                        TextButton(onClick = onLogin) { Text(tr("Sign in with Google", "Accedi con Google")) }
                        TextButton(onClick = { cookieDialog = true }) { Text(tr("Import cookies", "Importa cookie")) }
                    }
                }
            }
        }
        if (loggedIn) {
            item(key = "acc-hist") {
                SwitchRow(
                    tr("Update YouTube watch history", "Aggiorna la cronologia YouTube"),
                    tr("Videos watched here improve your account's recommendations", "I video visti qui migliorano i consigliati dell'account"),
                    s.reportWatchHistory,
                ) { v -> vm.set({ copy(reportWatchHistory = v) }) { setReportWatchHistory(v) } }
            }
        }
        divider("d-acc")

        item(key = "sb") { Section("SponsorBlock") }
        item(key = "sb-on") {
            SwitchRow(tr("Skip segments automatically", "Salta segmenti automaticamente"), tr("Data from sponsor.ajay.app", "Dati forniti da sponsor.ajay.app"), s.sponsorBlockEnabled) { v ->
                vm.set({ copy(sponsorBlockEnabled = v) }) { setSponsorBlockEnabled(v) }
            }
        }
        if (s.sponsorBlockEnabled) {
            item(key = "sb-notify") {
                SwitchRow(tr("Notify when skipping", "Notifica quando salta"), tr("With an option to undo", "Con possibilità di annullare"), s.sponsorNotify) { v ->
                    vm.set({ copy(sponsorNotify = v) }) { setSponsorNotify(v) }
                }
            }
            items(SponsorCategory.entries, key = { "sb-" + it.key }) { cat ->
                val checked = cat.key in s.sponsorCategories
                val toggle = { on: Boolean ->
                    vm.set({ copy(sponsorCategories = if (on) sponsorCategories + cat.key else sponsorCategories - cat.key) }) {
                        setSponsorCategory(cat.key, on)
                    }
                }
                Row(
                    Modifier.fillMaxWidth().clickable { toggle(!checked) }.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, onCheckedChange = { toggle(it) })
                    Column {
                        Text(cat.label)
                        Text(cat.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item(key = "sb-server") {
                var server by remember(s.sponsorServer) { mutableStateOf(s.sponsorServer) }
                Column {
                    OutlinedTextField(
                        value = server,
                        onValueChange = { server = it },
                        label = { Text(tr("API server", "Server API")) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        supportingText = { Text(tr("Default: ${AppSettings.DEFAULT_SPONSOR_SERVER}", "Predefinito: ${AppSettings.DEFAULT_SPONSOR_SERVER}")) },
                    )
                    if (server != s.sponsorServer && server.startsWith("http")) {
                        TextButton(
                            onClick = { vm.set({ copy(sponsorServer = server.trim().trimEnd('/')) }) { setSponsorServer(server) } },
                            modifier = Modifier.padding(horizontal = 8.dp),
                        ) { Text(tr("Save server", "Salva server")) }
                    }
                }
            }
        }
        divider("d-sb")

        item(key = "play") { Section(tr("Playback", "Riproduzione")) }
        item(key = "play-bg") {
            SwitchRow(
                tr("Keep playing in the background", "Continua in background"),
                tr("Outside the app or with the screen off, audio keeps playing with controls in the notification", "Fuori dall'app o a schermo spento continua solo l'audio, con i controlli nella notifica"),
                s.backgroundPlayback,
            ) { v -> vm.set({ copy(backgroundPlayback = v) }) { setBackgroundPlayback(v) } }
        }
        item(key = "play-pip") {
            SwitchRow(
                tr("Automatic pop-up", "Popup automatico"),
                tr("Leaving the app while a video is playing keeps it in a floating window", "Uscendo dall'app con un video in riproduzione, continua in una finestra mobile"),
                s.autoPip,
            ) { v -> vm.set({ copy(autoPip = v) }) { setAutoPip(v) } }
        }
        item(key = "play-dis") {
            SwitchRow(
                tr("Show dislike count", "Mostra i \"non mi piace\""),
                tr("Estimated counts from Return YouTube Dislike (returnyoutubedislike.com)", "Conteggi stimati da Return YouTube Dislike (returnyoutubedislike.com)"),
                s.showDislikes,
            ) { v -> vm.set({ copy(showDislikes = v) }) { setShowDislikes(v) } }
        }
        divider("d-play")

        item(key = "res") { Section(tr("Resume playback", "Ripresa riproduzione")) }
        item(key = "res-on") {
            SwitchRow(tr("Resume where you left off", "Riprendi dal punto interrotto"), tr("Position saved for every video", "Posizione salvata per ogni video"), s.resumeEnabled) { v ->
                vm.set({ copy(resumeEnabled = v) }) { setResumeEnabled(v) }
            }
        }
        item(key = "res-reopen") {
            SwitchRow(
                tr("Reopen the last video on launch", "Riapri l'ultimo video all'avvio"),
                tr("If the app is closed while playing", "Se l'app viene chiusa durante la riproduzione"),
                s.reopenLastVideo,
            ) { v -> vm.set({ copy(reopenLastVideo = v) }) { setReopenLastVideo(v) } }
        }
        item(key = "res-interval") {
            // Il cursore muove solo uno stato locale; si salva una volta, al rilascio.
            var interval by remember(s.saveIntervalSeconds) { mutableFloatStateOf(s.saveIntervalSeconds.toFloat()) }
            Column {
                Text(
                    tr("Save progress every ${interval.toInt()} s", "Salvataggio periodico ogni ${interval.toInt()} s"),
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Slider(
                    value = interval,
                    onValueChange = { interval = it },
                    onValueChangeFinished = {
                        val v = interval.toInt()
                        vm.set({ copy(saveIntervalSeconds = v) }) { setSaveInterval(v) }
                    },
                    valueRange = 1f..30f,
                    steps = 28,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        divider("d-res")

        item(key = "subs") { Section(tr("Subtitles", "Sottotitoli")) }
        item(key = "subs-auto") {
            SwitchRow(
                tr("Turn on automatically", "Attiva automaticamente"),
                tr("In the app language when available (otherwise from the player's CC button)", "Nella lingua dell'app, se disponibile (altrimenti dal pulsante CC del player)"),
                s.subtitlesAuto,
            ) { v -> vm.set({ copy(subtitlesAuto = v) }) { setSubtitlesAuto(v) } }
        }
        divider("d-subs")

        item(key = "q") { Section(tr("Video quality", "Qualità video")) }
        item(key = "q-info") {
            Text(
                tr(
                    "The player picks the quality based on your connection, between minimum and " +
                        "maximum: playback starts faster and you can still lock it from the player's gear.",
                    "Il player sceglie la qualità in base alla rete restando tra minima e massima: " +
                        "l'avvio è più rapido e puoi comunque fissarla dall'ingranaggio del player.",
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        item(key = "q-min") {
            QualityRow(tr("Minimum", "Minima"), s.minVideoHeight) { h ->
                vm.set({ copy(minVideoHeight = h, maxVideoHeight = maxOf(maxVideoHeight, h)) }) { setMinVideoHeight(h) }
            }
        }
        item(key = "q-max") {
            QualityRow(tr("Maximum", "Massima"), s.maxVideoHeight) { h ->
                vm.set({ copy(maxVideoHeight = h, minVideoHeight = minOf(minVideoHeight, h)) }) { setMaxVideoHeight(h) }
            }
        }
        divider("d-q")

        item(key = "shorts") { Section(tr("Shorts", "Shorts")) }
        item(key = "shorts-info") {
            Text(
                tr("Shorts are always hidden from home, search, channels, subscriptions and related videos.", "Gli Shorts sono sempre nascosti da home, ricerca, canali, iscrizioni e video correlati."),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        item(key = "shorts-aggr") {
            SwitchRow(
                tr("Aggressive filter", "Filtro aggressivo"),
                tr("Also hides videos ≤ 60 s not marked as Shorts", "Nasconde anche i video ≤ 60 s non marcati come Short"),
                s.aggressiveShortsFilter,
            ) { v -> vm.set({ copy(aggressiveShortsFilter = v) }) { setAggressiveShorts(v) } }
        }
    }

    if (cookieDialog) {
        var raw by remember { mutableStateOf("") }
        var error by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { cookieDialog = false },
            title = { Text(tr("Import YouTube cookies", "Importa cookie di YouTube")) },
            text = {
                Column {
                    Text(
                        tr(
                            "Alternative if Google blocks sign-in inside the app. In a desktop browser " +
                                "signed in to youtube.com: Developer tools → Network → a request to " +
                                "youtube.com → copy the value of the \"Cookie\" header and paste it here.",
                            "Alternativa se Google blocca il login nell'app. Da un browser desktop " +
                                "loggato su youtube.com: Strumenti sviluppatore → Rete → una richiesta a " +
                                "youtube.com → copia il valore dell'header \"Cookie\" e incollalo qui.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = raw,
                        onValueChange = { raw = it; error = false },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        maxLines = 4,
                        isError = error,
                        supportingText = { if (error) Text(tr("SAPISID cookie not found", "Cookie SAPISID non trovato")) },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (vm.account.importCookieHeader(raw)) cookieDialog = false else error = true
                }) { Text(tr("Import", "Importa")) }
            },
            dismissButton = { TextButton(onClick = { cookieDialog = false }) { Text(tr("Cancel", "Annulla")) } },
        )
    }
}

@Composable
private fun QualityRow(label: String, selected: Int, onSelect: (Int) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 16.dp, top = 12.dp))
    FlowRow(
        Modifier.padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppSettings.QUALITY_OPTIONS.forEach { h ->
            FilterChip(
                selected = selected == h,
                onClick = { onSelect(h) },
                label = { Text("${h}p") },
            )
        }
    }
}

private fun LazyListScope.divider(key: String) = item(key = key) { HorizontalDivider(Modifier.padding(top = 4.dp)) }

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Stato e attivazione dell'apertura dei link YouTube con MauzTube (ricontrollato al ritorno). */
@Composable
private fun OpenLinksRow() {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Lo stato si chiede al sistema (chiamata IPC): fuori dal thread dell'interfaccia,
    // e di nuovo a ogni ritorno sulla schermata (l'utente potrebbe averlo appena attivato).
    var enabled by remember { mutableStateOf<Boolean?>(null) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
            enabled = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                com.mauztube.util.OpenLinks.isEnabled(context)
            }
        }
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(
            when (enabled) {
                true -> tr("YouTube links open in MauzTube.", "I link YouTube si aprono in MauzTube.")
                false -> tr("YouTube links don't open in MauzTube yet.", "I link YouTube non si aprono ancora in MauzTube.")
                null -> tr("Open YouTube links with MauzTube.", "Apri i link YouTube con MauzTube.")
            },
            color = if (enabled == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        Text(
            com.mauztube.util.OpenLinks.instructions(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (enabled != true) {
            TextButton(onClick = { com.mauztube.util.OpenLinks.openSettings(context) }) {
                Text(tr("Enable YouTube links", "Attiva i link YouTube"))
            }
        }
    }
}
