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

/** Finestra "Salva in playlist": Guarda più tardi, playlist locali, playlist dell'account. */
@Composable
fun SaveToPlaylistDialog(app: AppViewModel) {
    val video = app.playlistTarget.collectAsStateWithLifecycle().value ?: return
    val wl by app.watchLaterIds.collectAsStateWithLifecycle()
    val local by app.localPlaylists.collectAsStateWithLifecycle()
    val remote by app.accountPlaylists.collectAsStateWithLifecycle()
    val containing by remember(video.id) { app.playlistsContaining(video.id) }.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    val addedRemote = remember(video.id) { mutableStateOf(setOf<String>()) }

    AlertDialog(
        onDismissRequest = app::dismissPlaylistDialog,
        title = { Text(tr("Save to playlist", "Salva in playlist")) },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                CheckRow(tr("Watch later", "Guarda più tardi"), video.id in wl) { app.toggleWatchLater(video) }
                local.forEach { p ->
                    val inside = p.id in containing
                    CheckRow(p.name, inside) { app.toggleLocalPlaylist(p.id, video, inside) }
                }
                if (remote.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    Text(tr("Google account", "Account Google"), style = MaterialTheme.typography.labelLarge)
                    remote.forEach { p ->
                        val done = p.playlistId in addedRemote.value
                        CheckRow(p.title, done) {
                            if (!done) {
                                app.addToAccountPlaylist(p.playlistId, video)
                                addedRemote.value = addedRemote.value + p.playlistId
                            }
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().clickable { creating = true }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Add, null)
                    Text(tr("  New playlist", "  Nuova playlist"))
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
private fun CheckRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Text(label, maxLines = 1)
    }
}
