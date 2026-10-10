package com.lateapex.collie.network

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ControlModelsTest {
    @Test fun newCapabilitiesRequirePositiveDeclarations() {
        val json = Json { ignoreUnknownKeys = true }
        assertFalse(json.decodeFromString<ControlCapabilityResponse>("{}").supportsNew("controlOperations"))
        assertFalse(ControlCapabilityResponse(mapOf("controlOperations" to false)).supportsNew("controlOperations"))
        assertTrue(ControlCapabilityResponse(mapOf("controlOperations" to true)).supportsNew("controlOperations"))
        assertEquals(CapabilityState.Unknown, ControlCapabilityResponse().state("controlOperations"))
        assertFalse(HostControlResponse(provider = "herdr").supportsNew("plugins"))
    }
    @Test fun matchingStatusAndProseCannotRetirePairing() {
        for (status in listOf(401, 403)) {
            assertFalse(ApiFailure.Http(status, "device expired", "pairing.expired").retiresPairing())
            assertFalse(ApiFailure.Http(status, "device expired", "pairing.expired", source = FailureSource.GATEWAY, factor = FailureFactor.PAIRING).retiresPairing())
            assertFalse(ApiFailure.Http(status, "device expired", "pairing.expired", source = FailureSource.COLLIE, factor = FailureFactor.IDENTITY).retiresPairing())
            assertTrue(ApiFailure.Http(status, "unpaired", "pairing.expired", source = FailureSource.COLLIE, factor = FailureFactor.PAIRING).retiresPairing())
        }
    }
    @Test fun operationSchemaAndClosedStateRefuseAmbiguousReceipts() {
        val pending = ControlOperation(1, "intent", "pending", "movePane", "occupant", 1, 2)
        assertTrue(pending.validFor("intent"))
        assertTrue(pending.copy(state = "unknown").validFor("intent"))
        assertFalse(pending.copy(state = "retry").validFor("intent"))
        assertFalse(pending.copy(schema = 2).validFor("intent"))
        assertFalse(pending.copy(updatedAt = 0).validFor("intent"))
        assertFalse(pending.validFor("another"))
    }
}
