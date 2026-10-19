package com.mauztube.ui.navigation

import com.mauztube.util.tr

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.navigation.NavBackStackEntry
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.verticalScroll
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
import androidx.navigation.toRoute
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
import com.mauztube.ui.components.popOnChange
import com.mauztube.ui.queue.QueueSheet
import com.mauztube.ui.search.SearchScreen
import com.mauztube.ui.settings.SettingsScreen
import com.mauztube.ui.subscriptions.SubscriptionsScreen
import com.mauztube.ui.theme.AppIcons
import com.mauztube.ui.theme.BrandRed
import com.mauztube.util.DeepLink
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

private data class TopLevel(
    val route: Any, private val en: String, private val it: String, val icon: ImageVector,
    private val shortEn: String = en, private val shortIt: String = it,
) {
    val label: String get() = tr(en, it)
    /** Etichetta per barre strette (telefoni piccoli o caratteri ingranditi). */
    val shortLabel: String get() = tr(shortEn, shortIt)
}

private val TOP_LEVEL = listOf(
    TopLevel(HomeRoute, "Home", "Home", Icons.Default.Home),
    TopLevel(SubscriptionsRoute, "Subscriptions", "Iscrizioni", AppIcons.Subscriptions, shortEn = "Subs"),
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

// ---------------- Transizioni di navigazione ----------------
// Brevi (≤ 260 ms, il default di Navigation è 700 ms) e solo con trasparenza, scala e
// traslazione: vengono applicate dalla GPU senza rimisurare le schermate.

private val Emphasized = androidx.compose.animation.core.CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private fun <T> enterSpec(ms: Int = 240) = tween<T>(ms, easing = Emphasized)
private fun <T> exitSpec(ms: Int = 160) = tween<T>(ms, easing = androidx.compose.animation.core.FastOutLinearInEasing)

private fun NavBackStackEntry.isPlayer() = destination.hasRoute(PlayerRoute::class)
private fun NavBackStackEntry.isTopLevel() = TOP_LEVEL.any { destination.hasRoute(it.route::class) }

private typealias NavScope = androidx.compose.animation.AnimatedContentTransitionScope<NavBackStackEntry>

private fun NavScope.navEnter(): androidx.compose.animation.EnterTransition = when {
    // Il video sale dal basso, come quando si tocca una miniatura su YouTube.
    targetState.isPlayer() && !initialState.isPlayer() ->
        slideInVertically(enterSpec(260)) { it / 5 } + fadeIn(enterSpec(200))
    targetState.isPlayer() -> fadeIn(enterSpec(180))
    // Cambio di scheda: dissolvenza con un leggero ingrandimento ("fade through").
    targetState.isTopLevel() && initialState.isTopLevel() ->
        fadeIn(tween(200, delayMillis = 50)) + androidx.compose.animation.scaleIn(enterSpec(240), initialScale = 0.96f)
    // Schermata successiva: entra da destra di poco.
    else -> slideInHorizontally(enterSpec()) { it / 12 } + fadeIn(enterSpec(200))
}

private fun NavScope.navExit(): androidx.compose.animation.ExitTransition = when {
    // Sotto al video che sale, la schermata resta ferma (niente lavoro inutile).
    targetState.isPlayer() && !initialState.isPlayer() -> androidx.compose.animation.ExitTransition.KeepUntilTransitionsFinished
    targetState.isTopLevel() && initialState.isTopLevel() -> fadeOut(tween(90))
    else -> slideOutHorizontally(exitSpec()) { -it / 24 } + fadeOut(exitSpec())
}

private fun NavScope.navPopEnter(): androidx.compose.animation.EnterTransition = when {
    initialState.isPlayer() && !targetState.isPlayer() -> androidx.compose.animation.EnterTransition.None
    else -> slideInHorizontally(enterSpec()) { -it / 24 } + fadeIn(enterSpec(200))
}

private fun NavScope.navPopExit(): androidx.compose.animation.ExitTransition = when {
    // Chiudendo il video, scende verso il mini player.
    initialState.isPlayer() && !targetState.isPlayer() ->
        slideOutVertically(exitSpec(200)) { it / 4 } + fadeOut(exitSpec(200))
    else -> slideOutHorizontally(exitSpec()) { it / 12 } + fadeOut(exitSpec())
}

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
    val incognito by app.account.incognito.collectAsStateWithLifecycle()
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

    // La schermata video segue sempre il video in riproduzione (coda, riproduzione automatica).
    LaunchedEffect(nav) {
        app.playback.nowPlaying.collect { np ->
            val entry = nav.currentBackStackEntry ?: return@collect
            if (np != null && entry.destination.hasRoute(PlayerRoute::class) &&
                entry.toRoute<PlayerRoute>().videoId != np.videoId
            ) nav.openVideo(np.videoId)
        }
    }
    // Lasciando la schermata video (non passando a un altro video) si esce dallo schermo intero.
    val onPlayerRoute = destination?.hasRoute(PlayerRoute::class) == true
    LaunchedEffect(onPlayerRoute) { if (!onPlayerRoute) app.playback.setFullscreen(false) }

    // Adattamento allo schermo: schede a sinistra (rail) quando lo schermo è largo
    // (orizzontale, pieghevoli, tablet); etichette brevi se ogni scheda ha poco spazio.
    val window = com.mauztube.ui.components.windowSize()
    val useRail = window.wide
    val narrowNav = window.widthDp / 5f / window.fontScale.coerceAtLeast(1f) < 76f

    fun goHomeOrTop() {
        if (destination?.hasRoute(HomeRoute::class) == true) homeScrollToTop.tryEmit(Unit)
        else nav.navigate(HomeRoute) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun openTopLevel(item: TopLevel) {
        if (item.route == HomeRoute) goHomeOrTop()
        else nav.navigate(item.route) {
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
                            val ctx = androidx.compose.ui.platform.LocalContext.current
                            IconButton(onClick = {
                                val on = !incognito
                                app.account.setIncognito(on)
                                android.widget.Toast.makeText(
                                    ctx,
                                    if (on) tr("Incognito mode on", "Modalità anonimo attiva") else tr("Incognito mode off", "Modalità anonimo disattivata"),
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            }) {
                                val tint by androidx.compose.animation.animateColorAsState(
                                    if (incognito) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    tween(com.mauztube.ui.components.Motion.MEDIUM), label = "incognitoTint",
                                )
                                Icon(
                                    AppIcons.Incognito, tr("Incognito mode", "Modalità anonimo"),
                                    tint = tint,
                                    modifier = Modifier.popOnChange(incognito),
                                )
                            }
                            IconButton(onClick = { nav.navigate(SearchRoute()) }) { Icon(Icons.Default.Search, tr("Search", "Cerca")) }
                        },
                    )
                }
            },
            bottomBar = {
                if (topLevel != null && !useRail) {
                    NavigationBar {
                        TOP_LEVEL.forEach { item ->
                            NavigationBarItem(
                                selected = item == topLevel,
                                onClick = { openTopLevel(item) },
                                icon = { Icon(item.icon, item.label) },
                                // Etichette lunghe si rimpiccioliscono; se lo spazio è poco si usa
                                // la forma breve ("Subs") invece di tagliarle.
                                label = { NavLabel(if (narrowNav) item.shortLabel else item.label) },
                            )
                        }
                    }
                }
            },
        ) { padding ->
            val fullscreen by app.playback.fullscreen.collectAsStateWithLifecycle()
            // A schermo intero il player usa TUTTO il display (anche la zona del foro della
            // fotocamera): nessun margine della Scaffold esterna.
            Row(Modifier.padding(if (fullscreen) androidx.compose.foundation.layout.PaddingValues(0.dp) else padding).fillMaxSize()) {
            if (useRail && topLevel != null && !fullscreen) {
                // Schermi bassi (telefono in orizzontale): etichetta solo sulla scheda attiva;
                // in ogni caso la colonna scorre, così nessuna scheda resta irraggiungibile.
                androidx.compose.material3.NavigationRail(
                    Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),
                ) {
                    TOP_LEVEL.forEach { item ->
                        androidx.compose.material3.NavigationRailItem(
                            selected = item == topLevel,
                            onClick = { openTopLevel(item) },
                            icon = { Icon(item.icon, item.label) },
                            label = { NavLabel(item.label) },
                            alwaysShowLabel = window.heightDp >= 520f,
                        )
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                Column(Modifier.fillMaxSize()) {
                // Avviso della modalità anonimo, sotto la barra in alto.
                androidx.compose.animation.AnimatedVisibility(
                    incognito && topLevel != null,
                    enter = androidx.compose.animation.expandVertically(tween(com.mauztube.ui.components.Motion.MEDIUM)) +
                        fadeIn(tween(com.mauztube.ui.components.Motion.MEDIUM)),
                    exit = androidx.compose.animation.shrinkVertically(tween(com.mauztube.ui.components.Motion.FAST)) +
                        fadeOut(tween(com.mauztube.ui.components.Motion.FAST)),
                ) {
                    Row(
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(AppIcons.Incognito, null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(18.dp))
                        Text(
                            tr("Incognito: history isn't saved and your account isn't used",
                                "Anonimo: la cronologia non viene salvata e l'account non viene usato"),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        )
                        androidx.compose.material3.TextButton(onClick = { app.account.setIncognito(false) }) { Text(tr("Turn off", "Disattiva")) }
                    }
                }
                NavHost(
                    nav,
                    startDestination = HomeRoute,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    enterTransition = { navEnter() },
                    exitTransition = { navExit() },
                    popEnterTransition = { navPopEnter() },
                    popExitTransition = { navPopExit() },
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
                    composable<HistoryRoute> {
                        HistoryScreen(onVideo = { nav.openVideo(it, resume = true) }, onMyComments = { nav.navigate(MyCommentsRoute) })
                    }
                    composable<MyCommentsRoute> {
                        com.mauztube.ui.mycomments.MyCommentsScreen(
                            onBack = { nav.popBackStack() },
                            onVideo = openVideo,
                            onAccountComments = { nav.navigate(AccountCommentsRoute) },
                        )
                    }
                    composable<AccountCommentsRoute> {
                        com.mauztube.ui.mycomments.AccountWebScreen(
                            title = tr("Account comments", "Commenti dell'account"),
                            url = com.mauztube.ui.mycomments.ACCOUNT_COMMENTS_URL,
                            onBack = { nav.popBackStack() },
                        )
                    }
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
        }

        if (queueOpen) QueueSheet(app.playback, onDismiss = { queueOpen = false })
        SaveToPlaylistDialog(app)

        // Popup (Picture-in-Picture): solo il video, a tutta finestra.
        if (com.mauztube.LocalInPip.current) {
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                com.mauztube.ui.player.VideoView(app.playback.player, Modifier.fillMaxSize(), pipSurface = true)
                com.mauztube.ui.player.SubtitleOverlay(app.playback, raised = false, large = false, modifier = Modifier.align(Alignment.BottomCenter))
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

/** Etichetta di una scheda: una riga, rimpicciolita se serve (mai tagliata a metà parola). */
@Composable
private fun NavLabel(text: String) {
    Text(
        text,
        maxLines = 1,
        softWrap = false,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = 12.sp),
    )
}
