package com.lateapex.collie.ui

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.format.DateFormat
import android.text.InputType
import android.text.style.TypefaceSpan
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.BuildConfig
import com.lateapex.collie.R
import com.lateapex.collie.data.ScopedReadContext
import com.lateapex.collie.databinding.ActivityMainBinding
import com.lateapex.collie.network.PaneSummary
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.CreatedPane
import com.lateapex.collie.network.Launcher
import com.lateapex.collie.network.ServerSummary
import com.lateapex.collie.network.SessionSummary
import com.lateapex.collie.network.WorkspaceSummary
import com.lateapex.collie.network.StartTarget
import com.lateapex.collie.network.LauncherItem
import com.lateapex.collie.network.WorktreeBase
import com.lateapex.collie.network.WorktreeFolder
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.Scope
import java.util.UUID
import java.util.Locale
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private var authorityRegistration: AutoCloseable? = null
    private val repository get() = (application as CollieApplication).container.repository
    private lateinit var binding: ActivityMainBinding
    private lateinit var viewModel: MainViewModel
    private val nativePreferences by lazy { NativePreferences(this) }
    private var spaceQuery = ""
    private val launchingCommands = mutableMapOf<String, Pair<String, ScopedReadContext>>()
    private var retryingLaunchIntent = false
    private var retryingLaunchActor: String? = null
    private var retryingLaunchContext: ScopedReadContext? = null
    private var structuralBusy = false
    private var structuralActor: String? = null
    private val shellHandler = Handler(Looper.getMainLooper())
    private val connectionHealth = DashboardConnectionHealth(System.currentTimeMillis())
    private var lastHealthStamp: Long? = null
    private var latestShellState = MainUiState()
    private var idlePaused = false
    private var catchingUp = false
    private var catchUpSettledBaseline = 0L
    private var idleDeadline = 0L
    private var visible = false
    private val shellTick = Runnable { renderShell(latestShellState) }
    private val idleTick = Runnable { enterIdlePause() }
    private val catchUpCap = Runnable { finishCatchUp() }
    @androidx.annotation.StringRes
    private var structuralProgressRes = R.string.space_creating
    private val adapter: DashboardAdapter = DashboardAdapter(
        onPane = ::openPane,
        onSpace = ::openSpace,
        onRecentOpen = { open ->
            nativePreferences.dashboardRecentOpen = open
            render(viewModel.state.value)
        },
        onRecentSort = {
            nativePreferences.dashboardRecentNewest = !nativePreferences.dashboardRecentNewest
            render(viewModel.state.value)
        },
        onSpacesOpen = { open ->
            nativePreferences.dashboardSpacesOpen = open
            render(viewModel.state.value)
        },
        onSpaceQuery = { query ->
            spaceQuery = query
            render(viewModel.state.value)
        },
        onNewSpace = ::showNewSpace,
        onLauncher = ::launch,
        onLaunchOpen = { open ->
            nativePreferences.dashboardLaunchOpen = open
            render(viewModel.state.value)
        },
        onPack = { startActivity(Intent(this, PackActivity::class.java)) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.prepareEdgeToEdgeContent()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applyAppTypeface()
        window.applySafeContentInsets(binding.root)
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        binding.originInput.setText(BuildConfig.DEFAULT_ORIGIN)
        binding.deviceLabelInput.setText(defaultDeviceLabel())
        binding.paneList.layoutManager = LinearLayoutManager(this)
        binding.paneList.adapter = adapter
        binding.connectButton.setOnClickListener {
            viewModel.connectReadOnly(binding.originInput.text.toString(), binding.deviceLabelInput.text.toString())
        }
        binding.pairButton.setOnClickListener {
            viewModel.pair(
                binding.originInput.text.toString(),
                binding.deviceLabelInput.text.toString(),
                binding.pairingCodeInput.text.toString(),
            )
        }
        binding.swipeRefresh.setOnRefreshListener(viewModel::refresh)
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.dashboardChangesButton.setOnClickListener { showChangesWorkspacePicker() }
        binding.dashboardOrganizeButton.setOnClickListener {
            if (viewModel.currentScope.viewAll) showSessionSwitcher()
            else startActivity(OrganizeSpacesActivity.intent(this, viewModel.currentScope))
        }
        binding.newWorkflowButton.setOnClickListener {
            startActivity(NewActivity.intent(this, viewModel.currentScope))
        }
        binding.hostSwitcher.setOnClickListener { showHostSwitcher() }
        binding.sessionSwitcher.setOnClickListener { showSessionSwitcher() }
        binding.setupSettingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.dashboardConnectionRetry.setOnClickListener {
            if (viewModel.state.value.authError) openProxySignIn() else viewModel.retryConnection()
        }
        binding.dashboardConnectionReload.setOnClickListener { recreate() }
        binding.dashboardIdleResume.setOnClickListener { beginCatchUp() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (viewModel.navigateBackScope()) return
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        })
        if (savedInstanceState == null) openDeepLink(intent.data)

        authorityRegistration = repository.onAuthorityInvalidated {
            structuralBusy = false
            launchingCommands.clear()
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect(::render)
            }
        }
    }

    private fun openDeepLink(uri: Uri?) {
        val origin = (application as com.lateapex.collie.CollieApplication)
            .container.repository.connection.value?.origin?.value ?: return
        when (val target = CollieDeepLink.parse(uri, com.lateapex.collie.domain.CollieOrigin(origin))) {
            null -> Unit
            is CollieDeepLink.Home -> viewModel.selectScope(target.scope, remember = false)
            is CollieDeepLink.Pack -> {
                viewModel.selectScope(target.scope.copy(viewAll = false), remember = false)
                startActivity(Intent(this, PackActivity::class.java))
            }
            is CollieDeepLink.Settings -> {
                viewModel.selectScope(target.scope.copy(viewAll = false), remember = false)
                startActivity(
                    if (target.updates) {
                        Intent(this, UpdatesActivity::class.java)
                    } else {
                        SettingsActivity.intent(
                            this,
                            pairCode = target.pairCode,
                            focusDevices = target.focusDevices,
                        )
                    },
                )
            }
            is CollieDeepLink.Pane -> {
                viewModel.selectScope(target.scope, remember = false)
                val address = PaneAddress(target.scope, target.paneId)
                if (target.history) {
                    startActivity(Intent(this, PaneActivity::class.java).apply {
                        putExtra(PaneActivity.EXTRA_PANE_ID, target.paneId)
                        putExtra(PaneActivity.EXTRA_HOST, target.scope.host)
                        putExtra(PaneActivity.EXTRA_SESSION, target.scope.session)
                        putExtra(PaneActivity.EXTRA_TITLE, target.paneId)
                    })
                    startActivity(HistoryActivity.intent(this, address, target.paneId, null))
                } else {
                    startActivity(Intent(this, PaneActivity::class.java).apply {
                        putExtra(PaneActivity.EXTRA_PANE_ID, target.paneId)
                        putExtra(PaneActivity.EXTRA_HOST, target.scope.host)
                        putExtra(PaneActivity.EXTRA_SESSION, target.scope.session)
                        putExtra(PaneActivity.EXTRA_TITLE, target.paneId)
                    })
                }
            }
            is CollieDeepLink.Space -> {
                viewModel.selectScope(target.scope, remember = false)
                startActivity(SpaceActivity.intent(
                    this,
                    WorkspaceSummary(
                        workspaceId = target.workspaceId,
                        number = 0,
                        label = target.workspaceId,
                        focused = false,
                        activeTabId = "",
                        tabCount = 0,
                        paneCount = 0,
                        host = target.scope.host,
                    ),
                    target.scope.session,
                ))
            }
        }
    }

    override fun onDestroy() {
        authorityRegistration?.close()
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        visible = true
        connectionHealth.markWake(System.currentTimeMillis())
        when {
            idlePaused -> beginCatchUp()
            catchingUp -> {
                shellHandler.postDelayed(catchUpCap, CATCH_UP_CAP_MS)
                viewModel.startPolling(refreshBridge = false)
            }
            else -> viewModel.startPolling()
        }
        noteInteraction()
        renderShell(viewModel.state.value)
    }

    override fun onStop() {
        visible = false
        shellHandler.removeCallbacks(shellTick)
        shellHandler.removeCallbacks(idleTick)
        shellHandler.removeCallbacks(catchUpCap)
        viewModel.stopPolling()
        super.onStop()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) noteInteraction()
        return super.dispatchTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) noteInteraction()
        return super.dispatchKeyEvent(event)
    }

    private fun render(state: MainUiState) = with(binding) {
        latestShellState = state
        setupPanel.visibility = if (state.configured && !state.pairingRequired) View.GONE else View.VISIBLE
        dashboardPanel.visibility = if (state.configured && !state.pairingRequired) View.VISIBLE else View.GONE
        setupProgress.visibility = if (state.loading && !state.configured) View.VISIBLE else View.GONE
        connectButton.isEnabled = !state.loading
        pairButton.isEnabled = !state.loading
        swipeRefresh.isRefreshing = state.refreshing
        adapter.submitList(
            state.snapshot?.let { snapshot ->
                DashboardModel.items(
                    snapshot = snapshot,
                    scope = viewModel.currentScope,
                    stale = state.error != null,
                    build = getString(R.string.build_stamp, BuildConfig.VERSION_NAME),
                    recentOpen = nativePreferences.dashboardRecentOpen,
                    recentNewest = nativePreferences.dashboardRecentNewest,
                    spacesOpen = nativePreferences.dashboardSpacesOpen
                        ?: (snapshot.workspaces.size <= COLLAPSE_THRESHOLD),
                    spaceQuery = spaceQuery,
                    creatingSpace = structuralBusy,
                    structuralProgressRes = structuralProgressRes,
                    canCreateSpace = state.writeAuthorized && (
                        state.mux?.supports("createSpace") != false ||
                            state.mux?.supports("createWorktree") != false &&
                            snapshot.workspaces.any(::isEligibleWorktreeRoot)
                        ),
                    createSpaceNote = state.mux?.takeIf { !it.supports("createSpace") }
                        ?.notes
                        ?.get("createSpace")
                        .orEmpty(),
                    launchers = state.launchers?.items?.filter { it.available }?.map { item ->
                        Launcher(
                            command = item.command ?: item.harness ?: "shell",
                            label = item.label,
                            cwd = item.cwd,
                            harness = item.harness,
                            noPrompts = item.noPrompts,
                            target = item.start,
                        )
                    } ?: state.launchers?.launchers.orEmpty(),
                    launcherHome = state.launchers?.home.orEmpty(),
                    writesEnabled = state.writeAuthorized,
                    launchOpen = nativePreferences.dashboardLaunchOpen
                        ?: ((state.launchers?.items?.count { it.available } ?: state.launchers?.launchers.orEmpty().size) <= COLLAPSE_THRESHOLD),
                    launching = activeLaunchingCommands(),
                )
            }.orEmpty(),
        )
        dashboardProgress.visibility = if (state.loading && state.snapshot == null) View.VISIBLE else View.GONE
        val muxTitle = state.muxName?.let { getString(R.string.header_on, it) }
        val freshness = TopologyStatus.describe(state.mux, state.lastSuccessAt, System.currentTimeMillis())
        headerStatus.text = listOfNotNull(muxTitle, freshness).joinToString(" · ")
        headerStatus.contentDescription = listOfNotNull(muxTitle, freshness).joinToString(". ")
        dashboardReadOnlyBanner.isVisible = state.configured && !state.writeAuthorized
        if (dashboardReadOnlyBanner.isVisible) {
            // Offline is not "unauthorised": until a snapshot has answered, the device's standing is
            // simply unknown, and the banner must say so rather than accuse the pairing.
            dashboardReadOnlyBanner.setText(
                when {
                    !state.paired -> R.string.read_only_not_paired
                    state.snapshot == null && (state.snapshotFailed || state.error != null) -> R.string.read_only_unreachable
                    else -> R.string.read_only_device_unauthorised
                },
            )
            dashboardReadOnlyBanner.setOnClickListener(
                if (state.paired) null else View.OnClickListener {
                    startActivity(SettingsActivity.intent(this@MainActivity, focusDevices = true))
                },
            )
            dashboardReadOnlyBanner.isClickable = !state.paired
        }
        renderScopeSwitchers(state)
        errorText.text = state.error.orEmpty()
        errorText.visibility = if (state.error == null) View.GONE else View.VISIBLE
        val unresolvedIntent = state.unknownLaunchIntents.firstOrNull()
        val dashboardNotice = state.refreshNotice ?: state.error.takeUnless { state.snapshotFailed || state.authError }
        if (dashboardNotice == null && unresolvedIntent != null) {
            dashboardErrorText.setText(R.string.launch_outcome_unknown_notice)
            dashboardErrorText.visibility = View.VISIBLE
            dashboardErrorText.isClickable = true
            dashboardErrorText.setOnClickListener { showUnknownLaunchIntent(unresolvedIntent) }
        } else {
            dashboardErrorText.text = dashboardNotice.orEmpty()
            dashboardErrorText.visibility = if (dashboardNotice == null) View.GONE else View.VISIBLE
            dashboardErrorText.isClickable = false
            dashboardErrorText.setOnClickListener(null)
        }
        if (state.pairingRequired) {
            originInput.setText(state.origin.orEmpty())
            deviceLabelInput.setText(state.label.orEmpty())
        }
        if (state.paired) pairingCodeInput.text?.clear()
        if (catchingUp && state.requestSettled > catchUpSettledBaseline) finishCatchUp()
        renderShell(state)
        if (state.configured && !state.pairingRequired && visible && !idlePaused && !catchingUp) armIdlePause()
    }

    private fun renderShell(state: MainUiState) {
        latestShellState = state
        state.lastSuccessAt?.takeIf { it != lastHealthStamp }?.let {
            lastHealthStamp = it
            connectionHealth.markLive(it)
        }
        renderConnectionBanner(state)
        renderIdleCover()
    }

    private fun renderConnectionBanner(state: MainUiState) = with(binding) {
        shellHandler.removeCallbacks(shellTick)
        val now = System.currentTimeMillis()
        val view = connectionHealth.view(
            DashboardConnectionInput(
                configured = state.configured && !state.pairingRequired,
                snapshot = state.snapshot,
                snapshotFailed = state.snapshotFailed,
                authError = state.authError,
                requestStartedAt = state.requestStartedAt,
            ),
            now,
        )
        dashboardConnectionBanner.isVisible = view.tone != DashboardConnectionTone.SILENT
        if (dashboardConnectionBanner.isVisible) {
            val base = when (view.tone) {
                DashboardConnectionTone.AUTH -> getString(R.string.dashboard_connection_auth)
                DashboardConnectionTone.AMBER -> getString(R.string.dashboard_connection_reconnecting)
                DashboardConnectionTone.GREEN -> getString(R.string.dashboard_connection_connected)
                DashboardConnectionTone.RED -> getString(
                    if (view.cause == DashboardConnectionCause.HERDR_DOWN) {
                        R.string.dashboard_connection_herdr_down
                    } else {
                        R.string.dashboard_connection_cant_reach
                    },
                )
                DashboardConnectionTone.SILENT -> ""
            }
            dashboardConnectionText.text = if (
                view.tone == DashboardConnectionTone.RED && state.lastSuccessAt != null
            ) {
                getString(
                    R.string.dashboard_connection_last_seen,
                    base,
                    DateFormat.getTimeFormat(this@MainActivity).format(state.lastSuccessAt),
                )
            } else base
            val actionable = view.tone == DashboardConnectionTone.RED || view.tone == DashboardConnectionTone.AUTH
            dashboardConnectionRetry.isVisible = actionable
            dashboardConnectionReload.isVisible = actionable
            dashboardConnectionRetry.setText(
                if (view.tone == DashboardConnectionTone.AUTH) {
                    R.string.dashboard_connection_sign_in
                } else {
                    R.string.dashboard_connection_retry
                },
            )
            dashboardConnectionBanner.setBackgroundResource(
                when (view.tone) {
                    DashboardConnectionTone.RED, DashboardConnectionTone.AUTH -> R.drawable.bg_dashboard_shell_blocked
                    DashboardConnectionTone.GREEN -> R.drawable.bg_dashboard_shell_done
                    else -> R.drawable.bg_dashboard_shell_working
                },
            )
            dashboardConnectionIcon.setImageResource(
                when (view.tone) {
                    DashboardConnectionTone.RED, DashboardConnectionTone.AUTH -> R.drawable.ic_dashboard_warning
                    DashboardConnectionTone.GREEN -> R.drawable.ic_dashboard_connected
                    else -> R.drawable.ic_dashboard_plug
                },
            )
            dashboardConnectionBanner.accessibilityLiveRegion = if (
                view.tone == DashboardConnectionTone.RED || view.tone == DashboardConnectionTone.AUTH
            ) View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE else View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        view.nextUpdateAt?.let { next ->
            shellHandler.postDelayed(shellTick, (next - now).coerceAtLeast(1L))
        }
    }

    private fun noteInteraction() {
        if (!visible || idlePaused || catchingUp || !latestShellState.configured) return
        idleDeadline = System.currentTimeMillis() + IDLE_MS
        armIdlePause()
    }

    private fun armIdlePause() {
        if (!visible || idlePaused || catchingUp || !latestShellState.configured) return
        if (idleDeadline == 0L) idleDeadline = System.currentTimeMillis() + IDLE_MS
        shellHandler.removeCallbacks(idleTick)
        shellHandler.postDelayed(idleTick, (idleDeadline - System.currentTimeMillis()).coerceAtLeast(1L))
    }

    private fun enterIdlePause() {
        if (!visible || idlePaused || catchingUp || System.currentTimeMillis() < idleDeadline) {
            armIdlePause()
            return
        }
        idlePaused = true
        viewModel.stopPolling()
        renderIdleCover()
    }

    private fun beginCatchUp() {
        if (!visible) return
        idlePaused = false
        catchingUp = true
        catchUpSettledBaseline = viewModel.state.value.requestSettled
        shellHandler.removeCallbacks(catchUpCap)
        shellHandler.postDelayed(catchUpCap, CATCH_UP_CAP_MS)
        renderIdleCover()
        viewModel.startPolling(refreshBridge = false)
    }

    private fun finishCatchUp() {
        if (!catchingUp) return
        catchingUp = false
        shellHandler.removeCallbacks(catchUpCap)
        idleDeadline = System.currentTimeMillis() + IDLE_MS
        renderIdleCover()
        armIdlePause()
    }

    private fun renderIdleCover() = with(binding) {
        val covered = idlePaused || catchingUp
        dashboardIdleCover.isVisible = covered
        dashboardPanel.importantForAccessibility = if (covered) {
            View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        } else {
            View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
        }
        dashboardIdleTitle.setText(
            if (catchingUp) R.string.dashboard_idle_catching_title else R.string.dashboard_idle_paused_title,
        )
        dashboardIdleBody.setText(
            if (catchingUp) R.string.dashboard_idle_catching_body else R.string.dashboard_idle_paused_body,
        )
        dashboardIdleProgress.isVisible = catchingUp
        dashboardIdleResume.isVisible = !catchingUp
        dashboardIdleCover.contentDescription = getString(R.string.dashboard_idle_dialog)
        if (covered) dashboardIdleCover.requestFocus()
    }

    private fun openProxySignIn() {
        if (viewModel.state.value.origin == null) return
        startActivity(Intent(this, ProxySignInActivity::class.java))
    }

    private fun renderScopeSwitchers(state: MainUiState) = with(binding) {
        val snapshot = state.snapshot
        val scope = viewModel.currentScope
        val servers = snapshot?.servers.orEmpty()
        val lead = servers.firstOrNull(ServerSummary::isLead)
        val currentServer = if (scope.host == null) lead else servers.firstOrNull { it.id == scope.host }
        hostSwitcher.isVisible = DashboardHostHealthModel.shouldShowSwitcher(servers, scope.host)
        hostSwitcher.text = currentServer?.name?.ifBlank { currentServer.id } ?: scope.host.orEmpty()
        hostSwitcher.contentDescription = getString(
            R.string.dashboard_host_switcher_named,
            hostSwitcher.text,
        )

        val sessions = sessionsForCurrentHost(snapshot?.sessions.orEmpty(), servers, scope.host)
        val currentSession = if (scope.viewAll) {
            getString(R.string.dashboard_all_sessions)
        } else {
            scope.session ?: sessions.firstOrNull(SessionSummary::isPrimary)?.name.orEmpty()
        }
        sessionSwitcher.isVisible = sessions.count(SessionSummary::reachable) > 1 || scope.session != null || scope.viewAll
        sessionSwitcher.text = currentSession
        sessionSwitcher.contentDescription = getString(
            R.string.dashboard_session_switcher_named,
            currentSession,
        )
    }

    private fun showHostSwitcher() {
        val snapshot = viewModel.state.value.snapshot ?: return
        val servers = snapshot.servers.orEmpty()
        if (servers.isEmpty()) return
        val current = viewModel.currentScope.host
        val leadId = servers.firstOrNull(ServerSummary::isLead)?.id
        val counts = snapshot.agents.groupBy { it.host ?: leadId.orEmpty() }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        servers.forEach { server ->
            val panes = counts[server.id].orEmpty()
            val health = DashboardHostHealthModel.from(server, snapshot)
            val lastSeen = if (health.lastSeenAt > 0L) {
                getString(
                    R.string.dashboard_host_last_seen,
                    DashboardModel.compactAge(health.lastSeenAt, snapshot.ts)?.resolve(binding.root).orEmpty(),
                )
            } else {
                getString(R.string.dashboard_host_never_seen)
            }
            val summary = buildList {
                if (server.isLead) add(getString(R.string.dashboard_host_lead))
                if (health.incompatible) {
                    add(getString(R.string.dashboard_host_incompatible))
                } else if (health.state != DashboardHostState.LIVE) {
                    add(
                        if (health.writable) lastSeen else getString(
                            R.string.dashboard_host_unreachable_last_seen,
                            lastSeen,
                        ),
                    )
                }
                panes.count { it.status == com.lateapex.collie.network.AgentStatus.BLOCKED }.takeIf { it > 0 }
                    ?.let { add(getString(R.string.dashboard_needs_count, it)) }
                panes.count { it.status == com.lateapex.collie.network.AgentStatus.WORKING }.takeIf { it > 0 }
                    ?.let { add(getString(R.string.dashboard_working_count, it)) }
            }.joinToString(" · ")
            val secondary = SpannableStringBuilder(summary)
            health.protocolDetail?.takeIf { health.incompatible && it.isNotBlank() }?.let { detail ->
                if (secondary.isNotEmpty()) secondary.append('\n')
                val start = secondary.length
                secondary.append(detail)
                secondary.setSpan(
                    TypefaceSpan("monospace"),
                    start,
                    secondary.length,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            list.addView(scopeRow(
                title = server.name.ifBlank { server.id },
                secondary = secondary,
                active = if (current == null) server.isLead else current == server.id,
                enabled = true,
                dimmed = !health.writable,
                tint = HostPalette.slot(servers, server.id)?.let(HostPalette::resource)
                    ?: R.color.collie_muted,
            ) {
                viewModel.selectScope(Scope(
                    host = if (server.isLead) null else server.id,
                    session = viewModel.currentScope.session,
                ))
            })
        }
        list.addView(scopeRow(
            title = getString(R.string.dashboard_pack_details),
            secondary = "",
            active = false,
            enabled = true,
            tint = R.color.collie_muted,
        ) { startActivity(Intent(this, PackActivity::class.java)) })
        CollieBottomSheetDialog(this, getString(R.string.dashboard_hosts_title)).apply {
            setSheetContent(list)
            list.tag = this
            show()
            bindSheetDismissOnSelection(list, this)
        }
    }

    private fun showSessionSwitcher() {
        val snapshot = viewModel.state.value.snapshot ?: return
        val scope = viewModel.currentScope
        val sessions = sessionsForCurrentHost(snapshot.sessions, snapshot.servers.orEmpty(), scope.host)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.addView(scopeRow(
            title = getString(R.string.dashboard_all_sessions),
            secondary = getString(R.string.dashboard_all_sessions_description),
            active = scope.viewAll,
            enabled = true,
            tint = R.color.collie_muted,
        ) { viewModel.selectScope(Scope(host = scope.host, viewAll = true)) })
        sessions.forEach { session ->
            val secondary = buildList {
                if (session.isPrimary) add(getString(R.string.dashboard_primary_session))
                if (!session.reachable) add(getString(R.string.dashboard_unreachable))
                if (session.blocked > 0) add(getString(R.string.dashboard_needs_count, session.blocked))
                if (session.working > 0) add(getString(R.string.dashboard_working_count, session.working))
            }.joinToString(" · ")
            list.addView(scopeRow(
                title = session.name,
                secondary = secondary,
                active = !scope.viewAll && if (scope.session == null) session.isPrimary else scope.session == session.name,
                enabled = session.reachable,
                tint = R.color.collie_muted,
            ) {
                viewModel.selectScope(Scope(
                    host = scope.host,
                    session = if (session.isPrimary) null else session.name,
                ))
            })
        }
        CollieBottomSheetDialog(this, getString(R.string.dashboard_sessions_title)).apply {
            setSheetContent(list)
            show()
            bindSheetDismissOnSelection(list, this)
        }
    }

    private fun bindSheetDismissOnSelection(list: ViewGroup, dialog: CollieBottomSheetDialog) {
        for (index in 0 until list.childCount) {
            val row = list.getChildAt(index)
            val listener = row.hasOnClickListeners()
            if (!listener) continue
            row.setOnClickListener {
                (row.tag as? (() -> Unit))?.invoke()
                dialog.dismiss()
            }
        }
    }

    private fun scopeRow(
        title: String,
        secondary: CharSequence,
        active: Boolean,
        enabled: Boolean,
        dimmed: Boolean = false,
        @androidx.annotation.ColorRes tint: Int,
        action: () -> Unit,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(52)
        alpha = if (enabled && !dimmed) 1f else 0.5f
        isEnabled = enabled
        isSelected = active
        ViewCompat.setStateDescription(
            this,
            getString(R.string.dashboard_scope_current).takeIf { active },
        )
        addView(View(this@MainActivity).apply {
            setBackgroundColor(ContextCompat.getColor(this@MainActivity, if (active) R.color.collie_primary else android.R.color.transparent))
        }, LinearLayout.LayoutParams(dp(2), ViewGroup.LayoutParams.MATCH_PARENT))
        addView(TextView(this@MainActivity).apply {
            text = "●"
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@MainActivity, tint))
            contentDescription = null
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(32), dp(44)))
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(8), dp(12), dp(8))
            addView(TextView(this@MainActivity).apply {
                text = title
                textSize = 14f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.collie_foreground))
            })
            if (secondary.isNotEmpty()) addView(TextView(this@MainActivity).apply {
                text = secondary
                textSize = 11f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.collie_muted))
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        tag = action
        contentDescription = listOf(title, secondary.toString()).filter(String::isNotBlank).joinToString(". ")
        setOnClickListener { if (enabled) action() }
        applyAppTypeface()
    }

    private fun sessionsForCurrentHost(
        sessions: List<SessionSummary>,
        servers: List<ServerSummary>,
        host: String?,
    ): List<SessionSummary> {
        val key = host ?: servers.firstOrNull(ServerSummary::isLead)?.id
        return sessions.filter { it.host == null || it.host == key }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun openPane(pane: PaneSummary) {
        val tabLabel = pane.tabLabel?.takeIf(String::isNotBlank)
            ?: viewModel.state.value.snapshot?.tabs?.firstOrNull { tab ->
                tab.tabId == pane.tabId && tab.workspaceId == pane.workspaceId &&
                    (tab.host == null || tab.host == pane.host)
            }?.label?.takeIf(String::isNotBlank)
        val defaultTitle = listOfNotNull(
            pane.workspaceLabel.takeIf(String::isNotBlank),
            tabLabel,
        ).joinToString(" › ").ifBlank { pane.workspaceId }
        val displayTitle = listOf(pane.paneLabel, pane.sessionName, defaultTitle)
            .firstNotNullOfOrNull { title -> displayAgentTitle(pane.agent, title) }
            ?: defaultTitle
        startActivity(Intent(this, PaneActivity::class.java).apply {
            putExtra(PaneActivity.EXTRA_PANE_ID, pane.paneId)
            putExtra(PaneActivity.EXTRA_HOST, pane.host)
            putExtra(PaneActivity.EXTRA_SESSION, pane.session)
            putExtra(PaneActivity.EXTRA_TITLE, displayTitle)
            putExtra(PaneActivity.EXTRA_META, getString(R.string.pane_meta, pane.agent, pane.workspaceLabel))
            putExtra(PaneActivity.EXTRA_AGENT, pane.agent)
            putExtra(PaneActivity.EXTRA_CWD, pane.cwd)
            putExtra(PaneActivity.EXTRA_TAB_LABEL, tabLabel)
            putExtra(PaneActivity.EXTRA_STATUS, pane.status.name.lowercase(Locale.ROOT))
        })
    }

    private fun openSpace(workspace: WorkspaceSummary) {
        startActivity(
            SpaceActivity.intent(
                this,
                workspace,
                DashboardNavigationModel.spaceSession(viewModel.currentScope),
            ),
        )
    }

    private fun showChangesWorkspacePicker() {
        val snapshot = viewModel.state.value.snapshot ?: return
        val workspaces = snapshot.workspaces.sortedWith(compareBy<WorkspaceSummary>({ it.host.orEmpty() }, { it.number }))
        if (workspaces.isEmpty()) {
            android.widget.Toast.makeText(this, R.string.changes_no_workspaces_dashboard, android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.changes_choose_workspace)
            .setItems(workspaces.map { it.label }.toTypedArray()) { _, index ->
                val workspace = workspaces.getOrNull(index) ?: return@setItems
                val selectedScope = viewModel.currentScope.copy(host = workspace.host ?: viewModel.currentScope.host)
                startActivity(ChangesActivity.intentForWorkspace(this, workspace.workspaceId, selectedScope, workspace.label))
            }
            .setNegativeButton(R.string.pane_action_cancel, null)
            .show()
    }

    private fun showNewSpace() {
        val snapshot = viewModel.state.value.snapshot ?: return
        val options = NewSpaceHostModel.options(snapshot)
        if (options.isEmpty()) {
            showNewSpaceMode(
                scope = viewModel.currentScope.copy(viewAll = false),
                repos = snapshot.workspaces.filter(::isEligibleWorktreeRoot),
            )
            return
        }

        val dialog = CollieBottomSheetDialog(this, getString(R.string.new_space), initiallyExpanded = true)
        val body = sheetBody()
        var selectedId = NewSpaceHostModel.defaultHostId(options, viewModel.currentScope.host)
        lateinit var render: () -> Unit
        render = {
            val currentSnapshot = viewModel.state.value.snapshot ?: snapshot
            val currentServers = currentSnapshot.servers.orEmpty()
            val currentOptions = NewSpaceHostModel.options(currentSnapshot)
            if (currentOptions.none { it.server.id == selectedId }) {
                selectedId = NewSpaceHostModel.defaultHostId(currentOptions, viewModel.currentScope.host)
            }
            body.removeAllViews()
            body.addView(TextView(this).apply {
                setText(R.string.space_new_machine)
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.collie_foreground))
            }, sheetRowParams(top = 4))
            body.addView(TextView(this).apply {
                setText(R.string.space_new_machine_description)
                textSize = 11f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.collie_muted))
                setPadding(0, dp(2), 0, dp(4))
            }, sheetRowParams())
            currentOptions.forEach { option ->
                body.addView(scopeRow(
                    title = option.server.name.ifBlank { option.server.id },
                    secondary = newSpaceHostSecondary(option),
                    active = option.server.id == selectedId,
                    enabled = option.writable,
                    dimmed = !option.writable,
                    tint = option.slot?.let(HostPalette::resource) ?: R.color.collie_muted,
                ) {
                    selectedId = option.server.id
                    render()
                })
            }
            val scope = NewSpaceHostModel.validatedScope(currentSnapshot, viewModel.currentScope, selectedId)
            if (scope == null) {
                val refusal = currentOptions.firstOrNull { it.server.id == selectedId }
                    ?.let(::newSpaceHostRefusal)
                    .orEmpty()
                body.addView(TextView(this).apply {
                    text = refusal.ifBlank { getString(R.string.space_new_machine_unavailable) }
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.collie_blocked))
                    setPadding(0, dp(8), 0, 0)
                }, sheetRowParams())
            }
            val repos = NewSpaceHostModel.repositoriesFor(
                currentSnapshot.workspaces,
                currentServers,
                selectedId,
            )
                .filter(::isEligibleWorktreeRoot)
            val canPlain = viewModel.state.value.mux?.supports("createSpace") != false
            val canWorktree = viewModel.state.value.mux?.supports("createWorktree") != false && repos.isNotEmpty()
            if (canPlain) {
                body.addView(sheetActionButton(getString(R.string.space_mode_plain)) {
                    val latest = viewModel.state.value.snapshot ?: return@sheetActionButton
                    val target = NewSpaceHostModel.validatedScope(latest, viewModel.currentScope, selectedId)
                    if (target == null) {
                        render()
                        return@sheetActionButton
                    }
                    dialog.dismiss()
                    showPlainSpaceDialog(target)
                }.apply { isEnabled = scope != null }, sheetRowParams(top = 12))
            }
            if (canWorktree) {
                body.addView(sheetActionButton(getString(R.string.space_mode_worktree)) {
                    val latest = viewModel.state.value.snapshot ?: return@sheetActionButton
                    val target = NewSpaceHostModel.validatedScope(latest, viewModel.currentScope, selectedId)
                    if (target == null) {
                        render()
                        return@sheetActionButton
                    }
                    dialog.dismiss()
                    val latestRepos = NewSpaceHostModel.repositoriesFor(
                        latest.workspaces,
                        latest.servers.orEmpty(),
                        selectedId,
                    ).filter(::isEligibleWorktreeRoot)
                    chooseWorktreeRepo(latestRepos, target)
                }.apply { isEnabled = scope != null }, sheetRowParams(top = if (canPlain) 8 else 12))
            }
            if (!canPlain && !canWorktree) {
                body.addView(TextView(this).apply {
                    text = viewModel.state.value.mux?.notes?.get("createSpace")
                        ?.takeIf(String::isNotBlank)
                        ?: getString(R.string.space_create_unsupported)
                    textSize = 12f
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.collie_muted))
                    setPadding(0, dp(8), 0, 0)
                }, sheetRowParams())
            }
        }
        render()
        dialog.setSheetContent(ScrollView(this).apply { addView(body) })
        dialog.show()
    }

    private fun showNewSpaceMode(scope: Scope, repos: List<WorkspaceSummary>) {
        val canPlain = viewModel.state.value.mux?.supports("createSpace") != false
        val canWorktree = viewModel.state.value.mux?.supports("createWorktree") != false && repos.isNotEmpty()
        when {
            canPlain && canWorktree -> showChoiceSheet(
                title = getString(R.string.new_space),
                choices = listOf(
                    getString(R.string.space_mode_plain) to { showPlainSpaceDialog(scope) },
                    getString(R.string.space_mode_worktree) to { chooseWorktreeRepo(repos, scope) },
                ),
            )
            canWorktree -> chooseWorktreeRepo(repos, scope)
            canPlain -> showPlainSpaceDialog(scope)
            else -> viewModel.report(getString(R.string.space_create_unsupported))
        }
    }

    private fun newSpaceHostSecondary(option: NewSpaceHostOption): CharSequence {
        val status = when {
            option.health.incompatible -> getString(R.string.dashboard_host_incompatible)
            !option.health.writable -> {
                val lastSeen = if (option.health.lastSeenAt > 0L) {
                    getString(R.string.dashboard_host_last_seen, DashboardModel.compactAge(
                        option.health.lastSeenAt,
                        viewModel.state.value.snapshot?.ts ?: System.currentTimeMillis(),
                    )?.resolve(binding.root).orEmpty())
                } else {
                    getString(R.string.dashboard_host_never_seen)
                }
                getString(R.string.dashboard_host_unreachable_last_seen, lastSeen)
            }
            option.server.isLead -> getString(R.string.dashboard_host_lead)
            else -> getString(R.string.health_reachable)
        }
        val secondary = SpannableStringBuilder(status)
        option.health.protocolDetail?.takeIf { option.health.incompatible && it.isNotBlank() }?.let { detail ->
            secondary.append('\n').append(detail)
            secondary.setSpan(
                TypefaceSpan("monospace"),
                secondary.length - detail.length,
                secondary.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return secondary
    }

    private fun newSpaceHostRefusal(option: NewSpaceHostOption): String {
        val name = option.server.name.ifBlank { option.server.id }
        if (option.health.incompatible) {
            return option.health.protocolDetail?.takeIf(String::isNotBlank)?.let { detail ->
                getString(R.string.pane_host_incompatible_detail, name, detail)
            } ?: getString(R.string.pane_host_incompatible, name)
        }
        val lastSeen = if (option.health.lastSeenAt > 0L) {
            getString(
                R.string.dashboard_host_last_seen,
                DashboardModel.compactAge(
                    option.health.lastSeenAt,
                    viewModel.state.value.snapshot?.ts ?: System.currentTimeMillis(),
                )?.resolve(binding.root).orEmpty(),
            )
        } else {
            getString(R.string.dashboard_host_never_seen)
        }
        return getString(R.string.space_new_machine_unreachable, name, lastSeen)
    }

    private fun showPlainSpaceDialog(scope: Scope) {
        val dialog = CollieBottomSheetDialog(this, getString(R.string.new_space), initiallyExpanded = true)
        val body = sheetBody()
        val cwd = sheetTextField(R.string.space_directory_optional)
        val label = sheetTextField(R.string.space_label_optional)
        val repository = (application as CollieApplication).container.repository
        val readContext = repository.readContext(scope)
        var folderResponse: com.lateapex.collie.network.FoldersResponse? = null
        lateinit var folderPicker: FolderPickerView
        folderPicker = FolderPickerView(this, onSelect = { cwd.setText(it.orEmpty()) }, onStar = { folder, starred ->
            if (readContext != null) lifecycleScope.launch {
                when (val result = repository.starFolder(scope, folder, starred)) {
                    is ApiResult.Success -> if (repository.isCurrent(readContext)) {
                        folderResponse = result.value
                        folderPicker.render(folderResponse, cwd.valueOrNull(), repository.writesAllowed(scope))
                    }
                    is ApiResult.Failure -> if (repository.isCurrent(readContext)) viewModel.report(MainViewModel.describe(resources, result.error))
                    is ApiResult.NotModified -> Unit
                }
            }
        })
        body.addView(cwd, sheetRowParams(top = 4))
        folderPicker.render(null, cwd.valueOrNull(), repository.writesAllowed(scope))
        body.addView(folderPicker, sheetRowParams(top = 4))
        body.addView(label, sheetRowParams(top = 8))
        if (readContext != null) lifecycleScope.launch {
            val result = repository.folders(scope)
            if (repository.isCurrent(readContext) && result is ApiResult.Success) {
                folderResponse = result.value
                folderPicker.render(folderResponse, cwd.valueOrNull(), repository.writesAllowed(scope))
            }
        }
        body.addView(sheetActionButton(getString(R.string.action_create)) {
            val target = revalidatedStructuralScope(scope) ?: return@sheetActionButton
            dialog.dismiss()
            lifecycleScope.launch {
                handleCreate(structuralRequest(R.string.space_creating) {
                    viewModel.createWorkspace(label.valueOrNull(), cwd.valueOrNull(), target)
                } ?: return@launch, target)
            }
        }, sheetRowParams(top = 12))
        dialog.setSheetContent(body)
        dialog.show()
    }

    private fun chooseWorktreeRepo(repos: List<WorkspaceSummary>, scope: Scope) {
        showCreateWorktree(repos, scope)
    }

    /** Lists a repository's worktrees; a test replaces it because the dashboard test has no bridge. */
    @androidx.annotation.VisibleForTesting
    internal var worktreeLister: suspend (String, Scope) -> ApiResult<com.lateapex.collie.network.WorktreeListResponse> =
        { workspaceId, scope -> viewModel.listWorktrees(workspaceId, scope) }

    @androidx.annotation.VisibleForTesting
    internal var worktreeOpener: suspend (String, String, Scope) -> ApiResult<com.lateapex.collie.network.WorktreeOpenResponse> =
        { workspaceId, path, scope -> viewModel.openWorktree(workspaceId, path, scope) }

    @androidx.annotation.VisibleForTesting
    internal fun startWorktreeFlowForTest() {
        chooseWorktreeRepo(
            latestShellState.snapshot?.workspaces?.filter(::isEligibleWorktreeRoot).orEmpty(),
            viewModel.currentScope,
        )
    }

    /** B.3: one sheet that names the repository and the base it branches from. */
    private fun showCreateWorktree(repos: List<WorkspaceSummary>, scope: Scope) {
        val repository = (application as CollieApplication).container.repository
        val dialog = CollieBottomSheetDialog(this, getString(R.string.worktree_create), initiallyExpanded = true)
        val body = sheetBody()
        var selected = repos.first()

        fun baseCaption() = getString(
            R.string.worktree_base_caption,
            selected.repoRoot?.takeIf(String::isNotBlank) ?: selected.workspaceId,
        ) + "\n" + getString(R.string.worktree_base_unknown)
        val baseCaption = TextView(this).apply {
            id = R.id.worktree_base_caption
            text = baseCaption()
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.collie_muted))
        }
        // A linked worktree with no open space can be opened instead of branching again. The old
        // options sheet offered this; the one-sheet rework must not drop it (B.3, 2026-09-10).
        val existing = LinearLayout(this).apply {
            id = R.id.worktree_existing
            orientation = LinearLayout.VERTICAL
            isVisible = false
        }
        fun loadExisting(repo: WorkspaceSummary) {
            // Undispatched: the lookup starts in this frame and only yields at the network call.
            lifecycleScope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                val context = repository.readContext(viewModel.currentScope) ?: return@launch
                val result = worktreeLister(repo.workspaceId, scope)
                if (!repository.isCurrent(context)) return@launch
                if (selected !== repo) return@launch
                existing.removeAllViews()
                val unopened = (result as? ApiResult.Success)?.value?.worktrees
                    ?.filter { it.linked && it.openWorkspaceId == null }
                    .orEmpty()
                if (unopened.isNotEmpty()) {
                    existing.addView(TextView(this@MainActivity).apply {
                        text = getString(R.string.worktree_open_existing)
                        textSize = 13f
                        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.collie_muted))
                    }, sheetRowParams(top = 16))
                    unopened.forEach { worktree ->
                        existing.addView(
                            sheetActionButton(worktree.branch ?: getString(R.string.worktree_detached, worktree.path)) {
                                dialog.dismiss()
                                confirmOpenWorktree(repo, worktree, scope)
                            },
                            sheetRowParams(top = 4),
                        )
                    }
                }
                existing.isVisible = unopened.isNotEmpty()
            }
        }
        if (repos.size > 1) {
            val group = com.google.android.material.button.MaterialButtonToggleGroup(this).apply {
                id = R.id.worktree_repo_group
                isSingleSelection = true
                orientation = LinearLayout.VERTICAL
            }
            repos.forEach { repo ->
                group.addView(
                    MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                        tag = repo
                        text = repo.label
                        isAllCaps = false
                        isCheckable = true
                        gravity = Gravity.START or Gravity.CENTER_VERTICAL
                        minHeight = dp(48)
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
            group.addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (isChecked) {
                    (group.findViewById<MaterialButton>(checkedId).tag as? WorkspaceSummary)?.let { repo ->
                        selected = repo
                        baseCaption.text = baseCaption()
                        loadExisting(repo)
                    }
                }
            }
            (group.getChildAt(0) as? MaterialButton)?.let { it.isChecked = true }
            body.addView(group, sheetRowParams(top = 4))
        } else {
            body.addView(TextView(this).apply {
                text = getString(R.string.worktree_repository_caption, selected.label)
                textSize = 13f
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.collie_foreground))
            }, sheetRowParams(top = 4))
        }
        val branch = sheetTextField(R.string.worktree_branch_name).apply { id = R.id.worktree_branch_input }
        val baseRef = if (viewModel.state.value.receiptContextBound == true) sheetTextField(R.string.worktree_base_ref).apply {
            id = View.generateViewId()
            inputType = InputType.TYPE_CLASS_TEXT
        } else null
        val folderParent = if (viewModel.state.value.receiptContextBound == true) sheetTextField(R.string.worktree_parent_folder).apply {
            id = View.generateViewId()
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        } else null
        var folderResponse: com.lateapex.collie.network.FoldersResponse? = null
        lateinit var folderPicker: FolderPickerView
        val folderContext = repository.readContext(scope)
        folderPicker = FolderPickerView(this, onSelect = { folder -> folderParent?.setText(folder.orEmpty()) }, onStar = { folder, starred ->
            if (folderContext != null) lifecycleScope.launch {
                when (val result = repository.starFolder(scope, folder, starred)) {
                    is ApiResult.Success -> if (repository.isCurrent(folderContext)) {
                        folderResponse = result.value
                        folderPicker.render(folderResponse, folderParent?.valueOrNull(), repository.writesAllowed(scope))
                    }
                    is ApiResult.Failure -> if (repository.isCurrent(folderContext)) viewModel.report(MainViewModel.describe(resources, result.error))
                    is ApiResult.NotModified -> Unit
                }
            }
        })
        body.addView(baseCaption, sheetRowParams(top = 6))
        body.addView(branch, sheetRowParams(top = 4))
        baseRef?.let { body.addView(it, sheetRowParams(top = 4)) }
        folderParent?.let { body.addView(it, sheetRowParams(top = 4)) }
        if (folderParent != null) {
            folderPicker.render(null, folderParent.valueOrNull(), repository.writesAllowed(scope))
            body.addView(folderPicker, sheetRowParams(top = 4))
            if (folderContext != null) lifecycleScope.launch {
                val result = repository.folders(scope)
                if (repository.isCurrent(folderContext) && result is ApiResult.Success) {
                    folderResponse = result.value
                    folderPicker.render(folderResponse, folderParent.valueOrNull(), repository.writesAllowed(scope))
                }
            }
        }
        body.addView(sheetActionButton(getString(R.string.action_create)) {
            val name = branch.text.toString().trim()
            if (name.isEmpty()) return@sheetActionButton
            val target = revalidatedStructuralScope(scope) ?: return@sheetActionButton
            if (viewModel.state.value.receiptContextBound == true) {
                val cwd = selected.repoRoot?.takeIf(String::isNotBlank) ?: selected.folder?.takeIf(String::isNotBlank)
                if (cwd == null) {
                    viewModel.report(getString(R.string.worktree_repository_folder_missing))
                    return@sheetActionButton
                }
                val base = baseRef?.valueOrNull()?.let { WorktreeBase(kind = "ref", ref = it) } ?: WorktreeBase()
                val folder = folderParent?.valueOrNull()?.let { WorktreeFolder(kind = "parent", parent = it) } ?: WorktreeFolder()
                val context = repository.readContext(target) ?: return@sheetActionButton
                dialog.dismiss()
                lifecycleScope.launch {
                    val planned = viewModel.planWorktree(cwd, name, folder.parent, target)
                    if (!repository.isCurrent(context)) return@launch
                    when (planned) {
                        is ApiResult.Success -> if (planned.value.ok && planned.value.branchValid != false) {
                            showWorktreePlanConfirmation(selected, target, cwd, name, base, folder, context, planned.value)
                        } else viewModel.report(planned.value.error ?: getString(R.string.worktree_plan_refused))
                        is ApiResult.Failure -> viewModel.report(MainViewModel.describe(resources, planned.error))
                        is ApiResult.NotModified -> viewModel.report(getString(R.string.worktree_plan_refused))
                    }
                }
                return@sheetActionButton
            }
            dialog.dismiss()
            val repository = (application as CollieApplication).container.repository
            val context = repository.readContext(target) ?: return@sheetActionButton
            val requestId = UUID.randomUUID().toString()
            lifecycleScope.launch {
                if (!repository.isCurrent(context) || viewModel.currentScope != target) return@launch
                val actor = UUID.randomUUID().toString()
                structuralActor = actor
                structuralBusy = true
                structuralProgressRes = R.string.worktree_creating
                render(viewModel.state.value)
                try {
                    val result = viewModel.createWorkspaceWorktreeLegacyInitial(selected.workspaceId, name, requestId, target)
                    if (!repository.isCurrent(context) || viewModel.currentScope != target) return@launch
                    handleWorktreeIntentOutcome(result, requestId, target, context)
                } finally {
                    if (structuralActor == actor) {
                        structuralActor = null
                        structuralBusy = false
                        render(viewModel.state.value)
                    }
                }
            }
        }, sheetRowParams(top = 12))
        body.addView(existing, sheetRowParams())
        loadExisting(selected)
        dialog.setSheetContent(body)
        dialog.show()
        branch.post { branch.requestFocus() }
    }

    private fun showWorktreePlanConfirmation(
        repo: WorkspaceSummary,
        scope: Scope,
        cwd: String,
        branch: String,
        base: WorktreeBase,
        folder: WorktreeFolder,
        context: ScopedReadContext,
        plan: com.lateapex.collie.network.WorktreePlanResponse,
    ) {
        val repository = (application as CollieApplication).container.repository
        val dialog = CollieBottomSheetDialog(this, getString(R.string.worktree_plan_title))
        val body = sheetBody()
        body.addView(TextView(this).apply {
            text = getString(
                R.string.worktree_plan_preview,
                plan.repoRoot ?: cwd,
                plan.defaultBranch ?: getString(R.string.worktree_base_unknown),
                plan.currentBranch ?: getString(R.string.worktree_base_unknown),
                branch,
                base.ref ?: getString(R.string.worktree_base_default),
                folder.parent ?: getString(R.string.worktree_folder_default),
            )
            textSize = 12f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.collie_foreground))
            setPadding(0, dp(8), 0, dp(8))
        }, sheetRowParams())

        val availableLaunchers = viewModel.state.value.launchers?.items.orEmpty().filter { it.available }
        val choices = listOf<LauncherItem?>(null) + availableLaunchers
        var selectedChoice = 0
        val targets = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        choices.forEachIndexed { index, item ->
            val label = item?.label ?: getString(R.string.launch_target_shell)
            targets.addView(RadioButton(this).apply {
                id = View.generateViewId()
                text = label
                minHeight = dp(48)
                isChecked = index == selectedChoice
                setOnClickListener { selectedChoice = index }
            })
        }
        body.addView(targets, sheetRowParams(top = 4))
        body.addView(sheetActionButton(getString(R.string.worktree_plan_create)) {
            if (!repository.isCurrent(context) || viewModel.currentScope != scope) return@sheetActionButton
            val chosen = choices.getOrNull(selectedChoice)
            val start = chosen?.start ?: StartTarget(shell = true)
            val noPrompts = chosen?.noPrompts == true
            val launcher = chosen?.let { item ->
                Launcher(
                    command = item.command ?: item.start.command ?: item.label,
                    label = item.label,
                    cwd = cwd,
                    harness = item.harness ?: item.start.harness,
                    noPrompts = item.noPrompts,
                )
            }
            lifecycleScope.launch {
                if (launcher != null && !confirmLauncher(this@MainActivity, launcher, scope.host ?: context.connection.origin.value)) return@launch
                if (!repository.isCurrent(context) || viewModel.currentScope != scope) return@launch
                dialog.dismiss()
                val requestId = UUID.randomUUID().toString()
                val result = viewModel.createWorktreeIntent(
                    cwd, branch, start, requestId, base, folder,
                    confirmedNoPrompts = noPrompts, scope = scope,
                )
                if (repository.isCurrent(context) && viewModel.currentScope == scope) {
                    handleWorktreeIntentOutcome(result, requestId, scope, context)
                }
            }
        }, sheetRowParams(top = 8))
        dialog.setSheetContent(ScrollView(this).apply { addView(body) })
        dialog.show()
    }

    private fun confirmOpenWorktree(workspace: WorkspaceSummary, worktree: com.lateapex.collie.network.Worktree, scope: Scope) {
        val dialog = CollieBottomSheetDialog(this, getString(R.string.worktree_open_title))
        val body = sheetBody()
        body.addView(TextView(this).apply {
            text = worktree.path
            textSize = 14f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.collie_muted))
            setPadding(0, dp(8), 0, dp(8))
        }, sheetRowParams())
        body.addView(sheetActionButton(getString(R.string.action_open)) {
            val target = revalidatedStructuralScope(scope) ?: return@sheetActionButton
            dialog.dismiss()
            lifecycleScope.launch {
                handleWorktree(structuralRequest(R.string.worktree_opening) {
                    worktreeOpener(workspace.workspaceId, worktree.path, target)
                } ?: return@launch, target)
            }
        }, sheetRowParams(top = 8))
        dialog.setSheetContent(body)
        dialog.show()
    }

    private fun showChoiceSheet(title: String, choices: List<Pair<String, () -> Unit>>) {
        val dialog = CollieBottomSheetDialog(this, title)
        val body = sheetBody()
        choices.forEachIndexed { index, (label, action) ->
            body.addView(sheetActionButton(label) {
                dialog.dismiss()
                action()
            }, sheetRowParams(top = if (index == 0) 4 else 8))
        }
        dialog.setSheetContent(body)
        dialog.show()
    }

    private fun sheetBody() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun sheetTextField(@androidx.annotation.StringRes hintRes: Int) = EditText(this).apply {
        setHint(hintRes)
        inputType = InputType.TYPE_CLASS_TEXT
        isSingleLine = true
        minHeight = dp(48)
        setTextColor(ContextCompat.getColor(this@MainActivity, R.color.collie_foreground))
        setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.collie_muted))
    }

    private fun EditText.valueOrNull(): String? = text.toString().trim().ifBlank { null }

    private fun sheetActionButton(label: String, action: MaterialButton.() -> Unit) =
        MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label
            isAllCaps = false
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            minHeight = dp(48)
            setOnClickListener { action() }
        }

    private fun sheetRowParams(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun launch(launcher: Launcher) {
        val repository = (application as CollieApplication).container.repository
        val launchScope = viewModel.currentScope
        val launchContext = repository.readContext(launchScope) ?: return
        val currentActor = launchingCommands[launcher.command]
        if (currentActor != null) {
            if (repository.isCurrent(currentActor.second)) return
            launchingCommands.remove(launcher.command)
        }
        val actor = UUID.randomUUID().toString()
        launchingCommands[launcher.command] = actor to launchContext
        render(viewModel.state.value)
        lifecycleScope.launch {
            try {
                if (!confirmLauncher(this@MainActivity, launcher, launchScope.host ?: launchContext.connection.origin.value)) return@launch
                if (!repository.isCurrent(launchContext) || viewModel.currentScope != launchScope) return@launch
                val unresolved = repository.unresolvedLaunchIntents(launchScope).firstOrNull()
                if (unresolved != null) {
                    showUnknownLaunchIntent(unresolved)
                    return@launch
                }
                val result = if (viewModel.state.value.receiptContextBound == true) {
                    val target = launcher.target ?: if (launcher.harness != null) StartTarget(harness = launcher.harness) else StartTarget(command = launcher.command)
                    val requestId = UUID.randomUUID().toString()
                    val intentResult = viewModel.launchIntent(target, requestId, launcher.cwd, launcher.noPrompts, launchScope)
                    if (!repository.isCurrent(launchContext) || viewModel.currentScope != launchScope) return@launch
                    handleLaunchIntentOutcome(intentResult, requestId, launchScope, launchContext)
                    return@launch
                } else {
                    // Old/mixed-version bridges retain their established launch path, without a
                    // new owner-bound retry affordance.
                    if (launcher.target?.shell == true || launcher.harness != null) {
                        ApiResult.Failure(com.lateapex.collie.network.ApiFailure.Http(404, getString(R.string.launch_target_unavailable), "capability.unsupported"))
                    } else viewModel.launch(launcher.command)
                }
                if (!repository.isCurrent(launchContext) || viewModel.currentScope != launchScope) return@launch
                handleCreate(result, launchScope)
            } finally {
                if (launchingCommands[launcher.command]?.first == actor) {
                    launchingCommands.remove(launcher.command)
                    if (repository.isCurrent(launchContext) && viewModel.currentScope == launchScope) render(viewModel.state.value)
                }
            }
        }
    }

    private fun activeLaunchingCommands(): Set<String> {
        val repository = (application as CollieApplication).container.repository
        launchingCommands.entries.removeAll { !repository.isCurrent(it.value.second) }
        return launchingCommands.keys.toSet()
    }

    private fun showUnknownLaunchIntent(record: com.lateapex.collie.data.StoredLaunchIntent) {
        val repository = (application as CollieApplication).container.repository
        val scope = viewModel.currentScope
        val context = repository.readContext(scope) ?: return
        if (!viewModel.state.value.unknownLaunchIntents.any { it.intent.requestId == record.intent.requestId }) return
        val dialog = CollieBottomSheetDialog(this, getString(R.string.launch_outcome_unknown_title))
        val body = sheetBody()
        body.addView(TextView(this).apply {
            setText(R.string.launch_outcome_unknown_body)
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.collie_foreground))
            setPadding(0, dp(8), 0, dp(8))
        }, sheetRowParams())
        body.addView(TextView(this).apply {
            text = getString(R.string.launch_request_id, record.intent.requestId)
            textSize = 11f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.collie_muted))
        }, sheetRowParams())
        val retry = sheetActionButton(getString(R.string.launch_retry_same_request)) {
            if (!repository.isCurrent(context) || viewModel.currentScope != scope) return@sheetActionButton
            if (retryingLaunchIntent && retryingLaunchContext?.let(repository::isCurrent) == true) return@sheetActionButton
            val actor = UUID.randomUUID().toString()
            retryingLaunchActor = actor
            retryingLaunchContext = context
            retryingLaunchIntent = true
            dialog.dismiss()
            lifecycleScope.launch {
                try {
                    if (record.intent.operation == "launch") {
                        val result = viewModel.retryLaunchIntent(record.intent.requestId, scope)
                        if (repository.isCurrent(context) && viewModel.currentScope == scope) {
                            finishRetryingLaunchIntent(actor)
                            handleLaunchIntentOutcome(result, record.intent.requestId, scope, context)
                        }
                    } else if (record.intent.operation == "worktree.createAt" || record.intent.operation == "worktree.create") {
                        val result = viewModel.retryWorktreeIntent(record.intent.requestId, scope)
                        if (repository.isCurrent(context) && viewModel.currentScope == scope) {
                            finishRetryingLaunchIntent(actor)
                            handleWorktreeIntentOutcome(result, record.intent.requestId, scope, context)
                        }
                    } else if (record.intent.operation == "launcher.add") {
                        val result = viewModel.retryAddedLauncherIntent(record.intent.requestId, scope)
                        if (repository.isCurrent(context) && viewModel.currentScope == scope) {
                            finishRetryingLaunchIntent(actor)
                            val unresolved = viewModel.state.value.unknownLaunchIntents.firstOrNull { it.intent.requestId == record.intent.requestId }
                            if (unresolved != null) showUnknownLaunchIntent(unresolved) else when (result) {
                                is ApiResult.Success -> if (result.value.ok) {
                                    dialog.dismiss()
                                    viewModel.report(getString(R.string.launcher_add_confirmed))
                                } else viewModel.report(result.value.error ?: getString(R.string.launch_outcome_unknown_notice))
                                is ApiResult.Failure -> viewModel.report(MainViewModel.describe(resources, result.error))
                                is ApiResult.NotModified -> viewModel.report(getString(R.string.launch_outcome_unknown_notice))
                            }
                        }
                    }
                } finally {
                    if (retryingLaunchActor == actor) {
                        retryingLaunchActor = null
                        retryingLaunchContext = null
                        retryingLaunchIntent = false
                        if (repository.isCurrent(context) && viewModel.currentScope == scope) render(viewModel.state.value)
                    }
                }
            }
        }
        val canReplaySameId = record.intent.receiptMode == com.lateapex.collie.data.LaunchIntentReceiptMode.ownerBound
        retry.visibility = if (canReplaySameId) View.VISIBLE else View.GONE
        retry.isEnabled = !retryingLaunchIntent
        if (canReplaySameId) body.addView(retry, sheetRowParams(top = 12)) else body.addView(TextView(this).apply {
            setText(R.string.launch_legacy_unknown_inspect)
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.collie_foreground))
            setPadding(0, dp(8), 0, 0)
        }, sheetRowParams(top = 12))
        body.addView(sheetActionButton(getString(R.string.launch_abandon_request)) {
            if (!repository.isCurrent(context) || viewModel.currentScope != scope) return@sheetActionButton
            androidx.appcompat.app.AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.launch_abandon_title)
                .setMessage(R.string.launch_abandon_duplicate_warning)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.launch_abandon_confirm) { _, _ ->
                    if (!repository.isCurrent(context) || viewModel.currentScope != scope) return@setPositiveButton
                    val result = viewModel.abandonLaunchIntent(record.intent.requestId, scope)
                    if (!repository.isCurrent(context) || viewModel.currentScope != scope) return@setPositiveButton
                    when (result) {
                        is ApiResult.Success -> {
                            dialog.dismiss()
                            viewModel.report(getString(R.string.launch_abandoned_notice))
                        }
                        is ApiResult.Failure -> viewModel.report(MainViewModel.describe(resources, result.error))
                        is ApiResult.NotModified -> viewModel.report(getString(R.string.launch_outcome_unknown_notice))
                    }
                }
                .show()
        }, sheetRowParams(top = 8))
        dialog.setSheetContent(body)
        dialog.show()
    }

    private fun finishRetryingLaunchIntent(actor: String) {
        if (retryingLaunchActor != actor) return
        retryingLaunchActor = null
        retryingLaunchContext = null
        retryingLaunchIntent = false
    }

    private fun handleLaunchIntentOutcome(
        result: ApiResult<com.lateapex.collie.network.CreateResponse>,
        requestId: String,
        scope: Scope,
        context: ScopedReadContext,
    ) {
        val repository = (application as CollieApplication).container.repository
        if (!repository.isCurrent(context) || viewModel.currentScope != scope) return
        val unresolved = viewModel.state.value.unknownLaunchIntents.firstOrNull { it.intent.requestId == requestId }
        if (unresolved != null) showUnknownLaunchIntent(unresolved) else handleCreate(result, scope)
    }

    private fun handleWorktreeIntentOutcome(
        result: ApiResult<com.lateapex.collie.network.WorktreeOpenResponse>,
        requestId: String,
        scope: Scope,
        context: ScopedReadContext,
    ) {
        val repository = (application as CollieApplication).container.repository
        if (!repository.isCurrent(context) || viewModel.currentScope != scope) return
        val unresolved = viewModel.state.value.unknownLaunchIntents.firstOrNull { it.intent.requestId == requestId }
        if (unresolved != null) showUnknownLaunchIntent(unresolved) else handleWorktree(result, scope)
    }

    private fun handleCreate(
        result: ApiResult<com.lateapex.collie.network.CreateResponse>,
        scope: Scope = viewModel.currentScope,
    ) {
        when (result) {
            is ApiResult.Success -> if (result.value.ok) {
                result.value.pane?.let { openCreatedPane(it, scope) }
                    ?: viewModel.report(getString(R.string.created_pane_missing))
            } else {
                viewModel.report(result.value.error ?: getString(R.string.space_create_failed))
            }
            is ApiResult.Failure -> viewModel.report(MainViewModel.describe(resources, result.error))
            is ApiResult.NotModified -> Unit
        }
    }

    private fun handleWorktree(
        result: ApiResult<com.lateapex.collie.network.WorktreeOpenResponse>,
        scope: Scope = viewModel.currentScope,
    ) {
        when (result) {
            is ApiResult.Success -> if (result.value.ok) {
                result.value.pane?.let { openCreatedPane(it, scope) }
                    ?: viewModel.report(getString(R.string.worktree_pane_missing))
            } else {
                viewModel.report(result.value.error ?: getString(R.string.worktree_open_failed))
            }
            is ApiResult.Failure -> viewModel.report(MainViewModel.describe(resources, result.error))
            is ApiResult.NotModified -> Unit
        }
    }

    private fun openCreatedPane(pane: CreatedPane) = openCreatedPane(pane, viewModel.currentScope)

    private fun openCreatedPane(pane: CreatedPane, scope: Scope) {
        startActivity(Intent(this, PaneActivity::class.java).apply {
            putExtra(PaneActivity.EXTRA_PANE_ID, pane.paneId)
            putExtra(PaneActivity.EXTRA_HOST, scope.host)
            putExtra(PaneActivity.EXTRA_SESSION, scope.session)
            putExtra(PaneActivity.EXTRA_TITLE, pane.workspaceLabel)
            putExtra(PaneActivity.EXTRA_META, getString(R.string.pane_shell_meta, pane.workspaceLabel))
            putExtra(PaneActivity.EXTRA_AGENT, "shell")
            putExtra(PaneActivity.EXTRA_CWD, pane.cwd)
            putExtra(PaneActivity.EXTRA_TAB_LABEL, pane.tabId)
            putExtra(PaneActivity.EXTRA_STATUS, "unknown")
            putExtra(PaneActivity.EXTRA_FRESH_PANE, true)
        })
    }

    private suspend fun <T> structuralRequest(
        @androidx.annotation.StringRes progressRes: Int,
        block: suspend () -> T,
    ): T? {
        val context = repository.readContext(viewModel.currentScope) ?: return null
        val actor = UUID.randomUUID().toString()
        structuralActor = actor
        structuralBusy = true
        structuralProgressRes = progressRes
        render(viewModel.state.value)
        return try {
            block().takeIf { repository.isCurrent(context) }
        } finally {
            if (structuralActor == actor) {
                structuralActor = null
                structuralBusy = false
                render(viewModel.state.value)
            }
        }
    }

    private fun revalidatedStructuralScope(scope: Scope): Scope? {
        val snapshot = viewModel.state.value.snapshot
        val target = snapshot?.let { NewSpaceHostModel.revalidateScope(it, scope) }
        if (target == null) viewModel.report(getString(R.string.space_new_machine_changed))
        return target
    }

    private fun defaultDeviceLabel(): String {
        val model = android.os.Build.MODEL.trim().take(40)
        val suffix = NativePreferences(this).pairingLabelSuffix
        return listOf(model, suffix).filter(String::isNotBlank).joinToString("-")
    }

    private fun isEligibleWorktreeRoot(workspace: WorkspaceSummary): Boolean =
        workspace.repoRoot != null && workspace.isWorktree == false

    private companion object {
        const val COLLAPSE_THRESHOLD = 8
        const val IDLE_MS = 30 * 60_000L
        const val CATCH_UP_CAP_MS = 8_000L
    }
}
