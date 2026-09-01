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
        val installs = library.values.filter { cfg ->
            cfg.managed &&
                cfg.autoInstall &&
                cfg.isSideloaded &&
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
