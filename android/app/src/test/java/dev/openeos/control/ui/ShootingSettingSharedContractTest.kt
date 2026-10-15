package dev.openeos.control.ui

import dev.openeos.control.data.*
import org.junit.Assert.*
import org.junit.Test

internal data class ShootingWireVector(val id: String, val request: ShootingSettingRequest, val expectedEngine: String,
    val status: Int, val version: Any?, val engine: Any?, val key: Any?, val requested: Any?, val write: Any?, val read: Any?,
    val setting: CameraSettingControl?, val expectedWrite: String, val expectedRead: String)
internal data class ShootingOwnerVector(val owner: Boolean, val attempt: Boolean, val revision: Boolean,
    val cancelled: Boolean, val response: Boolean, val matched: Boolean, val publish: Boolean, val consume: Boolean)
internal data class ShootingOutcomeVector(val dispatched: Boolean, val response: Boolean, val read: String, val matches: Boolean,
    val expectedWrite: String, val expectedRead: String)
internal data class ShootingFixture(val wire: List<ShootingWireVector>, val owners: List<ShootingOwnerVector>, val outcomes: List<ShootingOutcomeVector>)

class ShootingSettingSharedContractTest {
    @Test fun all24WireCasesRetainOnlyStrictlyBoundResponseEvidence() {
        val rows = loadShootingSettingFixture().wire; assertEquals(24, rows.size)
        rows.forEach { row ->
            val evidence = ShootingSettingEvidence(row.request); evidence.beforeDispatch()
            applyShootingSettingBridgeOutcome(evidence, row.expectedEngine, row.status, row.version, row.engine, row.key,
                row.requested, row.write, row.read, row.setting)
            assertEquals(row.id, row.expectedWrite, evidence.snapshot().writeEvidence.name.lowercase().replace('_', '-'))
            // A missing/invalid base does not permit read evidence from an unbound response.
            val read = if (evidence.snapshot().writeEvidence == ShootingSettingWriteEvidence.UNKNOWN) "unavailable"
                else evidence.snapshot().readbackEvidence.name.lowercase().replace('_', '-')
            assertEquals(row.id, row.expectedRead, read)
        }
    }
    @Test fun all64OwnershipCasesKeepCancelledAndReplacementDraftsIsolated() {
        val rows = loadShootingSettingFixture().owners; assertEquals(64, rows.size)
        rows.forEach { row ->
            val owner = Any(); val connection = if (row.owner) owner else Any()
            val request = ShootingSettingRequest("iso", "800")
            val receipt = ShootingSettingReceipt("attempt", ShootingSettingDraft(ShootingSettingOwner(owner, 1), "editor", 3, request),
                "synthetic", ShootingSettingSnapshot(request,
                    if (row.response) ShootingSettingWriteEvidence.RESPONSE_RECEIVED else ShootingSettingWriteEvidence.UNKNOWN,
                    if (row.matched) ShootingSettingReadEvidence.MATCHED else ShootingSettingReadEvidence.UNAVAILABLE),
                running = false, cancelled = row.cancelled)
            val publish = canPublishShootingSetting(receipt, if (row.attempt) "attempt" else "other", connection, 1)
            assertEquals(row.publish, publish)
            assertEquals(row.consume, publish && canConsumeShootingSettingDraft(receipt, "editor", if (row.revision) 3 else 4, connection, 1))
        }
    }
    @Test fun all24OutcomeCasesKeepIndependentAxes() {
        val rows = loadShootingSettingFixture().outcomes; assertEquals(24, rows.size)
        rows.forEach { row ->
            val evidence = ShootingSettingEvidence(ShootingSettingRequest("iso", "800"))
            if (row.dispatched) evidence.beforeDispatch()
            if (row.dispatched && row.response) evidence.responseReceived()
            when (row.read) {
                "unavailable" -> evidence.unavailable()
                "success" -> evidence.observe(ShootingSettingObservation("iso", if (row.matches) "800" else "400"))
            }
            assertEquals(row.expectedWrite, evidence.snapshot().writeEvidence.name.lowercase().replace('_', '-'))
            assertEquals(row.expectedRead, evidence.snapshot().readbackEvidence.name.lowercase().replace('_', '-'))
        }
    }
}
