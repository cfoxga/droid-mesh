package com.cfox.droidmesh.settings

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
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

    // [PROGRAMMATIC] SET-TEST-003: Config persistence & cross-VLAN seeds
    // [PROGRAMMATIC] MESH-TEST-004: Config sync across nodes with versioning
    @Test
    fun testExportAndImportConfig() {
        // Configure node A
        SettingsStore.setWebServerPort(mockContext, 2329)
        SettingsStore.setWebServerEnabled(mockContext, true)
        SettingsStore.setAutoUpdateEnabled(mockContext, false)
        SettingsStore.setPassword(mockContext, "fleetPassword123")
        SettingsStore.addCrossVlanSeed(mockContext, "192.168.50.64:2329")

        val exported = SettingsStore.exportConfigJson(mockContext)
        val initialVersion = exported.getLong("config_version")
        assertTrue(initialVersion > 0L)
        assertEquals(2329, exported.getInt("web_server_port"))
        assertFalse(exported.getBoolean("auto_update_enabled"))

        // Reset prefs for simulated node B
        inMemoryPrefs.clear()
        assertEquals(2325, SettingsStore.getWebServerPort(mockContext)) // default
        assertTrue(SettingsStore.isAutoUpdateEnabled(mockContext)) // default

        // Import exported config onto node B
        val importResult = SettingsStore.importConfigJson(mockContext, exported)
        assertTrue(importResult.applied)
        assertTrue(importResult.portChanged)
        assertTrue(importResult.autoUpdateToggled)
        assertTrue(importResult.passwordChanged)
        assertTrue(importResult.seedsChanged)

        // Verify Node B now matches Node A
        assertEquals(2329, SettingsStore.getWebServerPort(mockContext))
        assertFalse(SettingsStore.isAutoUpdateEnabled(mockContext))
        assertTrue(SettingsStore.verifyPassword(mockContext, "fleetPassword123"))
        assertTrue(SettingsStore.getCrossVlanSeeds(mockContext).contains("192.168.50.64:2329"))

        // Re-importing older or equal version should be ignored
        val reimportResult = SettingsStore.importConfigJson(mockContext, exported)
        assertFalse(reimportResult.applied)
    }

    // [PROGRAMMATIC] SET-TEST-001: Password verification
    // [PROGRAMMATIC] SET-TEST-002: Token generation and validation
    @Test
    fun testPasswordTokenVerificationAcrossNodes() {
        SettingsStore.setPassword(mockContext, "superSecretKey")
        val tokenOnNodeA = SettingsStore.generateToken(mockContext)
        assertTrue(SettingsStore.validateToken(mockContext, tokenOnNodeA))

        val exported = SettingsStore.exportConfigJson(mockContext)

        // Wipe and simulate Node B importing Node A's config
        inMemoryPrefs.clear()
        SettingsStore.importConfigJson(mockContext, exported)

        // Node B should validate token generated by Node A and verify password
        assertTrue(SettingsStore.validateToken(mockContext, tokenOnNodeA))
        assertTrue(SettingsStore.verifyPassword(mockContext, "superSecretKey"))
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
            isSideloaded = false
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

    // [PROGRAMMATIC] APP-TEST-006: Partition-specific app library defaults and DroidMesh exclusion
    @Test
    fun testMeshAppLibraryDefaultsByMeshPartition() {
        // Google TV mesh should NOT default Kiosk Satellite and should NEVER include DroidMesh
        val gtvLibrary = SettingsStore.getMeshAppLibrary(mockContext, "googletv")
        assertFalse(gtvLibrary.containsKey("me.jxl.kiosk_satellite"))
        assertFalse(gtvLibrary.containsKey("com.cfox.droidmesh"))

        // Meta Portals mesh SHOULD default Kiosk Satellite, but NEVER include DroidMesh
        val portalsLibrary = SettingsStore.getMeshAppLibrary(mockContext, "meta-portals")
        assertTrue(portalsLibrary.containsKey("me.jxl.kiosk_satellite"))
        assertEquals("Kiosk Satellite", portalsLibrary["me.jxl.kiosk_satellite"]?.appName)
        assertFalse(portalsLibrary.containsKey("com.cfox.droidmesh"))
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
}
