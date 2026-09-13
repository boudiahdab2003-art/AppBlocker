package com.appblocker

import com.appblocker.data.changelog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the in-app "What's new" list (Profile ▸ What's new) against the way it actually broke:
 * v1.92 shipped with no entry at all, so the app highlighted no version as current while the
 * owner was running it. CHANGELOG.md is written by the publish workflow; this list is written by
 * hand, and nothing used to notice when a release skipped it.
 *
 * Because the publish workflow runs the unit tests straight after bumping the version, a release
 * whose version has no entry here now stops the publish — before anything is committed, tagged or
 * uploaded.
 */
class ChangelogTest {

    @Test
    fun currentVersionHasAnEntry() {
        val versions = changelog.map { it.version }
        assertTrue(
            "No 'What's new' entry for version ${BuildConfig.VERSION_NAME}. Add a VersionLog " +
                "for it at the top of data/Changelog.kt (newest first) before releasing. " +
                "Entries present: $versions",
            versions.contains(BuildConfig.VERSION_NAME),
        )
    }

    @Test
    fun versionsAreUnique() {
        val versions = changelog.map { it.version }
        val duplicates = versions.groupBy { it }.filterValues { it.size > 1 }.keys
        assertEquals("Duplicate version entries in data/Changelog.kt", emptySet<String>(), duplicates)
    }

    /** Newest first — the screen renders the list in order and marks the installed one. */
    @Test
    fun entriesAreNewestFirst() {
        val order = changelog.map { versionKey(it.version) }
        assertEquals(
            "data/Changelog.kt must be newest-first",
            order.sortedDescending(), order,
        )
    }

    @Test
    fun everyEntryHasContent() {
        val empty = changelog.filter {
            it.title.isBlank() || it.date.isBlank() || it.points.isEmpty() ||
                it.points.any(String::isBlank)
        }.map { it.version }
        assertEquals("Entries missing a title, date or points", emptyList<String>(), empty)
    }

    /**
     * **CHANGELOG.md is written by the publish workflow, and only by it.** Twice a session wrote a
     * `## v1.145` / `## v1.161` section by hand ahead of the release; the workflow then inserted
     * its own, and the file carried two different entries for one version until 13 Sep 2026.
     * A hand-written section for a version not yet released is the shape to catch, so this fails
     * on the push that adds it — long before the publish that would duplicate it.
     */
    @Test
    fun changelogMdHasOneSectionPerReleasedVersion() {
        val file = listOf(java.io.File("../CHANGELOG.md"), java.io.File("CHANGELOG.md"))
            .firstOrNull { it.isFile } ?: return
        val headings = Regex("""^## v(\S+)""", RegexOption.MULTILINE)
            .findAll(file.readText()).map { it.groupValues[1] }.toList()
        val duplicates = headings.groupBy { it }.filterValues { it.size > 1 }.keys
        assertEquals("CHANGELOG.md has more than one section for", emptySet<String>(), duplicates)
        val ahead = headings.filter { versionKey(it) > versionKey(BuildConfig.VERSION_NAME) }
        assertEquals(
            "CHANGELOG.md has a section for a version that has not been released. The publish " +
                "workflow writes these; write the in-app entry in data/Changelog.kt instead.",
            emptyList<String>(), ahead,
        )
    }

    /** "1.9" -> 1_009, "1.92" -> 1_092: comparable across the 1.9 → 1.10 style jump. */
    private fun versionKey(v: String): Int {
        val parts = v.split(".")
        val major = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return major * 1000 + minor
    }
}
