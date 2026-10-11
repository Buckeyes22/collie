package com.lateapex.collie.data

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Carries the original immutable actor and declared operation admission through all transport waits. */
internal class OriginalReadActor(private val admitted: () -> Boolean) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<OriginalReadActor>
    fun permitsDispatch(): Boolean = admitted()
    override fun toString(): String = "OriginalReadActor(<redacted>)"
}
