package com.cfox.droidmesh.security

import org.junit.Assert.*
import org.junit.Test

class TrustedReleaseHostsTest {

    // [PROGRAMMATIC] UPD-TEST-014: Trusted release hosts allowlist and HTTPS validation
    @Test
    fun testTrustedReleaseHostsAllowlist() {
        // Trusted GitHub hosts
        assertTrue(TrustedReleaseHosts.isTrustedReleaseUrl("https://github.com/owner/repo/releases/download/v1.0/app.apk"))
        assertTrue(TrustedReleaseHosts.isTrustedReleaseUrl("https://api.github.com/repos/owner/repo/releases"))
        assertTrue(TrustedReleaseHosts.isTrustedReleaseUrl("https://objects.githubusercontent.com/github-production-release-asset-2e65be/12345/app.apk"))
        assertTrue(TrustedReleaseHosts.isTrustedReleaseUrl("https://raw.githubusercontent.com/owner/repo/main/app.apk"))
        assertTrue(TrustedReleaseHosts.isTrustedReleaseUrl("https://github-releases.githubusercontent.com/12345/app.apk"))

        // Trusted internal Gitea and cfoxga hosts
        assertTrue(TrustedReleaseHosts.isTrustedReleaseUrl("https://git.cfoxga.com/cfoxga/droid-mesh/releases/download/v1.0/app.apk"))
        assertTrue(TrustedReleaseHosts.isTrustedReleaseUrl("https://apps.cfoxga.com/releases/app.apk"))

        // Allowed configured host match
        assertTrue(TrustedReleaseHosts.isTrustedReleaseUrl("https://custom.internal.lan/downloads/app.apk", allowedHost = "custom.internal.lan"))

        // Negative cases: cleartext HTTP (must be rejected even on trusted domains)
        assertFalse(TrustedReleaseHosts.isTrustedReleaseUrl("http://github.com/owner/repo/app.apk"))
        assertFalse(TrustedReleaseHosts.isTrustedReleaseUrl("http://git.cfoxga.com/releases/app.apk"))
        assertFalse(TrustedReleaseHosts.isTrustedReleaseUrl("http://attacker.com/malicious.apk"))

        // Negative cases: untrusted arbitrary domains
        assertFalse(TrustedReleaseHosts.isTrustedReleaseUrl("https://attacker.com/malicious.apk"))
        assertFalse(TrustedReleaseHosts.isTrustedReleaseUrl("https://evil-github.com/app.apk"))
        assertFalse(TrustedReleaseHosts.isTrustedReleaseUrl("https://notcfoxga.com/app.apk"))
        assertFalse(TrustedReleaseHosts.isTrustedReleaseUrl("https://malicious.org/payload.apk"))
        assertFalse(TrustedReleaseHosts.isTrustedReleaseUrl(""))
        assertFalse(TrustedReleaseHosts.isTrustedReleaseUrl("   "))
        assertFalse(TrustedReleaseHosts.isTrustedReleaseUrl("ftp://github.com/app.apk"))
    }
}
