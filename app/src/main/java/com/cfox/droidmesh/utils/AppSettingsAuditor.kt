package com.cfox.droidmesh.utils

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings
import com.cfox.droidmesh.installer.AdbLoopbackInstaller
import com.cfox.droidmesh.installer.AppVersionHelper
import com.cfox.droidmesh.settings.AppSettingRequirement
import com.cfox.droidmesh.settings.AppSettingRequirement.SettingType
import com.cfox.droidmesh.settings.SettingsStore

// ASET-BEHAVE-002..009: audits the OS settings each App Library entry declares it needs, on every
// device that actually has that package installed, and repairs the missing ones over loopback ADB
// when the admin explicitly asks. This is REQ-ADMIN-010's self-provisioning shape (ProvisioningAuditor)
// turned outward at the managed apps. See project/docs/SPEC/app-settings.md.
object AppSettingsAuditor {

    enum class ItemStatus(val key: String) {
        SATISFIED("satisfied"), MISSING("missing"), UNVERIFIED("unverified")
    }

    /**
     * Everything the audit needs to read off the device, captured once so [evaluate] stays a pure
     * function. `verifiedAppOps` is only ever populated by an explicit Repair (ASET-BEHAVE-004).
     */
    data class DeviceSettingsSnapshot(
        val accessibilityEnabled: Boolean = false,
        val enabledAccessibilityServices: List<String> = emptyList(),
        val enabledNotificationListeners: List<String> = emptyList(),
        val batteryExemptPackages: Set<String> = emptySet(),
        val defaultHomeComponent: String? = null,
        val grantedPermissions: Map<String, Set<String>> = emptyMap(),
        val verifiedAppOps: Map<String, Map<String, Boolean>> = emptyMap()
    )

    data class AppSettingItem(
        val packageName: String,
        val appName: String,
        val requirement: AppSettingRequirement,
        val label: String,
        val status: ItemStatus,
        val externalCommand: String
    )

    data class AppSettingsAuditResult(
        val items: List<AppSettingItem>,
        val repairNeeded: Boolean,
        val missingCount: Int,
        val unverifiedCount: Int
    )

    data class FailedRepair(val id: String, val error: String)

    data class AppSettingsRepairResult(
        val audit: AppSettingsAuditResult,
        val repairedIds: List<String>,
        val failed: List<FailedRepair>
    )

    data class AppSettingsSummary(val missing: Int, val unverified: Int, val issues: List<String>)

    data class CaptureResult(
        val packageName: String,
        val requirements: List<AppSettingRequirement>,
        val candidates: Map<SettingType, List<String>>,
        val appOpsVerified: Boolean
    )

    /** ASET-BEHAVE-009: how long a computed summary is reused before the beacon recomputes it. */
    private const val SUMMARY_CACHE_MS = 60_000L

    /** ASET-BEHAVE-009: at most this many missing labels travel on the wire per peer. */
    private const val MAX_ISSUES = 8

    // ASET-BEHAVE-004: app ops for a *foreign* package cannot be read in-process at all
    // (AppOpsService requires UPDATE_APP_OPS_STATS, which a normal app can't hold), so the only
    // read path is `appops get` over loopback ADB. That pops the on-device "Allow debugging from
    // this computer?" prompt, which must never appear on a TV mid-use just because a background
    // audit ticked -- so it happens only inside an explicit Repair, and results live here for the
    // process lifetime. PROV-OPEN-001's ephemeral key means a restart clears this (ASET-OPEN-001).
    private val verifiedAppOps = mutableMapOf<String, MutableMap<String, Boolean>>()

    @Volatile
    private var appOpsVerifiedAtMs: Long? = null

    @Volatile
    private var cachedSummary: AppSettingsSummary? = null

    @Volatile
    private var cachedSummaryAtMs: Long = 0L

    fun appOpsVerifiedAt(): Long? = appOpsVerifiedAtMs

    private fun recordAppOp(packageName: String, op: String, allowed: Boolean) {
        synchronized(verifiedAppOps) {
            verifiedAppOps.getOrPut(packageName) { mutableMapOf() }[op] = allowed
        }
        appOpsVerifiedAtMs = System.currentTimeMillis()
    }

