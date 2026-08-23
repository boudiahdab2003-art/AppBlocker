#!/usr/bin/env bash
# AppBlocker — remote-device session driver (Samsung Remote Test Lab, or any adb device).
#
# A lab session is a paid clock on a phone that is wiped afterwards, so nothing here is
# improvised live: every step is a subcommand, rehearsed against the emulator first.
# Read docs/REMOTE_TEST_LAB.md before using it — that file is the plan, this one is the hands.
#
#   S=<serial> tools/rtl.sh <command>
#
#   devices   what adb can see
#   facts     model, Android, One UI, screen, font scale — and the ROTATION, which has already
#             invented five rendering failures by being sideways
#   setup     install the lab APKs, grant, switch the watcher on, keep the screen awake
#   alive     is the watcher AWAKE, not merely "Enabled"? (the 60-second stamp test)
#   rebind    when it is switched on and deaf
#   seed      arm real blocking: Instagram blocked, the word "example" blocked
#   browsers  which browsers are here, which ones we have actually READ, what the watcher saw
#   go        open a URL in one browser:  tools/rtl.sh go com.android.chrome https://example.com
#   prove     the whole "can we read this browser?" measurement, in the one order that works
#   ids       the view ids of whatever is on screen — what to run the moment prove says NOT READ
#   probe     DeviceProbeTest — the phone answers the guesses in GuardPackages/DeviceVendor
#   suite     the whole androidTest suite
#   who       what is really in front
#   watch     live log
#   blocklog  the app's own record of the covers it raised — the honest instrument for a block
#   shot      screenshot
set -u
export MSYS2_ARG_CONV_EXCL='*'   # Git Bash would rewrite /sdcard and com.appblocker/... otherwise

ROOT="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
ADB="$ROOT/_tools/platform-tools/adb.exe"
LAB="$ROOT/build/lab"
PKG="com.appblocker"
SVC="com.appblocker/com.appblocker.service.BlockerAccessibilityService"
RUNNER="com.appblocker.test/androidx.test.runner.AndroidJUnitRunner"
S="${S:-}"
export S   # `prove` re-enters this script; the serial has to survive the hop
a() { "$ADB" ${S:+-s "$S"} "$@"; }
# Local file paths must reach adb.exe as WINDOWS paths; device paths must not be touched.
win() { cygpath -w "$1"; }
prefs() { a shell run-as $PKG cat shared_prefs/appblocker_prefs.xml 2>/dev/null | tr -d '\015'; }

case "${1:-help}" in

devices)
  "$ADB" devices -l
  ;;

facts)
  echo "manufacturer : $(a shell getprop ro.product.manufacturer | tr -d '\015')"
  echo "model        : $(a shell getprop ro.product.model | tr -d '\015')"
  echo "android      : $(a shell getprop ro.build.version.release | tr -d '\015') (SDK $(a shell getprop ro.build.version.sdk | tr -d '\015'))"
  echo "fingerprint  : $(a shell getprop ro.build.fingerprint | tr -d '\015')"
  echo "one ui       : $(a shell getprop ro.build.version.oneui | tr -d '\015')"
  echo "screen       : $(a shell wm size | tr -d '\015') / $(a shell wm density | tr -d '\015')"
  echo "font scale   : $(a shell settings get system font_scale | tr -d '\015')"
  # A phone lying on its side is a different app: 832dp of width and 384dp of height. Five
  # "rendering failures" on 22 Aug 2026 were this and nothing else.
  echo "rotation     : $(a shell dumpsys window 2>/dev/null | grep -m1 -o 'mRotation=[A-Z_0-9]*' | tr -d '\015')"
  echo "wakefulness  : $(a shell dumpsys power 2>/dev/null | grep -m1 -o 'mWakefulness=[A-Za-z]*' | tr -d '\015')"
  ;;

