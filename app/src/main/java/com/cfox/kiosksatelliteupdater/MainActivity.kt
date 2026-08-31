package com.cfox.kiosksatelliteupdater

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.cfox.kiosksatelliteupdater.databinding.ActivityMainBinding
import com.cfox.kiosksatelliteupdater.databinding.ItemMeshPeerBinding
import com.cfox.kiosksatelliteupdater.installer.AppVersionHelper
import com.cfox.kiosksatelliteupdater.mesh.PeerNode
import com.cfox.kiosksatelliteupdater.server.UpdateCoordinator
import com.cfox.kiosksatelliteupdater.service.AutoInstallService
import com.cfox.kiosksatelliteupdater.service.UpdaterForegroundService
import com.cfox.kiosksatelliteupdater.settings.SettingsStore
import com.cfox.kiosksatelliteupdater.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var coordinator: UpdateCoordinator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Ensure foreground service is running
        UpdaterForegroundService.startService(this)
        coordinator = UpdaterForegroundService.activeCoordinator ?: UpdateCoordinator(this)

        setupUI()
        observeCoordinator()
        observeLogs()
        observeMeshPeers()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun setupUI() {
        binding.switchAutoUpdate.isChecked = SettingsStore.isAutoUpdateEnabled(this)
        binding.switchAutoUpdate.setOnCheckedChangeListener { _, isChecked ->
            SettingsStore.setAutoUpdateEnabled(this, isChecked)
            Logger.i("Auto-update ${if (isChecked) "enabled" else "disabled"} by user")
            // Re-ping the running service so it re-evaluates the loop immediately
            // instead of waiting for the next natural restart.
            UpdaterForegroundService.startService(this)
        }

        binding.btnCheck.setOnClickListener {
            checkReleaseInfo()
        }

        binding.btnUpdateNow.setOnClickListener {
            triggerUpdateNow()
        }

        binding.btnMeshBeacon.setOnClickListener {
            UpdaterForegroundService.activeMeshManager?.triggerBeacon()
            Logger.i("Triggered manual mesh beacon scan")
        }

        // Tap accessibility status to open settings if not enabled
        binding.tvAccessibilityStatus.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (e: Exception) {
                Logger.e("Cannot open accessibility settings", e)
            }
        }

        // Tap install permission to open settings if not allowed
        binding.tvInstallPermissionStatus.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
                } catch (e: Exception) {
                    Logger.e("Cannot open unknown app sources settings", e)
                }
            }
        }
    }

    private fun refreshStatus() {
        val installed = AppVersionHelper.getInstalledVersion(this)
        if (installed.isInstalled) {
            binding.tvInstalledVersion.text = "Installed Satellite: v${installed.versionName} (build ${installed.versionCode})"
            binding.tvInstalledVersion.setTextColor(getColor(R.color.white))
        } else {
            binding.tvInstalledVersion.text = "Installed Satellite: NOT INSTALLED"
            binding.tvInstalledVersion.setTextColor(getColor(R.color.status_amber))
        }

        // Accessibility service status
        val isA11yActive = AutoInstallService.isServiceRunning
        if (isA11yActive) {
            binding.tvAccessibilityStatus.text = "Accessibility Service: ACTIVE (Auto-Install Ready)"
            binding.tvAccessibilityStatus.setTextColor(getColor(R.color.status_green))
        } else {
            binding.tvAccessibilityStatus.text = "Accessibility Service: DISABLED (Tap to enable)"
            binding.tvAccessibilityStatus.setTextColor(getColor(R.color.status_red))
        }

        // Unknown app install permission status
        val canInstall = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            packageManager.canRequestPackageInstalls()
        } else {
            true
        }

        if (canInstall) {
            binding.tvInstallPermissionStatus.text = "Install Unknown Apps: GRANTED"
            binding.tvInstallPermissionStatus.setTextColor(getColor(R.color.status_green))
        } else {
            binding.tvInstallPermissionStatus.text = "Install Unknown Apps: NOT GRANTED (Tap to grant)"
            binding.tvInstallPermissionStatus.setTextColor(getColor(R.color.status_amber))
        }

        binding.tvServerStatus.text = "HTTP Trigger Server: Listening on :2325"
    }

    private fun checkReleaseInfo() {
        lifecycleScope.launch {
            binding.btnCheck.isEnabled = false
            binding.tvLatestVersion.text = "GitHub Latest: Querying..."
            val activeCoordinator = coordinator ?: UpdateCoordinator(this@MainActivity)
            val result = activeCoordinator.checkVersion()

            if (result.isSuccess) {
                val comp = result.getOrThrow()
                val statusText = if (comp.isUpdateAvailable) {
                    "GitHub Latest: ${comp.latestVersionTag} (UPDATE AVAILABLE)"
                } else {
                    "GitHub Latest: ${comp.latestVersionTag} (Up to date)"
                }
                binding.tvLatestVersion.text = statusText
                binding.tvLatestVersion.setTextColor(
                    if (comp.isUpdateAvailable) getColor(R.color.status_green) else getColor(R.color.white)
                )
                Logger.i("Check complete: latest tag ${comp.latestVersionTag}, update available: ${comp.isUpdateAvailable}")
            } else {
                val err = result.exceptionOrNull()?.message ?: "Check failed"
                binding.tvLatestVersion.text = "GitHub Latest: Error ($err)"
                binding.tvLatestVersion.setTextColor(getColor(R.color.status_red))
                Logger.e("Release check failed: $err")
            }
            binding.btnCheck.isEnabled = true
        }
    }

    private fun triggerUpdateNow() {
        val activeCoordinator = coordinator ?: UpdateCoordinator(this)
        activeCoordinator.startUpdateAsync(force = true) { result ->
            lifecycleScope.launch(Dispatchers.Main) {
                if (result.isSuccess) {
                    Logger.i("Update sequence dispatched successfully")
                } else {
                    Logger.e("Update sequence failed: ${result.exceptionOrNull()?.message}")
                }
            }
        }
    }

    private fun observeCoordinator() {
        val activeCoordinator = coordinator ?: return
        lifecycleScope.launch {
            activeCoordinator.statusFlow.collect { status ->
                when (status.state) {
                    "DOWNLOADING" -> {
                        binding.progressBar.visibility = View.VISIBLE
                        binding.tvProgressText.visibility = View.VISIBLE
                        binding.progressBar.progress = status.progressPercent
                        binding.tvProgressText.text = status.message
                        binding.btnUpdateNow.isEnabled = false
                    }
                    "INSTALLING", "CHECKING" -> {
                        binding.progressBar.visibility = View.VISIBLE
                        binding.tvProgressText.visibility = View.VISIBLE
                        binding.progressBar.isIndeterminate = true
                        binding.tvProgressText.text = status.message
                        binding.btnUpdateNow.isEnabled = false
                    }
                    "COMPLETED" -> {
                        binding.progressBar.visibility = View.GONE
                        binding.tvProgressText.visibility = View.VISIBLE
                        binding.tvProgressText.text = status.message
                        binding.btnUpdateNow.isEnabled = true
                        refreshStatus()
                    }
                    "ERROR" -> {
                        binding.progressBar.visibility = View.GONE
                        binding.tvProgressText.visibility = View.VISIBLE
                        binding.tvProgressText.text = "Error: ${status.message}"
                        binding.btnUpdateNow.isEnabled = true
                    }
                    else -> {
                        binding.progressBar.visibility = View.GONE
                        binding.tvProgressText.visibility = View.GONE
                        binding.btnUpdateNow.isEnabled = true
                    }
                }
            }
        }
    }

    private fun observeLogs() {
        lifecycleScope.launch {
            // Populate existing logs
            val existing = Logger.getRecentLogs()
            if (existing.isNotEmpty()) {
                binding.tvLogConsole.text = existing.takeLast(15).joinToString("\n")
            }

            Logger.logFlow.collect { newLogLine ->
                withContext(Dispatchers.Main) {
                    val current = binding.tvLogConsole.text.toString()
                    val lines = (current.lines() + newLogLine).takeLast(15)
                    binding.tvLogConsole.text = lines.joinToString("\n")
                }
            }
        }
    }

    private fun observeMeshPeers() {
        lifecycleScope.launch {
            while (true) {
                val meshManager = UpdaterForegroundService.activeMeshManager
                if (meshManager != null) {
                    meshManager.peersFlow.collect { peers ->
                        renderMeshPeers(peers)
                    }
                }
                delay(1000)
            }
        }
    }

    private fun renderMeshPeers(peers: List<PeerNode>) {
        val onlineCount = peers.count { it.isOnline }
        binding.tvMeshCount.text = "Active Nodes: ${peers.size} ($onlineCount online)"

        binding.layoutMeshPeers.removeAllViews()

        if (peers.isEmpty()) {
            val emptyTv = android.widget.TextView(this).apply {
                text = "Scanning for nearby Portals..."
                setTextColor(Color.parseColor("#777777"))
                textSize = 13f
            }
            binding.layoutMeshPeers.addView(emptyTv)
            return
        }

        val inflater = layoutInflater
        for (peer in peers) {
            val itemBinding = ItemMeshPeerBinding.inflate(inflater, binding.layoutMeshPeers, false)

            val titleSuffix = if (peer.isSelf) " [This Device]" else ""
            itemBinding.tvPeerTitle.text = "${peer.deviceModel} (${peer.ip})$titleSuffix"

            if (peer.targetInstalled && !peer.installedVersionName.isNullOrBlank()) {
                itemBinding.tvPeerVersion.text = "Kiosk Satellite: v${peer.installedVersionName} (build ${peer.installedVersionCode ?: 0})"
                itemBinding.tvPeerVersion.setTextColor(Color.parseColor("#DDDDDD"))
            } else {
                itemBinding.tvPeerVersion.text = "Kiosk Satellite: Not Installed"
                itemBinding.tvPeerVersion.setTextColor(Color.parseColor("#FFC107"))
            }

            itemBinding.tvPeerMessage.text = peer.updaterMessage ?: "Status: ${peer.updaterState}"

            if (peer.isSelf) {
                itemBinding.tvPeerLastSeen.text = "Local"
            } else {
                val sec = peer.lastSeenSecondsAgo
                itemBinding.tvPeerLastSeen.text = if (sec <= 5) "Just now" else "${sec}s ago"
            }

            val (badgeBg, badgeText) = when {
                !peer.isOnline -> Pair("#424242", "OFFLINE")
                peer.updaterState == "DOWNLOADING" -> Pair("#1565C0", "DOWNLOADING")
                peer.updaterState == "INSTALLING" -> Pair("#E65100", "INSTALLING")
                peer.updaterState == "CHECKING" -> Pair("#00838F", "CHECKING")
                peer.updaterState == "ERROR" -> Pair("#C62828", "ERROR")
                else -> Pair("#1B5E20", "IDLE")
            }

            itemBinding.tvPeerStateBadge.text = badgeText
            itemBinding.tvPeerStateBadge.setBackgroundColor(Color.parseColor(badgeBg))

            binding.layoutMeshPeers.addView(itemBinding.root)
        }
    }
}
