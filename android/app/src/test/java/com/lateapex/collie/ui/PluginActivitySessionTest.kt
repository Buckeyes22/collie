package com.lateapex.collie.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.data.ScopedReadContext
import com.lateapex.collie.data.ScopedRequestKey
import com.lateapex.collie.domain.CollieOrigin
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.domain.Scope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class PluginActivitySessionTest {
    private val scope = Scope(host = "fixture-host")

    private fun committedActor(identity: String, host: String = "fixture-host"): ScopedReadContext {
        val origin = CollieOrigin("https://plugins.fixture/")
        val connection = Connection(origin, "Fixture", "test-only-token")
        return ScopedReadContext(
            key = ScopedRequestKey(origin.value, 9, identity, 0, host, null, null),
            connection = connection,
            navigationGeneration = 3,
            requestScope = Scope(host = host),
            connectionIncarnation = "fixture-incarnation",
            committedStore = true,
        )
    }

    @Test
    fun processRestoreAllowsReceiptReadOnlyForUnchangedCommittedPairingAndHost() {
        val fingerprint = "a".repeat(64)
        val owner = requireNotNull(PluginReceiptOwner.create(fingerprint, scope))
        val sameProfile = committedActor(fingerprint)
        val replacedProfile = committedActor("b".repeat(64))
        val differentHost = committedActor(fingerprint, "another-host")

        assertSame(sameProfile, matchingCommittedPluginReceiptActor(owner, sameProfile))
        assertNull("a replacement pairing cannot read the old profile's receipts", matchingCommittedPluginReceiptActor(owner, replacedProfile))
        assertNull("a different host cannot read the old host's receipts", matchingCommittedPluginReceiptActor(owner, differentHost))
    }

    @Test
    fun receiptIdentityIsBoundToCommittedCredentialFingerprint() {
        val owner = requireNotNull(PluginReceiptOwner.create("c".repeat(64), scope))
        val unsupportedStoreActor = committedActor("c".repeat(64)).let {
            ScopedReadContext(it.key, it.connection, it.navigationGeneration, it.requestScope, it.connectionIncarnation, false)
        }
        assertTrue(owner.matches(committedActor("c".repeat(64)), requireCommitted = true))
        assertTrue(!owner.matches(unsupportedStoreActor, requireCommitted = true))
    }

    @Test
    fun lateHydrationCachesTheMatchingActorAcrossReceiptPostCheck() {
        val fingerprint = "d".repeat(64)
        val owner = requireNotNull(PluginReceiptOwner.create(fingerprint, scope))
        val hydratedActor = committedActor(fingerprint)
        val cache = PluginReceiptActorCache(initial = null)
        var providerCalls = 0
        val beforeGet = cache.resolve(
            receiptOwner = owner,
            scope = scope,
            requireCommitted = true,
            candidateProvider = { providerCalls++; null },
            isCurrent = { true },
        )
        assertNull("the committed store has not hydrated yet", beforeGet)

        val afterHydration = cache.resolve(
            receiptOwner = owner,
            scope = scope,
            requireCommitted = true,
            candidateProvider = { providerCalls++; hydratedActor },
            isCurrent = { true },
        )
        assertSame("late hydration yields the eligible receipt actor", hydratedActor, afterHydration)

        val afterGet = cache.resolve(
            receiptOwner = owner,
            scope = scope,
            requireCommitted = true,
            candidateProvider = { providerCalls++; committedActor(fingerprint) },
            isCurrent = { true },
        )
        assertSame("the post-GET identity check sees the cached actor", afterHydration, afterGet)
        assertEquals("cached receipt actor avoids minting a different identity", 2, providerCalls)
    }

    @Test
    fun reopeningPreservesOwnerAndConfigReceiptFamilies() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val preferencesName = "plugin-receipts-test-${UUID.randomUUID()}"
        val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        val owner = requireNotNull(PluginReceiptOwner.create("e".repeat(64), scope))
        val firstStore = PluginReceiptStore(context, preferencesName)
        try {
            assertTrue(firstStore.add(owner, "owner-request", PluginReceiptKind.OWNER_OPERATION))
            assertTrue(firstStore.add(owner, "config-request", PluginReceiptKind.CONTROL_OPERATION))

            val reopened = PluginReceiptStore(context, preferencesName).pending(owner)
            assertEquals(PluginReceiptKind.OWNER_OPERATION, reopened["owner-request"])
            assertEquals(PluginReceiptKind.CONTROL_OPERATION, reopened["config-request"])
            val session = PluginActivitySession(scope, null, null, owner, reopened, firstStore)
            assertEquals(PluginReceiptKind.OWNER_OPERATION, session.kind("owner-request"))
            assertEquals(PluginReceiptKind.CONTROL_OPERATION, session.kind("config-request"))
            assertEquals(PluginReceiptKind.OWNER_OPERATION, PendingPluginReceipt.parse("owner:owner-request")?.kind)
            assertEquals(PluginReceiptKind.CONTROL_OPERATION, PendingPluginReceipt.parse("control:config-request")?.kind)
            assertEquals(PluginReceiptKind.LEGACY_UNKNOWN, PendingPluginReceipt.parse("old-untyped-id")?.kind)
        } finally {
            preferences.edit().clear().commit()
        }
    }
}
