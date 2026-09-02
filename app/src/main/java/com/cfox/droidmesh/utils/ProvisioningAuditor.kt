package com.cfox.droidmesh.utils

import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import com.cfox.droidmesh.installer.AdbLoopbackInstaller

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
        val repairedKeys: List<String>
    )

    // PROV-BEHAVE-002: pure classifier, no Android framework calls — testable without a device.
    fun classify(
        installPackagesGranted: Boolean,
        accessibilityGranted: Boolean,
        batteryExemptionGranted: Boolean
    ): ProvisioningAuditResult {
        val items = listOf(
            ProvisioningItem(
                key = KEY_INSTALL_PACKAGES,
                label = "Install Unknown Apps",
                satisfied = installPackagesGranted,
                externalCommand = "adb shell appops set $PACKAGE_NAME REQUEST_INSTALL_PACKAGES allow"
            ),
            ProvisioningItem(
                key = KEY_ACCESSIBILITY,
                label = "Accessibility Service",
                satisfied = accessibilityGranted,
                externalCommand = "adb shell settings put secure enabled_accessibility_services " +
                    "$ACCESSIBILITY_SERVICE_COMPONENT && adb shell settings put secure accessibility_enabled 1"
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
    // GET /api/system/provisioning.
    fun audit(context: Context): ProvisioningAuditResult {
        return classify(
            installPackagesGranted = context.packageManager.canRequestPackageInstalls(),
            accessibilityGranted = isAccessibilityGranted(context),
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
        return services.split(":").any { it.trim().equals(ACCESSIBILITY_SERVICE_COMPONENT, ignoreCase = true) }
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
        if (current.any { it.equals(ACCESSIBILITY_SERVICE_COMPONENT, ignoreCase = true) }) {
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

        for (item in before.items) {
            if (item.satisfied) continue
            val outcome = when (item.key) {
                KEY_INSTALL_PACKAGES ->
                    AdbLoopbackInstaller.runShellCommand("appops set $PACKAGE_NAME REQUEST_INSTALL_PACKAGES allow")
                KEY_ACCESSIBILITY -> repairAccessibility()
                KEY_BATTERY_OPTIMIZATION ->
                    AdbLoopbackInstaller.runShellCommand("dumpsys deviceidle whitelist +$PACKAGE_NAME")
                else -> Result.failure(IllegalStateException("Unknown provisioning item: ${item.key}"))
            }
            if (outcome.isSuccess) {
                repaired.add(item.key)
            } else {
                Logger.w("Provisioning repair failed for ${item.key}: ${outcome.exceptionOrNull()?.message}")
            }
        }

        return Result.success(ProvisioningRepairResult(audit = audit(context), repairedKeys = repaired))
    }

    private suspend fun repairAccessibility(): Result<String> {
        val current = AdbLoopbackInstaller.runShellCommand("settings get secure enabled_accessibility_services")
            .getOrElse { return Result.failure(it) }
        val merged = mergeAccessibilityServices(current)

        AdbLoopbackInstaller.runShellCommand("settings put secure enabled_accessibility_services $merged")
            .onFailure { return Result.failure(it) }

        return AdbLoopbackInstaller.runShellCommand("settings put secure accessibility_enabled 1")
    }
}
