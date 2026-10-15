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
import androidx.core.widget.doAfterTextChanged
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
import com.lateapex.collie.network.WorktreeBase
import com.lateapex.collie.network.WorktreeFolder
import com.lateapex.collie.network.WorktreePlanResponse
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
    val branchSupported: Boolean = false,
)

/** Pure catalog mapping kept separate from Activity rendering so legacy/malformed choices are tested. */
internal object NewLaunchModel {
    fun choices(response: LaunchersResponse): List<NewLaunchChoice> = response.items?.map { item ->
        NewLaunchChoice(item.key, item.label, item.start, item.command, item.cwd, item.noPrompts, item.available, item.reason, branchSupported = item.branch)
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
    private lateinit var worktreeToggle: com.google.android.material.switchmaterial.SwitchMaterial
    private lateinit var worktreeOptions: LinearLayout
    private lateinit var worktreeBranch: EditText
    private lateinit var worktreeBaseRef: EditText
    private lateinit var worktreeParent: EditText
    private lateinit var pickerSlot: LinearLayout
    private lateinit var recoverySlot: LinearLayout
    private lateinit var launchButton: MaterialButton
    private lateinit var runButton: MaterialButton
    private lateinit var recipesButton: MaterialButton
    private lateinit var folderPicker: FolderPickerView
    @Volatile private var contextKey: ScopedReadContext? = null
    private var catalog: List<NewLaunchChoice> = emptyList()
    private var selectedKey: String? = null
    private var requiresTargetChoice = false
    private var folderOverrideSelected = false
    private var applyingFolderPolicy = false
    private var worktreeSelected = false
    private var folders: FoldersResponse? = null
    private var running = false
    private var inventory: SnapshotResponse? = null
    private var scopedSnapshotReady = false
    private var uiOperation = 0L
    private class WorktreeFormOwner(val context: ScopedReadContext, val operation: Long)
    private var worktreeFormOwner: WorktreeFormOwner? = null
    // Editable inputs outlive a modal review; one Activity subscription owns this separate copy.
    private var editableFormContext: ScopedReadContext? = null
    private var editableFormScope: Scope? = null
    private data class SavedEditableForm(val ownerHash: String, val incarnation: String,
        val cwd: String, val branch: String, val base: String, val parent: String,
        val folderOverride: Boolean, val worktreeSelected: Boolean)
    private var pendingEditableForm: SavedEditableForm? = null
    private var savedFormCopy: Bundle? = null
    private var scopeSelectionInProgress = false
    private var authoritySubscription: AutoCloseable? = null
    private var connectionObservation: Job? = null
    private var namedAgentPaneDialog: AlertDialog? = null
    private var namedAgentPaneActor: ScopedReadContext? = null
    private var namedAgentPaneRetirement: AutoCloseable? = null

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
        // Saved private strings are inert until their original durable owner matches an admitted
        // current connection. A bearer-bearing Connection never enters saved state.
        val savedOwner = savedInstanceState?.getString(STATE_FORM_OWNER)
        val savedIncarnation = savedInstanceState?.getString(STATE_FORM_INCARNATION)
        if (savedInstanceState != null && savedOwner?.matches(Regex("[0-9a-f]{64}")) == true &&
            savedIncarnation?.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) == true) {
            pendingEditableForm = SavedEditableForm(savedOwner, savedIncarnation,
                savedInstanceState.getString(STATE_CWD).orEmpty().take(MAX_SAVED_CWD_CHARS),
                savedInstanceState.getString(STATE_WORKTREE_BRANCH).orEmpty().take(MAX_SAVED_BRANCH_CHARS),
                savedInstanceState.getString(STATE_WORKTREE_BASE).orEmpty().take(MAX_SAVED_BRANCH_CHARS),
                savedInstanceState.getString(STATE_WORKTREE_PARENT).orEmpty().take(MAX_SAVED_CWD_CHARS),
                savedInstanceState.getBoolean(STATE_FOLDER_SELECTED), savedInstanceState.getBoolean(STATE_WORKTREE_SELECTED))
        }
        buildContent("")
        window.applySafeContentInsets(root)
        observeRepositoryAuthority()
        loadInventory()
    }

    private fun observeRepositoryAuthority() {
        authoritySubscription?.close()
        connectionObservation?.cancel()
        authoritySubscription = repository.onAuthorityRetired { event ->
            withMainCustodyAck {
                if (event.retiresReadActor && event.affects(editableFormContext)) clearEditableForm()
                if (event.retiresReadActor && pendingEditableForm?.ownerHash == event.owner?.storageIdentity) pendingEditableForm = null
                if (!event.affects(contextKey)) return@withMainCustodyAck
                val reason = event.reason
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
        }
        var observedConnection = repository.connection.value
        connectionObservation = lifecycleScope.launch {
            repository.connection.collect { current ->
                val adopted = contextKey?.takeIf { !repository.isActorRetired(it) }
                if (adopted != null) {
                    if (adopted.connection === current) observedConnection = current
                    return@collect
                }
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

    override fun onStop() {
        closeNamedAgentPanePicker()
        super.onStop()
    }

    override fun onDestroy() {
        namedAgentPaneRetirement?.close(); namedAgentPaneRetirement = null
        closeNamedAgentPanePicker()
        authoritySubscription?.close()
        authoritySubscription = null
        pendingEditableForm = null
        // Android owns a permitted saved rotation copy after destruction; its witness still
        // prevents any replacement incarnation from restoring those strings.
        savedFormCopy = null
        clearEditableForm()
        super.onDestroy()
    }

    private fun clearSavedFormCopy() {
        savedFormCopy?.let { copy ->
            listOf(STATE_FORM_OWNER, STATE_FORM_INCARNATION, STATE_CWD, STATE_WORKTREE_BRANCH,
                STATE_WORKTREE_BASE, STATE_WORKTREE_PARENT).forEach { copy.remove(it) }
            copy.putBoolean(STATE_FOLDER_SELECTED, false); copy.putBoolean(STATE_WORKTREE_SELECTED, false)
        }
        savedFormCopy = null
    }

    private fun clearEditableForm() {
        clearSavedFormCopy()
        editableFormContext = null; editableFormScope = null; worktreeFormOwner = null
        folderOverrideSelected = false
        setFolderText("")
        worktreeBranch.setText(""); worktreeBaseRef.setText(""); worktreeParent.setText("")
        recoverySlot.removeAllViews()
    }

    /** Called on Main before publishing a newly admitted actor or its catalog defaults. */
    private fun adoptEditableForm(captured: ScopedReadContext) {
        val previous = editableFormContext
        if (previous != null && (previous.connection !== captured.connection ||
                previous.connectionIncarnation != captured.connectionIncarnation || editableFormScope != scope ||
                repository.isActorRetired(previous))) clearEditableForm()
        editableFormContext = captured; editableFormScope = scope
        pendingEditableForm?.let { saved ->
            pendingEditableForm = null
            val owner = com.lateapex.collie.data.ScopedCustodyOwner(captured.key.origin, captured.key.origin, captured.key.pairedDeviceIdentity)
            if (captured.committedStore && captured.connectionIncarnation == saved.incarnation && owner.storageIdentity == saved.ownerHash) {
                folderOverrideSelected = saved.folderOverride
                setFolderText(saved.cwd)
                worktreeBranch.setText(saved.branch); worktreeBaseRef.setText(saved.base); worktreeParent.setText(saved.parent)
                worktreeToggle.isChecked = saved.worktreeSelected
            } else {
                selectedKey = null; requiresTargetChoice = true
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_SCOPE_HOST, scope.host)
        outState.putString(STATE_SCOPE_SESSION, scope.session)
        outState.putBoolean(STATE_REQUIRES_TARGET_CHOICE, requiresTargetChoice)
        outState.putString(STATE_SELECTED_KEY, selectedKey)
        clearSavedFormCopy()
        val copied = editableFormContext?.takeIf { it.committedStore && it.connectionIncarnation != null &&
            editableFormScope == scope && !repository.isActorRetired(it) }
        val maySave = copied != null
        if (copied != null) {
            val owner = com.lateapex.collie.data.ScopedCustodyOwner(copied.key.origin, copied.key.origin, copied.key.pairedDeviceIdentity)
            outState.putString(STATE_FORM_OWNER, owner.storageIdentity)
            outState.putString(STATE_FORM_INCARNATION, copied.connectionIncarnation)
            savedFormCopy = outState
        }
        outState.putString(STATE_CWD, if (maySave) cwd.text.toString().take(MAX_SAVED_CWD_CHARS) else "")
        outState.putBoolean(STATE_FOLDER_SELECTED, maySave && folderOverrideSelected)
        outState.putBoolean(STATE_WORKTREE_SELECTED, maySave && worktreeToggle.isChecked)
        outState.putString(STATE_WORKTREE_BRANCH, if (maySave) worktreeBranch.text.toString().take(MAX_SAVED_BRANCH_CHARS) else "")
        outState.putString(STATE_WORKTREE_BASE, if (maySave) worktreeBaseRef.text.toString().take(MAX_SAVED_BRANCH_CHARS) else "")
        outState.putString(STATE_WORKTREE_PARENT, if (maySave) worktreeParent.text.toString().take(MAX_SAVED_CWD_CHARS) else "")
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
            isSaveEnabled = false
            hint = getString(R.string.new_folder_manual)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
            filters = arrayOf(InputFilter.LengthFilter(MAX_SAVED_CWD_CHARS))
            setText(savedCwd.take(MAX_SAVED_CWD_CHARS))
            minHeight = dp(48)
            contentDescription = getString(R.string.new_folder_manual_accessibility)
            doAfterTextChanged {
                // Manual folder input is an explicit choice, just like the native picker.
                // Rebuilding target rows/toggling worktree mode must not erase that input.
                if (!applyingFolderPolicy) folderOverrideSelected = true
            }
        }
        root.addView(cwd, rowParams(8))
        worktreeToggle = com.google.android.material.switchmaterial.SwitchMaterial(this).apply {
            text = getString(R.string.new_worktree_toggle)
            isChecked = worktreeSelected
            minHeight = dp(48)
            setOnCheckedChangeListener { _, checked ->
                worktreeSelected = checked
                worktreeOptions.isVisible = checked
                launchButton.text = getString(if (checked) R.string.new_worktree_review else R.string.new_start)
                renderChoices()
                launchButton.isEnabled = canStartSelected()
            }
        }
        root.addView(worktreeToggle, rowParams(8))
        worktreeOptions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; isVisible = worktreeSelected }
        worktreeBranch = EditText(this).apply {
            isSaveEnabled = false
            hint = getString(R.string.new_worktree_branch)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            minHeight = dp(48)
        }
        worktreeBaseRef = EditText(this).apply {
            isSaveEnabled = false
            hint = getString(R.string.new_worktree_base_ref)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            minHeight = dp(48)
        }
        worktreeParent = EditText(this).apply {
            isSaveEnabled = false
            hint = getString(R.string.new_worktree_parent)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
            filters = arrayOf(InputFilter.LengthFilter(MAX_SAVED_CWD_CHARS))
            minHeight = dp(48)
        }
        worktreeOptions.addView(worktreeBranch, rowParams(4))
        worktreeOptions.addView(worktreeBaseRef, rowParams(4))
        worktreeOptions.addView(worktreeParent, rowParams(4))
        root.addView(worktreeOptions, rowParams(2))
        pickerSlot = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        folderPicker = FolderPickerView(this, onSelect = { selected ->
            folderOverrideSelected = true
            setFolderText(selected.orEmpty())
            cwd.setSelection(cwd.text.length)
        }, onStar = ::toggleStar)
        pickerSlot.addView(folderPicker, rowParams())
        root.addView(pickerSlot, rowParams(4))
        runButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.new_run_one_off)
            isAllCaps = false
            minHeight = dp(48)
            setOnClickListener { startActivity(LaunchRecipeActivity.intent(this@NewActivity, scope, LaunchRecipeActivity.MODE_RUN)) }
        }
        recipesButton = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.new_manage_recipes)
            isAllCaps = false
            minHeight = dp(48)
            setOnClickListener { startActivity(LaunchRecipeActivity.intent(this@NewActivity, scope, LaunchRecipeActivity.MODE_RECIPES)) }
        }
        root.addView(runButton, rowParams(6))
        root.addView(recipesButton, rowParams(4))
        root.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            id = R.id.agent_new_existing_pane; text = getString(R.string.agent_control_existing_pane); isAllCaps = false; minHeight = dp(48)
            setOnClickListener { chooseNamedAgentPane() }
        }, rowParams(4))
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
        if (contextKey != null && (contextKey?.connection !== captured.connection || contextKey?.connectionIncarnation != captured.connectionIncarnation)) {
            catalog = emptyList(); inventory = null; folders = null; selectedKey = null; requiresTargetChoice = true; renderChoices()
        }
        adoptEditableForm(captured)
        contextKey = captured
        lifecycleScope.launch {
            val scopedSnapshot = repository.snapshot(scope, captured)
            if (!repository.isCurrent(captured) || operation != uiOperation) {
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
            val launchResult = repository.launchers(scope, captured)
            val folderResult = repository.folders(scope, captured)
            if (!repository.isCurrent(captured) || operation != uiOperation) {
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
            worktreeToggle.isEnabled = isLeadLocalTarget()
            worktreeToggle.contentDescription = getString(if (isLeadLocalTarget()) R.string.new_worktree_toggle else R.string.new_worktree_local_only)
            launchButton.isEnabled = canStartSelected()
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
        if (contextKey != null && (contextKey?.connection !== inventoryContext.connection || contextKey?.connectionIncarnation != inventoryContext.connectionIncarnation)) {
            catalog = emptyList(); inventory = null; folders = null; selectedKey = null; requiresTargetChoice = true; renderChoices()
        }
        adoptEditableForm(inventoryContext)
        contextKey = inventoryContext
        progress.isVisible = true
        lifecycleScope.launch {
            val result = repository.snapshot(Scope(viewAll = true), inventoryContext)
            if (!repository.isCurrent(inventoryContext) || operation != uiOperation) {
                settleStaleOperation(operation)
                return@launch
            }
            if (operation != uiOperation) return@launch
            progress.isVisible = false
            if (result is ApiResult.Success) inventory = result.value
            loadCatalog()
        }
    }

    private fun closeNamedAgentPanePicker() = withMainCustodyAck {
        namedAgentPaneDialog?.dismiss(); namedAgentPaneDialog = null; namedAgentPaneActor = null
    }
    private fun chooseNamedAgentPane() {
        val captured = contextKey ?: return
        if (!repository.isCurrent(captured) || scopeSelectionInProgress || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        val originalScope = Scope(scope.host, scope.session)
        namedAgentPaneRetirement?.close()
        namedAgentPaneRetirement = repository.onReadContextRetired { event -> withRetirementOnMain(event, { namedAgentPaneActor }) { closeNamedAgentPanePicker() } }
        lifecycleScope.launch {
            val config = repository.config(originalScope, captured)
            if (!repository.isCurrent(captured) || contextKey !== captured || scope != originalScope || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return@launch
            if (config !is ApiResult.Success || config.value.mux?.supportsNew("agentAutomation") != true) { status.setText(R.string.agent_control_unavailable); return@launch }
            val snapshot = repository.snapshot(originalScope, captured)
            if (snapshot !is ApiResult.Success || !repository.isCurrent(captured) || contextKey !== captured || scope != originalScope || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return@launch
            val panes = snapshot.value.shellPanes.take(200)
            if (panes.isEmpty()) { status.setText(R.string.agent_control_no_shell_panes); return@launch }
            closeNamedAgentPanePicker()
            val originalDialog = AlertDialog.Builder(this@NewActivity).setTitle(R.string.agent_control_existing_pane)
                .setItems(panes.map { it.paneId }.toTypedArray()) { _, selected ->
                    if (repository.isCurrent(captured) && contextKey === captured && scope == originalScope && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                        startActivity(AgentControlActivity.intent(this@NewActivity, com.lateapex.collie.domain.PaneAddress(originalScope, panes[selected].paneId)))
                    }
                }.setNegativeButton(android.R.string.cancel, null).create()
            namedAgentPaneActor = captured; namedAgentPaneDialog = originalDialog
            originalDialog.setOnDismissListener { if (namedAgentPaneDialog === originalDialog) { namedAgentPaneDialog = null; namedAgentPaneActor = null } }
            if (repository.isCurrent(captured)) originalDialog.show() else closeNamedAgentPanePicker()
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
                setFolderText("")
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
                setFolderText("")
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
                isEnabled = option.available && (!worktreeToggle.isChecked || option.branchSupported && option.ownerIntentSupported)
                isChecked = option.key == selectedKey || selectedKey == null && !requiresTargetChoice && option.available && catalog.firstOrNull(NewLaunchChoice::available)?.key == option.key
                if (isChecked) selectedKey = option.key
                contentDescription = getString(R.string.new_choice_accessibility, option.label)
                setOnClickListener {
                    selectedKey = option.key
                    requiresTargetChoice = false
                    applySelectedFolderPolicy(option)
                    launchButton.isEnabled = canStartSelected()
                }
            }
            choicesView.addView(radio, RadioGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            if (!option.available || worktreeToggle.isChecked && (!option.branchSupported || !option.ownerIntentSupported)) choicesView.addView(TextView(this).apply {
                text = if (worktreeToggle.isChecked && (!option.branchSupported || !option.ownerIntentSupported)) getString(R.string.new_worktree_target_unavailable)
                    else option.reason?.takeIf(String::isNotBlank) ?: getString(R.string.new_choice_unavailable)
                textSize = 12f
                setTextColor(ContextCompat.getColor(this@NewActivity, R.color.collie_muted))
                setPadding(dp(44), 0, 0, dp(6))
            }, rowParams())
        }
        catalog.firstOrNull { it.key == selectedKey }?.let(::applySelectedFolderPolicy)
    }

    private fun setFolderText(value: String) {
        val wasApplying = applyingFolderPolicy
        applyingFolderPolicy = true
        try { cwd.setText(value) } finally { applyingFolderPolicy = wasApplying }
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
            setFolderText(pinned)
            folderPickerSlotVisible(false)
        } else {
            if (!folderOverrideSelected) setFolderText(option.cwd.orEmpty())
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
            val result = repository.starFolder(scope, folder, starred, captured)
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
        if (ownedUnresolvedIntents().isNotEmpty()) {
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
                if (worktreeToggle.isChecked) {
                    startWorktreeForSelected(selected, captured, operation)
                    return@launch
                }
                val enteredPath = cwd.text.toString().trim().takeIf(String::isNotEmpty)
                val path = if (selected.target.starter != null) null else selected.cwd?.takeIf(String::isNotBlank) ?: enteredPath
                val launcher = Launcher(
                    command = selected.command ?: selected.target.command ?: selected.target.harness ?: selected.target.starter ?: getString(R.string.launch_target_shell),
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
                    repository.startLegacyLaunchInitial(scope, command, requestId, path, selected.noPrompts, captured)
                } else {
                    val requestId = UUID.randomUUID().toString()
                    repository.startLaunchIntent(scope, selected.target, requestId, path, selected.noPrompts, captured)
                }
                if (!repository.isCurrent(captured)) {
                    settleStaleOperation(operation)
                    return@launch
                }
                when (result) {
                    is ApiResult.Success -> if (result.value.ok && result.value.pane != null) openCreatedPane(result.value)
                    else status.text = result.value.error ?: getString(R.string.new_start_failed)
                    is ApiResult.Failure -> {
                        val unresolved = ownedUnresolvedIntents().firstOrNull { it.intent.operation == "launch" }
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
                    launchButton.isEnabled = canStartSelected()
                }
            }
        }
    }

    private suspend fun startWorktreeForSelected(selected: NewLaunchChoice, captured: ScopedReadContext, operation: Long) {
        val formOwner = WorktreeFormOwner(captured, operation)
        worktreeFormOwner = formOwner
        val lease = WorktreeUiLease(this, repository, captured) {
            // Dialog copies belong to the lease and are always wiped. The shared form may
            // already belong to a later deliberate operation or replacement credential.
            if (worktreeFormOwner !== formOwner || contextKey !== formOwner.context) return@WorktreeUiLease
            clearEditableForm()
            status.text = ""; recoverySlot.removeAllViews(); running = false; progress.isVisible = false
            launchButton.isEnabled = false
        }
        try {
        if (!lease.current() || operation != uiOperation) return
        val repo = cwd.text.toString().trim()
        val branch = worktreeBranch.text.toString().trim()
        if (repo.isBlank() || branch.isBlank()) {
            status.setText(R.string.new_worktree_repo_branch_required)
            return
        }
        if (!isLeadLocalTarget()) {
            status.setText(R.string.new_worktree_local_only)
            return
        }
        if (!selected.ownerIntentSupported || !selected.branchSupported) {
            status.setText(R.string.new_worktree_receipts_unsupported)
            return
        }
        val ref = worktreeBaseRef.text.toString().trim().takeIf(String::isNotEmpty)
        val parent = worktreeParent.text.toString().trim().takeIf(String::isNotEmpty)
        val base = if (ref == null) WorktreeBase() else WorktreeBase(kind = "ref", ref = ref)
        val folder = if (parent == null) WorktreeFolder() else WorktreeFolder(kind = "parent", parent = parent)
        val launcher = Launcher(
            command = selected.command ?: selected.target.command ?: selected.target.harness ?: selected.target.starter ?: getString(R.string.launch_target_shell),
            label = selected.label, cwd = repo, harness = selected.target.harness,
            noPrompts = selected.noPrompts, target = selected.target,
        )
        if (!confirmLauncher(this, launcher, scope.host ?: captured.connection.origin.value, lease)) return
        if (!lease.current() || operation != uiOperation || !writesAvailable()) return
        val planned = repository.planWorktree(scope, repo, branch, parent, captured)
        if (!lease.current() || operation != uiOperation) {
            settleStaleOperation(operation)
            return
        }
        when (planned) {
            is ApiResult.Success -> {
                when (val presentation = WorktreePlanPresentation.evaluate(
                    planned.value, parent, ref, repository.isCurrent(captured), isLeadLocalTarget(),
                )) {
                    is WorktreePlanPresentation.Result.Refused -> {
                        status.text = presentation.detail ?: getString(when (presentation.reason) {
                            WorktreePlanPresentation.Refusal.INVALID_BRANCH -> R.string.new_worktree_branch_invalid
                            WorktreePlanPresentation.Refusal.FORBIDDEN_PARENT -> R.string.new_worktree_parent_forbidden
                            WorktreePlanPresentation.Refusal.TARGET_EXISTS -> R.string.new_worktree_target_exists
                            else -> R.string.new_worktree_plan_refused
                        })
                        return
                    }
                    is WorktreePlanPresentation.Result.Ready -> showWorktreePlanConfirmation(selected, captured, operation, repo, branch, base, folder, presentation, lease)
                }
            }
            is ApiResult.Failure -> status.text = getString(R.string.new_worktree_plan_error, MainViewModel.describe(resources, planned.error))
            is ApiResult.NotModified -> status.setText(R.string.new_worktree_plan_refused)
        }
        } finally { if (lease.boundCopiesForTesting == 0) lease.finish() }
    }

    /** Local recovery rows stay bound to the accepted original; callbacks never mint an actor. */
    private fun ownedUnresolvedIntents() = contextKey?.let { repository.unresolvedLaunchIntents(scope, it) }.orEmpty()

    private fun isLeadLocalTarget(): Boolean {
        val lead = inventory?.servers.orEmpty().firstOrNull { it.isLead }?.id
        return scope.host == null || lead != null && scope.host == lead
    }

    private fun showWorktreePlanConfirmation(
        selected: NewLaunchChoice,
        captured: ScopedReadContext,
        operation: Long,
        repo: String,
        branch: String,
        base: WorktreeBase,
        folder: WorktreeFolder,
        presentation: WorktreePlanPresentation.Result.Ready,
        lease: WorktreeUiLease,
    ) {
        val detail = getString(R.string.new_worktree_plan_confirmation, selected.label, repo, branch, presentation.baseLabel, presentation.targetPath)
        if (!lease.current() || operation != uiOperation) return
        val requestId = UUID.randomUUID().toString()
        val once = java.util.concurrent.atomic.AtomicBoolean(false)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.new_worktree_review)
            .setMessage(detail)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.new_worktree_create) { clicked, _ ->
                if (!lease.current() || operation != uiOperation || !writesAvailable() || running || !once.compareAndSet(false, true)) return@setPositiveButton
                lease.handoff(clicked as android.app.Dialog)
                running = true
                progress.isVisible = true
                lifecycleScope.launch {
                    try {
                        val result = repository.createWorktreeAtIntent(
                            scope, repo, branch, selected.target, requestId, base, folder,
                            confirmedNoPrompts = selected.noPrompts, expectedContext = captured,
                        )
                        if (!lease.current() || operation != uiOperation) {
                            settleStaleOperation(operation)
                            return@launch
                        }
                        when (result) {
                            is ApiResult.Success -> if (result.value.ok && result.value.pane != null) openCreatedPane(result.value)
                            else status.text = result.value.error ?: getString(R.string.new_worktree_create_failed)
                            is ApiResult.Failure -> {
                                val unresolved = ownedUnresolvedIntents().firstOrNull { it.intent.requestId == requestId }
                                if (unresolved != null) {
                                    status.setText(R.string.new_outcome_unknown)
                                    showRecoveryActions(unresolved.intent.requestId, unresolved.intent.receiptMode, unresolved.intent.operation)
                                } else status.text = getString(R.string.new_worktree_create_error, MainViewModel.describe(resources, result.error))
                            }
                            is ApiResult.NotModified -> status.setText(R.string.new_worktree_create_failed)
                        }
                    } finally {
                        if (operation == uiOperation && lease.current()) {
                            running = false
                            progress.isVisible = false
                            launchButton.isEnabled = canStartSelected()
                        }
                        lease.finish()
                    }
                }
            }.create()
        if (lease.bind(dialog)) dialog.show()
    }

    private fun showUnresolvedIntent() {
        recoverySlot.removeAllViews()
        val unresolved = ownedUnresolvedIntents().firstOrNull { it.intent.operation in setOf("launch", "worktree.createAt", "worktree.create") } ?: return
        status.setText(R.string.new_outcome_unknown)
        launchButton.isEnabled = false
        showRecoveryActions(unresolved.intent.requestId, unresolved.intent.receiptMode, unresolved.intent.operation)
    }

    private fun showRecoveryActions(requestId: String, receiptMode: LaunchIntentReceiptMode = LaunchIntentReceiptMode.ownerBound, operationKind: String = "launch") {
        recoverySlot.removeAllViews()
        val recoveryContext = contextKey ?: return
        val recoveryScope = scope
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
                    val captured = recoveryContext
                    if (contextKey !== captured || scope != recoveryScope || !repository.isCurrent(captured) || running) return@setOnClickListener
                    val operation = ++uiOperation
                    running = true
                    progress.isVisible = true
                    lifecycleScope.launch {
                        try {
                            if (operationKind == "worktree.createAt" || operationKind == "worktree.create") {
                                val result = repository.retryWorktreeIntent(scope, requestId, captured)
                                if (!repository.isCurrent(captured) || operation != uiOperation) {
                                    settleStaleOperation(operation)
                                    return@launch
                                }
                                if (result is ApiResult.Success && result.value.ok && result.value.pane != null) openCreatedPane(result.value)
                                else status.setText(R.string.new_outcome_unknown)
                            } else {
                                val result = repository.retryLaunchIntent(scope, requestId, captured)
                                if (!repository.isCurrent(captured) || operation != uiOperation) {
                                    settleStaleOperation(operation)
                                    return@launch
                                }
                                if (result is ApiResult.Success && result.value.ok && result.value.pane != null) openCreatedPane(result.value)
                                else status.setText(R.string.new_outcome_unknown)
                            }
                        } finally {
                            if (operation == uiOperation) {
                                running = false
                                progress.isVisible = false
                                launchButton.isEnabled = canStartSelected()
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
                if (contextKey !== recoveryContext || scope != recoveryScope || !repository.isCurrent(recoveryContext)) return@setOnClickListener
                val lease = WorktreeUiLease(this@NewActivity, repository, recoveryContext)
                val dialog = androidx.appcompat.app.AlertDialog.Builder(this@NewActivity)
                    .setTitle(R.string.new_abandon_title)
                    .setMessage(R.string.new_abandon_warning)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.new_abandon_confirm) { _, _ ->
                        val captured = recoveryContext
                        if (contextKey !== captured || scope != recoveryScope || !lease.current()) return@setPositiveButton
                        val result = repository.abandonLaunchIntent(scope, requestId)
                        if (result is ApiResult.Success) {
                            status.setText(R.string.new_intent_abandoned)
                            retry?.visibility = View.GONE
                            this.visibility = View.GONE
                        } else status.setText(R.string.new_outcome_unknown)
                    }.create()
                if (lease.bind(dialog)) dialog.show()
            }
        }
        recoverySlot.addView(abandon, rowParams(4))
    }

    private fun openCreatedPane(response: CreateResponse) {
        response.pane?.let(::openCreatedPane)
    }

    private fun openCreatedPane(response: com.lateapex.collie.network.WorktreeOpenResponse) {
        response.pane?.let(::openCreatedPane)
    }

    private fun openCreatedPane(pane: com.lateapex.collie.network.CreatedPane) {
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

    private fun canStartSelected(): Boolean {
        val selected = catalog.firstOrNull { it.key == selectedKey && it.available } ?: return false
        if (requiresTargetChoice || !writesAvailable() || running || ownedUnresolvedIntents().isNotEmpty()) return false
        return !worktreeToggle.isChecked || isLeadLocalTarget() && selected.branchSupported && selected.ownerIntentSupported
    }

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
        private const val STATE_FORM_OWNER = "new.formOwner"
        private const val STATE_FORM_INCARNATION = "new.formIncarnation"
        private const val STATE_CWD = "new.cwd"
        private const val STATE_FOLDER_SELECTED = "new.folderSelected"
        private const val STATE_WORKTREE_SELECTED = "new.worktreeSelected"
        private const val STATE_WORKTREE_BRANCH = "new.worktreeBranch"
        private const val STATE_WORKTREE_BASE = "new.worktreeBase"
        private const val STATE_WORKTREE_PARENT = "new.worktreeParent"
        private const val MAX_SAVED_CWD_CHARS = 4096
        private const val MAX_SAVED_BRANCH_CHARS = 256

        fun intent(context: Context, scope: Scope) = Intent(context, NewActivity::class.java).apply {
            putExtra(EXTRA_HOST, scope.host)
            putExtra(EXTRA_SESSION, scope.session)
        }
    }
}
