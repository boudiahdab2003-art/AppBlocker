package com.appblocker.data

import android.content.Context

/**
 * Per-template app overrides. A template ships with a default set of packages; if the user
 * customises which apps it blocks, that override is stored here (keyed by template id) and
 * used instead of the default. Package names are stored newline-joined.
 */
object TemplateStore {
    private const val PREFS = "template_apps"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The user's chosen packages for [templateId], or null if they haven't customised it. */
    fun packagesFor(context: Context, templateId: String): List<String>? {
        val raw = prefs(context).getString(templateId, null) ?: return null
        return if (raw.isEmpty()) emptyList() else raw.split("\n")
    }

    fun setPackages(context: Context, templateId: String, packages: List<String>) {
        prefs(context).edit().putString(templateId, packages.joinToString("\n")).apply()
    }
}
