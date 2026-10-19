package com.mauztube.ui.player

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.mauztube.data.model.VideoItem
import com.mauztube.data.model.VideoPreviews
import com.mauztube.data.prefs.SponsorCategory
import com.mauztube.data.repository.LibraryRepository
import com.mauztube.data.repository.SubscriptionRepository
import com.mauztube.player.NowPlaying
import com.mauztube.player.PlaybackManager
import com.mauztube.player.PlayerUiState
import com.mauztube.ui.navigation.PlayerRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Stato della schermata video. Il player vero vive in [PlaybackManager]: chiudere questa
 * schermata NON ferma il video, che prosegue nel mini player.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlayerViewModel @Inject constructor(
    savedState: SavedStateHandle,
    val manager: PlaybackManager,
    private val subscriptions: SubscriptionRepository,
    private val library: LibraryRepository,
    private val ratings: com.mauztube.data.repository.RatingRepository,
    private val downloads: com.mauztube.download.DownloadRepository,
) : ViewModel() {

    private val route = savedState.toRoute<PlayerRoute>()
    val videoId: String = route.videoId
    val preview: VideoItem? = VideoPreviews.get(videoId)

    val nowPlaying: StateFlow<NowPlaying?> = manager.nowPlaying
    val audioOnly: StateFlow<Boolean> = manager.audioOnly
    val skipEvents = manager.skipEvents
    val queue = manager.queue

    val fullscreen: StateFlow<Boolean> = manager.fullscreen
    val autoplay: StateFlow<Boolean> = manager.autoplay
    val loop: StateFlow<Boolean> = manager.loop
    fun toggleAutoplay() = manager.setAutoplay(!autoplay.value)
    fun toggleLoop() = manager.setLoop(!loop.value)
    val votes = manager.votes

    /** 1 = mi piace, -1 = non mi piace, 0 = nessuna valutazione. */
    val rating: StateFlow<Int> = ratings.rating(videoId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val subscribed: StateFlow<Boolean> = manager.nowPlaying
        .map { (it?.state as? PlayerUiState.Ready)?.details?.uploaderUrl }
        .flatMapLatest { url -> if (url == null) flowOf(false) else subscriptions.isSubscribed(url) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val inWatchLater: StateFlow<Boolean> = library.watchLaterIds
        .map { videoId in it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, videoId in library.watchLaterIds.value)

    init {
        manager.open(videoId, preview, resume = route.resume, startMs = route.startSeconds.takeIf { it >= 0 }?.times(1000))
    }

    fun retry() = manager.retry()
    fun setFullscreen(value: Boolean) = manager.setFullscreen(value)

    /** Tocco su 👍 / 👎: un secondo tocco sullo stesso pulsante annulla la valutazione. */
    fun rate(value: Int) {
        val item = manager.currentItem() ?: return
        ratings.setRating(item, if (rating.value == value) 0 else value)
    }
    fun toggleAudioOnly() = manager.setAudioOnly(!manager.audioOnly.value)

    private fun details() = (manager.nowPlaying.value?.state as? PlayerUiState.Ready)?.details

    fun toggleSubscription() {
        val d = details() ?: return
        val url = d.uploaderUrl ?: return
        viewModelScope.launch(com.mauztube.util.SafeHandler) {
            if (subscribed.value) subscriptions.unsubscribe(url)
            else subscriptions.subscribe(url, d.uploaderName, d.uploaderAvatarUrl)
        }
    }

    fun toggleWatchLater() {
        val video = details()?.let {
            VideoItem(
                id = it.id, url = it.url, title = it.title, uploaderName = it.uploaderName,
                uploaderUrl = it.uploaderUrl, thumbnailUrl = it.thumbnailUrl,
                durationSeconds = it.durationSeconds, viewCount = it.viewCount,
                textualUploadDate = it.textualUploadDate, uploadedAtMillis = null, isLive = it.isLive,
                uploaderAvatarUrl = it.uploaderAvatarUrl,
            )
        } ?: preview ?: return
        library.toggleWatchLater(video)
    }

    fun download(kind: String, quality: Int, label: String) {
        val d = details() ?: return
        downloads.enqueue(d, kind, quality, label)
    }

    fun shareTitle(): String = details()?.title ?: preview?.title.orEmpty()

    companion object {
        fun categoryLabel(key: String) = SponsorCategory.fromKey(key)?.label ?: key
    }
}
