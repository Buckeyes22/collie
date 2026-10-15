package com.lateapex.collie.ui

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.data.ScopedReadContext
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.AgentIdentityResponse
import com.lateapex.collie.network.OriginValidator
import com.lateapex.collie.network.PaneChatResponse
import com.lateapex.collie.network.PaneSummary
import com.lateapex.collie.network.SnapshotResponse
import com.lateapex.collie.network.TranscriptEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Private candidate: read-only evidence for one real Claude image turn in the owned completion profile. */
@RunWith(AndroidJUnit4::class)
class PhysicalClaudeJournalImageAcceptanceTest {
    @Test
    fun actualClaudeInputImageProjectsAndRendersThroughScopedNativeHistory() {
        val app = ApplicationProvider.getApplicationContext<Context>().applicationContext as CollieApplication
        val args = InstrumentationRegistry.getArguments()
        val originText = requireNotNull(args.getString("liveDurableOrigin"))
        val origin = OriginValidator().requireValid(originText)
        assertEquals("Use only the fixed stock completion HTTPS origin",
            OriginValidator().requireValid(STOCK_ORIGIN), origin)
        assertTrue("This acceptance must use the isolated completion package", app.packageName.endsWith(".completiontest"))
        val root = requireNotNull(args.getString("liveRepoPath"))
        assertTrue("Use only the owned dated Git fixture root", root.matches(PhysicalFixturePaths.datedRootPattern))
        val scope = Scope(args.getString("liveHost")?.trim()?.takeIf(String::isNotEmpty),
            args.getString("liveSession")?.trim()?.takeIf(String::isNotEmpty))
        val paneId = requireNotNull(args.getString("livePaneId"))
        assertTrue("Pane ID must be bounded", paneId.matches(Regex("[A-Za-z0-9:._-]{1,128}")))
        val sessionId = requireNotNull(args.getString("liveClaudeSessionId"))
        assertEquals("Supply the actual canonical Claude UUID", UUID.fromString(sessionId).toString(), sessionId)
        val expectedPrompt = requireNotNull(args.getString("liveImagePrompt"))
        assertTrue("The expected marker must be the harmless task-owned acceptance marker",
            expectedPrompt.matches(Regex("COLLIE_CC13_CLAUDE_IMAGE_[A-Z0-9_]{1,48}")))

        val store = app.container.connectionStore
        val repository = app.container.repository
        val connection = requireNotNull(store.connection.value)
        assertTrue("Use the existing paired profile", connection.isPaired)
        assertEquals("The existing pair must match the exact supplied origin", origin, connection.origin)
        val committed = requireNotNull(store.captureCommitted())
        assertSame("The original committed profile must belong to the selected connection", connection, committed.connection)
        val address = PaneAddress(scope, paneId)
        val priorScope = repository.selectedScope.value
        val priorSubject = repository.readContext()?.key?.subjectIdentity
        val launchIntent = Intent(app, PaneActivity::class.java).apply {
            putExtra(PaneActivity.EXTRA_PANE_ID, paneId)
            putExtra(PaneActivity.EXTRA_HOST, scope.host)
            putExtra(PaneActivity.EXTRA_SESSION, scope.session)
            putExtra(PaneActivity.EXTRA_AGENT, "claude")
            putExtra(PaneActivity.EXTRA_CWD, root)
            putExtra(PaneActivity.EXTRA_TITLE, "Claude image acceptance")
        }

        try {
            ActivityScenario.launch<PaneActivity>(launchIntent).use { paneScenario ->
                awaitPane(paneScenario) { activity ->
                    activity.intent.getStringExtra(PaneActivity.EXTRA_PANE_ID) == paneId &&
                        activity.findViewById<View>(R.id.body_mode_row)?.isShown == true
                }
                val actor = awaitOwnedPaneActor(app, paneScenario, connection, committed, scope, paneId, root)
                assertCurrentOriginal(app, connection, committed, actor, scope, paneId)
                val snapshotResult = runBlocking { repository.snapshot(scope, actor) }
                assertTrue("Exact read-only snapshot must succeed (failureType=${safeFailureType(snapshotResult)})",
                    snapshotResult is ApiResult.Success)
                assertCurrentOriginal(app, connection, committed, actor, scope, paneId)
                val pane = exactPane((snapshotResult as ApiResult.Success).value, scope, paneId)
                assertEquals("The supplied pane must be Claude", "claude", pane.agent.lowercase())
                assertEquals("The supplied Claude pane must stay in the owned root", root, pane.cwd)

                val identity = readIdentity(app, address, actor)
                assertEquals("The actual identity endpoint must report the supplied session UUID", sessionId, identity.session?.value)
                assertEquals("The actual identity endpoint must report Claude", "claude", identity.session?.agent?.lowercase())
                val chat = awaitActualImageChat(app, address, actor, connection, committed, scope, paneId, expectedPrompt)
                val imageTurn = chat.upserts.single { it.role == "user" && textOf(it) == expectedPrompt }
                val imageParts = imageTurn.parts.filter { it.kind == "image" }
                assertEquals("The real journal user turn must contain exactly one projected image part", 1, imageParts.size)
                val image = imageParts.single()
                assertTrue("Image MIME type must be declared", image.mimeType in setOf("image/png", "image/jpeg", "image/webp"))
                assertTrue("Image reference must use an admitted inline image or Collie blob route",
                    SAFE_IMAGE_DATA.matches(image.url.orEmpty()) || SAFE_BLOB.matches(image.url.orEmpty()))
                assertCurrentOriginal(app, connection, committed, actor, scope, paneId)
                assertIdentityUnchanged(app, address, actor, sessionId)

                paneScenario.onActivity { activity ->
                    val mode = activity.findViewById<com.google.android.material.button.MaterialButton>(R.id.body_mode_switch)
                    if (mode.isShown && mode.text == activity.getString(R.string.pane_body_switch_to_transcript)) {
                        assertTrue("Select the real transcript using the native body control", mode.performClick())
                    }
                }
                awaitPane(paneScenario) { it.findViewById<View>(R.id.transcript_body)?.isShown == true }
                paneScenario.onActivity { activity ->
                    val label = activity.findViewById<TextView>(R.id.body_mode_label)
                    assertTrue("Open History with the native transcript control", label.isShown && label.isEnabled && label.performClick())
                }
                val history = awaitResumedHistory()
                awaitHistoryImage(history, expectedPrompt)
                assertCurrentOriginal(app, connection, committed, actor, scope, paneId)
                assertIdentityUnchanged(app, address, actor, sessionId)
                assertSame("Native read-only image viewing must preserve the installed profile", connection, store.connection.value)
                assertEquals("Native read-only image viewing must preserve the committed profile", committed, store.captureCommitted())
                InstrumentationRegistry.getInstrumentation().runOnMainSync { history.finish() }
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .singleOrNull { it is HistoryActivity || it is PaneActivity }?.finish()
                repository.selectSubject(priorScope, priorSubject)
            }
        }
    }

