package com.appblocker.data

/**
 * App categories used to color Insights and to rank the app picker.
 * Color stored as ARGB Long (no compose dep here). [weight] = how distracting / worth
 * blocking the category is (higher floats to the top of the picker list).
 */
enum class AppCategory(val label: String, val color: Long, val weight: Int) {
    SOCIAL("Social", 0xFFEC4899, 100),
    VIDEO("Video", 0xFFEF4444, 100),
    GAMES("Games", 0xFFF59E0B, 70),
    CHAT("Chat", 0xFF22C55E, 40),
    PRODUCTIVE("Productive", 0xFF3B82F6, 0),
    OTHER("Other", 0xFF94A3B8, 20),
}

object AppCategories {
    private val map: Map<String, AppCategory> = buildMap {
        listOf(
            "com.instagram.android", "com.zhiliaoapp.musically", "com.facebook.katana",
            "com.twitter.android", "com.snapchat.android", "com.reddit.frontpage",
            "com.pinterest", "com.linkedin.android",
        ).forEach { put(it, AppCategory.SOCIAL) }

        listOf(
            "com.google.android.youtube", "com.netflix.mediaclient", "tv.twitch.android.app",
            "com.disney.disneyplus", "com.amazon.avod.thirdpartyclient",
        ).forEach { put(it, AppCategory.VIDEO) }

        listOf(
            "com.supercell.clashofclans", "com.king.candycrushsaga", "com.mojang.minecraftpe",
            "com.roblox.client", "com.google.android.play.games",
        ).forEach { put(it, AppCategory.GAMES) }

        listOf(
            "com.whatsapp", "com.facebook.orca", "org.telegram.messenger",
            "com.google.android.apps.messaging", "com.discord", "com.android.mms",
        ).forEach { put(it, AppCategory.CHAT) }

        listOf(
            "com.android.chrome", "com.google.android.gm", "com.google.android.calendar",
            "com.google.android.apps.docs", "com.google.android.keep",
            "com.microsoft.office.outlook", "com.android.vending",
        ).forEach { put(it, AppCategory.PRODUCTIVE) }
    }

    fun categoryOf(pkg: String): AppCategory = map[pkg] ?: AppCategory.OTHER

    /** Distraction weight for a package (higher = more worth blocking). */
    fun weightOf(pkg: String): Int = categoryOf(pkg).weight
}
