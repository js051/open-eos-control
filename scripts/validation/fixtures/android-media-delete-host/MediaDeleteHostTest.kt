package dev.openeos.control.data

import kotlinx.coroutines.runBlocking
import okhttp3.Body
import okhttp3.OkHttpClient
import okhttp3.Response
import org.junit.Assert.*
import org.junit.Test

class MediaDeleteHostTest {
    private fun probe(code: Int, headers: Map<String, String> = emptyMap(), expected: MediaDeleteOutcome,
        bytes: ByteArray = byteArrayOf(), ownerValid: Boolean = true) = runBlocking {
        val http = OkHttpClient(Response(code, headers, Body(bytes)))
        val client = Client(http).also { it.ownerValid = ownerValid }
        val target = CameraMediaItem("/ccapi/ver100/contents/SYNTHETIC.JPG", "SYNTHETIC.JPG")
        val other = CameraMediaItem("/ccapi/ver100/contents/OTHER.JPG", "OTHER.JPG")
        val original = CameraUiState(listOf(target, other), mediaPreviewItem = target, captureReviewItem = target)
        val view = ViewState(original)
        val attempt = MediaDeleteAttempt()
        var confirmed = 0
        var rejected = 0
        val result = runCatching { client.deleteMedia(target, MediaDeleteEvidence(
            beforeDispatch = attempt::beforeDispatch,
            onConfirmed = { confirmed++; attempt.confirm(); view.confirm(target) },
            onRejected = { rejected++; attempt.reject() },
        )) }
        val observed = attempt.finish()
        println("HOST_RESULT code=$code headers=$headers outcome=$observed confirmed=$confirmed rejected=$rejected " +
            "dispatches=${http.dispatches} targetPresent=${target in view.state.mediaItems} " +
            "otherPresent=${other in view.state.mediaItems} ownerValid=$ownerValid")
        assertEquals(expected, observed)
        val isConfirmed = expected == MediaDeleteOutcome.CONFIRMED
        assertEquals(isConfirmed, result.isSuccess)
        assertEquals(if (isConfirmed) 1 else 0, confirmed)
        assertEquals(if (expected == MediaDeleteOutcome.REJECTED) 1 else 0, rejected)
        assertEquals(if (ownerValid) 1 else 0, http.dispatches)
        assertEquals(isConfirmed, CameraFeature.MEDIA_DELETE in client.observedFeatures)
        assertEquals(!isConfirmed, target in view.state.mediaItems)
        assertTrue(other in view.state.mediaItems)
        if (!isConfirmed) assertEquals(original, view.state)
        if (ownerValid) {
            assertEquals("DELETE", http.lastRequest!!.method)
            assertEquals(client.baseUrl + target.id, http.lastRequest!!.url)
            assertTrue(http.lastRequest!!.body!!.isOneShot())
        }
        assertEquals(mapOf("retry" to false, "redirect" to false, "sslRedirect" to false), http.options)
    }
    @Test fun range204RetainsItem() = probe(204, mapOf("Content-Range" to "bytes 0-0/1"), MediaDeleteOutcome.UNCERTAIN)
    @Test fun emptyRange204RetainsItem() = probe(204, mapOf("Content-Range" to ""), MediaDeleteOutcome.UNCERTAIN)
    @Test fun mixedCaseRange204RetainsItem() = probe(204, mapOf("cOnTeNt-RaNgE" to "bytes */1", "Content-Length" to "0"), MediaDeleteOutcome.UNCERTAIN)
    @Test fun full204Confirms() = probe(204, expected = MediaDeleteOutcome.CONFIRMED)
    @Test fun fullLengthZero204Confirms() = probe(204, mapOf("Content-Length" to "0"), MediaDeleteOutcome.CONFIRMED)
    @Test fun partial206RemainsUncertain() = probe(206, expected = MediaDeleteOutcome.UNCERTAIN)
    @Test fun refusal403RemainsRejected() = probe(403, expected = MediaDeleteOutcome.REJECTED)
    @Test fun rangedRefusal403KeepsExistingRejection() = probe(403, mapOf("Content-Range" to ""), MediaDeleteOutcome.REJECTED)
    @Test fun nonempty204RemainsUncertain() = probe(204, expected = MediaDeleteOutcome.UNCERTAIN, bytes = byteArrayOf(1))
    @Test fun retiredOwnerPreventsDispatch() = probe(204, expected = MediaDeleteOutcome.NOT_SENT, ownerValid = false)
}
