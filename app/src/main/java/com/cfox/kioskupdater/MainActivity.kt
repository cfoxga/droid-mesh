package com.cfox.kioskupdater

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.cfox.kioskupdater.databinding.ActivityMainBinding
import com.cfox.kioskupdater.installer.AppVersionHelper
import com.cfox.kioskupdater.server.UpdateCoordinator
import com.cfox.kioskupdater.service.AutoInstallService
import com.cfox.kioskupdater.service.UpdaterForegroundService
import com.cfox.kioskupdater.utils.Logger
import kotlinx.coroutines.Dispatchers
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
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun setupUI() {
        binding.btnCheck.setOnClickListener {
            checkReleaseInfo()
        }

        binding.btnUpdateNow.setOnClickListener {
            triggerUpdateNow()
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
}
