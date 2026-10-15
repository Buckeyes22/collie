package com.lateapex.collie.ui

import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.R
import com.lateapex.collie.data.CollieRepository
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.ApiFailure
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.CollieApiClient
import com.lateapex.collie.network.OriginValidator
import com.lateapex.collie.network.PairResult
import java.net.InetAddress
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Owned emulator fixture: real HTTPS/native HTTP/bridge/encrypted pairing, fake host-local mux.
 * The only alternate trust is this instrumentation APK's exact ephemeral fixture CA.
 * Production client/trust and default hostname verification remain intact.
 */
@RunWith(AndroidJUnit4::class)
class NativeHttpsFixtureTest {
    // Never stringify successful pairing values or raw error bodies.
    private fun safeResult(result: ApiResult<*>): String = when (result) {
        is ApiResult.Success -> "Success HTTP ${result.status}"
        is ApiResult.NotModified -> "NotModified"
        is ApiResult.Failure -> when (val failure = result.error) {
            is ApiFailure.Http -> "HTTP ${failure.status}"
            is ApiFailure.Protocol -> "Protocol ${failure.message}"
            is ApiFailure.Network -> "Network"
            is ApiFailure.Redirect -> "Redirect ${failure.status}"
            ApiFailure.Timeout -> "Timeout"
        }
    }

    @Test fun actualMountedHttpsPairingPaneAndDashboardUseProductionNativeTransport() = runBlocking {
        val originText = requireNotNull(InstrumentationRegistry.getArguments().getString("fixtureOrigin"))
        val app = ApplicationProvider.getApplicationContext<CollieApplication>()
        val origin = OriginValidator().requireValid(originText)
        val store = app.container.connectionStore
        val saved = store.connection.value
        val previous = app.container.repository
        val cert = InstrumentationRegistry.getInstrumentation().context.assets.open("native-https-fixture-cert.pem").use {
            CertificateFactory.getInstance("X.509").generateCertificate(it)
        }
        val keys = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("owned-fixture", cert) }
        val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(keys) }
        val trust = managers.trustManagers.single() as X509TrustManager
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        val client = CollieApiClient.defaultHttpClient().newBuilder().sslSocketFactory(tls.socketFactory, trust).build()
        val api = CollieApiClient(client, app.container.json)
        val repository = CollieRepository(api, store, OriginValidator())
        val base = java.net.URI(origin.value)
        val bootstrap = "https://${base.rawAuthority}/fixture-test/bootstrap"
        try {
            val code = client.newCall(Request.Builder().url(bootstrap).build()).execute().use { response ->
                assertEquals(200, response.code)
                app.container.json.parseToJsonElement(response.body!!.string()).jsonObject.getValue("code").jsonPrimitive.content
            }
            val paired = repository.pair(origin.value, "Owned HTTPS ${System.currentTimeMillis()}", code)
            assertTrue("Actual native pairing failed: ${safeResult(paired)}", paired is ApiResult.Success && paired.value is PairResult.Paired)
            assertTrue("Production encrypted store did not retain paired connection", store.connection.value?.isPaired == true)
            val config = repository.config()
            assertTrue("Actual mounted config failed: ${safeResult(config)}", config is ApiResult.Success)
            val snapshot = repository.snapshot()
            assertTrue("Actual mounted snapshot failed: ${safeResult(snapshot)}", snapshot is ApiResult.Success)
            if (InstrumentationRegistry.getArguments().getString("fixtureExpectedNodes") == "3") {
                val topology = (snapshot as ApiResult.Success).value.servers.orEmpty()
                assertEquals("Real three-node Crew did not reach native transport", 3, topology.size)
                assertTrue("A Crew member is unreachable", topology.all { it.reachable })
                assertEquals("Expected one lead", 1, topology.count { it.isLead })
                for ((host, paneId, expectedText) in listOf(
                    Triple("crew-peer-a", "fixture:peer-a", "actual HTTPS Crew peer A"),
                    Triple("crew-peer-b", "fixture:peer-b", "actual HTTPS Crew peer B"),
                )) {
                    val scoped = repository.readPane(PaneAddress(Scope(host = host), paneId))
                    assertTrue("Native scoped Crew pane read failed for $host: ${safeResult(scoped)}",
                        scoped is ApiResult.Success && scoped.value.pane.text.contains(expectedText))
                }
            }
            val pane = repository.readPane(PaneAddress(paneId = "fixture:pane"))
            assertTrue("Actual mounted pane read failed", pane is ApiResult.Success && pane.value.pane.text.contains("actual HTTPS native fixture"))
            assertTrue(repository.writesAllowed())
            // Replacing only the fixture CA must not disable hostname verification.
            val wrong = client.newBuilder().dns(object : okhttp3.Dns { override fun lookup(hostname: String): List<InetAddress> = listOf(InetAddress.getByName("127.0.0.1")) }).build()
            var mismatch = false
            try { wrong.newCall(Request.Builder().url("https://wrong-fixture.invalid:${base.port}/fixture/api/health").build()).execute().close() }
            catch (e: SSLPeerUnverifiedException) { mismatch = true }
            assertTrue("Fixture client bypassed hostname verification", mismatch)
            var untrusted = false
            try { OkHttpClient().newCall(Request.Builder().url(origin.value + "api/health").build()).execute().close() }
            catch (e: SSLHandshakeException) { untrusted = true }
            assertTrue("Production defaults trusted an uninstalled fixture CA", untrusted)
            app.container.javaClass.getDeclaredField("repository").apply { isAccessible = true }.set(app.container, repository)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                var rendered = false
                val deadline = System.currentTimeMillis() + 15_000L
                while (!rendered && System.currentTimeMillis() < deadline) {
                    Thread.sleep(100)
                    scenario.onActivity { activity ->
                        val rows = activity.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.pane_list)
                        rendered = (rows.adapter?.itemCount ?: 0) > 0
                    }
                }
                assertTrue("Actual native dashboard did not render the HTTPS fixture pane", rendered)
            }
        } finally {
            app.container.javaClass.getDeclaredField("repository").apply { isAccessible = true }.set(app.container, previous)
            store.clear(); saved?.let(store::save)
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }
}
