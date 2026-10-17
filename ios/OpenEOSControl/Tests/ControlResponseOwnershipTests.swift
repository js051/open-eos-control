import Foundation
import OpenEOSCore
import XCTest
import UIKit

@testable import OpenEOSControl

/// These peers never open a socket and deliberately ignore request cancellation.
/// CCAPI delivers a real late success; Bridge may convert the same late ACK into
/// sessionChanged. Both paths must preserve the replacement app session.
@MainActor
final class ControlResponseOwnershipTests: XCTestCase {
    func testEveryRetiredControlResponsePreservesReplacementAndItsSameCategoryBusy() async throws {
        for bridge in [false, true] {
            for action in ControlOwnerAction.allCases {
                for fails in [false, true] {
                    try await assertRetired(action, bridge: bridge, fails: fails)
                }
            }
        }
    }

    func testRetiredCapabilityAndSensorSnapshotReadbacksCannotPublishOrRecoverReplacement() async throws {
        let boundaries: [(ControlOwnerAction, ControlOwnerWire)] = [
            (.directory, .capabilities), (.naming, .namingReadback), (.setting, .capabilities),
            (.clean, .info),
        ]
        for bridge in [false, true] {
            for (action, boundary) in boundaries {
                for fails in [false, true] {
                    try await assertRetired(action, bridge: bridge, fails: fails, readback: boundary)
                }
            }
        }
    }

    func testRetiredSleepAndCleaningCannotReplaceReplacementEventLoopOrRestartItsLiveView() async throws {
        for bridge in [false, true] {
            for action in [ControlOwnerAction.sleep, .clean, .cleanAndPowerOff] {
                for fails in [false, true] {
                    let oldPeer = ControlOwnerPeer(owner: "A", events: true)
                    let newPeer = ControlOwnerPeer(owner: "B", events: true)
                    let state = makeState([oldPeer, newPeer], bridge: bridge)
                    defer { state.requestDisconnect(); Task { await oldPeer.releaseAll(); await newPeer.releaseAll() } }
                    await oldPeer.enqueue(.events, gate: "old-event")
                    try await connectAndPrepare(state)
                    try await waitForGate("old-event", peer: oldPeer)
                    await oldPeer.enqueue(action.wire(bridge: bridge), gate: "old-command",
                                          error: fails ? .networkConnectionLost : nil)
                    let old = Task { await perform(action, state: state) }
                    try await waitForGate("old-command", peer: oldPeer)
                    await state.disconnect()
                    await newPeer.enqueue(.events, gate: "new-event")
                    await newPeer.enqueue(.events, gate: "next-event")
                    try await connectAndPrepare(state)
                    try await waitForGate("new-event", peer: newPeer)
                    state.reportCubeLutRenderFailure()
                    let saved = PreservedControl(state)
                    let starts = await newPeer.count(.liveViewStart)
                    let eventStops = await newPeer.count(.stopEvents)
                    let oldReads = await oldPeer.count(.info)

                    await oldPeer.release("old-command")
                    await old.value

                    assertPreserved(saved, state: state)
                    XCTAssertTrue(state.busyOperations.isEmpty)
                    let startsAfter = await newPeer.count(.liveViewStart)
                    let eventStopsAfter = await newPeer.count(.stopEvents)
                    let closed = await newPeer.count(.close)
                    let oldReadsAfter = await oldPeer.count(.info)
                    XCTAssertEqual(startsAfter, starts, "Retired \(action) must not start B's Live View")
                    XCTAssertEqual(eventStopsAfter, eventStops)
                    XCTAssertEqual(closed, 0, "Retired \(action) must not disconnect B")
                    XCTAssertEqual(oldReadsAfter, oldReads, "A must not begin a recovery snapshot after retirement")

                    // A new event and its subsequent poll prove B's original event
                    // task survived. Merely finding its old HTTP request held would
                    // not detect replacement by a loop using retired session A.
                    await newPeer.setMode("B event delivered")
                    await newPeer.release("new-event")
                    try await waitForGate("next-event", peer: newPeer)
                    XCTAssertEqual(state.status?.mode, "B event delivered")
                    XCTAssertEqual(state.info?.model, "Synthetic control B")
                    XCTAssertEqual(state.lastError, saved.error)
                    await assertOriginalClose(oldPeer, bridge: bridge)
                }
            }
        }
    }

    func testCurrentOwnerControlsStillPublishAndFinishTheirBusyCategory() async throws {
        for bridge in [false, true] {
            for action in ControlOwnerAction.allCases {
                let peer = ControlOwnerPeer(owner: "current", recording: action == .recordingStop)
                let state = makeState([peer], bridge: bridge)
                defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
                try await connectAndPrepare(state)
                state.reportCubeLutRenderFailure()
                let before = await peer.count(action.wire(bridge: bridge))
                await perform(action, state: state)
                let after = await peer.count(action.wire(bridge: bridge))
                XCTAssertEqual(after - before, 1, "Current \(action) must still issue exactly one command")
                XCTAssertFalse(state.isBusy(action.category), "Current \(action) must finish")
                XCTAssertNil(state.lastError, "Current \(action) must publish its success")
                switch action {
                case .clock:
                    XCTAssertNotNil(state.lastClockSyncAt)
                    XCTAssertEqual(state.maintenanceRecord?.outcome.clockReadEvidence, .matched)
                case .directory:
                    XCTAssertEqual(state.lastCreatedDirectoryName, "ABCDE")
                    XCTAssertEqual(state.capabilities?.setting("directoryselection")?.value, "101ABCDE")
                case .naming:
                    XCTAssertEqual(state.capabilities?.fileNaming?.stillUserSetting1, "NEW_")
                case .setting:
                    XCTAssertEqual(state.status?.exposure.iso, "800")
                case .autofocus, .halfPress:
                    XCTAssertEqual(state.focusMarker, FocusMarker(x: 0.5, y: 0.5, accepted: true))
                    XCTAssertEqual(state.status?.mode, "current command")
                case .tap:
                    XCTAssertEqual(state.focusMarker, FocusMarker(x: 0.2, y: 0.3, accepted: true))
                case .whiteBalance:
                    XCTAssertEqual(state.focusMarker, FocusMarker(x: 0.2, y: 0.3, accepted: true))
                    XCTAssertEqual(state.status?.exposure.whiteBalance, "click")
                case .drive:
                    XCTAssertEqual(state.focusMarker, FocusMarker(x: 0.4, y: 0.5, accepted: true))
                case .magnification:
                    XCTAssertEqual(state.liveViewMagnification, .x5)
                case .recordingStart:
                    XCTAssertTrue(state.recording)
                case .recordingStop:
                    XCTAssertFalse(state.recording)
                case .sleep, .cleanAndPowerOff:
                    XCTAssertTrue(state.connected)
                    XCTAssertNil(state.activeLiveViewSource)
                    XCTAssertTrue(state.maintenanceControlsPaused)
                    let closed = await peer.count(.close)
                    XCTAssertEqual(closed, 0, "Sleep evidence must not trigger automatic DELETE")
                case .clean:
                    XCTAssertTrue(state.connected)
                    XCTAssertEqual(state.maintenanceRecord?.outcome.commandEvidence, .responded)
                    XCTAssertNil(state.activeLiveViewSource)
                    XCTAssertTrue(state.maintenanceControlsPaused)
                }
                if !bridge, action == .autofocus || action == .halfPress {
                    let releases = await peer.count(.shutterRelease)
                    XCTAssertEqual(releases, 1)
                }
            }
        }
    }

