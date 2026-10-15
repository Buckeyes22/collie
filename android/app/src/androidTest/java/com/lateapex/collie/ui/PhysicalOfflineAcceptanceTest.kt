package com.lateapex.collie.ui

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lateapex.collie.BuildConfig
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.data.OfflineContentStore
import com.lateapex.collie.data.ScopedReadContext
import com.lateapex.collie.data.custodyOwner
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.OriginValidator
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Physical, real-server acceptance for retained read-only copies. Run one phase per invocation:
 * prepare while the paired completion server is online, kill the app and stop the server outside
 * instrumentation, run offline, restore the server, run reconnect, then run optout. No test
 * transport, fake records, or offline write attempts are used.
 */
@RunWith(AndroidJUnit4::class)
class PhysicalOfflineAcceptanceTest {
    @Test fun selectedOfflinePhaseUsesRealNativeUiAndCurrentPair() {
        val args = InstrumentationRegistry.getArguments()
        val originText = args.getString("liveOrigin")
        assumeTrue("liveOrigin was not supplied; physical offline acceptance is opt-in", originText != null)
        assertFalse("A supplied liveOrigin must not be blank", originText.isNullOrBlank())
        val expectedOrigin = OriginValidator().requireValid(REQUIRED_ORIGIN)
        assertEquals("Only the controlled completion HTTPS mount is accepted", expectedOrigin,
            OriginValidator().requireValid(requireNotNull(originText)))
        assertEquals("Offline acceptance requires the controlled stock pane", CONTROLLED_PANE_ID,
            args.getString("livePaneId")?.trim())

        val app = ApplicationProvider.getApplicationContext<Context>().applicationContext as CollieApplication
        assertTrue("Offline acceptance is confined to the isolated completion app", BuildConfig.APPLICATION_ID.endsWith(".completiontest"))
        val connection = app.container.connectionStore.connection.value
        assertTrue("The existing controlled completion pairing must remain installed", connection?.isPaired == true)
        assertEquals("The installed pairing must match the exact supplied origin", expectedOrigin, connection?.origin)

        val scope = Scope(
            host = args.getString("liveHost")?.trim()?.takeIf(String::isNotEmpty),
            session = args.getString("liveSession")?.trim()?.takeIf(String::isNotEmpty),
        )
        val address = PaneAddress(scope, CONTROLLED_PANE_ID)
        val repository = app.container.repository
        val preferences = NativePreferences(app)
        val store = preferences.offlineContentStore
        val phase = args.getString("offlinePhase")?.trim()
        assertTrue("offlinePhase must be prepare, offline, reconnect, or optout",
            phase in setOf("prepare", "offline", "reconnect", "optout"))

        when (phase) {
            "prepare" -> prepare(app, address, repository, preferences, store)
            "offline" -> verifyOffline(app, address, repository, store)
            "reconnect" -> verifyReconnect(app, address, repository, store)
            "optout" -> optOut(app, store)
        }
        assertTrue("The acceptance phase must preserve the exact installed pairing", app.container.connectionStore.connection.value === connection)
    }

