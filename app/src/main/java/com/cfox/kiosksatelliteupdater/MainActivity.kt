package com.cfox.kiosksatelliteupdater

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.cfox.kiosksatelliteupdater.api.ReleaseInfo
import com.cfox.kiosksatelliteupdater.databinding.ActivityMainBinding
import com.cfox.kiosksatelliteupdater.databinding.ItemMeshPeerBinding
import com.cfox.kiosksatelliteupdater.installer.AppVersionHelper
import com.cfox.kiosksatelliteupdater.mesh.PeerNode
import com.cfox.kiosksatelliteupdater.server.UpdateCoordinator
import com.cfox.kiosksatelliteupdater.service.AutoInstallService
import com.cfox.kiosksatelliteupdater.service.UpdaterForegroundService
import com.cfox.kiosksatelliteupdater.settings.SettingsStore
import com.cfox.kiosksatelliteupdater.utils.CpuStatsHelper
import com.cfox.kiosksatelliteupdater.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    enum class NavTab {
        OVERVIEW, ABOUT, LOGS
    }

    private lateinit var binding: ActivityMainBinding
    private var coordinator: UpdateCoordinator? = null
    private var availableReleases: List<ReleaseInfo> = emptyList()
    private var selectedReleaseIndex: Int = 0 // 0 = "Latest"
    private var currentTab: NavTab = NavTab.OVERVIEW

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyImmersiveMode()

        // Handle edge insets gracefully
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootLayout) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                systemBars.left,
                systemBars.top,
                systemBars.right,
                systemBars.bottom
            )
            insets
        }

        // Ensure foreground service is running
        UpdaterForegroundService.startService(this)
        coordinator = UpdaterForegroundService.activeCoordinator ?: UpdateCoordinator(this)

        setupNavigation()
        setupUI()
        setupLogActions()
        observeCoordinator()
        observeLogs()
        observeMeshPeers()
        startCpuTelemetryLoop()
        refreshStatus()
        refreshAbout()
        loadAvailableReleases(forceRefresh = false)
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveMode()
        refreshStatus()
        refreshAbout()
        loadAvailableReleases(forceRefresh = false)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyImmersiveMode()
        }
    }

    /**
     * Auto-hide Navigation Bar and Status Bar (Immersive Sticky mode matching KS).
     */
    private fun applyImmersiveMode() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        }
    }

    private fun setupNavigation() {
        // Resolve and display device name at top of sidebar
        binding.tvSidebarDeviceName.text = CpuStatsHelper.getDeviceName(this)

        binding.btnNavOverview.setOnClickListener {
            switchTab(NavTab.OVERVIEW)
        }

        binding.btnNavAbout.setOnClickListener {
            switchTab(NavTab.ABOUT)
        }

        binding.btnNavLogs.setOnClickListener {
            switchTab(NavTab.LOGS)
        }

        updateNavSelectionUI()
    }

    private fun switchTab(tab: NavTab) {
        if (currentTab == tab) return
        currentTab = tab
        updateNavSelectionUI()

        if (tab == NavTab.ABOUT) {
            refreshAbout()
        } else if (tab == NavTab.LOGS) {
            scrollLogsToBottom()
        }
    }

    private fun updateNavSelectionUI() {
        // Tab panes visibility
        binding.paneOverview.visibility = if (currentTab == NavTab.OVERVIEW) View.VISIBLE else View.GONE
        binding.paneAbout.visibility = if (currentTab == NavTab.ABOUT) View.VISIBLE else View.GONE
        binding.paneLogs.visibility = if (currentTab == NavTab.LOGS) View.VISIBLE else View.GONE

        // Sidebar rail item backgrounds
        val selectedBg = ContextCompat.getDrawable(this, R.drawable.bg_rail_item_selected)

        binding.btnNavOverview.background = if (currentTab == NavTab.OVERVIEW) selectedBg else null
        binding.btnNavAbout.background = if (currentTab == NavTab.ABOUT) selectedBg else null
        binding.btnNavLogs.background = if (currentTab == NavTab.LOGS) selectedBg else null
    }

    private fun setupUI() {
        // Auto-update switch toggle
        binding.switchAutoUpdate.isChecked = SettingsStore.isAutoUpdateEnabled(this)
        binding.switchAutoUpdate.setOnCheckedChangeListener { _, isChecked ->
            SettingsStore.setAutoUpdateEnabled(this, isChecked)
            Logger.i("Auto-update ${if (isChecked) "enabled" else "disabled"} by user")
            UpdaterForegroundService.startService(this)
            updateVersionActionVisibility()
            renderMeshPeers(UpdaterForegroundService.activeMeshManager?.peersFlow?.value ?: emptyList())
        }

        // Refresh releases button
        binding.btnRefreshVersions.setOnClickListener {
            loadAvailableReleases(forceRefresh = true)
        }

        // Update All button
        binding.btnUpdateAll.setOnClickListener {
            triggerUpdateAll()
        }

        // Mesh scan button
        binding.btnMeshBeacon.setOnClickListener {
            UpdaterForegroundService.activeMeshManager?.triggerBeacon()
            Logger.i("Triggered manual mesh beacon scan")
        }

        // System Permissions Shortcuts
        binding.tvAccessibilityStatus.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (e: Exception) {
                Logger.e("Cannot open accessibility settings", e)
            }
        }

        binding.tvInstallPermissionStatus.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
                } catch (e: Exception) {
                    Logger.e("Cannot open unknown app sources settings", e)
                }
            }
        }

        binding.tvAdbStatus.setOnClickListener {
            com.cfox.kiosksatelliteupdater.utils.AdbHelper.toggleAdb(this)
            refreshStatus()
            populateVersionSpinner()
            updateVersionSelectionUI()
        }

        // Version Spinner Listener
        binding.spnVersionToInstall.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedReleaseIndex = position
                updateVersionSelectionUI()
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun setupLogActions() {
        binding.btnCopyLogs.setOnClickListener {
            val fullLogs = Logger.getRecentLogs().joinToString("\n")
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("KSU Logs", fullLogs)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        binding.btnClearLogs.setOnClickListener {
            Logger.clear()
            binding.tvLogConsole.text = "[Ready] Log history cleared."
            binding.tvLogsMeta.text = "0 entries"
        }

        binding.btnRefreshLogs.setOnClickListener {
            val logs = Logger.getRecentLogs()
            binding.tvLogConsole.text = if (logs.isEmpty()) "[Ready] Initialized Kiosk Satellite Updater." else logs.joinToString("\n")
            binding.tvLogsMeta.text = "${logs.size} entries"
            scrollLogsToBottom()
        }
    }

    private fun startCpuTelemetryLoop() {
        lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                val telemetry = CpuStatsHelper.readTelemetry()
                withContext(Dispatchers.Main) {
                    binding.tvStatCpu.text = telemetry.usageDisplay
                    binding.tvStatTemp.text = telemetry.tempDisplay
                }
                delay(3000)
            }
        }
    }

    private fun refreshAbout() {
        val selfInfo = AppVersionHelper.getInstalledVersion(this, packageName)
        val targetInfo = AppVersionHelper.getInstalledVersion(this)

        binding.tvAboutAppVersion.text = if (selfInfo.isInstalled) {
            "v${selfInfo.versionName ?: "1.0.0"} (build ${selfInfo.versionCode ?: 1})"
        } else {
            "v1.0.0 (build 1)"
        }

        binding.tvAboutBuild.text = BuildConfig.BUILD_TYPE
        binding.tvAboutPackage.text = packageName
        binding.tvAboutTargetPackage.text = AppVersionHelper.TARGET_PACKAGE

        if (targetInfo.isInstalled) {
            binding.tvAboutTargetStatus.text = "Installed (v${targetInfo.versionName})"
            binding.tvAboutTargetStatus.setTextColor(getColor(R.color.ks_sage))
        } else {
            binding.tvAboutTargetStatus.text = "Not Installed"
            binding.tvAboutTargetStatus.setTextColor(getColor(R.color.ks_ochre))
        }
    }

    private fun loadAvailableReleases(forceRefresh: Boolean = false) {
        lifecycleScope.launch {
            if (forceRefresh) {
                binding.btnRefreshVersions.isEnabled = false
                binding.tvTargetVersionStatus.text = "GitHub Releases: Fetching..."
            }

            val activeCoordinator = coordinator ?: UpdateCoordinator(this@MainActivity)
            val result = activeCoordinator.fetchAvailableReleases(forceRefresh = forceRefresh)

            if (result.isSuccess) {
                availableReleases = result.getOrThrow()
                populateVersionSpinner()
                updateVersionSelectionUI()
                if (forceRefresh) {
                    Logger.i("Releases refreshed: ${availableReleases.size} versions available (Latest: ${availableReleases.firstOrNull()?.tagName})")
                }
            } else {
                val err = result.exceptionOrNull()?.message ?: "Failed to fetch releases"
                binding.tvTargetVersionStatus.text = "GitHub Releases: Error ($err)"
                binding.tvTargetVersionStatus.setTextColor(getColor(R.color.ks_rust))
                Logger.e("Failed to fetch available releases: $err")
            }

            binding.btnRefreshVersions.isEnabled = true
        }
    }

    private fun populateVersionSpinner() {
        val options = mutableListOf<String>()
        val latestTag = availableReleases.firstOrNull()?.tagName
        val isAdb = com.cfox.kiosksatelliteupdater.utils.AdbHelper.isAdbEnabled(this)
        val installed = AppVersionHelper.getInstalledVersion(this)

        if (latestTag != null) {
            options.add("Latest ($latestTag)")
        } else {
            options.add("Latest")
        }

        for (release in availableReleases) {
            if (isAdb || !installed.isInstalled || installed.versionName == null) {
                options.add(release.tagName)
            } else {
                // If ADB is disabled, allow only same or newer versions (no downgrades)
                val isDowngrade = AppVersionHelper.isUpdateAvailable(release.tagName, installed.versionName) &&
                        release.tagName.trim().removePrefix("v") != installed.versionName.trim().removePrefix("v")
                if (!isDowngrade) {
                    options.add(release.tagName)
                }
            }
        }

        val adapter = ArrayAdapter(this, R.layout.item_version_dropdown, options)
        adapter.setDropDownViewResource(R.layout.item_version_dropdown)
        binding.spnVersionToInstall.adapter = adapter

        if (selectedReleaseIndex < options.size) {
            binding.spnVersionToInstall.setSelection(selectedReleaseIndex)
        } else {
            binding.spnVersionToInstall.setSelection(0)
            selectedReleaseIndex = 0
        }
    }

    private fun updateVersionSelectionUI() {
        val isLatestSelected = (selectedReleaseIndex == 0)
        val selectedRelease = getSelectedRelease()

        if (selectedRelease != null) {
            val pubDate = if (selectedRelease.publishedAt.isNotEmpty()) {
                " · " + selectedRelease.publishedAt.take(10)
            } else ""

            if (isLatestSelected) {
                binding.tvTargetVersionStatus.text = "GitHub Latest: ${selectedRelease.tagName}$pubDate"
                binding.tvTargetVersionStatus.setTextColor(getColor(R.color.ks_sage))
            } else {
                binding.tvTargetVersionStatus.text = "Selected Release: ${selectedRelease.tagName}$pubDate"
                binding.tvTargetVersionStatus.setTextColor(getColor(R.color.ks_teal))
            }
        } else {
            binding.tvTargetVersionStatus.text = "Target Version: Latest"
            binding.tvTargetVersionStatus.setTextColor(getColor(R.color.ks_on_surface))
        }

        updateVersionActionVisibility()
        renderMeshPeers(UpdaterForegroundService.activeMeshManager?.peersFlow?.value ?: emptyList())
    }

    private fun updateVersionActionVisibility() {
        val isLatestSelected = (selectedReleaseIndex == 0)
        val isAutoUpdateEnabled = SettingsStore.isAutoUpdateEnabled(this)
        val targetRelease = getSelectedRelease()
        val targetTag = targetRelease?.tagName ?: "latest"

        // Auto-update section: Visible ONLY when "Latest" is selected
        binding.layoutAutoUpdate.visibility = if (isLatestSelected) View.VISIBLE else View.GONE

        // Update All button: Visible if Auto-Update is NOT enabled OR a specific version is selected
        val showUpdateAll = !isAutoUpdateEnabled || !isLatestSelected
        binding.btnUpdateAll.visibility = if (showUpdateAll) View.VISIBLE else View.GONE

        if (isLatestSelected) {
            binding.btnUpdateAll.text = "Update All Units (Latest: $targetTag)"
        } else {
            binding.btnUpdateAll.text = "Update All Units to $targetTag"
        }
    }

    private fun getSelectedRelease(): ReleaseInfo? {
        if (availableReleases.isEmpty()) return null
        return if (selectedReleaseIndex == 0) {
            availableReleases.firstOrNull()
        } else {
            val releaseIdx = selectedReleaseIndex - 1
            if (releaseIdx in availableReleases.indices) availableReleases[releaseIdx] else availableReleases.firstOrNull()
        }
    }

    private fun refreshStatus() {
        val installed = AppVersionHelper.getInstalledVersion(this)
        if (installed.isInstalled) {
            binding.tvInstalledVersion.text = "Installed on this device: v${installed.versionName} (build ${installed.versionCode})"
            binding.tvInstalledVersion.setTextColor(getColor(R.color.ks_on_surface_variant))
        } else {
            binding.tvInstalledVersion.text = "Installed on this device: NOT INSTALLED"
            binding.tvInstalledVersion.setTextColor(getColor(R.color.ks_ochre))
        }

        // Accessibility service status
        val isA11yActive = AutoInstallService.isServiceRunning
        if (isA11yActive) {
            binding.tvAccessibilityStatus.text = "Accessibility Service: ACTIVE (Auto-Install Ready)"
            binding.tvAccessibilityStatus.setTextColor(getColor(R.color.ks_sage))
        } else {
            binding.tvAccessibilityStatus.text = "Accessibility Service: DISABLED (Tap to enable)"
            binding.tvAccessibilityStatus.setTextColor(getColor(R.color.ks_rust))
        }

        // Unknown app install permission status
        val canInstall = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            packageManager.canRequestPackageInstalls()
        } else {
            true
        }

        if (canInstall) {
            binding.tvInstallPermissionStatus.text = "Install Unknown Apps: GRANTED"
            binding.tvInstallPermissionStatus.setTextColor(getColor(R.color.ks_sage))
        } else {
            binding.tvInstallPermissionStatus.text = "Install Unknown Apps: NOT GRANTED (Tap to grant)"
            binding.tvInstallPermissionStatus.setTextColor(getColor(R.color.ks_ochre))
        }

        // ADB Status
        val isAdb = com.cfox.kiosksatelliteupdater.utils.AdbHelper.isAdbEnabled(this)
        if (isAdb) {
            binding.tvAdbStatus.text = "ADB Debugging: ENABLED (Tap to toggle)"
            binding.tvAdbStatus.setTextColor(getColor(R.color.ks_sage))
        } else {
            binding.tvAdbStatus.text = "ADB Debugging: DISABLED (Tap to toggle)"
            binding.tvAdbStatus.setTextColor(getColor(R.color.ks_rust))
        }

        binding.tvServerStatus.text = "HTTP Trigger Server: Listening on :2325"
    }

    private fun triggerUpdateAll() {
        val targetRelease = getSelectedRelease()
        if (targetRelease == null) {
            Logger.w("Cannot update all: no target release selected")
            return
        }

        Logger.i("Dispatched 'Update All' to target version ${targetRelease.tagName}")

        // 1. Trigger local update
        val activeCoordinator = coordinator ?: UpdateCoordinator(this)
        activeCoordinator.startUpdateForRelease(targetRelease, force = true) { result ->
            lifecycleScope.launch(Dispatchers.Main) {
                if (result.isSuccess) {
                    Logger.i("Local update sequence dispatched successfully for ${targetRelease.tagName}")
                } else {
                    Logger.e("Local update failed: ${result.exceptionOrNull()?.message}")
                }
            }
        }

        // 2. Broadcast / trigger update to all online mesh peers
        val peers = UpdaterForegroundService.activeMeshManager?.peersFlow?.value ?: emptyList()
        val remotePeers = peers.filter { !it.isSelf && it.isOnline }

        if (remotePeers.isNotEmpty()) {
            lifecycleScope.launch(Dispatchers.IO) {
                for (peer in remotePeers) {
                    triggerRemotePeerUpdate(peer, targetRelease)
                }
            }
        }
    }

    private fun triggerSinglePeerUpdate(peer: PeerNode, targetRelease: ReleaseInfo) {
        if (peer.isSelf) {
            val activeCoordinator = coordinator ?: UpdateCoordinator(this)
            activeCoordinator.startUpdateForRelease(targetRelease, force = true)
            Logger.i("Triggered local update to ${targetRelease.tagName}")
        } else {
            lifecycleScope.launch(Dispatchers.IO) {
                triggerRemotePeerUpdate(peer, targetRelease)
            }
        }
    }

    private suspend fun triggerRemotePeerUpdate(peer: PeerNode, targetRelease: ReleaseInfo) {
        try {
            val encodedUrl = URLEncoder.encode(targetRelease.apkAssetUrl, "UTF-8")
            val url = "http://${peer.ip}:${peer.port}/update?force=true&tag=${targetRelease.tagName}&url=$encodedUrl"
            Logger.i("Sending update trigger to ${peer.deviceModel} (${peer.ip}) -> ${targetRelease.tagName}")

            val request = Request.Builder()
                .url(url)
                .post(ByteArray(0).toRequestBody(null, 0, 0))
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Logger.i("Update trigger accepted by ${peer.deviceModel} (${peer.ip})")
                } else {
                    Logger.e("Failed to trigger update on ${peer.ip}: HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            Logger.e("Error triggering update on peer ${peer.ip}: ${e.message}")
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
                        binding.progressBar.isIndeterminate = false
                        binding.progressBar.progress = status.progressPercent
                        binding.tvProgressText.text = status.message
                        binding.btnUpdateAll.isEnabled = false
                    }
                    "INSTALLING", "CHECKING" -> {
                        binding.progressBar.visibility = View.VISIBLE
                        binding.tvProgressText.visibility = View.VISIBLE
                        binding.progressBar.isIndeterminate = true
                        binding.tvProgressText.text = status.message
                        binding.btnUpdateAll.isEnabled = false
                    }
                    "COMPLETED" -> {
                        binding.progressBar.visibility = View.GONE
                        binding.tvProgressText.visibility = View.VISIBLE
                        binding.tvProgressText.text = status.message
                        binding.btnUpdateAll.isEnabled = true
                        refreshStatus()
                        refreshAbout()
                    }
                    "ERROR" -> {
                        binding.progressBar.visibility = View.GONE
                        binding.tvProgressText.visibility = View.VISIBLE
                        binding.tvProgressText.text = "Error: ${status.message}"
                        binding.btnUpdateAll.isEnabled = true
                    }
                    else -> {
                        binding.progressBar.visibility = View.GONE
                        binding.tvProgressText.visibility = View.GONE
                        binding.btnUpdateAll.isEnabled = true
                    }
                }
            }
        }
    }

    private fun observeLogs() {
        lifecycleScope.launch {
            val existing = Logger.getRecentLogs()
            if (existing.isNotEmpty()) {
                binding.tvLogConsole.text = existing.joinToString("\n")
                binding.tvLogsMeta.text = "${existing.size} entries"
            }

            Logger.logFlow.collect { _ ->
                withContext(Dispatchers.Main) {
                    val logs = Logger.getRecentLogs()
                    binding.tvLogConsole.text = logs.joinToString("\n")
                    binding.tvLogsMeta.text = "${logs.size} entries"
                    if (currentTab == NavTab.LOGS) {
                        scrollLogsToBottom()
                    }
                }
            }
        }
    }

    private fun scrollLogsToBottom() {
        binding.scrollLogConsole.post {
            binding.scrollLogConsole.fullScroll(View.FOCUS_DOWN)
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

    private fun isVersionMatching(installed: String?, targetTag: String?): Boolean {
        if (installed.isNullOrBlank() || targetTag.isNullOrBlank()) return false
        val cleanInstalled = installed.trim().removePrefix("v").removePrefix("V")
        val cleanTarget = targetTag.trim().removePrefix("v").removePrefix("V")
        return cleanInstalled.equals(cleanTarget, ignoreCase = true)
    }

    private fun renderMeshPeers(peers: List<PeerNode>) {
        val onlineCount = peers.count { it.isOnline }
        binding.tvMeshCount.text = "Active Nodes: ${peers.size} ($onlineCount online)"

        binding.layoutMeshPeers.removeAllViews()

        if (peers.isEmpty()) {
            val emptyTv = android.widget.TextView(this).apply {
                text = "Scanning for nearby Portals on mesh..."
                setTextColor(getColor(R.color.ks_on_surface_variant))
                typeface = binding.tvMeshCount.typeface
                textSize = 13f
                setPadding(0, 8, 0, 8)
            }
            binding.layoutMeshPeers.addView(emptyTv)
            return
        }

        val isAutoUpdateEnabled = SettingsStore.isAutoUpdateEnabled(this)
        val isLatestSelected = (selectedReleaseIndex == 0)
        val targetRelease = getSelectedRelease()
        val targetTag = targetRelease?.tagName

        val inflater = layoutInflater
        for (peer in peers) {
            val itemBinding = ItemMeshPeerBinding.inflate(inflater, binding.layoutMeshPeers, false)

            val titleSuffix = if (peer.isSelf) " [This Device]" else ""
            itemBinding.tvPeerTitle.text = "${peer.deviceModel} (${peer.ip})$titleSuffix"

            val isVersionMatch = isVersionMatching(peer.installedVersionName, targetTag)

            if (peer.targetInstalled && !peer.installedVersionName.isNullOrBlank()) {
                val matchSuffix = if (isVersionMatch) " ✓" else " (Target: ${targetTag ?: "latest"})"
                itemBinding.tvPeerVersion.text = "Kiosk Satellite: v${peer.installedVersionName}$matchSuffix"
                itemBinding.tvPeerVersion.setTextColor(
                    if (isVersionMatch) getColor(R.color.ks_sage) else getColor(R.color.ks_on_surface_variant)
                )
            } else {
                itemBinding.tvPeerVersion.text = "Kiosk Satellite: Not Installed"
                itemBinding.tvPeerVersion.setTextColor(getColor(R.color.ks_ochre))
            }

            itemBinding.tvPeerMessage.text = peer.updaterMessage ?: "Status: ${peer.updaterState}"

            if (peer.isSelf) {
                itemBinding.tvPeerLastSeen.text = "Local"
            } else {
                val sec = peer.lastSeenSecondsAgo
                itemBinding.tvPeerLastSeen.text = if (sec <= 5) "Just now" else "${sec}s ago"
            }

            // ADB Status Button
            if (peer.adbEnabled) {
                itemBinding.btnPeerAdbStatus.text = "ADB: ON"
                itemBinding.btnPeerAdbStatus.setTextColor(getColor(R.color.ks_sage))
                itemBinding.btnPeerAdbStatus.strokeColor = android.content.res.ColorStateList.valueOf(getColor(R.color.ks_sage))
            } else {
                itemBinding.btnPeerAdbStatus.text = "ADB: OFF"
                itemBinding.btnPeerAdbStatus.setTextColor(getColor(R.color.ks_rust))
                itemBinding.btnPeerAdbStatus.strokeColor = android.content.res.ColorStateList.valueOf(getColor(R.color.ks_rust))
            }

            itemBinding.btnPeerAdbStatus.setOnClickListener {
                if (peer.isSelf) {
                    com.cfox.kiosksatelliteupdater.utils.AdbHelper.toggleAdb(this)
                    refreshStatus()
                    populateVersionSpinner()
                } else {
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val req = Request.Builder().url("http://${peer.ip}:${peer.port}/adb/toggle").post("{}".toRequestBody()).build()
                            httpClient.newCall(req).execute().close()
                        } catch (e: Exception) {
                            Logger.e("Failed to toggle ADB on ${peer.ip}", e)
                        }
                    }
                }
            }

            val (badgeBg, badgeText) = when {
                !peer.isOnline -> Pair("#5F6368", "OFFLINE")
                peer.updaterState == "DOWNLOADING" -> Pair("#6C9B9F", "DOWNLOADING")
                peer.updaterState == "INSTALLING" -> Pair("#CE9C3E", "INSTALLING")
                peer.updaterState == "CHECKING" -> Pair("#558387", "CHECKING")
                peer.updaterState == "ERROR" -> Pair("#D97E4C", "ERROR")
                else -> Pair("#749C6F", "IDLE")
            }

            itemBinding.tvPeerStateBadge.text = badgeText
            itemBinding.tvPeerStateBadge.setBackgroundColor(Color.parseColor(badgeBg))

            // Add an "Update" button when the current version does not match the selected version,
            // unless auto-update is enabled (with Latest selected).
            val shouldShowPeerUpdate = (!isVersionMatch || !peer.targetInstalled) &&
                !(isAutoUpdateEnabled && isLatestSelected) &&
                peer.isOnline &&
                targetRelease != null

            if (shouldShowPeerUpdate) {
                itemBinding.btnPeerUpdate.visibility = View.VISIBLE
                itemBinding.btnPeerUpdate.text = if (peer.targetInstalled) "Update" else "Install"
                itemBinding.btnPeerUpdate.setOnClickListener {
                    if (targetRelease != null) {
                        triggerSinglePeerUpdate(peer, targetRelease)
                    }
                }
            } else {
                itemBinding.btnPeerUpdate.visibility = View.GONE
            }

            binding.layoutMeshPeers.addView(itemBinding.root)
        }
    }
}
