package com.mauztube.ui.navigation

import com.mauztube.util.tr

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mauztube.ui.account.LoginScreen
import com.mauztube.ui.channel.ChannelScreen
import com.mauztube.ui.components.AppViewModel
import com.mauztube.ui.components.LocalVideoActions
import com.mauztube.ui.history.HistoryScreen
import com.mauztube.ui.home.HomeScreen
import com.mauztube.ui.onboarding.OnboardingScreen
import com.mauztube.ui.player.MiniPlayer
import com.mauztube.ui.player.PlayerScreen
import com.mauztube.ui.playlists.PlaylistDetailScreen
import com.mauztube.ui.playlists.PlaylistsScreen
import com.mauztube.ui.playlists.SaveToPlaylistDialog
import com.mauztube.ui.queue.QueueBar
import com.mauztube.ui.queue.QueueSheet
import com.mauztube.ui.search.SearchScreen
import com.mauztube.ui.settings.SettingsScreen
import com.mauztube.ui.subscriptions.SubscriptionsScreen
import com.mauztube.ui.theme.AppIcons
import com.mauztube.ui.theme.BrandRed
import com.mauztube.util.DeepLink
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

private data class TopLevel(val route: Any, private val en: String, private val it: String, val icon: ImageVector) {
    val label: String get() = tr(en, it)
}

private val TOP_LEVEL = listOf(
    TopLevel(HomeRoute, "Home", "Home", Icons.Default.Home),
    TopLevel(SubscriptionsRoute, "Subscriptions", "Iscrizioni", AppIcons.Subscriptions),
    TopLevel(PlaylistsRoute, "Playlists", "Playlist", AppIcons.Library),
    TopLevel(HistoryRoute, "History", "Cronologia", AppIcons.History),
    TopLevel(SettingsRoute, "Settings", "Impostazioni", Icons.Default.Settings),
)

/** Apre un video sostituendo l'eventuale schermata video già presente. */
fun NavHostController.openVideo(videoId: String, resume: Boolean = false, startSeconds: Long = -1) =
    navigate(PlayerRoute(videoId, resume, startSeconds)) {
    popUpTo<PlayerRoute> { inclusive = true }
    launchSingleTop = true
}

