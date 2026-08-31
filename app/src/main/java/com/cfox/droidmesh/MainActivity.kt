package com.cfox.droidmesh

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.cfox.droidmesh.api.ReleaseInfo
import com.cfox.droidmesh.databinding.ActivityMainBinding
import com.cfox.droidmesh.databinding.DialogConnectVlanBinding
import com.cfox.droidmesh.databinding.ItemMeshPeerBinding
import com.cfox.droidmesh.databinding.ItemSeedRowBinding
import com.cfox.droidmesh.databinding.ItemSidebarMeshBinding
import com.cfox.droidmesh.installer.AppVersionHelper
import com.cfox.droidmesh.mesh.MeshDiscoveryManager
import com.cfox.droidmesh.mesh.PeerNode
import com.cfox.droidmesh.server.UpdateCoordinator
import com.cfox.droidmesh.service.AutoInstallService
import com.cfox.droidmesh.service.UpdaterForegroundService
import com.cfox.droidmesh.settings.SettingsStore
import com.cfox.droidmesh.utils.CpuStatsHelper
import com.cfox.droidmesh.utils.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    enum class NavTab {
        MESH, OVERVIEW, SETTINGS, LOGS
    }

    private lateinit var binding: ActivityMainBinding
    private var coordinator: UpdateCoordinator? = null
    private var availableReleases: List<ReleaseInfo> = emptyList()
    private var selectedReleaseIndex: Int = 0 // 0 = "Latest"
    private var currentTab: NavTab = NavTab.MESH
    private var selectedMeshId: String? = null
    private var lastGroupedData: JSONObject? = null
    private val peerAppsExpandedState = mutableMapOf<String, Boolean>()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyImmersiveMode()

        ViewCompat.setOnApplyWindowInsetsListener(binding.rootLayout) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        UpdaterForegroundService.startService(this)
        coordinator = UpdaterForegroundService.activeCoordinator ?: UpdateCoordinator(this)

        selectedMeshId = SettingsStore.getLocalMeshId(this)

        setupNavigation()
        setupOverviewUI()
        setupSettingsUI()
        setupMeshActions()
        setupLogsUI()

        observeCoordinator()
        observeLogs()
        observeMeshDiscovery()
        startCpuTelemetryLoop()

        refreshStatus()
        refreshSettingsUI()
        loadAvailableReleases(forceRefresh = false)
    }

    override fun onResume() {
        super.onResume()
        applyImmersiveMode()
        refreshStatus()
        refreshSettingsUI()
        loadAvailableReleases(forceRefresh = false)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

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

    // ==================== NAVIGATION ====================

    private fun setupNavigation() {
        binding.tvSidebarDeviceName.text = CpuStatsHelper.getDeviceName(this)

        binding.btnSidebarConnectVlan.setOnClickListener {
            showConnectVlanDialog()
        }

        binding.btnNavOverview.setOnClickListener {
            switchTab(NavTab.OVERVIEW)
        }

        binding.btnNavSettings.setOnClickListener {
            switchTab(NavTab.SETTINGS)
        }

        binding.btnNavLogs.setOnClickListener {
            switchTab(NavTab.LOGS)
        }

        updateNavSelectionUI()
    }

    private fun switchTab(tab: NavTab) {
        currentTab = tab
        updateNavSelectionUI()

        if (tab == NavTab.SETTINGS) {
            refreshSettingsUI()
        } else if (tab == NavTab.OVERVIEW) {
            refreshStatus()
        } else if (tab == NavTab.LOGS) {
            scrollLogsToBottom()
        } else if (tab == NavTab.MESH) {
            renderCurrentMeshView()
        }
    }

    private fun selectMesh(meshId: String) {
        selectedMeshId = meshId
        currentTab = NavTab.MESH
        updateNavSelectionUI()
        renderCurrentMeshView()
    }

    private fun updateNavSelectionUI() {
        binding.paneMesh.visibility = if (currentTab == NavTab.MESH) View.VISIBLE else View.GONE
        binding.paneOverview.visibility = if (currentTab == NavTab.OVERVIEW) View.VISIBLE else View.GONE
        binding.paneSettings.visibility = if (currentTab == NavTab.SETTINGS) View.VISIBLE else View.GONE
        binding.paneLogs.visibility = if (currentTab == NavTab.LOGS) View.VISIBLE else View.GONE

        val selectedBg = ContextCompat.getDrawable(this, R.drawable.bg_rail_item_selected)

        binding.btnNavOverview.background = if (currentTab == NavTab.OVERVIEW) selectedBg else null
        binding.btnNavSettings.background = if (currentTab == NavTab.SETTINGS) selectedBg else null
        binding.btnNavLogs.background = if (currentTab == NavTab.LOGS) selectedBg else null

        renderSidebarMeshes()
    }

    // ==================== MESH NETWORK VIEW ====================

    private fun setupMeshActions() {
        binding.btnMeshConnectVlan.setOnClickListener {
            showConnectVlanDialog()
        }

        binding.btnMeshBeacon.setOnClickListener {
            UpdaterForegroundService.activeMeshManager?.triggerBeacon()
            Logger.i("Broadcast manual mesh beacon scan")
            Toast.makeText(this, "Beacon broadcast sent", Toast.LENGTH_SHORT).show()
        }

        binding.btnMeshUpdateAll.setOnClickListener {
            triggerMeshUpdateAll()
        }
    }

    private fun observeMeshDiscovery() {
        lifecycleScope.launch {
            while (isActive) {
                val meshManager = UpdaterForegroundService.activeMeshManager
                if (meshManager != null) {
                    val grouped = meshManager.getMeshesGrouped()
                    lastGroupedData = grouped
                    withContext(Dispatchers.Main) {
                        renderSidebarMeshes()
                        if (currentTab == NavTab.MESH) {
                            renderCurrentMeshView()
                        }
                    }
                }
                delay(2000)
            }
        }
    }

    private fun renderSidebarMeshes() {
        val grouped = lastGroupedData ?: return
        val meshesArray = grouped.optJSONArray("meshes") ?: return
        val localMeshId = grouped.optString("local_mesh_id", SettingsStore.getLocalMeshId(this))

        binding.layoutSidebarMeshes.removeAllViews()

        val inflater = layoutInflater
        for (i in 0 until meshesArray.length()) {
            val meshObj = meshesArray.optJSONObject(i) ?: continue
            val id = meshObj.optString("id")
            val name = meshObj.optString("name", id)
            val peerCount = meshObj.optInt("peer_count", 0)

            val itemBinding = ItemSidebarMeshBinding.inflate(inflater, binding.layoutSidebarMeshes, false)
            itemBinding.tvMeshTitle.text = name
            itemBinding.tvMeshNodeBadge.text = peerCount.toString()

            val isSelected = (currentTab == NavTab.MESH && (selectedMeshId == id || (selectedMeshId == null && id == localMeshId)))
            itemBinding.root.background = if (isSelected) ContextCompat.getDrawable(this, R.drawable.bg_rail_item_selected) else null

            val isTv = id.contains("tv", ignoreCase = true) || name.contains("tv", ignoreCase = true)
            if (isTv) {
                itemBinding.ivMeshIcon.setImageResource(R.drawable.ic_device_tv)
                itemBinding.layoutMeshDisc.setBackgroundResource(R.drawable.bg_disc_ochre)
            } else {
                itemBinding.ivMeshIcon.setImageResource(R.drawable.ic_device_tablet)
                itemBinding.layoutMeshDisc.setBackgroundResource(R.drawable.bg_disc_teal)
            }

            itemBinding.root.setOnClickListener {
                selectMesh(id)
            }

            binding.layoutSidebarMeshes.addView(itemBinding.root)
        }
    }

    private fun renderCurrentMeshView() {
        val grouped = lastGroupedData ?: UpdaterForegroundService.activeMeshManager?.getMeshesGrouped() ?: return
        val localMeshId = grouped.optString("local_mesh_id", SettingsStore.getLocalMeshId(this))
        val activeMeshId = selectedMeshId ?: localMeshId

        val meshesArray = grouped.optJSONArray("meshes")
        var targetMeshObj: JSONObject? = null

        if (meshesArray != null) {
            for (i in 0 until meshesArray.length()) {
                val m = meshesArray.optJSONObject(i) ?: continue
                if (m.optString("id") == activeMeshId) {
                    targetMeshObj = m
                    break
                }
            }
        }

        val meshName = targetMeshObj?.optString("name") ?: if (activeMeshId == localMeshId) SettingsStore.getLocalMeshName(this) else activeMeshId
        val isLocal = targetMeshObj?.optBoolean("is_local", activeMeshId == localMeshId) ?: (activeMeshId == localMeshId)
        val peerCount = targetMeshObj?.optInt("peer_count", 0) ?: 0
        val onlineCount = targetMeshObj?.optInt("online_count", 0) ?: 0

        binding.tvMeshViewTitle.text = "$meshName Mesh"
        binding.tvMeshViewSubtitle.text = "Partition: $activeMeshId • $onlineCount/$peerCount nodes online"
        binding.btnMeshUpdateAll.text = "Update All in $meshName"

        if (isLocal) {
            binding.tvMeshViewBadge.text = "Local Subnet"
            binding.tvMeshViewBadge.setTextColor(getColor(R.color.ks_teal))
            binding.tvMeshViewBadge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.ks_teal_container)
        } else {
            binding.tvMeshViewBadge.text = "Cross-VLAN Peered"
            binding.tvMeshViewBadge.setTextColor(getColor(R.color.ks_sage))
            binding.tvMeshViewBadge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.ks_sage_container)
        }

        val allPeers = UpdaterForegroundService.activeMeshManager?.peersFlow?.value ?: emptyList()
        val meshPeers = allPeers.filter { (it.meshId) == activeMeshId }

        renderMeshAppLibrary(activeMeshId, meshPeers)
        renderMeshPeerCards(meshPeers, activeMeshId)
    }

    private fun renderMeshAppLibrary(meshId: String, meshPeers: List<PeerNode>) {
        binding.layoutMeshAppLibrary.removeAllViews()

        val storedLibrary = SettingsStore.getMeshAppLibrary(this, meshId).toMutableMap()
        for (p in meshPeers) {
            for (app in p.installedApps) {
                if (!storedLibrary.containsKey(app.packageName)) {
                    storedLibrary[app.packageName] = SettingsStore.MeshAppConfig(
                        packageName = app.packageName,
                        appName = if (app.appName.isNotBlank()) app.appName else app.packageName,
                        managed = false,
                        autoInstall = false,
                        targetVersion = "latest",
                        autoUpdate = false,
                        isSideloaded = AppVersionHelper.isSideloadedApp(app.packageName)
                    )
                }
            }
        }

        val libraryList = storedLibrary.values.sortedBy { it.appName.lowercase() }
        binding.tvMeshLibraryAppCount.text = "${libraryList.size} Apps"

        if (libraryList.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "No apps discovered in this mesh partition."
                setTextColor(getColor(R.color.ks_outline))
                textSize = 12f
                setPadding(0, 8, 0, 8)
            }
            binding.layoutMeshAppLibrary.addView(emptyTv)
            return
        }

        val inflater = layoutInflater
        for (app in libraryList) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 8, 0, 8)
            }

            val infoLayout = LinearLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                orientation = LinearLayout.VERTICAL
            }

            val titleLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
            }

            val nameTv = TextView(this).apply {
                text = app.appName
                setTextColor(getColor(R.color.ks_on_surface))
                textSize = 13f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            titleLayout.addView(nameTv)

            val typeBadge = TextView(this).apply {
                text = if (app.isSideloaded) "Sideloaded" else "Store App"
                setTextColor(if (app.isSideloaded) getColor(R.color.ks_teal) else getColor(R.color.ks_outline))
                background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_badge_pill)
                backgroundTintList = ContextCompat.getColorStateList(
                    this@MainActivity,
                    if (app.isSideloaded) R.color.ks_teal_container else R.color.ks_surface_highest
                )
                setPadding(12, 2, 12, 2)
                textSize = 9f
                setPaddingRelative(12, 2, 12, 2)
                val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                params.marginStart = 12
                layoutParams = params
            }
            titleLayout.addView(typeBadge)
            infoLayout.addView(titleLayout)

            val pkgTv = TextView(this).apply {
                text = app.packageName
                setTextColor(getColor(R.color.ks_outline))
                textSize = 10f
            }
            infoLayout.addView(pkgTv)
            row.addView(infoLayout)

            // Managed Checkbox
            val chkManaged = android.widget.CheckBox(this).apply {
                text = "Managed"
                isChecked = app.managed
                setTextColor(getColor(R.color.ks_on_surface_variant))
                textSize = 11f
                setOnCheckedChangeListener { _, isChecked ->
                    val updated = app.copy(managed = isChecked)
                    SettingsStore.setMeshAppConfig(this@MainActivity, meshId, updated)
                    UpdaterForegroundService.activeMeshManager?.syncConfigToMesh()
                    renderCurrentMeshView()
                }
            }
            row.addView(chkManaged)

            binding.layoutMeshAppLibrary.addView(row)
        }
    }

    private fun renderMeshPeerCards(peers: List<PeerNode>, activeMeshId: String) {
        binding.layoutMeshPeers.removeAllViews()

        if (peers.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "No nodes detected in this mesh partition."
                setTextColor(getColor(R.color.ks_on_surface_variant))
                textSize = 14f
                setPadding(16, 32, 16, 32)
                gravity = android.view.Gravity.CENTER
            }
            binding.layoutMeshPeers.addView(emptyTv)
            return
        }

        val library = SettingsStore.getMeshAppLibrary(this, activeMeshId)
        val inflater = layoutInflater

        for (peer in peers) {
            val itemBinding = ItemMeshPeerBinding.inflate(inflater, binding.layoutMeshPeers, false)

            itemBinding.tvPeerTitle.text = peer.deviceModel
            itemBinding.tvPeerIpPort.text = "${peer.ip}:${peer.port}"

            val isTv = peer.deviceModel.contains("tv", ignoreCase = true) || peer.deviceModel.contains("onn", ignoreCase = true)
            itemBinding.ivPeerDeviceIcon.setImageResource(if (isTv) R.drawable.ic_device_tv else R.drawable.ic_device_tablet)

            itemBinding.tvPeerSelfBadge.visibility = if (peer.isSelf) View.VISIBLE else View.GONE
            itemBinding.tvPeerVlanBadge.visibility = if (peer.isCrossVlan) View.VISIBLE else View.GONE

            val isOnline = peer.isOnline
            if (isOnline) {
                if (peer.updaterState == "IDLE") {
                    itemBinding.tvPeerStateBadge.text = "IDLE"
                    itemBinding.tvPeerStateBadge.setTextColor(getColor(R.color.ks_sage))
                    itemBinding.tvPeerStateBadge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.ks_sage_container)
                } else if (peer.updaterState == "ERROR") {
                    itemBinding.tvPeerStateBadge.text = "ERROR"
                    itemBinding.tvPeerStateBadge.setTextColor(getColor(R.color.ks_rust))
                    itemBinding.tvPeerStateBadge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.ks_rust_container)
                } else {
                    itemBinding.tvPeerStateBadge.text = peer.updaterState
                    itemBinding.tvPeerStateBadge.setTextColor(getColor(R.color.ks_teal))
                    itemBinding.tvPeerStateBadge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.ks_teal_container)
                }
            } else {
                itemBinding.tvPeerStateBadge.text = "OFFLINE"
                itemBinding.tvPeerStateBadge.setTextColor(getColor(R.color.ks_outline))
                itemBinding.tvPeerStateBadge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.ks_surface_highest)
            }

            if (peer.adbEnabled) {
                itemBinding.tvPeerAdbStatus.text = "Enabled (:5555)"
                itemBinding.tvPeerAdbStatus.setTextColor(getColor(R.color.ks_sage))
            } else {
                itemBinding.tvPeerAdbStatus.text = "Disabled"
                itemBinding.tvPeerAdbStatus.setTextColor(getColor(R.color.ks_rust))
            }

            // Only show apps that have been marked as managed in the library
            val managedApps = peer.installedApps.filter { library[it.packageName]?.managed == true }

            val outOfDateApps = managedApps.filter { app ->
                val cfg = library[app.packageName]
                cfg != null && cfg.isSideloaded && cfg.targetVersion.isNotBlank() && AppVersionHelper.isVersionMismatch(app.versionName, cfg.targetVersion)
            }

            itemBinding.tvPeerAppsCountBadge.text = "${managedApps.size} Installed"
            if (outOfDateApps.isNotEmpty()) {
                itemBinding.tvPeerAppsActionBadge.visibility = View.VISIBLE
                itemBinding.tvPeerAppsActionBadge.text = "${outOfDateApps.size} Update Needed"
            } else {
                itemBinding.tvPeerAppsActionBadge.visibility = View.GONE
            }

            // Collapsible container setup
            val isExpanded = peerAppsExpandedState[peer.id] ?: false
            itemBinding.layoutPeerApps.visibility = if (isExpanded) View.VISIBLE else View.GONE
            itemBinding.tvPeerAppsChevron.text = if (isExpanded) "▲" else "▼"

            itemBinding.layoutPeerAppsHeader.setOnClickListener {
                val nextState = !(peerAppsExpandedState[peer.id] ?: false)
                peerAppsExpandedState[peer.id] = nextState
                itemBinding.layoutPeerApps.visibility = if (nextState) View.VISIBLE else View.GONE
                itemBinding.tvPeerAppsChevron.text = if (nextState) "▲" else "▼"
            }

            itemBinding.layoutPeerApps.removeAllViews()
            if (managedApps.isNotEmpty()) {
                for (app in managedApps) {
                    val cfg = library[app.packageName]
                    val isSideloaded = cfg?.isSideloaded ?: AppVersionHelper.isSideloadedApp(app.packageName)
                    val needsUpdate = cfg != null && cfg.isSideloaded && cfg.targetVersion.isNotBlank() && AppVersionHelper.isVersionMismatch(app.versionName, cfg.targetVersion)

                    val appRow = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        setPadding(0, 4, 0, 4)
                    }

                    val infoLayout = LinearLayout(this).apply {
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        orientation = LinearLayout.VERTICAL
                    }

                    val nameTv = TextView(this).apply {
                        text = app.appName.ifBlank { app.packageName }
                        setTextColor(getColor(R.color.ks_on_surface))
                        textSize = 13f
                    }
                    infoLayout.addView(nameTv)

                    val verPill = TextView(this).apply {
                        text = if (!app.versionName.isNullOrBlank()) "v${app.versionName}" else if (app.versionCode != null) "build ${app.versionCode}" else "Installed"
                        setTextColor(if (needsUpdate) getColor(R.color.ks_rust) else getColor(R.color.ks_teal))
                        background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_badge_pill)
                        backgroundTintList = ContextCompat.getColorStateList(
                            this@MainActivity,
                            if (needsUpdate) R.color.ks_rust_container else R.color.ks_teal_container
                        )
                        setPadding(16, 4, 16, 4)
                        textSize = 11f
                    }

                    appRow.addView(infoLayout)
                    appRow.addView(verPill)

                    if (needsUpdate && isSideloaded) {
                        val updateBtn = com.google.android.material.button.MaterialButton(
                            this,
                            null,
                            com.google.android.material.R.attr.materialButtonStyle
                        ).apply {
                            text = "Update"
                            textSize = 10f
                            setPadding(8, 2, 8, 2)
                            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                            params.marginStart = 8
                            layoutParams = params
                            setOnClickListener {
                                triggerRemoteAppUpdate(peer, app.packageName, cfg?.targetVersion ?: "latest")
                            }
                        }
                        appRow.addView(updateBtn)
                    }

                    itemBinding.layoutPeerApps.addView(appRow)
                }
            } else {
                val noAppsTv = TextView(this).apply {
                    text = "No managed apps installed"
                    setTextColor(getColor(R.color.ks_outline))
                    textSize = 12f
                }
                itemBinding.layoutPeerApps.addView(noAppsTv)
            }

            itemBinding.tvPeerMessage.text = peer.updaterMessage ?: "Status: Ready"
            itemBinding.tvPeerLastSeen.text = if (peer.isSelf) "Local" else if (peer.lastSeenSecondsAgo <= 5) "Just now" else "${peer.lastSeenSecondsAgo}s ago"

            if (!peer.isSelf) {
                itemBinding.layoutPeerActions.visibility = View.VISIBLE
                itemBinding.btnPeerToggleAdb.setOnClickListener {
                    toggleRemoteAdb(peer)
                }
                itemBinding.btnPeerUpdate.setOnClickListener {
                    triggerRemoteUpdate(peer)
                }
            } else {
                itemBinding.layoutPeerActions.visibility = View.GONE
            }

            binding.layoutMeshPeers.addView(itemBinding.root)
        }
    }

    private fun triggerRemoteAppUpdate(peer: PeerNode, packageName: String, targetVersion: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val queryParams = mutableListOf("force=true")
                if (targetVersion.isNotBlank() && targetVersion != "latest") {
                    queryParams.add("tag=${java.net.URLEncoder.encode(targetVersion, "UTF-8")}")
                }
                queryParams.add("package=${java.net.URLEncoder.encode(packageName, "UTF-8")}")
                val updateUrl = "http://${peer.ip}:${peer.port}/update?${queryParams.joinToString("&")}"

                val req = Request.Builder()
                    .url(updateUrl)
                    .post(ByteArray(0).toRequestBody(null, 0, 0))
                    .build()
                httpClient.newCall(req).execute().close()

                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Update dispatched for $packageName to ${peer.deviceModel}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Failed to update $packageName: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun triggerMeshUpdateAll() {
        val targetRelease = getSelectedRelease()
        val allPeers = UpdaterForegroundService.activeMeshManager?.peersFlow?.value ?: emptyList()
        val localMeshId = SettingsStore.getLocalMeshId(this)
        val activeMeshId = selectedMeshId ?: localMeshId
        val meshPeers = allPeers.filter { it.meshId == activeMeshId }

        Logger.i("Triggering Update All in mesh partition $activeMeshId (${meshPeers.size} nodes)")

        val selfNode = meshPeers.firstOrNull { it.isSelf }
        if (selfNode != null && targetRelease != null) {
            val activeCoordinator = coordinator ?: UpdateCoordinator(this)
            activeCoordinator.startUpdateForRelease(targetRelease, force = true)
        }

        val remotes = meshPeers.filter { !it.isSelf && it.isOnline }
        if (remotes.isNotEmpty()) {
            lifecycleScope.launch(Dispatchers.IO) {
                for (peer in remotes) {
                    try {
                        val tag = targetRelease?.tagName ?: "latest"
                        val url = "http://${peer.ip}:${peer.port}/update?force=true&tag=$tag"
                        val request = Request.Builder()
                            .url(url)
                            .post(ByteArray(0).toRequestBody(null, 0, 0))
                            .build()
                        httpClient.newCall(request).execute().close()
                        Logger.i("Dispatched update to ${peer.deviceModel} (${peer.ip})")
                    } catch (e: Exception) {
                        Logger.e("Error updating ${peer.ip}: ${e.message}")
                    }
                }
            }
        }
        Toast.makeText(this, "Update broadcast sent to all nodes in mesh", Toast.LENGTH_SHORT).show()
    }

    private fun toggleRemoteAdb(peer: PeerNode) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val url = "http://${peer.ip}:${peer.port}/api/peers/adb/toggle"
                val payload = JSONObject().apply {
                    put("ip", peer.ip)
                    put("port", peer.port)
                }
                val request = Request.Builder()
                    .url(url)
                    .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()
                httpClient.newCall(request).execute().close()
                Logger.i("Toggled ADB on ${peer.ip}")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Toggled ADB on ${peer.deviceModel}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Logger.e("Failed to toggle ADB on ${peer.ip}: ${e.message}")
            }
        }
    }

    private fun triggerRemoteUpdate(peer: PeerNode) {
        val targetRelease = getSelectedRelease()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val tag = targetRelease?.tagName ?: "latest"
                val url = "http://${peer.ip}:${peer.port}/update?force=true&tag=$tag"
                val request = Request.Builder()
                    .url(url)
                    .post(ByteArray(0).toRequestBody(null, 0, 0))
                    .build()
                httpClient.newCall(request).execute().close()
                Logger.i("Dispatched update to ${peer.deviceModel} (${peer.ip})")
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Update dispatched to ${peer.deviceModel}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Logger.e("Failed to update peer ${peer.ip}: ${e.message}")
            }
        }
    }

    private fun showConnectVlanDialog() {
        val dialogBinding = DialogConnectVlanBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()

        dialogBinding.btnCancelConnect.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnSubmitConnect.setOnClickListener {
            val ip = dialogBinding.etSeedIpInput.text?.toString()?.trim() ?: ""
            if (ip.isNotBlank()) {
                dialogBinding.btnSubmitConnect.isEnabled = false
                val meshManager = UpdaterForegroundService.activeMeshManager
                meshManager?.addCrossVlanSeed(ip, reciprocal = true)
                Logger.i("Added cross-VLAN seed: $ip")
                Toast.makeText(this, "Connecting to $ip...", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                refreshSettingsUI()
            }
        }

        dialog.show()
    }

    private fun setupOverviewUI() {
        binding.btnRefreshOverview.setOnClickListener {
            refreshStatus()
            loadAvailableReleases(forceRefresh = true)
        }

        binding.btnToggleAdb.setOnClickListener {
            com.cfox.droidmesh.utils.AdbHelper.toggleAdb(this)
            refreshStatus()
        }

        binding.btnTriggerUpdate.setOnClickListener {
            val release = getSelectedRelease()
            if (release != null) {
                val activeCoordinator = coordinator ?: UpdateCoordinator(this)
                val force = binding.chkForceUpdate.isChecked
                activeCoordinator.startUpdateForRelease(release, force = force)
                Logger.i("Triggered local install for ${release.tagName} (force=$force)")
            }
        }

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
            com.cfox.droidmesh.utils.AdbHelper.toggleAdb(this)
            refreshStatus()
        }

        binding.spnVersionToInstall.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedReleaseIndex = position
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun refreshStatus() {
        val installed = AppVersionHelper.getInstalledVersion(this)
        if (installed.isInstalled) {
            binding.tvOverviewTargetBadge.text = "Installed"
            binding.tvOverviewTargetBadge.setTextColor(getColor(R.color.ks_sage))
            binding.tvOverviewTargetBadge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.ks_sage_container)
            binding.tvOverviewInstalledVer.text = "Installed: v${installed.versionName}"
        } else {
            binding.tvOverviewTargetBadge.text = "Not Installed"
            binding.tvOverviewTargetBadge.setTextColor(getColor(R.color.ks_ochre))
            binding.tvOverviewTargetBadge.backgroundTintList = ContextCompat.getColorStateList(this, R.color.ks_ochre_container)
            binding.tvOverviewInstalledVer.text = "Installed: None"
        }

        val isA11y = AutoInstallService.isServiceRunning
        binding.tvOverviewA11y.text = "A11y: ${if (isA11y) "Active (Auto-Click Ready)" else "Disabled"}"
        binding.tvAccessibilityStatus.text = "Accessibility Service: ${if (isA11y) "ACTIVE" else "DISABLED (Tap to enable)"}"
        binding.tvAccessibilityStatus.setTextColor(getColor(if (isA11y) R.color.ks_sage else R.color.ks_rust))

        val isAdb = com.cfox.droidmesh.utils.AdbHelper.isAdbEnabled(this)
        binding.tvOverviewAdb.text = "ADB :5555: ${if (isAdb) "Enabled" else "Disabled"}"
        binding.tvAdbStatus.text = "ADB Debugging: ${if (isAdb) "ENABLED" else "DISABLED (Tap to toggle)"}"
        binding.tvAdbStatus.setTextColor(getColor(if (isAdb) R.color.ks_sage else R.color.ks_rust))
        binding.btnToggleAdb.text = if (isAdb) "Disable ADB" else "Enable ADB"

        val canInstall = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) packageManager.canRequestPackageInstalls() else true
        binding.tvInstallPermissionStatus.text = "Install Unknown Apps: ${if (canInstall) "GRANTED" else "NOT GRANTED (Tap to grant)"}"
        binding.tvInstallPermissionStatus.setTextColor(getColor(if (canInstall) R.color.ks_sage else R.color.ks_ochre))

        val ip = getLocalIpAddress() ?: "127.0.0.1"
        binding.tvOverviewIp.text = "IP: $ip"
        binding.tvOverviewModelBadge.text = Build.MODEL
    }

    private fun loadAvailableReleases(forceRefresh: Boolean = false) {
        lifecycleScope.launch {
            val activeCoordinator = coordinator ?: UpdateCoordinator(this@MainActivity)
            val result = activeCoordinator.fetchAvailableReleases(forceRefresh = forceRefresh)
            if (result.isSuccess) {
                availableReleases = result.getOrThrow()
                populateVersionSpinner()
                val latest = availableReleases.firstOrNull()?.tagName
                binding.tvOverviewLatestVer.text = "Latest: ${latest ?: "None"}"
            }
        }
    }

    private fun populateVersionSpinner() {
        val options = mutableListOf<String>()
        val latestTag = availableReleases.firstOrNull()?.tagName
        if (latestTag != null) options.add("Latest ($latestTag)") else options.add("Latest")

        for (rel in availableReleases) {
            options.add(rel.tagName)
        }

        val adapter = ArrayAdapter(this, R.layout.item_version_dropdown, options)
        adapter.setDropDownViewResource(R.layout.item_version_dropdown)
        binding.spnVersionToInstall.adapter = adapter
    }

    private fun getSelectedRelease(): ReleaseInfo? {
        if (availableReleases.isEmpty()) return null
        return if (selectedReleaseIndex == 0) availableReleases.firstOrNull() else {
            val idx = selectedReleaseIndex - 1
            if (idx in availableReleases.indices) availableReleases[idx] else availableReleases.firstOrNull()
        }
    }

    private fun setupSettingsUI() {
        binding.btnSaveMeshIdentity.setOnClickListener {
            val meshId = binding.etSettingMeshId.text?.toString()?.trim() ?: ""
            val meshName = binding.etSettingMeshName.text?.toString()?.trim() ?: ""
            if (meshId.isNotBlank() && meshName.isNotBlank()) {
                SettingsStore.setLocalMeshId(this, meshId)
                SettingsStore.setLocalMeshName(this, meshName)
                selectedMeshId = meshId
                Logger.i("Saved mesh identity: $meshId ($meshName)")
                Toast.makeText(this, "Mesh identity updated", Toast.LENGTH_SHORT).show()
                refreshSettingsUI()
                renderCurrentMeshView()
            }
        }

        binding.btnAddSeedIp.setOnClickListener {
            showConnectVlanDialog()
        }

        binding.switchAutoUpdate.setOnCheckedChangeListener { _, isChecked ->
            SettingsStore.setAutoUpdateEnabled(this, isChecked)
            Logger.i("Auto-update preference set to $isChecked")
        }

        binding.btnSaveWebServerPort.setOnClickListener {
            val portText = binding.etWebServerPort.text?.toString()?.trim() ?: ""
            val port = portText.toIntOrNull()
            if (port != null && port in 1024..65535) {
                SettingsStore.setWebServerPort(this, port)
                UpdaterForegroundService.startService(this)
                Logger.i("Web server port set to $port")
                Toast.makeText(this, "Port set to $port", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Invalid port (1024-65535)", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnSaveAdminPassword.setOnClickListener {
            val pass = binding.etAdminPassword.text?.toString() ?: ""
            if (pass.isNotBlank()) {
                SettingsStore.setPassword(this, pass)
                binding.etAdminPassword.setText("")
                Logger.i("Admin password configured")
                refreshSettingsUI()
                Toast.makeText(this, "Admin password saved", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnClearAdminPassword.setOnClickListener {
            SettingsStore.clearPassword(this)
            binding.etAdminPassword.setText("")
            Logger.i("Admin password removed")
            refreshSettingsUI()
            Toast.makeText(this, "Admin password removed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshSettingsUI() {
        binding.etSettingMeshId.setText(SettingsStore.getLocalMeshId(this))
        binding.etSettingMeshName.setText(SettingsStore.getLocalMeshName(this))
        binding.switchAutoUpdate.isChecked = SettingsStore.isAutoUpdateEnabled(this)
        binding.etWebServerPort.setText(SettingsStore.getWebServerPort(this).toString())

        val isPassSet = SettingsStore.isPasswordSet(this)
        if (isPassSet) {
            binding.tvPasswordStatusBadge.text = "Status: Password Configured (Protected)"
            binding.tvPasswordStatusBadge.setTextColor(getColor(R.color.ks_sage))
        } else {
            binding.tvPasswordStatusBadge.text = "Status: No password set (Open Access)"
            binding.tvPasswordStatusBadge.setTextColor(getColor(R.color.ks_ochre))
        }

        binding.layoutSeedList.removeAllViews()
        val seeds = SettingsStore.getCrossVlanSeeds(this)
        if (seeds.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "No cross-VLAN seeds configured."
                setTextColor(getColor(R.color.ks_outline))
                textSize = 13f
                setPadding(0, 4, 0, 4)
            }
            binding.layoutSeedList.addView(emptyTv)
        } else {
            val inflater = layoutInflater
            for (seed in seeds) {
                val seedBinding = ItemSeedRowBinding.inflate(inflater, binding.layoutSeedList, false)
                seedBinding.tvSeedIp.text = seed
                seedBinding.btnRemoveSeed.setOnClickListener {
                    val meshManager = UpdaterForegroundService.activeMeshManager
                    meshManager?.removeCrossVlanSeed(seed)
                    SettingsStore.removeCrossVlanSeed(this, seed)
                    refreshSettingsUI()
                    Logger.i("Removed seed $seed")
                }
                binding.layoutSeedList.addView(seedBinding.root)
            }
        }

        val selfInfo = AppVersionHelper.getInstalledVersion(this, packageName)
        binding.tvAboutAppVersion.text = "Version: ${selfInfo.versionName ?: "0.0.1"} (build ${selfInfo.versionCode ?: 1})"
        binding.tvAboutPackage.text = "Package: $packageName"
    }

    private fun setupLogsUI() {
        binding.btnCopyLogs.setOnClickListener {
            val logs = Logger.getRecentLogs().joinToString("\n")
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("DroidMesh Logs", logs))
            Toast.makeText(this, "Logs copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        binding.btnClearLogs.setOnClickListener {
            Logger.clear()
            binding.tvLogConsole.text = "[Ready] Log history cleared."
            binding.tvLogsMeta.text = "0 entries"
        }

        binding.btnRefreshLogs.setOnClickListener {
            renderLogs()
        }

        binding.etLogFilter.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { renderLogs() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }

    private fun renderLogs() {
        val filter = binding.etLogFilter.text?.toString()?.lowercase() ?: ""
        val allLogs = Logger.getRecentLogs()
        val filtered = if (filter.isBlank()) allLogs else allLogs.filter { it.lowercase().contains(filter) }

        binding.tvLogsMeta.text = "${filtered.size} entries"
        binding.tvLogConsole.text = if (filtered.isEmpty()) "[No log entries matched filter]" else filtered.joinToString("\n")

        if (binding.chkAutoScroll.isChecked) {
            scrollLogsToBottom()
        }
    }

    private fun scrollLogsToBottom() {
        binding.scrollLogConsole.post {
            binding.scrollLogConsole.fullScroll(View.FOCUS_DOWN)
        }
    }

    private fun observeLogs() {
        lifecycleScope.launch {
            renderLogs()
            Logger.logFlow.collect {
                withContext(Dispatchers.Main) {
                    renderLogs()
                }
            }
        }
    }

    private fun observeCoordinator() {
        val activeCoordinator = coordinator ?: return
        lifecycleScope.launch {
            activeCoordinator.statusFlow.collect { status ->
                binding.tvOverviewServiceBadge.text = status.state
                when (status.state) {
                    "DOWNLOADING" -> {
                        binding.progressBar.visibility = View.VISIBLE
                        binding.tvProgressText.visibility = View.VISIBLE
                        binding.progressBar.isIndeterminate = false
                        binding.progressBar.progress = status.progressPercent
                        binding.tvProgressText.text = status.message
                        binding.btnTriggerUpdate.isEnabled = false
                    }
                    "INSTALLING", "CHECKING" -> {
                        binding.progressBar.visibility = View.VISIBLE
                        binding.tvProgressText.visibility = View.VISIBLE
                        binding.progressBar.isIndeterminate = true
                        binding.tvProgressText.text = status.message
                        binding.btnTriggerUpdate.isEnabled = false
                    }
                    "COMPLETED" -> {
                        binding.progressBar.visibility = View.GONE
                        binding.tvProgressText.visibility = View.VISIBLE
                        binding.tvProgressText.text = status.message
                        binding.btnTriggerUpdate.isEnabled = true
                        refreshStatus()
                    }
                    "ERROR" -> {
                        binding.progressBar.visibility = View.GONE
                        binding.tvProgressText.visibility = View.VISIBLE
                        binding.tvProgressText.text = "Error: ${status.message}"
                        binding.btnTriggerUpdate.isEnabled = true
                    }
                    else -> {
                        binding.progressBar.visibility = View.GONE
                        binding.tvProgressText.visibility = View.GONE
                        binding.btnTriggerUpdate.isEnabled = true
                    }
                }
            }
        }
    }

    private fun startCpuTelemetryLoop() {
        lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                val telemetry = CpuStatsHelper.readTelemetry()
                withContext(Dispatchers.Main) {
                    binding.tvStatCpu.text = telemetry.usageDisplay
                    binding.tvStatTemp.text = telemetry.tempDisplay
                    binding.tvOverviewCpu.text = "CPU: ${telemetry.usageDisplay}"
                }
                delay(3000)
            }
        }
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (_: Exception) {}
        return null
    }
}
