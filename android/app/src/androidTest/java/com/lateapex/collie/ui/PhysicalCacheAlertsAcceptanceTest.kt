package com.lateapex.collie.ui

import android.app.Activity
import android.app.UiAutomation
import android.content.Context
import android.os.SystemClock
import android.graphics.Rect
import android.text.format.DateFormat
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.CompoundButton
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.lifecycle.ViewModelProvider
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.data.ScopedReadContext
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.CacheWatchResponse
import com.lateapex.collie.network.MachineAlertRule
import com.lateapex.collie.network.MachineAlerts
import com.lateapex.collie.network.MachineRow
import com.lateapex.collie.network.OriginValidator
import com.lateapex.collie.network.UpdateCheckResponse
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matchers.startsWith
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real paired native controls only. Parent prepares real telemetry and owns event timing. */
@RunWith(AndroidJUnit4::class)
class PhysicalCacheAlertsAcceptanceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val args get() = InstrumentationRegistry.getArguments()
    private data class Live(val app: CollieApplication, val paired: Connection)

    private fun withLive(test: (Live) -> Unit) {
        val origin = args.getString("liveOrigin")
        assumeTrue("liveOrigin absent; physical acceptance is opt-in", origin != null)
        assertFalse("Explicit liveOrigin must not be blank", origin.isNullOrBlank())
        val app = ApplicationProvider.getApplicationContext<Context>().applicationContext as CollieApplication
        assertTrue("Only the isolated completion package is permitted", app.packageName.endsWith(".completiontest"))
        val paired = app.container.connectionStore.connection.value
        assertTrue("Explicit liveOrigin requires an existing pairing", paired?.isPaired == true)
        assertEquals("Exact configured HTTPS origin and mount must match", OriginValidator().requireValid(requireNotNull(origin)), paired?.origin)
        assertTrue("Actual stock acceptance requires trusted HTTPS", paired?.origin?.value?.startsWith("https://") == true)
        val live = Live(app, requireNotNull(paired))
        try { test(live) }
        finally { assertTrue("Physical controls must preserve the existing pairing", app.container.connectionStore.connection.value === paired) }
    }

    private fun required(name: String): String = requireNotNull(args.getString(name)?.takeIf(String::isNotBlank)) {
        "Explicit $name is required with liveOrigin"
    }

    private fun withPane(live: Live, test: (PaneAddress, ScopedReadContext) -> Unit) {
        val pane = required("livePaneId")
        assertTrue("Pane identifier must be bounded", pane.matches(Regex("[A-Za-z0-9:._-]{1,128}")))
        val address = PaneAddress(Scope(args.getString("liveHost")?.takeIf(String::isNotBlank), args.getString("liveSession")?.takeIf(String::isNotBlank)), pane)
        val repository = live.app.container.repository
        val priorScope = repository.selectedScope.value
        val priorSubject = repository.readContext()?.key?.subjectIdentity
        try {
            instrumentation.runOnMainSync { repository.selectSubject(address.scope, address.paneId) }
            val actor = requireNotNull(repository.readContext(address.scope, address.paneId)) { "The exact pane actor must be admitted" }
            current(live, actor)
            test(address, actor)
        } finally { instrumentation.runOnMainSync { repository.selectSubject(priorScope, priorSubject) } }
    }

    /** cacheWatching=true adds an eligible watch; false removes it. Run the opposite to restore. */
    @Test fun liveCacheWatchPersistsRequestedNativeToggle() = withLive { live -> withPane(live) { address, actor ->
        val requested = when (required("cacheWatching")) { "true" -> true; "false" -> false; else -> throw AssertionError("cacheWatching must be true|false") }
        val before = watch(live, address, actor)
        assertFalse("Global cache alerts must be off to prove a per-pane watch", before.global)
        assertTrue("Requested native toggle must change the real watch state", before.on != requested)
        if (requested) assertTrue("A real eligible cache reading must already exist", before.watchable)
        ActivityScenario.launch<CacheWatchActivity>(CacheWatchActivity.intent(live.app, address)).use { scenario ->
            await(scenario, "native watch admission") { switch(it)?.let { button -> button.isEnabled && button.isChecked == before.on } == true }
            current(live, actor)
            onView(withText(live.app.getString(R.string.cache_watch_label))).perform(scrollTo(), click())
            await(scenario, "native watch acknowledgement") { switch(it)?.let { button -> button.isEnabled && button.isChecked == requested } == true }
            assertEquals("Only the requested lead-owned watch state may change", before.copy(on = requested), watch(live, address, actor))
        }
    } }

    /** Requires an actual warm/expiring cache reading, source catalog and model on the live pane. */
    @Test fun liveCacheContextShowsRealSourceModelAndDecreasingCountdown() = withLive { live -> withPane(live) { address, actor ->
        current(live, actor)
        val repository = live.app.container.repository
        val result = runBlocking { repository.paneSnapshot(address.scope, actor) }
        current(live, actor)
        assertTrue("Actual pane snapshot must be readable", result is ApiResult.Success)
        val snapshot = (result as ApiResult.Success).value
        val pane = (snapshot.agents + snapshot.shellPanes).singleOrNull { it.paneId == address.paneId && it.host == address.scope.host && it.session == address.scope.session }
        assertNotNull("Exact pane must be live", pane)
        val cache = requireNotNull(pane!!.cache) { "Real harness cache telemetry is required; no synthetic report is substituted" }
        val timing = requireNotNull(CacheContextPresenter.timing(cache, snapshot.ts))
        assertTrue("Real telemetry must have a live countdown with second precision", timing.state in setOf("warm", "expiring") && (timing.remainingSeconds ?: 0) in 11..3599)
        assertTrue("Real model and rule identity are required", !pane.model.isNullOrBlank() && !cache.ruleId.isNullOrBlank())
        assertNull("This selector verifies the connected lead's own source catalog", address.scope.host)
        val catalog = runBlocking { repository.cacheRules(address.scope, address.paneId, expectedContext = actor) }
        current(live, actor)
        assertTrue("Actual lead catalog must be readable", catalog is ApiResult.Success)
        val rule = (catalog as ApiResult.Success).value.rules.singleOrNull { it.id == cache.ruleId }
        assertNotNull("Real pane rule must be present in the source catalog", rule)
        ActivityScenario.launch<CacheWatchActivity>(CacheWatchActivity.intent(live.app, address)).use { scenario ->
            await(scenario, "actual source and model context") { activity ->
                val texts = texts(activity)
                texts.any { it == activity.getString(R.string.cache_model_value, pane.model) } &&
                    texts.any { rule!!.label in it && (if (cache.overridden == true) rule.overridden?.sourceUrl ?: "__missing_override__" else rule.sourceUrl) in it } && countdown(activity) != null
            }
            var first = 0L
            scenario.onActivity { first = requireNotNull(countdown(it)) }
            assertTrue("Visible countdown must be bounded by the real TTL", first in 1..(requireNotNull(timing.ttlSeconds) + 300))
            SystemClock.sleep(3_100)
            scenario.onActivity { activity ->
                val next = requireNotNull(countdown(activity))
                assertTrue("Actual native monotonic countdown must decrease without a new request", first - next in 2..8)
                assertTrue("Actual confidence must be visible", texts(activity).any { text -> text.contains(activity.getString(when (cache.confidence) {
                    "observed" -> R.string.cache_confidence_observed; "reported" -> R.string.cache_confidence_reported; "documented" -> R.string.cache_confidence_documented; else -> R.string.cache_confidence_unknown
                })) })
            }
            current(live, actor)
        }
    } }

    /** Explicit own-machine consent; create CPU99%/120min, change98%/120min, remove own rule. */
    @Test fun liveMachineAlertRulePersistsRequestedNativeAction() = withLive { live ->
        assertEquals("Only a disposable acceptance machine may be edited", "true", required("liveMachineDisposable"))
        val machineId = required("liveMachineId")
        val action = required("machineRuleAction")
        val repository = live.app.container.repository
        ActivityScenario.launch(MainActivity::class.java).use { dashboard ->
            await(dashboard, "actual live global dashboard write admission") { activity ->
                val state = ViewModelProvider(activity)[MainViewModel::class.java].state.value
                val source = repository.readSource.value as? ReadSourceState.Live
                state.snapshot != null && state.savedCopy == null && state.writeAuthorized &&
                    repository.selectedScope.value == Scope() && source?.key?.host == null &&
                    source?.key?.session == null && source?.key?.subjectIdentity == null &&
                    source != null && repository.writesAllowed(Scope(), null) &&
                    activity.findViewById<View>(R.id.dashboard_machines_button).let { it.isShown && it.isEnabled }
            }
            val actor = requireNotNull(repository.machineReadContext())
            val before = machine(live, actor, machineId)
            assertNotNull("Official server must expose rule revision and incarnation", before.rulesRevision)
            assertNotNull("Official server must expose rule revision and incarnation", before.machineIncarnation)
            val created = MachineAlerts(cpu = MachineAlertRule(0.99, 120))
            val changed = MachineAlerts(cpu = MachineAlertRule(0.98, 120))
            val expected = when (action) {
                "create" -> { assertEquals("Never overwrite pre-existing operator rules", MachineAlerts(), before.alerts); created }
                "change" -> { assertEquals("Only the exact helper-created harmless rule may change", created, before.alerts); changed }
                "remove" -> { assertTrue("Only the helper's exact harmless rule may be removed", before.alerts == created || before.alerts == changed); MachineAlerts() }
                else -> throw AssertionError("machineRuleAction must be create|change|remove")
            }
            current(live, actor)
            onView(withId(R.id.dashboard_machines_button)).perform(click())
            var machinesActivity: MachinesActivity? = null
            try {
                machinesActivity = awaitMachines("actual dashboard Machines navigation") { true }
                current(live, actor)
                assertTrue("Normal navigation must preserve the dashboard's live global write admission",
                    repository.readSource.value is ReadSourceState.Live && repository.writesAllowed(Scope(), null))
                val editDescription = live.app.getString(R.string.machines_edit_alert_rules_accessibility, before.name)
                awaitMachines("actual machine census") { activity -> activity === machinesActivity && descendants(activity.window.decorView).filterIsInstance<TextView>().any { it.isShown && it.text.startsWith(before.name + "\n") } }
                onView(withText(startsWith(before.name + "\n"))).perform(scrollTo(), click())
                awaitMachines("official native rule editor admission") { activity -> activity === machinesActivity && descendants(activity.window.decorView).any { it.contentDescription == editDescription && it.isEnabled } }
                current(live, actor)
                onView(withContentDescription(editDescription)).perform(scrollTo(), click())
                val cpu = live.app.getString(R.string.machines_metric_cpu)
                awaitRuleDialog(live, actor, requireNotNull(machinesActivity), before)
                if (action != "change") onView(withText(live.app.getString(R.string.machines_enable_metric_rule, cpu))).inRoot(isDialog()).perform(scrollTo(), click())
                if (action != "remove") {
                    onView(withContentDescription(live.app.getString(R.string.machines_threshold_accessibility, cpu))).inRoot(isDialog()).perform(scrollTo(), replaceText(if (action == "create") "99" else "98"))
                    onView(withContentDescription(live.app.getString(R.string.machines_duration_accessibility, cpu))).inRoot(isDialog()).perform(scrollTo(), replaceText("120"))
                }
                current(live, actor)
                onView(withText(live.app.getString(R.string.machines_save_rules))).inRoot(isDialog()).perform(click())
                awaitMachines("native one-shot rule acknowledgement") { activity -> activity === machinesActivity && texts(activity).any { it == activity.getString(R.string.machines_rules_saved) } }
                val after = machine(live, actor, machineId)
                assertEquals("Actual lead must persist the complete requested rules", expected, after.alerts)
                assertEquals("Rule edit must retain the original machine incarnation", before.machineIncarnation, after.machineIncarnation)
                assertEquals("Unknown extension fields must survive the native replacement", before.extensions, after.extensions)
                assertEquals("One native save must increment the server revision exactly once", before.rulesRevision!!.toULong() + 1uL, after.rulesRevision!!.toULong())
            } finally { instrumentation.runOnMainSync { machinesActivity?.finish() } }
        }
    }

    /** Explicit Crew-only real memory rule; the parent owns alert delivery and timing. */
    @Test fun liveCrewMemoryAlertRuleCreateThroughNativeUi() = crewMemoryRule(remove = false)

    /** Separate normal native cleanup; never automatically remove a rule after failed evidence. */
    @Test fun liveCrewMemoryAlertRuleRemoveThroughNativeUi() = crewMemoryRule(remove = true)

    private fun crewMemoryRule(remove: Boolean) {
        assumeTrue("Explicit nativeCrewMemoryRule=true is required for this actual rule edit",
            args.getString("nativeCrewMemoryRule") == "true")
        withLive { live ->
            assertEquals("Only the isolated owned Crew mount is permitted",
                OriginValidator().requireValid("https://prod.lateapexllc.com/completion-crew/"), live.paired.origin)
            assertEquals("The exact owned Crew must be supplied", "DaMBiPa97dJy0W9W1aGAcg", required("liveCrewId"))
            assertEquals("Only the owned new lead may receive the memory rule", "qa-deputy", required("liveMachineId"))
            assertEquals("The operator must explicitly designate this disposable machine", "true", required("liveMachineDisposable"))
            assertTrue("Crew machine writes are global, never a remote/pane scope",
                args.getString("liveHost").isNullOrBlank() && args.getString("liveSession").isNullOrBlank())
            val expectedRevision = required("liveMachineRulesRevision")
            val expectedIncarnation = required("liveMachineIncarnation")
            val repository = live.app.container.repository
            val store = live.app.container.connectionStore
            val committed = requireNotNull(store.captureCommitted())
            assertTrue("A committed original pairing incarnation is required",
                committed.connection === live.paired && committed.incarnationId != null)
            val pushStore = com.lateapex.collie.notifications.NativePushStore(live.app)
            val enrollment = requireNotNull(pushStore.read()) { "The exact Crew profile must already be enrolled" }
            val credentialHash = java.security.MessageDigest.getInstance("SHA-256")
                .digest((live.paired.origin.value + "\u0000" + live.paired.token.orEmpty()).encodeToByteArray())
                .joinToString("") { "%02x".format(it) }
            fun enrolled() {
                val now = pushStore.read()
                assertTrue("The original Crew enrollment must stay active under its exact current credential incarnation",
                    now != null && !now.retired && now.instance == enrollment.instance &&
                        now.registrationId != null && now.registrationId == enrollment.registrationId &&
                        now.signingPublicKey != null && now.signingPublicKey == enrollment.signingPublicKey &&
                        now.origin == live.paired.origin.value && now.incarnation == committed.incarnationId &&
                        now.credentialHash == credentialHash)
            }
            enrolled()
            val wantedRule = MachineAlerts(mem = MachineAlertRule(0.50, 5))
            val expected = if (remove) MachineAlerts() else wantedRule
            val priorScope = repository.selectedScope.value
            val priorSubject = repository.readContext()?.key?.subjectIdentity
            try {
                ActivityScenario.launch(MainActivity::class.java).use { dashboard ->
                    await(dashboard, "actual Crew dashboard global live write admission") { activity ->
                        assertTrue("Dashboard cannot adopt another pair", store.connection.value === live.paired)
                        assertEquals("Dashboard cannot adopt another committed incarnation", committed, store.captureCommitted())
                        val state = ViewModelProvider(activity)[MainViewModel::class.java].state.value
                        val source = repository.readSource.value as? ReadSourceState.Live
                        state.snapshot != null && state.savedCopy == null && state.writeAuthorized &&
                            repository.selectedScope.value == Scope() && source != null && source.key.host == null &&
                            source.key.session == null && source.key.subjectIdentity == null &&
                            repository.writesAllowed(Scope(), null) &&
                            activity.findViewById<View>(R.id.dashboard_machines_button).let { it.isShown && it.isEnabled }
                    }
                    val actor = requireNotNull(repository.machineReadContext())
                    fun owned() {
                        current(live, actor)
                        assertEquals("Every action retains the original committed pairing", committed, store.captureCommitted())
                        assertTrue("Every action retains the original global machine actor",
                            actor.connection === live.paired && actor.committedSnapshot == committed &&
                                actor.key.host == null && actor.key.session == null && actor.key.subjectIdentity == null)
                        enrolled()
                    }
                    fun serverPreconditions(): com.lateapex.collie.network.NotifyPreferences {
                        owned()
                        val crewResult = runBlocking { repository.crew(actor) }
                        owned()
                        assertTrue("The actual authenticated Crew status must be readable", crewResult is ApiResult.Success)
                        val crew = (crewResult as ApiResult.Success).value
                        assertEquals("The authenticated server must remain the exact owned Crew", "DaMBiPa97dJy0W9W1aGAcg", crew.crew.id)
                        assertEquals("This server must be the actual new lead", "qa-deputy", crew.self.id)
                        assertEquals("The actual sole leader must be qa-deputy", listOf("qa-deputy"), crew.members.filter { it.isLead }.map { it.id })
                        val prefsResult = runBlocking { repository.notificationPreferences() }
                        owned()
                        assertTrue("The real native Machines alert preference must be readable", prefsResult is ApiResult.Success)
                        val prefs = (prefsResult as ApiResult.Success).value
                        assertTrue("Machines alerts must already be enabled; this helper never changes preferences", prefs.machines)
                        val snapshotResult = runBlocking { repository.snapshot(Scope(), actor) }
                        owned()
                        assertTrue("The actual original-actor snooze state must be readable", snapshotResult is ApiResult.Success)
                        val snapshot = (snapshotResult as ApiResult.Success).value
                        val notifications = requireNotNull(snapshot.notifications)
                        val snoozedUntil = notifications.snoozedUntil
                        assertTrue("The existing Crew profile must not be actively snoozed",
                            snoozedUntil == null || snoozedUntil <= snapshot.ts)
                        return prefs
                    }
                    val preferencesBefore = serverPreconditions()
                    val censusResult = runBlocking { repository.machines(actor) }
                    owned()
                    assertTrue("The actual complete machine census must be readable", censusResult is ApiResult.Success)
                    val censusBefore = (censusResult as ApiResult.Success).value.machines
                    val before = censusBefore.single { it.id == "qa-deputy" }
                    assertTrue("Only the real reachable lead is eligible", before.isLead && before.health == "reachable")
                    assertEquals("The supplied actual rule revision must still match", expectedRevision, before.rulesRevision)
                    assertEquals("The supplied actual machine incarnation must still match", expectedIncarnation, before.machineIncarnation)
                    assertEquals("Never overwrite or remove any other operator rule",
                        if (remove) wantedRule else MachineAlerts(), before.alerts)
                    if (!remove) {
                        val sample = requireNotNull(before.sample) { "The real lead must supply current memory telemetry" }
                        assertTrue("Actual telemetry must exceed the requested threshold; no load is injected",
                            sample.memTotal > 0 && sample.memUsed.toDouble() / sample.memTotal > 0.50)
                    }
                    fun nativeClick(matcher: org.hamcrest.Matcher<View>, activity: MachinesActivity? = null) {
                        val normalClick = click()
                        onView(matcher).perform(object : androidx.test.espresso.ViewAction {
                            override fun getDescription() = "One normal native Crew action under the original pairing and actor"
                            override fun getConstraints(): org.hamcrest.Matcher<View> = normalClick.constraints
                            override fun perform(controller: androidx.test.espresso.UiController, view: View) {
                                owned()
                                assertTrue("Native actions require current global write admission", repository.writesAllowed(Scope(), null))
                                if (activity != null) {
                                    val vm = ViewModelProvider(activity)[MachinesViewModel::class.java]
                                    val state = vm.state.value
                                    val shownActor = requireNotNull(state.actor)
                                    assertTrue("The native editor retains the captured actor witness",
                                        shownActor.key == actor.key && shownActor.connection === actor.connection &&
                                            shownActor.navigationGeneration == actor.navigationGeneration &&
                                            shownActor.committedSnapshot == actor.committedSnapshot && repository.isCurrent(shownActor))
                                    val row = requireNotNull(state.response?.machines?.singleOrNull { it.id == before.id })
                                    assertTrue("Native action cannot silently replace changed rules, incarnation or extensions",
                                        row.alerts == before.alerts && row.rulesRevision == before.rulesRevision &&
                                            row.machineIncarnation == before.machineIncarnation && row.extensions == before.extensions)
                                    assertTrue("The actual native editor must still admit this unchanged row", vm.canEditRules(row))
                                }
                                normalClick.perform(controller, view)
                            }
                        })
                    }
                    nativeClick(withId(R.id.dashboard_machines_button))
                    var machinesActivity: MachinesActivity? = null
                    try {
                        val activity = awaitMachines("the genuine owned Crew Machines census") { activity ->
                            texts(activity).any { it.startsWith(before.name + "\n") }
                        }
                        machinesActivity = activity
                        onView(withText(startsWith(before.name + "\n"))).perform(scrollTo())
                        nativeClick(withText(startsWith(before.name + "\n")), activity)
                        val edit = live.app.getString(R.string.machines_edit_alert_rules_accessibility, before.name)
                        awaitMachines("actual Crew memory rule editor admission") { it === activity && descendants(it.window.decorView).any { view -> view.contentDescription == edit && view.isEnabled } }
                        onView(withContentDescription(edit)).perform(scrollTo())
                        nativeClick(withContentDescription(edit), activity)
                        awaitRuleDialog(live, actor, activity, before, R.string.machines_metric_memory)
                        val memory = live.app.getString(R.string.machines_metric_memory)
                        onView(withText(live.app.getString(R.string.machines_enable_metric_rule, memory))).inRoot(isDialog()).perform(scrollTo(), click())
                        if (!remove) {
                            onView(withContentDescription(live.app.getString(R.string.machines_threshold_accessibility, memory))).inRoot(isDialog()).perform(scrollTo(), replaceText("50"))
                            onView(withContentDescription(live.app.getString(R.string.machines_duration_accessibility, memory))).inRoot(isDialog()).perform(scrollTo(), replaceText("5"))
                        }
                        assertEquals("Immediately before Save all real notification preferences remain unchanged", preferencesBefore, serverPreconditions())
                        val save = click()
                        onView(withText(live.app.getString(R.string.machines_save_rules))).inRoot(isDialog()).perform(object : androidx.test.espresso.ViewAction {
                            override fun getDescription() = "Save the exact reviewed memory rule once under original live custody"
                            override fun getConstraints(): org.hamcrest.Matcher<View> = save.constraints
                            override fun perform(controller: androidx.test.espresso.UiController, view: View) {
                                owned()
                                val vm = ViewModelProvider(activity)[MachinesViewModel::class.java]
                                val state = vm.state.value
                                val row = requireNotNull(state.response?.machines?.singleOrNull { it.id == before.id })
                                assertTrue("The final Save requires the original current actor and unchanged whole-rule witness",
                                    state.actor?.key == actor.key && state.actor?.navigationGeneration == actor.navigationGeneration &&
                                        state.actor?.committedSnapshot == committed && state.selectedMachineId == before.id &&
                                        row.alerts == before.alerts && row.rulesRevision == before.rulesRevision &&
                                        row.machineIncarnation == before.machineIncarnation && row.extensions == before.extensions &&
                                        vm.canEditRules(row) && repository.writesAllowed(Scope(), null))
                                val dialogViews = descendants(view.rootView)
                                for (metric in listOf(R.string.machines_metric_cpu, R.string.machines_metric_memory, R.string.machines_metric_disk)) {
                                    val label = live.app.getString(R.string.machines_enable_metric_rule, live.app.getString(metric))
                                    val checkbox = dialogViews.filterIsInstance<CompoundButton>().single { it.text.toString() == label }
                                    assertEquals("Only the exact reviewed memory rule may be enabled in the real form",
                                        !remove && metric == R.string.machines_metric_memory, checkbox.isChecked)
                                }
                                if (!remove) {
                                    fun field(resource: Int): String = dialogViews.filterIsInstance<android.widget.EditText>()
                                        .single { it.contentDescription?.toString() == live.app.getString(resource, memory) }.text.toString()
                                    assertEquals("The actual memory threshold is exactly 50 percent", "50", field(R.string.machines_threshold_accessibility))
                                    assertEquals("The actual memory duration is exactly five minutes", "5", field(R.string.machines_duration_accessibility))
                                }
                                save.perform(controller, view)
                            }
                        })
                        awaitMachines("the sole real memory-rule save acknowledges positively") { currentActivity ->
                            owned()
                            assertTrue("The same actual Machines instance must acknowledge this edit", currentActivity === activity)
                            val state = ViewModelProvider(currentActivity)[MachinesViewModel::class.java].state.value
                            assertTrue("A refusal, conflict or unknown outcome must not be replayed",
                                state.ruleWriteState == null || state.ruleWriteState in setOf(MachineRuleWriteState.SAVING, MachineRuleWriteState.SAVED))
                            state.ruleWriteMachineId == before.id && state.ruleWriteState == MachineRuleWriteState.SAVED &&
                                texts(currentActivity).any { it == currentActivity.getString(R.string.machines_rules_saved) }
                        }
                        val afterResult = runBlocking { repository.machines(actor) }
                        owned()
                        assertTrue("The actual post-Save census must be readable", afterResult is ApiResult.Success)
                        val afterRows = (afterResult as ApiResult.Success).value.machines
                        val after = afterRows.single { it.id == before.id }
                        assertEquals("The real server persists only the exact requested whole rules", expected, after.alerts)
                        assertEquals("Exactly one native Save advances the rule revision once", before.rulesRevision!!.toULong() + 1uL, after.rulesRevision!!.toULong())
                        assertEquals("The exact machine incarnation is preserved", before.machineIncarnation, after.machineIncarnation)
                        assertEquals("Every unknown extension is preserved", before.extensions, after.extensions)
                        assertEquals("No machine is added or removed", censusBefore.map { it.id }.toSet(), afterRows.map { it.id }.toSet())
                        for (other in censusBefore.filter { it.id != before.id }) {
                            val currentRow = afterRows.single { it.id == other.id }
                            assertTrue("All other machine rule witnesses remain unchanged",
                                currentRow.alerts == other.alerts && currentRow.rulesRevision == other.rulesRevision &&
                                    currentRow.machineIncarnation == other.machineIncarnation && currentRow.extensions == other.extensions)
                        }
                        assertEquals("Rule editing never changes alert preferences", preferencesBefore, serverPreconditions())
                        owned()
                        instrumentation.sendStatus(2, android.os.Bundle().apply {
                            putString("liveMemoryRuleRevision", after.rulesRevision)
                            putString("liveMemoryMachineIncarnation", after.machineIncarnation)
                            putString("stream", "One normal native Crew memory rule ${if (remove) "removal" else "creation"} acknowledged; actual revision advanced once. No delivery claim.\n")
                        })
                    } finally { instrumentation.runOnMainSync { machinesActivity?.finish() } }
                }
            } finally { instrumentation.runOnMainSync { repository.selectSubject(priorScope, priorSubject) } }
        }
    }

    /** Read-only sampled charts and honest unavailable-member gaps; this does not emit an alert. */
    @Test fun liveMachineChartUsesRealSamplesAndUnavailableMemberShowsHonestGaps() = withLive { live ->
        val availableId = required("liveMachineId")
        val unavailableId = required("liveUnavailableMachineId")
        assertTrue("Sampled and unavailable members must be distinct explicit machines", availableId != unavailableId)
        val repository = live.app.container.repository
        ActivityScenario.launch(MainActivity::class.java).use { dashboard ->
            await(dashboard, "real live global dashboard before machine chart navigation") { activity ->
                val state = ViewModelProvider(activity)[MainViewModel::class.java].state.value
                val source = repository.readSource.value as? ReadSourceState.Live
                state.snapshot != null && state.savedCopy == null && state.writeAuthorized &&
                    repository.selectedScope.value == Scope() && source != null &&
                    source.key.host == null && source.key.session == null && source.key.subjectIdentity == null &&
                    repository.writesAllowed(Scope(), null) && activity.findViewById<View>(R.id.dashboard_machines_button).isShown
            }
            val actor = requireNotNull(repository.machineReadContext())
            val available = machine(live, actor, availableId)
            val unavailable = machine(live, actor, unavailableId)
            assertTrue("Real reachable fixture must carry an actual resource sample", available.health == "reachable" && available.sample != null && available.sampledAt != null)
            assertEquals("The explicit stopped member must actually be unreachable", "unreachable", unavailable.health)
            current(live, actor)
            val historyResult = runBlocking { repository.machineHistory(actor, unavailableId) }
            current(live, actor)
            assertTrue("The unavailable member's actual retained history must be readable", historyResult is ApiResult.Success)
            val retainedHistory = (historyResult as ApiResult.Success).value
            val lastRecordedMinute = requireNotNull(retainedHistory.points.lastOrNull()) {
                "This fixture requires genuine retained samples from before the member stopped"
            }.t
            val firstMissingMinute = lastRecordedMinute + retainedHistory.stepMs
            assertTrue("Allow a full missing minute to elapse naturally after the final real sample before this check",
                firstMissingMinute + retainedHistory.stepMs <= retainedHistory.ts - retainedHistory.ts % retainedHistory.stepMs)
            current(live, actor)
            onView(withId(R.id.dashboard_machines_button)).perform(click())
            var owned: MachinesActivity? = null
            try {
                owned = awaitMachines("actual Machines list containing the two real fixture members") { activity ->
                    texts(activity).any { it.startsWith(available.name + "\n") } && texts(activity).any { it.startsWith(unavailable.name + "\n") }
                }
                onView(withText(startsWith(available.name + "\n"))).perform(scrollTo(), click())
                val sampledActivity = awaitMachines("real sampled machine history and native charts") { activity ->
                    if (activity !== owned) return@awaitMachines false
                    val state = ViewModelProvider(activity)[MachinesViewModel::class.java].state.value
                    val row = state.response?.machines?.singleOrNull { it.id == availableId }
                    val spark = row?.spark
                    val history = state.history
                    state.actor?.key == actor.key && state.selectedMachineId == availableId && state.error == null && state.historyError == null &&
                        row?.sample != null && spark != null && history != null &&
                        machineSparkWindow(spark).zipWithNext().any { (a, b) -> a.cpu != null && b.cpu != null } &&
                        buildMachineHistoryWindow(history).points.zipWithNext().any { (a, b) -> a != null && b != null } &&
                        descendants(activity.window.decorView).filterIsInstance<ResourceChartView>().size == 2
                }
                val sparkPrefix = live.app.resources.getText(R.string.machines_chart_accessibility).toString().substringBefore('%')
                val historyPrefix = live.app.resources.getText(R.string.machines_history_chart_accessibility).toString().substringBefore('%')
                val legendPrefix = live.app.resources.getText(R.string.machines_history_legend).toString().substringBefore('%')
                onView(withContentDescription(startsWith(sparkPrefix))).perform(scrollTo())
                awaitCurrentVisibleChart(live, actor, sampledActivity, availableId, history = false)
                onView(withText(live.app.getString(R.string.machines_chart_legend))).perform(scrollTo())
                onView(withContentDescription(startsWith(historyPrefix))).perform(scrollTo())
                awaitCurrentVisibleChart(live, actor, sampledActivity, availableId, history = true)
                onView(withText(startsWith(legendPrefix))).perform(scrollTo())
                awaitCurrentHistoryLegend(live, actor, sampledActivity, availableId)
                current(live, actor)
                // Return through the real native list action before selecting the unavailable row.
                onView(withText(live.app.getString(R.string.machines_back_to_list))).perform(scrollTo(), click())
                awaitMachines("actual unavailable member row") { activity -> activity === owned && texts(activity).any { it == "${unavailable.name}\nunreachable · ${unavailable.resourceSummary()}" } }
                onView(withText(startsWith(unavailable.name + "\n"))).perform(scrollTo(), click())
                val missingActivity = awaitMachines("native unavailable member retains actual history and missing-minute gaps") { activity ->
                    if (activity !== owned) return@awaitMachines false
                    val state = ViewModelProvider(activity)[MachinesViewModel::class.java].state.value
                    val row = state.response?.machines?.singleOrNull { it.id == unavailableId }
                    state.actor?.key == actor.key && state.selectedMachineId == unavailableId && state.error == null && state.historyError == null &&
                        row?.health == "unreachable" && row.sample == unavailable.sample && row.sampledAt == unavailable.sampledAt &&
                        state.history?.points?.any { it.t == lastRecordedMinute } == true &&
                        texts(activity).any { text -> text.lineSequence().any { it == activity.getString(R.string.machines_health, "unreachable") } }
                }
                instrumentation.runOnMainSync {
                    val state = ViewModelProvider(missingActivity)[MachinesViewModel::class.java].state.value
                    val row = requireNotNull(state.response?.machines?.singleOrNull { it.id == unavailableId })
                    assertEquals("Unreachable member must retain its actual last reading without advancing time", unavailable.sample, row.sample)
                    assertEquals("Unreachable member must retain the actual sample timestamp", unavailable.sampledAt, row.sampledAt)
                    val spark = row.spark
                    if (spark == null) assertTrue("No retained sparkline must be shown as explicitly unavailable", texts(missingActivity).contains(missingActivity.getString(R.string.machines_spark_unavailable)))
                    else {
                        val responseAt = requireNotNull(state.response).ts
                        val sparkEnd = responseAt - responseAt % spark.stepMs
                        val missingIndices = spark.cpu.indices.filter { index ->
                            sparkEnd - (spark.cpu.size - index) * spark.stepMs > lastRecordedMinute
                        }
                        assertTrue("Retained sparkline must include a completed missing minute", missingIndices.isNotEmpty())
                        assertTrue("Every spark minute after the last real sample must be a gap",
                            missingIndices.all { spark.cpu[it] == null && spark.mem[it] == null })
                        val sampled = machineSparkWindow(spark).count { it.cpu != null || it.memory != null }
                        assertTrue("Sparkline accessibility must count retained real samples", descendants(missingActivity.window.decorView).filterIsInstance<ResourceChartView>().any { it.contentDescription?.toString() == missingActivity.getString(R.string.machines_chart_accessibility, sampled) })
                    }
                    val history = requireNotNull(state.history)
                    val retained = retainedHistory.points.filter { it.t <= history.ts && history.ts - it.t < 86_400_000L }
                    assertEquals("Native history must preserve actual API samples without adding or changing readings", retained, history.points)
                    val window = buildMachineHistoryWindow(history)
                    assertTrue("Native history must retain measured points", window.points.any { it != null })
                    val missingSlots = window.points.indices.filter { window.startAt + it * history.stepMs > lastRecordedMinute }
                    assertTrue("Native history must include a missing interval after the last real sample", missingSlots.isNotEmpty())
                    assertTrue("Every history minute after the final actual sample must remain missing", missingSlots.all { window.points[it] == null })
                    assertTrue("History accessibility must count genuine retained samples", descendants(missingActivity.window.decorView).filterIsInstance<ResourceChartView>().any {
                        it.contentDescription?.toString() == missingActivity.getString(R.string.machines_history_chart_accessibility, historyRange(missingActivity, window), window.points.count { point -> point != null })
                    })
                    val gap = missingActivity.getString(R.string.machines_history_gap, DateFormat.format("MMM d, HH:mm", firstMissingMinute))
                    assertTrue("Native history table must explicitly label the minute after the last real sample", texts(missingActivity).any { gap in it })
                }
                current(live, actor)
                for (before in listOf(available, unavailable)) {
                    val after = machine(live, actor, before.id)
                    assertEquals("Read-only chart browsing must preserve all machine rules", before.alerts, after.alerts)
                    assertEquals("Read-only chart browsing must preserve rule revision", before.rulesRevision, after.rulesRevision)
                    assertEquals("Read-only chart browsing must preserve incarnation", before.machineIncarnation, after.machineIncarnation)
                    assertEquals("Read-only chart browsing must preserve unknown fields", before.extensions, after.extensions)
                }
            } finally { instrumentation.runOnMainSync { owned?.finish() } }
        }
    }

    /** Real official-release check only. Parent measures any eligible digest notification separately. */
    @Test fun liveServerUpdatesNativeCheckShowsGenuineReleaseWithoutInstalling() = withLive { live ->
        val expected = required("expectedServerRelease")
        assertTrue("Expected official release must be an explicit stable semantic version", expected.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")))
        val repository = live.app.container.repository
        val actor = requireNotNull(repository.readContext())
        ActivityScenario.launch(UpdatesActivity::class.java).use { scenario ->
            await(scenario, "actual server Updates read") { activity ->
                activity.findViewById<View>(R.id.updates_check_button)?.isEnabled == true &&
                    activity.findViewById<TextView>(R.id.updates_native_summary)?.text?.contains(activity.getString(R.string.updates_native_newest_suffix, expected)) == true
            }
            val before = updates(live, actor)
            assertTrue("Actual official check must offer a real newer server release", before.releaseAvailable)
            assertEquals("Only the expected genuine official offer is accepted", expected, before.latest)
            assertNull("Never interfere with an active or retained server update run", before.run)
            current(live, actor)
            onView(withId(R.id.updates_check_button)).perform(click())
            // The unchanged initial label is not an acknowledgement. Observe the actual completed
            // release check before accepting the enabled native button and its refreshed offer.
            val after = awaitFreshUpdate(live, actor, before)
            await(scenario, "actual server Check now completion") { activity ->
                activity.findViewById<View>(R.id.updates_check_button).isEnabled &&
                    texts(activity).none { it == activity.getString(R.string.updates_native_check_error) } &&
                    activity.findViewById<TextView>(R.id.updates_native_summary).text.contains(activity.getString(R.string.updates_native_newest_suffix, expected))
            }
            assertTrue("Native Check must advance the real upstream check timestamp", after.checkedAt != null && after.checkedAt > (before.checkedAt ?: 0))
            assertTrue("The genuine offered release must remain available", after.releaseAvailable)
            assertEquals(expected, after.latest)
            assertEquals("Checking must preserve the actual installed server version", before.current, after.current)
            assertNull("Checking must not start a server installation or rollback", after.run)
        }
    }

    private fun current(live: Live, actor: ScopedReadContext) = assertTrue("Original pairing and actor must remain current", live.app.container.connectionStore.connection.value === live.paired && live.app.container.repository.isCurrent(actor))
    private fun watch(live: Live, address: PaneAddress, actor: ScopedReadContext): CacheWatchResponse {
        current(live, actor)
        val result = runBlocking { live.app.container.repository.cacheWatch(address, actor) }
        current(live, actor)
        assertTrue("Actual cache watch must be readable", result is ApiResult.Success)
        return (result as ApiResult.Success).value
    }
    private fun machine(live: Live, actor: ScopedReadContext, id: String): MachineRow {
        current(live, actor)
        val result = runBlocking { live.app.container.repository.machines(actor) }
        current(live, actor)
        assertTrue("Actual official machine census must be readable", result is ApiResult.Success)
        return requireNotNull((result as ApiResult.Success).value.machines.singleOrNull { it.id == id }) { "Explicit disposable machine must be present" }
    }
    private fun updates(live: Live, actor: ScopedReadContext): UpdateCheckResponse {
        current(live, actor)
        val result = runBlocking { live.app.container.repository.updateState() }
        current(live, actor)
        assertTrue("Actual original-actor server update state must be readable", result is ApiResult.Success)
        return (result as ApiResult.Success).value
    }
    private fun awaitFreshUpdate(live: Live, actor: ScopedReadContext, before: UpdateCheckResponse): UpdateCheckResponse {
        val deadline = SystemClock.uptimeMillis() + 25_000
        while (SystemClock.uptimeMillis() < deadline) {
            current(live, actor)
            val result = runBlocking { live.app.container.repository.updateState() }
            current(live, actor)
            if (result is ApiResult.Success) {
                val state = result.value
                assertEquals("Checking must preserve the installed server throughout acknowledgement", before.current, state.current)
                assertNull("Checking must never start an installation or rollback", state.run)
                if (state.checkedAt != null && state.checkedAt > (before.checkedAt ?: 0)) return state
            }
            // Read-only reconciliation is bounded and never replays the Check now action.
            SystemClock.sleep(200)
        }
        throw AssertionError("Actual server check timestamp did not advance under the original actor")
    }
    private fun switch(activity: Activity) = descendants(activity.window.decorView).filterIsInstance<CompoundButton>().singleOrNull { it.text.toString() == activity.getString(R.string.cache_watch_label) }
    private fun texts(activity: Activity) = descendants(activity.window.decorView).filterIsInstance<TextView>().filter { it.isShown }.map { it.text.toString() }
    private fun descendants(view: View): List<View> = buildList { add(view); if (view is ViewGroup) for (i in 0 until view.childCount) addAll(descendants(view.getChildAt(i))) }
    private fun countdown(activity: Activity): Long? {
        val marker = activity.getString(R.string.cache_expires_value, "COUNTDOWN")
        val expression = Regex(Regex.escape(marker.substringBefore("COUNTDOWN")) + "(.+)" + Regex.escape(marker.substringAfter("COUNTDOWN")))
        val value = texts(activity).firstNotNullOfOrNull { text -> expression.find(text)?.groupValues?.get(1) } ?: return null
        fun parsed(id: Int, units: List<Long>): Long? {
            val example = if (units.size == 1) activity.getString(id, 11111L) else activity.getString(id, 11111L, 22222L)
            val first = example.split("11111", limit = 2)
            val tail = if (units.size == 1) Regex.escape(first[1]) else {
                val second = first[1].split("22222", limit = 2)
                Regex.escape(second[0]) + "([0-9]+)" + Regex.escape(second[1])
            }
            val regex = Regex("^" + Regex.escape(first[0]) + "([0-9]+)" + tail + "$")
            val match = regex.matchEntire(value) ?: return null
            return units.indices.sumOf { i -> match.groupValues[i + 1].toLong() * units[i] }
        }
        return parsed(R.string.cache_duration_seconds, listOf(1)) ?: parsed(R.string.cache_duration_minutes, listOf(60, 1)) ?: parsed(R.string.cache_duration_hours, listOf(3600, 60)) ?: parsed(R.string.cache_duration_days, listOf(86400, 3600))
    }
    private fun <T : Activity> await(scenario: ActivityScenario<T>, checkpoint: String, ready: (T) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 25_000
        while (SystemClock.uptimeMillis() < deadline) {
            var matched = false
            scenario.onActivity { matched = ready(it) }
            if (matched) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Physical native cache/alerts checkpoint timed out: $checkpoint")
    }
    private fun awaitMachines(checkpoint: String, ready: (MachinesActivity) -> Boolean): MachinesActivity {
        val deadline = SystemClock.uptimeMillis() + 25_000
        while (SystemClock.uptimeMillis() < deadline) {
            var matched: MachinesActivity? = null
            instrumentation.runOnMainSync {
                val activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MachinesActivity>().singleOrNull()
                if (activity != null && ready(activity)) matched = activity
            }
            matched?.let { return it }
            SystemClock.sleep(100)
        }
        throw AssertionError("Physical native cache/alerts checkpoint timed out: $checkpoint")
    }
    private fun awaitRuleDialog(live: Live, actor: ScopedReadContext, activity: MachinesActivity, expected: MachineRow, metricResource: Int = R.string.machines_metric_cpu) {
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val cpu = live.app.getString(metricResource)
        val title = live.app.getString(R.string.machines_edit_rules_title, expected.name)
        val checkbox = live.app.getString(R.string.machines_enable_metric_rule, cpu)
        val threshold = live.app.getString(R.string.machines_threshold_accessibility, cpu)
        val duration = live.app.getString(R.string.machines_duration_accessibility, cpu)
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            current(live, actor)
            instrumentation.runOnMainSync {
                val state = ViewModelProvider(activity)[MachinesViewModel::class.java].state.value
                val row = state.response?.machines?.singleOrNull { it.id == expected.id }
                assertTrue("Real rule dialog must retain its original actor and current row", state.actor?.key == actor.key && row != null)
                assertTrue("Real rule dialog must retain its original revision/incarnation/extensions",
                    row?.rulesRevision == expected.rulesRevision && row?.machineIncarnation == expected.machineIncarnation && row?.extensions == expected.extensions)
                assertTrue("Real rule dialog must not hide a screen admission failure", state.error == null)
            }
            val root = automation.rootInActiveWindow
            if (root != null) {
                try {
                    if (root.packageName?.toString() == live.app.packageName &&
                        hasVisibleNode(root) { it.text?.toString() == title } &&
                        hasVisibleNode(root) { it.text?.toString() == checkbox && it.isCheckable && it.isChecked == (if (metricResource == R.string.machines_metric_memory) expected.alerts.mem != null else expected.alerts.cpu != null) } &&
                        hasVisibleNode(root) { it.contentDescription?.toString() == threshold } &&
                        hasVisibleNode(root) { it.contentDescription?.toString() == duration }) return
                } finally { root.recycle() }
            }
            SystemClock.sleep(100)
        }
        throw AssertionError("Actual rule dialog title and selected metric controls did not become visible; editor was not reopened and Save was not replayed")
    }
    private fun hasVisibleNode(node: AccessibilityNodeInfo, matches: (AccessibilityNodeInfo) -> Boolean): Boolean {
        if (node.isVisibleToUser && matches(node)) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try { if (hasVisibleNode(child, matches)) return true } finally { child.recycle() }
        }
        return false
    }
    private fun awaitCurrentVisibleChart(live: Live, actor: ScopedReadContext, activity: MachinesActivity, id: String, history: Boolean) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            current(live, actor)
            var matched = false
            instrumentation.runOnMainSync {
                val state = ViewModelProvider(activity)[MachinesViewModel::class.java].state.value
                if (state.actor?.key != actor.key || state.selectedMachineId != id || state.error != null || state.historyError != null) return@runOnMainSync
                val description = if (history) {
                    val currentHistory = state.history ?: return@runOnMainSync
                    val window = buildMachineHistoryWindow(currentHistory)
                    if (!window.points.zipWithNext().any { (a, b) -> a != null && b != null }) return@runOnMainSync
                    activity.getString(R.string.machines_history_chart_accessibility, historyRange(activity, window), window.points.count { it != null })
                } else {
                    val spark = state.response?.machines?.singleOrNull { it.id == id }?.spark ?: return@runOnMainSync
                    val points = machineSparkWindow(spark)
                    if (!points.zipWithNext().any { (a, b) -> a.cpu != null && b.cpu != null }) return@runOnMainSync
                    activity.getString(R.string.machines_chart_accessibility, points.count { it.cpu != null || it.memory != null })
                }
                // Fresh VM values and the actual rendered view are compared in one main callback.
                val chart = descendants(activity.window.decorView).filterIsInstance<ResourceChartView>().singleOrNull { it.contentDescription?.toString() == description }
                val bounds = Rect()
                matched = chart != null && chart.width > 0 && chart.height > 0 && chart.isShown && chart.getGlobalVisibleRect(bounds) && !bounds.isEmpty && chart.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_YES
            }
            if (matched) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Actual visible chart did not match its current real sampled-point accessibility description")
    }
    private fun awaitCurrentHistoryLegend(live: Live, actor: ScopedReadContext, activity: MachinesActivity, id: String) {
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            current(live, actor)
            var matched = false
            instrumentation.runOnMainSync {
                val state = ViewModelProvider(activity)[MachinesViewModel::class.java].state.value
                if (state.actor?.key != actor.key || state.selectedMachineId != id || state.error != null || state.historyError != null) return@runOnMainSync
                val window = buildMachineHistoryWindow(state.history ?: return@runOnMainSync)
                val expected = activity.getString(R.string.machines_history_legend, historyRange(activity, window))
                matched = descendants(activity.window.decorView).filterIsInstance<TextView>().any { view ->
                    val bounds = Rect()
                    view.text.toString() == expected && view.isShown && view.getGlobalVisibleRect(bounds) && !bounds.isEmpty
                }
            }
            if (matched) return
            SystemClock.sleep(100)
        }
        throw AssertionError("Actual visible history legend did not match the current real history time range")
    }
    private fun historyRange(activity: Activity, window: MachineHistoryWindow) = activity.getString(R.string.machines_history_time_range,
        DateFormat.format("MMM d, HH:mm", window.startAt), DateFormat.format("MMM d, HH:mm", window.endAt))
}
