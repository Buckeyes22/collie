package com.lateapex.collie.ui

import android.content.Intent
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.domain.CollieOrigin
import com.lateapex.collie.domain.Connection
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.withContext

@RunWith(RobolectricTestRunner::class)
class LinkImportTest {
    private val app = ApplicationProvider.getApplicationContext<CollieApplication>()
    private val origin = CollieOrigin("https://collie.example/completion/")
    private val pairUrl = "https://collie.example/completion/settings?pair=ABCD-EF23"

    @Before fun clearConnection() {
        app.container.connectionStore.clear()
        awaitPublication(null)
    }

    @Test fun pairedPaneAndHistoryRequireExactOriginMountAndBoundedTargets() {
        val connection = Connection(origin, "fixture", "fixture-token")
        val valid = LinkImportCoordinator.parse("https://collie.example/completion/pane/w1%3Ap2/history?h=peer&s=work", connection)
        assertTrue(valid is LinkImportCoordinator.Candidate.Navigation)
        assertEquals(CollieDeepLink.Pane("w1:p2", com.lateapex.collie.domain.Scope("peer", "work"), true),
            (valid as LinkImportCoordinator.Candidate.Navigation).target)
        for (raw in listOf(
            "http://collie.example/completion/pane/w1:p2", "https://other.example/completion/pane/w1:p2",
            "https://collie.example/pane/w1:p2", "https://user:password@collie.example/completion/pane/w1:p2",
            "https://collie.example/completion/pane/w1:p2?h=one&h=two", "https://collie.example/completion/pane/w1:p2?token=secret",
            "https://collie.example/completion/../pane/w1:p2", "https://collie.example/completion/pane/%2e%2e",
            "https://collie.example/completion/pane/w1%2fp2", "https://collie.example/completion/pane/w1:p2#private",
        )) assertNull(LinkImportCoordinator.parse(raw, connection))
        assertNull(LinkImportCoordinator.parse("https://collie.example/completion/pane/w1:p2", null))
        assertNull(LinkImportCoordinator.parse("https://collie.example/completion/pane/w1:p2", Connection(origin, "fixture", null)))
    }

    @Test fun pairingPreviewInfersOnlyOfficialSettingsMountAndCannotReplaceConfiguredOwner() {
        val candidate = LinkImportCoordinator.parse(pairUrl, null) as LinkImportCoordinator.Candidate.Pairing
        assertEquals(origin, candidate.origin)
        assertEquals("ABCD-EF23", candidate.code)
        assertFalse(candidate.toString().contains(candidate.code))
        assertNull(LinkImportCoordinator.parse(pairUrl, Connection(CollieOrigin("https://other.example/"), "other", null)))
        assertTrue(LinkImportCoordinator.parse(pairUrl + "#paired-devices", null) is LinkImportCoordinator.Candidate.Pairing)
        for (raw in listOf(pairUrl + "&pair=ABCD-EF23", pairUrl + "&h=peer", pairUrl + "#private",
            pairUrl.replace("ABCD-EF23", "short"), pairUrl.replace("/settings", "/pane/w1:p1"), "com.lateapex.collie:/oauth2redirect?code=private")) {
            assertNull(LinkImportCoordinator.parse(raw, null))
        }
    }