    /** Retained Codex transcript phase; each invocation runs while the real bridge is in one state. */
    @Test fun selectedOfflineChatPhaseUsesRealNativeUiAndCurrentPair() {
        val args = InstrumentationRegistry.getArguments()
        val originText = args.getString("liveOrigin")
        assumeTrue("liveOrigin was not supplied; physical offline Chat acceptance is opt-in", originText != null)
        assertFalse("A supplied liveOrigin must not be blank", originText.isNullOrBlank())
        val expectedOrigin = OriginValidator().requireValid(REQUIRED_ORIGIN)
        assertEquals("Only the controlled completion HTTPS mount is accepted", expectedOrigin,
            OriginValidator().requireValid(requireNotNull(originText)))
        val phase = args.getString("offlineChatPhase")?.trim()
        assumeTrue("offlineChatPhase was not supplied; the separate retained Chat phase is opt-in", phase != null)
        assertTrue("offlineChatPhase must be prepare, offline, reconnect, or optout",
            phase in setOf("prepare", "offline", "reconnect", "optout"))
        val paneId = args.getString("livePaneId")?.trim()
        assertEquals("Chat acceptance requires the native-created Codex pane", CODEX_PANE_ID, paneId)
        val sessionText = args.getString("liveCodexSessionId")?.trim()
        assertNotNull("Supply the actual session UUID reported by the stock Codex CLI", sessionText)
        val sessionId = java.util.UUID.fromString(requireNotNull(sessionText)).toString()
        assertEquals("The session UUID must be canonical", sessionId, sessionText)
        val root = args.getString("liveRepoPath")?.trim()
        assertTrue("Use the owned dated completion Git fixture root", root?.matches(PhysicalFixturePaths.datedRootPattern) == true)

        val app = ApplicationProvider.getApplicationContext<Context>().applicationContext as CollieApplication
        assertTrue("Offline Chat acceptance is confined to the isolated completion app", BuildConfig.APPLICATION_ID.endsWith(".completiontest"))
        val connection = app.container.connectionStore.connection.value
        assertTrue("The existing controlled completion pairing must remain installed", connection?.isPaired == true)
        assertEquals("The installed pairing must match the exact supplied origin", expectedOrigin, connection?.origin)
        val scope = Scope(
            host = args.getString("liveHost")?.trim()?.takeIf(String::isNotEmpty),
            session = args.getString("liveSession")?.trim()?.takeIf(String::isNotEmpty),
        )
        val address = PaneAddress(scope, CODEX_PANE_ID)
        val repository = app.container.repository
        val preferences = NativePreferences(app)
        val store = preferences.offlineContentStore
        when (phase) {
            "prepare" -> prepareCodexChat(app, address, root!!, sessionId, connection!!, repository, preferences, store)
            "offline" -> verifyCodexChatOffline(app, address, root!!, connection!!, repository, store)
            "reconnect" -> verifyCodexChatReconnect(app, address, root!!, sessionId, connection!!, repository, store)
            "optout" -> optOutCodexChat(store)
        }
        assertTrue("The Chat phase must preserve the exact installed pairing", app.container.connectionStore.connection.value === connection)
    }

    private fun prepareCodexChat(
        app: CollieApplication, address: PaneAddress, root: String, sessionId: String,
        connection: com.lateapex.collie.domain.Connection,
        repository: com.lateapex.collie.data.CollieRepository,
        preferences: NativePreferences, store: OfflineContentStore,
    ) {
        assertTrue("Encrypted offline storage must be available", store.available)
        selectRetention(OfflineContentStore.Retention.OFF, store)
        await("native opt-out clears old isolated retained reads") { store.retention == OfflineContentStore.Retention.OFF && store.storageUsage().second == 0 }
        selectRetention(OfflineContentStore.Retention.ONE_DAY, store)
        await("native one-day retention choice is persisted") { store.retention == OfflineContentStore.Retention.ONE_DAY }

        ActivityScenario.launch<PaneActivity>(codexPaneIntent(app, address)).use { scenario ->
            await(scenario, "live Codex pane and actual retained Chat reply are available") { activity ->
                val state = androidx.lifecycle.ViewModelProvider(activity)[PaneViewModel::class.java].state.value
                state.pane?.paneId == address.paneId && hasExactLiveCodexPane(state, address, root) && state.savedCopy == null &&
                    hasVendorReply(state.transcript) &&
                    repository.readSource.value is ReadSourceState.Live && store.storageUsage().second > 0
            }
            var acceptedActor: ScopedReadContext? = null
            scenario.onActivity { activity ->
                val vm = androidx.lifecycle.ViewModelProvider(activity)[PaneViewModel::class.java]
                val state = vm.state.value
                val actor = requireNotNull(state.paneReadContext) { "Live Codex pane must have its accepted actor" }
                acceptedActor = actor
                assertOriginalChatActor(app, repository, actor, connection, address)
                assertEquals("The live server pane must be the exact Codex subject", address.paneId, state.pane?.paneId)
                assertTrue("The live server snapshot places this Codex pane inside the owned Git fixture", hasExactLiveCodexPane(state, address, root))
                assertTrue("The real transcript contains the exact assistant vendor reply", hasVendorReply(state.transcript))
                assertTrue("The real transcript response was retained under native one-day consent", store.storageUsage().second > 0)
            }
            val actor = requireNotNull(acceptedActor)
            verifyLiveCodexChat(repository, address, actor, sessionId)
            ensureTranscriptVisible(scenario)
            scenario.onActivity { activity ->
                assertTrue("The actual native Chat transcript renders the real reply", visibleText(activity.findViewById(R.id.transcript_body)).contains(EXPECTED_CODEX_REPLY))
            }
        }
        assertEquals("Prepare must leave native one-day retention selected", OfflineContentStore.Retention.ONE_DAY, preferences.offlineContentStore.retention)
    }