    func testCurrentOwnerFailuresStillReportAndMaintenanceRecoversItsOwnLoops() async throws {
        for bridge in [false, true] {
            for action in ControlOwnerAction.allCases {
                let peer = ControlOwnerPeer(owner: "current", recording: action == .recordingStop)
                let state = makeState([peer], bridge: bridge)
                defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
                try await connectAndPrepare(state)
                await peer.enqueue(action.wire(bridge: bridge), error: .networkConnectionLost)
                await perform(action, state: state)
                XCTAssertTrue(state.connected)
                XCTAssertFalse(state.isBusy(action.category))
                if action == .sleep || action == .clean || action == .cleanAndPowerOff {
                    XCTAssertNil(state.activeLiveViewSource)
                    XCTAssertNotNil(state.lastError)
                    XCTAssertTrue(state.maintenanceControlsPaused)
                    if bridge {
                        let starts = await peer.count(.liveViewStart)
                        XCTAssertEqual(starts, 1, "A failed maintenance command cannot automatically restore")
                    }
                } else {
                    XCTAssertNotNil(state.lastError, "Current \(action) must still report failure")
                }
                if !bridge, action == .autofocus || action == .halfPress {
                    let releases = await peer.count(.shutterRelease)
                    XCTAssertEqual(releases, 1, "Failed presses must still release their original camera")
                }
            }
        }
    }

    func testCurrentOwnerReadbackFailuresAreReportedWithoutReplayingTheirCommand() async throws {
        let boundaries: [(ControlOwnerAction, ControlOwnerWire)] = [
            (.directory, .capabilities), (.naming, .namingReadback), (.setting, .capabilities),
            (.clean, .info),
        ]
        for bridge in [false, true] {
            for (action, boundary) in boundaries {
                let peer = ControlOwnerPeer(owner: "current")
                let state = makeState([peer], bridge: bridge)
                defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
                // Leave Live View inactive so maintenance's recovery does not
                // clear the readback error with a successful Live View start.
                await state.connect()
                XCTAssertTrue(state.connected)
                await state.latestMediaTask?.value
                let original = state.snapshot
                await peer.enqueue(action.wire(bridge: bridge), gate: "command")
                let operation = Task { await perform(action, state: state) }
                try await waitForGate("command", peer: peer)
                let readback = action == .directory && bridge ? ControlOwnerWire.directoryReadback : boundary
                await peer.enqueue(readback, error: .networkConnectionLost)
                await peer.release("command")
                await operation.value

                XCTAssertTrue(state.connected)
                XCTAssertNotNil(state.lastError, "Current \(action) must report \(boundary) failure")
                XCTAssertFalse(state.isBusy(action.category))
                XCTAssertEqual(state.snapshot, original)
                let commands = await peer.count(action.wire(bridge: bridge))
                XCTAssertEqual(commands, 1, "Readback failure must not replay a camera command")
            }
        }
    }

    func testCurrentOwnerMaintenanceRestoresEventLoopOnlyAfterExplicitSuccessfulRestore() async throws {
        for bridge in [false, true] {
            let peer = ControlOwnerPeer(owner: "current", events: true)
            let state = makeState([peer], bridge: bridge)
            defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
            await peer.enqueue(.events, gate: "original-event")
            try await connectAndPrepare(state)
            try await waitForGate("original-event", peer: peer)
            await state.cleanSensor(autoPowerOff: false)
            XCTAssertTrue(state.maintenanceControlsPaused)
            XCTAssertNil(state.activeLiveViewSource)
            let before = await peer.count(.events)
            await state.recoverMaintenance()
            let afterRead = await peer.count(.events)
            XCTAssertEqual(before, afterRead, "Read-only recovery cannot restart events")
            await peer.enqueue(.events, gate: "restored-event")
            let id = try XCTUnwrap(state.maintenanceRecord?.id)
            await state.restoreMaintenance(id)
            try await waitForGate("restored-event", peer: peer)
            XCTAssertFalse(state.maintenanceControlsPaused)
            XCTAssertNotNil(state.activeLiveViewSource)
        }
    }

    func testDisconnectDrainsSensorRecoveryLiveViewBeforeReplacementAndPreservesItsWarning() async throws {
        for fails in [false, true] {
            let oldPeer = ControlOwnerPeer(owner: "A")
            let newPeer = ControlOwnerPeer(owner: "B")
            let state = makeState([oldPeer, newPeer], bridge: true)
            defer { state.requestDisconnect(); Task { await oldPeer.releaseAll(); await newPeer.releaseAll() } }
            try await connectAndPrepare(state)
            await oldPeer.enqueue(.liveViewStart, gate: "old-recovery", error: fails ? .networkConnectionLost : nil)
            var oldFinished = false
            let old = Task {
                await state.cleanSensor(autoPowerOff: false)
                if let id = state.maintenanceRecord?.id { await state.restoreMaintenance(id) }
                oldFinished = true
            }
            try await waitForGate("old-recovery", peer: oldPeer)
            // Disconnect owns the pending Start and must drain it before the next
            // camera is admitted. Releasing only after await disconnect deadlocks
            // this deliberately cancellation-uncooperative fixture.
            var disconnectEntered = false
            var disconnected = false
            let disconnect = Task { disconnectEntered = true; await state.disconnect(); disconnected = true }
            defer { disconnect.cancel(); old.cancel() }
            let entryClock = ContinuousClock()
            let entryDeadline = entryClock.now.advanced(by: .seconds(3))
            while !disconnectEntered, entryClock.now < entryDeadline { await Task.yield() }
            XCTAssertTrue(disconnectEntered)
            let originalStillHeld = await oldPeer.waiting("old-recovery")
            XCTAssertTrue(originalStillHeld)
            XCTAssertFalse(disconnected, "Disconnect must wait for A's held Start")
            let beforeRelease = await newPeer.count(.info)
            XCTAssertEqual(beforeRelease, 0)
            await oldPeer.release("old-recovery")
            let clock = ContinuousClock()
            let deadline = clock.now.advanced(by: .seconds(5))
            while !disconnected, clock.now < deadline { await Task.yield() }
            guard disconnected else {
                XCTFail("Disconnect did not settle after releasing the original Start")
                throw URLError(.timedOut)
            }
            await disconnect.value
            try await connectAndPrepare(state)
            state.reportCubeLutRenderFailure()
            let saved = PreservedControl(state)
            // The held wire reply is already drained; the outer maintenance
            // completion may still be scheduled. Observe it without an unbounded
            // await, and verify it cannot clear B's warning.
            let completionDeadline = clock.now.advanced(by: .seconds(5))
            while !oldFinished, clock.now < completionDeadline { await Task.yield() }
            guard oldFinished else {
                XCTFail("Original sensor recovery did not finish after its Start drained")
                throw URLError(.timedOut)
            }
            await old.value

            assertPreserved(saved, state: state)
            XCTAssertTrue(state.busyOperations.isEmpty)
            let starts = await newPeer.count(.liveViewStart)
            XCTAssertEqual(starts, 1)
        }
    }

    func testDisconnectWaitsForCancelledAutofocusAndHalfPressToReleaseOnlyOriginalCamera() async throws {
        for action in [ControlOwnerAction.autofocus, .halfPress] {
            let oldPeer = ControlOwnerPeer(owner: "A")
            let newPeer = ControlOwnerPeer(owner: "B")
            let state = makeState([oldPeer, newPeer], bridge: false)
            defer { state.requestDisconnect(); Task { await oldPeer.releaseAll(); await newPeer.releaseAll() } }
            try await connectAndPrepare(state)
            await oldPeer.enqueue(.halfPress, gate: "press")
            let old = Task { await perform(action, state: state) }
            try await waitForGate("press", peer: oldPeer)
            old.cancel()
            state.requestDisconnect()
            var reconnectEntered = false
            let reconnect = Task {
                reconnectEntered = true
                try await connectAndPrepare(state)
            }
            let clock = ContinuousClock()
            let deadline = clock.now.advanced(by: .seconds(5))
            while !reconnectEntered, clock.now < deadline { await Task.yield() }
            XCTAssertTrue(reconnectEntered)
            let prematureReads = await newPeer.count(.info)
            let prematureReleases = await oldPeer.count(.shutterRelease)
            XCTAssertEqual(prematureReads, 0, "Reconnect must wait for the original in-flight short press")
            XCTAssertEqual(prematureReleases, 0, "Stop must not overtake the held press")
            await oldPeer.release("press")
            try await reconnect.value
            state.reportCubeLutRenderFailure()
            let saved = PreservedControl(state)
            await old.value

            assertPreserved(saved, state: state)
            let oldReleases = await oldPeer.count(.shutterRelease)
            let newReleases = await newPeer.count(.shutterRelease)
            XCTAssertEqual(oldReleases, 1, "A's required safety release must survive retirement and cancellation")
            XCTAssertEqual(newReleases, 0, "Safety cleanup must never be redirected to B")
            XCTAssertTrue(state.busyOperations.isEmpty)
        }
    }

