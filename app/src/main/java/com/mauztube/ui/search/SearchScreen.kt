package com.mauztube.ui.search

import com.mauztube.util.tr

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import com.mauztube.ui.components.Motion
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.stateIn
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.Alignment
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.clickable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mauztube.data.model.ContentItem
import com.mauztube.data.repository.YouTubeRepository
import com.mauztube.ui.components.ContentList
import com.mauztube.ui.components.ListUiState
import com.mauztube.ui.components.PagedListController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repo: YouTubeRepository,
    private val savedState: SavedStateHandle,
    private val history: com.mauztube.data.repository.SearchHistoryRepository,
) : ViewModel() {

    /** Ricerche recenti (vuote se la cronologia ricerche è disattivata e cancellata). */
    val recent: StateFlow<List<String>> = history.recent
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5_000), emptyList())

    fun removeRecent(q: String) = viewModelScope.launch { history.delete(q) }


    val query: StateFlow<String> = savedState.getStateFlow("query", "")
    private val _controller = MutableStateFlow<PagedListController<ContentItem>?>(null)
    val controller: StateFlow<PagedListController<ContentItem>?> = _controller

    init {
        if (query.value.isNotBlank()) search()
    }

    fun onQueryChange(q: String) { savedState["query"] = q }

    fun search() {
        val q = query.value.trim()
        if (q.isEmpty()) return
        viewModelScope.launch { history.record(q) }
        _controller.value = PagedListController(viewModelScope) { repo.search(q) }.also { it.refresh() }
    }
}

@Composable
fun SearchScreen(
    onBack: () -> Unit,
    onVideo: (String) -> Unit,
    onChannel: (String) -> Unit,
    vm: SearchViewModel = hiltViewModel(),
) {
    val query by vm.query.collectAsStateWithLifecycle()
    val controller by vm.controller.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    val recent by vm.recent.collectAsStateWithLifecycle()
    var focused by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { if (controller == null) focus.requestFocus() }

    Column {
        // Casella a "pillola" piena, senza bordo né linea: il colore di sfondo cambia
        // (animato) quando è attiva.
        val cs = MaterialTheme.colorScheme
        val container by animateColorAsState(
            if (focused) cs.surfaceContainerHighest else cs.surfaceContainerHigh,
            tween(Motion.FAST), label = "searchBg",
        )
        TextField(
            value = query,
            onValueChange = vm::onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).focusRequester(focus)
                .onFocusChanged { focused = it.isFocused },
            shape = RoundedCornerShape(50),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = container,
                unfocusedContainerColor = container,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
            singleLine = true,
            placeholder = { Text(tr("Search YouTube", "Cerca su YouTube")) },
            leadingIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back", "Indietro")) }
            },
            trailingIcon = {
                AnimatedVisibility(
                    query.isNotEmpty(),
                    enter = fadeIn(tween(Motion.FAST)) + scaleIn(tween(Motion.FAST), initialScale = 0.6f),
                    exit = fadeOut(tween(Motion.FAST)) + scaleOut(tween(Motion.FAST), targetScale = 0.6f),
                ) {
                    IconButton(onClick = { vm.onQueryChange(""); focus.requestFocus() }) { Icon(Icons.Default.Clear, tr("Clear", "Cancella")) }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus(); vm.search() }),
        )
        val c = controller
        // Ricerche recenti: mentre si scrive (filtrate) o prima della prima ricerca.
        if (focused || c == null) {
            val shown = if (query.isBlank()) recent else recent.filter { it.contains(query.trim(), ignoreCase = true) && it != query.trim() }
            androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth()) {
                items(shown.size, key = { shown[it] }) { i ->
                    val q = shown[i]
                    Row(
                        Modifier.animateItem(fadeInSpec = tween(Motion.FAST), fadeOutSpec = tween(Motion.FAST))
                            .fillMaxWidth().clickable { vm.onQueryChange(q); focusManager.clearFocus(); vm.search() }
                            .padding(start = 20.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(com.mauztube.ui.theme.AppIcons.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(q, modifier = Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 14.dp), maxLines = 1)
                        IconButton(onClick = { vm.removeRecent(q) }) { Icon(Icons.Default.Clear, tr("Remove", "Rimuovi")) }
                    }
                }
            }
            if (c == null || shown.isNotEmpty()) return@Column
        }
        val state by c.state.collectAsStateWithLifecycle(ListUiState())
        ContentList(
            state = state,
            onVideo = { onVideo(it.id) },
            onChannel = { onChannel(it.url) },
            onLoadMore = c::loadMore,
            onRetry = c::refresh,
            emptyMessage = tr("No results", "Nessun risultato"),
        )
    }
}
