package com.cfox.droidmesh.api

import java.net.URL

/**
 * Generic release URL parser for handling multiple release sources:
 * - GitHub releases API endpoints: https://api.github.com/repos/owner/repo/releases
 * - Direct APK download URLs: https://example.com/app-1.2.3.apk
 *
 * Replaces the hardcoded GitHubReleaseApi with a generic, composable approach
 * that doesn't bake any app-specific logic into the source.
 */
object ReleaseParser {

    /**
     * Detects if a URL points to a GitHub releases API endpoint.
     *
     * @param url the URL to check
     * @return true if the URL matches the GitHub API pattern
     */
    fun isGitHubReleaseUrl(url: String): Boolean {
        val normalized = url.trim().lowercase()
        return normalized.contains("api.github.com") &&
               normalized.contains("/repos/") &&
               (normalized.contains("/releases") || normalized.endsWith("/repos"))
    }

    /**
     * Detects if a URL points to a direct APK file download.
     *
     * @param url the URL to check
     * @return true if the URL ends with .apk
     */
    fun isDirectApkUrl(url: String): Boolean {
        return url.trim().lowercase().endsWith(".apk")
    }

    /**
     * Extracts the owner and repository name from a GitHub API URL.
     *
     * Example: "https://api.github.com/repos/owner/repo/releases"
     * Returns: Pair("owner", "repo")
     *
     * @param url the GitHub API URL
     * @return Pair of (owner, repo) name
     * @throws IllegalArgumentException if the URL doesn't match the expected pattern
     */
    fun extractGitHubRepoPath(url: String): Pair<String, String> {
        try {
            val normalized = url.trim().lowercase()
            val pattern = "/repos/([^/]+)/([^/]+)(/releases)?".toRegex()
            val match = pattern.find(normalized) ?: throw IllegalArgumentException("URL doesn't match GitHub repo pattern")
            val (owner, repo) = match.destructured
            if (owner.isBlank() || repo.isBlank()) {
                throw IllegalArgumentException("Invalid GitHub URL: owner or repo is empty")
            }
            return Pair(owner, repo)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid GitHub releases URL: ${e.message}")
        }
    }

    /**
     * Normalizes a GitHub releases URL to ensure it points to the correct API endpoint.
     *
     * - Removes trailing slashes
     * - Adds /releases path if missing
     *
     * Examples:
     * - "https://api.github.com/repos/owner/repo" → "https://api.github.com/repos/owner/repo/releases"
     * - "https://api.github.com/repos/owner/repo/releases/" → "https://api.github.com/repos/owner/repo/releases"
     *
     * @param url the GitHub URL to normalize
     * @return normalized URL pointing to the releases API endpoint
     */
    fun normalizeGitHubUrl(url: String): String {
        var normalized = url.trim()
        if (normalized.endsWith("/")) {
            normalized = normalized.dropLast(1)
        }
        if (!normalized.endsWith("/releases")) {
            normalized = "$normalized/releases"
        }
        return normalized
    }

    /**
     * Plain github.com repo/releases page URLs — what an admin actually copies out of their
     * browser address bar — matched here so they can be normalized to the api.github.com form
     * ReleaseParser/GitHubReleaseFetcher actually understand.
     */
    private val PLAIN_GITHUB_URL = Regex(
        "^https?://(www\\.)?github\\.com/([^/]+)/([^/]+?)(\\.git)?(/releases(/.*)?)?/?$",
        RegexOption.IGNORE_CASE
    )

    /**
     * Resolves any accepted GitHub input (a plain github.com repo/releases URL, or an already
     * api.github.com URL) to the normalized api.github.com releases endpoint (`UPD-BEHAVE-008`).
     *
     * @param url the URL as entered by an admin
     * @return the normalized api.github.com releases URL, or null if [url] is neither an
     *   api.github.com URL nor a plain github.com repo URL
     */
    fun toGitHubApiUrl(url: String): String? {
        val trimmed = url.trim()
        if (isGitHubReleaseUrl(trimmed)) return normalizeGitHubUrl(trimmed)
        val match = PLAIN_GITHUB_URL.find(trimmed) ?: return null
        val owner = match.groupValues[2]
        val repo = match.groupValues[3]
        if (owner.isBlank() || repo.isBlank()) return null
        return "https://api.github.com/repos/$owner/$repo/releases"
    }

    /**
     * Extracts version number from a filename using common semver patterns.
     *
     * Handles formats like:
     * - "app-1.2.3.apk" → "1.2.3"
     * - "v1.2.3.apk" → "1.2.3"
     * - "some-app-1.2.3.apk" → "1.2.3"
     * - "app-1.2.3-beta.apk" → "1.2.3"
     * - "app-1.2.3+build.apk" → "1.2.3"
     *
     * @param filename the APK filename
     * @return the extracted version string, or the entire filename if no version found
     */
    fun extractVersionFromFilename(filename: String): String {
        // Remove .apk extension
        val nameWithoutExt = filename.substringBeforeLast(".")

        // Look for semver pattern: digits.digits.digits
        val semverPattern = "\\d+\\.\\d+\\.\\d+".toRegex()
        val match = semverPattern.find(nameWithoutExt)

        return if (match != null) {
            // Extract the semver part and strip any pre-release/build metadata
            match.value.substringBefore("-").substringBefore("+")
        } else {
            // Fallback: return name without extension
            nameWithoutExt
        }
    }

    /**
     * Creates a basic ReleaseInfo from a direct APK download URL.
     *
     * Extracts filename and infers version from the URL path.
     *
     * @param directUrl the direct APK download URL (must end with .apk)
     * @return ReleaseInfo with basic metadata, or null if URL is invalid
     */
    fun parseDirectApkUrl(directUrl: String): ReleaseInfo? {
        if (!isDirectApkUrl(directUrl)) return null

        return try {
            val url = URL(directUrl.trim())
            val path = url.path
            val filename = path.substringAfterLast("/")
            val version = extractVersionFromFilename(filename)

            ReleaseInfo(
                tagName = version,
                name = filename.substringBeforeLast("."),
                publishedAt = "",
                apkAssetUrl = directUrl.trim(),
                apkFileName = filename,
                apkSize = 0L  // Direct URL doesn't provide size; will be discovered on download
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Route URL to appropriate parser based on its type.
     *
     * @param downloadUrl the URL to parse (GitHub API endpoint or direct APK link)
     * @return ReleaseInfo if direct URL, or null if GitHub URL (requires separate API call)
     */
    fun parseUrl(downloadUrl: String): ReleaseInfo? {
        return when {
            isDirectApkUrl(downloadUrl) -> parseDirectApkUrl(downloadUrl)
            else -> null  // GitHub URLs require async API call via GitHubReleaseApi
        }
    }
}
