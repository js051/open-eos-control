package dev.openeos.control.ui

import dev.openeos.control.data.*
import org.junit.Assert.*
import org.junit.Test

internal data class CaptureNamingVector(val id: String, val value: CameraFileNaming, val valid: Boolean)
internal data class CaptureDirectoryVector(val id: String, val current: Any?, val values: List<Any?>, val valid: Boolean)
internal data class CaptureExactFixture(val receipt: List<CaptureReceiptVector>, val naming: List<CaptureNamingVector>, val directory: List<CaptureDirectoryVector>)
class CaptureConfigurationExactValueTest {
    @Test fun all68ReceiptLexicalCasesUseExactAsciiAndCanonicalNumbers() {
        val vectors = loadCaptureExactFixture().receipt; assertEquals(68, vectors.size)
        vectors.forEach { v -> assertEquals(v.id, v.valid, v.request?.let { request -> acceptsCaptureConfigurationBridgeReceipt(request,
            v.status, v.code, v.engine, v.feature, v.version, v.operation, v.stage, v.requested, v.acknowledged, v.fieldPresent, v.field) } ?: false) }
    }
    @Test fun all13CompleteNamingValuesRejectTrailingNewlinesAndUnicode() {
        val vectors = loadCaptureExactFixture().naming; assertEquals(13, vectors.size)
        vectors.forEach { v -> assertEquals(v.id, v.valid, validCaptureConfigurationNaming(v.value)) }
    }
    @Test fun all7DirectorySelectionsRequireFullStringAndMembership() {
        val vectors = loadCaptureExactFixture().directory; assertEquals(7, vectors.size)
        vectors.forEach { v -> assertEquals(v.id, v.valid, validCaptureDirectorySelection(v.current, v.values) != null) }
    }
}
