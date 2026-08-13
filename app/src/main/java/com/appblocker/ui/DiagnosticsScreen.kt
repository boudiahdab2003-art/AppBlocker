package com.appblocker.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.appblocker.data.QuickSession
import com.appblocker.data.SettingsStore
import com.appblocker.data.WatcherDiagnostics
import com.appblocker.service.findBrowserPackages
import com.appblocker.ui.theme.AppCard
import com.appblocker.ui.theme.Space
import com.appblocker.ui.theme.appBackground
import com.appblocker.ui.theme.pageWidth

/** Test tags for the rendering test. */
const val DIAGNOSTICS_BROWSERS_TAG = "diagnostics_browsers"
const val DIAGNOSTICS_LAST_LOOK_TAG = "diagnostics_last_look"

/**
 * **Profile ▸ What the blocker sees.** The screen this app has needed since it was written.
 *
 * The project's whole doctrine (`docs/BLOCKING_INVARIANTS.md`) is that **under-blocking is
 * invisible**: the owner notices a block screen that shouldn't be there, and never one that
 * failed to appear. That doctrine had no tool attached to it. When he asked why Instagram wasn't
 * blocked in Brave, there was no way to answer except to read the source, form a theory and ship
 * it — and the first theory was wrong, which cost a release to find out.
 *
 * The reason it is hard is that four separate things fail with the *same* symptom, which is
 * nothing happening at all:
 *
 * - the app doesn't count that browser as a browser (so no layer runs);
 * - it counts it, but can't read its address bar (so website blocking can't run);
 * - it reads it, but there are no site words live (nothing to match);
 * - everything works and blocking itself is paused.
 *
 * This tells them apart on the phone, in one screen, in plain words. It reads live state and one
 * record the watcher writes as it works ([WatcherDiagnostics]); it changes nothing and turns
 * nothing on.
 *
 * **Addresses:** only the host of the last page is ever shown, and only for the last look — see
 * [WatcherDiagnostics] for why the path is dropped before it is written.
 */
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // Read once per open, and again on demand: this is a snapshot of a moving system, and a
    // snapshot that quietly refreshes underneath the reader is harder to report back from.
    var nonce by remember { mutableStateOf(0) }
    val snapshot = remember(nonce) { readSnapshot(context) }

    Column(Modifier.fillMaxSize().background(appBackground()).safeDrawingPadding()) {
        EditorTopBar(title = "What the blocker sees", onBack = onBack)
        LazyColumn(
            Modifier.fillMaxHeight().pageWidth().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            item {
                Text(
                    "This is what the blocker believes right now. If something isn't being " +
                        "blocked, the reason is almost always on this page.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = Space.xs),
                )
            }

            item { SectionHeading("Is blocking on at all?") }
            item {
                AppCard {
                    for (line in snapshot.protection) FactRow(line)
                }
            }

            item { SectionHeading("Browsers it has found") }
            item {
                AppCard(modifier = Modifier.testTag(DIAGNOSTICS_BROWSERS_TAG)) {
                    if (snapshot.browsers.isEmpty()) {
                        FactRow(
                            Fact(
                                "No browsers found",
                                "Nothing on this phone is being treated as a browser, so no " +
                                    "website blocking can happen anywhere.",
                                good = false,
                            ),
                        )
                    } else {
                        Text(
                            "A browser missing from this list is not filtered at all — no " +
                                "blocked sites, no adult sites, nothing.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = Space.sm),
                        )
                        for (b in snapshot.browsers) {
                            FactRow(Fact(appLabel(context, b), b, good = true))
                        }
                    }
                }
            }

            item { SectionHeading("The last app it looked at") }
            item {
                AppCard(modifier = Modifier.testTag(DIAGNOSTICS_LAST_LOOK_TAG)) {
                    for (line in snapshot.lastLook) FactRow(line)
                }
            }

            item {
                Text(
                    "Nothing on this page is sent anywhere. Only the website name of the last " +
                        "page is kept — never the full address, and never a history.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = Space.sm),
                )
            }
            item {
                GradientButton(text = "Refresh", onClick = { nonce++ })
                Spacer(Modifier.height(Space.xxl))
            }
        }
    }
}

/** One plain-language fact, and whether it is the healthy answer. */
internal data class Fact(val title: String, val detail: String, val good: Boolean?)

private data class Snapshot(
    val protection: List<Fact>,
    val browsers: List<String>,
    val lastLook: List<Fact>,
)

/**
 * Everything the screen shows, gathered in one place so the page itself stays a rendering of it.
 *
 * `good` is `null` where a setting is a genuine choice rather than a fault — the point is to
 * explain, not to nag about a configuration the owner picked deliberately.
 */
