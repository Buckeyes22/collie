package com.lateapex.collie.ui

import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.NoMatchingViewException
import androidx.test.espresso.NoMatchingRootException
import androidx.test.espresso.Root
import androidx.test.espresso.UiController
import androidx.test.espresso.ViewAction
import androidx.test.espresso.action.GeneralLocation
import androidx.test.espresso.action.GeneralSwipeAction
import androidx.test.espresso.action.Press
import androidx.test.espresso.action.Swipe
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.google.android.material.button.MaterialButton
import com.lateapex.collie.BuildConfig
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.data.LaunchIntentReceiptMode
import com.lateapex.collie.data.LaunchIntentState
import com.lateapex.collie.data.LaunchIntentStore
import com.lateapex.collie.data.StoredLaunchIntent
import com.lateapex.collie.data.CommittedConnectionSnapshot
import com.lateapex.collie.data.ScopedReadContext
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.LaunchersResponse
import com.lateapex.collie.network.SnapshotResponse
import com.lateapex.collie.network.Worktree
import com.lateapex.collie.network.WorkspaceSummary
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.Matchers.allOf
import org.hamcrest.TypeSafeMatcher
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Real native controls and real paired reads only; every mutation comes from a visible UI tap.
 *
 * Opt in with liveOrigin=https://prod.lateapexllc.com/completion, liveRepoPath=ROOT and
 * liveWorkspaceId naming its existing main checkout. Only the isolated completiontest package
 * is accepted. Run the three selectors separately. Parent prepares a CLEAN, UNOPENED linked
 * checkout OPEN_PATH on branch OPEN_BRANCH using normal git worktree add before the open test.
 * The create test uses the explicitly supplied fixture home and CREATE_BRANCH; no force-removal is permitted.
 * Recipes require the operator's [phone] adds=true, free_text=true, run=true. The saved plain
 * command uses the existing harmless shell fixture, never an autonomous vendor-agent recipe.
 * This proves ordinary acknowledged mutations, not an interrupted/ambiguous-create outcome.
 * After a failed assertion following a successful Add, parent may supply recoverSavedRecipeId
 * for the exact persisted owned row. The successful original native journal is required; this
 * resumes normal Use/Rename/Remove/Run UI without issuing another Add or hiding the prior failure.
 */
