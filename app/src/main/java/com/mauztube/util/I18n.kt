package com.mauztube.util

import android.content.Context
import android.content.res.Configuration
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.util.Locale

enum class AppLanguage(val tag: String, val label: String) {
    EN("en", "English"),
    IT("it", "Italiano"),
}

/**
 * Lingua dell'app (inglese predefinito, italiano disponibile), scelta al primo avvio e
 * modificabile dalle Impostazioni. È salvata in SharedPreferences (lettura sincrona) per
 * essere applicata prima che venga disegnato qualsiasi testo; un cambio ricrea l'activity.
 *
 * I testi usano [tr]: entrambe le traduzioni stanno accanto, nel punto in cui si usano.
 */
object I18n {
    @Volatile
    var language: AppLanguage = AppLanguage.EN
        private set

    private const val PREFS = "i18n"
    private const val KEY = "language"

    fun init(context: Context) {
        val tag = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
        language = AppLanguage.entries.firstOrNull { it.tag == tag } ?: AppLanguage.EN
    }

    fun set(context: Context, lang: AppLanguage) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, lang.tag).apply()
        language = lang
        applyToExtractor()
    }

    val locale: Locale get() = Locale.forLanguageTag(language.tag)

    /** Lingua dei contenuti YouTube (date relative, contatori, titoli localizzati). */
    fun applyToExtractor() {
        val country = Locale.getDefault().country.ifBlank { if (language == AppLanguage.IT) "IT" else "US" }
        NewPipe.setupLocalization(Localization(language.tag, country), ContentCountry(country))
    }

    /** Contesto con la lingua dell'app (risorse di sistema, formati numerici, notifiche). */
    fun wrap(base: Context): Context {
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}

/** Testo nella lingua corrente dell'app. */
fun tr(en: String, it: String): String = if (I18n.language == AppLanguage.IT) it else en
