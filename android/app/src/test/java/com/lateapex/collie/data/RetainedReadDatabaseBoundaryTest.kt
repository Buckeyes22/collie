package com.lateapex.collie.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.network.OriginValidator
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Adversarial real SQLite row changes. AES/GCM stays real; no read/authority state is injected. */
@RunWith(RobolectricTestRunner::class)
class RetainedReadDatabaseBoundaryTest {
    @Test fun expiredDashboardAndDraftCiphertextCannotBeFreshenedBySQLTimes() = Fixture().use { f ->
        f.store.chooseRetention(OfflineContentStore.Retention.SEVEN_DAYS); f.store.chooseDrafts(true)
        assertTrue(f.store.retainDashboard(f.dashboard, OfflineContentStoreTest.snapshot()) { true })
        assertTrue(f.store.saveDraft("draft", "private retained draft", f.pane.owner, f.pane))
        val dashboard = f.row("read"); val draft = f.row("draft")
        val committed = f.connection.captureCommitted()
        f.clock += DAY
        assertNull(f.cold().dashboard(f.dashboard))
        f.put(dashboard.also { it.put("captured", f.clock); it.put("expires", f.clock + DAY); it.put("accessed", f.clock) })
        assertNull(f.cold().dashboard(f.dashboard))
        assertTrue(f.store.retainDashboard(f.dashboard, OfflineContentStoreTest.snapshot()) { true })
        assertNotNull(f.cold().dashboard(f.dashboard))
        f.clock += DAY
        assertNull(f.cold().draft("draft", f.pane.owner))
        f.put(draft.also { it.put("captured", f.clock); it.put("expires", f.clock + 2 * DAY); it.put("accessed", f.clock) })
        assertNull(f.cold().draft("draft", f.pane.owner))
        assertTrue(f.store.saveDraft("draft", "new deliberate draft", f.pane.owner, f.pane))
        assertEquals("new deliberate draft", f.cold().draft("draft", f.pane.owner))
        assertEquals(committed, f.connection.captureCommitted())
        assertEquals("kept", f.preferences.getString("cosmetic-marker", null))
    }

    @Test fun eachAuthenticatedRowAttributeAndActualChargedBytesRefuseTampering() {
        val edits: List<Pair<String, (ContentValues, Fixture) -> Unit>> = listOf(
            "captured" to { row, f -> row.put("captured", f.clock + 1) },
            "expires" to { row, f -> row.put("expires", f.clock + 7 * DAY) },
            "target" to { row, _ -> row.put("target", "b".repeat(64)) },
            "kind" to { row, _ -> row.put("kind", "draft") },
            "bytes-plus-one" to { row, _ -> row.put("bytes", row.getAsLong("bytes") + 1) },
            "bytes-negative" to { row, _ -> row.put("bytes", -1L) },
            "oversized-body" to { row, _ -> row.put("body", ByteArray(RetainedReadDatabase.MAX_RECORD_BYTES + 1)); row.put("bytes", RetainedReadDatabase.MAX_RECORD_BYTES + 13) },
            "wrong-iv-size" to { row, _ -> row.put("iv", ByteArray(13)); row.put("bytes", row.getAsByteArray("body").size + 13) },
        )
        edits.forEach { (name, change) -> Fixture().use { f ->
            f.store.chooseRetention(OfflineContentStore.Retention.ONE_DAY); f.store.chooseDrafts(true)
            assertTrue(f.store.retainDashboard(f.dashboard, OfflineContentStoreTest.snapshot()) { true })
            assertNotNull(f.cold().dashboard(f.dashboard))
            val row = f.row("read"); val original = ContentValues(row)
            change(row, f); f.clock += 5; f.put(row)
            assertNull(name, f.cold().dashboard(f.dashboard))
            // Authentication refusal alone cannot turn a still-valid original row into another owner.
            f.put(original)
            assertNotNull(name, f.cold().dashboard(f.dashboard))
            assertNotNull(f.connection.captureCommitted()?.connection?.token)
            assertEquals("kept", f.preferences.getString("cosmetic-marker", null))
        } }
    }

    @Test fun metadata8191AllowsOneKey8192AllowsExistingUpdateAndNever8193() = Fixture().use { f ->
        f.database.policy("reads") // Materializes the ordinary clock key before the boundary.
        f.fill(8191)
        val target = "a".repeat(64)
        assertTrue(f.database.allowTarget(target) { true }); assertEquals(8192, f.count())
        assertTrue(f.database.allowTarget(target) { true }); assertEquals(8192, f.count())
        val held = f.metadata()
        assertFalse(f.database.allowTarget("b".repeat(64)) { true })
        assertEquals(8192, f.count()); assertEquals(held, f.metadata())
        val cold = f.coldDatabase()
        assertTrue(cold.allowTarget(target) { true }); assertEquals(8192, f.count())
    }

