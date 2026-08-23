#!/usr/bin/env python3
"""Remove the device-admin <receiver> from an AndroidManifest, and nothing else.

Samsung refuses to install any app declaring a DeviceAdminReceiver, so lab builds
(docs/REMOTE_TEST_LAB.md) need it gone. See tools/lab_apk.sh, which calls this.

Deliberately narrow: it removes exactly one block, the one whose body names the receiver, plus a
comment sitting directly above it. Everything else in the file — including its line endings — comes
out byte-identical, so `git diff` on a restored manifest is empty and a mistake here is visible
rather than buried in a whole-file rewrite.
"""
import re
import sys

MARKER = "AdminReceiver"

# newline="" keeps CRLF as CRLF: rewriting a whole file's endings would make every later diff
# useless, and this file is edited in place on a working tree.
with open(sys.argv[1], "r", encoding="utf-8", newline="") as f:
    text = f.read()

# A <receiver …>…</receiver> whose body names the admin receiver, with an optional XML comment
# and blank line directly above it. Non-greedy and anchored on </receiver> so it can never eat
# the receivers that follow.
pattern = re.compile(
    r"(?:[ \t]*<!--(?:(?!-->).)*?-->[ \t]*\r?\n)?"
    r"[ \t]*<receiver\b(?:(?!</receiver>).)*?" + MARKER + r"(?:(?!</receiver>).)*?</receiver>[ \t]*\r?\n"
    r"(?:[ \t]*\r?\n)?",
    re.DOTALL,
)

out, n = pattern.subn("", text)
if n != 1:
    sys.exit("expected exactly one %s block, found %d" % (MARKER, n))
if MARKER in out:
    sys.exit("the receiver survived the strip")

with open(sys.argv[1], "w", encoding="utf-8", newline="") as f:
    f.write(out)
print("removed 1 device-admin receiver block")
