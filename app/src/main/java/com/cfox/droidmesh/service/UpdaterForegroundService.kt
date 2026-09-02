package com.cfox.droidmesh.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.cfox.droidmesh.MainActivity
import com.cfox.droidmesh.R
import com.cfox.droidmesh.mesh.MeshDiscoveryManager
import com.cfox.droidmesh.server.LocalHttpServer
import com.cfox.droidmesh.server.UpdateCoordinator
import com.cfox.droidmesh.installer.AppVersionHelper
import com.cfox.droidmesh.settings.SettingsStore
import com.cfox.droidmesh.utils.Logger
import com.cfox.droidmesh.utils.ProvisioningAuditor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class UpdaterForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "droid_mesh_updater_service_channel"
        const val NOTIFICATION_ID = 1001
        const val PORT = 2325

        const val ACTION_START = "com.cfox.droidmesh.action.START"
        const val ACTION_STOP = "com.cfox.droidmesh.action.STOP"

        // How often the mesh app auto-install loop checks for missing managed apps.
        const val AUTO_INSTALL_CHECK_MS = 60 * 60 * 1000L // 1 hour

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var activeCoordinator: UpdateCoordinator? = null
            private set

        // Dedicated coordinator for self-update (DroidMesh updating its own APK), kept
        // separate from activeCoordinator so a managed-app update in progress never shares
        // statusFlow/updateMutex with a concurrent self-update check or trigger.
        @Volatile
        var activeSelfUpdateCoordinator: UpdateCoordinator? = null
            private set

        @Volatile
        var activeMeshManager: MeshDiscoveryManager? = null
            private set

        fun startService(context: Context) {
            val intent = Intent(context, UpdaterForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var httpServer: LocalHttpServer? = null
    private var updateCoordinator: UpdateCoordinator? = null
    private var selfUpdateCoordinator: UpdateCoordinator? = null
    private var meshDiscoveryManager: MeshDiscoveryManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var meshAutoInstallJob: Job? = null

    // FLT-BEHAVE-008: lets a libraryChanged config event (a new pinned targetVersion,
    // autoUpdate/autoInstall flipped) wake the hourly mesh auto-action loop immediately instead
    // of leaving it asleep for the rest of AUTO_INSTALL_CHECK_MS.
    private val meshAutoActionTicker = WakeableTicker(AUTO_INSTALL_CHECK_MS)

    override fun onCreate() {
        super.onCreate()
        Logger.i("UpdaterForegroundService onCreate")
        createNotificationChannel()

        val coordinator = UpdateCoordinator(applicationContext)
        updateCoordinator = coordinator
        activeCoordinator = coordinator

        val selfCoordinator = UpdateCoordinator(applicationContext)
        selfUpdateCoordinator = selfCoordinator
        activeSelfUpdateCoordinator = selfCoordinator

        val mesh = MeshDiscoveryManager(applicationContext, coordinator, serviceScope)
        meshDiscoveryManager = mesh
        activeMeshManager = mesh
        mesh.start()

        // PROV-BEHAVE-001: audit the three OS-level grants DroidMesh depends on outside its own
        // package on every service start (covers real boot via BootReceiver and manual app
        // launch alike). GET /api/system/provisioning re-audits live on every call — this pass
        // only logs, so a cleared grant is visible in /logs without waiting for a poll.
        val provisioningAudit = ProvisioningAuditor.audit(applicationContext)
        if (provisioningAudit.repairNeeded) {
            val missing = provisioningAudit.items.filter { !it.satisfied }.joinToString(", ") { it.label }
            Logger.w("Provisioning audit: repair needed — missing: $missing")
        } else {
            Logger.i("Provisioning audit: all grants satisfied")
        }

        manageHttpServer()
        SettingsStore.addConfigChangeListener(configChangeListener)

        // Collect coordinator status updates to update notification & wake lock
        serviceScope.launch {
            coordinator.statusFlow.collect { status ->
                updateNotification(status.message)
                handleWakeLockForState(status.state)
            }
        }
        serviceScope.launch {
            selfCoordinator.statusFlow.collect { status ->
                updateNotification(status.message)
                handleWakeLockForState(status.state)
            }
        }
    }

    private val configChangeListener = SettingsStore.OnConfigChangeListener { result ->
        serviceScope.launch(Dispatchers.Main) {
            Logger.i(
                "Config change received in UpdaterForegroundService: " +
                    "portChanged=${result.portChanged} libraryChanged=${result.libraryChanged}"
            )
            if (result.portChanged) {
                manageHttpServer()
                val currentPort = SettingsStore.getWebServerPort(applicationContext)
                updateNotification("Listening on port $currentPort")
            }
            if (result.libraryChanged) {
                // FLT-BEHAVE-008: an App Library edit (pin a new targetVersion, flip
                // autoUpdate/autoInstall) should not have to wait out the rest of the current
                // hour before the loop re-reads it — wake it now.
                Logger.i("Mesh App Library changed — waking auto-action loop early")
                meshAutoActionTicker.wake()
            }
        }
    }

    // Always runs — the WebView shell (MainActivity) loads its entire UI from this server, so
    // it's load-bearing app plumbing now, not an optional remote-admin feature (UI-BEHAVE-005).
    fun manageHttpServer() {
        val targetPort = SettingsStore.getWebServerPort(applicationContext)
        val currentServer = httpServer

        if (currentServer != null && currentServer.isAlive && currentServer.activePort == targetPort) {
            return
        }

        val coordinator = updateCoordinator ?: return
        val selfCoordinator = selfUpdateCoordinator ?: return
        val mesh = meshDiscoveryManager ?: return
        val newServer = LocalHttpServer(applicationContext, coordinator, mesh, targetPort, selfCoordinator)
        try {
            newServer.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            Logger.i("Local HTTP server successfully started on port $targetPort (non-daemon)")
        } catch (e: Exception) {
            // Do NOT touch currentServer/httpServer on failure — the WebView shell's UI is this
            // server's response, so a bind failure on the new port must leave whatever was
            // previously serving (if anything) untouched rather than leave the app unreachable.
            Logger.e("Failed to bind LocalHttpServer to port $targetPort; keeping prior server (if any) running", e)
            if (currentServer == null || !currentServer.isAlive) {
                // Nothing was serving before either (first boot, or the old one had already died) —
                // fall back to the last-known-good port so the persisted setting matches reality
                // and the next reconciliation pass doesn't retry the same broken port forever.
                SettingsStore.setWebServerPort(applicationContext, currentServer?.activePort ?: PORT)
            }
            return
        }

        // New server is confirmed alive — safe to retire the old one now.
        if (currentServer != null) {
            try { currentServer.stop() } catch (_: Exception) {}
        }
        httpServer = newServer
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        Logger.i("UpdaterForegroundService onStartCommand action=$action")

        val currentPort = SettingsStore.getWebServerPort(applicationContext)
        startForeground(NOTIFICATION_ID, buildNotification("Listening on port $currentPort"))
        isRunning = true

        manageHttpServer()
        manageMeshAutoActionLoop()

        when (action) {
            ACTION_STOP -> {
                stopSelf()
            }
        }

        return START_STICKY
    }

    /**
     * Periodically evaluates the mesh app library against MeshAutoActionPlanner.plan()
     * (FLT-BEHAVE-005 auto-install, FLT-BEHAVE-006 auto-update) and executes whatever it
     * returns: for each install candidate, downloads and dispatches install via
     * PackageInstallerDispatcher; for each update-check candidate (an installed, managed,
     * autoUpdate app), checks the coordinator for a newer release and triggers the same
     * download/install pipeline a manual `/update?package=` call would use. There is no
     * singleton "one managed app" constraint — any number of entries can be acted on in the
     * same pass. Runs once on startup then every AUTO_INSTALL_CHECK_MS — or immediately whenever
     * meshAutoActionTicker.wake() fires early on a libraryChanged config event (FLT-BEHAVE-008).
     */
    private fun manageMeshAutoActionLoop() {
        if (meshAutoInstallJob?.isActive == true) return
        Logger.i("Mesh app auto-action loop starting (interval ${AUTO_INSTALL_CHECK_MS / 60_000}min)")
        meshAutoInstallJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val localMeshId = SettingsStore.getLocalMeshId(applicationContext)
                    val library = SettingsStore.getMeshAppLibrary(applicationContext, localMeshId)
                    val installedPackages = com.cfox.droidmesh.installer.AppVersionHelper
                        .getUserInstalledApps(applicationContext)
                        .map { it.packageName }
                        .toSet()

                    val plan = MeshAutoActionPlanner.plan(
                        library = library,
                        installedPackages = installedPackages,
                        isExcluded = { pkg ->
                            com.cfox.droidmesh.installer.AppVersionHelper.isExcludedAppPackage(pkg, applicationContext)
                        }
                    )

                    for (cfg in plan.installs) {
                        if (!isActive) break
                        val pkg = cfg.packageName
                        val downloadUrl = cfg.downloadUrl.trim()
                        Logger.i("Mesh auto-install: $pkg (${cfg.appName}) is missing — resolving release from $downloadUrl")
                        try {
                            // UPD-BEHAVE-012: downloadUrl is a *releases page*, not an APK. Resolve
                            // it to the concrete asset for the pinned targetVersion first — feeding
                            // the raw URL to the downloader saved the HTML page as a .apk and
                            // dispatched that to the package installer.
                            val releaseResult = updateCoordinator?.resolveTargetRelease(downloadUrl, cfg.targetVersion)
                                ?: Result.failure(IllegalStateException("Update coordinator unavailable"))
                            if (releaseResult.isFailure) {
                                Logger.w("Mesh auto-install: could not resolve a release for $pkg: ${releaseResult.exceptionOrNull()?.message}")
                                continue
                            }
                            val release = releaseResult.getOrThrow()
                            val fileName = release.apkFileName.ifBlank { "$pkg-${release.tagName}.apk" }
                            Logger.i("Mesh auto-install: $pkg resolved to ${release.tagName} (${release.apkAssetUrl})")
                            val downloader = com.cfox.droidmesh.downloader.ApkDownloader(applicationContext)
                            val apkResult = downloader.downloadApk(release.apkAssetUrl, fileName)
                            if (apkResult.isSuccess) {
                                val apkFile = apkResult.getOrThrow()
                                com.cfox.droidmesh.service.AutoInstallService.pendingInstallPackage = pkg
                                com.cfox.droidmesh.installer.PackageInstallerDispatcher
                                    .dispatchInstall(applicationContext, apkFile)
                                Logger.i("Mesh auto-install: dispatched installer for ${cfg.appName}")
                            } else {
                                Logger.w("Mesh auto-install: download failed for $pkg: ${apkResult.exceptionOrNull()?.message}")
                            }
                        } catch (e: Exception) {
                            Logger.e("Mesh auto-install error for $pkg", e)
                        }
                    }

                    for (cfg in plan.updateChecks) {
                        if (!isActive) break
                        val pkg = cfg.packageName
                        val downloadUrl = cfg.downloadUrl.trim()
                        try {
                            // FLT-BEHAVE-007: resolve the entry's pinned targetVersion before
                            // deciding *and* before installing. This previously compared against
                            // releases.first() and then called startUpdateAsync(pkg, downloadUrl),
                            // which also installs releases.first() — so a pinned older release was
                            // ignored on both legs and the node silently got the newest build.
                            val releasesResult = updateCoordinator?.fetchAvailableReleases(downloadUrl)
                                ?: Result.failure(IllegalStateException("Update coordinator unavailable"))
                            if (releasesResult.isFailure) {
                                Logger.w("Mesh auto-update: release fetch failed for $pkg: ${releasesResult.exceptionOrNull()?.message}")
                                continue
                            }
                            val installed = AppVersionHelper.getInstalledVersion(applicationContext, pkg)
                            when (
                                val action = MeshAutoActionPlanner.decideUpdate(
                                    cfg, installed.versionName, releasesResult.getOrThrow()
                                )
                            ) {
                                is MeshAutoActionPlanner.UpdateAction.Install -> {
                                    Logger.i("Mesh auto-update: $pkg (${cfg.appName}) ${installed.versionName} -> ${action.release.tagName} — starting")
                                    updateCoordinator?.startUpdateForRelease(pkg, action.release, force = false)
                                }
                                is MeshAutoActionPlanner.UpdateAction.Skip ->
                                    Logger.i("Mesh auto-update: skipping $pkg — ${action.reason}")
                            }
                        } catch (e: Exception) {
                            Logger.e("Mesh auto-update error for $pkg", e)
                        }
                    }
                } catch (e: Exception) {
                    Logger.e("Mesh auto-action loop error", e)
                }
                meshAutoActionTicker.awaitNextTick()
            }
        }
    }

    private fun handleWakeLockForState(state: String) {
        if (state == "DOWNLOADING" || state == "INSTALLING") {
            acquireWakeLock()
        } else {
            releaseWakeLock()
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DroidMesh::UpdateWakeLock")
        }
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(10 * 60 * 1000L) // 10 minute timeout
            Logger.i("WakeLock acquired for update sequence")
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
            Logger.i("WakeLock released")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(statusText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DroidMesh Updater (:2325)")
            .setContentText(statusText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(statusText: String) {
        val notification = buildNotification(statusText)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Logger.i("UpdaterForegroundService onDestroy")
        isRunning = false
        activeCoordinator = null
        activeSelfUpdateCoordinator = null
        releaseWakeLock()

        try {
            meshDiscoveryManager?.stop()
            meshDiscoveryManager = null
            activeMeshManager = null
            httpServer?.stop()
            Logger.i("HTTP server stopped")
        } catch (e: Exception) {
            Logger.e("Error stopping HTTP server / mesh manager", e)
        }

        SettingsStore.removeConfigChangeListener(configChangeListener)
        serviceScope.cancel()
    }
}
