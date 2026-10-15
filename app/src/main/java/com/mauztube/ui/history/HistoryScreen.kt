package com.mauztube.ui.history

import com.mauztube.util.tr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
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
import com.mauztube.data.local.PlaybackStateEntity
import com.mauztube.data.model.VideoItem
import com.mauztube.data.model.VideoPreviews
import com.mauztube.data.repository.PlaybackRepository
import com.mauztube.data.repository.RecommendationRepository
import com.mauztube.ui.components.ContentList
import com.mauztube.ui.components.MessageView
import com.mauztube.ui.components.PagedListController
import com.mauztube.ui.components.SkeletonList
import com.mauztube.ui.components.VideoCard
import com.mauztube.util.Format
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Cronologia: con l'account collegato è quella dell'account Google (la stessa di youtube.com,
 * che include anche i video visti qui grazie ai ping di visualizzazione); altrimenti locale.
 * In entrambi i casi la barra rossa mostra il punto di ripresa salvato sul telefono.
 */
@HiltViewModel
class HistoryViewModel @Inject constructor(
    account: AccountManager,
    private val playback: PlaybackRepository,
    private val recommendations: RecommendationRepository,
) : ViewModel() {
    val loggedIn: StateFlow<Boolean> = account.loggedIn

    val local: StateFlow<List<PlaybackStateEntity>?> = playback.history
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val accountHistory = PagedListController<VideoItem>(viewModelScope) { recommendations.history() }

    init {
        viewModelScope.launch { loggedIn.collect { if (it) accountHistory.refresh() } }
    }

    fun remove(videoId: String) = viewModelScope.launch { playback.delete(videoId) }
    fun clear() = viewModelScope.launch { playback.clearHistory() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onVideo: (String) -> Unit,
    modifier: Modifier = Modifier,
    vm: HistoryViewModel = hiltViewModel(),
) {
    val loggedIn by vm.loggedIn.collectAsStateWithLifecycle()
    val local by vm.local.collectAsStateWithLifecycle()
    val progress = remember(local) { local.orEmpty().associateBy { it.videoId } }

    if (loggedIn) {
        val state by vm.accountHistory.state.collectAsStateWithLifecycle()
        PullToRefreshBox(
            isRefreshing = state.loading && state.items.isNotEmpty(),
            onRefresh = vm.accountHistory::refresh,
            modifier = modifier.fillMaxSize(),
        ) {
            if (state.items.isNotEmpty()) {
                LazyColumn(Modifier.fillMaxSize()) {
                    item { AssistChip(onClick = {}, label = { Text(tr("Google account history", "Cronologia dell'account Google")) }, modifier = Modifier.padding(horizontal = 12.dp)) }
                    items(state.items, key = { it.id }) { v ->
                        val p = progress[v.id]
                        VideoCard(
                            v,
                            onClick = { VideoPreviews.put(v); onVideo(v.id) },
                            progress = p?.progressFraction(),
                            extraLine = p?.resumeLabel(),
                        )
                    }
                    if (state.canLoadMore) item {
                        androidx.compose.runtime.LaunchedEffect(state.items.size) { vm.accountHistory.loadMore() }
                    }
                }
            } else {
                ContentList(
                    state = state,
                    onVideo = { onVideo(it.id) },
                    onChannel = {},
                    onLoadMore = vm.accountHistory::loadMore,
                    onRetry = vm.accountHistory::refresh,
                    emptyMessage = tr("Your account history is empty or paused.", "La cronologia dell'account è vuota o in pausa."),
                )
            }
        }
        return
    }

    var confirmClear by remember { mutableStateOf(false) }
    val list = local
    when {
        list == null -> SkeletonList(modifier)
        list.isEmpty() -> MessageView(tr("Videos you watch will appear here.", "I video che guardi compariranno qui."), modifier, title = tr("History is empty", "Cronologia vuota"))
        else -> Column(modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(tr("${list.size} videos", "${list.size} video"), style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = { confirmClear = true }) { Text(tr("Clear all", "Cancella tutto")) }
            }
            LazyColumn {
                items(list, key = { it.videoId }) { entry ->
                    VideoCard(
                        video = entry.toVideoItem(),
                        onClick = { VideoPreviews.put(entry.toVideoItem()); onVideo(entry.videoId) },
                        progress = entry.progressFraction(),
                        extraLine = entry.resumeLabel(),
                    )
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { vm.remove(entry.videoId) }) { Text(tr("Remove from history", "Rimuovi dalla cronologia")) }
                    }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(tr("Clear history?", "Cancellare la cronologia?")) },
            text = { Text(tr("Saved resume positions will be deleted too.", "Verranno eliminate anche le posizioni di ripresa salvate.")) },
            confirmButton = { TextButton(onClick = { vm.clear(); confirmClear = false }) { Text(tr("Clear", "Cancella")) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(tr("Cancel", "Annulla")) } },
        )
    }
}

private fun PlaybackStateEntity.progressFraction(): Float? = when {
    finished -> 1f
    durationMs > 0 -> positionMs.toFloat() / durationMs
    else -> null
}

private fun PlaybackStateEntity.resumeLabel(): String {
    if (finished) return tr("Watched", "Completato")
    val at = Format.durationMs(positionMs).ifEmpty { "0:00" }
    return tr("Stopped at $at", "Interrotto a $at")
}

private fun PlaybackStateEntity.toVideoItem() = VideoItem(
    id = videoId,
    url = "https://www.youtube.com/watch?v=$videoId",
    title = title,
    uploaderName = uploaderName,
    uploaderUrl = uploaderUrl,
    thumbnailUrl = thumbnailUrl,
    durationSeconds = durationMs / 1000,
    viewCount = -1,
    textualUploadDate = null,
    uploadedAtMillis = null,
    isLive = false,
)
