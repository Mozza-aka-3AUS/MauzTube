package com.mauztube.data.local


import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Posizione di riproduzione per videoId; è anche la base della cronologia. */
@Entity(tableName = "playback_state", indices = [Index("updatedAt")])
data class PlaybackStateEntity(
    @PrimaryKey val videoId: String,
    val positionMs: Long,
    val durationMs: Long,
    val title: String,
    val uploaderName: String,
    val thumbnailUrl: String?,
    val updatedAt: Long,
    val finished: Boolean,
    /** Aggiunto in v2: serve al motore di consigli locale (affinità per canale). */
    val uploaderUrl: String? = null,
)

/** Segmenti SponsorBlock in cache (tutte le categorie, filtrate a runtime). */
@Entity(tableName = "sponsor_segment", primaryKeys = ["videoId", "uuid"])
data class SponsorSegmentEntity(
    val videoId: String,
    val uuid: String,
    val category: String,
    val startMs: Long,
    val endMs: Long,
)

/** Quando i segmenti di un video sono stati scaricati: permette di cachare anche "nessun segmento". */
@Entity(tableName = "sponsor_fetch")
data class SponsorFetchEntity(
    @PrimaryKey val videoId: String,
    val fetchedAt: Long,
)

@Entity(tableName = "subscription")
data class SubscriptionEntity(
    @PrimaryKey val channelUrl: String,
    val name: String,
    val avatarUrl: String?,
    val subscribedAt: Long,
)

/** "Guarda più tardi" locale (sincronizzato anche con la playlist WL dell'account, se loggato). */
@Entity(tableName = "watch_later")
data class WatchLaterEntity(
    @PrimaryKey val videoId: String,
    val title: String,
    val uploaderName: String,
    val uploaderUrl: String?,
    val thumbnailUrl: String?,
    val durationSeconds: Long,
    val addedAt: Long,
)

/**
 * Cache di liste di video, identificate da [listKey]:
 *  - `rel:<videoId>`   correlati di un video guardato
 *  - `ch:<channelUrl>` ultimi video di un canale
 * È la materia prima dei consigli senza login: calcolarli non richiede rete.
 */
@Entity(tableName = "cached_video", primaryKeys = ["listKey", "videoId"], indices = [Index("fetchedAt")])
data class CachedVideoEntity(
    val listKey: String,
    val videoId: String,
    val position: Int,
    val title: String,
    val uploaderName: String,
    val uploaderUrl: String?,
    val uploaderAvatarUrl: String?,
    val thumbnailUrl: String?,
    val durationSeconds: Long,
    val viewText: String?,
    val textualUploadDate: String?,
    val uploadedAtMillis: Long?,
    val isLive: Boolean,
    val fetchedAt: Long,
)

@Entity(tableName = "playlist")
data class PlaylistEntity(
    @androidx.room.PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "playlist_item",
    primaryKeys = ["playlistId", "videoId"],
    indices = [Index("playlistId")],
)
data class PlaylistItemEntity(
    val playlistId: Long,
    val videoId: String,
    val position: Int,
    val title: String,
    val uploaderName: String,
    val uploaderUrl: String?,
    val thumbnailUrl: String?,
    val durationSeconds: Long,
    val addedAt: Long,
)

/** Riepilogo per l'elenco playlist (conteggio e copertina = primo video). */
data class PlaylistSummary(
    val id: Long,
    val name: String,
    val updatedAt: Long,
    val count: Int,
    val cover: String?,
)

/** Mi piace (+1) / Non mi piace (-1) espressi dall'utente; sincronizzati con l'account se collegato. */
@Entity(tableName = "rating", indices = [Index("rating")])
data class RatingEntity(
    @PrimaryKey val videoId: String,
    val rating: Int,
    val title: String,
    val uploaderName: String,
    val uploaderUrl: String?,
    val thumbnailUrl: String?,
    val durationSeconds: Long,
    val updatedAt: Long,
)

/** Un download (video MP4, audio MP3 o M4A) con stato e avanzamento. */
@Entity(tableName = "download", indices = [Index("status")])
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val videoId: String,
    val title: String,
    val uploaderName: String,
    val thumbnailUrl: String?,
    /** "video", "mp3" o "m4a". */
    val kind: String,
    /** Altezza video (es. 720) o bitrate MP3 in kbps (es. 192). */
    val quality: Int,
    val qualityLabel: String,
    /** QUEUED, RUNNING, DONE, FAILED. */
    val status: String,
    val progress: Float,
    val fileUri: String?,
    val fileName: String?,
    val sizeBytes: Long,
    val error: String?,
    val createdAt: Long,
)

/** Ricerche recenti (mostrate sotto il campo di ricerca). */
@Entity(tableName = "search_history", indices = [Index("usedAt")])
data class SearchHistoryEntity(
    @PrimaryKey val query: String,
    val usedAt: Long,
)

/** Commenti pubblicati da MauzTube con l'account, con il video a cui si riferiscono. */
@Entity(tableName = "my_comment", indices = [Index("createdAt")])
data class MyCommentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val videoId: String,
    val videoTitle: String,
    val thumbnailUrl: String?,
    val text: String,
    val createdAt: Long,
)
