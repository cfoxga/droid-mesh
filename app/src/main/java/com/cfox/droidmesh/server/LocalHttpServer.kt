package com.cfox.droidmesh.server

import android.content.Context
import com.cfox.droidmesh.BuildConfig
import com.cfox.droidmesh.api.ReleaseInfo
import com.cfox.droidmesh.installer.AppVersionHelper
import com.cfox.droidmesh.mesh.MeshDiscoveryManager
import com.cfox.droidmesh.mesh.PeerNode
import com.cfox.droidmesh.service.AutoInstallService
import com.cfox.droidmesh.settings.SettingsStore
import com.cfox.droidmesh.utils.AdbHelper
import com.cfox.droidmesh.utils.CpuStatsHelper
import com.cfox.droidmesh.utils.Logger
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class LocalHttpServer(
    private val context: Context,
    private val coordinator: UpdateCoordinator,
    private val meshManager: MeshDiscoveryManager? = null,
    val activePort: Int = 2325
) : NanoHTTPD("0.0.0.0", activePort) {


    init {
        val pool = java.util.concurrent.Executors.newCachedThreadPool { r ->
            Thread(r, "DroidMesh-HTTP-Worker").apply {
                isDaemon = false
            }
        }
        setAsyncRunner(object : AsyncRunner {
            override fun exec(code: ClientHandler) {
                pool.execute(code)
            }
            override fun closed(clientHandler: ClientHandler) {}
            override fun closeAll() {
                try { pool.shutdownNow() } catch (_: Exception) {}
            }
        })
    }

    override fun createClientHandler(accept: java.net.Socket, inputStream: InputStream): ClientHandler {
        val rawAddress = accept.inetAddress
        val fastAddress = try {
            val ip = rawAddress.hostAddress ?: "127.0.0.1"
            java.net.InetAddress.getByAddress(ip, rawAddress.address)
        } catch (_: Exception) {
            rawAddress
        }
        val wrapper = object : java.net.Socket() {
            override fun getInetAddress(): java.net.InetAddress = fastAddress
            override fun getInputStream(): InputStream = accept.getInputStream()
            override fun getOutputStream(): java.io.OutputStream = accept.getOutputStream()
            override fun close() = accept.close()
            override fun isClosed(): Boolean = accept.isClosed
            override fun isConnected(): Boolean = accept.isConnected
            override fun setSoTimeout(timeout: Int) { accept.soTimeout = timeout }
            override fun getSoTimeout(): Int = accept.soTimeout
        }
        return super.createClientHandler(wrapper, inputStream)
    }





    private val httpClient = OkHttpClient.Builder()

        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO)

    private val webHtmlBytes by lazy {
        try {
            context.assets.open("web/index.html").use { it.readBytes() }
        } catch (e: Exception) {
            Logger.e("Failed to load web/index.html from assets", e)
            ByteArray(0)
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        Logger.i("LocalHttpServer.serve: $method $uri from ${session.remoteIpAddress}")

        // Handle CORS Preflight

        if (method == Method.OPTIONS) {
            val res = newFixedLengthResponse(Response.Status.OK, "text/plain", "")
            addCorsHeaders(res)
            return res
        }

        return try {
            when {
                // Static Web Administration Interface
                (uri == "/" || uri == "/index.html" || uri == "/overview" || uri == "/settings" || uri == "/mesh" || uri == "/logs") && method == Method.GET -> {
                    val accept = session.headers["accept"] ?: ""
                    if (accept.contains("application/json") && uri == "/") {
                        handleStatus(session)
                    } else {
                        handleServeWeb()
                    }
                }

                // Auth Endpoints
                uri == "/api/auth/status" && method == Method.GET -> handleAuthStatus(session)
                uri == "/api/login" && method == Method.POST -> handleLogin(session)
                uri == "/api/logout" && method == Method.POST -> handleLogout()
                uri == "/api/password" && method == Method.POST -> handlePassword(session)

                // Status & Health
                (uri == "/status" || uri == "/api/status" || uri == "/api/health") && method == Method.GET -> handleStatus(session)

                // Settings
                uri == "/api/settings" && method == Method.GET -> handleGetSettings()
                uri == "/api/settings" && method == Method.POST -> handlePostSettings(session)

                // Releases & Updates
                (uri == "/check" || uri == "/api/check" || uri == "/api/releases") && method == Method.GET -> handleCheck(session)
                (uri == "/update" || uri == "/api/update") && (method == Method.POST || method == Method.GET) -> handleUpdate(session)

                // Network ADB
                (uri == "/adb/toggle" || uri == "/api/adb/toggle") && (method == Method.POST || method == Method.GET) -> handleAdbToggle(session)

                // Peer Mesh Fleet
                (uri == "/mesh" || uri == "/peers" || uri == "/api/mesh") && method == Method.GET -> handleMesh()
                (uri == "/api/mesh/library" || uri == "/mesh/library") && method == Method.GET -> handleMeshLibraryGet(session)
                (uri == "/api/mesh/library" || uri == "/mesh/library") && method == Method.POST -> handleMeshLibraryPost(session)
                uri == "/api/mesh/config" && method == Method.GET -> handleMeshConfigGet()
                uri == "/api/mesh/sync-config" && method == Method.POST -> handleMeshConfigSync(session)
                uri == "/api/mesh/beacon" && (method == Method.POST || method == Method.GET) -> handleMeshBeacon(session)
                uri == "/api/mesh/connect" && method == Method.POST -> handleMeshConnect(session)
                uri == "/api/mesh/handshake" && method == Method.POST -> handleMeshHandshake(session)
                (uri == "/api/mesh/seeds" || uri == "/api/mesh/persistent-connections") && method == Method.GET -> handleMeshPersistentConnectionsGet()
                (uri == "/api/mesh/seeds" || uri == "/api/mesh/seeds/remove" || uri == "/api/mesh/persistent-connections" || uri == "/api/mesh/persistent-connections/remove") && (method == Method.DELETE || method == Method.POST) -> handleMeshPersistentConnectionsModify(session)
                uri == "/api/mesh/create" && method == Method.POST -> handleMeshCreate(session)
                uri == "/api/mesh/update" && method == Method.POST -> handleMeshUpdate(session)
                uri == "/api/mesh/delete" && method == Method.POST -> handleMeshDelete(session)
                uri == "/api/peers/update" && method == Method.POST -> handlePeerUpdate(session)
                uri == "/api/peers/adb/toggle" && method == Method.POST -> handlePeerAdbToggle(session)

                // Runtime Logs
                (uri == "/logs" || uri == "/api/logs") && method == Method.GET -> handleLogs()
                uri == "/api/logs/clear" && method == Method.POST -> handleLogsClear(session)

                else -> {
                    jsonResponse(
                        Response.Status.NOT_FOUND,
                        JSONObject().apply {
                            put("status", "error")
                            put("message", "Endpoint not found: ${method.name} $uri")
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Logger.e("Error processing HTTP request ($method $uri)", e)
            jsonResponse(
                Response.Status.INTERNAL_ERROR,
                JSONObject().apply {
                    put("status", "error")
                    put("error", e.message ?: "Internal Server Error")
                }
            )
        }
    }

    // --- Authentication Helpers ---

    private fun extractToken(session: IHTTPSession): String? {
        val authHeader = session.headers["authorization"] ?: session.headers["Authorization"]
        if (!authHeader.isNullOrBlank() && authHeader.startsWith("Bearer ", ignoreCase = true)) {
            return authHeader.substring(7).trim()
        }

        val customHeader = session.headers["x-auth-token"] ?: session.headers["X-Auth-Token"]
        if (!customHeader.isNullOrBlank()) {
            return customHeader.trim()
        }

        val cookieHeader = session.headers["cookie"] ?: session.headers["Cookie"]
        if (!cookieHeader.isNullOrBlank()) {
            val token = cookieHeader.split(";")
                .map { it.trim() }
                .firstOrNull { it.startsWith("auth_token=") }
                ?.substringAfter("auth_token=")
            if (!token.isNullOrBlank()) return token
        }

        return session.parms["token"]?.trim()
    }

    private fun isAuthorized(session: IHTTPSession): Boolean {
        if (!SettingsStore.isPasswordSet(context)) return true
        val token = extractToken(session)
        return SettingsStore.validateToken(context, token)
    }

    private fun parseJsonBody(session: IHTTPSession): JSONObject {
        if (session.method != Method.POST && session.method != Method.PUT && session.method != Method.PATCH && session.method != Method.DELETE) {
            return JSONObject()
        }
        val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength <= 0) return JSONObject()

        return try {
            val buf = ByteArray(contentLength)
            var totalRead = 0
            val input = session.inputStream
            while (totalRead < contentLength) {
                val count = input.read(buf, totalRead, contentLength - totalRead)
                if (count <= 0) break
                totalRead += count
            }
            val text = String(buf, 0, totalRead, Charsets.UTF_8)
            if (text.isNotBlank()) JSONObject(text) else JSONObject()
        } catch (e: Exception) {
            JSONObject()
        }
    }


    // --- Web Assets Serving ---

    private fun handleServeWeb(): Response {
        val bytes = webHtmlBytes
        if (bytes.isEmpty()) {
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "text/plain",
                "Web administration UI asset not found."
            )
        }
        val html = String(bytes, Charsets.UTF_8)
        val res = newFixedLengthResponse(
            Response.Status.OK,
            "text/html; charset=utf-8",
            html
        )
        addCorsHeaders(res)
        return res
    }



    // --- Endpoint Handlers ---

    private fun handleAuthStatus(session: IHTTPSession): Response {
        val pwdSet = SettingsStore.isPasswordSet(context)
        val authed = if (pwdSet) isAuthorized(session) else true

        val json = JSONObject().apply {
            put("status", "ok")
            put("passwordSet", pwdSet)
            put("authenticated", authed)
        }
        return jsonResponse(Response.Status.OK, json)
    }

    private fun handleLogin(session: IHTTPSession): Response {
        val body = parseJsonBody(session)
        val password = body.optString("password", session.parms["password"] ?: "")

        if (SettingsStore.verifyPassword(context, password)) {
            val token = SettingsStore.generateToken(context)
            val json = JSONObject().apply {
                put("status", "ok")
                put("message", "Authentication successful")
                put("token", token)
                put("expiresIn", 7 * 86400)
            }
            return jsonResponse(Response.Status.OK, json, cookies = mapOf("auth_token" to token))
        } else {
            val json = JSONObject().apply {
                put("status", "error")
                put("error", "Invalid admin password")
            }
            return jsonResponse(Response.Status.UNAUTHORIZED, json)
        }
    }

    private fun handleLogout(): Response {
        val json = JSONObject().apply {
            put("status", "ok")
            put("message", "Logged out")
        }
        val res = jsonResponse(Response.Status.OK, json)
        res.addHeader("Set-Cookie", "auth_token=; Path=/; Max-Age=0; SameSite=Lax")
        return res
    }

    private fun handlePassword(session: IHTTPSession): Response {
        if (SettingsStore.isPasswordSet(context) && !isAuthorized(session)) {
            return jsonResponse(Response.Status.UNAUTHORIZED, JSONObject().apply {
                put("status", "error")
                put("error", "Authentication required to change password")
            })
        }

        val body = parseJsonBody(session)
        val currentPassword = body.optString("currentPassword", "")
        val newPassword = body.optString("password", "")

        if (SettingsStore.isPasswordSet(context)) {
            if (!SettingsStore.verifyPassword(context, currentPassword)) {
                return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().apply {
                    put("status", "error")
                    put("error", "Current password does not match")
                })
            }
        }

        SettingsStore.setPassword(context, newPassword)
        meshManager?.syncConfigToMesh()
        val newToken = if (newPassword.isNotBlank()) SettingsStore.generateToken(context) else null

        val json = JSONObject().apply {
            put("status", "ok")
            put("message", if (newPassword.isBlank()) "Admin password removed" else "Admin password updated successfully")
            if (newToken != null) {
                put("token", newToken)
            }
        }

        val cookies = if (newToken != null) mapOf("auth_token" to newToken) else mapOf("auth_token" to "")
        return jsonResponse(Response.Status.OK, json, cookies = cookies)
    }

    // TargetResolution / resolveTargetApp: resolves which App Library entry /status, /check,
    // and /update operate on ("the target app"). There is no hardcoded default target app or
    // release URL anywhere in this class — downloadUrl on the resolved entry is the only source
    // of release information. See API-BEHAVE-014/015.
    private sealed class TargetResolution {
        data class Resolved(val packageName: String, val config: SettingsStore.MeshAppConfig) : TargetResolution()
        object None : TargetResolution()
        data class Ambiguous(val candidates: List<String>) : TargetResolution()
    }

    private fun resolveTargetApp(explicitPackage: String?): TargetResolution {
        val meshId = SettingsStore.getLocalMeshId(context)
        val library = SettingsStore.getMeshAppLibrary(context, meshId)

        if (explicitPackage != null) {
            val cfg = library[explicitPackage] ?: return TargetResolution.None
            return TargetResolution.Resolved(explicitPackage, cfg)
        }

        val managed = library.values.filter { it.managed }
        return when {
            managed.isEmpty() -> TargetResolution.None
            managed.size == 1 -> TargetResolution.Resolved(managed.first().packageName, managed.first())
            else -> TargetResolution.Ambiguous(managed.map { it.packageName })
        }
    }

    private fun handleStatus(session: IHTTPSession): Response {
        val explicitPackage = session.parms["package"]?.trim()?.ifBlank { null }
        val resolution = resolveTargetApp(explicitPackage)
        val installed = when (resolution) {
            is TargetResolution.Resolved -> AppVersionHelper.getInstalledVersion(context, resolution.packageName)
            else -> AppVersionHelper.InstalledInfo(isInstalled = false, versionName = null, versionCode = null)
        }
        val installedApps = AppVersionHelper.getUserInstalledApps(context)
        val currentStatus = coordinator.statusFlow.value
        val telemetry = CpuStatsHelper.readTelemetry()

        val json = JSONObject().apply {
            put("status", "ok")
            put("app", "DroidMesh")
            put("version", BuildConfig.VERSION_NAME)
            put("versionCode", BuildConfig.VERSION_CODE)
            put("targetPackage", if (resolution is TargetResolution.Resolved) resolution.packageName else JSONObject.NULL)
            if (resolution is TargetResolution.Ambiguous) {
                put("managedCandidates", JSONArray(resolution.candidates))
            } else {
                put("managedCandidates", JSONArray())
            }
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

            put("accessibilityServiceActive", AutoInstallService.isServiceRunning)
            put("webServerEnabled", SettingsStore.isWebServerEnabled(context))
            put("webServerPort", SettingsStore.getWebServerPort(context))
            put("adbEnabled", AdbHelper.isAdbEnabled(context))
            put("updaterState", currentStatus.state)
            put("updaterMessage", currentStatus.message)
            put("progressPercent", currentStatus.progressPercent)
            put("deviceModel", CpuStatsHelper.getDeviceName(context))
            put("cpuUsage", telemetry.usagePercent ?: JSONObject.NULL)
            put("cpuTemp", telemetry.tempCelsius ?: JSONObject.NULL)
            put("passwordConfigured", SettingsStore.isPasswordSet(context))
            put("configVersion", SettingsStore.getConfigVersion(context))
        }

        return jsonResponse(Response.Status.OK, json)
    }

    private fun handleGetSettings(): Response {
        val seedsJson = JSONArray()
        SettingsStore.getPersistentConnections(context).forEach { seedsJson.put(it) }

        val json = JSONObject().apply {
            put("status", "ok")
            put("webServerEnabled", SettingsStore.isWebServerEnabled(context))
            put("webServerPort", SettingsStore.getWebServerPort(context))
            put("hasPassword", SettingsStore.isPasswordSet(context))
            put("adbEnabled", AdbHelper.isAdbEnabled(context))
            put("localMeshId", SettingsStore.getLocalMeshId(context))
            put("localMeshName", SettingsStore.getLocalMeshName(context))
            put("customDeviceName", SettingsStore.getCustomDeviceName(context))
            put("effectiveDeviceName", CpuStatsHelper.getDeviceName(context))
            put("crossVlanSeeds", seedsJson)
            put("configVersion", SettingsStore.getConfigVersion(context))
        }
        return jsonResponse(Response.Status.OK, json)
    }

    private fun handlePostSettings(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            return jsonResponse(Response.Status.UNAUTHORIZED, JSONObject().apply {
                put("status", "error")
                put("error", "Unauthorized")
            })
        }

        val body = parseJsonBody(session)
        var settingsChanged = false
        if (body.has("webServerEnabled")) {
            val enabled = body.getBoolean("webServerEnabled")
            SettingsStore.setWebServerEnabled(context, enabled)
            Logger.i("Web server toggled via HTTP API: $enabled")
            settingsChanged = true
        }
        if (body.has("webServerPort")) {
            val port = body.getInt("webServerPort")
            SettingsStore.setWebServerPort(context, port)
            Logger.i("Web server port updated via HTTP API: $port")
            settingsChanged = true
        }
        if (body.has("localMeshId")) {
            val meshId = body.getString("localMeshId").trim()
            if (meshId.isNotBlank()) {
                SettingsStore.setLocalMeshId(context, meshId)
                Logger.i("Local mesh ID updated: $meshId")
                settingsChanged = true
            }
        }
        if (body.has("localMeshName")) {
            val meshName = body.getString("localMeshName").trim()
            if (meshName.isNotBlank()) {
                SettingsStore.setLocalMeshName(context, meshName)
                Logger.i("Local mesh name updated: $meshName")
                settingsChanged = true
            }
        }
        if (body.has("customDeviceName")) {
            val deviceName = body.getString("customDeviceName").trim()
            SettingsStore.setCustomDeviceName(context, deviceName)
            if (deviceName.isNotBlank()) {
                Logger.i("Custom device name set: $deviceName")
            } else {
                Logger.i("Custom device name cleared, will use default system name")
            }
            settingsChanged = true
        }

        if (settingsChanged) {
            meshManager?.syncConfigToMesh()
        }

        return handleGetSettings()
    }

    private fun handleCheck(session: IHTTPSession): Response {
        val force = session.parms["force"]?.toBoolean() ?: false
        val explicitPackage = session.parms["package"]?.trim()?.ifBlank { null }

        val resolution = resolveTargetApp(explicitPackage)
        when (resolution) {
            is TargetResolution.None -> return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().apply {
                put("status", "error")
                put("message", "No managed app configured. Set a downloadUrl and mark an App Library entry as Managed.")
            })
            is TargetResolution.Ambiguous -> return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().apply {
                put("status", "error")
                put("message", "Multiple managed apps configured; specify ?package=")
                put("managedCandidates", JSONArray(resolution.candidates))
            })
            is TargetResolution.Resolved -> {}
        }
        val resolved = resolution as TargetResolution.Resolved
        val downloadUrl = resolved.config.downloadUrl.trim()
        if (downloadUrl.isBlank()) {
            return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().apply {
                put("status", "error")
                put("message", "No downloadUrl configured for ${resolved.packageName}")
            })
        }

        val releasesResult = runBlocking { coordinator.fetchAvailableReleases(downloadUrl, forceRefresh = force) }
        val checkResult = runBlocking { coordinator.checkVersion(resolved.packageName, downloadUrl) }

        if (checkResult.isSuccess) {
            val comp = checkResult.getOrThrow()
            val json = JSONObject().apply {
                put("status", "ok")
                put("targetPackage", resolved.packageName)
                put("installedVersionName", comp.installedVersionName ?: JSONObject.NULL)
                put("installedVersionCode", comp.installedVersionCode ?: JSONObject.NULL)
                put("latestVersionTag", comp.latestVersionTag)
                put("updateAvailable", comp.isUpdateAvailable)
                put("release", JSONObject().apply {
                    put("name", comp.releaseInfo.name)
                    put("tagName", comp.releaseInfo.tagName)
                    put("publishedAt", comp.releaseInfo.publishedAt)
                    put("apkAssetUrl", comp.releaseInfo.apkAssetUrl)
                    put("apkFileName", comp.releaseInfo.apkFileName)
                    put("apkSize", comp.releaseInfo.apkSize)
                })

                val relArr = JSONArray()
                if (releasesResult.isSuccess) {
                    for (rel in releasesResult.getOrThrow()) {
                        relArr.put(JSONObject().apply {
                            put("name", rel.name)
                            put("tagName", rel.tagName)
                            put("publishedAt", rel.publishedAt)
                            put("apkAssetUrl", rel.apkAssetUrl)
                            put("apkFileName", rel.apkFileName)
                            put("apkSize", rel.apkSize)
                        })
                    }
                }
                put("releases", relArr)
            }
            return jsonResponse(Response.Status.OK, json)
        } else {
            val err = checkResult.exceptionOrNull()?.message ?: "Check failed"
            val json = JSONObject().apply {
                put("status", "error")
                put("message", err)
            }
            return jsonResponse(Response.Status.INTERNAL_ERROR, json)
        }
    }

    private fun handleUpdate(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            return jsonResponse(Response.Status.UNAUTHORIZED, JSONObject().apply {
                put("status", "error")
                put("error", "Unauthorized")
            })
        }

        val body = parseJsonBody(session)
        val force = body.optBoolean("force", session.parms["force"]?.toBoolean() ?: false)
        val tag = body.optString("tag", session.parms["tag"] ?: session.parms["version"] ?: "")
        val url = body.optString("url", session.parms["url"] ?: session.parms["download_url"] ?: "")
        val filenameOverride = body.optString("filename", session.parms["filename"] ?: "")
        val explicitPackage = body.optString("package", session.parms["package"] ?: "").trim().ifBlank { null }

        val resolution = resolveTargetApp(explicitPackage)
        when (resolution) {
            is TargetResolution.None -> return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().apply {
                put("status", "error")
                put("message", "No managed app configured. Set a downloadUrl and mark an App Library entry as Managed.")
            })
            is TargetResolution.Ambiguous -> return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().apply {
                put("status", "error")
                put("message", "Multiple managed apps configured; specify ?package=")
                put("managedCandidates", JSONArray(resolution.candidates))
            })
            is TargetResolution.Resolved -> {}
        }
        val resolved = resolution as TargetResolution.Resolved
        val packageName = resolved.packageName

        if (url.isNotBlank() && tag.isNotBlank()) {
            val specificRelease = ReleaseInfo(
                tagName = tag,
                name = tag,
                publishedAt = "",
                apkAssetUrl = url,
                apkFileName = filenameOverride.ifBlank { "$packageName-$tag.apk" },
                apkSize = 0L
            )
            coordinator.startUpdateForRelease(packageName, specificRelease, force = force)
        } else {
            val downloadUrl = resolved.config.downloadUrl.trim()
            if (downloadUrl.isBlank()) {
                return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().apply {
                    put("status", "error")
                    put("message", "No downloadUrl configured for $packageName")
                })
            }
            coordinator.startUpdateAsync(packageName, downloadUrl, force = force)
        }

        val json = JSONObject().apply {
            put("status", "accepted")
            put("message", "Update sequence initiated (force=$force, target=${tag.ifBlank { "latest" }})")
            put("targetPackage", packageName)
            put("accessibilityServiceActive", AutoInstallService.isServiceRunning)
        }

        return jsonResponse(Response.Status.ACCEPTED, json)
    }

    private fun handleAdbToggle(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            return jsonResponse(Response.Status.UNAUTHORIZED, JSONObject().apply {
                put("status", "error")
                put("error", "Unauthorized")
            })
        }

        AdbHelper.toggleAdb(context)
        val json = JSONObject().apply {
            put("status", "ok")
            put("adbEnabled", AdbHelper.isAdbEnabled(context))
        }
        return jsonResponse(Response.Status.OK, json)
    }

    private fun handleMesh(): Response {
        val grouped = meshManager?.getMeshesGrouped()
        if (grouped != null) {
            grouped.put("status", "ok")
            grouped.put("meshPort", MeshDiscoveryManager.MESH_PORT)
            return jsonResponse(Response.Status.OK, grouped)
        }

        val peers = meshManager?.peersFlow?.value ?: emptyList()
        val json = JSONObject().apply {
            put("status", "ok")
            put("count", peers.size)
            put("meshPort", MeshDiscoveryManager.MESH_PORT)
            val arr = JSONArray()
            for (peer in peers) {
                arr.put(peer.toJson())
            }
            put("peers", arr)
        }
        return jsonResponse(Response.Status.OK, json)
    }

    private fun handleMeshLibraryGet(session: IHTTPSession): Response {
        val meshId = session.parms["meshId"] ?: session.parms["mesh_id"] ?: SettingsStore.getLocalMeshId(context)
        val grouped = meshManager?.getMeshesGrouped()
        val meshesArray = grouped?.optJSONArray("meshes")

        val libraryList = if (meshesArray != null) {
            var foundArray: JSONArray? = null
            for (i in 0 until meshesArray.length()) {
                val m = meshesArray.getJSONObject(i)
                if (m.optString("id") == meshId) {
                    foundArray = m.optJSONArray("app_library")
                    break
                }
            }
            foundArray ?: JSONArray()
        } else {
            val stored = SettingsStore.getMeshAppLibrary(context, meshId)
            val arr = JSONArray()
            stored.values
                .filter { !AppVersionHelper.isExcludedAppPackage(it.packageName, context) }
                .sortedWith(
                    compareByDescending<SettingsStore.MeshAppConfig> { it.isSideloaded }
                        .thenBy { it.appName.lowercase() }
                )
                .forEach { arr.put(it.toJson()) }
            arr
        }

        val json = JSONObject().apply {
            put("status", "ok")
            put("mesh_id", meshId)
            put("library", libraryList)
        }
        return jsonResponse(Response.Status.OK, json)
    }

    private fun handleMeshLibraryPost(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            return jsonResponse(Response.Status.UNAUTHORIZED, JSONObject().apply {
                put("status", "error")
                put("error", "Unauthorized")
            })
        }

        val body = parseJsonBody(session)
        val meshId = body.optString("meshId", body.optString("mesh_id", SettingsStore.getLocalMeshId(context)))
        val appObj = body.optJSONObject("app") ?: body

        val pkg = appObj.optString("packageName", appObj.optString("package", ""))
        if (pkg.isBlank()) {
            return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().apply {
                put("status", "error")
                put("error", "Missing packageName")
            })
        }

        val config = SettingsStore.MeshAppConfig.fromJson(appObj)
        val newVer = SettingsStore.setMeshAppConfig(context, meshId, config)
        meshManager?.syncConfigToMesh()

        val json = JSONObject().apply {
            put("status", "ok")
            put("message", "Updated library configuration for ${config.appName}")
            put("mesh_id", meshId)
            put("config_version", newVer)
            put("app", config.toJson())
        }
        return jsonResponse(Response.Status.OK, json)
    }

    private fun handleMeshConnect(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            return jsonResponse(Response.Status.UNAUTHORIZED, JSONObject().apply {
                put("status", "error")
                put("error", "Unauthorized")
            })
        }

        val body = parseJsonBody(session)
        val ip = body.optString("ip", body.optString("seed", ""))
        if (ip.isBlank()) {
            return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().apply {
                put("status", "error")
                put("error", "Missing 'ip' or 'seed' parameter")
            })
        }

        val result = meshManager?.addPersistentConnection(ip, reciprocal = true)
        val normalized = result?.getOrNull() ?: ip
        meshManager?.syncConfigToMesh()

        val json = JSONObject().apply {
            put("status", "ok")
            put("message", "Connecting to persistent connection $normalized")
            put("seed", normalized)
        }
        return jsonResponse(Response.Status.OK, json)
    }

    private fun handleMeshHandshake(session: IHTTPSession): Response {
        val body = parseJsonBody(session)
        val senderIp = session.remoteIpAddress ?: "127.0.0.1"
        val responseJson = if (meshManager != null) {
            meshManager.handleIncomingHandshake(body, senderIp)
        } else {
            val remoteSenderIp = body.optString("sender_ip", senderIp)
            val remotePort = body.optInt("sender_port", 2325)
            val remoteSeed = "$remoteSenderIp:$remotePort"
            SettingsStore.addPersistentConnection(context, remoteSeed)
            JSONObject()
        }
        responseJson.put("status", "ok")
        return jsonResponse(Response.Status.OK, responseJson)
    }

    private fun handleMeshConfigGet(): Response {
        val json = JSONObject().apply {
            put("status", "ok")
            put("config", SettingsStore.exportConfigJson(context))
        }
        return jsonResponse(Response.Status.OK, json)
    }

    private fun handleMeshConfigSync(session: IHTTPSession): Response {
        val body = parseJsonBody(session)
        val configJson = body.optJSONObject("config") ?: body
        val result = SettingsStore.importConfigJson(context, configJson)
        if (result.applied) {
            Logger.i("Applied incoming mesh config v${result.newVersion} via sync API from ${session.remoteIpAddress} (portChanged=${result.portChanged}, pwdChanged=${result.passwordChanged}, seedsChanged=${result.seedsChanged})")
            meshManager?.triggerBeacon()
        }
        val json = JSONObject().apply {
            put("status", "ok")
            put("applied", result.applied)
            put("old_version", result.oldVersion)
            put("new_version", result.newVersion)
            put("port_changed", result.portChanged)
            put("web_server_toggled", result.webServerToggled)
            put("password_changed", result.passwordChanged)
            put("seeds_changed", result.seedsChanged)
            put("config_version", SettingsStore.getConfigVersion(context))
        }
        return jsonResponse(Response.Status.OK, json)
    }

    private fun handleMeshPersistentConnectionsGet(): Response {
        val connections = SettingsStore.getPersistentConnections(context)
        val arr = JSONArray()
        connections.forEach { arr.put(it) }
        val json = JSONObject().apply {
            put("status", "ok")
            // Dual-emit: new key + old key for backward compatibility (1 cycle)
            put("persistent_connections", arr)
            put("seeds", arr)  // Deprecated: use persistent_connections
        }
        return jsonResponse(Response.Status.OK, json)
    }

    private fun handleMeshPersistentConnectionsModify(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            return jsonResponse(Response.Status.UNAUTHORIZED, JSONObject().apply {
                put("status", "error")
                put("error", "Unauthorized")
            })
        }

        val body = parseJsonBody(session)
        val ip = body.optString("ip", body.optString("seed", body.optString("connection", session.parms["ip"] ?: session.parms["seed"] ?: session.parms["connection"] ?: "")))
        if (ip.isBlank()) {
            return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().apply {
                put("status", "error")
                put("error", "Missing persistent connection IP")
            })
        }

        // Determine operation: DELETE or /remove path = remove; POST = add
        val isRemove = session.method == NanoHTTPD.Method.DELETE || session.uri.endsWith("/remove")

        return if (isRemove) {
            val removed = meshManager?.removePersistentConnection(ip) ?: SettingsStore.removePersistentConnection(context, ip)
            val json = JSONObject().apply {
                put("status", "ok")
                put("removed", removed)
                put("message", "Removed persistent connection $ip")
            }
            jsonResponse(Response.Status.OK, json)
        } else {
            // POST = add
            val result = meshManager?.addPersistentConnection(ip, reciprocal = true)
            val normalized = result?.getOrNull() ?: ip
            meshManager?.syncConfigToMesh()
            val json = JSONObject().apply {
                put("status", "ok")
                put("message", "Added persistent connection $normalized")
                put("connection", normalized)
            }
            jsonResponse(Response.Status.OK, json)
        }
    }

    private fun handleMeshBeacon(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            return jsonResponse(Response.Status.UNAUTHORIZED, JSONObject().apply {
                put("status", "error")
                put("error", "Unauthorized")
            })
        }

        meshManager?.triggerBeacon()
        val json = JSONObject().apply {
            put("status", "ok")
            put("message", "Mesh UDP beacon broadcast dispatched")
        }
        return jsonResponse(Response.Status.OK, json)
    }

    private fun handleMeshCreate(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            return jsonResponse(Response.Status.UNAUTHORIZED, JSONObject().apply {
                put("status", "error")
                put("error", "Unauthorized")
            })
        }

        val body = parseJsonBody(session)
        val meshId = body.optString("meshId", "").trim().lowercase()
        val meshName = body.optString("meshName", "").trim()

        if (meshId.isBlank() || meshName.isBlank()) {
            return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().apply {
                put("status", "error")
                put("error", "meshId and meshName are required")
            })
        }

        if (!meshId.matches(Regex("^[a-z0-9-]+$"))) {
            return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().apply {
                put("status", "error")
                put("error", "meshId must contain only lowercase letters, numbers, and hyphens")
            })
        }

        // Add mesh to known templates (doesn't assign device)
        val added = SettingsStore.addKnownMesh(context, meshId, meshName)
        if (!added) {
            return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().apply {
                put("status", "error")
                put("error", "Mesh $meshId already exists")
            })
        }

        Logger.i("Mesh template created: $meshId ($meshName)")
        // Sync mesh templates to all devices
        meshManager?.syncConfigToMesh()

        val json = JSONObject().apply {
            put("status", "ok")
            put("message", "Mesh created: $meshId")
            put("meshId", meshId)
            put("meshName", meshName)
        }
        return jsonResponse(Response.Status.OK, json)
    }

    private fun handleMeshDelete(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            return jsonResponse(Response.Status.UNAUTHORIZED, JSONObject().apply {
                put("status", "error")
                put("error", "Unauthorized")
            })
        }

        val body = parseJsonBody(session)
        val meshId = body.optString("meshId", "").trim().lowercase()

        if (meshId.isBlank()) {
            return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().apply {
                put("status", "error")
                put("error", "meshId is required")
            })
        }

        if (meshId == "unmanaged") {
            return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().apply {
                put("status", "error")
                put("error", "Cannot delete the Unmanaged mesh")
            })
        }

        val peerCount = meshManager?.peersFlow?.value?.count { it.meshId == meshId } ?: 0
        if (peerCount > 0) {
            return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().apply {
                put("status", "error")
                put("error", "Cannot delete a mesh with $peerCount assigned device(s)")
            })
        }

        val removed = SettingsStore.removeKnownMesh(context, meshId)
        if (!removed) {
            return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().apply {
                put("status", "error")
                put("error", "Mesh $meshId does not exist")
            })
        }

        Logger.i("Mesh template deleted: $meshId")
        // Sync deletion (tombstone) to all devices
        meshManager?.syncConfigToMesh()

        val json = JSONObject().apply {
            put("status", "ok")
            put("message", "Mesh deleted: $meshId")
            put("meshId", meshId)
        }
        return jsonResponse(Response.Status.OK, json)
    }

    private fun handleMeshUpdate(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            return jsonResponse(Response.Status.UNAUTHORIZED, JSONObject().apply {
                put("status", "error")
                put("error", "Unauthorized")
            })
        }

        val body = parseJsonBody(session)
        val meshId = body.optString("meshId", "").trim()
        val meshName = body.optString("meshName", "").trim()

        if (meshId.isBlank() || meshName.isBlank()) {
            return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().apply {
                put("status", "error")
                put("error", "meshId and meshName are required")
            })
        }

        // Only allow updating if this is the local mesh
        if (meshId != SettingsStore.getLocalMeshId(context)) {
            return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().apply {
                put("status", "error")
                put("error", "Can only update the local mesh name")
            })
        }

        SettingsStore.setLocalMeshName(context, meshName)
        SettingsStore.updateConfigVersion(context)
        Logger.i("Mesh updated: $meshId name -> $meshName")

        // Sync to fleet
        meshManager?.syncConfigToMesh()

        val json = JSONObject().apply {
            put("status", "ok")
            put("message", "Mesh name updated")
            put("meshId", meshId)
            put("meshName", meshName)
        }
        return jsonResponse(Response.Status.OK, json)
    }

    private fun handlePeerUpdate(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            return jsonResponse(Response.Status.UNAUTHORIZED, JSONObject().apply {
                put("status", "error")
                put("error", "Unauthorized")
            })
        }

        val body = parseJsonBody(session)
        val ip = body.optString("ip", "")
        val port = body.optInt("port", 2325)
        val tag = body.optString("tag", "")
        val url = body.optString("url", "")
        val pkg = body.optString("package", body.optString("packageName", ""))
        val force = body.optBoolean("force", true)

        if (ip.isBlank()) {
            return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().apply {
                put("status", "error")
                put("error", "Missing peer IP address")
            })
        }

        scope.launch {
            try {
                val queryParams = mutableListOf("force=$force")
                if (tag.isNotBlank()) queryParams.add("tag=${URLEncoder.encode(tag, "UTF-8")}")
                if (url.isNotBlank()) queryParams.add("url=${URLEncoder.encode(url, "UTF-8")}")
                if (pkg.isNotBlank()) queryParams.add("package=${URLEncoder.encode(pkg, "UTF-8")}")
                val updateUrl = "http://$ip:$port/update?${queryParams.joinToString("&")}"

                val req = Request.Builder()
                    .url(updateUrl)
                    .post(ByteArray(0).toRequestBody(null, 0, 0))
                    .build()
                httpClient.newCall(req).execute().close()
                Logger.i("Dispatched remote peer update to $ip:$port (package=$pkg)")
            } catch (e: Exception) {
                Logger.e("Error dispatching peer update to $ip:$port", e)
            }
        }

        val json = JSONObject().apply {
            put("status", "accepted")
            put("message", "Dispatched update sequence to peer $ip:$port")
        }
        return jsonResponse(Response.Status.ACCEPTED, json)
    }

    private fun handlePeerAdbToggle(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            return jsonResponse(Response.Status.UNAUTHORIZED, JSONObject().apply {
                put("status", "error")
                put("error", "Unauthorized")
            })
        }

        val body = parseJsonBody(session)
        val ip = body.optString("ip", "")
        val port = body.optInt("port", 2325)

        if (ip.isBlank()) {
            return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().apply {
                put("status", "error")
                put("error", "Missing peer IP address")
            })
        }

        scope.launch {
            try {
                val req = Request.Builder()
                    .url("http://$ip:$port/adb/toggle")
                    .post(ByteArray(0).toRequestBody(null, 0, 0))
                    .build()
                httpClient.newCall(req).execute().close()
                Logger.i("Dispatched remote ADB toggle to $ip:$port")
            } catch (e: Exception) {
                Logger.e("Failed to toggle ADB on remote peer $ip", e)
            }
        }

        val json = JSONObject().apply {
            put("status", "accepted")
            put("message", "Dispatched ADB toggle to peer $ip:$port")
        }
        return jsonResponse(Response.Status.ACCEPTED, json)
    }

    private fun handleLogs(): Response {
        val logs = Logger.getRecentLogs()
        val json = JSONObject().apply {
            put("status", "ok")
            put("logs", JSONArray(logs))
        }
        return jsonResponse(Response.Status.OK, json)
    }

    private fun handleLogsClear(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            return jsonResponse(Response.Status.UNAUTHORIZED, JSONObject().apply {
                put("status", "error")
                put("error", "Unauthorized")
            })
        }

        Logger.clear()
        val json = JSONObject().apply {
            put("status", "ok")
            put("message", "Log buffer cleared")
        }
        return jsonResponse(Response.Status.OK, json)
    }

    private fun addCorsHeaders(response: Response) {
        response.addHeader("Connection", "close")
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS, PATCH")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Auth-Token")
    }


    private fun jsonResponse(
        status: Response.Status,
        obj: JSONObject,
        cookies: Map<String, String>? = null
    ): Response {
        val res = newFixedLengthResponse(status, "application/json; charset=utf-8", obj.toString(2))
        addCorsHeaders(res)
        cookies?.forEach { (k, v) ->
            if (v.isBlank()) {
                res.addHeader("Set-Cookie", "$k=; Path=/; Max-Age=0; SameSite=Lax")
            } else {
                res.addHeader("Set-Cookie", "$k=$v; Path=/; Max-Age=604800; SameSite=Lax")
            }
        }
        return res
    }
}