    private fun readIdentity(app: CollieApplication, address: PaneAddress, actor: ScopedReadContext): AgentIdentityResponse {
        val repo = app.container.repository
        val result = runBlocking { repo.paneAgentIdentity(address, actor) }
        assertTrue("Actual read-only pane identity must succeed", result is ApiResult.Success)
        assertTrue("The original pane actor remains current after identity read", repo.isCurrent(actor))
        return (result as ApiResult.Success).value.also {
            assertTrue("Agent identity must be admitted", it.ok)
            assertEquals("Identity must match the supplied pane", address.paneId, it.paneId)
        }
    }

    private fun assertIdentityUnchanged(app: CollieApplication, address: PaneAddress, actor: ScopedReadContext, sessionId: String) {
        val identity = readIdentity(app, address, actor)
        assertEquals("The original vendor session must still own this pane after UI history read", sessionId, identity.session?.value)
        assertEquals("The original agent must still own this pane after UI history read", "claude", identity.session?.agent?.lowercase())
    }

    private fun awaitActualImageChat(
        app: CollieApplication, address: PaneAddress, actor: ScopedReadContext, connection: Connection,
        committed: com.lateapex.collie.data.CommittedConnectionSnapshot, scope: Scope, paneId: String, prompt: String,
    ): PaneChatResponse {
        val repo = app.container.repository
        val end = SystemClock.uptimeMillis() + WAIT_MS
        while (SystemClock.uptimeMillis() < end) {
            assertCurrentOriginal(app, connection, committed, actor, scope, paneId)
            val result = runBlocking { repo.chat(address, limit = 200, expectedContext = actor) }
            if (result is ApiResult.Success) {
                assertCurrentOriginal(app, connection, committed, actor, scope, paneId)
                val page = result.value
                assertTrue("The real Claude conversation must be available", page.available)
                assertEquals("The real Chat response must match the exact supplied pane", paneId, page.paneId)
                if (page.upserts.any { it.role == "user" && textOf(it) == prompt && it.parts.any { part -> part.kind == "image" } }) return page
            }
            SystemClock.sleep(800)
        }
        throw AssertionError("The real read-only Chat projection did not expose the exact Claude image turn")
    }

    private fun awaitOwnedPaneActor(
        app: CollieApplication,
        scenario: ActivityScenario<PaneActivity>,
        connection: Connection,
        committed: com.lateapex.collie.data.CommittedConnectionSnapshot,
        scope: Scope,
        paneId: String,
        root: String,
    ): ScopedReadContext {
        val repository = app.container.repository
        val end = SystemClock.uptimeMillis() + WAIT_MS
        var accepted: ScopedReadContext? = null
        while (accepted == null && SystemClock.uptimeMillis() < end) {
            scenario.onActivity { activity ->
                val state = androidx.lifecycle.ViewModelProvider(activity)[PaneViewModel::class.java].state.value
                val actor = state.paneReadContext
                val summary = state.panes.singleOrNull {
                    it.paneId == paneId && it.host == scope.host && it.session == scope.session
                }
                val authorityMatches = actor != null && repository.isCurrent(actor) &&
                    actor.connection === connection && actor.requestScope == scope &&
                    actor.key.host == scope.host && actor.key.session == scope.session &&
                    actor.key.subjectIdentity == paneId && actor.connectionIncarnation == committed.incarnationId &&
                    actor.committedStore && actor.committedSnapshot == committed
                val paneMatches = state.pane?.paneId == paneId && state.savedCopy == null &&
                    state.lastSuccessAt != null && summary?.agent.equals("claude", ignoreCase = true) && summary?.cwd == root
                if (authorityMatches && paneMatches) accepted = actor
            }
            if (accepted == null) SystemClock.sleep(100)
        }
        assertNotNull("Before the first API read, PaneViewModel must display a live current Claude pane under the exact original paired actor and owned root", accepted)
        return requireNotNull(accepted)
    }

