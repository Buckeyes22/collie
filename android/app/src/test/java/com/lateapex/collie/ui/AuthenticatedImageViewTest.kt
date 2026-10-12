package com.lateapex.collie.ui

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import com.lateapex.collie.data.CollieRepository
import com.lateapex.collie.data.ConnectionStore
import com.lateapex.collie.data.ScopedImageLoader
import com.lateapex.collie.domain.CollieOrigin
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.ApiFailure
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.CollieApi
import com.lateapex.collie.network.DevicesResponse
import com.lateapex.collie.network.ImageBlob
import com.lateapex.collie.network.OriginValidator
import com.lateapex.collie.network.TranscriptEntry
import com.lateapex.collie.network.TranscriptPart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AuthenticatedImageViewTest {
    @Test fun unchangedLiveTurnRecreatesItsImageCardAfterNavigationOnlyRetirement() {
        val png = ByteArrayOutputStream().also {
            Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        val reference = "data:image/png;base64," + Base64.encodeToString(png, Base64.NO_WRAP)
        val connection = Connection(CollieOrigin("https://collie.example/mount/"), "phone", "private")
        val repository = CollieRepository(EmptyApi(), Store(connection), OriginValidator())
        val scope = Scope("peer", "work")
        repository.selectSubject(scope, "pane")
        val loader = ScopedImageLoader(repository)
        val firstContext = repository.readContext(scope, "pane")!!
        val entry = TranscriptEntry(
            "same-turn", "2026-01-02T00:00:00Z", "assistant",
            listOf(TranscriptPart(kind = "image", url = reference, mimeType = "image/png")),
        )
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val body = TranscriptBodyView(activity)
        activity.addContentView(body, ViewGroup.LayoutParams(480, 640))
        body.bind("agent", listOf(entry), hasMore = false, imageLoader = loader, readContext = firstContext)
        val firstCard = descendants(body.turnViewForTest("same-turn")!!).filterIsInstance<AuthenticatedImageView>().single()
        awaitVisibleImage(firstCard)

        repository.showSavedCopy(123L)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val freshContext = repository.readContext(scope, "pane")!!
        assertEquals(firstContext.key, freshContext.key)
        assertTrue("navigation retirement advances the render actor", freshContext.navigationGeneration > firstContext.navigationGeneration)

        body.bind("agent", listOf(entry), hasMore = false, imageLoader = loader, readContext = freshContext)
        val restoredCard = descendants(body.turnViewForTest("same-turn")!!).filterIsInstance<AuthenticatedImageView>().single()
        assertTrue("unchanged turn UUID gets a new card for the newly admitted actor", firstCard !== restoredCard)
        awaitVisibleImage(restoredCard)

        loader.close()
        controller.close()
    }

    @Test fun cardAttachedAfterItsReadActorRetiresShowsUnavailableInsteadOfLoadingForever() {
        val connection = Connection(CollieOrigin("https://collie.example/mount/"), "phone", "private")
        val repository = CollieRepository(EmptyApi(), Store(connection), OriginValidator())
        val scope = Scope("peer", "work")
        repository.selectSubject(scope, "pane")
        val loader = ScopedImageLoader(repository)
        val expiredContext = repository.readContext(scope, "pane")!!
        repository.showSavedCopy(123L)

        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val staleCard = AuthenticatedImageView(activity, loader, expiredContext, "/api/blobs/${"ab".repeat(32)}", "journal image", "image/png")
        val contextlessCard = AuthenticatedImageView(activity, loader, null, null, "journal image", null)
        activity.addContentView(staleCard, ViewGroup.LayoutParams(480, 300))
        activity.addContentView(contextlessCard, ViewGroup.LayoutParams(480, 300))
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        val staleStatus = staleCard.getChildAt(1) as android.widget.TextView
        val contextlessStatus = contextlessCard.getChildAt(1) as android.widget.TextView
        assertEquals(View.VISIBLE, staleStatus.visibility)
        assertTrue(staleStatus.text.toString().contains("access changed", ignoreCase = true))
        assertEquals(View.VISIBLE, contextlessStatus.visibility)
        assertTrue(
            contextlessStatus.text.toString().contains("unavailable", ignoreCase = true) ||
                contextlessStatus.text.toString().contains("access changed", ignoreCase = true),
        )
        assertFalse(staleCard.isClickable)
        assertFalse(contextlessCard.isClickable)

        loader.close()
        controller.close()
    }

    @Test fun authorityRetirementClearsVisibleCardAndDismissesOpenViewer() {
        val bytes = ByteArrayOutputStream().also {
            Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        val reference = "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        val connection = Connection(CollieOrigin("https://collie.example/mount/"), "phone", "private")
        val repository = CollieRepository(EmptyApi(), Store(connection), OriginValidator())
        val scope = Scope("peer", "work")
        repository.selectSubject(scope, "pane")
        val loader = ScopedImageLoader(repository)
        val readContext = repository.readContext(scope, "pane")!!
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val card = AuthenticatedImageView(activity, loader, readContext, reference, "journal image", "image/png")
        activity.addContentView(card, ViewGroup.LayoutParams(480, 300))

        val image = card.getChildAt(0) as ImageView
        val deadline = System.currentTimeMillis() + 5_000
        while (image.drawable == null && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertNotNull("fixture bitmap should be visible before retirement", image.drawable)
        val visibleBitmap = (image.drawable as BitmapDrawable).bitmap
        assertFalse("visible card bitmap is live", visibleBitmap.isRecycled)
        card.performClick()
        val dialog = ShadowDialog.getLatestDialog()
        assertNotNull("viewer should open from a current image card", dialog)
        assertTrue(dialog!!.isShowing)

        loader.retire()
        // Drain ImageViewer's posted initial fit task after retirement. It must not read
        // dimensions from a bitmap whose last lease was released and recycled.
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        assertNull("retirement must synchronously clear the visible thumbnail", image.drawable)
        assertFalse("retired image card must not remain clickable", card.isClickable)
        assertFalse("retirement must dismiss the viewer", dialog.isShowing)
        assertEquals("retirement clears both card and viewer leases", 0L, loader.residentBytesForTest())
        assertTrue("card and viewer drawables are cleared before the last bitmap lease recycles", visibleBitmap.isRecycled)
        loader.close()
        controller.close()
    }

    @Test fun detachingCardDuringRotationDismissesViewerAndReleasesItsLease() {
        val bytes = ByteArrayOutputStream().also {
            Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        val reference = "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        val connection = Connection(CollieOrigin("https://collie.example/mount/"), "phone", "private")
        val repository = CollieRepository(EmptyApi(), Store(connection), OriginValidator())
        val scope = Scope("peer", "work")
        repository.selectSubject(scope, "pane")
        val loader = ScopedImageLoader(repository)
        val context = repository.readContext(scope, "pane")!!
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val card = AuthenticatedImageView(activity, loader, context, reference, "journal image", "image/png")
        activity.addContentView(card, ViewGroup.LayoutParams(480, 300))
        val image = card.getChildAt(0) as ImageView
        val deadline = System.currentTimeMillis() + 5_000
        while (image.drawable == null && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertNotNull("fixture bitmap should be visible before opening the viewer", image.drawable)
        val bitmap = (image.drawable as BitmapDrawable).bitmap
        card.performClick()
        val dialog = ShadowDialog.getLatestDialog()!!
        assertTrue(dialog.isShowing)

        (card.parent as ViewGroup).removeView(card)
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        assertFalse("rotation detaches the owner and dismisses its fullscreen viewer", dialog.isShowing)
        assertNull("detached card releases its drawable", image.drawable)
        assertFalse("the now-unpinned bitmap may remain in the bounded cache", bitmap.isRecycled)
        assertTrue("the detached dialog released its lease back to cache", loader.residentBytesForTest() > 0)
        loader.retire()
        assertTrue("retiring the cache recycles after card and viewer drawables were cleared", bitmap.isRecycled)
        assertEquals(0L, loader.residentBytesForTest())
        loader.close()
        controller.close()
    }

    @Test fun detachedRecycledCardCannotReceiveAQueuedImageResult() {
        val bytes = ByteArrayOutputStream().also {
            Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
        }.toByteArray()
        val reference = "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        val connection = Connection(CollieOrigin("https://collie.example/mount/"), "phone", "private")
        val repository = CollieRepository(EmptyApi(), Store(connection), OriginValidator())
        val scope = Scope("peer", "work")
        repository.selectSubject(scope, "pane")
        val loader = ScopedImageLoader(repository)
        val readContext = repository.readContext(scope, "pane")!!
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val card = AuthenticatedImageView(activity, loader, readContext, reference, "journal image", "image/png")
        activity.addContentView(card, ViewGroup.LayoutParams(480, 300))
        val image = card.getChildAt(0) as ImageView
        (card.parent as ViewGroup).removeView(card)

        val deadline = System.currentTimeMillis() + 1_000
        while (loader.residentBytesForTest() > 0 && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()

        assertNull(image.drawable)
        loader.retire()
        settleRetiredWork(loader)
        assertEquals(
            "retirement drains canceled decode/delivery owners before resident accounting reaches zero " +
                "(committed=${loader.committedBitmapBytesForTest()}, pending=${loader.pendingDeliveriesForTest()})",
            0L,
            loader.residentBytesForTest(),
        )
        loader.close()
        controller.close()
    }

    private fun settleRetiredWork(loader: ScopedImageLoader) {
        val deadline = System.currentTimeMillis() + 5_000
        while ((loader.residentBytesForTest() > 0 || loader.pendingDeliveriesForTest() > 0) && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    private fun awaitVisibleImage(card: AuthenticatedImageView) {
        val image = card.getChildAt(0) as ImageView
        val deadline = System.currentTimeMillis() + 5_000
        while (image.drawable == null && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertNotNull("current image actor should render its raster", image.drawable)
    }

    private fun descendants(root: android.view.View): List<android.view.View> = if (root !is ViewGroup) listOf(root) else buildList {
        add(root)
        repeat(root.childCount) { addAll(descendants(root.getChildAt(it))) }
    }

    private class Store(initial: Connection) : ConnectionStore {
        private val mutable = MutableStateFlow<Connection?>(initial)
        override val connection: StateFlow<Connection?> = mutable
        override fun save(connection: Connection) { mutable.value = connection }
        override fun clear() { mutable.value = null }
    }

    private class EmptyApi : CollieApi {
        override suspend fun health(connection: Connection) = ApiResult.Failure(ApiFailure.Protocol("unused"))
        override suspend fun snapshot(connection: Connection, scope: Scope) = ApiResult.Failure(ApiFailure.Protocol("unused"))
        override suspend fun pane(connection: Connection, address: com.lateapex.collie.domain.PaneAddress, lines: Int, etag: String?, markSeen: Boolean) = ApiResult.Failure(ApiFailure.Protocol("unused"))
        override suspend fun refresh(connection: Connection, scope: Scope) = ApiResult.Failure(ApiFailure.Protocol("unused"))
        override suspend fun pair(origin: CollieOrigin, code: String, label: String) = ApiResult.Failure(ApiFailure.Protocol("unused"))
        override suspend fun devices(connection: Connection): ApiResult<DevicesResponse> = ApiResult.Failure(ApiFailure.Protocol("unused"))
        override suspend fun imageBlob(connection: Connection, scope: Scope, sha256: String): ApiResult<ImageBlob> = ApiResult.Failure(ApiFailure.Http(404, null, null))
        override suspend fun reply(connection: Connection, address: com.lateapex.collie.domain.PaneAddress, text: String, submit: Boolean, expectedPrompt: String?) = ApiResult.Failure(ApiFailure.Protocol("unused"))
        override suspend fun keys(connection: Connection, address: com.lateapex.collie.domain.PaneAddress, keys: List<String>, expectedPrompt: String?) = ApiResult.Failure(ApiFailure.Protocol("unused"))
    }
}
