package dev.openeos.control.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MediaScreenActionGateTest {
    private data class SyntheticCamera(val name: String = "synthetic-camera")

    @Test fun sameOwnerDispatchesTheOriginalPayloadOnlyWhenInvoked() {
        val camera = SyntheticCamera()
        val owner = MediaMetadataOwner(camera, 3)
        val gate = MediaScreenActionGate(owner) { owner }
        val payload = listOf("same-id")
        var received: List<String>? = null
        val callback = { gate.dispatch { received = payload } }
        assertEquals(null, received)
        callback()
        assertSame(payload, received)
    }

    @Test fun equalCameraMetadataCannotAdmitRetiredTransferOrPreviewCallbacks() {
        val camera = SyntheticCamera()
        var current = MediaMetadataOwner(camera, 3)
        val gate = MediaScreenActionGate(current) { current }
        var effects = 0
        val callbacks = List(10) { { gate.dispatch { effects++ } } }
        current = MediaMetadataOwner(camera.copy(), 3)
        callbacks.forEach { it() }
        assertEquals(0, effects)
    }

    @Test fun changedGenerationRevokesTheOldScreenBeforeCameraDescriptionIsCleared() {
        val camera = SyntheticCamera()
        var current = MediaMetadataOwner(camera, 3)
        val gate = MediaScreenActionGate(current) { current }
        var calls = 0
        current = MediaMetadataOwner(camera, 4)
        gate.dispatch { calls++ }
        assertEquals(0, calls)
    }

    @Test fun disconnectedScreenCannotLaunchAPickerOrCancelNewWork() {
        var current = MediaMetadataOwner(SyntheticCamera(), 3)
        val gate = MediaScreenActionGate(current) { current }
        var calls = 0
        current = MediaMetadataOwner(null, 4)
        gate.dispatch { calls++ }
        assertEquals(0, calls)
    }

    @Test fun ownerIsCheckedAtDispatchRatherThanWhenTheCallbackWasCreated() {
        val camera = SyntheticCamera()
        var current = MediaMetadataOwner(camera, 3)
        val oldGate = MediaScreenActionGate(current) { current }
        var oldEffects = 0
        val delayed = { oldGate.dispatch { oldEffects++ } }
        delayed()
        current = MediaMetadataOwner(camera.copy(), 4)
        delayed()
        val newGate = MediaScreenActionGate(current) { current }
        var newEffects = 0
        newGate.dispatch { newEffects++ }
        assertEquals(1, oldEffects)
        assertEquals(1, newEffects)
    }

    @Test fun retiredDismissalDoesNotConsumeTheNewOwnersAction() {
        var current = MediaMetadataOwner(SyntheticCamera(), 3)
        val oldGate = MediaScreenActionGate(current) { current }
        var newPreviewOpen = true
        val delayedDismiss = { oldGate.dispatch { newPreviewOpen = false } }
        current = MediaMetadataOwner(SyntheticCamera(), 4)
        delayedDismiss()
        assertEquals(true, newPreviewOpen)
        MediaScreenActionGate(current) { current }.dispatch { newPreviewOpen = false }
        assertEquals(false, newPreviewOpen)
    }
}