    func testCaptureNamingConsumesReturnedStateWithoutRedundantCapabilityRead() async throws {
        for bridge in [false, true] {
            let peer = ControlOwnerPeer(owner: "naming")
            let state = makeState([peer], bridge: bridge)
            defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
            try await connectAndPrepare(state)
            let before = await peer.count(.capabilities)
            await state.setFileNaming(field: .stillUserSetting1, value: "NEW_")
            let reads = await peer.count(.capabilities)
            XCTAssertEqual(reads - before, 1, "Only the client's existing preflight; no app follow-up GET")
            XCTAssertEqual(state.captureConfigurationRecord?.outcome.writeEvidence, .acknowledged)
            XCTAssertEqual(state.captureConfigurationRecord?.outcome.readbackEvidence, .matched)
            XCTAssertEqual(state.capabilities?.fileNaming?.stillUserSetting1, "NEW_")
            let writes = await peer.count(.naming)
            XCTAssertEqual(writes, 1)
            await state.setFileNaming(field: .stillUserSetting1, value: "NEW_")
            let repeated = await peer.count(.naming)
            XCTAssertEqual(repeated, 1, "Repeated Apply requires an explicit new request")
        }
    }

    func testUnknownDirectoryWriteStaysUnknownAfterReadOnlyRecoveryAndCannotAutoRetry() async throws {
        for bridge in [false, true] {
            let peer = ControlOwnerPeer(owner: "unknown")
            let state = makeState([peer], bridge: bridge)
            defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
            try await connectAndPrepare(state)
            await peer.enqueue(.directory, error: .networkConnectionLost)
            await state.createDirectory(name: "ABCDE")
            let original = try XCTUnwrap(state.captureConfigurationRecord)
            XCTAssertEqual(original.outcome.writeEvidence, .unknown)
            XCTAssertEqual(original.outcome.requestedValue, "ABCDE")
            await state.createDirectory(name: "ABCDE")
            await state.recoverCaptureConfiguration()
            XCTAssertEqual(state.captureConfigurationRecord?.id, original.id)
            XCTAssertEqual(state.captureConfigurationRecord?.outcome.writeEvidence, .unknown)
            XCTAssertEqual(state.captureConfigurationRecord?.outcome.readbackEvidence, .refreshed)
            XCTAssertNil(state.lastCreatedDirectoryName, "Observation cannot invent an ACK")
            let writes = await peer.count(.directory)
            XCTAssertEqual(writes, 1)
            let readbacks = await peer.count(.directoryReadback)
            if bridge { XCTAssertEqual(readbacks, 1) }
        }
    }

    func testDirectoryAcknowledgmentSurvivesCancellationAndPreservesFrozenDraft() async throws {
        for bridge in [false, true] {
            let peer = ControlOwnerPeer(owner: "cancel")
            let state = makeState([peer], bridge: bridge)
            defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
            try await connectAndPrepare(state)
            let revision = state.captureConfigurationDraftRevision
            await peer.enqueue(.directory, gate: "write")
            let task = Task { await state.createDirectory(name: "ABCDE", draftRevision: revision) }
            try await waitForGate("write", peer: peer)
            state.reviseCaptureConfigurationDraft()
            let replacementRevision = state.captureConfigurationDraftRevision
            state.cancelCaptureConfiguration()
            await peer.release("write")
            await task.value
            let record = try XCTUnwrap(state.captureConfigurationRecord)
            XCTAssertEqual(record.draftRevision, revision)
            XCTAssertEqual(state.captureConfigurationDraftRevision, replacementRevision)
            XCTAssertEqual(record.outcome.requestedValue, "ABCDE")
            XCTAssertEqual(record.outcome.writeEvidence, .acknowledged)
            XCTAssertTrue(record.cancelled)
            XCTAssertFalse(record.running)
            XCTAssertFalse(state.isBusy(.directory))
            XCTAssertTrue(state.canRecoverCaptureConfiguration)
            await state.recoverCaptureConfiguration()
            XCTAssertEqual(state.captureConfigurationRecord?.outcome.writeEvidence, .acknowledged)
            XCTAssertEqual(state.captureConfigurationRecord?.outcome.readbackEvidence, .refreshed)
            let writes = await peer.count(.directory)
            XCTAssertEqual(writes, 1)
        }
    }

    func testCancelledLateNamingSuccessRetainsACKWithoutMergingUntilFreshRecovery() async throws {
        for bridge in [false, true] {
            let peer = ControlOwnerPeer(owner: "cancel-naming")
            let state = makeState([peer], bridge: bridge)
            defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
            try await connectAndPrepare(state)
            let before = state.snapshot
            await peer.enqueue(.naming, gate: "late-success")
            let task = Task { await state.setFileNaming(field: .stillUserSetting1, value: "NEW_") }
            try await waitForGate("late-success", peer: peer)
            state.cancelCaptureConfiguration()
            await peer.release("late-success")
            await task.value
            XCTAssertEqual(state.captureConfigurationRecord?.outcome.writeEvidence, .acknowledged)
            XCTAssertEqual(state.captureConfigurationRecord?.outcome.readbackEvidence, .matched)
            XCTAssertEqual(state.snapshot, before, "Cancelled invocation must not merge its late observation")
            XCTAssertFalse(state.isBusy(.setting), "Settlement still releases its own lock")
            XCTAssertTrue(state.captureConfigurationRecord?.cancelled == true)
            await state.recoverCaptureConfiguration()
            XCTAssertEqual(state.capabilities?.fileNaming?.stillUserSetting1, "NEW_",
                           "Fresh noncancelled recovery can publish despite historical cancellation")
            XCTAssertTrue(state.captureConfigurationRecord?.cancelled == true)
            XCTAssertEqual(state.captureConfigurationRecord?.outcome.writeEvidence, .acknowledged)
            let writes = await peer.count(.naming)
            XCTAssertEqual(writes, 1)
        }
    }

    func testOldCaptureACKOnlySettlesOriginalBoundedRecord() async throws {
        for bridge in [false, true] {
            let oldPeer = ControlOwnerPeer(owner: "old-config")
            let newPeer = ControlOwnerPeer(owner: "new-config")
            let state = makeState([oldPeer, newPeer], bridge: bridge)
            defer { state.requestDisconnect(); Task { await oldPeer.releaseAll(); await newPeer.releaseAll() } }
            try await connectAndPrepare(state)
            await oldPeer.enqueue(.directory, gate: "old")
            let oldTask = Task { await state.createDirectory(name: "ABCDE") }
            try await waitForGate("old", peer: oldPeer)
            let originalID = try XCTUnwrap(state.captureConfigurationRecord?.id)
            await state.disconnect()
            try await connectAndPrepare(state)
            await newPeer.enqueue(.directory, gate: "new")
            let newTask = Task { await state.createDirectory(name: "ABCDE") }
            try await waitForGate("new", peer: newPeer)
            let currentID = state.captureConfigurationCurrentID
            let revision = state.captureConfigurationDraftRevision
            await oldPeer.release("old")
            await oldTask.value
            XCTAssertEqual(state.captureConfigurationCurrentID, currentID)
            XCTAssertEqual(state.captureConfigurationDraftRevision, revision)
            XCTAssertTrue(state.isBusy(.directory))
            XCTAssertNil(state.lastCreatedDirectoryName)
            let original = try XCTUnwrap(state.captureConfigurationHistory.first { $0.id == originalID })
            XCTAssertTrue(original.retired)
            XCTAssertEqual(original.outcome.writeEvidence, .acknowledged)
            await newPeer.release("new")
            await newTask.value
            XCTAssertEqual(state.captureConfigurationHistory.count, 2)
            XCTAssertFalse(state.isBusy(.directory))
        }
    }

