package com.mauztube.data.prefs

import com.mauztube.util.tr

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Categorie SponsorBlock con actionType "skip". */
enum class SponsorCategory(
    val key: String,
    private val labelEn: String,
    private val labelIt: String,
    private val descEn: String,
    private val descIt: String,
) {
    SPONSOR("sponsor", "Sponsor", "Sponsor", "Paid promotion", "Promozione a pagamento"),
    SELF_PROMO("selfpromo", "Self-promotion", "Autopromozione", "Merch, Patreon, unpaid promotion", "Merch, Patreon, promozione non pagata"),
    INTERACTION("interaction", "Interaction reminder", "Promemoria interazione", "\"Subscribe\", \"leave a like\"…", "\"Iscriviti\", \"lascia un like\"…"),
    INTRO("intro", "Intro", "Intro", "Intro animations or opening sequences", "Animazioni o sigle iniziali"),
    OUTRO("outro", "Outro / End cards", "Outro / Titoli di coda", "End screens, credits", "Schermate finali, crediti"),
    PREVIEW("preview", "Preview / Recap", "Anteprima / Riepilogo", "Recaps or previews", "Recap o anticipazioni"),
    FILLER("filler", "Filler / Tangents", "Divagazioni", "Non-essential tangents", "Tangenti non essenziali"),
    MUSIC_OFFTOPIC("music_offtopic", "Music: non-music section", "Musica: parti non musicali", "Only in music videos", "Solo nei video musicali");

    /** Calcolati a ogni lettura: seguono la lingua corrente dell'app. */
    val label: String get() = tr(labelEn, labelIt)
    val description: String get() = tr(descEn, descIt)

    companion object {
        val ALL_KEYS: Set<String> = entries.map { it.key }.toSet()
        val DEFAULT_KEYS = setOf(SPONSOR.key, SELF_PROMO.key, INTERACTION.key)
        fun fromKey(key: String) = entries.firstOrNull { it.key == key }
    }
}

data class AppSettings(
    val sponsorBlockEnabled: Boolean = true,
    val sponsorCategories: Set<String> = SponsorCategory.DEFAULT_KEYS,
    val sponsorNotify: Boolean = true,
    val sponsorServer: String = DEFAULT_SPONSOR_SERVER,
    val resumeEnabled: Boolean = true,
    val reopenLastVideo: Boolean = false,
    val saveIntervalSeconds: Int = 5,
    val maxVideoHeight: Int = 1080,
    val minVideoHeight: Int = 360,
    val aggressiveShortsFilter: Boolean = false,
    val subtitlesAuto: Boolean = false,
    val reportWatchHistory: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val dynamicColor: Boolean = false,
    val backgroundPlayback: Boolean = true,
    val autoPip: Boolean = false,
    val showDislikes: Boolean = true,
    /** A fine video parte da solo il primo consigliato (disattivata di default). */
    val autoplayNext: Boolean = false,
    val saveSearchHistory: Boolean = true,
) {
    companion object {
        const val DEFAULT_SPONSOR_SERVER = "https://sponsor.ajay.app"
        val QUALITY_OPTIONS = listOf(144, 240, 360, 480, 720, 1080, 1440, 2160)
    }
}

enum class ThemeMode(private val en: String, private val it: String) {
    SYSTEM("System", "Sistema"), LIGHT("Light", "Chiaro"), DARK("Dark", "Scuro");

    val label: String get() = tr(en, it)
}

