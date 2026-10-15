package com.lateapex.collie.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.view.View
import android.widget.EditText
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.lateapex.collie.BuildConfig
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.ApiFailure
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.SnapshotResponse
import com.lateapex.collie.network.retiresPairing
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Existing paired HTTPS, no invented issuer or authentication headers.
 *
 * Run links first. Then run the held revocation selector with admissionWaitForParent=true.
 * At the READY marker the parent uses the NORMAL CLI against ONLY the completion stock state:
 * COLLIE_STATE_DIR=<actual stock state> HERDR_PLUGIN_CONFIG_DIR=<actual stock config>
 * HERDR_SOCKET_PATH=/var/tmp/cc13-h/herdr/sessions/cc13/herdr.sock
 * bun cli/main.ts devices revoke 'completion Pixel'
 * Never guess the state/config directories or edit/copy the registry. The marker has only the
 * public owned label, committed incarnation and process ID. Reads use the retained original
 * connection while this test is alive; it never restores a token after native retirement.
 * After success, parent kills/reopens ONLY the completion namespace and supplies admissionPriorPid
 * to the restart selector. Native pairing repair is a separate operator action after acceptance.
 * URL refusal exercises MainActivity's existing native deep-link boundary; this does not claim
 * a QR scanner/share importer or a configured external identity-expiry environment exists.
 */
