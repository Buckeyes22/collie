package com.lateapex.collie.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.data.CollieRepository
import com.lateapex.collie.data.AuthorityInvalidation
import com.lateapex.collie.data.LaunchIntentReceiptMode
import com.lateapex.collie.data.ScopedReadContext
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.CreateResponse
import com.lateapex.collie.network.FoldersResponse
import com.lateapex.collie.network.Launcher
import com.lateapex.collie.network.LauncherItem
import com.lateapex.collie.network.LaunchersResponse
import com.lateapex.collie.network.StartTarget
import com.lateapex.collie.network.SnapshotResponse
import java.util.UUID
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

internal data class NewLaunchChoice(
    val key: String,
    val label: String,
    val target: StartTarget,
    val command: String?,
    val cwd: String?,
    val noPrompts: Boolean,
    val available: Boolean,
    val reason: String?,
    val ownerIntentSupported: Boolean = true,
)

/** Pure catalog mapping kept separate from Activity rendering so legacy/malformed choices are tested. */
internal object NewLaunchModel {
    fun choices(response: LaunchersResponse): List<NewLaunchChoice> = response.items?.map { item ->
        NewLaunchChoice(item.key, item.label, item.start, item.command, item.cwd, item.noPrompts, item.available, item.reason)
    } ?: response.launchers.map { row ->
        NewLaunchChoice(
            key = "legacy:${row.command}", label = row.label,
            target = row.target ?: StartTarget(command = row.command), command = row.command, cwd = row.cwd,
            noPrompts = row.noPrompts, available = true, reason = null, ownerIntentSupported = row.target != null,
        )
    }
}

