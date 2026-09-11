package com.lateapex.collie.ui

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.data.EncryptedConnectionStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpdatesActivityParityTest {
    private val application = ApplicationProvider.getApplicationContext<CollieApplication>()

    @Before
    fun disconnect() {
        application.getSharedPreferences(EncryptedConnectionStore.PREFERENCES, 0).edit().clear().commit()
        application.container.connectionStore.clear()
    }

    @Test
    fun confirmationReplacesActionsInsideTheCardAndRequiresASecondTap() {
        val activity = Robolectric.buildActivity(UpdatesActivity::class.java).create().get()
        var started = false

        activity.showConfirmation(
            "Update to 1.6.0?",
            "Your terminal session stays alive. The phone view drops for up to 30 seconds.",
            "Yes, update",
        ) { started = true }

        assertFalse(started)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.updates_native_standard_actions).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.updates_native_confirmation).visibility)
        assertEquals(
            "Update to 1.6.0?",
            activity.findViewById<TextView>(R.id.updates_native_confirmation_title).text,
        )
        assertEquals(
            "Your terminal session stays alive. The phone view drops for up to 30 seconds.",
            activity.findViewById<TextView>(R.id.updates_native_confirmation_body).text,
        )

        activity.findViewById<View>(R.id.updates_native_confirmation_confirm).performClick()
        assertTrue(started)
        assertFalse(activity.findViewById<View>(R.id.updates_native_confirmation_confirm).isEnabled)
        assertFalse(activity.findViewById<View>(R.id.updates_native_confirmation_cancel).isEnabled)
        assertEquals(
            "Starting…",
            activity.findViewById<TextView>(R.id.updates_native_confirmation_confirm).text,
        )
    }

    @Test
    fun cancelLeavesTheUpdateUntouchedAndRestoresTheActionArea() {
        val activity = Robolectric.buildActivity(UpdatesActivity::class.java).create().get()
        var started = false
        activity.showConfirmation("Retry the pack update?", "Only peers run.", "Yes, retry") {
            started = true
        }

        activity.findViewById<View>(R.id.updates_native_confirmation_cancel).performClick()

        assertFalse(started)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.updates_native_standard_actions).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.updates_native_confirmation).visibility)
    }

    @Test
    fun cardSaysReleasesBehindNotAVersionList() {
        val activity = launchUpdates(
            running = "1.5.1",
            latest = "1.8.0",
            newer = listOf("1.5.2", "1.5.3", "1.5.4", "1.5.5", "1.5.6", "1.6.0", "1.7.0", "1.8.0"),
        )
        val summary = activity.findViewById<TextView>(R.id.updates_native_summary).text.toString()
        assertTrue(summary.contains("8 releases behind"))
        assertFalse(summary.contains("1.5.2"))
    }

    @Test
    fun remindIsAButtonAndBlockedReasonIsOneLine() {
        val activity = launchUpdates(preflightRed = 3)
        assertTrue(activity.findViewById<View>(R.id.updates_snooze_button) is com.google.android.material.button.MaterialButton)
        assertEquals(
            "Blocked by 3 preflight checks",
            activity.findViewById<TextView>(R.id.updates_native_blocked_reason).text.toString(),
        )
    }

    private fun launchUpdates(
        running: String = "1.5.1",
        latest: String? = null,
        newer: List<String> = emptyList(),
        preflightRed: Int = 0,
        remedy: (Int) -> String = { index -> "collie doctor --fix --step $index" },
    ): UpdatesActivity {
        val activity = Robolectric.buildActivity(UpdatesActivity::class.java).create().get()
        val checks = (0 until preflightRed).map { index ->
            com.lateapex.collie.network.PreflightCheck(
                id = "check-$index",
                verdict = "red",
                reason = "check $index failed",
                remedy = remedy(index),
            )
        }
        activity.renderForTest(
            com.lateapex.collie.network.UpdateCheckResponse(
                current = running,
                latest = latest,
                latestUrl = null,
                releaseAvailable = latest != null && latest != running,
                majorAvailable = null,
                majorUrl = null,
                bridgeStale = false,
                checkedAt = 1,
                newerVersions = newer,
                preflight = com.lateapex.collie.network.PreflightReport(
                    schema = 1,
                    verdict = if (preflightRed > 0) "red" else "green",
                    checks = checks,
                ),
            ),
        )
        return activity
    }

    @Test
    fun remedyLinesDropMarkdownBackticks() {
        // The bridge writes remedies as markdown; shown as plain text the ticks read literally
        // ("Fix on the host: `git stash` or commit them…", S25 Ultra walk 2026-09-11).
        val activity = launchUpdates(latest = "1.8.0", newer = listOf("1.8.0"), preflightRed = 1, remedy = { "`git stash` or commit them, then re-run this check" })
        val texts = generateSequence(listOf<View>(activity.window.decorView)) { level ->
            level.filterIsInstance<ViewGroup>().flatMap { g -> (0 until g.childCount).map(g::getChildAt) }.takeIf { it.isNotEmpty() }
        }.flatten().filterIsInstance<TextView>().map { it.text.toString() }.toList()
        val line = texts.single { it.startsWith("Fix on the host:") }
        assertEquals("Fix on the host: git stash or commit them, then re-run this check", line)
    }

    @Test
    fun restartProgressAndPackActionCopyMatchesTheWebContract() {
        val resources = application.resources

        assertEquals("Back", resources.getString(R.string.updates_native_back))
        assertEquals("Update Collie", resources.getString(R.string.updates_native_card_title))
        assertEquals("Update pack to 1.6.0", resources.getString(R.string.updates_native_action_pack, "1.6.0"))
        assertEquals("Restarting. This is not an outage.", resources.getString(R.string.updates_native_state_restarting))
        assertEquals(
            "Keep this screen open. Your terminal session is untouched.",
            resources.getString(R.string.updates_native_progress_note),
        )
    }
}
