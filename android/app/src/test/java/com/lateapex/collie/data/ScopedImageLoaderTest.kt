package com.lateapex.collie.data

import android.graphics.Bitmap
import android.os.Looper
import android.util.Base64
import com.lateapex.collie.domain.*
import com.lateapex.collie.network.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScopedImageLoaderTest {
    private val connection = Connection(CollieOrigin("https://collie.example/mount/"), "phone", "private")

    @Test fun foreignOriginsAndMismatchedScopedBlobQueriesAreRefusedBeforeTransport() {
        val api = FakeApi()
        val repository = CollieRepository(api, Store(connection), OriginValidator())
        val scope = Scope("peer", "work")
        repository.selectSubject(scope, "pane-1")
        val loader = ScopedImageLoader(repository)
        val context = repository.readContext(scope, "pane-1")!!
        val results = mutableListOf<ScopedImageResult>()
        val completed = java.util.concurrent.CountDownLatch(5)
        val callback: (ScopedImageResult) -> Unit = { synchronized(results) { results += it }; completed.countDown() }

        loader.load(context, "https://outside.example/image.png", 200, 120, callback = callback)
        loader.load(context, "//outside.example/api/blobs/${"ab".repeat(32)}", 200, 120, callback = callback)
        loader.load(context, "/api/blobs/${"ab".repeat(32)}?host=other&session=work", 200, 120, callback = callback)
        loader.load(context, "data:image/png;base64,AAAA", 200, 120, callback = callback)
        loader.load(context, "data:image/svg+xml;base64,PHN2Zy8+", 200, 120, callback = callback)

        pumpMainUntil(completed)
        assertEquals(5, synchronized(results) { results.size })
        assertEquals(4, synchronized(results) { results.count { it is ScopedImageResult.Refused } })
        assertEquals(1, synchronized(results) { results.count { it === ScopedImageResult.Unsupported } })
        assertEquals(0, api.imageReads)
        loader.close()
    }

    @Test fun inlineDataUriOverTheTenMibLimitIsRefusedBeforeDecode() {
        val repository = CollieRepository(FakeApi(), Store(connection), OriginValidator())
        val scope = Scope()
        repository.selectSubject(scope, "pane-1")
        val loader = ScopedImageLoader(repository)
        val context = repository.readContext(scope, "pane-1")!!
        val oversized = "data:image/png;base64," + "A".repeat(14 * 1024 * 1024)
        assertTrue(loadAndWait(loader, context, oversized, waitMillis = 10_000) is ScopedImageResult.Refused)
        loader.close()
    }

    @Test fun compressedRasterAdvertisingMoreThanSixteenMillionPixelsIsRefusedBeforeDecode() {
        val repository = CollieRepository(FakeApi(), Store(connection), OriginValidator())
        repository.selectSubject(Scope(), "pane-1")
        val loader = ScopedImageLoader(repository)
        val context = repository.readContext(Scope(), "pane-1")!!
        val bytes = oversizedCompressedPng(width = 5_000, height = 4_000)
        assertTrue("fixture is a compact compressed raster, not a large decoded allocation", bytes.size < 100_000)
        val dataUri = "data:image/png;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)

        val result = loadAndWait(loader, context, dataUri)

        assertSame("20 MP PNG is rejected by the source dimension gate", ScopedImageResult.Unsupported, result)
        assertEquals("oversized dimensions never reserve or commit decoded memory", 0L, loader.residentBytesForTest())
        assertEquals(0L, loader.committedBitmapBytesForTest())
        loader.close()
    }

    @Test fun supportedFormatsDecodeOnApi26AndRobolectricApi35AndRetirementNotifiesVisibleActors() {
        val repository = CollieRepository(FakeApi(), Store(connection), OriginValidator())
        repository.selectSubject(Scope(), "pane-1")
        val loader = ScopedImageLoader(repository)
        val context = repository.readContext(Scope(), "pane-1")!!
        var retired = false
        val retirement = loader.onRetired { retired = true }
        assertInlineDecodes(loader, context, "image/png", Bitmap.CompressFormat.PNG)
        assertInlineDecodes(loader, context, "image/jpeg", Bitmap.CompressFormat.JPEG)
        assertInlineDecodes(loader, context, "image/webp", Bitmap.CompressFormat.WEBP)
        val gif = "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7"
        assertInlineDecodes(loader, context, gif)
        loader.retire()
        assertTrue(retired)
        retirement.close()
        loader.close()
    }

    @Test fun filesImagesShareScopedDecoderAndCacheOnlyWhenSourceAndImageVersionsMatch() {
        val api = FakeApi()
        val repository = CollieRepository(api, Store(connection), OriginValidator())
        repository.selectSubject(Scope(), "pane-1")
        val loader = ScopedImageLoader(repository)
        val context = repository.readContext(Scope(), "pane-1")!!
        val bytes = Base64.decode(inlinePng(0xff227744.toInt(), 8).substringAfter(','), Base64.NO_WRAP)
        val path = RelativeFilePath.parse("docs/diagram.png")!!
        api.fileImageValue = FilesImageBlob("image/png", bytes, bytes.size.toLong(), 1.25)

        repeat(2) {
            val result = loadFileAndWait(loader, context, FilesTarget.Pane("pane-1"), path, bytes.size.toLong(), 1.25)
            assertTrue(result is ScopedImageResult.Ready)
            (result as ScopedImageResult.Ready).lease.close()
        }
        assertEquals("matching size and fractional mtime permit the existing scoped cache", 1, api.fileImageReads)

        api.fileImageValue = FilesImageBlob("image/png", bytes, bytes.size.toLong() + 1, 1.25)
        repeat(2) {
            val result = loadFileAndWait(loader, context, FilesTarget.Pane("pane-1"), path, bytes.size.toLong() + 2, 1.25)
            assertTrue(result is ScopedImageResult.Ready)
            (result as ScopedImageResult.Ready).lease.close()
        }
        assertEquals("a changed image version is decoded each time and never retained", 3, api.fileImageReads)
        loader.close()
    }

    @Test fun pinnedFilePixelsAreNeverReusedWithoutAnExactSourceVersion() {
        val path = RelativeFilePath.parse("docs/diagram.png")!!
        for (mismatched in listOf(false, true)) {
            val api = FakeApi()
            val repository = CollieRepository(api, Store(connection), OriginValidator())
            repository.selectSubject(Scope(), "pane-1")
            val loader = ScopedImageLoader(repository)
            val context = repository.readContext(Scope(), "pane-1")!!
            val sourceSize = if (mismatched) 100L else null
            val sourceMtime = if (mismatched) 1.0 else null
            val firstBytes = Base64.decode(inlinePng(0xffcc2200.toInt(), 8).substringAfter(','), Base64.NO_WRAP)
            val secondBytes = Base64.decode(inlinePng(0xff1166dd.toInt(), 8).substringAfter(','), Base64.NO_WRAP)
            api.fileImageValue = FilesImageBlob("image/png", firstBytes, if (mismatched) 101 else null, if (mismatched) 1.0 else null)
            val first = loadFileAndWait(loader, context, FilesTarget.Pane("pane-1"), path, sourceSize, sourceMtime) as ScopedImageResult.Ready
            val firstBitmap = first.lease.bitmap
            val firstPixel = firstBitmap.getPixel(2, 2)

            api.fileImageValue = FilesImageBlob("image/png", secondBytes, if (mismatched) 102 else null, if (mismatched) 1.0 else null)
            val second = loadFileAndWait(loader, context, FilesTarget.Pane("pane-1"), path, sourceSize, sourceMtime) as ScopedImageResult.Ready
            val secondBitmap = second.lease.bitmap
            assertTrue("changed no-version or mismatched-version response returns new pixels", firstPixel != secondBitmap.getPixel(2, 2))
            assertFalse("both independent leases are resident while pinned", firstBitmap.isRecycled || secondBitmap.isRecycled)
            assertTrue("both allocations count against one residency budget", loader.residentBytesForTest() >= firstBitmap.allocationByteCount + secondBitmap.allocationByteCount)
            second.lease.close()
            assertTrue("the second uncached bitmap recycles at its last release", secondBitmap.isRecycled)
            assertFalse("the first pinned bitmap remains live", firstBitmap.isRecycled)
            first.lease.close()
            assertTrue("the first bitmap recycles at its last release", firstBitmap.isRecycled)
            assertEquals(0L, loader.residentBytesForTest())
            loader.close()
        }
    }

    private fun loadFileAndWait(
        loader: ScopedImageLoader,
        context: ScopedReadContext,
        target: FilesTarget,
        path: RelativeFilePath,
        sourceSize: Long?,
        sourceMtime: Double?,
    ): ScopedImageResult {
        val done = java.util.concurrent.CountDownLatch(1)
        var result: ScopedImageResult? = null
        loader.loadFile(context, target, path, sourceSize, sourceMtime, 120, 120, callback = { result = it; done.countDown() })
        pumpMainUntil(done)
        return result ?: error("Files image read did not complete")
    }

    private fun assertInlineDecodes(loader: ScopedImageLoader, context: ScopedReadContext, mime: String, format: Bitmap.CompressFormat) {
        val output = ByteArrayOutputStream()
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).compress(format, 100, output)
        assertInlineDecodes(loader, context, "data:$mime;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP))
    }

    private fun assertInlineDecodes(loader: ScopedImageLoader, context: ScopedReadContext, dataUri: String) {
        val done = java.util.concurrent.CountDownLatch(1)
        var result: ScopedImageResult? = null
        loader.load(context, dataUri, 240, 180, callback = { result = it; done.countDown() })
        pumpMainUntil(done)
        assertTrue("image decode did not complete", done.await(0, TimeUnit.MILLISECONDS))
        assertTrue("image decode refused $dataUri: $result", result is ScopedImageResult.Ready)
        (result as ScopedImageResult.Ready).lease.close()
    }

    @Test fun scopeRetirementCancelsAnInFlightBlobAndSuppressesItsLateViewCallback() = runBlocking {
        val api = FakeApi()
        val entered = CompletableDeferred<Unit>()
        api.imageBlock = { entered.complete(Unit); awaitCancellation() }
        val repository = CollieRepository(api, Store(connection), OriginValidator())
        val oldScope = Scope("peer", "work")
        repository.selectSubject(oldScope, "pane-1")
        val loader = ScopedImageLoader(repository)
        val context = repository.readContext(oldScope, "pane-1")!!
        var callbacks = 0
        loader.load(context, "/api/blobs/${"ab".repeat(32)}?host=peer&session=work", 240, 180, callback = { callbacks++ })

        withTimeout(2_000) { entered.await() }
        repository.selectSubject(Scope("elsewhere", "next"), "pane-2")
        delay(50)

        assertEquals(0, callbacks)
        assertFalse(loader.isCurrent(context))
        loader.close()
    }

    @Test fun cancellingAfterDecodeBeforeMainDeliveryReleasesTheReservedReadyLease() {
        val repository = CollieRepository(FakeApi(), Store(connection), OriginValidator())
        repository.selectSubject(Scope(), "pane-1")
        val loader = ScopedImageLoader(repository)
        val context = repository.readContext(Scope(), "pane-1")!!
        var callbacks = 0
        val request = loader.load(context, inlinePng(0xff005577.toInt(), 32), 32, 32, callback = { callbacks++ })
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (loader.pendingDeliveriesForTest() == 0 && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals("Ready callback should be queued before cancellation", 1, loader.pendingDeliveriesForTest())

        request.close()
        pumpMainUntilNoPendingDelivery(loader)

        assertEquals(0, callbacks)
        loader.retire()
        assertEquals("canceled queued result must release its lease before retirement", 0L, loader.residentBytesForTest())
        loader.close()
    }

    @Test fun missingBlobStillDeliversUnavailablePlaceholderWithoutRetiringCurrentScope() {
        val api = FakeApi()
        val repository = CollieRepository(api, Store(connection), OriginValidator())
        repository.selectSubject(Scope(), "pane-1")
        val loader = ScopedImageLoader(repository)
        val context = repository.readContext(Scope(), "pane-1")!!

        val result = loadAndWait(loader, context, "/api/blobs/${"ee".repeat(32)}")

        assertEquals(1, api.imageReads)
        assertEquals(ScopedImageResult.Refused("Image is no longer available"), result)
        assertTrue("ordinary 404 does not retire the captured read context", repository.isCurrent(context))
        assertTrue(loader.isCurrent(context))
        loader.close()
    }

    @Test fun authenticationFailuresRetireImageAuthorityAndSuppressReadyCallback() {
        for (status in listOf(401, 403)) {
            val api = FakeApi().apply { imageBlock = { ApiResult.Failure(ApiFailure.Http(status, null, null)) } }
            val repository = CollieRepository(api, Store(connection), OriginValidator())
            repository.selectSubject(Scope(), "pane-1")
            val loader = ScopedImageLoader(repository)
            val context = repository.readContext(Scope(), "pane-1")!!
            var callbacks = 0

            loader.load(context, "/api/blobs/${"ef".repeat(32)}", 100, 80, callback = { callbacks++ })
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (loader.isCurrent(context) && System.nanoTime() < deadline) Thread.sleep(10)
            pumpMainUntilNoPendingDelivery(loader)

            assertFalse("HTTP $status retires the captured authority", loader.isCurrent(context))
            assertEquals("HTTP $status cannot deliver an image result", 0, callbacks)
            assertEquals(0L, loader.residentBytesForTest())
            loader.close()
        }
    }

    @Test fun changedLeadOnBlobResponseRetiresTheActorBeforeAnyReadyImageCanArrive() {
        val png = Base64.decode(inlinePng(0xff225577.toInt(), 8).substringAfter(','), Base64.DEFAULT)
        val api = FakeApi().apply {
            snapshotResult = ApiResult.Success(
                SnapshotResponse(
                    bridge = "connected",
                    controlAuthority = ControlAuthority("lead-a", "epoch-1"),
                    agents = emptyList(), shellPanes = emptyList(), workspaces = emptyList(), tabs = emptyList(), ts = 1,
                ),
                200,
                controlAuthority = ControlAuthority("lead-a", "epoch-1"),
            )
            imageBlock = { ApiResult.Success(ImageBlob("image/png", png), 200, controlAuthority = ControlAuthority("lead-b", "epoch-2")) }
        }
        val repository = CollieRepository(api, Store(connection), OriginValidator())
        val scope = Scope("peer", "work")
        repository.selectSubject(scope, "pane-1")
        val beforeSnapshot = repository.readContext(scope, "pane-1")!!
        assertTrue(runBlocking { repository.snapshot(scope) } is ApiResult.Success)
        val imageContext = repository.readContext(scope, "pane-1")!!
        val loader = ScopedImageLoader(repository)
        var readyCallbacks = 0
        loader.load(imageContext, "/api/blobs/${"ab".repeat(32)}", 80, 80, callback = { result ->
            if (result is ScopedImageResult.Ready) readyCallbacks++
        })
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (repository.isCurrent(imageContext) && System.nanoTime() < deadline) Thread.sleep(10)
        pumpMainUntilNoPendingDelivery(loader)

        assertEquals(1, api.imageReads)
        assertFalse("changed answering lead retires the captured image authority", repository.isCurrent(imageContext))
        assertEquals("blob response authority prevents stale image Ready delivery", 0, readyCallbacks)
        assertEquals(0L, loader.residentBytesForTest())
        loader.close()
    }

    @Test fun ordinaryNetworkAndServerFailuresReachExplicitUnavailablePlaceholder() {
        val failures = listOf(
            ApiFailure.Network("offline"),
            ApiFailure.Http(503, "busy", null),
        )
        for (failure in failures) {
            val api = FakeApi().apply { imageBlock = { ApiResult.Failure(failure) } }
            val repository = CollieRepository(api, Store(connection), OriginValidator())
            repository.selectSubject(Scope(), "pane-1")
            val loader = ScopedImageLoader(repository)
            val context = repository.readContext(Scope(), "pane-1")!!

            val result = loadAndWait(loader, context, "/api/blobs/${"fa".repeat(32)}")

            assertEquals(ScopedImageResult.Refused("Image could not be loaded"), result)
            assertTrue("ordinary media failure does not retire the captured context", repository.isCurrent(context))
            loader.close()
        }
    }

    @Test fun authorityRetirementBeforeMainDeliveryReleasesTheQueuedReadyLease() {
        val repository = CollieRepository(FakeApi(), Store(connection), OriginValidator())
        val firstScope = Scope("peer", "work")
        repository.selectSubject(firstScope, "pane-1")
        val loader = ScopedImageLoader(repository)
        val context = repository.readContext(firstScope, "pane-1")!!
        var callbacks = 0
        loader.load(context, inlinePng(0xff117755.toInt(), 32), 32, 32, callback = { callbacks++ })
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (loader.committedBitmapBytesForTest() == 0L && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("decode should commit its Ready bitmap before posting the result", loader.committedBitmapBytesForTest() > 0)

        repository.selectSubject(Scope("other", "next"), "pane-2")
        pumpMainUntilReleased(loader)

        assertEquals(0, callbacks)
        assertEquals("retired queued result must release its lease", 0L, loader.residentBytesForTest())
        loader.close()
    }

    @Test fun directLoaderRetirementFencesQueuedReadyEvenWhenRepositoryContextIsUnchanged() {
        val repository = CollieRepository(FakeApi(), Store(connection), OriginValidator())
        repository.selectSubject(Scope("peer", "work"), "pane-1")
        val loader = ScopedImageLoader(repository)
        val context = repository.readContext(Scope("peer", "work"), "pane-1")!!
        var callbacks = 0
        loader.load(context, inlinePng(0xff334455.toInt(), 32), 32, 32, callback = { callbacks++ })
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (loader.residentBytesForTest() == 0L && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue("decode should have committed its undelivered Ready lease", loader.residentBytesForTest() > 0)

        loader.retire()
        pumpMainUntilReleased(loader)

        assertTrue("explicit image retirement does not need to replace repository context", loader.isCurrent(context))
        assertEquals("retirement-generation fence must suppress delivery", 0, callbacks)
        assertEquals("undelivered Ready lease must be released", 0L, loader.residentBytesForTest())
        loader.close()
    }

    @Test fun blobMimeHeaderMustMatchSniffedRasterBytes() {
        val api = FakeApi()
        val output = ByteArrayOutputStream()
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.JPEG, 100, output)
        api.imageBlock = { ApiResult.Success(ImageBlob("image/png", output.toByteArray()), 200) }
        val repository = CollieRepository(api, Store(connection), OriginValidator())
        repository.selectSubject(Scope(), "pane-1")
        val loader = ScopedImageLoader(repository)
        val context = repository.readContext(Scope(), "pane-1")!!
        val result = loadAndWait(loader, context, "/api/blobs/${"ab".repeat(32)}")

        assertSame(ScopedImageResult.Unsupported, result)
        assertEquals(1, api.imageReads)
        loader.close()
    }

    @Test fun missingAuthenticatedBlobProducesVisibleUnavailableState() {
        val api = FakeApi()
        val repository = CollieRepository(api, Store(connection), OriginValidator())
        repository.selectSubject(Scope(), "pane-1")
        val loader = ScopedImageLoader(repository)
        val context = repository.readContext(Scope(), "pane-1")!!

        val result = loadAndWait(loader, context, "/api/blobs/${"cd".repeat(32)}") as ScopedImageResult.Refused

        assertEquals("Image is no longer available", result.message)
        assertEquals(1, api.imageReads)
        loader.close()
    }

    @Test fun cachedBitmapDoesNotBypassTheNextCardsDeclaredMimeCheck() {
        val repository = CollieRepository(FakeApi(), Store(connection), OriginValidator())
        repository.selectSubject(Scope(), "pane-1")
        val loader = ScopedImageLoader(repository)
        val context = repository.readContext(Scope(), "pane-1")!!
        val output = ByteArrayOutputStream()
        Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, output)
        val reference = "data:image/png;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)

        val ready = loadAndWait(loader, context, reference) as ScopedImageResult.Ready
        ready.lease.close()
        assertSame(ScopedImageResult.Unsupported, loadAndWait(loader, context, reference, expectedMimeType = "image/jpeg"))
        loader.close()
    }

    @Test fun warmCacheReleaseAndReacquireKeepResidentAccountingStable() {
        val repository = CollieRepository(FakeApi(), Store(connection), OriginValidator())
        repository.selectSubject(Scope(), "pane-1")
        val loader = ScopedImageLoader(repository)
        val context = repository.readContext(Scope(), "pane-1")!!
        val reference = inlinePng(0xff774422.toInt(), 8)

        val first = loadAndWait(loader, context, reference, expectedMimeType = "image/png") as ScopedImageResult.Ready
        first.lease.close()
        val cachedResidentBytes = loader.residentBytesForTest()
        assertTrue("released bitmap should remain cached", cachedResidentBytes > 0)

        val second = loadAndWait(loader, context, reference, expectedMimeType = "image/png") as ScopedImageResult.Ready
        assertEquals("acquiring a warm entry must not double-count cached bytes", cachedResidentBytes, loader.residentBytesForTest())
        second.lease.close()
        assertEquals("returning the last lease to cache must keep the same resident count", cachedResidentBytes, loader.residentBytesForTest())
        loader.retire()
        assertEquals("retiring the unpinned cache must clear its accounting", 0L, loader.residentBytesForTest())
        assertTrue("retirement recycles the bitmap after the last lease closes", second.lease.bitmap.isRecycled)
        loader.close()
    }

    @Test fun unpinnedEvictionAndOversizedCacheReleaseRecycleOnlyAfterTheLastLease() {
        val repository = CollieRepository(FakeApi(), Store(connection), OriginValidator())
        repository.selectSubject(Scope(), "pane-1")
        val context = repository.readContext(Scope(), "pane-1")!!
        val evictionLoader = ScopedImageLoader(repository, maxCacheBytes = 5_000, maxResidentBytes = 1024 * 1024)
        val first = loadAtAndWait(evictionLoader, context, inlinePng(0xff884422.toInt(), 32), 32, 32) as ScopedImageResult.Ready
        val firstBitmap = first.lease.bitmap
        first.lease.close()
        assertFalse("a cache entry stays valid while it is retained", firstBitmap.isRecycled)

        val second = loadAtAndWait(evictionLoader, context, inlinePng(0xff226688.toInt(), 32), 32, 32) as ScopedImageResult.Ready
        second.lease.close()
        assertTrue("evicting an unpinned entry releases its decoded allocation", firstBitmap.isRecycled)
        assertFalse("the new cached bitmap remains valid", second.lease.bitmap.isRecycled)
        evictionLoader.retire()
        assertTrue("retirement also releases the remaining unpinned bitmap", second.lease.bitmap.isRecycled)
        evictionLoader.close()

        val uncachedLoader = ScopedImageLoader(repository, maxCacheBytes = 128, maxResidentBytes = 1024 * 1024)
        val uncached = loadAtAndWait(uncachedLoader, context, inlinePng(0xff335577.toInt(), 8), 8, 8) as ScopedImageResult.Ready
        val uncachedBitmap = uncached.lease.bitmap
        assertFalse("active lease prevents recycling", uncachedBitmap.isRecycled)
        uncached.lease.close()
        assertTrue("last release recycles an entry larger than the cache target", uncachedBitmap.isRecycled)
        assertEquals(0L, uncachedLoader.residentBytesForTest())
        uncachedLoader.close()
    }

    @Test fun cardAndViewerLeasesShareOneBoundedResidentBudgetUntilBothRelease() {
        val repository = CollieRepository(FakeApi(), Store(connection), OriginValidator())
        repository.selectSubject(Scope(), "pane-1")
        val loader = ScopedImageLoader(repository, maxCacheBytes = 1024 * 1024, maxResidentBytes = 4 * 1024 * 1024)
        val context = repository.readContext(Scope(), "pane-1")!!
        val firstReference = inlinePng(0xff224466.toInt(), 600)
        val secondReference = inlinePng(0xffaa3311.toInt(), 600)
        val firstCard = loadAtAndWait(loader, context, firstReference, 600, 600) as ScopedImageResult.Ready
        val viewer = firstCard.lease.retain()!!
        val bitmap = firstCard.lease.bitmap

        assertTrue(loader.residentBytesForTest() in 1..(4L * 1024 * 1024))
        assertTrue(loadAtAndWait(loader, context, secondReference, 600, 600) is ScopedImageResult.Refused)
        firstCard.lease.close()
        assertFalse("viewer lease keeps the bitmap alive after card release", bitmap.isRecycled)
        assertTrue("fullscreen viewer must keep its separate lease", loader.residentBytesForTest() > 0)
        assertTrue(loadAtAndWait(loader, context, secondReference, 600, 600) is ScopedImageResult.Refused)
        viewer.close()
        assertEquals(0L, loader.residentBytesForTest())
        assertTrue("last viewer release recycles the retired uncached bitmap", bitmap.isRecycled)

        val secondCard = loadAtAndWait(loader, context, secondReference, 600, 600) as ScopedImageResult.Ready
        assertTrue(loader.residentBytesForTest() <= 4L * 1024 * 1024)
        secondCard.lease.close()
        loader.close()
    }

    @Test fun decodedRasterIsDownsampledToItsCardViewport() {
        val repository = CollieRepository(FakeApi(), Store(connection), OriginValidator())
        repository.selectSubject(Scope(), "pane-1")
        val loader = ScopedImageLoader(repository)
        val context = repository.readContext(Scope(), "pane-1")!!
        val reference = inlinePng(0xff667788.toInt(), 512)

        val ready = loadAtAndWait(loader, context, reference, 128, 128) as ScopedImageResult.Ready

        assertTrue(ready.lease.bitmap.width <= 128)
        assertTrue(ready.lease.bitmap.height <= 128)
        ready.lease.close()
        loader.close()
    }

    private fun loadAndWait(
        loader: ScopedImageLoader,
        context: ScopedReadContext,
        ref: String,
        waitMillis: Long = 5_000,
        expectedMimeType: String? = null,
    ): ScopedImageResult? {
        val done = java.util.concurrent.CountDownLatch(1)
        var result: ScopedImageResult? = null
        loader.load(context, ref, 240, 180, callback = { result = it; done.countDown() }, expectedMimeType = expectedMimeType)
        pumpMainUntil(done, waitMillis)
        assertTrue("image request did not complete", done.await(0, TimeUnit.MILLISECONDS))
        return result
    }

    private fun pumpMainUntil(done: java.util.concurrent.CountDownLatch, waitMillis: Long = 5_000) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMillis)
        while (done.count > 0 && System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    private fun loadAtAndWait(loader: ScopedImageLoader, context: ScopedReadContext, ref: String, width: Int, height: Int): ScopedImageResult? {
        val done = java.util.concurrent.CountDownLatch(1)
        var result: ScopedImageResult? = null
        loader.load(context, ref, width, height, callback = { result = it; done.countDown() })
        pumpMainUntil(done)
        assertTrue("image request did not complete", done.await(0, TimeUnit.MILLISECONDS))
        return result
    }

    private fun inlinePng(color: Int, side: Int): String {
        val output = ByteArrayOutputStream()
        Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).apply {
            eraseColor(color)
            compress(Bitmap.CompressFormat.PNG, 100, output)
            recycle()
        }
        return "data:image/png;base64," + Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    }

    private fun oversizedCompressedPng(width: Int, height: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val data = DataOutputStream(output)
        data.write(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
        val ihdr = ByteArrayOutputStream().also { header ->
            DataOutputStream(header).use { fields ->
                fields.writeInt(width)
                fields.writeInt(height)
                fields.write(byteArrayOf(8, 6, 0, 0, 0))
            }
        }.toByteArray()
        writePngChunk(data, "IHDR", ihdr)

        val compressed = ByteArrayOutputStream()
        DeflaterOutputStream(compressed).use { deflater ->
            val row = ByteArray(1 + width * 4) // filter byte plus a fully transparent RGBA row
            repeat(height) { deflater.write(row) }
        }
        writePngChunk(data, "IDAT", compressed.toByteArray())
        writePngChunk(data, "IEND", byteArrayOf())
        data.flush()
        return output.toByteArray()
    }

    private fun writePngChunk(output: DataOutputStream, kind: String, bytes: ByteArray) {
        val type = kind.toByteArray(Charsets.US_ASCII)
        output.writeInt(bytes.size)
        output.write(type)
        output.write(bytes)
        val crc = CRC32().apply { update(type); update(bytes) }
        output.writeInt(crc.value.toInt())
    }

    private fun pumpMainUntilReleased(loader: ScopedImageLoader) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (loader.residentBytesForTest() > 0 && System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    private fun pumpMainUntilNoPendingDelivery(loader: ScopedImageLoader) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (loader.pendingDeliveriesForTest() > 0 && System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertEquals("main-thread result delivery should settle", 0, loader.pendingDeliveriesForTest())
    }

    private class Store(initial: Connection) : ConnectionStore {
        private val mutable = MutableStateFlow<Connection?>(initial)
        override val connection: StateFlow<Connection?> = mutable
        override fun save(connection: Connection) { mutable.value = connection }
        override fun clear() { mutable.value = null }
    }

    private class FakeApi : CollieApi {
        var imageReads = 0
        var fileImageReads = 0
        var fileImageValue = FilesImageBlob(null, byteArrayOf(1), 1, 1.0)
        var snapshotResult: ApiResult<SnapshotResponse> = ApiResult.Failure(ApiFailure.Protocol("unused"))
        var imageBlock: suspend () -> ApiResult<ImageBlob> = { ApiResult.Failure(ApiFailure.Http(404, null, null)) }
        override suspend fun health(connection: Connection) = ApiResult.Failure(ApiFailure.Protocol("unused"))
        override suspend fun snapshot(connection: Connection, scope: Scope): ApiResult<SnapshotResponse> = snapshotResult
        override suspend fun imageBlob(connection: Connection, scope: Scope, sha256: String): ApiResult<ImageBlob> {
            imageReads++
            return imageBlock()
        }
        override suspend fun fileImage(connection: Connection, target: FilesTarget, scope: Scope, path: RelativeFilePath): ApiResult<FilesImageBlob> {
            fileImageReads++
            return ApiResult.Success(fileImageValue, 200)
        }
        override suspend fun pane(connection: Connection, address: PaneAddress, lines: Int, etag: String?, markSeen: Boolean) = ApiResult.Failure(ApiFailure.Protocol("unused"))
        override suspend fun refresh(connection: Connection, scope: Scope) = ApiResult.Failure(ApiFailure.Protocol("unused"))
        override suspend fun pair(origin: CollieOrigin, code: String, label: String) = ApiResult.Failure(ApiFailure.Protocol("unused"))
        override suspend fun devices(connection: Connection): ApiResult<DevicesResponse> = ApiResult.Failure(ApiFailure.Protocol("unused"))
        override suspend fun reply(connection: Connection, address: PaneAddress, text: String, submit: Boolean, expectedPrompt: String?) = ApiResult.Failure(ApiFailure.Protocol("unused"))
        override suspend fun keys(connection: Connection, address: PaneAddress, keys: List<String>, expectedPrompt: String?) = ApiResult.Failure(ApiFailure.Protocol("unused"))
    }
}
        
