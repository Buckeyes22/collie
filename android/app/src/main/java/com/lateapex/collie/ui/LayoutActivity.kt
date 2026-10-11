package com.lateapex.collie.ui

import android.os.Bundle
import android.text.InputType
import android.view.accessibility.AccessibilityEvent
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.ApiFailure
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.FoldersResponse
import com.lateapex.collie.network.LayoutPane
import com.lateapex.collie.network.PaneSummary
import com.lateapex.collie.network.WorkspaceSummary
import com.lateapex.collie.network.validForPane
import com.lateapex.collie.data.ScopedReadContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import java.util.UUID

/** Native, one-action-at-a-time pane layout controls. Plans have encrypted pending-operation custody; credentials stay in the connection store. */
class LayoutActivity : AppCompatActivity() {
    private val repository get() = (application as CollieApplication).container.repository
    private lateinit var address: PaneAddress
    @Volatile private var actor: ScopedReadContext? = null
    private lateinit var content: LinearLayout
    @Volatile private var pendingId: String? = null
    @Volatile private var pendingPlan: String? = null
    @Volatile private var pendingAction: String? = null
    @Volatile private var pendingSourceTabId: String? = null
    @Volatile private var pendingSourceWorkspaceId: String? = null
    @Volatile private var busy = false
    @Volatile private var supported = emptySet<String>()
    @Volatile private var controlReady = false
    @Volatile private var availableTargets: List<PaneSummary> = emptyList()
    @Volatile private var availableWorkspaces: List<WorkspaceSummary> = emptyList()
    @Volatile private var advertisedFolders: List<String> = emptyList()
    @Volatile private var displayedLayout: com.lateapex.collie.network.LayoutSnapshot? = null
    private class DialogCustody(val dialog: AlertDialog, val copiedView: android.view.View?)
    @Volatile private var activeDialog: DialogCustody? = null
    private val custodyGeneration = java.util.concurrent.atomic.AtomicLong(0)
    private val custodyLock = Any()
    private class Retirement(val generation: Long, val dialog: DialogCustody?) {
        val acknowledgement = java.util.concurrent.CountDownLatch(1)
        val running = java.util.concurrent.atomic.AtomicBoolean(false)
        @Volatile var failure: Throwable? = null
    }
    private val pendingRetirements = java.util.concurrent.CopyOnWriteArrayList<Retirement>()
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    internal var retirementPoster: (Runnable) -> Boolean = { mainHandler.post(it) }
    /** Holds the real form immediately before its custody publication in deterministic race tests. */
    internal var beforeDialogPublication: (AlertDialog, android.view.View?) -> Unit = { _, _ -> }
    @Volatile private var disposalRequested = false
    private val disposalAcknowledgement = java.util.concurrent.CountDownLatch(1)
    private var authorityRegistration: AutoCloseable? = null
    @Volatile private var receiptReadinessActor: ScopedReadContext? = null
    @Volatile private var recoverySnapshotReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val paneId = intent.getStringExtra(EXTRA_PANE_ID)?.takeIf(String::isNotBlank)
        if (paneId == null) { finish(); return }
        address = PaneAddress(Scope(intent.getStringExtra(EXTRA_HOST), intent.getStringExtra(EXTRA_SESSION)), paneId)
        actor = repository.readContext(address.scope, paneId)
        authorityRegistration = repository.onAuthorityInvalidated { retireAuthorityAndAcknowledge() }
        loadPendingIntent()
        val scroll = ScrollView(this)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(24)) }
        scroll.addView(content)
        setContentView(scroll)
        title = getString(R.string.pane_layout_title)
        if (pendingId != null) {
            showUnknown(getString(R.string.pane_layout_recovered_unknown))
        } else loadLayout()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        drainRetirements()
    }

    override fun onDestroy() {
        val dialog = synchronized(custodyLock) {
            disposalRequested = true
            val old = activeDialog
            retireCopiedCustody()
            custodyGeneration.incrementAndGet()
            old
        }
        try {
            drainRetirements()
            dialog?.let(::wipeDialog)
            if (::content.isInitialized) { content.tag = null; content.removeAllViews() }
        } finally {
            authorityRegistration?.close()
            authorityRegistration = null
            disposalAcknowledgement.countDown()
            super.onDestroy()
        }
    }

    private fun retireAuthorityAndAcknowledge() {
        val retirement = synchronized(custodyLock) {
            if (disposalRequested) null else {
                val next = custodyGeneration.incrementAndGet()
                val dialog = activeDialog
                retireCopiedCustody()
                Retirement(next, dialog).also(pendingRetirements::add)
            }
        }
        if (retirement == null) {
            // A callback already in flight when registration closes joins the actual disposal.
            if (android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) awaitAcknowledgement(disposalAcknowledgement)
            return
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) completeRetirement(retirement)
        else {
            // No repository/admission/custody lock is held while posting or waiting. A rejected
            // post is NOT an ACK: keep the pending wipe, actor retired, and old callbacks inert.
            // Next main-thread start/explicit refresh or disposal must finish it before any new actor.
            if (!retirementPoster(Runnable { completeRetirement(retirement) }))
                throw IllegalStateException("Main-thread custody retirement post rejected")
            awaitAcknowledgement(retirement.acknowledgement)
        }
        retirement.failure?.let { throw IllegalStateException("Main-thread custody retirement failed", it) }
    }

    private fun completeRetirement(retirement: Retirement) {
        if (!pendingRetirements.contains(retirement) || !retirement.running.compareAndSet(false, true)) return
        try {
            retirement.dialog?.let(::wipeDialog)
            val stillRetired = synchronized(custodyLock) {
                if (custodyGeneration.get() != retirement.generation) false else { retireCopiedCustody(); true }
            }
            if (stillRetired && ::content.isInitialized && !disposalRequested) showRetired()
            retirement.failure = null
            pendingRetirements.remove(retirement)
        } catch (failure: Throwable) {
            // Signal a failed completion explicitly; keep the wipe for a main-thread retry and
            // never acquire a replacement actor while its Views are still pending retirement.
            retirement.failure = failure
        } finally {
            retirement.running.set(false)
            retirement.acknowledgement.countDown()
        }
    }

    private fun drainRetirements() { pendingRetirements.toList().forEach(::completeRetirement) }

    private fun awaitAcknowledgement(acknowledgement: java.util.concurrent.CountDownLatch) {
        var interrupted = false
        while (true) {
            try { acknowledgement.await(); break }
            catch (_: InterruptedException) { interrupted = true }
        }
        // Interruption does not drop an in-flight custody wipe; restore the caller's signal only
        // after main-thread completion/disposal so cancellation cannot leave a live old modal.
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun loadLayout() {
        val captured = actor ?: run { showRetired(); return }
        if (!repository.isCurrent(captured)) { showMessage(getString(R.string.pane_layout_stale)); return }
        val owner = com.lateapex.collie.data.PaneLayoutIntentOwner.from(captured, address.paneId)
        val unresolved = (application as CollieApplication).container.paneLayoutIntentStore.unresolved(owner)
        if (unresolved != null) {
            pendingId = unresolved.requestId; pendingPlan = unresolved.planToken; pendingAction = unresolved.actionJson
            if (!controlReady) loadReceiptReadiness(captured, force = true)
            showUnknown(getString(R.string.pane_layout_recovered_unknown)); return
        }
        content.removeAllViews()
        content.addView(text(getString(R.string.pane_layout_loading)))
        lifecycleScope.launch {
            val config = repository.config(address.scope, captured)
            if (!actorIsCurrent(captured)) return@launch
            supported = (config as? ApiResult.Success)?.value?.mux?.capabilities?.filterValues { it }?.keys.orEmpty()
            controlReady = (config as? ApiResult.Success)?.value?.control?.supportsNew("controlOperations") == true
            receiptReadinessActor = captured
            val topology = (repository.paneSnapshot(address.scope, captured) as? ApiResult.Success)?.value
            if (!actorIsCurrent(captured)) return@launch
            recoverySnapshotReady = topology != null
            availableTargets = topology?.let { it.agents + it.shellPanes }.orEmpty()
            availableWorkspaces = topology?.workspaces?.filter { it.host == address.scope.host }.orEmpty()
            if (!actorIsCurrent(captured)) return@launch
            val folders = repository.folders(address.scope, captured)
            if (!actorIsCurrent(captured)) return@launch
            advertisedFolders = (folders as? ApiResult.Success)?.value?.let { it.recent + it.favourites }?.distinct().orEmpty()
            val result = repository.paneLayout(address, captured)
            if (!actorIsCurrent(captured)) return@launch
            when (result) {
                is ApiResult.Success -> { render(result.value.result); inspectAdjacency() }
                is ApiResult.Failure -> showUnavailable(result.error)
                is ApiResult.NotModified -> showMessage(getString(R.string.pane_layout_unavailable))
            }
        }
    }

    private fun render(read: com.lateapex.collie.network.PaneLayoutRead, actionsEnabled: Boolean = true) {
        val panes = read.layout.panes
        displayedLayout = read.layout
        content.removeAllViews()
        content.tag = read.layout.workspaceId
        content.addView(text(getString(R.string.pane_layout_workspace_summary, read.layout.workspaceId, read.layout.tabId)))
        content.addView(text(getString(R.string.pane_layout_summary, panes.size, panes.firstOrNull()?.rect?.width ?: 0, panes.firstOrNull()?.rect?.height ?: 0)))
        panes.take(64).forEach { pane ->
            val focus = if (pane.focused) getString(R.string.pane_layout_focused) else ""
            content.addView(text(getString(R.string.pane_layout_pane_row, pane.paneId, pane.rect.x, pane.rect.y, pane.rect.width, pane.rect.height, focus)))
        }
        if (pendingId != null) { showUnknown(getString(R.string.pane_layout_recovered_unknown)); return }
        if (repository.connection.value == null || "paneLayout" !in supported) { showMessage(getString(R.string.pane_layout_unavailable)); return }
        if (!actionsEnabled) {
            content.addView(text(getString(R.string.pane_layout_changed_review)))
            content.addView(refreshButton())
            return
        }
        if (!controlReady) content.addView(text(getString(R.string.pane_layout_receipts_unavailable)))
        addAction("paneSplit", getString(R.string.pane_layout_split_right)) { configureSplit("right") }
        addAction("paneSplit", getString(R.string.pane_layout_split_down)) { configureSplit("down") }
        listOf("left", "right", "up", "down").forEach { dir ->
            addAction("paneResize", getString(R.string.pane_layout_resize, dir)) { action("resize", dir, 0.1) }
            addAction("paneResize", getString(R.string.pane_layout_resize_shrink, dir)) { action("resize", dir, -0.1) }
        }
        addAction("paneZoom", getString(R.string.pane_layout_zoom_toggle)) { action("zoom", "toggle") }
        addAction("paneZoom", getString(R.string.pane_layout_zoom_on)) { action("zoom", "on") }
        addAction("paneZoom", getString(R.string.pane_layout_zoom_off)) { action("zoom", "off") }
        listOf("left", "right", "up", "down").forEach { dir ->
            addAction("paneSwap", getString(R.string.pane_layout_swap, dir)) { action("swap", dir) }
            addAction("paneDirectionalFocus", getString(R.string.pane_layout_focus, dir)) { action("focus-direction", dir) }
        }
        addAction("paneMove", getString(R.string.pane_layout_move_new_tab)) { moveNewTab() }
        addAction("paneMove", getString(R.string.pane_layout_move_existing_tab)) { chooseMoveTarget() }
        addAction("paneMove", getString(R.string.pane_layout_move_new_workspace)) { moveNewWorkspace() }
        addAction("paneSwap", getString(R.string.pane_layout_swap_explicit)) { chooseExplicitSwapTarget() }
        content.addView(refreshButton())
    }

    private fun addAction(capability: String, label: String, action: () -> Unit) {
        if (capability !in supported || !controlReady) return
        val captured = actor ?: return
        content.addView(button(label) { if (actorIsCurrent(captured)) action() }.apply {
            isEnabled = actorIsCurrent(captured) && repository.writesAllowed(address.scope, captured.key.subjectIdentity)
        })
    }

    private fun inspectAdjacency() {
        val captured = actor ?: return
        lifecycleScope.launch {
            val edges = repository.paneLayoutRead(address, "edges", expectedContext = captured) as? ApiResult.Success ?: return@launch
            if (!actorIsCurrent(captured)) return@launch
            val value = edges.value.result
            content.addView(text(getString(R.string.pane_layout_edges, edgeLabel("left", value.left), edgeLabel("right", value.right), edgeLabel("up", value.up), edgeLabel("down", value.down))))
            for (direction in listOf("left", "right", "up", "down")) {
                val neighbor = repository.paneLayoutRead(address, "neighbor", direction, captured) as? ApiResult.Success ?: continue
                if (!actorIsCurrent(captured)) return@launch
                content.addView(text(getString(R.string.pane_layout_neighbor, direction, neighbor.value.result.neighborPaneId ?: getString(R.string.pane_layout_none))))
            }
        }
    }

    private fun edgeLabel(direction: String, present: Boolean?): String = if (present == true) direction else ""

    private fun action(kind: String, value: String, amount: Double = 0.1) {
        val json = when (kind) {
            "split" -> buildJsonObject { put("kind", kind); put("direction", value); put("ratio", null as Double?); put("workspaceId", null as String?); put("targetPaneId", null as String?); put("cwd", null as String?); put("env", kotlinx.serialization.json.buildJsonObject {}) }
            "resize" -> buildJsonObject { put("kind", kind); put("direction", value); put("amount", amount) }
            "zoom" -> buildJsonObject { put("kind", kind); put("mode", value) }
            "swap" -> buildJsonObject { put("kind", kind); put("direction", value); put("sourcePaneId", null as String?); put("targetPaneId", null as String?) }
            "focus-direction" -> buildJsonObject { put("kind", kind); put("direction", value) }
            else -> return
        }
        val effectLabel = when (kind) {
            "split" -> getString(if (value == "right") R.string.pane_layout_split_right else R.string.pane_layout_split_down)
            "resize" -> getString(R.string.pane_layout_resize_effect, value, if (amount > 0) getString(R.string.pane_layout_grow) else getString(R.string.pane_layout_shrink))
            "zoom" -> getString(when (value) { "on" -> R.string.pane_layout_zoom_on; "off" -> R.string.pane_layout_zoom_off; else -> R.string.pane_layout_zoom_toggle })
            "swap" -> getString(R.string.pane_layout_swap, value)
            else -> getString(R.string.pane_layout_focus, value)
        }
        prepare(json, kind, effectLabel)
    }

    private fun configureSplit(direction: String) {
        val captured = actor ?: return showMessage(getString(R.string.pane_layout_unavailable))
        if (!actorIsCurrent(captured)) return showMessage(getString(R.string.pane_layout_stale))
        val workspace = content.tag as? String ?: return showMessage(getString(R.string.pane_layout_unavailable))
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val ratio = EditText(this).apply { hint = getString(R.string.pane_layout_ratio_hint); setText("0.5"); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; contentDescription = getString(R.string.pane_layout_ratio) }
        val cwdChoices = listOf(getString(R.string.pane_layout_cwd_default), getString(R.string.pane_layout_cwd_source), getString(R.string.pane_layout_cwd_workspace)) + advertisedFolders
        val cwd = Spinner(this).apply { adapter = ArrayAdapter(this@LayoutActivity, android.R.layout.simple_spinner_dropdown_item, cwdChoices); contentDescription = getString(R.string.pane_layout_cwd) }
        val environmentRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val environmentFields = mutableListOf<Pair<EditText, EditText>>()
        val addEnvironment = button(getString(R.string.pane_layout_add_env)) {
            if (!actorIsCurrent(captured)) return@button
            if (environmentFields.size >= 32) { showMessage(getString(R.string.pane_layout_env_invalid)); return@button }
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val key = EditText(this).apply { hint = getString(R.string.pane_layout_env_key_hint); contentDescription = getString(R.string.pane_layout_env_key_hint) }
            val value = EditText(this).apply {
                hint = getString(R.string.pane_layout_env_value_hint)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
                minLines = 1
                contentDescription = getString(R.string.pane_layout_env_value_hint)
            }
            row.addView(key, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(value, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val remove = button(getString(R.string.pane_layout_remove_env)) {
                if (!actorIsCurrent(captured)) return@button
                environmentRows.removeView(row)
                environmentFields.remove(key to value)
            }
            remove.contentDescription = getString(R.string.pane_layout_remove_env)
            row.addView(remove, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            environmentRows.addView(row)
            environmentFields.add(key to value)
        }
        form.addView(text(getString(R.string.pane_layout_ratio))); form.addView(ratio)
        form.addView(text(getString(R.string.pane_layout_cwd))); form.addView(cwd)
        form.addView(text(getString(R.string.pane_layout_env))); form.addView(environmentRows); form.addView(addEnvironment)
        AlertDialog.Builder(this).setTitle(if (direction == "right") R.string.pane_layout_split_right else R.string.pane_layout_split_down)
            .setView(form).setNegativeButton(R.string.pane_action_cancel, null)
            .setPositiveButton(R.string.pane_layout_continue) { _, _ ->
                if (!actorIsCurrent(captured)) return@setPositiveButton
                val ratioValue = ratio.text.toString().trim().ifEmpty { null }?.toDoubleOrNull()
                if (ratio.text.isNotBlank() && (ratioValue == null || !ratioValue.isFinite() || ratioValue <= 0.0 || ratioValue >= 1.0)) return@setPositiveButton showMessage(getString(R.string.pane_layout_ratio_invalid))
                val envEntries = environmentFields.map { (key, value) -> key.text.toString() to value.text.toString() }
                    .filterNot { (key, value) -> key.isEmpty() && value.isEmpty() }
                if (envEntries.map { it.first }.distinct().size != envEntries.size) return@setPositiveButton showMessage(getString(R.string.pane_layout_env_invalid))
                val envObject = buildJsonObject { envEntries.forEach { (key, value) -> put(key, value) } }
                if (!validEnvironment(envObject)) return@setPositiveButton showMessage(getString(R.string.pane_layout_env_invalid))
                val selected = cwd.selectedItemPosition
                val cwdChoice = when (selected) {
                    0 -> kotlinx.serialization.json.JsonNull
                    1 -> buildJsonObject { put("kind", "source-pane") }
                    2 -> buildJsonObject { put("kind", "workspace"); put("workspaceId", workspace) }
                    else -> buildJsonObject { put("kind", "advertised-folder"); put("path", advertisedFolders.getOrNull(selected - 3) ?: return@setPositiveButton showMessage(getString(R.string.pane_layout_cwd_missing))) }
                }
                val json = buildJsonObject { put("kind", "split"); put("direction", direction); put("ratio", ratioValue); put("workspaceId", workspace); put("targetPaneId", address.paneId); put("cwd", cwdChoice); put("env", envObject) }
                val keys = envObject.keys.sorted().joinToString(", ")
                val directionLabel = getString(if (direction == "right") R.string.pane_layout_split_right else R.string.pane_layout_split_down)
                val ratioLabel = ratioValue?.toString() ?: getString(R.string.pane_layout_ratio_default)
                val cwdLabel = cwdChoices.getOrNull(selected) ?: getString(R.string.pane_layout_cwd_missing)
                val parameters = getString(R.string.pane_layout_split_parameters, ratioLabel, cwdLabel)
                val label = "$directionLabel · $parameters"
                prepare(json, "split", if (keys.isEmpty()) label else "$label · ${envObject.size} environment values ($keys: •••)")
            }.create().also { presentDialog(captured, it, form) }
    }

    private fun chooseExplicitSwapTarget() {
        val captured = actor ?: return showMessage(getString(R.string.pane_layout_unavailable))
        if (!actorIsCurrent(captured)) return showMessage(getString(R.string.pane_layout_stale))
        val layout = displayedLayout ?: return showMessage(getString(R.string.pane_layout_unavailable))
        val targets = availableTargets.filter { it.paneId != address.paneId && it.host == address.scope.host &&
            it.session == address.scope.session && it.tabId == layout.tabId && layout.panes.any { pane -> pane.paneId == it.paneId } }
        if (targets.isEmpty()) { showMessage(getString(R.string.pane_layout_no_move_targets)); return }
        // Own the actual choice Views even before window attachment; setItems would retain an
        // unshown private labels/selection callback array inside the dialog controller.
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var selectionDialog: AlertDialog? = null
        targets.forEach { target ->
            val label = "${target.workspaceLabel} · ${target.tabLabel ?: target.tabId} · ${target.paneId}"
            form.addView(button(label) {
                if (!actorIsCurrent(captured)) return@button
                selectionDialog?.dismiss()
                prepare(buildJsonObject { put("kind", "swap"); put("direction", null as String?); put("sourcePaneId", address.paneId); put("targetPaneId", target.paneId) }, "swap", label)
            })
        }
        val scroll = ScrollView(this).apply { addView(form) }
        val dialog = AlertDialog.Builder(this).setTitle(R.string.pane_layout_swap_explicit).setView(scroll)
            .setNegativeButton(R.string.pane_action_cancel, null).create()
        selectionDialog = dialog
        presentDialog(captured, dialog, scroll)
    }

    private fun moveNewTab() {
        val captured = actor ?: return showMessage(getString(R.string.pane_layout_unavailable))
        if (!actorIsCurrent(captured)) return showMessage(getString(R.string.pane_layout_stale))
        if (availableWorkspaces.isEmpty()) { showMessage(getString(R.string.pane_layout_no_move_targets)); return }
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val workspace = Spinner(this).apply {
            adapter = ArrayAdapter(this@LayoutActivity, android.R.layout.simple_spinner_dropdown_item,
                availableWorkspaces.map { "${it.number}. ${it.label}" })
            contentDescription = getString(R.string.pane_layout_destination_workspace)
        }
        val label = EditText(this).apply { hint = getString(R.string.pane_layout_tab_label_hint); setText(getString(R.string.pane_layout_default_tab_label)); contentDescription = getString(R.string.pane_layout_tab_label) }
        form.addView(text(getString(R.string.pane_layout_destination_workspace))); form.addView(workspace)
        form.addView(text(getString(R.string.pane_layout_tab_label))); form.addView(label)
        AlertDialog.Builder(this).setTitle(R.string.pane_layout_move_new_tab).setView(form)
            .setNegativeButton(R.string.pane_action_cancel, null)
            .setPositiveButton(R.string.pane_layout_continue) { _, _ ->
                if (!actorIsCurrent(captured)) return@setPositiveButton
                val selected = availableWorkspaces.getOrNull(workspace.selectedItemPosition) ?: return@setPositiveButton showMessage(getString(R.string.pane_layout_no_move_targets))
                val requestedLabel = label.text.toString().trim().takeIf { it.length <= 128 && it.none { ch -> ch.code < 32 } }
                    ?: return@setPositiveButton showMessage(getString(R.string.pane_layout_label_invalid))
                prepare(buildJsonObject { put("kind", "move"); put("destination", buildJsonObject { put("type", "new_tab"); put("workspaceId", selected.workspaceId); put("label", requestedLabel) }) }, "move", "${selected.label} · $requestedLabel")
            }.create().also { presentDialog(captured, it, form) }
    }

    private fun chooseMoveTarget() {
        val captured = actor ?: return showMessage(getString(R.string.pane_layout_unavailable))
        if (!actorIsCurrent(captured)) return showMessage(getString(R.string.pane_layout_stale))
        val targets = availableTargets.filter { it.paneId != address.paneId && it.host == address.scope.host && it.session == address.scope.session }
        if (targets.isEmpty()) { showMessage(getString(R.string.pane_layout_no_move_targets)); return }
        val labels = targets.map { "${it.workspaceLabel} · ${it.tabLabel ?: it.tabId} · ${it.paneId}" }.toTypedArray()
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val target = Spinner(this).apply { adapter = ArrayAdapter(this@LayoutActivity, android.R.layout.simple_spinner_dropdown_item, labels); contentDescription = getString(R.string.pane_layout_move_existing_tab) }
        val direction = Spinner(this).apply { adapter = ArrayAdapter(this@LayoutActivity, android.R.layout.simple_spinner_dropdown_item, listOf("right", "down")); contentDescription = getString(R.string.pane_layout_split_direction) }
        val ratio = EditText(this).apply { hint = getString(R.string.pane_layout_ratio_hint); setText("0.5"); inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; contentDescription = getString(R.string.pane_layout_ratio) }
        form.addView(text(getString(R.string.pane_layout_move_existing_tab))); form.addView(target)
        form.addView(text(getString(R.string.pane_layout_split_direction))); form.addView(direction)
        form.addView(text(getString(R.string.pane_layout_ratio))); form.addView(ratio)
        AlertDialog.Builder(this).setTitle(R.string.pane_layout_move_existing_tab).setView(form)
            .setNegativeButton(R.string.pane_action_cancel, null)
            .setPositiveButton(R.string.pane_layout_continue) { _, _ ->
            if (!actorIsCurrent(captured)) return@setPositiveButton
            val index = target.selectedItemPosition
            val target = targets[index]
            val tabId = target.tabId ?: return@setPositiveButton showMessage(getString(R.string.pane_layout_no_move_targets))
            val splitRatio = ratio.text.toString().trim().toDoubleOrNull()
            if (splitRatio == null || !splitRatio.isFinite() || splitRatio <= 0.0 || splitRatio >= 1.0) return@setPositiveButton showMessage(getString(R.string.pane_layout_ratio_invalid))
            prepare(buildJsonObject {
                put("kind", "move")
                put("destination", buildJsonObject { put("type", "tab"); put("tabId", tabId); put("split", direction.selectedItem.toString()); put("targetPaneId", target.paneId); put("ratio", splitRatio) })
            }, "move", getString(R.string.pane_layout_move_target_parameters, labels[index], direction.selectedItem.toString(), splitRatio))
        }.create().also { presentDialog(captured, it, form) }
    }

    private fun moveNewWorkspace() {
        val captured = actor ?: return showMessage(getString(R.string.pane_layout_unavailable))
        if (!actorIsCurrent(captured)) return showMessage(getString(R.string.pane_layout_stale))
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val workspaceLabel = EditText(this).apply { hint = getString(R.string.pane_layout_workspace_label_hint); setText(getString(R.string.pane_layout_default_workspace_label)); contentDescription = getString(R.string.pane_layout_workspace_label) }
        val tabLabel = EditText(this).apply { hint = getString(R.string.pane_layout_tab_label_hint); setText(getString(R.string.pane_layout_default_tab_label)); contentDescription = getString(R.string.pane_layout_tab_label) }
        form.addView(text(getString(R.string.pane_layout_workspace_label))); form.addView(workspaceLabel)
        form.addView(text(getString(R.string.pane_layout_tab_label))); form.addView(tabLabel)
        AlertDialog.Builder(this).setTitle(R.string.pane_layout_move_new_workspace).setView(form)
            .setNegativeButton(R.string.pane_action_cancel, null)
            .setPositiveButton(R.string.pane_layout_continue) { _, _ ->
                if (!actorIsCurrent(captured)) return@setPositiveButton
                val workspace = workspaceLabel.text.toString().trim().takeIf { it.length <= 128 && it.none { ch -> ch.code < 32 } }
                    ?: return@setPositiveButton showMessage(getString(R.string.pane_layout_label_invalid))
                val tab = tabLabel.text.toString().trim().takeIf { it.length <= 128 && it.none { ch -> ch.code < 32 } }
                    ?: return@setPositiveButton showMessage(getString(R.string.pane_layout_label_invalid))
                prepare(buildJsonObject { put("kind", "move"); put("destination", buildJsonObject { put("type", "new_workspace"); put("label", workspace); put("tabLabel", tab) }) }, "move", "$workspace · $tab")
            }.create().also { presentDialog(captured, it, form) }
    }

    private fun validEnvironment(values: kotlinx.serialization.json.JsonObject): Boolean {
        if (values.size > 32) return false
        val reserved = setOf("__proto__", "prototype", "constructor", "HERDR_SOCKET_PATH", "HERDR_ENV", "HERDR_WORKSPACE_ID", "HERDR_TAB_ID", "HERDR_PANE_ID")
        if (values.any { (key, child) ->
                !key.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}")) || key in reserved || child !is kotlinx.serialization.json.JsonPrimitive || !child.isString ||
                    child.content.contains('\u0000') || child.content.toByteArray(Charsets.UTF_8).size > 1024
            }) return false
        return kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), values).toByteArray(Charsets.UTF_8).size <= 8192
    }

    private fun prepare(action: kotlinx.serialization.json.JsonObject, kind: String, label: String) {
        if (busy) return
        val captured = actor ?: return showMessage(getString(R.string.pane_layout_unavailable))
        if (!repository.isCurrent(captured)) return showMessage(getString(R.string.pane_layout_stale))
        val owner = com.lateapex.collie.data.PaneLayoutIntentOwner.from(captured, address.paneId)
        val unresolved = (application as CollieApplication).container.paneLayoutIntentStore.unresolved(owner)
        if (unresolved != null) {
            pendingId = unresolved.requestId; pendingPlan = unresolved.planToken; pendingAction = unresolved.actionJson
            showUnknown(getString(R.string.pane_layout_recovered_unknown)); return
        }
        busy = true
        lifecycleScope.launch {
            when (val result = repository.planPaneLayout(address, action, captured)) {
                is ApiResult.Success -> {
                    if (!actorIsCurrent(captured)) return@launch
                    val plan = result.value
                    if (!plan.validForPane(address.paneId, action)) {
                        busy = false
                        showMessage(getString(R.string.pane_layout_unavailable))
                        return@launch
                    }
                    if (displayedLayout != plan.layout.layout) {
                        busy = false
                        render(plan.layout, actionsEnabled = false)
                        return@launch
                    }
                    val requestId = UUID.randomUUID().toString()
                    val effect = when (kind) {
                        "move" -> getString(R.string.pane_layout_confirm_move, label)
                        "split" -> getString(R.string.pane_layout_confirm_split, label)
                        "focus-direction" -> getString(R.string.pane_layout_confirm_focus, label)
                        else -> getString(R.string.pane_layout_confirm_action, label)
                    }
                    val planEffects = listOfNotNull(
                        plan.effects["verb"]?.toString()?.trim('"'),
                        plan.effects["targetPaneId"]?.toString()?.trim('"'),
                        plan.effects["sourcePaneId"]?.toString()?.trim('"'),
                        plan.effects["targetPaneId"]?.toString()?.trim('"'),
                        plan.effects["direction"]?.toString()?.trim('"'),
                        plan.effects["ratio"]?.toString()?.trim('"'),
                        plan.effects["amount"]?.toString()?.trim('"'),
                        plan.effects["mode"]?.toString()?.trim('"'),
                        plan.effects["destination"]?.toString()?.trim('"'),
                        plan.effects["focusChanges"]?.toString()?.let { "focusChanges=$it" },
                    ).joinToString(" · ")
                    val confirmation = if (planEffects.isEmpty()) effect else "$effect\n$planEffects"
                    val dialog = AlertDialog.Builder(this@LayoutActivity).setTitle(R.string.pane_layout_confirm_title).setMessage(confirmation)
                        .setNegativeButton(R.string.pane_action_cancel) { _, _ -> if (actorIsCurrent(captured)) busy = false }
                        .setPositiveButton(R.string.pane_layout_confirm) { _, _ ->
                            if (actorIsCurrent(captured)) dispatch(requestId, plan.planToken, action, kind, captured,
                                plan.layout.layout.tabId, plan.layout.layout.workspaceId)
                        }
                        .setOnCancelListener { if (actorIsCurrent(captured)) busy = false }.create()
                    presentDialog(captured, dialog)
                }
                is ApiResult.Failure -> { if (actorIsCurrent(captured)) { busy = false; showUnavailable(result.error) } }
                is ApiResult.NotModified -> { if (actorIsCurrent(captured)) { busy = false; showMessage(getString(R.string.pane_layout_unavailable)) } }
            }
        }
    }

    private fun dispatch(id: String, plan: String, action: kotlinx.serialization.json.JsonObject, kind: String,
        captured: ScopedReadContext, sourceTabId: String?, sourceWorkspaceId: String?) {
        if (!actorIsCurrent(captured)) return
        pendingId = id; pendingPlan = plan; pendingAction = kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), action)
        pendingSourceTabId = sourceTabId; pendingSourceWorkspaceId = sourceWorkspaceId
        val owner = com.lateapex.collie.data.PaneLayoutIntentOwner.from(captured, address.paneId)
        val frozen = com.lateapex.collie.data.PaneLayoutIntent(owner, captured.key.connectionGeneration, captured.key.leadEpoch,
            id, plan, pendingAction!!, sourceTabId, sourceWorkspaceId)
        var reservation: com.lateapex.collie.data.PaneLayoutReservation = com.lateapex.collie.data.PaneLayoutReservation.Unavailable
        val admitted = acknowledgeCurrentActor(captured) {
            reservation = (application as CollieApplication).container.paneLayoutIntentStore.reserve(frozen)
            true
        }
        if (!admitted || !actorIsCurrent(captured)) { markPendingUnknown(captured, id); return }
        when (reservation) {
            com.lateapex.collie.data.PaneLayoutReservation.Reserved,
            is com.lateapex.collie.data.PaneLayoutReservation.Existing -> Unit
            com.lateapex.collie.data.PaneLayoutReservation.UnresolvedIntent -> {
                val original = (application as CollieApplication).container.paneLayoutIntentStore.unresolved(owner)
                pendingId = original?.requestId; pendingPlan = original?.planToken; pendingAction = original?.actionJson
                pendingSourceTabId = original?.sourceTabId; pendingSourceWorkspaceId = original?.sourceWorkspaceId
                showUnknown(getString(R.string.pane_layout_recovered_unknown)); return
            }
            com.lateapex.collie.data.PaneLayoutReservation.Unavailable -> {
                val retained = (application as CollieApplication).container.paneLayoutIntentStore.unresolved(owner)
                if (retained != null) {
                    pendingId = retained.requestId; pendingPlan = retained.planToken; pendingAction = retained.actionJson
                    pendingSourceTabId = retained.sourceTabId; pendingSourceWorkspaceId = retained.sourceWorkspaceId
                    showUnknown(getString(R.string.pane_layout_storage_recovery)); return
                }
                pendingId = null; pendingPlan = null; pendingAction = null; busy = false
                pendingSourceTabId = null; pendingSourceWorkspaceId = null
                showMessage(getString(R.string.pane_layout_unavailable)); return
            }
        }
        lifecycleScope.launch {
          try {
            when (val result = repository.applyPaneLayout(address, id, plan, action, captured)) {
                is ApiResult.Success -> {
                    if (!actorIsCurrent(captured)) { markPendingUnknown(captured, id); return@launch }
                    val response = result.value
                    if (response.ok && response.operation?.state == "succeeded") {
                        if (kind == "move" && (response.move == null || response.move.previousPaneId != address.paneId ||
                            response.move.previousTabId != sourceTabId || response.move.previousWorkspaceId != sourceWorkspaceId)) {
                            retainUnknown(captured, id)
                            if (!actorIsCurrent(captured)) return@launch
                            showUnknown(getString(R.string.pane_layout_unknown)); return@launch
                        }
                        if (!clearPendingIntent(captured, id)) {
                            retainUnknown(captured, id)
                            if (!actorIsCurrent(captured)) return@launch
                            showUnknown(getString(R.string.pane_layout_unknown)); return@launch
                        }
                        if (!actorIsCurrent(captured)) return@launch
                        pendingId = null; pendingPlan = null; pendingAction = null
                        pendingSourceTabId = null; pendingSourceWorkspaceId = null; busy = false
                        if (kind == "move" && response.move != null) {
                            // A moved terminal receives a new opaque pane ID; discard old navigation and reload authority.
                            completeMove(response.move.paneId)
                        } else { showMessage(getString(R.string.pane_layout_complete)); loadLayout() }
                    } else if (response.operation?.state == "refused" && reservation is com.lateapex.collie.data.PaneLayoutReservation.Reserved) {
                        if (!clearPendingIntent(captured, id)) {
                            retainUnknown(captured, id)
                            if (!actorIsCurrent(captured)) return@launch
                            showUnknown(getString(R.string.pane_layout_unknown)); return@launch
                        }
                        if (!actorIsCurrent(captured)) return@launch
                        pendingId = null; pendingPlan = null; pendingAction = null
                        pendingSourceTabId = null; pendingSourceWorkspaceId = null; busy = false
                        showMessage(getString(R.string.pane_layout_refused)); loadLayout()
                    } else { markPendingUnknown(captured, id); if (actorIsCurrent(captured)) showUnknown(getString(R.string.pane_layout_unknown)) }
                }
                is ApiResult.Failure -> { markPendingUnknown(captured, id); if (actorIsCurrent(captured)) showUnknown(getString(R.string.pane_layout_unknown)) }
                is ApiResult.NotModified -> { markPendingUnknown(captured, id); if (actorIsCurrent(captured)) showUnknown(getString(R.string.pane_layout_unknown)) }
            }
          } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { markPendingUnknown(captured, id) }
            throw cancelled
          } catch (_: Exception) {
            withContext(NonCancellable) { markPendingUnknown(captured, id) }
            if (actorIsCurrent(captured)) showUnknown(getString(R.string.pane_layout_unknown))
          }
        }
    }

    private fun reconcile(id: String) {
        val captured = actor ?: return showUnknown(getString(R.string.pane_layout_unknown))
        if (!repository.isCurrent(captured)) return showMessage(getString(R.string.pane_layout_stale))
        lifecycleScope.launch {
            when (val result = repository.controlOperation(address.scope, id, captured)) {
                is ApiResult.Success -> {
                    if (!actorIsCurrent(captured)) return@launch
                    val operation = result.value.operation
                    val action = pendingAction?.let { runCatching { kotlinx.serialization.json.Json.parseToJsonElement(it).jsonObject }.getOrNull() }
                    val kind = action?.get("kind")?.toString()?.trim('"')
                    val expectedCapability = when (kind) {
                        "split" -> "paneSplit"; "resize" -> "paneResize"; "zoom" -> "paneZoom"; "swap" -> "paneSwap"; "move" -> "paneMove"; "focus-direction" -> "paneDirectionalFocus"; else -> null
                    }
                    if (expectedCapability == null || operation.capability != expectedCapability) {
                        showUnknown(getString(R.string.pane_layout_unknown)); return@launch
                    }
                    when (operation.state) {
                        "succeeded" -> {
                            val move = operation.result
                            if (kind == "move") {
                                if (move?.kind != "pane-move" || move.previousPaneId != address.paneId || move.paneId == move.previousPaneId ||
                                    pendingSourceTabId == null || move.previousTabId != pendingSourceTabId ||
                                    pendingSourceWorkspaceId == null || move.previousWorkspaceId != pendingSourceWorkspaceId) {
                                    showUnknown(getString(R.string.pane_layout_unknown)); return@launch
                                }
                            } else if (move != null) {
                                showUnknown(getString(R.string.pane_layout_unknown)); return@launch
                            }
                            if (!clearPendingIntent(captured, id)) {
                                retainUnknown(captured, id)
                                if (!actorIsCurrent(captured)) return@launch
                                showUnknown(getString(R.string.pane_layout_unknown)); return@launch
                            }
                            if (!actorIsCurrent(captured)) return@launch
                            pendingId = null; pendingPlan = null; pendingAction = null
                            pendingSourceTabId = null; pendingSourceWorkspaceId = null; busy = false
                            if (move?.kind == "pane-move") return@launch completeMove(move.paneId)
                            loadLayout()
                        }
                        "unknown", "pending" -> showUnknown(getString(R.string.pane_layout_unknown))
                        "refused" -> {
                            if (!clearPendingIntent(captured, id)) {
                                retainUnknown(captured, id)
                                if (!actorIsCurrent(captured)) return@launch
                                showUnknown(getString(R.string.pane_layout_unknown)); return@launch
                            }
                            if (!actorIsCurrent(captured)) return@launch
                            pendingId = null; pendingPlan = null; pendingAction = null
                            pendingSourceTabId = null; pendingSourceWorkspaceId = null; busy = false
                            showMessage(getString(R.string.pane_layout_refused))
                        }
                        else -> showUnknown(getString(R.string.pane_layout_unknown))
                    }
                }
                is ApiResult.Failure -> if (actorIsCurrent(captured)) showUnknown(getString(R.string.pane_layout_unknown))
                is ApiResult.NotModified -> if (actorIsCurrent(captured)) showUnknown(getString(R.string.pane_layout_unknown))
            }
        }
    }

    private fun completeMove(paneId: String) {
        setResult(RESULT_OK, intent.putExtra(EXTRA_MOVED_PANE_ID, paneId))
        finish()
    }

    private fun loadPendingIntent() {
        val context = actor ?: return
        val owner = com.lateapex.collie.data.PaneLayoutIntentOwner.from(context, address.paneId)
        val row = (application as CollieApplication).container.paneLayoutIntentStore.unresolved(owner) ?: return
        pendingId = row.requestId; pendingPlan = row.planToken; pendingAction = row.actionJson
        pendingSourceTabId = row.sourceTabId; pendingSourceWorkspaceId = row.sourceWorkspaceId
    }
    private fun markPendingUnknown(context: ScopedReadContext, id: String) {
        if (actorIsCurrent(context)) recoverySnapshotReady = false
        (application as CollieApplication).container.paneLayoutIntentStore.markUnknown(
            com.lateapex.collie.data.PaneLayoutIntentOwner.from(context, address.paneId), id)
    }
    private fun acknowledgeCurrentActor(context: ScopedReadContext, acknowledgement: () -> Boolean): Boolean =
        synchronized(custodyLock) {
            actor === context && repository.acknowledgeLocalCustody(context, acknowledgement)
        }

    private fun clearPendingIntent(context: ScopedReadContext, id: String): Boolean =
        acknowledgeCurrentActor(context) {
            (application as CollieApplication).container.paneLayoutIntentStore.resolve(
                com.lateapex.collie.data.PaneLayoutIntentOwner.from(context, address.paneId), id)
        }
    private fun retainUnknown(context: ScopedReadContext, id: String) {
        val store = (application as CollieApplication).container.paneLayoutIntentStore
        store.markUnknown(com.lateapex.collie.data.PaneLayoutIntentOwner.from(context, address.paneId), id)
        if (!actorIsCurrent(context)) return
        store.unresolved(com.lateapex.collie.data.PaneLayoutIntentOwner.from(context, address.paneId))?.let {
            pendingId = it.requestId; pendingPlan = it.planToken; pendingAction = it.actionJson
            pendingSourceTabId = it.sourceTabId; pendingSourceWorkspaceId = it.sourceWorkspaceId
        }
    }
    private fun actorIsCurrent(context: ScopedReadContext): Boolean = actor === context && repository.isCurrent(context)

    private fun wipeDialog(custody: DialogCustody) {
        val dialog = custody.dialog
        fun wipe(view: android.view.View) {
            if (view is android.widget.AdapterView<*>) {
                view.onItemSelectedListener = null
                if (view is android.widget.ListView) {
                    view.onItemClickListener = null
                    view.onItemLongClickListener = null
                }
            } else view.setOnClickListener(null)
            if (view is TextView) view.text = ""
            if (view is Spinner) view.adapter = null
            if (view is android.widget.ListView) view.adapter = null
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) wipe(view.getChildAt(i))
        }
        // The custom form may not yet have been attached to the window when publication loses
        // authority. Its EditTexts/spinner/closures still require the same destruction as a shown UI.
        custody.copiedView?.let(::wipe)
        dialog.window?.decorView?.let(::wipe)
        dialog.listView?.adapter = null
        dialog.setOnCancelListener(null)
        dialog.setTitle("")
        dialog.setMessage(null)
        dialog.setView(null)
        for (which in listOf(android.content.DialogInterface.BUTTON_POSITIVE, android.content.DialogInterface.BUTTON_NEGATIVE,
            android.content.DialogInterface.BUTTON_NEUTRAL)) {
            dialog.setButton(which, "", null as android.content.DialogInterface.OnClickListener?)
            dialog.getButton(which)?.setOnClickListener(null)
        }
        dialog.dismiss()
    }

    private fun presentDialog(context: ScopedReadContext, dialog: AlertDialog, copiedView: android.view.View? = null) {
        val custody = DialogCustody(dialog, copiedView)
        if (!actorIsCurrent(context)) {
            wipeDialog(custody)
            return
        }
        beforeDialogPublication(dialog, copiedView)
        var previous: DialogCustody? = null
        val admitted = synchronized(custodyLock) {
            if (disposalRequested || pendingRetirements.isNotEmpty() || !actorIsCurrent(context)) false
            else { previous = activeDialog; activeDialog = custody; true }
        }
        if (!admitted) { wipeDialog(custody); return }
        previous?.let(::wipeDialog)
        dialog.setOnDismissListener {
            synchronized(custodyLock) { if (activeDialog === custody) activeDialog = null }
        }
        // Main-thread presentation and queued retirement cannot interleave here. A worker which
        // retires after publication captures this exact custody and cannot ACK until its main wipe.
        dialog.show()
    }

    private fun showUnknown(message: String) {
        busy = false
        content.tag = null
        content.removeAllViews(); content.addView(text(message))
        if (!controlReady) content.addView(text(getString(R.string.pane_layout_receipts_unavailable)))
        val captured = actor
        if (controlReady) pendingId?.let { id -> content.addView(button(getString(R.string.pane_layout_reconcile)) {
            if (captured != null && actorIsCurrent(captured)) reconcile(id)
        }) }
        val store = (application as CollieApplication).container.paneLayoutIntentStore
        val owner = captured?.let { com.lateapex.collie.data.PaneLayoutIntentOwner.from(it, address.paneId) }
        val storageRecovery = owner != null && pendingId?.let { store.needsRecovery(owner, it) } == true
        if (storageRecovery) content.addView(text(getString(R.string.pane_layout_storage_recovery)))
        if (!storageRecovery && pendingId != null && pendingPlan != null && pendingAction != null && controlReady && recoverySnapshotReady &&
            repository.writesAllowed(address.scope, captured?.key?.subjectIdentity) && captured != null && actorIsCurrent(captured)) {
            content.addView(button(getString(R.string.pane_layout_retry_same_id)) {
                if (!actorIsCurrent(captured)) return@button
                val action = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(pendingAction!!).jsonObject }.getOrNull()
                if (action == null) showMessage(getString(R.string.pane_layout_unknown))
                else if (actorIsCurrent(captured)) dispatch(pendingId!!, pendingPlan!!, action,
                    action["kind"]?.toString()?.trim('"').orEmpty(), captured, pendingSourceTabId, pendingSourceWorkspaceId)
            })
        }
        pendingId?.let { id ->
            if (storageRecovery && captured != null && owner != null) {
                val frozen = store.unresolved(owner)
                if (frozen != null) content.addView(button(getString(R.string.pane_layout_recover_storage)) {
                    if (!actorIsCurrent(captured) || pendingId != id) return@button
                    val dialog = AlertDialog.Builder(this)
                        .setTitle(R.string.pane_layout_recover_storage)
                        .setMessage(R.string.pane_layout_storage_recovery_warning)
                        .setNegativeButton(R.string.pane_action_cancel, null)
                        .setPositiveButton(R.string.pane_layout_recover_storage) { _, _ ->
                            if (actorIsCurrent(captured) && pendingId == id) {
                                val restored = acknowledgeCurrentActor(captured) { store.recoverCustody(frozen) }
                                if (!actorIsCurrent(captured)) return@setPositiveButton
                                showUnknown(getString(if (restored) R.string.pane_layout_recovered_unknown else R.string.pane_layout_storage_recovery))
                            }
                        }.create()
                    presentDialog(captured, dialog)
                })
            } else content.addView(button(getString(R.string.pane_layout_abandon)) { confirmAbandon(captured, id) })
        }
        content.addView(refreshButton())
        if (captured != null && actorIsCurrent(captured) && (!controlReady || !recoverySnapshotReady)) loadReceiptReadiness(captured)
    }

    private fun confirmAbandon(captured: ScopedReadContext?, id: String) {
        if (captured == null || !actorIsCurrent(captured)) return
        val store = (application as CollieApplication).container.paneLayoutIntentStore
        val owner = com.lateapex.collie.data.PaneLayoutIntentOwner.from(captured, address.paneId)
        if (store.unresolved(owner)?.requestId != id) { loadLayout(); return }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.pane_layout_abandon_title)
            .setMessage(R.string.pane_layout_abandon_warning)
            .setNegativeButton(R.string.pane_action_cancel) { _, _ -> if (actorIsCurrent(captured)) showUnknown(getString(R.string.pane_layout_recovered_unknown)) }
            .setPositiveButton(R.string.pane_layout_abandon) { _, _ ->
                if (!actorIsCurrent(captured)) return@setPositiveButton
                if (store.unresolved(owner)?.requestId != id || !acknowledgeCurrentActor(captured) { store.abandon(owner, id) }) {
                    retainUnknown(captured, id)
                    if (!actorIsCurrent(captured)) return@setPositiveButton
                    showUnknown(getString(R.string.pane_layout_unknown))
                    return@setPositiveButton
                }
                if (!actorIsCurrent(captured)) return@setPositiveButton
                pendingId = null; pendingPlan = null; pendingAction = null
                pendingSourceTabId = null; pendingSourceWorkspaceId = null; busy = false
                showMessage(getString(R.string.pane_layout_abandoned))
                loadLayout()
            }
            .create()
        presentDialog(captured, dialog)
    }

    private fun loadReceiptReadiness(captured: ScopedReadContext, force: Boolean = false) {
        if (!force && receiptReadinessActor === captured) return
        receiptReadinessActor = captured
        lifecycleScope.launch {
            val result = repository.config(address.scope, captured)
            if (!actorIsCurrent(captured)) return@launch
            val config = (result as? ApiResult.Success)?.value
            controlReady = config?.control?.supportsNew("controlOperations") == true
            supported = config?.mux?.capabilities?.filterValues { it }?.keys.orEmpty()
            val ready = if (controlReady) {
                val snapshot = repository.paneSnapshot(address.scope, captured)
                if (!actorIsCurrent(captured)) return@launch
                snapshot is ApiResult.Success
            } else false
            if (!actorIsCurrent(captured)) return@launch
            recoverySnapshotReady = ready
            if (actorIsCurrent(captured) && pendingId != null) showUnknown(getString(R.string.pane_layout_recovered_unknown))
        }
    }

    private fun refreshButton(): MaterialButton {
        val captured = actor
        val generation = custodyGeneration.get()
        return button(getString(R.string.pane_layout_refresh)) {
            if (custodyGeneration.get() != generation || actor !== captured) return@button
            if (captured == null) refreshCurrentActor() else if (actorIsCurrent(captured)) loadLayout()
        }
    }

    private fun showRetired() {
        content.tag = null
        content.removeAllViews()
        content.addView(text(getString(R.string.pane_layout_stale)))
        content.addView(refreshButton())
    }

    private fun retireCopiedCustody() {
        actor = null
        activeDialog = null
        busy = false
        pendingId = null; pendingPlan = null; pendingAction = null
        pendingSourceTabId = null; pendingSourceWorkspaceId = null
        supported = emptySet(); controlReady = false
        receiptReadinessActor = null; recoverySnapshotReady = false
        displayedLayout = null
        availableTargets = emptyList(); availableWorkspaces = emptyList(); advertisedFolders = emptyList()
    }

    private fun refreshCurrentActor() {
        drainRetirements()
        val (generation, dialog) = synchronized(custodyLock) {
            if (disposalRequested || pendingRetirements.isNotEmpty()) return
            val old = activeDialog
            retireCopiedCustody()
            custodyGeneration.incrementAndGet() to old
        }
        dialog?.let(::wipeDialog)
        // Repository acquisition can itself retire authority. It runs outside custody locks;
        // publish only if no off-main retirement/post failure intervened during acquisition.
        val candidate = repository.readContext(address.scope, address.paneId)
        val accepted = synchronized(custodyLock) {
            if (disposalRequested || pendingRetirements.isNotEmpty() || custodyGeneration.get() != generation) false
            else { actor = candidate; true }
        }
        if (!accepted) return
        loadPendingIntent()
        if (pendingId != null) showUnknown(getString(R.string.pane_layout_recovered_unknown)) else loadLayout()
    }

    private fun showUnavailable(error: ApiFailure) = showMessage(errorMessage(error))
    private fun errorMessage(error: ApiFailure): String = when (error) {
        is ApiFailure.Http -> error.message ?: error.code ?: getString(R.string.pane_layout_unavailable)
        is ApiFailure.Network -> getString(R.string.pane_layout_unavailable)
        is ApiFailure.Timeout -> getString(R.string.pane_layout_unavailable)
        is ApiFailure.Protocol -> error.message
        is ApiFailure.Redirect -> getString(R.string.pane_layout_unavailable)
    }
    private fun showMessage(message: String) { content.tag = null; content.removeAllViews(); content.addView(text(message).apply { sendAccessibilityEvent(AccessibilityEvent.TYPE_ANNOUNCEMENT) }) }
    private fun text(value: String) = TextView(this).apply { text = value; textSize = 16f; setPadding(0, dp(10), 0, dp(10)) }
    private fun button(label: String, action: () -> Unit) = MaterialButton(this).apply { text = label; minHeight = dp(48); contentDescription = label; setOnClickListener { action() } }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_PANE_ID = "layout.pane"
        const val EXTRA_HOST = "layout.host"
        const val EXTRA_SESSION = "layout.session"
        const val EXTRA_MOVED_PANE_ID = "layout.movedPane"
    }
}
