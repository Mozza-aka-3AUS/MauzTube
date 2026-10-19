package com.mauztube.ui.home

import com.mauztube.util.tr

import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mauztube.data.account.AccountManager
import com.mauztube.data.model.Paged
import com.mauztube.data.model.VideoItem
import com.mauztube.data.recommend.LocalRecommender
import com.mauztube.data.repository.RecommendationRepository
import com.mauztube.data.repository.YouTubeRepository
import com.mauztube.ui.components.ContentList
import com.mauztube.ui.components.MessageView
import com.mauztube.ui.components.PagedListController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.Flow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class HomeSource { ACCOUNT, LOCAL }

/**
 * Home = consigliati.
 *  - Account collegato: feed consigliato dell'account Google.
 *  - Senza account: consigli locali da iscrizioni + video visti ([LocalRecommender]).
 *    Vengono calcolati subito dalla cache (nessuna attesa di rete) e le fonti si aggiornano
 *    in background per la volta successiva.
 * Se il feed dell'account non risponde si ripiega sui consigli locali.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val account: AccountManager,
    private val recommendations: RecommendationRepository,
    private val local: LocalRecommender,
    private val youtube: YouTubeRepository,
) : ViewModel() {

    val loggedIn: StateFlow<Boolean> = account.loggedIn

    private val _source = MutableStateFlow(HomeSource.LOCAL)
    val source: StateFlow<HomeSource> = _source

    /** null = ancora da verificare; false = né cronologia né iscrizioni (primo avvio). */
    private val _hasSignals = MutableStateFlow<Boolean?>(null)
    val hasSignals: StateFlow<Boolean?> = _hasSignals

    private var seed = 0

    /** Video mostrati nelle ultime due aperture/aggiornamenti: alla prossima ricarica si evitano. */
    private val shownHistory = ArrayDeque<Set<String>>()
    private fun recentlyShown(): Set<String> = shownHistory.flatten().toSet()

    val feed = PagedListController<VideoItem>(viewModelScope) { load() }

    init {
        viewModelScope.launch(com.mauztube.util.SafeHandler) { loggedIn.collect { refresh() } }
    }

    private suspend fun load(): Paged<VideoItem> {
        if (account.loggedIn.value) {
            try {
                return accountFeedFresh().also { _source.value = HomeSource.ACCOUNT }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Feed account non disponibile, uso i consigli locali", e)
            }
        }
        _source.value = HomeSource.LOCAL
        _hasSignals.value = local.hasSignals()
        return Paged(local.recommend(seed, recentlyShown()), null)
    }

    /**
     * Come su YouTube, ricaricare deve dare video nuovi: si scartano quelli appena mostrati
     * e si attinge alle pagine successive del feed (al massimo 3 richieste in più). I già
     * visti, se servono a riempire, finiscono in fondo e mescolati.
     */
    private suspend fun accountFeedFresh(): Paged<VideoItem> {
        val exclude = recentlyShown()
        var page = recommendations.home()
        if (exclude.isEmpty()) return page
        val fresh = mutableListOf<VideoItem>()
        val repeated = mutableListOf<VideoItem>()
        var extra = 0
        while (true) {
            page.items.forEach { if (it.id in exclude) repeated += it else fresh += it }
            val next = page.loadNext
            if (fresh.size >= MIN_FRESH || next == null || extra++ >= 3) break
            page = next()
        }
        return Paged(fresh.distinctBy { it.id } + repeated.shuffled(), page.loadNext)
    }

    fun refresh() {
        seed++
        feed.refresh()
        viewModelScope.launch(com.mauztube.util.SafeHandler) {
            val first = feed.state.map { it.items }.filter { it.isNotEmpty() }.distinctUntilChanged().first()
            // Si ricordano i primi video (quelli effettivamente visti scorrendo poco).
            shownHistory.addLast(first.take(SHOWN_WINDOW).map { it.id }.toSet())
            while (shownHistory.size > 2) shownHistory.removeFirst()
            youtube.prefetch(first.map { it.id })
        }
        if (!account.loggedIn.value) {
            viewModelScope.launch(com.mauztube.util.SafeHandler) {
                val changed = runCatching { local.refreshSources() }.getOrDefault(false)
                // Se la home era vuota (cache fredda) mostra subito i nuovi consigli; altrimenti
                // non rimescola la lista sotto le dita: verranno usati al prossimo aggiornamento.
                if (changed && feed.state.value.items.isEmpty()) feed.refresh()
            }
        }
    }

    private companion object {
        const val TAG = "HomeViewModel"
        const val MIN_FRESH = 16
        const val SHOWN_WINDOW = 12
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onVideo: (String) -> Unit,
    onChannel: (String) -> Unit,
    onLogin: () -> Unit,
    onSearch: () -> Unit,
    scrollToTop: Flow<Unit>,
    modifier: Modifier = Modifier,
    vm: HomeViewModel = hiltViewModel(),
) {
    val listState = rememberLazyListState()
    LaunchedEffect(scrollToTop) {
        scrollToTop.collect {
            // Da lontano si salta quasi in cima e si anima solo l'ultimo tratto (più rapido).
            if (listState.firstVisibleItemIndex > 12) listState.scrollToItem(6)
            listState.animateScrollToItem(0)
        }
    }
    val state by vm.feed.state.collectAsStateWithLifecycle()
    val source by vm.source.collectAsStateWithLifecycle()
    val hasSignals by vm.hasSignals.collectAsStateWithLifecycle()
    val loggedIn by vm.loggedIn.collectAsStateWithLifecycle()

    if (!loggedIn && hasSignals == false && state.items.isEmpty() && !state.loading) {
        Onboarding(onSearch, onLogin, modifier)
        return
    }

    PullToRefreshBox(
        isRefreshing = state.loading && state.items.isNotEmpty(),
        onRefresh = vm::refresh,
        modifier = modifier.fillMaxSize(),
    ) {
        ContentList(
            state = state,
            onVideo = { onVideo(it.id) },
            onChannel = { onChannel(it.url) },
            onLoadMore = vm.feed::loadMore,
            onRetry = vm::refresh,
            listState = listState,
            emptyMessage = if (source == HomeSource.ACCOUNT)
                tr("No recommendations: your YouTube watch history is empty or paused.", "Nessun consiglio: la cronologia YouTube dell'account è vuota o in pausa.")
            else tr("Watch a few videos or subscribe to a channel: recommendations will start from there.", "Guarda qualche video o iscriviti a un canale: i consigli arriveranno da lì."),
            header = {
                item(key = "source") {
                    Row(
                        Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    if (source == HomeSource.ACCOUNT) tr("From your Google account", "Dal tuo account Google")
                                    else tr("Based on your subscriptions and history", "In base a iscrizioni e video visti")
                                )
                            },
                        )
                        if (!loggedIn) {
                            TextButton(onClick = onLogin) { Text(tr("Sign in", "Accedi")) }
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun Onboarding(onSearch: () -> Unit, onLogin: () -> Unit, modifier: Modifier) {
    MessageView(
        title = tr("Welcome to MauzTube", "Benvenuto su MauzTube"),
        message = tr(
            "Search for a video or subscribe to a channel: home will learn your taste, all on " +
                "this phone. Or sign in with Google to get your account's feed.",
            "Cerca un video o iscriviti a un canale: la home imparerà dai tuoi gusti, " +
                "tutto sul telefono. Oppure accedi con Google per il feed del tuo account.",
        ),
        actionLabel = tr("Search for a video", "Cerca un video"),
        onAction = onSearch,
        modifier = modifier,
    )
    Row(modifier.fillMaxSize().padding(bottom = 48.dp), verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.Center) {
        TextButton(onClick = onLogin) {
            Text(tr("Sign in with Google", "Accedi con Google"), style = MaterialTheme.typography.labelLarge)
        }
    }
}
