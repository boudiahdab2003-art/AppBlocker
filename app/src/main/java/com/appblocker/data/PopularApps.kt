package com.appblocker.data

/** A well-known app the user can pre-block even before it's installed. */
data class PopularApp(val packageName: String, val label: String)

/**
 * Curated list of popular, often-distracting apps so the user can block them ahead of time
 * ("just in case I install them"). Blocking matches by package name regardless of whether
 * the app is installed, so picking one here creates a rule that fires the moment it's
 * installed and opened. Package names mirror those in [AppCategories] (so the distraction
 * weights already apply); labels are the friendly display names.
 */
val POPULAR_APPS: List<PopularApp> = listOf(
    // Social
    PopularApp("com.zhiliaoapp.musically", "TikTok"),
    PopularApp("com.instagram.android", "Instagram"),
    PopularApp("com.snapchat.android", "Snapchat"),
    PopularApp("com.facebook.katana", "Facebook"),
    PopularApp("com.twitter.android", "X (Twitter)"),
    PopularApp("com.reddit.frontpage", "Reddit"),
    PopularApp("com.pinterest", "Pinterest"),
    PopularApp("com.linkedin.android", "LinkedIn"),
    PopularApp("com.bereal.ft", "BeReal"),
    // Video
    PopularApp("com.google.android.youtube", "YouTube"),
    PopularApp("com.netflix.mediaclient", "Netflix"),
    PopularApp("tv.twitch.android.app", "Twitch"),
    PopularApp("com.disney.disneyplus", "Disney+"),
    PopularApp("com.amazon.avod.thirdpartyclient", "Prime Video"),
    // Chat
    PopularApp("com.whatsapp", "WhatsApp"),
    PopularApp("com.facebook.orca", "Messenger"),
    PopularApp("org.telegram.messenger", "Telegram"),
    PopularApp("com.discord", "Discord"),
    // Games
    PopularApp("com.supercell.clashofclans", "Clash of Clans"),
    PopularApp("com.king.candycrushsaga", "Candy Crush Saga"),
    PopularApp("com.roblox.client", "Roblox"),
    PopularApp("com.mojang.minecraftpe", "Minecraft"),
    PopularApp("com.activision.callofduty.shooter", "Call of Duty Mobile"),
)
