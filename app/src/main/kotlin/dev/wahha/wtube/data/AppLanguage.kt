package dev.wahha.wtube.data

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * The interface language, [AppSettings.appLanguage]: [SYSTEM] follows the phone (Russian when it is Russian,
 * English otherwise), or one of the two the strings exist in. Plural rules, numbers and dates always follow
 * the language of the strings, never the phone's: Russian strings with English plural rules read wrong.
 */
object AppLanguage {
    const val SYSTEM = ""
    val Choices = listOf(SYSTEM, "ru", "en")

    /** "ru" or "en": a set [choice] wins, [SYSTEM] takes Russian only from a Russian [system]. */
    fun resolve(choice: String, system: Locale): String = when (choice) {
        "ru", "en" -> choice
        else -> if (system.language == "ru") "ru" else "en"
    }

    /** [resolve]d language with the [system]'s region, which picks the trending lists. */
    fun locale(choice: String, system: Locale): Locale =
        Locale.Builder().setLanguage(resolve(choice, system)).setRegion(system.country.takeIf { it.length == 2 }.orEmpty()).build()

    private var manager: LocaleManager? = null

    fun init(context: Context) {
        if (Build.VERSION.SDK_INT >= 33) manager = context.getSystemService(LocaleManager::class.java)
    }

    @Volatile private var system: Locale? = null

    /**
     * The phone's language and region, not the ones this process runs in: from Android 13 the process gets
     * the per-app language, without the region. Kept until [forgetSystem]: the lookup is a call to the system,
     * and every list row formats with it.
     */
    val systemLocale: Locale
        get() = system ?: readSystem().also { system = it }

    private fun readSystem(): Locale {
        if (Build.VERSION.SDK_INT >= 33) manager?.systemLocales?.takeUnless { it.isEmpty }?.let { return it[0] }
        return runCatching { Resources.getSystem().configuration.locales[0] }.getOrNull() ?: Locale.getDefault()
    }

    /** The configuration changed: the phone's language may be another one now. */
    fun forgetSystem() {
        system = null
    }

    /**
     * The language picked for the app in the phone's settings (Android 13+), as one of [Choices]; null when
     * it is none of them or the phone has no such setting.
     */
    fun fromSystemSettings(context: Context): String? {
        if (Build.VERSION.SDK_INT < 33) return null
        val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
        if (locales.isEmpty) return SYSTEM
        return locales[0].language.takeIf { it in Choices }
    }

    /** Shows [choice] in the phone's settings for the app (Android 13+), so both places agree. */
    fun toSystemSettings(context: Context, choice: String) {
        if (Build.VERSION.SDK_INT < 33) return
        val manager = context.getSystemService(LocaleManager::class.java)
        val locales = if (choice == SYSTEM) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(choice)
        if (manager.applicationLocales != locales) manager.applicationLocales = locales
    }

    private var cached: Pair<Locale, Resources>? = null

    /**
     * Strings in [AppLocale], for services and notifications: they get the process language, which is the
     * phone's. Kept for the current locale, so a switch reaches a notification already showing.
     */
    fun strings(context: Context): Resources {
        val locale = AppLocale
        cached?.let { (at, resources) -> if (at == locale) return resources }
        val config = Configuration(context.resources.configuration).apply { setLocale(locale) }
        return context.applicationContext.createConfigurationContext(config).resources.also { cached = locale to it }
    }
}

/** The locale of the interface, from [AppSettings.appLanguage]. */
val AppLocale: Locale get() = AppLanguage.locale(Settings.current.value.appLanguage, AppLanguage.systemLocale)
