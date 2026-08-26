package com.appblocker.data

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * What language the app speaks, and how that choice reaches every corner of it.
 *
 * **Why the app cannot simply follow the phone.** It can, and that is the default — but the owner
 * lives in Germany with an Arabic phone and wanted to be able to force English, and somebody else
 * will want the opposite. So "follow the phone" is a *choice* here rather than the only behaviour.
 *
 * **The half that is easy to get wrong.** An Activity picks its language up from
 * `attachBaseContext`, which is one line each and hard to forget. Everything the accessibility
 * service draws does not: the block screen is an overlay inflated from the *service's* context
 * ([com.appblocker.service.BlockOverlay]), and every notification is built the same way. Those have
 * to be handed a [wrap]ped context explicitly, or the app is Arabic and the block screen — the one
 * surface that matters most, met at the worst moment — is still English.
 *
 * **On Android 13 and up the system has its own per-app language screen**, and two settings that
 * disagree about the same thing is worse than one. So [set] writes both, and [tag] reads the
 * system's answer first where there is one; the two cannot drift apart.
 */
object AppLocale {

    private const val PREFS = "language"
    private const val KEY_TAG = "tag"

    /** Follow whatever the phone is set to. The default, and the reason first run is Arabic on an
     *  Arabic phone without anybody having to find a setting. */
    const val SYSTEM = ""
    const val ENGLISH = "en"
    const val ARABIC = "ar"

    /** In the order the picker shows them. */
    val CHOICES = listOf(SYSTEM, ENGLISH, ARABIC)

    /**
     * The language tag in force: [SYSTEM], [ENGLISH] or [ARABIC].
     *
     * On API 33+ the system's per-app setting wins, because that is the one the user can also
     * change from outside the app — reading our own copy there would let the Profile row claim
     * English while the app renders Arabic.
     */
    fun tag(ctx: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val system = runCatching {
                ctx.getSystemService(LocaleManager::class.java)?.applicationLocales
            }.getOrNull()
            if (system != null && !system.isEmpty) return system[0]?.language ?: SYSTEM
            return SYSTEM
        }
        return p(ctx).getString(KEY_TAG, SYSTEM) ?: SYSTEM
    }

    /** Both copies, always. See the class note on why they must not drift. */
    fun set(ctx: Context, tag: String) {
        p(ctx).edit().putString(KEY_TAG, tag).apply()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                ctx.getSystemService(LocaleManager::class.java)?.applicationLocales =
                    if (tag.isEmpty()) LocaleList.getEmptyLocaleList()
                    else LocaleList.forLanguageTags(tag)
            }
        }
    }

    /**
     * [ctx] with the chosen language applied — the context every string must be resolved through
     * outside an Activity.
     *
     * Returns [ctx] unchanged when the choice is "follow the phone", so the ordinary case costs
     * nothing and behaves exactly as the app always has. The layout direction is set alongside the
     * locale deliberately: a configuration carrying Arabic but a left-to-right direction renders an
     * Arabic block screen laid out backwards.
     */
    fun wrap(ctx: Context): Context {
        val tag = tag(ctx)
        if (tag.isEmpty()) return ctx
        val locale = Locale.forLanguageTag(tag)
        val config = Configuration(ctx.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return ctx.createConfigurationContext(config)
    }

    /**
     * **Each language names itself.** A picker that says "Arabic" in English is no use to somebody
     * who cannot read the screen it is on — the whole reason they are looking for it.
     */
    fun label(tag: String): String = when (tag) {
        ENGLISH -> "English"
        ARABIC -> "العربية"
        else -> "" // the system row is labelled from resources, in the current language
    }

    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
