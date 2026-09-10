package com.lateapex.collie.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OriginValidatorTest {
    private val validator = OriginValidator()

    @Test
    fun normalizesHttpsOrigin() {
        val result = validator.validate("  https://Example.COM:8443  ")
        assertEquals("https://example.com:8443/", (result as OriginValidation.Valid).origin.value)
        assertEquals("https://example.com:8443", result.origin.headerValue)
    }

    @Test
    fun rejectsAnythingOtherThanAnHttpsOrigin() {
        val invalid = mapOf(
            "" to OriginValidation.Reason.EMPTY,
            "http://example.com" to OriginValidation.Reason.NOT_HTTPS,
            "https://user:pass@example.com" to OriginValidation.Reason.USER_INFO,
            "https://example.com/api" to OriginValidation.Reason.NON_ROOT_PATH,
            "https://example.com/%2e" to OriginValidation.Reason.NON_ROOT_PATH,
            "https://example.com\\@elsewhere.invalid" to OriginValidation.Reason.MALFORMED,
            "https://example.com/?x=1" to OriginValidation.Reason.QUERY,
            "https://example.com/#x" to OriginValidation.Reason.FRAGMENT,
            "not a URL" to OriginValidation.Reason.MALFORMED,
        )
        invalid.forEach { (input, expected) ->
            val result = validator.validate(input)
            assertTrue(input, result is OriginValidation.Invalid)
            assertEquals(input, expected, (result as OriginValidation.Invalid).reason)
        }
    }
}
