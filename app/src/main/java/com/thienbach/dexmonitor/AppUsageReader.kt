package com.thienbach.dexmonitor

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AppUsageItem(val label: String, val minutes: Long)

class AppUsageReader(private val context: Context) {
    fun hasPermission(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        return try {
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName
            ) == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }

    /** Top apps by foreground time in the last 24 hours. This is not a live process list. */
    fun topRecentApps(limit: Int = 5): List<AppUsageItem> {
        if (!hasPermission()) return emptyList()
        val now = System.currentTimeMillis()
        val start = now - 24L * 60L * 60L * 1000L
        val usageManager = context.getSystemService(UsageStatsManager::class.java)
        val stats = try {
            usageManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, now)
        } catch (_: Exception) {
            emptyList()
        }
        return stats.orEmpty()
            .asSequence()
            .filter { it.packageName != context.packageName && it.totalTimeInForeground > 0L }
            .groupBy { it.packageName }
            .map { (packageName, entries) ->
                val totalMs = entries.sumOf { it.totalTimeInForeground }
                val label = try {
                    val appInfo = context.packageManager.getApplicationInfo(packageName, 0)
                    context.packageManager.getApplicationLabel(appInfo).toString()
                } catch (_: Exception) {
                    packageName.substringAfterLast('.')
                }
                AppUsageItem(label, totalMs / 60_000L)
            }
            .sortedByDescending { it.minutes }
            .take(limit)
            .toList()
    }

    companion object {
        fun format(items: List<AppUsageItem>): String {
            if (items.isEmpty()) return "Chưa có dữ liệu sử dụng trong 24 giờ gần nhất."
            return items.mapIndexed { index, item ->
                "${index + 1}. ${item.label} — ${item.minutes} phút"
            }.joinToString("\n")
        }
    }
}
