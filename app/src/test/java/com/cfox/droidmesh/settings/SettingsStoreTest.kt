package com.cfox.droidmesh.settings

import android.content.Context
import android.content.SharedPreferences
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock

class SettingsStoreTest {

    private val inMemoryPrefs = mutableMapOf<String, Any>()
    private lateinit var sharedPrefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var mockContext: Context

    @Before
    fun setUp() {
        inMemoryPrefs.clear()

        editor = mock {
            org.mockito.kotlin.whenever(it.putString(any(), org.mockito.kotlin.anyOrNull())).thenAnswer { invocation ->
                val key = invocation.getArgument<String>(0)
                val value = invocation.getArgument<String?>(1)
                if (value != null) inMemoryPrefs[key] = value else inMemoryPrefs.remove(key)
                editor
            }
            org.mockito.kotlin.whenever(it.putStringSet(any(), org.mockito.kotlin.anyOrNull())).thenAnswer { invocation ->
                val key = invocation.getArgument<String>(0)
                val value = invocation.getArgument<Set<String>?>(1)
                if (value != null) inMemoryPrefs[key] = value else inMemoryPrefs.remove(key)
                editor
            }
            org.mockito.kotlin.whenever(it.putBoolean(any(), any())).thenAnswer { invocation ->
                val key = invocation.getArgument<String>(0)
                val value = invocation.getArgument<Boolean>(1)
                inMemoryPrefs[key] = value
                editor
            }
            org.mockito.kotlin.whenever(it.putInt(any(), any())).thenAnswer { invocation ->
                val key = invocation.getArgument<String>(0)
                val value = invocation.getArgument<Int>(1)
                inMemoryPrefs[key] = value
                editor
            }
            org.mockito.kotlin.whenever(it.remove(any())).thenAnswer { invocation ->
                val key = invocation.getArgument<String>(0)
                inMemoryPrefs.remove(key)
                editor
            }
            org.mockito.kotlin.whenever(it.apply()).thenAnswer { }
            org.mockito.kotlin.whenever(it.commit()).thenAnswer { true }
        }

        sharedPrefs = mock {
            org.mockito.kotlin.whenever(it.getBoolean(any(), any())).thenAnswer { invocation ->
                val key = invocation.getArgument<String>(0)
                val def = invocation.getArgument<Boolean>(1)
                inMemoryPrefs[key] as? Boolean ?: def
            }
            org.mockito.kotlin.whenever(it.getInt(any(), any())).thenAnswer { invocation ->
                val key = invocation.getArgument<String>(0)
                val def = invocation.getArgument<Int>(1)
                inMemoryPrefs[key] as? Int ?: def
            }
            org.mockito.kotlin.whenever(it.getString(any(), org.mockito.kotlin.anyOrNull())).thenAnswer { invocation ->
                val key = invocation.getArgument<String>(0)
                val def = invocation.getArgument<String?>(1)
                inMemoryPrefs[key] as? String ?: def
            }

            org.mockito.kotlin.whenever(it.getStringSet(any(), org.mockito.kotlin.anyOrNull())).thenAnswer { invocation ->
                @Suppress("UNCHECKED_CAST")
                inMemoryPrefs[invocation.getArgument<String>(0)] as? Set<String> ?: invocation.getArgument<Set<String>?>(1) ?: emptySet<String>()
            }
            org.mockito.kotlin.whenever(it.edit()).thenAnswer { editor }
        }

        mockContext = mock {
            org.mockito.kotlin.whenever(it.getSharedPreferences(any(), any())).thenAnswer { sharedPrefs }
        }

    }

    @Test
    fun testAutoUpdateSetting() {
        assertTrue(SettingsStore.isAutoUpdateEnabled(mockContext))
        SettingsStore.setAutoUpdateEnabled(mockContext, false)
        assertFalse(SettingsStore.isAutoUpdateEnabled(mockContext))
        SettingsStore.setAutoUpdateEnabled(mockContext, true)
        assertTrue(SettingsStore.isAutoUpdateEnabled(mockContext))
    }

