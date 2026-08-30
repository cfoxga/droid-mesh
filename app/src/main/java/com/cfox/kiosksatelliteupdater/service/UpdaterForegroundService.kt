package com.cfox.kiosksatelliteupdater.service

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
import com.cfox.kiosksatelliteupdater.MainActivity
import com.cfox.kiosksatelliteupdater.R
import com.cfox.kiosksatelliteupdater.server.LocalHttpServer
import com.cfox.kiosksatelliteupdater.server.UpdateCoordinator
import com.cfox.kiosksatelliteupdater.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class UpdaterForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "kiosk_updater_service_channel"
        const val NOTIFICATION_ID = 1001
        const val PORT = 2325

        const val ACTION_START = "com.cfox.kiosksatelliteupdater.action.START"
        const val ACTION_STOP = "com.cfox.kiosksatelliteupdater.action.STOP"
        const val ACTION_TRIGGER_UPDATE = "com.cfox.kiosksatelliteupdater.action.TRIGGER_UPDATE"

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var activeCoordinator: UpdateCoordinator? = null
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
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        Logger.i("UpdaterForegroundService onCreate")
        createNotificationChannel()

        val coordinator = UpdateCoordinator(applicationContext)
        updateCoordinator = coordinator
        activeCoordinator = coordinator

        httpServer = LocalHttpServer(applicationContext, coordinator, PORT)

        try {
            httpServer?.start()
            Logger.i("Local HTTP server successfully started on port $PORT")
        } catch (e: Exception) {
            Logger.e("Failed to bind LocalHttpServer to port $PORT", e)
        }

        // Collect coordinator status updates to update notification & wake lock
        serviceScope.launch {
            coordinator.statusFlow.collect { status ->
                updateNotification(status.message)
                handleWakeLockForState(status.state)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        Logger.i("UpdaterForegroundService onStartCommand action=$action")

        startForeground(NOTIFICATION_ID, buildNotification("Listening on port $PORT"))
        isRunning = true

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
            httpServer?.stop()
            Logger.i("HTTP server stopped")
        } catch (e: Exception) {
            Logger.e("Error stopping HTTP server", e)
        }

        serviceScope.cancel()
    }
}
