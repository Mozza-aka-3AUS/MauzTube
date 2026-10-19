package com.mauztube.di

import android.content.Context
import androidx.room.Room
import com.mauztube.data.local.AppDatabase
import com.mauztube.data.local.PlaybackDao
import com.mauztube.data.local.SponsorSegmentDao
import com.mauztube.data.local.SubscriptionDao
import com.mauztube.data.local.VideoCacheDao
import com.mauztube.data.local.WatchLaterDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** Client HTTP per i dati audio/video (googlevideo.com), separato da quello delle API. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MediaHttp

/** Scope che sopravvive ai ViewModel: usato per i salvataggi che non devono essere cancellati. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDns(): com.mauztube.data.net.CachingDns = com.mauztube.data.net.CachingDns()

    @Provides
    @Singleton
    fun provideOkHttpClient(dns: com.mauztube.data.net.CachingDns): OkHttpClient = OkHttpClient.Builder()
        // Connessioni riutilizzate più a lungo: meno handshake TLS ⇒ estrazione più rapida.
        // Al cambio di rete il pool viene svuotato (NetworkWatcher).
        .connectionPool(okhttp3.ConnectionPool(10, 5, TimeUnit.MINUTES))
        .dns(dns)
        // Una connessione bloccata non deve fermare tutto per 30 s (default precedente):
        // dopo 15 s senza dati la richiesta fallisce e chi la usa riprova su una connessione nuova.
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Connessioni ai server video: tenute aperte al massimo 30 s (una connessione inattiva più a
     * lungo può essere stata chiusa in silenzio dalla rete e bloccherebbe la richiesta fino al
     * timeout) e 8 s senza dati per dichiararla morta: ExoPlayer riprova subito su una nuova.
     */
    @Provides
    @Singleton
    @MediaHttp
    fun provideMediaHttpClient(base: OkHttpClient): OkHttpClient = base.newBuilder()
        .connectionPool(okhttp3.ConnectionPool(6, 30, TimeUnit.SECONDS))
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + com.mauztube.util.SafeHandler)

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "mauztube.db")
            // Mai cancellare i dati negli aggiornamenti: solo in caso di downgrade.
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = true)
            .build()

    @Provides fun providePlaybackDao(db: AppDatabase): PlaybackDao = db.playbackDao()
    @Provides fun provideSponsorSegmentDao(db: AppDatabase): SponsorSegmentDao = db.sponsorSegmentDao()
    @Provides fun provideSubscriptionDao(db: AppDatabase): SubscriptionDao = db.subscriptionDao()
    @Provides fun provideWatchLaterDao(db: AppDatabase): WatchLaterDao = db.watchLaterDao()
    @Provides fun provideVideoCacheDao(db: AppDatabase): VideoCacheDao = db.videoCacheDao()
    @Provides fun provideSearchHistoryDao(db: AppDatabase): com.mauztube.data.local.SearchHistoryDao = db.searchHistoryDao()
    @Provides fun provideMyCommentDao(db: AppDatabase): com.mauztube.data.local.MyCommentDao = db.myCommentDao()
    @Provides fun provideDownloadDao(db: AppDatabase): com.mauztube.data.local.DownloadDao = db.downloadDao()
    @Provides fun provideRatingDao(db: AppDatabase): com.mauztube.data.local.RatingDao = db.ratingDao()
    @Provides fun providePlaylistDao(db: AppDatabase): com.mauztube.data.local.PlaylistDao = db.playlistDao()
}
