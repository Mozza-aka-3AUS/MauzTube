package com.mauztube.ui.subscriptions

import com.mauztube.util.tr

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.mauztube.data.local.SubscriptionEntity
import com.mauztube.data.model.Paged
import com.mauztube.data.model.VideoItem
import com.mauztube.data.repository.SubscriptionRepository
import com.mauztube.data.account.AccountManager
import com.mauztube.data.repository.ChannelFeedRepository
import com.mauztube.data.repository.RecommendationRepository
import com.mauztube.ui.components.Avatar
import kotlinx.coroutines.flow.combine
import com.mauztube.ui.components.ContentList
import com.mauztube.ui.components.MessageView
import com.mauztube.ui.components.PagedListController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SubscriptionsViewModel @Inject constructor(
    private val account: AccountManager,
    private val recommendations: RecommendationRepository,
    private val channelFeed: ChannelFeedRepository,
    private val subscriptionRepo: SubscriptionRepository,
) : ViewModel() {

    val loggedIn: StateFlow<Boolean> = account.loggedIn

    val channels: StateFlow<List<SubscriptionEntity>?> = subscriptionRepo.subscriptions
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Con account: feed iscrizioni dell'account Google (una richiesta).
     * Senza: cache locale istantanea; i canali più vecchi di 3 h si aggiornano in background
     * e la lista si ricarica (dalla cache) solo se qualcosa è cambiato.
     */
    val feed = PagedListController<VideoItem>(viewModelScope) {
        if (account.loggedIn.value) {
            runCatching { recommendations.subscriptionsFeed() }.getOrNull()?.let { return@PagedListController it }
        }
        Paged(channelFeed.cachedFeed(subscriptionRepo.all().map { it.channelUrl }), null)
    }

    init {
        // Allinea le iscrizioni con l'account (al massimo ogni 30 minuti).
        viewModelScope.launch { subscriptionRepo.sync() }
        viewModelScope.launch {
            combine(
                subscriptionRepo.subscriptions.map { list -> list.map { it.channelUrl }.toSet() }.distinctUntilChanged(),
                loggedIn,
            ) { urls, logged -> urls to logged }
                .collect { (urls, logged) ->
                    feed.refresh()
                    if (!logged) refreshStale(urls.toList(), ChannelFeedRepository.DEFAULT_MAX_AGE_MS)
                }
        }
    }

    fun pullToRefresh() {
        feed.refresh()
        if (loggedIn.value) viewModelScope.launch { subscriptionRepo.sync(force = true) }
        if (!loggedIn.value) viewModelScope.launch {
            refreshStale(subscriptionRepo.all().map { it.channelUrl }, maxAgeMs = 10 * 60 * 1000L)
        }
    }

    private suspend fun refreshStale(urls: List<String>, maxAgeMs: Long) {
        if (channelFeed.refresh(urls, maxAgeMs) > 0) feed.refresh()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionsScreen(
    onVideo: (String) -> Unit,
    onChannel: (String) -> Unit,
    modifier: Modifier = Modifier,
    vm: SubscriptionsViewModel = hiltViewModel(),
) {
    val channels by vm.channels.collectAsStateWithLifecycle()
    val state by vm.feed.state.collectAsStateWithLifecycle()

    val loggedIn by vm.loggedIn.collectAsStateWithLifecycle()
    if (channels?.isEmpty() == true && !loggedIn) {
        MessageView(
            title = tr("No subscriptions", "Nessuna iscrizione"),
            message = tr("Open a channel and tap \"Subscribe\", or sign in with Google to get your account's feed.", "Apri un canale e tocca \"Iscriviti\", oppure accedi con Google per il feed del tuo account."),
            modifier = modifier,
        )
        return
    }

    PullToRefreshBox(
        isRefreshing = state.loading && state.items.isNotEmpty(),
        onRefresh = vm::pullToRefresh,
        modifier = modifier.fillMaxSize(),
    ) {
        ContentList(
            state = state,
            onVideo = { onVideo(it.id) },
            onChannel = {},
            onLoadMore = vm.feed::loadMore,
            onRetry = vm.feed::refresh,
            emptyMessage = tr("No recent videos", "Nessun video recente"),
            header = if (channels.isNullOrEmpty()) null else {
                {
                item(key = "channels") {
                    LazyRow(
                        contentPadding = PaddingValues(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(channels.orEmpty(), key = { it.channelUrl }) { ch ->
                            Column(
                                Modifier.width(64.dp).clickable { onChannel(ch.channelUrl) },
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Avatar(ch.avatarUrl, ch.name, 52)
                                Text(
                                    ch.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }
                }
                }
            },
        )
    }
}