/** Transizioni brevi: la navigazione risponde subito (il default di Navigation è 700 ms). */
private const val NAV_ANIM_MS = 140

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation(deepLinks: Flow<DeepLink>) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val destination = backStack?.destination
    val topLevel = TOP_LEVEL.firstOrNull { t -> destination?.hasRoute(t.route::class) == true }
    val app: AppViewModel = hiltViewModel()
    val onboardingDone by app.onboardingDone.collectAsStateWithLifecycle()
    val loggedIn by app.account.loggedIn.collectAsStateWithLifecycle()
    var queueOpen by remember { mutableStateOf(false) }
    // "Torna in cima": tasto Home premuto di nuovo o tocco sul logo.
    val homeScrollToTop = remember { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }

    LaunchedEffect(nav) {
        deepLinks.collect { link ->
            when (link) {
                is DeepLink.Video -> nav.openVideo(link.videoId, startSeconds = link.startSeconds ?: -1)
                is DeepLink.Channel -> nav.navigate(ChannelRoute(link.url))
                is DeepLink.Playlist -> nav.navigate(
                    PlaylistRoute(com.mauztube.ui.playlists.KIND_PUBLIC, link.playlistId, tr("Playlist", "Playlist"))
                )
                is DeepLink.Search -> nav.navigate(SearchRoute(link.query))
            }
        }
    }

    fun goHomeOrTop() {
        if (destination?.hasRoute(HomeRoute::class) == true) homeScrollToTop.tryEmit(Unit)
        else nav.navigate(HomeRoute) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    CompositionLocalProvider(LocalVideoActions provides app) {
        Scaffold(
            topBar = {
                if (topLevel != null) {
                    TopAppBar(
                        title = {
                            if (topLevel.route == HomeRoute) Logo(onClick = ::goHomeOrTop)
                            else Text(topLevel.label, style = MaterialTheme.typography.titleLarge)
                        },
                        actions = {
                            IconButton(onClick = { nav.navigate(SearchRoute()) }) { Icon(Icons.Default.Search, tr("Search", "Cerca")) }
                        },
                    )
                }
            },
            bottomBar = {
                if (topLevel != null) {
                    NavigationBar {
                        TOP_LEVEL.forEach { item ->
                            NavigationBarItem(
                                selected = item == topLevel,
                                onClick = {
                                    if (item.route == HomeRoute) goHomeOrTop()
                                    else nav.navigate(item.route) {
                                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                },
                                icon = { Icon(item.icon, item.label) },
                                // Etichette lunghe ("Subscriptions") si rimpiccioliscono invece di essere tagliate.
                                label = {
                                    Text(
                                        item.label,
                                        maxLines = 1,
                                        autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(
                                            minFontSize = 9.sp, maxFontSize = 12.sp,
                                        ),
                                    )
                                },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            val fullscreen by app.playback.fullscreen.collectAsStateWithLifecycle()
            // A schermo intero il player usa TUTTO il display (anche la zona del foro della
            // fotocamera): nessun margine della Scaffold esterna.
            Box(Modifier.padding(if (fullscreen) androidx.compose.foundation.layout.PaddingValues(0.dp) else padding).fillMaxSize()) {
                NavHost(
                    nav,
                    startDestination = HomeRoute,
                    modifier = Modifier.fillMaxSize(),
                    enterTransition = { fadeIn(tween(NAV_ANIM_MS)) },
                    exitTransition = { fadeOut(tween(NAV_ANIM_MS)) },
                    popEnterTransition = { fadeIn(tween(NAV_ANIM_MS)) },
                    popExitTransition = { fadeOut(tween(NAV_ANIM_MS)) },
                ) {
                    val openChannel: (String) -> Unit = { nav.navigate(ChannelRoute(it)) }
                    val openVideo: (String) -> Unit = { nav.openVideo(it) }
                    val openLogin: () -> Unit = { nav.navigate(LoginRoute) }

                    composable<HomeRoute> {
                        HomeScreen(
                            openVideo, openChannel, openLogin,
                            onSearch = { nav.navigate(SearchRoute()) },
                            scrollToTop = homeScrollToTop,
                        )
                    }
                    composable<SubscriptionsRoute> { SubscriptionsScreen(openVideo, openChannel) }
                    composable<PlaylistsRoute> {
                        PlaylistsScreen(
                            onOpen = { kind, id, title -> nav.navigate(PlaylistRoute(kind, id, title)) },
                            onDownloads = { nav.navigate(DownloadsRoute) },
                        )
                    }
                    composable<DownloadsRoute> { com.mauztube.ui.downloads.DownloadsScreen(onBack = { nav.popBackStack() }) }
                    composable<PlaylistRoute> {
                        PlaylistDetailScreen(onBack = { nav.popBackStack() }, onVideo = openVideo)
                    }
                    // Dalla cronologia il video riprende sempre dal punto interrotto.
                    composable<HistoryRoute> { HistoryScreen(onVideo = { nav.openVideo(it, resume = true) }) }
                    composable<SettingsRoute> { SettingsScreen(onLogin = openLogin) }
                    composable<LoginRoute> { LoginScreen(onDone = { nav.popBackStack() }) }
                    composable<SearchRoute> {
                        SearchScreen(onBack = { nav.popBackStack() }, onVideo = openVideo, onChannel = openChannel)
                    }
                    composable<ChannelRoute> {
                        ChannelScreen(onBack = { nav.popBackStack() }, onVideo = openVideo)
                    }
                    composable<PlayerRoute> {
                        PlayerScreen(
                            onBack = { nav.popBackStack() },
                            onVideo = openVideo,
                            onChannel = openChannel,
                            onOpenQueue = { queueOpen = true },
                        )
                    }
                }

                // Mini player + barra della coda, disponibili in tutta l'app.
                val onPlayer = destination?.hasRoute(PlayerRoute::class) == true
                val onLogin = destination?.hasRoute(LoginRoute::class) == true
                if (!onLogin) {
                    Column(Modifier.align(Alignment.BottomEnd), horizontalAlignment = Alignment.End) {
                        if (!onPlayer) {
                            MiniPlayer(
                                manager = app.playback,
                                onExpand = { nav.openVideo(it) },
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                        QueueBar(app.playback, onOpen = { queueOpen = true })
                    }
                }
            }
        }

        if (queueOpen) QueueSheet(app.playback, onDismiss = { queueOpen = false })
        SaveToPlaylistDialog(app)

        // Popup (Picture-in-Picture): solo il video, a tutta finestra.
        if (com.mauztube.LocalInPip.current) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                com.mauztube.ui.player.VideoView(app.playback.player, Modifier.fillMaxSize(), pipSurface = true)
            }
            return@CompositionLocalProvider
        }

        // Primo avvio: scelta tra account Google e uso locale.
        if (onboardingDone == false && !loggedIn) {
            OnboardingScreen(
                onGoogle = { app.completeOnboarding(); nav.navigate(LoginRoute) },
                onLocal = { app.completeOnboarding() },
            )
        }
    }
}

@Composable
private fun Logo(onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(com.mauztube.R.drawable.ic_logo),
            contentDescription = null,
            modifier = Modifier.size(30.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text("MauzTube", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}