    private fun verifiedAppOpsCopy(): Map<String, Map<String, Boolean>> =
        synchronized(verifiedAppOps) { verifiedAppOps.mapValues { it.value.toMap() } }

    // --- Pure classification (no Android framework calls) ------------------------------------

    /**
     * ASET-BEHAVE-002: one item per requirement of every library entry whose package is actually
     * installed here. An entry for a package this device doesn't have produces nothing -- the
     * requirement applies mesh-wide, but only where the app exists.
     */
    fun evaluate(
        library: Collection<SettingsStore.MeshAppConfig>,
        installedPackages: Set<String>,
        snapshot: DeviceSettingsSnapshot
    ): AppSettingsAuditResult {
        val currentServices = snapshot.enabledAccessibilityServices
            .joinToString(":")
            .ifBlank { null }

        val items = mutableListOf<AppSettingItem>()
        for (entry in library) {
            if (entry.packageName !in installedPackages) continue
            for (requirement in entry.requiredSettings) {
                items.add(
                    AppSettingItem(
                        packageName = entry.packageName,
                        appName = entry.appName,
                        requirement = requirement,
                        label = labelFor(requirement),
                        status = statusOf(entry.packageName, requirement, snapshot),
                        externalCommand = externalCommand(entry.packageName, requirement, currentServices)
                    )
                )
            }
        }

        val missing = items.count { it.status == ItemStatus.MISSING }
        val unverified = items.count { it.status == ItemStatus.UNVERIFIED }
        // ASET-BEHAVE-002: unverified alone never demands repair -- an unread app op is an unknown,
        // not a known-broken setting, and surfacing it as "needs repair" would cry wolf on every
        // process restart.
        return AppSettingsAuditResult(
            items = items,
            repairNeeded = missing > 0,
            missingCount = missing,
            unverifiedCount = unverified
        )
    }

    // ASET-BEHAVE-003/004
    private fun statusOf(
        packageName: String,
        requirement: AppSettingRequirement,
        snapshot: DeviceSettingsSnapshot
    ): ItemStatus = when (requirement.type) {
        // Both halves must hold: the global switch being off means no service runs, however
        // complete enabled_accessibility_services looks. That is exactly the state Master Bedroom
        // GTV came back in on 2026-09-11.
        SettingType.ACCESSIBILITY_SERVICE -> boolStatus(
            snapshot.accessibilityEnabled &&
                snapshot.enabledAccessibilityServices.any {
                    AppSettingRequirement.componentsEqual(it, requirement.value)
                }
        )
        SettingType.NOTIFICATION_LISTENER -> boolStatus(
            snapshot.enabledNotificationListeners.any {
                AppSettingRequirement.componentsEqual(it, requirement.value)
            }
        )
        SettingType.BATTERY_OPTIMIZATION -> boolStatus(packageName in snapshot.batteryExemptPackages)
        SettingType.DEFAULT_HOME -> boolStatus(
            AppSettingRequirement.componentsEqual(snapshot.defaultHomeComponent, requirement.value)
        )
        SettingType.RUNTIME_PERMISSION -> boolStatus(
            snapshot.grantedPermissions[packageName]?.contains(requirement.value) == true
        )
        SettingType.APP_OP -> when (snapshot.verifiedAppOps[packageName]?.get(requirement.value)) {
            true -> ItemStatus.SATISFIED
            false -> ItemStatus.MISSING
            null -> ItemStatus.UNVERIFIED
        }
    }

    private fun boolStatus(satisfied: Boolean) = if (satisfied) ItemStatus.SATISFIED else ItemStatus.MISSING

    private fun labelFor(requirement: AppSettingRequirement): String = when (requirement.type) {
        SettingType.BATTERY_OPTIMIZATION -> requirement.type.label
        SettingType.RUNTIME_PERMISSION ->
            "${requirement.type.label}: ${requirement.value.substringAfterLast('.')}"
        SettingType.APP_OP -> "${requirement.type.label}: ${requirement.value}"
        else -> "${requirement.type.label}: ${requirement.value.substringAfterLast('.')}"
    }

