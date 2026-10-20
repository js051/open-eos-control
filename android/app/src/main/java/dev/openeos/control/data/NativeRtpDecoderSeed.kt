package dev.openeos.control.data

/** A real picture and its configuration keep the identity captured at RTP ingress. */
internal data class NativeRtpDecoderSeed<T>(
    val identity: NativeRtpInputIdentity,
    val input: T,
)

/** Observe before configuration resolution so rejected VCL still leaves a reference gap. */
internal class NativeRtpPictureSequence {
    private var sequence = 0L
    fun observe(hasPicture: Boolean): Long? = if (hasPicture) ++sequence else null
}

internal enum class NativeRtpInputAdmission { QUEUE, DROP, RECONFIGURE }

internal enum class NativeRtpInputSlotAdmission { SUBMIT, RETIRED, RETIRE_CODEC }

/** An acquired native slot cannot be abandoned on a codec that will continue running. */
internal fun nativeRtpInputSlotAdmission(
    ownsDecoder: Boolean,
    identity: NativeRtpInputIdentity,
    renderingGeneration: Long,
): NativeRtpInputSlotAdmission = when {
    !ownsDecoder -> NativeRtpInputSlotAdmission.RETIRED
    !identity.ownsRendering(renderingGeneration) -> NativeRtpInputSlotAdmission.RETIRE_CODEC
    else -> NativeRtpInputSlotAdmission.SUBMIT
}

/** Configuration equality is content equality; declarations not referenced by a picture do not enter here. */
internal class NativeRtpDecoderInputGate<C>(private val configuration: C) {
    private val continuity = NativeVideoDecodeContinuity()

    fun admit(
        identity: NativeRtpInputIdentity,
        renderingGeneration: Long,
        candidateConfiguration: C,
        keyFrame: Boolean,
    ): NativeRtpInputAdmission {
        if (!identity.ownsRendering(renderingGeneration)) {
            continuity.dropped()
            return NativeRtpInputAdmission.DROP
        }
        if (candidateConfiguration != configuration) {
            continuity.dropped()
            return if (keyFrame) NativeRtpInputAdmission.RECONFIGURE else NativeRtpInputAdmission.DROP
        }
        return if (continuity.admit(identity.frameId, keyFrame)) NativeRtpInputAdmission.QUEUE
            else NativeRtpInputAdmission.DROP
    }

    fun submitted() { continuity.accepted() }
    fun dropped() { continuity.dropped() }
}

/**
 * Retains only an unsubmitted decoder-start IDR through the native writer drain.
 *
 * Taking a configuration snapshot is not consuming a picture. A cancelled codec setup
 * or a pre-input GPU fallback must leave that exact picture for the next owner. Once
 * MediaCodec accepted it, neither a fallback nor a Surface replacement may replay it.
 * The caller serializes successful native submission and [submitted] with retirement.
 */
internal class NativeRtpDecoderSeedOwner<T> {
    private var decoderGeneration: Long? = null
    private var seed: NativeRtpDecoderSeed<T>? = null

    @Synchronized fun restart(
        generation: Long,
        renderingGeneration: Long,
        replacement: NativeRtpDecoderSeed<T>? = null,
    ) {
        decoderGeneration = generation
        seed = (replacement ?: seed)?.takeIf { it.identity.ownsRendering(renderingGeneration) }
    }

    @Synchronized fun peek(generation: Long, renderingGeneration: Long): NativeRtpDecoderSeed<T>? {
        if (generation != decoderGeneration) return null
        if (seed?.identity?.ownsRendering(renderingGeneration) == false) seed = null
        return seed
    }

    @Synchronized fun offer(
        generation: Long,
        renderingGeneration: Long,
        candidate: NativeRtpDecoderSeed<T>,
    ): Boolean {
        if (generation != decoderGeneration || !candidate.identity.ownsRendering(renderingGeneration)) return false
        if (seed?.identity?.ownsRendering(renderingGeneration) == false) seed = null
        if (seed != null) return false
        seed = candidate
        return true
    }

    @Synchronized fun submitted(generation: Long, candidate: NativeRtpDecoderSeed<T>): Boolean {
        if (generation != decoderGeneration || seed !== candidate) return false
        seed = null
        return true
    }

    @Synchronized fun clear() {
        decoderGeneration = null
        seed = null
    }
}
