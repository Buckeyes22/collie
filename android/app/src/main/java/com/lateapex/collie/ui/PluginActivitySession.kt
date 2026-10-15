package com.lateapex.collie.ui

import android.os.Bundle
import com.lateapex.collie.data.CollieRepository
import com.lateapex.collie.data.ScopedReadContext
import com.lateapex.collie.domain.Scope

/** Process-local write actor plus a strictly read-only actor for matching durable receipts. */
internal class PluginActivitySession(
    val scope: Scope,
    val actor: ScopedReadContext?,
    receiptActor: ScopedReadContext?,
    val receiptOwner: PluginReceiptOwner?,
    val pendingReceipts: LinkedHashMap<String, PluginReceiptKind>,
    private val receiptStore: PluginReceiptStore,
) {
    private val receiptActorCache = PluginReceiptActorCache(receiptActor)

    fun isWriteCurrent(repository: CollieRepository): Boolean =
        actor?.let(repository::isCurrent) == true && Scope(host = repository.selectedScope.value.host) == scope

    /** Process-restored sessions have no write actor; only this fingerprint-gated GET context. */
    fun receiptContext(repository: CollieRepository): ScopedReadContext? = receiptActorCache.resolve(
        receiptOwner = receiptOwner,
        scope = scope,
        requireCommitted = actor == null,
        candidateProvider = { if (actor == null) repository.readContext(scope) else null },
        isCurrent = repository::isCurrent,
    )

    /** Called before dispatch; fail closed if the bounded local receipt set cannot commit. */
    fun track(requestId: String, kind: PluginReceiptKind): Boolean {
        val owner = receiptOwner ?: return false
        if (!PLUGIN_REQUEST_ID.matches(requestId) || !receiptStore.add(owner, requestId, kind)) return false
        pendingReceipts[requestId] = kind
        return true
    }

    fun kind(requestId: String): PluginReceiptKind? = pendingReceipts[requestId]

    /** Keep an ID until a terminal owner receipt was read and its local retirement commits. */
    fun reconcile(requestId: String) {
        val owner = receiptOwner ?: return
        if (receiptStore.remove(owner, requestId)) pendingReceipts.remove(requestId)
    }

    fun save(outState: Bundle) {
        outState.putString(PLUGIN_OWNER_FINGERPRINT, receiptOwner?.pairedIdentity)
        outState.putStringArrayList(PLUGIN_PENDING_REQUESTS, ArrayList(pendingReceipts.map { (id, kind) -> PendingPluginReceipt(id, kind).encode() }))
    }

    companion object {
        private val PLUGIN_REQUEST_ID = Regex("[A-Za-z0-9_-]{1,128}")
    }
}

/** Caches the first eligible receipt actor so pre- and post-request checks compare the same object. */
internal class PluginReceiptActorCache(initial: ScopedReadContext?) {
    private var cached = initial

    @Synchronized
    fun resolve(
        receiptOwner: PluginReceiptOwner?,
        scope: Scope,
        requireCommitted: Boolean,
        candidateProvider: () -> ScopedReadContext?,
        isCurrent: (ScopedReadContext) -> Boolean,
    ): ScopedReadContext? {
        val owner = receiptOwner ?: return null
        val candidate = cached ?: candidateProvider()?.takeIf {
            owner.matches(it, requireCommitted) && it.key.host == scope.host && it.key.session == scope.session && isCurrent(it)
        }?.also { cached = it } ?: return null
        if (!owner.matches(candidate, requireCommitted) || candidate.key.host != scope.host ||
            candidate.key.session != scope.session || !isCurrent(candidate)) return null
        return candidate
    }
}

internal fun restorePluginActivitySession(
    retained: Any?,
    savedState: Bundle?,
    repository: CollieRepository,
    scope: Scope,
    receiptStore: PluginReceiptStore,
): PluginActivitySession {
    val savedReceipts = savedState?.getStringArrayList(PLUGIN_PENDING_REQUESTS).orEmpty()
        .asSequence().mapNotNull(PendingPluginReceipt::parse).take(MAX_PENDING_REQUESTS).toList()
    val retainedSession = retained as? PluginActivitySession
    if (retainedSession != null) {
        savedReceipts.forEach { retainedSession.track(it.requestId, it.kind) }
        return retainedSession
    }

    if (savedState == null) {
        val actor = repository.readContext(scope)
        val owner = actor?.key?.pairedDeviceIdentity?.let { PluginReceiptOwner.create(it, scope) }
        val pending = linkedMapOf<String, PluginReceiptKind>().apply { putAll(owner?.let(receiptStore::pending).orEmpty()) }
        return PluginActivitySession(scope, actor, actor, owner, pending, receiptStore)
    }

    val pairedIdentity = savedState.getString(PLUGIN_OWNER_FINGERPRINT)
    val owner = pairedIdentity?.let { PluginReceiptOwner.create(it, scope) }
    val candidate = repository.readContext(scope)
    val receiptActor = matchingCommittedPluginReceiptActor(owner, candidate)
        ?.takeIf(repository::isCurrent)
    val pending = linkedMapOf<String, PluginReceiptKind>().apply {
        putAll(owner?.let(receiptStore::pending).orEmpty())
        savedReceipts.forEach { receipt ->
            if (receipt.requestId in this || size < MAX_PENDING_REQUESTS) put(receipt.requestId, receipt.kind)
        }
    }
    if (owner != null) savedReceipts.forEach { receiptStore.add(owner, it.requestId, it.kind) }
    // Never restore a write actor across process death, even when the paired identity matches.
    return PluginActivitySession(scope, null, receiptActor, owner, pending, receiptStore)
}

internal fun matchingCommittedPluginReceiptActor(
    owner: PluginReceiptOwner?,
    candidate: ScopedReadContext?,
): ScopedReadContext? = candidate?.takeIf { owner?.matches(it, requireCommitted = true) == true }

internal const val PLUGIN_PENDING_REQUESTS = "host_plugin_pending_request_ids"
internal const val PLUGIN_OWNER_FINGERPRINT = "host_plugin_pairing_fingerprint"
private val PLUGIN_REQUEST_ID = Regex("[A-Za-z0-9_-]{1,128}")
private const val MAX_PENDING_REQUESTS = 32
