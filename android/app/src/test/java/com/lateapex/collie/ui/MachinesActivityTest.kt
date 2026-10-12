package com.lateapex.collie.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.network.MachineSpark
import com.lateapex.collie.network.MachinesResponse
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
class MachinesActivityTest {
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun producerTrimmedSparkStaysAtTheRightOfTheActualThirtyMinuteChart() {
        val app = ApplicationProvider.getApplicationContext<CollieApplication>()
        val activity = Robolectric.buildActivity(MachinesActivity::class.java).create().get()
        // This is the producer's short, oldest-first wire shape, including an internal gap.
        val wire = checkNotNull(javaClass.getResourceAsStream("/machines-short-spark.json"))
            .bufferedReader().use { it.readText() }
        val response = Json { ignoreUnknownKeys = true }.decodeFromString(MachinesResponse.serializer(), wire)
        val render = MachinesActivity::class.java.getDeclaredMethod("render", MachinesScreenState::class.java).apply { isAccessible = true }
        render.invoke(activity, MachinesScreenState(response = response, selectedMachineId = "lead"))
        val chart = descendants(activity.window.decorView).filterIsInstance<ResourceChartView>().single()
        assertEquals(app.getString(R.string.machines_chart_accessibility, 4), chart.contentDescription)
        assertTrue(findText(activity.window.decorView, app.getString(R.string.machines_spark_window_summary, 4)))
        val window = machineSparkWindow(response.machines.single().spark!!)
        assertEquals(30, window.size)
        assertTrue(window.take(25).all { it.cpu == null && it.memory == null })
        assertEquals(ResourceChartPoint(null, null), window[27])
        val bitmap = drawChart(chart)
        val cpuColor = ContextCompat.getColor(app, R.color.collie_info)
        val cpuColumns = (0 until bitmap.width).filter { x -> (0 until bitmap.height).any { y -> bitmap.getPixel(x, y) == cpuColor } }
        assertTrue("Short series must draw real CPU pixels", cpuColumns.isNotEmpty())
        assertTrue("Five recent slots must not stretch across thirty minutes", cpuColumns.first() > bitmap.width * .75)
        assertTrue("Newest samples reach the right edge of the plot", cpuColumns.last() > bitmap.width * .9)
        val left = chart.paddingLeft + app.resources.displayMetrics.density * 4
        val right = bitmap.width - chart.paddingRight - app.resources.displayMetrics.density * 4
        val gapX = (left + (right - left) * 27 / 29).toInt()
        assertTrue("An internal missing minute is not interpolated", (0 until bitmap.height).none { bitmap.getPixel(gapX, it) == cpuColor })

        val full = MachineSpark(60_000, List(30) { .2 }, List(30) { .4 })
        render.invoke(activity, MachinesScreenState(response = response.copy(machines = listOf(response.machines.single().copy(spark = full))), selectedMachineId = "lead"))
        val fullChart = descendants(activity.window.decorView).filterIsInstance<ResourceChartView>().single()
        assertEquals(app.getString(R.string.machines_chart_accessibility, 30), fullChart.contentDescription)
        val fullBitmap = drawChart(fullChart)
        assertTrue("A full series still covers the early window", (0 until fullBitmap.width / 4).any { x -> (0 until fullBitmap.height).any { y -> fullBitmap.getPixel(x, y) == cpuColor } })

        val empty = full.copy(cpu = List(5) { null }, mem = List(5) { null })
        render.invoke(activity, MachinesScreenState(response = response.copy(machines = listOf(response.machines.single().copy(spark = empty))), selectedMachineId = "lead"))
        assertTrue(findText(activity.window.decorView, app.getString(R.string.machines_spark_window_summary, 0)))
        render.invoke(activity, MachinesScreenState(response = response.copy(machines = listOf(response.machines.single().copy(spark = null))), selectedMachineId = "lead"))
        assertTrue(findText(activity.window.decorView, app.getString(R.string.machines_spark_unavailable)))
        assertTrue(descendants(activity.window.decorView).none { it is ResourceChartView })
    }

    private fun drawChart(chart: ResourceChartView): Bitmap {
        chart.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(180, View.MeasureSpec.EXACTLY))
        chart.layout(0, 0, 600, 180)
        return Bitmap.createBitmap(600, 180, Bitmap.Config.ARGB_8888).also { chart.draw(Canvas(it)) }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test
    fun dashboardAndPackExposeTheNativeMachinesScreen() {
        val app = ApplicationProvider.getApplicationContext<CollieApplication>()
        val dashboard = Robolectric.buildActivity(MainActivity::class.java).create().get()
        val dashboardButton = dashboard.findViewById<View>(R.id.dashboard_machines_button)
        assertNotNull(dashboardButton)
        dashboardButton.performClick()
        assertEquals(MachinesActivity::class.java.name, shadowOf(dashboard).nextStartedActivity.component?.className)

        val pack = Robolectric.buildActivity(PackActivity::class.java).create().get()
        val packButton = findClickableText(pack.window.decorView, app.getString(R.string.machines_title))
        assertNotNull(packButton)
        packButton!!.performClick()
        assertEquals(MachinesActivity::class.java.name, shadowOf(pack).nextStartedActivity.component?.className)

        val machines = Robolectric.buildActivity(MachinesActivity::class.java).create().get()
        assertTrue(findText(machines.window.decorView, app.getString(R.string.machines_title)))
    }

    @Test
    fun chartExposesAnAccessibleDescriptionAndDrawsMissingSamplesAsGaps() {
        val app = ApplicationProvider.getApplicationContext<CollieApplication>()
        val chart = ResourceChartView(app)
        chart.submit(listOf(ResourceChartPoint(.2, .4), ResourceChartPoint(null, null), ResourceChartPoint(.8, .6)), "CPU and memory chart; one missing minute")
        chart.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(150, View.MeasureSpec.EXACTLY))
        chart.layout(0, 0, 320, 150)
        val bitmap = Bitmap.createBitmap(320, 150, Bitmap.Config.ARGB_8888)
        chart.draw(Canvas(bitmap))
        assertEquals("CPU and memory chart; one missing minute", chart.contentDescription)
        assertTrue(chart.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_YES)
    }

    private fun findClickableText(view: View, expected: String): View? {
        if (view is TextView && view.text.toString() == expected && view.isClickable) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findClickableText(view.getChildAt(index), expected)?.let { return it }
        return null
    }

    private fun findText(view: View, expected: String): Boolean {
        if (view is TextView && view.text.toString() == expected) return true
        if (view is ViewGroup) for (index in 0 until view.childCount) if (findText(view.getChildAt(index), expected)) return true
        return false
    }
}