    /**
     * ASET-BEHAVE-005: the exact loopback-ADB command(s) that fix one requirement. Every string
     * produced here is independently re-validated by
     * [AdbLoopbackInstaller.isAllowedShellCommand] (INST-BEHAVE-015) before it runs.
     */
    fun shellCommands(
        packageName: String,
        requirement: AppSettingRequirement,
        currentAccessibilityServices: String?
    ): List<String> = when (requirement.type) {
        SettingType.ACCESSIBILITY_SERVICE -> listOf(
            "settings put secure enabled_accessibility_services " +
                mergeAccessibilityServices(currentAccessibilityServices, requirement.value),
            "settings put secure accessibility_enabled 1"
        )
        SettingType.NOTIFICATION_LISTENER ->
            listOf("cmd notification allow_listener ${requirement.value}")
        SettingType.BATTERY_OPTIMIZATION ->
            listOf("dumpsys deviceidle whitelist +$packageName")
        SettingType.APP_OP ->
            listOf("appops set $packageName ${requirement.value} allow")
        SettingType.DEFAULT_HOME ->
            listOf("pm set-home-activity ${requirement.value}")
        SettingType.RUNTIME_PERMISSION ->
            listOf("pm grant $packageName ${requirement.value}")
    }

    /** The same fix as a copy-pasteable workstation command, for the admin who'd rather not use ADB in-app. */
    fun externalCommand(
        packageName: String,
        requirement: AppSettingRequirement,
        currentAccessibilityServices: String?
    ): String = shellCommands(packageName, requirement, currentAccessibilityServices)
        .joinToString(" && ") { "adb shell $it" }

    /**
     * ASET-BEHAVE-005: the commands that fix one missing item, given the live read of
     * `enabled_accessibility_services` ([accessibilityRead] is null for every other type, which
     * needs no read).
     *
     * A *failed* read propagates as a failure instead of degrading to "nothing enabled".
     * [mergeAccessibilityServices] cannot tell those apart -- both reach it as a null/blank string
     * -- so merging against a failed read would rewrite the setting to hold the new component and
     * nothing else, silently disabling every other accessibility service on the device, including
     * DroidMesh's own AutoInstallService. ProvisioningAuditor takes the same care on the same read.
     */
    fun repairCommands(
        packageName: String,
        requirement: AppSettingRequirement,
        accessibilityRead: Result<String>?
    ): Result<List<String>> {
        // Defense in depth: re-assert here, at assembly time, that this requirement actually
        // belongs to [packageName]. AppSettingRequirement.create() already guarantees it, but that
        // happened at a different point in time -- a later refactor that paired one entry's
        // packageName with another entry's requirement would otherwise emit a well-formed
        // cross-package command that the shell allowlist cannot tell from a legitimate one, since
        // the three component commands interpolate only requirement.value.
        if (!ownsRequirement(packageName, requirement)) {
            return Result.failure(
                IllegalArgumentException("Requirement ${requirement.id} does not belong to $packageName")
            )
        }
        if (requirement.type != SettingType.ACCESSIBILITY_SERVICE) {
            return Result.success(shellCommands(packageName, requirement, null))
        }
        val read = accessibilityRead ?: return Result.failure(
            IllegalStateException("Accessibility repair requires a live read of enabled_accessibility_services")
        )
        return read.map { shellCommands(packageName, requirement, it) }
    }

    /**
     * True when [requirement] is one [packageName] may be repaired with. Component types must name
     * a component owned by the package; the other three interpolate the package itself, so only its
     * charset matters.
     */
    fun ownsRequirement(packageName: String, requirement: AppSettingRequirement): Boolean =
        if (requirement.type.isComponent) {
            AppSettingRequirement.normalizeComponent(packageName, requirement.value) != null
        } else {
            AppSettingRequirement.isValidPackageName(packageName)
        }

    fun appOpsReadCommand(packageName: String, op: String): String = "appops get $packageName $op"