    func testOlderHistoryRecoveryPreservesNewestRequestDraftAndCreationSuffix() async throws {
        for bridge in [false, true] {
            let peer = ControlOwnerPeer(owner: "history")
            let state = makeState([peer], bridge: bridge)
            defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
            try await connectAndPrepare(state)
            await state.createDirectory(name: "ABCDE")
            let originalID = try XCTUnwrap(state.captureConfigurationCurrentID)
            state.newCaptureConfigurationDraft()
            await state.createDirectory(name: "OTHER")
            let newestID = state.captureConfigurationCurrentID
            state.reviseCaptureConfigurationDraft()
            let revision = state.captureConfigurationDraftRevision
            let order = state.captureConfigurationHistory.map(\.id)
            XCTAssertEqual(state.lastCreatedDirectoryName, "OTHER")
            await peer.enqueue(bridge ? .directoryReadback : .capabilities, gate: "older-read")
            let recovery = Task { await state.recoverCaptureConfiguration(originalID) }
            try await waitForGate("older-read", peer: peer)
            XCTAssertFalse(state.canRecoverCaptureConfiguration(originalID), "Global single flight")
            XCTAssertFalse(state.canPrepareCaptureConfigurationDraft)
            await peer.release("older-read")
            await recovery.value
            XCTAssertEqual(state.captureConfigurationCurrentID, newestID)
            XCTAssertEqual(state.captureConfigurationDraftRevision, revision)
            XCTAssertEqual(state.captureConfigurationHistory.map(\.id), order)
            XCTAssertEqual(state.lastCreatedDirectoryName, "OTHER", "Older ACK cannot replace newer creation evidence")
            XCTAssertEqual(state.capabilities?.setting("directoryselection")?.value, "101OTHER")
            let original = try XCTUnwrap(state.captureConfigurationHistory.first { $0.id == originalID })
            XCTAssertEqual(original.outcome.acknowledgedValue, "ABCDE")
            XCTAssertEqual(original.outcome.directorySelection?.value, "101OTHER")
            let writes = await peer.count(.directory)
            XCTAssertEqual(writes, 2, "History recovery never repeats either creation")
            await state.disconnect()
            XCTAssertFalse(state.canRecoverCaptureConfiguration(originalID), "Retired history is read-only evidence only")
            await state.recoverCaptureConfiguration(originalID)
            let afterRetirement = await peer.count(.directory)
            XCTAssertEqual(afterRetirement, 2)
        }
    }

    func testCaptureConfigurationHistoryHasTwentyRecordBoundAndNewRequestIsExplicit() async throws {
        let peer = ControlOwnerPeer(owner: "bounded")
        let state = makeState([peer], bridge: false)
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        try await connectAndPrepare(state)
        var first: UUID?
        for index in 0..<21 {
            if index > 0 { state.newCaptureConfigurationDraft() }
            await state.createDirectory(name: "ABCDE")
            if index == 0 { first = state.captureConfigurationRecord?.id }
        }
        XCTAssertEqual(state.captureConfigurationHistory.count, 20)
        XCTAssertFalse(state.captureConfigurationHistory.contains { $0.id == first })
        let writes = await peer.count(.directory)
        XCTAssertEqual(writes, 21)
    }

    func testSettingResponseSurvivesReadFailureAndRecoveryNeverWrites() async throws {
        for bridge in [false, true] {
            let peer = ControlOwnerPeer(owner: "setting-response")
            let state = makeState([peer], bridge: bridge)
            defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
            try await connectAndPrepare(state)
            await peer.enqueue(.setting, gate: "write")
            let write = Task { await state.setSetting(key: "iso", value: "800") }
            try await waitForGate("write", peer: peer)
            await peer.enqueue(.capabilities, error: .networkConnectionLost)
            await peer.release("write")
            let consumed = await write.value
            XCTAssertFalse(consumed)
            XCTAssertEqual(state.shootingSettingRecord?.outcome.writeEvidence, .responded)
            XCTAssertEqual(state.shootingSettingRecord?.outcome.readbackEvidence, .unavailable)
            XCTAssertEqual(state.status?.exposure.iso, "100")
            let writes = await peer.count(.setting)
            await state.recoverShootingSetting()
            XCTAssertEqual(state.shootingSettingRecord?.outcome.writeEvidence, .responded)
            XCTAssertEqual(state.shootingSettingRecord?.outcome.readbackEvidence, .matched)
            XCTAssertEqual(state.status?.exposure.iso, "800")
            let after = await peer.count(.setting)
            XCTAssertEqual(after, writes)
        }
    }

    func testSettingDoubleApplyCancelAndLateResponseRetainEvidenceWithoutPublishing() async throws {
        for bridge in [false, true] {
            let peer = ControlOwnerPeer(owner: "setting-cancel")
            let state = makeState([peer], bridge: bridge)
            defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
            try await connectAndPrepare(state)
            await peer.enqueue(.setting, gate: "held")
            let original = Task { await state.setSetting(key: "iso", value: "800", draftRevision: UUID()) }
            try await waitForGate("held", peer: peer)
            let duplicate = await state.setSetting(key: "iso", value: "800")
            XCTAssertFalse(duplicate)
            state.cancelShootingSetting()
            XCTAssertFalse(state.canPrepareShootingSettingDraft)
            await peer.release("held")
            let consumed = await original.value
            XCTAssertFalse(consumed)
            XCTAssertEqual(state.shootingSettingRecord?.outcome.writeEvidence, .responded)
            XCTAssertTrue(state.shootingSettingRecord?.cancelled == true)
            XCTAssertEqual(state.status?.exposure.iso, "100")
            let writes = await peer.count(.setting)
            XCTAssertEqual(writes, 1)
            await state.recoverShootingSetting()
            XCTAssertEqual(state.status?.exposure.iso, "800")
            let after = await peer.count(.setting)
            XCTAssertEqual(after, 1)
        }
    }

    func testManualRefreshCannotPublishPrewriteSnapshotOverScopedSettingReadback() async throws {
        let peer = ControlOwnerPeer(owner: "setting-observation")
        let state = makeState([peer], bridge: true)
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        try await connectAndPrepare(state)
        await peer.enqueue(.setting, gate: "held")
        let original = Task { await state.setSetting(key: "iso", value: "800") }
        try await waitForGate("held", peer: peer)
        let reads = await peer.count(.capabilities)
        await state.refresh()
        let after = await peer.count(.capabilities)
        XCTAssertEqual(after, reads, "Unscoped refresh cannot overtake the setting owner")
        XCTAssertEqual(state.status?.exposure.iso, "100")
        await peer.release("held")
        let consumed = await original.value
        XCTAssertTrue(consumed)
        XCTAssertEqual(state.status?.exposure.iso, "800")
        XCTAssertEqual(state.shootingSettingRecord?.outcome.writeEvidence, .responded)
        XCTAssertFalse(state.isBusy(.setting))
    }

    func testSettingUnknownMatchingRecoveryCannotAuthorizeDraftConsumptionOrReplay() async throws {
        let peer = ControlOwnerPeer(owner: "setting-unknown")
        let state = makeState([peer], bridge: true)
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        try await connectAndPrepare(state)
        await peer.enqueue(.setting, error: .networkConnectionLost)
        let consumed = await state.setSetting(key: "iso", value: "800")
        XCTAssertFalse(consumed)
        XCTAssertEqual(state.shootingSettingRecord?.outcome.writeEvidence, .unknown)
        await state.recoverShootingSetting()
        XCTAssertEqual(state.shootingSettingRecord?.outcome.writeEvidence, .unknown)
        XCTAssertEqual(state.shootingSettingRecord?.outcome.readbackEvidence, .matched)
        let duplicate = await state.setSetting(key: "iso", value: "800")
        XCTAssertFalse(duplicate)
        let writes = await peer.count(.setting)
        XCTAssertEqual(writes, 1)
    }

