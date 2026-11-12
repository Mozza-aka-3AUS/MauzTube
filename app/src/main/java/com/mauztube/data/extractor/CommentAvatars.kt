package com.mauztube.data.extractor

import java.util.concurrent.ConcurrentHashMap

/**
 * Avatar (e nome) degli autori dei commenti. YouTube ora li mette in `author` del
 * `commentEntityPayload` (`avatarThumbnailUrl`, `displayName`), mentre NewPipeExtractor v0.26.5
 * cerca l'avatar in `avatar.image.sources` (vuoto): ogni commento arrivava senza immagine e si
 * vedeva solo l'iniziale; a volte mancava anche il nome.
 *
 * [NewPipeDownloader] passa qui le risposte che contengono commenti; si ricava "canale
 * dell'autore → avatar, nome" (stessa risposta, nessuna richiesta in più) e
 * [com.mauztube.data.repository.CommentsRepository] li usa quando NewPipe non li fornisce.
 */
object CommentAvatars {
    private class Author(val avatar: String?, val name: String?)

    private val byChannel = ConcurrentHashMap<String, Author>()

    /** Chiamato dal downloader: costa solo per le risposte che contengono commenti. */
    fun collect(body: String) {
        if (!body.contains("\"commentEntityPayload\"")) return
        if (byChannel.size > MAX) byChannel.clear()
        AUTHOR.findAll(body).forEach { m ->
            val fields = m.groupValues[2]
            // Canali vecchi senza handle: displayName è vuoto; YouTube mostra l'indirizzo
            // personalizzato del canale (canonicalBaseUrl, es. "/root" o "/@nome").
            val name = NAME.find(fields)?.groupValues?.get(1)?.let(::unescape)?.takeIf { it.isNotBlank() }
                ?: BASE_URL.find(authorTail(body, m.range.last + 1))?.groupValues?.get(1)?.let(::fromBaseUrl)
            byChannel[m.groupValues[1]] = Author(avatar = AVATAR.find(fields)?.groupValues?.get(1), name = name)
        }
    }

    /** Avatar per l'URL del canale dell'autore (…/channel/UC…), se noto. */
    fun forChannelUrl(url: String?): String? = author(url)?.avatar

    /** Nome dell'autore, se noto. */
    fun nameForChannelUrl(url: String?): String? = author(url)?.name?.takeIf { it.isNotBlank() }

    private fun author(url: String?): Author? {
        val id = url?.substringAfterLast("/channel/", "")?.substringBefore('?')?.takeIf { it.isNotEmpty() } ?: return null
        return byChannel[id]
    }

    /** Sequenze di escape JSON più comuni nei nomi. */
    private fun unescape(s: String): String =
        UNICODE.replace(s.replace(BACKSLASH_QUOTE, "\"").replace(DOUBLE_BACKSLASH, "\\")) {
            it.groupValues[1].toInt(16).toChar().toString()
        }

    /** Resto dell'oggetto "author" (channelCommand…), senza arrivare al commento successivo. */
    private fun authorTail(body: String, from: Int): String {
        val end = minOf(body.length, from + 1200)
        val toolbar = body.indexOf("\"toolbar\"", from).takeIf { it in from until end } ?: end
        return body.substring(from, toolbar)
    }

    /** "/@nome" → "@nome", "/c/nome" o "/user/nome" o "/nome" → "nome"; "/channel/UC…" non è un nome. */
    private fun fromBaseUrl(path: String): String? {
        val p = path.removePrefix("c/").removePrefix("user/")
        return p.takeIf { it.isNotBlank() && !p.startsWith("channel/") && '/' !in p }
    }

    private const val MAX = 3000
    private const val BACKSLASH_QUOTE = "\\\""
    private const val DOUBLE_BACKSLASH = "\\\\"

    // L'oggetto "author" inizia con channelId e, prima degli oggetti annidati (channelCommand…),
    // contiene displayName e avatarThumbnailUrl: [^{}]* resta dentro l'oggetto.
    private val AUTHOR = Regex("\"author\":\\{\"channelId\":\"(UC[\\w-]+)\"([^{}]*)")
    private val AVATAR = Regex("\"avatarThumbnailUrl\":\"([^\"]+)\"")
    private val NAME = Regex("\"displayName\":\"((?:[^\"\\\\]|\\\\.)*)\"")
    private val BASE_URL = Regex("\"canonicalBaseUrl\":\"/([^\"]+)\"")
    private val UNICODE = Regex("\\\\u([0-9a-fA-F]{4})")
}
