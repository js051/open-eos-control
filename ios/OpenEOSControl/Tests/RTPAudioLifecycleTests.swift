import AVFoundation
import Foundation
import OpenEOSCore
import XCTest

@testable import OpenEOSControl

/// Exercises the production controller + pipeline with only the AAC/output device
/// boundaries replaced. No socket, camera, microphone or physical output is opened.
@MainActor
final class RTPAudioLifecycleTests: XCTestCase {
    func testMutedDefaultAndSubmissionNeverCountAsPlayerRenderProgress() async throws {
        let f = try await AudioLifecycleFixture.make()
        defer { f.controller.retireDelivery() }
        XCTAssertFalse(f.status.requested)
        f.submit()
        await Task.yield()
        XCTAssertEqual(f.decoder.calls, 0)
        f.controller.setAudioEnabled(true)
        f.submit()
        await eventually { f.status.submittedSampleFrames == 1_024 }
        XCTAssertEqual(f.status.decoderInputs, 1)
        XCTAssertEqual(f.status.decodedAccessUnits, 1)
        XCTAssertEqual(f.status.decodedSampleFrames, 1_024)
        XCTAssertEqual(f.status.renderedSampleFrames, 0)
        XCTAssertEqual(f.status.outputProgress, .unknown)
        f.output.complete(0, as: .consumed)
        await Task.yield()
        XCTAssertEqual(f.status.renderedSampleFrames, 0, "Consumption/submission is not rendering")
        f.submit()
        await eventually { f.output.count == 2 }
        f.output.complete(1, as: .rendered)
        await eventually { f.status.renderedSampleFrames == 1_024 }
        XCTAssertEqual(f.status.outputProgress, .rendering)
    }

    func testMuteRevokesInflightAndQueuedAUAcrossImmediateReenable() async throws {
        let f = try await AudioLifecycleFixture.make()
        defer { f.controller.retireDelivery() }
        let entered = expectation(description: "AAC decode entered")
        let release = DispatchSemaphore(value: 0)
        f.decoder.blockNext(entered: { entered.fulfill() }, release: release)
        f.controller.setAudioEnabled(true)
        let oldStatus = try XCTUnwrap(f.deliveries.last)
        f.submit()
        await fulfillment(of: [entered], timeout: 2)
        for _ in 0..<20 { f.submit() }
        let resetsBeforeRetire = f.decoder.resetCount
        f.controller.setAudioEnabled(false)
        XCTAssertFalse(f.controller.accepts(oldStatus))
        XCTAssertFalse(f.status.requested)
        XCTAssertTrue(f.status.retiringResources, "The old converter has not returned/reset yet")
        f.controller.setAudioEnabled(true)
        XCTAssertTrue(f.status.requested)
        release.signal()
        await eventually { f.decoder.activeCalls == 0 && f.decoder.resetCount > resetsBeforeRetire }
        XCTAssertEqual(f.output.count, 0, "Neither in-flight PCM nor old queued AUs may schedule")
        f.submit()
        await eventually { f.output.count == 1 }
        XCTAssertEqual(f.decoder.calls, 2)
        XCTAssertEqual(f.decoder.maximumConcurrentCalls, 1)
        XCTAssertFalse(f.controller.accepts(oldStatus))
    }

    func testQueuedCapacityStaysGlobalAcrossEpochReplacement() async throws {
        let f = try await AudioLifecycleFixture.make()
        defer { f.controller.retireDelivery() }
        let entered = expectation(description: "decode retained")
        let release = DispatchSemaphore(value: 0)
        f.decoder.blockNext(entered: { entered.fulfill() }, release: release)
        f.controller.setAudioEnabled(true)
        f.submit()
        await fulfillment(of: [entered], timeout: 2)
        for _ in 0..<7 { f.submit() }
        for _ in 0..<10 {
            f.controller.setAudioEnabled(false)
            f.controller.setAudioEnabled(true)
            f.submit()
        }
        XCTAssertGreaterThanOrEqual(f.status.droppedAccessUnits, 10)
        release.signal()
        await eventually { f.decoder.activeCalls == 0 }
        XCTAssertEqual(f.decoder.calls, 1, "Old queued work must skip decode and retain bounded capacity")
    }

    func testOldDecodeFailureCannotDisableNewEpoch() async throws {
        let f = try await AudioLifecycleFixture.make()
        defer { f.controller.retireDelivery() }
        let entered = expectation(description: "failing decode retained")
        let release = DispatchSemaphore(value: 0)
        f.decoder.blockNext(entered: { entered.fulfill() }, release: release, thenThrow: true)
        f.controller.setAudioEnabled(true)
        f.submit()
        await fulfillment(of: [entered], timeout: 2)
        f.controller.setAudioEnabled(false)
        f.controller.setAudioEnabled(true)
        release.signal()
        await eventually { f.decoder.activeCalls == 0 }
        f.submit()
        await eventually { f.status.submittedSampleFrames > 0 }
        XCTAssertTrue(f.status.enabled)
        XCTAssertNil(f.status.error)
    }

    func testBackgroundAndMonitoringPauseRequireExplicitEnableAfterResume() async throws {
        let f = try await AudioLifecycleFixture.make()
        defer { f.controller.retireDelivery() }
        f.controller.setAudioEnabled(true)
        f.submit()
        await eventually { f.output.count == 1 }
        f.output.completeDuringStop = true
        f.controller.setApplicationActive(false)
        XCTAssertFalse(f.status.enabled)
        XCTAssertFalse(f.status.requested)
        await eventually { !f.output.prepared && !f.status.retiringResources }
        f.controller.setApplicationActive(true)
        XCTAssertFalse(f.status.requested)
        f.submit()
        await Task.yield()
        XCTAssertEqual(f.output.count, 1)
        f.controller.setAudioEnabled(true)
        f.controller.setRenderingEnabled(false)
        XCTAssertFalse(f.status.requested)
        f.controller.setRenderingEnabled(true)
        XCTAssertFalse(f.status.requested)
    }

