package com.cfox.droidmesh.installer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PROGRAMMATIC] INST-TEST-005: an install is "completed" only when the package manager actually
 * reports the target version. The coordinator used to set COMPLETED / 100% the instant the
 * installer Intent was dispatched, so a node whose confirmation dialog was never tapped reported
 * a finished update while still running the old build.
 */
class InstallVerificationTest {

    @Test
    fun testVerifiedOnlyWhenInstalledVersionMatchesTarget() {
        val outcome = InstallVerification.classify("2026.9.2", "2026.9.2", accessibilityServiceActive = true)
        assertTrue("expected Verified, got $outcome", outcome is InstallVerification.Outcome.Verified)
    }

    // Negative case: this is the exact live situation on the Master Bedroom Portal — installer
    // dispatched for 2026.9.2, package manager still reporting 2026.8.107.
    @Test
    fun testNotConfirmedWhenInstalledVersionStillTheOldBuild() {
        val outcome = InstallVerification.classify("2026.8.107", "2026.9.2", accessibilityServiceActive = false)
        assertTrue("expected NotConfirmed, got $outcome", outcome is InstallVerification.Outcome.NotConfirmed)
        val nc = outcome as InstallVerification.Outcome.NotConfirmed
        assertEquals(InstallVerification.STATE_AWAITING_CONFIRMATION, nc.state)
        assertTrue(
            "with the accessibility service off, the message must say so: ${nc.message}",
            nc.message.contains("accessibility", ignoreCase = true)
        )
        assertFalse(
            "an unconfirmed install must never claim completion: ${nc.message}",
            nc.message.contains("Installed", ignoreCase = true)
        )
    }

    // Accessibility on but the install still did not land -> still not confirmed, and the message
    // must not blame a service that is actually running.
    @Test
    fun testNotConfirmedWithAccessibilityOnDoesNotBlameTheService() {
        val outcome = InstallVerification.classify("2026.8.107", "2026.9.2", accessibilityServiceActive = true)
        assertTrue("expected NotConfirmed, got $outcome", outcome is InstallVerification.Outcome.NotConfirmed)
        val nc = outcome as InstallVerification.Outcome.NotConfirmed
        assertFalse(
            "must not tell the user to enable a service that is already on: ${nc.message}",
            nc.message.contains("accessibility", ignoreCase = true)
        )
    }

    @Test
    fun testNotConfirmedWhenPackageIsNotInstalledAtAll() {
        val outcome = InstallVerification.classify(null, "2026.9.2", accessibilityServiceActive = false)
        assertTrue("expected NotConfirmed, got $outcome", outcome is InstallVerification.Outcome.NotConfirmed)
    }

    // Tag/version formatting must not cause a real success to be reported as unconfirmed —
    // reuses the same normalization the rest of the updater uses.
    @Test
    fun testVerifiedAcrossTagPrefixAndBuildSuffixFormatting() {
        assertTrue(InstallVerification.classify("2026.9.2", "v2026.9.2", true) is InstallVerification.Outcome.Verified)
        assertTrue(InstallVerification.classify("0.1.0 (48)", "0.1.0", true) is InstallVerification.Outcome.Verified)
    }
    /**
     * [PROGRAMMATIC] INST-TEST-005 (regression guard)
     *
     * classify() is pure and unit-testable, but the lie lived in UpdateCoordinator's *wiring*:
     * it set COMPLETED the moment dispatchInstall() returned. Nothing in a JVM unit test can
     * drive that coroutine (it needs a real Context and PackageManager), so without this the
     * whole fix could be reverted in one line with every other test still green. Assert on the
     * source: after the installer is dispatched, the coordinator must consult classify() before
     * it is allowed to reach COMPLETED.
     */
    @Test
    fun `coordinator must verify with classify before reporting COMPLETED after dispatch`() {
        val src = java.io.File("src/main/java/com/cfox/droidmesh/server/UpdateCoordinator.kt").readText()

        assertFalse(
            "UpdateCoordinator still declares success purely from dispatching the installer Intent",
            src.contains("Accessibility service will handle installation dialogs")
        )

        val afterDispatch = src.substringAfter("PackageInstallerDispatcher.dispatchInstall(context, apkFile)")
        assertTrue("dispatchInstall call site not found in UpdateCoordinator", afterDispatch.isNotEmpty())

        val classifyAt = afterDispatch.indexOf("InstallVerification.classify")
        val completedAt = afterDispatch.indexOf("state = \"COMPLETED\"")

        assertTrue("UpdateCoordinator never verifies the install after dispatching it", classifyAt >= 0)
        assertTrue("UpdateCoordinator never reaches COMPLETED after a dispatched install", completedAt >= 0)
        assertTrue(
            "UpdateCoordinator reports COMPLETED before verifying the install with classify()",
            classifyAt < completedAt
        )
    }

}
