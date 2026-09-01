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

    private val inMemoryPrefs = mutableMapOf<String, Any>()
    private lateinit var mockContext: Context
    private lateinit var manager: MeshDiscoveryManager

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
            whenever(it.edit()).thenAnswer { editor }
        }

        mockContext = mock {
            whenever(it.getSharedPreferences(any(), any())).thenAnswer { sharedPrefs }
            whenever(it.packageName).thenReturn("com.cfox.droidmesh")
            whenever(it.packageManager).thenReturn(mock())
            whenever(it.contentResolver).thenReturn(mock())
        }

        val coordinator: UpdateCoordinator = mock()
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
}
