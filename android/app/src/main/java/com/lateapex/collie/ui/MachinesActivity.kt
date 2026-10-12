package com.lateapex.collie.ui

import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.button.MaterialButton
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.network.MachineHistoryPoint
import com.lateapex.collie.network.MachineRow
import com.lateapex.collie.network.MACHINE_MINUTE_MS
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

/** Native lead-owned machine census and resource history. The original read actor stays in its VM. */
class MachinesActivity : AppCompatActivity() {
    private lateinit var viewModel: MachinesViewModel
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private var machinePageIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.prepareEdgeToEdgeContent()
        val app = application as CollieApplication
        viewModel = ViewModelProvider(this, MachinesViewModel.Factory(app, app.container.repository))[MachinesViewModel::class.java]
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.collie_background))
        }
        root.addView(header(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        status = TextView(this).apply {
            textSize = 14f
            setTextColor(color(R.color.collie_muted))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(status)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(24))
        }
        root.addView(ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        window.applySafeContentInsets(root)
        root.applyAppTypeface()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.startPolling()
    }

    override fun onStop() {
        viewModel.stopPolling()
        super.onStop()
    }

    private fun header(): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(60)
        setPadding(dp(8), 0, dp(16), 0)
        setBackgroundResource(R.drawable.bg_pane_header)
        addView(android.widget.ImageButton(this@MachinesActivity).apply {
            setImageResource(R.drawable.ic_collie_back)
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            contentDescription = getString(R.string.machines_back)
            setOnClickListener { returnToDashboard() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        addView(CollieHeadingTextView(this@MachinesActivity).apply {
            setText(R.string.machines_title)
            textSize = 18f
            setTextColor(color(R.color.collie_foreground))
            setPadding(dp(8), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(MaterialButton(this@MachinesActivity).apply {
            setText(R.string.machines_refresh)
            contentDescription = getString(R.string.machines_refresh)
            minHeight = dp(48)
            setOnClickListener { viewModel.refresh() }
        })
    }

    private fun render(state: MachinesScreenState) {
        status.text = when {
            state.loading && state.response == null -> getString(R.string.machines_loading)
            state.error != null -> getString(errorLabel(state.error))
            state.historyError != null -> getString(errorLabel(state.historyError))
            state.response == null -> getString(R.string.machines_empty)
            else -> getString(R.string.machines_updated, DateFormat.format("MMM d, HH:mm", state.response.ts))
        }
        content.removeAllViews()
        val response = state.response
        if (response == null) return
        if (response.machines.isEmpty()) {
            content.addView(bodyText(getString(R.string.machines_empty)))
            return
        }
        val page = pageMachineRows(response.machines, machinePageIndex)
        machinePageIndex = page.pageIndex
        content.addView(bodyText(getString(R.string.machines_page, page.pageIndex + 1, page.pageCount, response.machines.size)), cardParams())
        if (page.pageCount > 1) {
            val pager = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            if (page.pageIndex > 0) pager.addView(MaterialButton(this@MachinesActivity).apply {
                setText(R.string.machines_previous_page)
                minHeight = dp(48)
                setOnClickListener { machinePageIndex--; render(viewModel.state.value) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (page.pageIndex + 1 < page.pageCount) pager.addView(MaterialButton(this@MachinesActivity).apply {
                setText(R.string.machines_next_page)
                minHeight = dp(48)
                setOnClickListener { machinePageIndex++; render(viewModel.state.value) }
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            content.addView(pager, cardParams())
        }
        page.rows.forEach { (absoluteIndex, row) ->
            val selected = row.id == state.selectedMachineId
            content.addView(MaterialButton(this).apply {
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                minHeight = dp(64)
                text = "${row.name}\n${row.health} · ${row.resourceSummary()}"
                contentDescription = getString(R.string.machines_row_accessibility, row.name, row.health, row.resourceSummary())
                isSelected = selected
                setOnClickListener {
                    machinePageIndex = absoluteIndex / MACHINE_PAGE_SIZE
                    viewModel.selectMachine(row.id)
                }
            }, cardParams())
        }
        val selectedId = state.selectedMachineId ?: return
        val row = response.machines.firstOrNull { it.id == selectedId } ?: return
        content.addView(detailHeading(row), cardParams())
        content.addView(resourceFacts(row, response.ts), cardParams())
        content.addView(bodyText(getString(R.string.machines_spark_heading)), cardParams())
        val spark = row.spark
        if (spark == null) {
            content.addView(bodyText(getString(R.string.machines_spark_unavailable)), cardParams())
        } else {
            val points = machineSparkWindow(spark)
            val sampledMinutes = points.count { it.cpu != null || it.memory != null }
            val chart = ResourceChartView(this).apply {
                submit(points, getString(R.string.machines_chart_accessibility, sampledMinutes))
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
            content.addView(chart, cardParams())
            content.addView(bodyText(getString(R.string.machines_spark_window_summary, sampledMinutes)), cardParams())
            content.addView(bodyText(getString(R.string.machines_chart_legend)), cardParams())
        }
        content.addView(bodyText(getString(R.string.machines_history_heading)), cardParams())
        val history = state.history
        if (history == null) {
            content.addView(bodyText(getString(R.string.machines_history_loading)), cardParams())
        } else {
            val window = buildMachineHistoryWindow(history)
            val timeRange = getString(
                R.string.machines_history_time_range,
                DateFormat.format("MMM d, HH:mm", window.startAt),
                DateFormat.format("MMM d, HH:mm", window.endAt),
            )
            val plot = ResourceChartView(this).apply {
                submit(
                    window.points.map { point -> point?.let { ResourceChartPoint(it.cpuAvg, it.mem) } ?: ResourceChartPoint(null, null) },
                    getString(R.string.machines_history_chart_accessibility, timeRange, window.points.count { it != null }),
                )
                setPadding(dp(8), dp(8), dp(8), dp(8))
            }
            content.addView(plot, cardParams())
            content.addView(bodyText(getString(R.string.machines_history_legend, timeRange)), cardParams())
            historyTable(window)
        }
        content.addView(MaterialButton(this).apply {
            setText(R.string.machines_back_to_list)
            minHeight = dp(48)
            setOnClickListener { viewModel.showMachines() }
        }, cardParams())
    }

    private fun detailHeading(row: MachineRow) = bodyText(getString(R.string.machines_detail_title, row.name, row.id)).apply {
        textSize = 18f
        setTextColor(color(R.color.collie_foreground))
        contentDescription = getString(R.string.machines_detail_accessibility, row.name, row.id, row.health)
    }

    private fun resourceFacts(row: MachineRow, responseAt: Long): View {
        val sample = row.sample
        val lines = buildList {
            add(getString(R.string.machines_health, row.health))
            if (sample == null) {
                add(getString(R.string.machines_resources_unavailable))
            } else {
                add(getString(R.string.machines_cpu, percent(sample.cpu), sample.cores))
                add(if (sample.memTotal > 0) getString(R.string.machines_memory, bytes(sample.memUsed), bytes(sample.memTotal)) else getString(R.string.machines_memory_unavailable))
                add(sample.load1?.let { getString(R.string.machines_load, decimal(it)) } ?: getString(R.string.machines_load_unavailable))
                add(getString(R.string.machines_network,
                    sample.rxBps?.let(::rate) ?: getString(R.string.machines_not_reported),
                    sample.txBps?.let(::rate) ?: getString(R.string.machines_not_reported)))
                val disks = sample.disks.orEmpty()
                add(if (disks.isEmpty()) getString(R.string.machines_disks_unavailable) else disks.joinToString("\n") {
                    getString(R.string.machines_disk, it.mount, percent(if (it.total > 0) it.used.toDouble() / it.total else Double.NaN), bytes(it.used), bytes(it.total))
                })
                add(getString(R.string.machines_gpu_unavailable))
                val firing = row.firing.map { metricLabel(it) }
                add(if (firing.isEmpty()) getString(R.string.machines_no_alerts_firing) else getString(
                    R.string.machines_current_alerts, firing.joinToString(),
                ))
                listOfNotNull(
                    row.alerts.cpu?.let { getString(R.string.machines_alert_rule, getString(R.string.machines_metric_cpu), percent(it.above), it.forMin) },
                    row.alerts.mem?.let { getString(R.string.machines_alert_rule, getString(R.string.machines_metric_memory), percent(it.above), it.forMin) },
                    row.alerts.disk?.let { getString(R.string.machines_alert_rule, getString(R.string.machines_metric_disk), percent(it.above), it.forMin) },
                ).takeIf { it.isNotEmpty() }?.let { add(it.joinToString("\n")) }
                val sampledAt = row.sampledAt
                add(if (sampledAt == null) getString(R.string.machines_sample_time_unknown) else getString(
                    R.string.machines_sample_age, ageString((responseAt - sampledAt).coerceAtLeast(0)),
                ))
            }
        }
        return bodyText(lines.joinToString("\n"))
    }

    private fun historyTable(window: MachineHistoryWindow) {
        val rows = window.tableRows(
            describePoint = { point -> getString(
                R.string.machines_history_row,
                DateFormat.format("MMM d, HH:mm", point.t), percent(point.cpuAvg), percent(point.cpuMax), percent(point.mem),
                point.rxBps?.let(::rate) ?: getString(R.string.machines_not_reported),
                point.txBps?.let(::rate) ?: getString(R.string.machines_not_reported),
                point.disk?.let(::percent) ?: getString(R.string.machines_not_reported),
            ) },
            noSample = { at -> getString(R.string.machines_history_gap, DateFormat.format("MMM d, HH:mm", at)) },
        )
        content.addView(bodyText(rows.joinToString("\n")), cardParams())
    }

    private fun bodyText(text: String) = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(color(R.color.collie_foreground))
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setBackgroundResource(R.drawable.bg_pane_card)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private fun errorLabel(error: MachineScreenError) = when (error) {
        MachineScreenError.NOT_CONNECTED -> R.string.machines_not_connected
        MachineScreenError.NOT_LEAD -> R.string.machines_not_lead
        MachineScreenError.UNKNOWN_MACHINE -> R.string.machines_unknown_host
        MachineScreenError.PAIRING_REQUIRED -> R.string.machines_pairing_required
        MachineScreenError.GATEWAY_IDENTITY -> R.string.machines_gateway_identity
        MachineScreenError.DEVICE_AUTHORIZATION -> R.string.machines_device_authorization
        MachineScreenError.ACCESS_CHECK_FAILED -> R.string.machines_access_check_failed
        MachineScreenError.MALFORMED -> R.string.machines_malformed
        MachineScreenError.UNAVAILABLE -> R.string.machines_unavailable
    }

    private fun metricLabel(metric: String) = when (metric) {
        "cpu" -> getString(R.string.machines_metric_cpu)
        "mem" -> getString(R.string.machines_metric_memory)
        "disk" -> getString(R.string.machines_metric_disk)
        else -> getString(R.string.machines_not_reported)
    }

    private fun returnToDashboard() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    private fun cardParams() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
    private fun percent(value: Double) = if (value.isFinite()) NumberFormat.getPercentInstance(Locale.getDefault()).apply { maximumFractionDigits = 1 }.format(value) else getString(R.string.machines_not_reported)
    private fun decimal(value: Double) = if (value.isFinite()) String.format(Locale.getDefault(), "%.2f", value) else getString(R.string.machines_not_reported)
    private fun bytes(value: Long): String = android.text.format.Formatter.formatShortFileSize(this, value.coerceAtLeast(0))
    private fun rate(value: Double) = if (value.isFinite() && value >= 0) getString(R.string.machines_rate, NumberFormat.getNumberInstance(Locale.getDefault()).format(value)) else getString(R.string.machines_not_reported)
    private fun ageString(milliseconds: Long): String {
        val seconds = milliseconds / 1_000
        return when {
            seconds < 60 -> getString(R.string.machines_seconds, seconds)
            seconds < 3_600 -> getString(R.string.machines_minutes, seconds / 60)
            else -> getString(R.string.machines_hours, seconds / 3_600)
        }
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun color(id: Int) = androidx.core.content.ContextCompat.getColor(this, id)
}

internal const val MACHINE_PAGE_SIZE = 50
internal const val MAX_HISTORY_WINDOW_POINTS = 1440

internal data class MachinePage(val pageIndex: Int, val pageCount: Int, val rows: List<Pair<Int, MachineRow>>)

internal fun pageMachineRows(rows: List<MachineRow>, pageIndex: Int, pageSize: Int = MACHINE_PAGE_SIZE): MachinePage {
    require(pageSize > 0)
    val pageCount = ((rows.size + pageSize - 1) / pageSize).coerceAtLeast(1)
    val normalizedPage = pageIndex.coerceIn(0, pageCount - 1)
    val start = normalizedPage * pageSize
    return MachinePage(normalizedPage, pageCount, rows.drop(start).take(pageSize).mapIndexed { index, row -> start + index to row })
}

internal data class MachineHistoryWindow(val startAt: Long, val endAt: Long, val points: List<MachineHistoryPoint?>)

internal fun MachineHistoryWindow.tableRows(
    describePoint: (MachineHistoryPoint) -> String,
    noSample: (Long) -> String,
): List<String> = points.mapIndexed { index, point ->
    val at = startAt + index * MACHINE_MINUTE_MS
    if (point == null) noSample(at) else describePoint(point)
}

internal fun buildMachineHistoryWindow(history: com.lateapex.collie.network.MachineHistoryResponse): MachineHistoryWindow {
    val end = history.ts - history.ts % MACHINE_MINUTE_MS
    val slots = ((end / MACHINE_MINUTE_MS) + 1).coerceIn(1, MAX_HISTORY_WINDOW_POINTS.toLong()).toInt()
    val start = end - (slots - 1) * MACHINE_MINUTE_MS
    val byTime = history.points.associateBy(MachineHistoryPoint::t)
    val points = List(slots) { index -> byTime[start + index * MACHINE_MINUTE_MS] }
    return MachineHistoryWindow(start, end, points)
}