    func testSameEpochRetirementSnapshotCannotOverwriteCompletedRetirement() async throws {
        let copied = expectation(description: "retiring snapshot copied before emission")
        let gate = AudioTestPublicationGate(entered: { copied.fulfill() })
        let f = try await AudioLifecycleFixture.make(beforeAudioStatusEmission: { gate.hold($0) })
        let stopEntered = expectation(description: "native stop retained")
        let stopRelease = DispatchSemaphore(value: 0)
        defer {
            stopRelease.signal()
            gate.release()
            f.controller.retireDelivery()
        }
        f.controller.setAudioEnabled(true)
        f.submit()
        await eventually { f.output.count == 1 }
        f.output.completeDuringStop = true
        f.output.blockNextStop(entered: { stopEntered.fulfill() }, release: stopRelease)
        let controller = f.controller
        let muteReturned = expectation(description: "held mute publication returned")
        DispatchQueue.global().async {
            controller.setAudioEnabled(false)
            muteReturned.fulfill()
        }
        await fulfillment(of: [copied, stopEntered], timeout: 2)
        let retiring = try XCTUnwrap(gate.captured)
        XCTAssertTrue(controller.accepts(retiring), "Current retirement evidence is initially admissible")
        stopRelease.signal()
        await eventually { !f.output.prepared && f.acceptedStatus?.retiringResources == false }
        let completed = try XCTUnwrap(f.deliveries.last)
        XCTAssertEqual(retiring.owner, completed.owner)
        XCTAssertEqual(retiring.audioEpoch, completed.audioEpoch, "Epoch filtering alone cannot fence this race")
        let oldPublication = try XCTUnwrap(retiring.audioPublication)
        let newPublication = try XCTUnwrap(completed.audioPublication)
        XCTAssertEqual(oldPublication.generation, newPublication.generation)
        XCTAssertLessThan(oldPublication.sequence, newPublication.sequence)
        XCTAssertFalse(controller.accepts(retiring), "A delayed consumer must reject the older same-epoch snapshot")
        let rawBeforeRelease = f.deliveries.rawSnapshot
        XCTAssertFalse(rawBeforeRelease.contains { $0.audioPublication == oldPublication })
        gate.release()
        await fulfillment(of: [muteReturned], timeout: 2)
        XCTAssertFalse(gate.timedOut)
        let rawAfterRelease = f.deliveries.rawSnapshot
        XCTAssertEqual(rawAfterRelease.count, rawBeforeRelease.count, "Emit must fence the superseded snapshot before the consumer")
        XCTAssertFalse(rawAfterRelease.contains { $0.audioPublication == oldPublication })
        XCTAssertFalse(f.status.retiringResources)
        XCTAssertFalse(f.status.enabled)
        XCTAssertFalse(f.status.requested)
        XCTAssertEqual(f.status.renderedSampleFrames, 0, "A stop-time callback cannot revive retired playback")
        XCTAssertEqual(f.output.count, 1)
    }

    func testAudioPublicationRolloverDoesNotReuseAnOldGeneration() {
        let last = IOSRTPAudioPublication(generation: UUID(), sequence: .max)
        let next = last.next()
        XCTAssertNotEqual(next.generation, last.generation)
        XCTAssertEqual(next.sequence, 1)
        XCTAssertEqual(next.next().generation, next.generation)
        XCTAssertEqual(next.next().sequence, 2)
    }

    func testEmptyDecoderOutputAndPacketsCannotRenewFirstPCMDeadline() async throws {
        let f = try await AudioLifecycleFixture.make()
        defer { f.controller.retireDelivery() }
        f.decoder.producesPCM = false
        f.controller.setAudioEnabled(true)
        for second in 0..<5 {
            f.clock.set(Double(second))
            f.controller.recordAudioPacket(sessionID: f.owner, at: Date())
            f.submit()
            await eventually { f.status.decoderInputs >= second + 1 }
        }
        XCTAssertEqual(f.status.decodedSampleFrames, 0)
        f.clock.set(5)
        // A watchdog may retire under its lock before publishing the new epoch.
        // Keep the deadline fixed at 5 seconds and wait only for its delivery.
        await eventually { f.acceptedStatus?.outputProgress == .stalled }
        let stopped = try XCTUnwrap(f.acceptedStatus)
        XCTAssertEqual(stopped.outputProgress, .stalled)
        XCTAssertFalse(stopped.requested)
        XCTAssertEqual(stopped.decodedSampleFrames, 0)
        XCTAssertEqual(f.output.count, 0)
        f.assertVideoStillWorks()
        f.decoder.producesPCM = true
        f.controller.setAudioEnabled(true)
        f.submit()
        await eventually { f.status.submittedSampleFrames == 1_024 }
        XCTAssertTrue(f.status.enabled)
    }

    func testFreshPCMDoesNotRenewSupportedRenderDeadlineAndRecoveryIsManual() async throws {
        let f = try await AudioLifecycleFixture.make()
        defer { f.controller.retireDelivery() }
        f.controller.setAudioEnabled(true)
        for second in 0..<5 {
            f.clock.set(Double(second))
            f.submit()
            await eventually { f.status.decodedAccessUnits >= second + 1 }
        }
        f.clock.set(5)
        XCTAssertEqual(f.status.outputProgress, .stalled)
        XCTAssertTrue(f.status.error?.contains("output is unverified") == true)
        XCTAssertFalse(f.status.requested)
        await eventually { !f.output.prepared && !f.status.retiringResources }
        f.output.complete(0, as: .rendered)
        f.submit()
        await Task.yield()
        XCTAssertFalse(f.status.enabled)
        f.assertVideoStillWorks()
        f.controller.setAudioEnabled(true)
        f.submit()
        await eventually { f.output.count == 6 }
        f.output.complete(5, as: .rendered)
        await eventually { f.status.outputProgress == .rendering }
        XCTAssertEqual(f.status.renderedSampleFrames, 1_024)
    }

