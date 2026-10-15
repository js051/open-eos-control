package dev.openeos.control.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class CaptureConfigurationOperation(val wireName: String) {
    CREATE_DIRECTORY("create-directory"), SET_FILE_NAMING("set-file-naming")
}
enum class CaptureConfigurationWriteEvidence { NOT_SENT, UNKNOWN, ACKNOWLEDGED }
enum class CaptureConfigurationReadEvidence { NOT_READ, UNAVAILABLE, MISMATCH, MATCHED, REFRESHED }

data class CaptureConfigurationRequest(
    val operation: CaptureConfigurationOperation,
    val requestedValue: String,
    val field: CameraFileNamingField? = null,
) {
    init {
        require((operation == CaptureConfigurationOperation.SET_FILE_NAMING) == (field != null))
        require(requestedValue.length <= 64 && requestedValue.none(Char::isISOControl))
    }
}

data class CaptureConfigurationSnapshot(
    val request: CaptureConfigurationRequest,
    val writeEvidence: CaptureConfigurationWriteEvidence = CaptureConfigurationWriteEvidence.NOT_SENT,
    val readbackEvidence: CaptureConfigurationReadEvidence = CaptureConfigurationReadEvidence.NOT_READ,
    val acknowledgedValue: String? = null,
    val observedValue: String? = null,
    val target: String? = null,
    val directoryValues: List<String> = emptyList(),
)

data class CaptureConfigurationReadback(val fileNaming: CameraFileNaming? = null, val directorySelection: String? = null,
    val directoryValues: List<String> = emptyList())

/** One logical mutation. Transport boundaries, rather than exception types, establish evidence. */
class CaptureConfigurationEvidence(
    val request: CaptureConfigurationRequest,
    private val checkOwner: () -> Unit = {},
    private val onChanged: (CaptureConfigurationSnapshot) -> Unit = {},
) {
    private var stopped = false
    private var state = CaptureConfigurationSnapshot(request)
    @Synchronized fun snapshot(): CaptureConfigurationSnapshot = state
    @Synchronized fun cancel() { stopped = true }
    @Synchronized fun beforeDispatch(target: String) {
        if (stopped) throw CancellationException("Capture configuration cancelled before dispatch.")
        checkOwner()
        check(state.writeEvidence == CaptureConfigurationWriteEvidence.NOT_SENT) { "Capture configuration dispatched twice." }
        state = state.copy(writeEvidence = CaptureConfigurationWriteEvidence.UNKNOWN, target = target)
        onChanged(state)
    }
    @Synchronized internal fun requireAutomaticReadAllowed() {
        if (stopped) throw CancellationException("Capture configuration cancelled before readback.")
        checkOwner()
        check(state.writeEvidence == CaptureConfigurationWriteEvidence.ACKNOWLEDGED)
    }
    @Synchronized fun acknowledge(value: String) {
        check(state.writeEvidence != CaptureConfigurationWriteEvidence.NOT_SENT) { "Capture configuration ACK without dispatch." }
        require(validCaptureConfigurationAcknowledgement(request, value)) { "Invalid capture configuration ACK." }
        // A complete validated same-attempt ACK is retained even after cancellation/owner retirement.
        state = state.copy(writeEvidence = CaptureConfigurationWriteEvidence.ACKNOWLEDGED, acknowledgedValue = value)
        onChanged(state)
    }
    @Synchronized fun observe(readback: CaptureConfigurationReadback) {
        val observed = if (request.operation == CaptureConfigurationOperation.CREATE_DIRECTORY) {
            requireNotNull(validCaptureDirectorySelection(readback.directorySelection, readback.directoryValues))
        } else requireNotNull(readback.fileNaming).also { require(validCaptureConfigurationNaming(it)) }
            .captureConfigurationValue(requireNotNull(request.field))
        val evidence = when {
            request.operation == CaptureConfigurationOperation.CREATE_DIRECTORY -> CaptureConfigurationReadEvidence.REFRESHED
            observed != request.requestedValue -> CaptureConfigurationReadEvidence.MISMATCH
            else -> CaptureConfigurationReadEvidence.MATCHED
        }
        state = state.copy(readbackEvidence = evidence, observedValue = observed, directoryValues = readback.directoryValues.toList())
        onChanged(state)
    }
    @Synchronized fun unavailable() {
        state = state.copy(readbackEvidence = CaptureConfigurationReadEvidence.UNAVAILABLE)
        onChanged(state)
    }
}

/** The successful write path performs one scoped read only after a validated live ACK. */
internal suspend fun captureConfigurationPostAckReadback(evidence: CaptureConfigurationEvidence,
    read: suspend () -> CaptureConfigurationReadback) {
    currentCoroutineContext().ensureActive()
    evidence.requireAutomaticReadAllowed()
    val readback = read()
    currentCoroutineContext().ensureActive()
    evidence.requireAutomaticReadAllowed()
    evidence.observe(readback)
}

internal fun validCaptureConfigurationAcknowledgement(request: CaptureConfigurationRequest, value: String): Boolean =
    if (request.operation == CaptureConfigurationOperation.CREATE_DIRECTORY)
        Regex("^(?:[A-Z0-9_]{5})?$").matches(request.requestedValue) && Regex("^[A-Z0-9_]{5}$").matches(value)
    else value == request.requestedValue && validCaptureConfigurationValue(requireNotNull(request.field), value)

