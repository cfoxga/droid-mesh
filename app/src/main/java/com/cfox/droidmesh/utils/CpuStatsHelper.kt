package com.cfox.droidmesh.utils

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import java.io.File
import java.util.HashMap
import kotlin.math.roundToInt

object CpuStatsHelper {

    private val SOC_ZONE_HINTS = listOf(
        "soc", "tsens", "cluster", "big", "little", "mid", "prime", "gold",
        "silver", "mtkts", "ap_ntc"
    )

    private val NOT_CPU_ZONE = listOf(
        "trip", "limit", "batt", "pmic", "charg", "wifi", "wlan", "usb", "skin",
        "gpu", "cam", "flash", "modem", "mdpa", "nrpa", "dram", "ibat", "vbat"
    )

    private class IdleSnapshot(
        val atNanos: Long,
        val idleUs: Map<String, Long>,
        val entries: Map<String, Long>,
        val online: Map<String, Boolean>
    )

    private var lastIdle: IdleSnapshot? = null
    private var thermalBlocked = false

    @Volatile
    private var cachedTelemetry = CpuTelemetry(null, null)

    fun getDeviceName(context: Context): String {
        val globalName = try {
            Settings.Global.getString(context.contentResolver, "device_name")
        } catch (e: Exception) { null }
        if (!globalName.isNullOrBlank()) return globalName

        val bluetoothName = try {
            Settings.Secure.getString(context.contentResolver, "bluetooth_name")
        } catch (e: Exception) { null }
        if (!bluetoothName.isNullOrBlank()) return bluetoothName

        val model = Build.MODEL ?: "Portal"
        val manufacturer = Build.MANUFACTURER ?: ""
        if (manufacturer.isNotBlank() && model.startsWith(manufacturer, ignoreCase = true)) {
            return model
        }
        return "$manufacturer $model".trim()
    }

    data class CpuTelemetry(
        val usagePercent: Double?,
        val tempCelsius: Double?
    ) {
        val usageDisplay: String
            get() = usagePercent?.let { "${it.roundToInt()}% CPU" } ?: "--% CPU"

        val tempDisplay: String
            get() = tempCelsius?.let { "${it.roundToInt()}°C" } ?: "--°C"
    }

    fun readTelemetry(): CpuTelemetry {
        return try {
            val usage = cpuUsage()
            val temp = cpuTemp()
            val result = CpuTelemetry(usage, temp)
            cachedTelemetry = result
            result
        } catch (e: Exception) {
            cachedTelemetry
        }
    }

    @Synchronized
    private fun cpuUsage(): Double? {
        val now = idleSnapshot() ?: return frequencyLoad()
        val first = lastIdle
        lastIdle = now

        if (first == null) return frequencyLoad()

        val age = now.atNanos - first.atNanos
        if (age < 100_000_000L || age > 300_000_000_000L) {
            return frequencyLoad()
        }

        val wallUs = age / 1000.0
        if (wallUs <= 0) return frequencyLoad()

        var busySum = 0.0
        var n = 0
        for ((name, idleNow) in now.idleUs) {
            val idleBefore = first.idleUs[name] ?: continue
            val offlineAtEdge = first.online[name] == false || now.online[name] == false
            val frozen = idleNow == idleBefore && now.entries[name] == first.entries[name]
            val busy = when {
                offlineAtEdge -> 0.0
                frozen -> 1.0
                else -> (1.0 - (idleNow - idleBefore) / wallUs).coerceIn(0.0, 1.0)
            }
            busySum += busy
            n++
        }
        if (n == 0) return frequencyLoad()
        return (busySum / n * 100.0).coerceIn(0.0, 100.0)
    }

    private fun idleSnapshot(): IdleSnapshot? {
        val cores = File("/sys/devices/system/cpu")
            .listFiles { f -> f.name.matches(Regex("cpu[0-9]+")) } ?: return null
        val idle = HashMap<String, Long>()
        val entries = HashMap<String, Long>()
        val online = HashMap<String, Boolean>()
        for (core in cores) {
            val states = File(core, "cpuidle")
                .listFiles { f -> f.name.startsWith("state") } ?: continue
            var timeSum = 0L
            var usageSum = 0L
            var any = false
            for (state in states) {
                val t = readLong(File(state, "time")) ?: continue
                timeSum += t
                usageSum += readLong(File(state, "usage")) ?: 0L
                any = true
            }
            if (!any) continue
            idle[core.name] = timeSum
            entries[core.name] = usageSum
            online[core.name] = readLong(File(core, "online"))?.let { it != 0L } ?: true
        }
        if (idle.isEmpty()) return null
        return IdleSnapshot(SystemClock.elapsedRealtimeNanos(), idle, entries, online)
    }

    private fun frequencyLoad(): Double? {
        val cores = File("/sys/devices/system/cpu")
            .listFiles { f -> f.name.matches(Regex("cpu[0-9]+")) } ?: return null
        var sum = 0.0
        var n = 0
        for (core in cores) {
            val fq = File(core, "cpufreq")
            val cur = readLong(File(fq, "scaling_cur_freq")) ?: continue
            val min = readLong(File(fq, "cpuinfo_min_freq")) ?: continue
            val max = readLong(File(fq, "cpuinfo_max_freq")) ?: continue
            if (max <= min) continue
            sum += ((cur - min).toDouble() / (max - min)).coerceIn(0.0, 1.0)
            n++
        }
        return if (n == 0) null else (sum / n * 100.0).coerceIn(0.0, 100.0)
    }

    private fun cpuTemp(): Double? =
        hottest { it.contains("cpu") }
            ?: hottest { type -> SOC_ZONE_HINTS.any { type.contains(it) } }

    private fun hottest(wanted: (String) -> Boolean): Double? {
        if (thermalBlocked) return null
        val zones = try {
            File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }
        } catch (e: Exception) {
            thermalBlocked = true
            null
        } ?: run {
            thermalBlocked = true
            return null
        }
        var max: Double? = null
        for (zone in zones) {
            val type = readText(File(zone, "type"))?.lowercase() ?: continue
            if (NOT_ZONE_ALLOWED(type)) continue
            if (!wanted(type)) continue
            val raw = readLong(File(zone, "temp")) ?: continue
            val deg = if (raw > 1000L) raw / 1000.0 else raw.toDouble()
            if (deg !in 0.0..120.0) continue
            if (max == null || deg > max) max = deg
        }
        return max
    }

    private fun NOT_ZONE_ALLOWED(type: String): Boolean =
        NOT_CPU_ZONE.any { type.contains(it) }

    private fun readText(f: File): String? = try {
        if (!f.exists() || !f.canRead()) null else f.readText().trim()
    } catch (e: Exception) {
        null
    }

    private fun readLong(f: File): Long? = readText(f)?.toLongOrNull()
}

