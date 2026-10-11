package com.lateapex.collie.auth

import java.net.URI
import okhttp3.HttpUrl.Companion.toHttpUrl
import kotlinx.serialization.json.*

class ProxyProfile private constructor(val raw: String, val issuer: String, val clientId: String, val audience: String,
    val authorizationEndpoint: String, val tokenEndpoint: String, val jwksEndpoint: String, val gatewayOrigin: String, val keys: JsonArray) {
    val identity: String = ProxyJose.hash(raw.encodeToByteArray())
    override fun toString() = "ProxyProfile(configured)"
    companion object {
        const val REDIRECT = "com.lateapex.collie:/oauth2redirect"
        fun parse(raw: String): ProxyProfile {
            val v = ProxyJson.objectOf(raw)
            require(v.keys == setOf("schema","issuer","clientId","audience","redirectUri","authorizationEndpoint","tokenEndpoint","jwksEndpoint","gatewayOrigin","algorithms","jwks"))
            require(v["schema"]?.jsonPrimitive?.isString == false && v["schema"]?.jsonPrimitive?.intOrNull == 1 && ProxyJson.string(v, "redirectUri") == REDIRECT)
            require(v["algorithms"]?.jsonArray == buildJsonArray { add("ES256") })
            fun endpoint(key: String): String = ProxyJson.string(v, key).also { text ->
                val u = URI(text); require(u.scheme == "https" && u.host != null && u.rawUserInfo == null && u.rawQuery == null && u.rawFragment == null && !u.rawPath.contains(Regex("%(2[fF]|5[cC]|00)")))
                val canonical = text.toHttpUrl().toString()
                val rootIssuer = key == "issuer" && u.rawPath.isEmpty() && canonical == "$text/"
                require(u.normalize().toASCIIString() == text && (canonical == text || rootIssuer))
            }
            val issuer = endpoint("issuer"); require(issuer.length <= 256); val auth = endpoint("authorizationEndpoint"); val token = endpoint("tokenEndpoint"); val jwks = endpoint("jwksEndpoint"); val gateway = endpoint("gatewayOrigin")
            fun origin(url: String) = URI(url).let { "${it.scheme}://${it.rawAuthority}" }
            require(listOf(auth, token, jwks).all { origin(it) == origin(issuer) } && URI(gateway).rawPath == "/")
            val keys = v["jwks"]?.jsonArray ?: error("Missing issuer keys"); require(keys.size in 1..8)
            val kids = mutableSetOf<String>()
            keys.forEach { element -> val k = element.jsonObject
                require(k.keys.all { it in setOf("kty", "crv", "x", "y", "kid", "alg", "use") })
                require(ProxyJson.string(k, "kty") == "EC" && ProxyJson.string(k, "crv") == "P-256")
                require(ProxyJose.decode(ProxyJson.string(k, "x", 43)).size == 32 && ProxyJose.decode(ProxyJson.string(k, "y", 43)).size == 32)
                val kid = ProxyJson.string(k, "kid", 128); require(kid.matches(Regex("[A-Za-z0-9._-]{1,128}")) && kids.add(kid))
                k["alg"]?.let { require(it.jsonPrimitive.content == "ES256") }; k["use"]?.let { require(it.jsonPrimitive.content == "sig") }
            }
            return ProxyProfile(raw, issuer, ProxyJson.string(v, "clientId", 256), ProxyJson.string(v, "audience", 256), auth, token, jwks, gateway, keys)
        }
    }
}
