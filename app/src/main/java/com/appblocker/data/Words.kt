package com.appblocker.data

import android.content.Context

/**
 * How code that must not hold a Context gets its words.
 *
 * **Two kinds of code need this, for opposite reasons.**
 *
 * The first is the accessibility service. Everything it draws — the block overlay, every
 * notification — runs in a Service, and a Service never passes through `attachBaseContext`. Calling
 * `getString` on it returns whatever the *phone* is set to, so an owner who chose Arabic would meet
 * an English block screen: the one surface they see at their worst moment. [of] wraps the context
 * in the app's chosen language and hands back this.
 *
 * The second is the pure logic — `decideBlock`, `plainLength`. Those are the only parts of the app
 * with real unit-test coverage precisely *because* they are functions of their arguments, and
 * handing them a Context to look strings up with would end that. They take a [Words] instead, and
 * the tests pass one backed by the shipped English `strings.xml`, so their assertions keep checking
 * real wording rather than a copy of it.
 *
 * Inside a `@Composable`, use `stringResource` / `pluralStringResource` instead — those already
 * resolve through the activity's context, which is localised.
 */
interface Words {
    fun get(id: Int, vararg args: Any): String
    fun plural(id: Int, count: Int, vararg args: Any): String

    companion object {
        @Volatile private var cachedTag: String? = null
        @Volatile private var cached: Words? = null

        /**
         * [context] in the app's chosen language.
         *
         * **Cached, and deliberately so.** The block decision builds one of these on every window
         * event, and `createConfigurationContext` is not free — a fresh Configuration a few times a
         * second is exactly the sort of cost that turns into a dropped frame on a slow phone, which
         * is the phone this app most needs to be fast on. The cache is keyed on the chosen language
         * and holds the *application* context, so it cannot outlive anything or leak an Activity.
         *
         * The language can still change while the service is running: the key check below rebuilds
         * it on the first call afterwards.
         */
        fun of(context: Context): Words {
            val tag = AppLocale.tag(context)
            cached?.let { if (cachedTag == tag) return it }
            val ctx = AppLocale.wrap(context.applicationContext)
            val words = object : Words {
                override fun get(id: Int, vararg args: Any): String =
                    if (args.isEmpty()) ctx.getString(id) else ctx.getString(id, *args)

                override fun plural(id: Int, count: Int, vararg args: Any): String =
                    ctx.resources.getQuantityString(id, count, *args)
            }
            cachedTag = tag
            cached = words
            return words
        }
    }
}
