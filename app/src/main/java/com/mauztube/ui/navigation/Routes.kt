package com.mauztube.ui.navigation

import kotlinx.serialization.Serializable

@Serializable data object HomeRoute
@Serializable data object SubscriptionsRoute
@Serializable data object PlaylistsRoute
@Serializable data object DownloadsRoute
@Serializable data class PlaylistRoute(val kind: String, val id: String, val title: String)
@Serializable data object HistoryRoute
@Serializable data object SettingsRoute
@Serializable data class SearchRoute(val query: String = "")
@Serializable data class ChannelRoute(val url: String)
/** @param resume riprende dal punto salvato anche con la ripresa automatica spenta (cronologia). */
@Serializable data class PlayerRoute(val videoId: String, val resume: Boolean = false, val startSeconds: Long = -1)
@Serializable data object LoginRoute
