package com.lateapex.collie.ui

import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.materialswitch.MaterialSwitch
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.DevicesResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SettingsServerControlsTest {
    @Test
    fun mutationGateDisablesEveryControlAndRestoresOnlyItsBaseline() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).create().get()
        val gate = SettingsMutationControlGate()
        val enabled = gate.register(View(activity), enabled = true)
        val capabilityBlocked = gate.register(View(activity), enabled = false)

        gate.setBusy(true)
        val addedWhileBusy = gate.register(View(activity), enabled = true)
        assertFalse(enabled.isEnabled)
        assertFalse(capabilityBlocked.isEnabled)
        assertFalse(addedWhileBusy.isEnabled)

        gate.setBusy(false)
        assertTrue(enabled.isEnabled)
        assertFalse(capabilityBlocked.isEnabled)
        assertTrue(addedWhileBusy.isEnabled)
    }

    @Test
    fun controlledSwitchDispatchesProposalWithoutPaintingItOptimistically() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).create().get()
        val control = MaterialSwitch(activity).apply {
            isEnabled = true
            isChecked = true
        }
        var proposal: Boolean? = null

        SettingsControlledSwitch.handleChange(control, renderedValue = false, mutationsBusy = false) {
            proposal = it
        }

        assertFalse(control.isChecked)
        assertEquals(true, proposal)

        control.isChecked = true
        SettingsControlledSwitch.handleChange(control, renderedValue = false, mutationsBusy = true) {
            proposal = false
        }
        assertFalse(control.isChecked)
        assertEquals(true, proposal)
    }

    @Test
    fun pairEntryScrollsThenPrefillsCodeAndTargetsTheDeviceName() {
        val application = ApplicationProvider.getApplicationContext<CollieApplication>()
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).create().get()
        val reveals = mutableListOf<Pair<View, View?>>()
        val parent = LinearLayout(activity)
        val controls = SettingsServerControls(
            activity = activity,
            repository = application.container.repository,
            onConnection = {},
            onSpeechCapability = {},
            initialPairCode = "AB12-CD34",
            focusDevices = true,
            onRevealDevices = { card, target -> reveals += card to target },
        )

        controls.bind(parent)
        assertEquals(1, reveals.size)
        assertNull(reveals.single().second)

        controls.renderDevices(
            ApiResult.Success(
                DevicesResponse(enforced = true, current = null, devices = emptyList()),
                status = 200,
            ),
            writesAllowed = false,
        )

        assertEquals("AB12-CD34", parent.findViewById<EditText>(R.id.settings_pair_code).text.toString())
        val name = parent.findViewById<EditText>(R.id.settings_pair_label)
        assertEquals("", name.text.toString())
        assertEquals(2, reveals.size)
        assertSame(name, reveals.last().second)
    }
}
