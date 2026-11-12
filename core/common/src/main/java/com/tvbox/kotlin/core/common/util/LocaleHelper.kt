package com.tvbox.kotlin.core.common.util

import android.annotation.TargetApi
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.Build
import java.util.Locale

/** 语言切换工具 */
object LocaleHelper {

    private const val SELECTED_LANGUAGE = "Locale.Helper.Selected.Language"

    @JvmStatic
    fun onAttach(context: Context): Context = setLocale(context, getPersistedData(context, Locale.getDefault().language))

    /**
     * 按 [defaultLanguage] 直接应用语言环境。
     * 注意：不能再用 getPersistedData 覆盖传入的目标语言——首次启动会 persist 默认语言，
     * 之后切换语言时若仍读持久化值，会导致 attachBaseContext 永远应用旧语言，切换不生效。
     */
    @JvmStatic
    fun onAttach(context: Context, defaultLanguage: String): Context =
        setLocale(context, defaultLanguage)

    @JvmStatic
    fun setLocale(context: Context, language: String): Context {
        persist(context, language)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            updateResources(context, language)
        } else {
            updateResourcesLegacy(context, language)
        }
    }

    private fun getPreferences(context: Context): SharedPreferences =
        context.getSharedPreferences("LocaleHelper", Context.MODE_PRIVATE)

    private fun getPersistedData(context: Context, defaultLanguage: String): String =
        getPreferences(context).getString(SELECTED_LANGUAGE, defaultLanguage) ?: defaultLanguage

    private fun persist(context: Context, language: String) {
        getPreferences(context).edit().putString(SELECTED_LANGUAGE, language).apply()
    }

    @TargetApi(Build.VERSION_CODES.N)
    private fun updateResources(context: Context, language: String): Context {
        val locale = Locale.forLanguageTag(language)
        Locale.setDefault(locale)
        val configuration = context.resources.configuration
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)
        return context.createConfigurationContext(configuration)
    }

    @Suppress("DEPRECATION")
    private fun updateResourcesLegacy(context: Context, language: String): Context {
        val locale = Locale.forLanguageTag(language)
        Locale.setDefault(locale)
        val resources = context.resources
        val configuration = resources.configuration
        configuration.locale = locale
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            configuration.setLayoutDirection(locale)
        }
        resources.updateConfiguration(configuration, resources.displayMetrics)
        return context
    }
}
