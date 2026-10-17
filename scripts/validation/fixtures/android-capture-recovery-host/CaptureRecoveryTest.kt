package dev.openeos.control.ui

import dev.openeos.control.data.*
import java.io.*
import java.util.concurrent.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

private const val JOIN_MESSAGE = "Capture recovery must release registered job while transport is held"
private fun directoryResponse() = JSONObject().put("operation", "create-directory").put("directorySelection",
    JSONObject().put("value", "100ABCDE").put("ability", JSONArray(listOf("100ABCDE", "101FGHIJ"))))
private fun namingResponse() = JSONObject().put("operation", "set-file-naming").put("fileNaming", JSONObject()
    .put("stillFilenameMode", "preset_code").put("stillFilenameModeOptions", JSONArray(listOf("preset_code")))
    .put("stillUserSetting1", "ABCD").put("stillUserSetting2", "ABC").put("movieIndex", "AB")
    .put("movieReelNumber", 1).put("movieReelRange", JSONObject().put("minimum", 1).put("maximum", 9999).put("step", 1))
    .put("movieClipNumber", 1).put("movieClipRange", JSONObject().put("minimum", 1).put("maximum", 999).put("step", 1))
    .put("movieUserDefined", "ABCDE"))
private class CaptureRig(val bodyHeld: Boolean, val naming: Boolean = false) {
    val client = ExtractedMaintenanceClient()
    val queue = QueueDispatcher()
    val repository = HostRepository(client)
    val vm = ExtractedMaintenanceViewModel(repository, queue)
    val attempt = vm.seedCapture(if (naming) CaptureConfigurationOperation.SET_FILE_NAMING else CaptureConfigurationOperation.CREATE_DIRECTORY)
    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val closed = CountDownLatch(1)
    lateinit var job: Job
    lateinit var call: HostCall
    init {
        client.respond = { request, held ->
            if ("/capture-configuration/" in request.url.encodedPath) {
                call = held
                if (!bodyHeld) { entered.countDown(); check(release.await(45, TimeUnit.SECONDS)) }
                val bytes = (if (naming) namingResponse() else directoryResponse()).toString().toByteArray()
                Response(200, ResponseBody(object : ByteArrayInputStream(bytes) {
                    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                        if (bodyHeld) { entered.countDown(); check(release.await(45, TimeUnit.SECONDS)) }
                        return super.read(bytes, offset, length)
                    }
                    override fun close() { closed.countDown(); super.close() }
                }))
            } else client.validResponse(request)
        }
    }
    fun start() {
        vm.recoverCaptureConfiguration(attempt.id)
        job = requireNotNull(vm.cameraOperationJobs[CameraOperation.DIRECTORY])
        assertSame(job, vm.captureJob)
        assertTrue(queue.until { entered.count == 0L })
        assertEquals(1, client.requests.size)
        assertEquals("GET", call.request.method)
        assertEquals("no-cache, no-store", call.request.headerMap["Cache-Control"])
    }
    fun assertReleased(timeout: Long = 750) {
        assertEquals(1L, release.count)
        val finished = queue.until(timeout) { repository.closed && !vm.state.connectionTeardownPending }
        if (!finished) {
            assertEquals(0, repository.disconnects)
            assertFalse(job.isCompleted)
            println("Runtime counterexample: heldBody=$bodyHeld; registered DIRECTORY job incomplete; Disconnect cannot reach repository; execute=${call.executeCount}; enqueue=${call.enqueueCount}")
        }
        assertTrue(JOIN_MESSAGE, finished)
        assertTrue(job.isCompleted)
        assertEquals(1, repository.disconnects)
        assertEquals(0, client.mutationCalls)
        assertEquals(0, client.statusPublications.get())
        assertEquals(1L, release.count)
    }
    fun finish() {
        release.countDown()
        assertTrue(queue.until { closed.count == 0L })
        assertTrue(queue.until { job.isCompleted })
        if (call.enqueueCount.get() > 0) assertTrue(queue.until { call.callbacksCompleted.get() > 0 })
        queue.drain()
    }
}
private fun cancelProbe(body: Boolean, late: Boolean = false, naming: Boolean = false) {
    val rig = CaptureRig(body, naming); rig.start()
    try {
        rig.vm.disconnect(); rig.queue.drain()
        rig.assertReleased()
        assertTrue(rig.call.cancelCount.get() > 0)
        if (late) {
            val replacement = rig.client.replaceSession()
            val state = rig.vm.replaceConnection()
            val receipt = rig.attempt.snapshot()
            rig.finish()
            assertSame(replacement, rig.client.owner)
            assertEquals(state, rig.vm.state)
            assertEquals(receipt, rig.attempt.snapshot())
            assertEquals(0, rig.client.statusPublications.get())
        }
    } finally { rig.finish() }
}
private fun timeoutProbe() {
    val rig = CaptureRig(true); rig.start()
    try {
        val completed = rig.queue.until(17_000) { rig.job.isCompleted }
        assertTrue(JOIN_MESSAGE, completed)
        assertEquals(CaptureConfigurationReadEvidence.UNAVAILABLE, rig.attempt.snapshot().evidence.readbackEvidence)
        rig.vm.disconnect(); rig.queue.drain(); rig.assertReleased()
    } finally { rig.finish() }
}
class BaselineCaptureRecoveryTest {
    @Test fun heldResponseMustReleaseDisconnect() = cancelProbe(false)
    @Test fun heldBodyMustReleaseDisconnect() = cancelProbe(true)
    @Test fun realReadDeadlineMustReleaseRegisteredJob() = timeoutProbe()
}
class FixedCaptureRecoveryTest {
    @Test fun heldResponseMustReleaseDisconnect() = cancelProbe(false)
    @Test fun heldBodyMustReleaseDisconnect() = cancelProbe(true)
    @Test fun realReadDeadlineMustReleaseRegisteredJob() = timeoutProbe()
    @Test fun cancelButtonReleasesRegisteredRecoveryBeforeDisconnect() {
        val rig = CaptureRig(true); rig.start()
        try {
            rig.vm.cancelCaptureConfiguration(rig.attempt.id)
            assertTrue(rig.queue.until { rig.job.isCompleted })
            assertEquals(0, rig.repository.disconnects)
            assertTrue(rig.attempt.snapshot().cancelled)
            assertEquals(1L, rig.release.count)
            rig.vm.disconnect(); rig.queue.drain(); rig.assertReleased()
        } finally { rig.finish() }
    }
    @Test fun lateSameTextReplacementResponseCannotPublish() = cancelProbe(false, late = true)
    @Test fun lateSameTextReplacementBodyCannotPublish() = cancelProbe(true, late = true)
    @Test fun fileNamingReadHasSameCancellationBoundary() = cancelProbe(true, late = true, naming = true)
    @Test fun successfulExactRoutesPublishOnlyCaptureReadback() {
        for (naming in listOf(false, true)) {
            val rig = CaptureRig(true, naming)
            rig.release.countDown(); rig.start(); rig.finish()
            assertEquals(if (naming) CaptureConfigurationReadEvidence.MATCHED else CaptureConfigurationReadEvidence.REFRESHED,
                rig.attempt.snapshot().evidence.readbackEvidence)
            assertEquals(0, rig.client.statusPublications.get())
            assertEquals(0, rig.client.mutationCalls)
            assertEquals(1, rig.client.calls.single().enqueueCount.get())
            assertEquals(0, rig.client.calls.single().executeCount.get())
        }
    }
    @Test fun recoveryRejectsWrongRouteMethodOwnerStatusAndMutationEvidence() = runBlocking {
        val client = ExtractedMaintenanceClient()
        val routes = listOf("/v1/session/synthetic-session/status", "/v1/session/synthetic-session/capture-configuration/create-directory/extra",
            "/v1/session/other/capture-configuration/create-directory")
        for (path in routes) assertTrue(runCatching { client.captureProbe(path = path) }.isFailure)
        assertTrue(runCatching { client.captureProbe(method = "POST") }.isFailure)
        assertTrue(runCatching { client.captureProbe(observe = true) }.isFailure)
        assertTrue(runCatching { client.captureProbe(captured = null) }.isFailure)
        assertTrue(runCatching { client.captureProbe(evidence = CaptureConfigurationEvidence(
            CaptureConfigurationRequest(CaptureConfigurationOperation.CREATE_DIRECTORY, "ABCDE"))) }.isFailure)
        val old = client.owner; client.replaceSession()
        assertTrue(runCatching { client.captureProbe(captured = old) }.isFailure)
        assertTrue(client.requests.isEmpty())
        assertEquals(0, client.statusPublications.get())
    }
    @Test fun failedOrMalformedReadCannotPublishStatusOrSettings() = runBlocking {
        val client = ExtractedMaintenanceClient()
        for ((code, bytes, length) in listOf(
            Triple(503, directoryResponse().toString().toByteArray(), -1L),
            Triple(200, "{invalid".toByteArray(), -1L),
            Triple(200, directoryResponse().toString().toByteArray(), 1L),
            Triple(200, ByteArray(65_537) { 32 }, -1L))) {
            client.respond = { _, _ -> Response(code, ResponseBody(ByteArrayInputStream(bytes), length)) }
            assertTrue(runCatching { client.readCaptureConfiguration(CaptureConfigurationOperation.CREATE_DIRECTORY) }.isFailure)
        }
        assertEquals(0, client.statusPublications.get())
        assertEquals(0, client.mutationCalls)
        assertTrue(client.calls.all { it.enqueueCount.get() == 1 && it.executeCount.get() == 0 })
    }
    @Test fun duplicateCallbackRemainsSingleReadOnlyCompletion() {
        val rig = CaptureRig(true)
        rig.client.callbackCopies = 2
        rig.start()
        try {
            rig.finish()
            assertTrue(rig.queue.until { rig.call.callbacksCompleted.get() == 2 })
            assertEquals(CaptureConfigurationReadEvidence.REFRESHED, rig.attempt.snapshot().evidence.readbackEvidence)
            assertEquals(0, rig.client.statusPublications.get())
            assertEquals(1, rig.client.calls.size)
            assertEquals(TimeUnit.SECONDS.toNanos(15), rig.call.timeout().timeoutNanos())
        } finally { rig.finish() }
    }
    @Test fun ordinaryTransportRetainsSynchronousExecution() = runBlocking {
        val client = ExtractedMaintenanceClient()
        client.respond = { _, _ -> client.jsonResponse(directoryResponse()) }
        client.captureProbe(recovery = false)
        assertEquals(1, client.calls.single().executeCount.get())
        assertEquals(0, client.calls.single().enqueueCount.get())
    }
    @Test fun replacementBeforeBodyCompletionRejectsAdoption() {
        val rig = CaptureRig(true); rig.start()
        try {
            val fresh = rig.client.replaceSession(); val state = rig.vm.replaceConnection()
            rig.finish()
            assertSame(fresh, rig.client.owner)
            assertEquals(state.capabilities, rig.vm.state.capabilities)
            assertEquals(CaptureConfigurationReadEvidence.UNAVAILABLE, rig.attempt.snapshot().evidence.readbackEvidence)
            assertEquals(0, rig.client.statusPublications.get())
        } finally { rig.finish() }
    }
}
