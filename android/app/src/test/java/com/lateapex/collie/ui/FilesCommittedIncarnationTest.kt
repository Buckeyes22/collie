package com.lateapex.collie.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.data.*
import com.lateapex.collie.domain.*
import com.lateapex.collie.network.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Adds missing custody consumers without changing any original retirement test or deadline. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FilesCommittedIncarnationTest {
    @Test fun pausedPublisherSameObjectABAWipesRowsSourceAndHandoffWithoutFreshReads() {
        for (throughB in listOf(false, true)) Fixture().use { f ->
            val controller = f.activity()
            try {
                val a = controller.get()
                val list = a.findViewById<RecyclerView>(R.id.files_list)
                await { list.adapter!!.itemCount == 1 }
                val detached = bindRow(list).itemView as TextView
                val pooled = bindRow(list)
                list.recycledViewPool.putRecycledView(pooled)
                val actor = field(a, "acceptedPageContext") as ScopedReadContext
                val handoff = FilesReadHandoff.issue(f.repository, FilesTarget.Pane("pane"), Scope(), RelativeFilePath.parse("private.md")!!, actor)!!
                (field(a, "viewModel") as FilesViewModel).openFile("private.md")
                await { field(a, "currentSource") != null }
                val oldText = a.findViewById<ViewGroup>(R.id.files_source_content).children().filterIsInstance<TextView>().first { it.text.contains("Private A") }
                controller.pause().stop()
                val count = f.reads.get()
                pausedABA(f, throughB) {
                    assertTrue(f.repository.isActorRetired(actor))
                    assertFalse(f.repository.isCurrent(actor))
                    assertFalse(f.store.compareAndSet(actor.committedSnapshot!!, f.second))
                }
                await { detached.text.isEmpty() && (pooled.itemView as TextView).text.isEmpty() && oldText.text.isEmpty() }
                assertNull(detached.contentDescription)
                assertFalse(detached.hasOnClickListeners())
                assertNull(pooled.itemView.contentDescription)
                assertFalse(pooled.itemView.hasOnClickListeners())
                assertNull(FilesReadHandoff.take(handoff, f.repository, FilesTarget.Pane("pane"), Scope(), "private.md"))
                assertEquals(count, f.reads.get())
                assertTrue(f.repository.isActorRetired(actor))
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun pausedPublisherSameObjectABAReleasesNativeSvgGraphAndDrawTouchWithoutReads() {
        for (throughB in listOf(false, true)) Fixture().use { f ->
            val controller = f.activity("private.svg")
            try {
                val a = controller.get()
                await { field(a, "currentSource") != null }
                a.findViewById<View>(R.id.files_preview).performClick()
                val svg = a.findViewById<ViewGroup>(R.id.files_source_content).children().first { it is RetirableFilePreview }
                assertEquals(0xffff0000.toInt(), pixel(svg))
                a.findViewById<View>(R.id.files_source).performClick()
                controller.pause().stop()
                val count = f.reads.get()
                pausedABA(f, throughB) { }
                await { field(svg, "drawing") == null }
                assertEquals(0, pixel(svg))
                val motion = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, 2f, 2f, 0)
                try { assertFalse(svg.onTouchEvent(motion)) } finally { motion.recycle() }
                assertEquals(count, f.reads.get())
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun pausedPublisherSameObjectABAClearsCardViewerAndVersionedCacheWithoutReads() {
        for (throughB in listOf(false, true)) Fixture().use { f ->
            val actor = f.actor()
            val warmed = f.load(actor)
            val cached = warmed.bitmap
            warmed.close()
            val controller = f.activity("private.png")
            try {
                val a = controller.get()
                val content = a.findViewById<ViewGroup>(R.id.files_source_content)
                await { content.children().any { it is AuthenticatedImageView && field(it, "bitmapLease") != null } }
                val card = content.children().filterIsInstance<AuthenticatedImageView>().single()
                val bitmap = (field(card, "bitmapLease") as ScopedImageLease).bitmap
                assertTrue(card.performClick())
                val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
                assertTrue(dialog.isShowing)
                controller.pause().stop()
                val reads = f.reads.get()
                val imageReads = f.imageReads.get()
                pausedABA(f, throughB) { assertTrue(f.repository.isActorRetired(actor)) }
                await { !dialog.isShowing && bitmap.isRecycled && cached.isRecycled && f.images.residentBytesForTest() == 0L }
                assertNull(field(card, "readContext"))
                assertNull(field(card, "reference"))
                assertNull(field(card, "filePath"))
                assertNull(field(card, "bitmapLease"))
                assertFalse(card.hasOnClickListeners())
                assertEquals(reads, f.reads.get())
                assertEquals(imageReads, f.imageReads.get())
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun queuedReadyAndNoncancellableOriginalResponsesCannotRestoreSameObjectABACache() {
        Fixture().use { f ->
            val actor = f.actor()
            val callbacks = AtomicInteger(0)
            f.images.loadFile(actor, FilesTarget.Pane("pane"), f.imagePath, f.png(f.first).size.toLong(), 1.25, 32, 32, { callbacks.incrementAndGet() })
            awaitWithoutMain { f.images.pendingDeliveriesForTest() > 0 }
            assertTrue(f.images.committedBitmapBytesForTest() > 0)
            pausedABA(f, false, pump = false) { assertTrue(f.repository.isActorRetired(actor)) }
            await { f.images.pendingDeliveriesForTest() == 0 && f.images.residentBytesForTest() == 0L }
            assertEquals(0, callbacks.get())
        }
        Fixture().use { f ->
            val actor = f.actor()
            val entered = AtomicBoolean(false)
            val returned = AtomicBoolean(false)
            val held = CompletableDeferred<ApiResult<FilesImageBlob>>()
            val callbacks = AtomicInteger(0)
            f.imageAnswer = { withContext(NonCancellable) { entered.set(true); held.await().also { returned.set(true) } } }
            f.images.loadFile(actor, FilesTarget.Pane("pane"), f.imagePath, f.png(f.first).size.toLong(), 1.25, 32, 32, { callbacks.incrementAndGet() })
            await { entered.get() }
            try {
                pausedABA(f, true) { assertTrue(f.repository.isActorRetired(actor)) }
                held.complete(f.raster(f.first))
                await { returned.get() }
                await { f.images.residentBytesForTest() == 0L && f.images.pendingDeliveriesForTest() == 0 }
                assertEquals(0, callbacks.get())
            } finally { held.complete(f.raster(f.first)) }
        }
    }

    @Test fun delayedABACleanupKeepsReadmittedBSvgRasterAndViewerLiveAcrossSameInstanceRecommit() {
        for (path in listOf("private.svg", "private.png")) Fixture().use { f ->
            val controller = f.activity(path)
            try {
                val a = controller.get()
                val content = a.findViewById<ViewGroup>(R.id.files_source_content)
                await { field(a, "currentSource") != null }
                if (path.endsWith(".svg")) a.findViewById<View>(R.id.files_preview).performClick()
                await { content.children().any { it is RetirableFilePreview && (it !is AuthenticatedImageView || field(it, "bitmapLease") != null) } }
                val old = content.children().first { it is RetirableFilePreview }
                val oldActor = field(a, "acceptedPageContext") as ScopedReadContext
                pausedABA(f, false) {
                    f.store.save(f.second)
                    (field(a, "viewModel") as FilesViewModel).refresh()
                    await { (field(a, "acceptedPageContext") as? ScopedReadContext)?.connection === f.second }
                    await { content.children().any { it is RetirableFilePreview && (it !is AuthenticatedImageView || field(it, "bitmapLease") != null) } }
                }
                val fresh = content.children().first { it is RetirableFilePreview }
                val actorB = field(a, "acceptedPageContext") as ScopedReadContext
                assertNotSame(old, fresh)
                assertTrue(f.repository.isActorRetired(oldActor))
                assertTrue(f.repository.isCurrent(actorB))
                val lease = (fresh as? AuthenticatedImageView)?.let { field(it, "bitmapLease") as ScopedImageLease }
                val dialog = lease?.let { assertTrue(fresh.performClick()); org.robolectric.shadows.ShadowDialog.getLatestDialog() }
                val reads = f.reads.get()
                val images = f.imageReads.get()
                val published = CountDownLatch(1)
                val observer = f.store.observeCommitted { published.countDown() }
                try {
                    f.store.save(f.second)
                    await { published.count == 0L }
                    assertTrue(f.repository.isCurrent(actorB))
                    assertFalse(f.repository.isActorRetired(actorB))
                    assertEquals(reads, f.reads.get())
                    assertEquals(images, f.imageReads.get())
                    if (lease == null) { assertNull(field(old, "drawing")); assertNotNull(field(fresh, "drawing")); assertEquals(0xff00ff00.toInt(), pixel(fresh)) }
                    else { assertFalse(lease.bitmap.isRecycled); assertTrue(dialog!!.isShowing); assertEquals(0xff00ff00.toInt(), lease.bitmap.getPixel(2, 2)); assertNotNull(lease.retain()?.also { it.close() }) }
                } finally { observer.close() }
            } finally { controller.pause().stop().destroy() }
        }
    }

    private fun pausedABA(f: Fixture, throughB: Boolean, pump: Boolean = true, beforeRelease: () -> Unit) {
        val initial = CountDownLatch(1)
        val observer = f.store.observeCommitted { initial.countDown() }
        if (pump) await { initial.count == 0L } else awaitWithoutMain { initial.count == 0L }
        observer.close()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        f.store.beforePublicationForTesting = {
            entered.countDown()
            try { assertTrue(release.await(2, TimeUnit.SECONDS)) } catch (e: Throwable) { failure.set(e) }
        }
        try {
            val committed = CountDownLatch(1)
            Thread { try { if (throughB) f.store.save(f.second) else f.store.clear() } catch (e: Throwable) { failure.set(e) } finally { committed.countDown() } }.start()
            assertTrue(committed.await(2, TimeUnit.SECONDS))
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            f.store.save(f.first)
            beforeRelease()
            f.store.beforePublicationForTesting = {}
            release.countDown()
            val published = CountDownLatch(1)
            val receipt = f.store.observeCommitted { published.countDown() }
            try {
                if (pump) await { published.count == 0L } else awaitWithoutMain { published.count == 0L }
                assertNull(failure.get())
            } finally { receipt.close() }
        } finally { release.countDown(); f.store.beforePublicationForTesting = {} }
    }

    private class Fixture : AutoCloseable {
        val app = ApplicationProvider.getApplicationContext<CollieApplication>()
        val first = Connection(CollieOrigin("https://files.copies.fixture/"), "A", "fixture-a")
        val second = Connection(first.origin, "B", "fixture-b")
        private val preferences = app.getSharedPreferences("files-copies-${System.nanoTime()}", Context.MODE_PRIVATE)
        private val cipher = object : SecretCipher {
            override fun encrypt(plaintext: ByteArray) = CipherEnvelope(byteArrayOf(9), plaintext.map { (it.toInt() xor 0x5a).toByte() }.toByteArray())
            override fun decrypt(envelope: CipherEnvelope) = envelope.ciphertext.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
        }
        val store = EncryptedConnectionStore(app, cipher, Json, app.container.originValidator, preferences)
        val reads = AtomicInteger(0)
        val imageReads = AtomicInteger(0)
        val imagePath = RelativeFilePath.parse("private.png")!!
        var imageAnswer: suspend (Connection) -> ApiResult<FilesImageBlob> = { raster(it) }
        private val api = object : CollieApi by app.container.api {
            override suspend fun files(connection: Connection, target: FilesTarget, scope: Scope, path: RelativeFilePath?, dir: RelativeFilePath?, ignored: Boolean): ApiResult<FilesAnswer> {
                reads.incrementAndGet()
                val name = path?.value
                return ApiResult.Success(if (name == null) FilesAnswer.Listing("/repo", dir!!.value,
                    listOf(FileEntry("Private ${connection.label}.md", FileEntryKind.FILE, 64, false)), false)
                else FilesAnswer.Source("/repo", name, if (name.endsWith(".png")) png(connection).size.toLong() else 128L, 1.25, name.endsWith(".png"), false,
                    when { name.endsWith(".svg") -> "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"32\" height=\"32\"><rect width=\"32\" height=\"32\" fill=\"${if (connection === first) "#ff0000" else "#00ff00"}\"/></svg>"
                        name.endsWith(".png") -> ""
                        else -> "Private ${connection.label}" }), 200)
            }
            override suspend fun fileImage(connection: Connection, target: FilesTarget, scope: Scope, path: RelativeFilePath): ApiResult<FilesImageBlob> { imageReads.incrementAndGet(); return imageAnswer(connection) }
            override suspend fun config(connection: Connection, scope: Scope): ApiResult<BridgeConfigResponse> = ApiResult.Failure(ApiFailure.Protocol("temporary fixture failure"))
        }
        val repository = CollieRepository(api, store, app.container.originValidator)
        val images = ScopedImageLoader(repository)
        private val oldRepository = app.container.repository
        private val oldImages = app.container.imageLoader
        init {
            store.save(first)
            app.container.javaClass.getDeclaredField("repository").apply { isAccessible = true }.set(app.container, repository)
            app.container.javaClass.getDeclaredField("imageLoader").apply { isAccessible = true }.set(app.container, images)
        }
        fun actor(): ScopedReadContext { repository.selectSubject(Scope(), "pane"); return repository.readContext(Scope(), "pane")!! }
        fun activity(path: String? = null) = Robolectric.buildActivity(FilesActivity::class.java, FilesActivity.intent(app, FilesTarget.Pane("pane"), Scope(), path = path)).create().start().resume().visible()
        fun png(owner: Connection): ByteArray {
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(if (owner === first) 0xffff0000.toInt() else 0xff00ff00.toInt())
            return ByteArrayOutputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); bitmap.recycle(); output.toByteArray() }
        }
        fun raster(owner: Connection): ApiResult<FilesImageBlob> { val bytes = png(owner); return ApiResult.Success(FilesImageBlob("image/png", bytes, bytes.size.toLong(), 1.25), 200) }
        fun load(actor: ScopedReadContext): ScopedImageLease {
            val result = AtomicReference<ScopedImageResult?>()
            images.loadFile(actor, FilesTarget.Pane("pane"), imagePath, png(actor.connection).size.toLong(), 1.25, 32, 32, { result.set(it) })
            await { result.get() != null }
            return (result.get() as ScopedImageResult.Ready).lease
        }
        fun assertNoOwnerLocks(vm: FilesViewModel) {
            for ((owner, name) in listOf(store to "persistenceLock", repository to "readAdmissionLock", images to "lock", vm to "custodyLock"))
                assertFalse(Thread.holdsLock(field(owner, name)))
        }
        override fun close() {
            store.beforePublicationForTesting = {}; images.close()
            app.container.javaClass.getDeclaredField("repository").apply { isAccessible = true }.set(app.container, oldRepository)
            app.container.javaClass.getDeclaredField("imageLoader").apply { isAccessible = true }.set(app.container, oldImages)
            preferences.edit().clear().commit()
        }
    }
    companion object {
        private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
        @Suppress("UNCHECKED_CAST") private fun adapter(list: RecyclerView) = list.adapter as RecyclerView.Adapter<RecyclerView.ViewHolder>
        private fun bindRow(list: RecyclerView): RecyclerView.ViewHolder = adapter(list).createViewHolder(list, 0).also { bindExisting(list, it) }
        private fun bindExisting(list: RecyclerView, holder: RecyclerView.ViewHolder) { adapter(list).bindViewHolder(holder, 0) }
        private fun ViewGroup.children() = (0 until childCount).map(::getChildAt)
        private fun pixel(view: View): Int {
            val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            return try { view.layout(0, 0, 32, 32); view.draw(Canvas(bitmap)); bitmap.getPixel(8, 8) } finally { bitmap.recycle() }
        }
        private fun await(predicate: () -> Boolean) { waitFor(true, predicate) }
        private fun awaitWithoutMain(predicate: () -> Boolean) { waitFor(false, predicate) }
        private fun waitFor(pump: Boolean, predicate: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            do { if (pump) shadowOf(Looper.getMainLooper()).idle(); if (predicate()) return; Thread.sleep(10) } while (System.nanoTime() < deadline)
            assertTrue("custody must settle within five seconds", predicate())
        }
    }
}
