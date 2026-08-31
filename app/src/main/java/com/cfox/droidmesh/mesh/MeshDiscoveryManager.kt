package com.cfox.droidmesh.mesh

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import com.cfox.droidmesh.installer.AppVersionHelper
import com.cfox.droidmesh.server.UpdateCoordinator
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
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap

class MeshDiscoveryManager(
    private val context: Context,
    private val coordinator: UpdateCoordinator,
    private val scope: CoroutineScope
) {
    companion object {
        const val MESH_PORT = 23250
        const val BEACON_INTERVAL_MS = 8000L
        const val PEER_EXPIRATION_MS = 30000L
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

    fun start() {
        Logger.i("Starting MeshDiscoveryManager on UDP port $MESH_PORT (Device ID: $deviceId, Model: $deviceModel)")
        acquireMulticastLock()
        startReceiver()
        startTransmitter()
        startPruner()
    }

    fun stop() {
        Logger.i("Stopping MeshDiscoveryManager")
        txJob?.cancel()
        rxJob?.cancel()
        pruneJob?.cancel()

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

    private fun sendBeacon() {
        try {
            val installed = AppVersionHelper.getInstalledVersion(context)
            val currentStatus = coordinator.statusFlow.value
            val localIp = getLocalIpAddress() ?: "127.0.0.1"

            val json = JSONObject().apply {
                put("type", "ks_mesh_beacon")
                put("id", deviceId)
                put("ip", localIp)
                put("port", 2325)
                put("deviceModel", deviceModel)
                put("targetInstalled", installed.isInstalled)
                put("installedVersionName", installed.versionName ?: JSONObject.NULL)
                put("installedVersionCode", installed.versionCode ?: JSONObject.NULL)
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
                    // Update online/offline states
                    updatePeersList()
                }
            }
        }
    }

    private fun updatePeersList() {
        val installed = AppVersionHelper.getInstalledVersion(context)
        val currentStatus = coordinator.statusFlow.value
        val localIp = getLocalIpAddress() ?: "127.0.0.1"

        val selfNode = PeerNode(
            id = deviceId,
            ip = localIp,
            port = 2325,
            deviceModel = "$deviceModel (This Device)",
            targetInstalled = installed.isInstalled,
            installedVersionName = installed.versionName,
            installedVersionCode = installed.versionCode,
            updaterState = currentStatus.state,
            updaterMessage = currentStatus.message,
            adbEnabled = com.cfox.droidmesh.utils.AdbHelper.isAdbEnabled(context),
            lastSeenTimestamp = System.currentTimeMillis(),
            isSelf = true
        )

        val remotes = peersMap.values.toList().sortedBy { it.ip }
        val all = mutableListOf<PeerNode>()
        all.add(selfNode)
        all.addAll(remotes)

        _peersFlow.value = all
    }

    private fun getLocalIpAddress(): String? {
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
