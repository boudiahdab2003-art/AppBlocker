package com.appblocker.data

/**
 * Whether AppBlocker's own UI is the thing in front, published by MainActivity's lifecycle.
 *
 * The blocking watcher cannot work this out for itself. Our block cover and our activity are
 * the same package, so an accessibility event — or `rootInActiveWindow` — reporting "us"
 * is ambiguous: it means either "the cover is up, the blocked app is still behind it" or "the
 * user has walked into AppBlocker's own screens". The watcher assumed the former, which let a
 * cover be raised over our own settings screens, attributed to whichever app happened to be
 * open beforehand (the watcher never updates its foreground cache for our own package, so it
 * stays pointing at that app).
 *
 * The activity is the only place that knows, hence this flag. Read from the service's main
 * thread and written from the main thread, but volatile so the background scans can read it too.
 */
object OwnUi {
    @Volatile
    var visible: Boolean = false
}
