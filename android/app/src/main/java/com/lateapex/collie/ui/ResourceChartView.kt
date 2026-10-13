package com.lateapex.collie.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.lateapex.collie.R

internal data class ResourceChartPoint(val cpu: Double?, val memory: Double?)

/** The producer trims leading empty minutes; its final slot is always the newest minute. */
internal fun machineSparkWindow(spark: com.lateapex.collie.network.MachineSpark): List<ResourceChartPoint> {
    val recent = spark.cpu.indices.toList().takeLast(30).map { index ->
        ResourceChartPoint(spark.cpu[index], spark.mem.getOrNull(index))
    }
    return List(30 - recent.size) { ResourceChartPoint(null, null) } + recent
}

/** Small bounded Canvas chart; the visible data table supplies an equivalent for assistive technology. */
class ResourceChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val cpuPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2.5f
        color = ContextCompat.getColor(context, R.color.collie_info)
    }
    private val memoryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density * 2.5f
        color = ContextCompat.getColor(context, R.color.collie_done)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
        color = ContextCompat.getColor(context, R.color.collie_rule)
    }
    private var samples: List<ResourceChartPoint> = emptyList()

    init {
        minimumHeight = (140 * resources.displayMetrics.density).toInt()
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    internal fun submit(points: List<ResourceChartPoint>, description: String) {
        samples = points.takeLast(MAX_CHART_POINTS)
        contentDescription = description
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val left = paddingLeft.toFloat() + resources.displayMetrics.density * 4
        val right = width - paddingRight.toFloat() - resources.displayMetrics.density * 4
        val top = paddingTop.toFloat() + resources.displayMetrics.density * 10
        val bottom = height - paddingBottom.toFloat() - resources.displayMetrics.density * 10
        if (right <= left || bottom <= top) return
        for (line in 0..2) {
            val y = top + (bottom - top) * line / 2f
            canvas.drawLine(left, y, right, y, gridPaint)
        }
        drawSeries(canvas, samples.map { it.cpu }, left, right, top, bottom, cpuPaint)
        drawSeries(canvas, samples.map { it.memory }, left, right, top, bottom, memoryPaint)
    }

    private fun drawSeries(canvas: Canvas, values: List<Double?>, left: Float, right: Float, top: Float, bottom: Float, paint: Paint) {
        if (values.size < 2) return
        val path = Path()
        var drawing = false
        values.forEachIndexed { index, value ->
            if (value == null || !value.isFinite() || value !in 0.0..1.0) {
                drawing = false
            } else {
                val x = left + (right - left) * index / (values.size - 1).toFloat()
                val y = bottom - (bottom - top) * value.toFloat()
                if (drawing) path.lineTo(x, y) else path.moveTo(x, y)
                drawing = true
            }
        }
        canvas.drawPath(path, paint)
    }

    companion object { private const val MAX_CHART_POINTS = 1440 }
}
