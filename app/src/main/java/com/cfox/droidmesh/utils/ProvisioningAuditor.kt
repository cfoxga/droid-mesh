package com.cfox.droidmesh.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
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

    // PROV-BEHAVE-014 (gitea#98): minimum cooldown between continuous repair attempts
    const val CONTINUOUS_REPAIR_COOLDOWN_MS = 5000L

    // PROV-BEHAVE-014 (gitea#98): rate limiter and safety gate for continuous accessibility repair
    fun shouldTriggerContinuousRepair(
        isRepairing: Boolean,
        lastRepairTimeMs: Long,
        nowMs: Long,
        accessibilitySatisfied: Boolean
    ): Boolean {
        if (accessibilitySatisfied) return false
        if (isRepairing) return false
        if (nowMs - lastRepairTimeMs < CONTINUOUS_REPAIR_COOLDOWN_MS) return false
        return true
    }

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

    // PROV-BEHAVE-012 (gitea#90): pure predicate so UpdaterForegroundService can log the
    // PROV-BEHAVE-008 side-effect warning (every other enabled accessibility service on the
    // device briefly disables too) BEFORE calling repair(), not after — an admin reading /logs
    // chronologically needs the warning to precede the event it explains. Mirrors classify()'s own
    // "enabled but not running" label check rather than re-deriving it from raw booleans, so this
    // stays in sync if that sub-case's detection ever changes.
    fun accessibilityRebindToggleWillFire(audit: ProvisioningAuditResult): Boolean {
        val item = audit.items.firstOrNull { it.key == KEY_ACCESSIBILITY } ?: return false
        return !item.satisfied && item.label == "Accessibility Service (enabled but not running)"
    }

    // PROV-BEHAVE-012 (gitea#90): pure formatter for the auto-repair log line, so its wording is
    // unit-testable without a live ADB session — mirrors describeRepairFailure's precedent.
    fun describeAutoRepairOutcome(result: ProvisioningRepairResult): String {
        val repairedText = if (result.repairedKeys.isNotEmpty()) {
            "repaired ${result.repairedKeys.joinToString(", ")}"
        } else null
        val failuresText = if (result.failures.isNotEmpty()) {
            "still failing: " + result.failures.joinToString("; ") { "${it.label}: ${it.error}" }
        } else null
        return listOfNotNull(repairedText, failuresText).joinToString("; ").ifEmpty {
            "nothing left to repair"
        }
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

    // PROV-BEHAVE-013 (gitea#92): true once WRITE_SECURE_SETTINGS has been granted (a single
    // `adb shell pm grant com.cfox.droidmesh android.permission.WRITE_SECURE_SETTINGS`, ever, per
    // device -- like the REQUEST_INSTALL_PACKAGES/ACCESS_RESTRICTED_SETTINGS app-ops already
    // observed in this project, a `pm grant` persists across every future app update with no
    // re-prompt). When held, the two Settings.Secure writes below no longer need the in-app
    // loopback ADB client at all -- eliminating the exact failure mode that stalled Great Room
    // GTV's repair indefinitely: that client's own RSA keypair needs a fresh on-screen "Allow
    // debugging from this computer?" tap the first time adbd sees it, which nobody driving this
    // repair from the Web UI or an automatic boot-time call can ever answer.
    private fun hasWriteSecureSettingsPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    // PROV-TEST-014: ACCESS_RESTRICTED_SETTINGS is an AppOpsManager mode, not a Settings.Secure
    // key -- WRITE_SECURE_SETTINGS does not cover it, and clearing it still requires ADB/shell.
    // But that app-op persists across updates independent of this ADB key's trust state, so a
    // device that already has it allowed from a prior repair must not have today's repair
    // blocked just because the current process's loopback key happens to be unauthorized right
    // now: only abort when there is no WRITE_SECURE_SETTINGS fallback to fall back on either.
    internal fun shouldAbortAccessibilityRepair(
        restrictedSettingsClearSucceeded: Boolean,
        canWriteSecureSettingsDirectly: Boolean
    ): Boolean = !restrictedSettingsClearSucceeded && !canWriteSecureSettingsDirectly

    // PROV-TEST-015: explains, in /api/logs, why an ADB error didn't actually stop the repair.
    internal fun describeRestrictedSettingsClearBypass(errorClassName: String): String =
        "Could not clear ACCESS_RESTRICTED_SETTINGS over ADB ($errorClassName); proceeding via " +
            "WRITE_SECURE_SETTINGS since it's already held"

    // PROV-BEHAVE-008: two distinct failure modes need two distinct fixes. If the OS setting
    // already lists DroidMesh's service but the live instance isn't running, the settings string
    // isn't the problem — merging into it again is a no-op — so toggle accessibility_enabled
    // off/on to force AccessibilityManagerService to tear down and recreate every enabled
    // service's binding (including ours). Note this briefly disables every other currently-
    // enabled accessibility service on the device too, not just DroidMesh's. Otherwise, fall back
    // to the existing enable/merge path for the "not enabled at all" case.
    private suspend fun repairAccessibility(context: Context): Result<String> {
        val canWriteSecureSettingsDirectly = hasWriteSecureSettingsPermission(context)

        // PROV-BEHAVE-011 (gitea#89): clear ACCESS_RESTRICTED_SETTINGS first, best-effort --
        // Android 13+/14 resets it to `deny` for a sideloaded app on every install/update, and
        // while denied the OS silently strips whatever this function writes below back out.
        // Idempotent when already allowed. PROV-BEHAVE-013: a failure here only aborts the whole
        // item when there is no WRITE_SECURE_SETTINGS fallback for the writes that follow --
        // otherwise it's logged and repair proceeds via the direct-write path instead.
        val restrictedSettingsClear =
            AdbLoopbackInstaller.runShellCommand("appops set $PACKAGE_NAME ACCESS_RESTRICTED_SETTINGS allow")
        if (restrictedSettingsClear.isFailure) {
            if (shouldAbortAccessibilityRepair(restrictedSettingsClearSucceeded = false, canWriteSecureSettingsDirectly)) {
                return Result.failure(restrictedSettingsClear.exceptionOrNull()!!)
            }
            Logger.w(
                describeRestrictedSettingsClearBypass(
                    restrictedSettingsClear.exceptionOrNull()?.javaClass?.simpleName ?: "unknown error"
                )
            )
        }

        if (canWriteSecureSettingsDirectly) {
            return repairAccessibilityDirect(context)
        }

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

    // PROV-BEHAVE-013: same two sub-cases as the ADB path above, written directly through
    // ContentResolver instead — no socket, no per-process key trust, no on-screen prompt. Only
    // reachable when hasWriteSecureSettingsPermission() is true. Not unit tested directly (it's a
    // thin ContentResolver wrapper requiring a real Context, matching the existing precedent for
    // PROV-BEHAVE-001/004/006); covered by live-fleet verification instead.
    private suspend fun repairAccessibilityDirect(context: Context): Result<String> = try {
        if (isAccessibilityGranted(context) && !AutoInstallService.isServiceRunning) {
            Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
            Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            delay(ACCESSIBILITY_REBIND_SETTLE_MS)
        } else {
            val current = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            val merged = mergeAccessibilityServices(current)
            Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, merged)
            Settings.Secure.putInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        }
        Result.success("Repaired directly via WRITE_SECURE_SETTINGS")
    } catch (e: Exception) {
        Result.failure(e)
    }
}