    @Test
    fun testWebServerSettings() {
        assertTrue(SettingsStore.isWebServerEnabled(mockContext))
        SettingsStore.setWebServerEnabled(mockContext, false)
        assertFalse(SettingsStore.isWebServerEnabled(mockContext))
        SettingsStore.setWebServerEnabled(mockContext, true)
        assertTrue(SettingsStore.isWebServerEnabled(mockContext))

        assertEquals(2325, SettingsStore.getWebServerPort(mockContext))
        SettingsStore.setWebServerPort(mockContext, 8080)
        assertEquals(8080, SettingsStore.getWebServerPort(mockContext))
    }


    @Test
    fun testPasswordLifecycleAndVerification() {
        assertFalse(SettingsStore.isPasswordSet(mockContext))
        assertTrue("When no password is set, verify returns true", SettingsStore.verifyPassword(mockContext, "any"))

        // Set password
        SettingsStore.setPassword(mockContext, "superSecret123")
        assertTrue(SettingsStore.isPasswordSet(mockContext))

        // Verify correct & incorrect passwords
        assertTrue(SettingsStore.verifyPassword(mockContext, "superSecret123"))
        assertFalse(SettingsStore.verifyPassword(mockContext, "wrongPassword"))
        assertFalse(SettingsStore.verifyPassword(mockContext, ""))

        // Clear password
        SettingsStore.setPassword(mockContext, "")
        assertFalse(SettingsStore.isPasswordSet(mockContext))
    }

    @Test
    fun testTokenGenerationAndValidation() {
        // No password set -> validateToken returns true
        assertTrue(SettingsStore.validateToken(mockContext, null))
        assertTrue(SettingsStore.validateToken(mockContext, "invalid.token"))

        // Set password -> validation requires signed token
        SettingsStore.setPassword(mockContext, "adminPass")

        assertFalse("Null token fails when password set", SettingsStore.validateToken(mockContext, null))
        assertFalse("Malformed token fails", SettingsStore.validateToken(mockContext, "badtoken"))
        assertFalse("Tampered token fails", SettingsStore.validateToken(mockContext, "9999999999999.invalidSignature"))

        val token = SettingsStore.generateToken(mockContext, ttlSeconds = 3600)
        assertNotNull(token)
        assertTrue("Valid token succeeds", SettingsStore.validateToken(mockContext, token))

        // Changing password cycles secret and invalidates existing tokens
        SettingsStore.setPassword(mockContext, "newAdminPass")
        assertFalse("Previous token invalidated after password change", SettingsStore.validateToken(mockContext, token))

        val newToken = SettingsStore.generateToken(mockContext, ttlSeconds = 3600)
        assertTrue("New token succeeds", SettingsStore.validateToken(mockContext, newToken))
    }

    @Test
    fun testMeshIdentityAndCrossVlanSeeds() {
        // Defaults
        assertEquals("meta-portals", SettingsStore.getLocalMeshId(mockContext))
        assertEquals("Meta Portals", SettingsStore.getLocalMeshName(mockContext))
        assertTrue(SettingsStore.getCrossVlanSeeds(mockContext).isEmpty())

        // Custom Mesh Identity
        SettingsStore.setLocalMeshId(mockContext, "googletv")
        SettingsStore.setLocalMeshName(mockContext, "Google TV")
        assertEquals("googletv", SettingsStore.getLocalMeshId(mockContext))
        assertEquals("Google TV", SettingsStore.getLocalMeshName(mockContext))

        // Cross-VLAN Seeds
        assertTrue(SettingsStore.addCrossVlanSeed(mockContext, "192.168.50.10:2325"))
        assertTrue(SettingsStore.addCrossVlanSeed(mockContext, "192.168.50.11:2325"))
        assertFalse("Duplicate seed addition returns false", SettingsStore.addCrossVlanSeed(mockContext, "192.168.50.10:2325"))

        val seeds = SettingsStore.getCrossVlanSeeds(mockContext)
        assertEquals(2, seeds.size)
        assertTrue(seeds.contains("192.168.50.10:2325"))
        assertTrue(seeds.contains("192.168.50.11:2325"))

        // Remove seed
        assertTrue(SettingsStore.removeCrossVlanSeed(mockContext, "192.168.50.10:2325"))
        assertEquals(1, SettingsStore.getCrossVlanSeeds(mockContext).size)
        assertFalse(SettingsStore.getCrossVlanSeeds(mockContext).contains("192.168.50.10:2325"))
    }
}
