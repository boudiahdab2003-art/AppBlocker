package com.appblocker.data

import android.content.Context

/**
 * **Whether this install may send reports on its own** — asked once, on a new install (his choice,
 * 28 Sep 2026).
 *
 * Until 1.170 every direct-download install sent a profile the first time it was opened, a weekly
 * summary, and every stoppage, while the Accessibility disclosure said "no server" and the privacy
 * policy "no server-side copy". Every install that had ever reported was his, and on 25 Sep he
 * chose to leave it. On 26 Sep a stranger's PC emulator (#202 — the repository is public) sent its
 * profile the moment it was opened. Asked again, he chose "Ask first on new installs": a new install
 * asks once and sends nothing on its own until it is told yes, while his own installs keep sending
 * and never see the question.
 *
 * What the owner types and sends himself (Profile ▸ Report a problem) is never held back: that
 * screen lists what goes with it, and pressing Send is the answer.
 */
object ReportConsent {

    enum class State { UNASKED, YES, NO }

    /**
     * The installs that were reporting before the question existed: his main space, its Second Space
     * copy, his second phone and his tablet — every installId in the tracker on 28 Sep 2026 but the
     * stranger's. They are random ids minted per install ([SettingsStore.installId]) and name nothing
     * about anyone. A reinstall mints a new one and is asked like any other install.
     */
    internal val OWNER_INSTALLS = setOf("a8dfc579", "5bf2e3b3", "868db09c", "8eef460f")

    /**
     * The answer, from what is stored. Pure, so every case is tested.
     *
     * An answer given always wins — his own installs can be switched off in Profile like any other.
     * With none given, only an install already on [OWNER_INSTALLS] counts as yes; a fresh install has
     * no id yet, and an unknown one was never asked.
     */
    internal fun state(stored: String?, installId: String?): State = when (stored) {
        "yes" -> State.YES
        "no" -> State.NO
        else -> if (installId != null && installId in OWNER_INSTALLS) State.YES else State.UNASKED
    }

    /** Unreadable reads as [State.UNASKED]: nothing is sent on the strength of an answer not read. */
    fun state(context: Context): State = runCatching {
        state(SettingsStore.reportConsent(context), SettingsStore.installIdIfMinted(context))
    }.getOrDefault(State.UNASKED)

    /** Whether reports the app files by itself may be queued and sent. */
    fun automaticAllowed(context: Context): Boolean = state(context) == State.YES

    /** Records his answer. A no also drops what an earlier build queued without asking. */
    fun answer(context: Context, yes: Boolean) {
        runCatching {
            SettingsStore.setReportConsent(context, yes)
            if (!yes) BugReportQueue.dropAutomatic(context)
        }
    }
}
