package com.cfox.kiosksatelliteupdater.mesh

import org.json.JSONObject

data class PeerNode(
    val id: String,
    val ip: String,
    val port: Int = 2325,
    val deviceModel: String = "",
    val targetInstalled: Boolean = false,
    val installedVersionName: String? = null,
    val installedVersionCode: Long? = null,
    val updaterState: String = "IDLE",
    val updaterMessage: String? = null,
    val adbEnabled: Boolean = false,
    val lastSeenTimestamp: Long = System.currentTimeMillis(),
    val isSelf: Boolean = false
) {
    val isOnline: Boolean
        get() = isSelf || (System.currentTimeMillis() - lastSeenTimestamp) < 30_000L

    val lastSeenSecondsAgo: Long
        get() = (System.currentTimeMillis() - lastSeenTimestamp) / 1000L

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("ip", ip)
        put("port", port)
        put("deviceModel", deviceModel)
        put("targetInstalled", targetInstalled)
        put("installedVersionName", installedVersionName ?: JSONObject.NULL)
        put("installedVersionCode", installedVersionCode ?: JSONObject.NULL)
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
                val targetInstalled = json.optBoolean("targetInstalled", false)
                val installedVersionName = if (json.isNull("installedVersionName")) null else json.optString("installedVersionName")
                val installedVersionCode = if (json.isNull("installedVersionCode")) null else json.optLong("installedVersionCode")
                val updaterState = json.optString("updaterState", "IDLE")
                val updaterMessage = if (json.isNull("updaterMessage")) null else json.optString("updaterMessage")
                val adbEnabled = json.optBoolean("adbEnabled", false)
                PeerNode(
                    id = id,
                    ip = ip,
                    port = port,
                    deviceModel = deviceModel,
                    targetInstalled = targetInstalled,
                    installedVersionName = installedVersionName,
                    installedVersionCode = installedVersionCode,
                    updaterState = updaterState,
                    updaterMessage = updaterMessage,
                    adbEnabled = adbEnabled,
                    lastSeenTimestamp = System.currentTimeMillis(),
                    isSelf = false
                )
            } catch (e: Exception) {
                null
            }
        }
    }
}
