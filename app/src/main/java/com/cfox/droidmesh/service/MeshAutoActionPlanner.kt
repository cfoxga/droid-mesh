package com.cfox.droidmesh.service

import com.cfox.droidmesh.api.ReleaseInfo
import com.cfox.droidmesh.api.ReleaseSelector
import com.cfox.droidmesh.installer.AppVersionHelper
import com.cfox.droidmesh.settings.SettingsStore.MeshAppConfig

/**
 * Pure decision logic for UpdaterForegroundService's hourly mesh app library loop
 * (FLT-BEHAVE-005 auto-install, FLT-BEHAVE-006 auto-update) — no Android dependencies,
 * so it's unit-testable without mocking Service/Context internals. The Service itself
 * is just I/O glue: call plan(), then execute side effects (download+install,
 * checkVersion+startUpdateAsync).
 */
object MeshAutoActionPlanner {

    data class Plan(
        val installs: List<MeshAppConfig>,
        val updateChecks: List<MeshAppConfig>
    )

    fun plan(
        library: Map<String, MeshAppConfig>,
        installedPackages: Set<String>,
        isExcluded: (String) -> Boolean
    ): Plan {
        // FLT-BEHAVE-005 / APP-BEHAVE-006: manageability is URL-driven. `isSideloaded` is
        // descriptive origin metadata (a package-prefix guess in AppVersionHelper), never a gate —
        // gating installs on it silently dropped any admin-configured entry outside that prefix
        // list while the UI still offered it an enabled Auto Install checkbox.
        val installs = library.values.filter { cfg ->
            cfg.managed &&
                cfg.autoInstall &&
                cfg.downloadUrl.isNotBlank() &&
                cfg.packageName !in installedPackages &&
                !isExcluded(cfg.packageName)
        }
        val updateChecks = library.values.filter { cfg ->
            cfg.managed &&
                cfg.autoUpdate &&
                cfg.downloadUrl.isNotBlank() &&
                cfg.packageName in installedPackages
        }
        return Plan(installs = installs, updateChecks = updateChecks)
    }

    /** What auto-update should do with one App Library entry on this pass. */
    sealed class UpdateAction {
        data class Install(val release: ReleaseInfo) : UpdateAction()
        data class Skip(val reason: String) : UpdateAction()
    }

    /**
     * FLT-BEHAVE-007: decide which release auto-update should install for one App Library entry.
     *
     * The pin has to be resolved *before* the up-to-date comparison, not after. Comparing against
     * `releases.first()` and then installing `releases.first()` ignored `targetVersion` on both
     * legs, so an entry pinned to an older-than-newest release silently got the newest build.
     */
    fun decideUpdate(
        cfg: MeshAppConfig,
        installedVersionName: String?,
        releases: List<ReleaseInfo>
    ): UpdateAction {
        if (releases.isEmpty()) {
            return UpdateAction.Skip("no releases available for ${cfg.packageName}")
        }
        val pin = cfg.targetVersion.trim()
        val target = ReleaseSelector.selectRelease(releases, pin)
            ?: return UpdateAction.Skip(
                "pinned target '$pin' matches no published release for ${cfg.packageName}"
            )

        if (AppVersionHelper.isUpdateAvailable(installedVersionName, target.tagName)) {
            return UpdateAction.Install(target)
        }
        // Not an upgrade. Distinguish "already there" from "the pin is behind what is installed" —
        // the latter is a real misconfiguration worth naming, and Android rejects downgrades
        // outright, so attempting one every hour would fail forever instead of converging.
        val installed = installedVersionName?.trim().orEmpty()
        val isPinBehindInstalled = installed.isNotEmpty() &&
            !AppVersionHelper.isUpdateAvailable(installedVersionName, target.tagName) &&
            AppVersionHelper.isVersionMismatch(installedVersionName, target.tagName)
        return if (isPinBehindInstalled) {
            UpdateAction.Skip(
                "refusing to downgrade ${cfg.packageName} from '$installed' to pinned '${target.tagName}'"
            )
        } else {
            UpdateAction.Skip("${cfg.packageName} is already on '${target.tagName}'")
        }
    }
}
