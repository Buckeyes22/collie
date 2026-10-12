package com.lateapex.collie.ui

import androidx.test.core.app.ApplicationProvider
import com.lateapex.collie.CollieApplication
import com.lateapex.collie.data.CollieRepository
import com.lateapex.collie.data.ConnectionStore
import com.lateapex.collie.data.AuthorityRetirement
import com.lateapex.collie.data.ScopedReadContext
import com.lateapex.collie.domain.CollieOrigin
import com.lateapex.collie.domain.Connection
import com.lateapex.collie.network.ApiFailure
import com.lateapex.collie.network.ApiResult
import com.lateapex.collie.network.CollieApi
import com.lateapex.collie.network.MachineAlerts
import com.lateapex.collie.network.MachineHistoryPoint
import com.lateapex.collie.network.MachineHistoryResponse
import com.lateapex.collie.network.MachineRow
import com.lateapex.collie.network.MachineSample
import com.lateapex.collie.network.MachineSpark
import com.lateapex.collie.network.MachinesResponse
import com.lateapex.collie.network.OriginValidator
import com.lateapex.collie.network.isValid
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MachinesViewModelTest {
    @Test
    fun actualHttpsRepositoryRetirementPreservesEveryGatewayRefusalAndUnknownAccess(): Unit = testOnMain {
        val app = ApplicationProvider.getApplicationContext<CollieApplication>()
        val certificate = okhttp3.tls.HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val trust = okhttp3.tls.HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = okhttp3.mockwebserver.MockWebServer().apply {
            useHttps(okhttp3.tls.HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
            start()
        }
        try {
            val requestId = "machines-original-request"
            val client = com.lateapex.collie.network.CollieApiClient.defaultHttpClient().newBuilder()
                .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
                // A controlled ingress correlation fixture; the real API validates the closed
                // gateway envelope, and the real repository owns the resulting retirement.
                .addInterceptor { chain ->
                    val original = chain.request()
                    val captured = original.tag(ScopedReadContext::class.java)!!
                    chain.proceed(original.newBuilder().tag(com.lateapex.collie.network.NativeIngressEvidence::class.java,
                        com.lateapex.collie.network.NativeIngressEvidence(requestId, captured.key.origin, captured.key.connectionGeneration)).build())
                }.build()
            val actualApi = com.lateapex.collie.network.CollieApiClient(client, kotlinx.serialization.json.Json { ignoreUnknownKeys = true })
            val known = listOf("identity.required", "use_dpop_nonce", "identity.proof_replayed", "identity.expired", "identity.invalid", "identity.sign_in_required", "identity.nonce_unavailable")
                .map { Triple(401, "identity", it) } +
                listOf("device.binding_required", "device.binding_revoked", "device.binding_changed").map { Triple(403, "device", it) }
            for ((status, factor, code) in known + listOf(Triple(401, "unknown", "unknown"), Triple(403, "unknown", "unknown"))) {
                val connection = Connection(CollieOrigin(server.url("/mount/").toString()), "original", "original-pairing")
                val store = TestStore(connection)
                val repo = CollieRepository(actualApi, store, OriginValidator())
                val original = repo.machineReadContext()!!
                val delivered = CompletableDeferred<AuthorityRetirement>()
                val subscription = repo.onReadContextRetired { delivered.complete(it) }
                val vm = MachinesViewModel(app, repo)
                val response = okhttp3.mockwebserver.MockResponse().setResponseCode(status)
                if (factor != "unknown") {
                    response.setHeader("X-Collie-Error-Source", "native_gateway")
                        .setHeader("X-Collie-Response-Source", "native_gateway")
                        .setHeader("X-Collie-Native-Request-Id", requestId)
                        .setHeader("X-Collie-Error-Factor", factor).setHeader("X-Collie-Error-Code", code)
                        .setBody("""{"source":"native_gateway","factor":"$factor","code":"$code","requestId":"$requestId"}""")
                } else response.setBody("unclassified access refusal")
                server.enqueue(response)
                vm.refresh()
                val event = delivered.await()
                advanceUntilIdle()
                val expected = when (factor) {
                    "identity" -> MachineScreenError.GATEWAY_IDENTITY
                    "device" -> MachineScreenError.DEVICE_AUTHORIZATION
                    else -> MachineScreenError.ACCESS_CHECK_FAILED
                }
                assertEquals(code, expected, vm.state.value.error)
                assertNull(vm.state.value.response)
                assertFalse(vm.state.value.loading)
                assertTrue(event.affects(original))
                assertTrue(repo.isActorRetired(original))
                assertEquals(status, event.refusal!!.status)
                assertTrue("Gateway refusal must retain Collie pairing", store.connection.value === connection)
                assertFalse(event.toString().contains("original-pairing"))
                val request = server.takeRequest()
                assertEquals("GET", request.method)
                assertEquals("/mount/api/machines?spark=30", request.path)
                assertEquals("Bearer original-pairing", request.getHeader("Authorization"))
                val count = server.requestCount
                vm.refresh(); advanceUntilIdle()
                assertEquals("Retired original VM cannot reacquire an actor", count, server.requestCount)
                assertEquals("Refusal provenance survives an inert refresh", expected, vm.state.value.error)
                subscription.close()
            }
        } finally { server.shutdown() }
    }

    @Test
    fun heldOldGatewayRefusalCannotRetireOrEraseFreshReplacementMachineFacts(): Unit = testOnMain {
        val app = ApplicationProvider.getApplicationContext<CollieApplication>()
        val pending = CompletableDeferred<ApiResult<MachinesResponse>>()
        val oldConnection = Connection(CollieOrigin("https://machines.fixture/"), "same label", "old")
        val replacement = Connection(oldConnection.origin, "same label", "replacement")
        val store = TestStore(oldConnection)
        val api = fakeApi(app).apply {
            onMachines = { connection, _ ->
                if (connection === oldConnection) withContext(NonCancellable) { pending.await() }
                else ApiResult.Success(MachinesResponse(120_000, listOf(machineRow().copy(name = "Fresh B"))), 200)
            }
        }
        val repo = CollieRepository(api, store, OriginValidator())
        val oldVm = MachinesViewModel(app, repo)
        val original = oldVm.state.value.actor!!
        oldVm.refresh(); runCurrent()
        store.save(replacement)
        val freshVm = MachinesViewModel(app, repo)
        val freshActor = freshVm.state.value.actor!!
        freshVm.refresh(); runCurrent()
        assertEquals("Fresh B", freshVm.state.value.response!!.machines.single().name)
        pending.complete(ApiResult.Failure(ApiFailure.Http(403, "private old response", "device.binding_revoked",
            source = com.lateapex.collie.network.FailureSource.GATEWAY, factor = com.lateapex.collie.network.FailureFactor.DEVICE)))
        advanceUntilIdle()
        assertTrue(repo.isActorRetired(original))
        assertTrue(repo.isCurrent(freshActor))
        assertTrue(store.connection.value === replacement)
        assertEquals("Fresh B", freshVm.state.value.response!!.machines.single().name)
        assertNull(freshVm.state.value.error)
        assertNull(oldVm.state.value.response)
        val reads = api.censusCalls
        oldVm.refresh(); advanceUntilIdle()
        assertEquals(reads, api.censusCalls)
    }

    private class TestStore(initial: Connection) : ConnectionStore {
        override val connection = MutableStateFlow<Connection?>(initial)
        override fun save(connection: Connection) { this.connection.value = connection }
        override fun clear() { connection.value = null }
        override fun compareAndSet(expected: Connection, replacement: Connection): Boolean = connection.compareAndSet(expected, replacement)
    }

    @Test
    fun readsLeadCensusAndMinuteHistoryWithoutInventingMissingMetrics() = testOnMain {
        val app = ApplicationProvider.getApplicationContext<CollieApplication>()
        val sample = MachineSample(cpu = .25, cores = 8, memUsed = 2_000_000, memTotal = 4_000_000, rxBps = 5.0)
        val row = MachineRow("lead", "Desk", true, "reachable", sample, 90_000, MachineAlerts(), emptyList(), MachineSpark(60_000, listOf(.2, null), listOf(.4, null)))
        val unavailablePeer = MachineRow("peer", "Peer", false, "unreachable")
        val api = fakeApi(app).apply {
            onMachines = { _, _ -> ApiResult.Success(MachinesResponse(120_000, listOf(row, unavailablePeer)), 200) }
            onHistory = { _, _, _ -> ApiResult.Success(
                MachineHistoryResponse(180_000, 60_000, listOf(
                    MachineHistoryPoint(60_000, .2, .3, .4, null, 12.0, null),
                    MachineHistoryPoint(120_000, .3, .5, .6, 13.0, 14.0, .7),
                )), 200,
            ) }
        }
        val vm = MachinesViewModel(app, repository(api))
        vm.refresh()
        advanceUntilIdle()
        assertEquals(30, api.sparkRequested)
        assertEquals("A degraded peer stays in the lead census with unavailable resources", listOf("lead", "peer"), vm.state.value.response!!.machines.map { it.id })
        assertTrue(row.resourceSummary().contains("CPU 25%"))
        assertTrue(row.resourceSummary().contains("load unavailable"))
        assertTrue(row.resourceSummary().contains("network received 5 bytes/s / sent unavailable"))
        assertFalse(row.resourceSummary().contains("0 bytes/s"))
        vm.selectMachine("lead")
        advanceUntilIdle()
        assertEquals("lead", api.historyMachine)
        assertNull(api.historySince)
        val points = vm.state.value.history!!.points
        assertEquals(listOf(60_000L, 120_000L), points.map { it.t })
        assertNull(points.first().rxBps)
        assertEquals(.7, points.last().disk!!, 0.0)
    }

    @Test
    fun delayedOriginalCensusCannotPublishAfterLeadActorRetires() = testOnMain {
        val app = ApplicationProvider.getApplicationContext<CollieApplication>()
        val pending = CompletableDeferred<ApiResult<MachinesResponse>>()
        val api = fakeApi(app).apply {
            onMachines = { _, _ -> pending.await() }
        }
        val repository = repository(api)
        val vm = MachinesViewModel(app, repository)
        vm.refresh()
        runCurrent()
        repository.setLeadEpoch(7)
        pending.complete(ApiResult.Success(MachinesResponse(120_000, listOf(machineRow())), 200))
        advanceUntilIdle()
        assertNull(vm.state.value.response)
        assertTrue(vm.state.value.error == MachineScreenError.UNAVAILABLE || vm.state.value.error == MachineScreenError.NOT_CONNECTED)
    }

    @Test
    fun pollingRunsOnlyWhileVisibleAndStopCancelsTheNextRead() = testOnMain {
        val app = ApplicationProvider.getApplicationContext<CollieApplication>()
        val api = fakeApi(app).apply {
            onMachines = { _, _ -> ApiResult.Success(MachinesResponse(120_000, listOf(machineRow())), 200) }
        }
        val vm = MachinesViewModel(app, repository(api))
        vm.startPolling()
        runCurrent()
        assertEquals(1, api.censusCalls)
        vm.stopPolling()
        advanceTimeBy(45_000)
        runCurrent()
        assertEquals("A stopped screen must not keep polling in the background", 1, api.censusCalls)
    }

    @Test
    fun malformedSamplesAndUnknownLeadMemberAreNotRenderedAsZeroOrEmptySuccess() = testOnMain {
        val app = ApplicationProvider.getApplicationContext<CollieApplication>()
        val invalid = machineRow().copy(sample = MachineSample(Double.NaN, 4, 1, 2))
        val api = fakeApi(app).apply {
            onMachines = { _, _ -> ApiResult.Success(MachinesResponse(100, listOf(invalid)), 200) }
        }
        val vm = MachinesViewModel(app, repository(api))
        vm.refresh()
        advanceUntilIdle()
        assertNull(vm.state.value.response)
        assertEquals(MachineScreenError.MALFORMED, vm.state.value.error)
    }

    @Test
    fun historyMergeOrdersPointsReplacesOverlapsAndCapsToTheDay() {
        val end = 100_020_000L
        val previousEnd = end - 30 * 60_000L
        val expired = previousEnd - 86_400_000L + 60_000L
        val overlap = previousEnd - 60_000L
        val old = MachineHistoryResponse(previousEnd, 60_000, listOf(
            MachineHistoryPoint(expired, .1, .2, .3),
            MachineHistoryPoint(overlap, .2, .3, .4),
        ))
        val newer = MachineHistoryResponse(end, 60_000, listOf(
            MachineHistoryPoint(overlap, .8, .9, .7),
            MachineHistoryPoint(end - 60_000L, .3, .4, .5),
        ))
        val merged = mergeMachineHistory(old, newer)
        assertEquals(listOf(overlap, end - 60_000L), merged.points.map { it.t })
        assertEquals(.8, merged.points[0].cpuAvg, 0.0)

        val sparse = mergeMachineHistory(
            MachineHistoryResponse(previousEnd, 60_000, listOf(
                MachineHistoryPoint(expired, .1, .2, .3),
                MachineHistoryPoint(previousEnd - 60_000L, .2, .3, .4),
            )),
            MachineHistoryResponse(end, 60_000, listOf(MachineHistoryPoint(end - 60_000L, .6, .7, .8))),
        )
        assertEquals(listOf(previousEnd - 60_000L, end - 60_000L), sparse.points.map { it.t })
        val empty = mergeMachineHistory(
            MachineHistoryResponse(previousEnd, 60_000, listOf(MachineHistoryPoint(expired, .1, .2, .3))),
            MachineHistoryResponse(end, 60_000, emptyList()),
        )
        assertTrue("Empty rollover cannot retain history older than the response window", empty.points.isEmpty())

        val ts = 200_040_000L
        val tooMany = MachineHistoryResponse(ts, 60_000, (0..1440).map { index ->
            MachineHistoryPoint(ts - 1440 * 60_000L + index * 60_000L, .1, .2, .3)
        })
        assertFalse(tooMany.isValid())
    }

    @Test
    fun rosterLargerThan256IsAcceptedAndPagedWithoutDroppingTheLastRow() {
        val rows = (0 until 257).map { index ->
            MachineRow("host-$index", "Host $index", index == 0, "unreachable")
        }
        assertTrue(MachinesResponse(120_000, rows).isValid())
        val seen = (0 until ((rows.size + MACHINE_PAGE_SIZE - 1) / MACHINE_PAGE_SIZE)).flatMap { page ->
            pageMachineRows(rows, page).rows.map { it.first to it.second.id }
        }
        assertEquals(257, seen.size)
        assertEquals((0 until 257).toList(), seen.map { it.first })
        assertEquals("host-256", pageMachineRows(rows, Int.MAX_VALUE).rows.last().second.id)
    }

    @Test
    fun fixedHistoryWindowIncludesLeadingInternalTrailingAndEmptyGaps() {
        val ts = 2_000_040_000L
        val end = ts - ts % 60_000L
        val start = end - (MAX_HISTORY_WINDOW_POINTS - 1) * 60_000L
        val response = MachineHistoryResponse(ts, 60_000, listOf(
            MachineHistoryPoint(start + 60_000L, .2, .3, .4),
            MachineHistoryPoint(end - 60_000L, .5, .6, .7),
        ))
        val window = buildMachineHistoryWindow(response)
        assertEquals(MAX_HISTORY_WINDOW_POINTS, window.points.size)
        assertEquals(start, window.startAt)
        assertEquals(end, window.endAt)
        assertNull(window.points.first())
        assertNull(window.points.last())
        assertNull(window.points[2])
        val lines = window.tableRows({ "sample:${it.t}" }, { "gap:$it" })
        assertTrue(lines.first().startsWith("gap:$start"))
        assertTrue(lines.last().startsWith("gap:$end"))
        assertEquals("sample:${start + 60_000L}", lines[1])
        val empty = buildMachineHistoryWindow(MachineHistoryResponse(ts, 60_000, emptyList()))
        assertEquals(MAX_HISTORY_WINDOW_POINTS, empty.points.size)
        assertTrue(empty.points.all { it == null })
        assertTrue(empty.tableRows({ "sample" }, { "gap:$it" }).all { it.startsWith("gap:") })
    }

    @Test
    fun accessFailuresKeepPairingGatewayAndUnknownFactorsDistinct() {
        assertEquals(MachineScreenError.PAIRING_REQUIRED, ApiFailure.Http(
            401, "expired", "pairing.expired", source = com.lateapex.collie.network.FailureSource.COLLIE,
            factor = com.lateapex.collie.network.FailureFactor.PAIRING,
        ).toMachineScreenError())
        assertEquals(MachineScreenError.GATEWAY_IDENTITY, ApiFailure.Http(
            403, "gateway sign in", "proxy.identity_required", source = com.lateapex.collie.network.FailureSource.GATEWAY,
            factor = com.lateapex.collie.network.FailureFactor.IDENTITY,
        ).toMachineScreenError())
        assertEquals(MachineScreenError.DEVICE_AUTHORIZATION, ApiFailure.Http(
            403, "device gate", "proxy.device_required", source = com.lateapex.collie.network.FailureSource.GATEWAY,
            factor = com.lateapex.collie.network.FailureFactor.DEVICE,
        ).toMachineScreenError())
        assertEquals(MachineScreenError.ACCESS_CHECK_FAILED, ApiFailure.Http(401, "unclassified", null).toMachineScreenError())
        assertEquals(MachineScreenError.ACCESS_CHECK_FAILED, ApiFailure.Http(
            403, "not pairing", "device.not_authorised", source = com.lateapex.collie.network.FailureSource.COLLIE,
            factor = com.lateapex.collie.network.FailureFactor.DEVICE,
        ).toMachineScreenError())
    }

    @Test
    fun removedMachineDuringHistoryReadIsRemovedFromTheCensus() = testOnMain {
        val app = ApplicationProvider.getApplicationContext<CollieApplication>()
        val api = fakeApi(app).apply {
            onMachines = { _, _ -> ApiResult.Success(MachinesResponse(120_000, listOf(machineRow())), 200) }
            onHistory = { _, _, _ -> ApiResult.Failure(ApiFailure.Http(404, "unknown", "host.unknown")) }
        }
        val vm = MachinesViewModel(app, repository(api))
        vm.refresh()
        advanceUntilIdle()
        vm.selectMachine("lead")
        advanceUntilIdle()
        assertTrue(vm.state.value.response!!.machines.isEmpty())
        assertNull(vm.state.value.selectedMachineId)
        assertEquals(MachineScreenError.UNKNOWN_MACHINE, vm.state.value.historyError)
    }

    private fun testOnMain(body: suspend TestScope.() -> Unit) {
        val dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        try {
            kotlinx.coroutines.test.runTest(dispatcher) { body() }
        } finally { Dispatchers.resetMain() }
    }

    private fun repository(api: CollieApi): CollieRepository {
        val store = object : ConnectionStore {
            override val connection = MutableStateFlow<Connection?>(Connection(CollieOrigin("https://machines.fixture/"), "test", "paired"))
            override fun save(connection: Connection) { this.connection.value = connection }
            override fun clear() { connection.value = null }
        }
        return CollieRepository(api, store, OriginValidator())
    }

    private fun fakeApi(app: CollieApplication): FakeApi = FakeApi(app.container.api)

    private companion object {
        fun machineRow() = MachineRow("lead", "Desk", true, "reachable", MachineSample(.2, 4, 1, 2), 90, MachineAlerts(), emptyList())
    }

    private class FakeApi(delegate: CollieApi) : CollieApi by delegate {
        var censusCalls = 0
        var sparkRequested: Int? = null
        var historyMachine: String? = null
        var historySince: Long? = null
        var onMachines: suspend (Connection, Int) -> ApiResult<MachinesResponse> = { _, _ ->
            ApiResult.Success(MachinesResponse(120_000, listOf(machineRow())), 200)
        }
        var onHistory: suspend (Connection, String, Long?) -> ApiResult<MachineHistoryResponse> = { _, _, _ ->
            ApiResult.Success(MachineHistoryResponse(120_000, 60_000, emptyList()), 200)
        }
        override suspend fun machines(connection: Connection, spark: Int): ApiResult<MachinesResponse> {
            censusCalls++
            sparkRequested = spark
            return onMachines(connection, spark)
        }
        override suspend fun machineHistory(connection: Connection, machineId: String, since: Long?): ApiResult<MachineHistoryResponse> {
            historyMachine = machineId
            historySince = since
            return onHistory(connection, machineId, since)
        }
    }
}