/** One scoped native New workflow; launch inputs stay declarative and folder strings come from the host. */
class NewActivity : AppCompatActivity() {
    private lateinit var repository: CollieRepository
    private var scope: Scope = Scope()
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var scopeCaption: TextView
    private lateinit var machineButton: MaterialButton
    private lateinit var sessionButton: MaterialButton
    private lateinit var progress: ProgressBar
    private lateinit var choicesView: RadioGroup
    private lateinit var cwd: EditText
    private lateinit var pickerSlot: LinearLayout
    private lateinit var recoverySlot: LinearLayout
    private lateinit var launchButton: MaterialButton
    private lateinit var folderPicker: FolderPickerView
    private var contextKey: ScopedReadContext? = null
    private var catalog: List<NewLaunchChoice> = emptyList()
    private var selectedKey: String? = null
    private var requiresTargetChoice = false
    private var folderOverrideSelected = false
    private var folders: FoldersResponse? = null
    private var running = false
    private var inventory: SnapshotResponse? = null
    private var scopedSnapshotReady = false
    private var uiOperation = 0L
    private var scopeSelectionInProgress = false
    private var authoritySubscription: AutoCloseable? = null
    private var connectionObservation: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.prepareEdgeToEdgeContent()
        if (!::repository.isInitialized) repository = (application as CollieApplication).container.repository
        scope = Scope(
            host = (if (savedInstanceState?.containsKey(STATE_SCOPE_HOST) == true) savedInstanceState.getString(STATE_SCOPE_HOST) else intent.getStringExtra(EXTRA_HOST))?.takeIf(String::isNotBlank),
            session = (if (savedInstanceState?.containsKey(STATE_SCOPE_SESSION) == true) savedInstanceState.getString(STATE_SCOPE_SESSION) else intent.getStringExtra(EXTRA_SESSION))?.takeIf(String::isNotBlank),
        )
        requiresTargetChoice = savedInstanceState?.getBoolean(STATE_REQUIRES_TARGET_CHOICE) == true
        selectedKey = savedInstanceState?.getString(STATE_SELECTED_KEY).takeUnless { requiresTargetChoice }
        folderOverrideSelected = savedInstanceState?.getBoolean(STATE_FOLDER_SELECTED) == true
        buildContent(savedInstanceState?.getString(STATE_CWD).orEmpty())
        window.applySafeContentInsets(root)
        observeRepositoryAuthority()
        loadInventory()
    }

    private fun observeRepositoryAuthority() {
        authoritySubscription?.close()
        connectionObservation?.cancel()
        authoritySubscription = repository.onAuthorityInvalidated { reason ->
            runOnUiThread {
                val contextIsStale = contextKey?.let { !repository.isCurrent(it) } == true
                if (reason == AuthorityInvalidation.SCOPE_CHANGED && scopeSelectionInProgress) return@runOnUiThread
                if (reason == AuthorityInvalidation.SCOPE_CHANGED && !contextIsStale) return@runOnUiThread
                uiOperation++
                running = false
                scopedSnapshotReady = false
                progress.isVisible = false
                launchButton.isEnabled = false
                folderPicker.render(folders, cwd.text.toString().ifBlank { null }, allowStar = false)
                status.setText(R.string.new_authority_unavailable)
            }
        }
        var observedConnection = repository.connection.value
        connectionObservation = lifecycleScope.launch {
            repository.connection.collect { current ->
                if (current === observedConnection) return@collect
                observedConnection = current
                uiOperation++
                running = false
                scopedSnapshotReady = false
                progress.isVisible = false
                launchButton.isEnabled = false
                if (current == null) {
                    status.setText(R.string.new_unavailable)
                    folderPicker.render(folders, cwd.text.toString().ifBlank { null }, allowStar = false)
                } else {
                    loadInventory()
                }
            }
        }
    }

    override fun onDestroy() {
        authoritySubscription?.close()
        authoritySubscription = null
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_SCOPE_HOST, scope.host)
        outState.putString(STATE_SCOPE_SESSION, scope.session)
        outState.putBoolean(STATE_REQUIRES_TARGET_CHOICE, requiresTargetChoice)
        outState.putString(STATE_SELECTED_KEY, selectedKey)
        outState.putString(STATE_CWD, cwd.text.toString().take(MAX_SAVED_CWD_CHARS))
        outState.putBoolean(STATE_FOLDER_SELECTED, folderOverrideSelected)
        super.onSaveInstanceState(outState)
    }

    private fun buildContent(savedCwd: String) {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
            setBackgroundColor(ContextCompat.getColor(this@NewActivity, R.color.collie_background))
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        root.addView(TextView(this).apply {
            text = getString(R.string.new_title)
            textSize = 22f
            setTextColor(ContextCompat.getColor(this@NewActivity, R.color.collie_foreground))
        })
        scopeCaption = TextView(this).apply {
            text = getString(R.string.new_machine_scope, scope.host ?: getString(R.string.new_this_machine), scope.session ?: getString(R.string.new_default_session))
            textSize = 13f
            setTextIsSelectable(true)
            setTextColor(ContextCompat.getColor(this@NewActivity, R.color.collie_muted))
        }
        root.addView(scopeCaption, rowParams(4))
        machineButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.new_machine_select, scope.host ?: getString(R.string.new_this_machine))
            isAllCaps = false
            minHeight = dp(48)
            setOnClickListener { chooseMachine() }
        }
        sessionButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.new_session_select, scope.session ?: getString(R.string.new_default_session))
            isAllCaps = false
            minHeight = dp(48)
            setOnClickListener { chooseSession() }
        }
        root.addView(machineButton, rowParams(8))
        root.addView(sessionButton, rowParams(4))
        status = TextView(this).apply {
            text = getString(R.string.new_loading)
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@NewActivity, R.color.collie_muted))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(status, rowParams(8))
        progress = ProgressBar(this).apply { isIndeterminate = true }
        root.addView(progress, rowParams(4))
        choicesView = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        root.addView(choicesView, rowParams(8))
        cwd = EditText(this).apply {
            hint = getString(R.string.new_folder_manual)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
            filters = arrayOf(InputFilter.LengthFilter(MAX_SAVED_CWD_CHARS))
            setText(savedCwd.take(MAX_SAVED_CWD_CHARS))
            minHeight = dp(48)
            contentDescription = getString(R.string.new_folder_manual_accessibility)
        }
        root.addView(cwd, rowParams(8))
        pickerSlot = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        folderPicker = FolderPickerView(this, onSelect = { selected ->
            folderOverrideSelected = true
            cwd.setText(selected.orEmpty())
            cwd.setSelection(cwd.text.length)
        }, onStar = ::toggleStar)
        pickerSlot.addView(folderPicker, rowParams())
        root.addView(pickerSlot, rowParams(4))
        launchButton = MaterialButton(this).apply {
            text = getString(R.string.new_start)
            isAllCaps = false
            minHeight = dp(48)
            setOnClickListener { startSelected() }
        }
        root.addView(launchButton, rowParams(12))
        recoverySlot = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(recoverySlot, rowParams(4))
    }

    private fun loadCatalog() {
        val operation = ++uiOperation
        progress.isVisible = true
        launchButton.isEnabled = false
        status.setText(R.string.new_loading)
        val captured = repository.readContext(scope)
        if (captured == null) {
            scopedSnapshotReady = false
            progress.isVisible = false
            status.setText(R.string.new_unavailable)
            catalog = emptyList()
            renderChoices()
            showUnresolvedIntent()
            return
        }
        contextKey = captured
        lifecycleScope.launch {
            val scopedSnapshot = repository.snapshot(scope, captured)
            if (!repository.isCurrent(captured)) {
                settleStaleOperation(operation)
                return@launch
            }
            scopedSnapshotReady = scopedSnapshot is ApiResult.Success
            if (scopedSnapshot !is ApiResult.Success) {
                progress.isVisible = false
                status.text = when (scopedSnapshot) {
                    is ApiResult.Failure -> getString(R.string.new_scope_snapshot_error, MainViewModel.describe(resources, scopedSnapshot.error))
                    is ApiResult.NotModified -> getString(R.string.new_scope_snapshot_error, getString(R.string.new_catalog_error_unknown))
                    is ApiResult.Success -> error("unreachable")
                }
                catalog = emptyList()
                renderChoices()
                folderPicker.render(null, cwd.text.toString().ifBlank { null }, allowStar = false)
                showUnresolvedIntent()
                return@launch
            }
            val launchResult = repository.launchers(scope)
            val folderResult = repository.folders(scope)
            if (!repository.isCurrent(captured)) {
                settleStaleOperation(operation)
                return@launch
            }
            progress.isVisible = false
            when (launchResult) {
                is ApiResult.Success -> {
                    catalog = NewLaunchModel.choices(launchResult.value)
                    status.text = if (catalog.isEmpty()) getString(R.string.new_empty) else getString(R.string.new_choose_target)
                    renderChoices()
                }
                is ApiResult.Failure -> status.text = getString(R.string.new_catalog_error, MainViewModel.describe(resources, launchResult.error))
                is ApiResult.NotModified -> status.setText(R.string.new_catalog_error_unknown)
            }
            when (folderResult) {
                is ApiResult.Success -> folders = folderResult.value
                is ApiResult.Failure -> status.append("\n${getString(R.string.folder_list_unavailable)}")
                is ApiResult.NotModified -> status.append("\n${getString(R.string.folder_list_unavailable)}")
            }
            folderPicker.render(folders, cwd.text.toString().ifBlank { null }, repository.writesAllowed(scope))
            launchButton.isEnabled = catalog.any { it.available && it.key == selectedKey } && !requiresTargetChoice && writesAvailable() &&
                repository.unresolvedLaunchIntents(scope).isEmpty()
            showUnresolvedIntent()
        }
    }

    private fun loadInventory() {
        val operation = ++uiOperation
        scopeSelectionInProgress = true
        repository.selectSubject(scope, null)
        scopeSelectionInProgress = false
        machineButton.text = getString(R.string.new_machine_select, scope.host ?: getString(R.string.new_this_machine))
        sessionButton.text = getString(R.string.new_session_select, scope.session ?: getString(R.string.new_default_session))
        val inventoryContext = repository.readContext(Scope(viewAll = true))
        if (inventoryContext == null) {
            loadCatalog()
            return
        }
        progress.isVisible = true
        lifecycleScope.launch {
            val result = repository.snapshot(Scope(viewAll = true), inventoryContext)
            if (!repository.isCurrent(inventoryContext)) {
                settleStaleOperation(operation)
                return@launch
            }
            progress.isVisible = false
            if (result is ApiResult.Success) inventory = result.value
            loadCatalog()
        }
    }

    private fun chooseMachine() {
        val snapshot = inventory ?: return
        val options = snapshot.servers.orEmpty().map { server ->
            (if (server.isLead) null else server.id) to (server.name.ifBlank { server.id })
        }.ifEmpty { listOf(scope.host to (scope.host ?: getString(R.string.new_this_machine))) }
        val labels = options.map { (_, label) -> label }.toTypedArray()
        AlertDialog.Builder(this).setTitle(R.string.new_machine_select_title).setItems(labels) { _, which ->
            val selectedHost = options[which].first
            if (selectedHost != scope.host) {
                selectedKey = null
                requiresTargetChoice = true
                scope = Scope(host = selectedHost)
                scopeSelectionInProgress = true
                repository.selectSubject(scope, null)
                scopeSelectionInProgress = false
                machineButton.text = getString(R.string.new_machine_select, options[which].second)
                sessionButton.text = getString(R.string.new_session_select, getString(R.string.new_default_session))
                updateScopeCaption()
                cwd.setText("")
                folderOverrideSelected = false
                folders = null
                catalog = emptyList()
                running = false
                scopedSnapshotReady = false
                launchButton.isEnabled = false
                status.setText(R.string.new_loading)
                recoverySlot.removeAllViews()
                folderPicker.render(null, null, repository.writesAllowed(scope))
                renderChoices()
                loadCatalog()
            }
        }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun chooseSession() {
        val snapshot = inventory ?: return
        val leadId = snapshot.servers?.firstOrNull { it.isLead }?.id
        val sessions = snapshot.sessions.filter { session ->
            if (scope.host == null) session.host == null || session.host == leadId
            else session.host == scope.host
        }
        val options = listOf<String?>(null) + sessions.map { it.name }
        val labels = (listOf(getString(R.string.new_all_sessions)) + sessions.map { it.name }).toTypedArray()
        AlertDialog.Builder(this).setTitle(R.string.new_session_select_title).setItems(labels) { _, which ->
            val selectedSession = options[which]
            if (selectedSession != scope.session) {
                selectedKey = null
                requiresTargetChoice = true
                scope = Scope(host = scope.host, session = selectedSession)
                scopeSelectionInProgress = true
                repository.selectSubject(scope, null)
                scopeSelectionInProgress = false
                sessionButton.text = getString(R.string.new_session_select, selectedSession ?: getString(R.string.new_default_session))
                updateScopeCaption()
                cwd.setText("")
                folderOverrideSelected = false
                folders = null
                catalog = emptyList()
                running = false
                scopedSnapshotReady = false
                launchButton.isEnabled = false
                status.setText(R.string.new_loading)
                recoverySlot.removeAllViews()
                folderPicker.render(null, null, repository.writesAllowed(scope))
                renderChoices()
                loadCatalog()
            }
        }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun renderChoices() {
        choicesView.removeAllViews()
        catalog.forEach { option ->
            val radio = RadioButton(this).apply {
                id = View.generateViewId()
                text = option.label
                isEnabled = option.available
                isChecked = option.key == selectedKey || selectedKey == null && !requiresTargetChoice && option.available && catalog.firstOrNull(NewLaunchChoice::available)?.key == option.key
                if (isChecked) selectedKey = option.key
                contentDescription = getString(R.string.new_choice_accessibility, option.label)
                setOnClickListener {
                    selectedKey = option.key
                    requiresTargetChoice = false
                    applySelectedFolderPolicy(option)
                    launchButton.isEnabled = writesAvailable() && !running && repository.unresolvedLaunchIntents(scope).isEmpty()
                }
            }
            choicesView.addView(radio, RadioGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            if (!option.available) choicesView.addView(TextView(this).apply {
                text = option.reason?.takeIf(String::isNotBlank) ?: getString(R.string.new_choice_unavailable)
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@NewActivity, R.color.collie_muted))
                setPadding(dp(44), 0, 0, dp(6))
            }, rowParams())
        }
        catalog.firstOrNull { it.key == selectedKey }?.let(::applySelectedFolderPolicy)
    }

    private fun applySelectedFolderPolicy(option: NewLaunchChoice) {
        val pinned = option.cwd?.takeIf(String::isNotBlank)
        cwd.isEnabled = pinned == null
        cwd.hint = getString(if (pinned == null) R.string.new_folder_manual else R.string.new_folder_pinned)
        cwd.contentDescription = if (pinned == null) {
            getString(R.string.new_folder_manual_accessibility)
        } else {
            getString(R.string.new_folder_pinned_accessibility, pinned)
        }
        if (pinned != null) {
            cwd.setText(pinned)
            folderPickerSlotVisible(false)
        } else {
            if (!folderOverrideSelected) cwd.setText(option.cwd.orEmpty())
            folderPickerSlotVisible(true)
            folderPicker.render(folders, cwd.text.toString().ifBlank { null }, writesAvailable())
        }
    }

    private fun folderPickerSlotVisible(visible: Boolean) {
        pickerSlot.isVisible = visible
    }

    private fun updateScopeCaption() {
        scopeCaption.text = getString(
            R.string.new_machine_scope,
            scope.host ?: getString(R.string.new_this_machine),
            scope.session ?: getString(R.string.new_default_session),
        )
    }

    private fun toggleStar(folder: String, starred: Boolean) {
        val captured = contextKey ?: return
        if (!repository.isCurrent(captured) || !writesAvailable() || running) return
        lifecycleScope.launch {
            val result = repository.starFolder(scope, folder, starred)
            if (!repository.isCurrent(captured)) return@launch
            when (result) {
                is ApiResult.Success -> {
                    folders = result.value
                    folderPicker.render(folders, cwd.text.toString().ifBlank { null }, writesAvailable())
                }
                is ApiResult.Failure -> status.text = getString(R.string.folder_star_failed, MainViewModel.describe(resources, result.error))
                is ApiResult.NotModified -> status.setText(R.string.folder_star_failed_unknown)
            }
        }
    }

    private fun startSelected() {
        val captured = contextKey ?: return
        val selected = catalog.firstOrNull { it.key == selectedKey && it.available } ?: run {
            status.setText(R.string.new_choose_target_error)
            return
        }
        if (!repository.isCurrent(captured) || !writesAvailable()) {
            status.setText(R.string.new_write_unavailable)
            return
        }
        if (repository.unresolvedLaunchIntents(scope).isNotEmpty()) {
            showUnresolvedIntent()
            launchButton.isEnabled = false
            return
        }
        if (running) return
        val operation = ++uiOperation
        running = true
        launchButton.isEnabled = false
        progress.isVisible = true
        lifecycleScope.launch {
            try {
                val enteredPath = cwd.text.toString().trim().takeIf(String::isNotEmpty)
                val path = selected.cwd?.takeIf(String::isNotBlank) ?: enteredPath
                val launcher = Launcher(
                    command = selected.command ?: selected.target.command ?: selected.target.harness ?: getString(R.string.launch_target_shell),
                    label = selected.label,
                    cwd = path,
                    harness = selected.target.harness,
                    noPrompts = selected.noPrompts,
                    target = selected.target,
                )
                if (!confirmLauncher(this@NewActivity, launcher, scope.host ?: captured.connection.origin.value)) return@launch
                if (!repository.isCurrent(captured) || !writesAvailable()) return@launch
                val result = if (!selected.ownerIntentSupported) {
                    val command = selected.command ?: return@launch
                    val requestId = UUID.randomUUID().toString()
                    repository.startLegacyLaunchInitial(scope, command, requestId, path, selected.noPrompts)
                } else {
                    val requestId = UUID.randomUUID().toString()
                    repository.startLaunchIntent(scope, selected.target, requestId, path, selected.noPrompts)
                }
                if (!repository.isCurrent(captured)) {
                    settleStaleOperation(operation)
                    return@launch
                }
                when (result) {
                    is ApiResult.Success -> if (result.value.ok && result.value.pane != null) openCreatedPane(result.value)
                    else status.text = result.value.error ?: getString(R.string.new_start_failed)
                    is ApiResult.Failure -> {
                        val unresolved = repository.unresolvedLaunchIntents(scope).firstOrNull { it.intent.operation == "launch" }
                        if (unresolved != null) {
                            status.setText(R.string.new_outcome_unknown)
                            showRecoveryActions(unresolved.intent.requestId, unresolved.intent.receiptMode)
                        } else status.text = getString(R.string.new_start_failed_detail, MainViewModel.describe(resources, result.error))
                    }
                    is ApiResult.NotModified -> status.setText(R.string.new_start_failed)
                }
            } finally {
                if (operation == uiOperation) {
                    running = false
                    progress.isVisible = false
                    launchButton.isEnabled = catalog.any { it.available && it.key == selectedKey } && !requiresTargetChoice && writesAvailable() &&
                        repository.unresolvedLaunchIntents(scope).isEmpty()
                }
            }
        }
    }

    private fun showUnresolvedIntent() {
        recoverySlot.removeAllViews()
        val unresolved = repository.unresolvedLaunchIntents(scope).firstOrNull { it.intent.operation == "launch" } ?: return
        status.setText(R.string.new_outcome_unknown)
        launchButton.isEnabled = false
        showRecoveryActions(unresolved.intent.requestId, unresolved.intent.receiptMode)
    }

    private fun showRecoveryActions(requestId: String, receiptMode: LaunchIntentReceiptMode = LaunchIntentReceiptMode.ownerBound) {
        recoverySlot.removeAllViews()
        var retry: View? = null
        if (receiptMode == LaunchIntentReceiptMode.legacyInitialOnly) {
            recoverySlot.addView(TextView(this).apply {
                text = getString(R.string.new_legacy_unknown_no_retry)
                setTextColor(ContextCompat.getColor(this@NewActivity, R.color.collie_muted))
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }, rowParams(4))
        } else {
            val retryButton = MaterialButton(this).apply {
                text = getString(R.string.new_retry_same_intent)
                isAllCaps = false
                minHeight = dp(48)
                isEnabled = writesAvailable()
                setOnClickListener {
                    val captured = contextKey ?: return@setOnClickListener
                    if (!repository.isCurrent(captured) || running) return@setOnClickListener
                    val operation = ++uiOperation
                    running = true
                    progress.isVisible = true
                    lifecycleScope.launch {
                        try {
                            val result = repository.retryLaunchIntent(scope, requestId)
                            if (!repository.isCurrent(captured)) {
                                settleStaleOperation(operation)
                                return@launch
                            }
                            if (result is ApiResult.Success && result.value.ok && result.value.pane != null) openCreatedPane(result.value)
                            else status.setText(R.string.new_outcome_unknown)
                        } finally {
                            if (operation == uiOperation) {
                                running = false
                                progress.isVisible = false
                                launchButton.isEnabled = catalog.any { it.available && it.key == selectedKey } && !requiresTargetChoice && writesAvailable() &&
                                    repository.unresolvedLaunchIntents(scope).isEmpty()
                            }
                        }
                    }
                }
            }
            retry = retryButton
            recoverySlot.addView(retryButton, rowParams(6))
        }
        val abandon = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.new_abandon_intent)
            isAllCaps = false
            minHeight = dp(48)
            setOnClickListener {
                androidx.appcompat.app.AlertDialog.Builder(this@NewActivity)
                    .setTitle(R.string.new_abandon_title)
                    .setMessage(R.string.new_abandon_warning)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.new_abandon_confirm) { _, _ ->
                        val captured = contextKey
                        if (captured == null || !repository.isCurrent(captured)) return@setPositiveButton
                        val result = repository.abandonLaunchIntent(scope, requestId)
                        if (result is ApiResult.Success) {
                            status.setText(R.string.new_intent_abandoned)
                            retry?.visibility = View.GONE
                            this.visibility = View.GONE
                        } else status.setText(R.string.new_outcome_unknown)
                    }.show()
            }
        }
        recoverySlot.addView(abandon, rowParams(4))
    }

    private fun openCreatedPane(response: CreateResponse) {
        val pane = response.pane ?: return
        startActivity(Intent(this, PaneActivity::class.java).apply {
            putExtra(PaneActivity.EXTRA_PANE_ID, pane.paneId)
            putExtra(PaneActivity.EXTRA_HOST, scope.host)
            putExtra(PaneActivity.EXTRA_SESSION, scope.session)
            putExtra(PaneActivity.EXTRA_TITLE, pane.workspaceLabel)
            putExtra(PaneActivity.EXTRA_META, getString(R.string.pane_meta, getString(R.string.new_agent_label), pane.workspaceLabel))
            putExtra(PaneActivity.EXTRA_AGENT, getString(R.string.new_agent_label))
            putExtra(PaneActivity.EXTRA_CWD, pane.cwd)
            putExtra(PaneActivity.EXTRA_TAB_LABEL, pane.tabId)
            putExtra(PaneActivity.EXTRA_STATUS, "unknown")
            putExtra(PaneActivity.EXTRA_FRESH_PANE, true)
        })
        finish()
    }

    private fun writesAvailable(): Boolean = scopedSnapshotReady && repository.writesAllowed(scope)

    private fun settleStaleOperation(operation: Long) {
        if (operation != uiOperation) return
        running = false
        scopedSnapshotReady = false
        progress.isVisible = false
        launchButton.isEnabled = false
        folderPicker.render(folders, cwd.text.toString().ifBlank { null }, allowStar = false)
        status.setText(R.string.new_authority_unavailable)
    }

    private fun rowParams(top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(top)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_HOST = "host"
        private const val EXTRA_SESSION = "session"
        private const val STATE_SCOPE_HOST = "new.scopeHost"
        private const val STATE_SCOPE_SESSION = "new.scopeSession"
        private const val STATE_REQUIRES_TARGET_CHOICE = "new.requiresTargetChoice"
        private const val STATE_SELECTED_KEY = "new.selectedKey"
        private const val STATE_CWD = "new.cwd"
        private const val STATE_FOLDER_SELECTED = "new.folderSelected"
        private const val MAX_SAVED_CWD_CHARS = 4096

        fun intent(context: Context, scope: Scope) = Intent(context, NewActivity::class.java).apply {
            putExtra(EXTRA_HOST, scope.host)
            putExtra(EXTRA_SESSION, scope.session)
        }
    }
}
