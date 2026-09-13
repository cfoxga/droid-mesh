package com.cfox.droidmesh.installer

/**
 * INST-BEHAVE-024: pure decision of which installation path `UpdateCoordinator` attempts first,
 * mirroring `InstallVerification`'s separation of decision logic from side-effecting I/O so the
 * branching itself is unit-testable without a live `Context`, `DevicePolicyManager`, or
 * `PackageInstaller` session.
 */
object DeviceOwnerInstallDecision {

    sealed class Path {
        object DeviceOwner : Path()
        object AdbLoopback : Path()
    }

    /**
     * Device Owner is attempted first whenever DroidMesh holds that role; otherwise the existing
     * ADB-loopback-then-PackageInstaller chain (`INST-BEHAVE-006`, `INST-BEHAVE-001`) is
     * unchanged.
     */
    fun primaryPath(isDeviceOwner: Boolean): Path =
        if (isDeviceOwner) Path.DeviceOwner else Path.AdbLoopback
}