    func testLastPCMDeadlineIsIndependentOfDuplicateOrLateRenderCallbacks() async throws {
        let f = try await AudioLifecycleFixture.make()
        defer { f.controller.retireDelivery() }
        f.controller.setAudioEnabled(true)
        f.submit()
        await eventually { f.output.count == 1 }
        f.output.complete(0, as: .rendered)
        await eventually { f.status.renderedSampleFrames == 1_024 }
        f.clock.set(4)
        f.output.complete(0, as: .rendered)
        await Task.yield()
        f.clock.set(5)
        XCTAssertEqual(f.status.outputProgress, .stalled)
        XCTAssertTrue(f.status.error?.contains("no fresh PCM") == true)
        XCTAssertEqual(f.status.renderedSampleFrames, 1_024)
    }

    func testUnsupportedRenderObservationStaysUnknownWhileFreshPCMContinues() async throws {
        let f = try await AudioLifecycleFixture.make(supportsRenderProgress: false)
        defer { f.controller.retireDelivery() }
        f.controller.setAudioEnabled(true)
        for second in 0..<9 {
            f.clock.set(Double(second))
            f.submit()
            await eventually { f.status.decodedAccessUnits >= second + 1 }
            f.output.complete(second, as: .consumed)
        }
        XCTAssertTrue(f.status.enabled)
        XCTAssertEqual(f.status.outputProgress, .unknown)
        XCTAssertEqual(f.status.renderedSampleFrames, 0)
        XCTAssertFalse(f.status.renderProgressSupported)
    }

    func testExpiredInflightPCMIsDiscardedBeforeSubmission() async throws {
        let f = try await AudioLifecycleFixture.make()
        defer { f.controller.retireDelivery() }
        let entered = expectation(description: "decode retained past freshness")
        let release = DispatchSemaphore(value: 0)
        f.decoder.blockNext(entered: { entered.fulfill() }, release: release)
        f.controller.setAudioEnabled(true)
        f.submit()
        await fulfillment(of: [entered], timeout: 2)
        f.clock.set(1)
        release.signal()
        await eventually { f.status.droppedAccessUnits > 0 }
        XCTAssertEqual(f.output.count, 0)
        XCTAssertEqual(f.status.decodedSampleFrames, 0)
        XCTAssertNil(f.status.lastPCMAt)
    }

    func testDiscontinuityAndStopCallbacksCannotCreditReplacementOutput() async throws {
        let f = try await AudioLifecycleFixture.make()
        defer { f.controller.retireDelivery() }
        f.controller.setAudioEnabled(true)
        f.submit()
        await eventually { f.output.count == 1 }
        f.output.completeDuringStop = true
        f.submit(discontinuity: true)
        await eventually { f.output.count == 2 }
        f.output.complete(0, as: .rendered)
        await Task.yield()
        XCTAssertEqual(f.status.renderedSampleFrames, 0)
        f.output.complete(1, as: .rendered)
        await eventually { f.status.renderedSampleFrames == 1_024 }
    }

    func testOutputPreparationFailureRetiresResourcesWithoutFailingVideo() async throws {
        let f = try await AudioLifecycleFixture.make()
        defer { f.controller.retireDelivery() }
        f.output.failPrepare = true
        f.controller.setAudioEnabled(true)
        f.submit()
        await eventually { f.status.outputProgress == .failed }
        await eventually { !f.output.prepared && !f.status.retiringResources }
        XCTAssertFalse(f.status.requested)
        XCTAssertEqual(f.output.count, 0)
        f.assertVideoStillWorks()
    }

    func testRetirementAndReplacementRejectOldStatusAndCompletion() async throws {
        let f = try await AudioLifecycleFixture.make()
        defer { f.controller.retireDelivery() }
        f.controller.setAudioEnabled(true)
        let oldStatus = try XCTUnwrap(f.deliveries.last)
        f.submit()
        await eventually { f.output.count == 1 }
        let replacement = try await f.controller.makeSession(description: f.description, destinationAddress: "127.0.0.1")
        let newOwner = try XCTUnwrap(f.deliveries.last).owner
        f.controller.audioListenerReady(sessionID: newOwner)
        f.controller.setAudioEnabled(true)
        await f.session.close()
        XCTAssertTrue(f.status.enabled)
        XCTAssertFalse(f.controller.accepts(oldStatus))
        f.output.complete(0, as: .rendered)
        await Task.yield()
        XCTAssertEqual(f.status.renderedSampleFrames, 0)
        await replacement.close()
        await eventually { f.status == .inactive }
    }

    func testLastInputDelayedPCMDrainsWithoutAnyFurtherAU() async throws {
        let f = try await AudioLifecycleFixture.make()
        defer { f.controller.retireDelivery() }
        f.decoder.producesPCM = false
        f.controller.setAudioEnabled(true)
        f.submit()
        await eventually { f.status.decoderInputs == 1 }
        XCTAssertEqual(f.status.decodedSampleFrames, 0)
        f.decoder.returnPCMOnNextDrain()
        f.clock.set(0.25)
        f.controller.pollAudioProgress()
        await eventually { f.status.decodedSampleFrames == 1_024 }
        XCTAssertEqual(f.decoder.calls, 1, "The real watchdog drain must not depend on a second AAC input")
        XCTAssertEqual(f.status.submittedSampleFrames, 1_024)
    }

