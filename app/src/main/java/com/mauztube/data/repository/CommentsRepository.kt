package com.mauztube.data.repository

import com.mauztube.data.account.AccountManager
import com.mauztube.data.innertube.InnerTubeClient
import com.mauztube.data.local.MyCommentDao
import com.mauztube.data.local.MyCommentEntity
import com.mauztube.data.model.CommentItem
import com.mauztube.data.model.CommentsPage
import com.mauztube.data.model.ContentMapper.bestUrl
import com.mauztube.data.model.Paged
import com.mauztube.util.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.comments.CommentsInfo
import org.schabi.newpipe.extractor.comments.CommentsInfoItem
import org.schabi.newpipe.extractor.stream.Description
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Commenti: lettura con NewPipeExtractor (anche senza account), pubblicazione con l'account
 * tramite l'API interna di YouTube (stesso flusso del sito: token della sezione commenti →
 * parametro "createCommentParams" → comment/create_comment).
 */
@Singleton
class CommentsRepository @Inject constructor(
    private val innerTube: InnerTubeClient,
    private val account: AccountManager,
    private val myComments: MyCommentDao,
) {
    private val service get() = ServiceList.YouTube

    val posted: Flow<List<MyCommentEntity>> = myComments.observeAll()

    suspend fun deletePostedRecord(id: Long) = myComments.delete(id)

    suspend fun comments(videoId: String): CommentsPage = withContext(Dispatchers.IO) {
        val url = "https://www.youtube.com/watch?v=$videoId"
        val info = CommentsInfo.getInfo(service, url)
        CommentsPage(
            disabled = info.isCommentsDisabled,
            count = info.commentsCount,
            first = Paged(info.relatedItems.map { it.toItem(url) }, nextLoader(url, info.nextPage)),
        )
    }

    private fun nextLoader(url: String, page: Page?): (suspend () -> Paged<CommentItem>)? {
        if (!Page.isValid(page)) return null
        return {
            withContext(Dispatchers.IO) {
                val more = CommentsInfo.getMoreItems(service, url, page!!)
                Paged(more.items.map { it.toItem(url) }, nextLoader(url, more.nextPage))
            }
        }
    }

    private fun CommentsInfoItem.toItem(url: String): CommentItem {
        val text = commentText
        val replies = replies
        return CommentItem(
            id = commentId ?: (uploaderName + textualUploadDate),
            author = uploaderName.orEmpty(),
            authorAvatarUrl = uploaderAvatars.bestUrl(96),
            authorUrl = uploaderUrl,
            textHtml = text.content.orEmpty(),
            isHtml = text.type == Description.HTML,
            likes = textualLikeCount?.takeIf { it.isNotBlank() } ?: likeCount.takeIf { it > 0 }?.toString().orEmpty(),
            date = textualUploadDate.orEmpty(),
            replyCount = replyCount.coerceAtLeast(0),
            pinned = isPinned,
            byChannelOwner = isChannelOwner,
            loadReplies = if (replyCount > 0 && Page.isValid(replies)) nextLoader(url, replies) else null,
        )
    }

    /** Pubblica un commento con l'account e lo annota tra "I tuoi commenti". */
    suspend fun post(videoId: String, videoTitle: String, thumbnailUrl: String?, text: String) {
        if (!account.loggedIn.value) throw IOException(tr("Sign in with Google to comment", "Accedi con Google per commentare"))
        val params = createCommentParams(videoId)
            ?: throw IOException(tr("YouTube doesn't allow comments on this video", "YouTube non consente commenti su questo video"))
        val response = innerTube.postWeb("comment/create_comment") {
            put("commentText", text)
            put("createCommentParams", params)
        }
        val status = findString(response, "status")
        if (status != null && status != "STATUS_SUCCEEDED") throw IOException(tr("Comment not published ($status)", "Commento non pubblicato ($status)"))
        myComments.insert(MyCommentEntity(videoId = videoId, videoTitle = videoTitle, thumbnailUrl = thumbnailUrl, text = text, createdAt = System.currentTimeMillis()))
    }

    /** Dalla pagina del video: token della sezione commenti → createCommentParams (solo con account). */
    private suspend fun createCommentParams(videoId: String): String? {
        val next = innerTube.next(videoId)
        val token = commentsSectionToken(next) ?: return null
        val section = innerTube.postWeb("next") { put("continuation", token) }
        return findString(section, "createCommentParams")
    }

    private fun commentsSectionToken(root: JsonElement): String? {
        fun walk(e: JsonElement, inside: Boolean): String? = when (e) {
            is JsonObject -> {
                val inSection = inside || (e["sectionIdentifier"] as? JsonPrimitive)?.contentOrNull == "comment-item-section" ||
                    (e["targetId"] as? JsonPrimitive)?.contentOrNull == "comments-section"
                val cmd = e["continuationCommand"] as? JsonObject
                if (inSection && cmd != null) (cmd["token"] as? JsonPrimitive)?.contentOrNull
                else e.values.firstNotNullOfOrNull { walk(it, inSection) }
            }
            is JsonArray -> e.firstNotNullOfOrNull { walk(it, inside) }
            else -> null
        }
        return walk(root, false)
    }

    private fun findString(e: JsonElement, key: String): String? = when (e) {
        is JsonObject -> (e[key] as? JsonPrimitive)?.contentOrNull ?: e.values.firstNotNullOfOrNull { findString(it, key) }
        is JsonArray -> e.firstNotNullOfOrNull { findString(it, key) }
        else -> null
    }
}
