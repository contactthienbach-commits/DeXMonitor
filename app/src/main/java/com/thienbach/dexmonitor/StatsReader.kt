package com.thienbach.dexmonitor

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

data class MonitorStats(
    val cpuPercent: Int?,
    val ramUsedMb: Long,
    val ramTotalMb: Long,
    val cpuMhz: Int?,
    val cpuCoreClocks: String,
    val batteryPercent: Int?,
    val batteryTempC: Float?,
    val socTempC: Float?,
    val voltageV: Float?,
    val currentMa: Int?,
    val downMbps: Float?,
    val upMbps: Float?,
    val thermal: String,
    val gpu: String,
    val gpuMhz: Int?,
    val gpuLoadPercent: Int?,
    val powerW: Float?,
    val cpuEstimated: Boolean = false
)

/** Best-effort, permission-safe Android system metrics. Unreadable sysfs nodes remain N/A. */
class StatsReader(private val context: Context) {
    private var previousCpu: Pair<Long, Long>? = null
    private var previousRx = TrafficStats.getTotalRxBytes()
    private var previousTx = TrafficStats.getTotalTxBytes()
    private var previousAt = System.nanoTime()

    fun read(): MonitorStats {
        val rawCpu = readCpuPercent()
        val cpu = rawCpu ?: estimateCpuFromFrequency()
        val memory = context.getSystemService(ActivityManager::class.java).let { am ->
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            val total = info.totalMem / 1024 / 1024
            val used = (info.totalMem - info.availMem) / 1024 / 1024
            used to total
        }
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else null
        val batteryTemp = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
            ?.takeIf { it >= 0 }?.div(10f)
        val voltage = battery?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
            ?.takeIf { it > 0 }?.div(1000f)
        val bm = context.getSystemService(BatteryManager::class.java)
        // BATTERY_PROPERTY_CURRENT_NOW is reported in microamps; convert to mA.
        val current = if (Build.VERSION.SDK_INT >= 21) {
            bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
                .takeIf { it != Int.MIN_VALUE }?.div(1000)
        } else null

        val now = System.nanoTime()
        val seconds = max(0.001, (now - previousAt) / 1_000_000_000.0)
        val rx = TrafficStats.getTotalRxBytes()
        val tx = TrafficStats.getTotalTxBytes()
        val down = if (rx >= previousRx && previousRx >= 0L) (((rx - previousRx) * 8.0 / seconds) / 1_000_000.0).toFloat() else null
        val up = if (tx >= previousTx && previousTx >= 0L) (((tx - previousTx) * 8.0 / seconds) / 1_000_000.0).toFloat() else null
        previousRx = rx
        previousTx = tx
        previousAt = now

        val clocks = readCpuCoreFrequencies()
        val gpu = readGpuMetrics()
        val watts = if (voltage != null && current != null) voltage * abs(current) / 1000f else null
        return MonitorStats(
            cpuPercent = cpu,
            ramUsedMb = memory.first,
            ramTotalMb = memory.second,
            cpuMhz = clocks.firstOrNull(),
            cpuCoreClocks = if (clocks.isEmpty()) "N/A" else clocks.mapIndexed { i, mhz -> "${i}:$mhz" }.joinToString("  ") + " MHz",
            batteryPercent = pct,
            batteryTempC = batteryTemp,
            socTempC = readSocTemperature(),
            voltageV = voltage,
            currentMa = current,
            downMbps = down,
            upMbps = up,
            thermal = readThermalStatus(),
            gpu = gpu.name,
            gpuMhz = gpu.mhz,
            gpuLoadPercent = gpu.load,
            powerW = watts,
            cpuEstimated = rawCpu == null && cpu != null
        )
    }

    private fun readCpuPercent(): Int? = try {
        val parts = File("/proc/stat").bufferedReader().use { it.readLine().trim().split(Regex("\\s+")) }
        val values = parts.drop(1).map { it.toLong() }
        val idle = values[3] + (values.getOrNull(4) ?: 0L)
        val total = values.sum()
        val old = previousCpu
        previousCpu = total to idle
        if (old == null) null else {
            val dt = total - old.first
            val di = idle - old.second
            if (dt <= 0) null else (((dt - di).toDouble() / dt) * 100).toInt().coerceIn(0, 100)
        }
    } catch (_: Exception) { null }