    func testSettingHistoryIsBoundedAndRecoveryPreservesNewestIntent() async throws {
        let peer = ControlOwnerPeer(owner: "setting-history")
        let state = makeState([peer], bridge: true)
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        try await connectAndPrepare(state)
        var first: UUID?
        for index in 0..<21 {
            if index > 0 { state.newShootingSettingDraft() }
            _ = await state.setSetting(key: "iso", value: "800")
            if index == 0 { first = state.shootingSettingRecord?.id }
        }
        XCTAssertEqual(state.shootingSettingHistory.count, 20)
        XCTAssertFalse(state.shootingSettingHistory.contains { $0.id == first })
        let newest = state.shootingSettingCurrentID
        let older = try XCTUnwrap(state.shootingSettingHistory.first?.id)
        await state.recoverShootingSetting(older)
        XCTAssertEqual(state.shootingSettingCurrentID, newest)
        let writes = await peer.count(.setting)
        XCTAssertEqual(writes, 21)
        await state.disconnect()
        XCTAssertFalse(state.canRecoverShootingSetting(older))
        await state.recoverShootingSetting(older)
        let after = await peer.count(.setting)
        XCTAssertEqual(after, 21)
    }

    private func assertRetired(_ action: ControlOwnerAction, bridge: Bool, fails: Bool,
                               readback: ControlOwnerWire? = nil) async throws {
        let oldPeer = ControlOwnerPeer(owner: "A", recording: action == .recordingStop)
        let newPeer = ControlOwnerPeer(owner: "B", recording: action == .recordingStop)
        let state = makeState([oldPeer, newPeer], bridge: bridge)
        defer { state.requestDisconnect(); Task { await oldPeer.releaseAll(); await newPeer.releaseAll() } }
        try await connectAndPrepare(state)
        let shortControl = !bridge && (action == .autofocus || action == .halfPress)
        await oldPeer.enqueue(action.wire(bridge: bridge), gate: "old-command",
                              error: readback == nil && !shortControl && fails ? .networkConnectionLost : nil)
        let old = Task { await perform(action, state: state) }
        try await waitForGate("old-command", peer: oldPeer)
        if let readback = readback ?? (shortControl ? .status : nil) {
            // Naming's complete state is now inside its one write response.
            // Gate that response's readback phase rather than adding a second GET.
            // Short presses now settle their exact release before Close returns;
            // gate their later status read to exercise a genuinely retired reply.
            let boundary = action == .directory && bridge ? ControlOwnerWire.directoryReadback : readback
            await oldPeer.enqueue(boundary, gate: "old-readback", error: fails ? .networkConnectionLost : nil)
            await oldPeer.release("old-command")
            try await waitForGate("old-readback", peer: oldPeer)
        }
        let oldCapabilityReads = await oldPeer.count(.capabilities)
        let oldInfoReads = await oldPeer.count(.info)
        await state.disconnect()
        try await connectAndPrepare(state)
        await newPeer.enqueue(action.wire(bridge: bridge), gate: "new-command")
        let replacement = Task { await perform(action, state: state) }
        try await waitForGate("new-command", peer: newPeer)
        state.reportCubeLutRenderFailure()
        let saved = PreservedControl(state)
        XCTAssertTrue(state.isBusy(action.category))

        await oldPeer.release(readback == nil && !shortControl ? "old-command" : "old-readback")
        await old.value

        assertPreserved(saved, state: state)
        XCTAssertTrue(state.isBusy(action.category), "Retired \(action) must not finish B's busy category")
        let replacementHeld = await newPeer.waiting("new-command")
        XCTAssertTrue(replacementHeld)
        XCTAssertFalse(replacement.isCancelled)
        let closed = await newPeer.count(.close)
        XCTAssertEqual(closed, 0)
        if readback == nil {
            let capabilitiesAfter = await oldPeer.count(.capabilities)
            let infoAfter = await oldPeer.count(.info)
            XCTAssertEqual(capabilitiesAfter, oldCapabilityReads,
                           "Retired \(action) must not begin app capability readback")
            XCTAssertEqual(infoAfter, oldInfoReads,
                           "Retired sensor cleaning must not begin recovery snapshot")
        }
        if !bridge, action == .autofocus || action == .halfPress {
            let oldReleases = await oldPeer.count(.shutterRelease)
            let newReleases = await newPeer.count(.shutterRelease)
            XCTAssertEqual(oldReleases, 1, "Retirement must preserve A's guaranteed shutter release")
            XCTAssertEqual(newReleases, 0)
        }
        await assertOriginalClose(oldPeer, bridge: bridge)
        await newPeer.release("new-command")
        await replacement.value
        XCTAssertFalse(state.isBusy(action.category))
        XCTAssertNil(state.lastError)
    }

    func testMagnificationRetiresVisibleFrameUntilFreshReaderAndBlocksCoordinates() async throws {
        for bridge in [false, true] {
            for fails in [false, true] {
                let peer = ControlOwnerPeer(owner: "zoom"), state = makeState([peer], bridge: bridge)
                defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
                func jpeg(_ color: UIColor) throws -> Data {
                    let image = UIGraphicsImageRenderer(size: CGSize(width: 4, height: 3)).image { context in
                        color.setFill(); context.fill(CGRect(x: 0, y: 0, width: 4, height: 3))
                    }
                    return try XCTUnwrap(image.jpegData(compressionQuality: 1))
                }
                let oldImage = try jpeg(.red), newImage = try jpeg(.blue)
                await peer.setFrame(oldImage)
                try await connectAndPrepare(state)
                await state.setAutoRefresh(true)
                try await waitForImage(oldImage, state: state)
                await peer.enqueue(.frame, gate: "old-frame")
                try await waitForGate("old-frame", peer: peer)
                await peer.enqueue(.magnification, gate: "zoom", error: fails ? .networkConnectionLost : nil)
                let zoom = Task { await state.setLiveViewMagnification(.x5) }
                try await waitForGate("zoom", peer: peer)
                XCTAssertNil(state.liveViewData); XCTAssertNil(state.lastFrameAt)
                XCTAssertFalse(state.liveViewCoordinateControlsReady)
                await state.tapFocus(x: 0.2, y: 0.3); await state.clickWhiteBalance(x: 0.2, y: 0.3)
                let blockedTap = await peer.count(.tap), blockedWB = await peer.count(.whiteBalance)
                XCTAssertEqual(blockedTap, 0); XCTAssertEqual(blockedWB, 0)
                await state.setAutoRefresh(false); await state.setAutoRefresh(true)
                XCTAssertNotNil(state.activeLiveViewSource, "Toggling refresh during zoom must not start an invalid reader")
                await peer.setFrame(newImage); await peer.enqueue(.frame, gate: "new-frame")
                await peer.release("zoom"); await zoom.value
                try await waitForGate("new-frame", peer: peer)
                await peer.release("old-frame")
                for _ in 0..<20 { await Task.yield() }
                XCTAssertNil(state.liveViewData, "Old completion cannot republish its red image")
                XCTAssertFalse(state.liveViewCoordinateControlsReady)
                await peer.release("new-frame")
                try await waitForImage(newImage, state: state)
                XCTAssertTrue(state.liveViewCoordinateControlsReady)
                await state.tapFocus(x: 0.2, y: 0.3); await state.clickWhiteBalance(x: 0.2, y: 0.3)
                let tap = await peer.count(.tap), wb = await peer.count(.whiteBalance), stops = await peer.count(.liveViewStop)
                XCTAssertEqual(tap, 1); XCTAssertEqual(wb, 1); XCTAssertEqual(stops, 0)
            }
        }
    }