@RunWith(AndroidJUnit4::class)
class PhysicalWorktreeRecipeAcceptanceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private var activeLive: Live? = null
    private data class Live(val app: CollieApplication, val connection: Connection, val scope: Scope,
        val workspace: String, val committed: CommittedConnectionSnapshot)

    @Test fun nativeAbandonOnlyTheExplicitlyOwnedDuplicateLauncherAddRequest() {
        assumeTrue("Set abandonOwnedDuplicate=true to authorize this local recovery action",
            args.getString("abandonOwnedDuplicate") == "true")
        assumeTrue("liveOrigin is required for the owned recovery fixture", args.getString("liveOrigin") != null)
        assertTrue("Only the isolated completion app may manage this request", BuildConfig.APPLICATION_ID.endsWith(".completiontest"))
        assertEquals(ROOT, required("liveRepoPath"))
        val workspace = required("liveWorkspaceId")
        assertEquals("w1", workspace)
        assertTrue("Worktrees remain on the local stock lead", args.getString("liveHost").isNullOrBlank())
        val suppliedOrigin = requireNotNull(args.getString("liveOrigin"))
        val app = ApplicationProvider.getApplicationContext<Context>().applicationContext as CollieApplication
        val origin = app.container.originValidator.requireValid(ORIGIN)
        assertEquals("Only the exact task-owned stock completion origin is accepted", origin,
            app.container.originValidator.requireValid(suppliedOrigin))
        val connection = requireNotNull(app.container.connectionStore.connection.value)
        assertTrue("The existing stock profile must remain paired", connection.isPaired)
        assertEquals(origin, connection.origin)
        val repository = app.container.repository
        val scope = Scope(session = args.getString("liveSession")?.takeIf(String::isNotBlank))
        assertEquals("The existing exact stock scope must already be selected", scope, repository.selectedScope.value)
        val committed = requireNotNull(app.container.connectionStore.captureCommitted())
        assertTrue("Recovery captures the current committed owner", committed.connection === connection && committed.incarnationId != null)
        val live = Live(app, connection, scope, workspace, committed)
        originalOwner(live)

        val initialActor = actor(live)
        val allBeforeMain = app.container.launchIntentStore.all()
        val duplicate = allBeforeMain.filter { row ->
            val intent = row.intent
            if (row.state != LaunchIntentState.unknown || intent.operation != "launcher.add" ||
                intent.receiptMode != LaunchIntentReceiptMode.ownerBound || intent.confirmedNoPrompts ||
                intent.origin != connection.origin.value || intent.host != scope.host || intent.session != scope.session ||
                intent.subjectIdentity != null || intent.pairedDeviceIdentity != initialActor.key.pairedDeviceIdentity ||
                intent.targetIdentity != "added-launcher-row" ||
                intent.bodySha256 != LaunchIntentStore.sha256(intent.canonicalBody)) return@filter false
            runCatching {
                val body = Json.parseToJsonElement(intent.canonicalBody).jsonObject
                body["label"]?.jsonPrimitive?.content == DUPLICATE_LABEL &&
                    body["text"]?.jsonPrimitive?.content == DUPLICATE_COMMAND &&
                    body["kind"]?.jsonPrimitive?.content == "command" &&
                    body["requestId"]?.jsonPrimitive?.content == intent.requestId
            }.getOrDefault(false)
        }
        assertEquals("Exactly one uncertain phone Add matches the exact owned duplicate operator launcher", 1, duplicate.size)
        val target = duplicate.single()
        val requestId = target.intent.requestId
        assertEquals("The selected unknown request ID is canonical", requestId, UUID.fromString(requestId).toString())
        assertEquals("The exact original owner-bound body remains pinned to the operator row", LaunchIntentStore.sha256(target.intent.canonicalBody), target.intent.bodySha256)

        val beforeSnapshot = snapshot(live)
        val beforePaneIds = paneIds(beforeSnapshot)
        val controlledWorkspace = beforeSnapshot.workspaces.singleOrNull { it.workspaceId == workspace }
        assertNotNull("The exact controlled stock workspace remains in the live snapshot", controlledWorkspace)
        assertEquals("The controlled workspace stays at the dated repository root", ROOT, controlledWorkspace?.repoRoot)
        assertEquals("The target is the main checkout, not a worktree", false, controlledWorkspace?.isWorktree)
        val beforeCatalog = catalog(live)
        val operatorRows = beforeCatalog.launchers.filter {
            it.source == "operator" && it.id == null && it.label == OPERATOR_LABEL &&
                it.command == DUPLICATE_COMMAND && !it.noPrompts
        }
        assertEquals("The actual host catalog still has exactly one original operator row", 1, operatorRows.size)
        val operatorRow = operatorRows.single()
        assertTrue("The saved operator row has no phone-added ID", operatorRow.id == null)
        assertFalse("The operator row is an ordinary command without no-prompts", operatorRow.noPrompts)
        val beforeRows = allBeforeMain
        val beforeIds = beforeRows.map { it.intent.requestId }.toSet()
        assertEquals("The test begins with the one owned request still unknown", LaunchIntentState.unknown,
            beforeRows.single { it.intent.requestId == requestId }.state)

        activeLive = live
        try {
            ActivityScenario.launch<MainActivity>(MainActivity::class.java).use { dashboard ->
                var originalActor: ScopedReadContext? = null
                await("The actual MainActivity must load the original global actor, live snapshot, and exact unknown request") {
                    originalOwner(live)
                    dashboard.onActivity { activity ->
                        val state = androidx.lifecycle.ViewModelProvider(activity)[MainViewModel::class.java].state.value
                        val actor = state.originalReadContext
                        val unresolved = state.unknownLaunchIntents.firstOrNull()
                        if (state.snapshot != null && state.lastSuccessAt != null && state.savedCopy == null &&
                            actor != null && actor.key.subjectIdentity == null && actor.requestScope == scope &&
                            actor.connection === connection && actor.key.pairedDeviceIdentity == initialActor.key.pairedDeviceIdentity &&
                            repository.isCurrent(actor) && unresolved?.intent?.requestId == requestId) {
                            originalActor = actor
                        }
                    }
                    originalActor != null
                }
                val owner = requireNotNull(originalActor)
                assertTrue("The current normal MainActivity global actor remains the original actor", repository.isCurrent(owner))
                assertEquals("Main navigation preserves the exact scope", scope, repository.selectedScope.value)
                assertEquals("Startup cannot silently change any saved intent row", beforeRows, app.container.launchIntentStore.all())

                val liveSnapshot = snapshot(live)
                assertEquals("The actual Main snapshot contains the exact same pane inventory", beforePaneIds, paneIds(liveSnapshot))
                val workspaceRow = liveSnapshot.workspaces.singleOrNull { it.workspaceId == workspace }
                assertNotNull("The controlled stock workspace remains present", workspaceRow)
                assertEquals(ROOT, workspaceRow?.repoRoot)
                assertEquals("The operator launcher catalog is unchanged before user confirmation", beforeCatalog, catalog(live))
                assertMainOwner(dashboard, live, owner, target)
                onView(allOf(withId(R.id.dashboard_error_text), withText(R.string.launch_outcome_unknown_notice)))
                    .check(matches(isDisplayed()))

                assertMainOwner(dashboard, live, owner, target)
                tap(withId(R.id.dashboard_error_text))
                val idLabel = live.app.getString(R.string.launch_request_id, requestId)
                onView(withText(idLabel)).check(matches(isDisplayed()))

                assertMainOwner(dashboard, live, owner, target)
                tap(withText(R.string.launch_abandon_request))
                onView(withText(R.string.launch_abandon_duplicate_warning)).check(matches(isDisplayed()))
                onView(withText(R.string.launch_abandon_confirm)).check(matches(isDisplayed()))

                // This is the only destructive step: the operator explicitly confirms abandoning
                // this one exact uncertain local request after the duplicate-risk disclosure.
                assertMainOwner(dashboard, live, owner, target)
                tap(withId(android.R.id.button1))
                await("The exact selected request is removed from the unresolved native journal") {
                    originalOwner(live)
                    repository.isCurrent(owner) &&
                        app.container.launchIntentStore.all().none { it.intent.requestId == requestId } &&
                        dashboardStateHasNoRequest(dashboard, live, owner, requestId)
                }
                originalOwner(live)
                val afterRows = app.container.launchIntentStore.all()
                assertEquals("Abandon removes only the selected local request; all other journal rows are unchanged",
                    beforeRows.filterNot { it.intent.requestId == requestId }, afterRows)
                assertEquals("Only the exact selected ID disappears", beforeIds - requestId,
                    afterRows.map { it.intent.requestId }.toSet())
                assertEquals("The original operator catalog row is unchanged", beforeCatalog, catalog(live))
                assertEquals("The actual pane inventory remains unchanged", beforePaneIds, paneIds(snapshot(live)))
                assertEquals("The stock workspace still has the exact original repository root", ROOT,
                    snapshot(live).workspaces.single { it.workspaceId == workspace }.repoRoot)
                originalOwner(live)
                instrumentation.sendStatus(2, Bundle().apply {
                    putString("liveAbandonedRequestId", requestId)
                    putString("stream", "Abandoned exactly the explicitly selected unknown local request through native confirmation.\n")
                })
            }
        } finally {
            activeLive = null
        }
    }

    private fun required(name: String) = args.getString(name)?.takeIf(String::isNotBlank)
        ?: error("Supplied liveOrigin requires explicit $name")

    private fun withLive(allowUnresolvedRequestId: String? = null, test: (Live) -> Unit) {
        val supplied = args.getString("liveOrigin")
        assumeTrue("No liveOrigin supplied; physical worktree/recipe acceptance is opt-in", supplied != null)
        assertFalse("A supplied liveOrigin must not be blank", supplied.isNullOrBlank())
        assertTrue("Only the isolated completion app may mutate this fixture", BuildConfig.APPLICATION_ID.endsWith(".completiontest"))
        assertEquals(ROOT, required("liveRepoPath"))
        val workspace = required("liveWorkspaceId")
        assertTrue(workspace.matches(Regex("[A-Za-z0-9:._-]{1,128}")))
        assertTrue("Worktrees must stay on the local stock lead", args.getString("liveHost").isNullOrBlank())
        val app = ApplicationProvider.getApplicationContext<Context>().applicationContext as CollieApplication
        val expected = app.container.originValidator.requireValid(ORIGIN)
        assertEquals("Acceptance origin must be the exact task-owned HTTPS mount", expected, app.container.originValidator.requireValid(requireNotNull(supplied)))
        val connection = requireNotNull(app.container.connectionStore.connection.value)
        assertTrue("Use the existing installed pairing", connection.isPaired)
        assertEquals(expected, connection.origin)
        val repository = app.container.repository
        val priorScope = repository.selectedScope.value
        val priorSubject = repository.readContext()?.key?.subjectIdentity
        val scope = Scope(session = args.getString("liveSession")?.takeIf(String::isNotBlank))
        val committed = requireNotNull(app.container.connectionStore.captureCommitted())
        assertTrue("The installed pair must have durable custody", committed.connection === connection && committed.incarnationId != null)
        val live = Live(app, connection, scope, workspace, committed)
        try {
            instrumentation.runOnMainSync { repository.selectSubject(scope, null) }
            val root = snapshot(live).workspaces.singleOrNull { it.workspaceId == workspace }
            assertNotNull("Exact existing main-checkout workspace must be published", root)
            assertEquals(ROOT, root?.repoRoot)
            assertEquals(false, root?.isWorktree)
            assertNull(root?.host)
            val unresolved = repository.unresolvedLaunchIntents(scope).map { it.intent.requestId }
            if (allowUnresolvedRequestId == null) assertTrue("Reconcile prior uncertain intents before creating new ones", unresolved.isEmpty())
            else assertEquals("Recovery permits only the explicitly selected original unknown request", listOf(allowUnresolvedRequestId), unresolved)
            activeLive = live
            test(live)
        } finally {
            activeLive = null
            assertSame("The original installed pairing must survive", connection, app.container.connectionStore.connection.value)
            instrumentation.runOnMainSync { repository.selectSubject(priorScope, priorSubject) }
        }
    }

    /** Parent holds the real POST /completion/api/worktree response and externally kills this process. */
    @Test fun holdNativeWorktreeCreateForExternalProcessDeath() {
        assumeTrue("Explicit nativeInterruptedWorktree=true is required", args.getString("nativeInterruptedWorktree") == "true")
        assertEquals("Parent must explicitly arm the real worktree response hold", "true", required("liveHoldForKill"))
        withLive { live ->
            interruptedScope(live)
            val beforeTrees = trees(live)
            assertTrue("The interrupted branch and target path must be entirely fresh",
                beforeTrees.none { it.branch == INTERRUPTED_BRANCH || it.path == INTERRUPTED_PATH })
            val beforePanes = paneIds(snapshot(live))
            val beforeRows = live.app.container.launchIntentStore.all()
            val beforeIds = beforeRows.map { it.intent.requestId }.toSet()
            ActivityScenario.launch<MainActivity>(MainActivity::class.java).use { dashboard ->
                val displayedActor = awaitInterruptedDashboard(dashboard, live)
                val config = runBlocking { live.app.container.repository.config(live.scope, displayedActor) }
                assertTrue("The real original stock host must declare owner-bound receipts and clean removal",
                    config is ApiResult.Success && config.value.receiptContextBound == true && config.value.mux?.supportsNew("removeWorktree") == true)
                assertTrue("The initial original dashboard actor remains current", live.app.container.repository.isCurrent(displayedActor))
                openWorktreeForm(live)
                fill(withId(R.id.worktree_branch_input), INTERRUPTED_BRANCH)
                fill(withHint(R.string.worktree_base_ref), "")
                fill(withHint(R.string.worktree_parent_folder), PARENT)
                tap(button(R.string.action_create), expectedActor = displayedActor)
                awaitFocusedNativeSheetTitle(R.string.worktree_plan_title)
                awaitView(allOf(withText(org.hamcrest.Matchers.startsWith("Repository: $ROOT\n")), withText(containsText(INTERRUPTED_BRANCH))))
                expandVisibleOwnedSheet(allOf(withId(R.id.collie_sheet_title), withText(R.string.worktree_plan_title))) {
                    interruptedCurrent(live, displayedActor)
                }
                tap(plainShellRadio(live.app.getString(R.string.launch_target_shell)), expectedActor = displayedActor)
                assertTrue("Read-only Plan and target review reserve no write UUID", beforeIds == ids(live))
                tap(button(R.string.worktree_plan_create), mutation = true, expectedActor = displayedActor, requireClickReady = true)
                var pending: StoredLaunchIntent? = null
                await("The single real native Create reserves one pending immutable request before response delivery") {
                    interruptedCurrent(live, displayedActor)
                    val added = live.app.container.launchIntentStore.all().filter { it.intent.requestId !in beforeIds }
                    assertTrue("One Create must not reserve multiple UUIDs", added.size <= 1)
                    pending = added.singleOrNull()?.takeIf { it.state == LaunchIntentState.pending }
                    pending != null
                }
                val record = requireNotNull(pending)
                assertInterruptedFrozen(live, record, displayedActor)
                assertTrue("The exact pending request must still precede the parent kill",
                    live.app.container.launchIntentStore.get(record.intent.requestId) == record)
                assertTrue("No other local operation record changes during dispatch",
                    journalHash(live.app.container.launchIntentStore.all().filterNot { it.intent.requestId == record.intent.requestId }) == journalHash(beforeRows))
                assertTrue("The original pane inventory is bounded", beforePanes.size in 1..200)
                instrumentation.sendStatus(2, Bundle().apply {
                    putString("liveRequestId", record.intent.requestId)
                    putString("liveBodySha256", record.intent.bodySha256)
                    putString("liveRequestState", "pending")
                    putString("liveBeforePaneIds", beforePanes.sorted().joinToString(","))
                    putString("liveBeforeWorktreesSha256", worktreesHash(beforeTrees))
                    putString("liveBeforeJournalSha256", journalHash(beforeRows))
                    putString("liveOwnerHash", LaunchIntentStore.sha256(record.intent.pairedDeviceIdentity))
                    putString("liveOldPid", android.os.Process.myPid().toString())
                    putString("liveWorktreePath", INTERRUPTED_PATH)
                    putString("stream", "Actual native worktree Create is pending; no response awaited. Parent must independently verify the held upstream receipt/tree/pane before external process death.\n")
                })
                // Preparation is intentionally interrupted by an external kill, never counted as
                // a passing test. Do not transition the store, retry, or dispatch another Create.
                SystemClock.sleep(60_000)
                fail("Parent did not externally kill the pending worktree process; no process-death proof exists")
            }
        }
    }

    @Test fun recoverSavedWorktreeCreateAfterActualProcessDeath() {
        assumeTrue("Explicit nativeInterruptedWorktree=true is required", args.getString("nativeInterruptedWorktree") == "true")
        val requestId = required("liveRequestId")
        assertEquals("The selected saved request UUID is canonical", requestId, UUID.fromString(requestId).toString())
        assertNotEquals("Recovery must run in a different actual process", required("liveOldPid").toInt(), android.os.Process.myPid())
        withLive(allowUnresolvedRequestId = requestId) { live ->
            interruptedScope(live)
            assertEquals("Only the exact interrupted checkout may be reconciled", INTERRUPTED_PATH, required("liveWorktreePath"))
            val createdId = required("liveCreatedPaneId")
            assertTrue("The actual receipt pane ID is bounded", createdId.matches(Regex("[A-Za-z0-9:._-]{1,128}")))
            val beforeText = required("liveBeforePaneIds")
            assertTrue("The original pane inventory is bounded", beforeText.length <= 16_384)
            val beforePanes = beforeText.split(',').toSet()
            assertTrue("The original pane IDs are all bounded", beforePanes.size in 1..200 && beforePanes.all { it.matches(Regex("[A-Za-z0-9:._-]{1,128}")) })
            assertFalse("The original inventory must not already contain the newly created pane", createdId in beforePanes)
            val bodyHash = requiredHash("liveBodySha256")
            val beforeTreeHash = requiredHash("liveBeforeWorktreesSha256")
            val beforeJournalHash = requiredHash("liveBeforeJournalSha256")
            val store = live.app.container.launchIntentStore
            val record = requireNotNull(store.get(requestId))
            assertEquals("Startup must recover the genuine interrupted Pending request as Unknown", LaunchIntentState.unknown, record.state)
            assertEquals("Recovery selects only the exact pre-kill body", bodyHash, record.intent.bodySha256)
            assertEquals("Recovery retains the exact original pairing owner", requiredHash("liveOwnerHash"), LaunchIntentStore.sha256(record.intent.pairedDeviceIdentity))
            assertInterruptedFrozen(live, record, actor(live))
            assertTrue("Every other local journal record remains unchanged across death",
                beforeJournalHash == journalHash(store.all().filterNot { it.intent.requestId == requestId }))
            val beforeIds = ids(live)
            assertOneNewPane(live, beforePanes, createdId, INTERRUPTED_PATH)
            assertInterruptedTree(live, createdId)
            assertTrue("The real first Create preserves every other checkout",
                beforeTreeHash == worktreesHash(trees(live).filterNot { it.path == INTERRUPTED_PATH }))
            ActivityScenario.launch<MainActivity>(MainActivity::class.java).use { dashboard ->
                val displayedActor = awaitInterruptedDashboard(dashboard, live, requestId)
                assertMainOwner(dashboard, live, displayedActor, record)
                tap(allOf(withId(R.id.dashboard_error_text), withText(R.string.launch_outcome_unknown_notice)), expectedActor = displayedActor)
                awaitFocusedNativeSheetTitle(R.string.launch_outcome_unknown_title)
                awaitView(withText(live.app.getString(R.string.launch_request_id, requestId)))
                assertMainOwner(dashboard, live, displayedActor, record)
                assertTrue("Opening the actual recovery sheet performs no automatic retry", store.get(requestId) == record && beforeIds == ids(live))
                assertOneNewPane(live, beforePanes, createdId, INTERRUPTED_PATH)
                assertTrue("Opening recovery preserves the one factual worktree",
                    beforeTreeHash == worktreesHash(trees(live).filterNot { it.path == INTERRUPTED_PATH }))
                assertMainOwner(dashboard, live, displayedActor, record)
                // Only the production retry handler may replay the saved canonical body and UUID.
                tap(button(R.string.launch_retry_same_request), mutation = true, expectedActor = displayedActor, requireClickReady = true)
                val pane = awaitPane()
                try {
                    assertEquals("Frozen receipt retry opens only the original result pane", createdId, pane.intent.getStringExtra(PaneActivity.EXTRA_PANE_ID))
                    assertEquals(INTERRUPTED_PATH, pane.intent.getStringExtra(PaneActivity.EXTRA_CWD))
                    assertEquals(live.scope.host, pane.intent.getStringExtra(PaneActivity.EXTRA_HOST))
                    assertEquals(live.scope.session, pane.intent.getStringExtra(PaneActivity.EXTRA_SESSION))
                    val succeeded = requireNotNull(store.get(requestId))
                    assertEquals("The same acknowledged retry becomes successful", LaunchIntentState.succeeded, succeeded.state)
                    assertTrue("Every byte and owner field of the original frozen intent is unchanged", record.intent == succeeded.intent)
                    assertTrue("Explicit Retry mints no other request ID", beforeIds == ids(live))
                    assertTrue("Explicit Retry changes no other local operation receipt",
                        beforeJournalHash == journalHash(store.all().filterNot { it.intent.requestId == requestId }))
                    assertOneNewPane(live, beforePanes, createdId, INTERRUPTED_PATH)
                    assertInterruptedTree(live, createdId)
                    assertTrue("Explicit Retry creates no additional worktree and changes no earlier checkout",
                        beforeTreeHash == worktreesHash(trees(live).filterNot { it.path == INTERRUPTED_PATH }))
                    originalOwner(live)
                    emit(succeeded, createdId)
                } finally { instrumentation.runOnMainSync { pane.finish() } }
            }
        }
    }

    /** Separate explicit cleanup after successful recovery; never runs automatically on failure. */
    @Test fun removeOnlyAcknowledgedInterruptedWorktreeThroughNativeUi() {
        assumeTrue("Explicit nativeInterruptedWorktreeCleanup=true is required", args.getString("nativeInterruptedWorktreeCleanup") == "true")
        withLive { live ->
            interruptedScope(live)
            assertEquals(INTERRUPTED_PATH, required("liveWorktreePath"))
            val requestId = required("liveRequestId")
            val createdId = required("liveCreatedPaneId")
            val record = requireNotNull(live.app.container.launchIntentStore.get(requestId))
            assertEquals("Only the exact acknowledged interrupted Create may be cleaned up", LaunchIntentState.succeeded, record.state)
            assertEquals(requiredHash("liveBodySha256"), record.intent.bodySha256)
            assertInterruptedFrozen(live, record, actor(live))
            assertInterruptedTree(live, createdId)
            val beforeTrees = trees(live)
            val beforePanes = paneIds(snapshot(live))
            assertInterruptedSolePane(live, createdId)
            ActivityScenario.launch<MainActivity>(MainActivity::class.java).use { dashboard ->
                awaitInterruptedDashboard(dashboard, live)
                assertInterruptedSolePane(live, createdId)
                removeWorktreeThroughUi(live, INTERRUPTED_BRANCH, INTERRUPTED_PATH)
            }
            assertTrue("Targeted clean removal preserves every other checkout",
                trees(live) == beforeTrees.filterNot { it.path == INTERRUPTED_PATH })
            assertTrue("Targeted clean removal closes only its acknowledged result pane",
                paneIds(snapshot(live)) == beforePanes - createdId)
            assertTrue("Cleanup cannot alter the original Create receipt", record == live.app.container.launchIntentStore.get(requestId))
        }
    }

    private fun interruptedScope(live: Live) {
        assertEquals("The interrupted fixture is only the original local stock scope", Scope(), live.scope)
        assertEquals("The interrupted fixture is only the exact main checkout workspace", "w1", live.workspace)
        originalOwner(live)
    }

    private fun requiredHash(name: String): String = required(name).also {
        assertTrue("$name must be a bounded SHA-256 witness", it.matches(Regex("[0-9a-f]{64}")))
    }

    private fun awaitInterruptedDashboard(scenario: ActivityScenario<MainActivity>, live: Live, requestId: String? = null): ScopedReadContext {
        var accepted: ScopedReadContext? = null
        await("The real native dashboard admits the original global live worktree owner") {
            originalOwner(live)
            scenario.onActivity { activity ->
                val state = androidx.lifecycle.ViewModelProvider(activity)[MainViewModel::class.java].state.value
                val actor = state.originalReadContext
                val root = state.snapshot?.workspaces?.singleOrNull { it.workspaceId == live.workspace }
                val unknown = state.unknownLaunchIntents.map { it.intent.requestId }
                val ready = actor != null && actor.connection === live.connection && actor.committedSnapshot == live.committed &&
                    actor.requestScope == live.scope && actor.key.subjectIdentity == null && live.app.container.repository.isCurrent(actor) &&
                    state.savedCopy == null && state.lastSuccessAt != null && state.writeAuthorized &&
                    state.receiptContextBound == true && state.mux?.supportsNew("removeWorktree") == true &&
                    root?.repoRoot == ROOT && root?.isWorktree == false && live.app.container.repository.writesAllowed(live.scope, null) &&
                    (if (requestId == null) unknown.isEmpty() else unknown == listOf(requestId))
                if (ready) accepted = actor
            }
            accepted != null
        }
        return requireNotNull(accepted)
    }

    private fun interruptedCurrent(live: Live, original: ScopedReadContext) {
        originalOwner(live)
        val current = requireNotNull(live.app.container.repository.readContext(live.scope))
        assertTrue("The exact original displayed worktree actor cannot be silently replaced",
            live.app.container.repository.isCurrent(original) && live.app.container.repository.isCurrent(current) && sameActorWitness(original, current))
    }

    private fun assertInterruptedFrozen(live: Live, record: StoredLaunchIntent, actor: ScopedReadContext) {
        val frozen = record.intent
        assertEquals("The frozen UUID is canonical", frozen.requestId, UUID.fromString(frozen.requestId).toString())
        assertEquals("Only the original published create-at operation is eligible", "worktree.createAt", frozen.operation)
        assertEquals(LaunchIntentReceiptMode.ownerBound, frozen.receiptMode)
        assertEquals(live.connection.origin.value, frozen.origin)
        assertEquals(frozen.origin, frozen.mount)
        assertEquals(live.scope.host, frozen.host)
        assertEquals(live.scope.session, frozen.session)
        assertNull("The original real Main review must have the global subject", frozen.subjectIdentity)
        assertTrue("The immutable request belongs to the same original paired identity", frozen.pairedDeviceIdentity == actor.key.pairedDeviceIdentity)
        assertFalse(frozen.confirmedNoPrompts)
        assertNull("Create-at has no alternate route target", frozen.targetIdentity)
        assertEquals(LaunchIntentStore.sha256(frozen.canonicalBody), frozen.bodySha256)
        val body = Json.parseToJsonElement(frozen.canonicalBody).jsonObject
        assertEquals(setOf("cwd", "branch", "base", "folder", "requestId", "shell"), body.keys)
        assertEquals(ROOT, body["cwd"]?.jsonPrimitive?.content)
        assertEquals(INTERRUPTED_BRANCH, body["branch"]?.jsonPrimitive?.content)
        assertEquals(frozen.requestId, body["requestId"]?.jsonPrimitive?.content)
        assertEquals("true", body["shell"]?.jsonPrimitive?.content)
        assertEquals(setOf("kind"), body["base"]?.jsonObject?.keys)
        assertEquals("default", body["base"]?.jsonObject?.get("kind")?.jsonPrimitive?.content)
        assertEquals(setOf("kind", "parent"), body["folder"]?.jsonObject?.keys)
        assertEquals("parent", body["folder"]?.jsonObject?.get("kind")?.jsonPrimitive?.content)
        assertEquals(PARENT, body["folder"]?.jsonObject?.get("parent")?.jsonPrimitive?.content)
    }

    private fun assertInterruptedTree(live: Live, createdId: String) {
        val selected = trees(live).filter { it.path == INTERRUPTED_PATH || it.branch == INTERRUPTED_BRANCH }
        assertEquals("The original Create produces exactly one factual linked checkout", 1, selected.size)
        val tree = selected.single()
        assertEquals(INTERRUPTED_PATH, tree.path)
        assertEquals(INTERRUPTED_BRANCH, tree.branch)
        assertTrue("The acknowledged checkout is linked and not prunable", tree.linked && !tree.prunable)
        val pane = (snapshot(live).let { it.agents + it.shellPanes }).single { it.paneId == createdId && it.host == live.scope.host && it.session == live.scope.session }
        assertEquals(INTERRUPTED_PATH, pane.cwd)
        assertTrue("The original reviewed default creates an ordinary shell", pane.agent == "shell" || pane.kind == "shell")
        assertEquals("The factual linked checkout belongs only to the receipt pane's workspace", pane.workspaceId, tree.openWorkspaceId)
    }

    private fun assertInterruptedSolePane(live: Live, createdId: String) {
        val current = snapshot(live)
        val panes = current.agents + current.shellPanes
        val created = panes.single { it.paneId == createdId && it.host == live.scope.host && it.session == live.scope.session }
        val workspace = requireNotNull(created.workspaceId)
        assertTrue("The result workspace ID must be bounded", workspace.matches(Regex("[A-Za-z0-9:._-]{1,128}")))
        val tree = trees(live).single { it.path == INTERRUPTED_PATH && it.branch == INTERRUPTED_BRANCH }
        assertEquals("The exact linked worktree still belongs to the acknowledged pane's workspace", workspace, tree.openWorkspaceId)
        assertTrue("Refuse before Remove if any additional pane now occupies this worktree workspace",
            panes.filter { it.host == live.scope.host && it.session == live.scope.session && it.workspaceId == workspace }
                .map { it.paneId }.toSet() == setOf(createdId))
    }

    private fun worktreesHash(rows: List<Worktree>): String = LaunchIntentStore.sha256(
        Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(Worktree.serializer()), rows.sortedBy { it.path }))
    private fun journalHash(rows: List<StoredLaunchIntent>): String = LaunchIntentStore.sha256(
        Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(StoredLaunchIntent.serializer()), rows.sortedBy { it.intent.requestId }))

    @Test fun nativeWorktreePlanCancelThenCreateAndRemoveExactCleanCheckout() = withLive { live ->
        val repository = live.app.container.repository
        val before = snapshot(live)
        val beforeTrees = trees(live)
        assertTrue("The controlled create branch/path must not already exist", beforeTrees.none { it.branch == CREATE_BRANCH || it.path == CREATE_PATH })
        val actor = actor(live)
        val config = runBlocking { repository.config(live.scope, actor) }
        assertTrue("Stock host must offer acknowledged worktree removal", config is ApiResult.Success && config.value.receiptContextBound == true && config.value.mux?.supportsNew("removeWorktree") == true)
        val plan = runBlocking { repository.planWorktree(live.scope, ROOT, CREATE_BRANCH, PARENT, actor) }
        assertTrue("Real read-only plan must admit the controlled parent", plan is ApiResult.Success && plan.value.ok && plan.value.branchValid == true)
        assertEquals(CREATE_PATH, (plan as ApiResult.Success).value.parentTarget?.path)
        assertEquals(true, plan.value.parentTarget?.ok)
        val beforeIds = ids(live)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openWorktreeForm(live)
            fill(withId(R.id.worktree_branch_input), CREATE_BRANCH)
            fill(withHint(R.string.worktree_parent_folder), PARENT)
            tap(button(R.string.action_create))
            awaitFocusedNativeSheetTitle(R.string.worktree_plan_title)
            awaitView(allOf(withText(org.hamcrest.Matchers.startsWith("Repository: $ROOT\n")), withText(containsText(CREATE_BRANCH))))
            tap(withId(R.id.collie_sheet_close))
            assertEquals("Cancelling the real plan cannot reserve an intent", beforeIds, ids(live))
            assertEquals("Cancelling cannot create a linked checkout", beforeTrees, trees(live))
            assertEquals("Cancelling cannot create a terminal pane", paneIds(before), paneIds(snapshot(live)))

            openWorktreeForm(live)
            fill(withId(R.id.worktree_branch_input), CREATE_BRANCH)
            fill(withHint(R.string.worktree_parent_folder), PARENT)
            tap(button(R.string.action_create))
            awaitFocusedNativeSheetTitle(R.string.worktree_plan_title)
            // The reviewed default is a plain shell, never an autonomous agent or no-prompts row.
            val reviewedActor = actor(live)
            expandVisibleOwnedSheet(allOf(withId(R.id.collie_sheet_title), withText(R.string.worktree_plan_title))) {
                originalOwner(live)
                val current = requireNotNull(repository.readContext(live.scope))
                assertTrue("The reviewed sheet keeps the original accepted actor",
                    repository.isCurrent(reviewedActor) && repository.isCurrent(current) &&
                        sameActorWitness(reviewedActor, current))
            }
            tap(plainShellRadio(live.app.getString(R.string.launch_target_shell)), expectedActor = reviewedActor)
            tap(button(R.string.worktree_plan_create), mutation = true, expectedActor = reviewedActor, requireClickReady = true)
            val pane = awaitPane()
            val createdId = requireNotNull(pane.intent.getStringExtra(PaneActivity.EXTRA_PANE_ID))
            try {
                assertEquals(CREATE_PATH, pane.intent.getStringExtra(PaneActivity.EXTRA_CWD))
                assertEquals(live.scope.host, pane.intent.getStringExtra(PaneActivity.EXTRA_HOST))
                assertEquals(live.scope.session, pane.intent.getStringExtra(PaneActivity.EXTRA_SESSION))
                val receipt = record(live, beforeIds, "worktree.createAt")
                val body = Json.parseToJsonElement(receipt.intent.canonicalBody).jsonObject
                assertEquals(ROOT, body["cwd"]?.jsonPrimitive?.content)
                assertEquals(CREATE_BRANCH, body["branch"]?.jsonPrimitive?.content)
                assertEquals(PARENT, body["folder"]?.jsonObject?.get("parent")?.jsonPrimitive?.content)
                assertEquals("true", body["shell"]?.jsonPrimitive?.content)
                assertOneNewPane(live, paneIds(before), createdId, CREATE_PATH)
                await("Exactly one acknowledged linked checkout must exist") { trees(live).count { it.branch == CREATE_BRANCH && it.path == CREATE_PATH && it.linked && it.openWorkspaceId != null } == 1 }
                emit(receipt, createdId)
            } finally { instrumentation.runOnMainSync { pane.finish() } }
            removeWorktreeThroughUi(live, CREATE_BRANCH, CREATE_PATH)
            assertEquals("Native removal must restore the exact worktree inventory", beforeTrees, trees(live))
            assertEquals("Native removal must remove only its created pane", paneIds(before), paneIds(snapshot(live)))
        }
    }

    @Test fun nativeOpensAndRemovesOnlyThePreparedUnopenedCheckout() = withLive { live ->
        val before = snapshot(live)
        val initialTrees = trees(live)
        val prepared = initialTrees.singleOrNull { it.path == OPEN_PATH && it.branch == OPEN_BRANCH }
        assertNotNull("Parent must prepare the exact clean linked checkout using normal git", prepared)
        assertEquals(true, prepared?.linked)
        assertNull("Prepared checkout must initially be unopened", prepared?.openWorkspaceId)
        ActivityScenario.launch(MainActivity::class.java).use {
            openWorktreeForm(live)
            onView(withId(R.id.worktree_branch_input)).perform(closeSoftKeyboard())
            tap(buttonText(OPEN_BRANCH))
            awaitView(withText(R.string.worktree_open_title))
            awaitView(withText(OPEN_PATH))
            assertEquals("Reviewing Open must not mutate the inventory", paneIds(before), paneIds(snapshot(live)))
            assertNull(trees(live).single { row -> row.path == OPEN_PATH }.openWorkspaceId)
            tap(button(R.string.action_open), mutation = true)
            val pane = awaitPane()
            try {
                val created = requireNotNull(pane.intent.getStringExtra(PaneActivity.EXTRA_PANE_ID))
                assertEquals(OPEN_PATH, pane.intent.getStringExtra(PaneActivity.EXTRA_CWD))
                assertOneNewPane(live, paneIds(before), created, OPEN_PATH)
                assertEquals("Opening must not manufacture another linked checkout", initialTrees.map { row -> row.path }.toSet(), trees(live).map { row -> row.path }.toSet())
                assertNotNull(trees(live).single { row -> row.path == OPEN_PATH }.openWorkspaceId)
            } finally { instrumentation.runOnMainSync { pane.finish() } }
            removeWorktreeThroughUi(live, OPEN_BRANCH, OPEN_PATH)
            assertEquals(initialTrees.filterNot { row -> row.path == OPEN_PATH }, trees(live))
            assertEquals(paneIds(before), paneIds(snapshot(live)))
        }
    }

    @Test fun nativeSavedPlainLauncherUseRenameRemoveAndHarmlessRunPreserveExactInventories() = withLive { live ->
        val initial = catalog(live)
        val recoveryId = args.getString("recoverSavedRecipeId")
        if (recoveryId != null) assertTrue("Recovery requires an explicit bounded saved-row ID", recoveryId.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
        val recovered = recoveryId?.let { id ->
            initial.launchers.singleOrNull { it.id == id && it.label == LABEL && it.command == COMMAND && it.source == "added" && !it.noPrompts }
                ?: error("Explicit recovery must name the one exact owned harmless saved recipe")
        }
        val before = if (recovered == null) initial else initial.copy(launchers = initial.launchers.filterNot { it.id == recoveryId })
        if (recovered == null) assertTrue("The exact saved command must be distinct from every existing operator/phone launcher before Add",
            initial.launchers.none { it.command == COMMAND })
        assertTrue("Parent must explicitly permit additions/free text/runs", before.adding?.let {
            it.adds && it.freeText && it.run && if (recovered == null) it.count < it.max else it.count <= it.max
        } == true)
        assertTrue("Controlled labels must be fresh", before.launchers.none { it.label in setOf(LABEL, RENAMED) })
        val beforeIds = ids(live)
        var savedId: String? = recoveryId
        ActivityScenario.launch<LaunchRecipeActivity>(LaunchRecipeActivity.intent(live.app, live.scope, LaunchRecipeActivity.MODE_RECIPES)).use {
            if (recovered != null) {
                val identity = actor(live).key.pairedDeviceIdentity
                val priorAdd = live.app.container.launchIntentStore.all().singleOrNull { record ->
                    val intent = record.intent
                    if (record.state != LaunchIntentState.succeeded || intent.operation != "launcher.add" ||
                        intent.origin != live.connection.origin.value || intent.host != live.scope.host || intent.session != live.scope.session ||
                        intent.pairedDeviceIdentity != identity || intent.confirmedNoPrompts) false
                    else Json.parseToJsonElement(intent.canonicalBody).jsonObject.let { body ->
                        body["text"]?.jsonPrimitive?.content == COMMAND && body["label"]?.jsonPrimitive?.content == LABEL &&
                            body["kind"]?.jsonPrimitive?.content == "command" && intent.bodySha256 == LaunchIntentStore.sha256(intent.canonicalBody)
                    }
                }
                assertNotNull("Recovery must have the original successful native Add journal; it never sends another Add", priorAdd)
                assertEquals(LaunchIntentReceiptMode.ownerBound, priorAdd?.intent?.receiptMode)
                instrumentation.sendStatus(2, Bundle().apply {
                    putString("liveRecoveredRecipeId", recoveryId)
                    putString("liveOriginalAddRequestId", priorAdd?.intent?.requestId)
                    putString("stream", "Continuing the explicitly owned previously saved recipe; Add is not repeated.\n")
                })
                awaitView(withText(LABEL))
                assertEquals("Recovery cannot reserve another Add request", beforeIds, ids(live))
            } else {
                tap(button(R.string.recipes_add_text))
                tap(withText(R.string.recipes_kind_command))
                fill(withHint(R.string.recipes_command), COMMAND)
                fill(withHint(R.string.recipes_add_label), LABEL)
                tap(button(R.string.recipes_add_confirm), mutation = true)
                await("Exactly one real phone-owned harmless launcher must be added") {
                    val added = catalog(live).launchers.filter { row -> row.id != null && before.launchers.none { old -> old.id == row.id } }
                    assertTrue("One UI add must not create multiple rows", added.size <= 1)
                    savedId = added.singleOrNull()?.takeIf { row -> row.label == LABEL && row.command == COMMAND && row.source == "added" && !row.noPrompts }?.id
                    savedId != null
                }
                val saved = record(live, beforeIds, "launcher.add")
                assertEquals(COMMAND, Json.parseToJsonElement(saved.intent.canonicalBody).jsonObject["text"]?.jsonPrimitive?.content)
                assertEquals("command", Json.parseToJsonElement(saved.intent.canonicalBody).jsonObject["kind"]?.jsonPrimitive?.content)
                awaitView(withText(LABEL))
            }
        }
        val savedInventory = catalog(live).launchers
        val priorPanes = paneIds(snapshot(live))
        val idsBeforeUse = ids(live)
        ActivityScenario.launch<NewActivity>(NewActivity.intent(live.app, live.scope)).use {
            tap(allOf(withText(LABEL), isAssignableFrom(RadioButton::class.java)))
            fill(withHint(R.string.new_folder_manual), ROOT)
            tap(button(R.string.new_start), mutation = true)
            val pane = awaitPane()
            try {
                val created = requireNotNull(pane.intent.getStringExtra(PaneActivity.EXTRA_PANE_ID))
                val receipt = record(live, idsBeforeUse, "launch")
                assertEquals(COMMAND, Json.parseToJsonElement(receipt.intent.canonicalBody).jsonObject["command"]?.jsonPrimitive?.content)
                assertOneNewPane(live, priorPanes, created, ROOT)
                assertEquals(savedInventory, catalog(live).launchers)
                emit(receipt, created)
            } finally { instrumentation.runOnMainSync { pane.finish() } }
        }
        ActivityScenario.launch<LaunchRecipeActivity>(LaunchRecipeActivity.intent(live.app, live.scope, LaunchRecipeActivity.MODE_RECIPES)).use {
            tap(rowButton(LABEL, R.string.recipes_rename))
            fill(isAssignableFrom(EditText::class.java), RENAMED)
            tap(button(R.string.recipes_rename), mutation = true)
            await("Rename changes only its own stable launcher ID") { catalog(live).launchers.singleOrNull { row -> row.id == savedId }?.label == RENAMED }
            assertEquals(before.launchers, catalog(live).launchers.filterNot { row -> row.id == savedId })
            assertEquals(COMMAND, catalog(live).launchers.single { row -> row.id == savedId }.command)
            tap(rowButton(RENAMED, R.string.recipes_remove))
            tap(button(R.string.recipes_remove_confirm), mutation = true)
            await("Remove restores original launcher inventory") { catalog(live).launchers == before.launchers }
        }
        runHarmlessCommandThroughUi(live, before)
    }

    /** Continue the acknowledged recipe phase's remaining Run proof without repeating Add/Use. */
    @Test fun nativeHarmlessRunReviewThenConfirmOnceAndRemoveOnlyItsRecentLine() {
        assumeTrue("Set nativeRun=true only when intentionally creating a fresh harmless Run",
            args.getString("nativeRun") == "true")
        withLive { live -> runHarmlessCommandThroughUi(live, catalog(live)) }
    }

    /** Remove the exact already-succeeded Run row after its result pane and journal are captured. */
    @Test fun nativeRecentRunCleanupRemovesOnlyTheExplicitlyOwnedCompletedLine() {
        assumeTrue("Set nativeRecentRun=true to authorize removal of the exact completed native Run line",
            args.getString("nativeRecentRun") == "true")
        withLive { live ->
            originalOwner(live)
            val requestId = required("liveRequestId")
            val expectedHash = required("liveBodySha256")
            val createdPaneId = required("liveCreatedPaneId")
            assertEquals("The selected receipt UUID is canonical", requestId, UUID.fromString(requestId).toString())
            val originalActor = actor(live)
            val originalJournal = live.app.container.launchIntentStore.all()
            val receipt = originalJournal.singleOrNull { it.intent.requestId == requestId }
            assertNotNull("The exact prior native Run receipt must exist", receipt)
            val runReceipt = requireNotNull(receipt)
            assertEquals("Only the exact previously successful Run is eligible", LaunchIntentState.succeeded, runReceipt.state)
            assertEquals("The prior receipt is a Run", "launch.run", runReceipt.intent.operation)
            assertEquals("The prior Run receipt retains its owner-bound response mode",
                LaunchIntentReceiptMode.ownerBound, runReceipt.intent.receiptMode)
            assertEquals("The prior Run receipt retains its exact supplied UUID", requestId, runReceipt.intent.requestId)
            assertEquals("The body hash must match the actual receipt", expectedHash, runReceipt.intent.bodySha256)
            assertEquals("The canonical request body still verifies against its original hash",
                expectedHash, LaunchIntentStore.sha256(runReceipt.intent.canonicalBody))
            assertEquals("Receipt remains bound to the exact installed origin", live.connection.origin.value, runReceipt.intent.origin)
            assertEquals("Receipt remains bound to the exact host scope", live.scope.host, runReceipt.intent.host)
            assertEquals("Receipt remains bound to the exact session scope", live.scope.session, runReceipt.intent.session)
            assertEquals("Receipt remains bound to the original paired device", originalActor.key.pairedDeviceIdentity,
                runReceipt.intent.pairedDeviceIdentity)
            assertFalse("The actual Run was not marked no-prompts", runReceipt.intent.confirmedNoPrompts)
            val body = Json.parseToJsonElement(runReceipt.intent.canonicalBody).jsonObject
            assertEquals("The exact harmless RUN line is authorized for this cleanup", RUN, body["run"]?.jsonPrimitive?.content)
            assertEquals("The exact controlled cwd is authorized for this cleanup", ROOT, body["cwd"]?.jsonPrimitive?.content)
            assertEquals("Receipt body names its immutable request UUID", requestId, body["requestId"]?.jsonPrimitive?.content)

            val beforeSnapshot = snapshot(live)
            val paneIdsBefore = paneIds(beforeSnapshot)
            val createdPane = (beforeSnapshot.agents + beforeSnapshot.shellPanes).singleOrNull { it.paneId == createdPaneId }
            assertNotNull("The actual result pane from the prior Run remains present", createdPane)
            assertEquals("The result pane is inside the controlled Git fixture", ROOT, createdPane?.cwd)
            assertEquals("The result pane is in the original local host/session", live.scope.host, createdPane?.host)
            assertEquals("The result pane is in the exact session scope", live.scope.session, createdPane?.session)
            val recordedSubject = runReceipt.intent.subjectIdentity
            assertTrue("Preserve the receipt's original subject witness without requiring it to remain open",
                recordedSubject == null || recordedSubject.matches(Regex("[A-Za-z0-9:._-]{1,128}")))

            val beforeCatalog = catalog(live)
            val recent = requireNotNull(beforeCatalog.recentRuns) { "The real recent-run catalog must be available" }
            assertEquals("The host removal API addresses recent commands by line only, so no duplicate line may exist",
                1, recent.count { it.line == RUN })
            val targetRow = recent.singleOrNull { it.line == RUN && it.cwd == ROOT && !it.noPrompts }
            assertNotNull("Exactly one current recent row matches the original succeeded Run", targetRow)
            val selectedRow = requireNotNull(targetRow)
            val expectedRecentAfter = recent - selectedRow
            assertEquals("The recent target is present exactly once", 1,
                recent.count { it.line == RUN && it.cwd == ROOT && !it.noPrompts })
            val journalBeforeAction = live.app.container.launchIntentStore.all()
            assertEquals("No receipt changed after identifying the exact target", originalJournal, journalBeforeAction)
            originalOwner(live)
            assertTrue("The original accepted actor is current before opening the native row action",
                live.app.container.repository.isCurrent(originalActor))

            ActivityScenario.launch<LaunchRecipeActivity>(LaunchRecipeActivity.intent(live.app, live.scope, LaunchRecipeActivity.MODE_RUN)).use {
                val remove = rowButton(RUN, R.string.recipes_recent_remove)
                if (args.getString("nativeRecentRunGeometry") == "true") {
                    // Explicit read-only diagnosis: never open Remove or submit a mutation.
                    expose(remove)
                    reportRecentRunGeometry(remove, live, originalActor)
                    originalOwner(live)
                    assertTrue("Geometry inspection cannot alter any operation receipt",
                        journalBeforeAction == live.app.container.launchIntentStore.all())
                    assertTrue("Geometry inspection cannot alter the current launcher catalog", beforeCatalog == catalog(live))
                    assertTrue("Geometry inspection cannot alter any pane", paneIdsBefore == paneIds(snapshot(live)))
                    return@use
                }
                tap(remove, expectedActor = originalActor, requireClickReady = true)
                awaitView(withText(R.string.recipes_recent_remove_title))
                val expectedDialog = live.app.getString(
                    R.string.recipes_recent_remove_body,
                    live.scope.host ?: live.app.getString(R.string.new_this_machine), RUN,
                )
                awaitView(allOf(withId(android.R.id.message), withText(expectedDialog)))
                originalOwner(live)
                assertEquals("The exact prior Run receipt remains unchanged through confirmation", journalBeforeAction,
                    live.app.container.launchIntentStore.all())
                assertEquals("The owned Run result pane remains present before removal", paneIdsBefore, paneIds(snapshot(live)))
                assertEquals("All launchers remain unchanged before removal", beforeCatalog.launchers, catalog(live).launchers)
                assertTrue("The same accepted actor survives until the explicit confirmation",
                    live.app.container.repository.isCurrent(originalActor))
                tap(withId(android.R.id.button1), mutation = true, expectedActor = originalActor)

                await("Only the selected harmless recent line is removed") {
                    catalog(live).recentRuns?.none { it == selectedRow } == true
                }
                val afterCatalog = catalog(live)
                assertEquals("The target row is absent and every other recent row remains unchanged",
                    expectedRecentAfter, afterCatalog.recentRuns)
                assertEquals("Recent-run cleanup cannot change the launcher catalog", beforeCatalog.launchers, afterCatalog.launchers)
                assertEquals("Recent-run cleanup cannot change any pane", paneIdsBefore, paneIds(snapshot(live)))
                assertEquals("Recent-run cleanup creates or rewrites no native operation receipt", journalBeforeAction,
                    live.app.container.launchIntentStore.all())
                originalOwner(live)
            }
        }
    }

    private fun runHarmlessCommandThroughUi(live: Live, before: LaunchersResponse) {
        val beforeRunPanes = paneIds(snapshot(live))
        val beforeRunIds = ids(live)
        assertTrue("The controlled one-off line must be fresh", catalog(live).recentRuns.orEmpty().none { it.line == RUN })
        ActivityScenario.launch<LaunchRecipeActivity>(LaunchRecipeActivity.intent(live.app, live.scope, LaunchRecipeActivity.MODE_RUN)).use {
            fill(withHint(R.string.recipes_run_input), RUN)
            fill(withHint(R.string.recipes_run_cwd), ROOT)
            val reviewedActor = actor(live)
            tap(button(R.string.recipes_run))
            awaitView(withText(live.app.getString(R.string.recipes_run_confirm_title, live.app.getString(R.string.new_this_machine))))
            assertEquals("Reviewing Run cannot reserve a request", beforeRunIds, ids(live))
            assertEquals(beforeRunPanes, paneIds(snapshot(live)))
            // Match the confirmation window's positive button, avoiding the same underlying label.
            tap(withId(android.R.id.button1), mutation = true, expectedActor = reviewedActor)
            val pane = awaitPane()
            try {
                val created = requireNotNull(pane.intent.getStringExtra(PaneActivity.EXTRA_PANE_ID))
                val receipt = record(live, beforeRunIds, "launch.run")
                val body = Json.parseToJsonElement(receipt.intent.canonicalBody).jsonObject
                assertEquals(RUN, body["run"]?.jsonPrimitive?.content)
                assertEquals(ROOT, body["cwd"]?.jsonPrimitive?.content)
                assertOneNewPane(live, beforeRunPanes, created, ROOT)
                awaitView(allOf(withId(R.id.terminal_text), withText(containsText(MARKER))))
                assertEquals(1, catalog(live).recentRuns.orEmpty().count { row -> row.line == RUN && row.cwd == ROOT && !row.noPrompts })
                emit(receipt, created)
            } finally { instrumentation.runOnMainSync { pane.finish() } }
        }
        ActivityScenario.launch<LaunchRecipeActivity>(LaunchRecipeActivity.intent(live.app, live.scope, LaunchRecipeActivity.MODE_RUN)).use {
            tap(rowButton(RUN, R.string.recipes_recent_remove))
            tap(withId(android.R.id.button1), mutation = true)
            await("Remove only the controlled recent line") { catalog(live).recentRuns.orEmpty().none { row -> row.line == RUN } }
            assertEquals(before.recentRuns, catalog(live).recentRuns)
            assertEquals(before.launchers, catalog(live).launchers)
        }
    }

    private fun originalOwner(live: Live) {
        assertSame("Inventory/actions must retain the installed original pair", live.connection, live.app.container.connectionStore.connection.value)
        assertEquals("Inventory/actions must retain the original committed incarnation", live.committed, live.app.container.connectionStore.captureCommitted())
        assertEquals("Only the explicit owned scope may be read", live.scope, live.app.container.repository.selectedScope.value)
    }
    private fun assertMainOwner(
        scenario: ActivityScenario<MainActivity>,
        live: Live,
        actor: ScopedReadContext,
        target: StoredLaunchIntent,
    ) {
        originalOwner(live)
        val requestId = target.intent.requestId
        assertEquals("The exact immutable unknown request remains unchanged before each native tap", target,
            live.app.container.launchIntentStore.all().singleOrNull { it.intent.requestId == requestId })
        assertTrue("The same post-navigation global read actor remains current", live.app.container.repository.isCurrent(actor))
        scenario.onActivity { activity ->
            val state = androidx.lifecycle.ViewModelProvider(activity)[MainViewModel::class.java].state.value
            val displayed = requireNotNull(state.originalReadContext)
            assertTrue("The real MainActivity retains the accepted global authority witness",
                sameActorWitness(actor, displayed))
            assertTrue("The displayed global authority remains current", live.app.container.repository.isCurrent(displayed))
            assertEquals("The real MainActivity remains on the exact owned scope", live.scope,
                live.app.container.repository.selectedScope.value)
            assertEquals("The selected uncertainty remains the exact original request", requestId,
                state.unknownLaunchIntents.firstOrNull()?.intent?.requestId)
            assertTrue("No saved copy can be used to abandon a request", state.savedCopy == null)
        }
        assertTrue("The same global actor remains current after inspecting UI state", live.app.container.repository.isCurrent(actor))
        originalOwner(live)
    }

    private fun dashboardStateHasNoRequest(
        scenario: ActivityScenario<MainActivity>,
        live: Live,
        actor: ScopedReadContext,
        requestId: String,
    ): Boolean {
        var absent = false
        scenario.onActivity { activity ->
            val state = androidx.lifecycle.ViewModelProvider(activity)[MainViewModel::class.java].state.value
            val displayed = state.originalReadContext
            absent = displayed != null && sameActorWitness(actor, displayed) &&
                live.app.container.repository.isCurrent(actor) && live.app.container.repository.isCurrent(displayed) &&
                state.unknownLaunchIntents.none { it.intent.requestId == requestId }
        }
        return absent
    }
    private fun sameActorWitness(first: ScopedReadContext, second: ScopedReadContext): Boolean =
        first.key == second.key && first.connection === second.connection &&
            first.navigationGeneration == second.navigationGeneration &&
            first.connectionIncarnation == second.connectionIncarnation &&
            first.committedStore == second.committedStore &&
            first.committedSnapshot == second.committedSnapshot &&
            first.custodyProcessIdentity == second.custodyProcessIdentity
    private fun actor(live: Live): ScopedReadContext {
        var captured: ScopedReadContext? = null
        await("Normal native loading must release its transient admission fence for an original-owner read") {
            originalOwner(live)
            instrumentation.runOnMainSync {
                // Keep the current native subject: publishing a null-subject snapshot while a
                // pane-owned confirmation is open would replace its live write-admission key.
                val candidate = live.app.container.repository.readContext(live.scope)
                captured = candidate?.takeIf { live.app.container.repository.isCurrent(it) }
            }
            captured != null
        }
        return requireNotNull(captured).also {
            assertSame(live.connection, it.connection)
            assertEquals(live.scope, it.requestScope)
            originalOwner(live)
        }
    }
    private fun snapshot(live: Live): SnapshotResponse {
        val actor = actor(live)
        val result = runBlocking { live.app.container.repository.snapshot(live.scope, actor) }
        assertTrue("Read actual stock inventory under the captured actor", result is ApiResult.Success)
        assertTrue(live.app.container.repository.isCurrent(actor))
        return (result as ApiResult.Success).value
    }
    private fun trees(live: Live): List<Worktree> {
        val actor = actor(live)
        val result = runBlocking { live.app.container.repository.listWorktrees(live.workspace, live.scope, actor) }
        assertTrue("Actual stock worktree listing must succeed", result is ApiResult.Success && result.value.ok)
        assertTrue(live.app.container.repository.isCurrent(actor))
        return (result as ApiResult.Success).value.worktrees.sortedBy { it.path }
    }
    private fun catalog(live: Live): LaunchersResponse {
        val actor = actor(live)
        val result = runBlocking { live.app.container.repository.launchers(live.scope, actor) }
        assertTrue("Read the actual stock launcher catalog", result is ApiResult.Success)
        assertTrue(live.app.container.repository.isCurrent(actor))
        return (result as ApiResult.Success).value
    }
    private fun paneIds(snapshot: SnapshotResponse) = (snapshot.agents + snapshot.shellPanes).map { it.paneId }.toSet()
    private fun ids(live: Live) = live.app.container.launchIntentStore.all().map { it.intent.requestId }.toSet()
    private fun record(live: Live, before: Set<String>, operation: String): StoredLaunchIntent {
        var found: StoredLaunchIntent? = null
        await("One UI confirmation must persist one successful $operation UUID") {
            val added = live.app.container.launchIntentStore.all().filter { it.intent.requestId !in before }
            assertTrue("One action cannot mint multiple request IDs", added.size <= 1)
            found = added.singleOrNull()?.takeIf { it.state == LaunchIntentState.succeeded && it.intent.operation == operation }
            found != null
        }
        return requireNotNull(found).also {
            assertEquals(it.intent.requestId, UUID.fromString(it.intent.requestId).toString())
            assertEquals(LaunchIntentReceiptMode.ownerBound, it.intent.receiptMode)
            assertEquals(live.connection.origin.value, it.intent.origin)
            assertEquals(live.scope.host, it.intent.host)
            assertEquals(live.scope.session, it.intent.session)
            assertTrue("Receipt must belong to the original current paired identity", it.intent.pairedDeviceIdentity == actor(live).key.pairedDeviceIdentity)
            assertFalse(it.intent.confirmedNoPrompts)
            assertEquals(LaunchIntentStore.sha256(it.intent.canonicalBody), it.intent.bodySha256)
            assertEquals(it.intent.requestId, Json.parseToJsonElement(it.intent.canonicalBody).jsonObject["requestId"]?.jsonPrimitive?.content)
        }
    }
    private fun assertOneNewPane(live: Live, before: Set<String>, id: String, cwd: String) {
        val current = snapshot(live)
        assertEquals("Exactly one pane is added and all original panes remain", before + id, paneIds(current))
        val pane = (current.agents + current.shellPanes).single { it.paneId == id }
        assertEquals(cwd, pane.cwd)
        assertEquals(live.scope.host, pane.host)
        assertEquals(live.scope.session, pane.session)
    }
    private fun openWorktreeForm(live: Live) {
        tap(withId(R.id.new_space))
        tap(button(R.string.space_mode_worktree))
        awaitView(withId(R.id.worktree_branch_input))
        onView(withId(R.id.worktree_branch_input)).perform(closeSoftKeyboard())
        if (snapshot(live).workspaces.count { it.repoRoot != null && it.isWorktree == false } > 1) {
            tap(object : TypeSafeMatcher<View>() {
                override fun describeTo(description: Description) { description.appendText("exact disposable repository workspace button") }
                override fun matchesSafely(item: View): Boolean = item is MaterialButton &&
                    (item.tag as? WorkspaceSummary)?.let { it.workspaceId == live.workspace && it.repoRoot == ROOT } == true
            })
        }
        awaitView(allOf(withId(R.id.worktree_base_caption), withText(containsText(ROOT))))
    }
    private fun removeWorktreeThroughUi(live: Live, branch: String, path: String) {
        assertTrue("Only the three exact controlled clean worktrees can be removed", branch == CREATE_BRANCH && path == CREATE_PATH || branch == OPEN_BRANCH && path == OPEN_PATH || branch == INTERRUPTED_BRANCH && path == INTERRUPTED_PATH)
        val beforeIds = ids(live)
        openWorktreeForm(live)
        tap(buttonText(live.app.getString(R.string.worktree_remove_label, branch)))
        awaitView(withText(R.string.worktree_remove_title))
        awaitView(withText(containsText(path)))
        assertEquals("Reviewing removal cannot reserve an intent", beforeIds, ids(live))
        tap(withId(android.R.id.button1), mutation = true)
        val receipt = record(live, beforeIds, "worktree.remove")
        assertEquals(path, receipt.intent.plannedWorktreePath)
        assertEquals(branch, receipt.intent.plannedWorktreeBranch)
        assertEquals("false", Json.parseToJsonElement(receipt.intent.canonicalBody).jsonObject["force"]?.jsonPrimitive?.content)
        await("Only the acknowledged controlled checkout must disappear") { trees(live).none { it.path == path } }
    }
    private fun button(id: Int): Matcher<View> = allOf(withText(id), isAssignableFrom(android.widget.Button::class.java))
    private fun buttonText(text: String): Matcher<View> = allOf(withText(text), isAssignableFrom(MaterialButton::class.java))
    private fun containsText(text: String): Matcher<String> = org.hamcrest.Matchers.containsString(text)
    private fun rowButton(label: String, action: Int): Matcher<View> = allOf(button(action), object : TypeSafeMatcher<View>() {
        override fun describeTo(description: Description) { description.appendText("button belonging to the exact controlled row") }
        override fun matchesSafely(item: View): Boolean {
            val card = item.parent?.parent as? android.view.ViewGroup ?: return false
            return (0 until card.childCount).map { card.getChildAt(it) }.filterIsInstance<android.widget.TextView>().any { it.text.toString() == label }
        }
    })

    private fun plainShellRadio(label: String): Matcher<View> = object : TypeSafeMatcher<View>() {
        override fun describeTo(description: Description) {
            description.appendText("the checked default Shell RadioButton at index zero in the reviewed plan RadioGroup")
        }

        override fun matchesSafely(view: View): Boolean {
            val radio = view as? RadioButton ?: return false
            val group = radio.parent as? RadioGroup ?: return false
            return radio.text.toString() == label && radio.isChecked &&
                group.checkedRadioButtonId == radio.id && group.childCount > 0 && group.getChildAt(0) === radio
        }
    }
    private fun expandVisibleOwnedSheet(title: org.hamcrest.Matcher<View>, originalActor: () -> Unit) {
        // A normal drag begins at the fully visible title, outside the scrollable content.
        // Default swipeUp on the short title text can be below touch slop; use the existing
        // Espresso slow-swipe pattern with a bounded upward drag inside the visible window.
        val swipe = GeneralSwipeAction(
            Swipe.SLOW,
            GeneralLocation.CENTER,
            { view -> GeneralLocation.CENTER.calculateCoordinates(view).also { end ->
                val window = Rect()
                assertTrue("The owned sheet must have a visible window", view.rootView.getGlobalVisibleRect(window))
                end[1] = maxOf(window.top + 1f, end[1] - 180f * view.resources.displayMetrics.density)
            } },
            Press.FINGER,
        )
        onView(title).perform(object : ViewAction {
            override fun getDescription() = "Expand the actual owned sheet with one normal visible-header drag"
            override fun getConstraints(): org.hamcrest.Matcher<View> = swipe.constraints
            override fun perform(controller: UiController, view: View) {
                originalActor()
                swipe.perform(controller, view)
                originalActor()
            }
        })
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            var expanded = false
            onView(title).perform(object : ViewAction {
                override fun getDescription() = "Observe actual sheet expansion without changing its behavior"
                override fun getConstraints(): org.hamcrest.Matcher<View> = isAssignableFrom(View::class.java)
                override fun perform(controller: UiController, view: View) {
                    originalActor()
                    val sheet = requireNotNull(view.rootView.findViewById<android.widget.FrameLayout>(
                        com.google.android.material.R.id.design_bottom_sheet))
                    expanded = com.google.android.material.bottomsheet.BottomSheetBehavior.from(sheet).state ==
                        com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
                }
            })
            if (expanded) return
            SystemClock.sleep(100)
        }
        error("The single normal header drag did not expand the owned sheet; no action was clicked")
    }

    private fun awaitFocusedNativeSheetTitle(titleResource: Int) {
        val live = requireNotNull(activeLive)
        val text = live.app.getString(titleResource)
        val exactFocusedDialog = allOf(isDialog(), object : TypeSafeMatcher<Root>() {
            override fun describeTo(description: Description) {
                description.appendText("the focused native dialog with its exact canonical sheet title")
            }
            override fun matchesSafely(root: Root): Boolean {
                val title = root.decorView.findViewById<android.widget.TextView>(R.id.collie_sheet_title) ?: return false
                return root.decorView.hasWindowFocus() && title.isShown && title.text.toString() == text
            }
        })
        await("The actual focused native sheet must expose its exact canonical title") {
            originalOwner(live)
            try {
                onView(allOf(withId(R.id.collie_sheet_title), withText(text))).inRoot(exactFocusedDialog)
                    .check(matches(isDisplayed()))
                true
            } catch (_: NoMatchingViewException) { false }
            catch (_: NoMatchingRootException) { false }
            catch (_: AssertionError) { false }
        }
    }

    private fun fill(matcher: Matcher<View>, text: String) {
        expose(matcher)
        onView(matcher).perform(replaceText(text), closeSoftKeyboard())
    }
    private fun reportRecentRunGeometry(matcher: Matcher<View>, live: Live, expectedActor: ScopedReadContext) {
        val geometry = mutableListOf<String>()
        onView(matcher).perform(object : ViewAction {
            override fun getDescription() = "Inspect only the verified recent Remove control and five parent layouts"
            override fun getConstraints(): Matcher<View> = isAssignableFrom(View::class.java)
            override fun perform(uiController: UiController, view: View) {
                originalOwner(live)
                val current = requireNotNull(live.app.container.repository.readContext(live.scope))
                assertTrue("Geometry must retain the exact original actor",
                    live.app.container.repository.isCurrent(expectedActor) &&
                        live.app.container.repository.isCurrent(current) && sameActorWitness(expectedActor, current))
                uiController.loopMainThreadUntilIdle()
                var node: View? = view
                repeat(6) { depth ->
                    val item = node ?: return@repeat
                    val visible = Rect()
                    val hasVisibleRect = item.getGlobalVisibleRect(visible)
                    val group = item as? android.view.ViewGroup
                    val linear = item as? android.widget.LinearLayout
                    geometry += "depth=$depth class=${item.javaClass.simpleName} " +
                        "left=${item.left} top=${item.top} x=${item.x} y=${item.y} " +
                        "width=${item.width} height=${item.height} " +
                        "hasGlobalVisibleRect=$hasVisibleRect globalVisibleRect=${visible.toShortString()} " +
                        "clipChildren=${group?.clipChildren} clipToPadding=${group?.clipToPadding} " +
                        "baselineAligned=${linear?.isBaselineAligned}"
                    node = item.parent as? View
                }
                originalOwner(live)
                assertTrue("Geometry observation cannot retire the original actor",
                    live.app.container.repository.isCurrent(expectedActor))
            }
        })
        instrumentation.sendStatus(2, Bundle().apply {
            putString("stream", "nativeRecentRunGeometryOnly=true removalClicks=0\n" +
                geometry.joinToString("\n") + "\n")
        })
    }
    private fun tap(
        matcher: Matcher<View>, mutation: Boolean = false, expectedActor: ScopedReadContext? = null,
        requireClickReady: Boolean = false,
    ) {
        expose(matcher)
        if (requireClickReady) awaitView(allOf(matcher, isCompletelyDisplayed(), isDisplayingAtLeast(90)))
        awaitView(allOf(matcher, isEnabled()))
        val nativeClick = click()
        onView(matcher).perform(object : ViewAction {
            override fun getDescription() = "Click the actual native control under its original paired actor"
            override fun getConstraints(): Matcher<View> = nativeClick.constraints
            override fun perform(uiController: UiController, view: View) {
                val live = requireNotNull(activeLive)
                originalOwner(live)
                val captured = requireNotNull(live.app.container.repository.readContext(live.scope))
                assertTrue("Native action must use a fresh original-owner actor", live.app.container.repository.isCurrent(captured))
                assertSame(live.connection, captured.connection)
                if (expectedActor != null) assertTrue("The exact original reviewed actor survives unchanged until confirmation",
                    live.app.container.repository.isCurrent(expectedActor) && sameActorWitness(expectedActor, captured))
                if (mutation) assertTrue("Only current live native admission may mutate", live.app.container.repository.writesAllowed(live.scope))
                nativeClick.perform(uiController, view)
            }
        })
    }
    private fun expose(matcher: Matcher<View>) {
        await("Native UI control must exist") {
            try { onView(matcher).check(matches(isEnabled())); true } catch (_: NoMatchingViewException) { false } catch (_: AssertionError) { false }
        }
        onView(matcher).perform(object : ViewAction {
            override fun getDescription() = "Scroll the native control into its actual viewport"
            override fun getConstraints(): Matcher<View> = isAssignableFrom(View::class.java)
            override fun perform(uiController: UiController, view: View) {
                view.requestRectangleOnScreen(Rect(0, 0, view.width, view.height), true)
                uiController.loopMainThreadUntilIdle()
            }
        })
        awaitView(matcher)
    }
    private fun awaitView(matcher: Matcher<View>) = await("Native UI must display the expected control") {
        try { onView(matcher).check(matches(isDisplayed())); true } catch (_: NoMatchingViewException) { false } catch (_: AssertionError) { false }
    }
    private fun await(message: String, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 25_000
        while (SystemClock.elapsedRealtime() < until) { if (condition()) return; SystemClock.sleep(100) }
        fail(message)
    }
    private fun awaitPane(): PaneActivity {
        var pane: PaneActivity? = null
        await("Actual native action must open its returned PaneActivity") {
            instrumentation.runOnMainSync { pane = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<PaneActivity>().singleOrNull() }
            pane != null
        }
        return requireNotNull(pane)
    }
    private fun emit(record: StoredLaunchIntent, pane: String) = instrumentation.sendStatus(2, Bundle().apply {
        putString("liveRequestId", record.intent.requestId)
        putString("liveOperation", record.intent.operation)
        putString("liveBodySha256", record.intent.bodySha256)
        putString("liveCreatedPaneId", pane)
        putString("stream", "Real native receipt/inventory marker; no credentials or command content.\n")
    })
    companion object {
        const val ORIGIN = "https://prod.lateapexllc.com/completion"
        val ROOT get() = PhysicalFixturePaths.root
        val PARENT get() = PhysicalFixturePaths.home
        const val INTERRUPTED_BRANCH = "cc13-native-interrupted"
        val INTERRUPTED_PATH get() = "${PhysicalFixturePaths.home}/cc13-native-interrupted"
        const val CREATE_BRANCH = "cc13-native-create"
        val CREATE_PATH get() = "${PhysicalFixturePaths.home}/cc13-native-create"
        const val OPEN_BRANCH = "cc13-native-open"
        val OPEN_PATH get() = "${PhysicalFixturePaths.root}-cc13-native-open"
        const val LABEL = "Completion saved harmless shell"
        const val DUPLICATE_LABEL = "Completion saved harmless shell"
        const val OPERATOR_LABEL = "Completion harmless shell"
        const val RENAMED = "Completion renamed harmless shell"
        val COMMAND get() = "bash --noprofile --norc ${PhysicalFixturePaths.root}/completion-shell.sh cc13-native-saved"
        val DUPLICATE_COMMAND get() = "bash --noprofile --norc ${PhysicalFixturePaths.root}/completion-shell.sh"
        const val MARKER = "COLLIE_CC13_NATIVE_RUN"
        const val RUN = "printf COLLIE_CC13_NATIVE_RUN"
    }
}
