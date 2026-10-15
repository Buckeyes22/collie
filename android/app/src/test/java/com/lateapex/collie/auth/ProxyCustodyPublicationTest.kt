package com.lateapex.collie.auth

import com.lateapex.collie.data.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import mockwebserver3.MockResponse
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
class ProxyCustodyPublicationTest {
    private fun begin(f: ProxyHttpsFixture): okhttp3.HttpUrl {
        f.server.enqueue(MockResponse.Builder().body(f.discovery()).build()); f.server.enqueue(MockResponse.Builder().body(f.jwks()).build())
        return f.coordinator.begin(f.actor).toHttpUrl()
    }
    private fun callback(f: ProxyHttpsFixture, url: okhttp3.HttpUrl) = ProxyProfile.REDIRECT + "?state=" + url.queryParameter("state") +
        "&code=once&iss=" + java.net.URLEncoder.encode(f.server.url("/").toString(), "UTF-8")

    @Test fun consumedCallbackCannotPublishAfterCompletedRetirementEvenWithoutReplacement() {
        ProxyHttpsFixture().use { f ->
            val url = begin(f); f.server.enqueue(MockResponse.Builder().body(f.tokens(url.queryParameter("nonce")!!)).build())
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            f.coordinator.beforePublicationForTesting = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            val executor = Executors.newSingleThreadExecutor()
            try {
                val old = executor.submit<Boolean> { f.coordinator.consumeCallback(callback(f, url)) }
                assertTrue(entered.await(5, TimeUnit.SECONDS)); assertNull(f.store.record()!!.transaction)
                val consumedRevision = f.store.record()!!.revision
                f.navigation++; f.coordinator.retire() // Completed synchronous custody retirement ACK.
                val retiredRevision = f.store.record()!!.revision
                assertNotEquals(consumedRevision, retiredRevision)
                release.countDown(); assertFalse(old.get(5, TimeUnit.SECONDS))
                assertEquals(retiredRevision, f.store.record()!!.revision); assertNull(f.store.record()!!.tokens)
                assertFalse(f.coordinator.consumeCallback(callback(f, url))); assertEquals(3, f.server.requestCount)
            } finally { release.countDown(); executor.shutdownNow() }
        }
    }

    @Test fun oldImportAfterLastCheckCannotEraseTheReplacementProfileAndCurrentImportSucceeds() {
        ProxyHttpsFixture().use { f ->
            val oldActor = f.actor; val entered = CountDownLatch(1); val release = CountDownLatch(1)
            f.coordinator.beforePublicationForTesting = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            val executor = Executors.newSingleThreadExecutor()
            try {
                val old = executor.submit<Boolean> { runCatching { f.coordinator.importProfile(f.rawProfile, oldActor) }.isSuccess }
                assertTrue(entered.await(5, TimeUnit.SECONDS)); f.navigation++; f.coordinator.retire()
                f.coordinator.beforePublicationForTesting = {}; f.coordinator.importProfile(f.rawProfile, f.actor)
                val replacement = f.store.record()!!.revision
                release.countDown(); assertFalse(old.get(5, TimeUnit.SECONDS)); assertEquals(replacement, f.store.record()!!.revision)
                assertEquals(f.rawProfile, f.store.record()!!.profile); assertEquals(0, f.server.requestCount)
            } finally { release.countDown(); executor.shutdownNow() }
        }
    }

    @Test fun beginAndRefreshLastCheckPublicationsRefuseAfterRetirementAndReplacement() {
        for (operation in listOf("begin", "refresh")) ProxyHttpsFixture().use { f ->
            if (operation == "begin") { f.server.enqueue(MockResponse.Builder().body(f.discovery()).build()); f.server.enqueue(MockResponse.Builder().body(f.jwks()).build()) }
            else {
                f.readyTokens(); val r = f.store.record()!!; val prior = r.tokens!!
                f.store.write(r.copyCustody(tokens = ProxyTokens(prior.accessToken, prior.refreshToken, prior.subject, 1, prior.thumbprint, prior.nonce)))
                f.server.enqueue(MockResponse.Builder().body(f.tokens(prior.nonce)).build())
            }
            val entered = CountDownLatch(1); val release = CountDownLatch(1)
            f.coordinator.beforePublicationForTesting = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            val executor = Executors.newSingleThreadExecutor()
            try {
                val old = executor.submit<Boolean> { runCatching { if (operation == "begin") f.coordinator.begin(f.actor) else f.coordinator.credentials(f.connection) }.isSuccess }
                assertTrue(entered.await(5, TimeUnit.SECONDS)); f.navigation++; f.coordinator.retire()
                f.coordinator.beforePublicationForTesting = {}; f.coordinator.importProfile(f.rawProfile, f.actor); f.readyTokens()
                val replacement = f.store.record()!!.revision
                release.countDown(); assertFalse(old.get(5, TimeUnit.SECONDS)); assertEquals(replacement, f.store.record()!!.revision)
                assertNull(f.store.record()!!.transaction); assertNotNull(f.coordinator.credentials(f.connection))
            } finally { release.countDown(); executor.shutdownNow() }
        }
    }

