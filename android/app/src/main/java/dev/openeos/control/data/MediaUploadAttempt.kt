package dev.openeos.control.data

import kotlinx.coroutines.CancellationException

/** Local evidence for exactly one upload. An inventory entry is never a receipt. */
enum class MediaUploadStatus { PREPARING, SENDING, CONFIRMED, NOT_SENT, UNCERTAIN }

/** Invoked at the actual transport boundary and before a cancellable return can discard a receipt. */
data class MediaUploadEvidence(
    val beforeDispatch: () -> Unit = {},
    val onConfirmed: (CameraMediaUploadResult) -> Unit = {},
)

class MediaUploadAttempt {
    private var dispatched = false
    private var cancelled = false
    private var finished = false
    private var receipt: CameraMediaUploadResult? = null

    @Synchronized
    fun beforeDispatch() {
        if (cancelled || finished) throw CancellationException("Upload stopped before dispatch.")
        check(!dispatched) { "An upload attempt must not be sent twice." }
        dispatched = true
    }

    /** Only a transport-validated response from this invocation may call this. */
    @Synchronized
    fun confirm(result: CameraMediaUploadResult) {
        if (finished || receipt != null) return
        check(dispatched) { "Upload receipt arrived without dispatch." }
        require(result.item.id.isNotEmpty() && result.item.name.isNotEmpty() &&
            result.bytesTransferred > 0L && result.item.sizeBytes == result.bytesTransferred)
        receipt = result
    }

    @Synchronized
    fun cancel() { cancelled = true }

    @Synchronized
    fun finish(): MediaUploadStatus {
        finished = true
        return status()
    }

    @Synchronized
    fun confirmedResult(): CameraMediaUploadResult? = receipt

    @Synchronized
    fun acceptsProgress(): Boolean = !cancelled && !finished && receipt == null

    @Synchronized
    fun status(): MediaUploadStatus = when {
        receipt != null -> MediaUploadStatus.CONFIRMED
        finished || cancelled -> if (dispatched) MediaUploadStatus.UNCERTAIN else MediaUploadStatus.NOT_SENT
        dispatched -> MediaUploadStatus.SENDING
        else -> MediaUploadStatus.PREPARING
    }
}

/** Reference identity prevents a replacement connection or attempt inheriting old callbacks. */
fun ownsMediaUpload(
    expectedAttempt: Any, currentAttempt: Any?, expectedConnection: Any?, currentConnection: Any?,
    expectedGeneration: Long, currentGeneration: Long,
): Boolean = expectedAttempt === currentAttempt && expectedConnection === currentConnection &&
    expectedGeneration == currentGeneration

/** ASCII case equivalence only; do not trim, normalize Unicode, or case-fold opaque identities. */
fun uploadReceiptNameMatches(expected: String, returned: String): Boolean =
    expected.length == returned.length && expected.indices.all { index ->
        val a = expected[index]; val b = returned[index]
        a == b || (if (a in 'A'..'Z') a + 32 else a) == (if (b in 'A'..'Z') b + 32 else b)
    }

fun validUploadReceipt(
    httpStatus: Int, expectedName: String, expectedSize: Long, bytesTransferred: Long,
    returnedId: Any?, returnedName: Any?, returnedSize: Any?, returnedKind: Any?, sizeToken: String?,
): Boolean {
    val size = when (returnedSize) { is Int -> returnedSize.toLong(); is Long -> returnedSize; else -> null }
    return httpStatus == 201 && expectedSize in 1L..4_294_967_295L && bytesTransferred == expectedSize &&
        returnedKind is String && returnedKind.isNotEmpty() &&
        returnedId is String && returnedId.isNotEmpty() && returnedName is String && returnedName.isNotEmpty() &&
        uploadReceiptNameMatches(expectedName, returnedName) && size == expectedSize &&
        sizeToken != null && Regex("[1-9][0-9]*").matches(sizeToken) && sizeToken.toLongOrNull() == expectedSize
}

fun canStartMediaUpload(
    previewMode: Boolean, featureSupported: Boolean, mediaBusy: Boolean,
    uploadBusy: Boolean, downloadBusy: Boolean,
): Boolean = !previewMode && featureSupported && !mediaBusy && !uploadBusy && !downloadBusy
