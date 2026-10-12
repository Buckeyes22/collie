package com.lateapex.collie.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.network.*
import com.lateapex.collie.ui.NativePreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

@RunWith(RobolectricTestRunner::class)
class OfflineContentStoreTest {
    @Test fun defaultsDoNotPersistReadsOrEnableDrafts() = Fixture().use { f ->
        assertEquals(OfflineContentStore.Retention.OFF, f.store.retention)
        assertFalse(f.store.draftsEnabled)
        assertFalse(f.store.retainDashboard(f.dashboard, snapshot()) { true })
        assertFalse(f.store.retainPane(f.pane, mirror(), entries()) { true })
        assertNull(f.store.dashboard(f.dashboard)); assertNull(f.store.pane(f.pane))
        assertEquals(0L to 0, f.store.storageUsage())
    }

    @Test fun actualEncryptedColdStoreRetainsOnlyMaskedDisplayAndNeverActionsOrMedia() = Fixture().use { f ->
        assertTrue(f.store.chooseRetention(OfflineContentStore.Retention.SEVEN_DAYS))
        assertFalse(f.store.draftsEnabled)
        assertTrue(f.store.retainDashboard(f.dashboard, snapshot()) { true })
        assertTrue(f.store.retainPane(f.pane, mirror(), entries()) { true })
        val cold = f.cold()
        val dashboard = cold.dashboard(f.dashboard)!!
        assertNull(dashboard.value.device); assertNull(dashboard.value.controlAuthority)
        assertNull(dashboard.value.update); assertNull(dashboard.value.notifications)
        assertEquals("", dashboard.value.agents.single().cwd)
        val pane = cold.pane(f.pane)!!
        assertEquals("masked retained output", pane.value.pane!!.text)
        assertEquals(false, pane.value.pane!!.filesReadReady)
        assertNull(pane.value.pane!!.linkText)
        assertTrue(pane.value.entries.flatMap { it.parts }.all { it.kind == "text" && it.call == null && it.url == null && it.result == null })
        assertTrue(pane.value.entries.flatMap { it.parts }.any { it.text == "displayed tool summary\ndisplayed result" })
        assertEquals(f.clock, pane.provenance.capturedAt)
        for (file in f.directory.walkTopDown().filter { it.isFile }) {
            val disk = file.readBytes().decodeToString()
            assertFalse(disk.contains("masked retained output")); assertFalse(disk.contains("private/repository"))
            assertFalse(disk.contains("never-store-this-secret")); assertFalse(disk.contains("https://retention.test"))
        }
    }

    @Test fun mirrorAndDashboardExpireAt24HoursWhileSevenDayChatRemainsAndThenPrunes() = Fixture().use { f ->
        f.store.chooseRetention(OfflineContentStore.Retention.SEVEN_DAYS)
        f.store.retainDashboard(f.dashboard, snapshot()) { true }
        f.store.retainPane(f.pane, mirror(), entries()) { true }
        f.clock += 24L * 60 * 60 * 1000
        assertNull(f.cold().dashboard(f.dashboard))
        val tail = f.cold().pane(f.pane)!!
        assertNull(tail.value.pane); assertFalse(tail.value.entries.isEmpty())
        f.clock += 6L * 24 * 60 * 60 * 1000
        assertNull(f.cold().pane(f.pane)); assertEquals(0L to 0, f.store.storageUsage())
    }

    @Test fun oneDayPolicyNeverKeepsChatBeyondOneDayAndOffClearsWithoutEnablingDrafts() = Fixture().use { f ->
        f.store.chooseRetention(OfflineContentStore.Retention.ONE_DAY)
        f.store.retainPane(f.pane, mirror(), entries()) { true }
        f.clock += 24L * 60 * 60 * 1000
        assertNull(f.store.pane(f.pane))
        f.store.retainPane(f.pane, mirror(), entries()) { true }
        assertTrue(f.store.chooseRetention(OfflineContentStore.Retention.OFF))
        assertNull(f.cold().pane(f.pane)); assertEquals(0L to 0, f.cold().storageUsage()); assertFalse(f.store.draftsEnabled)
    }

