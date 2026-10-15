package com.lateapex.collie.notifications

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import com.lateapex.collie.data.AndroidKeystoreCipher
import com.lateapex.collie.data.CipherEnvelope
import com.lateapex.collie.data.SecretCipher
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
internal data class PushEnrollment(
    val instance: String, val origin: String, val incarnation: String, val credentialHash: String,
    val endpoint: String? = null, val p256dh: String? = null, val auth: String? = null,
    val registrationId: String? = null, val signingPublicKey: String? = null,
    val bindingId: String? = null, val bindingRevision: Long? = null,
    val sequences: Map<String, Long> = emptyMap(), val retired: Boolean = false,
) { override fun toString() = "PushEnrollment(enrolled=${registrationId != null}, retired=$retired)" }

/** Push endpoint/auth-secret, owner witness and replay watermarks share one encrypted commit. */
internal class NativePushStore(context: Context, private val cipher: SecretCipher = AndroidKeystoreCipher("com.lateapex.collie.push.v1")) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "native-push-v1"))
    private val retirements = AtomicFile(File(context.noBackupFilesDir, "native-push-retirements-v1"))
    private val json = Json { encodeDefaults = true; explicitNulls = false }
    @Synchronized fun read(): PushEnrollment? {
        if (!file.baseFile.exists()) return null
        return runCatching {
            val bytes = file.openRead().use { it.readNBytes(128 * 1024 + 1) }
            require(bytes.size in 14..128 * 1024)
            val plaintext = cipher.decrypt(CipherEnvelope(bytes.copyOfRange(0, 12), bytes.copyOfRange(12, bytes.size)))
            json.decodeFromString<PushEnrollment>(plaintext.decodeToString())
        }.getOrNull()
    }
    @Synchronized fun write(state: PushEnrollment) {
        require(state.sequences.size <= 128)
        val envelope = cipher.encrypt(json.encodeToString(PushEnrollment.serializer(), state).encodeToByteArray())
        val stream = file.startWrite()
        try { stream.write(envelope.iv); stream.write(envelope.ciphertext); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
    @Synchronized fun commitRetirement(event: com.lateapex.collie.auth.ProxyBindingRetirement) {
        val old = if (retirements.baseFile.exists()) {
            val bytes = retirements.openRead().use { it.readNBytes(64 * 1024 + 1) }
            require(bytes.size in 14..64 * 1024)
            json.decodeFromString<List<com.lateapex.collie.auth.ProxyBindingRetirement>>(
                cipher.decrypt(CipherEnvelope(bytes.copyOfRange(0, 12), bytes.copyOfRange(12, bytes.size))).decodeToString())
        } else emptyList()
        val rows = (old.filterNot { it == event } + event).takeLast(128)
        val sealed = cipher.encrypt(json.encodeToString(kotlinx.serialization.builtins.ListSerializer(com.lateapex.collie.auth.ProxyBindingRetirement.serializer()), rows).encodeToByteArray())
        val stream = retirements.startWrite()
        try { stream.write(sealed.iv); stream.write(sealed.ciphertext); retirements.finishWrite(stream) }
        catch (e: Exception) { retirements.failWrite(stream); throw e }
    }
    fun newInstance(): String = UUID.randomUUID().toString()
    companion object {
        fun decode64(value: String): ByteArray = Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}
