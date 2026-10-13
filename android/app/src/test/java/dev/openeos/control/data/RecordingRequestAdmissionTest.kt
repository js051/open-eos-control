package dev.openeos.control.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class RecordingRequestAdmissionTest {
    /** Hold the actual evidence monitor until the production HTTP worker blocks at admission.
     * It has already passed ensureActive; retiring at this exact boundary must still send zero REC.
     */
    @Test fun retirementBetweenCancellationCheckAndAdmissionPreventsEveryRecordingTransport() = runBlocking {
        for (transport in listOf("direct-json", "direct-ok", "bridge-json")) {
            val server = MockWebServer()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = MockResponse()
                    .setHeader("Content-Type", "application/json").setBody(when (request.path) {
                        "/ccapi" -> """{"ver110":[{"path":"/shooting/control/recbutton","post":true}]}"""
                        "/health" -> """{"ok":true,"service":"open-eos-control-bridge","version":"0.13.0"}"""
                        "/v1/session" -> """{"id":"synthetic-admission","engine":"ccapi"}"""
                        else -> "{}"
                    })
            }
            server.start()
            val http = OkHttpClient.Builder().retryOnConnectionFailure(false).callTimeout(2, TimeUnit.SECONDS).build()
            val direct = CcapiClient(server.url("/").toString(), treatAsSimulator = transport == "direct-json", httpClient = http)
            val bridge = DesktopBridgeClient(server.url("/").toString(), httpClient = http)
            try {
                if (transport == "bridge-json") bridge.initialize() else direct.initialize()
                val before = server.requestCount
                val evidence = RecordingRequestEvidence(RecordingCommand.START)
                val worker = AtomicReference<Thread?>()
                val started = CountDownLatch(1)
                val result = synchronized(evidence) {
                    val operation = async(Dispatchers.IO) {
                        worker.set(Thread.currentThread())
                        started.countDown()
                        runCatching {
                            withContext(evidence) {
                                if (transport == "bridge-json") bridge.startRecording() else direct.startRecording()
                            }
                        }.exceptionOrNull()
                    }
                    assertTrue(transport, started.await(2, TimeUnit.SECONDS))
                    assertTrue("$transport did not reach the production REC admission boundary",
                        awaitBlockedOnEvidence(requireNotNull(worker.get()), evidence))
                    assertFalse(evidence.attempted)
                    assertFalse("Retirement before admission must not claim a sent request", evidence.retire())
                    operation
                }
                assertTrue("$transport must propagate cancellation", result.await() is CancellationException)
                assertFalse(evidence.attempted)
                assertFalse(evidence.unresolved)
                assertEquals("$transport must send zero REC after retirement", before, server.requestCount)
            } finally {
                direct.close()
                bridge.close()
                server.shutdown()
                http.connectionPool.evictAll()
                http.dispatcher.executorService.shutdown()
            }
        }
    }

    @Test fun retirementAfterAdmissionRetainsResponsibilityWithoutAdmittingAnotherRequest() {
        val evidence = RecordingRequestEvidence(RecordingCommand.START)
        evidence.beginRequest()
        assertTrue(evidence.retire())
        assertTrue(evidence.unresolved)
        assertTrue(runCatching { evidence.beginRequest() }.exceptionOrNull() is CancellationException)
        assertTrue(evidence.unresolved)
    }

    private fun awaitBlockedOnEvidence(worker: Thread, evidence: RecordingRequestEvidence): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (worker.state == Thread.State.BLOCKED && worker.stackTrace.any {
                it.className == evidence.javaClass.name && it.methodName == "beginRequest"
            }) return true
            Thread.sleep(1)
        }
        return false
    }
}
