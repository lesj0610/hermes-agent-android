package io.github.lesj0610.hermes.core

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * App language handling.
 *
 * The app owns its own translations rather than reading them from the gateway.
 * That is not a preference — the api_server platform exposes no locale field at
 * all, so there is nothing to read without changing the backend, and changing
 * the backend is out of scope (DESIGN.md §1). Korean therefore works whether or
 * not the agent behind it speaks Korean.
 *
 * Language *codes* follow the agent's own `locales/` set so a string bundle can
 * be lifted across without renaming:
 *
 *   af ar de en es fr ga hu it ja ko pt ru tr uk zh zh-hant
 *
 * [SUPPORTED] lists only the languages this app actually ships resources for.
 * Adding one means adding `res/values-<code>/strings.xml` and an entry here —
 * nothing else. Anything not listed falls back to English.
 */
object Language {

    /** Empty tag means "follow the device". */
    const val SYSTEM = ""

    data class Option(val tag: String, val englishName: String, val nativeName: String)

    val SUPPORTED: List<Option> = listOf(
        Option("en", "English", "English"),
        Option("ko", "Korean", "한국어"),
    )

    /**
     * Returns a context configured for [tag]. Called from
     * `Activity.attachBaseContext`, which is early enough that the whole view
     * tree — including resources loaded by Compose — sees the override.
     */
    fun wrap(context: Context, tag: String): Context {
        if (tag == SYSTEM) return context
        val locale = Locale.forLanguageTag(tag)
        if (locale.language.isEmpty()) return context

        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return context.createConfigurationContext(config)
    }

    /** The tag actually in effect, resolving [SYSTEM] against the device locale. */
    fun effectiveTag(context: Context, stored: String): String {
        if (stored != SYSTEM) return stored
        val device = context.resources.configuration.locales[0] ?: Locale.ENGLISH
        return SUPPORTED.firstOrNull { it.tag == device.language }?.tag ?: "en"
    }
}