setup)
  APK="$LAB/appblocker-lab-verbose.apk"
  test -f "$APK" || APK="$LAB/appblocker-lab.apk"
  test -f "$APK" || { echo "no lab APK — run: tools/lab_apk.sh --verbose --with-tests" >&2; exit 1; }
  echo "== installing $(basename "$APK") =="
  # Samsung REFUSES any build declaring a DeviceAdminReceiver, over adb too. lab_apk.sh is the
  # only supported way to produce a build it will accept.
  a install -r -g "$(win "$APK")" || exit 1
  if [ -f "$LAB/appblocker-lab-androidTest.apk" ]; then
    echo "== installing instrumentation =="
    a install -r "$(win "$LAB/appblocker-lab-androidTest.apk")" || exit 1
  fi
  echo "== appops =="
  a shell appops set $PKG SYSTEM_ALERT_WINDOW allow
  a shell appops set $PKG GET_USAGE_STATS allow
  # A dozing phone has nothing in the foreground, so every block test silently measures nothing.
  echo "== keeping the screen awake =="
  a shell settings put system screen_off_timeout 1800000
  a shell input keyevent KEYCODE_WAKEUP
  echo "== switching the watcher on =="
  a shell settings put secure enabled_accessibility_services "$SVC"
  a shell settings put secure accessibility_enabled 1
  sleep 2
  # Android does not BIND the service until something starts the process. Skip this and the
  # device sits Enabled-but-unbound, which looks identical to a broken build.
  echo "== launching the app once, so the service actually binds =="
  a shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 5
  echo "== Settings claims =="
  a shell settings get secure enabled_accessibility_services | tr -d '\015'
  echo "== the framework has bound =="
  a shell dumpsys accessibility 2>/dev/null | grep -m1 -o 'Bound services:{[^,}]*' | cut -c1-70
  a shell input keyevent KEYCODE_HOME
  echo "== now run: seed, then alive =="
  ;;

alive)
  # "Is the watcher awake?" needs an app the watcher actually SCANS. A swipe on the launcher
  # produces no line at all, by design (launchers are never scanned), which reads as death.
  # A browser is always scanned on every build, so it is the honest probe.
  TARGET="${TARGET:-com.android.chrome}"
  a logcat -c
  a shell monkey -p "$TARGET" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 5
  echo "== the framework's view (can be wrong: bound and still deaf) =="
  a shell dumpsys accessibility 2>/dev/null | grep -m1 -o 'Bound services:{[^,}]*' | cut -c1-70
  echo "== the service's own voice =="
  a logcat -d -s AppBlocker:D 2>/dev/null | tail -8
  echo "== the verdict =="
  # ServiceHealth stamps health_last_event_at on real events, throttled to one write a MINUTE.
  # So its AGE is an instant answer needing no before/after read: a living service can never
  # have a stamp older than ~60s, and a dead one's stamp is frozen where it died.
  NOW=$(a shell date +%s | tr -d '\015')
  STAMP=$(prefs | sed -n 's/.*health_last_event_at" value="\([0-9]*\)".*/\1/p' | head -1)
  if [ -z "$STAMP" ]; then
    echo "  NO STAMP — the app has never run here. Open it once, then re-check."
  else
    AGE=$(( NOW - STAMP / 1000 ))
    if [ "$AGE" -lt 90 ]; then
      echo "  AWAKE — last event ${AGE}s ago"
    else
      echo "  STALE — last event ${AGE}s ago: switched on and DEAF. Run: rebind"
    fi
  fi
  a shell input keyevent KEYCODE_HOME
  ;;

rebind)
  a shell settings put secure accessibility_enabled 0
  a shell settings delete secure enabled_accessibility_services
  a shell am force-stop $PKG
  sleep 2
  a shell settings put secure enabled_accessibility_services "$SVC"
  a shell settings put secure accessibility_enabled 1
  sleep 2
  a shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 4
  a shell dumpsys accessibility 2>/dev/null | grep -m1 -o 'Bound services:{[^,}]*' | cut -c1-70
  a shell input keyevent KEYCODE_HOME
  ;;

