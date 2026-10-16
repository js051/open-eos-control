package dev.openeos.control.data

/** Native API evidence, never a claim that a physical display or camera was verified. */
enum class NativeFrameEvidence { CODEC_RENDER_CALLBACK, EGL_SURFACE_SUBMISSION }

/** Identity of decoded content, independent of a later asynchronous UI analysis. */
data class NativeRtpFrameIdentity(
    val decoderGeneration: Long,
    val formatGeneration: Long,
    val renderingGeneration: Long,
    val lutRevision: Long,
    val frameId: Long,
)

/** Immutable, bounded GPU readback of the same texture and LUT submitted for this frame. */
class NativeRtpFramePixels(val width: Int, val height: Int, pixels: IntArray) {
    private val argb = pixels.copyOf()
    init {
        require(width in 1..320 && height in 1..320 && argb.size == width * height)
    }
    fun copyPixels(): IntArray = argb.copyOf()
}

/** Encoded input and release intent cannot advance either decoded or presentation progress. */
internal class NativeRtpFrameProgress {
    private var generation = 0L
    private var lastDecodedId = 0L
    private var lastPresentedId = 0L
    private val decodedIds = linkedSetOf<Long>()
    @Volatile var decodedCount = 0L
        private set
    @Volatile var presentedCount = 0L
        private set

    @Synchronized fun reset(generation: Long) {
        this.generation = generation
        lastDecodedId = 0
        lastPresentedId = 0
        decodedIds.clear()
    }

    @Synchronized fun decoded(generation: Long, frameId: Long): Boolean {
        if (generation != this.generation || frameId <= lastDecodedId) return false
        lastDecodedId = frameId
        decodedIds.add(frameId)
        while (decodedIds.size > 16) decodedIds.remove(decodedIds.first())
        decodedCount++
        return true
    }

    @Synchronized fun presented(generation: Long, frameId: Long): Boolean {
        if (generation != this.generation || frameId <= lastPresentedId || frameId !in decodedIds) return false
        lastPresentedId = frameId
        decodedIds.removeAll { it <= frameId }
        presentedCount++
        return true
    }
}

/** MediaCodec callbacks key by PTS; repeated RTP timestamps may never alias old frame metadata. */
internal class NativeRtpCodecTimeline {
    private var previous = -1L
    fun next(mediaTimeUs: Long): Long {
        require(mediaTimeUs >= 0 && previous < Long.MAX_VALUE)
        return maxOf(previous + 1, mediaTimeUs).also { previous = it }
    }
}

/** Capture at local RTP ingress and retain through codec output; never stamp a new owner at dequeue. */
internal data class NativeRtpInputIdentity(val frameId: Long, val renderingGeneration: Long) {
    fun ownsRendering(currentGeneration: Long): Boolean = renderingGeneration == currentGeneration
}
