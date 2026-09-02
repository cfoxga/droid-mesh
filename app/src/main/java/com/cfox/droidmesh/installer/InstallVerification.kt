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
    private val STALE_STATES = setOf(STATE_AWAITING_CONFIRMATION, "ERROR")

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

    /**
     * FLT-BEHAVE-009: decides whether the hourly mesh auto-update loop's `Skip` pass ("already on
     * target") should reconcile a stale [STATE_AWAITING_CONFIRMATION]/`ERROR` status back to
     * verified/idle. Only [classify]'s existing evidence is trusted — the caller supplies
     * [statusPackage], the package the *live* status value currently describes, so this never
     * clears a different, genuinely-still-stuck package's status just because an unrelated
     * managed app in the same pass happened to already be on target.
     *
     * Returns `null` when there is nothing to reconcile: the state isn't stale, the status
     * belongs to a different package, or the version still doesn't match target.
     */
    fun reconcileStale(
        currentState: String,
        statusPackage: String?,
        packageName: String,
        installedVersionName: String?,
        targetTag: String,
        accessibilityServiceActive: Boolean
    ): Outcome.Verified? {
        if (currentState !in STALE_STATES) return null
        if (statusPackage != packageName) return null
        return classify(installedVersionName, targetTag, accessibilityServiceActive) as? Outcome.Verified
    }
}
