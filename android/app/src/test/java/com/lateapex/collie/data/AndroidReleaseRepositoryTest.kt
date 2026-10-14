package com.lateapex.collie.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.net.InetAddress
import java.io.File
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class AndroidReleaseRepositoryTest {
    private lateinit var server: MockWebServer
    private lateinit var metadataKey: KeyPair
    private lateinit var apkBytes: ByteArray
    private lateinit var metadataBytes: ByteArray
    private lateinit var signatureText: String
    private lateinit var apiRoot: okhttp3.HttpUrl
    private lateinit var repositoryClient: OkHttpClient
    private lateinit var repository: AndroidReleaseRepository
    private var releaseVersionName = "1.5.3"
    private var releaseVersionCode = 100000L
    private var releaseKeyId: String = ""
    private var releaseChain = "[]"

    @Before
    fun setUp() {
        val runtimeContext = ApplicationProvider.getApplicationContext<Context>()
        // ShadowStatFs defaults to zero capacity. Simulate sufficient storage on
        // the exact parent path checked by production; its reserve guard stays real.
        org.robolectric.shadows.ShadowStatFs.registerStats(runtimeContext.cacheDir, 1_000_000, 1_000_000, 1_000_000)
        assertTrue(android.os.StatFs(runtimeContext.cacheDir.absolutePath).availableBytes > 128L * 1024L * 1024L)
        if (!androidx.work.WorkManager.isInitialized()) {
            androidx.work.WorkManager.initialize(runtimeContext, androidx.work.Configuration.Builder().build())
        }
        assertTrue(androidx.work.WorkManager.isInitialized())
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("android_release_trust", Context.MODE_PRIVATE).edit().clear().commit()
        metadataKey = newP256KeyPair()
        apkBytes = requireNotNull(javaClass.classLoader?.getResourceAsStream("collie-android-1.5.3.apk"))
            .use { it.readBytes() }
        releaseVersionName = "1.5.3"
        releaseVersionCode = 100000L
        releaseKeyId = sha256(metadataKey.public.encoded)
        releaseChain = "[]"
        metadataBytes = metadata()
        signatureText = Base64.getUrlEncoder().withoutPadding().encodeToString(sign(metadataBytes))
        val held = HeldCertificate.Builder()
            .addSubjectAlternativeName("api.github.com")
            .addSubjectAlternativeName("github.com")
            .addSubjectAlternativeName("release-assets.githubusercontent.com")
            .build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(held).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(held.certificate).build()
        server = MockWebServer().apply {
            // The original Host assertion is an HTTP/1.1 fixture contract; HTTP/2
            // carries :authority instead. Production protocol negotiation is unchanged.
            protocols = listOf(Protocol.HTTP_1_1)
            useHttps(serverTls.sslSocketFactory())
            start()
        }
        apiRoot = "https://api.github.com:${server.port}".toHttpUrl()
        repositoryClient = OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .dns(object : Dns { override fun lookup(hostname: String): List<InetAddress> = listOf(InetAddress.getByName("127.0.0.1")) })
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        val context = ApplicationProvider.getApplicationContext<Context>()
        registerSimulatedInstalledFixtureMetadata(context)
        repository = AndroidReleaseRepository(
            archivePackageInfoForTests = VerifiedPublicArchiveFixture::read,
            context = context,
            client = repositoryClient,
            apiRoot = apiRoot,
            apiHost = "api.github.com",
            releaseHost = "github.com",
            releaseHosts = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"),
            allowedPort = server.port,
            verifier = AndroidReleaseMetadataVerifier(publicPem(metadataKey)), packageName = FIXTURE_APPLICATION_ID,
            installedVersionCodeOverrideForTests = { 3L },
            installedSignerDigestsOverrideForTests = { setOf(FIXTURE_CERTIFICATE_SHA256) },
        )
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `uses only stable android tags and an anonymous public HTTPS client`() {
        queueReleaseFeed()
        queueMetadata()
        queueSignature()
        val result = kotlinx.coroutines.runBlocking { repository.latest(installedVersionCode = 3) }
        assertTrue("Verified public fixture offer: ${(result as? AndroidReleaseLookup.Unavailable)?.reason}", result is AndroidReleaseLookup.Available)
        val candidate = (result as AndroidReleaseLookup.Available).candidate
        assertEquals("android-v1.5.3", candidate.tag)
        assertEquals(100000, candidate.versionCode)
        assertEquals(apkBytes.size.toLong(), candidate.byteSize)
        assertEquals("https://github.com/Buckeyes22/collie/releases/tag/android-v1.5.3", candidate.sourceUrl)
        val requests = listOf(server.takeRequest(), server.takeRequest(), server.takeRequest())
        assertTrue(requests.all { it.requestLine!!.startsWith("GET ") })
        assertTrue(requests.all { it.headers["Authorization"] == null })
        assertTrue(requests.all { it.headers["Cookie"] == null })
        assertTrue(requests.all { it.headers["Host"]!!.contains("github.com") })
    }

    @Test
    fun `public signed release cannot borrow the debug application package identity`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(context.packageName != FIXTURE_APPLICATION_ID)
        val debugPackage = AndroidReleaseRepository(archivePackageInfoForTests = VerifiedPublicArchiveFixture::read, context = context, client = repositoryClient,
            apiRoot = apiRoot, allowedPort = server.port,
            verifier = AndroidReleaseMetadataVerifier(publicPem(metadataKey)),
            installedVersionCodeOverrideForTests = { 3L },
            installedSignerDigestsOverrideForTests = { setOf(FIXTURE_CERTIFICATE_SHA256) })
        queueReleaseFeed(); queueMetadata(); queueSignature()
        assertEquals(AndroidReleaseLookup.Reason.UNSIGNED_OR_INVALID,
            (kotlinx.coroutines.runBlocking { debugPackage.latest(3) } as AndroidReleaseLookup.Unavailable).reason)
        assertEquals(3, server.requestCount)
        assertEquals(null, debugPackage.currentOperation())
        assertEquals(null, debugPackage.currentInstallAttempt())
        repeat(3) { server.takeRequest() }
    }

    @Test
    fun `invalid signature refuses before any APK request is exposed`() {
        queueReleaseFeed()
        queueMetadata()
        server.enqueue(MockResponse.Builder().body("not-a-signature").build())
        val result = kotlinx.coroutines.runBlocking { repository.latest(installedVersionCode = 3) }
        assertTrue(result is AndroidReleaseLookup.Unavailable)
        assertEquals(3, server.requestCount)
        repeat(3) { server.takeRequest() }
    }

    @Test
    fun `foreign release asset host and duplicate expected assets are refused`() {
        val foreign = releaseFeed(apkUrl = "https://attacker.example/payload.apk")
        server.enqueue(MockResponse.Builder().body(foreign).build())
        val result = kotlinx.coroutines.runBlocking { repository.latest(installedVersionCode = 3) }
        assertEquals(AndroidReleaseLookup.Reason.UNSIGNED_OR_INVALID, (result as AndroidReleaseLookup.Unavailable).reason)
        assertEquals(1, server.requestCount)

        server.enqueue(MockResponse.Builder().body(releaseFeed(duplicateApk = true)).build())
        val duplicate = kotlinx.coroutines.runBlocking { repository.latest(installedVersionCode = 3) }
        assertFalse(duplicate is AndroidReleaseLookup.Available)
        assertEquals(2, server.requestCount)
        repeat(2) { server.takeRequest() }
    }

    @Test
    fun `equal or lower installed version is never offered`() {
        queueReleaseFeed()
        queueMetadata()
        queueSignature()
        val result = kotlinx.coroutines.runBlocking { repository.latest(installedVersionCode = 100000) }
        assertEquals(AndroidReleaseLookup.UpToDate, result)
        repeat(3) { server.takeRequest() }
    }

    @Test
    fun `same version is accepted only for the previously accepted exact manifest digest`() {
        queueReleaseFeed(); queueMetadata(); queueSignature()
        assertTrue(kotlinx.coroutines.runBlocking { repository.latest(installedVersionCode = 3) } is AndroidReleaseLookup.Available)
        repeat(3) { server.takeRequest() }

        queueReleaseFeed(); queueMetadata(); queueSignature()
        assertTrue(kotlinx.coroutines.runBlocking { repository.latest(installedVersionCode = 3) } is AndroidReleaseLookup.Available)
        repeat(3) { server.takeRequest() }

        metadataBytes = metadataBytes + " ".encodeToByteArray()
        signatureText = Base64.getUrlEncoder().withoutPadding().encodeToString(sign(metadataBytes))
        queueReleaseFeed(); queueMetadata(); queueSignature()
        val replay = kotlinx.coroutines.runBlocking { repository.latest(installedVersionCode = 3) }
        assertEquals(AndroidReleaseLookup.Reason.UNSIGNED_OR_INVALID, (replay as AndroidReleaseLookup.Unavailable).reason)
        repeat(3) { server.takeRequest() }
    }

    @Test
    fun `durable adopted key rejects retired root and branch but accepts current key and signed extension`() {
        queueReleaseFeed(); queueMetadata(); queueSignature()
        assertTrue(kotlinx.coroutines.runBlocking { repository.latest(3) } is AndroidReleaseLookup.Available)
        repeat(3) { server.takeRequest() }

        val second = newP256KeyPair()
        releaseVersionName = "1.5.4"; releaseVersionCode = 100001L
        val firstTransition = transition(metadataKey, second, 100001, "android-v1.5.4")
        releaseChain = "[$firstTransition]"
        releaseKeyId = sha256(second.public.encoded)
        metadataBytes = metadata(); signatureText = sign(metadataBytes, second).toBase64Url()
        queueReleaseFeed(); queueMetadata(); queueSignature()
        assertTrue(kotlinx.coroutines.runBlocking { repository.latest(3) } is AndroidReleaseLookup.Available)
        repeat(3) { server.takeRequest() }

        releaseVersionName = "1.5.5"; releaseVersionCode = 100002L
        releaseChain = "[]"; releaseKeyId = sha256(metadataKey.public.encoded)
        metadataBytes = metadata(); signatureText = sign(metadataBytes).toBase64Url()
        queueReleaseFeed(); queueMetadata(); queueSignature()
        assertEquals(AndroidReleaseLookup.Reason.UNSIGNED_OR_INVALID,
            (kotlinx.coroutines.runBlocking { repository.latest(3) } as AndroidReleaseLookup.Unavailable).reason)
        repeat(3) { server.takeRequest() }

        val branch = newP256KeyPair()
        releaseChain = "[${transition(metadataKey, branch, 100001, "android-v1.5.4")}]"
        releaseKeyId = sha256(branch.public.encoded)
        metadataBytes = metadata(); signatureText = sign(metadataBytes, branch).toBase64Url()
        queueReleaseFeed(); queueMetadata(); queueSignature()
        assertEquals(AndroidReleaseLookup.Reason.UNSIGNED_OR_INVALID,
            (kotlinx.coroutines.runBlocking { repository.latest(3) } as AndroidReleaseLookup.Unavailable).reason)
        repeat(3) { server.takeRequest() }

        releaseChain = "[$firstTransition]"
        releaseKeyId = sha256(second.public.encoded)
        metadataBytes = metadata(); signatureText = sign(metadataBytes, second).toBase64Url()
        queueReleaseFeed(); queueMetadata(); queueSignature()
        assertTrue(kotlinx.coroutines.runBlocking { repository.latest(3) } is AndroidReleaseLookup.Available)
        repeat(3) { server.takeRequest() }

        val third = newP256KeyPair()
        releaseVersionName = "1.5.6"; releaseVersionCode = 100003L
        // Persisted prefixes include the exact randomized ECDSA signature bytes.
        // A replay/extension repeats that accepted link, not a newly signed replacement.
        releaseChain = "[$firstTransition,${transition(second, third, 100002, "android-v1.5.5")}]"
        releaseKeyId = sha256(third.public.encoded)
        metadataBytes = metadata(); signatureText = sign(metadataBytes, third).toBase64Url()
        queueReleaseFeed(); queueMetadata(); queueSignature()
        assertTrue(kotlinx.coroutines.runBlocking { repository.latest(3) } is AndroidReleaseLookup.Available)
        repeat(3) { server.takeRequest() }

        releaseVersionName = "1.5.7"; releaseVersionCode = 100004L
        releaseChain = "[$firstTransition]"
        releaseKeyId = sha256(second.public.encoded)
        metadataBytes = metadata(); signatureText = sign(metadataBytes, second).toBase64Url()
        queueReleaseFeed(); queueMetadata(); queueSignature()
        assertEquals(AndroidReleaseLookup.Reason.UNSIGNED_OR_INVALID,
            (kotlinx.coroutines.runBlocking { repository.latest(3) } as AndroidReleaseLookup.Unavailable).reason)
        repeat(3) { server.takeRequest() }
    }

    @Test
    fun `cancelled redirected APK body resumes exact range with zero GET body and matching staged hash`() {
        metadataBytes = metadata()
        signatureText = Base64.getUrlEncoder().withoutPadding().encodeToString(sign(metadataBytes))
        val directory = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "android-update-resume-test")
        directory.deleteRecursively()
        try {
            val witness = acceptedOperation()
            queueDownloadMetadata()
            // Keep a real slow byte stream while the client holds its first progress
            // callback. Short server sleeps can observe socket closure at teardown;
            // a five-minute server sleep outlives cancellation and shutdown's bound.
            server.enqueue(MockResponse.Builder().body(Buffer().write(apkBytes)).throttleBody(1L, 25L, java.util.concurrent.TimeUnit.MILLISECONDS).build())
            val firstProgress = CompletableDeferred<Unit>()
            val releaseReader = CompletableDeferred<Unit>()
            val canceled = kotlinx.coroutines.runBlocking {
                val transfer = async {
                    repository.download(witness.operationId, directory) { _, _ ->
                        firstProgress.complete(Unit)
                        releaseReader.await()
                    }
                }
                withTimeout(5_000) { firstProgress.await() }
                repository.cancelActiveRequest(witness.operationId)
                releaseReader.complete(Unit)
                transfer.await()
            }
            assertTrue(canceled is AndroidApkDownload.Failed)
            assertEquals(AndroidReleaseLookup.Reason.NETWORK, (canceled as AndroidApkDownload.Failed).reason)
            val part = File(repository.operationDirectory(directory, witness.operationId), "$releaseVersionName.apk.part")
            val savedBytes = part.length()
            assertTrue("cancel keeps a nonempty resumable partial", savedBytes in 1 until apkBytes.size.toLong())
            repeat(4) { server.takeRequest() }

            queueDownloadMetadata()
            server.enqueue(MockResponse.Builder().code(302).setHeader(
                "Location", "https://release-assets.githubusercontent.com:${server.port}/Buckeyes22/collie/releases/download/android-v$releaseVersionName/collie-android-$releaseVersionName.apk",
            ).build())
            server.enqueue(MockResponse.Builder()
                .code(206)
                .setHeader("Content-Range", "bytes $savedBytes-${apkBytes.lastIndex}/${apkBytes.size}")
                .body(Buffer().write(apkBytes, savedBytes.toInt(), apkBytes.size - savedBytes.toInt())).build())
            val resumed = kotlinx.coroutines.runBlocking {
                repository.download(witness.operationId, directory, reportProgress = { _, _ -> })
            }
            assertTrue("the controlled byte-stream fixture may stage only after exact hash validation", resumed is AndroidApkDownload.Ready)
            val ready = (resumed as AndroidApkDownload.Ready).apk
            val requests = List(5) { server.takeRequest() }
            assertEquals("bytes=$savedBytes-", requests[4].headers["Range"])
            assertEquals(0L, requests[3].bodySize)
            assertEquals(0L, requests[4].bodySize)
            assertEquals(apkBytes.size.toLong(), ready.length())
            assertArrayEquals(apkBytes, ready.readBytes())
            assertEquals(sha256(apkBytes), sha256(ready.readBytes()))
            assertFalse(part.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `partial durable trust record refuses the root key as corrupted state`() {
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("android_release_trust", Context.MODE_PRIVATE).edit()
            .putLong("accepted_version_code", 0L).commit()
        queueReleaseFeed(); queueMetadata(); queueSignature()
        val result = kotlinx.coroutines.runBlocking { repository.latest(3) }
        assertEquals(AndroidReleaseLookup.Reason.UNSIGNED_OR_INVALID,
            (result as AndroidReleaseLookup.Unavailable).reason)
        repeat(3) { server.takeRequest() }
    }

    @Test
    fun `held old operation cannot reserve installation after a newer manifest is accepted`() {
        val witness = acceptedOperation()
        val staged = stageOperation(witness)
        val enteredAdmission = CompletableDeferred<Unit>()
        val continueAdmission = CompletableDeferred<Unit>()
        val delayedRepository = AndroidReleaseRepository(
            archivePackageInfoForTests = VerifiedPublicArchiveFixture::read,
            context = ApplicationProvider.getApplicationContext(),
            client = repositoryClient,
            apiRoot = apiRoot,
            apiHost = "api.github.com",
            releaseHost = "github.com",
            releaseHosts = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"),
            allowedPort = server.port,
            verifier = AndroidReleaseMetadataVerifier(publicPem(metadataKey)), packageName = FIXTURE_APPLICATION_ID,
            installedVersionCodeOverrideForTests = { 3L },
            installedSignerDigestsOverrideForTests = { setOf(FIXTURE_CERTIFICATE_SHA256) },
            beforeFinalInstallAdmissionForTests = {
                enteredAdmission.complete(Unit)
                continueAdmission.await()
            },
        )
        val admission = kotlinx.coroutines.runBlocking {
            val pending = async { delayedRepository.reserveInstaller(witness.operationId, staged) }
            withTimeout(5_000) { enteredAdmission.await() }
            releaseVersionName = "1.5.4"
            releaseVersionCode = 100001L
            metadataBytes = metadata()
            signatureText = Base64.getUrlEncoder().withoutPadding().encodeToString(sign(metadataBytes))
            queueReleaseFeed(); queueMetadata(); queueSignature()
            val newer = repository.latest(3) as AndroidReleaseLookup.Available
            repeat(3) { server.takeRequest() }
            val replacement = requireNotNull(repository.beginDownload(newer.candidate))
            assertTrue(replacement.operationId != witness.operationId)
            continueAdmission.complete(Unit)
            pending.await()
        }
        assertTrue(admission is AndroidInstallAdmission.Refused)
        assertEquals("staleOperation", (admission as AndroidInstallAdmission.Refused).reason)
        assertEquals(null, repository.currentInstallAttempt())
        staged.delete()
    }

    @Test
    fun acknowledgedSameIdCancellationBeforeCleanupFencesHeldInstallerReservation() = kotlinx.coroutines.runBlocking {
        val witness = acceptedOperation()
        val staged = stageOperation(witness)
        val enteredAdmission = CompletableDeferred<Unit>()
        val releaseAdmission = CompletableDeferred<Unit>()
        val cancellationCommitted = java.util.concurrent.CountDownLatch(1)
        val releaseCleanup = java.util.concurrent.CountDownLatch(1)
        var cancelFailure: Throwable? = null
        var changed = false
        val original = AndroidReleaseRepository(
            archivePackageInfoForTests = VerifiedPublicArchiveFixture::read,
            context = ApplicationProvider.getApplicationContext(), client = repositoryClient, apiRoot = apiRoot,
            apiHost = "api.github.com", releaseHost = "github.com",
            releaseHosts = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"),
            allowedPort = server.port, verifier = AndroidReleaseMetadataVerifier(publicPem(metadataKey)),
            packageName = FIXTURE_APPLICATION_ID,
            installedVersionCodeOverrideForTests = { 3L },
            installedSignerDigestsOverrideForTests = { setOf(FIXTURE_CERTIFICATE_SHA256) },
            beforeFinalInstallAdmissionForTests = { enteredAdmission.complete(Unit); releaseAdmission.await() },
            afterDurableCancellationBeforeCleanupForTests = { id ->
                assertEquals(witness.operationId, id)
                cancellationCommitted.countDown()
                assertTrue(releaseCleanup.await(5, java.util.concurrent.TimeUnit.SECONDS))
            },
        )
        val pending = async { original.reserveInstaller(witness.operationId, staged) }
        withTimeout(5_000) { enteredAdmission.await() }
        val cancellation = Thread {
            try { changed = original.markDownloadCanceled(witness.operationId) }
            catch (failure: Throwable) { cancelFailure = failure }
        }
        try {
            cancellation.start()
            assertTrue(cancellationCommitted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals("canceled", original.currentOperation()?.lifecycleState)
            assertTrue("Actual cancellation ACK precedes outside-lock artifact cleanup", staged.isFile)
            releaseAdmission.complete(Unit)
            val refused = withTimeout(5_000) { pending.await() }
            assertEquals("staleOperation", (refused as AndroidInstallAdmission.Refused).reason)
            assertEquals(null, original.currentInstallAttempt())
            assertEquals("canceled", original.currentOperation()?.lifecycleState)
        } finally {
            pending.cancel()
            releaseAdmission.complete(Unit)
            releaseCleanup.countDown()
            cancellation.join(5_000)
            assertFalse(cancellation.isAlive)
            assertEquals(null, cancelFailure)
            assertTrue(changed)
            staged.delete()
        }
    }

    @Test
    fun currentOperationStillRechecksStagedHashAfterHeldInstallAdmission() = kotlinx.coroutines.runBlocking {
        val witness = acceptedOperation()
        val staged = stageOperation(witness)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val delayed = AndroidReleaseRepository(
            archivePackageInfoForTests = VerifiedPublicArchiveFixture::read,
            context = ApplicationProvider.getApplicationContext(), client = repositoryClient, apiRoot = apiRoot,
            apiHost = "api.github.com", releaseHost = "github.com",
            releaseHosts = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"),
            allowedPort = server.port, verifier = AndroidReleaseMetadataVerifier(publicPem(metadataKey)),
            packageName = FIXTURE_APPLICATION_ID,
            installedVersionCodeOverrideForTests = { 3L },
            installedSignerDigestsOverrideForTests = { setOf(FIXTURE_CERTIFICATE_SHA256) },
            beforeFinalInstallAdmissionForTests = { entered.complete(Unit); release.await() },
        )
        try {
            val pending = async { delayed.reserveInstaller(witness.operationId, staged) }
            withTimeout(5_000) { entered.await() }
            // The same original operation stays current; only its on-disk byte changes.
            staged.writeBytes(apkBytes.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() })
            release.complete(Unit)
            val result = pending.await()
            assertEquals("fileRefused", (result as AndroidInstallAdmission.Refused).reason)
            assertEquals(null, repository.currentInstallAttempt())
            assertEquals(witness.operationId, repository.currentOperation()?.operationId)
        } finally { staged.delete() }
    }

    @Test
    fun `unknown installer attempt stays unknown after repository restart and is never auto redispatched`() {
        val preferences = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("android_release_trust", Context.MODE_PRIVATE)
        val attempt = AndroidReleaseInstallAttempt(
            attemptId = java.util.UUID.randomUUID().toString(),
            operationId = java.util.UUID.randomUUID().toString(),
            tag = "android-v99.0.0",
            versionCode = 9900,
            manifestDigest = "a".repeat(64),
            signerSha256 = "b".repeat(64),
            originalInstalledVersionCode = 3,
            originalInstalledSigners = listOf("c".repeat(64)),
            phase = "dispatched",
            updatedAtEpochMs = System.currentTimeMillis(),
        )
        preferences.edit().putString(
            "install_attempt",
            kotlinx.serialization.json.Json.encodeToString(AndroidReleaseInstallAttempt.serializer(), attempt),
        ).commit()
        val first = kotlinx.coroutines.runBlocking { repository.reconcileInstallAttempt() }
        assertEquals("unknown", first?.phase)
        val restarted = AndroidReleaseRepository(
            archivePackageInfoForTests = VerifiedPublicArchiveFixture::read,
            context = ApplicationProvider.getApplicationContext(),
            client = repositoryClient,
            apiRoot = apiRoot,
            apiHost = "api.github.com",
            releaseHost = "github.com",
            releaseHosts = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"),
            allowedPort = server.port,
            verifier = AndroidReleaseMetadataVerifier(publicPem(metadataKey)), packageName = FIXTURE_APPLICATION_ID,
        )
        val afterRestart = kotlinx.coroutines.runBlocking { restarted.reconcileInstallAttempt() }
        assertEquals("unknown", afterRestart?.phase)
        assertEquals(attempt.attemptId, afterRestart?.attemptId)
        assertTrue(server.requestCount == 0)
    }

    @Test
    fun `failed durable install reservation refuses before any installer dispatch state exists`() {
        val witness = acceptedOperation()
        val preferences = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("android_release_trust", Context.MODE_PRIVATE)
        val failing = failCommits(preferences)
        val staged = stageOperation(witness)
        val failingRepository = AndroidReleaseRepository(
            archivePackageInfoForTests = VerifiedPublicArchiveFixture::read,
            context = ApplicationProvider.getApplicationContext(), client = repositoryClient, apiRoot = apiRoot,
            apiHost = "api.github.com", releaseHost = "github.com",
            releaseHosts = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"),
            allowedPort = server.port, verifier = AndroidReleaseMetadataVerifier(publicPem(metadataKey)), packageName = FIXTURE_APPLICATION_ID,
            preferences = failing,
            installedVersionCodeOverrideForTests = { 3L },
            installedSignerDigestsOverrideForTests = { setOf(FIXTURE_CERTIFICATE_SHA256) },
        )
        val result = kotlinx.coroutines.runBlocking { failingRepository.reserveInstaller(witness.operationId, staged) }
        assertEquals("durableReservationFailed", (result as AndroidInstallAdmission.Refused).reason)
        assertFalse(preferences.contains("install_attempt"))
        assertEquals(null, repository.currentInstallAttempt())
        staged.delete()
    }

    @Test
    // Signed public bytes are real; PackageManager identity below is explicitly
    // simulated from independent aapt2/apksigner receipts, not JVM parser proof.
    fun `genuine signed archive can reserve and durably advance to pending without opening platform UI`() {
        val witness = acceptedOperation()
        val staged = stageOperation(witness)
        try {
            val reserved = kotlinx.coroutines.runBlocking { repository.reserveInstaller(witness.operationId, staged) }
            assertTrue(reserved is AndroidInstallAdmission.Reserved)
            val attempt = (reserved as AndroidInstallAdmission.Reserved).attempt
            assertEquals("prepared", attempt.phase)
            assertTrue(repository.markInstallerDispatched(attempt.attemptId))
            assertTrue(repository.markInstallerPending(attempt.attemptId))
            assertEquals("pending", repository.currentInstallAttempt()?.phase)
            assertEquals("installPending", repository.getOperation(witness.operationId)?.lifecycleState)
        } finally {
            staged.delete()
        }
    }

    @Test
    fun `new signed offer cancels original held body across repositories and keeps replacement ready`() = kotlinx.coroutines.runBlocking {
        val original = acceptedOperation()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var oldProgress = 0
        queueDownloadMetadata()
        server.enqueue(MockResponse.Builder().body(Buffer().write(apkBytes)).build())
        val held = async {
            repository.download(original.operationId, updateDirectory()) { _, _ ->
                oldProgress++
                entered.complete(Unit)
                release.await()
            }
        }
        withTimeout(5_000) { entered.await() }
        repeat(4) { server.takeRequest() }
        val calls = AndroidReleaseRepository::class.java.getDeclaredField("activeCalls").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val originalCall = (calls.get(repository) as Map<String, okhttp3.Call>).getValue(original.operationId)
        selectSecondArchive()
        val successor = reopenedRepository()
        queueReleaseFeed(); queueMetadata(); queueSignature()
        val offer = (successor.latest(3) as AndroidReleaseLookup.Available).candidate
        repeat(3) { server.takeRequest() }
        assertTrue("metadata admission cancels the exact original body before it is released", originalCall.isCanceled())
        val replacement = requireNotNull(successor.beginDownload(offer))
        assertTrue(original.operationId != replacement.operationId)
        val replacementFile = stageOperation(replacement)
        val progressAtRetirement = oldProgress
        release.complete(Unit)
        assertTrue(withTimeout(5_000) { held.await() } is AndroidApkDownload.Failed)
        assertEquals(progressAtRetirement, oldProgress)
        assertFalse(repository.markDownloadCanceled(original.operationId))
        assertEquals(replacement.operationId, repository.currentOperation()?.operationId)
        assertEquals("ready", repository.currentOperation()?.lifecycleState)
        assertArrayEquals(apkBytes, replacementFile.readBytes())
        assertFalse(repository.operationDirectory(updateDirectory(), original.operationId).exists())
    }

    @Test
    fun `cancel before queued worker starts is durable and stale canceled row cannot cancel resumed UUID`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val original = acceptedOperation()
        val request = androidx.work.OneTimeWorkRequestBuilder<ApkDownloadWorker>()
            .setInputData(androidx.work.workDataOf(ApkDownloadWorker.INPUT_OPERATION_ID to original.operationId))
            .setInitialDelay(1, java.util.concurrent.TimeUnit.DAYS)
            .addTag(original.operationId).build()
        val manager = androidx.work.WorkManager.getInstance(context)
        manager.enqueueUniqueWork(ApkDownloadWorker.uniqueWorkName(original.operationId), androidx.work.ExistingWorkPolicy.KEEP, request)
            .result.get(5, java.util.concurrent.TimeUnit.SECONDS)
        val part = File(repository.operationDirectory(updateDirectory(), original.operationId), "$releaseVersionName.apk.part")
        part.parentFile!!.mkdirs(); part.writeBytes(apkBytes.copyOfRange(0, 37))
        ApkDownloadWorker.cancel(context, original.operationId, repository)
        manager.cancelUniqueWork(ApkDownloadWorker.uniqueWorkName(original.operationId)).result.get(5, java.util.concurrent.TimeUnit.SECONDS)
        assertEquals(androidx.work.WorkInfo.State.CANCELLED, requireNotNull(manager.getWorkInfoById(request.id).get(5, java.util.concurrent.TimeUnit.SECONDS)).state)
        assertEquals("canceled", reopenedRepository().currentOperation()?.lifecycleState)
        queueReleaseFeed(); queueMetadata(); queueSignature()
        val offer = (kotlinx.coroutines.runBlocking { repository.latest(3) } as AndroidReleaseLookup.Available).candidate
        repeat(3) { server.takeRequest() }
        val resumed = requireNotNull(kotlinx.coroutines.runBlocking { repository.beginDownload(offer) })
        assertTrue(original.operationId != resumed.operationId)
        val copied = File(repository.operationDirectory(updateDirectory(), resumed.operationId), "$releaseVersionName.apk.part")
        assertArrayEquals(apkBytes.copyOfRange(0, 37), copied.readBytes())
        assertFalse(repository.markDownloadCanceled(original.operationId))
        assertEquals(resumed.operationId, repository.currentOperation()?.operationId)
        // A 200 full body exercises the existing safe restart when Range is ignored.
        assertArrayEquals(apkBytes, stageOperation(resumed).readBytes())
        assertEquals("ready", repository.currentOperation()?.lifecycleState)
    }

    @Test
    fun `generic viewer cancel remains unknown across restart preserving grant until installed proof`() {
        val witness = acceptedOperation()
        val staged = stageOperation(witness)
        val attempt = (kotlinx.coroutines.runBlocking { repository.reserveInstaller(witness.operationId, staged) } as AndroidInstallAdmission.Reserved).attempt
        assertTrue(repository.markInstallerDispatched(attempt.attemptId))
        assertTrue(repository.markInstallerPending(attempt.attemptId))
        assertTrue(releasePreferences().edit().putString("install_attempt", kotlinx.serialization.json.Json.encodeToString(
            AndroidReleaseInstallAttempt.serializer(), requireNotNull(repository.currentInstallAttempt()).copy(installerProtocol = "view"))).commit())
        val unknown = kotlinx.coroutines.runBlocking { repository.recordInstallerResult(attempt.attemptId, android.app.Activity.RESULT_CANCELED) }
        assertEquals("unknown", unknown?.phase)
        val obsolete = File(updateDirectory(), java.util.UUID.randomUUID().toString()).apply { mkdirs() }
        File(obsolete, "obsolete.apk").writeBytes(byteArrayOf(1, 2, 3))
        val restarted = reopenedRepository()
        assertFalse(obsolete.exists())
        assertTrue(staged.isFile)
        assertEquals(attempt.attemptId, kotlinx.coroutines.runBlocking { restarted.reconcileInstallAttempt() }?.attemptId)
        assertEquals("unknown", restarted.currentInstallAttempt()?.phase)
        assertEquals(attempt.attemptId, kotlinx.coroutines.runBlocking { restarted.recordInstallerResult(java.util.UUID.randomUUID().toString(), android.app.Activity.RESULT_OK) }?.attemptId)
        assertTrue(staged.isFile)
        assertTrue(kotlinx.coroutines.runBlocking { restarted.reserveInstaller(witness.operationId, staged) } is AndroidInstallAdmission.Refused)
        val requestsBefore = server.requestCount
        val installed = reopenedRepository { releaseVersionCode }
        assertEquals("installed", kotlinx.coroutines.runBlocking { installed.reconcileInstallAttempt() }?.phase)
        assertFalse(staged.exists())
        assertEquals(requestsBefore, server.requestCount)
    }

    @Test
    fun `dispatch refusal requires a new verified operation and a fresh explicit reservation`() {
        val original = acceptedOperation()
        val staged = stageOperation(original)
        val attempt = (kotlinx.coroutines.runBlocking { repository.reserveInstaller(original.operationId, staged) } as AndroidInstallAdmission.Reserved).attempt
        assertTrue(repository.markInstallerDispatched(attempt.attemptId))
        assertTrue(repository.failInstallerDispatch(attempt.attemptId))
        assertEquals("refused", repository.currentInstallAttempt()?.phase)
        assertFalse(staged.exists())
        assertTrue(kotlinx.coroutines.runBlocking { repository.reserveInstaller(original.operationId, staged) } is AndroidInstallAdmission.Refused)
        val replacement = acceptedOperation()
        assertTrue(original.operationId != replacement.operationId)
        val ready = stageOperation(replacement)
        val fresh = (kotlinx.coroutines.runBlocking { repository.reserveInstaller(replacement.operationId, ready) } as AndroidInstallAdmission.Reserved).attempt
        assertTrue(attempt.attemptId != fresh.attemptId)
        assertFalse(repository.failInstallerDispatch(attempt.attemptId))
        assertEquals(fresh.attemptId, repository.currentInstallAttempt()?.attemptId)
        assertTrue(ready.isFile)
    }

    @Test
    fun `hash mismatch discards archive and cannot reserve install`() {
        val witness = acceptedOperation()
        val tampered = apkBytes.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        assertArchiveRefused(witness, tampered)
    }

    @Test
    fun `honestly signed hash for invalid archive still refuses parser and installation`() {
        apkBytes = ByteArray(1024) { (it % 251).toByte() }
        resignMetadata()
        assertArchiveRefused(acceptedOperation(), apkBytes)
    }

    @Test
    fun `valid archive with wrong declared certificate refuses after matching hash`() {
        metadataBytes = metadata().decodeToString().replace(FIXTURE_CERTIFICATE_SHA256, "d".repeat(64)).encodeToByteArray()
        signatureText = sign(metadataBytes).toBase64Url()
        assertArchiveRefused(acceptedOperation(), apkBytes)
    }

    @Test
    fun `honestly matching hash for genuine wrong package archive refuses parsed identity`() {
        apkBytes = requireNotNull(javaClass.classLoader?.getResourceAsStream("collie-wrong-package.apk")).use { it.readBytes() }
        resignMetadata()
        assertArchiveRefused(acceptedOperation(), apkBytes)
    }

    @Test
    fun `wrong application metadata refuses before APK or operation exists`() {
        metadataBytes = metadata().decodeToString().replace("com.lateapex.collie", "com.example.other").encodeToByteArray()
        signatureText = sign(metadataBytes).toBase64Url()
        queueReleaseFeed(); queueMetadata(); queueSignature()
        assertEquals(AndroidReleaseLookup.Reason.UNSIGNED_OR_INVALID,
            (kotlinx.coroutines.runBlocking { repository.latest(3) } as AndroidReleaseLookup.Unavailable).reason)
        assertEquals(null, repository.currentOperation())
        assertEquals(null, repository.currentInstallAttempt())
        assertEquals(3, server.requestCount)
        repeat(3) { server.takeRequest() }
    }

    @Test
    fun `trusted certificate with incorrect SAN fails default hostname verification before HTTP`() {
        val wrongCertificate = HeldCertificate.Builder().addSubjectAlternativeName("wrong.example").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(wrongCertificate).build()
        val trusted = HandshakeCertificates.Builder().addTrustedCertificate(wrongCertificate.certificate).build()
        val wrong = MockWebServer().apply { useHttps(tls.sslSocketFactory()); start() }
        try {
            val client = OkHttpClient.Builder().sslSocketFactory(trusted.sslSocketFactory(), trusted.trustManager)
                .dns(object : Dns { override fun lookup(hostname: String) = listOf(InetAddress.getByName("127.0.0.1")) })
                .callTimeout(2, java.util.concurrent.TimeUnit.SECONDS).build()
            val isolated = AndroidReleaseRepository(archivePackageInfoForTests = VerifiedPublicArchiveFixture::read, context = ApplicationProvider.getApplicationContext(), client = client,
                apiRoot = "https://api.github.com:${wrong.port}".toHttpUrl(), allowedPort = wrong.port,
                verifier = AndroidReleaseMetadataVerifier(publicPem(metadataKey)), packageName = FIXTURE_APPLICATION_ID)
            assertEquals(AndroidReleaseLookup.Reason.NETWORK,
                (kotlinx.coroutines.runBlocking { isolated.latest(3) } as AndroidReleaseLookup.Unavailable).reason)
            assertEquals(0, wrong.requestCount)
            assertEquals(null, isolated.currentOperation())
        } finally { wrong.close() }
    }

    @Test
    fun `restart retains only exact legacy unknown grant and bounded current partial`() {
        val witness = acceptedOperation()
        val staged = stageOperation(witness)
        val attempt = (kotlinx.coroutines.runBlocking { repository.reserveInstaller(witness.operationId, staged) } as AndroidInstallAdmission.Reserved).attempt
        assertTrue(repository.markInstallerDispatched(attempt.attemptId))
        val legacy = File(updateDirectory(), "$releaseVersionName.apk")
        assertTrue(staged.renameTo(legacy))
        val obsolete = File(updateDirectory(), "0.0.1.apk").apply { writeBytes(byteArrayOf(1)) }
        val restarted = reopenedRepository()
        assertTrue(legacy.isFile)
        assertArrayEquals(apkBytes, legacy.readBytes())
        assertFalse(obsolete.exists())
        assertEquals("unknown", kotlinx.coroutines.runBlocking { restarted.reconcileInstallAttempt() }?.phase)
        assertTrue(legacy.isFile)
        assertEquals("installed", kotlinx.coroutines.runBlocking { reopenedRepository { releaseVersionCode }.reconcileInstallAttempt() }?.phase)
        assertFalse(legacy.exists())
        assertEquals(7, server.requestCount)
    }

    @Test
    fun `legacy canceled grant reopens unknown blocks replacement and closes only installed identity`() {
        val witness = acceptedOperation()
        val ready = stageOperation(witness)
        val original = seedLegacyCanceled(witness, ready)
        val legacy = File(updateDirectory(), "$releaseVersionName.apk")
        assertTrue(ready.renameTo(legacy))
        val requestsBefore = server.requestCount
        val reopened = reopenedRepository()
        val effective = requireNotNull(reopened.currentInstallAttempt())
        assertEquals("unknown", effective.phase)
        assertEquals(original.attemptId, effective.attemptId)
        assertEquals(original.operationId, effective.operationId)
        assertEquals(original.versionCode, effective.versionCode)
        assertEquals(original.signerSha256, effective.signerSha256)
        assertEquals("unknown", storedAttempt().phase)
        assertEquals("unknown", reopened.currentOperation()?.lifecycleState)
        assertArrayEquals(apkBytes, legacy.readBytes())
        val observed = kotlinx.coroutines.runBlocking { reopened.reconcileInstallAttempt() }
        assertEquals("unknown", observed?.phase)
        assertTrue(observed?.reconciledAtEpochMs != null)
        assertTrue(kotlinx.coroutines.runBlocking { reopened.reserveInstaller(witness.operationId, legacy) } is AndroidInstallAdmission.Refused)
        assertEquals(requestsBefore, server.requestCount)
        selectSecondArchive()
        queueReleaseFeed(); queueMetadata(); queueSignature()
        assertEquals(AndroidReleaseLookup.Reason.UNSIGNED_OR_INVALID,
            (kotlinx.coroutines.runBlocking { reopened.latest(3) } as AndroidReleaseLookup.Unavailable).reason)
        repeat(3) { server.takeRequest() }
        assertEquals(witness.operationId, reopened.currentOperation()?.operationId)
        assertEquals(original.attemptId, reopened.currentInstallAttempt()?.attemptId)
        assertTrue(legacy.isFile)
        assertEquals(null, kotlinx.coroutines.runBlocking { reopened.beginDownload(publicCandidateForCurrentWitness(witness)) })
        // A target version alone is insufficient if the installed signer is different.
        val wrongSigner = migrationRepository({ witness.versionCode }, { setOf("e".repeat(64)) })
        assertEquals("unknown", kotlinx.coroutines.runBlocking { wrongSigner.reconcileInstallAttempt() }?.phase)
        assertTrue(legacy.isFile)
        val installed = reopenedRepository { witness.versionCode }
        assertEquals("installed", kotlinx.coroutines.runBlocking { installed.reconcileInstallAttempt() }?.phase)
        assertEquals(original.attemptId, installed.currentInstallAttempt()?.attemptId)
        assertFalse(legacy.exists())
        assertEquals(requestsBefore + 3, server.requestCount)
    }

    @Test
    fun `failed legacy cancellation migration preserves raw records and grant and refuses admission`() {
        val witness = acceptedOperation()
        val ready = stageOperation(witness)
        val original = seedLegacyCanceled(witness, ready)
        val preferences = releasePreferences()
        val rawAttempt = preferences.getString("install_attempt", null)
        val rawOperation = preferences.getString("active_release_operation", null)
        assertTrue(rawAttempt != null)
        assertTrue(rawOperation != null)
        assertEquals("canceled", storedAttempt().phase)
        val originalBytes = ready.readBytes()
        val requestsBefore = server.requestCount
        val unavailable = migrationRepository({ 3L }, { setOf(FIXTURE_CERTIFICATE_SHA256) }, failCommits(preferences))
        assertEquals("unknown", unavailable.currentInstallAttempt()?.phase)
        assertEquals("unknown", unavailable.currentOperation()?.lifecycleState)
        assertEquals(original.attemptId, unavailable.currentInstallAttempt()?.attemptId)
        assertEquals(rawAttempt, preferences.getString("install_attempt", null))
        assertEquals(rawOperation, preferences.getString("active_release_operation", null))
        unavailable.cleanupArtifacts()
        assertArrayEquals(originalBytes, ready.readBytes())
        assertEquals(AndroidReleaseLookup.Reason.STORAGE,
            (kotlinx.coroutines.runBlocking { unavailable.latest(3) } as AndroidReleaseLookup.Unavailable).reason)
        assertEquals(null, kotlinx.coroutines.runBlocking { unavailable.beginDownload(publicCandidateForCurrentWitness(witness)) })
        assertTrue(kotlinx.coroutines.runBlocking { unavailable.reserveInstaller(witness.operationId, ready) } is AndroidInstallAdmission.Refused)
        assertEquals(null, kotlinx.coroutines.runBlocking { unavailable.reconcileInstallAttempt() })
        assertEquals(rawAttempt, preferences.getString("install_attempt", null))
        assertEquals(rawOperation, preferences.getString("active_release_operation", null))
        assertArrayEquals(originalBytes, ready.readBytes())
        assertEquals(requestsBefore, server.requestCount)
        val failedInstalled = migrationRepository({ witness.versionCode }, { setOf(FIXTURE_CERTIFICATE_SHA256) }, failCommits(preferences))
        assertEquals(null, kotlinx.coroutines.runBlocking { failedInstalled.reconcileInstallAttempt() })
        assertEquals("unknown", failedInstalled.currentInstallAttempt()?.phase)
        assertEquals(rawAttempt, preferences.getString("install_attempt", null))
        assertEquals(rawOperation, preferences.getString("active_release_operation", null))
        assertArrayEquals(originalBytes, ready.readBytes())
        val recovered = reopenedRepository()
        assertEquals("unknown", recovered.currentInstallAttempt()?.phase)
        assertEquals(original.attemptId, recovered.currentInstallAttempt()?.attemptId)
        assertTrue(ready.isFile)
    }

    @Test
    fun androidSessionAbortIsTerminalAcrossRestartAndAllowsOnlyANewReviewedDownload() = kotlinx.coroutines.runBlocking {
        val witness = acceptedOperation()
        val staged = stageOperation(witness)
        val original = (repository.reserveInstaller(witness.operationId, staged) as AndroidInstallAdmission.Reserved).attempt
        val sessionId = requireNotNull(repository.prepareInstallerSession(original.attemptId, staged))
        assertEquals(sessionId, repository.currentInstallAttempt()?.installerSessionId)
        assertTrue(repository.markInstallerDispatched(original.attemptId))
        assertTrue(repository.markInstallerPending(original.attemptId))
        assertEquals(null, repository.recordInstallerSessionResult(java.util.UUID.randomUUID().toString(), sessionId,
            android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED, null))
        assertEquals(null, repository.recordInstallerSessionResult(original.attemptId, sessionId + 1,
            android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED, null))
        val canceled = repository.recordInstallerSessionResult(original.attemptId, sessionId,
            android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED, null)
        assertEquals("canceled", canceled?.phase)
        assertFalse(staged.exists())
        assertEquals("canceled", reopenedRepository().reconcileInstallAttempt()?.phase)
        assertFalse(repository.commitInstallerSession(original.attemptId, sessionId))
        val replacement = acceptedOperation()
        assertTrue(replacement.operationId != witness.operationId)
        val ready = stageOperation(replacement)
        val retry = repository.reserveInstaller(replacement.operationId, ready) as AndroidInstallAdmission.Reserved
        assertTrue(retry.attempt.attemptId != original.attemptId)
    }

    @Test
    fun successCallbackWithoutMatchingInstalledIdentityRemainsUnknownAndDoesNotReplay() = kotlinx.coroutines.runBlocking {
        val witness = acceptedOperation()
        val staged = stageOperation(witness)
        val attempt = (repository.reserveInstaller(witness.operationId, staged) as AndroidInstallAdmission.Reserved).attempt
        val sessionId = requireNotNull(repository.prepareInstallerSession(attempt.attemptId, staged))
        assertTrue(repository.markInstallerDispatched(attempt.attemptId))
        assertEquals("unknown", repository.recordInstallerSessionResult(attempt.attemptId, sessionId,
            android.content.pm.PackageInstaller.STATUS_SUCCESS, null)?.phase)
        assertTrue(staged.isFile)
        assertFalse(repository.commitInstallerSession(attempt.attemptId, sessionId))
        assertEquals("unknown", reopenedRepository().reconcileInstallAttempt()?.phase)
        assertEquals("installed", reopenedRepository { witness.versionCode }.reconcileInstallAttempt()?.phase)
        assertFalse(staged.exists())
    }

    @Test
    fun processDeathBeforeCommitAbandonsPreparedSessionWithoutInstalling() = kotlinx.coroutines.runBlocking {
        val witness = acceptedOperation()
        val staged = stageOperation(witness)
        val attempt = (repository.reserveInstaller(witness.operationId, staged) as AndroidInstallAdmission.Reserved).attempt
        val sessionId = requireNotNull(repository.prepareInstallerSession(attempt.attemptId, staged))
        val installer = ApplicationProvider.getApplicationContext<Context>().packageManager.packageInstaller
        assertTrue(installer.getSessionInfo(sessionId) != null)
        assertEquals("refused", reopenedRepository().reconcileInstallAttempt()?.phase)
        assertEquals(null, installer.getSessionInfo(sessionId))
        assertFalse(repository.commitInstallerSession(attempt.attemptId, sessionId))
    }

    @Test
    fun androidConfirmationCapabilitySurvivesRepositoryRestartAndCancelRetiresOnlyOwnedSession() = kotlinx.coroutines.runBlocking {
        val witness = acceptedOperation()
        val staged = stageOperation(witness)
        val attempt = (repository.reserveInstaller(witness.operationId, staged) as AndroidInstallAdmission.Reserved).attempt
        val sessionId = requireNotNull(repository.prepareInstallerSession(attempt.attemptId, staged))
        assertTrue(repository.markInstallerDispatched(attempt.attemptId))
        val confirmation = android.content.Intent("android.content.pm.action.CONFIRM_INSTALL")
            .setPackage("com.android.packageinstaller")
            .putExtra(android.content.pm.PackageInstaller.EXTRA_SESSION_ID, sessionId)
        assertEquals("pending", repository.recordInstallerSessionResult(attempt.attemptId, sessionId,
            android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION, confirmation)?.phase)
        val originalCapability = requireNotNull(repository.pendingInstallerConfirmation())
        val reopened = reopenedRepository()
        assertEquals("pending", reopened.reconcileInstallAttempt()?.phase)
        assertEquals(originalCapability, reopened.pendingInstallerConfirmation())
        assertTrue(reopened.cancelPendingInstaller(attempt.attemptId))
        assertEquals("canceled", reopened.currentInstallAttempt()?.phase)
        assertEquals(null, reopened.pendingInstallerConfirmation())
        assertFalse(staged.exists())
        assertEquals(null, ApplicationProvider.getApplicationContext<Context>().packageManager.packageInstaller.getSessionInfo(sessionId))
        assertEquals(null, reopened.recordInstallerSessionResult(attempt.attemptId, sessionId,
            android.content.pm.PackageInstaller.STATUS_PENDING_USER_ACTION, confirmation))
    }

    @Test
    fun boundAndroidResultPendingIntentReachesNonexportedReceiverAndRetiresOriginalAttempt() = kotlinx.coroutines.runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val witness = acceptedOperation()
        val staged = stageOperation(witness)
        val attempt = (repository.reserveInstaller(witness.operationId, staged) as AndroidInstallAdmission.Reserved).attempt
        val sessionId = requireNotNull(repository.prepareInstallerSession(attempt.attemptId, staged))
        assertTrue(repository.markInstallerDispatched(attempt.attemptId))
        // Model the actually installed older version for the receiver's real PM lookup;
        // constructor-only installed-version fixtures cannot cross a BroadcastReceiver.
        val owner = context.packageManager.getPackageInfo("com.lateapex.collie", android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES)
        owner.longVersionCode = 3L
        org.robolectric.Shadows.shadowOf(context.packageManager).installPackage(owner)
        val callback = android.app.PendingIntent.getBroadcast(context, sessionId,
            android.content.Intent(context, AndroidReleaseInstallReceiver::class.java).apply {
                action = AndroidReleaseInstallReceiver.RESULT_ACTION
                data = android.net.Uri.parse("collie-install:${attempt.attemptId}")
            }, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_MUTABLE)
        callback.send(context, 0, android.content.Intent().apply {
            putExtra(android.content.pm.PackageInstaller.EXTRA_SESSION_ID, sessionId)
            putExtra(android.content.pm.PackageInstaller.EXTRA_STATUS, android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED)
        })
        withTimeout(5_000) {
            while (repository.currentInstallAttempt()?.phase != "canceled" || staged.exists()) {
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                kotlinx.coroutines.delay(10)
            }
        }
        assertEquals(attempt.attemptId, repository.currentInstallAttempt()?.attemptId)
        assertEquals("canceled", repository.currentOperation()?.lifecycleState)
        assertFalse(staged.exists())
    }

    private fun releasePreferences() = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("android_release_trust", Context.MODE_PRIVATE)

    private fun storedAttempt() = kotlinx.serialization.json.Json.decodeFromString(
        AndroidReleaseInstallAttempt.serializer(), requireNotNull(releasePreferences().getString("install_attempt", null)))

    private fun seedLegacyCanceled(witness: AndroidReleaseOperationWitness, ready: File): AndroidReleaseInstallAttempt {
        val attempt = (kotlinx.coroutines.runBlocking { repository.reserveInstaller(witness.operationId, ready) } as AndroidInstallAdmission.Reserved).attempt
        assertTrue(repository.markInstallerDispatched(attempt.attemptId))
        val old = attempt.copy(phase = "canceled", resultCode = android.app.Activity.RESULT_CANCELED, installerProtocol = "view")
        assertTrue(releasePreferences().edit()
            .putString("install_attempt", kotlinx.serialization.json.Json.encodeToString(AndroidReleaseInstallAttempt.serializer(), old))
            .putString("active_release_operation", kotlinx.serialization.json.Json.encodeToString(AndroidReleaseOperationWitness.serializer(), witness.copy(lifecycleState = "canceled")))
            .commit())
        return old
    }

    private fun publicCandidateForCurrentWitness(witness: AndroidReleaseOperationWitness): AndroidReleaseCandidate {
        val raw = Base64.getUrlDecoder().decode(witness.rawMetadataBase64Url)
        val metadata = kotlinx.serialization.json.Json.decodeFromString(AndroidReleaseMetadata.serializer(), raw.decodeToString())
        return AndroidReleaseCandidate(tag = witness.tag, versionName = witness.versionName, versionCode = witness.versionCode,
            notes = witness.releaseNotes, sourceUrl = witness.sourceUrl, byteSize = witness.apkSizeBytes,
            metadata = VerifiedAndroidRelease(metadata, raw, Base64.getUrlDecoder().decode(witness.signatureBase64Url)),
            manifestDigest = witness.manifestDigest, acceptedRevision = witness.acceptedRevision,
            installedVersionCode = witness.installedVersionCode, installedSignerDigests = witness.installedSignerDigests,
            apkUrl = originalAssetUrl(witness, witness.apkName), metadataUrl = originalAssetUrl(witness, "release-metadata.json"),
            signatureUrl = originalAssetUrl(witness, "release-metadata.sig"))
    }

    private fun originalAssetUrl(witness: AndroidReleaseOperationWitness, name: String) =
        "https://github.com:${server.port}/Buckeyes22/collie/releases/download/${witness.tag}/$name".toHttpUrl()

    private fun migrationRepository(installed: () -> Long?, signers: () -> Set<String>?,
        preferences: android.content.SharedPreferences = releasePreferences()) = AndroidReleaseRepository(
            archivePackageInfoForTests = VerifiedPublicArchiveFixture::read,
        context = ApplicationProvider.getApplicationContext(), client = repositoryClient, apiRoot = apiRoot,
        apiHost = "api.github.com", releaseHost = "github.com", allowedPort = server.port,
        verifier = AndroidReleaseMetadataVerifier(publicPem(metadataKey)), packageName = FIXTURE_APPLICATION_ID, preferences = preferences,
        installedVersionCodeOverrideForTests = installed, installedSignerDigestsOverrideForTests = signers)

    private fun assertArchiveRefused(witness: AndroidReleaseOperationWitness, body: ByteArray) {
        queueDownloadMetadata(); server.enqueue(MockResponse.Builder().body(Buffer().write(body)).build())
        val result = kotlinx.coroutines.runBlocking { repository.download(witness.operationId, updateDirectory()) { _, _ -> } }
        assertEquals(AndroidReleaseLookup.Reason.UNSIGNED_OR_INVALID, (result as AndroidApkDownload.Failed).reason)
        repeat(4) { server.takeRequest() }
        val folder = repository.operationDirectory(updateDirectory(), witness.operationId)
        assertTrue(folder.listFiles().orEmpty().isEmpty())
        assertTrue(kotlinx.coroutines.runBlocking { repository.reserveInstaller(witness.operationId, File(folder, "$releaseVersionName.apk")) } is AndroidInstallAdmission.Refused)
        assertEquals(null, repository.currentInstallAttempt())
    }

    /** Simulated installed metadata from ROOT-verified public fixtures, never an OS install.
     * Robolectric's archive primitive is unavailable on these JDK/API combinations;
     * real Android cache-file parsing remains a separate mandatory device check.
     */
    @Suppress("DEPRECATION")
    private fun registerSimulatedInstalledFixtureMetadata(context: Context) {
        val archive = File(context.cacheDir, "verified-public-fixture.apk")
        archive.writeBytes(apkBytes)
        try {
            val parsed = requireNotNull(VerifiedPublicArchiveFixture.read(archive))
            assertEquals(FIXTURE_APPLICATION_ID, parsed.packageName)
            assertEquals("1.5.3", parsed.versionName)
            val version = if (android.os.Build.VERSION.SDK_INT >= 28) parsed.longVersionCode else parsed.versionCode.toLong()
            assertEquals(100000L, version)
            val signatures = if (android.os.Build.VERSION.SDK_INT >= 28)
                requireNotNull(parsed.signingInfo).apkContentsSigners else requireNotNull(parsed.signatures)
            assertTrue(signatures.isNotEmpty())
            assertEquals(setOf(FIXTURE_CERTIFICATE_SHA256), signatures.map { sha256(it.toByteArray()) }.toSet())
            // Production installed-owner queries stay real. Existing explicit
            // constructor overrides represent an older installation of this signer.
            org.robolectric.Shadows.shadowOf(context.packageManager).installPackage(parsed)
        } finally { archive.delete() }
    }

    @Test
    fun zeroCapacityRefusesBeforeAnyApkRequestOrInstallReservation() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val witness = acceptedOperation()
        val beforeRequests = server.requestCount
        org.robolectric.shadows.ShadowStatFs.registerStats(context.cacheDir, 1_000_000, 1_000_000, 0)
        assertEquals(0L, android.os.StatFs(context.cacheDir.absolutePath).availableBytes)
        queueDownloadMetadata()
        val result = kotlinx.coroutines.runBlocking {
            repository.download(witness.operationId, updateDirectory()) { _, _ -> }
        }
        assertEquals(AndroidReleaseLookup.Reason.STORAGE, (result as AndroidApkDownload.Failed).reason)
        assertEquals(beforeRequests + 3, server.requestCount)
        repeat(3) { assertFalse(requireNotNull(server.takeRequest().target).endsWith(".apk")) }
        assertEquals(null, repository.currentInstallAttempt())
        assertFalse(repository.operationDirectory(updateDirectory(), witness.operationId).listFiles().orEmpty()
            .any { it.name.endsWith(".apk") || it.name.endsWith(".apk.part") })
    }

    @Test
    fun simulatedArchiveBoundaryRejectsUnknownAndChangedBytesAndRetainsWrongPackageFacts() {
        val archive = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "fixture-boundary.apk")
        try {
            archive.writeBytes(apkBytes)
            assertEquals(FIXTURE_APPLICATION_ID, requireNotNull(VerifiedPublicArchiveFixture.read(archive)).packageName)
            archive.writeBytes(apkBytes.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() })
            assertEquals(null, VerifiedPublicArchiveFixture.read(archive))
            archive.writeBytes(ByteArray(1024))
            assertEquals(null, VerifiedPublicArchiveFixture.read(archive))
            val wrong = requireNotNull(javaClass.classLoader?.getResourceAsStream("collie-wrong-package.apk")).use { it.readBytes() }
            archive.writeBytes(wrong)
            assertEquals("com.lateapex.collie.wrongfixture", requireNotNull(VerifiedPublicArchiveFixture.read(archive)).packageName)
        } finally { archive.delete() }
    }

    private fun updateDirectory() = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, ApkDownloadWorker.UPDATE_DIRECTORY)

    private fun selectSecondArchive() {
        releaseVersionName = "1.5.4"; releaseVersionCode = 100001L
        apkBytes = requireNotNull(javaClass.classLoader?.getResourceAsStream("collie-android-1.5.4.apk")).use { it.readBytes() }
        resignMetadata()
    }

    private fun resignMetadata() {
        metadataBytes = metadata()
        signatureText = sign(metadataBytes).toBase64Url()
    }

    private fun reopenedRepository(installed: () -> Long? = { 3L }) = AndroidReleaseRepository(
            archivePackageInfoForTests = VerifiedPublicArchiveFixture::read,
        context = ApplicationProvider.getApplicationContext(), client = repositoryClient, apiRoot = apiRoot,
        apiHost = "api.github.com", releaseHost = "github.com",
        releaseHosts = setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com"),
        allowedPort = server.port, verifier = AndroidReleaseMetadataVerifier(publicPem(metadataKey)), packageName = FIXTURE_APPLICATION_ID,
        installedVersionCodeOverrideForTests = installed,
        installedSignerDigestsOverrideForTests = { setOf(FIXTURE_CERTIFICATE_SHA256) },
    )

    private fun failCommits(delegate: android.content.SharedPreferences): android.content.SharedPreferences {
        val editorInterface = android.content.SharedPreferences.Editor::class.java
        val underlyingEditor = delegate.edit()
        var editorProxy: Any? = null
        val proxy = java.lang.reflect.Proxy.newProxyInstance(
            editorInterface.classLoader, arrayOf(editorInterface),
        ) { _, method, args ->
            when (method.name) {
                "commit" -> false
                "apply" -> null
                else -> {
                    val result = method.invoke(underlyingEditor, *(args ?: emptyArray()))
                    if (method.name.startsWith("put") || method.name in setOf("remove", "clear")) editorProxy else result
                }
            }
        }
        editorProxy = proxy
        return java.lang.reflect.Proxy.newProxyInstance(
            android.content.SharedPreferences::class.java.classLoader,
            arrayOf(android.content.SharedPreferences::class.java),
        ) { _, method, args ->
            if (method.name == "edit") proxy
            else method.invoke(delegate, *(args ?: emptyArray()))
        } as android.content.SharedPreferences
    }

    private fun acceptedOperation(): AndroidReleaseOperationWitness {
        queueReleaseFeed(); queueMetadata(); queueSignature()
        val lookup = kotlinx.coroutines.runBlocking { repository.latest(3) }
        assertTrue("Verified public fixture offer: ${(lookup as? AndroidReleaseLookup.Unavailable)?.reason}", lookup is AndroidReleaseLookup.Available)
        val candidate = (lookup as AndroidReleaseLookup.Available).candidate
        repeat(3) { server.takeRequest() }
        return requireNotNull(kotlinx.coroutines.runBlocking { repository.beginDownload(candidate) })
    }

    private fun stageOperation(witness: AndroidReleaseOperationWitness): File {
        val directory = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "android-updates")
        queueDownloadMetadata()
        server.enqueue(MockResponse.Builder().body(Buffer().write(apkBytes)).build())
        val result = kotlinx.coroutines.runBlocking {
            repository.download(witness.operationId, directory, reportProgress = { _, _ -> })
        }
        assertTrue("test stages the exact signed archive through the real HTTPS path", result is AndroidApkDownload.Ready)
        repeat(4) { server.takeRequest() }
        return (result as AndroidApkDownload.Ready).apk
    }

    private fun queueDownloadMetadata() {
        queueReleaseFeed(); queueMetadata(); queueSignature()
    }

    private fun queueReleaseFeed() = server.enqueue(MockResponse.Builder().body(releaseFeed()).build())
    private fun queueMetadata() = server.enqueue(MockResponse.Builder().body(Buffer().write(metadataBytes)).build())
    private fun queueSignature() = server.enqueue(MockResponse.Builder().body(signatureText).build())

    private fun releaseFeed(
        apkUrl: String = assetUrl("collie-android-$releaseVersionName.apk"),
        duplicateApk: Boolean = false,
    ): String {
        val assets = mutableListOf(
            "{\"name\":\"collie-android-$releaseVersionName.apk\",\"size\":${apkBytes.size},\"browser_download_url\":\"$apkUrl\"}",
            asset("release-metadata.json", metadataBytes.size.toLong()),
            asset("release-metadata.sig", signatureText.encodeToByteArray().size.toLong()),
        )
        if (duplicateApk) assets += assets.first()
        val stable = "{\"tag_name\":\"android-v$releaseVersionName\",\"draft\":false,\"prerelease\":false,\"body\":\"Stable release notes\",\"assets\":[${assets.joinToString(",")}] }"
        val server = "{\"tag_name\":\"v1.99.0\",\"draft\":false,\"prerelease\":false,\"assets\":[]}"
        val prerelease = "{\"tag_name\":\"android-v1.6.0-beta.1\",\"draft\":false,\"prerelease\":true,\"assets\":[]}"
        return "[$server,$prerelease,$stable]"
    }

    private fun asset(name: String, size: Long): String =
        "{\"name\":\"$name\",\"size\":$size,\"browser_download_url\":\"${assetUrl(name)}\"}"

    private fun assetUrl(name: String): String =
        "https://github.com:${server.port}/Buckeyes22/collie/releases/download/android-v$releaseVersionName/$name"

    private fun metadata(): ByteArray {
        return """
            {"tag":"android-v$releaseVersionName","applicationId":"com.lateapex.collie","versionName":"$releaseVersionName","versionCode":$releaseVersionCode,
             "sourceCommit":"${"a".repeat(40)}","buildInputsVerified":true,"apk":"collie-android-$releaseVersionName.apk",
             "apkSizeBytes":${apkBytes.size},"sha256":"${sha256(apkBytes)}","signingCertificateSha256":"$FIXTURE_CERTIFICATE_SHA256",
             "minSdk":37,"metadataKeyId":"$releaseKeyId","metadataKeyChain":$releaseChain}
        """.trimIndent().encodeToByteArray()
    }

    private fun sign(bytes: ByteArray, signer: KeyPair = metadataKey): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(signer.private)
        update(bytes)
        sign()
    }

    private fun transition(from: KeyPair, to: KeyPair, activation: Long, tag: String): String {
        val spki = Base64.getUrlEncoder().withoutPadding().encodeToString(to.public.encoded)
        val values = listOf("1", sha256(from.public.encoded), sha256(to.public.encoded), spki, activation.toString(), tag)
        val payload = buildString {
            append("CollieAndroidMetadataKeyTransitionV1\n")
            values.forEach { value -> append(value.toByteArray(Charsets.UTF_8).size).append(':').append(value) }
        }.encodeToByteArray()
        val signature = sign(payload, from).toBase64Url()
        return "{\"schemaVersion\":1,\"fromKeyId\":\"${values[1]}\",\"toKeyId\":\"${values[2]}\",\"successorSpki\":\"$spki\",\"activationVersionCode\":$activation,\"activationTag\":\"$tag\",\"signature\":\"$signature\"}"
    }

    private fun ByteArray.toBase64Url(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(this)

    private fun newP256KeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1"))
        generateKeyPair()
    }

    private fun publicPem(pair: KeyPair): ByteArray {
        val encoded = Base64.getMimeEncoder(64, "\n".encodeToByteArray()).encodeToString(pair.public.encoded)
        return "-----BEGIN PUBLIC KEY-----\n$encoded\n-----END PUBLIC KEY-----\n".encodeToByteArray()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        // Public signed parser resources are release packages; the unit APK uses .debug.
        // Only this constructor test input represents their actual package identity.
        private const val FIXTURE_APPLICATION_ID = "com.lateapex.collie"
        private const val FIXTURE_CERTIFICATE_SHA256 = "04cc6cede086ffbfcd6aa50c7a52eb13705bb6fdd08e00b76c785b04bfd6400d"
    }
}
