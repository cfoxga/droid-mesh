package com.cfox.droidmesh.mesh

import com.cfox.droidmesh.installer.AppVersionHelper
import org.json.JSONArray
import org.json.JSONObject

data class PeerNode(
    val id: String,
    val ip: String,
    val port: Int = 2325,
    val deviceModel: String = "",
    /** User-configured name (from Settings.Global.device_name / bluetooth_name). Empty = not set. */
    val displayName: String = "",
    val targetInstalled: Boolean = false,
    val installedVersionName: String? = null,
    val installedVersionCode: Long? = null,
    val installedApps: List<AppVersionHelper.InstalledAppInfo> = emptyList(),
    val updaterState: String = "IDLE",
    val updaterMessage: String? = null,
    val adbEnabled: Boolean = false,
    val lastSeenTimestamp: Long = System.currentTimeMillis(),
    val isSelf: Boolean = false,
    val meshId: String = "meta-portals",
    val meshName: String = "Meta Portals",
    val isCrossVlan: Boolean = false,
    val configVersion: Long = 0L
) {
    /** Human-readable node label: prefers user-configured displayName over raw Build model string. */
    val effectiveName: String
        get() = if (displayName.isNotBlank()) displayName else deviceModel

    val isOnline: Boolean
        get() = isSelf || (System.currentTimeMillis() - lastSeenTimestamp) < 30_000L

    val lastSeenSecondsAgo: Long
        get() = (System.currentTimeMillis() - lastSeenTimestamp) / 1000L

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("ip", ip)
        put("port", port)
        put("deviceModel", deviceModel)
        put("displayName", displayName)
        put("meshId", meshId)
        put("meshName", meshName)
        put("isCrossVlan", isCrossVlan)
        put("configVersion", configVersion)
        put("targetInstalled", targetInstalled)
        put("installedVersionName", installedVersionName ?: JSONObject.NULL)
        put("installedVersionCode", installedVersionCode ?: JSONObject.NULL)

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

        put("updaterState", if (isOnline) updaterState else "OFFLINE")
        put("updaterMessage", updaterMessage ?: JSONObject.NULL)
        put("adbEnabled", adbEnabled)
        put("lastSeenTimestamp", lastSeenTimestamp)
        put("lastSeenSecondsAgo", lastSeenSecondsAgo)
        put("isOnline", isOnline)
        put("isSelf", isSelf)
    }

    companion object {
        fun fromBeaconJson(json: JSONObject, senderIp: String): PeerNode? {
            return try {
                val id = json.optString("id", senderIp)
                val ip = json.optString("ip", senderIp)
                val port = json.optInt("port", 2325)
                val deviceModel = json.optString("deviceModel", "Portal")
                val displayName = json.optString("displayName", "")
                val meshId = json.optString("meshId", json.optString("mesh_id", "meta-portals"))
                val meshName = json.optString("meshName", json.optString("mesh_name", "Meta Portals"))
                val isCrossVlan = json.optBoolean("isCrossVlan", json.optBoolean("is_cross_vlan", false))
                val targetInstalled = json.optBoolean("targetInstalled", false)
                val installedVersionName = if (json.isNull("installedVersionName")) null else json.optString("installedVersionName")
                val installedVersionCode = if (json.isNull("installedVersionCode")) null else json.optLong("installedVersionCode")

                val appsList = mutableListOf<AppVersionHelper.InstalledAppInfo>()
                val appsJsonArray = json.optJSONArray("installedApps") ?: json.optJSONArray("installed_apps")
                if (appsJsonArray != null) {
                    for (i in 0 until appsJsonArray.length()) {
                        val appObj = appsJsonArray.optJSONObject(i) ?: continue
                        val pkgName = appObj.optString("packageName", appObj.optString("package_name", ""))
                        if (pkgName.isNotBlank()) {
                            appsList.add(
                                AppVersionHelper.InstalledAppInfo(
                                    packageName = pkgName,
                                    appName = appObj.optString("appName", appObj.optString("app_name", pkgName)),
                                    versionName = if (appObj.isNull("versionName")) null else appObj.optString("versionName"),
                                    versionCode = if (appObj.isNull("versionCode")) null else appObj.optLong("versionCode")
                                )
                            )
                        }
                    }
                }
                if (appsList.isEmpty() && targetInstalled) {
                    appsList.add(
                        AppVersionHelper.InstalledAppInfo(
                            packageName = AppVersionHelper.TARGET_PACKAGE,
                            appName = "Kiosk Satellite",
                            versionName = installedVersionName,
                            versionCode = installedVersionCode
                        )
                    )
                }

                val updaterState = json.optString("updaterState", "IDLE")
                val updaterMessage = if (json.isNull("updaterMessage")) null else json.optString("updaterMessage")
                val adbEnabled = json.optBoolean("adbEnabled", false)
                val configVersion = json.optLong("config_version", json.optLong("configVersion", 0L))
                PeerNode(
                    id = id,
                    ip = ip,
                    port = port,
                    deviceModel = deviceModel,
                    displayName = displayName,
                    targetInstalled = targetInstalled,
                    installedVersionName = installedVersionName,
                    installedVersionCode = installedVersionCode,
                    installedApps = appsList,
                    updaterState = updaterState,
                    updaterMessage = updaterMessage,
                    adbEnabled = adbEnabled,
                    lastSeenTimestamp = System.currentTimeMillis(),
                    isSelf = false,
                    meshId = meshId,
                    meshName = meshName,
                    isCrossVlan = isCrossVlan,
                    configVersion = configVersion
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
