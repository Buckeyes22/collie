package com.lateapex.collie.network

import com.lateapex.collie.domain.CollieOrigin
import java.net.URI
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

sealed interface OriginValidation {
    data class Valid(val origin: CollieOrigin) : OriginValidation
    data class Invalid(val reason: Reason) : OriginValidation

    enum class Reason {
        EMPTY,
        NOT_HTTPS,
        MISSING_HOST,
        USER_INFO,
        NON_ROOT_PATH,
        QUERY,
        FRAGMENT,
        MALFORMED,
    }
}

class OriginValidator {
    fun validate(raw: String): OriginValidation {
        val candidate = raw.trim()
        if (candidate.isEmpty()) return OriginValidation.Invalid(OriginValidation.Reason.EMPTY)

        val lexical = try {
            URI(candidate)
        } catch (_: Exception) {
            return OriginValidation.Invalid(OriginValidation.Reason.MALFORMED)
        }
        if (!lexical.scheme.equals("https", ignoreCase = true)) {
            return OriginValidation.Invalid(OriginValidation.Reason.NOT_HTTPS)
        }
        if (lexical.rawUserInfo != null) return OriginValidation.Invalid(OriginValidation.Reason.USER_INFO)
        if (lexical.rawPath !in listOf(null, "", "/")) {
            return OriginValidation.Invalid(OriginValidation.Reason.NON_ROOT_PATH)
        }
        if (lexical.rawQuery != null) return OriginValidation.Invalid(OriginValidation.Reason.QUERY)
        if (lexical.rawFragment != null) return OriginValidation.Invalid(OriginValidation.Reason.FRAGMENT)

        val parsed = candidate.toHttpUrlOrNull()
            ?: return OriginValidation.Invalid(OriginValidation.Reason.MALFORMED)
        if (parsed.host.isBlank()) {
            return OriginValidation.Invalid(OriginValidation.Reason.MISSING_HOST)
        }
        if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            return OriginValidation.Invalid(OriginValidation.Reason.USER_INFO)
        }
        if (parsed.encodedPath != "/") {
            return OriginValidation.Invalid(OriginValidation.Reason.NON_ROOT_PATH)
        }
        if (parsed.query != null) return OriginValidation.Invalid(OriginValidation.Reason.QUERY)
        if (parsed.fragment != null) return OriginValidation.Invalid(OriginValidation.Reason.FRAGMENT)

        return OriginValidation.Valid(CollieOrigin(parsed.toString()))
    }

    fun requireValid(raw: String): CollieOrigin = when (val result = validate(raw)) {
        is OriginValidation.Valid -> result.origin
        is OriginValidation.Invalid -> throw IllegalArgumentException("Invalid Collie origin: ${result.reason}")
    }
}
