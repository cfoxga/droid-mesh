package com.cfox.droidmesh.utils

import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import com.cfox.droidmesh.installer.AdbAuthorizationPendingException
import com.cfox.droidmesh.installer.AdbLoopbackInstaller
import com.cfox.droidmesh.service.AutoInstallService
import kotlinx.coroutines.delay

// PROV-BEHAVE-001/002: audits the three OS-level grants DroidMesh depends on outside its own
// package (REQUEST_INSTALL_PACKAGES appop, accessibility service enablement, battery
// optimization/Doze whitelist exemption) and, on request, repairs whichever are missing over
// loopback ADB. See project/docs/SPEC/provisioning.md.
object ProvisioningAuditor {

    const val ACCESSIBILITY_SERVICE_COMPONENT = "com.cfox.droidmesh/com.cfox.droidmesh.service.AutoInstallService"
    private const val PACKAGE_NAME = "com.cfox.droidmesh"

    const val KEY_INSTALL_PACKAGES = "install_packages"
    const val KEY_ACCESSIBILITY = "accessibility"
    const val KEY_BATTERY_OPTIMIZATION = "battery_optimization"

    // PROV-BEHAVE-008: how long to let AccessibilityManagerService rebind after an
    // enable-off/enable-on toggle before the caller re-audits and reports post-repair state.
    private const val ACCESSIBILITY_REBIND_SETTLE_MS = 1500L

    data class ProvisioningItem(
        val key: String,
        val label: String,
        val satisfied: Boolean,
        val externalCommand: String
    )

    data class ProvisioningAuditResult(
        val items: List<ProvisioningItem>,
        val repairNeeded: Boolean
    )

    data class ProvisioningRepairResult(
        val audit: ProvisioningAuditResult,
        val repairedKeys: List<String>,
        val failures: List<ProvisioningRepairFailure>
    )

    // PROV-BEHAVE-010: what went wrong, per item. Without it the UI can only say "some items
    // could not be repaired" and the reason lives nowhere but logcat, which is exactly how a
    // permanently-unauthorized ADB key looked like an unexplained failure for a whole release.
    data class ProvisioningRepairFailure(
        val key: String,
        val label: String,
        val error: String
    )

    // PROV-BEHAVE-002/008: pure classifier, no Android framework calls — testable without a
    // device. `accessibilityGranted` and `accessibilityServiceRunning` are checked separately:
    // the OS setting can still say the service is enabled while the live instance has died
    // (crash, or Doze/App Standby kill on a TV device) and not been rebound — both must hold for
    // the accessibility item to read satisfied, or install-confirmation dialogs go unhandled
    // while the repair banner reports all-clear.
    fun classify(
        installPackagesGranted: Boolean,
        accessibilityGranted: Boolean,
        accessibilityServiceRunning: Boolean,
        batteryExemptionGranted: Boolean
    ): ProvisioningAuditResult {
        val accessibilityLabel: String
        val accessibilityCommand: String
        if (accessibilityGranted && !accessibilityServiceRunning) {
            // Enabled per the OS setting, but no live instance is bound — merging the component
            // into enabled_accessibility_services again is a no-op (it's already there). The fix
            // is to force AccessibilityManagerService to tear down and rebind every enabled
            // service, which the plain enable command doesn't do.
            accessibilityLabel = "Accessibility Service (enabled but not running)"
            accessibilityCommand = "adb shell settings put secure accessibility_enabled 0 && " +
                "adb shell settings put secure accessibility_enabled 1"
        } else {
            // PROV-BEHAVE-011 (gitea#89): Android 13+/14 resets ACCESS_RESTRICTED_SETTINGS to
            // `deny` for a sideloaded app on every install/update, and while denied the OS
            // silently strips the accessibility grant back out even when the settings write below
            // succeeds -- so an admin following this command by hand needs the app-op cleared
            // first, or they hit the exact same silent-revert loop repairAccessibility() used to.
            accessibilityLabel = "Accessibility Service"
            accessibilityCommand = "adb shell appops set $PACKAGE_NAME ACCESS_RESTRICTED_SETTINGS allow && " +
                "adb shell settings put secure enabled_accessibility_services " +
                "$ACCESSIBILITY_SERVICE_COMPONENT && adb shell settings put secure accessibility_enabled 1"
        }
        val items = listOf(
            ProvisioningItem(
                key = KEY_INSTALL_PACKAGES,
                label = "Install Unknown Apps",
                satisfied = installPackagesGranted,
                externalCommand = "adb shell appops set $PACKAGE_NAME REQUEST_INSTALL_PACKAGES allow"
            ),
            ProvisioningItem(
                key = KEY_ACCESSIBILITY,
                label = accessibilityLabel,
                satisfied = accessibilityGranted && accessibilityServiceRunning,
                externalCommand = accessibilityCommand
            ),
            ProvisioningItem(
                key = KEY_BATTERY_OPTIMIZATION,
                label = "Battery Optimization Exemption",
                satisfied = batteryExemptionGranted,
                externalCommand = "adb shell dumpsys deviceidle whitelist +$PACKAGE_NAME"
            )
        )
        return ProvisioningAuditResult(items = items, repairNeeded = items.any { !it.satisfied })
    }