    private fun verifyCodexChatOffline(
        app: CollieApplication, address: PaneAddress, root: String,
        connection: com.lateapex.collie.domain.Connection,
        repository: com.lateapex.collie.data.CollieRepository, store: OfflineContentStore,
    ) {
        assertEquals("Offline Chat phase uses the prior native one-day consent", OfflineContentStore.Retention.ONE_DAY, store.retention)
        assertTrue("The completed online phase retained encrypted Chat content", store.available && store.storageUsage().second > 0)
        ActivityScenario.launch<PaneActivity>(codexPaneIntent(app, address)).use { scenario ->
            await(scenario, "native Codex transcript restores from encrypted offline copy") { activity ->
                val state = androidx.lifecycle.ViewModelProvider(activity)[PaneViewModel::class.java].state.value
                state.pane?.paneId == address.paneId && state.savedCopy != null && state.savedCopy.scope.subject == address.paneId &&
                    hasVendorReply(state.transcript) &&
                    repository.readSource.value is ReadSourceState.SavedCopy &&
                    activity.findViewById<EditText>(R.id.reply_input).isShown &&
                    !activity.findViewById<EditText>(R.id.reply_input).isEnabled &&
                    !activity.findViewById<View>(R.id.send_button).isEnabled
            }
            ensureTranscriptVisible(scenario)
            scenario.onActivity { activity ->
                val state = androidx.lifecycle.ViewModelProvider(activity)[PaneViewModel::class.java].state.value
                val currentPairActor = requireNotNull(repository.readContext(address.scope, address.paneId)) {
                    "The native pane must retain a local actor for exact paired-custody comparison"
                }
                assertTrue("The local actor is current without making an offline network read", repository.isCurrent(currentPairActor))
                assertSame("Offline Chat remains bound to the original installed pair", connection, currentPairActor.connection)
                assertEquals("Offline copy belongs to the exact native-created Codex pane", address.paneId, state.savedCopy?.scope?.subject)
                assertEquals("Offline copy retains exact host/session scope", address.scope.host, state.savedCopy?.scope?.host)
                assertEquals("Offline copy retains exact session scope", address.scope.session, state.savedCopy?.scope?.session)
                assertEquals("Offline Chat is retained under the same paired owner", currentPairActor.custodyOwner, state.savedCopy?.scope?.owner)
                assertEquals("Offline Chat is retained under the same committed profile incarnation", currentPairActor.connectionIncarnation, state.savedCopy?.scope?.incarnation)
                assertTrue("Saved Chat copy has read-only provenance", state.savedCopy != null && repository.readSource.value is ReadSourceState.SavedCopy)
                assertFalse("Offline Chat cannot enable pane writes", state.canWrite || repository.writesAllowed(address.scope, address.paneId))
                assertTrue("Saved Chat has no live pane or transcript read actor", state.paneReadContext == null && state.transcriptReadContext == null)
                assertFalse("Offline reply composer remains disabled", activity.findViewById<EditText>(R.id.reply_input).isEnabled)
                assertFalse("Offline Send remains disabled", activity.findViewById<View>(R.id.send_button).isEnabled)
                assertTrue("The saved-copy status label is actually visible in pane chrome",
                    activity.findViewById<View>(R.id.pane_status).isShown)
                assertEquals("The native pane chrome explicitly labels this as a saved copy",
                    activity.getString(R.string.offline_saved_copy_status),
                    activity.findViewById<android.widget.TextView>(R.id.pane_status).text.toString())
                assertTrue("The saved copy still carries the exact assistant Codex turn", hasVendorReply(state.transcript))
                assertTrue("Offline native Chat UI shows the retained vendor reply", visibleText(activity.findViewById(R.id.transcript_body)).contains(EXPECTED_CODEX_REPLY))
            }
        }
        assertTrue("Offline verification makes no session switch", app.container.connectionStore.connection.value === connection)
    }

