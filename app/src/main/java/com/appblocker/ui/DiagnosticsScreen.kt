package com.appblocker.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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
import com.appblocker.Dist
import com.appblocker.data.DeviceProfile
import com.appblocker.data.DeviceVendor
import com.appblocker.data.PhoneFacts
import com.appblocker.data.QuickSession
import com.appblocker.data.ServiceHealth
import com.appblocker.data.SettingsStore
import com.appblocker.data.SilenceLog
import com.appblocker.data.UninstallGuardVerdict
import com.appblocker.data.WatcherDiagnostics
import com.appblocker.data.uninstallGuardVerdict
import com.appblocker.service.AccessibilityUtil
import com.appblocker.service.BlockerAccessibilityService
import com.appblocker.service.GuardPackages
import com.appblocker.service.KNOWN_READABLE_BROWSERS
import com.appblocker.service.findBrowserPackages
import com.appblocker.service.findRealBrowserPackages
import com.appblocker.ui.theme.AppCard
import com.appblocker.ui.theme.Space
import com.appblocker.ui.theme.appBackground
import com.appblocker.ui.theme.pageWidth

/** Test tags for the rendering test. */
/**
 * The scrolling list itself. A `LazyColumn` does not compose what is off screen, so a test must
 * scroll *the list* to reach a card — `performScrollTo` on a card that was never composed fails
 * with "could not find any node", which is what happened the first time this page was measured on
 * a phone shorter than the release-gate emulator.
 */
