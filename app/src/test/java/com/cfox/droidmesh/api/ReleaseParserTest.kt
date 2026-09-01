package com.cfox.droidmesh.api

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for generic ReleaseParser that handles:
 * - GitHub releases API endpoints (generic, not hardcoded)
 * - Direct APK download URLs
 * - Error cases
 */
class ReleaseParserTest {

    /**
     * [APP-TEST-008] — ReleaseParser detects GitHub releases API URL pattern
     * and extracts repository path from it.
     */
    @Test
    fun testDetectGitHubReleaseUrl() {
        val githubUrl = "https://api.github.com/repos/jxlarrea/kiosk-satellite/releases"
        assertTrue("Should detect GitHub API URL", ReleaseParser.isGitHubReleaseUrl(githubUrl))

        val directUrl = "https://releases.example.com/app-1.2.3.apk"
        assertFalse("Should not detect direct APK URL as GitHub", ReleaseParser.isGitHubReleaseUrl(directUrl))
    }

    /**
     * [APP-TEST-009] — ReleaseParser correctly extracts repository owner and name
     * from a GitHub releases API URL.
     */
    @Test
    fun testExtractGitHubRepoPath() {
        val url = "https://api.github.com/repos/jxlarrea/kiosk-satellite/releases"
        val (owner, repo) = ReleaseParser.extractGitHubRepoPath(url)
        assertEquals(owner, "jxlarrea")
        assertEquals(repo, "kiosk-satellite")
    }

    /**
     * [APP-TEST-010] — ReleaseParser handles malformed GitHub URLs gracefully
     * and returns null or throws.
     */
    @Test
    fun testExtractGitHubRepoPathInvalid() {
        val invalidUrl = "https://api.github.com/repos/invalid"
        try {
            ReleaseParser.extractGitHubRepoPath(invalidUrl)
            fail("Should throw on invalid GitHub URL")
        } catch (e: IllegalArgumentException) {
            assertTrue("Should indicate invalid format", e.message?.contains("Invalid") == true)
        }
    }

    /**
     * [APP-TEST-011] — ReleaseParser creates a basic ReleaseInfo from direct APK URL,
     * extracting filename and version from URL path.
     */
    @Test
    fun testCreateReleaseInfoFromDirectUrl() {
        val directUrl = "https://releases.example.com/app-1.2.3.apk"
        val release = ReleaseParser.parseDirectApkUrl(directUrl)

        assertNotNull("Should create ReleaseInfo", release)
        assertEquals("Should extract filename", "app-1.2.3.apk", release?.apkFileName)
        assertEquals("Should use URL as download URL", directUrl, release?.apkAssetUrl)
        assertEquals("Should infer version from filename", "1.2.3", release?.tagName)
    }

    /**
     * [APP-TEST-012] — ReleaseParser identifies direct APK URLs by file extension.
     */
    @Test
    fun testIsDirectApkUrl() {
        assertTrue("Should identify .apk file", ReleaseParser.isDirectApkUrl("https://example.com/app.apk"))
        assertTrue("Should identify versioned APK", ReleaseParser.isDirectApkUrl("https://example.com/app-1.2.3.apk"))
        assertFalse("Should not identify directory", ReleaseParser.isDirectApkUrl("https://example.com/releases/"))
        assertFalse("Should not identify HTML", ReleaseParser.isDirectApkUrl("https://example.com/download.html"))
    }

    /**
     * [APP-TEST-013] — ReleaseParser normalizes GitHub URLs to ensure they point
     * to the correct API endpoint.
     */
    @Test
    fun testNormalizeGitHubUrl() {
        val withoutTrailingSlash = "https://api.github.com/repos/owner/repo/releases"
        val withTrailingSlash = "https://api.github.com/repos/owner/repo/releases/"
        val withoutPath = "https://api.github.com/repos/owner/repo"

        val normalized1 = ReleaseParser.normalizeGitHubUrl(withoutTrailingSlash)
        val normalized2 = ReleaseParser.normalizeGitHubUrl(withTrailingSlash)
        val normalized3 = ReleaseParser.normalizeGitHubUrl(withoutPath)

        assertEquals("Should normalize trailing slash", normalized1, normalized2)
        assertEquals("Should add /releases path", normalized1, normalized3)
    }

    /**
     * [APP-TEST-014] — ReleaseParser extracts semver version from various filename formats.
     */
    @Test
    fun testExtractVersionFromFilename() {
        val tests = mapOf(
            "app-1.2.3.apk" to "1.2.3",
            "v1.2.3.apk" to "1.2.3",
            "kiosk-satellite-1.2.3.apk" to "1.2.3",
            "kiosk-satellite-v1.2.3.apk" to "1.2.3",
            "app-1.2.3-beta.apk" to "1.2.3",
            "app-1.2.3+build.apk" to "1.2.3"
        )

        for ((filename, expectedVersion) in tests) {
            val extracted = ReleaseParser.extractVersionFromFilename(filename)
            assertEquals("Should extract version from $filename", expectedVersion, extracted)
        }
    }
}
