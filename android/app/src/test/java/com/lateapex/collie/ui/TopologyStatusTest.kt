package com.lateapex.collie.ui

import com.lateapex.collie.network.MuxConfigResponse
import com.lateapex.collie.network.MuxTopologyLatencyResponse
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlinx.serialization.json.JsonPrimitive

class TopologyStatusTest {
    @Test fun declaredBoundAndSnapshotAgeRemainSeparate() {
        val mux = MuxConfigResponse(topologyLatency = MuxTopologyLatencyResponse("bounded", JsonPrimitive(12_000)))
        assertEquals("topology census: up to 12000 ms · snapshot read: 5s ago", TopologyStatus.describe(mux, 10_000, 15_000))
    }

    @Test fun oldOrMalformedDeclarationDoesNotInventFreshness() {
        assertEquals("topology timing: not declared · snapshot: not read", TopologyStatus.describe(null, null, 100))
        assertEquals("topology timing: not declared · snapshot read: 2m ago", TopologyStatus.describe(
            MuxConfigResponse(topologyLatency = MuxTopologyLatencyResponse("bounded", JsonPrimitive(-1))), 1_000, 121_000,
        ))
        assertEquals(null, MuxTopologyLatencyResponse("bounded", JsonPrimitive(12_001)).displayBoundMs())
    }
}
