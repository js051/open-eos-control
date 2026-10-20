package dev.openeos.control.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeRtpDecoderSelectionTest {
    @Test fun exactGoldfishComponentPrefersAvailableCodec2Reference() {
        assertEquals("c2.android.avc.decoder", nativeRtpReferenceAvcDecoder(
            GOLDFISH_AVC_DECODER, "ranchu", listOf("OMX.google.h264.decoder", "c2.android.avc.decoder"),
        ))
    }

    @Test fun legacyReferenceIsUsedOnlyWhenAdvertised() {
        assertEquals("OMX.google.h264.decoder", nativeRtpReferenceAvcDecoder(
            GOLDFISH_AVC_DECODER, "goldfish", listOf("c2.vendor.avc.decoder", "OMX.google.h264.decoder"),
        ))
    }

    @Test fun missingReferenceKeepsTheSystemDefaultWithoutGuessing() {
        assertNull(nativeRtpReferenceAvcDecoder(GOLDFISH_AVC_DECODER, "ranchu", emptyList()))
        assertNull(nativeRtpReferenceAvcDecoder(GOLDFISH_AVC_DECODER, "ranchu", listOf(
            "c2.vendor.avc.decoder", "c2.android.hevc.decoder", "c2.android.avc.encoder",
            "c2.android.avc.decoder.secure",
        )))
    }

    @Test fun realDeviceDefaultsAreNotForcedToSoftware() {
        for (name in listOf("c2.qti.avc.decoder", "c2.exynos.h264.decoder", "OMX.MTK.VIDEO.DECODER.AVC")) {
            assertNull(nativeRtpReferenceAvcDecoder(
                name, "ranchu", listOf("c2.android.avc.decoder", "OMX.google.h264.decoder"),
            ))
        }
    }

    @Test fun otherEmulatorCodecsAndSimilarNamesAreNotBlacklisted() {
        for (name in listOf(
            "c2.android.avc.decoder", "OMX.google.h264.decoder", "c2.goldfish.hevc.decoder",
            "c2.goldfish.h264.decoder.secure", "vendor.c2.goldfish.h264.decoder",
        )) {
            assertNull(nativeRtpReferenceAvcDecoder(name, "ranchu", listOf("c2.android.avc.decoder")))
        }
    }

    @Test fun exactCodecNameAloneDoesNotBlacklistUnknownOrRealHardware() {
        for (hardware in listOf("qcom", "exynos", "mt6893", "", "ranchu-custom", "goldfish-custom")) {
            assertNull(nativeRtpReferenceAvcDecoder(GOLDFISH_AVC_DECODER, hardware, listOf("c2.android.avc.decoder")))
        }
    }
}
