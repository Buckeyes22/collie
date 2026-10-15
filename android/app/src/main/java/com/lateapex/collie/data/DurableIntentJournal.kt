package com.lateapex.collie.data

import android.content.Context
import android.util.AtomicFile
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.network.DurableAnswerRequest
import com.lateapex.collie.network.DurableControlRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@Serializable
data class DurableIntentOwner(val origin: String, val credentialHash: String, val host: String?, val session: String?, val paneId: String) {
    override fun toString() = "DurableIntentOwner(<private>)"
    internal fun valid(): Boolean = runCatching {
        val uri = java.net.URI(origin)
        uri.scheme == "https" && uri.host != null && uri.userInfo == null && uri.query == null && uri.fragment == null &&
            origin.length <= 2048 && origin.endsWith('/') && credentialHash.matches(Regex("[0-9a-f]{64}")) &&
            listOfNotNull(host, session, paneId).all { it.isNotBlank() && it.length <= 256 && it.none(Char::isISOControl) }
    }.getOrDefault(false)
    companion object {
        fun from(connection: Connection, address: PaneAddress): DurableIntentOwner {
            require(connection.isPaired)
            return DurableIntentOwner(connection.origin.value,
                digest((connection.origin.value + "\u0000" + connection.token.orEmpty()).encodeToByteArray()),
                address.scope.host, address.scope.session, address.paneId).also { require(it.valid()) }
        }
        internal fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

@Serializable
data class DurableIntentRecord(val owner: DurableIntentOwner, val action: String, val requestId: String, val requestJson: String) {
    override fun toString() = "DurableIntentRecord(action=$action, body=<private>)"
}

/** One unresolved device intent per exact scope. It never sends requests or grants authority. */
class DurableIntentJournal(context: Context, private val cipher: SecretCipher = AndroidKeystoreCipher("com.lateapex.collie.durable-intents.v1")) {
    sealed interface Lookup {
        data object Empty : Lookup
        data class Pending(val record: DurableIntentRecord) : Lookup
        data object Unreadable : Lookup
    }
    private val directory = File(context.noBackupFilesDir, "durable-intents-v1")
    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private fun file(owner: DurableIntentOwner): AtomicFile {
        require(owner.valid())
        val name = DurableIntentOwner.digest(json.encodeToString(DurableIntentOwner.serializer(), owner).encodeToByteArray())
        return AtomicFile(File(directory, name))
    }
    fun read(owner: DurableIntentOwner): Lookup = synchronized(publication) {
        if (!owner.valid()) return Lookup.Unreadable
        val file = file(owner)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return Lookup.Empty
        return runCatching {
            val bytes = file.openRead().use { it.readNBytes(MAX_FILE_BYTES + 1) }
            require(bytes.size in 29..MAX_FILE_BYTES)
            val plaintext = cipher.decrypt(CipherEnvelope(bytes.copyOfRange(0, 12), bytes.copyOfRange(12, bytes.size)))
            require(plaintext.size <= MAX_FILE_BYTES)
            val record = json.decodeFromString<DurableIntentRecord>(plaintext.decodeToString(throwOnInvalidSequence = true))
            require(record.owner == owner && valid(record))
            Lookup.Pending(record)
        }.getOrDefault(Lookup.Unreadable)
    }
    /** Compare the exact immutable intent; unreadable custody never becomes an empty slot. */
    fun reserve(owner: DurableIntentOwner, action: String, requestId: String, requestJson: String): Boolean = synchronized(publication) {
        val record = DurableIntentRecord(owner, action, requestId, requestJson)
        if (!valid(record)) return false
        when (val existing = read(owner)) {
            Lookup.Unreadable -> return false
            is Lookup.Pending -> return existing.record == record
            Lookup.Empty -> Unit
        }
        val plaintext = json.encodeToString(DurableIntentRecord.serializer(), record).encodeToByteArray()
        if (plaintext.size > MAX_FILE_BYTES - 64) return false
        return runCatching {
            require(directory.isDirectory || directory.mkdirs())
            require((directory.listFiles()?.count { it.isFile && !it.name.endsWith(".bak") && !it.name.endsWith(".new") } ?: 0) < MAX_OWNERS)
            val encrypted = cipher.encrypt(plaintext)
            require(encrypted.iv.size == 12)
            val file = file(owner); val stream = file.startWrite()
            try { stream.write(encrypted.iv); stream.write(encrypted.ciphertext); file.finishWrite(stream) }
            catch (error: Exception) { file.failWrite(stream); throw error }
            true
        }.getOrDefault(false)
    }
    /** Only the original owner/request receipt can resolve this slot. */
    fun clear(owner: DurableIntentOwner, requestId: String): Boolean = synchronized(publication) {
        val existing = read(owner)
        if (existing !is Lookup.Pending || existing.record.requestId != requestId) return false
        file(owner).delete()
        return read(owner) == Lookup.Empty
    }
    /** Recovery is a caller-confirmed check-Chat action; only unreadable exact-owner custody drops. */
    fun discardUnreadable(owner: DurableIntentOwner): Boolean = synchronized(publication) {
        if (!owner.valid() || read(owner) != Lookup.Unreadable) return false
        file(owner).delete()
        return read(owner) == Lookup.Empty
    }
    /** Canonical retirement drops only this original credential/scope's encrypted custody. */
    fun retire(owner: DurableIntentOwner): Boolean = synchronized(publication) {
        if (!owner.valid()) return false
        file(owner).delete()
        return read(owner) == Lookup.Empty
    }
    private fun valid(record: DurableIntentRecord): Boolean = runCatching {
        if (!record.owner.valid() || UUID.fromString(record.requestId).toString() != record.requestId.lowercase(java.util.Locale.ROOT) ||
            record.requestJson.toByteArray(Charsets.UTF_8).size !in 1..MAX_REQUEST_BYTES) return false
        val body = Json.parseToJsonElement(record.requestJson) as? JsonObject ?: return false
        if ((body["requestId"] as? JsonPrimitive)?.content != record.requestId) return false
        when (record.action) {
            "send", "abort" -> {
                require(setOf("requestId", "turnId", "askId", "text", "steer").containsAll(body.keys))
                val request = json.decodeFromString<DurableControlRequest>(record.requestJson)
                request.valid(record.action == "send")
            }
            "answer" -> {
                require(body.keys == setOf("requestId", "askId", "revision", "optionId"))
                json.decodeFromString<DurableAnswerRequest>(record.requestJson).valid()
            }
            else -> false
        }
    }.getOrDefault(false)
    companion object { private val publication = Any(); private const val MAX_OWNERS = 256; private const val MAX_FILE_BYTES = 128 * 1024; private const val MAX_REQUEST_BYTES = 96 * 1024 }
}
