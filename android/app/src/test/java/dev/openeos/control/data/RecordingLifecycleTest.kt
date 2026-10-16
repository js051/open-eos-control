package dev.openeos.control.data

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class RecordingLifecycleTest {
    @Test fun genericHintFenceRejectsCrossCommandAndInFlightPollDelivery() {
        val fence = RecordingEventFence()
        val preStartRead = fence.capture()
        val evidence = RecordingRequestEvidence(RecordingCommand.START)
        evidence.onAdmission = { fence.commandAdmission(); 0L }
        evidence.beginRequest()
        var published = false
        assertFalse(fence.accept(preStartRead) { published = true })
        assertFalse(published)
        assertTrue(RecordingReceipt(RecordingCommand.START, evidence.responseEvidence()).stopRequired)
        val duringWrite = fence.capture()
        assertFalse(fence.accept(duringWrite) { published = true })
        fence.commandCompleted()
        assertFalse(fence.accept(duringWrite) { published = true })
        assertTrue(fence.accept(fence.capture()) { published = true })
        assertTrue(published)
    }
    @Test fun eventShapeRejectsConflictingDirectionsNullCoercionsAndDuplicateJson() {
        assertEquals(false, recordingEventValue(mapOf("action" to "stop")))
        assertEquals(true, recordingEventValue(mapOf("status" to "start")))
        assertEquals(false, recordingEventValue(mapOf("action" to "stop", "status" to "stop")))
        listOf(mapOf("action" to "stop", "status" to "start"), mapOf("action" to false),
            mapOf("action" to null, "status" to "stop"), emptyMap()).forEach { assertNull(recordingEventValue(it)) }
        assertTrue(runCatching { StrictMediaJson("""{"recbutton":{"status":"start","status":"stop"}}""").validate() }.isFailure)
        assertTrue(runCatching { readStrictMediaJsonText(byteArrayOf(0xc3.toByte(), 0x28).inputStream(), 2, 100) }.isFailure)
    }
    @Test fun explicitRecoveryCannotRefreshTheAgeOfAnAlreadyUsedEventCache() {
        val evidence = RecordingRequestEvidence(RecordingCommand.START)
        evidence.beginRequest()
        val event = RecordingObservation(false, 3, RecordingObservationSource.SIMULATOR_STATUS)
        evidence.observe(event)
        evidence.beginObservationRead()
        evidence.observe(event)
        assertNull(evidence.observation)
        assertEquals(RecordingResponseEvidence.UNKNOWN, evidence.responseEvidence())
        evidence.observe(event.copy(sequence = 4))
        assertEquals(false, evidence.observation?.recording)
    }
    @Test fun missingRecoveryNeverReplaysOriginalResponseAgainstNewerObservedDuty() {
        val stop = RecordingReceipt(RecordingCommand.STOP, RecordingResponseEvidence.RESPONSE_RECEIVED,
            RecordingObservation(true, 1, RecordingObservationSource.BRIDGE_STATUS), priorStopRequired = true)
        val stopUnavailable = stop.withObservation(null)
        assertTrue(stopUnavailable.stopRequired)
        assertFalse(stopUnavailable.mayReviewStoppedVideo)
        assertEquals(RecordingResponseEvidence.RESPONSE_RECEIVED, stopUnavailable.response)
        val start = RecordingReceipt(RecordingCommand.START, RecordingResponseEvidence.UNKNOWN,
            RecordingObservation(false, 2, RecordingObservationSource.SIMULATOR_STATUS))
        val startUnavailable = start.withObservation(null)
        assertFalse(startUnavailable.stopRequired)
        assertEquals(RecordingResponseEvidence.UNKNOWN, startUnavailable.response)
    }
    @Test fun lostStartThenFreshFalseNeverInventsTheMissingResponse() {
        val evidence = RecordingRequestEvidence(RecordingCommand.START)
        evidence.observationFloor = 10
        evidence.beginRequest()
        assertTrue(RecordingReceipt(evidence.command, evidence.responseEvidence()).stopRequired)
        evidence.observe(RecordingObservation(false, 10, RecordingObservationSource.USB_EVENT))
        assertNull(evidence.observation)
        evidence.observe(RecordingObservation(false, 11, RecordingObservationSource.USB_EVENT))
        val receipt = RecordingReceipt(evidence.command, evidence.responseEvidence(), evidence.observation)
        assertEquals(RecordingResponseEvidence.UNKNOWN, receipt.response)
        assertFalse(receipt.stopRequired)
        assertFalse(receipt.mayReviewStoppedVideo)
        assertTrue(runCatching { evidence.beginRequest() }.isFailure)
    }
    @Test fun stopResponseClearsDutyButOnlyFreshFalseAdmitsStoppedVideoReview() {
        val evidence = RecordingRequestEvidence(RecordingCommand.STOP)
        evidence.beginRequest(); evidence.acknowledge()
        val receipt = RecordingReceipt(evidence.command, evidence.responseEvidence(), priorStopRequired = true)
        assertFalse(receipt.stopRequired)
        assertFalse(receipt.mayReviewStoppedVideo)
        val stopped = receipt.copy(observation = RecordingObservation(false, 1, RecordingObservationSource.SIMULATOR_STATUS))
        assertTrue(stopped.mayReviewStoppedVideo)
        assertEquals(RecordingResponseEvidence.RESPONSE_RECEIVED, stopped.response)
        assertFalse(stopped.copy(sameAttempt = false).mayReviewStoppedVideo)
    }
    @Test fun rejectedAndTruncatedStopKeepOriginalDuty() {
        listOf(RecordingResponseEvidence.REJECTED, RecordingResponseEvidence.UNKNOWN, RecordingResponseEvidence.NOT_REQUIRED).forEach {
            assertTrue(RecordingReceipt(RecordingCommand.STOP, it, priorStopRequired = true).stopRequired)
        }
    }
    @Test fun cancellationAndRetirementRetainAdmissionButCannotAcceptOldObservation() {
        val evidence = RecordingRequestEvidence(RecordingCommand.START)
        evidence.beginRequest(); assertTrue(evidence.retire()); evidence.acknowledge()
        val receipt = RecordingReceipt(evidence.command, evidence.responseEvidence(),
            RecordingObservation(false, 1, RecordingObservationSource.SIMULATOR_STATUS), retired = true)
        assertTrue(receipt.stopRequired); assertFalse(receipt.canPublish); assertFalse(receipt.mayReviewStoppedVideo)
        assertTrue(runCatching { evidence.beginRequest() }.exceptionOrNull() is CancellationException)
    }
    @Test fun cachedUsbAlreadyTargetIsNotACommandResponseOrFreshRead() {
        val evidence = RecordingRequestEvidence(RecordingCommand.STOP)
        evidence.observationFloor = 5; evidence.noOp()
        evidence.observe(RecordingObservation(false, 5, RecordingObservationSource.USB_EVENT))
        assertFalse(evidence.attempted); assertNull(evidence.observation)
        assertEquals(RecordingResponseEvidence.NOT_REQUIRED, evidence.responseEvidence())
        assertTrue(RecordingReceipt(evidence.command, evidence.responseEvidence(), priorStopRequired = true).stopRequired)
    }
    @Test fun syntheticUsbRequestedPropertyNoOpMayResolveDutyWithoutInventingAck() {
        val evidence = RecordingRequestEvidence(RecordingCommand.STOP)
        evidence.observationFloor = 5; evidence.noOp()
        evidence.observe(RecordingObservation(false, 6, RecordingObservationSource.USB_EVENT))
        val receipt = RecordingReceipt(evidence.command, evidence.responseEvidence(), evidence.observation, priorStopRequired = true)
        assertFalse(receipt.stopRequired); assertTrue(receipt.mayReviewStoppedVideo)
        assertEquals(RecordingResponseEvidence.NOT_REQUIRED, receipt.response)
    }
    @Test fun responseReceivedRemainsImmutableAcrossSubsequentFailureAndObservation() {
        val evidence = RecordingRequestEvidence(RecordingCommand.STOP)
        evidence.beginRequest(); evidence.acknowledge(); evidence.rejected = true
        evidence.observe(RecordingObservation(false, 1, RecordingObservationSource.USB_EVENT))
        assertEquals(RecordingResponseEvidence.RESPONSE_RECEIVED, evidence.responseEvidence())
        val rejected = RecordingRequestEvidence(RecordingCommand.START)
        rejected.beginRequest(); rejected.rejected = true; rejected.acknowledge()
        assertEquals(RecordingResponseEvidence.REJECTED, rejected.responseEvidence())
    }
    @Test fun bridgeOutcomeRequiresExactOperationEngineSchemaAndSeparateObservation() {
        val observation = mapOf<String, Any?>("version" to 1, "engine" to "ccapi", "observationEvidence" to "unavailable",
            "recording" to null, "source" to null)
        val raw = mapOf<String, Any?>("version" to 1, "engine" to "ccapi", "operation" to "stop",
            "responseEvidence" to "response-received", "observation" to observation)
        val valid = requireNotNull(recordingWireOutcome(raw, "ccapi", RecordingCommand.STOP))
        assertEquals(RecordingResponseEvidence.RESPONSE_RECEIVED, valid.response); assertNull(valid.observation.recording)
        assertNull(recordingWireOutcome(raw, "ccapi", RecordingCommand.START))
        assertNull(recordingWireOutcome(raw, "libgphoto2", RecordingCommand.STOP))
        assertNull(recordingWireOutcome(raw + ("version" to 1.0), "ccapi", RecordingCommand.STOP))
        assertNull(recordingWireOutcome(raw + ("observation" to observation + ("recording" to false)), "ccapi", RecordingCommand.STOP))
        assertNull(recordingWireOutcome(raw + ("extra" to true), "ccapi", RecordingCommand.STOP))
    }
}
