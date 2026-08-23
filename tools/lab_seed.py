#!/usr/bin/env python3
"""Write test rules straight into a copy of the app's own database.

WHY NOT `sqlite3` ON THE PHONE: retail phones ship no sqlite3 binary, so the rules cannot be
INSERTed on the device. WHY NOT A CHECKED-IN SEED FILE: Room refuses to open a database whose
identity hash disagrees with the schema the APK was compiled against, so a seed file built once
goes stale the first time an entity changes — silently, on a rented phone, mid-session.

So the database being patched is *the device's own*, created by the app itself moments earlier:
pull it, add rows, push it back. The schema is right by construction, for every future version.

    python3 tools/lab_seed.py pulled.db                      # Instagram blocked + the word "example"
    python3 tools/lab_seed.py pulled.db --block com.x:Label --keyword word

The two defaults are chosen to test the two website paths, which fail differently:
  * the word "example" — the user's own keywords, matched against page text as well as the address
  * Instagram blocked  — SOCIAL_DOMAINS, matched against **the address only**, so a cover on
    instagram.com cannot have come from anywhere but a successfully read address bar
"""
import argparse
import os
import sqlite3
import sys

ap = argparse.ArgumentParser()
ap.add_argument("db")
ap.add_argument("--block", action="append", default=[], help="package:Label to block, repeatable")
ap.add_argument("--keyword", action="append", default=[], help="blocked word, repeatable")
args = ap.parse_args()

blocked = args.block or ["com.instagram.android:Instagram"]
words = args.keyword or ["example"]

if not os.path.exists(args.db):
    sys.exit("no such database: %s" % args.db)

con = sqlite3.connect(args.db)
# Autocommit: a PRAGMA that has to checkpoint cannot run inside python's implicit transaction.
con.isolation_level = None
# A pulled database usually arrives with its write-ahead log alongside it. Fold that in first, or
# the rows below land in a -wal that gets deleted on the way back to the phone.
con.execute("PRAGMA journal_mode=DELETE")

# Replaced rather than added to: a leftover rule from an earlier run is exactly the kind of thing
# that makes a session's result impossible to interpret. NOTE the browser must never be among
# these — a blanket-blocked browser is covered before any scan runs, so it can never demonstrate
# that its address bar is readable, which is the whole point of the exercise.
con.execute("DELETE FROM app_rules")
for spec in blocked:
    pkg, _, label = spec.partition(":")
    con.execute(
        "INSERT INTO app_rules (packageName, appLabel, isBlocked, isAllowed, mode,"
        " scheduleStartMinutes, scheduleEndMinutes, dailyLimitMinutes)"
        " VALUES (?, ?, 1, 0, 'HARD', -1, -1, -1)",
        (pkg, label or pkg),
    )

con.execute("DELETE FROM blocked_keywords")
for w in words:
    con.execute("INSERT INTO blocked_keywords (keyword) VALUES (?)", (w,))

con.commit()
print("blocked apps:", con.execute("SELECT packageName FROM app_rules").fetchall())
print("blocked words:", con.execute("SELECT keyword FROM blocked_keywords").fetchall())
con.close()
