package com.mauztube.ui.playlists

import com.mauztube.util.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.mauztube.data.account.AccountManager
import com.mauztube.data.innertube.InnerTubeParser
import com.mauztube.data.local.PlaylistSummary
import com.mauztube.data.repository.LibraryRepository
import com.mauztube.data.repository.PlaylistRepository
import com.mauztube.ui.theme.AppIcons
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaylistsViewModel @Inject constructor(
    private val playlists: PlaylistRepository,
    private val library: LibraryRepository,
    private val ratings: com.mauztube.data.repository.RatingRepository,
    val account: AccountManager,
) : ViewModel() {
    val likedCount: StateFlow<Int> = ratings.liked.map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val likedCover: StateFlow<String?> = ratings.liked.map { it.firstOrNull()?.thumbnailUrl }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val local: StateFlow<List<PlaylistSummary>> = playlists.playlists
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val watchLaterCount: StateFlow<Int> = library.watchLaterIds.map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val watchLaterCover: StateFlow<String?> = library.watchLater.map { it.firstOrNull()?.thumbnailUrl }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _remote = MutableStateFlow<List<InnerTubeParser.PlaylistRef>>(emptyList())
    val remote: StateFlow<List<InnerTubeParser.PlaylistRef>> = _remote

    init {
        viewModelScope.launch(com.mauztube.util.SafeHandler) { ratings.syncLikedFromAccount() }
        viewModelScope.launch(com.mauztube.util.SafeHandler) {
            library.syncFromAccount()
            _remote.value = runCatching { playlists.accountPlaylists() }.getOrDefault(emptyList())
        }
    }

    fun create(name: String) = viewModelScope.launch(com.mauztube.util.SafeHandler) { playlists.create(name) }
}

/** Sezione Playlist: "Guarda più tardi", playlist locali e playlist dell'account. */
@Composable
fun PlaylistsScreen(
    onOpen: (kind: String, id: String, title: String) -> Unit,
    onDownloads: () -> Unit,
    modifier: Modifier = Modifier,
    vm: PlaylistsViewModel = hiltViewModel(),
) {
    val local by vm.local.collectAsStateWithLifecycle()
    val remote by vm.remote.collectAsStateWithLifecycle()
    val wlCount by vm.watchLaterCount.collectAsStateWithLifecycle()
    val wlCover by vm.watchLaterCover.collectAsStateWithLifecycle()
    val likedCount by vm.likedCount.collectAsStateWithLifecycle()
    val likedCover by vm.likedCover.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }

    LazyColumn(modifier.fillMaxSize()) {
        item {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onDownloads).padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(AppIcons.Download, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                Text(tr("Downloads", "Download"), style = MaterialTheme.typography.titleSmall)
            }
        }
        item {
            PlaylistRow(
                title = tr("Watch later", "Guarda più tardi"),
                subtitle = tr("$wlCount videos", "$wlCount video"),
                cover = wlCover,
                icon = AppIcons.WatchLater,
                onClick = { onOpen(KIND_WATCH_LATER, "WL", tr("Watch later", "Guarda più tardi")) },
            )
        }
        item {
            PlaylistRow(
                title = tr("Liked videos", "Video che ti piacciono"),
                subtitle = tr("$likedCount videos", "$likedCount video"),
                cover = likedCover,
                icon = AppIcons.ThumbUp,
                onClick = { onOpen(KIND_LIKED, "LL", tr("Liked videos", "Video che ti piacciono")) },
            )
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(tr("Your playlists", "Le tue playlist"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                FilledTonalButton(onClick = { creating = true }) {
                    Icon(Icons.Default.Add, null)
                    Text(tr("New", "Nuova"))
                }
            }
        }
        if (local.isEmpty()) {
            item {
                Text(
                    tr("Create a playlist or use \"Save to playlist\" from a video's ⋮ menu.", "Crea una playlist o usa \"Salva in playlist\" dal menu ⋮ di un video."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        items(local, key = { "l${it.id}" }) { p ->
            PlaylistRow(p.name, tr("${p.count} videos", "${p.count} video"), p.cover, AppIcons.PlaylistPlay) {
                onOpen(KIND_LOCAL, p.id.toString(), p.name)
            }
        }
        if (remote.isNotEmpty()) {
            item {
                Text(
                    tr("From your Google account", "Dal tuo account Google"),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                )
            }
            items(remote, key = { "r${it.playlistId}" }) { p ->
                PlaylistRow(p.title, p.countText ?: tr("Playlists", "Playlist"), p.thumbnailUrl, AppIcons.PlaylistPlay) {
                    onOpen(KIND_ACCOUNT, p.playlistId, p.title)
                }
            }
        }
    }

    if (creating) {
        NewPlaylistDialog(onDismiss = { creating = false }, onCreate = { vm.create(it); creating = false })
    }
}

@Composable
fun NewPlaylistDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("New playlist", "Nuova playlist")) },
        text = {
            OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text(tr("Name", "Nome")) })
        },
        confirmButton = { TextButton(onClick = { onCreate(name) }, enabled = name.isNotBlank()) { Text(tr("Create", "Crea")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Cancel", "Annulla")) } },
    )
}

@Composable
private fun PlaylistRow(title: String, subtitle: String, cover: String?, icon: ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.width(132.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (cover != null) AsyncImage(cover, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(
                Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(6.dp))
                    .background(Color.Black.copy(alpha = 0.7f)).padding(4.dp)
            ) { Icon(icon, null, tint = Color.White) }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

const val KIND_WATCH_LATER = "wl"
const val KIND_LOCAL = "local"
const val KIND_ACCOUNT = "account"
const val KIND_LIKED = "liked"
/** Playlist pubblica aperta da un link. */
const val KIND_PUBLIC = "public"
