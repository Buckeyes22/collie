package com.lateapex.collie.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.Cursor
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Private ciphertext only. A durable wipe intent precedes deletion; every access processes it.
 * No callback/Looper ACK runs while this database's file lock is held. */
class RetainedReadDatabase(
    context: Context,
    private val cipher: SecretCipher = AndroidKeystoreCipher("com.lateapex.collie.retained.v1"),
    private val now: () -> Long = System::currentTimeMillis,
    private val directory: File = File(context.noBackupFilesDir, "retained-v1"),
) {
    private val json = Json { encodeDefaults = true }
    private val lock = locks.computeIfAbsent(directory.absolutePath) { Any() }
    @Volatile private var unavailable = false

    internal data class Record(val payload: String, val capturedAt: Long, val expiresAt: Long)
    @Serializable private data class BoundPayload(
        val version: Int = 2, val key: String, val owner: String, val target: String, val kind: String,
        val capturedAt: Long, val expiresAt: Long, val maximumAge: Long,
        val payloadBytes: Int, val epoch: String, val payload: String,
    )

    internal fun policy(name: String): Int = access(0) { db -> value(db, "policy:$name").toInt() }

    internal fun choose(name: String, value: Int, deleteKind: String? = null): Boolean {
        require(name in setOf("reads", "drafts") && value in 0..2)
        return wipe("policy-$name", if (deleteKind == null) null else "kind:$deleteKind") { db ->
            set(db, "policy:$name", value.toLong())
        }
    }

    internal fun clearReads(): Boolean = wipe("reads", "kind:read") { }
    internal fun clearDrafts(): Boolean = wipe("drafts", "kind:draft") { }
    internal fun retireOwner(owner: String): Boolean = wipe("owner-$owner", "owner:$owner") { }
    internal fun retireRecord(key: String): Boolean = wipe("record-$key", "record:$key") { }
    internal fun retireTarget(target: String): Boolean = wipe("target-$target", "target:$target") { }
    internal fun passwordTarget(target: String, current: () -> Boolean): Boolean =
        wipe("target-$target", "target:$target", current) { db -> set(db, "blocked:$target", 1); set(db, "legacy-retired:$target", 1) }
    internal fun targetAllowed(target: String): Boolean = access(false) { value(it, "blocked:$target") == 0L }
    internal fun legacyAllowed(target: String): Boolean = access(false) { value(it, "legacy-retired:$target") == 0L && value(it, "blocked:$target") == 0L }
    internal fun allowTarget(target: String, current: () -> Boolean): Boolean = access(false) { db ->
        if (!current()) false else { set(db, "blocked:$target", 0); true }
    }

    internal fun epoch(key: String, owner: String, target: String, kind: String): String? = access(null) { epoch(it, key, owner, target, kind) }
    fun isAvailable(): Boolean = !unavailable

    internal fun read(key: String, owner: String, expectedTarget: String? = null): Record? = access(null) { db ->
        // Query lengths before selecting either BLOB: a forged SQL length cannot force a large
        // CursorWindow/blob allocation before the authenticated envelope and budget checks.
        db.rawQuery("SELECT captured,expires,bytes,length(iv),length(body),length(owner),length(epoch),length(target),length(kind) FROM records WHERE id=?", arrayOf(key)).use { row ->
            if (!row.moveToFirst() || (0..8).any { row.getType(it) != Cursor.FIELD_TYPE_INTEGER }) return@access null
            if (listOf(5, 6, 7).any { row.getLong(it) != 64L } || row.getLong(8) !in 4L..5L) return@access null
            val fields = db.rawQuery("SELECT owner,epoch,target,kind FROM records WHERE id=?", arrayOf(key)).use { text ->
                require(text.moveToFirst()); List(4) { index -> require(text.getType(index) == Cursor.FIELD_TYPE_STRING); text.getString(index) }
            }
            if (fields[0] != owner) return@access null
            val target = fields[2]; val kind = fields[3]
            if (expectedTarget != null && target != expectedTarget) return@access null
            if (kind !in setOf("read", "draft") || !target.matches(Regex("[0-9a-f]{64}")) || policyValue(db, kind) == 0L) return@access null
            val ivSize = row.getLong(3); val bodySize = row.getLong(4)
            val limit = if (kind == "read") MAX_RECORD_BYTES else MAX_DRAFT_BYTES
            if (ivSize != 12L || bodySize !in 16L..(limit - 12).toLong() || row.getLong(2) != ivSize + bodySize) return@access null
            val currentEpoch = epoch(db, key, owner, target, kind)
            if (fields[1] != currentEpoch) return@access null
            val clock = now()
            val captured = row.getLong(0); val expires = row.getLong(1)
            if (clock < captured || clock >= expires) {
                db.delete("records", "id=?", arrayOf(key)); return@access null
            }
            val (iv, bytes) = db.rawQuery("SELECT iv,body FROM records WHERE id=?", arrayOf(key)).use { blob ->
                require(blob.moveToFirst()); blob.getBlob(0) to blob.getBlob(1)
            }
            if (iv.size.toLong() != ivSize || bytes.size.toLong() != bodySize) return@access null
            val bound = try {
                val clear = cipher.decrypt(CipherEnvelope(iv, bytes))
                require(clear.size + 16 == bytes.size && clear.size <= limit - 28)
                json.decodeFromString(BoundPayload.serializer(), clear.decodeToString(throwOnInvalidSequence = true))
            } catch (_: Exception) {
                // Corrupt/unknown cache ciphertext never alters connection or cosmetic preferences.
                applyWipe(db, "record:$key"); return@access null
            }
            if (bound.version != 2) {
                applyWipe(db, "record:$key"); return@access null
            }
            val legalAge = if (kind == "draft") bound.maximumAge == 2 * DAY else
                bound.maximumAge in setOf(DAY, 7 * DAY) && bound.maximumAge <= (if (policyValue(db, kind) == 1L) DAY else 7 * DAY)
            if (bound.key != key || bound.owner != owner || bound.target != target || bound.kind != kind || bound.epoch != currentEpoch ||
                bound.capturedAt != captured || bound.expiresAt != expires || !legalAge || captured <= 0 || expires <= captured ||
                expires - captured > bound.maximumAge || bound.payloadBytes != bound.payload.encodeToByteArray().size) return@access null
            db.execSQL("UPDATE records SET accessed=? WHERE id=?", arrayOf<Any>(clock, key))
            Record(bound.payload, captured, expires)
        }
    }

    internal fun write(
        key: String, owner: String, target: String, kind: String, payload: String,
        expiresAt: Long, maximumAge: Long, expectedEpoch: String, current: () -> Boolean,
    ): Boolean = access(false) { db ->
        require(kind == "read" || kind == "draft")
        require(listOf(key, owner, target).all { it.matches(Regex("[0-9a-f]{64}")) })
        if (value(db, "retirement:owner:$owner") != 0L || value(db, "blocked:$target") != 0L || policyValue(db, kind) == 0L || epoch(db, key, owner, target, kind) != expectedEpoch || !current()) return@access false
        val clock = now()
        val legalAge = if (kind == "draft") maximumAge == 2 * DAY else maximumAge in setOf(DAY, 7 * DAY) && maximumAge <= (if (policyValue(db, kind) == 1L) DAY else 7 * DAY)
        if (!legalAge || clock <= 0 || expiresAt <= clock || expiresAt - clock > maximumAge) return@access false
        val clear = json.encodeToString(BoundPayload.serializer(), BoundPayload(key = key, owner = owner, target = target, kind = kind,
            capturedAt = clock, expiresAt = expiresAt, maximumAge = maximumAge, payloadBytes = payload.encodeToByteArray().size,
            epoch = expectedEpoch, payload = payload)).encodeToByteArray()
        val limit = if (kind == "read") MAX_RECORD_BYTES else MAX_DRAFT_BYTES
        if (clear.size > limit - 28) return@access false
        val envelope = cipher.encrypt(clear)
        require(envelope.iv.size == 12 && envelope.ciphertext.size == clear.size + 16)
        if (envelope.iv.size + envelope.ciphertext.size > limit) return@access false
        // Encryption may block: the original actor and persistent epoch are checked LAST.
        if (!current() || now() !in clock until expiresAt || value(db, "blocked:$target") != 0L || epoch(db, key, owner, target, kind) != expectedEpoch || policyValue(db, kind) == 0L) return@access false
        db.execSQL("INSERT OR REPLACE INTO records(id,owner,target,kind,epoch,captured,expires,iv,body,bytes,accessed) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
            arrayOf<Any>(key, owner, target, kind, expectedEpoch, clock, expiresAt, envelope.iv, envelope.ciphertext, envelope.iv.size + envelope.ciphertext.size, clock))
        prune(db, clock)
        true
    }

    /** Wipe intent is fsynced by AtomicFile before any transaction. A crash leaves a deny marker.
     * Failed/unreadable persistence is not reported as a successful choice or absence. */
    private fun wipe(id: String, selector: String?, current: () -> Boolean = { true }, edit: (SQLiteDatabase) -> Unit): Boolean = synchronized(lock) {
        if (unavailable && selector == null) return@synchronized false
        if (!current()) return@synchronized false
        try {
            directory.mkdirs(); require(directory.isDirectory)
            val marker = AtomicFile(File(directory, "$id.wipe"))
            val stream = marker.startWrite()
            try { stream.write((selector ?: "none").encodeToByteArray()); stream.fd.sync(); marker.finishWrite(stream)
                require(marker.baseFile.readText() == (selector ?: "none")) }
            catch (error: Exception) { marker.failWrite(stream); throw error }
            transaction { db -> applyWipe(db, selector); edit(db) }
            marker.delete()
            require(!marker.baseFile.exists())
            unavailable = false // An explicit successful durable wipe establishes safe selected custody.
            true
        } catch (_: Exception) { unavailable = true; false }
    }

    private fun <T> access(fallback: T, block: (SQLiteDatabase) -> T): T = synchronized(lock) {
        if (unavailable) return@synchronized fallback
        try {
            directory.mkdirs(); require(directory.isDirectory)
            val markers = requireNotNull(directory.listFiles()) { "Cannot enumerate retirement intents" }.mapNotNull {
                when { it.name.endsWith(".wipe") -> it; it.name.endsWith(".wipe.bak") -> File(it.path.removeSuffix(".bak")); else -> null }
            }.distinctBy { it.path }
            require(markers.size <= 1024)
            transaction { db ->
                markers.forEach { marker ->
                    val selector = AtomicFile(marker).openRead().use { input ->
                        val bytes = input.readNBytes(161); require(bytes.size <= 160)
                        bytes.decodeToString(throwOnInvalidSequence = true)
                    }
                    applyWipe(db, selector.takeUnless { it == "none" })
                    if (selector.startsWith("target:")) {
                        val target = selector.removePrefix("target:")
                        set(db, "blocked:$target", 1); set(db, "legacy-retired:$target", 1)
                    }
                    // An interrupted policy change closes that policy conservatively.
                    if (marker.name == "policy-reads.wipe") set(db, "policy:reads", 0)
                    if (marker.name == "policy-drafts.wipe") set(db, "policy:drafts", 0)
                }
                db.rawQuery("SELECT COUNT(*) FROM metadata", emptyArray()).use { require(it.moveToFirst() && it.getInt(0) <= 8192) }
                val clock = now(); require(clock > 0)
                if (clock < value(db, "clock")) applyWipe(db, "all")
                set(db, "clock", clock); prune(db, clock)
                block(db)
            }.also { markers.forEach { marker -> AtomicFile(marker).delete(); require(!marker.exists()) } }
        } catch (_: Exception) { unavailable = true; fallback }
    }

    private fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        requireNotNull(directory.listFiles()) { "Cannot enumerate retirement intents" }
        return SQLiteDatabase.openOrCreateDatabase(File(directory, "content.db"), null).use { db ->
            db.execSQL("CREATE TABLE IF NOT EXISTS metadata(id TEXT PRIMARY KEY,value INTEGER NOT NULL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS records(id TEXT PRIMARY KEY,owner TEXT NOT NULL,target TEXT NOT NULL,kind TEXT NOT NULL,epoch TEXT NOT NULL,captured INTEGER NOT NULL,expires INTEGER NOT NULL,iv BLOB NOT NULL,body BLOB NOT NULL,bytes INTEGER NOT NULL,accessed INTEGER NOT NULL DEFAULT 0)")
            val hasAccessed = db.rawQuery("PRAGMA table_info(records)", emptyArray()).use { columns ->
                var found = false; while (columns.moveToNext()) if (columns.getString(1) == "accessed") found = true; found
            }
            if (!hasAccessed) db.execSQL("ALTER TABLE records ADD COLUMN accessed INTEGER NOT NULL DEFAULT 0")
            db.execSQL("PRAGMA synchronous=FULL")
            db.beginTransaction()
            try {
                require(metadataCount(db) <= MAX_METADATA_KEYS)
                block(db).also { db.setTransactionSuccessful() }
            } finally { db.endTransaction() }
        }
    }

    private fun applyWipe(db: SQLiteDatabase, selector: String?) {
        if (selector == null) return
        val where: String; val args: Array<String>
        when {
            selector == "all" -> { where = "1=1"; args = emptyArray() }
            selector.startsWith("record:") -> { where = "id=?"; args = arrayOf(selector.removePrefix("record:")) }
            selector.startsWith("kind:") -> { where = "kind=?"; args = arrayOf(selector.removePrefix("kind:")) }
            selector.startsWith("owner:") -> { where = "owner=?"; args = arrayOf(selector.removePrefix("owner:")) }
            selector.startsWith("target:") -> { where = "target=?"; args = arrayOf(selector.removePrefix("target:")) }
            else -> error("Invalid retirement marker")
        }
        // Advance even when no row exists: an earlier queued save must not resurrect it.
        val markerKey = "retirement:${selector}"
        set(db, markerKey, Math.addExact(value(db, markerKey), 1))
        db.delete("records", where, args)
    }

    private fun prune(db: SQLiteDatabase, clock: Long) {
        db.delete("records", "expires<=? OR captured>?", arrayOf(clock.toString(), clock.toString()))
        for ((kind, budget, count) in listOf(Triple("read", MAX_READ_BYTES, 512), Triple("draft", MAX_DRAFT_TOTAL_BYTES, 64))) {
            while (true) {
                val over = db.rawQuery("SELECT COALESCE(SUM(bytes),0),COUNT(*) FROM records WHERE kind=?", arrayOf(kind)).use {
                    it.moveToFirst(); it.getLong(0) > budget || it.getInt(1) > count
                }
                if (!over) break
                val oldest = db.rawQuery("SELECT id FROM records WHERE kind=? ORDER BY accessed,captured,id LIMIT 1", arrayOf(kind)).use { it.moveToFirst(); it.getString(0) }
                db.delete("records", "id=?", arrayOf(oldest))
            }
        }
    }

    private fun epoch(db: SQLiteDatabase, key: String, owner: String, target: String, kind: String): String =
        digest(listOf(key, value(db, "retirement:all"), value(db, "retirement:record:$key"), value(db, "retirement:kind:$kind"),
            value(db, "retirement:owner:$owner"), value(db, "retirement:target:$target")).joinToString("|"))

    internal fun usage(): Pair<Long, Int> = access(0L to 0) { db ->
        db.rawQuery("SELECT COALESCE(SUM(bytes),0),COUNT(*) FROM records WHERE kind='read'", emptyArray()).use { it.moveToFirst(); it.getLong(0) to it.getInt(1) }
    }

    private fun policyValue(db: SQLiteDatabase, kind: String): Long = value(db, "policy:${if (kind == "read") "reads" else "drafts"}")
    private fun value(db: SQLiteDatabase, key: String): Long = db.rawQuery("SELECT value FROM metadata WHERE id=?", arrayOf(key)).use { if (it.moveToFirst()) it.getLong(0) else 0 }
    private fun metadataCount(db: SQLiteDatabase): Int = db.rawQuery("SELECT COUNT(*) FROM metadata", emptyArray()).use { it.moveToFirst(); it.getInt(0) }
    private fun set(db: SQLiteDatabase, key: String, value: Long) {
        require(key.length <= 160)
        val exists = db.rawQuery("SELECT 1 FROM metadata WHERE id=?", arrayOf(key)).use { it.moveToFirst() }
        if (!exists) require(metadataCount(db) < MAX_METADATA_KEYS) { "Retirement metadata capacity exhausted" }
        db.execSQL("INSERT OR REPLACE INTO metadata(id,value) VALUES(?,?)", arrayOf<Any>(key, value))
    }

    companion object {
        internal const val MAX_READ_BYTES = 10L * 1024 * 1024
        internal const val MAX_RECORD_BYTES = 256 * 1024
        internal const val MAX_DRAFT_BYTES = 64 * 1024
        internal const val MAX_DRAFT_TOTAL_BYTES = 1024L * 1024
        internal const val MAX_METADATA_KEYS = 8192
        private const val DAY = 24L * 60 * 60 * 1000
        private val locks = java.util.concurrent.ConcurrentHashMap<String, Any>()
        internal fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