    func testScheduledBufferExpiryFlushesBeforeFiveSecondFailureDeadline() async throws {
        let f = try await AudioLifecycleFixture.make()
        defer { f.controller.retireDelivery() }
        f.controller.setAudioEnabled(true)
        f.submit()
        // A fake schedule records entry before its native call returns. Wait for
        // completed submission, not merely entry, before testing watchdog expiry.
        await eventually { f.acceptedStatus?.submittedSampleFrames == 1_024 }
        XCTAssertEqual(f.output.count, 1)
        XCTAssertTrue(f.output.prepared)
        f.clock.set(1)
        // Publication can be superseded before delivery; virtual time stays at 1.
        await eventually { f.acceptedStatus?.expiredOutputBuffers == 1 }
        XCTAssertEqual(f.status.expiredOutputBuffers, 1)
        await eventually { !f.output.prepared && !f.status.retiringResources }
        XCTAssertTrue(f.status.enabled, "Fresh input can rebuild output before the failure deadline")
        f.output.complete(0, as: .rendered)
        f.submit()
        await eventually { f.output.count == 2 }
        XCTAssertEqual(f.status.renderedSampleFrames, 0)
        f.output.complete(1, as: .rendered)
        await eventually { f.status.renderedSampleFrames == 1_024 }
        f.clock.set(2.1)
        XCTAssertEqual(f.status.outputProgress, .unknown, "Historical render progress is not current proof")
    }

    func testNativeScheduleReturnAccountsExpiryAtExactBoundary() async throws {
        try await assertScheduledExpiry(order: .nativeReturn)
    }

    func testTimerFirstScheduledExpiryIsCountedExactlyOnce() async throws {
        try await assertScheduledExpiry(order: .timer)
    }

    func testSnapshotFirstScheduledExpiryIsCountedExactlyOnce() async throws {
        try await assertScheduledExpiry(order: .snapshot)
    }

    func testRevokedNativeScheduleAndExpiryTimerCannotRetireReplacement() async throws {
        let f = AudioExpiryFixture()
        let entered = expectation(description: "old native schedule entered")
        let returned = expectation(description: "old consume returned")
        let release = DispatchSemaphore(value: 0)
        defer { release.signal(); f.pipeline.retire() }
        f.output.blockNextSchedule(entered: { entered.fulfill() }, release: release)
        f.submit { returned.fulfill() }
        await fulfillment(of: [entered], timeout: 2)
        let oldTimer = try XCTUnwrap(f.timers.last)
        let replacement = IOSRTPAudioPlaybackToken(owner: f.token.owner, epoch: UUID())
        f.pipeline.retire()
        f.pipeline.activate(replacement)
        f.clock.set(1)
        release.signal()
        await fulfillment(of: [returned], timeout: 2)
        let rebuilt = expectation(description: "replacement consume returned")
        f.submit(token: replacement, expiresAt: 2) { rebuilt.fulfill() }
        await fulfillment(of: [rebuilt], timeout: 2)
        XCTAssertEqual(f.output.count, 2)
        XCTAssertTrue(oldTimer.cancelled)
        oldTimer.fire() // A cancelled timer may already have a queued callback.
        f.output.complete(0, as: .rendered)
        XCTAssertEqual(f.pipeline.snapshot(for: replacement)?.pendingBuffers, 1)
        f.output.complete(1, as: .rendered)
        await eventually { f.pipeline.snapshot(for: replacement)?.pendingBuffers == 0 }
        let current = try XCTUnwrap(f.pipeline.snapshot(for: replacement))
        XCTAssertEqual(current.expiredBuffers, 0)
        XCTAssertEqual(current.renderedSampleFrames, 1_024)
        XCTAssertFalse(current.retiring)
        XCTAssertTrue(f.output.prepared)
        XCTAssertNil(f.pipeline.snapshot(for: f.token))
    }

    func testRealAACFixtureTraversesProductionPipelineAndNoDataNowDrain() async throws {
        let clock = AudioTestClock()
        let output = AudioTestOutput(supportsRenderProgress: true)
        let pipeline = IOSCcapiRTPAudioPipeline(output: output, now: { clock.now })
        let token = IOSRTPAudioPlaybackToken(owner: UUID(), epoch: UUID())
        pipeline.activate(token)
        defer { pipeline.retire(); pipeline.resetRetiredDecoder() }
        let extractor = CCAPILatmSampleExtractor()
        let firstMux = Data(base64Encoded: "IAARkB/gvvAQAmMLsxmxkXGRwXGJgZACEQBGCMHA")!
        let repeatedMux = Data(base64Encoded: "gxCIAjBGDgA=")!
        var decoded = 0
        var submitted = 0
        for index in 0..<32 {
            let unit = try extractor.consume(CCAPILatmRTPAccessUnit(
                audioMuxElement: index == 0 ? firstMux : repeatedMux,
                rtpTimestamp: UInt32(index * 1_024)), presentationTimeMicroseconds: Int64(index * 21_333))
            if let result = try pipeline.consume(unit, token: token, expiresAt: 1) {
                decoded += result.decodedSampleFrames
                submitted += result.submittedSampleFrames
            }
            if output.count > 0 { output.complete(output.count - 1, as: .rendered) }
            await Task.yield()
        }
        _ = try pipeline.drain(token: token)
        XCTAssertGreaterThan(decoded, 0, "Actual Apple AAC conversion, not fake PCM")
        XCTAssertGreaterThan(submitted, 0)
        // Output is deliberately injected; this test cannot establish physical playback.
        await eventually { (pipeline.snapshot(for: token)?.renderedSampleFrames ?? 0) > 0 }
    }

