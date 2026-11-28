package com.apkupdater.util.play

import android.content.Context
import android.os.Build
import java.util.Locale

object PlayLocales {

    fun preferredLocale(context: Context): Locale =
        deviceLocales(context).firstOrNull() ?: Locale.US

    fun additionalLocales(context: Context): List<Locale> =
        deviceLocales(context).drop(1)

    private fun deviceLocales(context: Context): List<Locale> {
        val locales = mutableListOf<Locale>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val configured = context.resources.configuration.locales
            for (i in 0 until configured.size()) {
                normalizeLocale(configured[i])?.let { locales.add(it) }
            }
        }
        if (locales.isEmpty()) normalizeLocale(Locale.getDefault())?.let { locales.add(it) }
        return locales.distinctBy { it.toLanguageTag() }
    }

    private fun normalizeLocale(locale: Locale): Locale? {
        val language = locale.language.takeIf { it.isNotBlank() } ?: return null
        val country = locale.country.takeIf { it.isNotBlank() }
        return if (country == null) Locale(language) else Locale(language, country)
    }
}
