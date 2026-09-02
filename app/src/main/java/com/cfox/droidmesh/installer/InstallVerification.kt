package com.cfox.droidmesh.installer

/**
 * INST-BEHAVE-007: classifies whether an install actually happened.
 *
 * Dispatching the installer Intent is not the same as installing. On a node where the
 * confirmation dialog is never tapped — no accessibility service, or the user walked away —
 * the package manager keeps reporting the old build indefinitely. Reporting COMPLETED / 100%
 * at dispatch time made that node look updated in the Web UI and in `/status` while it was
 * still running the previous version.
 *
 * Pure and Android-free so the classification is unit-testable; the polling around it is I/O
 * glue in UpdateCoordinator.
 */
object InstallVerification {

    const val STATE_AWAITING_CONFIRMATION = "AWAITING_CONFIRMATION"

    sealed class Outcome {
        data class Verified(val installedVersion: String) : Outcome()
        data class NotConfirmed(val state: String, val message: String) : Outcome()
    }

    fun classify(
        installedVersionName: String?,
        targetTag: String,
        accessibilityServiceActive: Boolean
    ): Outcome {
        val installed = installedVersionName?.trim().orEmpty()
        if (installed.isNotEmpty() && !AppVersionHelper.isVersionMismatch(installed, targetTag)) {
            return Outcome.Verified(installed)
        }

        val current = if (installed.isEmpty()) "not installed" else "still $installed"
        val message = if (accessibilityServiceActive) {
            "Installer was dispatched for $targetTag but the package is $current — " +
                "the install was not confirmed on this device."
        } else {
            "Installer was dispatched for $targetTag but the package is $current — " +
                "this device has DroidMesh's accessibility service disabled, so nothing tapped " +
                "the system install dialog."
        }
        return Outcome.NotConfirmed(STATE_AWAITING_CONFIRMATION, message)
    }
}
