package com.cfox.droidmesh.installer

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * [PROGRAMMATIC] INST-TEST-041/042/043/044: DeviceOwnerInstaller (INST-BEHAVE-024) is silent
 * background installation via a Device Owner-committed PackageInstaller.Session. This module has
 * no androidTest instrumentation harness, so — mirroring AutoInstallServiceTest's approach for
 * AccessibilityNodeInfo — Mockito 5's inline mock maker stands in for the final/concrete Android
 * framework classes (DevicePolicyManager, PackageInstaller.Session) involved, and the decision /
 * status-mapping logic is split into pure functions ([DeviceOwnerInstaller.streamApkIntoSession],
 * [DeviceOwnerInstaller.interpretCommitResult]) that need no real broadcast dispatch at all. Live
 * fleet deploy verification of the full Device Owner-provisioned flow happens separately per the
 * project workflow.
 */
class DeviceOwnerInstallerTest {

    private val packageName = "com.cfox.droidmesh"

    // INST-TEST-041: isDeviceOwner is true exactly when DevicePolicyManager reports this app is
    // the registered Device Owner.
    @Test
    fun testIsDeviceOwnerTrueWhenDevicePolicyManagerReportsDeviceOwner() {
        val context: Context = mock()
        val dpm: DevicePolicyManager = mock()
        whenever(context.packageName).thenReturn(packageName)
        whenever(context.getSystemService(Context.DEVICE_POLICY_SERVICE)).thenReturn(dpm)
        whenever(dpm.isDeviceOwnerApp(packageName)).thenReturn(true)

        assertTrue(DeviceOwnerInstaller.isDeviceOwner(context))
    }

    // INST-TEST-042 (negative): not the Device Owner, no DevicePolicyManager available, and an
    // exception from the platform call must all fail closed to false rather than throwing or
    // defaulting to true.
    @Test
    fun testIsDeviceOwnerFalseWhenNotOwnerOrServiceUnavailableOrThrows() {
        val notOwner: Context = mock()
        val dpm: DevicePolicyManager = mock()
        whenever(notOwner.packageName).thenReturn(packageName)
        whenever(notOwner.getSystemService(Context.DEVICE_POLICY_SERVICE)).thenReturn(dpm)
        whenever(dpm.isDeviceOwnerApp(packageName)).thenReturn(false)
        assertFalse(DeviceOwnerInstaller.isDeviceOwner(notOwner))

        val noService: Context = mock()
        whenever(noService.packageName).thenReturn(packageName)
        whenever(noService.getSystemService(Context.DEVICE_POLICY_SERVICE)).thenReturn(null)
        assertFalse(DeviceOwnerInstaller.isDeviceOwner(noService))

        val throwing: Context = mock()
        whenever(throwing.packageName).thenReturn(packageName)
        whenever(throwing.getSystemService(Context.DEVICE_POLICY_SERVICE)).thenThrow(RuntimeException("boom"))
        assertFalse(DeviceOwnerInstaller.isDeviceOwner(throwing))
    }

    // INST-TEST-043: streaming writes the APK's exact bytes into the session's write stream at
    // offset 0 for its full length, and fsyncs that same stream -- the two steps the spec
    // requires ("Stream APK bytes into session" then commit) before commit is ever reached.
    @Test
    fun testStreamApkIntoSessionWritesExactBytesAndFsyncs() {
        val apkFile = File.createTempFile("device-owner-install-test", ".apk")
        apkFile.deleteOnExit()
        val payload = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9)
        apkFile.writeBytes(payload)

        val session: PackageInstaller.Session = mock()
        val captured = ByteArrayOutputStream()
        whenever(session.openWrite(eq(apkFile.name), eq(0L), eq(apkFile.length()))).thenReturn(captured)

        DeviceOwnerInstaller.streamApkIntoSession(session, apkFile)

        assertEquals(payload.toList(), captured.toByteArray().toList())
        verify(session).fsync(captured)

        apkFile.delete()
    }

    // INST-TEST-044: STATUS_SUCCESS maps to Result.success carrying the broadcast message; any
    // other status maps to Result.failure, naming the broadcast message when present and a
    // status-carrying default when the platform sends none.
    @Test
    fun testInterpretCommitResultMapsStatusToSuccessOrFailure() {
        val success = DeviceOwnerInstaller.interpretCommitResult(PackageInstaller.STATUS_SUCCESS, "installed")
        assertTrue(success.isSuccess)
        assertEquals("installed", success.getOrNull())

        val failureWithMessage = DeviceOwnerInstaller.interpretCommitResult(
            PackageInstaller.STATUS_FAILURE_INVALID,
            "signature mismatch"
        )
        assertTrue(failureWithMessage.isFailure)
        assertEquals("signature mismatch", failureWithMessage.exceptionOrNull()?.message)

        val failureNoMessage = DeviceOwnerInstaller.interpretCommitResult(PackageInstaller.STATUS_FAILURE, null)
        assertTrue(failureNoMessage.isFailure)
        assertTrue(
            "default failure message must name the status code",
            failureNoMessage.exceptionOrNull()?.message?.contains("status=${PackageInstaller.STATUS_FAILURE}") == true
        )

        val failureBlankMessage = DeviceOwnerInstaller.interpretCommitResult(PackageInstaller.STATUS_FAILURE, "   ")
        assertTrue(failureBlankMessage.isFailure)
        assertTrue(
            "a blank broadcast message must fall back to the status-carrying default, not an empty exception message",
            failureBlankMessage.exceptionOrNull()?.message?.contains("status=") == true
        )
    }
}
