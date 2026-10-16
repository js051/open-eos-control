package dev.openeos.control.ui

import dev.openeos.control.data.*
import org.junit.Assert.*
import org.junit.Test

internal data class MaintenanceClockVector(val name: String, val reference: Map<String, Any?>?, val observation: Map<String, Any?>?, val valid: Boolean, val matched: Boolean)
internal data class MaintenanceWireVector(val name: String, val status: Int, val engine: String, val operation: String,
    val autoPowerOff: Boolean, val response: Map<String, Any?>, val accepted: Boolean, val clock: String)
internal data class MaintenanceOwnerVector(val owner: Boolean, val attempt: Boolean, val cancelled: Boolean, val retired: Boolean,
    val response: Boolean, val stopped: Boolean, val operation: String, val autoPowerOff: Boolean, val wasLive: Boolean,
    val reachable: Boolean, val safe: Boolean, val publish: Boolean, val restore: Boolean)
internal data class MaintenanceFixture(val clocks: List<MaintenanceClockVector>, val wire: List<MaintenanceWireVector>, val owners: List<MaintenanceOwnerVector>)

class MaintenanceSharedContractTest {
    @Test fun all32ClockVectorsPreserveBoundsZoneDstAndExactTypes() {
        val rows = loadMaintenanceFixture().clocks; assertEquals(32, rows.size)
        rows.forEach { row ->
            val reference = maintenanceClockReference(row.reference)
            val observation = maintenanceClockObservation(row.observation)
            val valid = reference != null && observation != null
            assertEquals(row.name, row.valid, valid)
            assertEquals(row.name, row.matched, valid && maintenanceClockMatches(reference!!, observation!!))
        }
    }
    @Test fun all28WireVectorsKeepResponseAndClockEvidenceSeparate() {
        val rows = loadMaintenanceFixture().wire; assertEquals(28, rows.size)
        rows.forEach { row ->
            val action = MaintenanceAction.entries.single { it.wireName == row.operation }
            val request = MaintenanceRequest(action, if (action == MaintenanceAction.CLOCK_SYNC) MaintenanceClockTarget(1_700_000_000_000, 28_800, false) else null, row.autoPowerOff)
            val evidence = MaintenanceEvidence(request)
            if (request.needsStop) { evidence.beginEventStop(); evidence.eventStopped(); evidence.beginLiveViewStop(); evidence.liveViewStopped() }
            evidence.beforeDispatch()
            val accepted = applyMaintenanceBridgeOutcome(evidence, row.engine, row.status, row.response)
            assertEquals(row.name, row.accepted, accepted)
            assertEquals(row.name, row.accepted, evidence.snapshot().command == MaintenanceCommandEvidence.RESPONSE_RECEIVED)
            val clock = if (!accepted) "unavailable" else evidence.snapshot().clockRead.name.lowercase().replace('_', '-')
            assertEquals(row.name, row.clock, clock)
        }
    }
    @Test fun all70OwnershipVectorsGatePublicationAndExplicitRestore() {
        val rows = loadMaintenanceFixture().owners; assertEquals(70, rows.size)
        rows.forEach { row ->
            assertEquals(row.publish, maintenanceMayPublish(row.owner, row.attempt, row.cancelled, row.retired))
            assertEquals(row.restore, maintenanceMayRestore(row.owner, row.attempt, row.cancelled, row.retired,
                row.response, row.stopped, row.operation, row.autoPowerOff, row.wasLive, row.reachable, row.safe))
        }
    }
}
