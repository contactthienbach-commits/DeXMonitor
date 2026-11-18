package com.thienbach.dexmonitor

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Monitor as a normal app window. In Samsung DeX it opens as a resizable window,
 * and the DeX "pin" button on its title bar keeps it on top.
 */
class MonitorActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statsReader: StatsReader
    private lateinit var appUsageReader: AppUsageReader

    private lateinit var cpuValue: TextView
    private lateinit var ramValue: TextView
    private lateinit var tempValue: TextView
    private lateinit var batteryValue: TextView
    private lateinit var networkValue: TextView
    private lateinit var detailValue: TextView
    private lateinit var appUsageValue: TextView
    private lateinit var historyChart: MiniHistoryView
    private var lastUsageRefresh = 0L
    private var running = false

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            val s = try { statsReader.read() } catch (_: Exception) { null }
            if (s != null) {
                val approx = if (s.cpuEstimated) "~" else ""
                cpuValue.text = s.cpuPercent?.let { "$approx$it%" } ?: "N/A"
                ramValue.text = String.format(Locale.US, "%.1f / %.1f GB", s.ramUsedMb / 1024.0, s.ramTotalMb / 1024.0)
                tempValue.text = s.socTempC?.let { "${StatsReader.fmt(it)}°C" }
                    ?: s.batteryTempC?.let { "${StatsReader.fmt(it)}°C" } ?: "N/A"
                val ramPercent = if (s.ramTotalMb > 0) ((s.ramUsedMb * 100) / s.ramTotalMb).toInt() else null
                historyChart.addSample(s.cpuPercent, ramPercent)
                batteryValue.text = s.batteryPercent?.let { "$it%" } ?: "N/A"
                val down = s.downMbps?.let { String.format(Locale.US, "%.1f", it) } ?: "N/A"
                val up = s.upMbps?.let { String.format(Locale.US, "%.1f", it) } ?: "N/A"
                networkValue.text = "↓ $down Mbps     ↑ $up Mbps"
                val freq = s.cpuMhz?.let { "$it MHz" } ?: "N/A"
                val current = s.currentMa?.let { "$it mA" } ?: "N/A"
                val power = s.powerW?.let { "${StatsReader.fmt(it)} W" } ?: "N/A"
                val soc = s.socTempC?.let { "${StatsReader.fmt(it)}°C" } ?: "N/A"
                val batt = s.batteryTempC?.let { "${StatsReader.fmt(it)}°C" } ?: "N/A"
                val gpuFreq = s.gpuMhz?.let { "$it MHz" } ?: "N/A"
                val gpuLoad = s.gpuLoadPercent?.let { "$it%" } ?: "N/A"
                detailValue.text = "CPU cores  ${s.cpuCoreClocks}  (core 0: $freq)\n" +
                    "SoC temp  $soc   Battery temp  $batt   Thermal  ${s.thermal}\n" +
                    "Battery current  $current   Power  $power\n" +
                    "GPU  ${s.gpu}   Clock  $gpuFreq   Load  $gpuLoad" +
                    (if (s.cpuEstimated) "\nCPU % is estimated from clock speed (marked ~)." else "")
                val now = System.currentTimeMillis()
                if (now - lastUsageRefresh >= 15_000L) {
                    lastUsageRefresh = now
                    appUsageValue.text = if (appUsageReader.hasPermission()) {
                        "ỨNG DỤNG DÙNG NHIỀU NHẤT · 24 GIỜ\n" + AppUsageReader.format(appUsageReader.topRecentApps())
                    } else {
                        "ỨNG DỤNG · cần cấp Usage Access trong màn hình chính."
                    }
                }
            }
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "DeX Monitor"
        statsReader = StatsReader(this)
        appUsageReader = AppUsageReader(this)
        setContentView(buildUi())
    }

    override fun onStart() {
        super.onStart()
        running = true
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    override fun onStop() {
        running = false
        handler.removeCallbacks(ticker)
        super.onStop()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val metrics = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        cpuValue = addMetric(metrics, "CPU", Color.rgb(105, 180, 255))
        ramValue = addMetric(metrics, "RAM", Color.rgb(190, 160, 255))
        tempValue = addMetric(metrics, "TEMP", Color.rgb(255, 190, 90))
        batteryValue = addMetric(metrics, "BATTERY", Color.rgb(110, 220, 150))
        root.addView(metrics, LinearLayout.LayoutParams(-1, dp(46)))

        networkValue = TextView(this).apply {
            text = "↓ N/A Mbps     ↑ N/A Mbps"
            textSize = 12f
            setTextColor(Color.rgb(220, 225, 235))
            setPadding(0, dp(4), 0, dp(6))
        }
        root.addView(networkValue)

        val divider = View(this).apply { setBackgroundColor(Color.rgb(65, 72, 84)) }
        root.addView(divider, LinearLayout.LayoutParams(-1, dp(1)))

        detailValue = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(8), 0, 0)
        }
        root.addView(detailValue)

        historyChart = MiniHistoryView(this)
        root.addView(historyChart, LinearLayout.LayoutParams(-1, dp(110)))

        appUsageValue = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(8), 0, dp(2))
            text = "ỨNG DỤNG · đang đọc..."
        }
        root.addView(appUsageValue)

        return ScrollView(this).apply {
            setBackgroundColor(Color.rgb(14, 18, 26))
            addView(root)
        }
    }

    private fun addMetric(parent: LinearLayout, label: String, valueColor: Int): TextView {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val labelView = TextView(this).apply {
            text = label
            textSize = 11f
            setTextColor(Color.rgb(155, 165, 180))
        }
        val valueView = TextView(this).apply {
            text = "N/A"
            textSize = 20f
            setTextColor(valueColor)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            maxLines = 1
        }
        cell.addView(labelView)
        cell.addView(valueView)
        parent.addView(cell, LinearLayout.LayoutParams(0, -1, 1f))
        return valueView
    }
}