    func testBlockedNativePrepareCannotBlockWatchdogOrCreateReplacementOutput() async throws {
        let f = try await AudioLifecycleFixture.make()
        defer { f.controller.retireDelivery() }
        let entered = expectation(description: "native prepare retained")
        let release = DispatchSemaphore(value: 0)
        f.output.blockNextPrepare(entered: { entered.fulfill() }, release: release)
        f.controller.setAudioEnabled(true)
        f.submit()
        await fulfillment(of: [entered], timeout: 2)
        let controlStarted = ProcessInfo.processInfo.systemUptime
        XCTAssertEqual(f.status.decodedSampleFrames, 1_024, "PCM evidence is independent of blocked native output")
        f.clock.set(5)
        XCTAssertFalse(f.status.requested)
        XCTAssertFalse(f.status.enabled)
        XCTAssertTrue(f.status.retiringResources)
        XCTAssertLessThan(ProcessInfo.processInfo.systemUptime - controlStarted, 1)
        f.assertVideoStillWorks()
        f.controller.setAudioEnabled(true)
        f.submit()
        XCTAssertEqual(f.output.prepareCalls, 1, "No replacement device before old native call/retirement returns")
        release.signal()
        await eventually { f.output.count == 1 && f.output.prepareCalls == 2 }
        XCTAssertEqual(f.output.maximumConcurrentCalls, 1)
        XCTAssertTrue(f.status.enabled)
        XCTAssertFalse(f.status.retiringResources)
    }

    func testBlockedNativeScheduleAllowsImmediateLogicalMuteAndFencesLateCompletion() async throws {
        let f = try await AudioLifecycleFixture.make()
        defer { f.controller.retireDelivery() }
        let entered = expectation(description: "native schedule retained")
        let release = DispatchSemaphore(value: 0)
        f.output.blockNextSchedule(entered: { entered.fulfill() }, release: release)
        f.controller.setAudioEnabled(true)
        f.submit()
        await fulfillment(of: [entered], timeout: 2)
        let controlStarted = ProcessInfo.processInfo.systemUptime
        f.controller.setAudioEnabled(false)
        XCTAssertFalse(f.status.requested)
        XCTAssertTrue(f.status.retiringResources)
        XCTAssertLessThan(ProcessInfo.processInfo.systemUptime - controlStarted, 1)
        f.controller.setAudioEnabled(true)
        f.submit()
        XCTAssertEqual(f.output.prepareCalls, 1)
        release.signal()
        await eventually { f.output.count == 2 && !f.status.retiringResources }
        f.output.complete(0, as: .rendered)
        await Task.yield()
        XCTAssertEqual(f.status.renderedSampleFrames, 0)
        f.output.complete(1, as: .rendered)
        await eventually { f.status.renderedSampleFrames == 1_024 }
    }

    func testBlockedNativeStopKeepsResourcesRetiringAndReplacementWaits() async throws {
        let f = try await AudioLifecycleFixture.make()
        defer { f.controller.retireDelivery() }
        f.controller.setAudioEnabled(true)
        f.submit()
        await eventually { f.output.count == 1 }
        let entered = expectation(description: "native stop retained")
        let release = DispatchSemaphore(value: 0)
        f.output.blockNextStop(entered: { entered.fulfill() }, release: release)
        f.controller.setAudioEnabled(false)
        await fulfillment(of: [entered], timeout: 2)
        XCTAssertFalse(f.status.requested)
        XCTAssertTrue(f.status.retiringResources)
        f.controller.setAudioEnabled(true)
        f.submit()
        XCTAssertEqual(f.output.prepareCalls, 1)
        release.signal()
        await eventually { f.output.count == 2 && !f.status.retiringResources }
        XCTAssertEqual(f.output.maximumConcurrentCalls, 1)
    }

    private enum ScheduledExpiryOrder { case nativeReturn, timer, snapshot }

    private func assertScheduledExpiry(order: ScheduledExpiryOrder,
                                       file: StaticString = #filePath, line: UInt = #line) async throws {
        let f = AudioExpiryFixture()
        let entered = expectation(description: "native schedule retained")
        let returned = expectation(description: "consume returned")
        let stopEntered = expectation(description: "expired native output stopping")
        let release = DispatchSemaphore(value: 0)
        let stopRelease = DispatchSemaphore(value: 0)
        defer { release.signal(); stopRelease.signal(); f.pipeline.retire() }
        f.output.blockNextSchedule(entered: { entered.fulfill() }, release: release)
        f.submit { returned.fulfill() }
        await fulfillment(of: [entered], timeout: 2)
        let expiry = try XCTUnwrap(f.timers.last, file: file, line: line)
        XCTAssertEqual(expiry.delay, 1, file: file, line: line)
        f.clock.set(0.999)
        let before = try XCTUnwrap(f.pipeline.snapshot(for: f.token), file: file, line: line)
        XCTAssertEqual(before.expiredBuffers, 0, file: file, line: line)
        XCTAssertEqual(before.pendingBuffers, 1, file: file, line: line)
        XCTAssertTrue(f.output.prepared, file: file, line: line)
        f.output.blockNextStop(entered: { stopEntered.fulfill() }, release: stopRelease)
        f.clock.set(1)
        switch order {
        case .nativeReturn:
            break // Neither a watchdog nor a snapshot may expire this buffer first.
        case .timer:
            expiry.fire()
        case .snapshot:
            XCTAssertEqual(f.pipeline.snapshot(for: f.token)?.expiredBuffers, 1, file: file, line: line)
        }
        release.signal()
        await fulfillment(of: [returned, stopEntered], timeout: 2)
        let expired = try XCTUnwrap(f.pipeline.snapshot(for: f.token), file: file, line: line)
        XCTAssertEqual(expired.expiredBuffers, 1, file: file, line: line)
        XCTAssertEqual(expired.pendingBuffers, 0, file: file, line: line)
        XCTAssertEqual(expired.renderedSampleFrames, 0, file: file, line: line)
        XCTAssertTrue(expired.retiring, file: file, line: line)
        XCTAssertTrue(f.output.prepared, "Physical stop is still blocked", file: file, line: line)
        stopRelease.signal()
        await eventually({ !f.output.prepared && !f.pipeline.isRetiring }, file: file, line: line)
        let rebuilt = expectation(description: "fresh output submission returned")
        f.submit(expiresAt: 2) { rebuilt.fulfill() }
        await fulfillment(of: [rebuilt], timeout: 2)
        XCTAssertEqual(f.output.count, 2, file: file, line: line)
        XCTAssertTrue(expiry.cancelled, file: file, line: line)
        expiry.fire()
        f.output.complete(0, as: .rendered)
        XCTAssertEqual(f.pipeline.snapshot(for: f.token)?.pendingBuffers, 1, file: file, line: line)
        f.output.complete(1, as: .rendered)
        // Waiting for removal of the new buffer drains both FIFO callbacks.
        await eventually({ f.pipeline.snapshot(for: f.token)?.pendingBuffers == 0 }, file: file, line: line)
        let recovered = try XCTUnwrap(f.pipeline.snapshot(for: f.token), file: file, line: line)
        XCTAssertEqual(recovered.expiredBuffers, 1, file: file, line: line)
        XCTAssertEqual(recovered.renderedSampleFrames, 1_024, file: file, line: line)
        XCTAssertTrue(f.output.prepared, file: file, line: line)
        XCTAssertEqual(f.clock.now, 1, "Delivery waits cannot advance the deadline", file: file, line: line)
    }

