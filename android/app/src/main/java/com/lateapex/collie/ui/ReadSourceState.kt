package com.lateapex.collie.ui

import com.lateapex.collie.data.ScopedRequestKey

/** Saved reads convey information but cannot restore live write authority. */
sealed interface ReadSourceState {
    val capturedAt: Long?
    val connectionGeneration: Long
    data class Live(val key: ScopedRequestKey, override val capturedAt: Long) : ReadSourceState {
        override val connectionGeneration: Long get() = key.connectionGeneration
    }
    data class SavedCopy(override val capturedAt: Long, override val connectionGeneration: Long) : ReadSourceState
    data class Unavailable(override val connectionGeneration: Long, val reason: String? = null) : ReadSourceState {
        override val capturedAt: Long? = null
    }
    fun permitsWrites(current: ScopedRequestKey): Boolean = this is Live && key == current
}
