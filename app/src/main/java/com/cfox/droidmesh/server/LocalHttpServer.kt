package com.cfox.droidmesh.server

import android.content.Context
import com.cfox.droidmesh.installer.AppVersionHelper
import com.cfox.droidmesh.mesh.MeshDiscoveryManager
import com.cfox.droidmesh.service.AutoInstallService
import com.cfox.droidmesh.settings.SettingsStore
import com.cfox.droidmesh.utils.Logger
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

class LocalHttpServer(
    private val context: Context,
    private val coordinator: UpdateCoordinator,
    private val meshManager: MeshDiscoveryManager? = null,
    port: Int = 2325
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        Logger.i("HTTP ${method.name} request received at $uri from ${session.remoteIpAddress}")

        return try {
            when {
                // GET / or GET /status
                (uri == "/" || uri == "/status") && method == Method.GET -> {
                    handleStatus()
                }

                // GET /mesh or GET /peers
                (uri == "/mesh" || uri == "/peers") && method == Method.GET -> {
                    handleMesh()
                }

                // POST /adb/toggle or GET /adb/toggle
                (uri == "/adb/toggle") -> {
                    val newTarget = com.cfox.droidmesh.utils.AdbHelper.toggleAdb(context)
                    val json = JSONObject().apply {
                        put("status", "ok")
                        put("adbEnabled", com.cfox.droidmesh.utils.AdbHelper.isAdbEnabled(context))
                    }
                    newFixedLengthResponse(Response.Status.OK, "application/json", json.toString(2))
                }

                // GET /check
                uri == "/check" && method == Method.GET -> {
                    handleCheck()
                }

                // POST /update or GET /update
                (uri == "/update") && (method == Method.POST || method == Method.GET) -> {
                    val params = session.parms
                    handleUpdate(params)
                }

                // GET /logs
                uri == "/logs" && method == Method.GET -> {
                    handleLogs()
                }

                else -> {
                    val json = JSONObject().apply {
                        put("status", "error")
                        put("message", "Endpoint not found: ${method.name} $uri")
                    }
                    newFixedLengthResponse(
                        Response.Status.NOT_FOUND,
                        "application/json",
                        json.toString(2)
                    )
                }
            }
        } catch (e: Exception) {
            Logger.e("Error processing HTTP request", e)
            val json = JSONObject().apply {
                put("status", "error")
                put("error", e.message ?: "Internal Server Error")
            }
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                json.toString(2)
            )
        }
    }

    private fun handleStatus(): Response {
        val installed = AppVersionHelper.getInstalledVersion(context)
        val currentStatus = coordinator.statusFlow.value

        val json = JSONObject().apply {
            put("status", "ok")
            put("app", "Kiosk Satellite Updater")
            put("targetPackage", AppVersionHelper.TARGET_PACKAGE)
            put("targetInstalled", installed.isInstalled)
            put("installedVersionName", installed.versionName ?: JSONObject.NULL)
            put("installedVersionCode", installed.versionCode ?: JSONObject.NULL)
            put("accessibilityServiceActive", AutoInstallService.isServiceRunning)
            put("autoUpdateEnabled", SettingsStore.isAutoUpdateEnabled(context))
            put("adbEnabled", com.cfox.droidmesh.utils.AdbHelper.isAdbEnabled(context))
            put("updaterState", currentStatus.state)
            put("updaterMessage", currentStatus.message)
            put("progressPercent", currentStatus.progressPercent)
        }

        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString(2))
    }

    private fun handleMesh(): Response {
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
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString(2))
    }

    private fun handleCheck(): Response {
        val result = runBlocking { coordinator.checkVersion() }

        return if (result.isSuccess) {
            val comp = result.getOrThrow()
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
            }
            newFixedLengthResponse(Response.Status.OK, "application/json", json.toString(2))
        } else {
            val err = result.exceptionOrNull()?.message ?: "Check failed"
            val json = JSONObject().apply {
                put("status", "error")
                put("message", err)
            }
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", json.toString(2))
        }
    }

    private fun handleUpdate(params: Map<String, String>): Response {
        val force = params["force"]?.toBoolean() ?: false
        val tag = params["tag"] ?: params["version"]
        val url = params["url"] ?: params["download_url"]
        val filename = params["filename"] ?: (if (tag != null) "kiosk-satellite-$tag.apk" else null)

        if (!url.isNullOrBlank() && !tag.isNullOrBlank()) {
            val specificRelease = com.cfox.droidmesh.api.ReleaseInfo(
                tagName = tag,
                name = tag,
                publishedAt = "",
                apkAssetUrl = url,
                apkFileName = filename ?: "kiosk-satellite-$tag.apk",
                apkSize = 0L
            )
            coordinator.startUpdateForRelease(specificRelease, force = force)
        } else {
            coordinator.startUpdateAsync(force = force)
        }

        val json = JSONObject().apply {
            put("status", "accepted")
            put("message", "Update sequence initiated (force=$force, target=${tag ?: "latest"})")
            put("accessibilityServiceActive", AutoInstallService.isServiceRunning)
        }

        return newFixedLengthResponse(Response.Status.ACCEPTED, "application/json", json.toString(2))
    }

    private fun handleLogs(): Response {
        val logs = Logger.getRecentLogs()
        val json = JSONObject().apply {
            put("status", "ok")
            put("logs", JSONArray(logs))
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString(2))
    }
}
