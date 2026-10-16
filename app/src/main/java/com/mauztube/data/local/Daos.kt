package com.mauztube.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaybackDao {
    @Upsert
    suspend fun upsert(state: PlaybackStateEntity)

    @Query("SELECT * FROM playback_state WHERE videoId = :videoId")
    suspend fun get(videoId: String): PlaybackStateEntity?

    @Query("SELECT * FROM playback_state ORDER BY updatedAt DESC LIMIT 500")
    fun observeHistory(): Flow<List<PlaybackStateEntity>>

    @Query("SELECT * FROM playback_state ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<PlaybackStateEntity>

    @Query("SELECT videoId FROM playback_state")
    suspend fun allIds(): List<String>

    @Query("DELETE FROM playback_state WHERE videoId = :videoId")
    suspend fun delete(videoId: String)

    @Query("DELETE FROM playback_state")
    suspend fun clear()
}

@Dao
interface SponsorSegmentDao {
    @Query("SELECT * FROM sponsor_segment WHERE videoId = :videoId ORDER BY startMs")
    suspend fun segments(videoId: String): List<SponsorSegmentEntity>

    @Query("SELECT * FROM sponsor_fetch WHERE videoId = :videoId")
    suspend fun fetchInfo(videoId: String): SponsorFetchEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSegments(segments: List<SponsorSegmentEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFetch(fetch: SponsorFetchEntity)

    @Query("DELETE FROM sponsor_segment WHERE videoId = :videoId")
    suspend fun deleteSegments(videoId: String)

    @Transaction
    suspend fun replace(videoId: String, segments: List<SponsorSegmentEntity>, now: Long) {
        deleteSegments(videoId)
        insertSegments(segments)
        insertFetch(SponsorFetchEntity(videoId, now))
    }

    @Query("DELETE FROM sponsor_fetch WHERE fetchedAt < :before")
    suspend fun pruneFetches(before: Long)

    @Query("DELETE FROM sponsor_segment WHERE videoId NOT IN (SELECT videoId FROM sponsor_fetch)")
    suspend fun pruneOrphanSegments()
}

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscription ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscription")
    suspend fun all(): List<SubscriptionEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM subscription WHERE channelUrl = :url)")
    fun observeIsSubscribed(url: String): Flow<Boolean>

    @Upsert
    suspend fun upsert(entity: SubscriptionEntity)

    @Query("DELETE FROM subscription WHERE channelUrl = :url")
    suspend fun delete(url: String)
}

@Dao
interface WatchLaterDao {
    @Query("SELECT * FROM watch_later ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<WatchLaterEntity>>

    @Query("SELECT videoId FROM watch_later")
    fun observeIds(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<WatchLaterEntity>)

    @Upsert
    suspend fun upsert(item: WatchLaterEntity)

    @Query("DELETE FROM watch_later WHERE videoId = :videoId")
    suspend fun delete(videoId: String)
}

@Dao
interface VideoCacheDao {
    @Query("SELECT * FROM cached_video WHERE listKey = :key ORDER BY position")
    suspend fun list(key: String): List<CachedVideoEntity>

    @Query("SELECT * FROM cached_video WHERE listKey IN (:keys) ORDER BY listKey, position")
    suspend fun lists(keys: List<String>): List<CachedVideoEntity>

    @Query("SELECT MAX(fetchedAt) FROM cached_video WHERE listKey = :key")
    suspend fun fetchedAt(key: String): Long?

    @Query("SELECT DISTINCT listKey FROM cached_video WHERE listKey LIKE :prefix || '%'")
    suspend fun keysWithPrefix(prefix: String): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(items: List<CachedVideoEntity>)

    @Query("DELETE FROM cached_video WHERE listKey = :key")
    suspend fun deleteList(key: String)

    @Transaction
    suspend fun replaceList(key: String, items: List<CachedVideoEntity>) {
        deleteList(key)
        insert(items)
    }

