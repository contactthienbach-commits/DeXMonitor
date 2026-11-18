package com.thienbach.dexmonitor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.max

/** Lightweight 60-sample CPU/RAM history chart; no charting dependency. */
class MiniHistoryView(context: Context) : View(context) {
    private val cpu = ArrayDeque<Float>()
    private val ram = ArrayDeque<Float>()
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x334F5B6B; strokeWidth = 1f }
    private val cpuPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF69B4FF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.2f }
    private val ramPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFC4A5FF.toInt(); style = Paint.Style.STROKE; strokeWidth = 2.2f }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFB8C2D0.toInt(); textSize = 10f }

    fun addSample(cpuPercent: Int?, ramPercent: Int?) {
        append(cpu, cpuPercent?.coerceIn(0, 100)?.toFloat())
        append(ram, ramPercent?.coerceIn(0, 100)?.toFloat())
        postInvalidateOnAnimation()
    }

    private fun append(list: ArrayDeque<Float>, value: Float?) {
        list.addLast(value ?: Float.NaN)
        while (list.size > 60) list.removeFirst()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = 8f
        val right = width.toFloat() - 8f
        val top = 18f * resources.displayMetrics.density
        val bottom = height.toFloat() - 8f
        if (right <= left || bottom <= top) return
        for (i in 0..4) {
            val y = top + (bottom - top) * i / 4f
            canvas.drawLine(left, y, right, y, gridPaint)
        }
        val d = resources.displayMetrics.density
        textPaint.textSize = 10f * d
        canvas.drawText("CPU", left, 12f * d, textPaint)
        canvas.drawText("RAM", left + 36f * d, 12f * d, textPaint)
        drawSeries(canvas, cpu, cpuPaint, left, top, right, bottom)
        drawSeries(canvas, ram, ramPaint, left, top, right, bottom)
    }

    private fun drawSeries(canvas: Canvas, values: ArrayDeque<Float>, paint: Paint, left: Float, top: Float, right: Float, bottom: Float) {
        if (values.size < 2) return
        val path = Path()
        val count = values.size
        val step = (right - left) / max(1, count - 1)
        var started = false
        values.forEachIndexed { index, value ->
            if (value.isNaN()) { started = false; return@forEachIndexed }
            val x = left + index * step
            val y = bottom - (value / 100f) * (bottom - top)
            if (!started) { path.moveTo(x, y); started = true } else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
    }
}
