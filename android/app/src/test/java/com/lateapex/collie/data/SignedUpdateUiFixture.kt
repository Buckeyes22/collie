package com.lateapex.collie.data

import android.content.Context
import androidx.work.WorkerFactory
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import java.io.File
import java.net.InetAddress
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.HttpUrl.Companion.toHttpUrl
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Actual signed HTTPS/repository producer; PackageManager fixture identity is simulated.
 * No Activity fields or WorkInfo state are injected. The production worker performs the real
 * download under one originally bound repository and publishes its genuine result.
 */
internal class SignedUpdateUiFixture(
    val context: Context,
    private val factory: OriginalUpdateWorkerFactory,
    beforeInstall: suspend () -> Unit = {},
) : AutoCloseable {
    private val installedVersionCode = com.lateapex.collie.BuildConfig.VERSION_CODE.toLong()
    private val apk = requireNotNull(javaClass.classLoader?.getResourceAsStream("collie-android-1.5.3.apk"))
        .use { it.readBytes() }
    private val key = KeyPairGenerator.getInstance("EC").run {
        initialize(ECGenParameterSpec("secp256r1")); generateKeyPair()
    }
    private val server = MockWebServer()
    private val metadata = """{"tag":"android-v1.5.3","applicationId":"com.lateapex.collie","versionName":"1.5.3","versionCode":100000,"sourceCommit":"${"a".repeat(40)}","buildInputsVerified":true,"apk":"collie-android-1.5.3.apk","apkSizeBytes":${apk.size},"sha256":"${sha256(apk)}","signingCertificateSha256":"04cc6cede086ffbfcd6aa50c7a52eb13705bb6fdd08e00b76c785b04bfd6400d","minSdk":37,"metadataKeyId":"${sha256(key.public.encoded)}","metadataKeyChain":[]}""".encodeToByteArray()
    private val signature = Base64.getUrlEncoder().withoutPadding().encodeToString(
        Signature.getInstance("SHA256withECDSA").run { initSign(key.private); update(metadata); sign() },
    )
    val repository: AndroidReleaseRepository
    val requestCount: Int get() = server.requestCount
    val directory = File(context.cacheDir, ApkDownloadWorker.UPDATE_DIRECTORY)
    var operation: AndroidReleaseOperationWitness? = null
        private set

    init {
        assertTrue(context.getSharedPreferences("android_release_trust", Context.MODE_PRIVATE).edit().clear().commit())
        org.robolectric.shadows.ShadowStatFs.registerStats(context.cacheDir, 1_000_000, 1_000_000, 1_000_000)
        val archive = File(context.cacheDir, "ui-public-owner.apk")
        archive.writeBytes(apk)
        try {
            val owner = requireNotNull(VerifiedPublicArchiveFixture.read(archive))
            assertEquals("com.lateapex.collie", owner.packageName)
            org.robolectric.Shadows.shadowOf(context.packageManager).installPackage(owner)
        } finally { archive.delete() }
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("api.github.com")
            .addSubjectAlternativeName("github.com").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server.protocols = listOf(Protocol.HTTP_1_1)
        server.useHttps(serverTls.sslSocketFactory())
        server.start()
        val client = OkHttpClient.Builder().protocols(listOf(Protocol.HTTP_1_1))
            .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = listOf(InetAddress.getByName("127.0.0.1"))
            }).followRedirects(false).followSslRedirects(false).build()
        val publicKey = "-----BEGIN PUBLIC KEY-----\n${Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(key.public.encoded)}\n-----END PUBLIC KEY-----\n"
        repository = AndroidReleaseRepository(
            context, client = client, apiRoot = "https://api.github.com:${server.port}".toHttpUrl(),
            allowedPort = server.port, verifier = AndroidReleaseMetadataVerifier(publicKey.encodeToByteArray()),
            packageName = "com.lateapex.collie", installedVersionCodeOverrideForTests = { installedVersionCode },
            installedSignerDigestsOverrideForTests = { setOf("04cc6cede086ffbfcd6aa50c7a52eb13705bb6fdd08e00b76c785b04bfd6400d") },
            archivePackageInfoForTests = VerifiedPublicArchiveFixture::read,
            beforeFinalInstallAdmissionForTests = beforeInstall,
        )
    }

    fun enqueueDiscovery() {
        val assets = listOf("collie-android-1.5.3.apk" to apk.size, "release-metadata.json" to metadata.size,
            "release-metadata.sig" to signature.encodeToByteArray().size).joinToString(",") { (name, size) ->
            """{"name":"$name","size":$size,"browser_download_url":"https://github.com:${server.port}/Buckeyes22/collie/releases/download/android-v1.5.3/$name"}"""
        }
        server.enqueue(MockResponse.Builder().body("""[{"tag_name":"android-v1.5.3","draft":false,"prerelease":false,"body":"Verified public fixture","assets":[$assets]}]""").build())
        server.enqueue(MockResponse.Builder().body(Buffer().write(metadata)).build())
        server.enqueue(MockResponse.Builder().body(signature).build())
    }

    suspend fun reserveDownload(): AndroidReleaseOperationWitness {
        enqueueDiscovery()
        val lookup = repository.latest(installedVersionCode)
        assertTrue("Actual signed HTTPS lookup produces offer: ${(lookup as? AndroidReleaseLookup.Unavailable)?.reason}", lookup is AndroidReleaseLookup.Available)
        val witness = requireNotNull(repository.beginDownload((lookup as AndroidReleaseLookup.Available).candidate))
        operation = witness
        enqueueDiscovery()
        server.enqueue(MockResponse.Builder().body(Buffer().write(apk)).build())
        factory.originals[witness.operationId] = repository
        return witness
    }

    override fun close() {
        operation?.let { factory.originals.remove(it.operationId, repository) }
        server.close()
    }

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}

/** Instance-owned factory for this actual WorkManager runtime, not a global override. */
internal class OriginalUpdateWorkerFactory : WorkerFactory() {
    val originals = ConcurrentHashMap<String, AndroidReleaseRepository>()
    val createCalls = java.util.concurrent.atomic.AtomicInteger()
    val matchedOriginals = java.util.concurrent.atomic.AtomicInteger()

    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? {
        if (workerClassName != ApkDownloadWorker::class.java.name) return null
        createCalls.incrementAndGet()
        val id = workerParameters.inputData.getString(ApkDownloadWorker.INPUT_OPERATION_ID) ?: return null
        val original = originals[id] ?: return null
        matchedOriginals.incrementAndGet()
        return ApkDownloadWorker(appContext, workerParameters, original)
    }
}
