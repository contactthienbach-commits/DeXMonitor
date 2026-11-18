package com.thienbach.dexmonitor

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("monitor", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(28, 28, 28, 28)
        }
        root.addView(TextView(this).apply {
            text = "DeX Monitor 0.9.1\n\nThanh giám sát nổi dành cho Samsung DeX. Cấp quyền Overlay rồi bật thanh."
            textSize = 19f
            setPadding(0, 0, 0, 18)
        })
        root.addView(TextView(this).apply { text = "Khoảng cách phía trên Taskbar: ${prefs.getInt("bottom_offset", 58)} dp" })
        val offset = SeekBar(this).apply {
            max = 88
            progress = (prefs.getInt("bottom_offset", 58) - 32).coerceIn(0, 88)
        }
        root.addView(offset, LinearLayout.LayoutParams(-1, -2))
        offset.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                (root.getChildAt(1) as TextView).text = "Khoảng cách phía trên Taskbar: ${progress + 32} dp"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                prefs.edit().putInt("bottom_offset", (seekBar?.progress ?: 26) + 32).apply()
            }
        })
        root.addView(TextView(this).apply { text = "Độ đậm nền: ${prefs.getInt("opacity", 94)}%" })
        val opacity = SeekBar(this).apply {
            max = 45
            progress = (prefs.getInt("opacity", 94) - 55).coerceIn(0, 45)
        }
        root.addView(opacity, LinearLayout.LayoutParams(-1, -2))
        opacity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                (root.getChildAt(3) as TextView).text = "Độ đậm nền: ${progress + 55}%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                prefs.edit().putInt("opacity", (seekBar?.progress ?: 39) + 55).apply()
            }
        })
        root.addView(Button(this).apply {
            text = "1. Cấp quyền Overlay"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
            }
        })
        root.addView(Button(this).apply {
            text = "2. Cấp quyền Usage Access (thống kê ứng dụng)"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            }
        })
        root.addView(Button(this).apply {
            text = "3. Bật / áp dụng thanh DeX Monitor"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) {
                    startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                } else {
                    stopService(Intent(this@MainActivity, MonitorService::class.java))
                    val i = Intent(this@MainActivity, MonitorService::class.java)
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
                }
            }
        })
        root.addView(Button(this).apply {
            text = "4. Tắt thanh giám sát"
            setOnClickListener { stopService(Intent(this@MainActivity, MonitorService::class.java)) }
        })
        root.addView(TextView(this).apply {
            text = "Lưu ý: thanh là cửa sổ nổi riêng, không được nhúng vào Taskbar Samsung. Thống kê ứng dụng chỉ là thời gian dùng foreground trong 24 giờ, không phải danh sách tiến trình sống hoặc RAM từng app. Bảng chi tiết tự thu gọn sau 10 giây. Khi tất cả màn hình tắt, overlay được gỡ và vòng đọc số liệu tạm dừng; khi màn hình DeX/điện thoại bật lại, ứng dụng thử tạo lại overlay. Ở chế độ thu gọn, chu kỳ đọc giảm còn 2,5 giây."
            textSize = 12f
            setPadding(0, 18, 0, 0)
        })
        root.addView(Button(this).apply {
            text = "5. Đặt lại vị trí thanh"
            setOnClickListener {
                prefs.edit().remove("pos_x").remove("pos_y").apply()
                stopService(Intent(this@MainActivity, MonitorService::class.java))
                val i = Intent(this@MainActivity, MonitorService::class.java)
                if (Settings.canDrawOverlays(this@MainActivity)) startForegroundService(i)
            }
        })
        root.addView(Button(this).apply {
            text = "7. Mở cửa sổ giám sát (dùng trong DeX, nên chọn cách này)"
            setOnClickListener {
                startActivity(Intent(this@MainActivity, MonitorActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        })
        val diagTitle = TextView(this).apply {
            text = "Chẩn đoán hiển thị (chụp ảnh phần này gửi lại nếu thanh không hiện trên màn hình DeX):"
            textSize = 13f
            setPadding(0, 24, 0, 6)
        }
        val diagText = TextView(this).apply {
            textSize = 11f
            setTextIsSelectable(true)
            text = prefs.getString("diag", "Chưa có dữ liệu. Bấm nút 3, rồi cắm vào hub/DeX và bấm nút 6.")
        }
        root.addView(diagTitle)
        root.addView(diagText)
        root.addView(Button(this).apply {
            text = "6. Làm mới chẩn đoán"
            setOnClickListener {
                diagText.text = prefs.getString("diag", "Chưa có dữ liệu.")
            }
        })
        setContentView(ScrollView(this).apply { addView(root) })
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }
}
