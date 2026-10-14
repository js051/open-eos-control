package dev.openeos.control.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.floor

/** Immutable numeric preview LUT. No camera, file or persistent identity is retained. */
class NativeVideoLut(size: Int, domainMin: FloatArray, domainMax: FloatArray, values: FloatArray) {
    val size: Int = size
    private val minimum = domainMin.copyOf()
    private val maximum = domainMax.copyOf()
    private val table = values.copyOf()

    init {
        require(size in 2..64)
        require(minimum.size == 3 && maximum.size == 3)
        require(table.size == size * size * size * 3)
        require(table.all(Float::isFinite))
        require((0..2).all {
            minimum[it].isFinite() && maximum[it].isFinite() &&
                minimum[it] < maximum[it] && (maximum[it] - minimum[it]).isFinite() &&
                (1f / (maximum[it] - minimum[it])).isFinite() &&
                (-minimum[it] / (maximum[it] - minimum[it])).isFinite()
        })
    }

    fun domainMinimum(): FloatArray = minimum.copyOf()
    fun domainMaximum(): FloatArray = maximum.copyOf()

    /** Red changes fastest. Float texels preserve values outside 0..1 until AFTER interpolation. */
    fun rgbaTexture(): FloatBuffer {
        val result = ByteBuffer.allocateDirect(size * size * size * 4 * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        for (index in table.indices step 3) {
            result.put(table[index]).put(table[index + 1]).put(table[index + 2]).put(1f)
        }
        result.flip()
        return result
    }

    /** Independent CPU oracle for the GPU's manual trilinear interpolation. */
    fun sample(red: Float, green: Float, blue: Float): FloatArray {
        val input = floatArrayOf(red, green, blue)
        require(input.all(Float::isFinite))
        val position = FloatArray(3) {
            ((input[it] - minimum[it]) / (maximum[it] - minimum[it])).coerceIn(0f, 1f) * (size - 1)
        }
        val low = IntArray(3) { floor(position[it]).toInt() }
        val high = IntArray(3) { (low[it] + 1).coerceAtMost(size - 1) }
        val fraction = FloatArray(3) { position[it] - low[it] }
        fun value(r: Int, g: Int, b: Int, c: Int) = table[((b * size + g) * size + r) * 3 + c]
        fun mix(a: Float, b: Float, t: Float) = a * (1 - t) + b * t
        return FloatArray(3) { c ->
            val lower = mix(
                mix(value(low[0], low[1], low[2], c), value(high[0], low[1], low[2], c), fraction[0]),
                mix(value(low[0], high[1], low[2], c), value(high[0], high[1], low[2], c), fraction[0]),
                fraction[1],
            )
            val upper = mix(
                mix(value(low[0], low[1], high[2], c), value(high[0], low[1], high[2], c), fraction[0]),
                mix(value(low[0], high[1], high[2], c), value(high[0], high[1], high[2], c), fraction[0]),
                fraction[1],
            )
            mix(lower, upper, fraction[2]).coerceIn(0f, 1f)
        }
    }
}

enum class NativeVideoLutStatus { OFF, WAITING, APPLIED, UNSUPPORTED_COLOR, UNSUPPORTED_GPU, FAILED }

/** A preview contract, not a calibrated scene/exposure claim. Values map Android MediaFormat. */
internal fun nativeLutColorSupported(standard: Int?, transfer: Int?, bitDepth: Int?): Boolean =
    (standard == null || standard in setOf(1, 2, 4)) && // BT.709, BT.601 PAL/NTSC
        (transfer == null || transfer == 3) && // SDR_VIDEO; never linear/PQ/HLG/unknown tags
        (bitDepth == null || bitDepth == 8)

/** Serializes retirement without permitting a stale decoder/format/settings owner to publish. */
internal class NativeVideoPresentationGate {
    data class Ticket(val generation: Long, val sequence: Long)
    private var generation = 0L
    private var sequence = 0L
    private var active = false

    @Synchronized fun configure(enabled: Boolean) {
        generation++
        active = enabled
    }

    @Synchronized fun begin(): Ticket? = if (active) Ticket(generation, ++sequence) else null
    @Synchronized fun owns(ticket: Ticket): Boolean = active && ticket.generation == generation
}

/** Only explicitly 8-bit H.264 profiles are admitted; no speculative parsing of high-depth VUI. */
internal fun nativeLutH264ProfileSupported(sps: ByteArray?): Boolean {
    if (sps == null || sps.size !in 4..65_536) return false
    val start = when {
        sps.size >= 4 && sps[0] == 0.toByte() && sps[1] == 0.toByte() && sps[2] == 0.toByte() && sps[3] == 1.toByte() -> 4
        sps.size >= 3 && sps[0] == 0.toByte() && sps[1] == 0.toByte() && sps[2] == 1.toByte() -> 3
        else -> 0
    }
    return sps.size >= start + 4 && sps[start].toInt() and 0x1f == 7 &&
        (sps[start + 1].toInt() and 0xff) in setOf(66, 77, 88, 100)
}

/** Missing compressed input invalidates prediction references until the next IDR. */
internal class NativeVideoDecodeContinuity {
    var needsKeyFrame = true
        private set
    private var previous: Long? = null

    fun admit(sequence: Long, keyFrame: Boolean): Boolean {
        if (previous?.let { sequence != it + 1 } == true) needsKeyFrame = true
        previous = sequence
        return !needsKeyFrame || keyFrame
    }

    fun accepted() { needsKeyFrame = false }
    fun dropped() { needsKeyFrame = true }
}

/** Even a cancelled intermediate replacement must retain the prior decoder's real drain barrier. */
internal fun launchDrainedNativeVideoJob(scope: CoroutineScope, previous: Job?, block: suspend () -> Unit): Job =
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
        withContext(NonCancellable) { previous?.join() }
        // UNDISPATCHED establishes the barrier before another replacement can cancel us.
        // Yield back to the scope dispatcher before entering any native codec/GL operation.
        yield()
        currentCoroutineContext().ensureActive()
        block()
    }
