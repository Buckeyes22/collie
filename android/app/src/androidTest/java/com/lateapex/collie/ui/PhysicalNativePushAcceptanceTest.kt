package com.lateapex.collie.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.NotificationManager
import android.app.UiAutomation
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import android.widget.CompoundButton
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.core.content.ContextCompat
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.data.ScopedReadContext
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.ApiFailure
import com.lateapex.collie.network.NotifyPreferences
import com.lateapex.collie.network.OriginValidator
import com.lateapex.collie.notifications.NativePushRuntime
import com.lateapex.collie.notifications.NativePushStore
import com.lateapex.collie.notifications.NativePushTapActivity
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest

/** Opt-in real distributor/bridge acceptance. Never posts an endpoint or manufactures a push. */
@RunWith(AndroidJUnit4::class)
class PhysicalNativePushAcceptanceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private fun withLive(test: (CollieApplication, Connection, UiAutomation) -> Unit) {
        val supplied = InstrumentationRegistry.getArguments().getString("liveOrigin")
        assumeTrue("liveOrigin absent; physical notification acceptance is opt-in", supplied != null)
        assertFalse("Supplied liveOrigin must not be blank", supplied.isNullOrBlank())
        val app = ApplicationProvider.getApplicationContext<Context>().applicationContext as CollieApplication
        assertTrue("Physical notification acceptance must use the isolated completion application", app.packageName.endsWith(".completiontest"))
        val connection = app.container.connectionStore.connection.value
        assertTrue("Supplied liveOrigin requires an existing installed pairing", connection?.isPaired == true)
        val paired = requireNotNull(connection)
        assertTrue("Installed pairing must match the exact supplied HTTPS origin and mount", paired.origin == OriginValidator().requireValid(requireNotNull(supplied)))
        val actor = app.container.repository.readContext()
        assertTrue("Existing pairing must admit a current native read actor", actor != null && app.container.repository.isCurrent(actor))
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        val info = automation.serviceInfo
        val oldFlags = info.flags
        info.flags = oldFlags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        automation.serviceInfo = info
        try { test(app, paired, automation) }
        finally {
            info.flags = oldFlags
            automation.serviceInfo = info
            assertTrue("Acceptance must preserve the installed pairing", app.container.connectionStore.connection.value === paired)
        }
    }

    @Test fun liveEnableEnrollsThroughNativeUiAndRealNtfyDistributor() = withLive { app, _, automation ->
        ActivityScenario.launch(NativeNotificationsActivity::class.java).use { scenario ->
            onView(withText(app.getString(R.string.native_notifications_enable))).perform(click())
            val enrolled = app.getString(R.string.native_notifications_enrolled)
            val deadline = SystemClock.uptimeMillis() + WAIT_MS
            while (SystemClock.uptimeMillis() < deadline && app.container.nativePush.status() != enrolled) {
                // Approve only Android's actual permission prompt; never grant a permission directly.
                clickVisible(automation) { node ->
                    node.packageName?.toString() in PERMISSION_PACKAGES &&
                        node.viewIdResourceName?.substringAfterLast('/') == "permission_allow_button"
                }
                // The connector presents the installed distributor by its real display name.
                clickVisible(automation) { node ->
                    node.packageName?.toString() == app.packageName && node.text?.toString()?.equals("ntfy", ignoreCase = true) == true
                }
                SystemClock.sleep(150)
            }
            assertTrue("Native enrollment must complete against the real configured provider", app.container.nativePush.status() == enrolled)
            var displayed = false
            val displayDeadline = SystemClock.uptimeMillis() + 5_000
            while (SystemClock.uptimeMillis() < displayDeadline && !displayed) {
                scenario.onActivity { activity -> displayed = hasVisibleText(activity.window.decorView, enrolled) }
                if (!displayed) SystemClock.sleep(150)
            }
            assertTrue("The same native enrollment screen must visibly update to Enrolled without recreation", displayed)
            val state = NativePushStore(app).read()
            assertTrue("Enrollment must persist current registration custody", state != null && !state.retired &&
                state.registrationId != null && state.endpoint != null && state.signingPublicKey != null)
            val channel = app.getSystemService(NotificationManager::class.java).getNotificationChannel(NativePushRuntime.CHANNEL)
            assertTrue("Actual native channel must be enabled", channel != null && channel.importance != NotificationManager.IMPORTANCE_NONE)
        }
    }

    @Test fun liveDisableRetiresEnrollmentThroughNativeUi() = withLive { app, _, _ ->
        val before = requireNotNull(NativePushStore(app).read()) { "Disable acceptance requires a real persisted enrollment" }
        assertTrue("Disable acceptance starts from the current real enrolled owner", !before.retired &&
            app.container.nativePush.status() == app.getString(R.string.native_notifications_enrolled))
        ActivityScenario.launch(NativeNotificationsActivity::class.java).use { scenario ->
            onView(withText(app.getString(R.string.native_notifications_disable))).perform(click())
            val off = app.getString(R.string.native_notifications_off)
            val manager = app.getSystemService(NotificationManager::class.java)
            val deadline = SystemClock.uptimeMillis() + WAIT_MS
            var retired = false
            while (SystemClock.uptimeMillis() < deadline && !retired) {
                val state = NativePushStore(app).read()
                var displayed = false
                scenario.onActivity { activity -> displayed = hasVisibleText(activity.window.decorView, off) }
                retired = state != null && state.instance == before.instance && state.retired &&
                    app.container.nativePush.status() == off && displayed &&
                    manager.activeNotifications.none { it.tag?.startsWith("collie-push:${before.instance}:") == true }
                if (!retired) SystemClock.sleep(150)
            }
            assertTrue("Real native Disable must visibly retire the same enrollment and cancel its OS alerts", retired)
        }
    }

    /** Parent independently proves the exact previously delivered ciphertext was republished through
     * the real ntfy pipeline. This selector observes replay custody; it never delivers a message. */
    @Test fun liveRealPipelineReplaysPreserveWatermarkAndLeaveNoNativeAlert() = withLive { app, _, _ ->
        val args = InstrumentationRegistry.getArguments()
        assertTrue("Parent must explicitly attest actual real-pipeline ciphertext republication",
            args.getString("liveReplaySent") == "true")
        val expected = args.getString("expectedPushSequence")?.toLongOrNull()
        assertTrue("Explicit positive safe-integer expectedPushSequence is required",
            expected != null && expected in 1..9_007_199_254_740_991L)
        val before = requireNotNull(NativePushStore(app).read()) { "Real persisted enrollment is required" }
        assertTrue("Replay acceptance requires the current nonretired enrollment and signer pin",
            !before.retired && before.registrationId != null && !before.signingPublicKey.isNullOrBlank() &&
                app.container.nativePush.status() == app.getString(R.string.native_notifications_enrolled))
        assertTrue("Initial persisted watermark must match the independently measured server sequence",
            before.sequences.values.maxOrNull() == expected)
        val manager = app.getSystemService(NotificationManager::class.java)
        fun hasOwnAlert() = manager.activeNotifications.any {
            it.notification.channelId == NativePushRuntime.CHANNEL && it.tag?.startsWith("collie-push:${before.instance}:") == true
        }
        assertFalse("Replay check must begin after the previous native alert was dismissed normally", hasOwnAlert())
        SystemClock.sleep(2_000)
        val after = NativePushStore(app).read()
        assertTrue("Real replays must retain the same current registration and signer custody",
            after != null && !after.retired && after.instance == before.instance &&
                after.registrationId == before.registrationId && after.signingPublicKey == before.signingPublicKey &&
                app.container.nativePush.status() == app.getString(R.string.native_notifications_enrolled))
        assertTrue("Duplicate and older real deliveries must not change any persisted slot watermark",
            after != null && after.sequences == before.sequences && after.sequences.values.maxOrNull() == expected)
        assertFalse("Duplicate and older real deliveries must not recreate a native alert", hasOwnAlert())
    }

    /** Requested state remains installed for the parent's independently timed producer checks. */
    @Test fun liveNativeNotificationPreferencePersistsExactToggle() = withLive { app, paired, _ ->
        val args = InstrumentationRegistry.getArguments()
        val kind = args.getString("notifyKind")
        val enabled = when (args.getString("notifyEnabled")) {
            "true" -> true
            "false" -> false
            else -> throw AssertionError("Explicit notifyEnabled=true|false is required")
        }
        val control = when (kind) {
            "blocked" -> R.id.settings_notify_blocked_switch
            "done" -> R.id.settings_notify_done_switch
            "updates" -> R.id.settings_notify_updates_switch
            "machines" -> R.id.settings_notify_machines_switch
            "cache" -> R.id.settings_notify_cache_switch
            else -> throw AssertionError("Explicit notifyKind=blocked|done|updates|machines|cache is required")
        }
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            awaitSettings(scenario, "real native notification preference admission") {
                it.findViewById<CompoundButton>(control)?.isEnabled == true
            }
            val actor = captureSettingsActor(app, paired)
            val before = readPreferences(app, paired, actor)
            val prior = when (kind) {
                "blocked" -> before.blocked
                "done" -> before.done
                "updates" -> before.updates
                "machines" -> before.machines
                else -> before.cache
            }
            assertTrue("Requested control must change state through its actual native button", prior != enabled)
            scenario.onActivity {
                assertTrue("Native control must display the actual persisted starting value",
                    it.findViewById<CompoundButton>(control).isChecked == prior)
            }
            assertSettingsActor(app, paired, actor)
            onView(withId(control)).perform(scrollTo(), click())
            awaitSettings(scenario, "native preference refresh displays the requested state") {
                it.findViewById<CompoundButton>(control)?.let { button -> button.isEnabled && button.isChecked == enabled } == true
            }
            val after = readPreferences(app, paired, actor)
            val expected = when (kind) {
                "blocked" -> before.copy(blocked = enabled)
                "done" -> before.copy(done = enabled)
                "updates" -> before.copy(updates = enabled)
                "machines" -> before.copy(machines = enabled)
                else -> before.copy(cache = enabled)
            }
            assertEquals("Server must persist only the requested notification preference", expected, after)
        }
    }

    /** Parent changes Android's real permission/channel controls through Settings before this run. */
    @Test fun liveFreshNativeNotificationUiReportsActualAndroidRefusal() = withLive { app, paired, _ ->
        val refusal = InstrumentationRegistry.getArguments().getString("notificationRefusal")
        val allowed = ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val manager = app.getSystemService(NotificationManager::class.java)
        val label = when (refusal) {
            "permission" -> {
                assertFalse("Android's actual notification permission must already be denied", allowed)
                app.getString(R.string.native_notifications_permission)
            }
            "channel" -> {
                assertTrue("Channel refusal must be distinguished from permission refusal", allowed)
                assertEquals("Android's real Collie channel must already be disabled", NotificationManager.IMPORTANCE_NONE,
                    manager.getNotificationChannel(NativePushRuntime.CHANNEL)?.importance)
                app.getString(R.string.native_notifications_channel_off)
            }
            else -> throw AssertionError("notificationRefusal must be permission|channel")
        }
        val before = requireNotNull(NativePushStore(app).read())
        val committed = requireNotNull(app.container.connectionStore.captureCommitted())
        val actor = captureSettingsActor(app, paired)
        val credentialHash = MessageDigest.getInstance("SHA-256")
            .digest((paired.origin.value + "\u0000" + paired.token.orEmpty()).encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
        assertTrue("Refusal must be tested against an actual current enrolled installation",
            !before.retired && before.registrationId != null && before.signingPublicKey != null &&
                committed.connection === paired && before.origin == paired.origin.value &&
                before.incarnation == committed.incarnationId && before.credentialHash == credentialHash)
        ActivityScenario.launch(NativeNotificationsActivity::class.java).use { scenario ->
            val deadline = SystemClock.uptimeMillis() + 5_000
            var displayed = false
            while (SystemClock.uptimeMillis() < deadline && !displayed) {
                scenario.onActivity { displayed = hasVisibleText(it.window.decorView, label) }
                if (!displayed) SystemClock.sleep(100)
            }
            assertTrue("Fresh native UI must display the actual Android refusal", displayed)
            assertEquals("Native runtime must report the same actual refusal", label, app.container.nativePush.status())
            assertEquals("Opening refusal UI must preserve enrollment custody and watermarks", before, NativePushStore(app).read())
            assertTrue("Fresh refusal UI must preserve pairing", app.container.connectionStore.connection.value === paired)
            assertSettingsActor(app, paired, actor)
            assertTrue("Fresh refusal UI must retain the original committed owner witness",
                app.container.connectionStore.captureCommitted()?.let { latest ->
                    latest.connection === paired && latest.revision == committed.revision &&
                        latest.incarnationId == committed.incarnationId
                } == true)
        }
    }

    /** The supported native durations are 30 minutes, one hour and four hours; use the first. */
    @Test fun liveNativeNotificationSnoozePersistsRequestedAction() = withLive { app, paired, _ ->
        val action = InstrumentationRegistry.getArguments().getString("notifySnoozeAction")
        val control = when (action) {
            "snooze30" -> R.id.settings_snooze_30
            "resume" -> R.id.settings_snooze_resume
            else -> throw AssertionError("Explicit notifySnoozeAction=snooze30|resume is required")
        }
        ActivityScenario.launch(SettingsActivity::class.java).use { scenario ->
            awaitSettings(scenario, "real native snooze control admission") { it.findViewById<View>(control)?.isEnabled == true }
            val actor = captureSettingsActor(app, paired)
            val before = readPreferences(app, paired, actor)
            val originalUntil = readSnoozedUntil(app, paired, actor)
            assertTrue("Requested snooze action must change the actual server state",
                if (action == "resume") originalUntil != null && originalUntil > System.currentTimeMillis()
                else originalUntil == null || originalUntil <= System.currentTimeMillis())
            assertSettingsActor(app, paired, actor)
            val clickedAt = System.currentTimeMillis()
            onView(withId(control)).perform(scrollTo(), click())
            val nextControl = if (action == "resume") R.id.settings_snooze_30 else R.id.settings_snooze_resume
            awaitSettings(scenario, "native snooze refresh displays the requested state") { it.findViewById<View>(nextControl)?.isEnabled == true }
            val actualUntil = readSnoozedUntil(app, paired, actor)
            if (action == "resume") assertNull("Native Resume must clear the persisted server snooze", actualUntil)
            else assertTrue("Native 30-minute control must persist its actual bounded deadline",
                // Physical phone/server clock checks observed up to 1.26 seconds of skew.
                actualUntil != null && actualUntil in (clickedAt + 30 * 60_000 - 5_000)..(System.currentTimeMillis() + 30 * 60_000 + 5_000))
            assertEquals("Snooze must preserve every alert preference", before, readPreferences(app, paired, actor))
        }
    }

    private fun captureSettingsActor(app: CollieApplication, paired: Connection): ScopedReadContext {
        assertTrue("Settings must retain the originally paired connection", app.container.connectionStore.connection.value === paired)
        return requireNotNull(app.container.repository.readContext()).also { assertSettingsActor(app, paired, it) }
    }

    private fun assertSettingsActor(app: CollieApplication, paired: Connection, actor: ScopedReadContext) {
        assertTrue("Native control/read must remain owned by the original paired actor",
            app.container.connectionStore.connection.value === paired && app.container.repository.isCurrent(actor))
    }

    private fun readPreferences(app: CollieApplication, paired: Connection, actor: ScopedReadContext): NotifyPreferences {
        assertSettingsActor(app, paired, actor)
        val result = runBlocking { app.container.repository.notificationPreferences() }
        assertSettingsActor(app, paired, actor)
        assertTrue("Actual server notification preferences must be readable (${readResultMetadata(result)})", result is ApiResult.Success)
        return (result as ApiResult.Success).value
    }

    private fun readSnoozedUntil(app: CollieApplication, paired: Connection, actor: ScopedReadContext): Long? {
        assertSettingsActor(app, paired, actor)
        val result = runBlocking { app.container.repository.snapshot(actor.key.scope, actor) }
        assertSettingsActor(app, paired, actor)
        assertTrue("Actual original-actor snapshot must confirm server snooze state (${readResultMetadata(result)})", result is ApiResult.Success)
        val state = (result as ApiResult.Success).value.notifications
        assertNotNull("Actual snapshot must explicitly report notification state", state)
        return requireNotNull(state).snoozedUntil
    }

    private fun awaitSettings(scenario: ActivityScenario<SettingsActivity>, checkpoint: String, ready: (SettingsActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            var matched = false
            scenario.onActivity {
                // Controls render partway through the real parallel refresh. Wait for its final
                // status clear so optional-route admission fences have settled before our reads.
                matched = !hasVisibleText(it.window.decorView, it.getString(R.string.settings_refreshing)) && ready(it)
            }
            if (matched) return
            SystemClock.sleep(150)
        }
        throw AssertionError("Physical native notification checkpoint timed out: $checkpoint")
    }

    private fun readResultMetadata(result: ApiResult<*>): String = when (result) {
        is ApiResult.Success -> "Success"
        is ApiResult.NotModified -> "NotModified"
        is ApiResult.Failure -> when (val failure = result.error) {
            is ApiFailure.Http -> "Http/status=${failure.status}"
            else -> failure.javaClass.simpleName
        }
    }

    /** Parent first produces one real alert while the app is backgrounded; this test consumes its OS tap. */
    @Test fun liveDeliveredNotificationHasPrivateCustodyAndNormalTapOpensExactPane() = withLive { app, paired, automation ->
        val args = InstrumentationRegistry.getArguments()
        val destination = args.getString("expectedDestination") ?: "pane"
        assertTrue("Only the existing pane, updates or HOME route is supported", destination in setOf("pane", "updates", "home"))
        val pane = args.getString("expectedPaneId")
        if (destination == "pane") {
            assertTrue("Pane delivery acceptance requires explicit expectedPaneId", !pane.isNullOrBlank() && pane.length <= 256)
        } else {
            assertNull("Updates and HOME deliveries have no pane target", pane)
        }
        val manager = app.getSystemService(NotificationManager::class.java)
        val state = requireNotNull(NativePushStore(app).read()) { "Real persisted enrollment is required" }
        assertTrue("Delivery must retain a current enrolled owner", !state.retired &&
            app.container.nativePush.status() == app.getString(R.string.native_notifications_enrolled))
        val committedOwner = if (destination != "pane") requireNotNull(app.container.connectionStore.captureCommitted()) else null
        val actor = if (destination != "pane") requireNotNull(app.container.repository.readContext()) else null
        if (destination != "pane") {
            val originalCommitted = requireNotNull(committedOwner)
            val originalActor = requireNotNull(actor)
            assertTrue("Delivery must begin under the original installed owner",
                originalCommitted.connection === paired && originalCommitted.incarnationId != null)
            assertTrue("Delivery must begin with the current actor bound to the exact committed owner",
                app.container.repository.isCurrent(originalActor) && originalActor.connection === paired && originalActor.committedStore &&
                    originalActor.connectionIncarnation == originalCommitted.incarnationId && originalActor.committedSnapshot == originalCommitted)
        }
        val launchIntentsBefore = if (destination != "pane") app.container.launchIntentStore.all() else emptyList()
        val deadline = SystemClock.uptimeMillis() + WAIT_MS
        var delivered: android.service.notification.StatusBarNotification? = null
        while (SystemClock.uptimeMillis() < deadline) {
            val candidates = manager.activeNotifications.filter { notification ->
                notification.notification.channelId == NativePushRuntime.CHANNEL &&
                    notification.tag?.startsWith("collie-push:${state.instance}:") == true
            }
            assertTrue("Controlled acceptance requires at most one native alert", candidates.size <= 1)
            if (candidates.size == 1) { delivered = candidates.single(); break }
            SystemClock.sleep(150)
        }
        val actual = requireNotNull(delivered) { "No real native delivery reached Android" }
        assertTrue("Delivered notification must belong to this installed app", actual.packageName == app.packageName && actual.id == 0)
        val slot = requireNotNull(actual.tag).substringAfterLast(':')
        val committed = requireNotNull(NativePushStore(app).read())
        assertTrue("Receiver must commit its replay watermark before display", committed.instance == state.instance && (committed.sequences[slot] ?: 0) > 0)
        val notification = actual.notification
        val tap = requireNotNull(notification.contentIntent) { "Actual native notification must have its OS-held tap" }
        assertTrue("OS-held notification tap must be immutable and app-owned", tap.isImmutable && tap.creatorPackage == app.packageName)
        @Suppress("DEPRECATION")
        val tapActivity = app.packageManager.getActivityInfo(ComponentName(app, NativePushTapActivity::class.java), 0)
        assertFalse("Native notification tap Activity must remain private", tapActivity.exported)
        assertTrue("Public notification extras must not expose enrollment or envelope secrets",
            notification.extras.keySet().none { it in setOf("payload", "endpoint", "auth", "p256dh", "token", "registrationId") })
        val title = notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()
        assertTrue("Actual notification must have its bounded display title", !title.isNullOrBlank() && title.length <= 160)

        // A real touchscreen shade gesture and SystemUI accessibility click exercise the OS tap.
        openNotificationShade(automation, app)
        val tapDeadline = SystemClock.uptimeMillis() + 10_000
        var tapped = false
        while (SystemClock.uptimeMillis() < tapDeadline && !tapped) {
            tapped = clickVisible(automation) { node -> node.packageName?.toString() == "com.android.systemui" && node.text?.toString() == title }
            if (!tapped) SystemClock.sleep(150)
        }
        assertTrue("Actual notification must be tapped through visible SystemUI", tapped)
        when (destination) {
            "pane" -> {
                var opened: PaneActivity? = null
                try {
                    val openDeadline = SystemClock.uptimeMillis() + WAIT_MS
                    while (SystemClock.uptimeMillis() < openDeadline && opened == null) {
                        instrumentation.runOnMainSync {
                            opened = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                                .filterIsInstance<PaneActivity>().singleOrNull()
                        }
                        if (opened == null) SystemClock.sleep(150)
                    }
                    assertNotNull("Normal notification tap must open the actual native Pane Activity", opened)
                    instrumentation.runOnMainSync {
                        val intent = requireNotNull(opened).intent
                        assertTrue("Normal tap must retain the exact delivered pane", intent.getStringExtra(PaneActivity.EXTRA_PANE_ID) == pane)
                        assertTrue("Normal tap must retain the exact delivered host", intent.getStringExtra(PaneActivity.EXTRA_HOST) == args.getString("expectedHost"))
                        assertTrue("Normal tap must retain the exact delivered session", intent.getStringExtra(PaneActivity.EXTRA_SESSION) == args.getString("expectedSession"))
                    }
                } finally { instrumentation.runOnMainSync { opened?.finish() } }
            }
            "updates" -> assertUpdatesNotificationDestination(app, paired, requireNotNull(actor), requireNotNull(committedOwner), committed, launchIntentsBefore)
            "home" -> assertHomeNotificationDestination(app, paired, requireNotNull(committedOwner), committed, launchIntentsBefore)
        }
    }

    /** Normal HOME taps load the real global dashboard; they grant no new terminal authority. */
    private fun assertHomeNotificationDestination(
        app: CollieApplication,
        paired: Connection,
        committedBefore: com.lateapex.collie.data.CommittedConnectionSnapshot,
        pushBefore: com.lateapex.collie.notifications.PushEnrollment,
        launchIntentsBefore: List<com.lateapex.collie.data.StoredLaunchIntent>,
    ) {
        val args = InstrumentationRegistry.getArguments()
        assertNull("This HOME acceptance targets the lead's global dashboard", args.getString("expectedHost"))
        assertNull("This HOME acceptance has no selected session", args.getString("expectedSession"))
        val repository = app.container.repository
        fun owner() {
            assertTrue("HOME tap preserves the exact paired credential instance", app.container.connectionStore.connection.value === paired)
            assertEquals("HOME tap preserves the committed pairing revision and incarnation", committedBefore, app.container.connectionStore.captureCommitted())
        }
        val credentialHash = MessageDigest.getInstance("SHA-256")
            .digest((paired.origin.value + "\u0000" + paired.token.orEmpty()).encodeToByteArray())
            .joinToString("") { "%02x".format(it) }
        assertTrue("HOME delivery belongs to the actual current enrollment and paired credential",
            !pushBefore.retired && pushBefore.registrationId != null && pushBefore.signingPublicKey != null &&
                pushBefore.origin == paired.origin.value && pushBefore.incarnation == committedBefore.incarnationId &&
                pushBefore.credentialHash == credentialHash)
        owner()
        var opened: MainActivity? = null
        var accepted: ScopedReadContext? = null
        try {
            val deadline = SystemClock.uptimeMillis() + WAIT_MS
            while (SystemClock.uptimeMillis() < deadline && accepted == null) {
                owner()
                instrumentation.runOnMainSync {
                    val resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    val activity = resumed.singleOrNull() as? MainActivity
                    if (activity != null) {
                        opened = activity
                        val state = androidx.lifecycle.ViewModelProvider(activity)[MainViewModel::class.java].state.value
                        val actor = state.originalReadContext
                        val source = repository.readSource.value as? ReadSourceState.Live
                        if (state.configured && state.paired && state.origin == paired.origin.value && state.snapshot != null &&
                            state.savedCopy == null && state.savedCopyVersion == null && state.error == null && !state.authError &&
                            !state.pairingRequired && !state.snapshotFailed && repository.selectedScope.value == com.lateapex.collie.domain.Scope() &&
                            actor != null && repository.isCurrent(actor) && actor.connection === paired && actor.committedStore &&
                            actor.connectionIncarnation == committedBefore.incarnationId && actor.committedSnapshot == committedBefore &&
                            actor.key.host == null && actor.key.session == null && actor.key.subjectIdentity == null &&
                            source?.key == actor.key && activity.findViewById<View>(R.id.dashboard_machines_button)?.isShown == true) accepted = actor
                    }
                }
                if (accepted == null) SystemClock.sleep(150)
            }
            val actor = requireNotNull(accepted) { "Normal OS HOME tap did not load the real current global MainActivity dashboard" }
            val home = requireNotNull(opened)
            fun currentHome() {
                owner()
                assertTrue("The accepted dashboard actor remains current under its original owner", repository.isCurrent(actor))
                instrumentation.runOnMainSync {
                    val resumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    assertTrue("HOME tap leaves only the real MainActivity resumed", resumed.singleOrNull() === home)
                    assertTrue("HOME tap must not take a Pane, Updates or Machines side route", resumed.none { it is PaneActivity || it is UpdatesActivity || it is MachinesActivity })
                    val state = androidx.lifecycle.ViewModelProvider(home)[MainViewModel::class.java].state.value
                    val shown = requireNotNull(state.originalReadContext)
                    assertTrue("The live dashboard retains its accepted exact global actor", shown.key == actor.key &&
                        shown.navigationGeneration == actor.navigationGeneration && shown.committedSnapshot == committedBefore && repository.isCurrent(shown))
                    assertTrue("HOME dashboard remains live without a saved copy or read error", state.snapshot != null && state.savedCopy == null &&
                        state.savedCopyVersion == null && state.error == null && !state.authError && !state.snapshotFailed && !state.pairingRequired &&
                        repository.selectedScope.value == com.lateapex.collie.domain.Scope() &&
                        (repository.readSource.value as? ReadSourceState.Live)?.key == actor.key)
                }
            }
            currentHome()
            val expectedCrew = args.getString("expectedCrewId")
            val expectedLead = args.getString("expectedLeadId")
            if (expectedCrew != null) assertTrue("Expected Crew identity must be an explicit bounded identifier", expectedCrew.isNotBlank() && expectedCrew.length <= 128)
            if (expectedLead != null) assertTrue("Expected lead must be a bounded member identifier", expectedLead.matches(Regex("[a-z0-9][a-z0-9-]{0,62}")))
            val result = runBlocking { repository.crew(actor) }
            currentHome()
            assertTrue("The actual authenticated current Crew must be readable", result is ApiResult.Success)
            val crew = (result as ApiResult.Success).value
            if (expectedCrew != null) assertEquals("HOME dashboard remains on the exact owned Crew", expectedCrew, crew.crew.id)
            assertEquals("The responding Crew member must be its single actual lead", listOf(crew.self.id), crew.members.filter { it.isLead }.map { it.id })
            if (expectedLead != null) assertEquals("The HOME dashboard must use the expected current lead", expectedLead, crew.self.id)
            currentHome()
            assertEquals("HOME tap preserves notification enrollment, credential custody and replay watermarks", pushBefore, NativePushStore(app).read())
            assertEquals("HOME tap must not create, remove or change launch-journal entries", launchIntentsBefore, app.container.launchIntentStore.all())
        } finally { instrumentation.runOnMainSync { opened?.finish() } }
    }

    /** Read-only proof for the fresh owned 1.19.0 -> 1.21.0 update offer; no update or dismiss action is clicked. */
    private fun assertUpdatesNotificationDestination(
        app: CollieApplication,
        paired: com.lateapex.collie.domain.Connection,
        actor: com.lateapex.collie.data.ScopedReadContext,
        committedBefore: com.lateapex.collie.data.CommittedConnectionSnapshot,
        pushBefore: com.lateapex.collie.notifications.PushEnrollment,
        launchIntentsBefore: List<com.lateapex.collie.data.StoredLaunchIntent>,
    ) {
        val args = InstrumentationRegistry.getArguments()
        val expectedCurrent = requireNotNull(args.getString("expectedUpdateCurrent")?.takeIf(String::isNotBlank))
        val expectedLatest = requireNotNull(args.getString("expectedUpdateLatest")?.takeIf(String::isNotBlank))
        assertEquals("This controlled notification acceptance is the fixed source package version", "1.19.0", expectedCurrent)
        assertEquals("This controlled notification acceptance is the fixed offered package version", "1.21.0", expectedLatest)
        fun assertOriginalOwner() {
            assertTrue("Updates destination must retain the exact installed pairing", app.container.connectionStore.connection.value === paired)
            assertTrue("Updates destination must retain the original current actor", app.container.repository.isCurrent(actor))
            val committed = app.container.connectionStore.captureCommitted()
            assertTrue("Updates destination must retain original nonnull connection incarnation and revision",
                committedBefore.incarnationId != null && committed != null && committed.connection === paired &&
                    committed.revision == committedBefore.revision && committed.incarnationId == committedBefore.incarnationId)
        }
        assertOriginalOwner()
        var opened: UpdatesActivity? = null
        try {
            val openDeadline = SystemClock.uptimeMillis() + WAIT_MS
            while (SystemClock.uptimeMillis() < openDeadline && opened == null) {
                instrumentation.runOnMainSync {
                    opened = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<UpdatesActivity>().singleOrNull()
                }
                if (opened == null) SystemClock.sleep(150)
            }
            assertNotNull("Normal OS notification tap must open the native Updates Activity", opened)
            assertOriginalOwner()
            var noPaneResumed = false
            instrumentation.runOnMainSync {
                noPaneResumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .none { it is PaneActivity }
            }
            assertTrue("Updates notification target must not open a terminal Pane Activity", noPaneResumed)

            // This is the Activity's ordinary read endpoint, independently checked under the same
            // still-current app owner; it does not invoke Check Now or any update mutation.
            val result = runBlocking { app.container.repository.updateState() }
            assertOriginalOwner()
            assertTrue("The real mounted update offer must be readable", result is com.lateapex.collie.network.ApiResult.Success)
            val update = (result as com.lateapex.collie.network.ApiResult.Success).value
            assertEquals("Actual source version must match the update offer", expectedCurrent, update.current)
            assertEquals("Actual latest offer must match the parent-produced notification", expectedLatest, update.latest)
            assertTrue("The latest release must be a genuine available offer", update.releaseAvailable)

            val expectedSummary = app.getString(R.string.updates_native_running, expectedCurrent) +
                app.getString(R.string.updates_native_newest_suffix, expectedLatest)
            val expectedOffer = app.getString(R.string.updates_native_dismiss_offer, expectedLatest)
            val uiDeadline = SystemClock.uptimeMillis() + WAIT_MS
            var offerLoaded = false
            while (SystemClock.uptimeMillis() < uiDeadline && !offerLoaded) {
                instrumentation.runOnMainSync {
                    val activity = opened
                    val summary = activity?.findViewById<TextView>(R.id.updates_native_summary)
                    val offer = activity?.findViewById<View>(R.id.updates_native_offer_dismiss_button)
                    offerLoaded = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).singleOrNull() === activity &&
                        summary?.isShown == true && summary.text.toString().contains(expectedSummary) &&
                        offer?.isShown == true && (offer as? TextView)?.text?.toString() == expectedOffer
                }
                if (!offerLoaded) SystemClock.sleep(150)
            }
            assertTrue("Updates screen must load the genuine current version and undismissed latest offer", offerLoaded)
            // Scroll the actual native Updates view so the offer is fully onscreen; do not click it.
            onView(withId(R.id.updates_native_offer_dismiss_button)).perform(scrollTo())
            var rendered = false
            while (SystemClock.uptimeMillis() < uiDeadline && !rendered) {
                instrumentation.runOnMainSync {
                    val activity = opened
                    val summary = activity?.findViewById<TextView>(R.id.updates_native_summary)
                    val offer = activity?.findViewById<View>(R.id.updates_native_offer_dismiss_button)
                    val visibleBounds = android.graphics.Rect()
                    val fullyVisible = offer != null && offer.getGlobalVisibleRect(visibleBounds) &&
                        visibleBounds.width() >= offer.width && visibleBounds.height() >= offer.height
                    rendered = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).singleOrNull() === activity &&
                        summary?.isShown == true && summary.text.toString().contains(expectedSummary) &&
                        offer?.isShown == true && (offer as? TextView)?.text?.toString() == expectedOffer && fullyVisible
                }
                if (!rendered) SystemClock.sleep(150)
            }
            assertTrue("Updates screen must visibly show the genuine current version and fully onscreen undismissed latest offer", rendered)
            assertOriginalOwner()
            assertEquals("Notification tap must not alter native notification enrollment or replay watermarks",
                pushBefore, NativePushStore(app).read())
            assertEquals("Opening the update offer must not add, remove, or change launch-journal records",
                launchIntentsBefore, app.container.launchIntentStore.all())
            var sameUpdatesResumed = false
            instrumentation.runOnMainSync {
                sameUpdatesResumed = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).singleOrNull() === opened
            }
            assertTrue("Updates tap must leave the same Updates Activity resumed", sameUpdatesResumed)
        } finally { instrumentation.runOnMainSync { opened?.finish() } }
    }

    private fun clickVisible(automation: UiAutomation, matches: (AccessibilityNodeInfo) -> Boolean): Boolean {
        fun visit(node: AccessibilityNodeInfo): Boolean {
            if (node.isVisibleToUser && matches(node)) {
                var candidate = AccessibilityNodeInfo.obtain(node)
                try {
                    repeat(8) {
                        if (candidate.isVisibleToUser && candidate.isEnabled && candidate.isClickable)
                            return candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        val parent = candidate.parent ?: return false
                        candidate.recycle(); candidate = parent
                    }
                } finally { candidate.recycle() }
            }
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                try { if (visit(child)) return true } finally { child.recycle() }
            }
            return false
        }
        val root = automation.rootInActiveWindow ?: return false
        return try { visit(root) } finally { root.recycle() }
    }

    private fun hasVisibleText(view: View, expected: String): Boolean {
        if (view is TextView && view.isShown && view.text.toString() == expected) return true
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            if (hasVisibleText(view.getChildAt(index), expected)) return true
        }
        return false
    }

    private fun openNotificationShade(automation: UiAutomation, app: Context) {
        val metrics = app.resources.displayMetrics
        val start = SystemClock.uptimeMillis()
        fun touch(action: Int, elapsed: Long, y: Float) {
            val event = MotionEvent.obtain(start, start + elapsed, action, metrics.widthPixels / 2f, y, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try { assertTrue("OS notification shade gesture must be injected normally", automation.injectInputEvent(event, true)) }
            finally { event.recycle() }
        }
        touch(MotionEvent.ACTION_DOWN, 0, 1f)
        for (step in 1..8) {
            SystemClock.sleep(35)
            touch(MotionEvent.ACTION_MOVE, SystemClock.uptimeMillis() - start, metrics.heightPixels * .65f * step / 8)
        }
        touch(MotionEvent.ACTION_UP, SystemClock.uptimeMillis() - start, metrics.heightPixels * .65f)
    }

    private companion object {
        const val WAIT_MS = 45_000L
        val PERMISSION_PACKAGES = setOf("com.android.permissioncontroller", "com.google.android.permissioncontroller")
    }
}