    /** Fallback when /proc/stat is blocked (Android 8+): average of current/max core frequency. Approximate. */
    private fun estimateCpuFromFrequency(): Int? {
        return try {
            val ratios = ArrayList<Double>()
            for (core in 0..15) {
                val base = "/sys/devices/system/cpu/cpu$core/cpufreq"
                val cur = readLongOrNull(File("$base/scaling_cur_freq")) ?: readLongOrNull(File("$base/cpuinfo_cur_freq"))
                val max = readLongOrNull(File("$base/cpuinfo_max_freq")) ?: readLongOrNull(File("$base/scaling_max_freq"))
                if (cur != null && max != null && max > 0L) ratios.add(cur.toDouble() / max.toDouble())
            }
            if (ratios.isEmpty()) null else (ratios.average() * 100.0).toInt().coerceIn(0, 100)
        } catch (_: Exception) { null }
    }

    private fun readLongOrNull(file: File): Long? =
        try { file.readText().trim().toLongOrNull() } catch (_: Exception) { null }

    /** Read one current frequency per CPU core; access may be restricted by the vendor kernel. */
    private fun readCpuCoreFrequencies(): List<Int> {
        val cpuDir = File("/sys/devices/system/cpu")
        val cores = cpuDir.listFiles()?.mapNotNull { dir ->
            val match = Regex("cpu(\\d+)").matchEntire(dir.name) ?: return@mapNotNull null
            match.groupValues[1].toIntOrNull()
        }?.sorted() ?: return emptyList()
        return cores.mapNotNull { core ->
            val candidates = listOf(
                File("/sys/devices/system/cpu/cpu$core/cpufreq/scaling_cur_freq"),
                File("/sys/devices/system/cpu/cpu$core/cpufreq/cpuinfo_cur_freq")
            )
            candidates.firstNotNullOfOrNull { file ->
                try { file.readText().trim().toLong().takeIf { it > 0 }?.div(1000)?.toInt() }
                catch (_: Exception) { null }
            }
        }
    }

    private data class GpuMetrics(val name: String, val mhz: Int?, val load: Int?)

    /** Qualcomm KGSL nodes are commonly readable on Snapdragon devices, but not guaranteed. */
    private fun readGpuMetrics(): GpuMetrics {
        val name = if (Build.HARDWARE.contains("qcom", ignoreCase = true) || Build.BOARD.contains("kona", ignoreCase = true)) "Adreno (Qualcomm)" else "GPU"
        val freqFiles = listOf(
            File("/sys/class/kgsl/kgsl-3d0/gpuclk"),
            File("/sys/class/devfreq/kgsl-3d0/cur_freq"),
            File("/sys/class/devfreq/kgsl-3d0/device/cur_freq")
        )
        val freq = freqFiles.firstNotNullOfOrNull { file ->
            try {
                val raw = file.readText().trim().toLong()
                if (raw <= 0) null else if (raw > 10_000_000L) (raw / 1_000_000L).toInt() else (raw / 1000L).toInt()
            } catch (_: Exception) { null }
        }
        val busy = try {
            val parts = File("/sys/class/kgsl/kgsl-3d0/gpubusy").readText().trim().split(Regex("\\s+"))
            val used = parts.getOrNull(0)?.toLongOrNull()
            val total = parts.getOrNull(1)?.toLongOrNull()
            if (used != null && total != null && total > 0) ((used * 100L) / total).toInt().coerceIn(0, 100) else null
        } catch (_: Exception) { null }
        return GpuMetrics(name, freq, busy)
    }

    /** Only labels a sensor as SoC when its kernel zone name clearly identifies CPU/AP/SoC. */
    private fun readSocTemperature(): Float? {
        val zones = File("/sys/class/thermal").listFiles()?.filter { it.name.startsWith("thermal_zone") } ?: return null
        for (zone in zones) {
            try {
                val type = File(zone, "type").readText().trim().lowercase(Locale.US)
                if (!(type.contains("cpu") || type.contains("soc") || type.startsWith("ap") || type.contains("tsens"))) continue
                val raw = File(zone, "temp").readText().trim().toLongOrNull() ?: continue
                val celsius = if (raw > 1000) raw / 1000f else raw.toFloat()
                if (celsius in 0f..120f) return celsius
            } catch (_: Exception) { }
        }
        return null
    }

    private fun readThermalStatus(): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val pm = context.getSystemService(PowerManager::class.java)
            return when (pm.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE -> "Normal"
                PowerManager.THERMAL_STATUS_LIGHT -> "Light"
                PowerManager.THERMAL_STATUS_MODERATE -> "Moderate"
                PowerManager.THERMAL_STATUS_SEVERE -> "Severe"
                PowerManager.THERMAL_STATUS_CRITICAL -> "Critical"
                PowerManager.THERMAL_STATUS_EMERGENCY -> "Emergency"
                else -> "N/A"
            }
        }
        return "N/A"
    }

    companion object {
        fun fmt(value: Float?): String = value?.let { String.format(Locale.US, "%.1f", it) } ?: "N/A"
    }
}
