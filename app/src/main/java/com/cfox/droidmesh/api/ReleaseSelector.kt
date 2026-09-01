package com.cfox.droidmesh.api

/**
 * Picks which release an App Library entry's pinned `targetVersion` refers to (`UPD-BEHAVE-012`).
 *
 * Tag comparison is prefix-insensitive on `v` so an admin who pins "2.0.0" still matches a repo
 * that tags "v2.0.0" (and vice versa).
 */
object ReleaseSelector {

    /**
     * @param releases newest-first, as returned by [GitHubReleaseFetcher.fetchReleases]
     * @param targetVersion the entry's pinned tag, or `latest`/blank for "newest"
     * @return the matching release, or `null` when the list is empty or a pinned tag matches
     *   nothing. A pin that matches nothing deliberately does NOT fall back to newest — silently
     *   installing a version the admin did not pin is worse than doing nothing.
     */
    fun selectRelease(releases: List<ReleaseInfo>, targetVersion: String?): ReleaseInfo? {
        if (releases.isEmpty()) return null
        val pinned = targetVersion?.trim().orEmpty()
        if (pinned.isEmpty() || pinned.equals("latest", ignoreCase = true)) {
            return releases.first()
        }
        return releases.firstOrNull { normalizeTag(it.tagName) == normalizeTag(pinned) }
    }

    private fun normalizeTag(tag: String): String =
        tag.trim().removePrefix("v").removePrefix("V").lowercase()
}
