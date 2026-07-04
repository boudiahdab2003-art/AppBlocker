package com.appblocker.data

import android.content.Context

/**
 * Per-template overrides for which Quick Block extra options a template turns on. A template
 * ships with default options; if the user customises them, the chosen option keys are stored
 * here (keyed by template id, newline-joined) and used instead of the defaults.
 */
object TemplateOptionsStore {
    private const val PREFS = "template_options"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The user's chosen option keys for [templateId], or null if they haven't customised it. */
    fun optionsFor(context: Context, templateId: String): Set<String>? {
        val raw = prefs(context).getString(templateId, null) ?: return null
        return if (raw.isEmpty()) emptySet() else raw.split("\n").toSet()
    }

    fun setOptions(context: Context, templateId: String, keys: Set<String>) {
        prefs(context).edit().putString(templateId, keys.joinToString("\n")).apply()
    }
}
