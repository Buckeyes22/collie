package com.lateapex.collie.ui

import android.content.Context
import android.os.Looper
import android.text.Spanned
import android.text.style.ClickableSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.data.CipherEnvelope
import com.lateapex.collie.data.CollieRepository
import com.lateapex.collie.data.EncryptedConnectionStore
import com.lateapex.collie.data.ScopedImageLoader
import com.lateapex.collie.data.SecretCipher
import com.lateapex.collie.domain.CollieOrigin
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.domain.Scope
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
import org.robolectric.shadows.ShadowDialog
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real encrypted-store persistence/publication, with a deterministic test cipher (not Keystore evidence). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FilesEncryptedRetirementTest {
    @Test fun idleStoreClearRetiresOriginalHandoffWithoutAnotherReadAndFreshBStillWorks() {
        Fixture().use { f ->
            f.repository.selectSubject(Scope(), "pane")
            val original = requireNotNull(f.repository.readContext(Scope(), "pane"))
            val vm = FilesViewModel(f.app, f.repository, FilesTarget.Pane("pane"), Scope(), "", "private.md", false,
                handoffRequired = true, initialContext = original)
            await { vm.state.value.answer is FilesAnswer.Source }
            val reads = f.reads.size
            val acknowledged = AtomicBoolean(false)
            val callbackFailure = AtomicReference<Throwable?>()
            val registration = vm.onPageRetired {
                try {
                    f.assertNoOwnerLocksHeld(vm)
                    assertNull(vm.state.value.answer)
                    assertNull(vm.state.value.acceptedContext)
                    acknowledged.set(true)
                } catch (failure: Throwable) { callbackFailure.set(failure) }
            }
            try {
                val worker = Thread { f.store.clear() }.apply { start() }
                await { acknowledged.get() || callbackFailure.get() != null }
                worker.join(2000)
                assertFalse(worker.isAlive)
                assertNull(callbackFailure.get())
                assertTrue(f.repository.isActorRetired(original))
                assertEquals("idle retirement performs no repository read", reads, f.reads.size)
                assertNull(field(vm, "navigationContext"))
                assertNull(field(vm, "pageContext"))
                f.store.save(f.second)
                vm.refresh()
                shadowOf(Looper.getMainLooper()).idle()
                assertEquals("a consumed original handoff never acquires B", reads, f.reads.size)
                assertNull(vm.state.value.answer)
                val fresh = f.vm()
                await { (fresh.state.value.answer as? FilesAnswer.Source)?.text?.contains("Private B") == true }
                assertTrue(fresh.state.value.acceptedContext?.let(f.repository::isCurrent) == true)
                clear(fresh)
            } finally { registration.close(); clear(vm) }
        }
    }

    @Test fun queuedStorePublicationCannotRetireTheFreshBPageAfterExplicitRetry() {
        Fixture().use { f ->
            val vm = f.vm()
            await { vm.state.value.answer is FilesAnswer.Source }
            val actorA = requireNotNull(vm.state.value.acceptedContext)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val callbackFailure = AtomicReference<Throwable?>()
            val retirements = AtomicInteger(0)
            val subscription = vm.onPageRetired { retirements.incrementAndGet() }
            f.store.beforePublicationForTesting = {
                entered.countDown()
                try { assertTrue(release.await(2, TimeUnit.SECONDS)) }
                catch (failure: Throwable) { callbackFailure.set(failure) }
            }
            try {
                f.store.clear()
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                f.store.save(f.second)
                // Explicit user refresh observes committed B while A's publication is still queued.
                vm.refresh()
                await { (vm.state.value.answer as? FilesAnswer.Source)?.text?.contains("Private B") == true }
                val actorB = requireNotNull(vm.state.value.acceptedContext)
                assertTrue(f.repository.isActorRetired(actorA))
                assertFalse(f.repository.isActorRetired(actorB))
                val count = f.reads.size
                val retiredCount = retirements.get()
                f.store.beforePublicationForTesting = {}
                release.countDown()
                val published = CountDownLatch(1)
                f.store.beforePublicationForTesting = { published.countDown() }
                f.store.save(f.second)
                await { published.count == 0L }
                assertNull(callbackFailure.get())
                assertEquals("queued A notifications do not clear B", retiredCount, retirements.get())
                assertTrue(vm.state.value.acceptedContext === actorB)
                assertTrue((vm.state.value.answer as FilesAnswer.Source).text.contains("Private B"))
                assertEquals("notification handling never issues a stale read", count, f.reads.size)
            } finally {
                release.countDown(); f.store.beforePublicationForTesting = {}; subscription.close(); clear(vm)
            }
        }
    }

    @Test fun heldOldResponseIsCancelledAndCannotRestoreRetiredContentOrWipePositiveB() {
        Fixture().use { f ->
            val vm = f.vm()
            await { vm.state.value.answer is FilesAnswer.Source }
            val entered = AtomicBoolean(false)
            val returned = AtomicBoolean(false)
            val old = CompletableDeferred<ApiResult<FilesAnswer>>()
            f.source = { owner, path ->
                if (owner === f.first) withContext(NonCancellable) {
                    entered.set(true)
                    old.await().also { returned.set(true) }
                } else f.answer(owner, path)
            }
            try {
                vm.refresh()
                await { entered.get() }
                f.store.save(f.second)
                await { vm.state.value.answer == null && field(vm, "pageContext") == null }
                val countAfterRetirement = f.reads.size
                assertEquals("retirement does not dispatch under replacement credentials", 2, countAfterRetirement)
                vm.refresh()
                await { (vm.state.value.answer as? FilesAnswer.Source)?.text?.contains("Private B") == true }
                val actorB = vm.state.value.acceptedContext
                old.complete(f.answer(f.first, "private.md"))
                await { returned.get() }
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue(vm.state.value.acceptedContext === actorB)
                assertTrue((vm.state.value.answer as FilesAnswer.Source).text.contains("Private B"))
                assertEquals(3, f.reads.size)
                assertNull(field(vm, "navigationContext"))
            } finally { old.complete(f.answer(f.first, "private.md")); clear(vm) }
        }
    }

    @Test fun stoppedActivityWipesSourcePreviewDialogAndFindCopiesOnOffMainStoreClear() {
        Fixture().use { f ->
            val controller = Robolectric.buildActivity(FilesActivity::class.java,
                FilesActivity.intent(f.app, FilesTarget.Pane("pane"), Scope(), path = "private.md"))
                .create().start().resume().visible()
            try {
                val activity = controller.get()
                val content = activity.findViewById<ViewGroup>(R.id.files_source_content)
                await { text(content).contains("Private A") }
                val detachedSource = field(activity, "sourceText") as TextView
                activity.findViewById<View>(R.id.files_preview).performClick()
                await { text(content).contains("Private A") && content.descendants().filterIsInstance<TextView>().any { it.text is Spanned } }
                val linkView = content.descendants().filterIsInstance<TextView>().first {
                    (it.text as? Spanned)?.getSpans(0, it.text.length, ClickableSpan::class.java)?.isNotEmpty() == true
                }
                val oldLink = (linkView.text as Spanned).getSpans(0, linkView.text.length, ClickableSpan::class.java).first()
                oldLink.onClick(linkView)
                val dialog = ShadowDialog.getLatestDialog()
                assertTrue(dialog.isShowing)
                activity.findViewById<android.widget.EditText>(R.id.files_find).setText("Private A")
                val vm = field(activity, "viewModel") as FilesViewModel
                val callbacks = AtomicBoolean(false)
                val callbackFailure = AtomicReference<Throwable?>()
                val subscription = vm.onPageRetired {
                    try {
                        f.assertNoOwnerLocksHeld(vm)
                        assertEquals(0, content.childCount)
                        assertEquals("", detachedSource.text.toString())
                        assertFalse(dialog.isShowing)
                        callbacks.set(true)
                    } catch (failure: Throwable) { callbackFailure.set(failure) }
                }
                try {
                    controller.pause().stop()
                    assertTrue("stopped content really remains owned before retirement", text(content).contains("Private A"))
                    val reads = f.reads.size
                    Thread { f.store.clear() }.start()
                    await { callbacks.get() || callbackFailure.get() != null }
                    assertNull(callbackFailure.get())
                    assertNull(vm.state.value.answer)
                    assertNull(vm.state.value.acceptedContext)
                    assertNull(field(activity, "currentSource"))
                    assertNull(field(activity, "acceptedPageContext"))
                    assertEquals("", activity.findViewById<android.widget.EditText>(R.id.files_find).text.toString())
                    assertEquals("", activity.findViewById<TextView>(R.id.files_breadcrumb).text.toString())
                    assertEquals("no later request was needed to erase stopped views", reads, f.reads.size)
                    oldLink.onClick(linkView)
                    assertNull("retained old link cannot open a browser", shadowOf(f.app).nextStartedActivity)
                    controller.start().resume().visible()
                    shadowOf(Looper.getMainLooper()).idle()
                    assertEquals(0, content.childCount)
                } finally { subscription.close() }
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun stoppedReplacementWipesOnlyAAndExplicitCurrentBRetryRendersPositiveContent() {
        Fixture().use { f ->
            val controller = Robolectric.buildActivity(FilesActivity::class.java,
                FilesActivity.intent(f.app, FilesTarget.Pane("pane"), Scope(), path = "private.md"))
                .create().start().resume().visible()
            try {
                val activity = controller.get()
                val content = activity.findViewById<ViewGroup>(R.id.files_source_content)
                await { text(content).contains("Private A") }
                val vm = field(activity, "viewModel") as FilesViewModel
                val actorA = requireNotNull(vm.state.value.acceptedContext)
                val count = f.reads.size
                controller.pause().stop()
                Thread { f.store.save(f.second) }.start()
                await { content.childCount == 0 && vm.state.value.answer == null }
                assertEquals(count, f.reads.size)
                assertTrue(f.repository.isActorRetired(actorA))
                controller.start().resume().visible()
                await { activity.findViewById<View>(R.id.files_retry).visibility == View.VISIBLE }
                activity.findViewById<View>(R.id.files_retry).performClick()
                await { text(content).contains("Private B") }
                assertTrue(vm.state.value.acceptedContext?.let(f.repository::isCurrent) == true)
                assertEquals(count + 1, f.reads.size)
                assertFalse(text(content).contains("Private A"))
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun temporaryAdmissionFencePausesBoundReadsWithoutPermanentlyRetiringTheOriginalActor() {
        Fixture().use { f ->
            f.repository.selectSubject(Scope(), "pane")
            val actor = requireNotNull(f.repository.readContext(Scope(), "pane"))
            val vm = FilesViewModel(f.app, f.repository, FilesTarget.Pane("pane"), Scope(), "", "private.md", false,
                handoffRequired = true, initialContext = actor)
            await { vm.state.value.answer is FilesAnswer.Source }
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val finished = AtomicBoolean(false)
            val failure = AtomicReference<Throwable?>()
            val retired = AtomicBoolean(false)
            val subscription = f.repository.onAuthorityInvalidated {
                entered.countDown(); assertTrue(release.await(2, TimeUnit.SECONDS))
            }
            try {
                Thread {
                    try { runBlocking { f.repository.config() } }
                    catch (error: Throwable) { failure.set(error) }
                    finally { finished.set(true) }
                }.start()
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                assertFalse(f.repository.isCurrent(actor))
                assertFalse(f.repository.isActorRetired(actor))
                val fenceObserver = f.repository.onFilesActorRetired(actor) { retired.set(true) }
                try {
                    val count = f.reads.size
                    vm.refresh()
                    assertEquals(count, f.reads.size)
                    assertTrue(vm.state.value.answer is FilesAnswer.Source)
                    assertTrue(field(vm, "navigationContext") === actor)
                    assertFalse(retired.get())
                    release.countDown()
                    await { finished.get() }
                    assertNull(failure.get())
                    assertTrue(f.repository.isCurrent(actor))
                    vm.refresh()
                    await { f.reads.size == count + 1 && vm.state.value.answer is FilesAnswer.Source }
                    assertFalse(retired.get())
                } finally { fenceObserver.close() }
            } finally { release.countDown(); subscription.close(); clear(vm) }
        }
    }

    private class Fixture : AutoCloseable {
        val app = ApplicationProvider.getApplicationContext<CollieApplication>()
        val first = Connection(CollieOrigin("https://files.retirement.fixture/"), "A", "fixture-a")
        val second = Connection(first.origin, "B", "fixture-b")
        private val cipher = object : SecretCipher {
            override fun encrypt(plaintext: ByteArray) = CipherEnvelope(byteArrayOf(7), plaintext.map { (it.toInt() xor 0x5a).toByte() }.toByteArray())
            override fun decrypt(envelope: CipherEnvelope) = envelope.ciphertext.map { (it.toInt() xor 0x5a).toByte() }.toByteArray()
        }
        private val preferences = app.getSharedPreferences("files-retirement-${System.nanoTime()}", Context.MODE_PRIVATE)
        val store = EncryptedConnectionStore(app, cipher, Json, app.container.originValidator, preferences)
        val reads = Collections.synchronizedList(mutableListOf<Connection>())
        var source: suspend (Connection, String) -> ApiResult<FilesAnswer> = { owner, path -> answer(owner, path) }
        private val api = object : CollieApi by app.container.api {
            override suspend fun files(connection: Connection, target: FilesTarget, scope: Scope, path: RelativeFilePath?, dir: RelativeFilePath?, ignored: Boolean): ApiResult<FilesAnswer> {
                reads.add(connection)
                return source(connection, path?.value.orEmpty())
            }
            override suspend fun config(connection: Connection, scope: Scope): ApiResult<BridgeConfigResponse> = ApiResult.Failure(ApiFailure.Protocol("temporary fixture failure"))
        }
        val repository = CollieRepository(api, store, app.container.originValidator)
        private val oldRepository = app.container.repository
        private val oldImages = app.container.imageLoader
        private val images = ScopedImageLoader(repository)
        init {
            store.save(first)
            app.container.javaClass.getDeclaredField("repository").apply { isAccessible = true }.set(app.container, repository)
            app.container.javaClass.getDeclaredField("imageLoader").apply { isAccessible = true }.set(app.container, images)
        }
        fun vm() = FilesViewModel(app, repository, FilesTarget.Pane("pane"), Scope(), "", "private.md", false)
        fun answer(owner: Connection, path: String): ApiResult<FilesAnswer> = ApiResult.Success(
            FilesAnswer.Source("/repo", path, 64, 1.0, false, false, "# Private ${owner.label}\n\nhttps://must-not-open.invalid/secret"), 200)
        fun assertNoOwnerLocksHeld(vm: FilesViewModel) {
            for ((owner, name) in listOf(store to "persistenceLock", repository to "readAdmissionLock", vm to "custodyLock")) {
                val lock = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
                assertFalse("retirement callbacks must run outside $name", Thread.holdsLock(lock))
            }
        }
        override fun close() {
            store.beforePublicationForTesting = {}
            images.close()
            app.container.javaClass.getDeclaredField("repository").apply { isAccessible = true }.set(app.container, oldRepository)
            app.container.javaClass.getDeclaredField("imageLoader").apply { isAccessible = true }.set(app.container, oldImages)
            preferences.edit().clear().commit()
        }
    }

    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    private fun clear(vm: FilesViewModel) {
        FilesViewModel::class.java.getDeclaredMethod("onCleared").apply { isAccessible = true }.invoke(vm)
    }
    private fun ViewGroup.descendants(): List<View> = buildList {
        fun visit(view: View) {
            add(view)
            if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
        }
        visit(this@descendants)
    }
    private fun text(content: ViewGroup) = content.descendants().filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
    private fun await(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (predicate()) return
            Thread.sleep(10)
        } while (System.nanoTime() < deadline)
        assertTrue("retirement/current-positive condition must settle within five seconds", predicate())
    }
}
