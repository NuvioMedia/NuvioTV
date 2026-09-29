package com.nuvio.tv.core.util

import android.content.Context
import android.content.res.Configuration
import com.nuvio.tv.LocaleCache
import java.util.Locale

/**
 * Resolves this context against the app's own language setting.
 *
 * An injected `@ApplicationContext` keeps the system configuration, so it returns the wrong
 * language whenever the app language differs from the device's. Returns the receiver unchanged
 * when no app language is set.
 */
fun Context.withAppLocale(): Context {
    val tag = LocaleCache.localeTag.takeIf { it != LocaleCache.UNSET && it.isNotEmpty() }
        ?: return this
    val locale = Locale.forLanguageTag(tag)
    if (locale.toLanguageTag().isEmpty()) return this
    val configuration = Configuration(resources.configuration)
    configuration.setLocale(locale)
    return createConfigurationContext(configuration)
}
