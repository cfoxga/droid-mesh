package com.cfox.droidmesh.security

import java.net.URI

object TrustedReleaseHosts {

    private val TRUSTED_HOST_PATTERNS = listOf(
        Regex("^github\\.com$", RegexOption.IGNORE_CASE),
        Regex("^([a-zA-Z0-9_-]+\\.)*github\\.com$", RegexOption.IGNORE_CASE),
        Regex("^([a-zA-Z0-9_-]+\\.)*githubusercontent\\.com$", RegexOption.IGNORE_CASE),
        Regex("^git\\.cfoxga\\.com$", RegexOption.IGNORE_CASE),
        Regex("^([a-zA-Z0-9_-]+\\.)*cfoxga\\.com$", RegexOption.IGNORE_CASE)
    )

    /**
     * Checks if a URL is a valid, secure HTTPS release URL pointing to an allowlisted release host,
     * or matching an optionally configured explicit release host ([allowedHost]).
     */
    fun isTrustedReleaseUrl(url: String, allowedHost: String? = null): Boolean {
        if (url.isBlank()) return false
        val trimmed = url.trim()
        if (!trimmed.startsWith("https://", ignoreCase = true)) {
            return false
        }

        return try {
            val uri = URI(trimmed)
            val host = uri.host ?: return false
            isTrustedHost(host, allowedHost)
        } catch (e: Exception) {
            false
        }
    }

    fun isTrustedHost(host: String, allowedHost: String? = null): Boolean {
        val cleanHost = host.trim().lowercase()
        if (allowedHost != null && cleanHost.equals(allowedHost.trim().lowercase(), ignoreCase = true)) {
            return true
        }

        return TRUSTED_HOST_PATTERNS.any { pattern ->
            pattern.matches(cleanHost)
        }
    }
}
