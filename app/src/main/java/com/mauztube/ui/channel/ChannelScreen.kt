package com.mauztube.ui.channel

import com.mauztube.util.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import coil3.compose.AsyncImage
import com.mauztube.data.model.ChannelDetails
import com.mauztube.data.model.Paged
import com.mauztube.data.model.VideoItem
import com.mauztube.data.repository.SubscriptionRepository
import com.mauztube.data.repository.YouTubeRepository
import com.mauztube.ui.components.ContentList
import com.mauztube.ui.components.PagedListController
import com.mauztube.ui.navigation.ChannelRoute
import com.mauztube.util.Format
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ChannelViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: YouTubeRepository,
    private val subscriptions: SubscriptionRepository,
) : ViewModel() {

    private val url = savedState.toRoute<ChannelRoute>().url

    private val _header = MutableStateFlow<ChannelDetails?>(null)
    val header: StateFlow<ChannelDetails?> = _header

    val subscribed: StateFlow<Boolean> = subscriptions.isSubscribed(url)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // Il primo caricamento recupera intestazione + prima pagina della tab "Video".
    val videos = PagedListController<VideoItem>(viewModelScope) {
        val details = repo.channel(url)
        _header.value = details
        Paged(details.videos.items, details.videos.loadNext)
    }

    init { videos.refresh() }

    fun toggleSubscription() {
        val h = _header.value ?: return
        viewModelScope.launch(com.mauztube.util.SafeHandler) {
            if (subscribed.value) subscriptions.unsubscribe(url)
            else subscriptions.subscribe(url, h.name, h.avatarUrl)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelScreen(
    onBack: () -> Unit,
    onVideo: (String) -> Unit,
    vm: ChannelViewModel = hiltViewModel(),
) {
    val header by vm.header.collectAsStateWithLifecycle()
    val subscribed by vm.subscribed.collectAsStateWithLifecycle()
    val state by vm.videos.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(header?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back", "Indietro")) }
                },
            )
        },
    ) { padding ->
        ContentList(
            state = state,
            onVideo = { onVideo(it.id) },
            onChannel = {},
            onLoadMore = vm.videos::loadMore,
            onRetry = vm.videos::refresh,
            modifier = Modifier.padding(padding),
            emptyMessage = tr("No videos", "Nessun video"),
            header = header?.let { h ->
                {
                    item(key = "header") {
                        ChannelHeader(h, subscribed, vm::toggleSubscription)
                    }
                }
            },
        )
    }
}

@Composable
private fun ChannelHeader(h: ChannelDetails, subscribed: Boolean, onToggle: () -> Unit) {
    Column {
        if (h.bannerUrl != null) {
            AsyncImage(
                model = h.bannerUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(6f),
            )
        }
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = h.avatarUrl,
                contentDescription = null,
                modifier = Modifier.size(64.dp).clip(CircleShape).background(Color.Gray),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(h.name, style = MaterialTheme.typography.titleLarge, maxLines = 1)
                if (h.subscriberCount >= 0) {
                    Text(tr("${Format.count(h.subscriberCount)} subscribers", "${Format.count(h.subscriberCount)} iscritti"), style = MaterialTheme.typography.bodySmall)
                }
            }
            if (subscribed) OutlinedButton(onClick = onToggle) { Text(tr("Subscribed", "Iscritto")) }
            else Button(onClick = onToggle) { Text(tr("Subscribe", "Iscriviti")) }
        }
    }
}
