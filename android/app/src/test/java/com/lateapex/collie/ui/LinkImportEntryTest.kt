package com.lateapex.collie.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.domain.CollieOrigin
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.domain.Scope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Activity-entry coverage for the confirmed opaque-link handoff. */
@RunWith(RobolectricTestRunner::class)
class LinkImportEntryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val app get() = context.applicationContext as CollieApplication
    private val origin = CollieOrigin("https://collie.example/completion/")
    private val pairingUrl = "https://collie.example/completion/settings?pair=ABCD-EF23"
    private val navigationUrl = "https://collie.example/completion/pane/w1%3Ap2?h=peer&s=work"

    @Before
    fun clearOwner() {
        app.container.connectionStore.clear()
        awaitStoreConnection(null)
    }

    @After
    fun clearOwnerAfterTest() {
        app.container.connectionStore.clear()
    }

    @Test
    fun confirmedNavigationOpensExactNativePaneAndItsOpaqueGrantIsOneUse() {
        val connection = seedConnection(paired = true)
        val committedBefore = app.container.connectionStore.captureCommitted()
        val owner = requireNotNull(LinkImportOwner.capture(app))
        val candidate = requireNotNull(LinkImportCoordinator.parse(navigationUrl, connection))
            as LinkImportCoordinator.Candidate.Navigation
        val id = LinkImportHandoff.putConfirmed(LinkImportHandoff.Prepared(candidate, owner))
        val incoming = Intent(context, MainActivity::class.java).putExtra(LinkImportHandoff.EXTRA_ID, id)

        val controller = Robolectric.buildActivity(MainActivity::class.java, incoming).create()
        val activity = controller.get()
        assertFalse("Main consumes the opaque confirmation extra", activity.intent.hasExtra(LinkImportHandoff.EXTRA_ID))
        assertNull("The imported navigation URL is never left on the Activity intent", activity.intent.data)
        val paneIntent = shadowOf(activity).nextStartedActivity
        assertEquals(PaneActivity::class.java.name, paneIntent.component?.className)
        assertEquals("w1:p2", paneIntent.getStringExtra(PaneActivity.EXTRA_PANE_ID))
        assertEquals("peer", paneIntent.getStringExtra(PaneActivity.EXTRA_HOST))
        assertEquals("work", paneIntent.getStringExtra(PaneActivity.EXTRA_SESSION))
        assertNull("Native pane intent carries no raw link URI", paneIntent.data)
        assertFalse("Native pane intent carries no imported URL string", paneIntent.extras?.toString()?.contains(navigationUrl) == true)
        assertSame("Navigation preserves the exact installed pairing", connection, app.container.connectionStore.connection.value)
        assertEquals("Navigation leaves durable connection custody unchanged", committedBefore, app.container.connectionStore.captureCommitted())

        controller.newIntent(Intent(context, MainActivity::class.java).putExtra(LinkImportHandoff.EXTRA_ID, id))
        assertNull("Replaying a consumed grant cannot start a second navigation", shadowOf(activity).nextStartedActivity)
        assertNull("Replayed opaque ID has no private URL fallback", activity.intent.data)
        assertSame(connection, app.container.connectionStore.connection.value)
    }

    @Test
    fun confirmedNavigationOwnerCaptureWorksWithAnActivePaneAndConsumesBeforeNavigationRetiresIt() {
        val connection = seedConnection(paired = true)
        val repository = app.container.repository
        repository.selectSubject(Scope(), "w1:p1")
        val activePaneActor = requireNotNull(repository.readContext())
        assertEquals("The fixture starts with an active pane subject", "w1:p1", activePaneActor.key.subjectIdentity)

        // Owner capture intentionally uses the global read actor (Scope(), null); an active
        // subject must not make an otherwise current pairing owner impossible to capture.
        val owner = requireNotNull(LinkImportOwner.capture(app))
        assertTrue("The owner remains current while a pane is selected", owner.current(app))
        assertTrue("The pane actor itself is current before handoff", repository.isCurrent(activePaneActor))
        val candidate = requireNotNull(LinkImportCoordinator.parse(navigationUrl, connection))
            as LinkImportCoordinator.Candidate.Navigation
        val id = LinkImportHandoff.putConfirmed(LinkImportHandoff.Prepared(candidate, owner))
        val activity = Robolectric.buildActivity(
            MainActivity::class.java,
            Intent(context, MainActivity::class.java).putExtra(LinkImportHandoff.EXTRA_ID, id),
        ).create().get()

        val paneIntent = shadowOf(activity).nextStartedActivity
        assertEquals("The confirmed grant is consumed before native navigation changes the actor",
            PaneActivity::class.java.name, paneIntent.component?.className)
        assertEquals("The exact imported pane is opened", "w1:p2", paneIntent.getStringExtra(PaneActivity.EXTRA_PANE_ID))
        assertEquals("The target host is carried as structured native routing", "peer", paneIntent.getStringExtra(PaneActivity.EXTRA_HOST))
        assertEquals("The target session is carried as structured native routing", "work", paneIntent.getStringExtra(PaneActivity.EXTRA_SESSION))
        assertSame("The connection owner survives navigation", connection, app.container.connectionStore.connection.value)
    }

    @Test
    fun previewNavigationCannotOpenMainOrNavigateBeforeContinue() {
        val connection = seedConnection(paired = true)
        val owner = requireNotNull(LinkImportOwner.capture(app))
        val candidate = requireNotNull(LinkImportCoordinator.parse(navigationUrl, connection))
        val previewId = LinkImportHandoff.putPreview(LinkImportHandoff.Prepared(candidate, owner))
        val previewController = Robolectric.buildActivity(
            LinkImportActivity::class.java,
            LinkImportActivity.intent(app).putExtra(LinkImportHandoff.EXTRA_ID, previewId),
        ).create().start().resume()
        val preview = previewController.get()
        assertTrue("A parsed navigation preview requires the explicit Continue action", preview.findViewById<View>(R.id.link_import_continue).isEnabled)
        assertNull("Rendering a preview does not navigate", shadowOf(preview).nextStartedActivity)

        // A distinct, still-pending preview capability presented directly to Main still lacks
        // confirmed authority; this exercises the handoff's grant type rather than replay alone.
        val mainPreviewId = LinkImportHandoff.putPreview(LinkImportHandoff.Prepared(candidate, owner))
        val hostileMain = Robolectric.buildActivity(
            MainActivity::class.java,
            Intent(context, MainActivity::class.java).putExtra(LinkImportHandoff.EXTRA_ID, mainPreviewId),
        ).create().get()
        assertNull("Main refuses a preview-only grant", shadowOf(hostileMain).nextStartedActivity)
        assertFalse("Main consumes and removes the rejected opaque ID", hostileMain.intent.hasExtra(LinkImportHandoff.EXTRA_ID))
        assertNull("Rejected preview has no raw URI to fall back to", hostileMain.intent.data)
        assertSame("Rejected preview cannot replace the configured owner", connection, app.container.connectionStore.connection.value)
        previewController.pause().stop().destroy()
    }

    @Test
    fun unconfiguredPairingContinuePrefillsMainWithoutPersistingOrSubmittingRawLink() {
        assertNull("Test begins without a configured connection", app.container.connectionStore.connection.value)
        val committedBefore = app.container.connectionStore.captureCommitted()
        val owner = requireNotNull(LinkImportOwner.capture(app))
        val candidate = requireNotNull(LinkImportCoordinator.parse(pairingUrl, null))
            as LinkImportCoordinator.Candidate.Pairing
        val previewId = LinkImportHandoff.putPreview(LinkImportHandoff.Prepared(candidate, owner))
        val previewController = Robolectric.buildActivity(
            LinkImportActivity::class.java,
            LinkImportActivity.intent(app).putExtra(LinkImportHandoff.EXTRA_ID, previewId),
        ).create().start().resume()
        val preview = previewController.get()
        assertNull("Pairing code is not submitted from the local preview", app.container.connectionStore.connection.value)
        assertTrue("Explicit Continue is available only after preview confirmation", preview.findViewById<View>(R.id.link_import_continue).isEnabled)
        assertTrue("Pairing continues through the actual preview button", preview.findViewById<View>(R.id.link_import_continue).performClick())
        val mainIntent = shadowOf(preview).nextStartedActivity
        assertEquals(MainActivity::class.java.name, mainIntent.component?.className)
        assertEquals("Only the opaque handoff capability crosses into Main", setOf(LinkImportHandoff.EXTRA_ID), mainIntent.extras?.keySet())
        assertNull("Pairing handoff includes no raw URL", mainIntent.data)
        assertFalse("Pairing handoff extras do not contain the one-time code", mainIntent.extras.toString().contains(candidate.code))

        val mainController = Robolectric.buildActivity(MainActivity::class.java, mainIntent).create()
        val main = mainController.get()
        assertEquals("Unconfigured Main receives the exact official origin as a prefill", origin.value,
            main.findViewById<EditText>(R.id.origin_input).text.toString())
        val code = main.findViewById<EditText>(R.id.pairing_code_input)
        assertEquals("Unconfigured Main receives the normalized one-time code as a prefill", candidate.code, code.text.toString())
        assertFalse("Pairing prefill is not eligible for Android view-state persistence", code.isSaveEnabled)
        assertNull("Prefill never saves a connection automatically", app.container.connectionStore.connection.value)
        assertNull("Main consumes the opaque handoff instead of retaining the raw URI", main.intent.data)
        assertNull("Main retains no clip-data copy of the imported URL", main.intent.clipData)
        assertFalse("Main removes the handoff capability after consuming it", main.intent.hasExtra(LinkImportHandoff.EXTRA_ID))
        assertFalse("Main has no raw pairing URL extra", main.intent.extras?.toString()?.contains(pairingUrl) == true)
        assertNull("Prefill does not auto-navigate or auto-submit", shadowOf(main).nextStartedActivity)

        main.findViewById<EditText>(R.id.origin_input).setText("https://other.example/completion/")
        assertEquals("Changing the imported origin immediately clears its one-time code", "", code.text.toString())
        assertNull("Editing the imported origin does not configure a connection", app.container.connectionStore.connection.value)

        val saved = Bundle()
        mainController.saveInstanceState(saved)
        assertFalse("Saved view state contains no one-time pairing code", saved.toString().contains(candidate.code))
        assertNull("Saving the prefill does not persist a pairing", app.container.connectionStore.connection.value)
        assertEquals("Prefill and state saving leave durable connection custody unchanged", committedBefore,
            app.container.connectionStore.captureCommitted())
        previewController.pause().stop().destroy()
        mainController.pause().stop().destroy()
    }

    @Test
    fun retiringTheImportedEmptyOwnerClearsItsPrefilledPairingCode() {
        val owner = requireNotNull(LinkImportOwner.capture(app))
        val candidate = requireNotNull(LinkImportCoordinator.parse(pairingUrl, null))
            as LinkImportCoordinator.Candidate.Pairing
        val id = LinkImportHandoff.putConfirmed(LinkImportHandoff.Prepared(candidate, owner))
        val mainController = Robolectric.buildActivity(
            MainActivity::class.java,
            Intent(context, MainActivity::class.java).putExtra(LinkImportHandoff.EXTRA_ID, id),
        ).create()
        val main = mainController.get()
        val code = main.findViewById<EditText>(R.id.pairing_code_input)
        assertEquals("The confirmed import is initially displayed", candidate.code, code.text.toString())

        // Simulate a distinct committed owner arriving while this screen still holds the
        // empty-profile grant. This fixture write retires the grant; Main must purge the code.
        val replacement = Connection(origin, "Replacement fixture", null)
        saveFixtureConnection(app, replacement)
        val deadline = System.nanoTime() + 1_000_000_000L
        while (code.text.isNotEmpty() && System.nanoTime() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.yield()
        }
        assertEquals("Retiring the imported owner clears the sensitive prefill", "", code.text.toString())
        assertSame("Clearing the prefill does not replace or mutate the new owner", replacement,
            app.container.connectionStore.connection.value)
        assertNull("Retirement does not trigger an automatic pair or navigation", shadowOf(main).nextStartedActivity)
        mainController.pause().stop().destroy()
    }

    @Test
    fun sameOriginConfirmedPairingUsesOpaqueSettingsHandoffAndOnlyPrefills() {
        val connection = seedConnection(paired = false)
        val committedBefore = app.container.connectionStore.captureCommitted()
        val owner = requireNotNull(LinkImportOwner.capture(app))
        val candidate = requireNotNull(LinkImportCoordinator.parse(pairingUrl, connection))
            as LinkImportCoordinator.Candidate.Pairing
        assertEquals("Pairing import is bound to the exact already-configured origin", origin, candidate.origin)
        val previewId = LinkImportHandoff.putPreview(LinkImportHandoff.Prepared(candidate, owner))
        val previewController = Robolectric.buildActivity(
            LinkImportActivity::class.java,
            LinkImportActivity.intent(app).putExtra(LinkImportHandoff.EXTRA_ID, previewId),
        ).create().start().resume()
        val preview = previewController.get()
        assertTrue("Same-origin pairing still requires explicit Continue", preview.findViewById<View>(R.id.link_import_continue).performClick())
        val mainIntent = shadowOf(preview).nextStartedActivity
        assertEquals(MainActivity::class.java.name, mainIntent.component?.className)

        val main = Robolectric.buildActivity(MainActivity::class.java, mainIntent).create().get()
        val settingsIntent = shadowOf(main).nextStartedActivity
        assertEquals(SettingsActivity::class.java.name, settingsIntent.component?.className)
        assertEquals("Paired-owner route sends only confirmation capability plus focus request",
            setOf(LinkImportHandoff.EXTRA_ID, SettingsActivity.EXTRA_FOCUS_DEVICES), settingsIntent.extras?.keySet())
        assertTrue(settingsIntent.getBooleanExtra(SettingsActivity.EXTRA_FOCUS_DEVICES, false))
        assertFalse("Settings intent never receives the raw one-time code", settingsIntent.extras.toString().contains(candidate.code))
        assertNull("Settings intent never receives the raw link URI", settingsIntent.data)

        val settingsController = Robolectric.buildActivity(SettingsActivity::class.java, settingsIntent).create()
        val settings = settingsController.get()
        assertTrue("Settings consumes its opaque import capability",
            !settings.intent.hasExtra(LinkImportHandoff.EXTRA_ID) &&
                !settings.intent.hasExtra(SettingsActivity.EXTRA_PAIR_CODE) &&
                !settings.intent.hasExtra(SettingsActivity.EXTRA_FOCUS_DEVICES))
        val code = settings.findViewById<EditText>(R.id.settings_pair_code)
        assertEquals("Same-origin confirmed code prefills the native Settings pairing form", candidate.code, code.text.toString())
        assertFalse("One-time pairing code is excluded from view-state saving", code.isSaveEnabled)
        assertTrue("Prefilled pairing does not automatically submit", settings.findViewById<View>(R.id.settings_pair_button).isEnabled)
        assertSame("Settings prefill leaves the original read-only connection unchanged", connection,
            app.container.connectionStore.connection.value)
        assertTrue("Settings intent has no remaining imported payload", settings.intent.extras?.keySet()?.isEmpty() != false)
        assertNull("Prefilling Settings never auto-navigates to a pairing result", shadowOf(settings).nextStartedActivity)
        val saved = Bundle()
        settingsController.saveInstanceState(saved)
        assertFalse("Settings saved state contains no one-time pairing code", saved.toString().contains(candidate.code))
        assertSame("Saving the Settings prefill does not pair or replace the owner", connection,
            app.container.connectionStore.connection.value)
        assertEquals("Settings prefill and state saving leave durable connection custody unchanged", committedBefore,
            app.container.connectionStore.captureCommitted())

        previewController.pause().stop().destroy()
        settingsController.pause().stop().destroy()
    }

    private fun seedConnection(paired: Boolean): Connection {
        val connection = Connection(origin, "Link-import fixture", if (paired) "fixture-token" else null)
        saveFixtureConnection(app, connection)
        assertSame(connection, app.container.connectionStore.connection.value)
        return connection
    }

    private fun awaitStoreConnection(expected: Connection?) {
        val deadline = System.nanoTime() + 1_000_000_000L
        while (app.container.connectionStore.connection.value !== expected && System.nanoTime() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.yield()
        }
        assertSame(expected, app.container.connectionStore.connection.value)
    }
}