    private func eventually(_ condition: @MainActor () -> Bool, file: StaticString = #filePath, line: UInt = #line) async {
        for _ in 0..<1_000 {
            if condition() { return }
            try? await Task.sleep(for: .milliseconds(1))
        }
        XCTFail("Timed out waiting for production audio queue", file: file, line: line)
    }
}

@MainActor
private final class AudioLifecycleFixture {
    let clock: AudioTestClock
    let decoder: AudioTestDecoder
    let output: AudioTestOutput
    let deliveries: AudioTestDeliveries
    let controller: IOSCcapiRTPController
    let session: any CCAPIRTPSession
    let owner: UUID
    let description: CCAPIRTPSessionDescription
    private let video: AudioTestVideo

    private init(supportsRenderProgress: Bool,
                 beforeAudioStatusEmission: (@Sendable (IOSCcapiRTPDelivery) -> Void)?) async throws {
        let clock = AudioTestClock()
        let decoder = AudioTestDecoder()
        let output = AudioTestOutput(supportsRenderProgress: supportsRenderProgress)
        let deliveries = AudioTestDeliveries()
        let video = AudioTestVideo()
        let pipeline = IOSCcapiRTPAudioPipeline(decoder: decoder, output: output, now: { clock.now })
        let controller = IOSCcapiRTPController(audioPipeline: pipeline, audioClock: { clock.now },
                                               beforeAudioStatusEmission: beforeAudioStatusEmission,
                                               videoConsumer: { video.timestamps.append($0.rtpTimestamp) })
        controller.setEventHandler { [weak controller] delivery in
            guard let controller else { return }
            deliveries.append(delivery, acceptingWith: controller)
        }
        let description = try CCAPIRTPSessionDescriptionParser.parse("""
            v=0
            m=video 12000 RTP/AVP 103
            a=rtpmap:103 H264/90000
            m=audio 12010 RTP/AVP 106
            a=rtpmap:106 MP4A-LATM/48000
            a=fmtp:106 cpresent=1
            """)
        let session = try await controller.makeSession(description: description, destinationAddress: "127.0.0.1")
        let owner = try XCTUnwrap(deliveries.last).owner
        controller.audioListenerReady(sessionID: owner)
        self.clock = clock
        self.decoder = decoder
        self.output = output
        self.deliveries = deliveries
        self.video = video
        self.controller = controller
        self.description = description
        self.session = session
        self.owner = owner
    }

    static func make(supportsRenderProgress: Bool = true,
                     beforeAudioStatusEmission: (@Sendable (IOSCcapiRTPDelivery) -> Void)? = nil) async throws -> AudioLifecycleFixture {
        try await AudioLifecycleFixture(supportsRenderProgress: supportsRenderProgress,
                                         beforeAudioStatusEmission: beforeAudioStatusEmission)
    }

    var acceptedStatus: IOSCcapiRTPAudioStatus? {
        controller.pollAudioProgress()
        for delivery in deliveries.snapshot.reversed() where controller.accepts(delivery) {
            if case let .audioStatus(status) = delivery.event { return status }
        }
        return nil
    }

    var status: IOSCcapiRTPAudioStatus {
        controller.pollAudioProgress()
        guard let delivery = deliveries.last, case let .audioStatus(status) = delivery.event else {
            XCTFail("Expected audio status; audio failures must never emit video failure")
            return .inactive
        }
        return status
    }

    func submit(discontinuity: Bool = false) {
        let format = CCAPIAACStreamFormat(sampleRate: 48_000, channels: 2, framesPerPacket: 1_024,
                                          audioSpecificConfig: Data([0x11, 0x90]), codec: "mp4a.40.2")
        controller.submitAudio(CCAPIAACAccessUnit(bytes: Data([0x01]), presentationTimeMicroseconds: 0,
                                                   format: format, discontinuity: discontinuity), sessionID: owner)
    }

    func assertVideoStillWorks(file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertNil(controller.currentVideoFailure(), file: file, line: line)
        let frame = CCAPIH264AccessUnit(nalUnits: [Data([0x65, 0x01])], rtpTimestamp: 10,
                                       keyFrame: true, sequenceParameterSet: nil, pictureParameterSet: nil)
        controller.enqueue(frame, sequenceParameterSet: nil, pictureParameterSet: nil, sessionID: owner)
        XCTAssertEqual(video.timestamps.last, 10, file: file, line: line)
    }
}

