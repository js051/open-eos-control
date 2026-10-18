package dev.openeos.control.data

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class RecordingLifecycleBridgeTest {
    private fun observation(value: String = "null", source: String = "null", evidence: String = "unavailable") =
        """{"version":1,"engine":"ccapi","observationEvidence":"$evidence","recording":$value,"source":$source}"""
    private fun outcome(observation: String = observation()) =
        """{"version":1,"engine":"ccapi","operation":"stop","responseEvidence":"response-received","observation":$observation}"""
    private fun json(value: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(value)

    @Test fun stopOutcomeAndExplicitRecoveryNeverFetchGeneralStatusOrReplayCommands() = runBlocking {
        val paths = CopyOnWriteArrayList<String>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                paths += "${request.method} ${request.path}"
                return when ("${request.method} ${request.path}") {
                    "GET /health" -> json("""{"ok":true,"service":"open-eos-control-bridge","version":"0.13.0"}""")
                    "POST /v1/session" -> json("""{"id":"synthetic-recording-lifecycle","engine":"ccapi","camera":{"engine":"ccapi"}}""")
                    "POST /v1/session/synthetic-recording-lifecycle/recording/stop/outcome" -> json(outcome())
                    "GET /v1/session/synthetic-recording-lifecycle/recording/observation" -> json(observation("false", "\"ccapi-simulator\"", "observed"))
                    "DELETE /v1/session/synthetic-recording-lifecycle" -> MockResponse().setResponseCode(204)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val http = OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS).build()
        val client = DesktopBridgeClient(server.url("/").toString(), httpClient = http)
        try {
            client.initialize(); paths.clear()
            val evidence = RecordingRequestEvidence(RecordingCommand.STOP)
            val status = withContext(evidence) { client.recordingLifecycleCommand(RecordingCommand.STOP) }
            assertEquals(RecordingResponseEvidence.RESPONSE_RECEIVED, evidence.responseEvidence())
            assertNull(status.recordingObservation)
            val fresh = client.recordingObservation()
            assertEquals(false, fresh?.recording)
            assertEquals(RecordingResponseEvidence.RESPONSE_RECEIVED, evidence.responseEvidence())
            assertEquals(listOf("POST /v1/session/synthetic-recording-lifecycle/recording/stop/outcome",
                "GET /v1/session/synthetic-recording-lifecycle/recording/observation"), paths.toList())
        } finally { client.close(); server.shutdown(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
    }

    @Test fun malformedAndRedirectedOutcomesCannotBecomeAckOrFallbackToLegacyPost() = runBlocking {
        for (response in listOf(json(outcome().replace("\"version\":1", "\"version\":1,\"version\":1")),
            json(outcome(observation("false", "\"gphoto-config\"", "observed"))),
            json(outcome().replace("\"operation\":\"stop\"", "\"operation\":\"start\"")),
            json(outcome()).setResponseCode(206), MockResponse().setResponseCode(307).setHeader("Location", "/legacy"))) {
            val paths = CopyOnWriteArrayList<String>()
            val server = MockWebServer()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when ("${request.method} ${request.path}") {
                    "GET /health" -> json("""{"ok":true,"service":"open-eos-control-bridge","version":"0.13.0"}""")
                    "POST /v1/session" -> json("""{"id":"synthetic-recording-lifecycle","engine":"ccapi","camera":{"engine":"ccapi"}}""")
                    "POST /v1/session/synthetic-recording-lifecycle/recording/stop/outcome" -> response
                    "DELETE /v1/session/synthetic-recording-lifecycle" -> MockResponse().setResponseCode(204)
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val http = OkHttpClient.Builder().callTimeout(2, TimeUnit.SECONDS).build()
            val client = DesktopBridgeClient(server.url("/").toString(), httpClient = http)
            try {
                client.initialize()
                val evidence = RecordingRequestEvidence(RecordingCommand.STOP)
                assertTrue(runCatching { withContext(evidence) { client.recordingLifecycleCommand(RecordingCommand.STOP) } }.isFailure)
                assertNotEquals(RecordingResponseEvidence.RESPONSE_RECEIVED, evidence.responseEvidence())
                assertTrue(RecordingReceipt(RecordingCommand.STOP, evidence.responseEvidence(), priorStopRequired = true).stopRequired)
                assertEquals(3, server.requestCount)
                repeat(3) { paths += requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)).path.orEmpty() }
                assertEquals("/v1/session/synthetic-recording-lifecycle/recording/stop/outcome", paths.last())
            } finally { client.close(); server.shutdown(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
        }
    }
}
