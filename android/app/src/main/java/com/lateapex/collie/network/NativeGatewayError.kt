package com.lateapex.collie.network

import com.lateapex.collie.auth.ProxyJson
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Response

/** A correlated header alone cannot grant gateway refusal or credential-retirement authority. */
internal object NativeGatewayError {
    const val MAX_BODY_BYTES = 2048

    fun verified(response: Response, body: String, requestId: String?): Boolean {
        if (requestId.isNullOrEmpty() || response.code !in setOf(401, 403) ||
            response.header("X-Collie-Response-Source") != "native_gateway" ||
            response.header("X-Collie-Error-Source") != "native_gateway" ||
            response.header("X-Collie-Native-Request-Id") != requestId
        ) return false
        val envelope = runCatching { ProxyJson.objectOf(body, MAX_BODY_BYTES) }.getOrNull() ?: return false
        if (envelope.keys != setOf("source", "factor", "code", "requestId")) return false
        // Safe casts reject containers; isString rejects null, numbers and booleans without
        // coercion. Both the API decoder and the interceptor use this same closed validator.
        fun string(name: String): String? = (envelope[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val source = string("source") ?: return false
        val factor = string("factor") ?: return false
        val code = string("code") ?: return false
        val correlation = string("requestId") ?: return false
        return source == "native_gateway" && factor in setOf("identity", "device") &&
            factor == response.header("X-Collie-Error-Factor") && code.isNotEmpty() &&
            code == response.header("X-Collie-Error-Code") && correlation == requestId
    }
}
