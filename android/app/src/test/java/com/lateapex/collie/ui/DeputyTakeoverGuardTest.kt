package com.lateapex.collie.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.domain.CollieOrigin
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.network.CrewDeputy
import com.lateapex.collie.network.CrewIdentity
import com.lateapex.collie.network.CrewMember
import com.lateapex.collie.network.CrewSelf
import com.lateapex.collie.network.CrewStatusResponse
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DeputyTakeoverGuardTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("collie_takeover_v1", Context.MODE_PRIVATE)
    private val connection = Connection(CollieOrigin("https://collie.example/mount/"), "phone", "private-bearer")
    @Before fun clear() { prefs.edit().clear().commit() }
    private fun crew(lead: String, self: String = lead) = CrewStatusResponse(
        CrewIdentity("crew1", "Crew", 1, 0), CrewSelf(self, self, "1.21.0"), CrewDeputy("deputy"),
        listOf(CrewMember(lead, lead, true, health = "reachable", lastSeenAt = 1, secretBehind = false, provisional = false)), 1,
    )
    @Test fun uncertainAttemptSurvivesRestartAndCannotReserveAgain() {
        val first = DeputyTakeoverGuard(context).reserve(connection, "deputy")!!
        val restarted = DeputyTakeoverGuard(context)
        assertTrue(restarted.pending(connection)); assertNull(restarted.reserve(connection, "deputy"))
        assertEquals(first, restarted.currentAttempt(connection))
        val persisted = prefs.all.values.joinToString()
        assertFalse(persisted.contains("private-bearer")); assertFalse(persisted.contains("collie.example"))
        assertFalse(first.toString().contains("deputy"))
    }
    @Test fun profileSwitchCannotForgetOriginalUncertainAttempt() {
        val guard = DeputyTakeoverGuard(context)
        val original = guard.reserve(connection, "deputy")!!
        val replacement = Connection(connection.origin, "other", "replacement-bearer")
        assertFalse(guard.pending(replacement)); assertNotNull(guard.reserve(replacement, "other-deputy"))
        assertTrue(guard.pending(connection)); assertNull(guard.reserve(connection, "deputy"))
        assertFalse(guard.observeNewLead(replacement, crew("deputy")))
        assertEquals(original, guard.currentAttempt(connection))
        val otherMount = Connection(CollieOrigin("https://collie.example/other/"), "phone", connection.token)
        assertFalse(guard.pending(otherMount))
    }
    @Test fun knownRefusalClearsOnlyMatchingAttemptAndLateResponseCannotEraseSuccessor() {
        val guard = DeputyTakeoverGuard(context)
        val first = guard.reserve(connection, "deputy")!!
        assertTrue(guard.refused(first))
        val second = guard.reserve(connection, "deputy")!!
        assertFalse(guard.refused(first)); assertTrue(guard.pending(connection))
        assertEquals(second, guard.currentAttempt(connection))
    }
    @Test fun onlyExpectedDeputyWithUnambiguousLeadAndSelfSettlesAutomatically() {
        val guard = DeputyTakeoverGuard(context)
        assertNotNull(guard.reserve(connection, "deputy"))
        assertFalse(guard.observeNewLead(connection, crew("old-lead")))
        assertFalse(guard.observeNewLead(connection, crew("deputy", "old-lead")))
        val ambiguous = crew("deputy").let { it.copy(members = it.members + crew("old-lead").members) }
        assertFalse(guard.observeNewLead(connection, ambiguous))
        assertTrue(guard.observeNewLead(connection, crew("deputy"))); assertFalse(guard.pending(connection))
    }
    @Test fun coldStartUnknownNeedsExplicitReviewedLeadAndExactOriginalAttempt() {
        val guard = DeputyTakeoverGuard(context)
        val first = guard.reserve(connection, null)!!
        assertFalse(guard.observeNewLead(connection, crew("deputy")))
        assertTrue(guard.resolveReviewedLead(first, connection, crew("deputy")))
        assertNotNull(guard.reserve(connection, null))
        assertFalse(guard.resolveReviewedLead(first, connection, crew("deputy")))
        assertTrue(guard.pending(connection))
    }
    @Test fun unpairedCorruptOrConcurrentCustodyRefusesDuplicateReservation() {
        val guard = DeputyTakeoverGuard(context)
        assertNull(guard.reserve(Connection(connection.origin, "unpaired", null), "deputy"))
        val gate = java.util.concurrent.CountDownLatch(1)
        val count = java.util.concurrent.atomic.AtomicInteger()
        val threads = List(2) { Thread { gate.await(); if (DeputyTakeoverGuard(context).reserve(connection, "deputy") != null) count.incrementAndGet() } }
        threads.forEach(Thread::start); gate.countDown(); threads.forEach { it.join(5000); assertFalse(it.isAlive) }
        assertEquals(1, count.get())
        val key = prefs.all.keys.single(); prefs.edit().putString(key, "malformed").commit()
        assertTrue(guard.pending(connection)); assertNull(guard.reserve(connection, "deputy"))
    }
}
