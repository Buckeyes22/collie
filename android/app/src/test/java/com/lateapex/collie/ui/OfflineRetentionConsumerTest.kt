package com.lateapex.collie.ui

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.widget.TextView
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.data.*
import com.lateapex.collie.domain.*
import com.lateapex.collie.network.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Actual TLS/API/repository and public ViewModel/Activity consumers. Crypto is real AES/GCM
 * with an ephemeral fixture key; the separate instrumentation case owns Android Keystore proof. */
@OptIn(ExperimentalCoroutinesApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class OfflineRetentionConsumerTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = ViewModelStore()
    @Before fun mainDispatcher() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { models.clear(); Dispatchers.resetMain() }

    @Test fun actualMountedHttpsColdPaneAndChatAreReadOnlyUntilTheirOwnFresh200() {
        Fixture().use { f ->
            val scope = f.seedPane()
            val cold = f.repository()
            val vm = PaneViewModel(f.app, f.address, "shell", cold,
                com.lateapex.collie.diagnostics.RecordingDiagnosticsRecorder(mutableListOf()))
            models.put("pane", vm)
            await("encrypted cold pane and Chat restore") { vm.state.value.savedCopy != null }
            val copy = vm.state.value
            assertEquals(MIRROR, copy.pane?.text); assertEquals(CHAT, copy.transcript.single().parts.single().text)
            assertFalse(copy.canWrite); assertNull(copy.paneReadContext); assertNull(copy.transcriptReadContext)
            assertNull(copy.transcriptSendNow); assertTrue(copy.transcriptQueued.isEmpty())
            assertFalse(cold.writesAllowed()); assertNotNull(PaneWriteGate.block(f.address, copy))
            val before = f.effects()
            vm.sendReply("must not send")
            dispatcher.scheduler.runCurrent()
            assertEquals(before, f.effects())
            assertNotNull(f.store.pane(scope))

            f.online = true
            vm.refresh()
            await("a newly accepted real HTTPS 200 reacquires only live authority") { vm.state.value.paneReadContext != null && vm.state.value.savedCopy == null }
            assertTrue(cold.isCurrent(requireNotNull(vm.state.value.paneReadContext)))
            val paneRequests = f.requests.filter { it.url?.encodedPath == "/mount/api/pane/pane-1" }
            assertTrue(paneRequests.size >= 2)
            assertNull("saved copies cannot supply a validator to mint a body", paneRequests.last().headers["If-None-Match"])
            assertTrue(paneRequests.all { it.method == "GET" && it.headers["Authorization"] == "Bearer fixture-bearer" })
            assertEquals(before, f.effects())
        }
    }

    @Test fun actualDashboardColdRestoreHasNoDeviceCapabilityOrMutationAuthorityAndClearErasesIt() {
        Fixture().use { f ->
            f.seedDashboard()
            val repo = f.repository()
            val vm = MainViewModel(f.app, repo); models.put("main", vm)
            await("cold Dashboard restore") { vm.state.value.savedCopy != null }
            assertEquals("pane-1", vm.state.value.panes.single().paneId)
            assertNull(vm.state.value.originalReadContext); assertFalse(vm.state.value.writeAuthorized)
            assertNull(vm.state.value.snapshot!!.device); assertNull(vm.state.value.snapshot!!.controlAuthority)
            assertNull(vm.state.value.mux); assertNull(vm.state.value.launchers)
            assertFalse(repo.writesAllowed()); assertEquals(0, f.effects())
            assertTrue(f.store.clearReads())
            await("clear-now erases retained public Dashboard state") { vm.state.value.snapshot == null && vm.state.value.savedCopy == null }
            assertEquals(0, f.effects())
        }
    }

    @Test fun stoppedActualPaneCopiesAreSynchronouslyWipedByOffMainOriginalOwnerDisconnect() {
        Fixture().use { f ->
            val scope = f.seedPane()
            val repo = f.repository()
            val container = f.app.container
            val field = container.javaClass.getDeclaredField("repository").apply { isAccessible = true }
            val originalRepository = field.get(container)
            field.set(container, repo)
            val controller = Robolectric.buildActivity(PaneActivity::class.java, Intent(f.app, PaneActivity::class.java)
                .putExtra(PaneActivity.EXTRA_PANE_ID, "pane-1").putExtra(PaneActivity.EXTRA_AGENT, "shell"))
                .create().start().resume()
            try {
                val activity = controller.get()
                await("real Activity draws encrypted retained terminal") { activity.findViewById<TextView>(R.id.terminal_text).text.toString().contains(MIRROR) }
                val terminal = activity.findViewById<TextView>(R.id.terminal_text)
                val editor = activity.findViewById<android.widget.EditText>(R.id.reply_input)
                assertTrue(activity.findViewById<TextView>(R.id.pane_status).text.toString().contains("Saved copy"))
                assertEquals(0, (terminal.text as? android.text.Spanned)?.getSpans(0, terminal.length(), android.text.style.ClickableSpan::class.java)?.size ?: 0)
                editor.setText("volatile current draft")
                controller.pause().stop()
                val failures = CopyOnWriteArrayList<Throwable>()
                val retired = Thread { try { runBlocking { repo.disconnect() } } catch (failure: Throwable) { failures += failure } }
                retired.start()
                await("off-main disconnect returns after the stopped screen ACK") { !retired.isAlive }
                assertTrue(failures.toString(), failures.isEmpty())
                assertEquals("", terminal.text.toString()); assertEquals("", editor.text.toString())
                assertNull(f.store.pane(scope)); assertEquals(0, f.effects())
            } finally { controller.destroy(); field.set(container, originalRepository) }
        }
    }

    @Test fun actualGatewayAndBareRefusalsFreezeButKeepOwnedCiphertextWhileTypedPairingRetirementDeletesIt() {
        Fixture().use { f ->
            val scope = f.seedPane()
            val repo = f.repository(); repo.selectSubject(f.address.scope, f.address.paneId)
            for ((source, factor, code) in listOf(Triple("gateway", "identity", "gateway.identity_required"),
                Triple("gateway", "device", "gateway.device_required"), Triple(null, null, null))) {
                f.status = 403; f.failure = """{"error":"refused"}"""
                f.source = source; f.factor = factor; f.code = code
                val context = requireNotNull(repo.readContext(f.address.scope, f.address.paneId))
                val result = runBlocking { repo.readPane(f.address, conditional = false, expectedContext = context) }
                assertTrue(result is ApiResult.Failure)
                assertNotNull(f.connections.captureCommitted()!!.connection!!.token)
                assertNotNull(f.store.pane(scope)); assertFalse(repo.writesAllowed())
            }
            f.failure = """{"error":"expired","code":"pairing.expired"}"""
            f.source = "collie"; f.factor = "pairing"; f.code = "pairing.expired"
            val actor = requireNotNull(repo.readContext(f.address.scope, f.address.paneId))
            runBlocking { repo.readPane(f.address, conditional = false, expectedContext = actor) }
            assertNull(f.store.pane(scope)); assertEquals(0, f.effects())
        }
    }

    @Test fun scopedConsentedMigrationIsRetiredByActualAcceptedPasswordWithoutAnActivity() {
        Fixture().use { f ->
            val scope = f.seedPane()
            assertTrue(f.store.chooseDrafts(true))
            val legacy = f.app.getSharedPreferences(NativePreferences.PREFERENCES, Context.MODE_PRIVATE)
            val key = listOf(scope.owner.origin, scope.owner.pairedDeviceIdentity, scope.host.orEmpty(), scope.session.orEmpty(), scope.subject).joinToString("|")
            val unknown = "legacy-unknown-${java.util.UUID.randomUUID()}"
            legacy.edit().putString("pane_draft:$key", "owned legacy draft").putLong("pane_draft_saved:$key", System.currentTimeMillis())
                .putString("pane_draft_owner:$key", scope.owner.storageIdentity)
                .putString("pane_draft:$unknown", "unknown target legacy").putString("pane_draft_owner:$unknown", scope.owner.storageIdentity).commit()
            val preferences = NativePreferences(f.app, f.store)
            val repo = f.repository(); repo.selectSubject(f.address.scope, f.address.paneId)
            val actor = requireNotNull(repo.readContext(f.address.scope, f.address.paneId))
            assertFalse(preferences.migrateLegacyDrafts(scope.owner) { repo.isCurrent(actor) })
            assertTrue(legacy.contains("pane_draft:$key"))
            assertTrue(preferences.migrateLegacyDrafts(scope.owner, scope) { repo.isCurrent(actor) })
            assertFalse(legacy.contains("pane_draft:$key")); assertTrue(legacy.contains("pane_draft:$unknown"))
            assertEquals("owned legacy draft", f.coldRetention().draft(key, scope.owner))
            val other = scope.copy(subject = "other-pane")
            assertTrue(f.store.saveDraft("other", "other pane draft", other.owner, other))

            f.online = true; f.paneBody = OfflineContentStoreTest.mirror().copy(text = "Password: ", revision = 5)
            val password = runBlocking { repo.readPane(f.address, conditional = false, expectedContext = actor) } as ApiResult.Success
            assertNotNull(com.lateapex.collie.ui.terminal.TerminalComposerSemantics.noEchoPrompt(password.value.pane.text))
            runBlocking { repo.retainPane(actor, password.value.pane) } // No Activity or input clear participates.
            assertNull(f.coldRetention().draft(key, scope.owner)); assertNull(f.coldRetention().pane(scope))
            assertEquals("other pane draft", f.coldRetention().draft("other", other.owner))
            assertTrue(legacy.contains("pane_draft:$unknown")) // Unknown user legacy data is not guessed/deleted.
            assertFalse(f.store.retainPane(scope, null, OfflineContentStoreTest.entries()) { repo.isCurrent(actor) })
            assertFalse(f.store.saveDraft(key, "must not save password", scope.owner, scope) { repo.isCurrent(actor) })

            f.paneBody = OfflineContentStoreTest.mirror().copy(revision = 6)
            val clean = runBlocking { repo.readPane(f.address, conditional = false, expectedContext = actor) } as ApiResult.Success
            runBlocking { repo.retainPane(actor, clean.value.pane) }
            assertTrue(f.store.saveDraft(key, "new ordinary edit", scope.owner, scope) { repo.isCurrent(actor) })
            assertEquals("new ordinary edit", f.coldRetention().draft(key, scope.owner))

            f.connections.save(Connection(requireNotNull(f.connections.captureCommitted()?.connection).origin, "successor", "replacement-fixture-bearer"))
            dispatcher.scheduler.runCurrent(); shadowOf(Looper.getMainLooper()).idle()
            val successor = requireNotNull(repo.readContext(f.address.scope, f.address.paneId))
            val next = requireNotNull(repo.retentionScope(successor))
            assertNotEquals(scope.owner, next.owner)
            assertTrue(f.store.saveDraft("successor", "B survives A retirement", next.owner, next) { repo.isCurrent(successor) })
            assertEquals("B survives A retirement", f.coldRetention().draft("successor", next.owner))
            assertEquals(0, f.effects())
            legacy.edit().remove("pane_draft:$unknown").remove("pane_draft_owner:$unknown").commit()
        }
    }

    @Test fun acceptedHeadlessPasswordRetiresOrdinaryRamAndBlocksFutureCopiesWithRetentionOnOrOff() {
        for (persist in listOf(false, true)) Fixture().use { f ->
            val scope = f.seedPane()
            assertTrue(f.store.chooseDrafts(persist))
            if (!persist) assertTrue(f.store.chooseRetention(OfflineContentStore.Retention.OFF))
            val prefs = NativePreferences(f.app, f.store)
            val repo = f.repository()
            val otherAddress = PaneAddress(paneId = "other-pane")
            repo.selectSubject(otherAddress.scope, otherAddress.paneId)
            val other = requireNotNull(repo.localDraftCustody(otherAddress))
            prefs.savePaneDraft(other.key, "other pane private", other.owner, other.scope, other.localIdentity) { repo.isLocalDraftCurrent(other) }
            repo.selectSubject(f.address.scope, f.address.paneId)
            val original = requireNotNull(repo.readContext(f.address.scope, f.address.paneId))
            val local = requireNotNull(repo.localDraftCustody(f.address, original))
            prefs.savePaneDraft(local.key, "ordinary private draft", scope.owner, scope) { repo.isCurrent(original) }
            assertEquals("ordinary private draft", NativePreferences(f.app, f.store).paneDraft(local.key, scope.owner))
            if (persist) assertEquals("ordinary private draft", f.coldRetention().draft(local.key, scope.owner))
            val unknown = "unknown-target-${java.util.UUID.randomUUID()}"
            val legacy = f.app.getSharedPreferences(NativePreferences.PREFERENCES, Context.MODE_PRIVATE)
            legacy.edit().putString("pane_draft:$unknown", "unknown legacy").commit()
            f.online = true; f.paneBody = OfflineContentStoreTest.mirror().copy(text = "Password: ", revision = 12)
            val password = runBlocking { repo.readPane(f.address, conditional = false, expectedContext = original) } as ApiResult.Success
            runBlocking { repo.retainPane(original, password.value.pane) }
            assertEquals("", NativePreferences(f.app, f.store).paneDraft(local.key, scope.owner))
            assertNull(f.coldRetention().draft(local.key, scope.owner))
            assertEquals("other pane private", prefs.paneDraft(other.key, other.owner, other.scope))
            assertTrue(legacy.contains("pane_draft:$unknown"))
            prefs.savePaneDraft(local.key, "must stay blocked", scope.owner, scope) { repo.isCurrent(original) }
            assertEquals("", prefs.paneDraft(local.key, scope.owner))
            assertFalse(f.store.retainPane(scope, null, OfflineContentStoreTest.entries()) { repo.isCurrent(original) })
            f.paneBody = OfflineContentStoreTest.mirror().copy(revision = 13)
            val clean = runBlocking { repo.readPane(f.address, conditional = false, expectedContext = original) } as ApiResult.Success
            runBlocking { repo.retainPane(original, clean.value.pane) }
            prefs.savePaneDraft(local.key, "new ordinary edit", scope.owner, scope) { repo.isCurrent(original) }
            assertEquals("new ordinary edit", prefs.paneDraft(local.key, scope.owner))
            f.connections.save(Connection(requireNotNull(f.connections.captureCommitted()?.connection).origin, "B", "successor-bearer"))
            val successor = requireNotNull(repo.localDraftCustody(f.address))
            prefs.savePaneDraft(successor.key, "successor edit", successor.owner, successor.scope, successor.localIdentity) { repo.isLocalDraftCurrent(successor) }
            prefs.savePaneDraft(local.key, "late old edit", local.owner, local.scope, local.localIdentity) { repo.isLocalDraftCurrent(local) }
            assertEquals("successor edit", prefs.paneDraft(successor.key, successor.owner, successor.scope))
            assertEquals(0, f.effects())
            legacy.edit().remove("pane_draft:$unknown").commit()
        }
    }

    @Test fun unconfiguredLocalEditingWitnessSurvivesRecreationAndRejectsReplacementWithoutReadActor() {
        Fixture().use { f ->
            assertTrue(f.store.chooseRetention(OfflineContentStore.Retention.OFF))
            assertFalse(f.store.draftsEnabled)
            val predecessorRepo = f.repository(); predecessorRepo.selectSubject(f.address.scope, f.address.paneId)
            val predecessor = requireNotNull(predecessorRepo.localDraftCustody(f.address))
            f.connections.clear()
            val repo = f.repository(); repo.selectSubject(f.address.scope, f.address.paneId)
            val local = requireNotNull(repo.localDraftCustody(f.address))
            assertNull(local.owner); assertNull(local.scope); assertNull(repo.readContext(f.address.scope, f.address.paneId))
            val prefs = NativePreferences(f.app, f.store)
            prefs.savePaneDraft(local.key, "volatile before any read", localIdentity = local.localIdentity) { repo.isLocalDraftCurrent(local) }
            val recreated = requireNotNull(repo.localDraftCustody(f.address))
            assertEquals(local.key, recreated.key)
            assertEquals("volatile before any read", prefs.paneDraft(recreated.key, localIdentity = recreated.localIdentity))
            prefs.retireSensitiveContent(requireNotNull(predecessor.owner))
            assertEquals("volatile before any read", prefs.paneDraft(recreated.key, localIdentity = recreated.localIdentity))
            f.connections.save(Connection(CollieOrigin("https://replacement.test/"), "B", "replacement-bearer"))
            val replacement = requireNotNull(repo.localDraftCustody(f.address))
            prefs.savePaneDraft(replacement.key, "new owner local edit", replacement.owner, replacement.scope, replacement.localIdentity) { repo.isLocalDraftCurrent(replacement) }
            assertFalse(repo.isLocalDraftCurrent(local))
            prefs.savePaneDraft(local.key, "late original overwrite", localIdentity = local.localIdentity) { repo.isLocalDraftCurrent(local) }
            prefs.retireSensitiveContent(requireNotNull(predecessor.owner))
            assertEquals("new owner local edit", prefs.paneDraft(replacement.key, replacement.owner, replacement.scope))
            assertEquals(0, f.effects())
        }
    }

    @Test fun actualStoppedPaneAckClearsCopiedRamAfterAcceptedHeadlessPasswordWithRetentionOff() {
        Fixture().use { f ->
            assertTrue(f.store.chooseRetention(OfflineContentStore.Retention.OFF))
            assertFalse(f.store.draftsEnabled)
            val preferences = NativePreferences(f.app, f.store) // The actual facade retains its preference consumer.
            val repo = f.repository()
            val container = f.app.container
            val field = container.javaClass.getDeclaredField("repository").apply { isAccessible = true }
            val previous = field.get(container)
            field.set(container, repo)
            f.online = true
            val controller = Robolectric.buildActivity(PaneActivity::class.java, Intent(f.app, PaneActivity::class.java)
                .putExtra(PaneActivity.EXTRA_PANE_ID, f.address.paneId).putExtra(PaneActivity.EXTRA_AGENT, "shell"))
                .create().start().resume()
            try {
                val activity = controller.get()
                await("the actual Activity accepts its current HTTPS mirror") {
                    activity.findViewById<TextView>(R.id.terminal_text).text.toString().contains(MIRROR)
                }
                val actor = requireNotNull(repo.readContext(f.address.scope, f.address.paneId))
                val local = requireNotNull(repo.localDraftCustody(f.address, actor))
                val editor = activity.findViewById<android.widget.EditText>(R.id.reply_input)
                editor.setText("stopped ordinary private draft")
                assertEquals("stopped ordinary private draft", preferences.paneDraft(local.key, local.owner, local.scope))
                controller.pause().stop()
                f.paneBody = OfflineContentStoreTest.mirror().copy(text = "Password: ", revision = 20)
                val failures = CopyOnWriteArrayList<Throwable>()
                val retired = Thread {
                    try {
                        runBlocking {
                            val password = repo.readPane(f.address, conditional = false, expectedContext = actor) as ApiResult.Success
                            repo.retainPane(actor, password.value.pane)
                        }
                    } catch (failure: Throwable) { failures += failure }
                }
                retired.start()
                await("headless password retirement returns only after the stopped Activity ACK") { !retired.isAlive }
                assertTrue(failures.toString(), failures.isEmpty())
                assertEquals("", editor.text.toString())
                assertEquals("", NativePreferences(f.app, f.store).paneDraft(local.key, local.owner, local.scope))
                preferences.savePaneDraft(local.key, "password future copy", local.owner, local.scope, local.localIdentity) { repo.isLocalDraftCurrent(local) }
                assertEquals("", preferences.paneDraft(local.key, local.owner, local.scope))
                assertFalse(f.store.draftsEnabled)
                assertEquals(OfflineContentStore.Retention.OFF, f.store.retention)
                assertEquals(0, f.effects())
            } finally { controller.destroy(); field.set(container, previous) }
        }
    }

    @Test fun acceptedPipeTargetsHaveDistinctKeysAndEveryRamAndColdCipherReadChecksTarget() {
        for (persist in listOf(false, true)) Fixture().use { f ->
            assertTrue(f.store.chooseDrafts(persist))
            val repo = f.repository()
            val a = PaneAddress(Scope(session = "s|p"), "x")
            val b = PaneAddress(Scope(session = "s"), "p|x")
            repo.selectSubject(a.scope, a.paneId)
            val first = requireNotNull(repo.localDraftCustody(a))
            repo.selectSubject(b.scope, b.paneId)
            val second = requireNotNull(repo.localDraftCustody(b))
            val firstScope = requireNotNull(first.scope); val secondScope = requireNotNull(second.scope)
            assertEquals(first.owner, second.owner)
            assertNotEquals(firstScope.target, secondScope.target)
            fun oldRaw(local: LocalDraftCustody) = listOf(local.connection?.origin?.value.orEmpty(), local.owner?.pairedDeviceIdentity,
                local.address.scope.host.orEmpty(), local.address.scope.session.orEmpty(), local.address.paneId).joinToString("|")
            assertEquals("The actual accepted tuples reproduce the former ambiguous encoding", oldRaw(first), oldRaw(second))
            assertNotEquals(first.key, second.key)
            assertTrue(first.key.startsWith("draft:v2:")); assertTrue(second.key.startsWith("draft:v2:"))
            val preferences = NativePreferences(f.app, f.store)
            val legacy = f.app.getSharedPreferences(NativePreferences.PREFERENCES, Context.MODE_PRIVATE)
            val ambiguousLegacyKey = oldRaw(first)
            legacy.edit().putString("pane_draft:$ambiguousLegacyKey", "ambiguous old user draft")
                .putString("pane_draft_owner:$ambiguousLegacyKey", requireNotNull(first.owner).storageIdentity).commit()
            repo.selectSubject(a.scope, a.paneId)
            preferences.savePaneDraft(first.key, "scope A only", first.owner, firstScope, first.localIdentity) { repo.isLocalDraftCurrent(first) }
            assertEquals("scope A only", preferences.paneDraft(first.key, first.owner, firstScope))
            assertEquals("", preferences.paneDraft(second.key, second.owner, secondScope))
            assertEquals("Even intentionally reused keys cannot borrow RAM target A", "", preferences.paneDraft(first.key, first.owner, secondScope))
            assertEquals("", preferences.paneDraft(first.key, first.owner, firstScope.copy(incarnation = "00000000-0000-0000-0000-000000000999")))
            assertEquals("", preferences.paneDraft(first.key, first.owner, firstScope.copy(viewAll = true)))
            assertFalse(preferences.migrateLegacyDrafts(requireNotNull(first.owner), firstScope) { repo.isLocalDraftCurrent(first) })
            assertEquals("ambiguous old user draft", legacy.getString("pane_draft:$ambiguousLegacyKey", null))
            val reusedKey = "defense-${java.util.UUID.randomUUID()}"
            preferences.savePaneDraft(reusedKey, "defense A", first.owner, firstScope) { repo.isLocalDraftCurrent(first) }
            assertEquals("defense A", preferences.paneDraft(reusedKey, first.owner, firstScope))
            assertEquals("", preferences.paneDraft(reusedKey, second.owner, secondScope))
            if (persist) {
                val cold = f.coldRetention()
                assertEquals("scope A only", cold.draft(first.key, requireNotNull(first.owner), firstScope))
                assertNull(cold.draft(first.key, requireNotNull(first.owner), secondScope))
                assertNull(cold.draft(second.key, requireNotNull(second.owner), secondScope))
                // This product reader has no RAM entry: actual SQL/GCM expected-target binding is exercised.
                val ciphertextKey = "cipher-only-${java.util.UUID.randomUUID()}"
                assertTrue(f.store.saveDraft(ciphertextKey, "cipher A", requireNotNull(first.owner), firstScope))
                assertEquals("cipher A", NativePreferences(f.app, cold).paneDraft(ciphertextKey, first.owner, firstScope))
                assertEquals("", NativePreferences(f.app, cold).paneDraft(ciphertextKey, second.owner, secondScope))
            } else assertNull(f.coldRetention().draft(first.key, requireNotNull(first.owner), firstScope))
            repo.selectSubject(f.address.scope, f.address.paneId)
            val normal = requireNotNull(repo.localDraftCustody(f.address))
            assertEquals(oldRaw(normal), normal.key) // Every original unambiguous raw-key control remains valid.
            val broad = PaneAddress(Scope(viewAll = true), f.address.paneId)
            repo.selectSubject(broad.scope, broad.paneId)
            val all = requireNotNull(repo.localDraftCustody(broad))
            assertNotEquals(normal.key, all.key); assertTrue(requireNotNull(all.scope).viewAll)
            assertNotEquals(normal.scope?.target, all.scope?.target)
            repo.selectSubject(a.scope, a.paneId)
            val same = requireNotNull(f.connections.captureCommitted()?.connection)
            f.connections.save(same)
            assertEquals(first.key, requireNotNull(repo.localDraftCustody(a)).key)
            assertTrue(repo.isLocalDraftCurrent(first))
            f.connections.save(Connection(same.origin, "replacement", "replacement-bearer"))
            val replacement = requireNotNull(repo.localDraftCustody(a))
            assertNotEquals(first.key, replacement.key)
            assertEquals("", preferences.paneDraft(replacement.key, replacement.owner, replacement.scope))
            legacy.edit().remove("pane_draft:$ambiguousLegacyKey").remove("pane_draft_owner:$ambiguousLegacyKey").commit()
            assertEquals(0, f.effects())
        }
    }

    @Test fun heldActualCacheDecryptionCannotRemintNavigationOrOverwriteFreshHttpsPane() {
        val cipher = BlockingFixtureCipher()
        Fixture(retainedCipher = cipher).use { f ->
            f.seedPane()
            val repo = f.repository()
            val vm = PaneViewModel(f.app, f.address, "shell", repo,
                com.lateapex.collie.diagnostics.RecordingDiagnosticsRecorder(mutableListOf()))
            models.put("cache-overlap", vm)
            await("initial actual encrypted copy installs") { vm.state.value.savedCopy != null }
            dispatcher.scheduler.runCurrent()
            assertEquals("An unchanged initial changes replay must not decrypt a second copy", 1, cipher.decryptions.get())
            val oldCopy = requireNotNull(vm.state.value.savedCopyVersion)
            val cache = cipher.holdNext()
            assertTrue(f.store.chooseDrafts(true)) // Real facade change, no read-policy clear or UI state injection.
            await("actual AES decryption is held during the second restore") { cache.entered.count == 0L }
            val pane = HeldPane()
            f.heldPane = pane; f.online = true
            f.paneBody = OfflineContentStoreTest.mirror().copy(text = "fresh current HTTPS body", revision = 30)
            val requestActor = requireNotNull(repo.readContext(f.address.scope, f.address.paneId))
            try {
                vm.refresh()
                await("a real original HTTPS GET starts while cache decryption is held") { pane.entered.count == 0L }
                assertEquals(oldCopy, vm.state.value.savedCopyVersion)
                assertTrue(repo.isCurrent(requestActor))
                pane.release.countDown()
                cache.release.countDown()
                await("the late cache cannot retire or overwrite the actual accepted 200") {
                    vm.state.value.pane?.text == "fresh current HTTPS body" && vm.state.value.savedCopy == null &&
                        vm.state.value.paneReadContext?.let(repo::isCurrent) == true
                }
                val accepted = requireNotNull(vm.state.value.paneReadContext)
                assertNull(vm.state.value.savedCopyVersion)
                assertTrue(repo.isCurrent(accepted))
                val count = cipher.decryptions.get()
                assertTrue(f.store.chooseDrafts(false))
                dispatcher.scheduler.runCurrent(); shadowOf(Looper.getMainLooper()).idle()
                assertEquals(count, cipher.decryptions.get()) // A later change cannot install cache over an accepted body.
                assertEquals("fresh current HTTPS body", vm.state.value.pane?.text)
                assertEquals(0, f.effects())
            } finally { cache.release.countDown(); pane.release.countDown(); models.clear() }
        }
    }

    @Test fun copiedMirrorAndChatExpireDuringHeldHttpsWithoutErasingTheLaterFreshBody() {
        val day = 24L * 60 * 60 * 1000
        for (policy in listOf(OfflineContentStore.Retention.ONE_DAY, OfflineContentStore.Retention.SEVEN_DAYS)) {
            var clock = System.currentTimeMillis()
            val start = clock
            Fixture(clock = { clock }).use { f ->
                assertTrue(f.store.chooseRetention(policy))
                f.seedPane()
                val repo = f.repository()
                val vm = PaneViewModel(f.app, f.address, "shell", repo,
                    com.lateapex.collie.diagnostics.RecordingDiagnosticsRecorder(mutableListOf()))
                models.put("expiry-$policy", vm)
                await("actual copied mirror and Chat install before their deadlines") { vm.state.value.savedCopy != null }
                assertEquals(MIRROR, vm.state.value.pane?.text)
                assertEquals(CHAT, vm.state.value.transcript.single().parts.single().text)
                val pane = HeldPane(); f.heldPane = pane; f.online = true
                f.paneBody = OfflineContentStoreTest.mirror().copy(text = "live after old copy expiry", revision = 40)
                try {
                    vm.refresh()
                    await("actual HTTPS response is held across copied expiry") { pane.entered.count == 0L }
                    clock = start + day + 1
                    dispatcher.scheduler.advanceTimeBy(day + 1); dispatcher.scheduler.runCurrent()
                    assertNull("The old 24h mirror must erase without waiting for GET or SQL", vm.state.value.pane)
                    assertNull(vm.state.value.paneReadContext); assertFalse(vm.state.value.canWrite)
                    if (policy == OfflineContentStore.Retention.SEVEN_DAYS) {
                        assertEquals(CHAT, vm.state.value.transcript.single().parts.single().text)
                        assertNotNull(vm.state.value.savedCopy)
                        clock = start + 7 * day + 1
                        dispatcher.scheduler.advanceTimeBy(6 * day); dispatcher.scheduler.runCurrent()
                    }
                    assertTrue(vm.state.value.transcript.isEmpty())
                    assertNull(vm.state.value.savedCopy); assertNull(vm.state.value.savedCopyVersion)
                    assertEquals("No response release was required for copied erasure", 1L, pane.release.count)
                    pane.release.countDown()
                    await("original live200 remains admissible after local copied expiry") {
                        vm.state.value.pane?.text == "live after old copy expiry" && vm.state.value.savedCopy == null &&
                            vm.state.value.paneReadContext?.let(repo::isCurrent) == true
                    }
                    val live = requireNotNull(vm.state.value.paneReadContext)
                    clock += 7 * day
                    dispatcher.scheduler.advanceTimeBy(7 * day); dispatcher.scheduler.runCurrent()
                    assertEquals("live after old copy expiry", vm.state.value.pane?.text)
                    assertSame(live, vm.state.value.paneReadContext)
                    assertTrue(repo.isCurrent(live)); assertEquals(0, f.effects())
                } finally { pane.release.countDown(); models.clear() }
            }
        }
    }

    @Test fun actualCacheIoReleasedAfterViewModelClearCannotPublishCopiedContent() {
        val cipher = BlockingFixtureCipher()
        Fixture(retainedCipher = cipher).use { f ->
            f.seedPane()
            val cache = cipher.holdNext()
            val vm = PaneViewModel(f.app, f.address, "shell", f.repository(),
                com.lateapex.collie.diagnostics.RecordingDiagnosticsRecorder(mutableListOf()))
            models.put("clear-held-cache", vm)
            try {
                await("original cache AES IO is held") { cache.entered.count == 0L }
                models.clear()
                cache.release.countDown()
                await("held actual decryption returns after lifecycle clear") { cache.finished.count == 0L }
                dispatcher.scheduler.runCurrent(); shadowOf(Looper.getMainLooper()).idle()
                assertNull(vm.state.value.savedCopy); assertNull(vm.state.value.pane)
                assertEquals(0, f.effects())
            } finally { cache.release.countDown() }
        }
    }

    @Test fun actualSupporting503AcknowledgementsKeepSavedViewsWhileRealOwnerRetirementStillWipesThem() {
        val fingerprint = android.os.Build.FINGERPRINT
        try { Fixture().use { f ->
            val scope = f.seedPane()
            // Application initialization keeps its original JVM watchdog behavior. Only this
            // owned Activity render takes the actual asynchronous text-preparation branch.
            org.robolectric.shadows.ShadowBuild.setFingerprint("collie-owned-async-render")
            val repo = f.repository()
            val container = f.app.container
            val field = container.javaClass.getDeclaredField("repository").apply { isAccessible = true }
            val previous = field.get(container)
            field.set(container, repo)
            val config = HeldPane(); val snapshot = HeldPane()
            f.heldConfig = config; f.heldSnapshot = snapshot
            val controller = Robolectric.buildActivity(PaneActivity::class.java, Intent(f.app, PaneActivity::class.java)
                .putExtra(PaneActivity.EXTRA_PANE_ID, f.address.paneId).putExtra(PaneActivity.EXTRA_AGENT, "shell"))
                .create().start().resume()
            var notification: AutoCloseable? = null
            try {
                val activity = controller.get()
                val terminal = activity.findViewById<TextView>(R.id.terminal_text)
                val editor = activity.findViewById<android.widget.EditText>(R.id.reply_input)
                await("actual encrypted fallback is drawn before the supporting failure returns") {
                    config.entered.count == 0L && terminal.text.toString().contains(MIRROR)
                }
                assertTrue(activity.findViewById<TextView>(R.id.pane_status).text.toString().contains("Saved copy"))
                val copied = requireNotNull(repo.offlineSelection(f.address.scope, f.address.paneId)).version
                val acknowledgedFreezes = java.util.concurrent.atomic.AtomicInteger()
                // Registration follows the actual Activity listener; this observes settlement of
                // its real callback, not a fabricated UI state or a private renderer invocation.
                notification = repo.onAuthorityRetired { event ->
                    if (!event.retiresReadActor && event.affectsCustody(copied)) acknowledgedFreezes.incrementAndGet()
                }
                editor.setText("ordinary current draft")
                config.release.countDown()
                await("actual config503 ACK finishes before the next held snapshot") {
                    snapshot.entered.count == 0L && acknowledgedFreezes.get() == 1
                }
                assertTrue("Temporary read-only admission freeze cannot erase the opted mirror", terminal.text.toString().contains(MIRROR))
                assertEquals("ordinary current draft", editor.text.toString())
                assertNotNull(f.store.pane(scope)); assertFalse(repo.writesAllowed())
                snapshot.release.countDown()
                await("actual snapshot503 callback completes after the Activity ACK") { acknowledgedFreezes.get() == 2 }
                assertTrue(terminal.text.toString().contains(MIRROR))
                assertEquals("ordinary current draft", editor.text.toString())
                assertTrue(activity.findViewById<TextView>(R.id.pane_status).text.toString().contains("Saved copy"))
                assertEquals(0, (terminal.text as? android.text.Spanned)?.getSpans(0, terminal.length(), android.text.style.ClickableSpan::class.java)?.size ?: 0)
                assertEquals(0, f.effects())
                controller.pause().stop()
                val failures = CopyOnWriteArrayList<Throwable>()
                val retired = Thread { try { runBlocking { repo.disconnect() } } catch (failure: Throwable) { failures += failure } }
                retired.start()
                await("actual original-owner disconnect still waits for stopped copied-view wipe") { !retired.isAlive }
                assertTrue(failures.toString(), failures.isEmpty())
                assertEquals("", terminal.text.toString()); assertEquals("", editor.text.toString())
                assertNull(f.store.pane(scope)); assertEquals(0, f.effects())
            } finally {
                notification?.close(); config.release.countDown(); snapshot.release.countDown()
                controller.destroy(); field.set(container, previous)
            }
        } } finally { org.robolectric.shadows.ShadowBuild.setFingerprint(fingerprint) }
    }

    @Test fun actualHeldAsyncTextPreparationCannotDrawAfterStoppedOriginalOwnerRetirement() {
        val fingerprint = android.os.Build.FINGERPRINT
        try { Fixture().use { f ->
            val scope = f.seedPane()
            // Application initialization keeps its original JVM watchdog behavior. Only this
            // owned Activity render takes the actual asynchronous text-preparation branch.
            org.robolectric.shadows.ShadowBuild.setFingerprint("collie-owned-held-async-render")
            val repo = f.repository()
            val container = f.app.container
            val field = container.javaClass.getDeclaredField("repository").apply { isAccessible = true }
            val previous = field.get(container)
            field.set(container, repo)
            val config = HeldPane(); val snapshot = HeldPane()
            f.heldConfig = config; f.heldSnapshot = snapshot
            val prepared = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val finished = java.util.concurrent.CountDownLatch(1)
            val controller = Robolectric.buildActivity(PaneActivity::class.java, Intent(f.app, PaneActivity::class.java)
                .putExtra(PaneActivity.EXTRA_PANE_ID, f.address.paneId).putExtra(PaneActivity.EXTRA_AGENT, "shell"))
            val activity = controller.get()
            activity.precomputeTerminalText = { text, metrics ->
                try {
                    prepared.countDown()
                    check(release.await(5, TimeUnit.SECONDS)) { "Bounded original text preparation released" }
                    androidx.core.text.PrecomputedTextCompat.create(text, metrics) // Actual default factory, no replacement result.
                } finally { finished.countDown() }
            }
            controller.create().start().resume()
            try {
                await("actual original copied draw reaches held async Android text preparation") {
                    prepared.count == 0L && activity.findViewById<TextView>(R.id.pane_status).text.toString().contains("Saved copy")
                }
                val terminal = activity.findViewById<TextView>(R.id.terminal_text)
                val editor = activity.findViewById<android.widget.EditText>(R.id.reply_input)
                assertFalse("A held actual factory has not yet committed its old plaintext", terminal.text.toString().contains(MIRROR))
                editor.setText("old owner private draft")
                controller.pause().stop()
                val failures = CopyOnWriteArrayList<Throwable>()
                val retired = Thread { try { runBlocking { repo.disconnect() } } catch (failure: Throwable) { failures += failure } }
                retired.start()
                await("actual retirement returns after stopped copied content and pending draw are retired") { !retired.isAlive }
                assertTrue(failures.toString(), failures.isEmpty())
                assertEquals("", terminal.text.toString()); assertEquals("", editor.text.toString())
                assertNull(f.store.pane(scope))
                release.countDown()
                await("the held actual Android text factory finishes only after original retirement") { finished.count == 0L }
                dispatcher.scheduler.runCurrent(); shadowOf(Looper.getMainLooper()).idle()
                assertEquals("Late real preparation cannot resurrect retired copied terminal", "", terminal.text.toString())
                assertEquals("", editor.text.toString()); assertEquals(0, f.effects())
            } finally {
                release.countDown(); config.release.countDown(); snapshot.release.countDown()
                controller.destroy(); field.set(container, previous)
            }
        } } finally { org.robolectric.shadows.ShadowBuild.setFingerprint(fingerprint) }
    }

    private class HeldPane {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
    }
    private class HeldDecrypt {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val finished = java.util.concurrent.CountDownLatch(1)
    }
    private class BlockingFixtureCipher : SecretCipher {
        private val actual = FixtureCipher()
        private val held = java.util.concurrent.atomic.AtomicReference<HeldDecrypt?>()
        val decryptions = java.util.concurrent.atomic.AtomicInteger()
        fun holdNext() = HeldDecrypt().also { check(held.compareAndSet(null, it)) }
        override fun encrypt(plaintext: ByteArray): CipherEnvelope = actual.encrypt(plaintext)
        override fun decrypt(envelope: CipherEnvelope): ByteArray {
            val value = actual.decrypt(envelope)
            decryptions.incrementAndGet()
            val gate = held.getAndSet(null) ?: return value
            try {
                gate.entered.countDown()
                check(gate.release.await(5, TimeUnit.SECONDS)) { "Bounded actual cache decryption released" }
                return value
            } finally { gate.finished.countDown() }
        }
    }

    private fun await(message: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!predicate() && System.nanoTime() < deadline) {
            dispatcher.scheduler.runCurrent(); shadowOf(Looper.getMainLooper()).idle()
            if (!predicate()) Thread.sleep(5)
        }
        dispatcher.scheduler.runCurrent(); shadowOf(Looper.getMainLooper()).idle()
        assertTrue(message, predicate())
    }

    private class Fixture(
        private val clock: () -> Long = System::currentTimeMillis,
        retainedCipher: SecretCipher? = null,
    ) : AutoCloseable {
        val app: CollieApplication = ApplicationProvider.getApplicationContext()
        private val directory = File(app.noBackupFilesDir, "offline-consumer-${java.util.UUID.randomUUID()}")
        private val cipher = retainedCipher ?: FixtureCipher()
        private val connectionCipher = if (retainedCipher == null) cipher else FixtureCipher()
        val store = OfflineContentStore(RetainedReadDatabase(app, cipher, clock, directory), clock)
        private val preferences = app.getSharedPreferences("offline-owner-${java.util.UUID.randomUUID()}", Context.MODE_PRIVATE)
        val connections = EncryptedConnectionStore(app, connectionCipher, Json { ignoreUnknownKeys = true }, OriginValidator(), preferences)
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val address = PaneAddress(paneId = "pane-1")
        @Volatile var online = false
        @Volatile var heldPane: HeldPane? = null
        @Volatile var heldConfig: HeldPane? = null
        @Volatile var heldSnapshot: HeldPane? = null
        @Volatile var paneBody = OfflineContentStoreTest.mirror()
        @Volatile var status = 503
        @Volatile var failure = """{"error":"unreachable fixture"}"""
        @Volatile var source: String? = null
        @Volatile var factor: String? = null
        @Volatile var code: String? = null
        private val server = MockWebServer()
        private val actual: CollieApiClient
        init {
            val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
            val host = HandshakeCertificates.Builder().heldCertificate(certificate).build()
            val trust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
            server.useHttps(host.sslSocketFactory())
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    val path = request.url?.encodedPath
                    if (path == "/mount/api/pane/pane-1") heldPane?.let { gate ->
                        gate.entered.countDown()
                        check(gate.release.await(5, TimeUnit.SECONDS)) { "Bounded original HTTPS pane response released" }
                    }
                    val supportingGate = when (path) {
                        "/mount/api/config" -> heldConfig
                        "/mount/api/snapshot" -> heldSnapshot
                        else -> null
                    }
                    supportingGate?.let { gate ->
                        gate.entered.countDown()
                        check(gate.release.await(5, TimeUnit.SECONDS)) { "Bounded actual supporting response released" }
                    }
                    val body = when (path) {
                        "/mount/api/config" -> "{}"
                        "/mount/api/snapshot" -> Json.encodeToString(SnapshotResponse.serializer(), OfflineContentStoreTest.snapshot())
                        "/mount/api/pane/pane-1" -> Json.encodeToString(PaneReadResponse.serializer(), paneBody)
                        "/mount/api/pane/pane-1/chat" -> """{"paneId":"pane-1","available":false}"""
                        else -> """{"ok":true}"""
                    }
                    val response = MockResponse.Builder().code(if (online) 200 else status).setHeader("Content-Type", "application/json")
                    if (!online) {
                        source?.let { response.setHeader("x-collie-error-source", it) }
                        factor?.let { response.setHeader("x-collie-error-factor", it) }
                        code?.let { response.setHeader("x-collie-error-code", it) }
                    }
                    return response.body(if (online) body else failure).build()
                }
            }
            server.start()
            actual = CollieApiClient(CollieApiClient.defaultHttpClient().newBuilder()
                .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build(), Json { ignoreUnknownKeys = true })
            connections.save(Connection(CollieOrigin(server.url("/mount/").toString()), "fixture-phone", "fixture-bearer"))
            check(store.chooseRetention(OfflineContentStore.Retention.SEVEN_DAYS))
        }
        fun coldRetention() = OfflineContentStore(RetainedReadDatabase(app, cipher, clock, directory), clock)
        fun repository() = CollieRepository(actual, connections, OriginValidator(), offlineContentStore = store)
        fun effects(): Int = requests.count { it.method != "GET" && it.url?.encodedPath != "/mount/api/refresh" }
        fun seedPane(): RetainedReadScope = runBlocking {
            val repo = repository(); repo.selectSubject(address.scope, address.paneId)
            val actor = requireNotNull(repo.readContext(address.scope, address.paneId))
            online = true
            val result = repo.readPane(address, conditional = false, expectedContext = actor) as ApiResult.Success
            repo.retainPane(actor, result.value.pane, listOf(TranscriptEntry("saved-turn", "2026-10-12", "assistant", listOf(TranscriptPart("text", CHAT)))))
            online = false
            requireNotNull(repo.retentionScope(actor))
        }
        fun seedDashboard() = runBlocking {
            val repo = repository(); repo.selectSubject(Scope(), null)
            val actor = requireNotNull(repo.readContext())
            online = true
            val result = repo.snapshot(expectedContext = actor) as ApiResult.Success
            repo.retainSnapshot(actor, result.value); online = false
        }
        override fun close() { heldPane?.release?.countDown(); heldConfig?.release?.countDown(); heldSnapshot?.release?.countDown(); server.close(); preferences.edit().clear().commit(); directory.deleteRecursively() }
    }
    companion object {
        private const val MIRROR = "masked retained output"
        private const val CHAT = "saved masked Chat tail"
    }
}
