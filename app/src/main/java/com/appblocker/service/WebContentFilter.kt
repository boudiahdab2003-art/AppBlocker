package com.appblocker.service

import android.content.Context

/**
 * Decides whether some on-screen text (a web address or search query) should be
 * blocked: user keywords first, then — if enabled — the bundled adult lists.
 * Lists are loaded once from assets.
 */
class WebContentFilter private constructor(
    private val adultDomains: List<String>,
    private val adultKeywords: List<String>,
) {
    data class Hit(val title: String, val message: String)

    fun check(text: String, userKeywords: List<String>, blockAdult: Boolean): Hit? {
        if (text.isBlank()) return null
        val lower = text.lowercase()

        for (k in userKeywords) {
            if (k.isNotBlank() && lower.contains(k)) {
                return Hit("Blocked word", "“$k” is on your blocked list.")
            }
        }
        if (blockAdult) {
            for (d in adultDomains) {
                if (lower.contains(d)) {
                    return Hit("Adult site blocked", "This site is on the adult-content list.")
                }
            }
            for (k in adultKeywords) {
                if (lower.contains(k)) {
                    return Hit("Adult content blocked", "That search or page looks like adult content.")
                }
            }
        }
        return null
    }

    companion object {
        @Volatile private var INSTANCE: WebContentFilter? = null

        fun get(context: Context): WebContentFilter =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: WebContentFilter(
                    readLines(context, "adult_domains.txt"),
                    readLines(context, "adult_keywords.txt"),
                ).also { INSTANCE = it }
            }

        private fun readLines(context: Context, asset: String): List<String> =
            runCatching {
                context.assets.open(asset).bufferedReader().useLines { lines ->
                    lines.map { it.trim().lowercase() }
                        .filter { it.isNotEmpty() && !it.startsWith("#") }
                        .toList()
                }
            }.getOrDefault(emptyList())
    }
}