@MainActor
private final class AudioTestVideo { var timestamps: [UInt32] = [] }

private final class AudioTestClock: @unchecked Sendable {
    private let lock = NSLock()
    private var value: TimeInterval = 0
    var now: TimeInterval { lock.audioLocked { value } }
    func set(_ value: TimeInterval) { lock.audioLocked { self.value = value } }
}

/// No controller watchdog participates: only the selected expiry ordering can win.
private final class AudioExpiryFixture: @unchecked Sendable {
    let clock: AudioTestClock
    let output: AudioTestOutput
    let timers: AudioTestExpiryTimers
    let token = IOSRTPAudioPlaybackToken(owner: UUID(), epoch: UUID())
    let pipeline: IOSCcapiRTPAudioPipeline
    private let decodeQueue = DispatchQueue(label: "dev.openeos.test.audio-expiry-decode")

    init() {
        let clock = AudioTestClock()
        let output = AudioTestOutput(supportsRenderProgress: true)
        let timers = AudioTestExpiryTimers()
        self.clock = clock
        self.output = output
        self.timers = timers
        pipeline = IOSCcapiRTPAudioPipeline(decoder: AudioTestDecoder(), output: output,
                                           now: { clock.now }, expiryTimerFactory: { _ in timers.make() })
        pipeline.activate(token)
    }

    func submit(token: IOSRTPAudioPlaybackToken? = nil, expiresAt: TimeInterval = 1,
                completed: @escaping () -> Void) {
        let token = token ?? self.token
        decodeQueue.async { [self] in
            defer { completed() }
            // Mirror the controller's serial retirement barrier before new work.
            pipeline.resetRetiredDecoder()
            let format = CCAPIAACStreamFormat(sampleRate: 48_000, channels: 2, framesPerPacket: 1_024,
                                              audioSpecificConfig: Data([0x11, 0x90]), codec: "mp4a.40.2")
            let unit = CCAPIAACAccessUnit(bytes: Data([0x01]), presentationTimeMicroseconds: 0,
                                           format: format, discontinuity: false)
            do { _ = try pipeline.consume(unit, token: token, expiresAt: expiresAt) }
            catch { XCTFail("Unexpected expiry fixture decode failure: \(error)") }
        }
    }
}

private final class AudioTestExpiryTimers: @unchecked Sendable {
    private let lock = NSLock()
    private var timers: [AudioTestExpiryTimer] = []
    var last: AudioTestExpiryTimer? { lock.audioLocked { timers.last } }
    func make() -> AudioTestExpiryTimer {
        let timer = AudioTestExpiryTimer()
        lock.audioLocked { timers.append(timer) }
        return timer
    }
}

private final class AudioTestExpiryTimer: IOSRTPAudioExpiryTimer {
    private let lock = NSLock()
    private var interval: TimeInterval = 0
    private var handler: (() -> Void)?
    private var resumed = false
    private var isCancelled = false
    var delay: TimeInterval { lock.audioLocked { interval } }
    var cancelled: Bool { lock.audioLocked { isCancelled } }
    func schedule(after delay: TimeInterval) { lock.audioLocked { interval = delay } }
    func setEventHandler(_ handler: @escaping () -> Void) { lock.audioLocked { self.handler = handler } }
    func resume() { lock.audioLocked { resumed = true } }
    func cancel() { lock.audioLocked { isCancelled = true } }
    func fire() {
        // Deliberately allow cancelled callbacks to test the incarnation fence.
        let callback = lock.audioLocked { resumed ? handler : nil }
        callback?()
    }
}

private final class AudioTestDeliveries: @unchecked Sendable {
    private let lock = NSLock()
    private var values: [IOSCcapiRTPDelivery] = []
    private var rawValues: [IOSCcapiRTPDelivery] = []
    var last: IOSCcapiRTPDelivery? { lock.audioLocked { values.last } }
    var snapshot: [IOSCcapiRTPDelivery] { lock.audioLocked { values } }
    var rawSnapshot: [IOSCcapiRTPDelivery] { lock.audioLocked { rawValues } }
    func append(_ value: IOSCcapiRTPDelivery, acceptingWith controller: IOSCcapiRTPController) {
        lock.audioLocked {
            rawValues.append(value)
            // Match the production MainActor consumer: admission and application
            // are serial, including delayed deliveries from a retired epoch.
            if controller.accepts(value) { values.append(value) }
        }
    }
}

private final class AudioTestPublicationGate: @unchecked Sendable {
    private let lock = NSLock()
    private let resumed = DispatchSemaphore(value: 0)
    private let entered: () -> Void
    private var snapshot: IOSCcapiRTPDelivery?
    private var waitExpired = false
    var captured: IOSCcapiRTPDelivery? { lock.audioLocked { snapshot } }
    var timedOut: Bool { lock.audioLocked { waitExpired } }

    init(entered: @escaping () -> Void) { self.entered = entered }

    func hold(_ delivery: IOSCcapiRTPDelivery) {
        guard case let .audioStatus(status) = delivery.event,
              !status.enabled, status.retiringResources else { return }
        let shouldHold = lock.audioLocked { () -> Bool in
            guard snapshot == nil else { return false }
            snapshot = delivery
            return true
        }
        guard shouldHold else { return }
        entered()
        if resumed.wait(timeout: .now() + 5) != .success {
            lock.audioLocked { waitExpired = true }
        }
    }

    func release() { resumed.signal() }
}

