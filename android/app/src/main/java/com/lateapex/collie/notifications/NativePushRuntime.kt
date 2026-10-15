package com.lateapex.collie.notifications

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.lateapex.collie.AppContainer
import com.lateapex.collie.R
import com.lateapex.collie.auth.ProxyBindingRetirement
import com.lateapex.collie.data.AuthorityInvalidation
import com.lateapex.collie.data.AuthorityRetirement
import com.lateapex.collie.data.ScopedReadContext
import com.lateapex.collie.domain.Connection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.PushEndpoint
import java.security.MessageDigest

class NativePushRuntime(private val context: Context, private val container: AppContainer) {
    private val tasks = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = NativePushStore(context)
    private val lock = Any()
    // UI invalidation only; this counter conveys no endpoint, pairing, payload, or authority.
    private val mutableStatusChanges = MutableStateFlow(0L)
    val statusChanges: StateFlow<Long> = mutableStatusChanges.asStateFlow()
    private fun statusChanged() { mutableStatusChanges.value += 1 }
    init {
        container.proxyCoordinator.bindingLifecycle.observe { event ->
            retire(event)
            store.commitRetirement(event)
            container.proxyCoordinator.bindingLifecycle.acknowledge(event)
        }
    }
    private val manager get() = context.getSystemService(NotificationManager::class.java)
    private fun current(state: PushEnrollment): Boolean {
        val committed = container.connectionStore.captureCommitted() ?: return false
        val connection = committed.connection ?: return false
        return !state.retired && connection.isPaired && state.origin == connection.origin.value &&
            state.incarnation == committed.incarnationId && state.credentialHash == credentialHash(connection)
    }
    private fun credentialHash(connection: Connection) = MessageDigest.getInstance("SHA-256")
        .digest((connection.origin.value + "\u0000" + connection.token.orEmpty()).encodeToByteArray()).joinToString("") { "%02x".format(it) }
    fun resume() = synchronized(lock) {
        val state = store.read() ?: return@synchronized
        if (!current(state)) { if (!state.retired) retire(state); return@synchronized }
        if (UnifiedPush.getAckDistributor(context) != null) UnifiedPush.register(context, instance = state.instance, messageForDistributor = "Collie")
    }
    suspend fun enable(activity: Activity): Boolean {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.native_notifications_channel), NotificationManager.IMPORTANCE_DEFAULT))
        if (synchronized(lock) { store.read()?.let(::current) == true } && UnifiedPush.getAckDistributor(context) != null) { resume(); return true }
        val committed = container.connectionStore.captureCommitted() ?: return false
        val connection = committed.connection?.takeIf { it.isPaired } ?: return false
        val incarnation = committed.incarnationId ?: return false
        val actor = container.repository.readContext() ?: return false
        val configuration = request(actor, "GET", null) ?: return false
        if ((configuration["enabled"] as? JsonPrimitive)?.booleanOrNull != true) return false
        val key = configuration.string("signingPublicKey") ?: return false
        synchronized(lock) {
            store.read()?.let(::retire)
            if (!container.repository.isCurrent(actor)) return false
            store.write(PushEnrollment(store.newInstance(), connection.origin.value, incarnation, credentialHash(connection), signingPublicKey = key))
            statusChanged()
        }
        withContext(Dispatchers.Main) {
            UnifiedPush.tryUseCurrentOrDefaultDistributor(activity) { success ->
                if (success) synchronized(lock) { store.read()?.takeIf(::current)?.let { UnifiedPush.register(context, instance = it.instance, messageForDistributor = "Collie") } }
            }
        }
        return true
    }
    fun enqueueEndpoint(endpoint: PushEndpoint, instance: String) {
        // The process runtime owns enrollment work after the connector unbinds its private service.
        tasks.launch { endpoint(endpoint, instance) }
    }
    private suspend fun endpoint(endpoint: PushEndpoint, instance: String) {
        if (endpoint.temporary || endpoint.pubKeySet == null) return
        val state = synchronized(lock) { store.read()?.takeIf { it.instance == instance && current(it) } } ?: return
        val keys = endpoint.pubKeySet ?: return
        val actor = container.repository.readContext() ?: return
        val body = buildJsonObject {
            put("installationId", instance); put("endpoint", endpoint.url)
            putJsonObject("keys") { put("p256dh", keys.pubKey); put("auth", keys.auth) }
        }
        val response = request(actor, "POST", body) ?: return
        synchronized(lock) {
            val latest = store.read() ?: return
            if (!container.repository.isCurrent(actor) || latest.instance != state.instance || !current(latest)) return
            val registration = response.string("registrationId")?.takeIf { Regex("[A-Za-z0-9_-]{16,128}").matches(it) } ?: return
            val key = response.string("signingPublicKey")?.takeIf { it == state.signingPublicKey } ?: return
            store.write(latest.copy(endpoint = endpoint.url, p256dh = keys.pubKey, auth = keys.auth, registrationId = registration,
                signingPublicKey = key, bindingId = response.string("bindingId"), bindingRevision = response["bindingRevision"]?.jsonPrimitive?.longOrNull,
                sequences = if (latest.registrationId == registration) latest.sequences else emptyMap()))
            statusChanged()
        }
    }
    fun receive(content: ByteArray, decrypted: Boolean, instance: String) = synchronized(lock) {
        val accepted = NativePushReceiverCustody(store, ::current).accept(content, decrypted, instance) ?: return@synchronized
        if (accepted is NativePushReceiverCustody.Accepted.Retired) { retire(accepted.enrollment); return@synchronized }
        val display = accepted as NativePushReceiverCustody.Accepted.Display
        val state = display.enrollment
        val decoded = display.payload
        val tag = "collie-push:${state.instance}:${decoded.slot}"
        if (decoded.kind == "clear") { manager.cancel(tag, 0); return@synchronized }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return@synchronized
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.native_notifications_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val tap = Intent(context, NativePushTapActivity::class.java).putExtra("instance", instance).putExtra("payload", display.rawPayload)
            .setData(android.net.Uri.parse("collie-push://$instance/${decoded.slot}"))
        val pending = PendingIntent.getActivity(context, 0, tap, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(tag, 0, NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification_icon)
            .setContentTitle(decoded.title).setContentText(decoded.body).setStyle(NotificationCompat.BigTextStyle().bigText(decoded.body))
            .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(!decoded.renotify).setTimeoutAfter(decoded.expiresAt - System.currentTimeMillis()).build())
    }
    fun tap(instance: String?, payload: String?): NativePushPayload.Target? = synchronized(lock) {
        val state = store.read()?.takeIf { it.instance == instance && current(it) } ?: return@synchronized null
        val decoded = NativePushPayload.decode(payload ?: return@synchronized null, state.registrationId ?: return@synchronized null, System.currentTimeMillis()) ?: return@synchronized null
        if (state.sequences[decoded.slot] != decoded.sequence) return@synchronized null
        decoded.target
    }
    fun retire(event: AuthorityRetirement) = synchronized(lock) {
        if (event.reason !in setOf(AuthorityInvalidation.PAIRING_RETIRED, AuthorityInvalidation.CONNECTION_CHANGED, AuthorityInvalidation.DISCONNECTED)) return@synchronized
        store.read()?.takeIf { it.incarnation == event.custodyVersion?.connectionIncarnation }?.let(::retire)
    }
    fun retire(event: ProxyBindingRetirement) = synchronized(lock) {
        store.read()?.takeIf { it.bindingId == event.bindingId && it.bindingRevision == event.revision }?.let(::retire)
        // A durable tombstone is present before the original outbox event is acknowledged.
    }
    fun unregistered(instance: String) = synchronized(lock) { store.read()?.takeIf { it.instance == instance }?.let(::retire) }
    suspend fun disable() {
        val state = synchronized(lock) { store.read()?.also(::retire) } ?: return
        val actor = container.repository.readContext() ?: return
        if (actor.connection.origin.value == state.origin && credentialHash(actor.connection) == state.credentialHash)
            request(actor, "DELETE", buildJsonObject { put("installationId", state.instance) })
    }
    fun status(): String = synchronized(lock) {
        val state = store.read()
        when {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED -> context.getString(R.string.native_notifications_permission)
            manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE -> context.getString(R.string.native_notifications_channel_off)
            state == null || state.retired -> context.getString(R.string.native_notifications_off); !current(state) -> context.getString(R.string.native_notifications_pair);
            state.registrationId == null || UnifiedPush.getAckDistributor(context) == null -> context.getString(R.string.native_notifications_waiting); else -> context.getString(R.string.native_notifications_enrolled) }
    }
    private fun retire(state: PushEnrollment) {
        store.write(state.copy(retired = true, endpoint = null, p256dh = null, auth = null, sequences = emptyMap()))
        statusChanged()
        manager.activeNotifications.filter { it.tag?.startsWith("collie-push:${state.instance}:") == true }.forEach { manager.cancel(it.tag, it.id) }
        UnifiedPush.unregister(context, instance = state.instance)
    }
    private suspend fun request(actor: ScopedReadContext, method: String, body: JsonObject?): JsonObject? {
        return when (val response = container.repository.nativePush(method, body, actor)) {
            is com.lateapex.collie.network.ApiResult.Success -> response.value
            else -> null
        }
    }
    private fun JsonObject.string(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    companion object { const val CHANNEL = "collie-native-v1" }
}
