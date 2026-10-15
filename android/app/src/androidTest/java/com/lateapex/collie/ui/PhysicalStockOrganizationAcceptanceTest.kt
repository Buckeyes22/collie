package com.lateapex.collie.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.RadioButton
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.data.CollieRepository
import com.lateapex.collie.data.ScopedReadContext
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.OriginValidator
import com.lateapex.collie.network.PaneSummary
import com.lateapex.collie.network.SnapshotResponse
import com.lateapex.collie.network.TabSummary
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Physical acceptance for ordinary stock UI organization. Every structural target is created by this test. */
@RunWith(AndroidJUnit4::class)
class PhysicalStockOrganizationAcceptanceTest {
    private data class Live(
        val app: CollieApplication,
        val repository: CollieRepository,
        val scope: Scope,
        val paneId: String,
        val root: String,
        val connection: Connection,
        val priorScope: Scope,
        val priorSubject: String?,
    )

    private fun live(): Live {
        val args = InstrumentationRegistry.getArguments()
        val originInput = args.getString("liveOrigin")
        assumeTrue("liveOrigin was not supplied; stock organization acceptance is opt-in", originInput != null)
        assertFalse("Supplied liveOrigin must not be blank", originInput.isNullOrBlank())
        val origin = OriginValidator().requireValid(requireNotNull(originInput))
        assertEquals("Only the fixed stock completion HTTPS origin is accepted", OriginValidator().requireValid(REQUIRED_ORIGIN), origin)
        val paneId = args.getString("livePaneId")?.trim()
        assertTrue("Supply the existing disposable fixture pane", !paneId.isNullOrEmpty())
        assertTrue("Pane id must be bounded", requireNotNull(paneId).matches(Regex("[A-Za-z0-9:._-]{1,128}")))
        val root = args.getString("liveRepoPath")?.trim()
        assertTrue("Supply the owned dated Git fixture root", root?.matches(PhysicalFixturePaths.datedRootPattern) == true)

        val app = ApplicationProvider.getApplicationContext<Context>().applicationContext as CollieApplication
        assertTrue("Stock organization acceptance requires the isolated completion app", app.packageName.endsWith(".completiontest"))
        val repository = app.container.repository
        val connection = app.container.connectionStore.connection.value
        assertTrue("The existing stock completion pairing must remain installed", connection?.isPaired == true)
        assertEquals("The installed pairing must match the exact completion origin", origin, connection?.origin)
        val scope = Scope(
            host = args.getString("liveHost")?.trim()?.takeIf(String::isNotEmpty),
            session = args.getString("liveSession")?.trim()?.takeIf(String::isNotEmpty),
        )
        val priorScope = repository.selectedScope.value
        val priorSubject = repository.readContext()?.key?.subjectIdentity
        assertEquals("The host-owned stock fixture must be selected in the native repository", scope, priorScope)
        val live = Live(app, repository, scope, requireNotNull(paneId), requireNotNull(root), requireNotNull(connection), priorScope, priorSubject)
        val actor = requireNotNull(repository.readContext(scope, paneId)) { "Existing fixture pane must have a current actor" }
        val snapshot = snapshot(live, actor)
        val target = exactPane(snapshot, scope, paneId)
        assertEquals("The existing fixture pane must be in its exact Git fixture root", root, target.cwd)
        assertTrue("Only a stock shell pane is a structural fixture", target.agent == "shell" || target.kind == "shell")
        assertTrue("Fixture actor must remain current after its inventory read", repository.isCurrent(actor))
        return live
    }

