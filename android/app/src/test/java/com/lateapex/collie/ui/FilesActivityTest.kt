package com.lateapex.collie.ui

import android.graphics.Bitmap
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import android.widget.TextView
import android.text.Spanned
import android.text.style.ClickableSpan
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.data.CollieRepository
import com.lateapex.collie.data.ConnectionStore
import com.lateapex.collie.data.ScopedImageLoader
import com.lateapex.collie.domain.CollieOrigin
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.*
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDialog
import org.robolectric.annotation.GraphicsMode
import okhttp3.Protocol
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import kotlinx.serialization.json.Json

@RunWith(RobolectricTestRunner::class)
class FilesActivityTest {
    @Test fun mountedHttpsFilesReadFlowsThroughApiRepositoryViewModelAndVisibleActivity() {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory()); start() }
        val app = ApplicationProvider.getApplicationContext<CollieApplication>()
        val originalRepository = app.container.repository
        val originalLoader = app.container.imageLoader
        val repositoryField = app.container.javaClass.getDeclaredField("repository").apply { isAccessible = true }
        val loaderField = app.container.javaClass.getDeclaredField("imageLoader").apply { isAccessible = true }
        val connection = Connection(CollieOrigin(server.url("/mount/").toString()), "phone", "mounted-token")
        val http = CollieApiClient.defaultHttpClient().newBuilder()
            .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .protocols(listOf(Protocol.HTTP_1_1))
            .build()
        val repository = CollieRepository(CollieApiClient(http, Json { ignoreUnknownKeys = true }), TestStore(connection), app.container.originValidator)
        val testLoader = ScopedImageLoader(repository)
        repositoryField.set(app.container, repository)
        loaderField.set(app.container, testLoader)
        val sourceText = "mounted-native-source-content"
        server.enqueue(MockResponse.Builder().code(200).setHeader("Content-Type", "application/json")
            .body("""{"paneId":"w1:p1","available":true,"root":"/repo","path":"src/Main.kt","size":${sourceText.length},"mtimeMs":12.5,"binary":false,"truncated":false,"text":"$sourceText"}""").build())
        val controller = Robolectric.buildActivity(
            FilesActivity::class.java,
            FilesActivity.intent(app, FilesTarget.Pane("w1:p1"), Scope("peer", "work"), path = "src/Main.kt"),
        ).create().start().resume().visible()
        try {
            val activity = controller.get()
            waitForMain { activity.findViewById<View>(R.id.files_source_scroll).visibility == View.VISIBLE }
            assertTrue(activity.findViewById<ViewGroup>(R.id.files_source_content).descendants().filterIsInstance<TextView>().any { it.text.toString().contains(sourceText) })
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/mount/api/pane/w1%3Ap1/files?path=src%2FMain.kt&host=peer&session=work", request.target)
            assertEquals("Bearer mounted-token", request.headers["Authorization"])
            assertNull(request.headers["Origin"])
        } finally {
            controller.pause().stop().destroy()
            server.close()
            testLoader.close()
            repositoryField.set(app.container, originalRepository)
            loaderField.set(app.container, originalLoader)
        }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun actualPreviewRendersNativeBlocksAndLocalImageThenRetiresCopiesAndRetries() {
        val app = ApplicationProvider.getApplicationContext<CollieApplication>()
        val originalRepository = app.container.repository
        val originalLoader = app.container.imageLoader
        val repositoryField = app.container.javaClass.getDeclaredField("repository").apply { isAccessible = true }
        val loaderField = app.container.javaClass.getDeclaredField("imageLoader").apply { isAccessible = true }
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory()); start() }
        val http = CollieApiClient.defaultHttpClient().newBuilder()
            .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .protocols(listOf(Protocol.HTTP_1_1)).build()
        val imageApi = CollieApiClient(http, Json { ignoreUnknownKeys = true })
        val store = TestStore(Connection(CollieOrigin(server.url("/files-mount/").toString()), "phone", "token"))
        val png = ByteArrayOutputStream().also { Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        server.enqueue(MockResponse.Builder().code(200).setHeader("Content-Type", "image/png")
            .setHeader("X-Collie-File-Size", png.size).setHeader("X-Collie-File-Mtime", "8.0")
            .body(okio.Buffer().write(png)).build())
        var fileCalls = 0
        var imageCalls = 0
        var failNextRead = false
        val api = object : CollieApi by app.container.api {
            override suspend fun files(connection: Connection, target: FilesTarget, scope: Scope, path: RelativeFilePath?, dir: RelativeFilePath?, ignored: Boolean): ApiResult<FilesAnswer> {
                fileCalls++
                if (failNextRead) {
                    failNextRead = false
                    return ApiResult.Failure(ApiFailure.Http(403, "proxy factor required", "device.not_authorised", source = FailureSource.COLLIE, factor = FailureFactor.DEVICE))
                }
                return if (path == null) ApiResult.Success(FilesAnswer.Listing("/repo", dir?.value.orEmpty(), emptyList(), false), 200)
                else ApiResult.Success(FilesAnswer.Source("/repo", path.value, 150, 7.0, false, false,
                    "# Guide\n\nA paragraph. [Source](../src/Main.kt#part)\n\n| key | value |\n| --- | --- |\n| a | b |\n\n![diagram](images/a diagram.bin)"), 200)
            }
            override suspend fun filesExist(connection: Connection, target: FilesTarget, scope: Scope, paths: List<RelativeFilePath>) =
                ApiResult.Success(FilesExistAnswer(paths.map { it.value }.toSet()), 200)
            override suspend fun fileImage(connection: Connection, target: FilesTarget, scope: Scope, path: RelativeFilePath): ApiResult<FilesImageBlob> {
                imageCalls++
                assertEquals("docs/images/a diagram.bin", path.value)
                return imageApi.fileImage(connection, target, scope, path)
            }
        }
        val repository = CollieRepository(api, store, app.container.originValidator)
        val testLoader = ScopedImageLoader(repository)
        repositoryField.set(app.container, repository)
        loaderField.set(app.container, testLoader)
        val controller = Robolectric.buildActivity(
            FilesActivity::class.java,
            FilesActivity.intent(app, FilesTarget.Pane("w1:p1"), Scope(), path = "docs/readme.md"),
        ).create().start().resume().visible()
        try {
            var activity = controller.get()
            waitForMain { activity.findViewById<View>(R.id.files_source_scroll).visibility == View.VISIBLE }
            val sourceContent = activity.findViewById<ViewGroup>(R.id.files_source_content)
            assertTrue(sourceContent.descendants().filterIsInstance<TextView>().any { it.text.toString().contains("# Guide") })
            activity.findViewById<View>(R.id.files_preview).performClick()
            waitForMain { sourceContent.descendants().filterIsInstance<TextView>().any { it.text.toString() == "Guide" } }
            assertTrue("the live preview renders the heading as a native view", sourceContent.descendants().filterIsInstance<TextView>().any { it.tag == "files.heading" })
            assertTrue("the live preview renders table cells as native views", sourceContent.descendants().filterIsInstance<TextView>().any { it.text.toString() == "key" })
            waitForMain {
                sourceContent.descendants().filterIsInstance<AuthenticatedImageView>().any { card ->
                    card.descendants().filterIsInstance<android.widget.ImageView>().any { it.visibility == View.VISIBLE && it.drawable != null }
                }
            }
            assertEquals("response byte sniff admits an extensionless-looking local image reference", 1, imageCalls)
            val imageRequest = server.takeRequest()
            assertEquals("actual mounted HTTPS bytes reach the attached decoder", "/files-mount/api/pane/w1%3Ap1/files/image?path=docs%2Fimages%2Fa%20diagram.bin", imageRequest.target)
            assertEquals("Bearer token", imageRequest.headers["Authorization"])
            assertEquals("markdown image path is resolved relative to the source file", 1, sourceContent.descendants().filterIsInstance<AuthenticatedImageView>().size)
            val imageCard = sourceContent.descendants().filterIsInstance<AuthenticatedImageView>().single()
            assertTrue("attached ready card opens its native viewer", imageCard.performClick())
            val imageDialog = ShadowDialog.getLatestDialog()
            assertTrue(imageDialog.isShowing)
            imageDialog.dismiss()
            waitForMain { sourceContent.descendants().filterIsInstance<android.widget.Button>().any { it.text.toString().contains("Source") } }
            val sourceLink = sourceContent.descendants().filterIsInstance<android.widget.Button>().single { it.text.toString().contains("Source") }
            sourceLink.performClick()
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
            val linkIntent = shadowOf(app).nextStartedActivity
            assertEquals("src/Main.kt", linkIntent.getStringExtra("files_path"))
            assertEquals("part", linkIntent.getStringExtra("files_anchor"))

            val wipedBeforeInvalidationReturned = java.util.concurrent.atomic.AtomicBoolean(false)
            val invalidationFinished = java.util.concurrent.atomic.AtomicBoolean(false)
            Thread {
                repository.setLeadEpoch(2)
                wipedBeforeInvalidationReturned.set(sourceContent.childCount == 0 &&
                    activity.findViewById<ViewGroup>(R.id.files_source_scroll).descendants().filterIsInstance<TextView>().none { it.text.toString().contains("Guide") })
                invalidationFinished.set(true)
            }.start()
            waitForMain { invalidationFinished.get() && activity.findViewById<View>(R.id.files_retry).visibility == View.VISIBLE }
            assertTrue("off-main authority retirement wipes the Activity copy before returning", wipedBeforeInvalidationReturned.get())
            assertTrue("retiring the page actor clears copied source and image views", sourceContent.childCount == 0)
            assertTrue("retiring the page actor erases the detached Source text", activity.findViewById<ViewGroup>(R.id.files_source_scroll).descendants().filterIsInstance<TextView>().none { it.text.toString().contains("Guide") })

            failNextRead = true
            activity.findViewById<View>(R.id.files_retry).performClick()
            waitForMain { activity.findViewById<View>(R.id.files_retry).visibility == View.VISIBLE && fileCalls >= 2 }
            assertFalse("refusal settles the progress state", activity.findViewById<View>(R.id.files_progress).visibility == View.VISIBLE)
            assertNotNull("refusal leaves an accessible retry button", activity.findViewById<View>(R.id.files_retry))
            val beforeRecovery = fileCalls
            activity.findViewById<View>(R.id.files_retry).performClick()
            waitForMain { fileCalls > beforeRecovery && activity.findViewById<View>(R.id.files_source_scroll).visibility == View.VISIBLE }
            assertTrue("fresh recovery retains the user's Preview mode", sourceContent.descendants().filterIsInstance<TextView>().any { it.tag == "files.heading" && it.text.toString() == "Guide" })
            assertTrue("the actual Source control restores raw text after current recovery", activity.findViewById<View>(R.id.files_source).performClick())
            assertTrue("a fresh authorized read restores the source page", sourceContent.descendants().filterIsInstance<TextView>().any { it.text.toString().contains("# Guide") })
        } finally {
            controller.pause().stop().destroy()
            server.close()
            testLoader.close()
            repositoryField.set(app.container, originalRepository)
            loaderField.set(app.container, originalLoader)
        }
    }

    @Test fun processRestoreReissuesTheCurrentFileReadWithoutSavingContentOrActor() {
        val app = ApplicationProvider.getApplicationContext<CollieApplication>()
        val originalRepository = app.container.repository
        val originalLoader = app.container.imageLoader
        val repositoryField = app.container.javaClass.getDeclaredField("repository").apply { isAccessible = true }
        val loaderField = app.container.javaClass.getDeclaredField("imageLoader").apply { isAccessible = true }
        val store = TestStore(Connection(CollieOrigin("https://files.restore.fixture/"), "phone", "token"))
        val reads = mutableListOf<String>()
        val api = object : CollieApi by app.container.api {
            override suspend fun files(connection: Connection, target: FilesTarget, scope: Scope, path: RelativeFilePath?, dir: RelativeFilePath?, ignored: Boolean): ApiResult<FilesAnswer> {
                reads += path?.value ?: dir?.value.orEmpty()
                return if (path != null) ApiResult.Success(FilesAnswer.Source("/repo", path.value, 12, 2.0, false, false, "process restore content"), 200)
                else ApiResult.Success(FilesAnswer.Listing("/repo", dir?.value.orEmpty(),
                    if (dir?.value.isNullOrEmpty()) listOf(FileEntry("docs", FileEntryKind.DIRECTORY, null, false))
                    else listOf(FileEntry("Main.kt", FileEntryKind.FILE, 12, false)), false), 200)
            }
        }
        val repository = CollieRepository(api, store, app.container.originValidator)
        val testLoader = ScopedImageLoader(repository)
        repositoryField.set(app.container, repository)
        loaderField.set(app.container, testLoader)
        val activityIntent = FilesActivity.intent(app, FilesTarget.Pane("w1:p1"), Scope())
        var controller = Robolectric.buildActivity(FilesActivity::class.java, activityIntent).create().start().resume().visible()
        try {
            var activity = controller.get()
            measureAndLayoutWindow(activity)
            waitForMain { activity.findViewById<RecyclerView>(R.id.files_list).findViewHolderForAdapterPosition(0)?.itemView?.let { it is TextView && it.text.toString().contains("docs") } == true }
            clickVisibleFileRow(activity, 0)
            waitForMain { reads.lastOrNull() == "docs" && activity.findViewById<RecyclerView>(R.id.files_list).findViewHolderForAdapterPosition(0)?.itemView?.let { it is TextView && it.text.toString().contains("Main.kt") } == true }
            clickVisibleFileRow(activity, 0)
            waitForMain { activity.findViewById<View>(R.id.files_source_scroll).visibility == View.VISIBLE }
            val oldContext = FilesActivity::class.java.getDeclaredField("acceptedPageContext").apply { isAccessible = true }.get(activity)
            val saved = android.os.Bundle()
            controller.saveInstanceState(saved)
            val savedText = saved.keySet().joinToString(" ") { key -> "$key=${saved.get(key)}" }
            assertFalse("bundle does not contain document text", savedText.contains("process restore content"))
            assertFalse("bundle does not contain a token", savedText.contains("token"))
            assertFalse("bundle does not contain the accepted actor", saved.keySet().any { it.contains("context", ignoreCase = true) || it.contains("actor", ignoreCase = true) })
            assertEquals("docs/Main.kt", saved.getString("files.state.path"))

            controller.pause().stop().destroy()
            val previousReadCount = reads.size
            controller = Robolectric.buildActivity(FilesActivity::class.java, activityIntent).create(saved).start().resume().visible()
            activity = controller.get()
            measureAndLayoutWindow(activity)
            waitForMain { reads.size > previousReadCount && reads.lastOrNull() == "docs/Main.kt" && activity.findViewById<View>(R.id.files_source_scroll).visibility == View.VISIBLE }
            val newContext = FilesActivity::class.java.getDeclaredField("acceptedPageContext").apply { isAccessible = true }.get(activity)
            assertTrue("restored source uses a fresh admitted actor", newContext != null && newContext !== oldContext)
            assertTrue("current file content was freshly fetched", activity.findViewById<ViewGroup>(R.id.files_source_content).descendants().filterIsInstance<TextView>().any { it.text.toString().contains("process restore content") })
        } finally {
            runCatching { controller.pause().stop().destroy() }
            testLoader.close()
            repositoryField.set(app.container, originalRepository)
            loaderField.set(app.container, originalLoader)
        }
    }

    @Test fun retainedBreadcrumbCannotReadThroughReplacementCredentialsOrAnotherPage() {
        val fixture = FilesFixture()
        val controller = Robolectric.buildActivity(FilesActivity::class.java,
            FilesActivity.intent(fixture.app, FilesTarget.Pane("w1:p1"), Scope(), directory = "docs/sub"))
            .create().start().resume().visible()
        try {
            val activity = controller.get()
            waitForMain { activity.findViewById<TextView>(R.id.files_breadcrumb).text.toString() == "/docs/sub" }
            val breadcrumb = activity.findViewById<TextView>(R.id.files_breadcrumb)
            val old = (breadcrumb.text as Spanned).getSpans(0, breadcrumb.text.length, ClickableSpan::class.java).last()
            fixture.store.save(Connection(CollieOrigin("https://replacement.files.fixture/"), "phone", "replacement"))
            // Force observation of the replacement, as every next request does in production.
            fixture.repository.readContext(Scope(), "w1:p1")
            waitForMain { activity.findViewById<View>(R.id.files_retry).visibility == View.VISIBLE }
            activity.findViewById<View>(R.id.files_retry).performClick()
            waitForMain { breadcrumb.text.toString() == "/docs/sub" }
            val readsBeforeStaleClick = fixture.reads.size
            old.onClick(breadcrumb)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("a retired breadcrumb cannot acquire replacement authority", readsBeforeStaleClick, fixture.reads.size)
            val current = (breadcrumb.text as Spanned).getSpans(0, breadcrumb.text.length, ClickableSpan::class.java)
            current[1].onClick(breadcrumb)
            waitForMain { breadcrumb.text.toString() == "/docs" }
            assertEquals("current breadcrumb navigation remains reachable", "dir:docs", fixture.reads.last())
            val afterPageChange = fixture.reads.size
            current.last().onClick(breadcrumb)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("old page callbacks cannot navigate a newer page on the same actor", afterPageChange, fixture.reads.size)
        } finally { controller.pause().stop().destroy(); fixture.close() }
    }

    @Test fun delayedCrossDocumentReadPreservesPreviewAndAppliesTheNativeHeadingAfterLayout() {
        val fixture = FilesFixture()
        val pending = CompletableDeferred<ApiResult<FilesAnswer>>()
        fixture.sourceRead = { pending.await() }
        val intent = FilesActivity.intent(fixture.app, FilesTarget.Pane("w1:p1"), Scope(),
            path = "docs/guide.md", openReference = true, anchor = "part")
        var controller = Robolectric.buildActivity(FilesActivity::class.java, intent).create().start().resume().visible()
        try {
            var activity = controller.get()
            waitForMain { fixture.reads.contains("path:docs/guide.md") }
            assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.files_progress).visibility)
            val text = "# First\n\n" + (1..50).joinToString("\n\n") { "Paragraph $it above the target." } + "\n\n# Part\n\nTarget paragraph."
            pending.complete(ApiResult.Success(FilesAnswer.Source("/repo", "docs/guide.md", text.length.toLong(), 1.0, false, false, text), 200))
            waitForMain { activity.findViewById<ViewGroup>(R.id.files_source_content).descendants().filterIsInstance<TextView>().any { it.tag == "files.heading" && it.text.toString() == "Part" } }
            measureAndLayoutWindow(activity)
            activity.window.decorView.viewTreeObserver.dispatchOnPreDraw()
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600))
            assertTrue("cross-document heading is reached by the actual native reader", activity.findViewById<android.widget.ScrollView>(R.id.files_source_scroll).scrollY > 0)
            val saved = android.os.Bundle()
            controller.saveInstanceState(saved)
            assertTrue("preview intent survives the suspended source read", saved.getBoolean("files.state.preview"))
            assertNull("a successfully applied anchor is consumed", saved.getString("files.state.anchor"))
            controller.pause().stop().destroy()

            val restored = CompletableDeferred<ApiResult<FilesAnswer>>()
            fixture.sourceRead = { restored.await() }
            val before = fixture.reads.size
            controller = Robolectric.buildActivity(FilesActivity::class.java, intent).create(saved).start().resume().visible()
            activity = controller.get()
            waitForMain { fixture.reads.size > before }
            val waiting = android.os.Bundle()
            controller.saveInstanceState(waiting)
            assertTrue("restored Preview mode is retained during the fresh read", waiting.getBoolean("files.state.preview"))
            assertNull("restoration does not resurrect the consumed launch anchor", waiting.getString("files.state.anchor"))
            // ComponentActivity saves at CREATED. Resume the actual stopped reader before its
            // suspended fresh response completes; a saved, inactive screen must not render.
            controller.pause().stop().start().resume()
            assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, activity.lifecycle.currentState)
            restored.complete(ApiResult.Success(FilesAnswer.Source("/repo", "docs/guide.md", text.length.toLong(), 1.0, false, false, text), 200))
            waitForMain(diagnostic = { filesDiagnostic(activity, fixture) }) { activity.findViewById<ViewGroup>(R.id.files_source_content).descendants().filterIsInstance<TextView>().any { it.tag == "files.heading" } }
        } finally { controller.pause().stop().destroy(); fixture.close() }
    }

    @Test fun folderStateAfterFileIntentRestoresNullPathInsteadOfTheLaunchFile() {
        val fixture = FilesFixture()
        val intent = FilesActivity.intent(fixture.app, FilesTarget.Pane("w1:p1"), Scope(), path = "docs/Main.kt")
        var controller = Robolectric.buildActivity(FilesActivity::class.java, intent).create().start().resume().visible()
        try {
            var activity = controller.get()
            waitForMain { activity.findViewById<View>(R.id.files_source_scroll).visibility == View.VISIBLE }
            activity.findViewById<View>(R.id.files_back).performClick()
            waitForMain { fixture.reads.lastOrNull() == "dir:docs" && activity.findViewById<View>(R.id.files_list).visibility == View.VISIBLE }
            val saved = android.os.Bundle()
            controller.saveInstanceState(saved)
            assertTrue("saved path presence is authoritative even when null", saved.containsKey("files.state.path"))
            assertNull(saved.getString("files.state.path"))
            controller.pause().stop().destroy()
            val previousReads = fixture.reads.size
            controller = Robolectric.buildActivity(FilesActivity::class.java, intent).create(saved).start().resume().visible()
            activity = controller.get()
            waitForMain { fixture.reads.size > previousReads && activity.findViewById<View>(R.id.files_list).visibility == View.VISIBLE }
            assertEquals("folder recreation reissues the current canonical directory", "dir:docs", fixture.reads.last())
        } finally { controller.pause().stop().destroy(); fixture.close() }
    }

    @Test
    fun directReferenceToolbarAndSystemBackReturnToCallerWithoutReadingADirectory() {
        for (systemBack in listOf(false, true)) {
            FilesFixture().use { fixture ->
                val intent = FilesActivity.intent(fixture.app, FilesTarget.Pane("p1"), Scope(),
                    path = "docs/guide.md", line = 3, openReference = true)
                val controller = Robolectric.buildActivity(FilesActivity::class.java, intent).create().start().resume().visible()
                try {
                    val activity = controller.get()
                    waitForMain { activity.findViewById<View>(R.id.files_source_scroll).isShown }
                    assertEquals("guide.md", activity.findViewById<TextView>(R.id.files_title).text.toString())
                    val readsBeforeBack = fixture.reads.toList()
                    if (systemBack) activity.onBackPressedDispatcher.onBackPressed()
                    else activity.findViewById<View>(R.id.files_back).performClick()
                    shadowOf(Looper.getMainLooper()).idle()
                    assertTrue("a direct source reference returns through the Android caller stack", activity.isFinishing)
                    assertEquals("Back must not replace the direct reference with an inferred listing", readsBeforeBack, fixture.reads)
                } finally { controller.pause().stop().destroy() }
            }
        }
    }

    @Test
    fun recreatedDirectReferenceKeepsReturnToOriginNavigationWithoutRestoringAuthority() {
        FilesFixture().use { fixture ->
            val intent = FilesActivity.intent(fixture.app, FilesTarget.Pane("p1"), Scope(),
                path = "docs/guide.md", openReference = true)
            var controller = Robolectric.buildActivity(FilesActivity::class.java, intent).create().start().resume().visible()
            try {
                var activity = controller.get()
                waitForMain { activity.findViewById<View>(R.id.files_source_scroll).isShown }
                val saved = android.os.Bundle()
                controller.saveInstanceState(saved)
                assertTrue(saved.getBoolean("files.state.reference-return"))
                controller.pause().stop().destroy()
                val previousReads = fixture.reads.size
                controller = Robolectric.buildActivity(FilesActivity::class.java, intent).create(saved).start().resume().visible()
                activity = controller.get()
                waitForMain { fixture.reads.size > previousReads && activity.findViewById<View>(R.id.files_source_scroll).isShown }
                assertEquals("path:docs/guide.md", fixture.reads.last())
                val readsBeforeBack = fixture.reads.toList()
                activity.onBackPressedDispatcher.onBackPressed()
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue(activity.isFinishing)
                assertEquals(readsBeforeBack, fixture.reads)
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test
    fun deliberateReferenceBreadcrumbBrowsingKeepsNormalHierarchyAfterRecreation() {
        FilesFixture().use { fixture ->
            val intent = FilesActivity.intent(fixture.app, FilesTarget.Pane("p1"), Scope(),
                path = "docs/guide.md", openReference = true)
            var controller = Robolectric.buildActivity(FilesActivity::class.java, intent).create().start().resume().visible()
            try {
                var activity = controller.get()
                waitForMain { activity.findViewById<View>(R.id.files_source_scroll).isShown }
                val breadcrumb = activity.findViewById<TextView>(R.id.files_breadcrumb)
                val spans = breadcrumb.text as Spanned
                val docsOffset = breadcrumb.text.toString().indexOf("docs")
                assertTrue(docsOffset >= 0)
                val currentDocs = spans.getSpans(docsOffset, docsOffset + 4, ClickableSpan::class.java).single()
                currentDocs.onClick(breadcrumb)
                waitForMain { fixture.reads.lastOrNull() == "dir:docs" && activity.findViewById<View>(R.id.files_list).isShown }
                assertFalse(activity.isFinishing)
                val saved = android.os.Bundle()
                controller.saveInstanceState(saved)
                assertFalse(saved.getBoolean("files.state.reference-return", true))
                controller.pause().stop().destroy()
                val previousReads = fixture.reads.size
                controller = Robolectric.buildActivity(FilesActivity::class.java, intent).create(saved).start().resume().visible()
                activity = controller.get()
                waitForMain { fixture.reads.size > previousReads && activity.findViewById<View>(R.id.files_list).isShown }
                assertEquals("dir:docs", fixture.reads.last())
                activity.findViewById<View>(R.id.files_back).performClick()
                waitForMain { fixture.reads.lastOrNull() == "dir:" && activity.findViewById<View>(R.id.files_list).isShown }
                assertFalse("ordinary nested-directory Back returns to its parent", activity.isFinishing)
                activity.findViewById<View>(R.id.files_back).performClick()
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue("the normal root listing still returns to its caller", activity.isFinishing)
            } finally { controller.pause().stop().destroy() }
        }
    }

    private class FilesFixture : AutoCloseable {
        val app = ApplicationProvider.getApplicationContext<CollieApplication>()
        private val originalRepository = app.container.repository
        private val originalLoader = app.container.imageLoader
        private val repositoryField = app.container.javaClass.getDeclaredField("repository").apply { isAccessible = true }
        private val loaderField = app.container.javaClass.getDeclaredField("imageLoader").apply { isAccessible = true }
        val store = TestStore(Connection(CollieOrigin("https://files.actor.fixture/"), "phone", "token"))
        val reads = mutableListOf<String>()
        var sourceRead: suspend (String) -> ApiResult<FilesAnswer> = { path ->
            ApiResult.Success(FilesAnswer.Source("/repo", path, 4, 1.0, false, false, "text"), 200)
        }
        private val api = object : CollieApi by app.container.api {
            override suspend fun files(connection: Connection, target: FilesTarget, scope: Scope, path: RelativeFilePath?, dir: RelativeFilePath?, ignored: Boolean): ApiResult<FilesAnswer> {
                reads += if (path != null) "path:${path.value}" else "dir:${dir?.value.orEmpty()}"
                if (path != null) return sourceRead(path.value)
                val directory = dir?.value.orEmpty()
                return if (directory.endsWith(".md") || directory.endsWith(".kt")) ApiResult.Failure(ApiFailure.Http(404, "unknown-path", "unknown-path"))
                else ApiResult.Success(FilesAnswer.Listing("/repo", directory, emptyList(), false), 200)
            }
            override suspend fun filesExist(connection: Connection, target: FilesTarget, scope: Scope, paths: List<RelativeFilePath>) =
                ApiResult.Success(FilesExistAnswer(paths.map { it.value }.toSet()), 200)
        }
        val repository = CollieRepository(api, store, app.container.originValidator)
        private val loader = ScopedImageLoader(repository)
        init { repositoryField.set(app.container, repository); loaderField.set(app.container, loader) }
        override fun close() {
            loader.close()
            repositoryField.set(app.container, originalRepository)
            loaderField.set(app.container, originalLoader)
        }
    }

    private fun clickVisibleFileRow(activity: FilesActivity, position: Int) {
        val recycler = activity.findViewById<RecyclerView>(R.id.files_list)
        val holder = recycler.findViewHolderForAdapterPosition(position) ?: error("visible Files row has not attached after measured layout")
        assertTrue("the selected row is the actual attached screen control", holder.itemView.isAttachedToWindow)
        assertTrue("visible Files row handles selection", holder.itemView.performClick())
    }

    private fun measureAndLayoutWindow(activity: FilesActivity) {
        val decor = activity.window.decorView
        val metrics = activity.resources.displayMetrics
        val width = metrics.widthPixels.coerceAtLeast(1)
        val height = metrics.heightPixels.coerceAtLeast(1)
        decor.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        decor.layout(0, 0, width, height)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun filesDiagnostic(activity: FilesActivity, fixture: FilesFixture): String {
        val model = FilesActivity::class.java.getDeclaredField("viewModel").apply { isAccessible = true }.get(activity) as FilesViewModel
        val state = model.state.value
        fun field(name: String) = FilesActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
        return "reads=${fixture.reads}; lifecycle=${activity.lifecycle.currentState}; loading=${state.loading}; error=${state.error}; path=${state.path}; answer=${state.answer?.javaClass?.simpleName}; accepted=${state.acceptedContext != null}; current=${state.acceptedContext?.let(fixture.repository::isCurrent)}; sameActivityActor=${state.acceptedContext === field("acceptedPageContext")}; preview=${field("showPreview")}; anchor=${field("pendingAnchor")}; headings=${activity.findViewById<ViewGroup>(R.id.files_source_content).descendants().filterIsInstance<TextView>().filter { it.tag == "files.heading" }.map { it.text.toString() }}"
    }

    private fun waitForMain(diagnostic: () -> String = { "" }, condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < until) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertTrue("UI condition completes before timeout: ${if (condition()) "" else diagnostic()}", condition())
    }

    private fun ViewGroup.descendants(): List<View> = buildList {
        fun visit(group: ViewGroup) {
            for (index in 0 until group.childCount) {
                val child = group.getChildAt(index)
                add(child)
                if (child is ViewGroup) visit(child)
            }
        }
        visit(this@descendants)
    }

    private class TestStore(initial: Connection) : ConnectionStore {
        override val connection = MutableStateFlow<Connection?>(initial)
        override fun save(connection: Connection) { this.connection.value = connection }
        override fun clear() { connection.value = null }
    }
}