    @Test fun stableEnrollmentSurvivesActualRefreshExpiryAndReloginThenDurablyRetiresOnlyExactBinding() {
        ProxyHttpsFixture().use { f ->
            f.readyTokens(); val events = mutableListOf<ProxyBindingRetirement>()
            f.coordinator.bindingLifecycle.observe { events.add(it) }
            var access = f.store.record()!!.tokens!!.accessToken
            f.coordinator.acceptBinding(f.connection, access, "binding-fixture-id", 7)
            val accepted = f.store.record()!!; val token = accepted.tokens!!
            f.store.write(accepted.copyCustody(tokens = ProxyTokens(token.accessToken, "refresh", token.subject, 1, token.thumbprint, token.nonce)))
            f.server.enqueue(MockResponse.Builder().body(f.tokens(token.nonce)).build())
            access = f.coordinator.credentials(f.connection)!!.second.accessToken
            assertEquals("binding-fixture-id", f.store.record()!!.binding!!.bindingId); assertTrue(events.isEmpty())
            f.coordinator.identityFailed(f.connection, access); assertNull(f.store.record()!!.tokens)
            assertEquals(7L, f.store.record()!!.binding!!.revision); assertTrue(events.isEmpty())
            val url = begin(f); f.server.enqueue(MockResponse.Builder().body(f.tokens(url.queryParameter("nonce")!!)).build())
            assertTrue(f.coordinator.consumeCallback(callback(f, url))); access = f.store.record()!!.tokens!!.accessToken
            f.coordinator.bindingRetired(f.connection, access, "unrelated-binding-id", 7); assertTrue(events.isEmpty())
            f.coordinator.bindingRetired(f.connection, access, "binding-fixture-id", 8); assertTrue(events.isEmpty())
            f.coordinator.bindingRetired(f.connection, "old-or-foreign-access", "binding-fixture-id", 7); assertTrue(events.isEmpty())
            f.coordinator.bindingRetired(f.connection, access, "binding-fixture-id", 7)
            val exact = ProxyBindingRetirement("binding-fixture-id", 7)
            assertEquals(listOf(exact), events); assertNull(f.store.record()!!.binding); assertNull(f.store.record()!!.tokens)
            assertEquals("pairing-secret", f.connection.token)
            val restarted = ProxySignInCoordinator(f.store, f.key, { f.connection }, { f.actor }, f.client)
            val replay = mutableListOf<ProxyBindingRetirement>(); restarted.bindingLifecycle.observe { replay.add(it) }
            assertEquals(listOf(exact), replay); assertEquals(listOf(exact), restarted.bindingLifecycle.pending())
            restarted.bindingLifecycle.acknowledge(ProxyBindingRetirement("unrelated-binding-id", 7))
            assertEquals(listOf(exact), restarted.bindingLifecycle.pending())
            restarted.bindingLifecycle.acknowledge(exact); assertTrue(f.store.pendingRetirements().isEmpty())
        }
    }

    @Test fun authenticatedBindingReplacementAfterRefreshRetiresOldWitnessOnceAndRetainsNew() {
        ProxyHttpsFixture().use { f ->
            f.readyTokens(); val access = f.store.record()!!.tokens!!.accessToken
            f.coordinator.acceptBinding(f.connection, access, "binding-fixture-id", 1)
            val r = f.store.record()!!; val token = r.tokens!!
            f.store.write(r.copyCustody(tokens = ProxyTokens(token.accessToken, "refresh", token.subject, 1, token.thumbprint, token.nonce)))
            f.server.enqueue(MockResponse.Builder().body(f.tokens(token.nonce)).build())
            val refreshed = f.coordinator.credentials(f.connection)!!.second.accessToken
            f.coordinator.acceptBinding(f.connection, refreshed, "binding-replacement-id", 2)
            f.coordinator.acceptBinding(f.connection, refreshed, "binding-replacement-id", 2)
            assertEquals(listOf(ProxyBindingRetirement("binding-fixture-id", 1)), f.coordinator.bindingLifecycle.pending())
            assertEquals("binding-replacement-id", f.store.record()!!.binding!!.bindingId)
        }
    }
}
