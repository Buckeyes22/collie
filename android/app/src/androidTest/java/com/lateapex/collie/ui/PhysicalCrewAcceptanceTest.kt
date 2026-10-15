package com.lateapex.collie.ui

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lateapex.collie.BuildConfig
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.CrewStatusResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Isolated real Crew/standby/native confirmation acceptance. No API mutations or guard seeding. */
@RunWith(AndroidJUnit4::class)
class PhysicalCrewAcceptanceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private data class Live(val app: CollieApplication, val crewId: String, val leadId: String, val deputyId: String)

    private fun required(name: String): String = args.getString(name)?.takeIf(String::isNotBlank)
        ?: error("Supplied liveOrigin requires explicit $name")
    private fun identifier(name: String): String = required(name).also {
        assertTrue("$name must be a bounded explicit fixture identifier", it.matches(Regex("[A-Za-z0-9_.:-]{1,128}")))
    }

    private fun withLive(test: (Live) -> Unit) {
        val supplied = args.getString("liveOrigin")
        assumeTrue("No liveOrigin supplied; physical Crew acceptance is opt-in", supplied != null)
        assertFalse("Supplied origin must not be blank", supplied.isNullOrBlank())
        assertTrue("Crew acceptance requires the isolated app", BuildConfig.APPLICATION_ID.endsWith(".completiontest"))
        val app = ApplicationProvider.getApplicationContext<Context>().applicationContext as CollieApplication
        val repository = app.container.repository
        val origin = app.container.originValidator.requireValid(requireNotNull(supplied))
        assertEquals("Only the task-owned HTTPS Crew mount is allowed", app.container.originValidator.requireValid(ORIGIN), origin)
        val connection = app.container.connectionStore.connection.value
        assertTrue("Use and preserve the installed pairing", connection?.isPaired == true)
        assertEquals("Pairing must match the exact configured HTTPS origin/mount", origin, connection?.origin)
        val live = Live(app, identifier("liveCrewId"), identifier("liveLeadId"), identifier("liveDeputyId"))
        assertNotEquals(live.leadId, live.deputyId)
        val previousScope = repository.selectedScope.value
        val previousSubject = repository.readContext()?.key?.subjectIdentity
        try {
            instrumentation.runOnMainSync { repository.selectSubject(Scope(), null) }
            assertNotNull("Existing profile must admit a current unscoped actor", repository.readContext(Scope(), null))
            test(live)
        } finally {
            assertSame("Crew recovery must not replace the original paired connection", connection, app.container.connectionStore.connection.value)
            instrumentation.runOnMainSync { repository.selectSubject(previousScope, previousSubject) }
        }
    }

    @Test fun nativePackReadsAndRefreshesActualStandbyState() = withLive { live ->
        val expected = required("expectedState")
        assertTrue("Only explicit cold or armed standby states are supported", expected in setOf("cold", "armed"))
        val connection = requireNotNull(live.app.container.connectionStore.connection.value)
        val guard = DeputyTakeoverGuard(live.app)
        assertFalse("A previous takeover must be resolved before standby acceptance", guard.pending(connection))
        assertHealth(live, expected)
        val coldCrew = if (expected == "cold") crew(live).also { assertCrew(live, it, live.leadId) } else null
        ActivityScenario.launch(PackActivity::class.java).use { scenario ->
            waitFor("Pack must render the actual $expected standby state") { stateShown(scenario, live, expected) }
            if (coldCrew != null) assertFormation(scenario, live, coldCrew)
            onView(withText(R.string.pack_takeover_refresh)).perform(scrollTo(), click())
            waitFor("Normal Refresh must retain verified $expected state") { stateShown(scenario, live, expected) }
            assertHealth(live, expected)
            scenario.onActivity {
                assertEquals("Takeover is enabled only for a verified armed standby", expected == "armed", it.findViewById<android.view.View>(R.id.pack_takeover_button).isEnabled)
            }
            assertFalse("Read/Refresh must never reserve or send takeover", guard.pending(connection))
        }
    }

    /** Parent stops only its isolated lead and waits for the real signed-warrant standby to arm. */
    @Test fun nativeConfirmedTakeoverRecordsCustodyWithoutAssumingRestart() = withLive { live ->
        assertEquals("Takeover requires explicit physical acceptance intent", "true", required("liveConfirmTakeover"))
        assertEquals("Takeover selector requires an explicitly armed fixture", "armed", required("expectedState"))
        val connection = requireNotNull(live.app.container.connectionStore.connection.value)
        val guard = DeputyTakeoverGuard(live.app)
        assertFalse("Do not repeat an existing uncertain takeover", guard.pending(connection))
        assertHealth(live, "armed")
        ActivityScenario.launch(PackActivity::class.java).use { scenario ->
            waitFor("Actual armed standby must enable native Take over") {
                var enabled = false
                scenario.onActivity { enabled = it.findViewById<android.view.View>(R.id.pack_takeover_button).isEnabled }
                enabled && stateShown(scenario, live, "armed")
            }
            onView(withId(R.id.pack_takeover_button)).perform(scrollTo(), click())
            onView(withText(R.string.pack_takeover_confirm_detail)).check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()))
            onView(withId(android.R.id.button1)).perform(click()) // One real native confirmation.
            var attemptId: String? = null
            var disposition: String? = null
            waitFor("Confirmed takeover must retain uncertainty or verify the actual serving new lead") {
                val attempt = guard.currentAttempt(connection)
                if (attempt != null) {
                    assertEquals(attempt.id, UUID.fromString(attempt.id).toString())
                    assertTrue("Any captured deputy must be this fixture's exact deputy", attempt.expectedDeputyId == null || attempt.expectedDeputyId == live.deputyId)
                    attemptId = attempt.id
                    if (status(scenario) == live.app.getString(R.string.pack_takeover_pending)) disposition = "pending"
                } else if (stateShown(scenario, live, "leading")) {
                    val result = crewResult(live)
                    if (result is ApiResult.Success && result.value.self.id == live.deputyId) {
                        assertCrew(live, result.value, live.deputyId)
                        assertHealth(live, "leading")
                        disposition = "verified-serving-new-lead"
                    }
                }
                disposition != null
            }
            scenario.onActivity { assertFalse("Never offer a second takeover after this confirmation", it.findViewById<android.view.View>(R.id.pack_takeover_button).isEnabled) }
            if (disposition == "pending") {
                assertEquals("Persisted custody must survive an independent guard read", attemptId, DeputyTakeoverGuard(live.app).currentAttempt(connection)?.id)
                assertTrue(guard.pending(connection))
            }
            instrumentation.sendStatus(2, Bundle().apply {
                putString("crewTakeoverDisposition", disposition)
                attemptId?.let { putString("liveCrewAttemptId", it) }
                putString("stream", "Native takeover confirmed once; pending does not prove the promoted lead has restarted.\n")
            })
        }
    }

    /** Run after the parent actually supervises the promoted deputy's normal restart/ingress. */
    @Test fun verifyServingNewLeadAfterParentRestartThroughNormalRefresh() = withLive { live ->
        assertEquals("Restart verification is a separate explicit phase", "true", required("liveAfterParentRestart"))
        val connection = requireNotNull(live.app.container.connectionStore.connection.value)
        val guard = DeputyTakeoverGuard(live.app)
        val previous = guard.currentAttempt(connection)
        args.getString("liveCrewAttemptId")?.let {
            assertEquals(it, UUID.fromString(it).toString())
            assertEquals("Retain the exact earlier pending attempt until verified Crew observation", it, previous?.id)
        }
        assertHealth(live, "leading")
        val currentCrew = crew(live)
        assertCrew(live, currentCrew, live.deputyId)
        assertTrue("A real authenticated snapshot must succeed after restart", snapshotSucceeds(live))
        ActivityScenario.launch(PackActivity::class.java).use { scenario ->
            onView(withText(R.string.pack_takeover_refresh)).perform(scrollTo(), click())
            waitFor("Pack must render the real restarted Crew formation") {
                var visible = false
                scenario.onActivity {
                    val expected = live.app.getString(R.string.pack_formation_summary,
                        currentCrew.crew.name.ifBlank { currentCrew.crew.id },
                        live.app.resources.getQuantityString(R.plurals.pack_machines, currentCrew.members.size, currentCrew.members.size),
                        currentCrew.members.count { member -> member.health == "reachable" })
                    visible = it.findViewById<android.view.View>(R.id.pack_formation).isShown &&
                        it.findViewById<TextView>(R.id.pack_summary).text.toString() == expected
                }
                visible
            }
            assertFormation(scenario, live, currentCrew)
            waitFor("Normal Pack reads must expose a serving lead or preserved takeover custody") {
                stateShown(scenario, live, "leading") || status(scenario) == live.app.getString(R.string.pack_takeover_pending)
            }
            if (guard.pending(connection)) {
                assertEquals("Resolving a nameless uncertain attempt requires explicit native review", "true", required("liveConfirmRecoveredLead"))
                onView(withId(R.id.pack_takeover_review_button)).perform(scrollTo(), click())
                onView(withId(android.R.id.button1)).perform(click()) // Reviews real Crew; no takeover POST.
            }
            waitFor("Current paired Crew/leading checks must resolve earlier custody") {
                !guard.pending(connection) && stateShown(scenario, live, "leading")
            }
            assertHealth(live, "leading")
            assertCrew(live, crew(live), live.deputyId)
            assertTrue(snapshotSucceeds(live))
            scenario.onActivity { assertFalse(it.findViewById<android.view.View>(R.id.pack_takeover_button).isEnabled) }
        }
    }

    private fun assertHealth(live: Live, expected: String) {
        val repository = live.app.container.repository
        val actor = requireNotNull(repository.readContext(Scope(), null))
        val result = runBlocking { repository.standbyHealth(actor) }
        assertTrue("Read actual HTTPS standby health", result is ApiResult.Success)
        assertTrue(repository.isCurrent(actor))
        val success = result as ApiResult.Success
        assertEquals(expected, success.value.state)
        assertEquals("Cold is the real 503 contract; armed/leading use 200", if (expected == "cold") 503 else 200, success.status)
        assertTrue(success.value.valid(success.status))
    }
    private fun crewResult(live: Live): ApiResult<CrewStatusResponse> {
        val repository = live.app.container.repository
        val actor = requireNotNull(repository.readContext(Scope(), null))
        val result = runBlocking { repository.crew(actor) }
        assertTrue(repository.isCurrent(actor))
        return result
    }
    private fun crew(live: Live): CrewStatusResponse {
        val result = crewResult(live)
        assertTrue("Authenticated published Crew API must actually serve", result is ApiResult.Success)
        return (result as ApiResult.Success).value
    }
    private fun assertCrew(live: Live, value: CrewStatusResponse, servingLead: String) {
        assertEquals(live.crewId, value.crew.id)
        assertEquals("Same HTTPS address must now serve the exact expected lead", servingLead, value.self.id)
        assertEquals(servingLead, value.members.singleOrNull { it.isLead }?.id)
        assertEquals("The real isolated roster must retain both original members", setOf(live.leadId, live.deputyId), value.members.map { it.id }.toSet())
        assertEquals(2, value.members.size)
        if (servingLead == live.leadId) value.deputy?.let { assertEquals(live.deputyId, it.id) }
    }
    private fun snapshotSucceeds(live: Live): Boolean {
        val repository = live.app.container.repository
        val actor = requireNotNull(repository.readContext(Scope(), null))
        val result = runBlocking { repository.snapshot(Scope(), actor) }
        assertTrue(repository.isCurrent(actor))
        return result is ApiResult.Success
    }
    private fun assertFormation(scenario: ActivityScenario<PackActivity>, live: Live, value: CrewStatusResponse) {
        scenario.onActivity {
            assertTrue("Native formation must be visible", it.findViewById<android.view.View>(R.id.pack_formation).isShown)
            val expected = live.app.getString(R.string.pack_formation_summary, value.crew.name.ifBlank { value.crew.id },
                live.app.resources.getQuantityString(R.plurals.pack_machines, value.members.size, value.members.size), value.members.count { member -> member.health == "reachable" })
            assertEquals("Native summary must describe the actual Crew response", expected, it.findViewById<TextView>(R.id.pack_summary).text.toString())
        }
    }
    private fun status(scenario: ActivityScenario<PackActivity>): String {
        var text = ""
        scenario.onActivity { text = it.findViewById<TextView>(R.id.pack_takeover_status).text.toString() }
        return text
    }
    private fun stateShown(scenario: ActivityScenario<PackActivity>, live: Live, expected: String): Boolean = when (expected) {
        "cold" -> status(scenario) == live.app.getString(R.string.pack_takeover_cold)
        "leading" -> status(scenario) == live.app.getString(R.string.pack_takeover_leading)
        "armed" -> status(scenario).startsWith("The deputy is armed after ")
        else -> false
    }
    private fun waitFor(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 45_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(100)
        }
        fail(message)
    }
    companion object { const val ORIGIN = "https://prod.lateapexllc.com/completion-crew" }
}
