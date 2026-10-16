package com.mauztube.data.model

/** Un commento (o una risposta) di un video. */
data class CommentItem(
    val id: String,
    val author: String,
    val authorAvatarUrl: String?,
    val authorUrl: String?,
    val textHtml: String,
    val isHtml: Boolean,
    val likes: String,
    val date: String,
    val replyCount: Int,
    val pinned: Boolean,
    val byChannelOwner: Boolean,
    /** Caricamento delle risposte (null se non ce ne sono). */
    val loadReplies: (suspend () -> Paged<CommentItem>)?,
)

data class CommentsPage(
    val disabled: Boolean,
    val count: Int,
    val first: Paged<CommentItem>,
)
