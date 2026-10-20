package dev.openeos.control.data

internal const val GOLDFISH_AVC_DECODER = "c2.goldfish.h264.decoder"

internal fun nativeRtpUsesGoldfishHostDecoder(defaultName: String, hardware: String): Boolean =
    defaultName == GOLDFISH_AVC_DECODER && hardware in setOf("goldfish", "ranchu")

/**
 * The goldfish host-render path can report BT.709 while producing BT.601 RGB.
 * Prefer an installed Android reference decoder only for that exact component.
 * Do not replace real-device/vendor defaults, guess aliases, or install codecs.
 */
internal fun nativeRtpReferenceAvcDecoder(
    defaultName: String,
    hardware: String,
    availableAvcDecoders: Collection<String>,
): String? {
    if (!nativeRtpUsesGoldfishHostDecoder(defaultName, hardware)) return null
    return listOf("c2.android.avc.decoder", "OMX.google.h264.decoder").firstOrNull { it in availableAvcDecoders }
}
