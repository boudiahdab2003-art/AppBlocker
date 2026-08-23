package com.appblocker.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One day's writing. The day *is* the identity — [day] is a [todayStamp]-style stamp and the
 * primary key, so there is exactly one entry per calendar day and reopening a date always finds
 * what was written on it. That is the whole request: "bound to the date of each day".
 *
 * In Room rather than SharedPreferences, unlike everything else in this package's per-day stores.
 * Three reasons, in order: these are long, they are permanent (a mood note is pruned after 35 days
 * — losing a diary entry that way would be unforgivable), and a `Flow` from the DAO is what keeps
 * the list live while an entry is being written.
 *
 * **Nothing in here ever leaves the phone.** Not in a bug report, not in the profile report, not
 * in an AI Coach prompt. It is the most private text the app will ever hold.
 */
@Entity(tableName = "journal_entries")
data class JournalEntry(
    @PrimaryKey val day: Int,
    val text: String,
    val createdAt: Long,
    val updatedAt: Long,
)