    private fun verifyCodexChatReconnect(
        app: CollieApplication, address: PaneAddress, root: String, sessionId: String,
        connection: com.lateapex.collie.domain.Connection,
        repository: com.lateapex.collie.data.CollieRepository, store: OfflineContentStore,
    ) {
        assertEquals("Reconnect retains the operator's one-day selection", OfflineContentStore.Retention.ONE_DAY, store.retention)
        ActivityScenario.launch<PaneActivity>(codexPaneIntent(app, address)).use { scenario ->
            await(scenario, "real Codex transcript returns as a fresh live read") { activity ->
                val state = androidx.lifecycle.ViewModelProvider(activity)[PaneViewModel::class.java].state.value
                state.pane?.paneId == address.paneId && hasExactLiveCodexPane(state, address, root) && state.savedCopy == null && state.canWrite &&
                    hasVendorReply(state.transcript) && repository.readSource.value is ReadSourceState.Live
            }
            var acceptedActor: ScopedReadContext? = null
            scenario.onActivity { activity ->
                val state = androidx.lifecycle.ViewModelProvider(activity)[PaneViewModel::class.java].state.value
                val actor = requireNotNull(state.paneReadContext) { "Reconnect needs a fresh accepted pane actor" }
                acceptedActor = actor
                assertOriginalChatActor(app, repository, actor, connection, address)
                assertTrue("Reconnected live snapshot keeps this Codex pane inside the owned fixture", hasExactLiveCodexPane(state, address, root))
                assertTrue("Reconnected Chat is fresh live content, not a saved copy", state.savedCopy == null && repository.readSource.value is ReadSourceState.Live)
                assertTrue("Pane writes return only with the live response", state.canWrite && repository.writesAllowed(address.scope, address.paneId))
                assertTrue("The actual Codex reply is still present after reconnect", hasVendorReply(state.transcript))
            }
            val actor = requireNotNull(acceptedActor)
            verifyLiveCodexChat(repository, address, actor, sessionId)
            ensureTranscriptVisible(scenario)
            scenario.onActivity { activity ->
                assertTrue("Reconnect native Chat UI shows the real reply", visibleText(activity.findViewById(R.id.transcript_body)).contains(EXPECTED_CODEX_REPLY))
            }
        }
    }

    private fun optOutCodexChat(store: OfflineContentStore) {
        assertTrue("Chat opt-out requires retained isolated app content", store.storageUsage().second > 0)
        selectRetention(OfflineContentStore.Retention.OFF, store)
        await("native Chat opt-out removes retained reads") { store.retention == OfflineContentStore.Retention.OFF && store.storageUsage().second == 0 }
        assertEquals("Native opt-out removes all isolated retained read rows", 0, store.storageUsage().second)
    }

    private fun assertOriginalChatActor(
        app: CollieApplication, repository: com.lateapex.collie.data.CollieRepository,
        actor: ScopedReadContext, connection: com.lateapex.collie.domain.Connection, address: PaneAddress,
    ) {
        assertTrue("The accepted Codex pane read actor is current", repository.isCurrent(actor))
        assertSame("The original installed pair remains selected", connection, app.container.connectionStore.connection.value)
        assertEquals("Chat actor is bound to the exact host/session/pane", address.scope, actor.requestScope)
        assertEquals("Chat actor is bound to the exact Codex pane", address.paneId, actor.key.subjectIdentity)
        assertTrue("The actor has committed paired custody", actor.committedStore && actor.connection === connection)
    }

