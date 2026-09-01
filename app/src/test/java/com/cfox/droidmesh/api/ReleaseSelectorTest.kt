package com.cfox.droidmesh.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [PROGRAMMATIC] UPD-TEST-009: pinned-version selection for background auto-install.
 *
 * Regression origin: the auto-install loop passed the App Library entry's raw downloadUrl
 * (e.g. "https://github.com/jxlarrea/kiosk-satellite/releases", an HTML page) straight into
 * ApkDownloader, so it saved the releases *web page* as a .apk and dispatched it to the package
 * installer. It also ignored the entry's pinned targetVersion entirely.
 */
class ReleaseSelectorTest {

    private fun rel(tag: String) = ReleaseInfo(
        tagName = tag,
        name = tag,
        publishedAt = "",
        apkAssetUrl = "https://example.com/app-$tag.apk",
        apkFileName = "app-$tag.apk",
        apkSize = 0L
    )

    // Newest-first, as GitHubReleaseFetcher returns them.
    private val releases = listOf(rel("v2.1.0"), rel("v2.0.0"), rel("v1.5.0"))

    @Test
    fun testLatestSelectsNewestRelease() {
        assertEquals("v2.1.0", ReleaseSelector.selectRelease(releases, "latest")?.tagName)
    }

    @Test
    fun testBlankTargetVersionSelectsNewestRelease() {
        assertEquals("v2.1.0", ReleaseSelector.selectRelease(releases, "")?.tagName)
        assertEquals("v2.1.0", ReleaseSelector.selectRelease(releases, "   ")?.tagName)
    }

    @Test
    fun testExactTagMatchSelectsPinnedRelease() {
        assertEquals("v2.0.0", ReleaseSelector.selectRelease(releases, "v2.0.0")?.tagName)
    }

    // An admin pinning "2.0.0" from a version string, against a repo that tags "v2.0.0".
    @Test
    fun testTagMatchIgnoresVPrefixOnEitherSide() {
        assertEquals("v2.0.0", ReleaseSelector.selectRelease(releases, "2.0.0")?.tagName)
        assertEquals(
            "1.5.0",
            ReleaseSelector.selectRelease(listOf(rel("1.5.0")), "v1.5.0")?.tagName
        )
    }

    // Tag matching is case-insensitive: "V2.0.0" and "v2.0.0" name the same release.
    @Test
    fun testTagMatchIsCaseInsensitive() {
        assertEquals("v2.0.0", ReleaseSelector.selectRelease(releases, "V2.0.0")?.tagName)
        assertEquals(
            "Release-A",
            ReleaseSelector.selectRelease(listOf(rel("Release-A")), "release-a")?.tagName
        )
    }

    // Negative: a pin that matches nothing must NOT silently fall back to newest — that would
    // install a version the admin did not ask for.
    @Test
    fun testUnmatchedPinnedTagReturnsNull() {
        assertNull(ReleaseSelector.selectRelease(releases, "v9.9.9"))
    }

    // Negative: empty release list yields nothing regardless of target.
    @Test
    fun testEmptyReleaseListReturnsNull() {
        assertNull(ReleaseSelector.selectRelease(emptyList(), "latest"))
        assertNull(ReleaseSelector.selectRelease(emptyList(), "v2.0.0"))
    }
}