    /** Passive native navigation must preserve host focus; createTab is background, then explicit focus is user driven. */
    @Test
    fun nativeCreateBesideKeepsFocusUntilShowInTerminalAndClosesOnlyItsOwnTab() {
        val live = live()
        val initialActor = requireNotNull(live.repository.readContext(live.scope, live.paneId))
        val before = snapshot(live, initialActor)
        val target = exactPane(before, live.scope, live.paneId)
        val initialFocus = before.panes().filter(PaneSummary::focused).map(PaneSummary::paneId).toSet()
        assertEquals("Stock fixture must report one focused terminal pane before navigation", 1, initialFocus.size)
        val reservedBefore = before.panes()
            .associate { it.paneId to (it.workspaceId to it.tabId) }
        val originalTabIds = before.tabs.map(TabSummary::tabId).toSet()
        val originalTabLabels = before.tabs.associate { it.tabId to it.label }
        val originalPaneIds = before.panes().map(PaneSummary::paneId).toSet()
        assertEquals("The disposable fixture pane must be the actual focused pane before testing create-beside", setOf(live.paneId), initialFocus)
        assertTrue("The stock fixture must contain protected pre-existing panes", reservedBefore.isNotEmpty())

        val configResult = runBlocking { live.repository.config(live.scope, initialActor) }
        assertTrue("Live stock mux configuration must be available", configResult is ApiResult.Success)
        val mux = requireNotNull((configResult as ApiResult.Success).value.mux) { "The stock host must report its actual mux capabilities" }
        assertTrue("The stock API must positively declare createTab", mux.capabilities["createTab"] == true)
        assertTrue("The stock API must positively declare renameTab", mux.capabilities["renameTab"] == true)
        assertTrue("The stock API must positively declare closeTab", mux.capabilities["closeTab"] == true)
        assertTrue("Stock mux config actor must remain current", live.repository.isCurrent(initialActor))

        var createdPaneId: String? = null
        var createdTabId: String? = null
        try {
            ActivityScenario.launch<PaneActivity>(paneIntent(live, live.paneId, target.tabLabel, target.cwd)).use { originalScenario ->
                await(originalScenario, "passive read-only pane navigation is live") { activity ->
                    val paneActor = live.repository.readContext(live.scope, live.paneId)
                    val cwd = activity.findViewById<TextView>(R.id.pane_cwd)?.text?.toString()
                    val create = activity.findViewById<View>(R.id.new_tab_button)
                    val reveal = activity.findViewById<View>(R.id.show_tabs_button)
                    paneActor != null && live.repository.isCurrent(paneActor) &&
                        uiPathMatches(live.root, cwd) &&
                        ((create?.isShown == true && create.isEnabled) || (reveal?.isShown == true && reveal.isEnabled))
                }
                originalScenario.onActivity { activity ->
                    ensureTabStripVisible(activity)
                    assertTrue("Supported createTab must be exposed through the native New tab control",
                        activity.findViewById<View>(R.id.new_tab_button).isShown)
                }
                await(originalScenario, "loaded current pane exposes an enabled native New tab control") { activity ->
                    val actor = live.repository.readContext(live.scope, live.paneId)
                    actor != null && live.repository.isCurrent(actor) &&
                        activity.findViewById<View>(R.id.new_tab_button)?.let { it.isShown && it.isEnabled } == true
                }
                val afterPassiveNavigation = awaitSnapshot(live, live.paneId, "passive navigation preserves focus") { it.panes().any { pane -> pane.paneId == live.paneId } }
                assertEquals("Opening the native pane screen must not focus a terminal pane", initialFocus,
                    afterPassiveNavigation.panes().filter(PaneSummary::focused).map(PaneSummary::paneId).toSet())

                originalScenario.onActivity { activity ->
                    val create = activity.findViewById<View>(R.id.new_tab_button)
                    assertTrue("Native New tab control must be enabled for the existing fixture workspace", create.isEnabled)
                    assertTrue("New tab must use the actual native click handler", create.performClick())
                }
                val createdActivity = awaitResumedPane("created adjacent tab opens as a loaded current PaneActivity") {
                    val id = it.intent.getStringExtra(PaneActivity.EXTRA_PANE_ID)?.takeIf(String::isNotBlank)
                    val actor = id?.let { paneId -> live.repository.readContext(live.scope, paneId) }
                    val cwd = it.findViewById<TextView>(R.id.pane_cwd)?.text?.toString()
                    val create = it.findViewById<View>(R.id.new_tab_button)
                    val reveal = it.findViewById<View>(R.id.show_tabs_button)
                    id != null && id !in originalPaneIds && actor != null && live.repository.isCurrent(actor) &&
                        uiPathMatches(live.root, cwd) &&
                        ((create?.isShown == true && create.isEnabled) || (reveal?.isShown == true && reveal.isEnabled))
                }
                createdPaneId = requireNotNull(createdActivity.intent.getStringExtra(PaneActivity.EXTRA_PANE_ID))
                val afterCreate = awaitSnapshot(live, createdPaneId!!, "native createTab appears in fresh inventory") {
                    val panes = it.panes()
                    val new = panes.filter { pane -> pane.paneId !in originalPaneIds }
                    new.size == 1 && new.single().paneId == createdPaneId && it.tabs.any { tab -> tab.tabId !in originalTabIds }
                }
                val newPane = exactPane(afterCreate, live.scope, createdPaneId!!)
                assertTrue("Create must add exactly one tab without removing any existing pane",
                    afterCreate.panes().map(PaneSummary::paneId).toSet().containsAll(originalPaneIds))
                assertTrue("Create must preserve every pre-existing tab", afterCreate.tabs.map(TabSummary::tabId).toSet().containsAll(originalTabIds))
                assertEquals("Background create must leave the terminal's prior focus unchanged", initialFocus,
                    afterCreate.panes().filter(PaneSummary::focused).map(PaneSummary::paneId).toSet())
                assertFalse("New tab must not report focused before explicit action", newPane.focused)
                val newTab = afterCreate.tabs.single { it.tabId !in originalTabIds }
                assertEquals("Created shell must be the only pane in its own disposable tab", 1, newTab.paneCount)
                createdTabId = newTab.tabId
                assertEquals("Create-beside must keep the same stock workspace", target.workspaceId, newPane.workspaceId)
                assertEquals("The created pane must belong to its one new tab", newTab.tabId, newPane.tabId)
                assertTrue("The created pane must be in the fixture repository root", newPane.cwd == live.root)
                val ownedPaneId = requireNotNull(createdPaneId)
                val ownedTabId = requireNotNull(createdTabId)

                InstrumentationRegistry.getInstrumentation().runOnMainSync { ensureTabStripVisible(createdActivity) }
                awaitResumedPane("created pane exposes an enabled native New tab control") {
                    it.intent.getStringExtra(PaneActivity.EXTRA_PANE_ID) == ownedPaneId &&
                        it.findViewById<View>(R.id.new_tab_button)?.let { button -> button.isShown && button.isEnabled } == true
                }
                awaitCurrentTabChip(live, createdActivity, ownedPaneId)

                focusThroughNativePaneActions(createdActivity)
                val afterFocus = awaitSnapshot(live, ownedPaneId, "explicit Show in terminal moves focus to the created pane") {
                    it.panes().singleOrNull { pane -> pane.paneId == ownedPaneId }?.focused == true
                }
                assertEquals("Explicit focus must name only the test-created pane", setOf(ownedPaneId),
                    afterFocus.panes().filter(PaneSummary::focused).map(PaneSummary::paneId).toSet())

                val newLabel = "cc13-${UUID.randomUUID().toString().take(8)}"
                renameActiveTabThroughNativeUi(createdActivity, newTab, newLabel)
                val afterRename = awaitSnapshot(live, ownedPaneId, "actual native rename appears in upstream tab inventory") {
                    it.tabs.singleOrNull { tab -> tab.tabId == ownedTabId }?.label == newLabel
                }
                awaitCurrentTabChip(live, createdActivity, ownedPaneId)
                assertEquals("Rename must change only the created tab", newLabel,
                    afterRename.tabs.single { it.tabId == ownedTabId }.label)
                assertTrue("Rename must preserve all pre-existing tabs", afterRename.tabs.map(TabSummary::tabId).toSet().containsAll(originalTabIds))
                assertEquals("Rename must not change any pre-existing tab label", originalTabLabels,
                    afterRename.tabs.filter { it.tabId in originalTabIds }.associate { it.tabId to it.label })

                closeActiveTabThroughNativeUi(createdActivity, newLabel)
                val restoredPaneActivity = awaitResumedPane("closing the owned disposable tab returns to an existing fixture pane") {
                    it.intent.getStringExtra(PaneActivity.EXTRA_PANE_ID) in originalPaneIds
                }
                val afterClose = awaitSnapshot(live, live.paneId, "created tab is removed while fixture inventory survives") {
                    ownedTabId !in it.tabs.map(TabSummary::tabId) && ownedPaneId !in it.panes().map(PaneSummary::paneId)
                }
                assertTrue("Close must preserve all pre-existing pane IDs", afterClose.panes().map(PaneSummary::paneId).toSet().containsAll(originalPaneIds))
                assertTrue("Close must preserve all pre-existing tab IDs", afterClose.tabs.map(TabSummary::tabId).toSet().containsAll(originalTabIds))
                assertEquals("Close must preserve every pre-existing tab label", originalTabLabels,
                    afterClose.tabs.filter { it.tabId in originalTabIds }.associate { it.tabId to it.label })
                assertTrue("Close must return navigation to a pre-existing fixture pane",
                    restoredPaneActivity.intent.getStringExtra(PaneActivity.EXTRA_PANE_ID) in originalPaneIds)
                assertEquals("Test-created focus must not remain after its tab is closed", initialFocus,
                    afterClose.panes().filter(PaneSummary::focused).map(PaneSummary::paneId).toSet())
                assertEquals("Every pre-existing stock pane must retain its original workspace/tab address", reservedBefore,
                    afterClose.panes().filter { it.paneId in reservedBefore.keys }.associate { it.paneId to (it.workspaceId to it.tabId) })
            }
        } catch (failure: Throwable) {
            val inventory = awaitSnapshotOrNull(live, createdPaneId ?: live.paneId)
                ?: awaitSnapshotOrNull(live, live.paneId)
            Log.e(TAG, "stockOrganizationFailure=${failure::class.java.simpleName} createdPaneId=$createdPaneId createdTabId=$createdTabId " +
                "paneIds=${inventory?.panes()?.map(PaneSummary::paneId).orEmpty()} tabIds=${inventory?.tabs?.map(TabSummary::tabId).orEmpty()} " +
                "focusedPaneIds=${inventory?.panes()?.filter(PaneSummary::focused)?.map(PaneSummary::paneId).orEmpty()}")
            throw failure
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val current = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).singleOrNull()
                current?.finish()
                live.repository.selectSubject(live.priorScope, live.priorSubject)
            }
            assertTrue("Organization test must preserve the exact original pairing", live.app.container.connectionStore.connection.value === live.connection)
        }
    }

    /** Native New exposes host-owned Recent and Favourites rows; only a fixture path is starred, then restored. */
    @Test
    fun nativeFolderPickerSelectsRecentAndRestoresFixtureFavouriteState() {
        val live = live()
        val actor = requireNotNull(live.repository.readContext(live.scope, live.paneId))
        val foldersResult = runBlocking { live.repository.folders(live.scope, actor) }
        assertTrue("The live host folder list must be available", foldersResult is ApiResult.Success)
        val beforeFolders = (foldersResult as ApiResult.Success).value
        assertTrue("The fixture root must already be in the host's recent or favourite list",
            live.root in beforeFolders.recent || live.root in beforeFolders.favourites)
        assertTrue("Folder-list actor must remain current", live.repository.isCurrent(actor))
        val launchersResult = runBlocking { live.repository.launchers(live.scope, actor) }
        assertTrue("The live host starter catalog must be available", launchersResult is ApiResult.Success)
        val candidates = NewLaunchModel.choices((launchersResult as ApiResult.Success).value)
            .filter { it.available && it.target.shell == true && it.cwd.isNullOrBlank() }
        assertTrue("Stock host must provide an unpinned shell target for the native folder picker", candidates.isNotEmpty())
        assertTrue("Starter catalog actor must remain current", live.repository.isCurrent(actor))
        val candidate = candidates.first()
        val originalFavorite = live.root in beforeFolders.favourites

        try {
            ActivityScenario.launch<NewActivity>(NewActivity.intent(live.app, live.scope)).use { scenario ->
            await(scenario, "native New screen loads its actual folder choices") { activity ->
                radioChoices(activity).any { it.text.toString() == candidate.label } &&
                    activity.findViewById<View>(android.R.id.content).isShown
            }
            scenario.onActivity { activity ->
                val target = radioChoices(activity).single { it.text.toString() == candidate.label }
                assertTrue("Folder picker target must be an actual available native radio choice", target.isEnabled)
                assertTrue("Selecting the shell target must reveal the real native folder picker", target.performClick())
            }
            await(scenario, "fixture folder appears in native Recent or Favourites") { activity ->
                starButton(activity, live.root) != null
            }
            scenario.onActivity { activity ->
                val star = requireNotNull(starButton(activity, live.root))
                assertEquals("Fixture row must appear under the actual upstream section",
                    if (originalFavorite) activity.getString(R.string.folder_favourites) else activity.getString(R.string.folder_recent),
                    folderSection(star)?.text?.toString())
            }

            val firstAction = if (originalFavorite) R.string.folder_unstar_accessibility else R.string.folder_star_accessibility
            scenario.onActivity { activity ->
                val button = requireNotNull(starButton(activity, live.root, activity.getString(firstAction, live.root)))
                assertTrue("Native favourite action must be enabled for the host-owned fixture path", button.isEnabled)
                assertTrue("Favourite state must change through the actual native row button", button.performClick())
            }
            val nextAction = if (originalFavorite) R.string.folder_star_accessibility else R.string.folder_unstar_accessibility
            await(scenario, "native favourite change is reflected by the same fixture path") { activity ->
                starButton(activity, live.root, activity.getString(nextAction, live.root))?.isEnabled == true
            }
            if (originalFavorite) {
                scenario.onActivity { activity ->
                    val button = requireNotNull(starButton(activity, live.root, activity.getString(R.string.folder_star_accessibility, live.root)))
                    assertEquals("Removing a favourite returns that row to actual Recent", activity.getString(R.string.folder_recent), folderSection(button)?.text?.toString())
                    assertTrue(button.performClick())
                }
            } else {
                scenario.onActivity { activity ->
                    val button = requireNotNull(starButton(activity, live.root, activity.getString(R.string.folder_unstar_accessibility, live.root)))
                    assertTrue(button.performClick())
                }
            }
            await(scenario, "favourite state is restored through the native folder picker") { activity ->
                (starButton(activity, live.root, activity.getString(if (originalFavorite) R.string.folder_unstar_accessibility else R.string.folder_star_accessibility, live.root))?.isEnabled == true)
            }
            scenario.onActivity { activity ->
                val button = requireNotNull(starButton(activity, live.root))
                assertEquals("Native folder UI must restore the original favourite state",
                    if (originalFavorite) activity.getString(R.string.folder_favourites) else activity.getString(R.string.folder_recent),
                    folderSection(button)?.text?.toString())
                val row = descendants(activity.findViewById(android.R.id.content)).single {
                    it.isShown && it.contentDescription?.toString() == activity.getString(R.string.folder_select_accessibility, live.root)
                }
                assertTrue("Fixture path must be selected through its actual native Recent/Favourites row", row.isEnabled && row.performClick())
                val folderInput = descendants(activity.findViewById(android.R.id.content)).filterIsInstance<EditText>().single {
                    it.contentDescription?.toString() == activity.getString(R.string.new_folder_manual_accessibility)
                }
                assertEquals("Selecting the host-owned folder row must set the exact fixture path", live.root, folderInput.text.toString())
            }
            }
        } catch (failure: Throwable) {
            val currentActor = live.repository.readContext(live.scope, null)
            val currentFolders = currentActor?.let { actor ->
                runBlocking { live.repository.folders(live.scope, actor) }
                    .let { result -> (result as? ApiResult.Success)?.value }
            }
            Log.e(TAG, "stockFolderPickerFailure=${failure::class.java.simpleName} fixtureRoot=${live.root} " +
                "expectedFavorite=$originalFavorite actualFavorite=${currentFolders?.let { live.root in it.favourites }} " +
                "actualRecent=${currentFolders?.let { live.root in it.recent }}")
            throw failure
        }
        val restoredActor = requireNotNull(live.repository.readContext(live.scope, null)) { "Native New page must retain a current scoped read actor" }
        assertTrue("Native folder interaction must retain its current actor", live.repository.isCurrent(restoredActor))
        assertTrue("Native folder interaction must preserve the exact installed connection", live.app.container.connectionStore.connection.value === live.connection)
        val restoredFolders = runBlocking { live.repository.folders(live.scope, restoredActor) }
        assertTrue("After native favourite restore the host folder list remains readable", restoredFolders is ApiResult.Success)
        val finalFolders = (restoredFolders as ApiResult.Success).value
        assertEquals("Favorite acceptance must restore the fixture path's original favourite state", originalFavorite,
            live.root in finalFolders.favourites)
        assertTrue("Removing a test favourite must leave the fixture in Recent", originalFavorite || live.root in finalFolders.recent)
        assertTrue("Native folder acceptance must preserve the original pairing", live.app.container.connectionStore.connection.value === live.connection)
    }

    private fun focusThroughNativePaneActions(activity: PaneActivity) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val menu = activity.findViewById<View>(R.id.refresh_button)
            assertTrue("Pane actions menu must be available on the selected fixture pane", menu.isShown && menu.isEnabled)
            assertTrue("Show in terminal must be reached from the actual Pane actions menu", menu.performClick())
        }
        onView(withText(R.string.pane_action_focus)).inRoot(isDialog()).perform(click())
    }

    private fun renameActiveTabThroughNativeUi(activity: PaneActivity, tab: TabSummary, label: String) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ensureTabStripVisible(activity)
            val chip = activeTabChip(activity, tab)
            assertTrue("Created tab actions must open through its actual active tab chip", chip.performClick())
        }
        onView(withText(R.string.tab_action_rename)).inRoot(isDialog()).perform(click())
        onView(isAssignableFrom(EditText::class.java)).inRoot(isDialog()).perform(replaceText(label))
        onView(withText(R.string.tab_action_rename)).inRoot(isDialog()).perform(click())
    }

    private fun closeActiveTabThroughNativeUi(activity: PaneActivity, label: String) {
        val tab = requireNotNull(currentSnapshotFor(activity).tabs.singleOrNull { it.label == label })
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ensureTabStripVisible(activity)
            assertTrue("Close must open actions for only the uniquely named test tab", activeTabChip(activity, tab).performClick())
        }
        onView(withText(R.string.tab_action_close)).inRoot(isDialog()).perform(click())
        val armed = activity.resources.getQuantityString(R.plurals.tab_close_again, 1, 1)
        onView(withText(armed)).inRoot(isDialog()).perform(click())
    }

    private fun currentSnapshotFor(activity: PaneActivity): SnapshotResponse {
        val app = activity.application as CollieApplication
        val scope = Scope(
            activity.intent.getStringExtra(PaneActivity.EXTRA_HOST)?.takeIf(String::isNotBlank),
            activity.intent.getStringExtra(PaneActivity.EXTRA_SESSION)?.takeIf(String::isNotBlank),
        )
        val paneId = requireNotNull(activity.intent.getStringExtra(PaneActivity.EXTRA_PANE_ID))
        val actor = requireNotNull(app.container.repository.readContext(scope, paneId))
        val result = runBlocking { app.container.repository.snapshot(scope, actor) }
        assertTrue("Current live tab inventory must be readable", result is ApiResult.Success)
        assertTrue("Current pane actor must remain admitted for tab actions", app.container.repository.isCurrent(actor))
        return (result as ApiResult.Success).value
    }

    private fun ensureTabStripVisible(activity: PaneActivity) {
        val reveal = activity.findViewById<View>(R.id.show_tabs_button)
        if (reveal?.isShown == true) assertTrue("Tab strip must open through its native Show tabs control", reveal.performClick())
    }

    private fun activeTabChip(activity: PaneActivity, tab: TabSummary): View {
        val label = tab.label.ifBlank { tab.number.toString() }
        val description = activity.getString(R.string.pane_tab_current_description, label)
        return descendants(activity.findViewById(R.id.tab_items)).single {
            it.isShown && it.contentDescription?.toString() == description
        }
    }

    private fun awaitCurrentTabChip(live: Live, activity: PaneActivity, paneId: String) {
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val actor = live.repository.readContext(live.scope, paneId)
            val snapshot = actor?.let { snapshotResult(live, it) }
            val pane = snapshot?.panes()?.singleOrNull { it.paneId == paneId }
            val tab = pane?.let { current -> snapshot?.tabs?.singleOrNull { it.tabId == current.tabId } }
            var visible = false
            if (tab != null && actor != null && live.repository.isCurrent(actor)) {
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    val description = activity.getString(R.string.pane_tab_current_description, tab.label.ifBlank { tab.number.toString() })
                    visible = descendants(activity.findViewById(R.id.tab_items)).any {
                        it.isShown && it.contentDescription?.toString() == description
                    }
                }
                if (visible) return
            }
            SystemClock.sleep(250)
        }
        throw AssertionError("Stock organization UI timed out: created tab chip is loaded and current")
    }

    private fun snapshotResult(live: Live, actor: ScopedReadContext): SnapshotResponse? =
        (runBlocking { live.repository.snapshot(live.scope, actor) } as? ApiResult.Success)?.value

    private fun uiPathMatches(root: String, visible: String?): Boolean {
        val value = visible?.trim()?.trimEnd('/') ?: return false
        return value == root || value == "~" + root.removePrefix(PhysicalFixturePaths.home)
    }

    private fun starButton(activity: NewActivity, path: String, description: String? = null): View? {
        val accepted = if (description != null) setOf(description) else setOf(
            activity.getString(R.string.folder_star_accessibility, path),
            activity.getString(R.string.folder_unstar_accessibility, path),
        )
        return descendants(activity.findViewById(android.R.id.content)).singleOrNull {
            it.isShown && it.contentDescription?.toString() in accepted
        }
    }

    private fun folderSection(button: View): TextView? {
        val row = button.parent as? ViewGroup ?: return null
        val picker = row.parent as? ViewGroup ?: return null
        val rowIndex = picker.indexOfChild(row)
        for (index in rowIndex - 1 downTo 0) {
            (picker.getChildAt(index) as? TextView)?.let { return it }
        }
        return null
    }

    private fun radioChoices(activity: NewActivity): List<RadioButton> = descendants(activity.findViewById(android.R.id.content))
        .filterIsInstance<RadioButton>().filter { it.isShown }

    private fun paneIntent(live: Live, paneId: String, title: String?, cwd: String?, agent: String = "shell") = Intent(live.app, PaneActivity::class.java)
        .putExtra(PaneActivity.EXTRA_PANE_ID, paneId)
        .putExtra(PaneActivity.EXTRA_AGENT, agent)
        .putExtra(PaneActivity.EXTRA_HOST, live.scope.host)
        .putExtra(PaneActivity.EXTRA_SESSION, live.scope.session)
        .putExtra(PaneActivity.EXTRA_TITLE, title ?: "Disposable stock organization fixture")
        .putExtra(PaneActivity.EXTRA_CWD, cwd)

    private fun awaitSnapshot(live: Live, subject: String, checkpoint: String, ready: (SnapshotResponse) -> Boolean): SnapshotResponse {
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            val actor = live.repository.readContext(live.scope, subject)
            if (actor != null) {
                val result = runBlocking { live.repository.snapshot(live.scope, actor) }
                if (result is ApiResult.Success && live.repository.isCurrent(actor) && ready(result.value)) return result.value
            }
            SystemClock.sleep(500)
        }
        throw AssertionError("Stock organization acceptance timed out: $checkpoint")
    }

    private fun awaitSnapshotOrNull(live: Live, subject: String): SnapshotResponse? {
        val deadline = SystemClock.uptimeMillis() + 5_000L
        while (SystemClock.uptimeMillis() < deadline) {
            val actor = live.repository.readContext(live.scope, subject)
            if (actor != null) {
                val result = runBlocking { live.repository.snapshot(live.scope, actor) }
                if (result is ApiResult.Success && live.repository.isCurrent(actor)) return result.value
            }
            SystemClock.sleep(500)
        }
        return null
    }

    private fun snapshot(live: Live, actor: ScopedReadContext): SnapshotResponse {
        val result = runBlocking { live.repository.snapshot(live.scope, actor) }
        assertTrue("Real host snapshot must succeed under the exact read actor", result is ApiResult.Success)
        assertTrue("Original read actor must remain current", live.repository.isCurrent(actor))
        return (result as ApiResult.Success).value
    }

    private fun exactPane(snapshot: SnapshotResponse, scope: Scope, paneId: String): PaneSummary =
        requireNotNull(snapshot.panes().singleOrNull { it.paneId == paneId && it.host == scope.host && it.session == scope.session }) {
            "Live snapshot must contain the exact scoped fixture pane"
        }

    private fun SnapshotResponse.panes() = agents + shellPanes

    private fun awaitResumedPane(checkpoint: String, ready: (PaneActivity) -> Boolean): PaneActivity {
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            var found: PaneActivity? = null
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<PaneActivity>().singleOrNull()
                if (activity != null && ready(activity)) found = activity
            }
            found?.let { return it }
            SystemClock.sleep(100)
        }
        throw AssertionError("Stock organization UI timed out: $checkpoint")
    }

    private fun <T : Activity> await(scenario: ActivityScenario<T>, checkpoint: String, ready: (T) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            var matched = false
            scenario.onActivity { matched = ready(it) }
            if (matched) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Stock organization UI timed out: $checkpoint")
    }

    private fun descendants(root: View): List<View> = buildList {
        add(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) addAll(descendants(root.getChildAt(index)))
    }

    companion object {
        private const val TAG = "CollieOrganizationAcceptance"
        private const val WAIT_MS = 45_000L
        private const val REQUIRED_ORIGIN = "https://prod.lateapexllc.com/completion/"
    }
}
