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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
) : ViewModel() {

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

    LaunchedEffect(Unit) { if (controller == null) focus.requestFocus() }

    Column {
        OutlinedTextField(
            value = query,
            onValueChange = vm::onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(8.dp).focusRequester(focus),
            singleLine = true,
            placeholder = { Text(tr("Search YouTube", "Cerca su YouTube")) },
            leadingIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back", "Indietro")) }
            },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { vm.onQueryChange("") }) { Icon(Icons.Default.Clear, tr("Clear", "Cancella")) }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus(); vm.search() }),
        )
        val c = controller
        if (c == null) return@Column
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