private final class AudioTestDecoder: IOSAACDecoding {
    private let lock = NSLock()
    private var count = 0
    private var active = 0
    private var maximum = 0
    private var resets = 0
    private var outputPCM = true
    private var delayedPCM = false
    private var nextBlock: (entered: () -> Void, release: DispatchSemaphore, thenThrow: Bool)?
    var calls: Int { lock.audioLocked { count } }
    var activeCalls: Int { lock.audioLocked { active } }
    var maximumConcurrentCalls: Int { lock.audioLocked { maximum } }
    var resetCount: Int { lock.audioLocked { resets } }
    var producesPCM: Bool {
        get { lock.audioLocked { outputPCM } }
        set { lock.audioLocked { outputPCM = newValue } }
    }
    func blockNext(entered: @escaping () -> Void, release: DispatchSemaphore, thenThrow: Bool = false) {
        lock.audioLocked { nextBlock = (entered, release, thenThrow) }
    }
    func returnPCMOnNextDrain() { lock.audioLocked { delayedPCM = true } }
    func drain() throws -> AVAudioPCMBuffer? {
        let ready = lock.audioLocked { () -> Bool in
            let ready = delayedPCM; delayedPCM = false
            return ready
        }
        return ready ? makePCM() : nil
    }
    func reset() { lock.audioLocked { resets += 1 } }
    func decode(_ accessUnit: CCAPIAACAccessUnit) throws -> AVAudioPCMBuffer? {
        let plan = lock.audioLocked { () -> (Bool, (() -> Void, DispatchSemaphore, Bool)?) in
            count += 1; active += 1; maximum = max(maximum, active)
            let block = nextBlock; nextBlock = nil
            return (outputPCM, block)
        }
        defer { lock.audioLocked { active -= 1 } }
        if let (entered, release, thenThrow) = plan.1 {
            entered()
            guard release.wait(timeout: .now() + 5) == .success else { throw AudioTestError.synthetic }
            if thenThrow { throw AudioTestError.synthetic }
        }
        guard plan.0 else { return nil }
        return makePCM()
    }
    private func makePCM() -> AVAudioPCMBuffer {
        let format = AVAudioFormat(standardFormatWithSampleRate: 48_000, channels: 2)!
        let pcm = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 1_024)!
        pcm.frameLength = 1_024
        return pcm
    }
}

private enum AudioTestError: Error { case synthetic }

private final class AudioTestOutput: IOSRTPAudioOutput {
    let supportsRenderProgress: Bool
    private let lock = NSLock()
    private var callbacks: [(IOSRTPAudioBufferCompletion) -> Void] = []
    private var isPrepared = false
    private var shouldFailPrepare = false
    private var stopCompletes = false
    private var prepareCount = 0
    private var active = 0
    private var maximum = 0
    private var prepareBlock: (() -> Void, DispatchSemaphore)?
    private var scheduleBlock: (() -> Void, DispatchSemaphore)?
    private var stopBlock: (() -> Void, DispatchSemaphore)?
    var prepareCalls: Int { lock.audioLocked { prepareCount } }
    var maximumConcurrentCalls: Int { lock.audioLocked { maximum } }
    var count: Int { lock.audioLocked { callbacks.count } }
    var prepared: Bool { lock.audioLocked { isPrepared } }
    var failPrepare: Bool {
        get { lock.audioLocked { shouldFailPrepare } }
        set { lock.audioLocked { shouldFailPrepare = newValue } }
    }
    var completeDuringStop: Bool {
        get { lock.audioLocked { stopCompletes } }
        set { lock.audioLocked { stopCompletes = newValue } }
    }
    init(supportsRenderProgress: Bool) { self.supportsRenderProgress = supportsRenderProgress }
    func blockNextPrepare(entered: @escaping () -> Void, release: DispatchSemaphore) {
        lock.audioLocked { prepareBlock = (entered, release) }
    }
    func blockNextSchedule(entered: @escaping () -> Void, release: DispatchSemaphore) {
        lock.audioLocked { scheduleBlock = (entered, release) }
    }
    func blockNextStop(entered: @escaping () -> Void, release: DispatchSemaphore) {
        lock.audioLocked { stopBlock = (entered, release) }
    }
    private func wait(_ block: (() -> Void, DispatchSemaphore)?) {
        if let (entered, release) = block {
            entered()
            _ = release.wait(timeout: .now() + 5)
        }
    }
    func prepare(format: AVAudioFormat) throws {
        let block = lock.audioLocked { () -> (() -> Void, DispatchSemaphore)? in
            active += 1; maximum = max(maximum, active); prepareCount += 1
            let block = prepareBlock; prepareBlock = nil
            return block
        }
        defer { lock.audioLocked { active -= 1 } }
        wait(block)
        try lock.audioLocked {
            isPrepared = true
            if shouldFailPrepare { throw AudioTestError.synthetic }
        }
    }
    func schedule(_ buffer: AVAudioPCMBuffer, completion: @escaping (IOSRTPAudioBufferCompletion) -> Void) {
        let block = lock.audioLocked { () -> (() -> Void, DispatchSemaphore)? in
            active += 1; maximum = max(maximum, active)
            callbacks.append(completion)
            let block = scheduleBlock; scheduleBlock = nil
            return block
        }
        defer { lock.audioLocked { active -= 1 } }
        wait(block)
    }
    func stop() {
        let block = lock.audioLocked { () -> (() -> Void, DispatchSemaphore)? in
            active += 1; maximum = max(maximum, active)
            let block = stopBlock; stopBlock = nil
            return block
        }
        defer { lock.audioLocked { active -= 1 } }
        wait(block)
        let pending = lock.audioLocked { () -> [(IOSRTPAudioBufferCompletion) -> Void] in
            isPrepared = false
            return stopCompletes ? callbacks : []
        }
        for completion in pending { completion(.rendered) }
    }
    func complete(_ index: Int, as result: IOSRTPAudioBufferCompletion) {
        let callback = lock.audioLocked { callbacks.indices.contains(index) ? callbacks[index] : nil }
        callback?(result)
    }
}

private extension NSLock {
    func audioLocked<T>(_ operation: () throws -> T) rethrows -> T {
        lock(); defer { unlock() }
        return try operation()
    }
}
