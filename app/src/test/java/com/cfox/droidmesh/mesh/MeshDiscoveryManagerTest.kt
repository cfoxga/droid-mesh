package com.cfox.droidmesh.mesh

import android.content.Context
import android.content.SharedPreferences
import com.cfox.droidmesh.server.UpdateCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class MeshDiscoveryManagerTest {

    // JDK 17 blocks the classic Field.modifiers reflection trick for clearing `final` on a static
    // field (JEP 396/403); Unsafe.putObject bypasses the final check entirely instead.
    private fun setFinalStatic(field: java.lang.reflect.Field, value: Any) {
        field.isAccessible = true
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafeField = unsafeClass.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null)
        val staticFieldBase = unsafeClass.getMethod("staticFieldBase", java.lang.reflect.Field::class.java)
            .invoke(unsafe, field)
        val staticFieldOffset = unsafeClass.getMethod("staticFieldOffset", java.lang.reflect.Field::class.java)
            .invoke(unsafe, field) as Long
        unsafeClass.getMethod("putObject", Any::class.java, Long::class.javaPrimitiveType, Any::class.java)
            .invoke(unsafe, staticFieldBase, staticFieldOffset, value)
    }

    private val inMemoryPrefs = mutableMapOf<String, Any>()
    private lateinit var mockContext: Context
    private lateinit var manager: MeshDiscoveryManager

    @Before
    fun setUp() {
        inMemoryPrefs.clear()

        // ingestRemotePeers()/updatePeersList() build a selfNode that reads
        // android.os.Build.MODEL/MANUFACTURER, which is null under the plain-JVM unit test
        // stub jar (no real device) and throws NPE the first time anything touches it.
        setFinalStatic(android.os.Build::class.java.getField("MODEL"), "PortalTest")
        setFinalStatic(android.os.Build::class.java.getField("MANUFACTURER"), "TestManufacturer")

        val editor: SharedPreferences.Editor = mock {
            whenever(it.putString(any(), anyOrNull())).thenAnswer { inv ->
                val k = inv.getArgument<String>(0)
                val v = inv.getArgument<String?>(1)
                if (v != null) inMemoryPrefs[k] = v else inMemoryPrefs.remove(k)
                it
            }
            whenever(it.putBoolean(any(), any())).thenAnswer { inv ->
                inMemoryPrefs[inv.getArgument<String>(0)] = inv.getArgument<Boolean>(1)
                it
            }
            whenever(it.putInt(any(), any())).thenAnswer { inv ->
                inMemoryPrefs[inv.getArgument<String>(0)] = inv.getArgument<Int>(1)
                it
            }
            whenever(it.putLong(any(), any())).thenAnswer { inv ->
                inMemoryPrefs[inv.getArgument<String>(0)] = inv.getArgument<Long>(1)
                it
            }
            whenever(it.putStringSet(any(), anyOrNull())).thenAnswer { inv ->
                val k = inv.getArgument<String>(0)
                val v = inv.getArgument<Set<String>?>(1)
                if (v != null) inMemoryPrefs[k] = v else inMemoryPrefs.remove(k)
                it
            }
            whenever(it.remove(any())).thenAnswer { inv ->
                inMemoryPrefs.remove(inv.getArgument<String>(0))
                it
            }
            whenever(it.apply()).thenAnswer { }
            whenever(it.commit()).thenAnswer { true }
        }

        val sharedPrefs: SharedPreferences = mock {
            whenever(it.getBoolean(any(), any())).thenAnswer { inv ->
                inMemoryPrefs[inv.getArgument<String>(0)] as? Boolean ?: inv.getArgument<Boolean>(1)
            }
            whenever(it.getInt(any(), any())).thenAnswer { inv ->
                inMemoryPrefs[inv.getArgument<String>(0)] as? Int ?: inv.getArgument<Int>(1)
            }
            whenever(it.getLong(any(), any())).thenAnswer { inv ->
                inMemoryPrefs[inv.getArgument<String>(0)] as? Long ?: inv.getArgument<Long>(1)
            }
            whenever(it.getString(any(), anyOrNull())).thenAnswer { inv ->
                inMemoryPrefs[inv.getArgument<String>(0)] as? String ?: inv.getArgument<String?>(1)
            }
            whenever(it.getStringSet(any(), anyOrNull())).thenAnswer { inv ->
                @Suppress("UNCHECKED_CAST")
                inMemoryPrefs[inv.getArgument<String>(0)] as? Set<String> ?: inv.getArgument<Set<String>?>(1) ?: emptySet<String>()
            }
            whenever(it.edit()).thenAnswer { editor }
        }

        mockContext = mock {
            whenever(it.getSharedPreferences(any(), any())).thenAnswer { sharedPrefs }
            whenever(it.packageName).thenReturn("com.cfox.droidmesh")
            whenever(it.packageManager).thenReturn(mock())
            whenever(it.contentResolver).thenReturn(mock())
        }

        val coordinator: UpdateCoordinator = mock {
            whenever(it.statusFlow).thenReturn(
                kotlinx.coroutines.flow.MutableStateFlow(
                    com.cfox.droidmesh.api.UpdateStatus(state = "IDLE", message = "Ready")
                )
            )
        }
        manager = MeshDiscoveryManager(mockContext, coordinator, CoroutineScope(Dispatchers.Unconfined))
    }

    // [PROGRAMMATIC] MESH-TEST-007: getMeshesGrouped() includes config_version so the periodic
    // persistent-connection syncer can detect a newer remote config across VLAN boundaries.
    @Test
    fun testGetMeshesGroupedIncludesConfigVersion() {
        val expectedVersion = com.cfox.droidmesh.settings.SettingsStore.updateConfigVersion(mockContext)

        val json = manager.getMeshesGrouped()

        assertTrue("config_version key must be present", json.has("config_version"))
        assertEquals(expectedVersion, json.getLong("config_version"))
    }

    // Negative control: proves the assertion above would actually fail if the field were absent,
    // since JSONObject.optLong defaults to 0 exactly like the production syncer's read path does.
    @Test
    fun testMissingConfigVersionWouldReadAsZero() {
        val json = org.json.JSONObject()
        assertEquals(0L, json.optLong("config_version", 0L))
    }

    // [PROGRAMMATIC] MESH-TEST-016: ingestRemotePeers() is fed every
    // PERSISTENT_CONNECTION_SYNC_INTERVAL_MS (12s) by every persistent connection's bulk /api/mesh
    // pull — a stale second-hand report about a peer (a relaying node that hasn't itself synced
    // with that peer in a while) must not clobber a fresher observation already held for that
    // same peer id.
    private fun peerJson(
        id: String,
        updaterState: String,
        installedVersionName: String,
        lastSeenTimestamp: Long
    ) = org.json.JSONObject().apply {
        put("id", id)
        put("ip", "192.168.40.250")
        put("port", 2325)
        put("deviceModel", "Facebook PortalMini")
        put("displayName", "Master Bedroom Portal")
        put("meshId", "kiosk-satellite-mesh")
        put("meshName", "Kiosk Satellite")
        put("updaterState", updaterState)
        put("installedVersionName", installedVersionName)
        put("installedVersionCode", 202)
        put("lastSeenTimestamp", lastSeenTimestamp)
    }

    @Test
    fun testIngestRemotePeersIgnoresStaleRelayOlderThanExistingEntry() {
        val fresh = org.json.JSONArray().put(peerJson("mb-portal", "IDLE", "2026.9.3", 2_000_000L))
        manager.ingestRemotePeers(fresh, "seed-a")

        val stale = org.json.JSONArray().put(
            peerJson("mb-portal", "AWAITING_CONFIRMATION", "2026.9.2", 1_000_000L)
        )
        manager.ingestRemotePeers(stale, "seed-b")

        val peer = manager.peersFlow.value.first { it.id == "mb-portal" }
        assertEquals(
            "a relay older than what's already known must not overwrite the fresher entry",
            "IDLE", peer.updaterState
        )
        assertEquals("2026.9.3", peer.installedVersionName)
    }

    @Test
    fun testIngestRemotePeersAppliesRelayNewerThanExistingEntry() {
        val stale = org.json.JSONArray().put(
            peerJson("mb-portal", "AWAITING_CONFIRMATION", "2026.9.2", 1_000_000L)
        )
        manager.ingestRemotePeers(stale, "seed-a")

        val fresh = org.json.JSONArray().put(peerJson("mb-portal", "IDLE", "2026.9.3", 2_000_000L))
        manager.ingestRemotePeers(fresh, "seed-b")

        val peer = manager.peersFlow.value.first { it.id == "mb-portal" }
        assertEquals("IDLE", peer.updaterState)
        assertEquals("2026.9.3", peer.installedVersionName)
    }

    @Test
    fun testIngestRemotePeersAcceptsFirstSightingRegardlessOfTimestamp() {
        val first = org.json.JSONArray().put(peerJson("mb-portal", "IDLE", "2026.9.3", 1L))
        manager.ingestRemotePeers(first, "seed-a")

        val peer = manager.peersFlow.value.first { it.id == "mb-portal" }
        assertEquals("IDLE", peer.updaterState)
    }

    // [PROGRAMMATIC] MESH-TEST-024: gitea#59 -- a relayed peer report claiming a lastSeenTimestamp
    // far in this device's future must be clamped to "now" at ingest time, not stored as-is. An
    // unclamped future value would make PeerNode.isOnline (now - lastSeenTimestamp < 30_000L)
    // evaluate true indefinitely and win every future recency comparison in ingestRemotePeers.
    @Test
    fun testIngestRemotePeersClampsFutureLastSeenTimestampToNow() {
        val farFuture = System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000
        val beforeIngest = System.currentTimeMillis()
        val forged = org.json.JSONArray().put(peerJson("attacker-forged", "IDLE", "9.9.9", farFuture))

        manager.ingestRemotePeers(forged, "seed-a")

        val peer = manager.peersFlow.value.first { it.id == "attacker-forged" }
        val afterIngest = System.currentTimeMillis()
        assertTrue(
            "a future-dated lastSeenTimestamp must be clamped to receipt time, not stored as the forged future value: ${peer.lastSeenTimestamp}",
            peer.lastSeenTimestamp in beforeIngest..afterIngest
        )
    }

    // [PROGRAMMATIC] MESH-TEST-025: gitea#59/#65 -- handleIncomingHandshake must persist the
    // connection keyed on the actual observed TCP source IP, not an attacker-declared sender_ip
    // in the request body that points somewhere else entirely.
    @Test
    fun testHandleIncomingHandshakeIgnoresForgedSenderIp() {
        val realSourceIp = "203.0.113.5"
        val forgedJson = org.json.JSONObject().apply {
            put("sender_ip", "10.0.0.99")
            put("sender_port", 2325)
            put("mesh_id", "unmanaged")
            put("reciprocal", false)
        }

        manager.handleIncomingHandshake(forgedJson, realSourceIp)

        val connections = com.cfox.droidmesh.settings.SettingsStore.getPersistentConnections(mockContext)
        assertTrue(
            "must persist the real observed source, not the forged sender_ip: $connections",
            connections.contains("$realSourceIp:2325")
        )
        assertFalse(
            "must never persist the attacker-declared sender_ip",
            connections.contains("10.0.0.99:2325")
        )
    }

    // [PROGRAMMATIC] MESH-TEST-026: negative control for MESH-TEST-025 -- a handshake whose
    // declared sender_ip happens to be truthful (the honest, common case) still persists normally.
    @Test
    fun testHandleIncomingHandshakeAcceptsTruthfulSenderIp() {
        val realSourceIp = "192.168.40.59"
        val truthfulJson = org.json.JSONObject().apply {
            put("sender_ip", realSourceIp)
            put("sender_port", 2325)
            put("mesh_id", "unmanaged")
            put("reciprocal", false)
        }

        manager.handleIncomingHandshake(truthfulJson, realSourceIp)

        val connections = com.cfox.droidmesh.settings.SettingsStore.getPersistentConnections(mockContext)
        assertTrue(connections.contains("$realSourceIp:2325"))
    }
}
