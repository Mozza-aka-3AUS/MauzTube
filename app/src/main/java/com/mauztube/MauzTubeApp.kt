package com.mauztube

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.request.allowRgb565
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.mauztube.data.extractor.NewPipeDownloader
import com.mauztube.util.I18n
import com.mauztube.data.repository.YouTubeRepository
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.util.Locale
import javax.inject.Inject

@HiltAndroidApp
class MauzTubeApp : Application(), SingletonImageLoader.Factory {

    @Inject lateinit var downloader: NewPipeDownloader
    @Inject lateinit var okHttpClient: OkHttpClient
    @Inject lateinit var youtube: YouTubeRepository

    override fun onCreate() {
        super.onCreate()
        I18n.init(this)
        val locale = Locale.getDefault()
        NewPipe.init(
            downloader,
            Localization.fromLocale(locale),
            ContentCountry(locale.country.ifBlank { "US" }),
        )
        I18n.applyToExtractor()
        // Connessioni TLS e versione client pronte prima che l'utente apra un video
        // (misurato: senza warm-up il primo video richiede ~5 s di estrazione, poi ~2 s).
        youtube.warmUp()
        // Al ritorno nell'app le connessioni potrebbero essere scadute: si riaprono subito.
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : androidx.lifecycle.DefaultLifecycleObserver {
                override fun onStart(owner: androidx.lifecycle.LifecycleOwner) = youtube.warmUp()
            }
        )
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient })) }
            // Miniature in RGB_565: metà memoria, meno GC durante lo scroll (non serve trasparenza).
            .allowRgb565(true)
            .memoryCache { coil3.memory.MemoryCache.Builder().maxSizePercent(context, 0.15).build() }
            .build()
}
