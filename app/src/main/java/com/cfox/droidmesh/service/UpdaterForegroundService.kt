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
        const val CHANNEL_ID = "kiosk_updater_service_channel"
        const val NOTIFICATION_ID = 1001
        const val PORT = 2325

        const val ACTION_START = "com.cfox.droidmesh.action.START"
        const val ACTION_STOP = "com.cfox.droidmesh.action.STOP"
        const val ACTION_TRIGGER_UPDATE = "com.cfox.droidmesh.action.TRIGGER_UPDATE"

        // How often the auto-update loop re-checks GitHub while enabled.
        // Not user-configurable; only the on/off toggle is exposed.
        const val AUTO_UPDATE_INTERVAL_MS = 6 * 60 * 60 * 1000L

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
    private var autoUpdateJob: Job? = null

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

        // Collect coordinator status updates to update notification & wake lock
        serviceScope.launch {
            coordinator.statusFlow.collect { status ->
                updateNotification(status.message)
                handleWakeLockForState(status.state)
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
        manageAutoUpdateLoop()


        when (action) {
            ACTION_TRIGGER_UPDATE -> {
                val force = intent?.getBooleanExtra("force", false) ?: false
                updateCoordinator?.startUpdateAsync(force)
            }
            ACTION_STOP -> {
                stopSelf()
            }
        }

        return START_STICKY
    }

    /**
     * Starts (or stops) the periodic auto-update loop to match the current
     * SettingsStore value. Idempotent: safe to call from every
     * onStartCommand without spawning duplicate loops.
     */
    private fun manageAutoUpdateLoop() {
        val enabled = SettingsStore.isAutoUpdateEnabled(applicationContext)
        if (enabled) {
            if (autoUpdateJob?.isActive == true) return
            Logger.i("Auto-update enabled — starting periodic check every ${AUTO_UPDATE_INTERVAL_MS / 3_600_000}h")
            autoUpdateJob = serviceScope.launch {
                while (isActive) {
                    Logger.i("Auto-update: checking for a Kiosk Satellite update")
                    updateCoordinator?.startUpdateAsync(force = false)
                    delay(AUTO_UPDATE_INTERVAL_MS)
                }
            }
        } else {
            if (autoUpdateJob != null) Logger.i("Auto-update disabled — stopping periodic check")
            autoUpdateJob?.cancel()
            autoUpdateJob = null
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
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KioskSatelliteUpdater::UpdateWakeLock")
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
            .setContentTitle("Kiosk Satellite Updater (:2325)")
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

        serviceScope.cancel()
    }
}