    private fun verifyLiveCodexChat(
        repository: com.lateapex.collie.data.CollieRepository, address: PaneAddress,
        actor: ScopedReadContext, sessionId: String,
    ) {
        val before = runBlocking { repository.paneAgentIdentity(address, actor) }
        assertTrue("Public pane-agent identity read succeeds for the current actor", before is ApiResult.Success)
        assertTrue("The original actor remains current after identity read", repository.isCurrent(actor))
        val reportedBefore = (before as ApiResult.Success).value
        assertEquals("Identity response names the exact pane", address.paneId, reportedBefore.paneId)
        assertTrue("Identity response names Codex", reportedBefore.ok && reportedBefore.session?.agent?.equals("codex", true) == true)
        assertEquals("Identity response matches the actual CLI session UUID", sessionId, reportedBefore.session?.value)

        val chat = runBlocking { repository.chat(address, limit = 200, expectedContext = actor) }
        assertTrue("The public Chat endpoint returns a live page", chat is ApiResult.Success)
        assertTrue("The original actor remains current after Chat read", repository.isCurrent(actor))
        val page = (chat as ApiResult.Success).value
        assertTrue("The exact pane Chat page is available", page.paneId == address.paneId && page.available)
        assertEquals("The genuine vendor response appears once as an assistant Chat turn", 1,
            page.upserts.count { it.role == "assistant" && textOf(it) == EXPECTED_CODEX_REPLY })

        val after = runBlocking { repository.paneAgentIdentity(address, actor) }
        assertTrue("A second identity read succeeds after the Chat page", after is ApiResult.Success)
        assertTrue("The original actor remains current after the second identity read", repository.isCurrent(actor))
        val reportedAfter = (after as ApiResult.Success).value
        assertTrue("Codex session identity stays the same around the Chat read",
            reportedAfter.ok && reportedAfter.paneId == address.paneId &&
                reportedAfter.session?.agent?.equals("codex", true) == true && reportedAfter.session?.value == sessionId)
    }

    private fun ensureTranscriptVisible(scenario: ActivityScenario<PaneActivity>) {
        var needsSwitch = false
        scenario.onActivity { activity -> needsSwitch = !activity.findViewById<View>(R.id.transcript_body).isShown }
        if (needsSwitch) onView(withId(R.id.body_mode_switch)).perform(click())
        await(scenario, "the normal Pane body switch shows native Chat") { activity ->
            activity.findViewById<View>(R.id.transcript_body).isShown
        }
    }

    private fun codexPaneIntent(app: CollieApplication, address: PaneAddress) = Intent(app, PaneActivity::class.java)
        .putExtra(PaneActivity.EXTRA_PANE_ID, address.paneId)
        .putExtra(PaneActivity.EXTRA_AGENT, "codex")
        .putExtra(PaneActivity.EXTRA_HOST, address.scope.host)
        .putExtra(PaneActivity.EXTRA_SESSION, address.scope.session)
        .putExtra(PaneActivity.EXTRA_TITLE, "Controlled stock Codex acceptance")

    private fun entryText(entry: com.lateapex.collie.network.TranscriptEntry): String =
        entry.parts.mapNotNull { it.text }.joinToString("\n")

    private fun hasExactLiveCodexPane(state: PaneUiState, address: PaneAddress, root: String): Boolean =
        state.panes.singleOrNull {
            it.paneId == address.paneId && it.host == address.scope.host && it.session == address.scope.session
        }?.let { it.cwd == root && it.agent.equals("codex", ignoreCase = true) } == true

    private fun hasVendorReply(entries: List<com.lateapex.collie.network.TranscriptEntry>): Boolean =
        entries.any { it.role.equals("assistant", ignoreCase = true) && entryText(it).trim() == EXPECTED_CODEX_REPLY }

    private fun textOf(entry: com.lateapex.collie.network.TranscriptEntry): String =
        entry.parts.filter { it.kind == "text" }.joinToString("\n") { it.text.orEmpty() }.trim()