    @Test fun exactOriginMountPeerSessionPaneAndIncarnationNeverBorrowAnotherCopy() = Fixture().use { f ->
        f.store.chooseRetention(OfflineContentStore.Retention.ONE_DAY)
        f.store.retainPane(f.pane, mirror(), entries()) { true }
        val others = listOf(f.pane.copy(owner = f.pane.owner.copy(origin = "https://retention.test/other/", mount = "https://retention.test/other/")),
            f.pane.copy(host = "peer-b"), f.pane.copy(session = "other"), f.pane.copy(subject = "other"),
            f.pane.copy(incarnation = "00000000-0000-0000-0000-000000000002"))
        others.forEach { assertNull(f.cold().pane(it)) }
        assertNotNull(f.cold().pane(f.pane))
    }

    @Test fun perPaneAndTotalCiphertextQuotasPruneOldestWithoutDroppingNewCurrentCopy() = Fixture().use { f ->
        f.store.chooseRetention(OfflineContentStore.Retention.SEVEN_DAYS)
        for (index in 0..120) {
            val scope = f.pane.copy(subject = "pane-$index")
            val text = "€\"\n".repeat(80_000)
            assertTrue(f.store.retainPane(scope, mirror().copy(paneId = scope.subject!!, text = text),
                entries().map { it.copy(parts = listOf(TranscriptPart("text", "turn-$index" + text))) }) { true })
            f.clock++
            val (bytes, count) = f.store.storageUsage()
            assertTrue(bytes <= 10L * 1024 * 1024); assertTrue(count <= 512)
        }
        assertNotNull(f.store.pane(f.pane.copy(subject = "pane-120")))
        SQLiteDatabase.openDatabase(File(f.directory, "content.db").path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT MAX(bytes) FROM records WHERE kind='read'", null).use { row -> row.moveToFirst(); assertTrue(row.getLong(0) <= 256 * 1024) }
        }
    }

    @Test fun interruptedDurableWipeIsProcessedBeforeColdReadAndOldCiphertextReplayRefuses() = Fixture().use { f ->
        f.store.chooseRetention(OfflineContentStore.Retention.ONE_DAY)
        f.store.retainPane(f.pane, mirror(), entries()) { true }
        val path = File(f.directory, "content.db")
        val backup = File(f.directory, "held-ciphertext.db")
        assertTrue(path.renameTo(backup)); assertTrue(path.mkdir())
        assertFalse(f.store.retireOwner(f.pane.owner)) // Wipe intent succeeds; SQLite open fails.
        assertTrue(File(f.directory, "owner-${f.pane.owner.storageIdentity}.wipe").isFile)
        assertTrue(path.delete()); assertTrue(backup.renameTo(path))
        val cold = f.cold()
        assertNull(cold.pane(f.pane)); assertEquals(0L to 0, cold.storageUsage())
        assertFalse(cold.retainPane(f.pane, mirror(), entries()) { true })
        assertFalse(File(f.directory, "owner-${f.pane.owner.storageIdentity}.wipe").exists())
    }

    @Test fun originalOwnerRetirementPreservesSuccessorAndPasswordClearsOnlyItsPaneAndDraft() = Fixture().use { f ->
        f.store.chooseRetention(OfflineContentStore.Retention.ONE_DAY); f.store.chooseDrafts(true)
        val b = f.pane.copy(owner = f.pane.owner.copy(pairedDeviceIdentity = "successor"), incarnation = "00000000-0000-0000-0000-000000000002")
        assertTrue(f.store.retainPane(f.pane, mirror(), entries()) { true }); assertTrue(f.store.retainPane(b, mirror(), entries()) { true })
        assertTrue(f.store.saveDraft("a-draft", "private draft", f.pane.owner, f.pane))
        assertTrue(f.store.retirePane(f.pane))
        assertNull(f.store.pane(f.pane)); assertNull(f.store.draft("a-draft", f.pane.owner)); assertNotNull(f.store.pane(b))
        assertTrue(f.store.retireOwner(f.pane.owner)); assertNotNull(f.cold().pane(b))
        assertFalse(f.store.retainPane(f.pane, mirror(), entries()) { true })
    }

