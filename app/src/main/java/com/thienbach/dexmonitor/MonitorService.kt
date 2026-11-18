package com.thienbach.dexmonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Presentation
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Draggable performance strip for Samsung DeX. Shown on the external (DeX) display when one is active. */
class MonitorService : Service() {
    private var wm: WindowManager? = null
    private lateinit var statsReader: StatsReader
    private lateinit var appUsageReader: AppUsageReader
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("monitor", MODE_PRIVATE) }

    private var panel: LinearLayout? = null
    private var detailRow: LinearLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var expanded = false
    private var collapseTask: Runnable? = null
    private var density = 1f

    private lateinit var cpuValue: TextView
    private lateinit var ramValue: TextView
    private lateinit var tempValue: TextView
    private lateinit var batteryValue: TextView
    private lateinit var networkValue: TextView
    private lateinit var detailValue: TextView
    private lateinit var historyChart: MiniHistoryView
    private lateinit var appUsageValue: TextView
    private var lastUsageRefresh = 0L
    private var overlayAttached = false
    private var overlayDisplayId = -1
    private var presentation: Presentation? = null
    private val diagLines = ArrayList<String>()
    private var displayListenerRegistered = false

    private val displayManager by lazy {
        getSystemService(DISPLAY_SERVICE) as DisplayManager
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = scheduleDisplayStateCheck()
        override fun onDisplayRemoved(displayId: Int) = scheduleDisplayStateCheck()
        override fun onDisplayChanged(displayId: Int) = scheduleDisplayStateCheck()
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            scheduleDisplayStateCheck()
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!overlayAttached) return
            val s = try { statsReader.read() } catch (_: Exception) { null }
            if (s == null) {
                handler.postDelayed(this, 1500L)
                return
            }
            val approx = if (s.cpuEstimated) "~" else ""
            cpuValue.text = s.cpuPercent?.let { "$approx$it%" } ?: "N/A"
            ramValue.text = String.format(Locale.US, "%.1f / %.1f GB", s.ramUsedMb / 1024.0, s.ramTotalMb / 1024.0)
            tempValue.text = s.socTempC?.let { "${StatsReader.fmt(it)}°C" }
                ?: s.batteryTempC?.let { "${StatsReader.fmt(it)}°C" } ?: "N/A"
            val ramPercent = if (s.ramTotalMb > 0) ((s.ramUsedMb * 100) / s.ramTotalMb).toInt() else null
            if (expanded) historyChart.addSample(s.cpuPercent, ramPercent)
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
                "GPU  ${s.gpu}   Clock  $gpuFreq   Load  $gpuLoad\n" +
                (if (s.cpuEstimated) "CPU % is estimated from clock speed (marked ~).\n" else "") +
                "Network figures are device-wide; GPU/SoC values depend on kernel access."
            val now = System.currentTimeMillis()
            if (expanded && now - lastUsageRefresh >= 15_000L) {
                lastUsageRefresh = now
                appUsageValue.text = if (appUsageReader.hasPermission()) {
                    "ỨNG DỤNG DÙNG NHIỀU NHẤT · 24 GIỜ\n" + AppUsageReader.format(appUsageReader.topRecentApps())
                } else {
                    "ỨNG DỤNG · cần cấp Usage Access trong màn hình chính."
                }
            }
            handler.postDelayed(this, if (expanded) 1000L else 2500L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        statsReader = StatsReader(this)
        appUsageReader = AppUsageReader(this)
        createNotificationChannel()
        startForeground(41, buildNotification())
        registerDisplayMonitoring()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        scheduleDisplayStateCheck()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun allDisplays(): List<Display> = try {
        displayManager.displays.toList()
    } catch (_: Exception) {
        emptyList()
    }

    /** Prefer an external (DeX) display; otherwise the phone screen. Displays that are OFF or private are ignored. */
    private fun pickTargetDisplay(): Display? {
        val usable = allDisplays().filter {
            it.state != Display.STATE_OFF && (it.flags and Display.FLAG_PRIVATE) == 0
        }
        val external = usable.filter { it.displayId != Display.DEFAULT_DISPLAY }
        val preferred = external.firstOrNull { (it.flags and Display.FLAG_PRESENTATION) != 0 } ?: external.firstOrNull()
        return preferred ?: usable.firstOrNull { it.displayId == Display.DEFAULT_DISPLAY }
    }

    private fun displaysReport(): String {
        val all = allDisplays().joinToString(" | ") {
            "id=${it.displayId} \"${it.name}\" state=${it.state} flags=0x${Integer.toHexString(it.flags)}"
        }
        val presentationIds = try {
            displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).joinToString(",") { it.displayId.toString() }
        } catch (_: Exception) {
            "?"
        }
        return "$all  [presentation ids: ${if (presentationIds.isEmpty()) "none" else presentationIds}]"
    }

    private fun saveDiag() {
        val text = diagLines.joinToString("\n")
        prefs.edit().putString("diag", text).apply()
        Log.i("DeXMonitor", text)
    }

    private fun createOverlay() {
        val target = pickTargetDisplay() ?: return
        diagLines.clear()
        diagLines.add("Giờ: " + SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + "   Android " + Build.VERSION.SDK_INT)
        diagLines.add("Các màn hình: " + displaysReport())
        diagLines.add("Màn hình được chọn: id=${target.displayId} \"${target.name}\"")
        val strategies = if (target.displayId != Display.DEFAULT_DISPLAY) {
            listOf("window", "presentation", "display", "default")
        } else {
            listOf("default")
        }
        var ok = false
        for (s in strategies) {
            try {
                attach(s, target)
                diagLines.add("THÀNH CÔNG: cách '$s'")
                ok = true
                break
            } catch (e: Exception) {
                diagLines.add("LỖI cách '$s': ${e.javaClass.simpleName}: ${e.message}")
                detachViews()
            }
        }
        overlayAttached = ok
        overlayDisplayId = if (ok) target.displayId else -1
        if (!ok) diagLines.add("Không cách nào gắn được thanh lên màn hình.")
        saveDiag()
    }

    private fun attach(strategy: String, target: Display) {
        when (strategy) {
            "presentation" -> attachPresentation(target)
            "window" -> attachWithContext(buildWindowContext(target))
            "display" -> attachWithContext(createDisplayContext(target))
            else -> attachWithContext(this)
        }
    }

    private fun buildWindowContext(display: Display): Context {
        val displayContext = createDisplayContext(display)
        return if (Build.VERSION.SDK_INT >= 30) {
            displayContext.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else {
            displayContext
        }
    }

    private fun initialX(): Int = prefs.getInt("pos_x", 0)

    private fun initialY(): Int = if (prefs.contains("pos_y")) {
        prefs.getInt("pos_y", 0)
    } else {
        dp(prefs.getInt("bottom_offset", 58).coerceIn(32, 120))
    }

    private fun attachWithContext(ctx: Context) {
        val windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        density = ctx.resources.displayMetrics.density
        val root = buildPanel(ctx)
        val screenWidth = ctx.resources.displayMetrics.widthPixels
        val lp = WindowManager.LayoutParams(
            min(dp(640), screenWidth - dp(32)),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        lp.x = initialX()
        lp.y = initialY()
        windowManager.addView(root, lp)
        wm = windowManager
        panel = root
        params = lp
    }

    /** Fallback: a Presentation window with overlay type, which can target a secondary display from a service. */
    private fun attachPresentation(target: Display) {
        val pres = Presentation(this, target)
        val w = pres.window ?: throw IllegalStateException("Presentation window is null")
        w.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        w.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        )
        val ctx = pres.context
        density = ctx.resources.displayMetrics.density
        val root = buildPanel(ctx)
        pres.setContentView(root)
        val lp = w.attributes
        lp.width = min(dp(640), ctx.resources.displayMetrics.widthPixels - dp(32))
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        lp.x = initialX()
        lp.y = initialY()
        w.attributes = lp
        presentation = pres
        panel = root
        params = lp
        pres.show()
    }

    private fun buildPanel(ctx: Context): LinearLayout {
        val opacity = prefs.getInt("opacity", 94).coerceIn(55, 100)
        val bg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(14).toFloat()
            setColor(Color.argb(opacity * 255 / 100, 18, 22, 30))
            setStroke(dp(1), Color.argb(110, 120, 135, 160))
        }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = bg
            elevation = dp(8).toFloat()
        }

        val metrics = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        cpuValue = addMetric(ctx, metrics, "CPU", "N/A", Color.rgb(105, 180, 255))
        ramValue = addMetric(ctx, metrics, "RAM", "N/A", Color.rgb(190, 160, 255))
        tempValue = addMetric(ctx, metrics, "TEMP", "N/A", Color.rgb(255, 190, 90))
        batteryValue = addMetric(ctx, metrics, "BATTERY", "N/A", Color.rgb(110, 220, 150))
        root.addView(metrics, LinearLayout.LayoutParams(-1, dp(40)))

        networkValue = TextView(ctx).apply {
            text = "↓ N/A Mbps     ↑ N/A Mbps"
            textSize = 11f
            setTextColor(Color.rgb(220, 225, 235))
            setPadding(0, dp(4), 0, 0)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        root.addView(networkValue, LinearLayout.LayoutParams(-1, -2))

        val details = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, dp(7), 0, 0)
        }
        val divider = View(ctx).apply { setBackgroundColor(Color.rgb(65, 72, 84)) }
        details.addView(divider, LinearLayout.LayoutParams(-1, dp(1)))
        detailValue = TextView(ctx).apply {
            textSize = 11f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(7), 0, 0)
        }
        details.addView(detailValue)
        historyChart = MiniHistoryView(ctx)
        details.addView(historyChart, LinearLayout.LayoutParams(-1, dp(92)))
        appUsageValue = TextView(ctx).apply {
            textSize = 11f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(8), 0, dp(2))
            text = "ỨNG DỤNG · cấp Usage Access trong màn hình chính để xem thống kê 24 giờ."
        }
        details.addView(appUsageValue)
        root.addView(details)
        detailRow = details

        root.setOnTouchListener { _, event -> handleDrag(event) }
        return root
    }

    private fun addMetric(ctx: Context, parent: LinearLayout, label: String, initial: String, valueColor: Int): TextView {
        val cell = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val labelView = TextView(ctx).apply {
            text = label
            textSize = 10f
            setTextColor(Color.rgb(155, 165, 180))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        val valueView = TextView(ctx).apply {
            text = initial
            textSize = 17f
            setTextColor(valueColor)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            maxLines = 1
        }
        cell.addView(labelView)
        cell.addView(valueView)
        parent.addView(cell, LinearLayout.LayoutParams(0, -1, 1f))
        return valueView
    }

    private fun detachViews() {
        try { presentation?.dismiss() } catch (_: Exception) { }
        try { if (presentation == null) panel?.let { wm?.removeView(it) } } catch (_: Exception) { }
        presentation = null
        panel = null
        detailRow = null
        params = null
        wm = null
    }

    private fun removeOverlay() {
        handler.removeCallbacks(ticker)
        collapseTask?.let { handler.removeCallbacks(it) }
        expanded = false
        detachViews()
        overlayAttached = false
        overlayDisplayId = -1
    }

    private fun applyParams() {
        val p = params ?: return
        try {
            val pres = presentation
            if (pres != null) {
                pres.window?.attributes = p
            } else {
                panel?.let { wm?.updateViewLayout(it, p) }
            }
        } catch (_: Exception) { }
    }

    private fun toggleExpanded() {
        expanded = !expanded
        detailRow?.visibility = if (expanded) View.VISIBLE else View.GONE
        if (expanded) scheduleCollapse() else collapseTask?.let { handler.removeCallbacks(it) }
    }

    private fun scheduleCollapse() {
        collapseTask?.let { handler.removeCallbacks(it) }
        val task = Runnable {
            expanded = false
            detailRow?.visibility = View.GONE
        }
        collapseTask = task
        handler.postDelayed(task, 10_000L)
    }

    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var dragged = false

    private fun handleDrag(event: MotionEvent): Boolean {
        val p = params ?: return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                startX = p.x
                startY = p.y
                dragged = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (abs(dx) > dp(5) || abs(dy) > dp(5)) dragged = true
                if (dragged) {
                    p.x = startX + dx.roundToInt()
                    p.y = startY - dy.roundToInt()
                    applyParams()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (dragged) {
                    prefs.edit().putInt("pos_x", p.x).putInt("pos_y", p.y).apply()
                } else {
                    toggleExpanded()
                }
                return true
            }
        }
        return false
    }

    private fun registerDisplayMonitoring() {
        try {
            displayManager.registerDisplayListener(displayListener, handler)
            displayListenerRegistered = true
        } catch (_: Exception) { }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(screenReceiver, filter)
            }
        } catch (_: Exception) { }
    }

    private fun scheduleDisplayStateCheck() {
        handler.removeCallbacks(displayStateCheck)
        handler.postDelayed(displayStateCheck, 500L)
    }

    private val displayStateCheck = Runnable {
        val target = pickTargetDisplay()
        if (target == null) {
            if (overlayAttached) removeOverlay()
        } else {
            if (overlayAttached && overlayDisplayId != target.displayId) removeOverlay()
            if (!overlayAttached && Settings.canDrawOverlays(this)) {
                createOverlay()
                if (overlayAttached) {
                    handler.removeCallbacks(ticker)
                    handler.post(ticker)
                }
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel("dex_monitor", "DeX Monitor", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, MonitorService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this, 42, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, "dex_monitor")
            .setContentTitle("DeX Monitor đang chạy")
            .setContentText("Chạm thanh để mở rộng · kéo để đổi vị trí")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .addAction(android.R.drawable.ic_media_pause, "Dừng", stopPending)
            .setOngoing(true)
            .build()
    }

    private fun dp(v: Int) = (v * density).roundToInt()

    override fun onDestroy() {
        handler.removeCallbacks(displayStateCheck)
        removeOverlay()
        if (displayListenerRegistered) {
            try { displayManager.unregisterDisplayListener(displayListener) } catch (_: Exception) { }
            displayListenerRegistered = false
        }
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) { }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.thienbach.dexmonitor.STOP"
    }
}
