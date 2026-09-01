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
import com.cfox.droidmesh.settings.SettingsStore
import com.cfox.droidmesh.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class UpdaterForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "droid_mesh_updater_service_channel"
        const val NOTIFICATION_ID = 1001
        const val PORT = 2325

        const val ACTION_START = "com.cfox.droidmesh.action.START"
        const val ACTION_STOP = "com.cfox.droidmesh.action.STOP"
        const val ACTION_TRIGGER_UPDATE = "com.cfox.droidmesh.action.TRIGGER_UPDATE"

        // How often the mesh app auto-install loop checks for missing managed apps.
        const val AUTO_INSTALL_CHECK_MS = 60 * 60 * 1000L // 1 hour

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var activeCoordinator: UpdateCoordinator? = null
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
    private var meshDiscoveryManager: MeshDiscoveryManager? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var meshAutoInstallJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Logger.i("UpdaterForegroundService onCreate")
        createNotificationChannel()

        val coordinator = UpdateCoordinator(applicationContext)
        updateCoordinator = coordinator
        activeCoordinator = coordinator

        val mesh = MeshDiscoveryManager(applicationContext, coordinator, serviceScope)
        meshDiscoveryManager = mesh
        activeMeshManager = mesh
        mesh.start()

        manageHttpServer()
        SettingsStore.addConfigChangeListener(configChangeListener)

        // Collect coordinator status updates to update notification & wake lock
        serviceScope.launch {
            coordinator.statusFlow.collect { status ->
                updateNotification(status.message)
                handleWakeLockForState(status.state)
            }
        }
    }

    private val configChangeListener = SettingsStore.OnConfigChangeListener { result ->
        serviceScope.launch(Dispatchers.Main) {
            Logger.i("Config change received in UpdaterForegroundService: portChanged=${result.portChanged}, webServerToggled=${result.webServerToggled}")
            if (result.portChanged || result.webServerToggled) {
                manageHttpServer()
                val currentPort = SettingsStore.getWebServerPort(applicationContext)
                updateNotification("Listening on port $currentPort")
            }
        }
    }

    fun manageHttpServer() {
        val enabled = SettingsStore.isWebServerEnabled(applicationContext)
        val targetPort = SettingsStore.getWebServerPort(applicationContext)
        val currentServer = httpServer

        if (!enabled) {
            if (currentServer != null) {
                Logger.i("Stopping Local HTTP Server (disabled in settings)")
                try { currentServer.stop() } catch (_: Exception) {}
                httpServer = null
            }
            return
        }

        if (currentServer != null && currentServer.isAlive && currentServer.activePort == targetPort) {
            return
        }


        if (currentServer != null) {
            try { currentServer.stop() } catch (_: Exception) {}
            httpServer = null
        }

        val coordinator = updateCoordinator ?: return
        val mesh = meshDiscoveryManager ?: return
        val server = LocalHttpServer(applicationContext, coordinator, mesh, targetPort)
        httpServer = server
        try {
            server.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            Logger.i("Local HTTP server successfully started on port $targetPort (non-daemon)")
        } catch (e: Exception) {
            Logger.e("Failed to bind LocalHttpServer to port $targetPort", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        Logger.i("UpdaterForegroundService onStartCommand action=$action")

        val currentPort = SettingsStore.getWebServerPort(applicationContext)
        startForeground(NOTIFICATION_ID, buildNotification("Listening on port $currentPort"))
        isRunning = true

        manageHttpServer()
        manageAutoInstallLoop()

        when (action) {
            ACTION_TRIGGER_UPDATE -> {
                val force = intent?.getBooleanExtra("force", false) ?: false
                // No explicit package on this intent — resolve the single Managed
                // (downloadUrl-configured) App Library entry for the local mesh, same as the
                // HTTP /update endpoint does when ?package= is omitted.
                val localMeshId = SettingsStore.getLocalMeshId(applicationContext)
                val library = SettingsStore.getMeshAppLibrary(applicationContext, localMeshId)
                val target = library.values.filter { it.managed && it.downloadUrl.isNotBlank() }.singleOrNull()
                if (target != null) {
                    updateCoordinator?.startUpdateAsync(target.packageName, target.downloadUrl, force = force)
                } else {
                    Logger.w("ACTION_TRIGGER_UPDATE: no single Managed app configured — ignoring")
                }
            }
            ACTION_STOP -> {
                stopSelf()
            }
        }

        return START_STICKY
    }

    /**
     * Periodically checks the mesh app library for apps with autoInstall=true that are not
     * currently installed on this device. For each missing sideloaded app that has a downloadUrl
     * configured, triggers a download+install via PackageInstallerDispatcher.
     * Runs once on startup then every AUTO_INSTALL_CHECK_MS.
     */
    private fun manageAutoInstallLoop() {
        if (meshAutoInstallJob?.isActive == true) return
        Logger.i("Mesh app auto-install loop starting (interval ${AUTO_INSTALL_CHECK_MS / 60_000}min)")
        meshAutoInstallJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val localMeshId = SettingsStore.getLocalMeshId(applicationContext)
                    val library = SettingsStore.getMeshAppLibrary(applicationContext, localMeshId)
                    val installedPackages = com.cfox.droidmesh.installer.AppVersionHelper
                        .getUserInstalledApps(applicationContext)
                        .map { it.packageName }
                        .toSet()

                    for ((pkg, cfg) in library) {
                        if (!isActive) break
                        if (!cfg.managed || !cfg.autoInstall || !cfg.isSideloaded) continue
                        if (installedPackages.contains(pkg)) continue
                        if (com.cfox.droidmesh.installer.AppVersionHelper.isExcludedAppPackage(pkg, applicationContext)) continue

                        val downloadUrl = cfg.downloadUrl.trim()
                        if (downloadUrl.isBlank()) {
                            Logger.w("Mesh auto-install: $pkg (${cfg.appName}) has autoInstall=true but no downloadUrl configured — skipping")
                            continue
                        }

                        Logger.i("Mesh auto-install: $pkg (${cfg.appName}) is missing — downloading from $downloadUrl")
                        try {
                            val fileName = "$pkg-${cfg.targetVersion.trim().ifBlank { "latest" }}.apk"
                            val downloader = com.cfox.droidmesh.downloader.ApkDownloader(applicationContext)
                            val apkResult = downloader.downloadApk(downloadUrl, fileName)
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
                } catch (e: Exception) {
                    Logger.e("Mesh auto-install loop error", e)
                }
                delay(AUTO_INSTALL_CHECK_MS)
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