seed)
  # Arming blocking on a fresh device, and the three traps that make a block test lie:
  #
  #  1. THE AFTER-UPDATE PAUSE. A re-install sets update_paused=true and ALL blocking stops
  #     until someone taps "Reactivate blocking". A fresh install does not (UpdatePause arms
  #     only when lastSeenVersionCode != -1) and `pm clear` restores exactly that state.
  #  2. pm clear PRUNES our component out of enabled_accessibility_services, a moment AFTER
  #     the clear returns — so the service is switched on at the END here, never at the start.
  #  3. `am force-stop` DOES NOT KEEP THE PROCESS DOWN. The framework rebinds an enabled
  #     accessibility service within seconds, and the rebound process opens the database
  #     BEFORE the seed is written over it. Room then serves the file it opened while the new
  #     one sits on disk: the rows are visibly there and the watcher has none of them. So the
  #     service is DISABLED first, and the file is replaced only once the process is really gone.
  TMP="$LAB/seed"; rm -rf "$TMP"; mkdir -p "$TMP"
  echo "== fresh-install state (clears the after-update pause) =="
  a shell settings put secure accessibility_enabled 0
  a shell settings delete secure enabled_accessibility_services
  a shell pm clear $PKG >/dev/null
  sleep 3
  # THE SERVICE IS WHAT CREATES THE DATABASE, not the app's UI. A fresh install opens on the
  # onboarding wizard, which touches no rules at all: launching the app and waiting leaves
  # databases/ empty, and the pull below then quietly captures `cat`'s error message instead of
  # a database. So the watcher is switched on here, and the file exists because it ran.
  echo "== switching the watcher on, so Room creates the database =="
  a shell settings put secure enabled_accessibility_services "$SVC"
  a shell settings put secure accessibility_enabled 1
  sleep 3
  a shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  # Polled, not slept: a fixed wait made this a coin flip on a busy machine, and the failure
  # arrives several steps later disguised as "that is not a database".
  for i in $(seq 1 20); do
    HAVE=$(a shell run-as $PKG ls databases/ 2>/dev/null | tr -d '\015' | grep -c '^appblocker.db$')
    if [ "$HAVE" = "1" ]; then echo "  database created after ${i}s"; break; fi
    sleep 1
  done
  test "$HAVE" = "1" || { echo "  the watcher never created a database — is it really bound?" >&2; exit 1; }
  echo "== switching it back off, so the rows can be written under it =="
  a shell settings put secure accessibility_enabled 0
  a shell settings delete secure enabled_accessibility_services
  echo "== waiting for the process to actually be gone =="
  a shell am force-stop $PKG
  for i in 1 2 3 4 5 6 7 8 9 10; do
    PID=$(a shell pidof $PKG | tr -d '\015')
    if [ -z "$PID" ]; then echo "  process gone"; break; fi
    sleep 1
    a shell am force-stop $PKG
  done
  # THE DEVICE'S OWN DATABASE is what gets patched — never a checked-in seed file. Room refuses
  # a database whose identity hash disagrees with the APK it was compiled against, so a stored
  # seed goes stale the first time an entity changes: silently, on a rented phone, mid-session.
  # exec-out because plain `adb shell` translates newlines and would corrupt the file in transit.
  echo "== pulling the database the app just created =="
  a exec-out run-as $PKG cat databases/appblocker.db > "$TMP/seed.db" 2>/dev/null
  # The schema of a just-created Room database lives almost entirely in its write-ahead log —
  # the .db itself is one empty page. Pulling both, named so sqlite recognises the pair, is what
  # lets the patch below see any tables at all.
  a exec-out run-as $PKG cat databases/appblocker.db-wal > "$TMP/seed.db-wal" 2>/dev/null
  # `cat` writes its own error message to stdout on some shells, so a non-empty file is not
  # proof of a database. The header is.
  head -c 15 "$TMP/seed.db" | grep -q "SQLite format 3" || {
    echo "  that is not a database:" >&2; head -c 120 "$TMP/seed.db" >&2; echo >&2; exit 1; }
  shift
  # Windows paths for python: `/tmp` in Git Bash and `/tmp` to a native python are two different
  # directories, and the mismatch reads as "the file I just wrote does not exist".
  python3 "$(win "$ROOT/tools/lab_seed.py")" "$(win "$TMP/seed.db")" "$@" || exit 1
  echo "== writing it back =="
  a push "$(win "$TMP/seed.db")" /sdcard/seed.db >/dev/null
  # run-as cannot read /sdcard (scoped storage), but the SHELL user can — so the shell opens
  # the file and run-as only receives the bytes on stdin.
  a shell "cat /sdcard/seed.db | run-as $PKG sh -c 'cat > databases/appblocker.db'"
  a shell run-as $PKG rm -f databases/appblocker.db-wal databases/appblocker.db-shm
  a shell rm -f /sdcard/seed.db
  rm -rf "$TMP"
  echo "== re-arming permissions and the watcher (AFTER the file is in place) =="
  a shell appops set $PKG SYSTEM_ALERT_WINDOW allow
  a shell appops set $PKG GET_USAGE_STATS allow
  a shell settings put system screen_off_timeout 1800000
  a shell input keyevent KEYCODE_WAKEUP
  a shell settings put secure enabled_accessibility_services "$SVC"
  a shell settings put secure accessibility_enabled 1
  sleep 2
  a shell monkey -p $PKG -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 6
  a shell input keyevent KEYCODE_HOME
  echo "== armed? =="
  a shell dumpsys accessibility 2>/dev/null | grep -m1 -o 'Bound services:{[^,}]*' | cut -c1-60
  prefs | grep -o 'name="update_paused" value="[a-z]*"' || echo "  update_paused: not set — good"
  ;;

