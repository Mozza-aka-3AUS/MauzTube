package com.mauztube.ui.components

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mauztube.data.account.AccountManager
import com.mauztube.data.innertube.InnerTubeParser
import com.mauztube.data.model.VideoItem
import com.mauztube.data.prefs.SettingsRepository
import com.mauztube.data.repository.LibraryRepository
import com.mauztube.data.repository.PlaylistRepository
import com.mauztube.data.repository.SubscriptionRepository
import com.mauztube.data.repository.YouTubeRepository
import com.mauztube.player.PlaybackManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Azioni disponibili su ogni video, fornite a tutte le card tramite CompositionLocal. */
interface VideoActions {
    val watchLaterIds: StateFlow<Set<String>>
    fun toggleWatchLater(video: VideoItem)
    fun enqueue(video: VideoItem)
    fun saveToPlaylist(video: VideoItem)
    /** Prefetch "a riposo" dei video rimasti visibili (solo su rete non a consumo). */
    fun prefetchVisible(videoIds: List<String>)
}

/** Stato e azioni a livello di app (sopra la navigazione). */
@HiltViewModel
class AppViewModel @Inject constructor(
    private val library: LibraryRepository,
    private val playlists: PlaylistRepository,
    private val youtube: YouTubeRepository,
    private val settings: SettingsRepository,
    private val subscriptions: SubscriptionRepository,
    val account: AccountManager,
    /** Esposto per il mini player e la coda globali. */
    val playback: PlaybackManager,
) : ViewModel(), VideoActions {

    override val watchLaterIds: StateFlow<Set<String>> = library.watchLaterIds
    override fun toggleWatchLater(video: VideoItem) = library.toggleWatchLater(video)
    override fun enqueue(video: VideoItem) = playback.enqueue(video)
    override fun prefetchVisible(videoIds: List<String>) = youtube.prefetch(videoIds, max = 2)

    // ---- Primo avvio ----
    val onboardingDone: StateFlow<Boolean?> = settings.onboardingDone
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun completeOnboarding() = viewModelScope.launch { settings.setOnboardingDone() }

    init {
        // Con l'account collegato le iscrizioni vengono allineate a ogni avvio/login.
        viewModelScope.launch { account.loggedIn.collect { if (it) subscriptions.sync() } }
    }

    // ---- Finestra "Salva in playlist" ----
    private val _playlistTarget = MutableStateFlow<VideoItem?>(null)
    val playlistTarget: StateFlow<VideoItem?> = _playlistTarget

    private val _accountPlaylists = MutableStateFlow<List<InnerTubeParser.PlaylistRef>>(emptyList())
    val accountPlaylists: StateFlow<List<InnerTubeParser.PlaylistRef>> = _accountPlaylists

    val localPlaylists = playlists.playlists.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    override fun saveToPlaylist(video: VideoItem) {
        _playlistTarget.value = video
        if (account.loggedIn.value && _accountPlaylists.value.isEmpty()) {
            viewModelScope.launch { _accountPlaylists.value = runCatching { playlists.accountPlaylists() }.getOrDefault(emptyList()) }
        }
    }

    fun dismissPlaylistDialog() { _playlistTarget.value = null }

    fun playlistsContaining(videoId: String) = playlists.playlistsContaining(videoId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(1_000), emptySet())

    fun toggleLocalPlaylist(playlistId: Long, video: VideoItem, contained: Boolean) = viewModelScope.launch {
        if (contained) playlists.remove(playlistId, video.id) else playlists.add(playlistId, video)
    }

    fun addToAccountPlaylist(playlistId: String, video: VideoItem) = viewModelScope.launch {
        runCatching { playlists.addToAccountPlaylist(playlistId, video) }
    }

    fun createPlaylistWith(name: String, video: VideoItem) = viewModelScope.launch {
        val id = playlists.create(name)
        playlists.add(id, video)
    }

}

val LocalVideoActions = staticCompositionLocalOf<VideoActions?> { null }
