package com.mauztube.ui.mycomments

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.mauztube.data.account.AccountManager
import com.mauztube.data.local.MyCommentEntity
import com.mauztube.data.repository.CommentsRepository
import com.mauztube.ui.components.MessageView
import com.mauztube.ui.theme.AppIcons
import com.mauztube.util.Format
import com.mauztube.util.tr
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Pagina ufficiale Google con tutti i commenti YouTube dell'account. */
const val ACCOUNT_COMMENTS_URL = "https://myactivity.google.com/page?page=youtube_comments"

@HiltViewModel
class MyCommentsViewModel @Inject constructor(
    private val repo: CommentsRepository,
    val account: AccountManager,
) : ViewModel() {
    val posted: StateFlow<List<MyCommentEntity>?> = repo.posted
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    fun delete(id: Long) = viewModelScope.launch(com.mauztube.util.SafeHandler) { repo.deletePostedRecord(id) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyCommentsScreen(
    onBack: () -> Unit,
    onVideo: (String) -> Unit,
    onAccountComments: () -> Unit,
    vm: MyCommentsViewModel = hiltViewModel(),
) {
    val posted by vm.posted.collectAsStateWithLifecycle()
    val hasSession by vm.account.hasSession.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tr("Your comments", "I tuoi commenti")) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back", "Indietro")) } },
            )
        },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        tr(
                            "Comments published from MauzTube are listed here. Your account's complete comment history is on Google's \"My Activity\" page.",
                            "Qui trovi i commenti pubblicati da MauzTube. La cronologia completa dei commenti dell'account è nella pagina \"Le mie attività\" di Google.",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (hasSession) {
                        FilledTonalButton(onClick = onAccountComments, modifier = Modifier.padding(top = 8.dp)) {
                            Icon(AppIcons.Comment, null)
                            Text("  " + tr("All your account's comments", "Tutti i commenti dell'account"))
                        }
                    }
                }
            }
            val list = posted.orEmpty()
            if (posted != null && list.isEmpty()) {
                item { MessageView(tr("You haven't published comments from MauzTube yet.", "Non hai ancora pubblicato commenti da MauzTube."), Modifier.padding(top = 48.dp)) }
            }
            items(list, key = { it.id }) { c ->
                Row(
                    Modifier.fillMaxWidth().clickable { onVideo(c.videoId) }.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(Modifier.width(120.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                        AsyncImage(c.thumbnailUrl, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.videoTitle, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(c.text, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                        Text(Format.date(c.createdAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    }
                    IconButton(onClick = { vm.delete(c.id) }) { Icon(Icons.Default.Delete, tr("Remove from list", "Rimuovi dall'elenco")) }
                }
            }
        }
    }
}

/** Pagina web Google dentro l'app, con la sessione già usata dal login di MauzTube. */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AccountWebScreen(title: String, url: String, onBack: () -> Unit) {
    var loading by remember { mutableStateOf(true) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Back", "Indietro")) } },
            )
        },
        contentWindowInsets = WindowInsets(0),
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        CookieManager.getInstance().setAcceptCookie(true)
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String) { loading = false }
                        }
                        loadUrl(url)
                    }
                },
                onRelease = { it.destroy() },
            )
        }
    }
}
