package dev.openeos.control.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

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
}
