package com.mauztube

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
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
    @Inject lateinit var dns: com.mauztube.data.net.CachingDns
    @Inject @com.mauztube.di.MediaHttp lateinit var mediaHttp: OkHttpClient

    override fun onCreate() {
        super.onCreate()
        // Solo nelle build di debug: segnala nel log letture/scritture/rete sul thread
        // dell'interfaccia (causa tipica di scatti).
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            android.os.StrictMode.setThreadPolicy(
                android.os.StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().detectNetwork().penaltyLog().build()
            )
        }
        com.mauztube.util.CrashLog.install(this)
        com.mauztube.util.DeviceProfile.init(this)
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
        com.mauztube.data.net.NetworkWatcher.start(this, listOf(okHttpClient, mediaHttp), dns)
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
            // Bitmap "hardware" (predefinite di Coil): l'immagine viene caricata nella memoria della
            // GPU già durante la decodifica, in background. Con RGB_565 (usato prima) erano bitmap
            // normali: ogni miniatura nuova veniva copiata sulla GPU mentre si disegnava il
            // fotogramma, proprio durante lo scorrimento (scatti a 90–120 Hz). In più le bitmap
            // hardware non occupano la memoria Java dell'app.
            // Niente dissolvenza: durante lo scorrimento ogni miniatura nuova verrebbe disegnata
            // due volte per 150 ms.
            .memoryCache {
                coil3.memory.MemoryCache.Builder()
                    .maxSizePercent(context, if (com.mauztube.util.DeviceProfile.lowMemory) 0.10 else 0.15)
                    .build()
            }
            .build()
}
