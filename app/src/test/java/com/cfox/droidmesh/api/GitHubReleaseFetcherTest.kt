package com.cfox.droidmesh.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ISSUE-91]: a release published on GitHub with no usable `.apk` asset (empty `assets: []`, or
 * an assets list with no `.apk` file) must be skipped when selecting "the latest release" -- but
 * skipping it silently, with zero log trace, is what let a dangling `v0.2.0` release (tagged and
 * published, asset upload never completed) mask a real update for days: self-update fell back to
 * the next release down (`v0.1.0`) and reported "up to date" with no hint anything was wrong.
 *
 * These tests exercise [GitHubReleaseFetcher.parseReleasesJson], the pure JSON-parsing function
 * extracted from `fetchReleases` so this logic is testable without a live network call.
 */
class GitHubReleaseFetcherTest {

    private fun releaseJson(
        tag: String,
        draft: Boolean = false,
        assetsJson: String = "[]"
    ): String = """
        {
          "tag_name": "$tag",
          "name": "$tag",
          "published_at": "2026-09-01T00:00:00Z",
          "draft": $draft,
          "assets": $assetsJson
        }
    """.trimIndent()

    private fun apkAsset(name: String, size: Long = 1234L): String = """
        {"name": "$name", "browser_download_url": "https://example.com/$name", "size": $size}
    """.trimIndent()

    @Test
    fun `release with empty assets array is skipped, not treated as latest`() {
        val body = "[" +
            releaseJson("v0.2.0", assetsJson = "[]") + "," +
            releaseJson("v0.1.0", assetsJson = "[${apkAsset("droid-mesh-v0.1.0.apk")}]") +
            "]"

        val releases = GitHubReleaseFetcher.parseReleasesJson(body, count = 10)

        assertEquals(1, releases.size)
        assertEquals("v0.1.0", releases.first().tagName)
    }

    @Test
    fun `release with assets but no apk file is skipped`() {
        val body = "[" +
            releaseJson(
                "v0.2.0",
                assetsJson = "[${apkAsset("release-notes.txt")}]"
            ) + "," +
            releaseJson("v0.1.0", assetsJson = "[${apkAsset("droid-mesh-v0.1.0.apk")}]") +
            "]"

        val releases = GitHubReleaseFetcher.parseReleasesJson(body, count = 10)

        assertEquals(1, releases.size)
        assertEquals("v0.1.0", releases.first().tagName)
    }

    @Test
    fun `skipping an asset-less release logs a warning naming its tag`() {
        val warnings = mutableListOf<String>()
        val body = "[" + releaseJson("v0.2.0", assetsJson = "[]") + "]"

        GitHubReleaseFetcher.parseReleasesJson(body, count = 10, onSkippedRelease = { warnings.add(it) })

        assertTrue(
            "expected a skip warning naming v0.2.0, got: $warnings",
            warnings.any { it.contains("v0.2.0") }
        )
    }

    @Test
    fun `release with a usable apk asset is not reported as skipped`() {
        val warnings = mutableListOf<String>()
        val body = "[" + releaseJson("v0.1.0", assetsJson = "[${apkAsset("droid-mesh-v0.1.0.apk")}]") + "]"

        val releases = GitHubReleaseFetcher.parseReleasesJson(body, count = 10, onSkippedRelease = { warnings.add(it) })

        assertEquals(1, releases.size)
        assertTrue("expected no skip warnings, got: $warnings", warnings.isEmpty())
    }

    @Test
    fun `draft releases are skipped without a warning`() {
        val warnings = mutableListOf<String>()
        val body = "[" + releaseJson("v0.3.0-draft", draft = true, assetsJson = "[${apkAsset("x.apk")}]") + "]"

        val releases = GitHubReleaseFetcher.parseReleasesJson(body, count = 10, onSkippedRelease = { warnings.add(it) })

        assertEquals(0, releases.size)
        assertTrue("drafts aren't the dangling-release case; expected no warning", warnings.isEmpty())
    }

    @Test
    fun `respects the count limit among usable releases only`() {
        val body = "[" +
            releaseJson("v0.3.0", assetsJson = "[]") + "," +
            releaseJson("v0.2.0", assetsJson = "[${apkAsset("a.apk")}]") + "," +
            releaseJson("v0.1.0", assetsJson = "[${apkAsset("b.apk")}]") +
            "]"

        val releases = GitHubReleaseFetcher.parseReleasesJson(body, count = 1)

        assertEquals(1, releases.size)
        assertEquals("v0.2.0", releases.first().tagName)
    }
}
