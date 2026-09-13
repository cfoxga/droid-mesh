package com.cfox.droidmesh.installer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [PROGRAMMATIC] INST-TEST-045: `UpdateCoordinator` wires this exact function to decide its
 * install path (see `executeUpdateForSpecificRelease`), so this test exercises the real
 * production branching logic, not a parallel reimplementation of it.
 */
class DeviceOwnerInstallDecisionTest {

    // INST-TEST-045: Device Owner is the primary path whenever DroidMesh holds that role.
    @Test
    fun testPrimaryPathChoosesDeviceOwnerWhenProvisioned() {
        assertEquals(DeviceOwnerInstallDecision.Path.DeviceOwner, DeviceOwnerInstallDecision.primaryPath(true))
    }

    // INST-TEST-045 (negative): a non-Device-Owner node keeps the existing ADB-loopback-first
    // chain entirely unchanged -- this is the "Non-Device Owner fallback behavior" case.
    @Test
    fun testPrimaryPathFallsBackToAdbLoopbackWhenNotProvisioned() {
        assertEquals(DeviceOwnerInstallDecision.Path.AdbLoopback, DeviceOwnerInstallDecision.primaryPath(false))
    }
}
