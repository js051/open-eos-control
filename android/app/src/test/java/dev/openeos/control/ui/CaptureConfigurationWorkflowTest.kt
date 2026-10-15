package dev.openeos.control.ui

import dev.openeos.control.data.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CaptureConfigurationWorkflowTest {
    private val request = CaptureConfigurationRequest(CaptureConfigurationOperation.SET_FILE_NAMING, "AB", CameraFileNamingField.MOVIE_INDEX)
    private fun draft(owner: Any = Any(), request: CaptureConfigurationRequest = this.request) =
        CaptureConfigurationDraft(CaptureConfigurationOwner(owner, 1), "SYNTHETIC-draft", 0, request)
    @Test fun preflightFailureIsNotSentAndExplicitRecoveryNeverWrites() = runBlocking {
        var writes = 0; var reads = 0
        val attempt = CaptureConfigurationAttempt(draft(), CaptureConfigurationAccess("https://synthetic.invalid", { _, _ ->
            writes++; error("SYNTHETIC preflight") }, { reads++; CaptureConfigurationReadback(fileNaming = syntheticCaptureNaming()) }), { true }, {})
        attempt.run()
        assertEquals(CaptureConfigurationWriteEvidence.NOT_SENT, attempt.snapshot().evidence.writeEvidence)
        attempt.recover()
        assertEquals(1, writes); assertEquals(1, reads)
        assertEquals(CaptureConfigurationWriteEvidence.NOT_SENT, attempt.snapshot().evidence.writeEvidence)
        assertEquals(CaptureConfigurationReadEvidence.MATCHED, attempt.snapshot().evidence.readbackEvidence)
    }
    @Test fun lostAckFollowedByMatchingReadDoesNotProveWrite() = runBlocking {
        var writes = 0; var reads = 0
        val attempt = CaptureConfigurationAttempt(draft(), CaptureConfigurationAccess("https://synthetic.invalid", { _, evidence ->
            evidence.beforeDispatch("PUT /SYNTHETIC/naming"); writes++; error("SYNTHETIC lost response")
        }, { reads++; CaptureConfigurationReadback(fileNaming = syntheticCaptureNaming()) }), { true }, {})
        attempt.run(); attempt.recover(); attempt.recover()
        assertEquals(1, writes); assertEquals(2, reads)
        assertEquals(CaptureConfigurationWriteEvidence.UNKNOWN, attempt.snapshot().evidence.writeEvidence)
        assertEquals(CaptureConfigurationReadEvidence.MATCHED, attempt.snapshot().evidence.readbackEvidence)
        assertFalse(canConsumeCaptureConfigurationDraft(attempt.snapshot(), "SYNTHETIC-draft", 0, attempt.draft.owner.connection, 1))
    }
    @Test fun acknowledgedWriteReadbackFailureCanBeRecoveredWithoutResending() = runBlocking {
        var writes = 0
        val attempt = CaptureConfigurationAttempt(draft(), CaptureConfigurationAccess("https://synthetic.invalid", { _, evidence ->
            evidence.beforeDispatch("PUT /SYNTHETIC/naming"); writes++; evidence.acknowledge("AB"); error("SYNTHETIC read failure")
        }, { CaptureConfigurationReadback(fileNaming = syntheticCaptureNaming()) }), { true }, {})
        attempt.run()
        assertEquals(CaptureConfigurationWriteEvidence.ACKNOWLEDGED, attempt.snapshot().evidence.writeEvidence)
        assertEquals(CaptureConfigurationReadEvidence.UNAVAILABLE, attempt.snapshot().evidence.readbackEvidence)
        attempt.recover()
        assertEquals(1, writes)
        assertEquals(CaptureConfigurationReadEvidence.MATCHED, attempt.snapshot().evidence.readbackEvidence)
    }
    @Test fun validAckAndStrictDifferentReadbackStayAcknowledgedAndMismatch() = runBlocking {
        val attempt = CaptureConfigurationAttempt(draft(), CaptureConfigurationAccess("https://synthetic.invalid", { _, evidence ->
            evidence.beforeDispatch("PUT /SYNTHETIC/naming"); evidence.acknowledge("AB")
            evidence.observe(CaptureConfigurationReadback(fileNaming = syntheticCaptureNaming("CD"))); error("SYNTHETIC mismatch")
        }, { error("No implicit recovery") }), { true }, {})
        attempt.run()
        assertEquals(CaptureConfigurationWriteEvidence.ACKNOWLEDGED, attempt.snapshot().evidence.writeEvidence)
        assertEquals(CaptureConfigurationReadEvidence.MISMATCH, attempt.snapshot().evidence.readbackEvidence)
        assertEquals("CD", attempt.snapshot().evidence.observedValue)
    }
    @Test fun directoryAckCanDifferAndRefreshNeverClaimsExactFolderCreation() = runBlocking {
        val directory = CaptureConfigurationRequest(CaptureConfigurationOperation.CREATE_DIRECTORY, "ABCDE")
        val attempt = CaptureConfigurationAttempt(draft(request = directory), CaptureConfigurationAccess("https://synthetic.invalid", { _, evidence ->
            evidence.beforeDispatch("POST /SYNTHETIC/directory"); evidence.acknowledge("OTHER"); null
        }, { CaptureConfigurationReadback(directorySelection = "100OTHER", directoryValues = listOf("100OTHER")) }), { true }, {})
        attempt.run(); attempt.recover()
        assertEquals("OTHER", attempt.snapshot().evidence.acknowledgedValue)
        assertEquals(CaptureConfigurationReadEvidence.REFRESHED, attempt.snapshot().evidence.readbackEvidence)
        assertFalse(canConsumeCaptureConfigurationDraft(attempt.snapshot(), "SYNTHETIC-draft", 0, attempt.draft.owner.connection, 1))
    }
    @Test fun cancelBeforeDispatchPreventsAnyWrite() = runBlocking {
        var writes = 0
        val attempt = CaptureConfigurationAttempt(draft(), CaptureConfigurationAccess("https://synthetic.invalid", { _, evidence ->
            evidence.beforeDispatch("PUT /SYNTHETIC/naming"); writes++; null
        }, { error("Not requested") }), { true }, {})
        attempt.cancel(); attempt.run()
        assertEquals(0, writes); assertTrue(attempt.snapshot().cancelled)
        assertEquals(CaptureConfigurationWriteEvidence.NOT_SENT, attempt.snapshot().evidence.writeEvidence)
    }
    @Test fun lateValidatedAckSurvivesCancellationButNeverConsumesDraft() = runBlocking {
        val dispatched = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val attempt = CaptureConfigurationAttempt(draft(), CaptureConfigurationAccess("https://synthetic.invalid", { _, evidence ->
            evidence.beforeDispatch("PUT /SYNTHETIC/naming"); dispatched.complete(Unit)
            withContext(NonCancellable) { release.await(); evidence.acknowledge("AB") }
            null
        }, { error("Not requested") }), { true }, {})
        val job = launch { attempt.run() }; dispatched.await(); attempt.cancel(); job.cancel(); release.complete(Unit); job.join()
        assertEquals(CaptureConfigurationWriteEvidence.ACKNOWLEDGED, attempt.snapshot().evidence.writeEvidence)
        assertEquals(CaptureConfigurationReadEvidence.UNAVAILABLE, attempt.snapshot().evidence.readbackEvidence)
        assertTrue(attempt.snapshot().cancelled)
    }
    @Test fun cancelledDirectoryLateAckStaysInHistoryWithoutReplacingCurrentSummary() = runBlocking {
        val dispatched = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val directory = CaptureConfigurationRequest(CaptureConfigurationOperation.CREATE_DIRECTORY, "ABCDE")
        var summary: String? = "PRIOR"
        val attempt = CaptureConfigurationAttempt(draft(request = directory), CaptureConfigurationAccess("https://synthetic.invalid", { _, evidence ->
            evidence.beforeDispatch("POST /SYNTHETIC/directory"); dispatched.complete(Unit)
            withContext(NonCancellable) { release.await(); evidence.acknowledge("OTHER") }
            null
        }, { CaptureConfigurationReadback(directorySelection = "100OTHER", directoryValues = listOf("100OTHER")) }), { true }, {
            summary = captureConfigurationDirectorySummary(it, summary)
        })
        val job = launch { attempt.run() }; dispatched.await(); attempt.cancel(); job.cancel(); release.complete(Unit); job.join()
        assertEquals("OTHER", attempt.snapshot().evidence.acknowledgedValue)
        assertEquals(CaptureConfigurationWriteEvidence.ACKNOWLEDGED, attempt.snapshot().evidence.writeEvidence)
        assertEquals("PRIOR", summary)
        // Fresh explicit recovery still publishes settings independently of the sticky write cancellation.
        val readback = requireNotNull(attempt.recover())
        val settings = mergeCaptureConfigurationDirectorySettings(emptyList(), readback, true)
        assertEquals("100OTHER", settings.single().value)
        assertTrue(attempt.snapshot().cancelled)
        assertEquals("PRIOR", summary)
        assertEquals("OTHER", captureConfigurationDirectorySummary(attempt.snapshot().copy(cancelled = false), "PRIOR"))
    }
    @Test fun oldOwnerCompletionCannotPublishToReplacementEvenAtSameAddress() = runBlocking {
        val owner = Any(); val replacement = Any(); var current = owner
        val dispatched = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val records = mutableListOf<CaptureConfigurationReceipt>()
        val attempt = CaptureConfigurationAttempt(draft(owner), CaptureConfigurationAccess("https://synthetic.invalid", { _, evidence ->
            evidence.beforeDispatch("PUT /SYNTHETIC/naming"); dispatched.complete(Unit); release.await(); evidence.acknowledge("AB"); null
        }, { error("No replacement reads") }), { current === owner }, { records += it })
        val job = launch { attempt.run() }; dispatched.await(); current = replacement; release.complete(Unit); job.join()
        assertEquals(CaptureConfigurationWriteEvidence.ACKNOWLEDGED, records.last().evidence.writeEvidence)
        assertFalse(canPublishCaptureConfiguration(records.last(), attempt.id, replacement, 1))
        assertNull(attempt.recover())
    }
    @Test fun newerDraftIsNeverConsumedByOlderSuccess() {
        val d = draft(); val receipt = CaptureConfigurationReceipt("SYNTHETIC", d, "https://synthetic.invalid",
            CaptureConfigurationSnapshot(request, CaptureConfigurationWriteEvidence.ACKNOWLEDGED, CaptureConfigurationReadEvidence.MATCHED), running = false)
        assertTrue(canConsumeCaptureConfigurationDraft(receipt, d.token, 0, d.owner.connection, 1))
        assertFalse(canConsumeCaptureConfigurationDraft(receipt, d.token, 1, d.owner.connection, 1))
        assertFalse(canConsumeCaptureConfigurationDraft(receipt, "SYNTHETIC-new-draft", 0, d.owner.connection, 1))
    }
    @Test fun recoveryIsSingleFlightAndNeverDispatchesMutation() = runBlocking {
        var writes = 0; var reads = 0; val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val attempt = CaptureConfigurationAttempt(draft(), CaptureConfigurationAccess("https://synthetic.invalid", { _, _ -> writes++; null }, {
            reads++; entered.complete(Unit); release.await(); CaptureConfigurationReadback(fileNaming = syntheticCaptureNaming())
        }), { true }, {})
        attempt.run(); val read = launch { attempt.recover() }; entered.await()
        assertNull(attempt.recover()); release.complete(Unit); read.join()
        assertEquals(1, writes); assertEquals(1, reads)
    }
    @Test fun recoveryFailurePreservesAckAndDoesNotWriteAgain() = runBlocking {
        var writes = 0
        val attempt = CaptureConfigurationAttempt(draft(), CaptureConfigurationAccess("https://synthetic.invalid", { _, e ->
            e.beforeDispatch("PUT /SYNTHETIC/naming"); writes++; e.acknowledge("AB"); null
        }, { error("SYNTHETIC old Bridge 404") }), { true }, {})
        attempt.run(); attempt.recover()
        assertEquals(1, writes); assertEquals(CaptureConfigurationWriteEvidence.ACKNOWLEDGED, attempt.snapshot().evidence.writeEvidence)
        assertEquals(CaptureConfigurationReadEvidence.UNAVAILABLE, attempt.snapshot().evidence.readbackEvidence)
    }
    @Test fun sameLogicalWriteCannotBeRunOrDispatchedTwice() = runBlocking {
        val evidence = CaptureConfigurationEvidence(request); evidence.beforeDispatch("PUT /SYNTHETIC/naming")
        assertTrue(runCatching { evidence.beforeDispatch("PUT /SYNTHETIC/naming") }.isFailure)
        val attempt = CaptureConfigurationAttempt(draft(), CaptureConfigurationAccess("https://synthetic.invalid", { _, _ -> null }, { error("Not requested") }), { true }, {})
        attempt.run(); assertTrue(runCatching { attempt.run() }.isFailure)
    }
    @Test fun historyBoundIsTwentyAndLateEvictedRowsAreNotReinserted() {
        val d = draft(); var history = emptyList<CaptureConfigurationReceipt>()
        repeat(21) { history = captureConfigurationHistory(history, CaptureConfigurationReceipt("SYNTHETIC-$it", d,
            "https://synthetic.invalid", CaptureConfigurationSnapshot(request), running = false)) }
        assertEquals(20, history.size); assertEquals("SYNTHETIC-20", history.first().id)
        assertFalse(history.any { it.id == "SYNTHETIC-0" })
        val late = CaptureConfigurationReceipt("SYNTHETIC-0", d, "https://synthetic.invalid", CaptureConfigurationSnapshot(request))
        val settled = history.map { if (it.id == late.id) late else it }
        assertEquals(history, settled)
    }
    @Test fun directorySelectionRequiresUniqueBoundedLegalValuesAndMembership() {
        assertEquals("100ABCDE", validCaptureDirectorySelection("100ABCDE", listOf("100ABCDE", "101OTHER")))
        assertNull(validCaptureDirectorySelection("100ABCDE", listOf("100ABCDE", "100ABCDE")))
        assertNull(validCaptureDirectorySelection("100ABCDE", listOf("101OTHER")))
        assertNull(validCaptureDirectorySelection("ABCDE", listOf("ABCDE")))
        assertNull(validCaptureDirectorySelection("100ABCDE", List(257) { "100ABCDE" }))
        assertNull(validCaptureDirectorySelection("100ABCDE", listOf(1)))
        assertNull(validCaptureDirectorySelection("100ABCDE\n", listOf("100ABCDE\n")))
        for (suffix in listOf("ABCDE\n", "ABCDE\r", "ABCDE\r\n", "ＡＢＣＤＥ")) {
            assertFalse(validCaptureConfigurationAcknowledgement(
                CaptureConfigurationRequest(CaptureConfigurationOperation.CREATE_DIRECTORY, "ABCDE"), suffix))
        }
    }
    @Test fun primitiveAckRequiresActualDispatchAndValidSuffix() {
        val evidence = CaptureConfigurationEvidence(CaptureConfigurationRequest(CaptureConfigurationOperation.CREATE_DIRECTORY, ""))
        assertTrue(runCatching { evidence.acknowledge("ABCDE") }.isFailure)
        evidence.beforeDispatch("POST /SYNTHETIC/directory")
        assertTrue(runCatching { evidence.acknowledge("101ABCDE") }.isFailure)
        assertEquals(CaptureConfigurationWriteEvidence.UNKNOWN, evidence.snapshot().writeEvidence)
    }
    @Test fun consumedRevisionCannotReplayUntilUserEditOrExplicitNewRequest() {
        val ledger = CaptureConfigurationDraftLedger(); val draft = draft()
        assertTrue(ledger.claim(draft)); assertFalse(ledger.claim(draft))
        assertFalse(ledger.claim(draft.copy(request = draft.request.copy(requestedValue = "CD"))))
        assertTrue(ledger.claim(draft.copy(revision = 1)))
        assertTrue(ledger.claim(draft.copy(token = "SYNTHETIC-explicit-new-request")))
    }
    @Test fun freshDirectoryChoicesReplaceStaleChoicesOnlyForTheirOwner() {
        val untouched = CameraSettingControl("iso", "ISO", "100", listOf("100", "200"))
        val old = listOf(untouched, CameraSettingControl("directoryselection", "Capture directory", "100ABCDE", listOf("100ABCDE")))
        val fresh = CaptureConfigurationReadback(directorySelection = "101OTHER", directoryValues = listOf("100ABCDE", "101OTHER"))
        val updated = mergeCaptureConfigurationDirectorySettings(old, fresh, sameOwner = true)
        assertEquals("101OTHER", updated.last().value)
        assertEquals(listOf("100ABCDE", "101OTHER"), updated.last().values)
        assertSame(untouched, updated.first())
        assertSame(old, mergeCaptureConfigurationDirectorySettings(old, fresh, sameOwner = false))
        assertSame(old, mergeCaptureConfigurationDirectorySettings(old, fresh.copy(directoryValues = listOf("100ABCDE")), sameOwner = true))
    }
    @Test fun recoveringOlderRetainedAttemptDoesNotChangeNewestRecordOrDraft() = runBlocking {
        val owner = Any(); var writes = 0
        val access = CaptureConfigurationAccess("https://synthetic.invalid", { _, e ->
            e.beforeDispatch("PUT /SYNTHETIC/naming"); writes++; error("SYNTHETIC lost ACK")
        }, { CaptureConfigurationReadback(fileNaming = syntheticCaptureNaming()) })
        var history = emptyList<CaptureConfigurationReceipt>()
        val first = CaptureConfigurationAttempt(draft(owner), access, { true }, { row -> history = history.map { if (it.id == row.id) row else it } })
        history = captureConfigurationHistory(history, first.snapshot()); first.run()
        val second = CaptureConfigurationAttempt(draft(owner).copy(token = "SYNTHETIC-new"), access, { true }, { row -> history = history.map { if (it.id == row.id) row else it } })
        history = captureConfigurationHistory(history, second.snapshot()); second.run()
        val newest = history.first(); first.recover()
        assertEquals(2, writes); assertEquals(newest, history.first())
        assertEquals(CaptureConfigurationReadEvidence.MATCHED, history.last().evidence.readbackEvidence)
        assertEquals(CaptureConfigurationWriteEvidence.UNKNOWN, history.last().evidence.writeEvidence)
    }
    @Test fun cancelledRecoveryCannotPublishLateSettingsButNewExplicitReadCan() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val attempt = CaptureConfigurationAttempt(draft(), CaptureConfigurationAccess("https://synthetic.invalid", { _, e ->
            e.beforeDispatch("PUT /SYNTHETIC/naming"); e.acknowledge("AB"); null
        }, {
            withContext(NonCancellable) { entered.complete(Unit); release.await() }
            CaptureConfigurationReadback(fileNaming = syntheticCaptureNaming())
        }), { true }, {})
        attempt.run()
        var result: CaptureConfigurationReadback? = null
        val job = launch { result = attempt.recover() }
        entered.await(); attempt.cancel(); job.cancel(); release.complete(Unit); job.join()
        assertNull(result)
        assertEquals(CaptureConfigurationWriteEvidence.ACKNOWLEDGED, attempt.snapshot().evidence.writeEvidence)
        assertEquals(CaptureConfigurationReadEvidence.UNAVAILABLE, attempt.snapshot().evidence.readbackEvidence)
        assertTrue(attempt.snapshot().cancelled)
        assertNotNull(attempt.recover())
        assertEquals(CaptureConfigurationReadEvidence.MATCHED, attempt.snapshot().evidence.readbackEvidence)
        assertTrue(attempt.snapshot().cancelled)
    }
    @Test fun successfulDirectoryPostAckReadRefreshesChoicesWithOneReadAndRetainsAckOnReadFailure() = runBlocking {
        val request = CaptureConfigurationRequest(CaptureConfigurationOperation.CREATE_DIRECTORY, "ABCDE")
        for (failRead in listOf(false, true)) {
            var reads = 0
            val evidence = CaptureConfigurationEvidence(request)
            val result = runCatching { captureConfigurationOperation(evidence) {
                evidence.beforeDispatch("POST /SYNTHETIC/session/directories"); evidence.acknowledge("OTHER")
                captureConfigurationPostAckReadback(evidence) {
                    reads++
                    if (failRead) error("SYNTHETIC scoped read failed")
                    CaptureConfigurationReadback(directorySelection = "100OTHER", directoryValues = listOf("100OTHER"))
                }
            } }
            assertEquals(1, reads)
            assertEquals(CaptureConfigurationWriteEvidence.ACKNOWLEDGED, evidence.snapshot().writeEvidence)
            assertEquals(failRead, result.isFailure)
            assertEquals(if (failRead) CaptureConfigurationReadEvidence.UNAVAILABLE else CaptureConfigurationReadEvidence.REFRESHED,
                evidence.snapshot().readbackEvidence)
        }
    }
    @Test fun automaticPostAckReadDoesNoIoAfterCancellationOrOwnerRetirement() = runBlocking {
        for (cancel in listOf(false, true)) {
            var owns = true; var reads = 0
            val evidence = CaptureConfigurationEvidence(CaptureConfigurationRequest(CaptureConfigurationOperation.CREATE_DIRECTORY, "ABCDE"),
                checkOwner = { if (!owns) throw CancellationException("SYNTHETIC retired owner") })
            evidence.beforeDispatch("POST /SYNTHETIC/session/directories"); evidence.acknowledge("OTHER")
            if (cancel) evidence.cancel() else owns = false
            val failure = runCatching { captureConfigurationOperation(evidence) {
                captureConfigurationPostAckReadback(evidence) { reads++; error("Must not read") }
            } }.exceptionOrNull()
            assertTrue(failure is CaptureConfigurationCancellation)
            assertEquals(0, reads)
            assertEquals("OTHER", evidence.snapshot().acknowledgedValue)
            assertEquals(CaptureConfigurationReadEvidence.UNAVAILABLE, evidence.snapshot().readbackEvidence)
        }
    }
    @Test fun directClientOperationExceptionRetainsAckReceipt() = runBlocking {
        val evidence = CaptureConfigurationEvidence(request)
        val failure = runCatching { captureConfigurationOperation(evidence) {
            evidence.beforeDispatch("PUT /SYNTHETIC/naming"); evidence.acknowledge("AB"); error("SYNTHETIC read failure")
        } }.exceptionOrNull()
        assertTrue(failure is CaptureConfigurationException)
        assertEquals(CaptureConfigurationWriteEvidence.ACKNOWLEDGED, (failure as CaptureConfigurationException).receipt.writeEvidence)
        assertEquals(CaptureConfigurationReadEvidence.UNAVAILABLE, failure.receipt.readbackEvidence)
    }
}