@RunWith(AndroidJUnit4::class)
class PhysicalAdmissionAcceptanceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private data class Live(val app: CollieApplication, val original: Connection)

    private fun live(paired: Boolean): Live {
        val supplied = args.getString("liveOrigin")
        assumeTrue("No liveOrigin supplied; physical admission acceptance is opt-in", supplied != null)
        assertFalse("Supplied liveOrigin must not be blank", supplied.isNullOrBlank())
        assertTrue("Only the isolated completion app may run this drill", BuildConfig.APPLICATION_ID.endsWith(".completiontest"))
        val app = ApplicationProvider.getApplicationContext<Context>().applicationContext as CollieApplication
        val expected = app.container.originValidator.requireValid(ORIGIN)
        assertEquals("Only the exact task-owned stock HTTPS mount is accepted", expected, app.container.originValidator.requireValid(requireNotNull(supplied)))
        assertEquals("Only the disposable stock shell is accepted", PANE, args.getString("livePaneId"))
        assertTrue("Keep this drill on the local default stock scope", args.getString("liveHost").isNullOrBlank() && args.getString("liveSession").isNullOrBlank())
        val original = requireNotNull(app.container.connectionStore.connection.value)
        assertEquals(expected, original.origin)
        assertEquals("Only the native form's task-owned device may be revoked", DEVICE, original.label)
        assertEquals("Installed pairing state must match this acceptance phase", paired, original.isPaired)
        return Live(app, original)
    }

    @Test fun actualNativeLinksRejectForeignMalformedAndOutsideMountTargetsWithoutChangingOwner() {
        val live = live(paired = true)
        val repository = live.app.container.repository
        val before = actualSnapshot(live)
        assertTrue("Controlled stock pane must be present before refusal checks", (before.agents + before.shellPanes).any { it.paneId == PANE && it.host == null && it.session == null })
        val priorScope = repository.selectedScope.value
        val priorSubject = repository.readContext()?.key?.subjectIdentity
        try {
            instrumentation.runOnMainSync { repository.selectSubject(Scope(), null) }
            ActivityScenario.launch<MainActivity>(Intent(live.app, MainActivity::class.java)).use { dashboard ->
                await(dashboard, "the normal native owning dashboard loads before rejection checks") { activity ->
                    val state = ViewModelProvider(activity)[MainViewModel::class.java].state.value
                    state.snapshot != null && state.savedCopy == null && activity.findViewById<View>(R.id.dashboard_panel).isShown
                }
                val committed = requireNotNull(live.app.container.connectionStore.captureCommitted())
                val priorPaneIds = paneIds(actualSnapshot(live))
                val links = listOf(
                    "https://example.invalid/completion/pane/$PANE?h=foreign&s=foreign",
                    "http://prod.lateapexllc.com/completion/pane/$PANE",
                    "https://prod.lateapexllc.com/outside-completion/pane/$PANE",
                    "https://unused@prod.lateapexllc.com/completion/pane/$PANE",
                    "https:///completion/pane/$PANE",
                )
                for (link in links) {
                    assertSame("Only the original installed pairing is used for each negative case",
                        live.original, live.app.container.connectionStore.connection.value)
                    assertEquals("The committed profile owner is unchanged before raw-link dispatch", committed,
                        live.app.container.connectionStore.captureCommitted())
                    assertEquals("The existing dashboard remains on the original global scope", Scope(), repository.selectedScope.value)
                    assertNull("Negative-link checks begin without a selected pane subject", repository.readContext()?.key?.subjectIdentity)
                    val intent = Intent(live.app, MainActivity::class.java)
                        .setAction(Intent.ACTION_VIEW)
                        .setData(Uri.parse(link))
                    val rawMainMonitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
                    // The raw-URI MainActivity intentionally sanitizes the intent and finishes.
                    // Start it through the normal existing task so the original dashboard can
                    // resume; ActivityScenario.launch(rawIntent) incorrectly requires the
                    // deliberately finishing Activity to reach a started state.
                    try {
                        dashboard.onActivity { activity -> activity.startActivity(intent) }
                        val rejected = monitorReturnedMain(rawMainMonitor)
                        await(dashboard, "invalid raw URI finishes without replacing the owning dashboard") { activity ->
                            val vm = ViewModelProvider(activity)[MainViewModel::class.java]
                            vm.currentScope == Scope() && vm.state.value.snapshot != null && vm.state.value.savedCopy == null &&
                                activity.findViewById<View>(R.id.dashboard_panel).isShown &&
                                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                                    .singleOrNull() === activity
                        }
                        instrumentation.runOnMainSync {
                            assertTrue("The rejected raw MainActivity finished after sanitizing its URI", rejected.isFinishing)
                        }
                        dashboard.onActivity { activity ->
                            assertNotSame("The original owner dashboard resumes after the separate raw MainActivity finishes",
                                activity, rejected)
                            assertEquals("A refused native link cannot redirect host/session selection", Scope(), repository.selectedScope.value)
                            assertNull("A refused native link cannot select another pane subject", repository.readContext()?.key?.subjectIdentity)
                            assertTrue("A refused native link cannot open a protected preview or leave another Activity resumed",
                                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).singleOrNull() === activity)
                        }
                        assertSame("Rejected links cannot replace installed credentials", live.original, live.app.container.connectionStore.connection.value)
                        assertEquals("Rejected links cannot create/close panes", priorPaneIds, paneIds(actualSnapshot(live)))
                        val after = requireNotNull(live.app.container.connectionStore.captureCommitted())
                        assertEquals("Rejected links cannot replace the committed profile owner", committed, after)
                    } finally {
                        instrumentation.removeMonitor(rawMainMonitor)
                    }
                }
            }
        } finally { instrumentation.runOnMainSync { repository.selectSubject(priorScope, priorSubject) } }
    }

    @Test fun holdForNormalParentRevocationThenNativeReadRefusalRetiresOnlyOwnedCredential() {
        val live = live(paired = true)
        assertEquals("Parent must explicitly arrange the normal owned-device revocation", "true", args.getString("admissionWaitForParent"))
        val repository = live.app.container.repository
        val devices = runBlocking { live.app.container.api.devices(live.original) }
        assertTrue("Real paired device list must succeed", devices is ApiResult.Success)
        val listed = (devices as ApiResult.Success).value
        assertTrue("Pairing must be enforced for this real device", listed.enforced && !listed.currentExpired)
        assertEquals(DEVICE, listed.current)
        assertEquals("Exactly the native owned device is current", listOf(DEVICE), listed.devices.filter { it.current && !it.expired }.map { it.label })
        val committed = requireNotNull(live.app.container.connectionStore.captureCommitted())
        val priorIds = live.app.container.launchIntentStore.all().map { it.intent.requestId }.toSet()
        ActivityScenario.launch<PaneActivity>(paneIntent(live)).use { scenario ->
            await(scenario, "original live stock pane is admitted before revocation") { activity ->
                val state = ViewModelProvider(activity)[PaneViewModel::class.java].state.value
                state.pane?.paneId == PANE && state.savedCopy == null && state.canWrite &&
                    state.paneReadContext?.let { it.connection === live.original && repository.isCurrent(it) } == true &&
                    activity.findViewById<EditText>(R.id.reply_input).isEnabled
            }
            instrumentation.sendStatus(2, Bundle().apply {
                putString("admissionDeviceLabel", DEVICE)
                putString("admissionIncarnation", committed.incarnationId)
                putString("admissionPriorPid", Process.myPid().toString())
                putString("stream", "ADMISSION READY: normal CLI may revoke ONLY the public task-owned label.\n")
            })
            var refusal: ApiFailure.Http? = null
            val until = SystemClock.elapsedRealtime() + 120_000
            while (SystemClock.elapsedRealtime() < until && refusal == null) {
                // A genuine read using the originally captured credential. It never goes into a
                // new store or grants authority; the native pane's own poll/Refresh must retire it.
                val read = runBlocking { live.app.container.api.snapshot(live.original, Scope()) }
                val failure = (read as? ApiResult.Failure)?.error as? ApiFailure.Http
                if (failure?.status == 403 && failure.retiresPairing()) refusal = failure
                else if (read is ApiResult.Failure) fail("Expected live reads until exact server pairing403, got ${read.error.javaClass.simpleName}")
                if (refusal == null) SystemClock.sleep(500)
            }
            assertNotNull("Normal parent CLI revocation must produce an actual pairing403", refusal)
            // Refresh is a shipped read action. No terminal text, Send, keys or launch is attempted.
            onView(withId(R.id.refresh_button)).perform(click())
            onView(withText(R.string.pane_refresh)).perform(click())
            await(scenario, "native refusal closes all original pane write authority") { activity ->
                val state = ViewModelProvider(activity)[PaneViewModel::class.java].state.value
                !state.canWrite && !repository.writesAllowed(Scope(), PANE) &&
                    !activity.findViewById<EditText>(R.id.reply_input).isEnabled &&
                    !activity.findViewById<View>(R.id.send_button).isEnabled &&
                    live.app.container.connectionStore.connection.value?.isPaired == false
            }
            scenario.onActivity { activity ->
                val state = ViewModelProvider(activity)[PaneViewModel::class.java].state.value
                assertFalse("A cached or retained pane cannot grant writes after revocation", state.canWrite)
                assertTrue("Original live pane actor must be retired", state.paneReadContext?.let(repository::isCurrent) != true)
            }
            assertUnpairedProfile(live)
            assertTrue("Read/refusal/navigation must never reserve a new mutation; retirement may erase owned receipts",
                live.app.container.launchIntentStore.all().all { it.intent.requestId in priorIds })
        }
    }

    @Test fun actualProcessRestartKeepsRetiredProfileAndRequiresNativePairingRepair() {
        val live = live(paired = false)
        val priorPid = requireNotNull(args.getString("admissionPriorPid")?.toIntOrNull())
        assertNotEquals("Parent must actually restart the isolated app process", priorPid, Process.myPid())
        assertUnpairedProfile(live)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            await(scenario, "fresh native dashboard requires explicit pairing repair") { activity ->
                val state = ViewModelProvider(activity)[MainViewModel::class.java].state.value
                !state.paired && !state.writeAuthorized && !live.app.container.repository.writesAllowed() &&
                    activity.findViewById<View>(R.id.setup_panel).isShown &&
                    activity.findViewById<EditText>(R.id.pairing_code_input).isShown
            }
        }
        assertUnpairedProfile(live)
    }

    private fun assertUnpairedProfile(live: Live) {
        val current = requireNotNull(live.app.container.connectionStore.connection.value)
        assertEquals("Retirement preserves the original public origin", live.original.origin, current.origin)
        assertEquals("Retirement preserves the original public profile label", DEVICE, current.label)
        assertFalse("Retired credentials cannot remain paired or gain new authority", current.isPaired)
        assertFalse(live.app.container.repository.writesAllowed(Scope(), PANE))
    }
    private fun actualSnapshot(live: Live): SnapshotResponse {
        val result = runBlocking { live.app.container.api.snapshot(live.original, Scope()) }
        assertTrue("Real current stock snapshot must succeed", result is ApiResult.Success)
        return (result as ApiResult.Success).value
    }
    private fun paneIds(snapshot: SnapshotResponse) = (snapshot.agents + snapshot.shellPanes).map { it.paneId }.toSet()
    private fun monitorReturnedMain(monitor: android.app.Instrumentation.ActivityMonitor): MainActivity {
        val activity = monitor.waitForActivityWithTimeout(10_000)
        assertNotNull("The raw URI must reach the actual native MainActivity boundary", activity)
        val launched = requireNotNull(activity)
        assertTrue("The actual raw URI activity is MainActivity", launched is MainActivity)
        return launched as MainActivity
    }
    private fun paneIntent(live: Live) = Intent(live.app, PaneActivity::class.java)
        .putExtra(PaneActivity.EXTRA_PANE_ID, PANE).putExtra(PaneActivity.EXTRA_AGENT, "shell")
        .putExtra(PaneActivity.EXTRA_TITLE, "Disposable native admission acceptance")
    private fun <T : android.app.Activity> await(scenario: ActivityScenario<T>, checkpoint: String, ready: (T) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 45_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var matched = false
            scenario.onActivity { matched = ready(it) }
            if (matched) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Physical admission checkpoint timed out: $checkpoint")
    }
    companion object {
        const val ORIGIN = "https://prod.lateapexllc.com/completion"
        const val PANE = "w1:p1"
        const val DEVICE = "completion Pixel"
    }
}