    func testPreMagnificationCoordinateCompletionCannotRestoreMarkerOrClearNewError() async throws {
        for bridge in [false, true] {
            for whiteBalance in [false, true] {
                let peer = ControlOwnerPeer(owner: "zoom-response"), state = makeState([peer], bridge: bridge)
                defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
                try await connectAndPrepare(state)
                await peer.enqueue(whiteBalance ? .whiteBalance : .tap, gate: "old-coordinate")
                let old = Task {
                    if whiteBalance { await state.clickWhiteBalance(x: 0.2, y: 0.3) }
                    else { await state.tapFocus(x: 0.2, y: 0.3) }
                }
                try await waitForGate("old-coordinate", peer: peer)
                await state.setLiveViewMagnification(.x5)
                state.reportCubeLutRenderFailure()
                let expectedError = state.lastError
                await peer.release("old-coordinate"); await old.value
                XCTAssertNil(state.focusMarker)
                XCTAssertEqual(state.lastError, expectedError)
                XCTAssertFalse(state.liveViewCoordinateControlsReady, "No post-change frame was acquired")
            }
        }
    }

    func testLateFocusDrivePreservesTerminalFrameFailureAndPendingStop() async throws {
        try await assertFocusDriveRetirement("frameFailure")
    }

    func testLateFocusDrivePreservesBackgroundRetirementAndPendingStop() async throws {
        try await assertFocusDriveRetirement("background")
    }

    func testLateFocusDriveCannotPublishIntoSameSessionReplacement() async throws {
        try await assertFocusDriveRetirement("replacement")
    }

    private func assertFocusDriveRetirement(_ boundary: String) async throws {
        for bridge in [false, true] {
            for fails in [false, true] {
                let peer = ControlOwnerPeer(owner: "focus-retirement")
                let state = makeState([peer], bridge: bridge)
                defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
                try await connectAndPrepare(state)
                await peer.enqueue(.drive, gate: "old-drive", error: fails ? .networkConnectionLost : nil)
                let old = Task { await state.driveFocus(direction: .near, step: .small) }
                try await waitForGate("old-drive", peer: peer)
                if bridge { await peer.enqueue(.liveViewStop, gate: "retirement-stop") }
                var replacement: Task<Void, Never>?
                if boundary == "frameFailure" {
                    await peer.enqueue(.frame, gate: "failed-frame", error: .cannotDecodeContentData)
                    await state.setAutoRefresh(true)
                    try await waitForGate("failed-frame", peer: peer)
                    await peer.release("failed-frame")
                } else if boundary == "background" {
                    state.reportCubeLutRenderFailure()
                    state.setApplicationActive(false)
                } else {
                    replacement = Task { await state.restartLiveView() }
                }
                if bridge {
                    try await waitForGate("retirement-stop", peer: peer)
                } else if replacement == nil {
                    // Direct simulator has local presentation ownership but no
                    // camera Stop obligation; do not wait for a nonexistent wire.
                    let deadline = ContinuousClock.now.advanced(by: .seconds(5))
                    while state.activeLiveViewSource != nil || state.liveViewRetiring {
                        guard ContinuousClock.now < deadline else { throw URLError(.timedOut) }
                        await Task.yield()
                    }
                }
                if let replacement {
                    if bridge { await peer.release("retirement-stop") }
                    await replacement.value
                    XCTAssertNotNil(state.activeLiveViewSource)
                    state.reportCubeLutRenderFailure()
                } else {
                    if bridge { XCTAssertTrue(state.liveViewRetiring) }
                    XCTAssertNil(state.activeLiveViewSource)
                }
                let preservedError = try XCTUnwrap(state.lastError)
                let preservedSource = state.activeLiveViewSource
                await peer.release("old-drive")
                await old.value
                XCTAssertEqual(state.lastError, preservedError)
                XCTAssertEqual(state.activeLiveViewSource, preservedSource)
                XCTAssertNil(state.focusMarker)
                XCTAssertFalse(state.isBusy(.focus), "Retired publication must still release its focus category")
                let drives = await peer.count(.drive)
                XCTAssertEqual(drives, 1, "No automatic replay")
                if bridge && boundary != "replacement" {
                    XCTAssertTrue(state.liveViewRetiring, "Focus completion cannot retire another operation's Stop")
                    let stopPending = await peer.waiting("retirement-stop")
                    XCTAssertTrue(stopPending)
                    await peer.release("retirement-stop")
                }
            }
        }
    }

    private func waitForImage(_ data: Data, state: CameraAppState) async throws {
        let deadline = ContinuousClock.now.advanced(by: .seconds(5))
        while state.liveViewData != data {
            guard ContinuousClock.now < deadline else { throw URLError(.timedOut) }
            await Task.yield()
        }
    }

    private struct PreservedControl {
        let snapshot: CameraSnapshot?
        let clock: Date?
        let directory: String?
        let marker: FocusMarker?
        let magnification: LiveViewMagnification?
        let source: LiveViewSource?
        let error: String?

        @MainActor init(_ state: CameraAppState) {
            snapshot = state.snapshot
            clock = state.lastClockSyncAt
            directory = state.lastCreatedDirectoryName
            marker = state.focusMarker
            magnification = state.liveViewMagnification
            source = state.activeLiveViewSource
            error = state.lastError
        }
    }

    private func assertPreserved(_ saved: PreservedControl, state: CameraAppState,
                                 file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertTrue(state.connected, file: file, line: line)
        XCTAssertEqual(state.snapshot, saved.snapshot, file: file, line: line)
        XCTAssertEqual(state.lastClockSyncAt, saved.clock, file: file, line: line)
        XCTAssertEqual(state.lastCreatedDirectoryName, saved.directory, file: file, line: line)
        XCTAssertEqual(state.focusMarker, saved.marker, file: file, line: line)
        XCTAssertEqual(state.liveViewMagnification, saved.magnification, file: file, line: line)
        XCTAssertEqual(state.activeLiveViewSource, saved.source, file: file, line: line)
        XCTAssertEqual(state.lastError, saved.error, file: file, line: line)
    }

    private func assertOriginalClose(_ peer: ControlOwnerPeer, bridge: Bool) async {
        if bridge {
            let closed = await peer.count(.close)
            XCTAssertEqual(closed, 1, "The original Bridge session must still receive its own DELETE")
        }
    }

    private func perform(_ action: ControlOwnerAction, state: CameraAppState) async {
        switch action {
        case .clock: await state.syncCameraClock()
        case .directory: await state.createDirectory(name: "ABCDE")
        case .naming: await state.setFileNaming(field: .stillUserSetting1, value: "NEW_")
        case .sleep: await state.sleepCamera()
        case .clean: await state.cleanSensor(autoPowerOff: false)
        case .cleanAndPowerOff: await state.cleanSensor(autoPowerOff: true)
        case .autofocus: await state.autofocus()
        case .halfPress: await state.halfPressShutter()
        case .recordingStart, .recordingStop: await state.toggleRecording()
        case .tap: await state.tapFocus(x: 0.2, y: 0.3)
        case .whiteBalance: await state.clickWhiteBalance(x: 0.2, y: 0.3)
        case .drive: await state.driveFocus(direction: .near, step: .small)
        case .magnification: await state.setLiveViewMagnification(.x5)
        case .setting: _ = await state.setSetting(key: "iso", value: "800")
        }
    }

    private func makeState(_ peers: [ControlOwnerPeer], bridge: Bool) -> CameraAppState {
        var remaining = peers
        let suite = "ControlResponseOwnershipTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        addTeardownBlock { defaults.removePersistentDomain(forName: suite) }
        let state = CameraAppState(defaults: defaults, sessionFactory: {
            let peer = remaining.removeFirst()
            return bridge
                ? .desktopBridge(try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", transport: peer))
                : .ccapi(try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: .simulator, transport: peer))
        })
        state.autoRefresh = false
        return state
    }

    private func connectAndPrepare(_ state: CameraAppState) async throws {
        await state.connect()
        XCTAssertTrue(state.connected)
        _ = try XCTUnwrap(state.snapshot)
        await state.latestMediaTask?.value
        await state.startLiveView()
        XCTAssertNotNil(state.activeLiveViewSource)
        XCTAssertTrue(state.busyOperations.isEmpty)
    }

    private func waitForGate(_ gate: String, peer: ControlOwnerPeer) async throws {
        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: .seconds(5))
        repeat {
            if await peer.waiting(gate) { return }
            await Task.yield()
        } while clock.now < deadline
        XCTFail("Expected controlled request did not enter gate \(gate)")
        throw URLError(.timedOut)
    }
}

