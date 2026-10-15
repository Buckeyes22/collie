package com.lateapex.collie.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PaneLayoutIntentStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val json = Json { ignoreUnknownKeys = true }
    private val cipher = TestCipher()
    private val owner = PaneLayoutIntentOwner("https://collie.example/", "a".repeat(64), "host", "work", null, "pane-1")

    @Before fun clear() {
        context.getSharedPreferences("collie_layout_intents", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun reservationIsEncryptedAndPendingRestoresAsUnknownWithoutMintingAnotherId() {
        val store = PaneLayoutIntentStore(context, cipher, json)
        val frozen = intent("req-1", "secret-command\n", "plan-1")
        assertEquals(PaneLayoutReservation.Reserved, store.reserve(frozen))

        val preferences = context.getSharedPreferences("collie_layout_intents", Context.MODE_PRIVATE)
        val envelope = preferences.getString("encrypted_journal", "").orEmpty()
        assertFalse(envelope.contains("secret-command"))
        assertFalse(envelope.contains("https://collie.example"))

        val restarted = PaneLayoutIntentStore(context, cipher, json)
        val recovered = restarted.unresolved(owner)
        assertNotNull(recovered)
        assertEquals("unknown", recovered!!.state)
        assertEquals("req-1", recovered.requestId)
        assertEquals(PaneLayoutReservation.Existing(recovered), restarted.reserve(frozen))
        assertEquals(PaneLayoutReservation.UnresolvedIntent, restarted.reserve(intent("req-2", "different", "plan-2")))
    }

    @Test fun resolvedReceiptClearsOnlyTheCorrelatedDurableIntent() {
        val store = PaneLayoutIntentStore(context, cipher, json)
        assertEquals(PaneLayoutReservation.Reserved, store.reserve(intent("req-1", "{}", "plan-1")))
        assertTrue(store.resolve(owner, "req-1"))
        assertEquals(null, store.unresolved(owner))
    }

    @Test fun wholeTransferParentAndOneChildAreCommittedTogetherAndRestartNeverReplays() {
        val transferOwner = PaneTransferOwner(owner.origin, owner.deviceIdentity, owner.host, owner.session, owner.subject, "workspace-1", "tab-1")
        val first = PaneTransferMember("pane-1", "a".repeat(64))
        val second = PaneTransferMember("pane-2", "b".repeat(64))
        val parent = PaneTransferIntent(transferOwner, 1, 2, "transfer-1", "new_tab", "workspace-2", "moved", null,
            listOf(PaneTransferRecipeStep("right", 0.5)), listOf(first, second), sourceLayout = PaneTransferLayout(0, 0, 80, 24, false, "pane-1",
                listOf(PaneTransferLayoutCell("pane-1", true, 0, 0, 40, 24), PaneTransferLayoutCell("pane-2", false, 40, 0, 40, 24)), emptyList()))
        val store = PaneLayoutIntentStore(context, cipher, json)
        val unrelated = intent("unrelated-id", "{\"kind\":\"zoom\"}", "unrelated-plan")
        assertEquals(PaneLayoutReservation.Reserved, store.reserve(unrelated))
        assertTrue(store.beginTransfer(parent))
        val firstChild = transferChild(parent, first, "child-1", first = true)
        assertTrue(store.reserveTransferChild(transferOwner, parent.transferId, firstChild))
        val restartedUnknown = PaneLayoutIntentStore(context, cipher, json).transfer(transferOwner)!!
        assertEquals("unknown", restartedUnknown.state)
        assertEquals("unknown", restartedUnknown.activeChild?.state)
        assertFalse(PaneLayoutIntentStore(context, cipher, json).reserveTransferChild(transferOwner, parent.transferId, firstChild.copy(requestId = "fresh-child")))
        assertFalse(store.completeTransferChild(transferOwner, parent.transferId, firstChild.requestId,
            PaneTransferStepResult(true, "pane-new-1", "workspace-2", "different-tab", first.witness, "tab-new")))
        assertEquals("pending", store.transfer(transferOwner)?.state)
        assertTrue(store.completeTransferChild(transferOwner, parent.transferId, firstChild.requestId,
            PaneTransferStepResult(true, "pane-new-1", "workspace-2", "tab-new", first.witness, "tab-new")))
        val progressed = store.transfer(transferOwner)!!
        assertEquals("ready", progressed.state)
        assertEquals(null, progressed.activeChild)
        assertEquals("pane-new-1", progressed.mappings.single().currentPaneId)
        val secondChild = transferChild(progressed, second, "child-2", first = false)
        assertTrue(store.reserveTransferChild(transferOwner, parent.transferId, secondChild))
        assertTrue(store.completeTransferChild(transferOwner, parent.transferId, secondChild.requestId,
            PaneTransferStepResult(true, "pane-new-2", "workspace-2", "tab-new", second.witness)))
        assertEquals("complete", store.transfer(transferOwner)?.state)
        assertEquals("unrelated-id", store.unresolved(owner)?.requestId)
    }

    @Test fun threePaneTransferConsumesEachPostContainerSplitAndCompletesDurably() {
        val transferOwner = PaneTransferOwner(owner.origin, owner.deviceIdentity, owner.host, owner.session, owner.subject, "workspace-1", "tab-1")
        val members = listOf(
            PaneTransferMember("pane-1", "a".repeat(64)),
            PaneTransferMember("pane-2", "b".repeat(64)),
            PaneTransferMember("pane-3", "c".repeat(64)),
        )
        val parent = PaneTransferIntent(transferOwner, 1, 2, "transfer-three", "new_tab", "workspace-2", "moved", null,
            listOf(PaneTransferRecipeStep("right", 0.5), PaneTransferRecipeStep("down", 0.4)), members,
            sourceLayout = PaneTransferLayout(0, 0, 80, 24, false, "pane-1", listOf(
                PaneTransferLayoutCell("pane-1", true, 0, 0, 40, 24),
                PaneTransferLayoutCell("pane-2", false, 40, 0, 40, 12),
                PaneTransferLayoutCell("pane-3", false, 40, 12, 40, 12),
            ), emptyList()))
        val store = PaneLayoutIntentStore(context, cipher, json)
        assertTrue(store.beginTransfer(parent))
        val first = transferChild(parent, members[0], "three-child-1", first = true)
        assertTrue(store.reserveTransferChild(transferOwner, parent.transferId, first))
        assertTrue(store.completeTransferChild(transferOwner, parent.transferId, first.requestId,
            PaneTransferStepResult(true, "pane-new-1", "workspace-2", "tab-new", members[0].witness, "tab-new")))

        val afterFirst = store.transfer(transferOwner)!!
        val second = transferChild(afterFirst, members[1], "three-child-2", first = false)
        assertTrue(store.reserveTransferChild(transferOwner, parent.transferId, second))
        assertTrue(store.completeTransferChild(transferOwner, parent.transferId, second.requestId,
            PaneTransferStepResult(true, "pane-new-2", "workspace-2", "tab-new", members[1].witness)))

        val afterSecond = store.transfer(transferOwner)!!
        val third = transferChild(afterSecond, members[2], "three-child-3", first = false)
        assertTrue(third.actionJson.contains("\"split\":\"down\""))
        val wrongStep = third.copy(actionJson = third.actionJson.replace("\"split\":\"down\"", "\"split\":\"right\""))
        assertFalse(store.reserveTransferChild(transferOwner, parent.transferId, wrongStep))
        assertTrue(store.reserveTransferChild(transferOwner, parent.transferId, third))
        assertTrue(store.completeTransferChild(transferOwner, parent.transferId, third.requestId,
            PaneTransferStepResult(true, "pane-new-3", "workspace-2", "tab-new", members[2].witness)))

        val recovered = PaneLayoutIntentStore(context, cipher, json).transfer(transferOwner)!!
        assertEquals("complete", recovered.state)
        assertEquals(listOf("pane-new-1", "pane-new-2", "pane-new-3"), recovered.mappings.map { it.currentPaneId })
        assertEquals(members.map { it.witness }, recovered.mappings.map { it.witness })
        val badRecipe = parent.copy(owner = transferOwner.copy(sourceWorkspaceId = "workspace-other", sourceTabId = "tab-other"),
            transferId = "bad-recipe", recipe = listOf(PaneTransferRecipeStep("right", 0.5)))
        assertFalse(PaneLayoutIntentStore(context, cipher, json).beginTransfer(badRecipe))
        assertFalse(PaneLayoutIntentStore(context, cipher, json).reserveTransferChild(transferOwner, parent.transferId,
            third.copy(requestId = "fresh-after-complete")))
    }

    @Test fun encryptionFailureBeforeReservationDoesNotCreateARecordOrAdmitAnotherId() {
        val failing = TestCipher().apply { failEncrypt = true }
        val store = PaneLayoutIntentStore(context, failing, json)
        assertEquals(PaneLayoutReservation.Unavailable, store.reserve(intent("req-1", "{}", "plan-1")))
        assertEquals(null, context.getSharedPreferences("collie_layout_intents", Context.MODE_PRIVATE).getString("encrypted_journal", null))
        assertEquals(PaneLayoutReservation.Unavailable, store.reserve(intent("req-2", "{}", "plan-2")))
    }

    @Test fun failedTerminalClearKeepsTheOriginalUnknownIdentityAvailableForSameIdOnly() {
        val rotatingCipher = TestCipher()
        val store = PaneLayoutIntentStore(context, rotatingCipher, json)
        val frozen = intent("req-1", "{\"kind\":\"zoom\"}", "plan-1")
        assertEquals(PaneLayoutReservation.Reserved, store.reserve(frozen))
        rotatingCipher.failEncrypt = true
        assertFalse(store.resolve(owner, "req-1"))
        assertEquals("unknown", store.unresolved(owner)?.state)
        assertEquals(PaneLayoutReservation.Unavailable, store.reserve(frozen))
        assertEquals(PaneLayoutReservation.Unavailable, store.reserve(intent("req-2", "{}", "plan-2")))
        assertEquals(frozen.requestId, store.unresolved(owner)?.requestId)
    }

    @Test fun explicitAbandonRequiresDurableAcknowledgementAndDoesNotResurrectAfterRestart() {
        val rotatingCipher = TestCipher()
        val store = PaneLayoutIntentStore(context, rotatingCipher, json)
        val frozen = intent("req-abandon", "{\"kind\":\"zoom\"}", "plan-abandon")
        assertEquals(PaneLayoutReservation.Reserved, store.reserve(frozen))
        assertTrue(store.markUnknown(owner, frozen.requestId))

        rotatingCipher.failEncrypt = true
        assertFalse(store.abandon(owner, frozen.requestId))
        assertEquals("req-abandon", store.unresolved(owner)?.requestId)
        assertEquals(PaneLayoutReservation.Unavailable, store.reserve(intent("new-id", "{}", "new-plan")))

        // A failed acknowledgement leaves the persisted original row recoverable.
        val afterFailure = PaneLayoutIntentStore(context, TestCipher(), json)
        assertEquals("req-abandon", afterFailure.unresolved(owner)?.requestId)
        assertEquals(PaneLayoutReservation.UnresolvedIntent, afterFailure.reserve(intent("another-id", "{}", "another-plan")))

        assertTrue(afterFailure.abandon(owner, frozen.requestId))
        assertEquals(null, afterFailure.unresolved(owner))
        val afterAcknowledgedAbandon = PaneLayoutIntentStore(context, TestCipher(), json)
        assertEquals(null, afterAcknowledgedAbandon.unresolved(owner))
        assertEquals(PaneLayoutReservation.Reserved, afterAcknowledgedAbandon.reserve(intent("explicit-new-action", "{}", "fresh-plan")))
    }

    @Test fun pendingMoveCustodyBindsTheSourceTabAndWorkspaceAliases() {
        val store = PaneLayoutIntentStore(context, cipher, json)
        val frozen = intent("move-request", "{\"kind\":\"move\"}", "move-plan")
            .copy(sourceTabId = "tab-old", sourceWorkspaceId = "space-old")
        assertEquals(PaneLayoutReservation.Reserved, store.reserve(frozen))
        assertEquals(PaneLayoutReservation.Existing(frozen.copy(state = "unknown")), store.reserve(frozen))
        assertEquals(PaneLayoutReservation.UnresolvedIntent,
            store.reserve(frozen.copy(sourceTabId = "tab-replacement")))
        val restarted = PaneLayoutIntentStore(context, cipher, json)
        assertEquals("tab-old", restarted.unresolved(owner)?.sourceTabId)
        assertEquals("space-old", restarted.unresolved(owner)?.sourceWorkspaceId)
    }

    @Test fun unreadableEnvelopeIsPreservedAndFailsClosedWithoutNewReservation() {
        val preferences = context.getSharedPreferences("collie_layout_intents", Context.MODE_PRIVATE)
        val corrupt = "{\"version\":99,\"iv\":\"bad\",\"ciphertext\":\"bad\"}"
        preferences.edit().putString("encrypted_journal", corrupt).commit()
        val store = PaneLayoutIntentStore(context, cipher, json)
        assertEquals(null, store.unresolved(owner))
        assertEquals(PaneLayoutReservation.Unavailable, store.reserve(intent("req-1", "{}", "plan-1")))
        assertFalse(store.recoverCustody(intent("req-1", "{}", "plan-1")))
        assertEquals(corrupt, preferences.getString("encrypted_journal", null))
    }

    @Test fun replacementCredentialAtSameOriginCannotLoadTheRetiredOwnersPendingIntent() {
        val store = PaneLayoutIntentStore(context, cipher, json)
        assertEquals(PaneLayoutReservation.Reserved, store.reserve(intent("old-request", "{}", "old-plan")))
        val replacement = owner.copy(deviceIdentity = "b".repeat(64))
        assertEquals(null, store.unresolved(replacement))
        assertEquals(PaneLayoutReservation.Reserved, store.reserve(PaneLayoutIntent(replacement, 1, 0, "new-request", "new-plan", "{}")))
        assertEquals("old-request", store.unresolved(owner)?.requestId)
        assertEquals("new-request", store.unresolved(replacement)?.requestId)
    }

    @Test fun ambiguousCommitRecoveryRecommitsRetainedWholeJournalBeforeSeparateAbandon() {
        val backing = context.getSharedPreferences("collie_layout_intents", Context.MODE_PRIVATE)
        var failNext = false
        val prefs = object : android.content.SharedPreferences by backing {
            override fun edit(): android.content.SharedPreferences.Editor {
                val editor = backing.edit()
                return object : android.content.SharedPreferences.Editor by editor {
                    // Fluent mutation must retain the wrapper, otherwise putString(...).commit()
                    // calls the backing editor and silently bypasses this failure injection.
                    override fun putString(key: String?, value: String?): android.content.SharedPreferences.Editor {
                        editor.putString(key, value)
                        return this
                    }
                    override fun commit(): Boolean {
                        val acknowledged = editor.commit()
                        if (failNext) { failNext = false; return false }
                        return acknowledged
                    }
                }
            }
        }
        val store = PaneLayoutIntentStore(context, cipher, json, prefs)
        val frozen = intent("original-id", "{\"kind\":\"zoom\"}", "original-plan")
        val other = frozen.copy(owner = owner.copy(paneId = "other-pane"), requestId = "other-id")
        assertEquals(PaneLayoutReservation.Reserved, store.reserve(frozen))
        assertEquals(PaneLayoutReservation.Reserved, store.reserve(other))
        // Worst case: commit(false) follows an actual changed cache/disk. A new wrapper is not proof.
        failNext = true
        assertFalse(store.resolve(owner, frozen.requestId))
        assertEquals(frozen.requestId, PaneLayoutIntentStore(context, cipher, json, prefs).unresolved(owner)?.requestId)
        assertEquals(PaneLayoutReservation.Unavailable, store.reserve(frozen))
        val retained = store.unresolved(owner)!!
        assertEquals("unknown", retained.state)
        assertTrue(store.needsRecovery(owner, frozen.requestId))
        assertFalse(store.recoverCustody(retained.copy(actionJson = "{}")))
        assertTrue(store.recoverCustody(retained))
        assertEquals(frozen.requestId, PaneLayoutIntentStore(context, cipher, json, prefs).unresolved(owner)?.requestId)
        assertEquals(other.requestId, PaneLayoutIntentStore(context, cipher, json, prefs).unresolved(other.owner)?.requestId)
        assertFalse(store.needsRecovery(owner, frozen.requestId))
        assertTrue(store.abandon(owner, frozen.requestId))
        val restarted = PaneLayoutIntentStore(context, cipher, json, prefs)
        assertEquals(null, restarted.unresolved(owner))
        assertEquals(other.requestId, restarted.unresolved(other.owner)?.requestId)
    }

    private fun intent(id: String, action: String, plan: String) = PaneLayoutIntent(owner, 1, 2, id, plan, action)

    private fun transferChild(parent: PaneTransferIntent, member: PaneTransferMember, id: String, first: Boolean): PaneLayoutIntent {
        val remaining = parent.sourceMembers.filter { source -> parent.mappings.none { it.originalPaneId == source.originalPaneId } }
        val step = if (first) null else parent.recipe.getOrNull(parent.mappings.size - 1)!!
        val destination = if (first) buildJsonObject {
            put("type", JsonPrimitive("new_tab")); put("workspaceId", JsonPrimitive("workspace-2")); put("label", JsonPrimitive("moved"))
        } else buildJsonObject {
            val firstMapping = parent.mappings.first()
            put("type", JsonPrimitive("tab")); put("tabId", JsonPrimitive(firstMapping.tabId)); put("split", JsonPrimitive(step!!.direction))
            put("targetPaneId", JsonPrimitive(firstMapping.currentPaneId)); put("ratio", JsonPrimitive(step.ratio))
        }
        val guard = buildJsonObject {
            put("schema", JsonPrimitive(1)); put("transferId", JsonPrimitive(parent.transferId))
            put("sourceWorkspaceId", JsonPrimitive(parent.owner.sourceWorkspaceId)); put("sourceTabId", JsonPrimitive(parent.owner.sourceTabId))
            put("remainingSource", buildJsonArray { remaining.forEach { row -> add(buildJsonObject { put("paneId", JsonPrimitive(row.originalPaneId)); put("witness", JsonPrimitive(row.witness)) }) } })
            if (first) put("destination", JsonNull) else put("destination", buildJsonObject {
                val firstMapping = parent.mappings.first()
                put("workspaceId", JsonPrimitive(firstMapping.workspaceId)); put("tabId", JsonPrimitive(firstMapping.tabId))
                put("members", buildJsonArray { parent.mappings.forEach { row -> add(buildJsonObject { put("paneId", JsonPrimitive(row.currentPaneId)); put("witness", JsonPrimitive(row.witness)) }) } })
            })
        }
        val action = buildJsonObject { put("kind", JsonPrimitive("move")); put("destination", destination); put("transfer", guard) }
        return PaneLayoutIntent(owner.copy(paneId = member.originalPaneId), 1, 2, id, "plan-$id", action.toString(), parent.owner.sourceTabId, parent.owner.sourceWorkspaceId)
    }

    private class TestCipher : SecretCipher {
        var failEncrypt = false
        private val key = javax.crypto.spec.SecretKeySpec(ByteArray(32) { 7 }, "AES")
        override fun encrypt(plaintext: ByteArray): com.lateapex.collie.data.CipherEnvelope {
            if (failEncrypt) throw IllegalStateException("injected keystore failure")
            val aes = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            aes.init(javax.crypto.Cipher.ENCRYPT_MODE, key)
            return com.lateapex.collie.data.CipherEnvelope(aes.iv, aes.doFinal(plaintext))
        }
        override fun decrypt(envelope: com.lateapex.collie.data.CipherEnvelope): ByteArray {
            val aes = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            aes.init(javax.crypto.Cipher.DECRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, envelope.iv))
            return aes.doFinal(envelope.ciphertext)
        }
    }
}
