package com.appblocker.data

import android.content.Context
import android.os.Build
import android.os.UserManager

/**
 * **Whether this copy of AppBlocker belongs to the space that is on screen** (invariant 79).
 *
 * Xiaomi's Second Space, and every phone's other users, are separate Android users, each with its
 * own copy of AppBlocker — and Android runs accessibility services **only for the user in front**.
 * Switching to the other space unbinds this space's watcher on purpose (the copy over there takes
 * over), and switching back binds it again. Nothing is broken while that happens, and nothing in
 * this space can be opened.
 *
 * Every check here read that as the blocker failing. On 16–18 Sep 2026 the owner's main phone filed
 * a stoppage for each visit to Second Space (`deaf=true` when the process outlived the unbind,
 * `killedBy=other@cached` when HyperOS then reclaimed it, and a HyperOS force stop "due to The system
 * loading is…" at almost every switch), always with `used=0`. The copy inside Second Space filed
 * the mirror image: 68 stoppages and 270 hours "unprotected", which were simply the hours he spent
 * in his main space. Worse, the reopen repair ([com.appblocker.service.SelfRestore]) fired from the
 * space behind, where no screen can open, counted those as tries that did not help, and so had
 * already given up for the day when a real stoppage cost him 35 minutes of use on 17 Sep.
 *
 * ⚠️ **Fails OPEN.** Older than Android 12, no service, or a throw all answer "in front", so a doubt
 * can only ever let a check run as it always did. Wrong the other way, it would hide a real stoppage
 * behind a guard nobody can see.
 */
object OwnSpace {

    /** True unless Android positively says another user is in front. */
    fun inFront(context: Context): Boolean =
        resolve(
            Build.VERSION.SDK_INT,
            runCatching { context.getSystemService(UserManager::class.java)?.isUserForeground }.getOrNull(),
        )

    /**
     * The rule on its own, for the tests: only a real `false` from Android on 12+ means "behind".
     * `UserManager.isUserForeground` is public from API 31 and needs no permission for the calling
     * user; below that there is nothing to ask.
     */
    internal fun resolve(sdk: Int, answer: Boolean?): Boolean =
        sdk < Build.VERSION_CODES.S || answer != false
}
