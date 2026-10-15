package dev.openeos.control.data

import kotlinx.coroutines.CancellationException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Successful transport response is deliberately weaker than an exact setting acknowledgement. */
enum class ShootingSettingWriteEvidence { NOT_SENT, UNKNOWN, RESPONSE_RECEIVED }
enum class ShootingSettingReadEvidence { NOT_READ, UNAVAILABLE, MISMATCH, MATCHED }

data class ShootingSettingRequest(val key: String, val requestedValue: String) {
    init {
        require(Regex("^[a-z][a-z0-9._-]{0,63}$").matches(key))
        require(validShootingSettingText(requestedValue))
    }
}

data class ShootingSettingSnapshot(
    val request: ShootingSettingRequest,
    val writeEvidence: ShootingSettingWriteEvidence = ShootingSettingWriteEvidence.NOT_SENT,
    val readbackEvidence: ShootingSettingReadEvidence = ShootingSettingReadEvidence.NOT_READ,
    val observedValue: String? = null,
)

data class ShootingSettingObservation(val key: String, val value: String) {
    init { ShootingSettingRequest(key, value) }
}

class ShootingSettingEvidence(
    val request: ShootingSettingRequest,
    private val checkOwner: () -> Unit = {},
    private val publish: (ShootingSettingSnapshot) -> Unit = {},
) {
    private var stopped = false
    private var state = ShootingSettingSnapshot(request)
    @Synchronized fun snapshot(): ShootingSettingSnapshot = state
    @Synchronized fun cancel() { stopped = true }
    @Synchronized fun beforeDispatch() {
        if (stopped) throw CancellationException("Setting attempt cancelled before dispatch.")
        checkOwner()
        check(state.writeEvidence == ShootingSettingWriteEvidence.NOT_SENT) { "Setting write cannot be replayed." }
        state = state.copy(writeEvidence = ShootingSettingWriteEvidence.UNKNOWN)
        publish(state)
    }
    @Synchronized fun responseReceived() {
        check(state.writeEvidence != ShootingSettingWriteEvidence.NOT_SENT)
        // Retain valid transport evidence even when the editor/session retired during I/O.
        state = state.copy(writeEvidence = ShootingSettingWriteEvidence.RESPONSE_RECEIVED)
        publish(state)
    }
    @Synchronized fun observe(observed: ShootingSettingObservation) {
        require(observed.key == request.key)
        state = state.copy(observedValue = observed.value,
            readbackEvidence = if (observed.value == request.requestedValue) ShootingSettingReadEvidence.MATCHED
            else ShootingSettingReadEvidence.MISMATCH)
        publish(state)
    }
    @Synchronized fun unavailable() {
        state = state.copy(readbackEvidence = ShootingSettingReadEvidence.UNAVAILABLE, observedValue = null)
        publish(state)
    }
}

/** Scoped to one coroutine, never a mutable client-wide current attempt. */
internal data class ShootingSettingRoute(val key: String, val readPath: String, val writePath: String, val aggregateKey: String? = null)
internal class ShootingSettingWriteContext(val evidence: ShootingSettingEvidence, val route: ShootingSettingRoute? = null) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ShootingSettingWriteContext>
}

/** Closures capture the backend before suspension; recovery never looks up a replacement backend. */
class ShootingSettingAccess(
    val origin: String,
    val write: suspend (ShootingSettingRequest, ShootingSettingEvidence) -> Unit,
    val read: suspend (ShootingSettingRequest) -> ShootingSettingObservation,
)

internal fun acceptsShootingSettingValue(control: CameraSettingControl, value: String): Boolean =
    validShootingSettingText(value) && when (control.inputKind) {
        CameraSettingInputKind.CHOICE -> value in control.values
        CameraSettingInputKind.TEXT -> control.maxLength?.let { it in 1..255 && value.length <= it &&
            value.all { character -> character.code in 0x20..0x7E } } == true
    }

/** JSON adapters must preserve the original types. Missing/coerced values are not observations. */
internal fun shootingSettingScalar(value: Any?): String? = when (value) {
    is String -> value.takeIf { validShootingSettingText(it) }
    is Int -> value.toString()
    is Long -> value.toString()
    else -> null
}

internal fun acceptsShootingSettingBridgeBase(request: ShootingSettingRequest, expectedEngine: String?,
    version: Any?, engine: Any?, key: Any?, requestedValue: Any?, writeEvidence: Any?): Boolean =
    expectedEngine != null && version is Int && version == 1 && engine == expectedEngine &&
        key == request.key && requestedValue == request.requestedValue && writeEvidence == "response-received"

internal fun acceptsShootingSettingObservation(control: CameraSettingControl?): Boolean = control != null &&
    runCatching { ShootingSettingRequest(control.key, control.value) }.isSuccess &&
    validShootingSettingText(control.label) && when (control.inputKind) {
        CameraSettingInputKind.CHOICE -> control.values.size in 1..1024 && control.values.distinct().size == control.values.size &&
            control.values.all { it.isNotEmpty() && validShootingSettingText(it) } && control.value in control.values &&
            control.maxLength == null
        CameraSettingInputKind.TEXT -> control.values.isEmpty() && acceptsShootingSettingValue(control, control.value)
    }

internal fun validShootingSettingText(value: String): Boolean {
    if (value.codePointCount(0, value.length) > 512 || value.any(Char::isISOControl)) return false
    var index = 0
    while (index < value.length) {
        val character = value[index++]
        if (Character.isHighSurrogate(character)) {
            if (index == value.length || !Character.isLowSurrogate(value[index++])) return false
        } else if (Character.isLowSurrogate(character)) return false
    }
    return true
}

/** Called only after complete strict JSON/framing validation. Observation cannot upgrade unknown write evidence. */
internal fun applyShootingSettingBridgeOutcome(evidence: ShootingSettingEvidence, expectedEngine: String?, status: Int,
    version: Any?, engine: Any?, key: Any?, requestedValue: Any?, writeEvidence: Any?, readEvidence: Any?,
    setting: CameraSettingControl?): Boolean {
    if (status != 200 || !acceptsShootingSettingBridgeBase(evidence.request, expectedEngine, version, engine, key,
            requestedValue, writeEvidence)) return false
    evidence.responseReceived()
    if (setting != null && setting.key == evidence.request.key && acceptsShootingSettingObservation(setting) &&
        ((readEvidence == "matched" && setting.value == evidence.request.requestedValue) ||
            (readEvidence == "mismatch" && setting.value != evidence.request.requestedValue))) {
        evidence.observe(ShootingSettingObservation(setting.key, setting.value))
    } else evidence.unavailable()
    return true
}
