package com.lateapex.collie.network

import com.lateapex.collie.data.ScopedReadContext
import com.lateapex.collie.domain.Connection
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import okhttp3.Request

/** Captured at canonical repository admission, propagated across dispatcher switches and API
 * children, then copied into each immutable request. Interception never mints an actor. */
internal object RequestActorCustody {
    private val admitted = ThreadLocal<ScopedReadContext?>()
    fun actor(): ScopedReadContext? = admitted.get()
    suspend fun <T> withActor(actor: ScopedReadContext, block: suspend () -> T): T =
        withContext(admitted.asContextElement(actor)) { block() }
}
/** Only the shared API's exact candidate health/pair builders install this bootstrap tag. It
 * permits no credentials and grants no gateway exemption or protected read/write authority. */
internal class NativeBootstrapAdmission(private val candidate: Connection, private val method: String, private val url: String) {
    fun matches(request: Request, connection: Connection): Boolean = candidate === connection && candidate.token == null &&
        method in setOf("GET", "POST") && request.method == method && request.url.toString() == url && request.url.scheme == "https" &&
        url == candidate.origin.value + "api/" + (if (method == "GET") "health" else "pair")
}