    @Test fun directRetirementAtCapacityKeepsDurableIntentAndDeniesColdAccessWithoutEviction() = Fixture().use { f ->
        f.store.chooseRetention(OfflineContentStore.Retention.ONE_DAY)
        assertTrue(f.store.retainPane(f.pane, OfflineContentStoreTest.mirror(), OfflineContentStoreTest.entries()) { true })
        f.fill(8191)
        assertTrue(f.database.retireOwner("a".repeat(64))); assertEquals(8192, f.count())
        assertTrue(f.database.retireOwner("a".repeat(64))); assertEquals(8192, f.count())
        val held = f.metadata()
        assertFalse(f.database.retireOwner(f.pane.owner.storageIdentity))
        val marker = File(f.directory, "owner-${f.pane.owner.storageIdentity}.wipe")
        assertTrue(marker.isFile); assertEquals(held, f.metadata())
        repeat(2) { assertNull(f.cold().pane(f.pane)); assertTrue(marker.isFile); assertEquals(8192, f.count()) }
        assertEquals(1, f.rows()) // Failed transaction retains ciphertext but every access is denied.
        assertFalse(f.cold().retainPane(f.pane, OfflineContentStoreTest.mirror(), OfflineContentStoreTest.entries()) { true })
        assertTrue(marker.isFile); assertEquals(held, f.metadata())
    }

    @Test fun passwordRetirementReservesBothNewMetadataKeysOrRollsBackAndKeepsDenial() = Fixture().use { f ->
        f.database.policy("reads"); f.fill(8191)
        val held = f.metadata(); val target = "c".repeat(64)
        assertFalse(f.database.passwordTarget(target) { true }) // Retirement, blocked and legacy-retired require the full new-key reservation.
        assertEquals(8191, f.count()); assertEquals(held, f.metadata())
        val marker = File(f.directory, "target-$target.wipe")
        assertTrue(marker.isFile)
        val cold = f.coldDatabase()
        assertEquals(0, cold.policy("reads"))
        assertFalse(cold.isAvailable()) // Each new wrapper encounters the unresolved marker.
        assertEquals(held, f.metadata()); assertTrue(marker.isFile)
    }

    private class Fixture : AutoCloseable {
        val context: Context = ApplicationProvider.getApplicationContext()
        val directory = File(context.noBackupFilesDir, "retained-boundary-${java.util.UUID.randomUUID()}")
        var clock = System.currentTimeMillis()
        private val cipher = FixtureCipher()
        val database = coldDatabase()
        val store = OfflineContentStore(database, { clock })
        val preferences = context.getSharedPreferences("retained-boundary-prefs-${java.util.UUID.randomUUID()}", Context.MODE_PRIVATE)
        val connection = EncryptedConnectionStore(context, cipher, Json { ignoreUnknownKeys = true }, OriginValidator(), preferences)
        val dashboard = RetainedReadScope(ScopedCustodyOwner("https://boundary.test/mount/", "https://boundary.test/mount/", "paired-owner"), "00000000-0000-0000-0000-000000000001", null, null, null)
        val pane = dashboard.copy(subject = "pane-1")
        init { connection.save(Connection(OriginValidator().requireValid("https://boundary.test/mount/"), "phone", "fixture-bearer")); preferences.edit().putString("cosmetic-marker", "kept").commit() }
        fun coldDatabase() = RetainedReadDatabase(context, cipher, { clock }, directory)
        fun cold() = OfflineContentStore(coldDatabase(), { clock })
        fun <T> sql(block: (SQLiteDatabase) -> T): T = SQLiteDatabase.openDatabase(File(directory, "content.db").path, null, SQLiteDatabase.OPEN_READWRITE).use(block)
        fun count(): Int = sql { db -> db.rawQuery("SELECT COUNT(*) FROM metadata", null).use { row -> row.moveToFirst(); row.getInt(0) } }
        fun rows(): Int = sql { db -> db.rawQuery("SELECT COUNT(*) FROM records", null).use { row -> row.moveToFirst(); row.getInt(0) } }
        fun metadata(): Map<String, Long> = sql { db -> db.rawQuery("SELECT id,value FROM metadata", null).use { row -> buildMap { while (row.moveToNext()) put(row.getString(0), row.getLong(1)) } } }
        fun fill(limit: Int) = sql { db ->
            db.beginTransaction()
            try {
                var size = db.rawQuery("SELECT COUNT(*) FROM metadata", null).use { row -> row.moveToFirst(); row.getInt(0) }; var index = 0
                while (size < limit) { db.execSQL("INSERT INTO metadata(id,value) VALUES(?,0)", arrayOf<Any>("fixture-$index")); size++; index++ }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        fun row(kind: String): ContentValues = sql { db -> db.rawQuery("SELECT * FROM records WHERE kind=?", arrayOf(kind)).use { row ->
            check(row.moveToFirst()); ContentValues().apply { for (index in 0 until row.columnCount) when (row.getType(index)) {
                Cursor.FIELD_TYPE_STRING -> put(row.getColumnName(index), row.getString(index))
                Cursor.FIELD_TYPE_INTEGER -> put(row.getColumnName(index), row.getLong(index))
                Cursor.FIELD_TYPE_BLOB -> put(row.getColumnName(index), row.getBlob(index))
                else -> error("Unexpected fixture SQL type")
            } }
        } }
        fun put(row: ContentValues) = sql { db -> check(db.insertWithOnConflict("records", null, row, SQLiteDatabase.CONFLICT_REPLACE) != -1L) }
        override fun close() { preferences.edit().clear().commit(); directory.deleteRecursively() }
    }
    companion object { private const val DAY = 24L * 60 * 60 * 1000 }
}