private enum ControlOwnerAction: CaseIterable {
    case clock, directory, naming, sleep, clean, cleanAndPowerOff, autofocus, halfPress
    case recordingStart, recordingStop, tap, whiteBalance, drive, magnification, setting

    var category: CameraOperation {
        switch self {
        case .clock: .clock
        case .directory: .directory
        case .naming, .whiteBalance, .setting: .setting
        case .sleep: .power
        case .clean, .cleanAndPowerOff: .maintenance
        case .autofocus, .halfPress, .tap, .drive: .focus
        case .recordingStart, .recordingStop: .recording
        case .magnification: .liveView
        }
    }

    func wire(bridge: Bool) -> ControlOwnerWire {
        switch self {
        case .clock: .clock
        case .directory: .directory
        case .naming: .naming
        case .sleep: .sleep
        case .clean, .cleanAndPowerOff: .clean
        case .autofocus: bridge ? .autofocus : .halfPress
        case .halfPress: .halfPress
        case .recordingStart: .recordingStart
        case .recordingStop: .recordingStop
        case .tap: .tap
        case .whiteBalance: .whiteBalance
        case .drive: .drive
        case .magnification: .magnification
        case .setting: .setting
        }
    }
}

private enum ControlOwnerWire: Hashable, Sendable {
    case health, open, close, info, status, capabilities, events, stopEvents, media, liveViewStart, liveViewStop, frame
    case clock, clockRead, directory, naming, directoryReadback, namingReadback, sleep, clean, autofocus, halfPress, shutterRelease
    case recordingStart, recordingStop, tap, whiteBalance, drive, magnification, setting
}

