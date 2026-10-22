package dev.openeos.control.data

import kotlinx.coroutines.runBlocking
import dev.openeos.control.ui.ShootingSettingAttempt
import dev.openeos.control.ui.ShootingSettingDraft
import dev.openeos.control.ui.ShootingSettingOwner
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Native JVM/MockWebServer pipeline tests. The cloud pure-Kotlin harness does not execute these. */
class ShootingSettingTransportTest {
    private fun json(text: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", "application/json").setBody(text)
    private val discovery = """{"ver100":[{"path":"/shooting/settings","get":true},{"path":"/shooting/settings/iso","get":true,"put":true}]}"""
    private val aggregate = """{"iso":{"value":"400","ability":["100","400","800"]}}"""
    private val setting = """{"value":"400","ability":["100","400","800"]}"""

    @Test fun directWriteRetainsResponseThenFreshMismatchAndNeverReadsStatusOrMedia() = runBlocking {
        val requests = CopyOnWriteArrayList<String>()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() { override fun dispatch(r: RecordedRequest): MockResponse {
                requests += "${r.method} ${r.path}"
                return when {
                    r.path == "/ccapi" -> json(discovery)
                    r.path == "/ccapi/ver100/shooting/settings" -> json(aggregate)
                    r.method == "PUT" -> MockResponse().setResponseCode(204)
                    r.path == "/ccapi/ver100/shooting/settings/iso" -> json(setting)
                    else -> MockResponse().setResponseCode(404)
                }
            } }
            server.start()
            val client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)
            client.initialize(); client.capabilities(); requests.clear()
            val request = ShootingSettingRequest("iso", "800"); val evidence = ShootingSettingEvidence(request)
            val access = client.shootingSettingAccess(request)
            access.write(request, evidence)
            assertEquals(ShootingSettingWriteEvidence.RESPONSE_RECEIVED, evidence.snapshot().writeEvidence)
            assertEquals("400", access.read(request).value)
            assertEquals(listOf("GET /ccapi/ver100/shooting/settings/iso", "PUT /ccapi/ver100/shooting/settings/iso",
                "GET /ccapi/ver100/shooting/settings/iso"), requests.toList())
        }
    }
    @Test fun changedFreshAbilityFailsBeforeDispatch() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(json(discovery)); server.enqueue(json(aggregate))
            server.enqueue(json("""{"value":"400","ability":["100","400"]}""")); server.start()
            val client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)
            client.initialize(); client.capabilities()
            val request = ShootingSettingRequest("iso", "800"); val evidence = ShootingSettingEvidence(request)
            assertTrue(runCatching { client.shootingSettingAccess(request).write(request, evidence) }.isFailure)
            assertEquals(ShootingSettingWriteEvidence.NOT_SENT, evidence.snapshot().writeEvidence)
            assertEquals(3, server.requestCount)
        }
    }
    @Test fun lostResponseHasExactlyOneMutationAndNoAliasFallback() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(json(discovery)); server.enqueue(json(aggregate)); server.enqueue(json(setting))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)); server.start()
            val client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)
            client.initialize(); client.capabilities()
            val request = ShootingSettingRequest("iso", "800"); val evidence = ShootingSettingEvidence(request)
            assertTrue(runCatching { client.shootingSettingAccess(request).write(request, evidence) }.isFailure)
            assertEquals(ShootingSettingWriteEvidence.UNKNOWN, evidence.snapshot().writeEvidence)
            assertEquals(4, server.requestCount)
            val requests = (0 until 4).map { server.takeRequest() }
            assertEquals(1, requests.count { it.method == "PUT" })
        }
    }
    @Test fun partialFreshReadCannotEstablishObservation() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(json(discovery)); server.enqueue(json(aggregate))
            server.enqueue(json(setting).setResponseCode(206).setHeader("Content-Range", "bytes 0-10/100")); server.start()
            val client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)
            client.initialize(); client.capabilities()
            val request = ShootingSettingRequest("iso", "800")
            assertTrue(runCatching { client.shootingSettingAccess(request).read(request) }.isFailure)
            assertEquals(3, server.requestCount)
        }
    }
    @Test fun structuredWriteRejectsMalformedFreshSiblingBeforeMutation() = runBlocking {
        val discovery = """{"ver100":[{"path":"/shooting/settings","get":true},{"path":"/shooting/settings/stillimagequality","put":true}]}"""
        val valid = """{"stillimagequality":{"value":{"raw":"raw","jpeg":"fine"},"ability":{"raw":["none","raw"],"jpeg":["none","fine"]}}}"""
        val invalid = valid.replace("\"jpeg\":\"fine\"", "\"jpeg\":17")
        MockWebServer().use { server ->
            server.enqueue(json(discovery)); server.enqueue(json(valid)); server.enqueue(json(valid)); server.enqueue(json(invalid)); server.start()
            val client = CcapiClient(server.url("/").toString(), treatAsSimulator = false)
            client.initialize(); client.capabilities()
            val request = ShootingSettingRequest("stillimagequality.raw", "none"); val evidence = ShootingSettingEvidence(request)
            assertTrue(runCatching { client.shootingSettingAccess(request).write(request, evidence) }.isFailure)
            assertEquals(ShootingSettingWriteEvidence.NOT_SENT, evidence.snapshot().writeEvidence)
            assertEquals(4, server.requestCount)
        }
    }
    private suspend fun initializedBridge(server: MockWebServer): DesktopBridgeClient {
        server.enqueue(json("""{"ok":true,"service":"open-eos-control-bridge","version":"0.13.0"}"""))
        server.enqueue(json("""{"id":"SYNTHETIC","engine":"ccapi","camera":{"engine":"ccapi","model":"Synthetic"}}"""))
        return DesktopBridgeClient(server.url("/").toString()).also { it.initialize() }
    }
    @Test fun bridgeReadFailureRetainsResponseAndDoesNotTryLegacyMutation() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val client = initializedBridge(server)
            server.enqueue(json("""{"version":1,"engine":"ccapi","key":"iso","requestedValue":"800","writeEvidence":"response-received","readbackEvidence":"unavailable","setting":null}"""))
            val request = ShootingSettingRequest("iso", "800"); val evidence = ShootingSettingEvidence(request)
            client.writeShootingSetting(request, evidence)
            assertEquals(ShootingSettingWriteEvidence.RESPONSE_RECEIVED, evidence.snapshot().writeEvidence)
            assertEquals(ShootingSettingReadEvidence.UNAVAILABLE, evidence.snapshot().readbackEvidence)
            assertEquals(3, server.requestCount)
            repeat(2) { server.takeRequest() }
            assertEquals("/v1/session/SYNTHETIC/settings/iso/outcome", server.takeRequest().path)
        }
    }
    @Test fun oldBridge404NeverFallsBackToLegacyPost() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val client = initializedBridge(server); server.enqueue(MockResponse().setResponseCode(404))
            val request = ShootingSettingRequest("iso", "800"); val evidence = ShootingSettingEvidence(request)
            assertTrue(runCatching { client.writeShootingSetting(request, evidence) }.isFailure)
            assertEquals(ShootingSettingWriteEvidence.UNKNOWN, evidence.snapshot().writeEvidence)
            assertEquals(3, server.requestCount)
        }
    }
    @Test fun duplicateBridgeBaseCannotPromoteUnknownCommandEvidence() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val client = initializedBridge(server)
            server.enqueue(json("""{"version":1,"version":1,"engine":"ccapi","key":"iso","requestedValue":"800","writeEvidence":"response-received","readbackEvidence":"unavailable","setting":null}"""))
            val request = ShootingSettingRequest("iso", "800"); val evidence = ShootingSettingEvidence(request)
            assertTrue(runCatching { client.writeShootingSetting(request, evidence) }.isFailure)
            assertEquals(ShootingSettingWriteEvidence.UNKNOWN, evidence.snapshot().writeEvidence)
        }
    }

    @Test fun cardFreshUnknownAbilityBlocksBothKeysBeforeAnyPut() = runBlocking {
        // Complete baseline PUT responses are queued: a RED is a contract assertion, not a missing stub.
        val results = cardCases.map { cardPrewriteWitness(it, """{"value":"card1","ability":["card1","card2","card3"]}""") }
        results.forEach { result ->
            assertEquals("CARD-FRESH-UNKNOWN-NOT-SENT:${result.card.key}", ShootingSettingWriteEvidence.NOT_SENT, result.snapshot.writeEvidence)
            assertCardPrewriteRejection(result)
        }
    }

    @Test fun cardFreshSingleChoiceBlocksBothKeysBeforeAnyPut() = runBlocking {
        val results = cardCases.map { cardPrewriteWitness(it, """{"value":"card2","ability":["card2"]}""") }
        results.forEach { result ->
            assertEquals("CARD-FRESH-SINGLE-NOT-SENT:${result.card.key}", ShootingSettingWriteEvidence.NOT_SENT, result.snapshot.writeEvidence)
            assertCardPrewriteRejection(result)
        }
    }

    @Test fun cardAcknowledgedInvalidReadbackStaysUnavailableForBothKeys() = runBlocking {
        val results = cardCases.map { cardWorkflowWitness(it, cardUnknownReadback) }
        results.forEach { result ->
            assertEquals(ShootingSettingWriteEvidence.RESPONSE_RECEIVED, result.snapshot.writeEvidence)
            assertEquals("CARD-ACK-INVALID-UNAVAILABLE:${result.card.key}", ShootingSettingReadEvidence.UNAVAILABLE, result.snapshot.readbackEvidence)
            assertNull(result.observed)
            assertNull(result.snapshot.observedValue)
            assertTrue(result.publishedValues.isEmpty())
            assertNull(result.writeFailure)
            assertCardValidationFailure(result.readFailure)
            assertCardWorkflowRequests(result, "card2")
        }
    }

    @Test fun cardLostAckInvalidManualReadStaysUnknownForBothKeys() = runBlocking {
        val results = cardCases.map { cardWorkflowWitness(it, cardUnknownReadback, lostAck = true) }
        results.forEach { result ->
            assertCardLostAck(result)
            assertEquals("CARD-UNKNOWN-INVALID-UNAVAILABLE:${result.card.key}", ShootingSettingReadEvidence.UNAVAILABLE, result.snapshot.readbackEvidence)
            assertNull(result.observed)
            assertNull(result.snapshot.observedValue)
            assertTrue(result.publishedValues.isEmpty())
            assertCardValidationFailure(result.readFailure)
            assertCardWorkflowRequests(result, "card2")
        }
    }

    @Test fun cardStillValidRawValueUsesExactlyOnePut() = runBlocking {
        val result = cardWorkflowWitness(cardCases[0], cardBody("card2"))
        assertEquals(ShootingSettingWriteEvidence.RESPONSE_RECEIVED, result.snapshot.writeEvidence)
        assertEquals(ShootingSettingReadEvidence.MATCHED, result.snapshot.readbackEvidence)
        assertEquals("card2", result.observed?.value)
        assertNull(result.writeFailure)
        assertNull(result.readFailure)
        assertCardWorkflowRequests(result, "card2")
    }

    @Test fun cardMovieNoneRawValueUsesExactlyOnePut() = runBlocking {
        val result = cardWorkflowWitness(cardCases[1], cardBody("none"), requested = "none")
        assertEquals(ShootingSettingWriteEvidence.RESPONSE_RECEIVED, result.snapshot.writeEvidence)
        assertEquals(ShootingSettingReadEvidence.MATCHED, result.snapshot.readbackEvidence)
        assertEquals("none", result.observed?.value)
        assertNull(result.writeFailure)
        assertNull(result.readFailure)
        assertCardWorkflowRequests(result, "none")
    }

    @Test fun cardLostAckValidManualReadNeverProvesResponseOrReplays() = runBlocking {
        val results = cardCases.map { cardWorkflowWitness(it, cardBody("card2"), lostAck = true) }
        results.forEach { result ->
            assertCardLostAck(result)
            assertEquals(ShootingSettingReadEvidence.MATCHED, result.snapshot.readbackEvidence)
            assertEquals("card2", result.observed?.value)
            assertEquals("card2", result.snapshot.observedValue)
            assertNull(result.readFailure)
            assertCardWorkflowRequests(result, "card2")
        }
    }

    @Test fun cardMalformedRawTypesDuplicatesAndMissingCurrentBlockBothKeys() = runBlocking {
        val malformed = listOf(
            """{"value":2,"ability":["card1","card2"]}""",
            """{"value":"card1","ability":["card1",2,"card2"]}""",
            """{"value":"card1","ability":["card1","card2","card2"]}""",
            """{"value":"none","ability":["card1","card2"]}""",
            """{"ability":["card1","card2"]}""",
        )
        val results = cardCases.flatMap { card -> malformed.map { cardPrewriteWitness(card, it) } }
        results.forEach { result ->
            assertEquals(ShootingSettingWriteEvidence.NOT_SENT, result.snapshot.writeEvidence)
            assertCardPrewriteRejection(result)
        }
    }

    private data class CardCase(val key: String, val suffix: String) {
        val path: String get() = "/ccapi/ver100$suffix"
        val setup: List<String> get() = listOf("GET /ccapi", "GET /ccapi/ver100/shooting/settings", "GET $path")
    }
    private val cardCases = listOf(
        CardCase("cardselectionstillimage", "/functions/cardselection/stillimage"),
        CardCase("cardselectionmovie", "/functions/cardselection/movie"),
    )
    private val cardUnknownReadback = """{"value":"card2","ability":["card1","card2","card3"]}"""
    private fun cardBody(value: String) = """{"value":"$value","ability":["none","card1","card2"]}"""
    private data class CardRequest(val method: String?, val path: String?, val body: String) {
        val route: String get() = "$method $path"
    }
    private data class CardPrewriteResult(val card: CardCase, val snapshot: ShootingSettingSnapshot,
        val failure: Exception?, val requests: List<CardRequest>)
    private data class CardWorkflowResult(val card: CardCase, val snapshot: ShootingSettingSnapshot,
        val beforeRecovery: ShootingSettingSnapshot?, val beforeRecoveryCount: Int?,
        val observed: ShootingSettingObservation?, val writeFailure: Exception?, val readFailure: Exception?,
        val requests: List<CardRequest>, val publishedValues: List<String>)

    private fun enqueueCardSetup(server: MockWebServer, card: CardCase) {
        server.enqueue(json("""{"ver100":[{"path":"/shooting/settings","get":true},{"path":"${card.suffix}","get":true,"put":true}]}"""))
        server.enqueue(json("{}"))
        server.enqueue(json(cardBody("card1")))
    }
    private suspend fun initializedCardClient(server: MockWebServer, card: CardCase): CcapiClient {
        val http = OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS).build()
        val client = CcapiClient(server.url("/").toString(), httpClient = http, treatAsSimulator = false)
        client.initialize()
        val control = client.capabilities().advancedSettings.single { it.key == card.key }
        assertEquals(listOf("none", "card1", "card2"), control.values)
        assertEquals("card1", control.value)
        return client
    }
    private fun recordedCardRequests(server: MockWebServer): List<CardRequest> = (0 until server.requestCount).map {
        val request = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)) { "Missing recorded card-selection request." }
        CardRequest(request.method, request.path, request.body.readUtf8())
    }
    private suspend fun cardPrewriteWitness(card: CardCase, fresh: String): CardPrewriteResult = MockWebServer().use { server ->
        enqueueCardSetup(server, card)
        server.enqueue(json(fresh))
        server.enqueue(MockResponse().setResponseCode(204)) // Fully provision the unfixed path, without accepting its dispatch.
        server.start()
        val client = initializedCardClient(server, card)
        val request = ShootingSettingRequest(card.key, "card2")
        val evidence = ShootingSettingEvidence(request)
        var failure: Exception? = null
        try { client.shootingSettingAccess(request).write(request, evidence) } catch (error: Exception) { failure = error }
        CardPrewriteResult(card, evidence.snapshot(), failure, recordedCardRequests(server))
    }
    private fun assertCardValidationFailure(failure: Exception?) {
        // Both baseline generic validation and the strict card validator have explicit local error shapes.
        val known = failure is IllegalArgumentException && failure.message in setOf(
            "The camera returned an invalid card selection setting.", "Required value was null.",
            "The camera did not return a valid current setting.") ||
            failure is IllegalStateException && failure.message == "Invalid setting ability."
        assertTrue("Expected local card validation failure, got $failure", known)
    }
    private fun assertCardPrewriteRejection(result: CardPrewriteResult) {
        assertCardValidationFailure(result.failure)
        assertEquals(ShootingSettingReadEvidence.NOT_READ, result.snapshot.readbackEvidence)
        assertNull(result.snapshot.observedValue)
        assertEquals(0, result.requests.count { it.method == "PUT" })
        assertEquals(result.card.setup + "GET ${result.card.path}", result.requests.map { it.route })
    }
    private suspend fun cardWorkflowWitness(card: CardCase, readback: String, lostAck: Boolean = false,
        requested: String = "card2"): CardWorkflowResult = MockWebServer().use { server ->
        enqueueCardSetup(server, card)
        server.enqueue(json(cardBody("card1")))
        server.enqueue(if (lostAck) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
            else MockResponse().setResponseCode(204))
        server.enqueue(json(readback))
        server.start()
        val client = initializedCardClient(server, card)
        val request = ShootingSettingRequest(card.key, requested)
        val actual = client.shootingSettingAccess(request)
        var writeFailure: Exception? = null
        var readFailure: Exception? = null
        // Transparent instrumentation: real client closures remain the only source of values and errors.
        val access = ShootingSettingAccess(actual.origin, write = { frozen, evidence ->
            try { actual.write(frozen, evidence) } catch (error: Exception) { writeFailure = error; throw error }
        }, read = { frozen ->
            try { actual.read(frozen) } catch (error: Exception) { readFailure = error; throw error }
        })
        val publishedValues = mutableListOf<String>()
        val draft = ShootingSettingDraft(ShootingSettingOwner(client, 1), "synthetic-card-editor", 1, request)
        val attempt = ShootingSettingAttempt(draft, access, { true }, readTimeoutMillis = 3_000) { receipt ->
            receipt.evidence.observedValue?.let { publishedValues += it }
        }
        val first = attempt.run()
        val beforeRecovery = if (lostAck) attempt.snapshot().evidence else null
        val beforeRecoveryCount = if (lostAck) server.requestCount else null
        if (lostAck) assertNull("Lost ACK cannot automatically produce an observation.", first)
        val observed = if (lostAck) attempt.recover() else first
        CardWorkflowResult(card, attempt.snapshot().evidence, beforeRecovery, beforeRecoveryCount, observed,
            writeFailure, readFailure, recordedCardRequests(server), publishedValues.toList())
    }
    private fun assertCardLostAck(result: CardWorkflowResult) {
        assertTrue("Expected synthetic lost transport response, got ${result.writeFailure}",
            result.writeFailure is IOException && result.writeFailure !is InterruptedIOException)
        assertEquals(ShootingSettingWriteEvidence.UNKNOWN, result.beforeRecovery?.writeEvidence)
        assertEquals(ShootingSettingReadEvidence.UNAVAILABLE, result.beforeRecovery?.readbackEvidence)
        assertNull(result.beforeRecovery?.observedValue)
        assertEquals(5, result.beforeRecoveryCount)
        assertEquals(ShootingSettingWriteEvidence.UNKNOWN, result.snapshot.writeEvidence)
    }
    private fun assertCardWorkflowRequests(result: CardWorkflowResult, requested: String) {
        assertEquals(result.card.setup + listOf("GET ${result.card.path}", "PUT ${result.card.path}", "GET ${result.card.path}"),
            result.requests.map { it.route })
        val writes = result.requests.filter { it.method == "PUT" }
        assertEquals(1, writes.size)
        val payload = JSONObject(writes.single().body)
        assertEquals(setOf("value"), payload.keys().asSequence().toSet())
        assertEquals(requested, payload.get("value"))
        assertTrue(result.requests.filter { it.method == "GET" }.all { it.body.isEmpty() })
    }
}
