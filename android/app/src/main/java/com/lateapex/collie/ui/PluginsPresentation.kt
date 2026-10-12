package com.lateapex.collie.ui

import com.lateapex.collie.data.CollieRepository
import com.lateapex.collie.data.ScopedReadContext
import com.lateapex.collie.domain.Scope
import com.lateapex.collie.network.ApiFailure
import com.lateapex.collie.network.ApiResult

internal sealed interface PluginReceiptRead {
    data class Success(val state: String, val code: String?, val removed: Boolean) : PluginReceiptRead
    data class Failure(val message: String?, val otherEndpointMayMatch: Boolean = false) : PluginReceiptRead
    data object NotModified : PluginReceiptRead
}

/** Read the endpoint recorded with the ID; old untyped IDs use only safe GET probing. */
internal suspend fun CollieRepository.readPluginReceipt(
    requestId: String,
    scope: Scope,
    actor: ScopedReadContext,
    kind: PluginReceiptKind,
): PluginReceiptRead {
    suspend fun ownerReceipt(): PluginReceiptRead = when (val result = hostPluginOwnerOperation(requestId, scope, actor)) {
        is ApiResult.Success -> result.value.ownerOperation.let { PluginReceiptRead.Success(it.state, it.code, it.removed) }
        is ApiResult.Failure -> PluginReceiptRead.Failure(
            result.error.pluginMessage,
            (result.error as? ApiFailure.Http)?.status in setOf(404, 501),
        )
        is ApiResult.NotModified -> PluginReceiptRead.NotModified
    }
    suspend fun controlReceipt(): PluginReceiptRead = when (val result = controlOperation(scope, requestId, actor)) {
        is ApiResult.Success -> result.value.operation.let { PluginReceiptRead.Success(it.state, it.code, false) }
        is ApiResult.Failure -> PluginReceiptRead.Failure(result.error.pluginMessage)
        is ApiResult.NotModified -> PluginReceiptRead.NotModified
    }

    return when (kind) {
        PluginReceiptKind.OWNER_OPERATION -> ownerReceipt()
        PluginReceiptKind.CONTROL_OPERATION -> controlReceipt()
        PluginReceiptKind.LEGACY_UNKNOWN -> when (val owner = ownerReceipt()) {
            is PluginReceiptRead.Success -> owner
            is PluginReceiptRead.Failure -> {
                if (owner.otherEndpointMayMatch) controlReceipt() else owner
            }
            PluginReceiptRead.NotModified -> controlReceipt()
        }
    }
}

internal val ApiFailure.pluginMessage: String?
    get() = when (this) {
        is ApiFailure.Http -> message
        is ApiFailure.Protocol -> message
        is ApiFailure.Network -> message
        is ApiFailure.Redirect -> "The host redirected the request. Refresh the configured connection."
        ApiFailure.Timeout -> "The host did not respond in time."
    }

internal val ApiFailure.pluginCode: String?
    get() = (this as? ApiFailure.Http)?.code