    @Test fun draftOptInIsIndependentEncryptedBoundedAndExpiresAt48Hours() = Fixture().use { f ->
        assertFalse(f.store.saveDraft("draft", "private draft", f.pane.owner, f.pane))
        assertTrue(f.store.chooseDrafts(true)); assertEquals(OfflineContentStore.Retention.OFF, f.store.retention)
        assertTrue(f.store.saveDraft("draft", "private draft", f.pane.owner, f.pane))
        assertEquals("private draft", f.cold().draft("draft", f.pane.owner))
        assertFalse(f.store.saveDraft("large", "x".repeat(8193), f.pane.owner, f.pane))
        f.clock += 48L * 60 * 60 * 1000
        assertNull(f.cold().draft("draft", f.pane.owner))
    }

    @Test fun legacyDraftsRequireConsentAndOnlyCommittedEncryptedReplacementRemovesOwnedPlaintext() = Fixture().use { f ->
        val preferences = f.context.getSharedPreferences(NativePreferences.PREFERENCES, Context.MODE_PRIVATE)
        val owned = legacyKey(f.pane); val unknown = "unknown-${System.nanoTime()}"
        preferences.edit().putString("pane_draft:$owned", "legacy owned").putLong("pane_draft_saved:$owned", f.clock)
            .putString("pane_draft_owner:$owned", f.pane.owner.storageIdentity).putString("pane_draft:$unknown", "legacy unknown").commit()
        val native = NativePreferences(f.context, f.store) { f.clock }
        assertEquals("legacy owned", native.paneDraft(owned, f.pane.owner))
        assertFalse(native.migrateLegacyDrafts(f.pane.owner, f.pane) { true })
        assertTrue(preferences.contains("pane_draft:$owned")); assertTrue(preferences.contains("pane_draft:$unknown"))
        f.store.chooseDrafts(true)
        assertTrue(native.migrateLegacyDrafts(f.pane.owner, f.pane) { true })
        assertFalse(preferences.contains("pane_draft:$owned")); assertTrue(preferences.contains("pane_draft:$unknown"))
        assertEquals("legacy owned", f.cold().draft(owned, f.pane.owner))
        native.savePaneDraft("new", "new plaintext forbidden", f.pane.owner)
        assertFalse(preferences.contains("pane_draft:new"))
        assertTrue(native.discardLegacyDrafts()); assertFalse(preferences.contains("pane_draft:$unknown"))
    }

    @Test fun failedEncryptionPreservesLegacyDraftAndMissingKeyReturnsNoSavedContent() = Fixture().use { f ->
        f.store.chooseRetention(OfflineContentStore.Retention.ONE_DAY); f.store.retainPane(f.pane, mirror(), entries()) { true }
        val wrong = OfflineContentStore(RetainedReadDatabase(f.context, FixtureCipher(), { f.clock }, f.directory), { f.clock })
        assertNull(wrong.pane(f.pane)); assertTrue(wrong.available)
        assertEquals(OfflineContentStore.Retention.ONE_DAY, wrong.retention)
        val key = legacyKey(f.pane)
        val prefs = f.context.getSharedPreferences(NativePreferences.PREFERENCES, Context.MODE_PRIVATE)
        prefs.edit().putString("pane_draft:$key", "preserve me").putString("pane_draft_owner:$key", f.pane.owner.storageIdentity).putLong("pane_draft_saved:$key", f.clock).commit()
        val broken = object : SecretCipher { override fun encrypt(plaintext: ByteArray): CipherEnvelope = error("fixture encryption failure"); override fun decrypt(envelope: CipherEnvelope): ByteArray = error("fixture key unavailable") }
        val bad = OfflineContentStore(RetainedReadDatabase(f.context, broken, { f.clock }, File(f.directory, "broken")), { f.clock })
        assertTrue(bad.chooseDrafts(true))
        val native = NativePreferences(f.context, bad) { f.clock }
        assertFalse(native.migrateLegacyDrafts(f.pane.owner, f.pane) { true }); assertTrue(prefs.contains("pane_draft:$key"))
    }

    @Test fun retiredOrWrongPaneResultsNeverSaveAndClockRollbackNeverRevealsFutureCopies() = Fixture().use { f ->
        f.store.chooseRetention(OfflineContentStore.Retention.ONE_DAY)
        assertFalse(f.store.retainPane(f.pane, mirror(), entries()) { false })
        assertFalse(f.store.retainPane(f.pane, mirror().copy(paneId = "wrong"), entries()) { true })
        assertTrue(f.store.retainPane(f.pane, mirror(), entries()) { true })
        f.clock--
        assertNull(f.cold().pane(f.pane))
    }