    @Query("DELETE FROM cached_video WHERE fetchedAt < :before")
    suspend fun prune(before: Long)
}

@Dao
interface PlaylistDao {
    @Query(
        """SELECT p.id, p.name, p.updatedAt,
                  (SELECT COUNT(*) FROM playlist_item i WHERE i.playlistId = p.id) AS count,
                  (SELECT thumbnailUrl FROM playlist_item i WHERE i.playlistId = p.id ORDER BY position LIMIT 1) AS cover
           FROM playlist p ORDER BY p.updatedAt DESC"""
    )
    fun observeSummaries(): Flow<List<PlaylistSummary>>

    @Query("SELECT * FROM playlist WHERE id = :id")
    suspend fun get(id: Long): PlaylistEntity?

    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Query("UPDATE playlist SET name = :name, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: Long, name: String, now: Long)

    @Query("UPDATE playlist SET updatedAt = :now WHERE id = :id")
    suspend fun touch(id: Long, now: Long)

    @Query("DELETE FROM playlist WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM playlist_item WHERE playlistId = :id")
    suspend fun deleteItems(id: Long)

    @Query("SELECT * FROM playlist_item WHERE playlistId = :id ORDER BY position")
    fun observeItems(id: Long): Flow<List<PlaylistItemEntity>>

    @Query("SELECT playlistId FROM playlist_item WHERE videoId = :videoId")
    fun observePlaylistsContaining(videoId: String): Flow<List<Long>>

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM playlist_item WHERE playlistId = :id")
    suspend fun nextPosition(id: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItem(item: PlaylistItemEntity)

    @Query("DELETE FROM playlist_item WHERE playlistId = :id AND videoId = :videoId")
    suspend fun deleteItem(id: Long, videoId: String)
}

@Dao
interface RatingDao {
    @Query("SELECT rating FROM rating WHERE videoId = :videoId")
    fun observeRating(videoId: String): Flow<Int?>

    @Query("SELECT * FROM rating WHERE rating = 1 ORDER BY updatedAt DESC")
    fun observeLiked(): Flow<List<RatingEntity>>

    @Upsert
    suspend fun upsert(entity: RatingEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(entities: List<RatingEntity>)

    @Query("DELETE FROM rating WHERE videoId = :videoId")
    suspend fun delete(videoId: String)
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM download ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM download WHERE status = 'QUEUED' ORDER BY createdAt LIMIT 1")
    suspend fun nextQueued(): DownloadEntity?

    @Query("SELECT * FROM download WHERE id = :id")
    suspend fun get(id: Long): DownloadEntity?

    @Insert
    suspend fun insert(entity: DownloadEntity): Long

    @Upsert
    suspend fun upsert(entity: DownloadEntity)

    @Query("UPDATE download SET progress = :progress WHERE id = :id")
    suspend fun setProgress(id: Long, progress: Float)

    @Query("UPDATE download SET status = 'QUEUED', progress = 0, error = NULL WHERE status = 'RUNNING'")
    suspend fun requeueInterrupted()

    @Query("DELETE FROM download WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface SearchHistoryDao {
    @Query("SELECT `query` FROM search_history ORDER BY usedAt DESC LIMIT 30")
    fun observeRecent(): Flow<List<String>>

    @Upsert
    suspend fun upsert(entity: SearchHistoryEntity)

    @Query("DELETE FROM search_history WHERE `query` = :query")
    suspend fun delete(query: String)

    @Query("DELETE FROM search_history")
    suspend fun clear()

    /** Tiene solo le 100 ricerche più recenti. */
    @Query("DELETE FROM search_history WHERE `query` NOT IN (SELECT `query` FROM search_history ORDER BY usedAt DESC LIMIT 100)")
    suspend fun trim()
}

@Dao
interface MyCommentDao {
    @Query("SELECT * FROM my_comment ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<MyCommentEntity>>

    @Insert
    suspend fun insert(entity: MyCommentEntity)

    @Query("DELETE FROM my_comment WHERE id = :id")
    suspend fun delete(id: Long)
}
