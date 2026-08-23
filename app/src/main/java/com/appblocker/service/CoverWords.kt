package com.appblocker.service

import android.content.Context
import com.appblocker.data.AppLocale

/**
 * A [BlockWords] that speaks the app's chosen language.
 *
 * **The reason this exists as its own thing.** Everything that puts words on the block screen runs
 * inside the accessibility service, and a Service never passes through `attachBaseContext` — so
 * `getString` on a service context returns whatever the *phone* is set to. Without this, an owner
 * who set the app to Arabic would meet an English block screen: the one surface they see at their
 * worst moment, and the one place a language they are not reading fluently is least welcome.
 *
 * Built fresh at each call rather than cached, because the language can change while the service
 * is running and the service does not restart when it does.
 */
internal fun coverWords(context: Context): BlockWords {
    val ctx = AppLocale.wrap(context)
    return object : BlockWords {
        override fun get(id: Int, vararg args: Any): String =
            if (args.isEmpty()) ctx.getString(id) else ctx.getString(id, *args)

        override fun plural(id: Int, count: Int, vararg args: Any): String =
            ctx.resources.getQuantityString(id, count, *args)
    }
}