private fun readSnapshot(context: Context): Snapshot {
    val updatePaused = SettingsStore.updatePaused(context)
    val quickPaused = SettingsStore.quickBlockPaused(context)
    val session = QuickSession.state(context)
    val allowlist = SettingsStore.quickBlockAllowlist(context)
    val pack = SettingsStore.adultWordsPack(context)
    val adultSites = SettingsStore.blockAdult(context)
    val unsupported = SettingsStore.blockUnsupportedBrowsers(context)

    val protection = buildList {
        add(
            if (updatePaused) Fact(
                "Blocking is paused after the update",
                "Nothing is blocked until you tap Reactivate on the Blocking tab. This is the " +
                    "most common reason blocking seems to have stopped.",
                good = false,
            ) else Fact("Not paused for an update", "Blocking is not being held off.", good = true),
        )
        add(
            when {
                session.active && !session.blockingNow -> Fact(
                    "A break is running", "Blocking is off until the break ends.", good = false,
                )
                quickPaused -> Fact(
                    "Quick Block is paused",
                    "You paused it. Blocked apps and their websites are open until you turn it " +
                        "back on.",
                    good = false,
                )
                else -> Fact("Quick Block is on", "Blocking is enforcing.", good = true)
            },
        )
        add(
            Fact(
                if (allowlist) "Allowlist mode" else "Blocklist mode",
                if (allowlist) {
                    "Only your allowed apps work. Blocked apps' websites are NOT auto-blocked " +
                        "in this mode — that's a Blocklist feature."
                } else "Only the apps you chose are blocked.",
                good = null,
            ),
        )
        add(
            Fact(
                if (unsupported) "\"Block unsupported browsers\" is on"
                else "\"Block unsupported browsers\" is off",
                if (!unsupported) {
                    "A browser the app can't read isn't blocked outright."
                } else if (!pack && !adultSites) {
                    "But it can't do anything: this switch only acts when there's something to " +
                        "filter for, and both adult filters are off and you may have no words " +
                        "of your own. That's why it can look like it's doing nothing."
                } else "A browser the app can't read is blocked outright.",
                good = if (unsupported && (pack || adultSites)) true else null,
            ),
        )
    }

    val look = WatcherDiagnostics.last(context)
    val lastLook = when {
        look == null -> listOf(
            Fact(
                "It hasn't looked at anything yet",
                "Open an app, come back here and tap Refresh. If this stays empty, the blocking " +
                    "service isn't running — check Profile ▸ Permissions.",
                good = false,
            ),
        )
        else -> buildList {
            add(
                Fact(
                    appLabel(context, look.packageName),
                    "${look.packageName} — ${agoLabel(System.currentTimeMillis() - look.at)}",
                    good = null,
                ),
            )
            add(
                if (look.isBrowser) Fact(
                    "Treated as a browser", "Website filtering runs in this app.", good = true,
                ) else Fact(
                    "NOT treated as a browser",
                    "If this is a browser, that is the bug: no website filtering runs here at " +
                        "all, and the \"block unsupported browsers\" switch can't act on it " +
                        "either, because that check starts by asking the same question.",
                    good = false,
                ),
            )
            if (look.isBrowser) {
                add(
                    if (look.host != null) Fact(
                        "Address bar read: ${look.host}",
                        "The app can see which site you're on here, so blocked sites can be " +
                            "caught.",
                        good = true,
                    ) else Fact(
                        "Couldn't read the address bar",
                        "Blocked words are still caught from the text on the page, but " +
                            "blocking a site because you blocked its app needs the address, so " +
                            "that part can't work in this browser.",
                        good = false,
                    ),
                )
            }
            add(
                if (look.siteWords.isNotBlank()) Fact(
                    "Websites being blocked", look.siteWords, good = true,
                ) else Fact(
                    "No website words are live",
                    "No blocked app contributed a website. Either nothing is blocked right now, " +
                        "the blocked apps have no known website, or blocking is paused above.",
                    good = false,
                ),
            )
        }
    }

    return Snapshot(
        protection = protection,
        browsers = runCatching { findBrowserPackages(context).sorted() }.getOrDefault(emptyList()),
        lastLook = lastLook,
    )
}

/** The app's own name for a package, falling back to the package name itself. */
private fun appLabel(context: Context, pkg: String): String = runCatching {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
}.getOrDefault(pkg)

/** "just now" / "4 min ago" / "2 h ago" — enough to tell a live reading from a stale one. */
internal fun agoLabel(millis: Long): String = when {
    millis < 60_000L -> "just now"
    millis < 3_600_000L -> "${millis / 60_000L} min ago"
    millis < 86_400_000L -> "${millis / 3_600_000L} h ago"
    else -> "${millis / 86_400_000L} days ago"
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(top = Space.sm),
    )
}

@Composable
private fun FactRow(fact: Fact) {
    val dot = when (fact.good) {
        true -> MaterialTheme.colorScheme.primary
        false -> MaterialTheme.colorScheme.error
        null -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(Modifier.fillMaxWidth().padding(vertical = Space.sm)) {
        Box(
            Modifier.padding(top = 6.dp).size(10.dp).clip(CircleShape).background(dot),
        )
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(
                fact.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                fact.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