    @Test fun shareDispatcherRoutesOnlyOpaquePreviewAndConsumesPrivateExternalFields() {
        val input = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, pairUrl)
        val controller = Robolectric.buildActivity(OpenLinkActivity::class.java, input).create()
        val activity = controller.get()
        val next = shadowOf(activity).nextStartedActivity
        assertEquals(LinkImportActivity::class.java.name, next.component!!.className)
        assertNull(next.data)
        assertEquals(setOf(LinkImportHandoff.EXTRA_ID), next.extras!!.keySet())
        assertFalse(next.getStringExtra(LinkImportHandoff.EXTRA_ID)!!.contains("ABCD"))
        assertNull(activity.intent.data); assertNull(activity.intent.clipData)
        assertFalse(activity.intent.hasExtra(Intent.EXTRA_TEXT))
        // A preview capability cannot bypass the user's Continue button.
        assertNull(LinkImportHandoff.consumeConfirmed(next, app))
        controller.destroy()
    }

    @Test fun actualPreviewDoesNotPairAndContinuesOnlyThroughOneShotOpaqueGrant() {
        val before = app.container.connectionStore.captureCommitted()
        val owner = requireNotNull(LinkImportOwner.capture(app))
        val candidate = requireNotNull(LinkImportCoordinator.parse(pairUrl, null))
        val id = LinkImportHandoff.putPreview(LinkImportHandoff.Prepared(candidate, owner))
        val controller = Robolectric.buildActivity(LinkImportActivity::class.java, LinkImportActivity.intent(app).putExtra(LinkImportHandoff.EXTRA_ID, id))
            .create().start().resume()
        val activity = controller.get()
        assertEquals(before, app.container.connectionStore.captureCommitted())
        assertNull(shadowOf(activity).nextStartedActivity)
        assertFalse(activity.findViewById<TextView>(R.id.link_import_status).text.contains("ABCD-EF23"))
        assertEquals("", activity.findViewById<EditText>(R.id.link_import_input).text.toString())
        assertTrue(activity.findViewById<android.view.View>(R.id.link_import_continue).performClick())
        val next = shadowOf(activity).nextStartedActivity
        assertEquals(MainActivity::class.java.name, next.component!!.className)
        assertEquals(setOf(LinkImportHandoff.EXTRA_ID), next.extras!!.keySet())
        val admitted = requireNotNull(LinkImportHandoff.consumeConfirmed(next, app))
        assertEquals("ABCD-EF23", (admitted.candidate as LinkImportCoordinator.Candidate.Pairing).code)
        assertNull(LinkImportHandoff.consumeConfirmed(next, app))
        assertEquals(before, app.container.connectionStore.captureCommitted())
        controller.pause().stop().destroy()
    }

    @Test fun originalEmptyProfileIncarnationCannotAuthorizeAnImportAfterClearOrTimeout() {
        val owner = requireNotNull(LinkImportOwner.capture(app))
        val candidate = requireNotNull(LinkImportCoordinator.parse(pairUrl, null))
        val id = LinkImportHandoff.putConfirmed(LinkImportHandoff.Prepared(candidate, owner))
        app.container.connectionStore.clear() // null -> null still changes the durable revision.
        assertFalse(owner.current(app))
        assertNull(LinkImportHandoff.consumeConfirmed(Intent().putExtra(LinkImportHandoff.EXTRA_ID, id), app))
        val fresh = requireNotNull(LinkImportOwner.capture(app))
        val expired = LinkImportHandoff.putConfirmed(LinkImportHandoff.Prepared(candidate, fresh))
        org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofSeconds(61))
        assertNull(LinkImportHandoff.consumeConfirmed(Intent().putExtra(LinkImportHandoff.EXTRA_ID, expired), app))
    }

    @Test fun delayedPublicationCannotMisclassifyCommittedConfiguredOrClearedOwner() {
        val store = app.container.connectionStore as com.lateapex.collie.data.EncryptedConnectionStore
        val configured = Connection(origin, "owned fixture", null)
        for (publishConnection in listOf(true, false)) {
            val previous = requireNotNull(LinkImportOwner.capture(app))
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            store.beforePublicationForTesting = {
                entered.countDown()
                check(release.await(2, TimeUnit.SECONDS))
            }
            try {
                if (publishConnection) saveFixtureConnection(app, configured) else store.clear()
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                val expected = if (publishConnection) configured else null
                assertTrue(store.captureCommitted().connection === expected)
                // Notification collectors are delayed; value/replayCache intentionally remain
                // authoritative committed reads even while the publisher is held.
                assertTrue(store.connection.value === expected)
                assertTrue(store.connection.replayCache.single() === expected)
                assertFalse("The original import owner must retire before notification publication", previous.current(app))
                val fresh = requireNotNull(LinkImportOwner.capture(app))
                assertTrue(fresh.connection === expected)
                assertTrue(fresh.current(app))
                if (publishConnection) assertNull(LinkImportCoordinator.parse(
                    "https://other.example/settings?pair=ABCD-EF23", fresh.connection))
            } finally { store.beforePublicationForTesting = {}; release.countDown() }
            awaitPublication(if (publishConnection) configured else null)
        }
    }

    @Test fun editingOrStoppingActualPreviewClearsItsAdmissionAndNeverSavesThePairingUri() {
        val controller = Robolectric.buildActivity(LinkImportActivity::class.java).create().start().resume()
        val activity = controller.get()
        val field = activity.findViewById<EditText>(R.id.link_import_input)
        val review = activity.findViewById<android.view.View>(R.id.link_import_review)
        val proceed = activity.findViewById<android.view.View>(R.id.link_import_continue)
        field.setText(pairUrl); review.performClick(); assertTrue(proceed.isEnabled)
        field.setText("https://other.example/"); assertFalse(proceed.isEnabled)
        field.setText(pairUrl); review.performClick(); assertTrue(proceed.isEnabled)
        val saved = Bundle(); controller.saveInstanceState(saved)
        assertFalse(saved.toString().contains("ABCD-EF23"))
        controller.pause().stop()
        assertFalse(proceed.isEnabled); assertEquals("", field.text.toString())
        assertNull(shadowOf(activity).nextStartedActivity)
        controller.destroy()
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun actualQrImageDecodesOfficialPairingUrlAndOversizedBytesAreRefused() {
        val matrix = QRCodeWriter().encode(pairUrl, BarcodeFormat.QR_CODE, 256, 256)
        val pixels = IntArray(256 * 256) { index -> if (matrix[index % 256, index / 256]) Color.BLACK else Color.WHITE }
        val bitmap = Bitmap.createBitmap(pixels, 256, 256, Bitmap.Config.ARGB_8888)
        val bytes = ByteArrayOutputStream().also { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
        bitmap.recycle()
        val decoded = LinkImportQr.decode(bytes)
        assertEquals(pairUrl, decoded)
        assertTrue(LinkImportCoordinator.parse(decoded, null) is LinkImportCoordinator.Candidate.Pairing)
        assertThrows(IllegalArgumentException::class.java) { LinkImportQr.decode(ByteArray(LinkImportQr.MAX_IMAGE_BYTES + 1)) }
        assertThrows(IllegalArgumentException::class.java) { LinkImportQr.decode(byteArrayOf(1, 2, 3)) }
    }

    @Test fun malformedCallbackNeverFallsThroughToImportEvenWithValidSharedPairingText() {
        val controller = Robolectric.buildActivity(OpenLinkActivity::class.java,
            Intent(Intent.ACTION_SEND).setDataAndType(Uri.parse("com.lateapex.collie:/oauth2redirect?code=private"), "text/plain").putExtra(Intent.EXTRA_TEXT, pairUrl)).create()
        assertNull(shadowOf(controller.get()).nextStartedActivity)
        assertNull(controller.get().intent.data)
        assertFalse(controller.get().intent.hasExtra(Intent.EXTRA_TEXT))
        controller.destroy()
    }

    @Test fun zeroProgressQrProviderIsClosedAndRefusedWithoutSpinning() {
        var reads = 0
        var closed = false
        val stream = object : InputStream() {
            override fun read(): Int = 0
            override fun read(buffer: ByteArray, offset: Int, count: Int): Int { ++reads; return 0 }
            override fun close() { closed = true }
        }
        assertThrows(IOException::class.java) { LinkImportQr.readProvider { stream } }
        assertEquals(33, reads)
        assertTrue(closed)
    }

    @Test fun blockedQrProviderTimesOutCancelsAndRetainsItsSharedSlotUntilCleanupFinishes() {
        val initial = DocumentAttachment.availableProviderSlots()
        assertTrue(initial > 0)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        var control: DocumentAttachment.ProviderReadControl? = null
        fun awaitRelease() {
            while (release.count > 0) {
                try { release.await() } catch (_: InterruptedException) { /* Deliberately hostile provider. */ }
            }
        }
        val stream = object : InputStream() {
            override fun read(): Int { awaitRelease(); return -1 }
            override fun read(buffer: ByteArray, offset: Int, count: Int): Int = read()
            override fun close() { closed.countDown(); awaitRelease() }
        }
        try {
            val started = System.nanoTime()
            assertThrows(IOException::class.java) { LinkImportQr.readProvider(timeoutMs = 100) { original -> control = original; stream } }
            assertTrue("The caller must return within a bounded provider deadline", System.nanoTime() - started < 2_000_000_000L)
            assertTrue(closed.await(1, TimeUnit.SECONDS))
            assertTrue(requireNotNull(control).signal.isCanceled)
            assertEquals("Noncooperating close/read must retain its original shared worker slot", initial - 1, DocumentAttachment.availableProviderSlots())
        } finally { release.countDown() }
        val deadline = System.nanoTime() + 2_000_000_000L
        while (DocumentAttachment.availableProviderSlots() < initial && System.nanoTime() < deadline) Thread.yield()
        assertEquals(initial, DocumentAttachment.availableProviderSlots())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun heldQrResultCannotPublishAfterStopOrEmptyOwnerReplacement() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            for (stop in listOf(true, false)) {
                val controller = Robolectric.buildActivity(LinkImportActivity::class.java).create().start().resume()
                val activity = controller.get()
                val held = CompletableDeferred<String>()
                var entered = false
                activity.qrDecoderForTests = { entered = true; withContext(NonCancellable) { held.await() } }
                assertTrue(activity.findViewById<android.view.View>(R.id.link_import_qr).performClick())
                val request = requireNotNull(shadowOf(activity).nextStartedActivityForResult)
                assertEquals(Intent.ACTION_OPEN_DOCUMENT, shadowOf(activity).nextStartedActivity.action)
                assertTrue(activity.activityResultRegistry.dispatchResult(request.requestCode, Activity.RESULT_OK,
                    Intent().setData(Uri.parse("content://owned-qr/fixture.png"))))
                runCurrent(); assertTrue("The original QR decoder must actually be held", entered)
                if (stop) controller.pause().stop() else app.container.connectionStore.clear()
                held.complete(pairUrl); runCurrent()
                assertFalse(activity.findViewById<android.view.View>(R.id.link_import_continue).isEnabled)
                assertEquals("", activity.findViewById<EditText>(R.id.link_import_input).text.toString())
                assertFalse(activity.findViewById<TextView>(R.id.link_import_status).text.contains("ABCD-EF23"))
                assertNull(shadowOf(activity).nextStartedActivity)
                if (stop) { controller.start().resume(); runCurrent(); assertFalse(activity.findViewById<android.view.View>(R.id.link_import_continue).isEnabled) }
                controller.pause().stop().destroy()
            }
        } finally { Dispatchers.resetMain() }
    }

    private fun awaitPublication(connection: Connection?) {
        val deadline = System.nanoTime() + 1_000_000_000L
        while (app.container.connectionStore.connection.value !== connection && System.nanoTime() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle(); Thread.yield()
        }
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertTrue(app.container.connectionStore.connection.value === connection)
    }
}