browsers)
  # THE ANSWER TO "can we read this browser?", in one place.
  #
  # `readable_browsers` is written by SettingsStore.addReadableBrowser the first time a browser's
  # address bar is genuinely read — and only ever from a bar found BY VIEW ID, never from page
  # text. So a browser appearing here is the app's own testimony that website blocking works in
  # it; a browser still missing after real browsing is the silent failure this whole exercise is
  # about. KNOWN_READABLE_BROWSERS is the *claim*; this list is the *evidence*.
  echo "== browsers installed here =="
  a shell pm list packages 2>/dev/null | tr -d '\015' | sed 's/^package://' \
    | grep -E 'chrome|sbrowser|firefox|brave|opera|edge|duckduckgo|browser|vivaldi|kiwi' | sort | sed 's/^/  /'
  echo "== address bars actually READ on this phone =="
  prefs | sed -n '/readable_browsers/,/<\/set>/p' | sed -n 's/.*<string>\(.*\)<\/string>.*/  \1/p'
  echo "== what the watcher last saw =="
  prefs | grep -o 'name="diag_[a-z_]*" value="[^"]*"' | sed 's/^/  /'
  ;;

go)
  # Open a URL in ONE named browser. -p pins the package, so no chooser appears and the
  # measurement is about the browser we meant.
  BPKG="${2:?usage: go <browser-package> <url>}"
  URL="${3:?usage: go <browser-package> <url>}"
  a logcat -c
  a shell am start -a android.intent.action.VIEW -d "$URL" -p "$BPKG" >/dev/null 2>&1
  sleep 7
  echo "== the watcher's own words =="
  a logcat -d -s AppBlocker:D 2>/dev/null | grep -E 'urlScan|URL BLOCK|BLOCK:|scan\[' | tail -8
  ;;