/** Ultimo video in riproduzione al momento della chiusura dell'app. */
data class LastSession(val videoId: String)

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @com.mauztube.di.ApplicationScope private val appScope: kotlinx.coroutines.CoroutineScope,
) {
    private object Keys {
        val SB_ENABLED = booleanPreferencesKey("sb_enabled")
        val SB_CATEGORIES = stringSetPreferencesKey("sb_categories")
        val SB_NOTIFY = booleanPreferencesKey("sb_notify")
        val SB_SERVER = stringPreferencesKey("sb_server")
        val RESUME = booleanPreferencesKey("resume_enabled")
        val REOPEN = booleanPreferencesKey("reopen_last")
        val SAVE_INTERVAL = intPreferencesKey("save_interval")
        val MAX_HEIGHT = intPreferencesKey("max_height")
        val AGGRESSIVE_SHORTS = booleanPreferencesKey("aggressive_shorts")
        val LAST_VIDEO = stringPreferencesKey("last_video_id")
        val SUBS_AUTO = booleanPreferencesKey("subtitles_auto")
        val REPORT_HISTORY = booleanPreferencesKey("report_history")
        val MIN_HEIGHT = intPreferencesKey("min_height")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val BACKGROUND = booleanPreferencesKey("background_playback")
        val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
        val AUTO_PIP = booleanPreferencesKey("auto_pip")
        val SHOW_DISLIKES = booleanPreferencesKey("show_dislikes")
        val AUTOPLAY = booleanPreferencesKey("autoplay_next")
        val SEARCH_HISTORY = booleanPreferencesKey("save_search_history")
        val SUBS_MERGED = booleanPreferencesKey("subscriptions_merged")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        val d = AppSettings()
        AppSettings(
            sponsorBlockEnabled = p[Keys.SB_ENABLED] ?: d.sponsorBlockEnabled,
            sponsorCategories = p[Keys.SB_CATEGORIES] ?: d.sponsorCategories,
            sponsorNotify = p[Keys.SB_NOTIFY] ?: d.sponsorNotify,
            sponsorServer = p[Keys.SB_SERVER] ?: d.sponsorServer,
            resumeEnabled = p[Keys.RESUME] ?: d.resumeEnabled,
            reopenLastVideo = p[Keys.REOPEN] ?: d.reopenLastVideo,
            saveIntervalSeconds = p[Keys.SAVE_INTERVAL] ?: d.saveIntervalSeconds,
            maxVideoHeight = p[Keys.MAX_HEIGHT] ?: d.maxVideoHeight,
            aggressiveShortsFilter = p[Keys.AGGRESSIVE_SHORTS] ?: d.aggressiveShortsFilter,
            subtitlesAuto = p[Keys.SUBS_AUTO] ?: d.subtitlesAuto,
            reportWatchHistory = p[Keys.REPORT_HISTORY] ?: d.reportWatchHistory,
            minVideoHeight = p[Keys.MIN_HEIGHT] ?: d.minVideoHeight,
            themeMode = p[Keys.THEME_MODE]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.themeMode,
            dynamicColor = p[Keys.DYNAMIC_COLOR] ?: d.dynamicColor,
            backgroundPlayback = p[Keys.BACKGROUND] ?: d.backgroundPlayback,
            autoPip = p[Keys.AUTO_PIP] ?: d.autoPip,
            showDislikes = p[Keys.SHOW_DISLIKES] ?: d.showDislikes,
            autoplayNext = p[Keys.AUTOPLAY] ?: d.autoplayNext,
            saveSearchHistory = p[Keys.SEARCH_HISTORY] ?: d.saveSearchHistory,
        )
    }.distinctUntilChanged()

    /** Ultimo valore sempre in memoria: le schermate partono già piene, senza attendere il disco. */
    val state: kotlinx.coroutines.flow.StateFlow<AppSettings?> by lazy {
        settings.stateIn(appScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, null)
    }

    suspend fun current(): AppSettings = state.value ?: settings.first()

    suspend fun setSponsorBlockEnabled(v: Boolean) = edit { it[Keys.SB_ENABLED] = v }
    suspend fun setSponsorNotify(v: Boolean) = edit { it[Keys.SB_NOTIFY] = v }
    suspend fun setSponsorServer(v: String) = edit { it[Keys.SB_SERVER] = v.trim().trimEnd('/') }
    suspend fun setSponsorCategory(key: String, enabled: Boolean) = edit {
        val current = it[Keys.SB_CATEGORIES] ?: SponsorCategory.DEFAULT_KEYS
        it[Keys.SB_CATEGORIES] = if (enabled) current + key else current - key
    }
    suspend fun setResumeEnabled(v: Boolean) = edit { it[Keys.RESUME] = v }
    suspend fun setReopenLastVideo(v: Boolean) = edit { it[Keys.REOPEN] = v }
    suspend fun setSaveInterval(seconds: Int) = edit { it[Keys.SAVE_INTERVAL] = seconds.coerceIn(1, 60) }
    /** Minima e massima restano coerenti: alzare la minima oltre la massima sposta anche questa. */
    suspend fun setMaxVideoHeight(h: Int) = edit {
        it[Keys.MAX_HEIGHT] = h
        if ((it[Keys.MIN_HEIGHT] ?: AppSettings().minVideoHeight) > h) it[Keys.MIN_HEIGHT] = h
    }
    suspend fun setMinVideoHeight(h: Int) = edit {
        it[Keys.MIN_HEIGHT] = h
        if ((it[Keys.MAX_HEIGHT] ?: AppSettings().maxVideoHeight) < h) it[Keys.MAX_HEIGHT] = h
    }
    suspend fun setThemeMode(mode: ThemeMode) = edit { it[Keys.THEME_MODE] = mode.name }
    suspend fun setDynamicColor(v: Boolean) = edit { it[Keys.DYNAMIC_COLOR] = v }
    suspend fun setBackgroundPlayback(v: Boolean) = edit { it[Keys.BACKGROUND] = v }
    suspend fun setAutoPip(v: Boolean) = edit { it[Keys.AUTO_PIP] = v }
    suspend fun setShowDislikes(v: Boolean) = edit { it[Keys.SHOW_DISLIKES] = v }
    suspend fun setAutoplayNext(v: Boolean) = edit { it[Keys.AUTOPLAY] = v }
    suspend fun setSaveSearchHistory(v: Boolean) = edit { it[Keys.SEARCH_HISTORY] = v }
    suspend fun setAggressiveShorts(v: Boolean) = edit { it[Keys.AGGRESSIVE_SHORTS] = v }
    suspend fun setSubtitlesAuto(v: Boolean) = edit { it[Keys.SUBS_AUTO] = v }
    suspend fun setReportWatchHistory(v: Boolean) = edit { it[Keys.REPORT_HISTORY] = v }

    // --- Primo avvio e sincronizzazione ---

    val onboardingDone: Flow<Boolean> = context.dataStore.data.map { it[Keys.ONBOARDING_DONE] ?: false }
    suspend fun setOnboardingDone() = edit { it[Keys.ONBOARDING_DONE] = true }

    suspend fun subscriptionsMerged(): Boolean = context.dataStore.data.first()[Keys.SUBS_MERGED] ?: false
    suspend fun setSubscriptionsMerged(v: Boolean) = edit { it[Keys.SUBS_MERGED] = v }

    // --- Sessione di riproduzione attiva ---

    suspend fun lastSession(): LastSession? =
        context.dataStore.data.first()[Keys.LAST_VIDEO]?.let(::LastSession)

    suspend fun setLastSession(videoId: String?) = edit {
        if (videoId == null) it.remove(Keys.LAST_VIDEO) else it[Keys.LAST_VIDEO] = videoId
    }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }
}
