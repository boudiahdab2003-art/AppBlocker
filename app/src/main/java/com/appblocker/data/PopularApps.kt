package com.appblocker.data

/**
 * A well-known app the user can pre-block even before it's installed.
 * [color] is the brand's signature colour (ARGB), used to draw a coloured initial badge in
 * the picker since a not-installed app has no real icon on the device yet.
 * [domains] are website keywords blocked automatically whenever this app is blocked, so the
 * app's website is covered too (matched as substrings of the browser's text — see
 * [com.appblocker.service.WebContentFilter]).
 */
data class PopularApp(
    val packageName: String,
    val label: String,
    val color: Long,
    val domains: List<String> = emptyList(),
)

/**
 * Curated list of popular social-media apps so the user can block them ahead of time
 * ("just in case I install them"). Focused on social media only (no games / streaming /
 * messaging). Blocking matches by package name regardless of whether the app is installed,
 * so picking one creates a rule that fires the moment it's installed and opened. Only apps
 * that aren't currently installed are shown (filtered in AppListViewModel).
 */
val POPULAR_APPS: List<PopularApp> = listOf(
    PopularApp("com.zhiliaoapp.musically", "TikTok", 0xFF010101, listOf("tiktok")),
    PopularApp("com.instagram.android", "Instagram", 0xFFE1306C, listOf("instagram")),
    PopularApp("com.snapchat.android", "Snapchat", 0xFFFFFC00, listOf("snapchat")),
    PopularApp("com.facebook.katana", "Facebook", 0xFF1877F2, listOf("facebook")),
    PopularApp("com.twitter.android", "X (Twitter)", 0xFF000000, listOf("twitter", "x.com")),
    PopularApp("com.reddit.frontpage", "Reddit", 0xFFFF4500, listOf("reddit")),
    PopularApp("com.pinterest", "Pinterest", 0xFFE60023, listOf("pinterest")),
    PopularApp("com.linkedin.android", "LinkedIn", 0xFF0A66C2, listOf("linkedin")),
    PopularApp("com.bereal.ft", "BeReal", 0xFF1A1A1A, listOf("bereal")),
    PopularApp("ai.x.grok", "Grok", 0xFF222222, listOf("grok.com")),
)

/**
 * Package name -> website keywords to block automatically when that app is blocked. Keyed by
 * package (not install state), so it works for both installed and not-yet-installed apps.
 */
val SOCIAL_DOMAINS: Map<String, List<String>> =
    POPULAR_APPS.associate { it.packageName to it.domains }
