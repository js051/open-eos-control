package dev.openeos.control.ui

import dev.openeos.control.data.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ShootingSettingWorkflowTest {
    private val owner = Any()
    private fun draft(revision: Long = 1) = ShootingSettingDraft(ShootingSettingOwner(owner, 3), "editor", revision,
        ShootingSettingRequest("iso", "800"))
    private fun attempt(access: ShootingSettingAccess, owns: () -> Boolean = { true }, timeout: Long = 1000) =
        ShootingSettingAttempt(draft(), access, owns, timeout, {})
    private fun response(e: ShootingSettingEvidence) { e.beforeDispatch(); e.responseReceived() }

    @Test fun oneWriteThenExactFreshRead() = runBlocking {
        var writes = 0; var reads = 0
        val attempt = attempt(ShootingSettingAccess("synthetic", { _, e -> writes++; response(e) }, { reads++; ShootingSettingObservation("iso", "800") }))
        assertEquals("800", attempt.run()?.value); assertEquals(1, writes); assertEquals(1, reads)
        assertEquals(ShootingSettingReadEvidence.MATCHED, attempt.snapshot().evidence.readbackEvidence)
    }
    @Test fun responseSurvivesReadFailureAndExplicitRecoveryNeverWrites() = runBlocking {
        var writes = 0; var reads = 0
        val attempt = attempt(ShootingSettingAccess("synthetic", { _, e -> writes++; response(e) }, {
            if (++reads == 1) error("read failure") else ShootingSettingObservation("iso", "400")
        }))
        assertNull(attempt.run()); assertEquals(ShootingSettingWriteEvidence.RESPONSE_RECEIVED, attempt.snapshot().evidence.writeEvidence)
        assertEquals(ShootingSettingReadEvidence.UNAVAILABLE, attempt.snapshot().evidence.readbackEvidence)
        assertEquals("400", attempt.recover()?.value); assertEquals(1, writes); assertEquals(2, reads)
        assertEquals(ShootingSettingReadEvidence.MISMATCH, attempt.snapshot().evidence.readbackEvidence)
    }
    @Test fun unknownWriteDoesNotAutomaticallyReadOrReplay() = runBlocking {
        var writes = 0; var reads = 0
        val attempt = attempt(ShootingSettingAccess("synthetic", { _, e -> writes++; e.beforeDispatch(); error("lost response") }, {
            reads++; ShootingSettingObservation("iso", "800")
        }))
        attempt.run(); assertEquals(1, writes); assertEquals(0, reads)
        attempt.recover(); assertEquals(1, writes); assertEquals(1, reads)
        assertEquals(ShootingSettingWriteEvidence.UNKNOWN, attempt.snapshot().evidence.writeEvidence)
        assertFalse(canConsumeShootingSettingDraft(attempt.snapshot(), "editor", 1, owner, 3))
    }
    @Test fun localRejectionRemainsNotSent() = runBlocking {
        var reads = 0
        val attempt = attempt(ShootingSettingAccess("synthetic", { _, _ -> error("ability changed") }, { reads++; error("unexpected") }))
        attempt.run(); assertEquals(0, reads)
        assertEquals(ShootingSettingWriteEvidence.NOT_SENT, attempt.snapshot().evidence.writeEvidence)
    }
    @Test fun bridgeOutcomeDoesNotTriggerSecondAutomaticGet() = runBlocking {
        var reads = 0
        val attempt = attempt(ShootingSettingAccess("synthetic", { _, e -> response(e); e.observe(ShootingSettingObservation("iso", "400")) },
            { reads++; error("unexpected") }))
        assertEquals("400", attempt.run()?.value); assertEquals(0, reads)
    }
    @Test fun bridgeUnavailableDoesNotTriggerAutomaticRetry() = runBlocking {
        var reads = 0
        val attempt = attempt(ShootingSettingAccess("synthetic", { _, e -> response(e); e.unavailable() }, { reads++; error("unexpected") }))
        assertNull(attempt.run()); assertEquals(0, reads)
    }
    @Test fun duplicateDispatchIsRejected() {
        val evidence = ShootingSettingEvidence(draft().request); evidence.beforeDispatch()
        assertTrue(runCatching { evidence.beforeDispatch() }.isFailure)
    }
    @Test fun cancelBeforeDispatchDoesNotWrite() = runBlocking {
        var dispatched = 0
        val attempt = attempt(ShootingSettingAccess("synthetic", { _, e -> e.beforeDispatch(); dispatched++ }, { error("unexpected") }))
        attempt.cancel(); attempt.run(); assertEquals(0, dispatched)
    }
    @Test fun cancelAfterDispatchRetainsLateCompleteResponseButNoRead() = runBlocking {
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>(); var reads = 0
        val attempt = attempt(ShootingSettingAccess("synthetic", { _, e ->
            e.beforeDispatch(); started.complete(Unit); withContext(NonCancellable) { release.await(); e.responseReceived() }
        }, { reads++; ShootingSettingObservation("iso", "800") }))
        val job = launch { attempt.run() }; started.await(); attempt.cancel(); job.cancel(); release.complete(Unit); job.join()
        assertEquals(0, reads); assertTrue(attempt.snapshot().cancelled)
        assertEquals(ShootingSettingWriteEvidence.RESPONSE_RECEIVED, attempt.snapshot().evidence.writeEvidence)
    }
    @Test fun replacementOwnerCannotPublishLateRead() = runBlocking {
        var owns = true
        val attempt = attempt(ShootingSettingAccess("synthetic", { _, e -> response(e) }, {
            owns = false; ShootingSettingObservation("iso", "800")
        }), { owns })
        assertNull(attempt.run()); assertTrue(attempt.snapshot().cancelled)
        assertFalse(canPublishShootingSetting(attempt.snapshot(), attempt.id, Any(), 3))
        assertNull(attempt.recover())
    }
    @Test fun readTimeoutHasNoRetryAndKeepsResponse() = runBlocking {
        var reads = 0
        val attempt = attempt(ShootingSettingAccess("synthetic", { _, e -> response(e) }, { reads++; delay(1000); error("late") }), timeout = 20)
        attempt.run(); assertEquals(1, reads)
        assertEquals(ShootingSettingWriteEvidence.RESPONSE_RECEIVED, attempt.snapshot().evidence.writeEvidence)
    }
    @Test fun concurrentRecoveryAdmitsOnlyOneRead() = runBlocking {
        val gate = CompletableDeferred<Unit>(); var reads = 0
        val attempt = attempt(ShootingSettingAccess("synthetic", { _, e -> e.beforeDispatch(); error("lost") }, {
            reads++; gate.await(); ShootingSettingObservation("iso", "800")
        }))
        attempt.run(); val first = async { attempt.recover() }; yield()
        assertNull(attempt.recover()); gate.complete(Unit); first.await(); assertEquals(1, reads)
    }
    @Test fun cancelledRecoveryCannotPublishNonCancellableLateRead() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val gate = CompletableDeferred<Unit>()
        val attempt = attempt(ShootingSettingAccess("synthetic", { _, e -> e.beforeDispatch(); error("lost") }, {
            entered.complete(Unit); withContext(NonCancellable) { gate.await() }; ShootingSettingObservation("iso", "800")
        }))
        attempt.run(); val job = launch { assertNull(attempt.recover()) }; entered.await(); job.cancel(); gate.complete(Unit); job.join()
        assertEquals(ShootingSettingReadEvidence.UNAVAILABLE, attempt.snapshot().evidence.readbackEvidence)
    }
    @Test fun draftLedgerAdmitsOnlyNewExplicitRevision() {
        val ledger = ShootingSettingDraftLedger(); assertTrue(ledger.claim(draft())); assertFalse(ledger.claim(draft()))
        assertTrue(ledger.claim(draft(2)))
    }
    @Test fun newerDraftRevisionAndOwnerNeverConsumed() = runBlocking {
        val attempt = attempt(ShootingSettingAccess("synthetic", { _, e -> response(e) }, { ShootingSettingObservation("iso", "800") }))
        attempt.run(); val receipt = attempt.snapshot()
        assertTrue(canConsumeShootingSettingDraft(receipt, "editor", 1, owner, 3))
        assertFalse(canConsumeShootingSettingDraft(receipt, "editor", 2, owner, 3))
        assertFalse(canConsumeShootingSettingDraft(receipt, "new-editor", 1, owner, 3))
        assertFalse(canConsumeShootingSettingDraft(receipt, "editor", 1, owner, 4))
    }
    @Test fun historyIsBoundedAndReplacesExistingRows() {
        var history = emptyList<ShootingSettingReceipt>()
        repeat(25) { n -> history = shootingSettingHistory(history, ShootingSettingReceipt("$n", draft(), "synthetic", ShootingSettingSnapshot(draft().request))) }
        assertEquals(20, history.size); assertEquals("24", history.first().id)
        history = shootingSettingHistory(history, history.first().copy(running = false))
        assertEquals(20, history.size); assertFalse(history.first().running)
    }
    @Test fun noOpHasReadEvidenceWithoutFabricatingResponse() = runBlocking {
        val attempt = attempt(ShootingSettingAccess("synthetic", { _, _ -> }, { ShootingSettingObservation("iso", "800") }))
        attempt.run(); assertEquals(ShootingSettingWriteEvidence.NOT_SENT, attempt.snapshot().evidence.writeEvidence)
        assertFalse(canConsumeShootingSettingDraft(attempt.snapshot(), "editor", 1, owner, 3))
    }
}