/// All identities and responses are synthetic. Gates model a transport which
/// accepts a command and then returns success/failure after its owner retires.
private actor ControlOwnerPeer: CameraHTTPTransport {
    private struct Plan {
        let gate: String?
        let error: URLError.Code?
    }
    private let owner: String
    private let events: Bool
    private var mode: String
    private var recording: Bool
    private var iso = "100"
    private var whiteBalance = "auto"
    private var directory = "100EOSXX"
    private var naming = "IMG_"
    private var magnification = 1
    private var frameData: Data?
    func setFrame(_ data: Data) { frameData = data }
    private var clockBody: [String: Any] = [:]
    private var plans: [ControlOwnerWire: [Plan]] = [:]
    private var gates: [String: CheckedContinuation<Void, Never>] = [:]
    private var requests: [ControlOwnerWire] = []
    private var drained = false

    init(owner: String, events: Bool = false, recording: Bool = false) {
        self.owner = owner
        self.events = events
        self.recording = recording
        mode = "\(owner) initial"
    }

    func enqueue(_ wire: ControlOwnerWire, gate: String? = nil, error: URLError.Code? = nil) {
        plans[wire, default: []].append(Plan(gate: gate, error: error))
    }
    func count(_ wire: ControlOwnerWire) -> Int { requests.filter { $0 == wire }.count }
    func waiting(_ gate: String) -> Bool { gates[gate] != nil }
    func setMode(_ value: String) { mode = value }
    func release(_ gate: String) { gates.removeValue(forKey: gate)?.resume() }
    func releaseAll() {
        drained = true
        plans.removeAll()
        let pending = gates
        gates.removeAll()
        for continuation in pending.values { continuation.resume() }
    }

    func send(_ request: URLRequest) async throws -> CameraHTTPResponse {
        let path = request.url!.path
        let method = request.httpMethod ?? "GET"
        let simulator = path.hasPrefix("/ccapi/")
        let wire = try route(path: path, method: method, simulator: simulator)
        requests.append(wire)
        let plan = plans[wire]?.isEmpty == false ? plans[wire]!.removeFirst() : Plan(gate: nil, error: nil)
        let responseFrame = frameData
        var object: [String: Any] = [:]
        switch wire {
        case .health:
            object = ["ok": true, "service": "open-eos-control-bridge", "version": "0.13.0"]
        case .open:
            object = ["id": owner, "engine": "ccapi"]
        case .info:
            if path.contains("/maintenance/") {
                object = ["version": 1, "engine": "ccapi", "operation": "sensor-cleaning", "observationEvidence": "reachable", "clockObservation": NSNull()]
            } else {
                object = ["connected": true, "model": "Synthetic control \(owner)", "serial": "SYNTHETIC-\(owner)", "api": "fixture"]
            }
        case .status:
            object = path.hasSuffix("/recording/observation") ? recordingObservationJSON() : statusJSON()
        case .capabilities:
            object = path.hasSuffix("/settings/iso")
                ? ["version": 1, "engine": "ccapi", "setting": shootingSettingJSON()]
                : capabilitiesJSON(simulator: simulator)
        case .directoryReadback:
            object = ["operation": "create-directory", "directorySelection": ["value": directory, "ability": directoryOptions()]]
        case .namingReadback:
            object = ["operation": "set-file-naming", "fileNaming": namingJSON()]
        case .events:
            guard events, !drained, plan.gate != nil else { throw CancellationError() }
            object = ["changedKeys": ["mode"], "keys": ["mode"], "sequence": count(.events)]
        case .media:
            object = ["items": []]
        case .clock:
            mode = "\(owner) command"
            if simulator {
                clockBody = try JSONSerialization.jsonObject(with: request.httpBody!) as! [String: Any]
                object = clockBody
            } else {
                let epoch = Int(Date().timeIntervalSince1970)
                object = ["version": 1, "engine": "ccapi", "operation": "clock-sync", "autoPowerOff": false, "responseEvidence": "response-received",
                    "clockReference": ["earliestEpochSeconds": epoch - 10, "latestEpochSeconds": epoch + 10, "utcOffsetMinutes": 0, "daylightSaving": false],
                    "clockObservation": ["epochSeconds": epoch, "utcOffsetMinutes": 0, "daylightSaving": false], "clockReadEvidence": "matched"]
            }
        case .clockRead: object = clockBody
        case .autofocus, .halfPress:
            mode = "\(owner) command"
            object = statusJSON()
        case .directory:
            let payload = (request.httpBody.flatMap { try? JSONSerialization.jsonObject(with: $0) }) as? [String: Any]
            let requested = (payload?[simulator ? "directoryname" : "name"] as? String) ?? "ABCDE"
            let suffix = requested.isEmpty ? "ABCDE" : requested
            directory = "101" + suffix
            object = ["name": suffix, "directoryname": suffix]
        case .naming:
            naming = "NEW_"
            object = namingJSON()
        case .clean, .sleep:
            mode = "\(owner) command"
            if !simulator {
                let payload = try JSONSerialization.jsonObject(with: request.httpBody!) as! [String: Any]
                object = ["version": 1, "engine": "ccapi", "operation": wire == .clean ? "sensor-cleaning" : "sleep",
                    "autoPowerOff": payload["autoPowerOff"]!, "responseEvidence": "response-received", "clockReadEvidence": "not-applicable",
                    "clockReference": NSNull(), "clockObservation": NSNull()]
            }
        case .recordingStart, .recordingStop:
            recording = wire == .recordingStart
            object = simulator ? statusJSON() : ["version": 1, "engine": "ccapi",
                "operation": wire == .recordingStart ? "start" : "stop", "responseEvidence": "response-received",
                "observation": recordingObservationJSON()]
        case .setting:
            iso = "800"
            object = simulator ? statusJSON() : ["version": 1, "engine": "ccapi", "key": "iso",
                "requestedValue": "800", "writeEvidence": "response-received", "readbackEvidence": "matched",
                "setting": shootingSettingJSON()]
        case .whiteBalance:
            whiteBalance = "click"
            object = statusJSON()
        case .tap:
            object = ["ok": true, "accepted": true, "x": 0.2, "y": 0.3]
        case .drive:
            object = ["ok": true, "accepted": true, "direction": "near", "step": "small"]
        case .magnification:
            magnification = 5
            object = ["accepted": true, "value": magnification]
        case .liveViewStart:
            object = ["active": true, "source": "CCAPI_JPEG_POLLING", "size": "MEDIUM", "requestedFps": 6]
        case .liveViewStop:
            object = ["active": false, "liveViewStopUnconfirmed": false]
        case .close, .stopEvents, .shutterRelease, .frame:
            break
        }
        if request.url!.path.contains("/maintenance/control/liveview/") {
            let payload = try JSONSerialization.jsonObject(with: request.httpBody!) as! [String: Any]
            if payload["prepareRestore"] as? Bool == true { object["restoreToken"] = "synthetic_restore_permit_0001" }
            if let token = payload["restoreToken"] as? String {
                object["restoreToken"] = token; object["restoreOutcome"] = wire == .liveViewStart ? "started" : "stopped"
                object["liveViewStopUnconfirmed"] = false
            }
        }
        if let gate = plan.gate, !drained { await withCheckedContinuation { gates[gate] = $0 } }
        if let error = plan.error { throw URLError(error) }
        if wire == .frame {
            guard let responseFrame else { throw URLError(.cannotDecodeContentData) }
            return CameraHTTPResponse(statusCode: 200, headers: ["content-type": "image/jpeg"], body: responseFrame)
        }
        if wire == .setting, !simulator, plans[.capabilities]?.isEmpty == false {
            let readback = plans[.capabilities]!.removeFirst()
            if let gate = readback.gate, !drained { await withCheckedContinuation { gates[gate] = $0 } }
            if readback.error != nil {
                object["readbackEvidence"] = "unavailable"
                object["setting"] = NSNull()
            }
        }
        // A synthetic response-phase gate, not an additional network request.
        if wire == .naming, plans[.namingReadback]?.isEmpty == false {
            let readback = plans[.namingReadback]!.removeFirst()
            if let gate = readback.gate, !drained { await withCheckedContinuation { gates[gate] = $0 } }
            if let error = readback.error { throw URLError(error) }
        }
        let empty = wire == .stopEvents || (simulator && (wire == .clean || wire == .sleep))
        return CameraHTTPResponse(statusCode: empty ? 204 : 200, body: empty ? Data() : try JSONSerialization.data(withJSONObject: object))
    }

    func download(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse {
        throw URLError(.unsupportedURL)
    }

    private func route(path: String, method: String, simulator: Bool) throws -> ControlOwnerWire {
        if path == "/health", method == "GET" { return .health }
        if path == "/v1/session", method == "POST" { return .open }
        let prefix = simulator ? "/ccapi" : "/v1/session/\(owner)"
        guard path == prefix || path.hasPrefix(prefix + "/") else { throw URLError(.unsupportedURL) }
        let suffix = String(path.dropFirst(prefix.count))
        switch (method, suffix) {
        case ("DELETE", ""): return .close
        case ("GET", "/info"): return .info
        case ("GET", "/status"), ("GET", "/recording/observation"): return .status
        case ("GET", "/capabilities"), ("GET", "/settings/iso"): return .capabilities
        case ("GET", "/capture-configuration/create-directory"): return .directoryReadback
        case ("GET", "/capture-configuration/set-file-naming"): return .namingReadback
        case ("GET", "/events"): return .events
        case ("DELETE", "/events"), ("DELETE", "/maintenance/control/events"): return .stopEvents
        case ("GET", "/media"): return .media
        case ("GET", "/liveview/frame"): return .frame
        case ("POST", "/liveview/start"), ("POST", "/maintenance/control/liveview/start"): return .liveViewStart
        case ("POST", "/liveview/stop"), ("POST", "/maintenance/control/liveview/stop"): return .liveViewStop
        case ("POST", "/clock/sync"), ("POST", "/maintenance/clock-sync/outcome"), ("PUT", "/ver100/functions/datetime"): return .clock
        case ("GET", "/ver100/functions/datetime"): return .clockRead
        case ("GET", "/maintenance/sensor-cleaning"): return .info
        case ("POST", "/directory"), ("POST", "/directories"): return .directory
        case ("PUT", "/file-naming/still-user-setting-1"): return .naming
        case ("POST", "/camera-sleep"), ("POST", "/power/sleep"), ("POST", "/maintenance/sleep/outcome"): return .sleep
        case ("POST", "/sensor-cleaning"), ("POST", "/maintenance/sensor-cleaning"), ("POST", "/maintenance/sensor-cleaning/outcome"): return .clean
        case ("POST", "/focus/auto"): return .autofocus
        case ("POST", "/shutter/half-press"): return .halfPress
        case ("POST", "/shutter/release"): return .shutterRelease
        case ("POST", "/record/start"), ("POST", "/recording/start/outcome"): return .recordingStart
        case ("POST", "/record/stop"), ("POST", "/recording/stop/outcome"): return .recordingStop
        case ("POST", "/focus/tap"): return .tap
        case ("POST", "/whitebalance/click"): return .whiteBalance
        case ("POST", "/focus/drive"): return .drive
        case ("POST", "/liveview/magnification"): return .magnification
        case ("PATCH", "/exposure"), ("POST", "/settings/iso"), ("POST", "/settings/iso/outcome"): return .setting
        default: throw URLError(.unsupportedURL)
        }
    }

    private func shootingSettingJSON() -> [String: Any] {
        ["key": "iso", "label": "ISO", "value": iso, "values": ["100", "800"], "inputKind": "choice"]
    }

    private func recordingObservationJSON() -> [String: Any] {
        ["version": 1, "engine": "ccapi", "observationEvidence": "observed", "recording": recording,
         "source": "ccapi-simulator"]
    }

    private func statusJSON() -> [String: Any] {
        ["connected": true, "recording": recording, "mode": mode,
         "battery": ["level": 73, "status": "full"], "media": ["available": true],
         "exposure": ["iso": iso, "shutter": "1/100", "aperture": "4.0",
                      "white_balance": whiteBalance, "whiteBalance": whiteBalance]]
    }

    private func namingJSON() -> [String: Any] {
        ["stillFilenameMode": "preset_code", "stillFilenameModeOptions": ["preset_code", "usersetting1", "usersetting2"],
         "stillUserSetting1": naming, "stillUserSetting2": "EOS", "movieIndex": "A_",
         "movieReelNumber": 1, "movieReelRange": ["minimum": 1, "maximum": 9999, "step": 1],
         "movieClipNumber": 1, "movieClipRange": ["minimum": 1, "maximum": 999, "step": 1],
         "movieUserDefined": "EOS01"]
    }

    private func directoryOptions() -> [String] {
        Array(Set(["100EOSXX", "101ABCDE", directory])).sorted()
    }

    private func capabilitiesJSON(simulator: Bool) -> [String: Any] {
        let liveView: [String: Any] = ["sources": ["DESKTOP_BRIDGE_STREAM"], "defaultSource": "DESKTOP_BRIDGE_STREAM",
            "sizes": ["MEDIUM"], "defaultSize": "MEDIUM", "magnifications": [1, 5, 10],
            "currentMagnification": magnification, "minFps": 1, "maxFps": 12]
        if simulator {
            return ["iso": ["100", "800"], "shutter": ["1/100"], "aperture": ["4.0"], "white_balance": ["auto", "click"],
                    "directoryselection": ["value": directory, "ability": directoryOptions()],
                    "autopoweroff": ["value": "60", "ability": ["60", "disable", "immediately"]],
                    "fileNaming": namingJSON(), "liveView": liveView]
        }
        var supported: [CameraFeature] = [.cameraClockSync, .directoryControl, .fileNamingControl, .cameraSleep,
            .sensorCleaning, .liveView, .liveViewMagnification, .autofocus, .shutterHalfPress, .videoRecording,
            .tapFocus, .clickWhiteBalance, .focusDrive, .exposureControl]
        if events { supported.append(.eventPolling) }
        return ["supported": supported.map(\.rawValue), "fileNaming": namingJSON(), "liveView": liveView,
                "settings": [["key": "iso", "value": iso, "values": ["100", "800"]],
                             ["key": "directoryselection", "value": directory, "values": directoryOptions()]]]
    }
}
