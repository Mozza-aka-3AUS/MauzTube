package com.mauztube.ui.playlists

import com.mauztube.util.tr

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mauztube.ui.components.AppViewModel

/**
 * Finestra "Salva in playlist": Guarda più tardi, playlist del telefono (con l'account collegato
 * sono sincronizzate con la loro copia su YouTube) e altre playlist dell'account, ognuna con lo
 * stato reale del video. Ogni scelta mostra un messaggio di conferma.
 */
@Composable
fun SaveToPlaylistDialog(app: AppViewModel) {
    val video = app.playlistTarget.collectAsStateWithLifecycle().value ?: return
    val wl by app.watchLaterIds.collectAsStateWithLifecycle()
    val local by app.localPlaylists.collectAsStateWithLifecycle()
    val remote by app.accountOptions.collectAsStateWithLifecycle()
    val remoteLoading by app.accountLoading.collectAsStateWithLifecycle()
    val loggedIn by app.account.loggedIn.collectAsStateWithLifecycle()
    val containing by remember(video.id) { app.playlistsContaining(video.id) }.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = app::dismissPlaylistDialog,
        title = { Text(tr("Save to playlist", "Salva in playlist")) },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                CheckRow(tr("Watch later", "Guarda più tardi"), video.id in wl) { app.toggleWatchLater(video) }
                if (local.isNotEmpty()) {
                    SectionLabel(if (loggedIn) tr("Your playlists (synced)", "Le tue playlist (sincronizzate)") else tr("Your playlists", "Le tue playlist"))
                    local.forEach { p ->
                        val inside = p.id in containing
                        CheckRow(p.name, inside) { app.toggleLocalPlaylist(p.id, video, inside) }
                    }
                }
                if (loggedIn && (remoteLoading || remote.isNotEmpty())) {
                    SectionLabel(tr("Other playlists on your Google account", "Altre playlist dell'account Google"))
                    if (remoteLoading) {
                        Row(Modifier.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(tr("  Loading…", "  Caricamento…"), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    remote.forEach { o -> CheckRow(o.title, o.containsVideo) { app.toggleAccountPlaylist(o, video) } }
                }
                Row(
                    Modifier.fillMaxWidth().clickable { creating = true }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Add, null)
                    Text(tr("  New playlist", "  Nuova playlist"))
                }
                if (loggedIn) {
                    Text(
                        tr("New playlists are also created, private, on your Google account.",
                            "Le nuove playlist vengono create anche sul tuo account Google, private."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = app::dismissPlaylistDialog) { Text(tr("Done", "Fine")) } },
    )

    if (creating) {
        NewPlaylistDialog(onDismiss = { creating = false }, onCreate = { app.createPlaylistWith(it, video); creating = false })
    }
}

@Composable
private fun SectionLabel(text: String) {
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Text(label, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    }
}
