package com.appblocker.data

/** App categories used to color Insights. Color stored as ARGB Long (no compose dep here). */
enum class AppCategory(val label: String, val color: Long) {
    SOCIAL("Social", 0xFFEC4899),
    VIDEO("Video", 0xFFEF4444),
    GAMES("Games", 0xFFF59E0B),
    CHAT("Chat", 0xFF22C55E),
    PRODUCTIVE("Productive", 0xFF3B82F6),
    OTHER("Other", 0xFF94A3B8),
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
}
