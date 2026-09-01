package com.cfox.droidmesh.mesh

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import com.cfox.droidmesh.installer.AppVersionHelper
import com.cfox.droidmesh.server.UpdateCoordinator
import com.cfox.droidmesh.settings.SettingsStore
import com.cfox.droidmesh.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class MeshDiscoveryManager(
    private val context: Context,
    private val coordinator: UpdateCoordinator,
    private val scope: CoroutineScope
) {
    companion object {
        const val MESH_PORT = 23250
        const val BEACON_INTERVAL_MS = 8000L
        const val PEER_EXPIRATION_MS = 30000L
        const val PERSISTENT_CONNECTION_SYNC_INTERVAL_MS = 12000L
        @Deprecated("Use PERSISTENT_CONNECTION_SYNC_INTERVAL_MS")
        const val CROSS_VLAN_SYNC_INTERVAL_MS = PERSISTENT_CONNECTION_SYNC_INTERVAL_MS
        private const val TAG = "MeshDiscovery"
    }

    private val _peersFlow = MutableStateFlow<List<PeerNode>>(emptyList())
    val peersFlow: StateFlow<List<PeerNode>> = _peersFlow.asStateFlow()

    private val peersMap = ConcurrentHashMap<String, PeerNode>()
    private var multicastLock: WifiManager.MulticastLock? = null
    private var rxSocket: DatagramSocket? = null

    private var txJob: Job? = null
    private var rxJob: Job? = null
    private var pruneJob: Job? = null
    private var discoveryJob: Job? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .build()

    val localMeshId: String
        get() = SettingsStore.getLocalMeshId(context)

    val localMeshName: String
        get() = SettingsStore.getLocalMeshName(context)

    val deviceId: String by lazy {
        try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                ?: Build.SERIAL
                ?: "portal-${System.currentTimeMillis() % 10000}"
        } catch (e: Exception) {
            "portal-${Build.MODEL}-${System.currentTimeMillis() % 10000}"
        }
    }

    val deviceModel: String by lazy {
        if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) {
            Build.MODEL
        } else {
            "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        }
    }

    fun getDisplayName(): String = com.cfox.droidmesh.utils.CpuStatsHelper.getDeviceName(context)

    fun start() {
        Logger.i("Starting MeshDiscoveryManager on UDP port $MESH_PORT (Device ID: $deviceId, Model: $deviceModel, Mesh: $localMeshId ($localMeshName))")
        acquireMulticastLock()
        startReceiver()
        startTransmitter()
        startPruner()
        startPersistentConnectionSyncer()
    }

    fun stop() {
        Logger.i("Stopping MeshDiscoveryManager")
        txJob?.cancel()
        rxJob?.cancel()
        pruneJob?.cancel()
        discoveryJob?.cancel()

        try {
            rxSocket?.close()
        } catch (e: Exception) {
            Logger.e("Error closing RX socket", e)
        }
        rxSocket = null

        releaseMulticastLock()
    }

    fun triggerBeacon() {
        scope.launch(Dispatchers.IO) {
            sendBeacon()
        }
    }

    private fun acquireMulticastLock() {
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("ks:mesh-udp")?.apply {
                setReferenceCounted(true)
                acquire()
            }
            Logger.i("Acquired Wi-Fi MulticastLock for mesh discovery")
        } catch (e: Exception) {
            Logger.e("Failed to acquire MulticastLock", e)
        }
    }

    private fun releaseMulticastLock() {
        try {
            multicastLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (e: Exception) {
            Logger.e("Error releasing MulticastLock", e)
        }
        multicastLock = null
    }

    private fun startReceiver() {
        rxJob = scope.launch(Dispatchers.IO) {
            try {
                val socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(MESH_PORT))
                }
                rxSocket = socket

                val buffer = ByteArray(4096)
                Logger.i("Mesh receiver listening on UDP 0.0.0.0:$MESH_PORT")

                while (isActive && !socket.isClosed) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                        val senderIp = packet.address.hostAddress ?: continue
                        val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                        handleIncomingPacket(text, senderIp)
                    } catch (e: Exception) {
                        if (socket.isClosed || !isActive) break
                        Logger.e("Mesh UDP receive error", e)
                    }
                }
            } catch (e: Exception) {
                Logger.e("Failed to start mesh receiver socket on port $MESH_PORT", e)
            }
        }
    }

    private fun handleIncomingPacket(payload: String, senderIp: String) {
        try {
            val json = JSONObject(payload)
            if (json.optString("type") != "ks_mesh_beacon") return

            val senderId = json.optString("id", senderIp)
            if (senderId == deviceId) {
                // Ignore self loopback beacon
                return
            }

            val peer = PeerNode.fromBeaconJson(json, senderIp) ?: return
            peersMap[peer.id] = peer
            updatePeersList()

            // Check config version and synchronize if needed
            val peerConfigVersion = peer.configVersion
            val localConfigVersion = SettingsStore.getConfigVersion(context)
            if (peerConfigVersion > localConfigVersion) {
                scope.launch(Dispatchers.IO) {
                    pullConfigFromPeer(peer.ip, peer.port)
                }
            } else if (peerConfigVersion < localConfigVersion && localConfigVersion > 0L) {
                scope.launch(Dispatchers.IO) {
                    pushConfigToPeer(peer.ip, peer.port)
                }
            }
        } catch (e: Exception) {
            Logger.e("Error parsing mesh beacon from $senderIp", e)
        }
    }

    private fun startTransmitter() {
        txJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                sendBeacon()
                delay(BEACON_INTERVAL_MS)
            }
        }
    }

    fun syncConfigToMesh() {
        scope.launch(Dispatchers.IO) {
            sendBeacon()
            val configJson = SettingsStore.exportConfigJson(context)
            val targets = mutableSetOf<String>()
            for (peer in _peersFlow.value) {
                if (!peer.isSelf && peer.isOnline) {
                    targets.add("${peer.ip}:${peer.port}")
                }
            }
            for (seed in SettingsStore.getCrossVlanSeeds(context)) {
                targets.add(normalizeSeedAddress(seed))
            }
            for (target in targets) {
                val host = target.substringBefore(":")
                val port = target.substringAfter(":", "2325").toIntOrNull() ?: 2325
                pushConfigToPeer(host, port, configJson)
            }
        }
    }

    suspend fun pullConfigFromPeer(ip: String, port: Int) = withContext(Dispatchers.IO) {
        try {
            val url = "http://$ip:$port/api/mesh/config"
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "DroidMesh-ConfigSync")
                .build()
            httpClient.newCall(req).execute().use { res ->
                if (res.isSuccessful) {
                    val body = res.body?.string() ?: return@use
                    val json = JSONObject(body)
                    val config = json.optJSONObject("config") ?: json
                    val result = SettingsStore.importConfigJson(context, config)
                    if (result.applied) {
                        Logger.i("Pulled newer mesh config v${result.newVersion} from $ip:$port (portChanged=${result.portChanged}, pwdChanged=${result.passwordChanged}, seedsChanged=${result.seedsChanged})")
                        sendBeacon()
                    }
                }
            }
        } catch (e: Exception) {
            Logger.w("Failed to pull config from $ip:$port: ${e.message}")
        }
    }

    suspend fun pushConfigToPeer(ip: String, port: Int, configJson: JSONObject = SettingsStore.exportConfigJson(context)) = withContext(Dispatchers.IO) {
        try {
            val url = "http://$ip:$port/api/mesh/sync-config"
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val req = Request.Builder()
                .url(url)
                .post(configJson.toString().toRequestBody(mediaType))
                .header("User-Agent", "DroidMesh-ConfigSync")
                .build()
            httpClient.newCall(req).execute().use { res ->
                if (res.isSuccessful) {
                    Logger.i("Successfully pushed config v${configJson.optLong("config_version")} to $ip:$port")
                }
            }
        } catch (e: Exception) {
            Logger.w("Failed to push config to $ip:$port: ${e.message}")
        }
    }

    private fun sendBeacon() {
        try {
            val installed = AppVersionHelper.getInstalledVersion(context)
            val installedApps = AppVersionHelper.getUserInstalledApps(context)
            val currentStatus = coordinator.statusFlow.value
            val localIp = getLocalIpAddress() ?: "127.0.0.1"
            val activePort = SettingsStore.getWebServerPort(context)

            val json = JSONObject().apply {
                put("type", "ks_mesh_beacon")
                put("id", deviceId)
                put("ip", localIp)
                put("port", activePort)
                put("meshId", localMeshId)
                put("meshName", localMeshName)
                put("deviceModel", deviceModel)
                put("displayName", getDisplayName())
                put("config_version", SettingsStore.getConfigVersion(context))
                put("targetInstalled", installed.isInstalled)
                put("installedVersionName", installed.versionName ?: JSONObject.NULL)
                put("installedVersionCode", installed.versionCode ?: JSONObject.NULL)

                val appsArray = JSONArray()
                for (app in installedApps) {
                    appsArray.put(JSONObject().apply {
                        put("packageName", app.packageName)
                        put("appName", app.appName)
                        put("versionName", app.versionName ?: JSONObject.NULL)
                        put("versionCode", app.versionCode ?: JSONObject.NULL)
                    })
                }
                put("installedApps", appsArray)

                put("updaterState", currentStatus.state)
                put("updaterMessage", currentStatus.message)
                put("adbEnabled", com.cfox.droidmesh.utils.AdbHelper.isAdbEnabled(context))
                put("timestamp", System.currentTimeMillis())
            }

            val bytes = json.toString().toByteArray(Charsets.UTF_8)
            val broadcastTargets = getBroadcastAddresses()

            DatagramSocket().use { socket ->
                socket.broadcast = true
                for (target in broadcastTargets) {
                    try {
                        val packet = DatagramPacket(bytes, bytes.size, target, MESH_PORT)
                        socket.send(packet)
                    } catch (e: Exception) {
                        // ignore target-specific send error
                    }
                }
            }

            // Always update self in the peers view
            updatePeersList()
        } catch (e: Exception) {
            Logger.e("Error sending mesh beacon", e)
        }
    }

    private fun startPersistentConnectionSyncer() {
        discoveryJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val connections = SettingsStore.getPersistentConnections(context)
                for (connection in connections) {
                    if (!isActive) break
                    try {
                        syncWithPersistentConnection(connection)
                    } catch (e: Exception) {
                        Logger.w("Failed persistent connection sync with $connection: ${e.message}")
                    }
                }
                delay(PERSISTENT_CONNECTION_SYNC_INTERVAL_MS)
            }
        }
    }

    suspend fun syncWithPersistentConnection(connection: String) = withContext(Dispatchers.IO) {
        val normalized = normalizeConnectionAddress(connection)
        val url = "http://$normalized/api/mesh"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "DroidMesh-Discovery")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code} from connection $connection")
            }
            val body = response.body?.string() ?: return@use
            val json = JSONObject(body)
            val peersArray = json.optJSONArray("peers")
            if (peersArray != null) {
                ingestRemotePeers(peersArray, connection)
            }

            // Check remote config version
            val remoteConfigVer = json.optLong("config_version", 0L)
            val localConfigVer = SettingsStore.getConfigVersion(context)
            val host = normalized.substringBefore(":")
            val port = normalized.substringAfter(":", "2325").toIntOrNull() ?: 2325
            if (remoteConfigVer > localConfigVer) {
                pullConfigFromPeer(host, port)
            } else if (remoteConfigVer < localConfigVer && localConfigVer > 0L) {
                pushConfigToPeer(host, port)
            }
        }
    }

    // Backward compatibility: alias for renamed method
    suspend fun syncWithConnection(connection: String) = syncWithPersistentConnection(connection)

    @Deprecated("Use syncWithPersistentConnection")
    suspend fun syncWithSeed(seed: String) = syncWithPersistentConnection(seed)

    fun addPersistentConnection(rawIp: String, reciprocal: Boolean = true): Result<String> {
        val normalized = normalizeConnectionAddress(rawIp)
        SettingsStore.addPersistentConnection(context, normalized)

        // Launch immediate handshake in background
        scope.launch(Dispatchers.IO) {
            try {
                performDiscoveryHandshake(normalized, reciprocal)
            } catch (e: Exception) {
                Logger.e("Handshake failed with persistent connection $normalized", e)
            }
        }

        return Result.success(normalized)
    }

    // Backward compatibility: aliases for renamed methods
    fun addDiscoveredDevice(rawIp: String, reciprocal: Boolean = true): Result<String> = addPersistentConnection(rawIp, reciprocal)

    @Deprecated("Use addPersistentConnection")
    fun addCrossVlanSeed(rawIp: String, reciprocal: Boolean = true): Result<String> = addPersistentConnection(rawIp, reciprocal)

    private suspend fun performDiscoveryHandshake(connection: String, reciprocal: Boolean) = withContext(Dispatchers.IO) {
        val normalized = normalizeConnectionAddress(connection)
        val localIp = getLocalIpAddress() ?: "127.0.0.1"
        val handshakeUrl = "http://$normalized/api/mesh/handshake"
        val activePort = SettingsStore.getWebServerPort(context)

        val payload = JSONObject().apply {
            put("sender_ip", localIp)
            put("sender_port", activePort)
            put("mesh_id", localMeshId)
            put("mesh_name", localMeshName)
            put("device_id", deviceId)
            put("device_model", deviceModel)
            put("config_version", SettingsStore.getConfigVersion(context))
            put("config", SettingsStore.exportConfigJson(context))
            put("reciprocal", reciprocal)
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val request = Request.Builder()
            .url(handshakeUrl)
            .post(payload.toString().toRequestBody(mediaType))
            .header("User-Agent", "DroidMesh-Handshake")
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val body = response.body?.string()
                if (!body.isNullOrBlank()) {
                    val json = JSONObject(body)
                    val peersArray = json.optJSONArray("peers")
                    if (peersArray != null) {
                        ingestRemotePeers(peersArray, normalized)
                    }
                    val remoteConfig = json.optJSONObject("config")
                    if (remoteConfig != null) {
                        SettingsStore.importConfigJson(context, remoteConfig)
                    }
                }
                Logger.i("Successfully performed handshake with discovered device $normalized")
            } else {
                Logger.w("Handshake returned HTTP ${response.code} from discovered device $normalized")
            }
        }
    }

    fun handleIncomingHandshake(json: JSONObject, senderIp: String): JSONObject {
        val remoteSenderIp = json.optString("sender_ip", senderIp)
        val remotePort = json.optInt("sender_port", 2325)
        val remoteConnection = "$remoteSenderIp:$remotePort"
        val reciprocal = json.optBoolean("reciprocal", false)

        // Automatically persist remote peer as persistent connection for power outage resilience
        SettingsStore.addPersistentConnection(context, remoteConnection)
        Logger.i("Registered incoming handshake from $remoteConnection (Mesh: ${json.optString("mesh_id")})")

        // Import config if provided
        val incomingConfig = json.optJSONObject("config")
        if (incomingConfig != null) {
            val result = SettingsStore.importConfigJson(context, incomingConfig)
            if (result.applied) {
                Logger.i("Imported config v${result.newVersion} during incoming handshake from $remoteConnection")
            }
        }

        // Trigger reciprocal connect if requested, and broadcast immediately to announce ourselves
        if (reciprocal) {
            scope.launch(Dispatchers.IO) {
                try {
                    syncWithConnection(remoteConnection)
                } catch (e: Exception) {
                    Logger.w("Reciprocal sync failed with $remoteConnection: ${e.message}")
                }
            }
        }

        // Immediately send a beacon so the remote peer knows this device is now aware of them
        triggerBeacon()

        val res = getMeshesGrouped()
        res.put("config", SettingsStore.exportConfigJson(context))
        return res
    }

    fun removePersistentConnection(rawIp: String): Boolean {
        val normalized = normalizeConnectionAddress(rawIp)
        val cleanHost = normalized.substringBefore(":")
        val removed = SettingsStore.removePersistentConnection(context, normalized) ||
                      SettingsStore.removePersistentConnection(context, cleanHost) ||
                      SettingsStore.removePersistentConnection(context, rawIp.trim())

        // Prune peers matching this connection host
        val iterator = peersMap.entries.iterator()
        var changed = false
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.isDiscoveredPeer && (entry.value.ip == cleanHost || entry.value.ip == normalized)) {
                iterator.remove()
                changed = true
            }
        }
        if (changed) {
            updatePeersList()
        }
        return removed
    }

    // Backward compatibility aliases
    fun removeDiscoveredDevice(rawIp: String): Boolean = removePersistentConnection(rawIp)

    @Deprecated("Use removePersistentConnection")
    fun removeCrossVlanSeed(rawIp: String): Boolean = removePersistentConnection(rawIp)

    fun ingestRemotePeers(peersArray: JSONArray, seed: String) {
        var changed = false
        val now = System.currentTimeMillis()
        for (i in 0 until peersArray.length()) {
            val peerJson = peersArray.optJSONObject(i) ?: continue
            val id = peerJson.optString("id")
            if (id.isBlank() || id == deviceId) continue

            val ip = peerJson.optString("ip", seed.substringBefore(":"))
            val port = peerJson.optInt("port", 2325)
            val model = peerJson.optString("deviceModel", "Remote Node")
            val displayName = peerJson.optString("displayName", "")
            val meshId = peerJson.optString("meshId", peerJson.optString("mesh_id", "default"))
            val meshName = peerJson.optString("meshName", peerJson.optString("mesh_name", meshId))
            val targetInstalled = peerJson.optBoolean("targetInstalled", false)
            val installedVersionName = if (peerJson.isNull("installedVersionName")) null else peerJson.optString("installedVersionName")
            val installedVersionCode = if (peerJson.isNull("installedVersionCode")) null else peerJson.optLong("installedVersionCode")

            val appsList = mutableListOf<AppVersionHelper.InstalledAppInfo>()
            val appsJsonArray = peerJson.optJSONArray("installedApps") ?: peerJson.optJSONArray("installed_apps")
            if (appsJsonArray != null) {
                for (j in 0 until appsJsonArray.length()) {
                    val appObj = appsJsonArray.optJSONObject(j) ?: continue
                    val pkgName = appObj.optString("packageName", appObj.optString("package_name", ""))
                    if (pkgName.isNotBlank()) {
                        appsList.add(
                            AppVersionHelper.InstalledAppInfo(
                                packageName = pkgName,
                                appName = appObj.optString("appName", appObj.optString("app_name", pkgName)),
                                versionName = if (appObj.isNull("versionName")) null else appObj.optString("versionName"),
                                versionCode = if (appObj.isNull("versionCode")) null else appObj.optLong("versionCode")
                            )
                        )
                    }
                }
            }
            if (appsList.isEmpty() && targetInstalled) {
                appsList.add(
                    AppVersionHelper.InstalledAppInfo(
                        packageName = AppVersionHelper.TARGET_PACKAGE,
                        appName = "Kiosk Satellite",
                        versionName = installedVersionName,
                        versionCode = installedVersionCode
                    )
                )
            }

            val updaterState = peerJson.optString("updaterState", "IDLE")
            val updaterMessage = if (peerJson.isNull("updaterMessage")) null else peerJson.optString("updaterMessage")
            val adbEnabled = peerJson.optBoolean("adbEnabled", false)

            val remotePeer = PeerNode(
                id = id,
                ip = ip,
                port = port,
                deviceModel = model,
                displayName = displayName,
                targetInstalled = targetInstalled,
                installedVersionName = installedVersionName,
                installedVersionCode = installedVersionCode,
                installedApps = appsList,
                updaterState = updaterState,
                updaterMessage = updaterMessage,
                adbEnabled = adbEnabled,
                lastSeenTimestamp = now,
                isSelf = false,
                meshId = meshId,
                meshName = meshName,
                isDiscoveredPeer = true
            )
            peersMap[id] = remotePeer
            changed = true
        }
        if (changed) {
            updatePeersList()
        }
    }

    private fun normalizeConnectionAddress(raw: String): String {
        val trimmed = raw.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
        return if (!trimmed.contains(":")) {
            "$trimmed:2325"
        } else {
            trimmed
        }
    }

    // Backward compatibility alias
    private fun normalizeSeedAddress(raw: String): String = normalizeConnectionAddress(raw)

    private fun startPruner() {
        pruneJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(5000L)
                val now = System.currentTimeMillis()
                var changed = false
                val iterator = peersMap.entries.iterator()
                while (iterator.hasNext()) {
                    val entry = iterator.next()
                    if (now - entry.value.lastSeenTimestamp > PEER_EXPIRATION_MS * 2) {
                        iterator.remove()
                        changed = true
                    }
                }
                if (changed) {
                    updatePeersList()
                } else {
                    updatePeersList()
                }
            }
        }
    }

    private fun updatePeersList() {
        val installed = AppVersionHelper.getInstalledVersion(context)
        val installedApps = AppVersionHelper.getUserInstalledApps(context)
        val currentStatus = coordinator.statusFlow.value
        val localIp = getLocalIpAddress() ?: "127.0.0.1"

        val selfNode = PeerNode(
            id = deviceId,
            ip = localIp,
            port = 2325,
            deviceModel = "$deviceModel (This Device)",
            displayName = "${getDisplayName()} (This Device)",
            targetInstalled = installed.isInstalled,
            installedVersionName = installed.versionName,
            installedVersionCode = installed.versionCode,
            installedApps = installedApps,
            updaterState = currentStatus.state,
            updaterMessage = currentStatus.message,
            adbEnabled = com.cfox.droidmesh.utils.AdbHelper.isAdbEnabled(context),
            lastSeenTimestamp = System.currentTimeMillis(),
            isSelf = true,
            meshId = localMeshId,
            meshName = localMeshName,
            isDiscoveredPeer = false
        )

        val remotes = peersMap.values.toList().sortedBy { it.ip }
        val all = mutableListOf<PeerNode>()
        all.add(selfNode)
        all.addAll(remotes)

        _peersFlow.value = all
    }

    fun getMeshesGrouped(): JSONObject {
        val currentPeers = _peersFlow.value
        val localIp = getLocalIpAddress() ?: "127.0.0.1"
        val connections = SettingsStore.getPersistentConnections(context)

        val byMesh = currentPeers.groupBy { it.meshId }
        val meshesArray = JSONArray()

        // Ensure local mesh is always present even if alone
        val meshIds = mutableSetOf(localMeshId)
        meshIds.addAll(byMesh.keys)

        for (mId in meshIds.sorted()) {
            val peers = byMesh[mId] ?: emptyList()
            val mName = peers.firstOrNull()?.meshName ?: if (mId == localMeshId) localMeshName else mId
            val isLocal = (mId == localMeshId)

            val mObj = JSONObject().apply {
                put("id", mId)
                put("name", mName)
                put("is_local", isLocal)
                put("peer_count", peers.size)
                put("online_count", peers.count { it.isOnline })
                val peersJson = JSONArray()
                peers.forEach { peersJson.put(it.toJson()) }
                put("peers", peersJson)

                val storedLibrary = SettingsStore.getMeshAppLibrary(context, mId).toMutableMap()
                for (p in peers) {
                    for (app in p.installedApps) {
                        if (com.cfox.droidmesh.installer.AppVersionHelper.isExcludedAppPackage(app.packageName, context)) {
                            continue
                        }
                        if (!storedLibrary.containsKey(app.packageName)) {
                            storedLibrary[app.packageName] = SettingsStore.MeshAppConfig(
                                packageName = app.packageName,
                                appName = if (app.appName.isNotBlank()) app.appName else app.packageName,
                                managed = false,
                                autoInstall = false,
                                targetVersion = "latest",
                                autoUpdate = false,
                                isSideloaded = com.cfox.droidmesh.installer.AppVersionHelper.isSideloadedApp(app.packageName)
                            )
                        }
                    }
                }

                // If non-portal mesh (e.g. googletv) has Kiosk Satellite from an old sync without any node having it installed, prune it
                if (mId != "meta-portals" && !peers.any { p -> p.installedApps.any { it.packageName == com.cfox.droidmesh.installer.AppVersionHelper.TARGET_PACKAGE } }) {
                    storedLibrary.remove(com.cfox.droidmesh.installer.AppVersionHelper.TARGET_PACKAGE)
                }

                // Sort library: sideloaded apps first, then app store apps, then alphabetically by app name
                val libraryJson = JSONArray()
                storedLibrary.values
                    .filter { !com.cfox.droidmesh.installer.AppVersionHelper.isExcludedAppPackage(it.packageName, context) }
                    .sortedWith(
                        compareByDescending<SettingsStore.MeshAppConfig> { it.isSideloaded }
                            .thenBy { it.appName.lowercase() }
                    )
                    .forEach {
                        libraryJson.put(it.toJson())
                    }
                put("app_library", libraryJson)
            }
            meshesArray.put(mObj)
        }

        val allPeersJson = JSONArray()
        currentPeers.forEach { allPeersJson.put(it.toJson()) }

        val connectionsJson = JSONArray()
        connections.forEach { connectionsJson.put(it) }

        return JSONObject().apply {
            put("local_mesh_id", localMeshId)
            put("local_mesh_name", localMeshName)
            put("local_ip", localIp)
            put("device_id", deviceId)
            put("meshes", meshesArray)
            put("persistent_connections", connectionsJson)
            // Backward compatibility: also include under old key
            put("seeds", connectionsJson)
            put("peers", allPeersJson)
        }
    }

    fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
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
        } catch (e: Exception) {
            Logger.e("Error getting local IP address", e)
        }
        return null
    }

    private fun getBroadcastAddresses(): List<InetAddress> {
        val list = mutableListOf<InetAddress>()
        try {
            // Standard fallback
            list.add(InetAddress.getByName("255.255.255.255"))

            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                for (interfaceAddress in iface.interfaceAddresses) {
                    val broadcast = interfaceAddress.broadcast
                    if (broadcast != null && !list.contains(broadcast)) {
                        list.add(broadcast)
                    }
                }
            }
        } catch (e: Exception) {
            Logger.e("Error resolving broadcast addresses", e)
        }
        return list
    }
}
