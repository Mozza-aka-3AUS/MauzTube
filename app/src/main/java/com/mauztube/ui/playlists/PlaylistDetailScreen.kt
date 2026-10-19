package com.mauztube.ui.playlists

import com.mauztube.util.tr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.mauztube.data.model.Paged
import com.mauztube.data.model.VideoItem
import com.mauztube.data.model.VideoPreviews
import com.mauztube.data.repository.LibraryRepository
import com.mauztube.data.repository.PlaylistRepository
import com.mauztube.player.PlaybackManager
import com.mauztube.ui.components.ContentList
import com.mauztube.ui.components.MessageView
import com.mauztube.ui.components.PagedListController
import com.mauztube.ui.components.SkeletonList
import com.mauztube.ui.components.VideoCard
import com.mauztube.ui.navigation.PlaylistRoute
import com.mauztube.ui.theme.AppIcons
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val playlists: PlaylistRepository,
    private val library: LibraryRepository,
    private val playback: PlaybackManager,
    private val ratings: com.mauztube.data.repository.RatingRepository,
    private val youtube: com.mauztube.data.repository.YouTubeRepository,
) : ViewModel() {
    /** Titolo mostrato: per le playlist aperte da link arriva dalla rete. */
    private val _title = kotlinx.coroutines.flow.MutableStateFlow("")
    val title: StateFlow<String> get() = _title

    val route = savedState.toRoute<PlaylistRoute>().also { _title.value = it.title }

    /** Liste locali (WL e playlist locali): osservate dal DB. */
    val localItems: StateFlow<List<VideoItem>?> = when (route.kind) {
        KIND_WATCH_LATER -> library.watchLater
        KIND_LOCAL -> playlists.items(route.id.toLong())
        KIND_LIKED -> ratings.liked
        else -> emptyFlow()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Playlist dell'account: paginate dalla rete. */
    val remote = PagedListController<VideoItem>(viewModelScope) {
        when (route.kind) {
            KIND_ACCOUNT -> playlists.accountPlaylistVideos(route.id)
            KIND_PUBLIC -> youtube.playlist(route.id).let { (name, page) -> _title.value = name; page }
            else -> Paged(emptyList(), null)
        }
    }

    private val isRemote = route.kind == KIND_ACCOUNT || route.kind == KIND_PUBLIC

    init { if (isRemote) remote.refresh() }

    private fun currentItems(): List<VideoItem> =
        if (isRemote) remote.state.value.items else localItems.value.orEmpty()

    fun playAll() = playback.enqueueAll(currentItems(), playFirst = true)
    fun queueAll() = playback.enqueueAll(currentItems(), playFirst = false)

    fun remove(video: VideoItem) = viewModelScope.launch(com.mauztube.util.SafeHandler) {
        when (route.kind) {
            KIND_WATCH_LATER -> library.toggleWatchLater(video)
            KIND_LOCAL -> playlists.remove(route.id.toLong(), video.id)
            KIND_LIKED -> ratings.setRating(video, 0)
        }
    }

    fun deletePlaylist() = viewModelScope.launch(com.mauztube.util.SafeHandler) { if (route.kind == KIND_LOCAL) playlists.delete(route.id.toLong()) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    onBack: () -> Unit,
    onVideo: (String) -> Unit,
    vm: PlaylistDetailViewModel = hiltViewModel(),
) {
    val local by vm.localItems.collectAsStateWithLifecycle()
    val remoteState by vm.remote.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    val editable = vm.route.kind != KIND_ACCOUNT && vm.route.kind != KIND_PUBLIC
    val title by vm.title.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back", "Indietro")) } },
                actions = {
                    if (vm.route.kind == KIND_LOCAL) {
                        IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, tr("Delete playlist", "Elimina playlist")) }
                    }
                },
            )
        },
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
    ) { padding ->
        val actionsRow: @Composable () -> Unit = {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = vm::playAll) { Icon(Icons.Default.PlayArrow, null); Text(tr("Play all", "Riproduci tutto")) }
                FilledTonalButton(onClick = vm::queueAll) { Icon(AppIcons.AddToQueue, null); Text(tr(" Queue all", " In coda")) }
            }
        }
        if (!editable) {
            ContentList(
                state = remoteState,
                onVideo = { onVideo(it.id) },
                onChannel = {},
                onLoadMore = vm.remote::loadMore,
                onRetry = vm.remote::refresh,
                modifier = Modifier.padding(padding),
                emptyMessage = tr("Empty playlist", "Playlist vuota"),
                header = if (remoteState.items.isNotEmpty()) ({ item { actionsRow() } }) else null,
            )
            return@Scaffold
        }
        val list = local
        when {
            list == null -> SkeletonList(Modifier.padding(padding))
            list.isEmpty() -> MessageView(
                if (vm.route.kind == KIND_WATCH_LATER) tr("Use a video's ⋮ menu or the button in the player to save it here.", "Usa il menu ⋮ di un video o il pulsante nel player per salvarlo qui.")
                else tr("Add videos with \"Save to playlist\" from the ⋮ menu.", "Aggiungi video con \"Salva in playlist\" dal menu ⋮."),
                Modifier.padding(padding),
                title = tr("Empty playlist", "Playlist vuota"),
            )
            else -> LazyColumn(Modifier.padding(padding).fillMaxSize()) {
                item { actionsRow() }
                // Una playlist può contenere lo stesso video più volte: chiave unica per posizione.
                itemsIndexed(list, key = { i, v -> "${v.id}#$i" }) { _, v ->
                    VideoCard(v, onClick = { VideoPreviews.put(v); onVideo(v.id) })
                    Row(Modifier.padding(horizontal = 12.dp)) {
                        TextButton(onClick = { vm.remove(v) }) { Text(tr("Remove from playlist", "Rimuovi dalla playlist")) }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(tr("Delete this playlist?", "Eliminare la playlist?")) },
            confirmButton = { TextButton(onClick = { vm.deletePlaylist(); confirmDelete = false; onBack() }) { Text(tr("Delete", "Elimina")) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("Cancel", "Annulla")) } },
        )
    }
}

