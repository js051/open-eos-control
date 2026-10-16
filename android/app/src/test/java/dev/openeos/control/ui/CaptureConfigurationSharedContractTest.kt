package dev.openeos.control.ui

import dev.openeos.control.data.*
import org.junit.Assert.*
import org.junit.Test

internal data class CaptureReceiptVector(val id: String, val request: CaptureConfigurationRequest?, val status: Int,
    val code: Any?, val engine: Any?, val feature: Any?, val version: Any?, val operation: Any?, val stage: Any?,
    val requested: Any?, val acknowledged: Any?, val fieldPresent: Boolean, val field: Any?, val valid: Boolean)
internal data class CaptureOutcomeVector(val id: String, val directory: Boolean, val dispatched: Boolean, val ack: Boolean,
    val readStatus: String, val matches: Boolean, val write: String, val read: String)
internal data class CaptureOwnershipVector(val id: String, val owner: Boolean, val attempt: Boolean, val retired: Boolean,
    val revision: Boolean, val ack: Boolean, val matched: Boolean, val cancelled: Boolean, val publish: Boolean, val consume: Boolean)
internal data class CaptureRecoveryVector(val id: String, val owner: Boolean, val writeActive: Boolean, val readActive: Boolean,
    val hasRecord: Boolean, val allowed: Boolean, val writes: Int)
internal data class CaptureConfigurationFixture(val receipt: List<CaptureReceiptVector>, val outcome: List<CaptureOutcomeVector>,
    val ownership: List<CaptureOwnershipVector>, val recovery: List<CaptureRecoveryVector>)

internal fun syntheticCaptureNaming(index: String = "AB") = CameraFileNaming("preset_code",
    listOf("preset_code", "usersetting1", "usersetting2"), "SYN_", "SY_", index,
    1, CameraIntegerRange(1, 9999, 1), 1, CameraIntegerRange(1, 999, 1), "SYNTH")

class CaptureConfigurationSharedContractTest {
    @Test fun all49StrictBridgeReceiptsMatchTheirFrozenRequest() {
        val vectors = loadCaptureConfigurationFixture().receipt; assertEquals(49, vectors.size)
        vectors.forEach { v -> assertEquals(v.id, v.valid, v.request?.let { request -> acceptsCaptureConfigurationBridgeReceipt(request,
            v.status, v.code, v.engine, v.feature, v.version, v.operation, v.stage, v.requested, v.acknowledged, v.fieldPresent, v.field) } ?: false) }
    }
    @Test fun all48EvidenceAxesRemainIndependent() {
        val vectors = loadCaptureConfigurationFixture().outcome; assertEquals(48, vectors.size)
        vectors.forEach { v ->
            val request = if (v.directory) CaptureConfigurationRequest(CaptureConfigurationOperation.CREATE_DIRECTORY, "ABCDE")
                else CaptureConfigurationRequest(CaptureConfigurationOperation.SET_FILE_NAMING, "AB", CameraFileNamingField.MOVIE_INDEX)
            val evidence = CaptureConfigurationEvidence(request)
            if (v.dispatched) evidence.beforeDispatch("PUT /SYNTHETIC/configuration")
            if (v.dispatched && v.ack) evidence.acknowledge(request.requestedValue)
            when (v.readStatus) {
                "unavailable" -> evidence.unavailable()
                "success" -> evidence.observe(if (v.directory) CaptureConfigurationReadback(directorySelection = "100ABCDE", directoryValues = listOf("100ABCDE"))
                    else CaptureConfigurationReadback(fileNaming = syntheticCaptureNaming(if (v.matches) "AB" else "CD")))
            }
            assertEquals(v.id, v.write, evidence.snapshot().writeEvidence.name.lowercase().replace('_', '-'))
            assertEquals(v.id, v.read, evidence.snapshot().readbackEvidence.name.lowercase().replace('_', '-'))
        }
    }
    @Test fun all128OwnershipCasesKeepDraftAndReplacementOwnerIsolated() {
        val vectors = loadCaptureConfigurationFixture().ownership; assertEquals(128, vectors.size)
        vectors.forEach { v ->
            val owner = Any(); val connection = if (v.owner) owner else Any()
            val request = CaptureConfigurationRequest(CaptureConfigurationOperation.SET_FILE_NAMING, "AB", CameraFileNamingField.MOVIE_INDEX)
            val receipt = CaptureConfigurationReceipt("SYNTHETIC-attempt", CaptureConfigurationDraft(CaptureConfigurationOwner(owner, 1),
                "SYNTHETIC-draft", 3, request), "https://synthetic.invalid", CaptureConfigurationSnapshot(request,
                if (v.ack) CaptureConfigurationWriteEvidence.ACKNOWLEDGED else CaptureConfigurationWriteEvidence.UNKNOWN,
                if (v.matched) CaptureConfigurationReadEvidence.MATCHED else CaptureConfigurationReadEvidence.UNAVAILABLE),
                running = false, cancelled = v.cancelled)
            val publish = canPublishCaptureConfiguration(receipt, if (v.attempt) receipt.id else "SYNTHETIC-other", connection, 1, v.retired)
            assertEquals(v.id, v.publish, publish)
            assertEquals(v.id, v.consume, publish && canConsumeCaptureConfigurationDraft(receipt,
                receipt.draft.token, if (v.revision) 3 else 4, connection, 1))
        }
    }
    @Test fun all16RecoveryCasesAdmitOnlyAnIdleOwnedRecord() {
        val vectors = loadCaptureConfigurationFixture().recovery; assertEquals(16, vectors.size)
        vectors.forEach { v ->
            assertEquals(v.id, v.allowed, canRecoverCaptureConfiguration(v.owner, v.writeActive, v.readActive, v.hasRecord))
            assertEquals(v.id, 0, v.writes)
        }
    }
}