    /**
     * ASET-BEHAVE-005: appends [component] to the colon-joined enabled_accessibility_services
     * value instead of overwriting it, so every other enabled service survives the repair.
     * Idempotent, and short/full component forms compare equal. `existing` may be null, blank, or
     * the literal "null" (`settings get` prints that for an unset key) -- all mean "nothing enabled".
     */
    fun mergeAccessibilityServices(existing: String?, component: String): String {
        val normalized = existing?.trim()
        val current = if (normalized.isNullOrBlank() || normalized.equals("null", ignoreCase = true)) {
            emptyList()
        } else {
            normalized.split(":").map { it.trim() }.filter { it.isNotEmpty() }
        }
        if (current.any { AppSettingRequirement.componentsEqual(it, component) }) {
            // Already enabled -- return the device's own strings untouched, order preserved.
            return current.joinToString(":")
        }
        return (current + component).joinToString(":")
    }

    /**
     * ASET-BEHAVE-004: an op counts as allowed only when a line of `appops get` output is that
     * op's own `allow`. `default`, `No operations.`, an error, or a *different* op's allow line
     * are all "not allowed" -- the last of those matters because `appops get` on a package with
     * one granted op prints that op regardless of which one was asked for.
     */
    fun parseAppOpsOutput(op: String, output: String): Boolean =
        output.lineSequence().any { line ->
            // Real devices prefix the uid-scoped mode line with "Uid mode: ".
            val trimmed = line.trim().removePrefix("Uid mode:").trim()
            trimmed.startsWith("$op:") &&
                trimmed.removePrefix("$op:").trim().startsWith("allow")
        }

    /**
     * ASET-BEHAVE-006: what [packageName] currently has on this device, as requirements. Anything
     * belonging to another package is dropped by [AppSettingRequirement.create], so a capture on a
     * healthy device can't accidentally pull in a neighbour app's settings.
     */
    fun captureRequirements(
        packageName: String,
        snapshot: DeviceSettingsSnapshot
    ): List<AppSettingRequirement> {
        val out = mutableListOf<AppSettingRequirement>()

        // Listed but globally switched off isn't actually active, so it isn't captured.
        if (snapshot.accessibilityEnabled) {
            snapshot.enabledAccessibilityServices.forEach { component ->
                AppSettingRequirement.create(packageName, SettingType.ACCESSIBILITY_SERVICE, component)
                    ?.let { out.add(it) }
            }
        }
        snapshot.enabledNotificationListeners.forEach { component ->
            AppSettingRequirement.create(packageName, SettingType.NOTIFICATION_LISTENER, component)
                ?.let { out.add(it) }
        }
        if (packageName in snapshot.batteryExemptPackages) {
            AppSettingRequirement.create(packageName, SettingType.BATTERY_OPTIMIZATION, "")
                ?.let { out.add(it) }
        }
        AppSettingRequirement.create(packageName, SettingType.DEFAULT_HOME, snapshot.defaultHomeComponent)
            ?.let { out.add(it) }
        snapshot.grantedPermissions[packageName]?.sorted()?.forEach { permission ->
            AppSettingRequirement.create(packageName, SettingType.RUNTIME_PERMISSION, permission)
                ?.let { out.add(it) }
        }
        // Only ops actually verified allow this process -- never a guess.
        snapshot.verifiedAppOps[packageName]?.filterValues { it }?.keys?.sorted()?.forEach { op ->
            AppSettingRequirement.create(packageName, SettingType.APP_OP, op)?.let { out.add(it) }
        }
        return out.distinct()
    }

    /** ASET-BEHAVE-009: the compact form that rides the mesh beacon. */
    fun summarize(result: AppSettingsAuditResult): AppSettingsSummary = AppSettingsSummary(
        missing = result.missingCount,
        unverified = result.unverifiedCount,
        issues = result.items
            .filter { it.status == ItemStatus.MISSING }
            .map { "${it.appName}: ${it.label}" }
            .take(MAX_ISSUES)
    )

    // --- Android-touching entry points --------------------------------------------------------

    /** ASET-BEHAVE-002: the local mesh's library entries whose package is installed here. */
    private fun installedLibraryEntries(context: Context): List<SettingsStore.MeshAppConfig> = try {
        val meshId = SettingsStore.getLocalMeshId(context)
        SettingsStore.getMeshAppLibrary(context, meshId).values
            .filter { it.requiredSettings.isNotEmpty() }
            .filter { AppVersionHelper.getInstalledVersion(context, it.packageName).isInstalled }
    } catch (e: Exception) {
        Logger.w("App settings audit could not read the App Library: ${e.message}")
        emptyList()
    }