prove)
  # "Can this browser's address bar be read here?" — the whole measurement, in the one order
  # that works, because the order is not obvious and getting it wrong reads as a failure:
  #
  #   A BLOCKED WORD LOCKS THE WHOLE BROWSER. `scanBrowserUrl` calls addKeywordLockout for any
  #   non-site hit, so after one word block every later visit is covered by the lockout fast
  #   path — which returns long before anything reads an address bar. Measured on the emulator
  #   23 Aug 2026: visit example.com first and the browser looks unreadable for the rest of the
  #   session, on a phone where reading works perfectly.
  #
  #   AND THE FAST PATH DOES NOT RECORD ANYTHING. addReadableBrowser is called from the FULL
  #   scan (rememberedBrowserAddress), and the full scan returns early while a cover is up. So
  #   the evidence is only ever written by browsing that DOESN'T block.
  #
  # Hence: an unblocked site first, and only then a blocked one.
  BPKG="${2:?usage: prove <browser-package>}"
  NEUTRAL="${NEUTRAL:-https://wikipedia.org}"
  BLOCKED="${BLOCKED:-https://instagram.com}"
  echo "############ 1. an unblocked site — this is what writes the evidence ############"
  "$0" go "$BPKG" "$NEUTRAL"
  echo
  echo "############ 2. the verdict ############"
  READ=$(prefs | sed -n '/readable_browsers/,/<\/set>/p' | sed -n 's/.*<string>\(.*\)<\/string>.*/\1/p')
  echo "$READ" | sed 's/^/  read: /'
  if echo "$READ" | grep -qx "$BPKG"; then
    echo "  PROVEN — $BPKG's address bar was read on this phone."
  else
    echo "  NOT READ — $BPKG did not report an address. Website blocking is SILENTLY OFF here."
    echo "  Next: dump the toolbar's real view id and compare it with OMNIBOX_ID_SUFFIXES."
  fi
  echo
  echo "############ 3. a blocked site — address-only, so a cover proves the read ############"
  "$0" go "$BPKG" "$BLOCKED"
  echo
  "$0" blocklog | tail -4
  ;;

ids)
  # The real view ids of what is on screen, straight from the platform's own dumper. This is the
  # answer when `prove` says NOT READ: OMNIBOX_ID_SUFFIXES claims a browser spells its address bar
  # `:id/url_bar` (Chromium) or `:id/location_bar_edit_text` (Samsung), and the only way to learn
  # that a phone disagrees is to read what it actually calls it.
  a shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  a shell cat /sdcard/ui.xml 2>/dev/null | tr -d '' | grep -o 'resource-id="[^"]*"' | sort -u | sed 's/^/  /'
  a shell rm -f /sdcard/ui.xml
  ;;

probe)
  a logcat -c
  a shell am instrument -w -e class com.appblocker.DeviceProbeTest "$RUNNER" 2>&1 | tail -25
  echo "== the profile block =="
  a logcat -d -s DeviceProbe:I 2>/dev/null | tail -45
  ;;

suite)
  a shell am instrument -w "$RUNNER" 2>&1 | tail -30
  ;;

who)
  a shell dumpsys window 2>/dev/null | grep -iE 'mCurrentFocus|mFocusedApp' | head -3
  a shell dumpsys activity activities 2>/dev/null | grep -iE 'topResumedActivity' | head -2
  ;;

watch)
  a logcat -c
  echo "watching — ctrl-c to stop"
  a logcat -v time AppBlocker:D DeviceProbe:I AndroidRuntime:E '*:S'
  ;;

blocklog)
  # The app's own record of the covers it raised: time|kind|ownUi|rootOk|why|counted.
  # A better instrument than a screenshot for an APP block, which writes no logcat line at all —
  # and it is the same format the owner's bug reports arrive in (data/BlockLog.kt).
  # NEVER use mCurrentFocus to decide whether a cover is up: the cover is FLAG_NOT_FOCUSABLE, so
  # the app underneath keeps focus and a blocked screen reads as unblocked.
  echo "when      kind  ownUi  rootOk  why     counted"
  prefs | sed -n 's/.*name="block_log">\([^<]*\).*/\1/p' | tr ';' '\n' \
    | awk -F'|' 'NF>=6 { printf "%s  %-5s %-6s %-7s %-7s %s\n", strftime("%H:%M:%S", $1/1000), $2, $3, $4, $5, $6 }'
  ;;

shot)
  OUT="${2:-$LAB/shot.png}"
  a exec-out screencap -p > "$OUT"
  echo "saved $OUT"
  ;;

*)
  sed -n 's/^#   \([a-z]*\) \{2,\}\(.*\)/  \1 — \2/p' "$0"
  ;;
esac