    private fun visibleText(view: View): String = buildString {
        fun collect(node: View) {
            if (!node.isShown) return
            if (node is android.widget.TextView) append(node.text).append('\n')
            if (node is android.view.ViewGroup) for (i in 0 until node.childCount) collect(node.getChildAt(i))
        }
        collect(view)
    }

    private fun prepare(
        app: CollieApplication,
        address: PaneAddress,
        repository: com.lateapex.collie.data.CollieRepository,
        preferences: NativePreferences,
        store: OfflineContentStore,
    ) {
        assertTrue("Encrypted offline storage must be available", store.available)
        // Clear only this isolated app's old retained reads through the shipped opt-out control.
        selectRetention(OfflineContentStore.Retention.OFF, store)
        await("native opt-out clears prior retained reads") { store.retention == OfflineContentStore.Retention.OFF && store.storageUsage().second == 0 }
        selectRetention(OfflineContentStore.Retention.ONE_DAY, store)
        await("native one-day opt-in is persisted") { store.retention == OfflineContentStore.Retention.ONE_DAY }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await(scenario, "live dashboard is accepted and retained") { activity ->
                val vm = androidx.lifecycle.ViewModelProvider(activity)[MainViewModel::class.java]
                val state = vm.state.value
                dashboardContainsAddress(activity, repository, address) &&
                    state.snapshot != null && state.savedCopy == null &&
                    repository.readSource.value is ReadSourceState.Live &&
                    activity.findViewById<View>(R.id.pane_list).isShown && store.storageUsage().second >= 1
            }
            scenario.onActivity { activity ->
                val state = assertDashboardScopeAndPane(activity, repository, address)
                assertNotNull("Dashboard must display a successful live snapshot", state.snapshot)
                assertTrue("Live dashboard must not be labelled as saved copy", state.savedCopy == null)
                assertTrue("Dashboard must be cached under the selected one-day policy", store.storageUsage().second >= 1)
            }
        }