    /** ASET-BEHAVE-003: reads every in-process-readable setting. Never opens an ADB session. */
    fun snapshot(context: Context, packages: Set<String>): DeviceSettingsSnapshot {
        val resolver = context.contentResolver
        val accessibilityEnabled = try {
            Settings.Secure.getInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        } catch (e: Exception) {
            false
        }
        val accessibilityServices = readColonList {
            Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        }
        val notificationListeners = readColonList {
            Settings.Secure.getString(resolver, "enabled_notification_listeners")
        }

        val powerManager = try {
            context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        } catch (e: Exception) {
            null
        }
        val batteryExempt = packages.filterTo(mutableSetOf()) { pkg ->
            try {
                powerManager?.isIgnoringBatteryOptimizations(pkg) == true
            } catch (e: Exception) {
                false
            }
        }

        val granted = packages.associateWith { grantedPermissionsFor(context, it) }

        return DeviceSettingsSnapshot(
            accessibilityEnabled = accessibilityEnabled,
            enabledAccessibilityServices = accessibilityServices,
            enabledNotificationListeners = notificationListeners,
            batteryExemptPackages = batteryExempt,
            defaultHomeComponent = defaultHomeComponent(context),
            grantedPermissions = granted,
            verifiedAppOps = verifiedAppOpsCopy()
        )
    }