    private fun awaitPane(scenario: ActivityScenario<PaneActivity>, ready: (PaneActivity) -> Boolean) {
        val end = SystemClock.uptimeMillis() + WAIT_MS
        var seen = false
        while (!seen && SystemClock.uptimeMillis() < end) {
            scenario.onActivity { seen = ready(it) }
            if (!seen) SystemClock.sleep(100)
        }
        assertTrue("Native PaneActivity did not load the exact Claude pane", seen)
    }

    private fun awaitResumedHistory(): HistoryActivity {
        val end = SystemClock.uptimeMillis() + WAIT_MS
        while (SystemClock.uptimeMillis() < end) {
            var found: HistoryActivity? = null
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<HistoryActivity>().singleOrNull()
            }
            found?.let { return it }
            SystemClock.sleep(100)
        }
        throw AssertionError("The native History screen did not open")
    }

    private fun awaitHistoryImage(activity: HistoryActivity, prompt: String) {
        val end = SystemClock.uptimeMillis() + WAIT_MS
        var rendered = false
        while (!rendered && SystemClock.uptimeMillis() < end) {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val root = activity.findViewById<ViewGroup>(R.id.history_entries)
                // HistoryActivity adds one turn root per direct child. Bind the image to the same
                // rendered turn as the exact marker instead of matching unrelated history content.
                val markedTurns = (0 until root.childCount).map(root::getChildAt)
                    .filterIsInstance<ViewGroup>()
                    .filter { visibleText(it).contains(prompt) }
                val markedTurn = markedTurns.singleOrNull()
                val card = markedTurn?.let { turn ->
                    descendants(turn).filterIsInstance<AuthenticatedImageView>()
                        .singleOrNull { it.isShown && it.isClickable }
                }
                val imageVisible = card?.let { imageCard ->
                    descendants(imageCard).filterIsInstance<ImageView>().any { it.isShown && it.drawable != null }
                } == true
                val expectedCardDescription = activity.getString(R.string.history_image_open, activity.getString(R.string.history_image))
                rendered = imageVisible && card?.contentDescription == expectedCardDescription
            }
            if (!rendered) SystemClock.sleep(150)
        }
        assertTrue("Native History must show the exact marker and a decoded authenticated image card", rendered)
    }

    private fun assertCurrentOriginal(
        app: CollieApplication, connection: Connection,
        committed: com.lateapex.collie.data.CommittedConnectionSnapshot,
        actor: ScopedReadContext, scope: Scope, paneId: String,
    ) {
        val repo = app.container.repository
        assertSame("The original installed profile cannot change", connection, app.container.connectionStore.connection.value)
        assertEquals("The original committed profile witness cannot change", committed, app.container.connectionStore.captureCommitted())
        assertTrue("The original exact-scope pane actor must remain current", repo.isCurrent(actor))
        assertEquals("The original actor must remain scoped to the supplied pane", scope, actor.requestScope)
        assertEquals("The original actor must remain scoped to the supplied subject", paneId, actor.key.subjectIdentity)
    }

    private fun exactPane(snapshot: SnapshotResponse, scope: Scope, paneId: String): PaneSummary =
        requireNotNull((snapshot.agents + snapshot.shellPanes).singleOrNull {
            it.paneId == paneId && it.host == scope.host && it.session == scope.session
        }) { "Live snapshot must contain the exact supplied host/session pane" }

    private fun textOf(entry: TranscriptEntry): String = entry.parts.filter { it.kind == "text" }.joinToString("\n") { it.text.orEmpty() }
    private fun safeFailureType(result: ApiResult<*>): String =
        (result as? ApiResult.Failure)?.error?.javaClass?.simpleName ?: "none"

    private fun visibleText(root: View): List<String> = descendants(root).filterIsInstance<TextView>()
        .filter { it.isShown }.map { it.text?.toString().orEmpty() }.toList()

    private fun descendants(root: View): Sequence<View> = sequence {
        yield(root)
        if (root is ViewGroup) for (index in 0 until root.childCount) yieldAll(descendants(root.getChildAt(index)))
    }

    companion object {
        private const val STOCK_ORIGIN = "https://prod.lateapexllc.com/completion"
        private const val WAIT_MS = 90_000L
        private val SAFE_IMAGE_DATA = Regex("data:image/(?:png|jpeg|webp);base64,[A-Za-z0-9+/=\\s]+")
        private val SAFE_BLOB = Regex("/api/blobs/[a-fA-F0-9]{64}")
    }
}
