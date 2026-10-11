package com.lateapex.collie.ui

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.lateapex.collie.data.CollieRepository
import com.lateapex.collie.data.ScopedReadContext
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.FilesTarget
import com.lateapex.collie.network.RelativeFilePath
import java.util.UUID

/** Single-use process-local navigation custody. Intents carry only an opaque handle, never credentials. */
internal object FilesReadHandoff {
    private const val MAX_PENDING = 32
    private const val TTL_MS = 30_000L
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val lock = Any()
    private val pending = mutableMapOf<String, Entry>()
    private class Entry(
        val repository: CollieRepository,
        val target: FilesTarget,
        val scope: Scope,
        val path: RelativeFilePath,
        val context: ScopedReadContext,
        val expiresAt: Long,
        var registration: AutoCloseable? = null,
        var expiry: Runnable? = null,
    )

    fun issue(repository: CollieRepository, target: FilesTarget, scope: Scope, path: RelativeFilePath, context: ScopedReadContext): String? {
        if (context.key.scope != scope || context.key.subjectIdentity != target.id || !repository.isCurrent(context)) return null
        val token = UUID.randomUUID().toString()
        val entry = Entry(repository, target, scope, path, context, SystemClock.elapsedRealtime() + TTL_MS)
        synchronized(lock) {
            if (pending.size >= MAX_PENDING) return null
            pending[token] = entry
        }
        // Registration/removal never invokes repository code while holding the custody lock.
        val registration = repository.onFilesActorRetired(context) { retire(token) }
        val kept = synchronized(lock) {
            if (pending[token] === entry) { entry.registration = registration; true } else false
        }
        if (!kept) registration.close()
        if (!kept || !repository.isCurrent(context)) { retire(token); return null }
        val expiry = Runnable { retire(token) }
        entry.expiry = expiry
        if (!handler.postDelayed(expiry, TTL_MS)) {
            retire(token)
            return null
        }
        if (synchronized(lock) { pending[token] !== entry }) handler.removeCallbacks(expiry)
        return token
    }

    fun take(token: String?, repository: CollieRepository, target: FilesTarget, scope: Scope, path: String?): ScopedReadContext? {
        if (token == null) return null
        val entry = synchronized(lock) { pending.remove(token) } ?: return null
        entry.expiry?.let(handler::removeCallbacks)
        entry.registration?.close()
        return entry.context.takeIf {
            entry.repository === repository && entry.target == target && entry.scope == scope &&
                entry.path.value == path && SystemClock.elapsedRealtime() < entry.expiresAt &&
                it.key.scope == scope && it.key.subjectIdentity == target.id && repository.isCurrent(it)
        }
    }

    private fun retire(token: String) {
        val entry = synchronized(lock) { pending.remove(token) }
        entry?.expiry?.let(handler::removeCallbacks)
        entry?.registration?.close()
    }
}