    private inline fun readColonList(read: () -> String?): List<String> = try {
        read()?.split(":")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    private fun grantedPermissionsFor(context: Context, packageName: String): Set<String> = try {
        val info = context.packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        val names = info.requestedPermissions
        val flags = info.requestedPermissionsFlags
        if (names == null || flags == null) {
            emptySet()
        } else {
            names.filterIndexed { i, _ ->
                i < flags.size && (flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
            }.toSet()
        }
    } catch (e: Exception) {
        emptySet()
    }

    private fun defaultHomeComponent(context: Context): String? = try {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        context.packageManager
            .resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo
            ?.let { "${it.packageName}/${it.name}" }
    } catch (e: Exception) {
        null
    }

    /** ASET-BEHAVE-002: the live audit behind GET /api/system/app-settings. */
    fun audit(context: Context): AppSettingsAuditResult {
        val entries = installedLibraryEntries(context)
        if (entries.isEmpty()) {
            return AppSettingsAuditResult(emptyList(), repairNeeded = false, missingCount = 0, unverifiedCount = 0)
        }
        val packages = entries.mapTo(mutableSetOf()) { it.packageName }
        return evaluate(entries, packages, snapshot(context, packages))
    }

    /** ASET-BEHAVE-009: audit result reused for up to a minute, so the beacon loop stays cheap. */
    fun cachedSummary(context: Context): AppSettingsSummary {
        val now = System.currentTimeMillis()
        val cached = cachedSummary
        if (cached != null && now - cachedSummaryAtMs < SUMMARY_CACHE_MS) return cached
        val summary = try {
            summarize(audit(context))
        } catch (e: Exception) {
            Logger.w("App settings summary failed: ${e.message}")
            AppSettingsSummary(0, 0, emptyList())
        }
        cachedSummary = summary
        cachedSummaryAtMs = now
        return summary
    }

    private fun invalidateSummary() {
        cachedSummaryAtMs = 0L
    }

    /**
     * ASET-BEHAVE-005: verify app ops over ADB, apply every missing item's commands, re-verify,
     * re-audit. Fails fast before opening any socket when ADB is off. One item failing never
     * aborts the rest -- the caller gets `repairedIds` and `failed` and can see what's still wrong.
     */
    suspend fun repair(context: Context): Result<AppSettingsRepairResult> {
        if (!AdbHelper.isAdbEnabled(context)) {
            return Result.failure(
                IllegalStateException("ADB is not enabled — enable USB/network debugging in Developer Options first")
            )
        }

        val entries = installedLibraryEntries(context)

        // Read the app ops we can't see in-process, before deciding what's missing.
        verifyAppOps(entries)

        val before = audit(context)
        val repaired = mutableListOf<String>()
        val failed = mutableListOf<FailedRepair>()

        for (item in before.items) {
            if (item.status != ItemStatus.MISSING) continue
            // Re-read the live value before each accessibility merge: an earlier item in this same
            // loop may already have changed it, and merging against a stale value would drop
            // whatever it just enabled.
            val accessibilityRead = if (item.requirement.type == SettingType.ACCESSIBILITY_SERVICE) {
                AdbLoopbackInstaller.runShellCommand("settings get secure enabled_accessibility_services")
            } else {
                null
            }

            var error: String? = null
            // A read failure fails this item rather than merging against an unknown value.
            val plan = repairCommands(item.packageName, item.requirement, accessibilityRead)
            val commands = plan.getOrNull()
            if (commands == null) {
                error = plan.exceptionOrNull()?.message ?: "Could not determine the repair commands"
            } else {
                for (command in commands) {
                    val outcome = AdbLoopbackInstaller.runShellCommand(command)
                    if (outcome.isFailure) {
                        error = outcome.exceptionOrNull()?.message ?: "Command failed: $command"
                        break
                    }
                }
            }

            if (error == null) {
                repaired.add(item.requirement.id)
            } else {
                Logger.w("App settings repair failed for ${item.packageName} ${item.requirement.id}: $error")
                failed.add(FailedRepair(item.requirement.id, error))
            }
        }

        // An `appops set` reporting success isn't proof the mode took -- re-read it.
        verifyAppOps(entries)
        invalidateSummary()

        return Result.success(
            AppSettingsRepairResult(audit = audit(context), repairedIds = repaired, failed = failed)
        )
    }

    private suspend fun verifyAppOps(entries: List<SettingsStore.MeshAppConfig>) {
        for (entry in entries) {
            for (requirement in entry.requiredSettings) {
                if (requirement.type != SettingType.APP_OP) continue
                val outcome = AdbLoopbackInstaller.runShellCommand(
                    appOpsReadCommand(entry.packageName, requirement.value)
                )
                outcome
                    .onSuccess { recordAppOp(entry.packageName, requirement.value, parseAppOpsOutput(requirement.value, it)) }
                    .onFailure { Logger.w("Could not read app op ${requirement.value} for ${entry.packageName}: ${it.message}") }
            }
        }
    }

    /** ASET-BEHAVE-006: snapshot this device's current state for [packageName], plus what it could require. */
    fun capture(context: Context, packageName: String): CaptureResult {
        val snapshot = snapshot(context, setOf(packageName))
        return CaptureResult(
            packageName = packageName,
            requirements = captureRequirements(packageName, snapshot),
            candidates = candidatesFor(context, packageName),
            appOpsVerified = verifiedAppOpsCopy().containsKey(packageName)
        )
    }

    /** What [packageName] actually declares, so the manual editor offers real values, not free text. */
    private fun candidatesFor(context: Context, packageName: String): Map<SettingType, List<String>> {
        val pm = context.packageManager
        fun services(action: String): List<String> = try {
            pm.queryIntentServices(Intent(action), PackageManager.GET_META_DATA)
                .filter { it.serviceInfo?.packageName == packageName }
                .map { "${it.serviceInfo.packageName}/${it.serviceInfo.name}" }
        } catch (e: Exception) {
            emptyList()
        }

        val homes = try {
            pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY
            )
                .filter { it.activityInfo?.packageName == packageName }
                .map { "${it.activityInfo.packageName}/${it.activityInfo.name}" }
        } catch (e: Exception) {
            emptyList()
        }

        val permissions = try {
            pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions?.toList().orEmpty()
        } catch (e: Exception) {
            emptyList()
        }

        return mapOf(
            SettingType.ACCESSIBILITY_SERVICE to services("android.accessibilityservice.AccessibilityService"),
            SettingType.NOTIFICATION_LISTENER to services("android.service.notification.NotificationListenerService"),
            SettingType.DEFAULT_HOME to homes,
            SettingType.RUNTIME_PERMISSION to permissions.sorted(),
            SettingType.APP_OP to AppSettingRequirement.ALLOWED_APP_OPS.sorted()
        )
    }
}
