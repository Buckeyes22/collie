package com.lateapex.collie.auth

import com.lateapex.collie.data.ProxyIdentityStore
import kotlinx.serialization.Serializable

/** Exact authenticated enrollment retirement. B22 must acknowledge only after its durable signed
 * consumer/tombstone commits; merely observing an event never acknowledges it. */
@Serializable
data class ProxyBindingRetirement(val bindingId: String, val revision: Long)
class ProxyBindingLifecycle internal constructor(private val store: ProxyIdentityStore? = null) {
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(ProxyBindingRetirement) -> Unit>()
    fun observe(listener: (ProxyBindingRetirement) -> Unit): AutoCloseable {
        listeners.add(listener); pending().forEach(listener)
        return AutoCloseable { listeners.remove(listener) }
    }
    fun pending(): List<ProxyBindingRetirement> = store?.pendingRetirements() ?: emptyList()
    fun acknowledge(event: ProxyBindingRetirement) { store?.acknowledgeRetirement(event) }
    internal fun publish(event: ProxyBindingRetirement) { listeners.forEach { it(event) } }
    internal fun retire(bindingId: String, revision: Long) {
        require(bindingId.matches(Regex("[A-Za-z0-9_-]{16,64}")) && revision > 0)
        publish(ProxyBindingRetirement(bindingId, revision))
    }
}
