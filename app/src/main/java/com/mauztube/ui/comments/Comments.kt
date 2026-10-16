package com.mauztube.ui.comments

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.fromHtml
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.mauztube.data.account.AccountManager
import com.mauztube.data.model.CommentItem
import com.mauztube.data.model.Paged
import com.mauztube.data.repository.CommentsRepository
import com.mauztube.ui.components.Avatar
import com.mauztube.ui.navigation.PlayerRoute
import com.mauztube.ui.theme.AppIcons
import com.mauztube.util.Format
import com.mauztube.util.tr
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CommentsUi(
    val loading: Boolean = false,
    val error: String? = null,
    val disabled: Boolean = false,
    val count: Int = -1,
    val items: List<CommentItem> = emptyList(),
    val canLoadMore: Boolean = false,
)

@HiltViewModel
class CommentsViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: CommentsRepository,
    account: AccountManager,
) : ViewModel() {
    private val videoId = savedState.toRoute<PlayerRoute>().videoId
    val loggedIn: StateFlow<Boolean> = account.loggedIn

    private val _ui = MutableStateFlow(CommentsUi())
    val ui: StateFlow<CommentsUi> = _ui
    private var next: (suspend () -> Paged<CommentItem>)? = null
    private var started = false

    /** Risposte caricate per commento (id → elenco). */
    val replies = mutableStateMapOf<String, List<CommentItem>>()
    val repliesLoading = mutableStateMapOf<String, Boolean>()

    var posting by mutableStateOf(false)
        private set

    /** Caricamento pigro: parte solo quando i commenti diventano visibili. */
    fun ensureLoaded() {
        if (started) return
        started = true
        load()
    }

    fun retry() { started = true; load() }

    private fun load() {
        _ui.value = CommentsUi(loading = true)
        viewModelScope.launch {
            runCatching { repo.comments(videoId) }
                .onSuccess { p ->
                    next = p.first.loadNext
                    _ui.value = CommentsUi(disabled = p.disabled, count = p.count, items = p.first.items, canLoadMore = next != null)
                }
                .onFailure { _ui.value = CommentsUi(error = it.message ?: it.javaClass.simpleName) }
        }
    }

    fun loadMore() {
        val loader = next ?: return
        if (_ui.value.loading) return
        _ui.value = _ui.value.copy(loading = true)
        viewModelScope.launch {
            runCatching { loader() }
                .onSuccess { p ->
                    next = p.loadNext
                    _ui.value = _ui.value.copy(loading = false, items = (_ui.value.items + p.items).distinctBy { it.id }, canLoadMore = next != null)
                }
                .onFailure { _ui.value = _ui.value.copy(loading = false, error = it.message) }
        }
    }

    fun toggleReplies(c: CommentItem) {
        if (replies.containsKey(c.id)) { replies.remove(c.id); return }
        val loader = c.loadReplies ?: return
        repliesLoading[c.id] = true
        viewModelScope.launch {
            runCatching { loader() }.onSuccess { replies[c.id] = it.items }
            repliesLoading.remove(c.id)
        }
    }

    fun post(title: String, thumb: String?, text: String, onResult: (String?) -> Unit) {
        if (text.isBlank() || posting) return
        posting = true
        viewModelScope.launch {
            val result = runCatching { repo.post(videoId, title, thumb, text.trim()) }
            posting = false
            onResult(result.exceptionOrNull()?.message)
            if (result.isSuccess) { started = true; load() }
        }
    }
}

