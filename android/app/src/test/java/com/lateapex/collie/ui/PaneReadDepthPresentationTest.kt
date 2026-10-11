package com.lateapex.collie.ui

import com.lateapex.collie.network.PaneReadResponse
import org.junit.Assert.assertEquals
import org.junit.Test

class PaneReadDepthPresentationTest {
    @Test fun adapterClampKeepsDesiredAndAdmittedCountsDistinct() {
        assertEquals(
            "Adapter ceiling: requested 1400 rows; admitted 1000; served 1000.",
            PaneReadDepthPresentation.explanation(PaneReadResponse("p", "x\n".repeat(1000), true, 1, requestedLines = 1000, servedLines = 1000, limitReason = "adapter-ceiling"), 1400),
        )
    }

    @Test fun unknownDepthDoesNotClaimExhaustivenessWhenReadIsShort() {
        assertEquals(
            "Served 200 of 1200 requested rows; available depth is unknown.",
            PaneReadDepthPresentation.explanation(PaneReadResponse("p", "x\n".repeat(200), false, 1, requestedLines = 1200, servedLines = 200), 1200),
        )
    }

    @Test fun nativeDesiredDepthRemainsSeparateWhenAdapterClampsItsRequest() {
        assertEquals(
            "Adapter ceiling: desired 1200 rows; admitted 1000; served 1000. Agent history is separate.",
            PaneReadDepthPresentation.explanation(
                PaneReadResponse("p", "x\n".repeat(1000), true, 1, requestedLines = 1000, servedLines = 1000),
                desiredLines = 1200,
                maximumLines = 1000,
                adapterLimitDeclared = true,
            ),
        )
        assertEquals(
            "Client compatibility limit: desired 1200 rows; admitted 1000; served 1000. Agent history is separate.",
            PaneReadDepthPresentation.explanation(
                PaneReadResponse("p", "x\n".repeat(1000), true, 1, requestedLines = 1000, servedLines = 1000),
                desiredLines = 1200,
                maximumLines = 1000,
            ),
        )
    }

    @Test fun reportedDepthIsNotAnAltScreenHistoryClaim() {
        assertEquals(
            "The reported retained depth was reached (24 rows); separate agent history may still exist.",
            PaneReadDepthPresentation.explanation(PaneReadResponse("p", "x\n".repeat(24), false, 1, servedLines = 24, readableLines = 24, limitReason = "available-depth"), 600),
        )
    }
}
