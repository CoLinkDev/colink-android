package com.colink.android.util

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

object LocaleHelper {
    private const val PREFS_NAME = "colink_locale"
    private const val KEY_LANGUAGE = "language"

    fun localized(context: Context): Context =
        wrap(context, cachedLanguage(context))

    fun wrap(context: Context, language: String): Context {
        val locale = getLocaleFromCode(language) ?: return context
        Locale.setDefault(locale)
        
        val resources = context.resources
        val configuration = Configuration(resources.configuration)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            configuration.setLocale(locale)
            val localeList = LocaleList(locale)
            LocaleList.setDefault(localeList)
            configuration.setLocales(localeList)
            return context.createConfigurationContext(configuration)
        } else {
            @Suppress("DEPRECATION")
            configuration.locale = locale
            @Suppress("DEPRECATION")
            resources.updateConfiguration(configuration, resources.displayMetrics)
            return context
        }
    }
    
    fun getLocaleFromCode(language: String): Locale? {
        return when (language) {
            "en" -> Locale.ENGLISH
            "zh-CN" -> Locale.SIMPLIFIED_CHINESE
            "zh-TW" -> Locale.TRADITIONAL_CHINESE
            "ja" -> Locale.JAPANESE
            "ko" -> Locale.KOREAN
            "es" -> Locale("es")
            "de" -> Locale.GERMAN
            "ru" -> Locale("ru")
            else -> null // system default / no override
        }
    }

    fun cachedLanguage(context: Context): String =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, "system")
            ?: "system"

    fun cacheLanguage(context: Context, language: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LANGUAGE, language)
            .apply()
    }
}