/** Riquadro "Commenti" nella pagina del video (anteprima del primo commento). */
@Composable
fun CommentsPreview(vm: CommentsViewModel, onOpen: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.ensureLoaded() }
    Column(
        Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth()
            .clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = !ui.disabled, onClick = onOpen).padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Comments", "Commenti"), style = MaterialTheme.typography.labelLarge)
            if (ui.count > 0) {
                Text("  ${Format.count(ui.count.toLong())}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val first = ui.items.firstOrNull()
        when {
            ui.disabled -> Text(tr("Comments are turned off.", "I commenti sono disattivati."), style = MaterialTheme.typography.bodySmall)
            first != null -> Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(first.authorAvatarUrl, first.author, 24)
                Spacer(Modifier.width(8.dp))
                Text(plain(first), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            ui.loading -> Text(tr("Loading…", "Caricamento…"), style = MaterialTheme.typography.bodySmall)
            ui.error != null -> Text(tr("Comments unavailable", "Commenti non disponibili"), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentsSheet(vm: CommentsViewModel, videoTitle: String, thumbnailUrl: String?, onDismiss: () -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val loggedIn by vm.loggedIn.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 3
        }
    }
    LaunchedEffect(nearEnd, ui.items.size) { if (nearEnd && ui.canLoadMore) vm.loadMore() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().imePadding()) {
            Text(
                tr("Comments", "Commenti") + if (ui.count > 0) " · ${Format.count(ui.count.toLong())}" else "",
                style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 16.dp),
            )
            if (loggedIn) {
                var text by remember { mutableStateOf("") }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = text, onValueChange = { text = it },
                        modifier = Modifier.weight(1f), maxLines = 4,
                        placeholder = { Text(tr("Add a comment…", "Aggiungi un commento…")) },
                    )
                    if (vm.posting) CircularProgressIndicator(Modifier.padding(12.dp).size(24.dp))
                    else IconButton(onClick = {
                        vm.post(videoTitle, thumbnailUrl, text) { err ->
                            if (err == null) {
                                text = ""
                                Toast.makeText(context, tr("Comment published", "Commento pubblicato"), Toast.LENGTH_SHORT).show()
                            } else Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                        }
                    }, enabled = text.isNotBlank()) {
                        Icon(Icons.AutoMirrored.Filled.Send, tr("Publish", "Pubblica"), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            } else {
                Text(
                    tr("Sign in with Google to write comments.", "Accedi con Google per scrivere commenti."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
            }
            HorizontalDivider()
            LazyColumn(Modifier.heightIn(max = 640.dp), state = listState) {
                items(ui.items, key = { it.id }) { c ->
                    CommentRow(c, indent = false)
                    val replies = vm.replies[c.id]
                    if (c.replyCount > 0) {
                        TextButton(onClick = { vm.toggleReplies(c) }, modifier = Modifier.padding(start = 52.dp)) {
                            Text(
                                when {
                                    vm.repliesLoading[c.id] == true -> tr("Loading…", "Caricamento…")
                                    replies != null -> tr("Hide replies", "Nascondi risposte")
                                    else -> tr("${c.replyCount} replies", "${c.replyCount} risposte")
                                }
                            )
                        }
                    }
                    replies?.forEach { r -> CommentRow(r, indent = true) }
                }
                if (ui.loading) item { Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center) { CircularProgressIndicator() } }
                if (ui.error != null && ui.items.isEmpty()) item {
                    Column(Modifier.padding(16.dp)) {
                        Text(tr("Comments unavailable: ", "Commenti non disponibili: ") + ui.error, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = vm::retry) { Text(tr("Retry", "Riprova")) }
                    }
                }
                if (ui.disabled) item { Text(tr("Comments are turned off.", "I commenti sono disattivati."), modifier = Modifier.padding(16.dp)) }
            }
        }
    }
}

@Composable
private fun CommentRow(c: CommentItem, indent: Boolean) {
    val link = MaterialTheme.colorScheme.primary
    val text = remember(c.id, link) {
        if (c.isHtml) AnnotatedString.fromHtml(c.textHtml, linkStyles = TextLinkStyles(SpanStyle(color = link))) else AnnotatedString(c.textHtml)
    }
    Row(Modifier.fillMaxWidth().padding(start = if (indent) 52.dp else 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp)) {
        Avatar(c.authorAvatarUrl, c.author, if (indent) 24 else 32)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.author, style = MaterialTheme.typography.labelMedium, fontWeight = if (c.byChannelOwner) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
                Text("  ${c.date}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                if (c.pinned) Text("  📌", style = MaterialTheme.typography.labelSmall)
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 2.dp))
            if (c.likes.isNotEmpty()) {
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(AppIcons.ThumbUpOutline, null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("  ${c.likes}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun plain(c: CommentItem): String =
    if (c.isHtml) AnnotatedString.fromHtml(c.textHtml).text else c.textHtml