internal fun validCaptureConfigurationValue(field: CameraFileNamingField, value: String): Boolean = when (field) {
    CameraFileNamingField.STILL_FILENAME_MODE -> value in setOf("preset_code", "usersetting1", "usersetting2")
    CameraFileNamingField.STILL_USER_SETTING_1 -> Regex("^[A-Z0-9][A-Z0-9_]{3}$").matches(value)
    CameraFileNamingField.STILL_USER_SETTING_2 -> Regex("^[A-Z0-9][A-Z0-9_]{2}$").matches(value)
    CameraFileNamingField.MOVIE_INDEX -> Regex("^[A-Z0-9][A-Z0-9_]$").matches(value)
    CameraFileNamingField.MOVIE_REEL_NUMBER -> value.toIntOrNull()?.let { it in 1..9999 && it.toString() == value } == true
    CameraFileNamingField.MOVIE_CLIP_NUMBER -> value.toIntOrNull()?.let { it in 1..999 && it.toString() == value } == true
    CameraFileNamingField.MOVIE_USER_DEFINED -> Regex("^[A-Z0-9]{5}$").matches(value)
}

internal fun validCaptureConfigurationNaming(state: CameraFileNaming): Boolean {
    val allowed = setOf("preset_code", "usersetting1", "usersetting2")
    fun range(value: CameraIntegerRange, maximum: Int): Boolean = value.minimum >= 1 && value.maximum <= maximum &&
        value.minimum <= value.maximum && value.step > 0
    return state.stillFilenameModeOptions.isNotEmpty() && state.stillFilenameModeOptions.size <= allowed.size &&
        state.stillFilenameModeOptions.distinct().size == state.stillFilenameModeOptions.size &&
        state.stillFilenameModeOptions.all { it in allowed } && state.stillFilenameMode in state.stillFilenameModeOptions &&
        range(state.movieReelRange, 9999) && range(state.movieClipRange, 999) &&
        CameraFileNamingField.entries.all { state.accepts(it, state.captureConfigurationValue(it)) }
}

/** Strict additive Bridge receipt parser input. JSON adapters must preserve types and field absence. */
internal fun acceptsCaptureConfigurationBridgeReceipt(
    request: CaptureConfigurationRequest, status: Int, code: Any?, engine: Any?, feature: Any?,
    version: Any?, operation: Any?, stage: Any?, requestedValue: Any?, acknowledgedValue: Any?,
    fieldPresent: Boolean, field: Any?,
): Boolean = status == 502 && code == "CAPTURE_CONFIG_READBACK_FAILED" && engine == "ccapi" &&
    feature == (if (request.operation == CaptureConfigurationOperation.CREATE_DIRECTORY) "DIRECTORY_CONTROL" else "FILE_NAMING_CONTROL") &&
    version is Int && version == 1 && operation == request.operation.wireName && stage == "acknowledged" &&
    requestedValue is String && requestedValue == request.requestedValue && acknowledgedValue is String &&
    acknowledgedValue.length <= 64 && validCaptureConfigurationAcknowledgement(request, acknowledgedValue) &&
    (if (request.field == null) !fieldPresent else fieldPresent && field == request.field.wireName)

internal fun CameraFileNaming.captureConfigurationValue(field: CameraFileNamingField): String = when (field) {
    CameraFileNamingField.STILL_FILENAME_MODE -> stillFilenameMode
    CameraFileNamingField.STILL_USER_SETTING_1 -> stillUserSetting1
    CameraFileNamingField.STILL_USER_SETTING_2 -> stillUserSetting2
    CameraFileNamingField.MOVIE_INDEX -> movieIndex
    CameraFileNamingField.MOVIE_REEL_NUMBER -> movieReelNumber.toString()
    CameraFileNamingField.MOVIE_CLIP_NUMBER -> movieClipNumber.toString()
    CameraFileNamingField.MOVIE_USER_DEFINED -> movieUserDefined
}

class CaptureConfigurationException(val receipt: CaptureConfigurationSnapshot, cause: Throwable) :
    IllegalStateException("Capture configuration did not complete; inspect the retained write and readback evidence.", cause)
class CaptureConfigurationCancellation(val receipt: CaptureConfigurationSnapshot, cause: Throwable) :
    CancellationException("Capture configuration cancelled; retained evidence remains available.") {
    init { initCause(cause) }
}
internal suspend fun <T> captureConfigurationOperation(evidence: CaptureConfigurationEvidence, action: suspend () -> T): T = try {
    action()
} catch (failure: Throwable) {
    if (evidence.snapshot().writeEvidence == CaptureConfigurationWriteEvidence.ACKNOWLEDGED &&
        evidence.snapshot().readbackEvidence == CaptureConfigurationReadEvidence.NOT_READ) evidence.unavailable()
    if (evidence.snapshot().writeEvidence == CaptureConfigurationWriteEvidence.NOT_SENT) throw failure
    if (failure is CancellationException) throw CaptureConfigurationCancellation(evidence.snapshot(), failure)
    throw CaptureConfigurationException(evidence.snapshot(), failure)
}

internal fun validCaptureDirectorySelection(value: Any?, values: List<Any?>): String? {
    val pattern = Regex("^[0-9]{3}[A-Z0-9_]{5}$")
    if (value !is String || !pattern.matches(value) || values.size !in 1..256 ||
        values.any { it !is String || !pattern.matches(it) } || values.distinct().size != values.size || value !in values) return null
    return value
}

/** Frozen backend closures: no operation resolves a replacement repository backend after an await. */
class CaptureConfigurationAccess(
    val origin: String,
    val write: suspend (CaptureConfigurationRequest, CaptureConfigurationEvidence) -> CameraFileNaming?,
    val read: suspend (CaptureConfigurationOperation) -> CaptureConfigurationReadback,
)
