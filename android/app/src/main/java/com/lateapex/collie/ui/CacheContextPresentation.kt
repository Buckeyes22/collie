package com.lateapex.collie.ui

import com.lateapex.collie.network.PaneCacheReading
import com.lateapex.collie.network.PaneGitHead
import com.lateapex.collie.network.CacheWatchEntry
import com.lateapex.collie.domain.PaneAddress
import com.lateapex.collie.domain.Scope

/** Display-only projection of facts carried by the owning Collie snapshot. */
internal object CacheContextPresenter {
    data class Timing(val state: String, val remainingSeconds: Long?, val confidence: String?, val ttlSeconds: Long?)
    data class GitFact(val kind: String, val value: String)
    data class PeerSource(val label: String, val confidence: String, val overridden: Boolean, val catalogAvailable: Boolean = false)

    fun timing(cache: PaneCacheReading?, serverNowMs: Long): Timing? {
        cache ?: return null
        val state = cache.state?.takeIf { it in STATES } ?: "unknown"
        val ttl = cache.ttlSeconds?.takeIf { it in 1..MAX_TTL_SECONDS }
        val expires = cache.expiresAt
        // Use the bridge's snapshot timestamp, then monotonic time in the view. Device wall-clock
        // skew must never turn an epoch deadline into a made-up duration.
        val remaining = if (state in setOf("warm", "expiring") && ttl != null && expires != null) {
            val seconds = (expires - serverNowMs) / 1000L
            seconds.takeIf { it in 0..(ttl + CLOCK_SKEW_TOLERANCE_SECONDS) }
        } else null
        return Timing(
            state = state,
            remainingSeconds = remaining,
            confidence = cache.confidence?.takeIf { it in CONFIDENCE } ?: "unknown",
            ttlSeconds = ttl,
        )
    }

    fun ageSeconds(atMs: Double?, serverNowMs: Long): Long? {
        atMs ?: return null
        val elapsedSeconds = (serverNowMs.toDouble() - atMs) / 1000.0
        if (!elapsedSeconds.isFinite()) return null
        val seconds = elapsedSeconds.toLong()
        return seconds.takeIf { it in 0..MAX_AGE_SECONDS }
    }

    fun gitFact(head: PaneGitHead): GitFact? = when (head.kind) {
        "branch" -> head.name?.takeIf(String::isNotBlank)?.let { GitFact("branch", it) }
        "detached" -> head.sha?.takeIf { it.matches(Regex("[0-9a-f]{40}|[0-9a-f]{64}")) }?.take(12)?.let { GitFact("detached", it) }
        else -> null
    }

    fun peerSource(cache: PaneCacheReading): PeerSource? {
        val label = cache.label?.takeIf(String::isNotBlank) ?: return null
        return PeerSource(
            label = label,
            confidence = cache.confidence?.takeIf { it in CONFIDENCE } ?: "unknown",
            overridden = cache.overridden == true,
        )
    }

    private val STATES = setOf("warm", "expiring", "cold", "unknown")
    private val CONFIDENCE = setOf("observed", "reported", "documented", "unknown")
    private const val MAX_TTL_SECONDS = 86_400L
    private const val CLOCK_SKEW_TOLERANCE_SECONDS = 300L
    private const val MAX_AGE_SECONDS = 365L * 24L * 60L * 60L
}

/** List rows link only when the lead supplied a live pane address; opaque orphan IDs only forget. */
internal object CacheWatchListPresenter {
    fun address(entry: CacheWatchEntry): PaneAddress? = entry.paneId
        ?.takeIf(String::isNotBlank)
        ?.let { PaneAddress(Scope(host = entry.host, session = entry.session), it) }
}