const val DIAGNOSTICS_LIST_TAG = "diagnostics_list"
const val DIAGNOSTICS_BROWSERS_TAG = "diagnostics_browsers"
const val DIAGNOSTICS_LAST_LOOK_TAG = "diagnostics_last_look"
const val DIAGNOSTICS_PHONE_TAG = "diagnostics_phone"

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
            Modifier.fillMaxHeight().pageWidth().padding(horizontal = 20.dp)
                .testTag(DIAGNOSTICS_LIST_TAG),
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

            // Above the browsers on purpose: this is the section a stranger on an unfamiliar
            // phone is being asked to screenshot, and it should not be below a scroll.
            item { SectionHeading("This phone") }
            item {
                AppCard(modifier = Modifier.testTag(DIAGNOSTICS_PHONE_TAG)) {
                    for (line in snapshot.phone) FactRow(line)
                }
            }

            item { SectionHeading("When the blocker went quiet") }
            item {
                AppCard {
                    Text(
                        "Everything else on this page is about blocks that happened. This is " +
                            "the opposite: the moments it decided not to block. Zeroes here are " +
                            "the good answer, and they are worth as much as the numbers.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = Space.sm),
                    )
                    for (line in snapshot.silence) FactRow(line)
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
                            FactRow(
                                Fact(
                                    appLabel(context, b),
                                    when {
                                        b !in snapshot.realBrowsers ->
                                            "$b — not really a browser, just an app that opens " +
                                                "web links. It's read for blocked words, and it " +
                                                "is never blocked as an unsupported browser."
                                        // Confirmed BEFORE assumed, so a browser that is both
                                        // reads as the stronger of the two.
                                        b in snapshot.confirmedReadable ->
                                            "$b — its address bar HAS been read on this phone, " +
                                                "so blocked sites are caught here."
                                        // The distinction the Mi Browser hole hid behind: this
                                        // browser is exempt from the unsupported-browser block on
                                        // the strength of an assumption nothing has tested.
                                        b in snapshot.assumedReadable ->
                                            "$b — assumed readable, but its address bar has " +
                                                "never actually been read on this phone. If " +
                                                "blocked sites open here, this line is why. Visit " +
                                                "a site in it and tap Refresh."
                                        else ->
                                            "$b — its address bar hasn't been read yet. Blocked " +
                                                "words still work; blocked sites can't be caught " +
                                                "until it has."
                                    },
                                    good = when {
                                        b !in snapshot.realBrowsers -> null
                                        b in snapshot.confirmedReadable -> true
                                        // Not a fault and not health either: unproven.
                                        b in snapshot.assumedReadable -> null
                                        else -> false
                                    },
                                ),
                            )
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
    /** What this phone turned out to be, versus what the app assumed — see [phoneFacts]. */
    val phone: List<Fact>,
    val browsers: List<String>,
    /** Those whose address bar the app knows how to read, or has read. The rest can still
     *  catch blocked words from the page — it is site blocking that needs the address. */
    /**
     * Browsers whose address bar this phone has **actually read** — evidence, not a claim.
     *
     * Split from [assumedReadable] because merging them is what let the Mi Browser hole hide: the
     * screen showed "its address bar can be read" for a browser nobody had ever read, purely
     * because it was on the seed list. Same shape as the `rootOk` flag fixed a round earlier —
     * one value covering a fact and an assumption, with the optimistic reading winning.
     */
    val confirmedReadable: Set<String>,
    /** Seeded as readable ([KNOWN_READABLE_BROWSERS]) and never confirmed on this phone. */
    val assumedReadable: Set<String>,
    /** Those that are really browsers rather than apps that open their own links. Only these
     *  can ever be blocked outright by the "block unsupported browsers" switch. */
    val realBrowsers: Set<String>,
    val lastLook: List<Fact>,
    /** What the blocker declined to do — see [SilenceLog]. The only card here that is about
     *  absence, and the only one whose zero is worth reading. */
    val silence: List<Fact>,
)

/**
 * Everything the screen shows, gathered in one place so the page itself stays a rendering of it.
 *
 * `good` is `null` where a setting is a genuine choice rather than a fault — the point is to
 * explain, not to nag about a configuration the owner picked deliberately.
 */
private fun readSnapshot(context: Context): Snapshot {
    val now = System.currentTimeMillis()
    val updatePaused = SettingsStore.updatePaused(context)
    val quickPaused = SettingsStore.quickBlockPaused(context)
    val session = QuickSession.state(context)
    val allowlist = SettingsStore.quickBlockAllowlist(context)
    val pack = SettingsStore.adultWordsPack(context)
    val adultSites = SettingsStore.blockAdult(context)
    val unsupported = SettingsStore.blockUnsupportedBrowsers(context)

    val deaf = SilenceLog.get(context, SilenceLog.DEAF_DISMISSALS)
    val declines = SilenceLog.get(context, SilenceLog.LATE_DECLINES)
    val unready = SilenceLog.get(context, SilenceLog.UNREADY_DECISIONS)
    val silence = buildList {
        add(
            Fact(
                "Times it went quiet after \"Got it\": ${deaf.today} today, ${deaf.total} in total",
                if (deaf.total == 0) {
                    "None. After a block was dismissed, it has always started watching again " +
                        "as soon as you moved somewhere new."
                } else {
                    "Each one is a spell where a block screen was dismissed and the blocker " +
                        "stayed quiet while you were still in that app. Some of that is normal " +
                        "while your phone is going Home. If this number keeps climbing, tell " +
                        "me — that is the shape of a block that should have come and didn't."
                },
                good = if (deaf.total == 0) true else null,
            ),
        )
        if (declines.total > 0) {
            add(
                Fact(
                    "Checks skipped in those spells: ${declines.today} today, ${declines.total} in total",
                    "How many times it looked away during the spells above. A big number " +
                        "against a small number of spells means one page you stayed on a while.",
                    good = null,
                ),
            )
        }
        add(
            Fact(
                "Decisions made before the block list loaded: ${unready.total}",
                if (unready.total == 0) {
                    "None. Every time the blocker restarted, it knew what to block before " +
                        "anything asked it."
                } else {
                    "Each restart — switching spaces is the usual cause — has a moment before " +
                        "the app's own list has loaded. It now remembers what was blocked and " +
                        "enforces that straight away, so this is a count of moments survived, " +
                        "not moments lost."
                },
                good = if (unready.total == 0) true else null,
            ),
        )
    }

    val protection = buildList {
        // FIRST, because it outranks everything below it: a watcher that isn't running makes
        // every other line on this card irrelevant. It is also the one fact the app was blind to
        // until now — Android's toggle says "on" over a watcher the phone killed, which is what
        // a Second Space switch leaves behind.
        val running = BlockerAccessibilityService.isConnected()
        val enabled = AccessibilityUtil.isEnabled(context)
        add(
            when {
                !enabled -> Fact(
                    "The blocker is switched off",
                    "Accessibility is off for AppBlocker, so nothing is being blocked.",
                    good = false,
                )
                running -> Fact(
                    "The blocker is running right now",
                    "It's switched on AND actually watching. Last thing it saw: " +
                        sinceLabel(now, ServiceHealth.lastEventAt(context)) +
                        ". Last heartbeat: " +
                        sinceLabel(now, ServiceHealth.lastAliveAt(context)) + ".",
                    good = true,
                )
                else -> Fact(
                    "Switched on, but NOT running",
                    "Android says it's on, but the watcher isn't there — your phone shut it " +
                        "down. Nothing is being blocked. Turning accessibility off and on again " +
                        "brings it back.",
                    good = false,
                )
            },
        )
        ServiceHealth.foundDeadCount(context).takeIf { it > 0 }?.let { dead ->
            add(
                Fact(
                    "Times blocking has been found stopped: $dead",
                    "Each time, your phone had shut the blocker down while Android still said " +
                        "it was on. If this number keeps climbing, tell me — it's the " +
                        "measurement that says whether a fix worked.",
                    good = null,
                ),
            )
        }
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
        phone = phoneFacts(context),
        browsers = runCatching { findBrowserPackages(context).sorted() }.getOrDefault(emptyList()),
        confirmedReadable = SettingsStore.readableBrowsers(context),
        // Only the ones still unproven — a seeded browser that has since been read belongs in
        // `confirmedReadable` alone, or the screen would hedge about something it has measured.
        assumedReadable = KNOWN_READABLE_BROWSERS - SettingsStore.readableBrowsers(context),
        realBrowsers = runCatching { findRealBrowserPackages(context) }.getOrDefault(emptySet()),
        lastLook = lastLook,
        silence = silence,
    )
}

/**
 * **The section that exists because five phones could not be bought.**
 *
 * Every non-Xiaomi thing this app believes was reasoned from evidence and never measured, and each
 * belief fails silently — a guard that never fires and a button that opens the wrong page both look
 * exactly like a working app. Android will answer three of those questions directly if asked, so
 * this asks, and the answers are readable by anyone who can take a screenshot.
 *
 * Every lookup is wrapped: this is a diagnostic, and a diagnostic that crashes the screen it
 * reports on is worse than one that says "couldn't check". Failures land as [PhoneFacts] nulls,
 * which [uninstallGuardVerdict] is careful to report as *unknown* rather than as fine.
 */
private fun phoneFacts(context: Context): List<Fact> {
    val advice = DeviceVendor.advice()

    // The lookups themselves live in DeviceProfile, because this screen is no longer the only
    // thing that asks: DeviceProbeTest asserts on them and BugReportSender.reportDeviceProfile
    // sends them. Sharing one implementation is what stops a screenshot and an auto-report of the
    // same phone disagreeing — which would be worse than either of them being absent.
    val facts = DeviceProfile.facts(
        context,
        sideloaded = Dist.SELF_UPDATE,
        sdkInt = Build.VERSION.SDK_INT,
    )

    return buildList {
        add(
            if (facts.brand.isNotBlank()) Fact(
                "${facts.brand} phone",
                "Setup shows ${facts.brand}'s own steps for keeping the blocker alive.",
                good = null,
            ) else Fact(
                "Phone brand not recognised",
                "Setup shows general advice instead of steps for this exact phone. Not a fault — " +
                    "blocking works the same either way; only the instructions are less specific.",
                good = null,
            ),
        )
        add(
            when (uninstallGuardVerdict(facts.uninstallHandler, GuardPackages.INSTALLERS)) {
                UninstallGuardVerdict.RECOGNISED -> Fact(
                    "The uninstall screen is recognised",
                    "${facts.uninstallHandler} — Strict Mode can catch an attempt to uninstall " +
                        "AppBlocker while a session is running.",
                    good = true,
                )
                // The one this whole section is for. On a phone whose installer we guessed wrong,
                // this is the only place that failure is ever visible.
                UninstallGuardVerdict.UNRECOGNISED -> Fact(
                    "The uninstall screen is NOT recognised",
                    "${facts.uninstallHandler} shows the \"uninstall this app?\" screen on this " +
                        "phone, and AppBlocker doesn't know that screen — so Strict Mode can't " +
                        "stop an uninstall mid-session. Please report this line; it's a one-word " +
                        "fix once we know the name.",
                    good = false,
                )
                UninstallGuardVerdict.UNKNOWN -> Fact(
                    "Couldn't check the uninstall screen",
                    "This phone didn't say which app shows it. Nothing is necessarily wrong — " +
                        "it just can't be confirmed from here.",
                    good = null,
                )
            },
        )
        when (facts.keepAliveResolves) {
            true -> add(
                Fact(
                    "The \"${advice.keepAliveLabel}\" button has somewhere to go",
                    "It opens this phone's own settings page for keeping apps running.",
                    good = true,
                ),
            )
            false -> add(
                Fact(
                    "The \"${advice.keepAliveLabel}\" button opens the app's settings page instead",
                    "This phone doesn't have the page AppBlocker expected, so the button falls " +
                        "back. Follow the written steps on the Setup screen — they're what " +
                        "matters, and they still apply.",
                    good = false,
                ),
            )
            null -> Unit // generic advice: there is no button destination to check
        }
        if (facts.sideloaded && facts.sdkInt >= Build.VERSION_CODES.TIRAMISU) {
            add(
                Fact(
                    "Android ${facts.sdkInt}, installed outside the Play Store",
                    "On this combination Android greys out the Accessibility switch until you " +
                        "allow restricted settings — Setup explains where.",
                    good = null,
                ),
            )
        }
    }
}

/** The app's own name for a package, falling back to the package name itself. */
private fun appLabel(context: Context, pkg: String): String = runCatching {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
}.getOrDefault(pkg)

/**
 * [agoLabel] for a stored timestamp, where **0 means "never since install"** rather than 1970.
 * Feeding the raw difference in would print "20000 days ago" for a watcher that has simply never
 * had anything to report yet — a scary number for a healthy phone.
 */
private fun sinceLabel(now: Long, at: Long): String =
    if (at <= 0L) "not yet" else agoLabel(now - at)

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
