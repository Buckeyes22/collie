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
class FilesCustodyCopiesTest {
    @Test fun detachedAndPooledListingCopiesClearOnOffMainEncryptedStoreRetirementWithoutReads() {
        for (replace in listOf(false, true)) Fixture().use { f ->
            val controller = f.activity()
            try {
                val a = controller.get()
                val list = a.findViewById<RecyclerView>(R.id.files_list)
                await { list.adapter!!.itemCount == 1 }
                val detached = bindRow(list)
                val pooled = bindRow(list)
                list.recycledViewPool.putRecycledView(pooled)
                assertTrue((detached.itemView as TextView).text.contains("Private A"))
                assertTrue((pooled.itemView as TextView).contentDescription?.toString()?.contains("Private A") == true)
                (field(a, "viewModel") as FilesViewModel).openFile("private.md")
                await { field(a, "currentSource") != null }
                controller.pause().stop()
                val count = f.reads.get()
                Thread { if (replace) f.store.save(f.second) else f.store.clear() }.start()
                await { (detached.itemView as TextView).text.isEmpty() && (pooled.itemView as TextView).text.isEmpty() }
                for (holder in listOf(detached, pooled)) {
                    assertNull(holder.itemView.contentDescription)
                    assertFalse(holder.itemView.hasOnClickListeners())
                    assertFalse(holder.itemView.performClick())
                }
                assertEquals(count, f.reads.get())
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun rebindingTheSameListingHolderToBProtectsItsTextAndClickFromQueuedAStorePublication() {
        Fixture().use { f ->
            val controller = f.activity()
            val release = CountDownLatch(1)
            try {
                val a = controller.get()
                val list = a.findViewById<RecyclerView>(R.id.files_list)
                await { list.adapter!!.itemCount == 1 }
                val reused = bindRow(list)
                val entered = CountDownLatch(1)
                f.store.beforePublicationForTesting = { entered.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)) }
                f.store.save(f.second)
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                (field(a, "viewModel") as FilesViewModel).refresh()
                await { (list.adapter!!.itemCount == 1) && (field(a, "acceptedPageContext") as? ScopedReadContext)?.connection === f.second }
                bindExisting(list, reused)
                val row = reused.itemView as TextView
                assertTrue(row.text.contains("Private B"))
                val reads = f.reads.get()
                f.store.beforePublicationForTesting = {}
                release.countDown()
                val published = CountDownLatch(1)
                f.store.beforePublicationForTesting = { published.countDown() }
                f.store.save(f.second)
                await { published.count == 0L }
                assertTrue(row.text.contains("Private B"))
                assertTrue(row.contentDescription?.toString()?.contains("Private B") == true)
                assertEquals(reads, f.reads.get())
                assertTrue(row.performClick())
                await { (field(a, "currentSource") as? FilesAnswer.Source)?.text?.contains("Private B") == true }
            } finally { release.countDown(); f.store.beforePublicationForTesting = {}; controller.pause().stop().destroy() }
        }
    }

    @Test fun detachedSupportedSvgReleasesItsGraphAndCannotDrawOrTouchAfterStoreClearOrReplacement() {
        for (replace in listOf(false, true)) Fixture().use { f ->
            val controller = f.activity("private.svg")
            try {
                val a = controller.get()
                val content = a.findViewById<ViewGroup>(R.id.files_source_content)
                await { field(a, "currentSource") != null }
                a.findViewById<View>(R.id.files_preview).performClick()
                val old = content.children().first { it is RetirableFilePreview }
                assertEquals(0xffff0000.toInt(), pixel(old))
                a.findViewById<View>(R.id.files_source).performClick()
                assertNull(old.parent)
                assertNotNull(field(old, "drawing"))
                controller.pause().stop()
                val reads = f.reads.get()
                Thread { if (replace) f.store.save(f.second) else f.store.clear() }.start()
                await { field(old, "drawing") == null }
                assertEquals(0, pixel(old))
                val motion = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, 4f, 4f, 0)
                try { assertFalse(old.onTouchEvent(motion)) } finally { motion.recycle() }
                assertNull(old.contentDescription)
                assertEquals(reads, f.reads.get())
                if (replace) {
                    controller.start().resume().visible()
                    (field(a, "viewModel") as FilesViewModel).refresh()
                    await { (field(a, "acceptedPageContext") as? ScopedReadContext)?.connection === f.second }
                    a.findViewById<View>(R.id.files_preview).performClick()
                    val fresh = content.children().first { it is RetirableFilePreview }
                    assertEquals(0xff00ff00.toInt(), pixel(fresh))
                    val touch = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 4f, 4f, 0)
                    try { assertTrue(fresh.onTouchEvent(touch)) } finally { touch.recycle() }
                    assertNull(field(old, "drawing"))
                }
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun versionedRasterCacheRetiresOnCommittedStoreFlowWithNoNewReadAndFreshBRemainsPinned() {
        for (replace in listOf(false, true)) Fixture().use { f ->
            val actorA = f.actor()
            val old = f.load(actorA)
            val bitmapA = old.bitmap
            old.close()
            assertTrue(f.images.committedBitmapBytesForTest() > 0)
            val reads = f.imageReads.get()
            Thread { if (replace) f.store.save(f.second) else f.store.clear() }.start()
            await { bitmapA.isRecycled && f.images.residentBytesForTest() == 0L }
            assertEquals(reads, f.imageReads.get())
            f.store.save(f.second)
            val actorB = f.actor()
            val fresh = f.load(actorB)
            try {
                val bBytes = f.images.committedBitmapBytesForTest()
                assertTrue(bBytes > 0)
                assertFalse(fresh.bitmap.isRecycled)
                assertEquals(0xff00ff00.toInt(), fresh.bitmap.getPixel(2, 2))
                val publication = CountDownLatch(1)
                f.store.beforePublicationForTesting = { publication.countDown() }
                f.store.save(f.second)
                await { publication.count == 0L }
                assertEquals(bBytes, f.images.committedBitmapBytesForTest())
                assertFalse(fresh.bitmap.isRecycled)
                assertNotNull(fresh.retain()?.also { it.close() })
            } finally { fresh.close() }
        }
    }

    @Test fun actualRasterCardAndViewerClearBeforeMainRetirementAckAndCannotRetainRetiredPixels() {
        Fixture().use { f ->
            val controller = f.activity("private.png")
            try {
                val a = controller.get()
                val content = a.findViewById<ViewGroup>(R.id.files_source_content)
                await { content.children().any { it is AuthenticatedImageView && field(it, "bitmapLease") != null } }
                val old = content.children().filterIsInstance<AuthenticatedImageView>().single()
                val lease = field(old, "bitmapLease") as ScopedImageLease
                val bitmap = lease.bitmap
                assertTrue(old.performClick())
                val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
                assertTrue(dialog.isShowing)
                val callback = AtomicBoolean(false)
                val vm = field(a, "viewModel") as FilesViewModel
                val subscription = vm.onPageRetired {
                    assertEquals(0, content.childCount)
                    assertNull(field(old, "bitmapLease"))
                    assertTrue(bitmap.isRecycled)
                    assertFalse(dialog.isShowing)
                    f.assertNoOwnerLocks(vm)
                    callback.set(true)
                }
                try {
                    controller.pause().stop()
                    val reads = f.imageReads.get()
                    Thread { f.store.clear() }.start()
                    await { callback.get() }
                    assertNull(field(old, "readContext"))
                    assertNull(old.contentDescription)
                    assertFalse(old.performClick())
                    assertEquals(reads, f.imageReads.get())
                    assertEquals(0L, f.images.residentBytesForTest())
                } finally { subscription.close() }
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun heldAndQueuedRasterAnswersCannotRestoreRetiredCacheOrClearPositiveB() {
        Fixture().use { f ->
            val actorA = f.actor()
            val entered = AtomicBoolean(false)
            val returned = AtomicBoolean(false)
            val held = CompletableDeferred<ApiResult<FilesImageBlob>>()
            f.imageAnswer = { owner -> if (owner === f.first) withContext(NonCancellable) {
                entered.set(true); held.await().also { returned.set(true) }
            } else f.raster(owner) }
            val callbacks = AtomicInteger(0)
            f.images.loadFile(actorA, FilesTarget.Pane("pane"), f.imagePath, f.png(f.first).size.toLong(), 1.25, 32, 32, { callbacks.incrementAndGet() })
            await { entered.get() }
            f.store.save(f.second)
            val actorB = f.actor()
            val b = f.load(actorB)
            try {
                held.complete(f.raster(f.first))
                await { returned.get() }
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals(0, callbacks.get())
                assertFalse(b.bitmap.isRecycled)
                assertEquals(0xff00ff00.toInt(), b.bitmap.getPixel(2, 2))
                assertEquals(b.bitmap.allocationByteCount.toLong(), f.images.committedBitmapBytesForTest())
            } finally { held.complete(f.raster(f.first)); b.close() }
        }
        Fixture().use { f ->
            val actor = f.actor()
            val callbacks = AtomicInteger(0)
            f.images.loadFile(actor, FilesTarget.Pane("pane"), f.imagePath, f.png(f.first).size.toLong(), 1.25, 32, 32, { callbacks.incrementAndGet() })
            awaitWithoutMain { f.images.pendingDeliveriesForTest() > 0 }
            assertTrue(f.images.committedBitmapBytesForTest() > 0)
            f.store.clear()
            awaitWithoutMain { f.images.committedBitmapBytesForTest() == 0L || (field(f.images, "entries") as Map<*, *>).values.all { field(it!!, "retired") == true } }
            shadowOf(Looper.getMainLooper()).idle()
            await { f.images.pendingDeliveriesForTest() == 0 && f.images.residentBytesForTest() == 0L }
            assertEquals(0, callbacks.get())
        }
    }

    @Test fun queuedAStorePublicationPreservesFreshBSvgAndRasterViewerWithoutFreshReads() {
        for (path in listOf("private.svg", "private.png")) Fixture().use { f ->
            val controller = f.activity(path)
            val release = CountDownLatch(1)
            try {
                val a = controller.get()
                val content = a.findViewById<ViewGroup>(R.id.files_source_content)
                await { field(a, "currentSource") != null }
                if (path.endsWith(".svg")) a.findViewById<View>(R.id.files_preview).performClick()
                await { content.children().any { it is RetirableFilePreview && (it !is AuthenticatedImageView || field(it, "bitmapLease") != null) } }
                val old = content.children().first { it is RetirableFilePreview }
                val entered = CountDownLatch(1)
                f.store.beforePublicationForTesting = { entered.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)) }
                f.store.clear()
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                f.store.save(f.second)
                (field(a, "viewModel") as FilesViewModel).refresh()
                await { (field(a, "acceptedPageContext") as? ScopedReadContext)?.connection === f.second }
                await { content.children().any { it is RetirableFilePreview && (it !is AuthenticatedImageView || field(it, "bitmapLease") != null) } }
                val fresh = content.children().first { it is RetirableFilePreview }
                assertNotSame(old, fresh)
                val bLease = (fresh as? AuthenticatedImageView)?.let { field(it, "bitmapLease") as ScopedImageLease }
                val dialog = bLease?.let { assertTrue(fresh.performClick()); org.robolectric.shadows.ShadowDialog.getLatestDialog() }
                val fileReads = f.reads.get()
                val imageReads = f.imageReads.get()
                val bytes = f.images.committedBitmapBytesForTest()
                f.store.beforePublicationForTesting = {}
                release.countDown()
                val published = CountDownLatch(1)
                f.store.beforePublicationForTesting = { published.countDown() }
                f.store.save(f.second)
                await { published.count == 0L }
                assertEquals(fileReads, f.reads.get())
                assertEquals(imageReads, f.imageReads.get())
                if (bLease == null) {
                    assertNull(field(old, "drawing"))
                    assertNotNull(field(fresh, "drawing"))
                    assertEquals(0xff00ff00.toInt(), pixel(fresh))
                    val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 2f, 2f, 0)
                    try { assertTrue(fresh.onTouchEvent(down)) } finally { down.recycle() }
                } else {
                    assertNull(field(old, "bitmapLease"))
                    assertFalse(bLease.bitmap.isRecycled)
                    assertEquals(0xff00ff00.toInt(), bLease.bitmap.getPixel(2, 2))
                    assertTrue(dialog!!.isShowing)
                    assertEquals(bytes, f.images.committedBitmapBytesForTest())
                    assertNotNull(bLease.retain()?.also { it.close() })
                }
            } finally { release.countDown(); f.store.beforePublicationForTesting = {}; controller.pause().stop().destroy() }
        }
    }

    @Test fun temporaryCanonicalAdmissionFencePreservesSvgListingAndRasterCustodyButBlocksNewReads() {
        Fixture().use { f ->
            val controller = f.activity()
            val release = CountDownLatch(1)
            try {
                val a = controller.get()
                val list = a.findViewById<RecyclerView>(R.id.files_list)
                await { list.adapter!!.itemCount == 1 }
                val row = bindRow(list).itemView as TextView
                (field(a, "viewModel") as FilesViewModel).openFile("private.svg")
                await { field(a, "currentSource") != null }
                a.findViewById<View>(R.id.files_preview).performClick()
                val svg = a.findViewById<ViewGroup>(R.id.files_source_content).children().first { it is RetirableFilePreview }
                val actor = field(a, "acceptedPageContext") as ScopedReadContext
                val raster = f.load(actor)
                val entered = CountDownLatch(1)
                val finished = AtomicBoolean(false)
                val subscription = f.repository.onAuthorityInvalidated { entered.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS)) }
                try {
                    Thread { try { runBlocking { f.repository.config() } } finally { finished.set(true) } }.start()
                    assertTrue(entered.await(2, TimeUnit.SECONDS))
                    assertFalse(f.repository.isCurrent(actor))
                    assertFalse(f.repository.isActorRetired(actor))
                    assertNotNull(field(svg, "drawing"))
                    assertEquals(0xffff0000.toInt(), pixel(svg))
                    assertFalse(raster.bitmap.isRecycled)
                    val reads = f.imageReads.get()
                    val refused = AtomicBoolean(false)
                    f.images.loadFile(actor, FilesTarget.Pane("pane"), f.imagePath, 1, 1.25, 32, 32, { refused.set(it is ScopedImageResult.Refused) }).close()
                    assertTrue(refused.get())
                    assertTrue(row.text.contains("Private A"))
                    val fileReads = f.reads.get()
                    row.performClick()
                    assertEquals(fileReads, f.reads.get())
                    assertEquals(reads, f.imageReads.get())
                    release.countDown()
                    await { finished.get() && f.repository.isCurrent(actor) }
                    assertNotNull(field(svg, "drawing"))
                    assertFalse(raster.bitmap.isRecycled)
                    assertTrue(row.text.contains("Private A"))
                    assertTrue(row.contentDescription?.toString()?.contains("Private A") == true)
                } finally { release.countDown(); subscription.close(); raster.close() }
            } finally { release.countDown(); controller.pause().stop().destroy() }
        }
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
