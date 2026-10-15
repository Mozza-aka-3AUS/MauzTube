package com.mauztube.ui.components

import com.mauztube.data.model.ContentItem
import com.mauztube.data.model.Paged
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ListUiState<T>(
    val items: List<T> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
    val canLoadMore: Boolean = false,
)

/** Gestisce caricamento iniziale e paginazione infinita per qualunque [Paged]. */
class PagedListController<T : ContentItem>(
    private val scope: CoroutineScope,
    private val source: suspend () -> Paged<T>,
) {
    private val _state = MutableStateFlow(ListUiState<T>())
    val state: StateFlow<ListUiState<T>> = _state

    private var next: (suspend () -> Paged<T>)? = null
    private var job: Job? = null

    fun refresh() {
        job?.cancel()
        _state.value = ListUiState(loading = true)
        job = scope.launch { load(source, replace = true) }
    }

    fun loadMore() {
        val loader = next ?: return
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        job = scope.launch { load(loader, replace = false) }
    }

    private suspend fun load(loader: suspend () -> Paged<T>, replace: Boolean) {
        try {
            var page = loader()
            var collected = page.items
            // Una pagina composta solo da Shorts risulta vuota dopo il filtro: si prosegue.
            var guard = 0
            while (collected.size < MIN_PAGE && guard++ < 3) {
                val more = page.loadNext ?: break
                page = more()
                collected = collected + page.items
            }
            next = page.loadNext
            _state.update {
                ListUiState(
                    items = (if (replace) collected else it.items + collected).distinctBy { item -> item.url },
                    loading = false,
                    canLoadMore = next != null,
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(loading = false, error = e.message ?: e.javaClass.simpleName) }
        }
    }

    private companion object { const val MIN_PAGE = 5 }
}
