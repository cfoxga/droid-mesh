package com.cfox.droidmesh.service

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
}
