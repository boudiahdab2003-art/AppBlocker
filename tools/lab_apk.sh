#!/usr/bin/env bash
# AppBlocker — build a lab APK: the shipping app minus its device-admin receiver.
#
# WHY THIS EXISTS. Samsung refuses to install any app that declares a DeviceAdminReceiver —
# "This admin app installation is not allowed" — and it refuses over ADB too, not only through
# their installer UI. AppBlocker declares one for Prevent-uninstall, so getting the app onto
# Samsung Remote Test Lab hardware (docs/REMOTE_TEST_LAB.md) needs a build with that <receiver>
# gone. It was done by hand the first time, which is exactly the kind of step that goes wrong on
# a rented clock.
#
# THE MANIFEST IS EDITED IN PLACE AND RESTORED BY A TRAP. An interrupted run — Ctrl-C, a failed
# build, a closed terminal — still puts the file back. `git status` must be clean afterwards, and
# the script says so itself before it exits.
#
#   tools/lab_apk.sh                  stripped debug build
#   tools/lab_apk.sh --verbose        ...and DEBUG=true in the watcher: logs every scan, every
#                                     URL read, every block, with millisecond timestamps
#   tools/lab_apk.sh --with-tests     ...and the androidTest APK, for DeviceProbeTest on the phone
#
# Output lands in build/lab/ (gitignored). Prevent-uninstall itself cannot be tested from these
# builds by construction — the feature is the receiver.
set -euo pipefail

ROOT="$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
cd "$ROOT"

VERBOSE=0
WITH_TESTS=0
for arg in "$@"; do
  case "$arg" in
    --verbose)    VERBOSE=1 ;;
    --with-tests) WITH_TESTS=1 ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done

MANIFEST="app/src/main/AndroidManifest.xml"
SERVICE="app/src/main/java/com/appblocker/service/BlockerAccessibilityService.kt"
OUT="build/lab"
GRADLE="_tools/gradle/gradle-8.9/bin/gradle.bat"
export JAVA_HOME="${JAVA_HOME:-C:\Program Files\Android\Android Studio\jbr}"

# Newest build-tools wins; aapt is what proves the strip actually happened.
AAPT="$(ls -d /c/Users/smh_7/AppData/Local/Android/Sdk/build-tools/*/ 2>/dev/null | sort -V | tail -1)aapt.exe"

# --- restore first, ask questions later ------------------------------------------------------
restore() {
  local rc=$?
  [ -f "$MANIFEST.labbak" ] && mv -f "$MANIFEST.labbak" "$MANIFEST"
  [ -f "$SERVICE.labbak" ]  && mv -f "$SERVICE.labbak"  "$SERVICE"
  local dirty
  dirty="$(git status --porcelain -- "$MANIFEST" "$SERVICE")"
  if [ -n "$dirty" ]; then
    echo "!! SOURCES NOT RESTORED — check these by hand:" >&2
    echo "$dirty" >&2
    exit 1
  fi
  exit $rc
}
trap restore EXIT INT TERM

# --- strip the receiver ----------------------------------------------------------------------
cp "$MANIFEST" "$MANIFEST.labbak"

# One block, removed as a unit, line endings untouched — see the script's own docstring for why
# a whole-file rewrite here would be worse than the problem it solves.
python3 "$ROOT/tools/strip_admin_receiver.py" "$MANIFEST"

if grep -qi "AdminReceiver" "$MANIFEST"; then
  echo "!! the receiver survived the strip — refusing to build" >&2
  exit 1
fi
echo "== manifest stripped: $(grep -c '<receiver' "$MANIFEST.labbak") receivers -> $(grep -c '<receiver' "$MANIFEST")"

if [ "$VERBOSE" = 1 ]; then
  cp "$SERVICE" "$SERVICE.labbak"
  sed -i 's/private const val DEBUG = false/private const val DEBUG = true/' "$SERVICE"
  grep -q "DEBUG = true" "$SERVICE" || { echo "!! could not flip DEBUG" >&2; exit 1; }
  echo "== watcher DEBUG logging ON (adb logcat -s AppBlocker:D)"
fi

# --- build -----------------------------------------------------------------------------------
TASKS=":app:assembleGithubDebug"
[ "$WITH_TESTS" = 1 ] && TASKS="$TASKS :app:assembleGithubDebugAndroidTest"
echo "== building: $TASKS"
"$GRADLE" -p . $TASKS

mkdir -p "$OUT"
SUFFIX=$([ "$VERBOSE" = 1 ] && echo "-verbose" || echo "")
cp app/build/outputs/apk/github/debug/app-github-debug.apk "$OUT/appblocker-lab$SUFFIX.apk"
[ "$WITH_TESTS" = 1 ] && cp app/build/outputs/apk/androidTest/github/debug/app-github-debug-androidTest.apk "$OUT/appblocker-lab-androidTest.apk"

# --- prove it ----------------------------------------------------------------------------------
# The manifest we edited is not the manifest that ships — the merger pulls in library manifests
# too. Only the built APK can answer whether a device-admin declaration survived.
ADMIN=$("$AAPT" dump xmltree "$(cygpath -w "$OUT/appblocker-lab$SUFFIX.apk")" AndroidManifest.xml | grep -ci "admin" || true)
echo "== built $OUT/appblocker-lab$SUFFIX.apk"
echo "== device-admin references in the built APK: $ADMIN"
if [ "$ADMIN" != "0" ]; then
  echo "!! Samsung will refuse this build. Do not take it to the lab." >&2
  exit 1
fi
echo "== OK: Samsung will accept this build."
