package com.cfox.droidmesh.server

import android.content.Context
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
                        handleStatus()
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
                (uri == "/status" || uri == "/api/status" || uri == "/api/health") && method == Method.GET -> handleStatus()

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
                uri == "/api/mesh/seeds" && method == Method.GET -> handleMeshSeedsGet()
                (uri == "/api/mesh/seeds" || uri == "/api/mesh/seeds/remove") && (method == Method.DELETE || method == Method.POST) -> handleMeshSeedsRemove(session)
                uri == "/api/peers/update" && method == Method.POST -> handlePeerUpdate(session)
                uri == "/api/peers/update-all" && method == Method.POST -> handlePeerUpdateAll(session)
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

    private fun handleStatus(): Response {
        val installed = AppVersionHelper.getInstalledVersion(context)
        val installedApps = AppVersionHelper.getUserInstalledApps(context)
        val currentStatus = coordinator.statusFlow.value
        val telemetry = CpuStatsHelper.readTelemetry()

        val json = JSONObject().apply {
            put("status", "ok")
            put("app", "DroidMesh")
            put("targetPackage", AppVersionHelper.TARGET_PACKAGE)
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
            put("autoUpdateEnabled", SettingsStore.isAutoUpdateEnabled(context))
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
        SettingsStore.getCrossVlanSeeds(context).forEach { seedsJson.put(it) }

        val json = JSONObject().apply {
            put("status", "ok")
            put("autoUpdateEnabled", SettingsStore.isAutoUpdateEnabled(context))
            put("webServerEnabled", SettingsStore.isWebServerEnabled(context))
            put("webServerPort", SettingsStore.getWebServerPort(context))
            put("hasPassword", SettingsStore.isPasswordSet(context))
            put("adbEnabled", AdbHelper.isAdbEnabled(context))
            put("localMeshId", SettingsStore.getLocalMeshId(context))
            put("localMeshName", SettingsStore.getLocalMeshName(context))
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
        if (body.has("autoUpdateEnabled")) {
            val enabled = body.getBoolean("autoUpdateEnabled")
            SettingsStore.setAutoUpdateEnabled(context, enabled)
            Logger.i("Auto-update toggled via HTTP API: $enabled")
            settingsChanged = true
        }
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

        if (settingsChanged) {
            meshManager?.syncConfigToMesh()
        }

        return handleGetSettings()
    }

    private fun handleCheck(session: IHTTPSession): Response {
        val force = session.parms["force"]?.toBoolean() ?: false
        val releasesResult = runBlocking { coordinator.fetchAvailableReleases(forceRefresh = force) }
        val checkResult = runBlocking { coordinator.checkVersion() }

        if (checkResult.isSuccess) {
            val comp = checkResult.getOrThrow()
            val json = JSONObject().apply {
                put("status", "ok")
                put("targetPackage", AppVersionHelper.TARGET_PACKAGE)
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
        val filename = body.optString("filename", session.parms["filename"] ?: if (tag.isNotBlank()) "kiosk-satellite-$tag.apk" else "")

        if (url.isNotBlank() && tag.isNotBlank()) {
            val specificRelease = ReleaseInfo(
                tagName = tag,
                name = tag,
                publishedAt = "",
                apkAssetUrl = url,
                apkFileName = filename.ifBlank { "kiosk-satellite-$tag.apk" },
                apkSize = 0L
            )
            coordinator.startUpdateForRelease(specificRelease, force = force)
        } else {
            coordinator.startUpdateAsync(force = force)
        }

        val json = JSONObject().apply {
            put("status", "accepted")
            put("message", "Update sequence initiated (force=$force, target=${tag.ifBlank { "latest" }})")
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

        val result = meshManager?.addCrossVlanSeed(ip, reciprocal = true)
        val normalized = result?.getOrNull() ?: ip
        meshManager?.syncConfigToMesh()

        val json = JSONObject().apply {
            put("status", "ok")
            put("message", "Connecting to cross-VLAN mesh seed $normalized")
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
            SettingsStore.addCrossVlanSeed(context, remoteSeed)
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
            put("auto_update_toggled", result.autoUpdateToggled)
            put("password_changed", result.passwordChanged)
            put("seeds_changed", result.seedsChanged)
            put("config_version", SettingsStore.getConfigVersion(context))
        }
        return jsonResponse(Response.Status.OK, json)
    }

    private fun handleMeshSeedsGet(): Response {
        val seeds = SettingsStore.getCrossVlanSeeds(context)
        val arr = JSONArray()
        seeds.forEach { arr.put(it) }
        val json = JSONObject().apply {
            put("status", "ok")
            put("seeds", arr)
        }
        return jsonResponse(Response.Status.OK, json)
    }

    private fun handleMeshSeedsRemove(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            return jsonResponse(Response.Status.UNAUTHORIZED, JSONObject().apply {
                put("status", "error")
                put("error", "Unauthorized")
            })
        }

        val body = parseJsonBody(session)
        val ip = body.optString("ip", body.optString("seed", session.parms["ip"] ?: session.parms["seed"] ?: ""))
        if (ip.isBlank()) {
            return jsonResponse(Response.Status.BAD_REQUEST, JSONObject().apply {
                put("status", "error")
                put("error", "Missing seed IP to remove")
            })
        }

        val removed = meshManager?.removeCrossVlanSeed(ip) ?: SettingsStore.removeCrossVlanSeed(context, ip)
        val json = JSONObject().apply {
            put("status", "ok")
            put("removed", removed)
            put("message", "Removed seed $ip")
        }
        return jsonResponse(Response.Status.OK, json)
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

    private fun handlePeerUpdateAll(session: IHTTPSession): Response {
        if (!isAuthorized(session)) {
            return jsonResponse(Response.Status.UNAUTHORIZED, JSONObject().apply {
                put("status", "error")
                put("error", "Unauthorized")
            })
        }

        val body = parseJsonBody(session)
        val tag = body.optString("tag", "")
        val url = body.optString("url", "")

        // 1. Trigger local
        coordinator.startUpdateAsync(force = true)

        // 2. Trigger remotes
        val peers = meshManager?.peersFlow?.value ?: emptyList()
        val remotes = peers.filter { !it.isSelf && it.isOnline }

        scope.launch {
            for (peer in remotes) {
                try {
                    val queryParams = mutableListOf("force=true")
                    if (tag.isNotBlank()) queryParams.add("tag=${URLEncoder.encode(tag, "UTF-8")}")
                    if (url.isNotBlank()) queryParams.add("url=${URLEncoder.encode(url, "UTF-8")}")
                    val updateUrl = "http://${peer.ip}:${peer.port}/update?${queryParams.joinToString("&")}"

                    val req = Request.Builder()
                        .url(updateUrl)
                        .post(ByteArray(0).toRequestBody(null, 0, 0))
                        .build()
                    httpClient.newCall(req).execute().close()
                } catch (e: Exception) {
                    Logger.e("Failed to dispatch update to peer ${peer.ip}", e)
                }
            }
        }

        val json = JSONObject().apply {
            put("status", "accepted")
            put("message", "Dispatched update command to local unit and ${remotes.size} online remote peers")
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

