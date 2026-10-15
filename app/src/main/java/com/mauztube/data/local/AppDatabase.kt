package com.mauztube.data.local

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        PlaybackStateEntity::class,
        SponsorSegmentEntity::class,
        SponsorFetchEntity::class,
        SubscriptionEntity::class,
        WatchLaterEntity::class,
        CachedVideoEntity::class,
        PlaylistEntity::class,
        PlaylistItemEntity::class,
        RatingEntity::class,
        DownloadEntity::class,
    ],
    version = 5,
    exportSchema = true,
    // v1 → v2: nuova colonna nullable + nuove tabelle, migrazione generata da Room
    // (cronologia, posizioni e iscrizioni vengono conservate).
    // v2 → v3: tabelle playlist (solo aggiunte).
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playbackDao(): PlaybackDao
    abstract fun sponsorSegmentDao(): SponsorSegmentDao
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun watchLaterDao(): WatchLaterDao
    abstract fun videoCacheDao(): VideoCacheDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun ratingDao(): RatingDao
    abstract fun downloadDao(): DownloadDao
}
