package com.lateapex.collie.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.data.AuthorityInvalidation
import com.lateapex.collie.data.CollieRepository
import com.lateapex.collie.data.ScopedReadContext
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.AddedLauncherResponse
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.CreateResponse
import com.lateapex.collie.network.LaunchCheckResponse
import com.lateapex.collie.network.LaunchersResponse
import com.lateapex.collie.network.RecentRun
import java.util.UUID
import java.util.Date
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Native recipe manager and one-off Run surface; all commands remain host-owned HTTP inputs. */
class LaunchRecipeActivity : AppCompatActivity() {
    private lateinit var repository: CollieRepository
    private lateinit var form: LaunchRecipeViewModel
    private lateinit var scope: Scope
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var scopeLabel: TextView
    private lateinit var recipePanel: LinearLayout
    private lateinit var runPanel: LinearLayout
    private lateinit var recipeRows: LinearLayout
    private lateinit var recentRows: LinearLayout
    private lateinit var recoveryRows: LinearLayout
    private lateinit var runInput: EditText
    private lateinit var cwdInput: EditText
    private lateinit var checkButton: MaterialButton
    private lateinit var runButton: MaterialButton
    private lateinit var addRecipeButton: MaterialButton
    private lateinit var addTextButton: MaterialButton
    private lateinit var reloadButton: MaterialButton
    private lateinit var clearRecentButton: MaterialButton
    private var contextKey: ScopedReadContext? = null
    private var catalog: LaunchersResponse? = null
    private var runCheck: LaunchCheckResponse? = null
    private var scopedReady = false
    private var operation = 0L
    private var checkJob: Job? = null
    private var authoritySubscription: AutoCloseable? = null
    private var running = false
    private var mode = MODE_ALL
    private var noticeAfterLoad: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.prepareEdgeToEdgeContent()
        if (!::repository.isInitialized) repository = (application as CollieApplication).container.repository
        form = ViewModelProvider(this)[LaunchRecipeViewModel::class.java]
        scope = Scope(
            host = intent.getStringExtra(EXTRA_HOST)?.takeIf(String::isNotBlank),
            session = intent.getStringExtra(EXTRA_SESSION)?.takeIf(String::isNotBlank),
        )
        mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_ALL
        buildContent()
        window.applySafeContentInsets(root)
        authoritySubscription = repository.onAuthorityInvalidated { reason ->
            runOnUiThread {
                if (reason == AuthorityInvalidation.SCOPE_CHANGED && contextKey?.let(repository::isCurrent) == true) return@runOnUiThread
                // A transport failure freezes writes without retiring the captured actor.
                // Keep the in-flight token so its durable UNKNOWN acknowledgement can render.
                val actorRetired = reason != AuthorityInvalidation.ACCESS_FROZEN || contextKey?.let(repository::isActorRetired) != false
                if (actorRetired) { operation++; running = false }
                scopedReady = false
                runCheck = null
                catalog = null
                renderRecipeRows()
                renderRecentRuns()
                showPendingIntents()
                progress.isVisible = false
                status.setText(R.string.recipes_not_authorized)
                renderActions()
            }
        }
        load()
    }

    override fun onDestroy() {
        checkJob?.cancel()
        authoritySubscription?.close()
        authoritySubscription = null
        super.onDestroy()
    }

    private fun buildContent() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
            setBackgroundColor(ContextCompat.getColor(this@LaunchRecipeActivity, R.color.collie_background))
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        root.addView(TextView(this).apply {
            text = getString(R.string.recipes_title)
            textSize = 22f
            setTextColor(ContextCompat.getColor(this@LaunchRecipeActivity, R.color.collie_foreground))
        }, params())
        scopeLabel = TextView(this).apply {
            text = getString(R.string.new_machine_scope, scope.host ?: getString(R.string.new_this_machine), scope.session ?: getString(R.string.new_default_session))
            textSize = 13f
            setTextIsSelectable(true)
            setTextColor(ContextCompat.getColor(this@LaunchRecipeActivity, R.color.collie_muted))
        }
        root.addView(scopeLabel, params(4))
        status = TextView(this).apply {
            setText(R.string.recipes_loading)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            setTextColor(ContextCompat.getColor(this@LaunchRecipeActivity, R.color.collie_muted))
        }
        root.addView(status, params(8))
        progress = ProgressBar(this).apply { isIndeterminate = true }
        root.addView(progress, params(4))
        reloadButton = button(R.string.recipes_reload) { if (!running && !progress.isVisible) load() }
        root.addView(reloadButton, params(4))

        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val recipesTab = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.recipes_tab); isAllCaps = false; minHeight = dp(48)
            setOnClickListener { showPanel(MODE_RECIPES) }
        }
        val runTab = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = getString(R.string.run_tab); isAllCaps = false; minHeight = dp(48)
            setOnClickListener { showPanel(MODE_RUN) }
        }
        tabs.addView(recipesTab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tabs.addView(runTab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(tabs, params(8))

        recipePanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        addRecipeButton = button(R.string.recipes_add) { chooseRecipe() }
        addTextButton = button(R.string.recipes_add_text) { chooseTypedLauncher() }
        recipePanel.addView(addRecipeButton, params(6))
        recipePanel.addView(addTextButton, params(4))
        recipePanel.addView(TextView(this).apply { setText(R.string.recipes_rows_title); textSize = 17f }, params(12))
        recipeRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        recipePanel.addView(recipeRows, params())
        recoveryRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(recipePanel, params(8))

        runPanel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        runInput = EditText(this).apply {
            hint = getString(R.string.recipes_run_input)
            isSingleLine = true
            inputType = LITERAL_TEXT_INPUT
            filters = arrayOf(android.text.InputFilter.LengthFilter(LaunchRecipeViewModel.MAX_RUN_CHARS))
            setText(form.runText)
            contentDescription = getString(R.string.recipes_run_input)
        }
        cwdInput = EditText(this).apply {
            hint = getString(R.string.recipes_run_cwd)
            isSingleLine = true
            inputType = LITERAL_TEXT_INPUT
            filters = arrayOf(android.text.InputFilter.LengthFilter(LaunchRecipeViewModel.MAX_CWD_CHARS))
            setText(form.cwd)
            contentDescription = getString(R.string.recipes_run_cwd)
        }
        runPanel.addView(runInput, params(4))
        runPanel.addView(cwdInput, params(4))
        checkButton = button(R.string.recipes_check) { scheduleCheck() }
        runButton = button(R.string.recipes_run) { requestRun() }
        runPanel.addView(checkButton, params(6))
        runPanel.addView(runButton, params(4))
        runPanel.addView(TextView(this).apply { setText(R.string.recipes_recent_title); textSize = 17f }, params(12))
        recentRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        runPanel.addView(recentRows, params())
        clearRecentButton = button(R.string.recipes_recent_clear) { confirmClearRecent() }
        runPanel.addView(clearRecentButton, params(4))
        root.addView(runPanel, params(8))
        // Recovery belongs to both entry points, including the Run-only screen.
        root.addView(recoveryRows, params(8))

        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                form.runText = s?.toString().orEmpty()
                runCheck = null
                checkButton.text = getString(R.string.recipes_check)
                renderActions()
                scheduleCheck()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        }
        runInput.addTextChangedListener(watcher)
        cwdInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { form.cwd = s?.toString().orEmpty() }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        showPanel(mode)
    }

    private fun showPanel(next: String) {
        mode = next
        recipePanel.isVisible = next != MODE_RUN
        runPanel.isVisible = next != MODE_RECIPES
    }

    private fun load() {
        val token = ++operation
        running = false
        scopedReady = false
        runCheck = null
        renderActions()
        renderRecipeRows()
        renderRecentRuns()
        showPendingIntents()
        val captured = repository.readContext(scope)
        if (captured == null) {
            catalog = null
            renderRecipeRows()
            renderRecentRuns()
            showPendingIntents()
            status.setText(R.string.recipes_unavailable)
            progress.isVisible = false
            scopedReady = false
            renderActions()
            return
        }
        contextKey = captured
        progress.isVisible = true
        renderActions()
        status.setText(R.string.recipes_loading)
        lifecycleScope.launch {
            val snapshot = repository.snapshot(scope, captured)
            if (token != operation || !repository.isCurrent(captured)) return@launch stale(token)
            if (snapshot !is ApiResult.Success) {
                scopedReady = false
                catalog = null
                progress.isVisible = false
                status.text = when (snapshot) {
                    is ApiResult.Failure -> getString(R.string.recipes_check_failed, MainViewModel.describe(resources, snapshot.error))
                    is ApiResult.NotModified -> getString(R.string.recipes_unavailable)
                    is ApiResult.Success -> error("unreachable")
                }
                renderRecipeRows()
                renderRecentRuns()
                showPendingIntents()
                renderActions()
                return@launch
            }
            scopedReady = true
            val result = repository.launchers(scope, captured)
            if (token != operation || !repository.isCurrent(captured)) return@launch stale(token)
            progress.isVisible = false
            when (result) {
                is ApiResult.Success -> {
                    catalog = result.value
                    status.setText(if (result.value.adding == null) R.string.recipes_old_server else R.string.recipes_title)
                    renderRecipeRows()
                    renderRecentRuns()
                    showPendingIntents()
                    if (form.runText.isNotBlank()) scheduleCheck()
                }
                is ApiResult.Failure -> {
                    catalog = null
                    status.text = getString(R.string.recipes_check_failed, MainViewModel.describe(resources, result.error))
                    renderRecipeRows()
                    renderRecentRuns()
                    showPendingIntents()
                }
                is ApiResult.NotModified -> {
                    catalog = null
                    status.setText(R.string.recipes_unavailable)
                    renderRecipeRows()
                    renderRecentRuns()
                    showPendingIntents()
                }
            }
            noticeAfterLoad?.let { status.text = it; noticeAfterLoad = null }
            renderActions()
        }
    }

    private fun stale(token: Long) {
        if (token != operation) return
        scopedReady = false
        running = false
        runCheck = null
        catalog = null
        progress.isVisible = false
        status.setText(R.string.recipes_not_authorized)
        renderRecipeRows()
        renderRecentRuns()
        showPendingIntents()
        renderActions()
    }

    private fun renderActions() {
        reloadButton.isEnabled = !running && !progress.isVisible
        val adding = catalog?.adding
        val writable = scopedReady && repository.writesAllowed(scope) && !running
        addRecipeButton.isEnabled = writable && LaunchRecipeModel.mayAddRecipe(adding)
        addTextButton.isEnabled = writable && LaunchRecipeModel.mayAddFreeText(adding)
        runButton.isEnabled = writable && LaunchRecipeModel.mayRun(adding) && runCheck?.let { it.problem == null } == true && form.checkFor(runInput.text.toString(), scopeKey()) != null
        checkButton.isEnabled = scopedReady && runInput.text.isNotBlank() && !running
        clearRecentButton.isEnabled = writable
    }

    private fun renderRecipeRows() {
        recipeRows.removeAllViews()
        val data = catalog ?: return
        val adding = data.adding
        if (data.launchers.isEmpty() && adding?.off.isNullOrEmpty()) {
            recipeRows.addView(label(getString(R.string.new_empty)), params())
        }
        data.launchers.forEach { row ->
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
            card.addView(label(row.label), params())
            card.addView(label(row.command), params(2))
            if (row.source == "added" && !row.id.isNullOrBlank()) {
            card.addView(label(getString(R.string.recipes_row_attribution, row.addedBy ?: getString(R.string.recipes_unknown_device), row.addedAs ?: getString(R.string.recipes_kind_command))), params(2))
                val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                actions.addView(button(R.string.recipes_rename) { promptRename(row.id, row.label) }.apply { isEnabled = canWrite() && !running }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                actions.addView(button(R.string.recipes_remove) { confirmRemoveLauncher(row.id, row.label) }.apply { isEnabled = canWrite() && !running }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                card.addView(actions, params(4))
            } else if (row.source != "added") {
                card.addView(label(getString(R.string.recipes_operator_row)), params(2))
            }
            recipeRows.addView(card, params(4))
        }
        adding?.off.orEmpty().forEach { off ->
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
            card.addView(label(off.label), params())
            card.addView(label(getString(R.string.recipes_switch_off, off.reason)), params(2))
            card.addView(button(R.string.recipes_remove) { confirmRemoveLauncher(off.id, off.label) }.apply { isEnabled = canWrite() && !running }, params(4))
            recipeRows.addView(card, params(4))
        }
        if (adding?.adds == false) recipeRows.addView(label(getString(R.string.recipes_switch_off, "adds_off")), params(4))
        if (adding != null && adding.count >= adding.max) recipeRows.addView(label(getString(R.string.recipes_limit, adding.max)), params(4))
    }

    private fun renderRecentRuns() {
        recentRows.removeAllViews()
        val recent = catalog?.recentRuns
        if (recent == null) {
            recentRows.addView(label(getString(R.string.recipes_recent_unavailable)), params())
            return
        }
        if (recent.isEmpty()) recentRows.addView(label(getString(R.string.recipes_recent_empty)), params())
        recent.forEach { item ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(8)) }
            row.addView(label(item.line), params())
            val happened = DateFormat.getMediumDateFormat(this).format(Date(item.at)) + " · " + DateFormat.getTimeFormat(this).format(Date(item.at))
            row.addView(label("${item.cwd ?: catalog?.home.orEmpty()} · $happened"), params(2))
            if (item.noPrompts) row.addView(label(getString(R.string.recipes_run_no_prompts_warning)), params(2))
            val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            actions.addView(button(R.string.recipes_replay_fill) {
                runInput.setText(item.line)
                cwdInput.setText(item.cwd.orEmpty())
                showPanel(MODE_RUN)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            actions.addView(button(R.string.recipes_recent_remove) { confirmRemoveRecent(item) }.apply { isEnabled = canWrite() && !running }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(actions, params(4))
            if (!item.available) row.addView(label(getString(R.string.recipes_run_disabled)), params(2))
            recentRows.addView(row, params(4))
        }
    }

    private fun showPendingIntents() {
        recoveryRows.removeAllViews()
        repository.unresolvedLaunchIntents(scope).forEach { saved ->
            when (saved.intent.operation) {
                "launch.run" -> {
                    recoveryRows.addView(label(getString(R.string.recipes_run_unknown)), params())
                    recoveryRows.addView(button(R.string.recipes_retry_run) { confirmRetryRun(saved.intent.requestId, saved.intent.canonicalBody) }.apply { isEnabled = canWrite() }, params(4))
                }
                "launcher.add" -> {
                    recoveryRows.addView(label(getString(R.string.recipes_add_unknown)), params())
                    recoveryRows.addView(button(R.string.recipes_retry_same_add) { retryAdd(saved.intent.requestId) }.apply { isEnabled = canWrite() }, params(4))
                }
            }
        }
    }

    private fun scheduleCheck() {
        checkJob?.cancel()
        runCheck = null
        if (runInput.text.isBlank() || catalog == null) { renderActions(); return }
        val asked = runInput.text.toString()
        val captured = contextKey ?: return
        checkJob = lifecycleScope.launch {
            delay(CHECK_DEBOUNCE_MS)
            if (!repository.isCurrent(captured) || asked != runInput.text.toString()) return@launch
            checkButton.setText(R.string.recipes_checking)
            when (val result = repository.checkRun(scope, asked, captured)) {
                is ApiResult.Success -> if (repository.isCurrent(captured) && asked == runInput.text.toString()) {
                    runCheck = result.value
                    form.recordCheck(asked, scopeKey(), result.value)
                    status.text = result.value.problem?.let(::problemText) ?: getString(R.string.recipes_check_ok)
                }
                is ApiResult.Failure -> if (repository.isCurrent(captured)) status.text = getString(R.string.recipes_check_failed, MainViewModel.describe(resources, result.error))
                is ApiResult.NotModified -> if (repository.isCurrent(captured)) status.text = getString(R.string.recipes_check_failed, "no validation response")
            }
            checkButton.text = getString(R.string.recipes_check)
            renderActions()
        }
    }

    private fun problemText(problem: String): String = when (LaunchRecipeModel.problemMessage(problem)) {
        "empty" -> getString(R.string.recipes_check_problem_empty)
        "too_long" -> getString(R.string.recipes_check_problem_too_long)
        else -> getString(R.string.recipes_check_problem_forbidden_character)
    }

    private fun requestRun() {
        val captured = contextKey ?: return
        val exactLine = runInput.text.toString()
        val exactCwd = cwdInput.text.toString().takeIf(String::isNotBlank)
        val checked = form.checkFor(exactLine, scopeKey()) ?: return
        if (checked.problem != null || !LaunchRecipeModel.mayRun(catalog?.adding) || !repository.isCurrent(captured) || !repository.writesAllowed(scope)) {
            status.setText(R.string.recipes_run_disabled)
            return
        }
        val warning = if (checked.noPrompts) "\n\n${getString(R.string.recipes_run_no_prompts_warning)}" else ""
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.recipes_run_confirm_title, scope.host ?: getString(R.string.new_this_machine)))
            .setMessage(getString(R.string.recipes_run_confirm_body, scope.host ?: getString(R.string.new_this_machine), exactLine, exactCwd ?: catalog?.home.orEmpty()) + warning)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(if (checked.noPrompts) R.string.recipes_no_prompt_ack else R.string.recipes_run) { _, _ ->
                if (!repository.isCurrent(captured) || exactLine != runInput.text.toString() || exactCwd != cwdInput.text.toString().takeIf(String::isNotBlank)) {
                    status.setText(R.string.recipes_not_authorized); return@setPositiveButton
                }
                val requestId = UUID.randomUUID().toString()
                submitRun(captured, exactLine, exactCwd, requestId, checked.noPrompts)
            }.show()
    }

    private fun submitRun(captured: ScopedReadContext, line: String, folder: String?, requestId: String, noPrompts: Boolean) {
        if (running) return
        val token = ++operation
        running = true; progress.isVisible = true; renderActions()
        lifecycleScope.launch {
            try {
                val result = repository.startRunIntent(scope, line, requestId, folder, noPrompts, captured)
                if (token != operation || repository.isActorRetired(captured)) return@launch stale(token)
                when (result) {
                is ApiResult.Success -> if (result.value.ok && result.value.pane != null) {
                    openPane(result.value)
                } else if (repository.unresolvedLaunchIntents(scope).any { it.intent.operation == "launch.run" }) {
                    status.setText(R.string.recipes_run_unknown)
                    showPendingIntents()
                } else {
                    status.text = result.value.error ?: getString(R.string.recipes_run_failed, "the server refused the command")
                }
                    is ApiResult.Failure -> {
                        val unresolved = repository.unresolvedLaunchIntents(scope).firstOrNull { it.intent.operation == "launch.run" }
                        if (unresolved != null) { status.setText(R.string.recipes_run_unknown); showPendingIntents() }
                        else status.text = getString(R.string.recipes_run_failed, MainViewModel.describe(resources, result.error))
                    }
                    is ApiResult.NotModified -> status.text = getString(R.string.recipes_run_failed, getString(R.string.recipes_mutation_unknown))
                }
            } finally {
                if (token == operation) { running = false; progress.isVisible = false; renderActions(); showPendingIntents() }
            }
        }
    }

    private fun confirmRetryRun(requestId: String, canonicalBody: String) {
        val captured = contextKey ?: return
        val run = runCatching { Json.parseToJsonElement(canonicalBody).jsonObject["run"]?.jsonPrimitive?.content }.getOrNull().orEmpty()
        AlertDialog.Builder(this).setTitle(R.string.recipes_run_unknown).setMessage(getString(R.string.recipes_retry_run_confirm) + "\n\n" + run)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.recipes_retry_run) { _, _ ->
                if (repository.isCurrent(captured) && repository.writesAllowed(scope)) retryRun(captured, requestId)
            }.show()
    }

    private fun retryRun(captured: ScopedReadContext, requestId: String) {
        if (running) return
        val token = ++operation
        running = true; progress.isVisible = true; renderActions()
        lifecycleScope.launch {
            try {
                when (val result = repository.retryLaunchIntent(scope, requestId, captured)) {
                    is ApiResult.Success -> if (repository.isCurrent(captured) && result.value.ok && result.value.pane != null) openPane(result.value) else status.setText(R.string.recipes_run_unknown)
                    is ApiResult.Failure -> if (repository.isCurrent(captured)) status.setText(R.string.recipes_run_unknown)
                    is ApiResult.NotModified -> if (repository.isCurrent(captured)) status.setText(R.string.recipes_run_unknown)
                }
            } finally {
                if (token == operation) { running = false; progress.isVisible = false; renderActions(); showPendingIntents() }
            }
        }
    }

    private fun openPane(response: CreateResponse) {
        val pane = response.pane ?: return
        startActivity(Intent(this, PaneActivity::class.java).apply {
            putExtra(PaneActivity.EXTRA_PANE_ID, pane.paneId)
            putExtra(PaneActivity.EXTRA_HOST, scope.host)
            putExtra(PaneActivity.EXTRA_SESSION, scope.session)
            putExtra(PaneActivity.EXTRA_TITLE, pane.workspaceLabel)
            putExtra(PaneActivity.EXTRA_META, getString(R.string.pane_meta, getString(R.string.launch_recipe_agent), pane.workspaceLabel))
            putExtra(PaneActivity.EXTRA_AGENT, getString(R.string.launch_recipe_agent))
            putExtra(PaneActivity.EXTRA_CWD, pane.cwd)
            putExtra(PaneActivity.EXTRA_TAB_LABEL, pane.tabId)
            putExtra(PaneActivity.EXTRA_STATUS, "unknown")
            putExtra(PaneActivity.EXTRA_FRESH_PANE, true)
        })
        finish()
    }

    private fun chooseRecipe() {
        val adding = catalog?.adding ?: return
        if (!canWrite() || !LaunchRecipeModel.mayAddRecipe(adding)) return
        val recipes = adding.recipes
        if (recipes.isEmpty()) { status.setText(R.string.recipes_old_server); return }
        AlertDialog.Builder(this).setTitle(R.string.recipes_add).setItems(recipes.map { "${it.label} · ${it.binary}" }.toTypedArray()) { _, index ->
            chooseRecipeOptions(recipes[index])
        }.setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun chooseRecipeOptions(recipe: com.lateapex.collie.network.RecipeWire) {
        if (recipe.options.isEmpty()) { submitRecipe(recipe, emptyList()); return }
        val checked = BooleanArray(recipe.options.size)
        AlertDialog.Builder(this).setTitle(recipe.label).setMultiChoiceItems(recipe.options.map { it.label }.toTypedArray(), checked) { _, which, value -> checked[which] = value }
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val ids = recipe.options.indices.filter { checked[it] }.map { recipe.options[it].id }
                if (!LaunchRecipeModel.optionsAreValid(recipe.options, ids)) {
                    status.setText(R.string.recipes_exclusive_options)
                    return@setPositiveButton
                }
                submitRecipe(recipe, ids)
            }.show()
    }

    private fun submitRecipe(recipe: com.lateapex.collie.network.RecipeWire, optionIds: List<String>) {
        val noPrompts = recipe.options.any { it.id in optionIds && it.noPrompts }
        val body = buildJsonObject {
            put("requestId", UUID.randomUUID().toString())
            putJsonObject("recipe") { put("harness", recipe.harness); putJsonArray("options") { optionIds.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } } }
        }.toString()
        val action = { addLauncher(body, confirmedNoPrompts = noPrompts, expectedRecipe = recipe) }
        if (noPrompts) AlertDialog.Builder(this).setTitle(R.string.recipes_no_prompts_confirm_title)
            .setMessage(getString(R.string.recipes_no_prompts_confirm_body, scope.host ?: getString(R.string.new_this_machine), recipe.binary + " " + recipe.options.filter { it.id in optionIds }.joinToString(" ") { it.args }))
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.recipes_add_confirm) { _, _ -> action() }.show()
        else action()
    }

    private fun chooseTypedLauncher() {
        val adding = catalog?.adding ?: return
        if (!canWrite() || !LaunchRecipeModel.mayAddFreeText(adding)) return
        val kinds = buildList {
            if (adding.recipes.isNotEmpty()) add(getString(R.string.recipes_kind_agent))
            add(getString(R.string.recipes_kind_command))
        }.toTypedArray()
        AlertDialog.Builder(this).setTitle(R.string.recipes_add_text).setItems(kinds) { _, which ->
            val agentAvailable = adding.recipes.isNotEmpty()
            typedLauncherDialog(if (agentAvailable && which == 0) "agent" else "command")
        }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun typedLauncherDialog(kind: String) {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val command = EditText(this).apply {
            hint = getString(R.string.recipes_command); isSingleLine = true
            inputType = LITERAL_TEXT_INPUT
            filters = arrayOf(android.text.InputFilter.LengthFilter(200))
        }
        val labelField = EditText(this).apply { hint = getString(R.string.recipes_add_label); isSingleLine = true; filters = arrayOf(android.text.InputFilter.LengthFilter(60)) }
        val noPrompts = CheckBox(this).apply { text = getString(R.string.recipes_no_prompts) }
        val recipeCatalog = catalog?.adding?.recipes.orEmpty()
        if (kind == "agent" && recipeCatalog.isEmpty()) { status.setText(R.string.recipes_old_server); return }
        val harness = Spinner(this).apply {
            adapter = ArrayAdapter(this@LaunchRecipeActivity, android.R.layout.simple_spinner_dropdown_item, recipeCatalog.map { it.harness })
            isVisible = kind == "agent"
            prompt = getString(R.string.recipes_harness)
            contentDescription = getString(R.string.recipes_harness)
        }
        layout.addView(command, params()); layout.addView(labelField, params(4)); if (kind == "agent") layout.addView(harness, params(4)); layout.addView(noPrompts, params(4))
        AlertDialog.Builder(this).setTitle(if (kind == "agent") R.string.recipes_kind_agent else R.string.recipes_kind_command).setView(layout)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.recipes_add_confirm, null).create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val text = command.text.toString()
                        if (text.isBlank()) { command.error = getString(R.string.recipes_check_problem_empty); return@setOnClickListener }
                        val request = buildJsonObject {
                            put("requestId", UUID.randomUUID().toString()); put("text", text); put("kind", kind); put("noPrompts", noPrompts.isChecked)
                            labelField.text.toString().trim().takeIf(String::isNotEmpty)?.let { put("label", it) }
                            if (kind == "agent") recipeCatalog.getOrNull(harness.selectedItemPosition)?.let { put("harness", it.harness) }
                        }.toString()
                        val send = { addLauncher(request, confirmedNoPrompts = noPrompts.isChecked) }
                        if (noPrompts.isChecked) AlertDialog.Builder(this@LaunchRecipeActivity).setTitle(R.string.recipes_no_prompts_confirm_title)
                            .setMessage(getString(R.string.recipes_no_prompts_confirm_body, scope.host ?: getString(R.string.new_this_machine), text))
                            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.recipes_add_confirm) { _, _ -> send() }.show()
                        else send()
                        dialog.dismiss()
                    }
                }
                dialog.show()
            }
    }

    private fun addLauncher(canonicalBody: String, confirmedNoPrompts: Boolean = false, expectedRecipe: com.lateapex.collie.network.RecipeWire? = null) {
        val captured = contextKey ?: return
        if (!repository.isCurrent(captured) || !canWrite()) return
        val id = runCatching { Json.parseToJsonElement(canonicalBody).jsonObject.getValue("requestId").jsonPrimitive.content }.getOrNull() ?: return
        performMutation { captured ->
            when (val result = repository.addLauncherIntent(
                scope, id, canonicalBody, expectedContext = captured,
                confirmedNoPrompts = confirmedNoPrompts, expectedRecipe = expectedRecipe,
            )) {
                is ApiResult.Success -> if (result.value.ok) {
                    status.setText(if (result.value.replayed == true) R.string.recipes_add_confirmed else R.string.recipes_title)
                    load()
                } else if (repository.unresolvedLaunchIntents(scope).any { it.intent.requestId == id }) {
                    status.setText(R.string.recipes_add_unknown)
                    showPendingIntents()
                } else {
                    status.text = result.value.error ?: getString(R.string.recipes_add_failed, "server refusal")
                }
                is ApiResult.Failure -> {
                    val unknown = repository.unresolvedLaunchIntents(scope).any { it.intent.requestId == id && it.intent.operation == "launcher.add" }
                    status.text = if (unknown) getString(R.string.recipes_add_unknown) else getString(R.string.recipes_add_failed, MainViewModel.describe(resources, result.error))
                    if (unknown) showPendingIntents()
                }
                is ApiResult.NotModified -> status.setText(R.string.recipes_mutation_unknown)
            }
        }
    }

    private fun retryAdd(requestId: String) {
        val captured = contextKey ?: return
        if (!repository.isCurrent(captured) || !canWrite()) return
        performMutation {
            when (val result = repository.retryAddedLauncherIntent(scope, requestId, captured)) {
                is ApiResult.Success -> if (result.value.ok) load() else if (repository.unresolvedLaunchIntents(scope).any { it.intent.requestId == requestId }) {
                    status.setText(R.string.recipes_mutation_unknown)
                    showPendingIntents()
                } else status.text = result.value.error ?: getString(R.string.recipes_mutation_unknown)
                is ApiResult.Failure -> status.text = getString(R.string.recipes_mutation_unknown)
                is ApiResult.NotModified -> status.setText(R.string.recipes_mutation_unknown)
            }
        }
    }

    private fun promptRename(id: String, oldLabel: String) {
        if (!canWrite()) return
        val input = EditText(this).apply { setText(oldLabel); setSelection(text.length); filters = arrayOf(android.text.InputFilter.LengthFilter(60)) }
        AlertDialog.Builder(this).setTitle(R.string.recipes_rename_title).setView(input).setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.recipes_rename, null).create().also { dialog ->
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val label = input.text.toString()
                        if (label.isBlank()) { input.error = getString(R.string.recipes_check_problem_empty); return@setOnClickListener }
                        dialog.dismiss(); renameLauncher(id, label)
                    }
                }; dialog.show()
            }
    }

    private fun renameLauncher(id: String, label: String) = performMutation { captured ->
        when (val result = repository.renameAddedLauncher(scope, id, label, captured)) {
            is ApiResult.Success -> if (result.value.ok) load() else status.text = result.value.error ?: getString(R.string.recipes_mutation_unknown)
            is ApiResult.Failure -> { noticeAfterLoad = getString(R.string.recipes_mutation_unknown); load() }
            is ApiResult.NotModified -> status.setText(R.string.recipes_mutation_unknown)
        }
    }

    private fun confirmRemoveLauncher(id: String, label: String) {
        if (!canWrite()) return
        AlertDialog.Builder(this).setTitle(R.string.recipes_remove_title).setMessage("$label\n\n${getString(R.string.recipes_remove_body)}")
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.recipes_remove_confirm) { _, _ ->
                performMutation { captured ->
                    when (val result = repository.removeAddedLauncher(scope, id, captured)) {
                        is ApiResult.Success -> if (result.value.ok && (result.value.removed ?: 0) == 1) load() else status.text = result.value.error ?: getString(R.string.recipes_mutation_unknown)
                        is ApiResult.Failure -> { noticeAfterLoad = getString(R.string.recipes_mutation_unknown); load() }
                        is ApiResult.NotModified -> status.setText(R.string.recipes_mutation_unknown)
                    }
                }
            }.show()
    }

    private fun confirmRemoveRecent(item: RecentRun) {
        if (!canWrite()) return
        AlertDialog.Builder(this).setTitle(R.string.recipes_recent_remove_title)
            .setMessage(getString(R.string.recipes_recent_remove_body, scope.host ?: getString(R.string.new_this_machine), item.line))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.recipes_recent_remove) { _, _ -> removeRecent(item) }
            .show()
    }

    private fun removeRecent(item: RecentRun) {
        if (!canWrite()) return
        performMutation { captured ->
            when (val result = repository.removeRecentRun(scope, item.line, captured)) {
                is ApiResult.Success -> if (result.value.ok && (result.value.removed ?: 0) >= 0) load() else status.text = result.value.error ?: getString(R.string.recipes_recent_mutation_failed, "server refusal")
                is ApiResult.Failure -> { noticeAfterLoad = getString(R.string.recipes_recent_mutation_failed, MainViewModel.describe(resources, result.error)); load() }
                is ApiResult.NotModified -> status.setText(R.string.recipes_mutation_unknown)
            }
        }
    }

    private fun confirmClearRecent() {
        if (!canWrite()) return
        AlertDialog.Builder(this).setTitle(R.string.recipes_recent_clear_title).setMessage(R.string.recipes_recent_clear_body)
            .setNegativeButton(android.R.string.cancel, null).setPositiveButton(R.string.recipes_recent_clear) { _, _ ->
                performMutation { captured ->
                    when (val result = repository.clearRecentRuns(scope, captured)) {
                        is ApiResult.Success -> if (result.value.ok) load() else status.text = result.value.error ?: getString(R.string.recipes_recent_mutation_failed, "server refusal")
                        is ApiResult.Failure -> { noticeAfterLoad = getString(R.string.recipes_recent_mutation_failed, MainViewModel.describe(resources, result.error)); load() }
                        is ApiResult.NotModified -> status.setText(R.string.recipes_mutation_unknown)
                    }
                }
            }.show()
    }

    private fun performMutation(block: suspend (ScopedReadContext) -> Unit) {
        if (!canWrite() || running) return
        val captured = contextKey ?: return
        val token = ++operation
        running = true; progress.isVisible = true; renderActions()
        lifecycleScope.launch {
            try {
                if (repository.isCurrent(captured)) block(captured) else stale(token)
            } finally {
                if (token == operation) { running = false; progress.isVisible = false; renderActions() }
            }
        }
    }

    private fun canWrite(): Boolean = scopedReady && repository.writesAllowed(scope) && catalog?.adding != null
    private fun scopeKey(): String = "${scope.host.orEmpty()}\u0000${scope.session.orEmpty()}\u0000${contextKey?.key?.connectionGeneration ?: -1L}"
    private fun button(text: Int, action: () -> Unit) = MaterialButton(this).apply { setText(text); isAllCaps = false; minHeight = dp(48); setOnClickListener { action() } }
    private fun label(text: String) = TextView(this).apply { this.text = text; setTextColor(ContextCompat.getColor(this@LaunchRecipeActivity, R.color.collie_foreground)); textSize = 14f }
    private fun params(top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val MODE_ALL = "all"
        const val MODE_RECIPES = "recipes"
        const val MODE_RUN = "run"
        private const val EXTRA_HOST = "host"
        private const val EXTRA_SESSION = "session"
        private const val EXTRA_MODE = "mode"
        private const val CHECK_DEBOUNCE_MS = 350L
        // Visible-password variation suppresses keyboard learning while keeping punctuation literal.
        private const val LITERAL_TEXT_INPUT = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS

        fun intent(context: Context, scope: Scope, mode: String = MODE_ALL) = Intent(context, LaunchRecipeActivity::class.java).apply {
            putExtra(EXTRA_HOST, scope.host); putExtra(EXTRA_SESSION, scope.session); putExtra(EXTRA_MODE, mode)
        }
    }
}
