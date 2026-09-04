package com.cfox.droidmesh.settings

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.security.MessageDigest
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class SettingsStoreTest {

    private val inMemoryPrefs = mutableMapOf<String, Any>()
    private lateinit var mockContext: Context

    @Before
    fun setUp() {
        inMemoryPrefs.clear()

        val editor: SharedPreferences.Editor = mock {
            whenever(it.putString(any(), anyOrNull())).thenAnswer { inv ->
                val k = inv.getArgument<String>(0)
                val v = inv.getArgument<String?>(1)
                if (v != null) inMemoryPrefs[k] = v else inMemoryPrefs.remove(k)
                it
            }
            whenever(it.putStringSet(any(), anyOrNull())).thenAnswer { inv ->
                val k = inv.getArgument<String>(0)
                val v = inv.getArgument<Set<String>?>(1)
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
    }

    // [PROGRAMMATIC] SET-TEST-003: Config persistence & persistent connections
    // [PROGRAMMATIC] MESH-TEST-004: Config sync across nodes with versioning
    @Test
    fun testExportAndImportConfig() {
        // Configure node A
        SettingsStore.setWebServerPort(mockContext, 2329)
        SettingsStore.setPassword(mockContext, "fleetPassword123")
        SettingsStore.addPersistentConnection(mockContext, "192.168.50.64:2329")

        val exported = SettingsStore.exportConfigJson(mockContext)
        val initialVersion = exported.getLong("config_version")
        assertTrue(initialVersion > 0L)
        assertEquals(2329, exported.getInt("web_server_port"))

        // Reset prefs for simulated node B
        inMemoryPrefs.clear()
        assertEquals(2325, SettingsStore.getWebServerPort(mockContext)) // default

        // Import exported config onto node B
        val importResult = SettingsStore.importConfigJson(mockContext, exported)
        assertTrue(importResult.applied)
        assertTrue(importResult.portChanged)
        // [SET-BEHAVE-007] Credential fields never propagate via config sync.
        assertFalse(importResult.passwordChanged)
        assertTrue(importResult.seedsChanged)

        // Verify Node B matches Node A on non-credential fields, but has NOT inherited Node A's
        // password -- SET-BEHAVE-007 keeps auth material strictly local, unlike everything else here.
        // verifyPassword fails open (true for any input) when no password is set at all -- unrelated,
        // pre-existing behavior, not evidence the password propagated.
        assertEquals(2329, SettingsStore.getWebServerPort(mockContext))
        assertFalse(SettingsStore.isPasswordSet(mockContext))
        assertTrue(SettingsStore.verifyPassword(mockContext, "fleetPassword123"))
        assertTrue(SettingsStore.verifyPassword(mockContext, "literally anything"))
        assertTrue(SettingsStore.getPersistentConnections(mockContext).contains("192.168.50.64:2329"))

        // Re-importing older or equal version should be ignored
        val reimportResult = SettingsStore.importConfigJson(mockContext, exported)
        assertFalse(reimportResult.applied)
    }

    // [PROGRAMMATIC] SET-TEST-001: Password verification
    // [PROGRAMMATIC] SET-TEST-002: Token generation and validation
    // [PROGRAMMATIC] SET-TEST-007/012: credential material stays local, never syncs via config import
    @Test
    fun testPasswordTokenVerificationDoesNotCrossNodesViaConfigSync() {
        SettingsStore.setPassword(mockContext, "superSecretKey")
        val tokenOnNodeA = SettingsStore.generateToken(mockContext)
        assertTrue(SettingsStore.validateToken(mockContext, tokenOnNodeA))

        val exported = SettingsStore.exportConfigJson(mockContext)

        // Wipe and simulate Node B importing Node A's config
        inMemoryPrefs.clear()
        SettingsStore.importConfigJson(mockContext, exported)

        // [SET-BEHAVE-007] Node B never received Node A's auth_secret or password hash/salt, so it
        // has no password of its own -- both validateToken and verifyPassword fail open (accept
        // anything) because no password is set at all, not because Node A's credentials validated.
        assertFalse(SettingsStore.isPasswordSet(mockContext))
        assertTrue(SettingsStore.validateToken(mockContext, tokenOnNodeA)) // fails open, not SSO
        assertTrue(SettingsStore.validateToken(mockContext, "not-even-a-real-token")) // proves it
    }

    // [PROGRAMMATIC] SET-TEST-011
    @Test
    fun testExportConfigJsonNeverIncludesCredentialFields() {
        SettingsStore.setPassword(mockContext, "fleetPassword123")
        // Force auth_secret to be generated too, mirroring what a real device would have on disk
        // once a token has ever been minted.
        SettingsStore.generateToken(mockContext)

        val exported = SettingsStore.exportConfigJson(mockContext)

        assertFalse(exported.has("auth_secret"))
        assertFalse(exported.has("web_password_hash"))
        assertFalse(exported.has("web_password_salt"))
    }

    // [PROGRAMMATIC] SET-TEST-012
    @Test
    fun testImportConfigJsonIgnoresCredentialFieldsFromNetwork() {
        // Node A has a real password/secret of its own.
        SettingsStore.setPassword(mockContext, "legitPassword")
        val legitHash = inMemoryPrefs["web_password_hash"]
        val legitSalt = inMemoryPrefs["web_password_salt"]
        val legitSecret = inMemoryPrefs["auth_secret"]

        // Attacker-crafted config with a higher config_version carrying their own credentials.
        val maliciousConfig = JSONObject().apply {
            put("config_version", SettingsStore.getConfigVersion(mockContext) + 1000L)
            put("web_password_hash", "attacker-hash")
            put("web_password_salt", "attacker-salt")
            put("auth_secret", "attacker-secret")
        }

        val result = SettingsStore.importConfigJson(mockContext, maliciousConfig)

        assertTrue(result.applied) // version bump itself still applies
        assertFalse(result.passwordChanged) // but no credential field is read or applied
        assertEquals(legitHash, inMemoryPrefs["web_password_hash"])
        assertEquals(legitSalt, inMemoryPrefs["web_password_salt"])
        assertEquals(legitSecret, inMemoryPrefs["auth_secret"])
        assertTrue(SettingsStore.verifyPassword(mockContext, "legitPassword"))
        assertFalse(SettingsStore.verifyPassword(mockContext, "attacker-hash"))
    }

    // [PROGRAMMATIC] SET-TEST-013: gitea#54 -- an incoming sync/handshake payload cannot override
    // managed/autoInstall/downloadUrl for a package this device ALREADY has an App Library entry
    // for. Those three fields are admin-local once an entry exists; only setMeshAppConfig
    // (the authenticated local endpoint) can change them from then on.
    @Test
    fun testImportConfigJsonPreservesExistingManagedAutoInstallDownloadUrlFromNetwork() {
        SettingsStore.setMeshAppConfig(
            mockContext, "meta-portals",
            SettingsStore.MeshAppConfig(
                packageName = "com.cfoxga.kiosksatellite",
                appName = "Kiosk Satellite",
                managed = true,
                autoInstall = true,
                downloadUrl = "https://github.com/cfoxga/kiosk-satellite/releases/download/v1/app.apk"
            )
        )

        // Attacker-crafted sync payload: same package, attacker-controlled downloadUrl, arriving
        // via the unauthenticated mesh gossip path with a higher config_version.
        val maliciousConfig = JSONObject().apply {
            put("config_version", SettingsStore.getConfigVersion(mockContext) + 1000L)
            put("mesh_app_libraries", JSONObject().apply {
                put("meta-portals", JSONObject().apply {
                    put("com.cfoxga.kiosksatellite", JSONObject().apply {
                        put("packageName", "com.cfoxga.kiosksatellite")
                        put("appName", "Kiosk Satellite (renamed)")
                        put("managed", true)
                        put("autoInstall", true)
                        put("downloadUrl", "https://attacker.evil/malicious.apk")
                    })
                })
            })
        }

        val result = SettingsStore.importConfigJson(mockContext, maliciousConfig)
        assertTrue(result.applied)

        val entry = SettingsStore.getMeshAppLibrary(mockContext, "meta-portals")["com.cfoxga.kiosksatellite"]
        assertEquals(
            "https://github.com/cfoxga/kiosk-satellite/releases/download/v1/app.apk",
            entry?.downloadUrl
        )
        assertEquals(true, entry?.managed)
        assertEquals(true, entry?.autoInstall)
        // Non-sensitive fields still sync normally.
        assertEquals("Kiosk Satellite (renamed)", entry?.appName)
    }

    // [PROGRAMMATIC] SET-TEST-014: gitea#54 -- an incoming sync payload cannot ARM auto-install
    // (flip managed/autoInstall false -> true) for a package this device already knows about but
    // has never marked managed, even with an attacker-controlled downloadUrl attached.
    @Test
    fun testImportConfigJsonCannotArmAutoInstallForExistingUnmanagedEntryFromNetwork() {
        SettingsStore.setMeshAppConfig(
            mockContext, "meta-portals",
            SettingsStore.MeshAppConfig(
                packageName = "com.example.reference",
                appName = "Reference App",
                managed = false,
                autoInstall = false,
                downloadUrl = ""
            )
        )

        val maliciousConfig = JSONObject().apply {
            put("config_version", SettingsStore.getConfigVersion(mockContext) + 1000L)
            put("mesh_app_libraries", JSONObject().apply {
                put("meta-portals", JSONObject().apply {
                    put("com.example.reference", JSONObject().apply {
                        put("packageName", "com.example.reference")
                        put("appName", "Reference App")
                        put("managed", true)
                        put("autoInstall", true)
                        put("downloadUrl", "https://attacker.evil/malicious.apk")
                    })
                })
            })
        }

        SettingsStore.importConfigJson(mockContext, maliciousConfig)

        val entry = SettingsStore.getMeshAppLibrary(mockContext, "meta-portals")["com.example.reference"]
        assertEquals(false, entry?.managed)
        assertEquals(false, entry?.autoInstall)
        assertEquals("", entry?.downloadUrl)
    }

    // [PROGRAMMATIC] APP-TEST-003: Mesh App Library persistence and synchronization
    @Test
    fun testMeshAppLibraryPersistenceAndSync() {
        val appConfig = SettingsStore.MeshAppConfig(
            packageName = "com.disney.disneyplus",
            appName = "Disney+",
            managed = true,
            autoInstall = true,
            targetVersion = "latest",
            autoUpdate = false,
            isSideloaded = false,
            downloadUrl = "https://example.com/releases/disneyplus.apk"
        )
        SettingsStore.setMeshAppConfig(mockContext, "googletv", appConfig)

        val library = SettingsStore.getMeshAppLibrary(mockContext, "googletv")
        assertTrue(library.containsKey("com.disney.disneyplus"))
        assertEquals("Disney+", library["com.disney.disneyplus"]?.appName)
        assertTrue(library["com.disney.disneyplus"]?.managed == true)
        assertTrue(library["com.disney.disneyplus"]?.autoInstall == true)

        val exported = SettingsStore.exportConfigJson(mockContext)
        assertTrue(exported.has("mesh_app_libraries"))

        // Reset and import on node B
        inMemoryPrefs.clear()
        val importResult = SettingsStore.importConfigJson(mockContext, exported)
        assertTrue(importResult.applied)
        assertTrue(importResult.libraryChanged)

        val importedLibrary = SettingsStore.getMeshAppLibrary(mockContext, "googletv")
        assertTrue(importedLibrary.containsKey("com.disney.disneyplus"))
        assertEquals(true, importedLibrary["com.disney.disneyplus"]?.managed)
    }

    // [PROGRAMMATIC] APP-TEST-006: No hardcoded default seed on any mesh partition, and DroidMesh
    // is never included. User-created meshes start empty; entries only exist once a peer reports
    // them or a user adds one — there is no app-specific bootstrapping of any kind.
    @Test
    fun testMeshAppLibraryDefaultsByMeshPartition() {
        val gtvLibrary = SettingsStore.getMeshAppLibrary(mockContext, "googletv")
        assertTrue("googletv partition should start with no seeded entries", gtvLibrary.isEmpty())
        assertFalse(gtvLibrary.containsKey("com.cfox.droidmesh"))

        val portalsLibrary = SettingsStore.getMeshAppLibrary(mockContext, "meta-portals")
        assertTrue("meta-portals partition should start with no seeded entries", portalsLibrary.isEmpty())
        assertFalse(portalsLibrary.containsKey("com.cfox.droidmesh"))
    }

    // [PROGRAMMATIC] SET-TEST-004: setMeshAppConfig coerces managed=false when downloadUrl is
    // blank, rather than throwing or persisting an unusable managed=true entry.
    @Test
    fun testSetMeshAppConfigCoercesManagedFalseWhenDownloadUrlBlank() {
        val appConfig = SettingsStore.MeshAppConfig(
            packageName = "com.example.someapp",
            appName = "Some App",
            managed = true,
            autoInstall = false,
            targetVersion = "latest",
            autoUpdate = false,
            isSideloaded = true,
            downloadUrl = ""
        )
        SettingsStore.setMeshAppConfig(mockContext, "googletv", appConfig)

        val stored = SettingsStore.getMeshAppLibrary(mockContext, "googletv")["com.example.someapp"]
        assertNotNull(stored)
        assertFalse("managed must coerce to false with no downloadUrl", stored!!.managed)
    }

    // [PROGRAMMATIC] SET-TEST-005: setMeshAppConfig allows managed=true when downloadUrl is set.
    @Test
    fun testSetMeshAppConfigAllowsManagedTrueWhenDownloadUrlPresent() {
        val appConfig = SettingsStore.MeshAppConfig(
            packageName = "com.example.someapp",
            appName = "Some App",
            managed = true,
            autoInstall = false,
            targetVersion = "latest",
            autoUpdate = false,
            isSideloaded = true,
            downloadUrl = "https://example.com/releases/someapp.apk"
        )
        SettingsStore.setMeshAppConfig(mockContext, "googletv", appConfig)

        val stored = SettingsStore.getMeshAppLibrary(mockContext, "googletv")["com.example.someapp"]
        assertNotNull(stored)
        assertTrue(stored!!.managed)
    }

    // [PROGRAMMATIC] SET-TEST-006: MeshAppConfig.fromJson self-heals managed=true with a blank
    // downloadUrl (legacy/synced sideloaded-origin data) to managed=false on read, mirroring the
    // write-path coercion. Explicit isSideloaded=true so this stays a sideloaded-scoped assertion
    // regardless of AppVersionHelper's prefix guess (APP-BEHAVE-007 narrows the gate to origin).
    @Test
    fun testMeshAppConfigFromJsonCoercesManagedFalseWhenDownloadUrlBlank() {
        val json = JSONObject().apply {
            put("packageName", "com.example.legacyapp")
            put("appName", "Legacy App")
            put("managed", true)
            put("isSideloaded", true)
            put("downloadUrl", "")
        }
        val decoded = SettingsStore.MeshAppConfig.fromJson(json)
        assertFalse("fromJson must coerce managed=false with no downloadUrl for a sideloaded entry", decoded.managed)
    }

    // [PROGRAMMATIC] SET-TEST-007 (APP-BEHAVE-007 negative): a store-origin entry (isSideloaded
    // = false) is never gated on downloadUrl — it will never have one, since DroidMesh doesn't
    // download/install Play Store APKs directly. Managed there is a plain admin toggle.
    @Test
    fun testSetMeshAppConfigAllowsManagedTrueForStoreAppWithBlankDownloadUrl() {
        val appConfig = SettingsStore.MeshAppConfig(
            packageName = "com.netflix.mediaclient",
            appName = "Netflix",
            managed = true,
            autoInstall = false,
            targetVersion = "latest",
            autoUpdate = false,
            isSideloaded = false,
            downloadUrl = ""
        )
        SettingsStore.setMeshAppConfig(mockContext, "googletv", appConfig)

        val stored = SettingsStore.getMeshAppLibrary(mockContext, "googletv")["com.netflix.mediaclient"]
        assertNotNull(stored)
        assertTrue("managed must NOT coerce to false for a store-origin entry with no downloadUrl", stored!!.managed)
    }

    // [PROGRAMMATIC] SET-TEST-008 (APP-BEHAVE-007 negative): same rule on the read/sync path —
    // fromJson must not coerce a store-origin entry's managed flag for lacking a downloadUrl.
    @Test
    fun testMeshAppConfigFromJsonAllowsManagedTrueForStoreAppWithBlankDownloadUrl() {
        val json = JSONObject().apply {
            put("packageName", "com.netflix.mediaclient")
            put("appName", "Netflix")
            put("managed", true)
            put("isSideloaded", false)
            put("downloadUrl", "")
        }
        val decoded = SettingsStore.MeshAppConfig.fromJson(json)
        assertTrue("fromJson must NOT coerce managed=false for a store-origin entry with no downloadUrl", decoded.managed)
    }

    // [PROGRAMMATIC] APP-TEST-007: App library sorting by type (sideloaded first), then alphabetically
    @Test
    fun testMeshAppLibrarySortingSideloadedFirstThenAlphabetical() {
        val apps = listOf(
            SettingsStore.MeshAppConfig("com.netflix.ninja", "Netflix", isSideloaded = false),
            SettingsStore.MeshAppConfig("com.cfoxga.mpttv", "MPT TV", isSideloaded = true),
            SettingsStore.MeshAppConfig("com.disney.disneyplus", "Disney+", isSideloaded = false),
            SettingsStore.MeshAppConfig("com.cfoxga.foxtvagent", "Fox TV Agent", isSideloaded = true)
        )

        val sorted = apps.sortedWith(
            compareByDescending<SettingsStore.MeshAppConfig> { it.isSideloaded }
                .thenBy { it.appName.lowercase() }
        )

        assertEquals("Fox TV Agent", sorted[0].appName)
        assertTrue(sorted[0].isSideloaded)

        assertEquals("MPT TV", sorted[1].appName)
        assertTrue(sorted[1].isSideloaded)

        assertEquals("Disney+", sorted[2].appName)
        assertFalse(sorted[2].isSideloaded)

        assertEquals("Netflix", sorted[3].appName)
        assertFalse(sorted[3].isSideloaded)
    }

    // [PROGRAMMATIC] MESH-TEST-005: Backward-compat import of old cross_vlan_seeds key
    @Test
    fun testBackwardCompatImportOldCrossVlanSeeds() {
        // Simulate old config format with "cross_vlan_seeds" key (old name)
        val seedsArr = JSONArray()
        seedsArr.put("192.168.50.64:2325")
        seedsArr.put("192.168.40.250:2325")

        val oldFormatConfig = JSONObject().apply {
            put("config_version", 100L)
            put("web_server_port", 2329)
            put("cross_vlan_seeds", seedsArr)
        }

        val result = SettingsStore.importConfigJson(mockContext, oldFormatConfig)
        assertTrue(result.applied)
        assertTrue(result.seedsChanged)

        // Verify old key was imported as new persistent_connections
        val connections = SettingsStore.getPersistentConnections(mockContext)
        assertEquals(2, connections.size)
        assertTrue(connections.contains("192.168.50.64:2325"))
        assertTrue(connections.contains("192.168.40.250:2325"))
    }

    // [PROGRAMMATIC] MESH-TEST-006: Dual-key export for backward compatibility
    @Test
    fun testDualKeyExportForBackwardCompat() {
        SettingsStore.addPersistentConnection(mockContext, "192.168.50.64:2325")
        SettingsStore.addPersistentConnection(mockContext, "192.168.40.250:2325")

        val exported = SettingsStore.exportConfigJson(mockContext)

        // Verify both old and new keys are present
        assertTrue(exported.has("persistent_connections"))
        assertTrue(exported.has("cross_vlan_seeds"))

        // Verify both contain the same connections
        val newKey = exported.optJSONArray("persistent_connections")
        val oldKey = exported.optJSONArray("cross_vlan_seeds")
        assertEquals(2, newKey?.length())
        assertEquals(2, oldKey?.length())

        // Both keys should have same contents
        val newConns = mutableSetOf<String>()
        val oldConns = mutableSetOf<String>()
        for (i in 0 until (newKey?.length() ?: 0)) {
            newConns.add(newKey!!.optString(i, ""))
        }
        for (i in 0 until (oldKey?.length() ?: 0)) {
            oldConns.add(oldKey!!.optString(i, ""))
        }
        assertEquals(newConns, oldConns)
    }

    // [PROGRAMMATIC] DM-DATA-007: All-device power loss recovery scenario
    @Test
    fun testPowerLossRecoveryWithPersistentConnections() {
        // Simulate Node A with persistent connections configured
        SettingsStore.addPersistentConnection(mockContext, "192.168.50.64:2325")
        SettingsStore.addPersistentConnection(mockContext, "192.168.40.250:2325")

        // Export config (simulating power loss save)
        val exported = SettingsStore.exportConfigJson(mockContext)

        // Clear preferences (simulating power loss and restart)
        inMemoryPrefs.clear()

        // Verify persistent connections are gone before import
        assertTrue(SettingsStore.getPersistentConnections(mockContext).isEmpty())

        // Import saved config (simulating boot after power loss)
        val result = SettingsStore.importConfigJson(mockContext, exported)
        assertTrue(result.applied)
        assertTrue(result.seedsChanged)

        // Verify connections are restored
        val restored = SettingsStore.getPersistentConnections(mockContext)
        assertEquals(2, restored.size)
        assertTrue(restored.contains("192.168.50.64:2325"))
        assertTrue(restored.contains("192.168.40.250:2325"))
    }

    // [PROGRAMMATIC] MESH-TEST-008: Deleting "unmanaged" is rejected (negative case)
    @Test
    fun testRemoveKnownMeshRejectsUnmanaged() {
        val removed = SettingsStore.removeKnownMesh(mockContext, "unmanaged")
        assertFalse(removed)
        assertTrue(SettingsStore.getKnownMeshes(mockContext).any { it.id == "unmanaged" })
        assertTrue(SettingsStore.getDeletedMeshes(mockContext).isEmpty())
    }

    // [PROGRAMMATIC] Negative case: deleting an id that doesn't exist is rejected, no tombstone created
    @Test
    fun testRemoveKnownMeshRejectsUnknownId() {
        val removed = SettingsStore.removeKnownMesh(mockContext, "does-not-exist")
        assertFalse(removed)
        assertTrue(SettingsStore.getDeletedMeshes(mockContext).isEmpty())
    }

    // [PROGRAMMATIC] MESH-TEST-010: Deleting an empty, non-unmanaged mesh removes it, records a
    // tombstone, and bumps config_version.
    @Test
    fun testRemoveKnownMeshDeletesAndTombstones() {
        SettingsStore.addKnownMesh(mockContext, "googletv", "Google TV")
        val versionBefore = SettingsStore.getConfigVersion(mockContext)

        val removed = SettingsStore.removeKnownMesh(mockContext, "googletv")

        assertTrue(removed)
        assertFalse(SettingsStore.getKnownMeshes(mockContext).any { it.id == "googletv" })
        val tombstones = SettingsStore.getDeletedMeshes(mockContext)
        assertTrue(tombstones.any { it.id == "googletv" })
        assertTrue(SettingsStore.getConfigVersion(mockContext) > versionBefore)
    }

    // [PROGRAMMATIC] MESH-TEST-011: A mesh deletion propagates via sync -- a peer that already
    // knows about a mesh removes it locally once it imports a config carrying that tombstone,
    // instead of the union-preserve merge resurrecting it. Payload is hand-built (like
    // testBackwardCompatImportOldCrossVlanSeeds above) so config_version ordering is deterministic
    // instead of racing the real wall clock across two simulated devices sharing one JVM.
    @Test
    fun testImportConfigPropagatesMeshDeletion() {
        // This node already knows about "googletv" from an earlier, pre-deletion sync.
        SettingsStore.addKnownMesh(mockContext, "googletv", "Google TV")
        val localVersion = SettingsStore.getConfigVersion(mockContext)
        assertTrue(SettingsStore.getKnownMeshes(mockContext).any { it.id == "googletv" })

        // Remote peer deleted "googletv" at a strictly later version and is syncing that in.
        val remoteVersion = localVersion + 1000
        val remoteConfig = JSONObject().apply {
            put("config_version", remoteVersion)
            put("known_meshes", JSONArray().apply {
                put(JSONObject().apply { put("id", "unmanaged"); put("name", "Unmanaged") })
            })
            put("deleted_meshes", JSONArray().apply {
                put(JSONObject().apply { put("id", "googletv"); put("deletedAt", remoteVersion) })
            })
        }

        val result = SettingsStore.importConfigJson(mockContext, remoteConfig)

        assertTrue(result.applied)
        assertTrue(result.meshesChanged)
        assertFalse(
            "deletion must propagate, not be resurrected by the union-preserve merge",
            SettingsStore.getKnownMeshes(mockContext).any { it.id == "googletv" }
        )
        assertTrue(SettingsStore.getDeletedMeshes(mockContext).any { it.id == "googletv" })
    }

    // [PROGRAMMATIC] MESH-TEST-012: Recreating a mesh whose id has a local tombstone clears that
    // tombstone -- explicit resurrection wins over a stale delete record.
    @Test
    fun testRecreatingMeshClearsLocalTombstone() {
        SettingsStore.addKnownMesh(mockContext, "googletv", "Google TV")
        SettingsStore.removeKnownMesh(mockContext, "googletv")
        assertTrue(SettingsStore.getDeletedMeshes(mockContext).any { it.id == "googletv" })

        val recreated = SettingsStore.addKnownMesh(mockContext, "googletv", "Google TV")

        assertTrue(recreated)
        assertTrue(SettingsStore.getKnownMeshes(mockContext).any { it.id == "googletv" })
        assertFalse(
            "recreating a mesh must clear its stale tombstone",
            SettingsStore.getDeletedMeshes(mockContext).any { it.id == "googletv" }
        )
    }

    // [PROGRAMMATIC] MESH-TEST-013: persistent_connections sync merge is union, not overwrite --
    // an import that omits a connection this device already has must not delete it. This is the
    // exact clobber bug reported on fleet: a reciprocally-learned connection to a peer getting
    // wiped out by an unrelated, higher-config_version config push from a third device that never
    // knew about it, because the field was a flat last-writer-wins overwrite instead of a merge.
    @Test
    fun testImportConfigMergesPersistentConnectionsUnion() {
        SettingsStore.addPersistentConnection(mockContext, "192.168.50.64:2325") // local-only, e.g. Great Room TV
        val localVersion = SettingsStore.getConfigVersion(mockContext)

        val remoteVersion = localVersion + 1000
        val remoteConfig = JSONObject().apply {
            put("config_version", remoteVersion)
            put("persistent_connections", JSONArray().apply { put("192.168.40.250:2325") }) // remote-only, e.g. Master Bedroom Portal
        }

        val result = SettingsStore.importConfigJson(mockContext, remoteConfig)

        assertTrue(result.applied)
        assertTrue(result.seedsChanged)
        val merged = SettingsStore.getPersistentConnections(mockContext)
        assertTrue("local-only connection must survive the merge, not be overwritten", merged.contains("192.168.50.64:2325"))
        assertTrue("incoming connection must be added", merged.contains("192.168.40.250:2325"))
    }

    // [PROGRAMMATIC] MESH-TEST-014: removePersistentConnection records a tombstone that appears in
    // exportConfigJson's deleted_connections, and importing a config carrying that tombstone
    // removes the connection from the merged set on a peer that still has it locally (deletion
    // propagates instead of being resurrected by the union merge).
    @Test
    fun testRemovePersistentConnectionTombstonesAndPropagates() {
        SettingsStore.addPersistentConnection(mockContext, "192.168.50.64:2325")
        val removed = SettingsStore.removePersistentConnection(mockContext, "192.168.50.64:2325")
        assertTrue(removed)
        assertFalse(SettingsStore.getPersistentConnections(mockContext).contains("192.168.50.64:2325"))

        val exported = SettingsStore.exportConfigJson(mockContext)
        val tombstonesArr = exported.optJSONArray("deleted_connections")
        assertNotNull(tombstonesArr)
        assertTrue((0 until tombstonesArr!!.length()).any { tombstonesArr.getJSONObject(it).optString("connection") == "192.168.50.64:2325" })

        // A peer that still has the connection locally (never saw the removal) should drop it on import.
        inMemoryPrefs.clear()
        SettingsStore.addPersistentConnection(mockContext, "192.168.50.64:2325")
        val localVersion = SettingsStore.getConfigVersion(mockContext)
        val remoteConfig = JSONObject().apply {
            put("config_version", localVersion + 1000)
            put("deleted_connections", tombstonesArr)
        }
        val result = SettingsStore.importConfigJson(mockContext, remoteConfig)
        assertTrue(result.applied)
        assertFalse(
            "deletion must propagate, not be resurrected by the union merge",
            SettingsStore.getPersistentConnections(mockContext).contains("192.168.50.64:2325")
        )
    }

    // [PROGRAMMATIC] MESH-TEST-015: re-adding a connection whose address has a local tombstone
    // clears that tombstone -- explicit re-add wins over a stale delete record, mirroring
    // testRecreatingMeshClearsLocalTombstone for known_meshes.
    @Test
    fun testReaddingPersistentConnectionClearsLocalTombstone() {
        SettingsStore.addPersistentConnection(mockContext, "192.168.50.124:2325")
        SettingsStore.removePersistentConnection(mockContext, "192.168.50.124:2325")
        assertTrue(SettingsStore.getDeletedConnections(mockContext).any { it.connection == "192.168.50.124:2325" })

        val added = SettingsStore.addPersistentConnection(mockContext, "192.168.50.124:2325")

        assertTrue(added)
        assertTrue(SettingsStore.getPersistentConnections(mockContext).contains("192.168.50.124:2325"))
        assertFalse(
            "re-adding a connection must clear its stale tombstone",
            SettingsStore.getDeletedConnections(mockContext).any { it.connection == "192.168.50.124:2325" }
        )
    }

    // [PROGRAMMATIC] SET-TEST-009: setPassword writes a PBKDF2-tagged hash, not a bare
    // single-round SHA-256 digest.
    @Test
    fun testSetPasswordWritesPbkdf2TaggedHash() {
        SettingsStore.setPassword(mockContext, "myPassword1")

        val storedHash = inMemoryPrefs["web_password_hash"] as? String
        assertNotNull("setPassword must write a hash", storedHash)
        assertTrue(
            "new hashes must be PBKDF2-tagged, not a bare legacy hex digest: $storedHash",
            storedHash!!.startsWith("pbkdf2$")
        )
        val iterations = storedHash.split("$")[1].toIntOrNull()
        assertNotNull("tagged hash must carry a numeric iteration count", iterations)
        assertTrue(
            "iteration count must be a real work factor (OWASP floor), not a token value: $iterations",
            iterations!! >= 100_000
        )
        assertTrue(SettingsStore.verifyPassword(mockContext, "myPassword1"))
        assertFalse(SettingsStore.verifyPassword(mockContext, "wrongPassword"))
    }

    // [PROGRAMMATIC] SET-TEST-010: a pre-existing untagged legacy SHA-256(salt||password) hash
    // (as written by the superseded SET-BEHAVE-001 mechanism, or synced in from a peer still on
    // the old build) still verifies correctly, and a successful verification migrates it in
    // place to a pbkdf2$-tagged hash -- no forced reset.
    @Test
    fun testVerifyPasswordMigratesLegacyShaHashToPbkdf2() {
        val salt = ByteArray(16) { it.toByte() }
        val legacyDigest = MessageDigest.getInstance("SHA-256").apply {
            update(salt)
        }.digest("hunter2".toByteArray(Charsets.UTF_8))
        val legacyHashHex = legacyDigest.joinToString("") { "%02x".format(it) }
        val saltHex = salt.joinToString("") { "%02x".format(it) }

        inMemoryPrefs["web_password_salt"] = saltHex
        inMemoryPrefs["web_password_hash"] = legacyHashHex

        assertFalse(
            "wrong password against a legacy hash must still be rejected",
            SettingsStore.verifyPassword(mockContext, "wrongPassword")
        )
        assertEquals(
            "a failed attempt must not touch the stored legacy hash",
            legacyHashHex,
            inMemoryPrefs["web_password_hash"]
        )

        assertTrue(
            "correct password against a legacy hash must still verify",
            SettingsStore.verifyPassword(mockContext, "hunter2")
        )

        val migratedHash = inMemoryPrefs["web_password_hash"] as? String
        assertNotNull(migratedHash)
        assertTrue(
            "successful legacy verification must migrate the stored hash to pbkdf2$: $migratedHash",
            migratedHash!!.startsWith("pbkdf2$")
        )
        assertTrue(
            "the migrated hash must still verify the same password",
            SettingsStore.verifyPassword(mockContext, "hunter2")
        )
    }
}
