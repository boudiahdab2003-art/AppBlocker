package com.appblocker.data

/**
 * **What this phone actually is, versus what the app assumed about it.**
 *
 * Everything the app knows about non-Xiaomi phones was reasoned rather than measured: that Samsung
 * routes an uninstall through `com.samsung.android.packageinstaller`, that Device Care sits at a
 * particular activity, that Oppo's browser keeps Chromium's toolbar id. Each guess is defensible
 * and each one **fails silently** — a guard that never fires and a button that opens the wrong
 * page look exactly like a working app (docs/BLOCKING_INVARIANTS.md, invariant 15).
 *
 * Owning five phones would settle it. Failing that, the phone can be asked directly: Android will
 * say which package handles an uninstall, and whether a deep link's destination exists. That is
 * what this file turns into plain sentences for Profile ▸ *What the blocker sees*, so **the first
 * person to run this on a Samsung answers the open questions in one screenshot.**
 *
 * Pure, so it can be tested: the caller does the Android lookups and hands the answers in.
 */
data class PhoneFacts(
    /** [VendorAdvice.brand], or "" when the phone matched nothing and got the generic advice. */
    val brand: String,
    val sdkInt: Int,
    /** True for the sideloaded build, which is the one Android 13's restricted setting applies to. */
    val sideloaded: Boolean,
    /** What `ACTION_DELETE` resolves to, or null if nothing did. */
    val uninstallHandler: String?,
    /**
     * Whether the keep-alive deep link's destination exists here: null when this phone's advice
     * has no deep link at all (the generic entry), which is not a fault.
     */
    val keepAliveResolves: Boolean?,
)

/** What the uninstall handler means for Strict Mode's guard. */
enum class UninstallGuardVerdict {
    /** The screen that would appear is one the guard watches. */
    RECOGNISED,

    /**
     * A real package the guard does **not** watch — so `uninstallConfirmation` answers "not a
     * threat" and an uninstall goes through mid-session with nothing on screen to say the guard
     * was skipped. The whole reason this file exists; it must read as a fault.
     */
    UNRECOGNISED,

    /** Nothing resolved, so there is nothing to say — not a fault, just no answer. */
    UNKNOWN,
}

/**
 * Whether [handler] — the package Android says would show the uninstall dialog — is one
 * `GuardPackages.INSTALLERS` watches.
 *
 * A blank handler is [UninstallGuardVerdict.UNKNOWN] rather than a fault: package visibility or an
 * OEM that resolves nothing both land there, and "we could not check" must never be reported as
 * "we checked and it is fine". That is the same can't-tell-is-not-a-no rule the watcher follows
 * (invariant 4), applied to a diagnostic instead of a block.
 */
fun uninstallGuardVerdict(handler: String?, known: Set<String>): UninstallGuardVerdict = when {
    handler.isNullOrBlank() -> UninstallGuardVerdict.UNKNOWN
    handler in known -> UninstallGuardVerdict.RECOGNISED
    else -> UninstallGuardVerdict.UNRECOGNISED
}
