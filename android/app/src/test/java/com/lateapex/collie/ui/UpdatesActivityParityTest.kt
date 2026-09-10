package com.lateapex.collie.ui

import android.view.View
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
