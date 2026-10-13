package com.lateapex.collie.ui

import com.lateapex.collie.network.PaneCacheReading
import com.lateapex.collie.network.PaneGitHead
import com.lateapex.collie.network.CacheWatchEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CacheContextPresentationTest {
    @Test
    fun countdownUsesBridgeSnapshotTimeAndNeverThePhoneWallClock() {
        val reading = PaneCacheReading(
            state = "warm",
            expiresAt = 1_700_000_300_000,
            ttlSeconds = 600,
            confidence = "observed",
        )

        val projection = CacheContextPresenter.timing(reading, serverNowMs = 1_700_000_000_000)

        assertEquals(300L, projection?.remainingSeconds)
        assertEquals(600L, projection?.ttlSeconds)
    }

    @Test
    fun unknownStateMissingDeadlineAndImplausibleWindowDoNotInventAnEstimate() {
        assertNull(CacheContextPresenter.timing(PaneCacheReading(state = "future-state", expiresAt = 500_000, ttlSeconds = 300), 0)?.remainingSeconds)
        assertNull(CacheContextPresenter.timing(PaneCacheReading(state = "warm", expiresAt = null, ttlSeconds = 300), 0)?.remainingSeconds)
        assertNull(CacheContextPresenter.timing(PaneCacheReading(state = "warm", expiresAt = 2_000_000, ttlSeconds = 300), 0)?.remainingSeconds)
        assertEquals("unknown", CacheContextPresenter.timing(PaneCacheReading(state = "future-state", confidence = "future"), 0)?.confidence)
    }

    @Test
    fun expiredCacheStaysColdWithoutAZeroOrFutureCountdownAndCannotStartAWatch() {
        val projection = CacheContextPresenter.timing(
            PaneCacheReading(state = "cold", expiresAt = 1_699_999_000_000, ttlSeconds = 300, coldReason = "expired"),
            serverNowMs = 1_700_000_000_000,
        )

        assertEquals("cold", projection?.state)
        assertNull(projection?.remainingSeconds)
        assertEquals(false, CacheWatchControlState.switchEnabled(CacheWatchScreenState(
            watch = com.lateapex.collie.network.CacheWatchResponse(on = false, global = false, watchable = false, warnSeconds = 75),
        )))
    }

    @Test
    fun detachedGitRequiresAnObjectIdAndHeaderContainsOnlyCarriedFacts() {
        assertEquals(CacheContextPresenter.GitFact("detached", "0123456789ab"), CacheContextPresenter.gitFact(
            PaneGitHead(kind = "detached", sha = "0123456789abcdef0123456789abcdef01234567"),
        ))
        assertNull(CacheContextPresenter.gitFact(PaneGitHead(kind = "detached", sha = "not-a-revision")))
        assertNull(CacheContextPresenter.gitFact(PaneGitHead(kind = "unknown", name = "invented")))
        assertEquals(CacheContextPresenter.GitFact("branch", "main"), CacheContextPresenter.gitFact(
            PaneGitHead(kind = "branch", name = "main"),
        ))
    }

    @Test
    fun orphanWatchCannotLinkAndPeerLinkRetainsBothScopeDimensions() {
        assertNull(CacheWatchListPresenter.address(CacheWatchEntry(id = "opaque", label = "retained")))
        assertNull(CacheWatchListPresenter.address(CacheWatchEntry(id = "empty", label = "retained", paneId = " ")))

        val address = CacheWatchListPresenter.address(
            CacheWatchEntry(id = "live", label = "agent", host = "east", session = "work", paneId = "w2:p7"),
        )

        assertEquals("w2:p7", address?.paneId)
        assertEquals("east", address?.scope?.host)
        assertEquals("work", address?.scope?.session)
    }
}