    @Test fun passwordRetirementBlocksLateChatAndDraftUntilAnAcceptedCleanMirror() = Fixture().use { f ->
        f.store.chooseRetention(OfflineContentStore.Retention.ONE_DAY); f.store.chooseDrafts(true)
        assertTrue(f.store.retainPane(f.pane, mirror(), entries()) { true })
        assertTrue(f.store.saveDraft("draft", "retained", f.pane.owner, f.pane))
        assertFalse(f.store.passwordPane(f.pane) { false })
        assertNotNull(f.store.pane(f.pane))
        assertTrue(f.store.passwordPane(f.pane) { true })
        val cold = f.cold()
        assertNull(cold.pane(f.pane)); assertNull(cold.draft("draft", f.pane.owner))
        assertFalse(cold.retainPane(f.pane, null, entries()) { true })
        assertFalse(cold.saveDraft("draft", "password", f.pane.owner, f.pane))
        assertTrue(cold.retainPane(f.pane, mirror(), entries()) { true })
        assertNotNull(cold.pane(f.pane))
        assertTrue(cold.saveDraft("draft", "ordinary", f.pane.owner, f.pane))
    }

    @Test fun oldCiphertextRowReinsertedAfterDurableClearCannotRestoreContent() = Fixture().use { f ->
        f.store.chooseRetention(OfflineContentStore.Retention.ONE_DAY)
        f.store.retainPane(f.pane, mirror(), entries()) { true }
        val file = File(f.directory, "content.db")
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("CREATE TABLE held AS SELECT * FROM records")
        }
        assertTrue(f.store.clearReads())
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("INSERT INTO records SELECT * FROM held")
            db.execSQL("DROP TABLE held")
        }
        assertNull(f.cold().pane(f.pane))
        assertTrue(f.store.retainPane(f.pane, mirror(), entries()) { true })
        assertNotNull(f.cold().pane(f.pane))
    }

    @Test fun unknownEncryptedSchemaRetiresOnlyTheCacheRecordAndKeepsConsent() = Fixture().use { f ->
        f.store.chooseRetention(OfflineContentStore.Retention.ONE_DAY)
        f.store.retainPane(f.pane, mirror(), entries()) { true }
        SQLiteDatabase.openDatabase(File(f.directory, "content.db").path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.rawQuery("SELECT id,owner,epoch,iv,body FROM records", null).use { row ->
                assertTrue(row.moveToFirst())
                val clear = f.cipher.decrypt(CipherEnvelope(row.getBlob(3), row.getBlob(4))).decodeToString()
                val future = clear.replace("\"version\":2", "\"version\":99")
                assertNotEquals(clear, future)
                val encrypted = f.cipher.encrypt(future.encodeToByteArray())
                db.execSQL("UPDATE records SET iv=?,body=? WHERE id=?", arrayOf<Any>(encrypted.iv, encrypted.ciphertext, row.getString(0)))
            }
        }
        assertNull(f.cold().pane(f.pane))
        assertEquals(OfflineContentStore.Retention.ONE_DAY, f.cold().retention)
        assertTrue(f.cold().available)
    }

    @Test fun staleBlankDraftCallbackCannotClearCurrentVolatileOrLegacyDraft() = Fixture().use { f ->
        val prefs = NativePreferences(f.context, f.store) { f.clock }
        val key = "blank-race-${java.util.UUID.randomUUID()}"
        prefs.savePaneDraft(key, "new owner edit", f.pane.owner)
        prefs.savePaneDraft(key, "", f.pane.owner, current = { false })
        assertEquals("new owner edit", prefs.paneDraft(key, f.pane.owner))
        prefs.savePaneDraft(key, "", f.pane.owner)
        assertEquals("", prefs.paneDraft(key, f.pane.owner))
    }

    @Test fun failedDirectoryEnumerationCannotSkipAnExistingDurableRetirementIntent() = Fixture().use { f ->
        assertTrue(f.store.chooseRetention(OfflineContentStore.Retention.ONE_DAY))
        assertTrue(f.store.retainPane(f.pane, mirror(), entries()) { true })
        val databasePath = File(f.directory, "content.db")
        val held = File(f.directory, "held-ciphertext.db")
        assertTrue(databasePath.renameTo(held)); assertTrue(databasePath.mkdir())
        assertFalse(f.store.retireOwner(f.pane.owner))
        val intent = File(f.directory, "owner-${f.pane.owner.storageIdentity}.wipe")
        assertTrue(intent.isFile)
        assertTrue(databasePath.delete()); assertTrue(held.renameTo(databasePath))
        SQLiteDatabase.openDatabase(databasePath.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM records", null).use { row -> assertTrue(row.moveToFirst()); assertEquals(1, row.getInt(0)) }
        }
        val failedEnumeration = object : File(f.directory.path) { override fun listFiles(): Array<File>? = null }
        val cold = OfflineContentStore(RetainedReadDatabase(f.context, f.cipher, { f.clock }, failedEnumeration), { f.clock })
        assertNull(cold.pane(f.pane)); assertFalse(cold.available); assertTrue(intent.isFile)
        // Direct destructive operations must also refuse before SQL and keep their own marker.
        assertFalse(cold.clearReads()); assertTrue(File(f.directory, "reads.wipe").isFile)
        SQLiteDatabase.openDatabase(databasePath.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT COUNT(*) FROM records", null).use { row -> assertTrue(row.moveToFirst()); assertEquals(1, row.getInt(0)) }
        }
        assertNull(f.cold().pane(f.pane)); assertFalse(intent.exists())
    }

    private fun legacyKey(scope: RetainedReadScope) = listOf(scope.owner.origin, scope.owner.pairedDeviceIdentity,
        scope.host.orEmpty(), scope.session.orEmpty(), scope.subject).joinToString("|")

    private class Fixture : AutoCloseable {
        val context: Context = ApplicationProvider.getApplicationContext()
        val directory = File(context.noBackupFilesDir, "offline-fixture-${java.util.UUID.randomUUID()}")
        var clock = System.currentTimeMillis()
        val cipher = FixtureCipher()
        val store = cold()
        val dashboard = RetainedReadScope(ScopedCustodyOwner("https://retention.test/mount/", "https://retention.test/mount/", "paired-fixture"), "00000000-0000-0000-0000-000000000001", "peer-a", "session-a", null)
        val pane = dashboard.copy(subject = "pane-1")
        fun cold() = OfflineContentStore(RetainedReadDatabase(context, cipher, { clock }, directory), { clock })
        override fun close() { directory.deleteRecursively() }
    }
    companion object {
        internal fun mirror() = PaneReadResponse("pane-1", "masked retained output", false, 4, linkText = "src/secret.txt", filesReadReady = true)
        internal fun entries() = listOf(TranscriptEntry("turn-1", "2026-10-12T00:00:00Z", "assistant", listOf(
            TranscriptPart("text", "masked chat"), TranscriptPart("image", url = "/api/blobs/secret"),
            TranscriptPart("tool", summary = "displayed tool summary", result = TranscriptResult("displayed result", imageUrl = "/api/blobs/secret"), call = buildJsonObject { put("token", "never-store-this-secret") })), seq = 1))
        internal fun snapshot() = SnapshotResponse("connected", ControlAuthority("lead", "epoch"), DeviceAuthorization(true, "private-device", true),
            listOf(PaneSummary("pane-1", "workspace-1", "Workspace", 1, "tab-1", "claude", AgentStatus.IDLE, "/private/repository", true)), emptyList(),
            listOf(WorkspaceSummary("workspace-1", 1, "Workspace", true, "tab-1", 1, 1, repoRoot = "/private/repository")),
            listOf(TabSummary("tab-1", "workspace-1", 1, "Tab", true, 1)), ts = System.currentTimeMillis())
    }
}

/** Real AES/GCM/tag, an ephemeral test key; hardware-backed AndroidKeyStore remains device evidence. */
internal class FixtureCipher : SecretCipher {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    override fun encrypt(plaintext: ByteArray): CipherEnvelope {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD("collie-retained-fixture".encodeToByteArray())
        return CipherEnvelope(cipher.iv, cipher.doFinal(plaintext))
    }
    override fun decrypt(envelope: CipherEnvelope): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, envelope.iv))
        cipher.updateAAD("collie-retained-fixture".encodeToByteArray()); return cipher.doFinal(envelope.ciphertext)
    }
}
