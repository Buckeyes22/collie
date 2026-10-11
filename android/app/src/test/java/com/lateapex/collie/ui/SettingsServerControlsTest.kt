package com.lateapex.collie.ui

import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.android.material.materialswitch.MaterialSwitch
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.CacheWatchEntry
import com.lateapex.collie.network.CacheWatchListResponse
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
        seedUnpairedConnection(application)
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
        assertEquals(R.id.settings_parity_pairing_card, reveals.single().first.id)
        val name = parent.findViewById<EditText>(R.id.settings_pair_label)
        assertSame(name, reveals.single().second)
        val code = parent.findViewById<EditText>(R.id.settings_pair_code)
        assertEquals("AB12-CD34", code.text.toString())
        assertFalse(code.isSaveEnabled)
        assertEquals(View.IMPORTANT_FOR_AUTOFILL_NO, code.importantForAutofill)
    }

    @Test
    fun settingsExposeSharedMachineAndCacheSwitchesAndTheLeadOwnedWatchList() {
        val application = ApplicationProvider.getApplicationContext<CollieApplication>()
        seedUnpairedConnection(application)
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).create().get()
        val parent = LinearLayout(activity)
        val controls = SettingsServerControls(activity, application.container.repository, {}, {})

        controls.bind(parent)

        assertTrue(parent.findViewById<View>(R.id.settings_notify_machines_switch) != null)
        assertTrue(parent.findViewById<View>(R.id.settings_notify_cache_switch) != null)
        assertTrue(parent.findViewById<View>(R.id.settings_cache_watch_card) != null)
    }

    @Test
    fun orphanedWatchIsNonlinkableAndOffersOnlyForget() {
        val application = ApplicationProvider.getApplicationContext<CollieApplication>()
        seedUnpairedConnection(application)
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).create().get()
        val parent = LinearLayout(activity)
        val controls = SettingsServerControls(activity, application.container.repository, {}, {})
        controls.bind(parent)

        controls.renderCacheWatchList(ApiResult.Success(
            CacheWatchListResponse(listOf(CacheWatchEntry(id = "opaque-id", label = "orphaned agent"))),
            200,
        ))

        val card = parent.findViewById<ViewGroup>(R.id.settings_cache_watch_card)
        val buttons = findButtons(card)
        assertEquals(1, buttons.size)
        assertEquals("Forget watch", buttons.single().text.toString())
        assertTrue(findText(card, "orphaned agent")?.text.toString().contains("cannot be opened"))
    }

    private fun findButtons(root: View): List<com.google.android.material.button.MaterialButton> {
        val found = mutableListOf<com.google.android.material.button.MaterialButton>()
        if (root is com.google.android.material.button.MaterialButton) found += root
        if (root is ViewGroup) for (index in 0 until root.childCount) found += findButtons(root.getChildAt(index))
        return found
    }

    private fun findText(root: View, phrase: String): TextView? {
        if (root is TextView && root.text.toString().contains(phrase)) return root
        if (root is ViewGroup) for (index in 0 until root.childCount) findText(root.getChildAt(index), phrase)?.let { return it }
        return null
    }

    private fun seedUnpairedConnection(application: CollieApplication) {
        saveFixtureConnection(application, com.lateapex.collie.domain.Connection(
            com.lateapex.collie.domain.CollieOrigin("https://collie.example.com/"),
            "S25U-native",
            token = null,
        ))
    }
}
