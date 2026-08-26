package com.appblocker.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/**
 * An island of left-to-right, for the screens that are still English inside an Arabic app.
 *
 * **This is not cosmetic tidying — without it those screens read as damaged.** An English sentence
 * laid out in a right-to-left paragraph puts its trailing punctuation at the *front*: "What should
 * we call you?" renders as "?What should we call you", and a paragraph ending in a full stop starts
 * with one. It was visible on the first Arabic build, on the setup screen, before that screen was
 * translated. So "leave it in English for now" is not a neutral choice in an RTL locale — it looks
 * like a bug, and a person who does not know what bidirectional text is has no way to read it as
 * anything else.
 *
 * Applied only to the long-form reading material the owner chose to leave in English: the
 * instructions, the detox guide, the Twelve Steps, the scenarios, the version history, and the
 * diagnostics page (which stays English on purpose — it is the screen he screenshots for me).
 *
 * **The rest of the app must never be wrapped in this.** Everything translated has to mirror, and a
 * stray use here would silently un-mirror a screen that should read right-to-left.
 */
@Composable
fun EnglishOnly(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalLayoutDirection provides LayoutDirection.Ltr,
        content = content,
    )
}
