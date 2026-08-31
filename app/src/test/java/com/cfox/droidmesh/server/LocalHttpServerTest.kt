package com.cfox.droidmesh.server

import android.content.Context
import android.content.SharedPreferences
import com.cfox.droidmesh.api.ReleaseInfo

import com.cfox.droidmesh.settings.SettingsStore
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.io.ByteArrayInputStream

class LocalHttpServerTest {

    private val inMemoryPrefs = mutableMapOf<String, Any>()
    private lateinit var mockContext: Context
    private lateinit var mockCoordinator: UpdateCoordinator
    private lateinit var server: LocalHttpServer

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

        mockCoordinator = mock {
            whenever(it.statusFlow).thenReturn(
                kotlinx.coroutines.flow.MutableStateFlow(
                    com.cfox.droidmesh.api.UpdateStatus(state = "IDLE", message = "Ready")
                )
            )
        }



        server = LocalHttpServer(
            context = mockContext,
            coordinator = mockCoordinator,
            meshManager = null,
            port = 2325
        )
    }

    private fun mockSession(
        uri: String,
        method: NanoHTTPD.Method = NanoHTTPD.Method.GET,
        headers: Map<String, String> = emptyMap(),
        params: Map<String, String> = emptyMap(),
        postBody: String? = null
    ): NanoHTTPD.IHTTPSession {
        val mutableHeaders = headers.toMutableMap()
        val bodyBytes = postBody?.toByteArray(Charsets.UTF_8)
        if (bodyBytes != null) {
            mutableHeaders["content-length"] = bodyBytes.size.toString()
        }
        val cookies = server.CookieHandler(mutableHeaders)
        return mock {
            whenever(it.uri).thenReturn(uri)
            whenever(it.method).thenReturn(method)
            whenever(it.headers).thenReturn(mutableHeaders)
            whenever(it.parms).thenReturn(params)
            whenever(it.cookies).thenReturn(cookies)
            whenever(it.remoteIpAddress).thenReturn("192.168.40.100")
            if (bodyBytes != null) {
                whenever(it.inputStream).thenAnswer { ByteArrayInputStream(bodyBytes) }
            }
        }
    }



    @Test
    fun testStatusEndpointReturnsOk() {
        val session = mockSession("/api/status")
        val response = server.serve(session)
        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        assertEquals("application/json; charset=utf-8", response.mimeType)
    }

    @Test
    fun testAuthStatusWhenNoPassword() {
        val session = mockSession("/api/auth/status")
        val response = server.serve(session)
        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
    }

    @Test
    fun testLoginAndProtectedEndpoints() {
        // Set password
        SettingsStore.setPassword(mockContext, "secret123")

        // 1. Unauthenticated request to /api/settings (POST) should fail with 401
        val unauthSession = mockSession(
            uri = "/api/settings",
            method = NanoHTTPD.Method.POST,
            postBody = """{"autoUpdateEnabled": false}"""
        )
        val unauthResponse = server.serve(unauthSession)
        assertEquals(NanoHTTPD.Response.Status.UNAUTHORIZED, unauthResponse.status)

        // 2. Login with wrong password should fail
        val badLoginSession = mockSession(
            uri = "/api/login",
            method = NanoHTTPD.Method.POST,
            postBody = """{"password": "wrong"}"""
        )
        val badLoginResponse = server.serve(badLoginSession)
        assertEquals(NanoHTTPD.Response.Status.UNAUTHORIZED, badLoginResponse.status)

        // 3. Login with correct password should return token
        val goodLoginSession = mockSession(
            uri = "/api/login",
            method = NanoHTTPD.Method.POST,
            postBody = """{"password": "secret123"}"""
        )
        val goodLoginResponse = server.serve(goodLoginSession)
        assertEquals(NanoHTTPD.Response.Status.OK, goodLoginResponse.status)

        // 4. Authenticated request with Bearer token should succeed
        val validToken = SettingsStore.generateToken(mockContext)
        val authedSession = mockSession(
            uri = "/api/settings",
            method = NanoHTTPD.Method.POST,
            headers = mapOf("authorization" to "Bearer $validToken"),
            postBody = """{"autoUpdateEnabled": false}"""
        )
        val authedResponse = server.serve(authedSession)
        assertEquals(NanoHTTPD.Response.Status.OK, authedResponse.status)
        assertFalse(SettingsStore.isAutoUpdateEnabled(mockContext))
    }
}