        ActivityScenario.launch<PaneActivity>(paneIntent(app, address)).use { scenario ->
            await(scenario, "controlled stock pane returns actual live content and is retained") { activity ->
                val state = androidx.lifecycle.ViewModelProvider(activity)[PaneViewModel::class.java].state.value
                activity.intent.getStringExtra(PaneActivity.EXTRA_PANE_ID) == address.paneId &&
                    state.pane?.paneId == address.paneId && state.savedCopy == null &&
                    repository.readSource.value is ReadSourceState.Live && store.storageUsage().second >= 2
            }
            scenario.onActivity { activity ->
                val state = androidx.lifecycle.ViewModelProvider(activity)[PaneViewModel::class.java].state.value
                assertEquals("The saved pane must be the exact controlled stock pane", address.paneId, state.pane?.paneId)
                assertTrue("Pane mirror must be read from the actual server", state.pane != null && state.savedCopy == null)
                assertTrue("Successful live pane response must be retained on this phone", store.storageUsage().second >= 2)
                assertTrue("Live response must retain its current actor", state.paneReadContext?.let(repository::isCurrent) == true)
            }
        }
        assertEquals("Prepare phase must leave the shipped one-day policy selected", OfflineContentStore.Retention.ONE_DAY, preferences.offlineContentStore.retention)
    }

    private fun verifyOffline(
        app: CollieApplication,
        address: PaneAddress,
        repository: com.lateapex.collie.data.CollieRepository,
        store: OfflineContentStore,
    ) {
        assertEquals("Offline phase must use the previously opted-in policy", OfflineContentStore.Retention.ONE_DAY, store.retention)
        assertTrue("A real online prepare phase must have left encrypted read records", store.available && store.storageUsage().second >= 2)

        // Do not call repository HTTP APIs here: the parent stops the actual backend before this
        // phase. The screen itself must restore the encrypted read as explicitly read-only.
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await(scenario, "dashboard presents saved-copy read-only state offline") { activity ->
                val state = androidx.lifecycle.ViewModelProvider(activity)[MainViewModel::class.java].state.value
                dashboardContainsAddress(activity, repository, address) &&
                    state.savedCopy != null && repository.readSource.value is ReadSourceState.SavedCopy &&
                    activity.findViewById<View>(R.id.dashboard_read_only_banner).isShown &&
                    activity.findViewById<View>(R.id.pane_list).isShown
            }
            scenario.onActivity { activity ->
                val state = assertDashboardScopeAndPane(activity, repository, address)
                assertNotNull("Dashboard saved copy provenance must be visible", state.savedCopy)
                assertTrue("Saved dashboard must never carry live write authority", !state.writeAuthorized && !repository.writesAllowed())
            }
        }

        ActivityScenario.launch<PaneActivity>(paneIntent(app, address)).use { scenario ->
            await(scenario, "pane mirror restores as saved copy without controls") { activity ->
                val vm = androidx.lifecycle.ViewModelProvider(activity)[PaneViewModel::class.java]
                val state = vm.state.value
                state.savedCopy != null && state.pane?.paneId == address.paneId &&
                    repository.readSource.value is ReadSourceState.SavedCopy &&
                    activity.findViewById<View>(R.id.reply_input).isShown &&
                    !activity.findViewById<EditText>(R.id.reply_input).isEnabled &&
                    !activity.findViewById<View>(R.id.send_button).isEnabled
            }
            scenario.onActivity { activity ->
                val state = androidx.lifecycle.ViewModelProvider(activity)[PaneViewModel::class.java].state.value
                assertEquals("Offline pane must show the actual retained pane", address.paneId, state.pane?.paneId)
                assertNotNull("Pane provenance must identify the displayed saved copy", state.savedCopy)
                assertFalse("A saved mirror must never enable pane writes", state.canWrite)
                assertFalse("Repository write admission must remain closed for a saved copy", repository.writesAllowed(address.scope, address.paneId))
                assertTrue("Pane status must explicitly identify the copy as read-only",
                    activity.findViewById<android.widget.TextView>(R.id.pane_status).text.toString().contains("Saved copy", ignoreCase = true))
                assertFalse("Offline composer must remain disabled", activity.findViewById<EditText>(R.id.reply_input).isEnabled)
                assertFalse("Offline Send must remain disabled", activity.findViewById<View>(R.id.send_button).isEnabled)
            }
        }
    }

    private fun verifyReconnect(
        app: CollieApplication,
        address: PaneAddress,
        repository: com.lateapex.collie.data.CollieRepository,
        store: OfflineContentStore,
    ) {
        assertEquals("Reconnect must preserve the operator's one-day choice", OfflineContentStore.Retention.ONE_DAY, store.retention)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await(scenario, "dashboard recovers fresh live snapshot after backend returns") { activity ->
                val state = androidx.lifecycle.ViewModelProvider(activity)[MainViewModel::class.java].state.value
                dashboardContainsAddress(activity, repository, address) &&
                    state.snapshot != null && state.savedCopy == null && state.writeAuthorized &&
                    repository.readSource.value is ReadSourceState.Live &&
                    !activity.findViewById<View>(R.id.dashboard_read_only_banner).isShown
            }
            scenario.onActivity { activity ->
                val state = assertDashboardScopeAndPane(activity, repository, address)
                assertNotNull("Reconnect must use a fresh dashboard response", state.snapshot)
                assertTrue("Fresh live dashboard must replace the saved-copy state", state.savedCopy == null && state.writeAuthorized)
            }
        }
        ActivityScenario.launch<PaneActivity>(paneIntent(app, address)).use { scenario ->
            await(scenario, "pane recovers fresh live response after backend returns") { activity ->
                val state = androidx.lifecycle.ViewModelProvider(activity)[PaneViewModel::class.java].state.value
                state.pane?.paneId == address.paneId && state.savedCopy == null && state.canWrite &&
                    repository.readSource.value is ReadSourceState.Live
            }
            scenario.onActivity { activity ->
                val state = androidx.lifecycle.ViewModelProvider(activity)[PaneViewModel::class.java].state.value
                assertEquals("Reconnect must read the exact controlled pane", address.paneId, state.pane?.paneId)
                assertTrue("A fresh live pane must replace the offline copy", state.savedCopy == null && state.canWrite)
                assertTrue("Pane writes may be admitted only after the live response", repository.writesAllowed(address.scope, address.paneId))
            }
        }
    }

    private fun optOut(app: CollieApplication, store: OfflineContentStore) {
        assertTrue("Opt-out phase requires real previously retained data", store.storageUsage().second > 0)
        selectRetention(OfflineContentStore.Retention.OFF, store)
        await("native opt-out deletes the app's retained reads") {
            store.retention == OfflineContentStore.Retention.OFF && store.storageUsage().second == 0
        }
        assertEquals("Native opt-out must remove all retained read rows", 0, store.storageUsage().second)
    }

    private fun selectRetention(value: OfflineContentStore.Retention, store: OfflineContentStore) {
        val control = when (value) {
            OfflineContentStore.Retention.OFF -> R.id.settings_offline_retention_off
            OfflineContentStore.Retention.ONE_DAY -> R.id.settings_offline_retention_one_day
            OfflineContentStore.Retention.SEVEN_DAYS -> R.id.settings_offline_retention_seven_days
        }
        ActivityScenario.launch<SettingsActivity>(SettingsActivity::class.java).use { scenario ->
            onView(withId(control)).perform(scrollTo(), click())
            await(scenario, "native retention setting action completes") { store.retention == value }
        }
    }

    private fun dashboardContainsAddress(
        activity: MainActivity,
        repository: com.lateapex.collie.data.CollieRepository,
        address: PaneAddress,
    ): Boolean {
        val viewModel = androidx.lifecycle.ViewModelProvider(activity)[MainViewModel::class.java]
        return viewModel.currentScope == address.scope && repository.selectedScope.value == address.scope &&
            viewModel.state.value.panes.any {
                it.paneId == address.paneId && it.host == address.scope.host && it.session == address.scope.session
            }
    }

    private fun assertDashboardScopeAndPane(
        activity: MainActivity,
        repository: com.lateapex.collie.data.CollieRepository,
        address: PaneAddress,
    ): MainUiState {
        val viewModel = androidx.lifecycle.ViewModelProvider(activity)[MainViewModel::class.java]
        assertEquals("Dashboard must use the exact supplied host/session scope", address.scope, viewModel.currentScope)
        assertEquals("Repository selection must match the exact supplied host/session scope", address.scope, repository.selectedScope.value)
        val state = viewModel.state.value
        assertTrue(
            "The dashboard snapshot must contain the controlled pane in the exact host/session",
            state.panes.any { it.paneId == address.paneId && it.host == address.scope.host && it.session == address.scope.session },
        )
        return state
    }

    private fun paneIntent(app: CollieApplication, address: PaneAddress) = Intent(app, PaneActivity::class.java)
        .putExtra(PaneActivity.EXTRA_PANE_ID, address.paneId)
        .putExtra(PaneActivity.EXTRA_AGENT, "shell")
        .putExtra(PaneActivity.EXTRA_HOST, address.scope.host)
        .putExtra(PaneActivity.EXTRA_SESSION, address.scope.session)
        .putExtra(PaneActivity.EXTRA_TITLE, "Controlled stock acceptance shell")

    private fun <T : android.app.Activity> await(scenario: ActivityScenario<T>, checkpoint: String, ready: (T) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            var matched = false
            scenario.onActivity { matched = ready(it) }
            if (matched) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Physical offline acceptance timed out: $checkpoint")
    }

    private fun await(checkpoint: String, ready: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            if (ready()) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Physical offline acceptance timed out: $checkpoint")
    }

    companion object {
        private const val WAIT_MS = 45_000L
        private const val REQUIRED_ORIGIN = "https://prod.lateapexllc.com/completion/"
        private const val CONTROLLED_PANE_ID = "w1:p1"
        private const val CODEX_PANE_ID = "w7:p1"
        private const val EXPECTED_CODEX_REPLY = "COLLIE_CC13_VENDOR_SOL_JOURNAL_OK"
    }
}