    // PROV-BEHAVE-001: reads real Android state and classifies it. Called on every
    // UpdaterForegroundService start (boot and manual launch alike) and on demand via
    // GET /api/system/provisioning. accessibilityServiceRunning reads AutoInstallService's
    // live, in-process flag (PROV-BEHAVE-008) — separate from the OS setting isAccessibilityGranted
    // reads, since only the live flag reflects whether a bound instance actually exists right now.
    fun audit(context: Context): ProvisioningAuditResult {
        return classify(
            installPackagesGranted = context.packageManager.canRequestPackageInstalls(),
            accessibilityGranted = isAccessibilityGranted(context),
            accessibilityServiceRunning = AutoInstallService.isServiceRunning,
            batteryExemptionGranted = isIgnoringBatteryOptimizations(context)
        )
    }

    private fun isAccessibilityGranted(context: Context): Boolean {
        val enabled = try {
            Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
        } catch (e: Exception) {
            0
        }
        if (enabled != 1) return false
        val services = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        } catch (e: Exception) {
            null
        } ?: return false
        return services.split(":").any { componentNamesEqual(it, ACCESSIBILITY_SERVICE_COMPONENT) }
    }

    // PROV-BEHAVE-009: recognizes the relative-dot shorthand ("pkg/.Class") an operator's adb
    // shell (or `settings put` invoked by hand, as the workstation-run externalCommand text
    // invites) commonly writes as equal to the fully-qualified form ("pkg/pkg.Class"). Android's
    // own ComponentName.unflattenFromString and AccessibilityManagerService bind both spellings
    // identically, but a plain string comparison did not, which left the audit reporting a
    // correctly-bound service as permanently "missing" and the merge appending a redundant
    // fully-qualified duplicate on every repair (gitea#82).
    internal fun componentNamesEqual(a: String, b: String): Boolean =
        expandComponentName(a).equals(expandComponentName(b), ignoreCase = true)

    private fun expandComponentName(raw: String): String {
        val trimmed = raw.trim()
        val slash = trimmed.indexOf('/')
        if (slash < 0) return trimmed
        val pkg = trimmed.substring(0, slash)
        val cls = trimmed.substring(slash + 1)
        val fullClass = if (cls.startsWith(".")) pkg + cls else cls
        return "$pkg/$fullClass"
    }

    private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            pm?.isIgnoringBatteryOptimizations(PACKAGE_NAME) ?: false
        } catch (e: Exception) {
            false
        }
    }

    // PROV-BEHAVE-005: appends DroidMesh's service to an existing colon-separated
    // enabled_accessibility_services value instead of overwriting it, so any already-enabled
    // OEM accessibility service (e.g. Meta Portal's presence/system services) survives repair.
    // Idempotent: a value that already contains the component is returned unchanged (order
    // preserved, no duplicate). `existing` may be null, blank, or the literal string "null" —
    // `settings get secure <unset key>` prints "null" on real devices — all three are treated as
    // "nothing currently enabled".
    fun mergeAccessibilityServices(existing: String?): String {
        val normalized = existing?.trim()
        val current = if (normalized.isNullOrBlank() || normalized.equals("null", ignoreCase = true)) {
            emptyList()
        } else {
            normalized.split(":").map { it.trim() }.filter { it.isNotEmpty() }
        }
        if (current.any { componentNamesEqual(it, ACCESSIBILITY_SERVICE_COMPONENT) }) {
            return current.joinToString(":")
        }
        return (current + ACCESSIBILITY_SERVICE_COMPONENT).joinToString(":")
    }

    // PROV-BEHAVE-004/006: re-audits, then runs the loopback-ADB fix for every currently
    // unsatisfied item. Fails fast (before opening any socket) when ADB isn't enabled at all.
    // A single item's command failing does not abort the rest — the caller sees exactly which
    // keys were actually repaired via `repairedKeys` and can re-audit to see what's still wrong.
    suspend fun repair(context: Context): Result<ProvisioningRepairResult> {
        if (!AdbHelper.isAdbEnabled(context)) {
            return Result.failure(
                IllegalStateException("ADB is not enabled — enable USB/network debugging in Developer Options first")
            )
        }

        val before = audit(context)
        val repaired = mutableListOf<String>()
        val failures = mutableListOf<ProvisioningRepairFailure>()

        for (item in before.items) {
            if (item.satisfied) continue
            val outcome = when (item.key) {
                KEY_INSTALL_PACKAGES ->
                    AdbLoopbackInstaller.runShellCommand("appops set $PACKAGE_NAME REQUEST_INSTALL_PACKAGES allow")
                KEY_ACCESSIBILITY -> repairAccessibility(context)
                KEY_BATTERY_OPTIMIZATION ->
                    AdbLoopbackInstaller.runShellCommand("dumpsys deviceidle whitelist +$PACKAGE_NAME")
                else -> Result.failure(IllegalStateException("Unknown provisioning item: ${item.key}"))
            }
            if (outcome.isSuccess) {
                repaired.add(item.key)
            } else {
                val failure = describeRepairFailure(item, outcome.exceptionOrNull())
                failures.add(failure)
                Logger.w("Provisioning repair failed for ${item.key}: ${failure.error}")
            }
        }

        return Result.success(
            ProvisioningRepairResult(
                audit = audit(context),
                repairedKeys = repaired,
                failures = failures
            )
        )
    }

    // PROV-BEHAVE-010: pure mapper, so the wording a caller actually sees is unit-testable.
    // AdbAuthorizationPendingException carries its own instructions; everything else falls back to
    // the exception message, and to the exception type when even that is absent (a bare
    // SocketTimeoutException, for one, reports a null message on some Android versions).
    internal fun describeRepairFailure(
        item: ProvisioningItem,
        error: Throwable?
    ): ProvisioningRepairFailure {
        val text = when {
            error is AdbAuthorizationPendingException -> AdbAuthorizationPendingException.MESSAGE
            error == null -> "Repair reported no result"
            !error.message.isNullOrBlank() -> error.message!!
            else -> error.javaClass.simpleName
        }
        return ProvisioningRepairFailure(key = item.key, label = item.label, error = text)
    }

    // PROV-BEHAVE-008: two distinct failure modes need two distinct fixes. If the OS setting
    // already lists DroidMesh's service but the live instance isn't running, the settings string
    // isn't the problem — merging into it again is a no-op — so toggle accessibility_enabled
    // off/on to force AccessibilityManagerService to tear down and recreate every enabled
    // service's binding (including ours). Note this briefly disables every other currently-
    // enabled accessibility service on the device too, not just DroidMesh's. Otherwise, fall back
    // to the existing enable/merge path for the "not enabled at all" case.
    private suspend fun repairAccessibility(context: Context): Result<String> {
        // PROV-BEHAVE-011 (gitea#89): clear ACCESS_RESTRICTED_SETTINGS first, unconditionally --
        // Android 13+/14 resets it to `deny` for a sideloaded app on every install/update, and
        // while denied the OS silently strips whatever this function writes below back out. This
        // is what makes in-app Repair Automatically self-sufficient instead of appearing to
        // succeed and then silently reverting. Idempotent when already allowed; failure here is
        // reported like any other repair failure rather than skipped, since a write that follows a
        // failed clear is not trustworthy.
        AdbLoopbackInstaller.runShellCommand("appops set $PACKAGE_NAME ACCESS_RESTRICTED_SETTINGS allow")
            .onFailure { return Result.failure(it) }

        if (isAccessibilityGranted(context) && !AutoInstallService.isServiceRunning) {
            AdbLoopbackInstaller.runShellCommand("settings put secure accessibility_enabled 0")
                .onFailure { return Result.failure(it) }
            val result = AdbLoopbackInstaller.runShellCommand("settings put secure accessibility_enabled 1")
            if (result.isSuccess) {
                // Give AccessibilityManagerService a moment to rebind before the caller re-audits.
                delay(ACCESSIBILITY_REBIND_SETTLE_MS)
            }
            return result
        }

        val current = AdbLoopbackInstaller.runShellCommand("settings get secure enabled_accessibility_services")
            .getOrElse { return Result.failure(it) }
        val merged = mergeAccessibilityServices(current)

        AdbLoopbackInstaller.runShellCommand("settings put secure enabled_accessibility_services $merged")
            .onFailure { return Result.failure(it) }

        return AdbLoopbackInstaller.runShellCommand("settings put secure accessibility_enabled 1")
    }
}
