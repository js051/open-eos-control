package dev.openeos.control.data

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CaptureConfigReceiptTest {
    private fun request(directory: Boolean) = if (directory)
        CaptureConfigurationRequest(CaptureConfigurationOperation.CREATE_DIRECTORY, "ABCDE") else
        CaptureConfigurationRequest(CaptureConfigurationOperation.SET_FILE_NAMING, "B2", CameraFileNamingField.MOVIE_INDEX)
    private fun response(directory: Boolean, code: Int = 200, headers: Map<String, String> = emptyMap()) =
        Response(code, Body(JSONObject().put(if (directory) "directoryname" else "index", if (directory) "ABCDE" else "B2").toString()), headers)
    private suspend fun write(client: Client, directory: Boolean, evidence: CaptureConfigurationEvidence) {
        if (directory) client.createDirectory("ABCDE", evidence) else client.setFileNaming(CameraFileNamingField.MOVIE_INDEX, "B2", evidence)
    }
    private fun partial(directory: Boolean, code: Int, headers: Map<String, String>) = runBlocking {
        for (real in if (directory) listOf(true, false) else listOf(true)) {
        val reply = response(directory, code, headers)
        val client = Client(reply, naming(), isRealCamera = real)
        val evidence = CaptureConfigurationEvidence(request(directory))
        val failure = runCatching { write(client, directory, evidence) }.exceptionOrNull()
        assertEquals("Partial capture reply must not acknowledge", CaptureConfigurationWriteEvidence.UNKNOWN, evidence.snapshot().writeEvidence)
        assertTrue(failure is CaptureConfigurationException)
        assertEquals(CaptureConfigurationReadEvidence.NOT_READ, evidence.snapshot().readbackEvidence)
        assertNull(evidence.snapshot().acknowledgedValue)
        assertEquals(0, client.reads)
        assertEquals(1, client.requests.size)
        assertEquals(listOf(true), client.bounded)
        val sent = client.requests.single()
        assertEquals(if (directory) "POST" else "PUT", sent.method)
        assertEquals(if (directory) { if (real) "/ccapi/ver100/functions/directory/createdirectory" else "/ccapi/directory" }
            else "/ccapi/ver100/functions/filename/movies/index", sent.url.encodedPath)
        assertEquals(if (directory) "{\"directoryname\":\"ABCDE\"}" else "{\"index\":\"B2\"}", sent.body)
        assertTrue(reply.closed && requireNotNull(reply.body).closed)
        assertEquals(1, client.executions)
        }
    }
    @Test fun directory206NeverAcknowledges() = partial(true, 206, emptyMap())
    @Test fun directoryRangeNeverAcknowledges() = partial(true, 200, mapOf("Content-Range" to "bytes 0-24/80"))
    @Test fun directoryEmptyRangeNeverAcknowledges() = partial(true, 200, mapOf("cOnTeNt-RaNgE" to ""))
    @Test fun naming206NeverAcknowledges() = partial(false, 206, emptyMap())
    @Test fun namingRangeNeverAcknowledges() = partial(false, 200, mapOf("Content-Range" to "bytes 0-13/80"))
    @Test fun namingEmptyRangeNeverAcknowledges() = partial(false, 200, mapOf("cOnTeNt-RaNgE" to ""))
    @Test fun completeReceiptsPreserveStatusesAndDirectorySuffixSemantics() = runBlocking {
        for (directory in listOf(true, false)) for (code in listOf(200, 201, 202)) {
            val client = Client(response(directory, code)); val evidence = CaptureConfigurationEvidence(request(directory))
            write(client, directory, evidence)
            assertEquals(CaptureConfigurationWriteEvidence.ACKNOWLEDGED, evidence.snapshot().writeEvidence)
            assertEquals(if (directory) "ABCDE" else "B2", evidence.snapshot().acknowledgedValue)
            assertEquals(if (directory) CaptureConfigurationReadEvidence.REFRESHED else CaptureConfigurationReadEvidence.MATCHED,
                evidence.snapshot().readbackEvidence)
            assertEquals(if (directory) "100EOSXX" else "B2", evidence.snapshot().observedValue)
            assertEquals(1, client.requests.size); assertEquals(1, client.reads)
        }
    }
    @Test fun completeNamingMismatchStillRetainsAckAndFails() = runBlocking {
        val client = Client(response(false), naming()); val evidence = CaptureConfigurationEvidence(request(false))
        assertTrue(runCatching { write(client, false, evidence) }.exceptionOrNull() is CaptureConfigurationException)
        assertEquals(CaptureConfigurationWriteEvidence.ACKNOWLEDGED, evidence.snapshot().writeEvidence)
        assertEquals(CaptureConfigurationReadEvidence.MISMATCH, evidence.snapshot().readbackEvidence)
        assertEquals("A_", evidence.snapshot().observedValue)
        assertEquals(1, client.requests.size)
    }
    @Test fun completeAckReadFailureRemainsUnavailableWithoutReplay() = runBlocking {
        for (directory in listOf(true, false)) {
            val client = Client(response(directory)); client.readFails = true
            val evidence = CaptureConfigurationEvidence(request(directory))
            runCatching { write(client, directory, evidence) }
            assertEquals(CaptureConfigurationWriteEvidence.ACKNOWLEDGED, evidence.snapshot().writeEvidence)
            assertEquals(CaptureConfigurationReadEvidence.UNAVAILABLE, evidence.snapshot().readbackEvidence)
            assertEquals(1, client.requests.size)
        }
    }
    @Test fun jsonAndLengthValidationStillPrecedeAck() = runBlocking {
        val invalid = listOf("{\"directoryname\":\"ABCDE\",\"directoryname\":\"OTHER\"}",
            "{\"directoryname\":\"ABCDE\"} {}", "{\"directoryname\":\"ABCDE\",\"ignored\":NaN}", "")
        for (text in invalid) {
            val evidence = CaptureConfigurationEvidence(request(true)); val client = Client(Response(200, Body(text)))
            assertTrue(runCatching { write(client, true, evidence) }.isFailure)
            assertEquals(CaptureConfigurationWriteEvidence.UNKNOWN, evidence.snapshot().writeEvidence)
            assertEquals(0, client.reads)
        }
        val reply = response(true)
        val client = Client(Response(200, Body(requireNotNull(reply.body).text, 500)))
        val evidence = CaptureConfigurationEvidence(request(true))
        assertTrue(runCatching { write(client, true, evidence) }.isFailure)
        assertEquals(CaptureConfigurationWriteEvidence.UNKNOWN, evidence.snapshot().writeEvidence)
    }
    @Test fun nonSuccessClassificationAndUnrelatedRangeRemainUnchanged() = runBlocking {
        val evidence = CaptureConfigurationEvidence(request(true)); val reply = response(true, 503, mapOf("Content-Range" to ""))
        val failure = runCatching { write(Client(reply), true, evidence) }.exceptionOrNull() as CaptureConfigurationException
        assertTrue(failure.cause is CcapiHttpException)
        assertEquals(503, (failure.cause as CcapiHttpException).statusCode)
        val unrelated = Client(response(true, 206, mapOf("Content-Range" to "bytes 0-24/80")))
        assertEquals("ABCDE", unrelated.unrelatedPartialGet().getString("directoryname"))
    }
    @Test fun cancellationAndRetiredOwnerBeforeDispatchStillSendNothing() = runBlocking {
        val cancelled = CaptureConfigurationEvidence(request(true)); cancelled.cancel()
        val client = Client(response(true))
        assertTrue(runCatching { write(client, true, cancelled) }.isFailure)
        assertEquals(CaptureConfigurationWriteEvidence.NOT_SENT, cancelled.snapshot().writeEvidence)
        assertEquals(0, client.executions) // Original dispatch fence follows Call construction.
        val retired = CaptureConfigurationEvidence(request(true), checkOwner = { error("Synthetic retired owner") })
        val failure = runCatching { write(Client(response(true)), true, retired) }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(CaptureConfigurationWriteEvidence.NOT_SENT, retired.snapshot().writeEvidence)
    }
}
