import Foundation
import XCTest

#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

@testable import OpenEOSCore

final class DesktopBridgeShutterRecoveryTests: XCTestCase {
    private let active = #"{"bulbExposureActive":true,"shutterReleaseUnconfirmed":false}"#
    private let released = #"{"bulbExposureActive":false,"shutterReleaseUnconfirmed":false}"#
    private let unknown = #"{"bulbExposureActive":null,"shutterReleaseUnconfirmed":true}"#
    private let releaseError = #"{"error":{"code":"SHUTTER_RELEASE_UNCONFIRMED","message":"Release not acknowledged","feature":"BULB_EXPOSURE","engine":"ccapi"}}"#
    private let startPath = "/v1/session/session-a/bulb/start"
    private let stopPath = "/v1/session/session-a/bulb/stop"
    private let statusPath = "/v1/session/session-a/status"

    func testUntouchedLegacyStatusPreservesMissingShutterFields() async throws {
        let transport = BridgeShutterTransport()
        let client = try await initializedClient(transport)
        await transport.enqueueJSON(path: statusPath, body: #"{"connected":true}"#)
        let status = try await client.status()
        XCTAssertNil(status.bulbExposureActive)
        XCTAssertNil(status.shutterReleaseUnconfirmed)
        await assertIdle(client)
    }

    func testNormalCloseDispatchesExactSessionDeleteAndReturnsIdle() async throws {
        let transport = BridgeShutterTransport()
        let client = try await initializedClient(transport)
        await transport.enqueueJSON(method: "POST", path: startPath, body: active)
        _ = try await client.startBulbExposure()
        await transport.enqueueJSON(method: "DELETE", path: "/v1/session/session-a", status: 204, body: "")
        let state = await client.closeWithShutterReleaseState()
        XCTAssertEqual(state, .idle)
        await assertIdle(client)
        let requests = await transport.requests()
        XCTAssertEqual(requests.last?.method, "DELETE")
        XCTAssertEqual(requests.last?.path, "/v1/session/session-a")
        await assertThrows { _ = try await client.startBulbExposure() }
    }

    func testAlreadyCancelledCallerStillAwaitsNonCancelledFinalDelete() async throws {
        let transport = BridgeShutterTransport()
        let client = try await initializedClient(transport)
        await transport.enqueueJSON(method: "POST", path: startPath, body: active)
        _ = try await client.startBulbExposure()
        await transport.enqueueHold(method: "DELETE", path: "/v1/session/session-a", key: "close")
        let close = Task {
            withUnsafeCurrentTask { $0?.cancel() }
            XCTAssertTrue(Task.isCancelled)
            return await client.closeWithShutterReleaseState()
        }
        try await waitForHold("close", on: transport)
        // The cancelled caller must await cleanup rather than erase ownership.
        let pending = await client.shutterReleaseState()
        XCTAssertTrue(pending.releaseRequired)
        await assertThrows { _ = try await client.startBulbExposure() }
        await transport.resume("close", status: 204, body: "")
        let outcome = await close.value
        XCTAssertEqual(outcome, .idle)
        await assertIdle(client)
        let requests = await transport.requests()
        XCTAssertEqual(requests.filter { $0.method == "DELETE" }.map(\.path), ["/v1/session/session-a"])
        let cancelledSends = await transport.cancelledSendCount()
        XCTAssertEqual(cancelledSends, 0)
    }

    func testLostStartPersistsObligationWithoutAutomaticallyReplayingStartOrStop() async throws {
        let transport = BridgeShutterTransport()
        let client = try await initializedClient(transport)
        await transport.enqueueFailure(method: "POST", path: startPath)
        await assertThrows { _ = try await client.startBulbExposure() }
        await assertPending(client)
        await assertThrows { _ = try await client.startBulbExposure() }
        let requests = await transport.requests()
        XCTAssertEqual(requests.map(\.path), ["/health", "/v1/session", startPath])
    }

    func testCancelledStartWithLateSuccessfulResponseKeepsUnknownRelease() async throws {
        let transport = BridgeShutterTransport()
        let client = try await initializedClient(transport)
        await transport.enqueueHold(method: "POST", path: startPath, key: "start")
        let task = Task { try await client.startBulbExposure() }
        try await waitForHold("start", on: transport)
        await assertPending(client)
        task.cancel()
        await transport.resume("start", body: active)
        await assertThrows { _ = try await task.value }
        await assertPending(client)
        let requests = await transport.requests()
        XCTAssertEqual(requests.filter { $0.path == startPath }.count, 1)
        XCTAssertFalse(requests.contains { $0.path == stopPath })
    }

    func testAllCameraMutationRoutesFailClosedWhileReadsAndSafetyStopsRemainAvailable() async throws {
        let transport = BridgeShutterTransport()
        let client = try await initializedClient(transport)
        await transport.enqueueJSON(path: statusPath, body: unknown)
        _ = try await client.status()
        let item = CameraMediaItem(id: "media-1", name: "sample.JPG", kind: "image")
        let mutations: [() async throws -> Void] = [
            { _ = try await client.startBulbExposure() },
            { _ = try await client.setSetting(key: "iso", value: "100") },
            { _ = try await client.createDirectory(name: "ABCDE") },
            { _ = try await client.syncCameraClock() },
            { try await client.cleanSensor(autoPowerOff: false) },
            { try await client.sleepCamera() },
            { _ = try await client.captureStill() },
            { _ = try await client.autofocus() },
            { _ = try await client.halfPressShutter() },
            { _ = try await client.startRecording() },
            { _ = try await client.tapFocus(x: 0.5, y: 0.5) },
            { _ = try await client.clickWhiteBalance(x: 0.5, y: 0.5) },
            { _ = try await client.driveFocus(direction: .near, step: .small) },
            { try await client.startLiveView() },
            { _ = try await client.setMediaProtection(item, enabled: true) },
            { _ = try await client.setMediaRating(item, rating: 3) },
            { _ = try await client.setMediaRotation(item, degrees: 90) },
            { _ = try await client.setMediaArchive(item, enabled: true) },
            { try await client.deleteMedia(item) },
        ]
        for mutation in mutations {
            do {
                try await mutation()
                XCTFail("An unsafe mutation reached the transport")
            } catch let error as DesktopBridgeError {
                XCTAssertEqual(error, .shutterReleaseUnconfirmed)
            }
        }
        let beforeReads = await transport.requests()
        XCTAssertEqual(beforeReads.count, 3)
        await transport.enqueueJSON(path: "/v1/session/session-a/info", body: #"{"model":"Synthetic Camera"}"#)
        _ = try await client.info()
        await transport.enqueueJSON(path: "/v1/session/session-a/capabilities", body: #"{"supported":["EVENT_POLLING"]}"#)
        _ = try await client.capabilities()
        await transport.enqueueJSON(method: "POST", path: "/v1/session/session-a/recording/stop", body: released)
        _ = try await client.stopRecording()
        await transport.enqueueJSON(method: "POST", path: "/v1/session/session-a/liveview/stop", body: "{}")
        await client.stopLiveView()
        await transport.enqueueJSON(method: "DELETE", path: "/v1/session/session-a/events", status: 204, body: "")
        await client.stopEventPolling()
        // A different stop route's response is never Bulb release proof.
        await assertPending(client)
        let requests = await transport.requests()
        XCTAssertEqual(requests.count, 8)
    }

    func testUploadAlsoUsesMutationGate() async throws {
        let transport = BridgeShutterTransport()
        let client = try await initializedClient(transport)
        await transport.enqueueJSON(path: statusPath, body: unknown)
        _ = try await client.status()
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let file = directory.appendingPathComponent("synthetic.JPG")
        try Data([0xff, 0xd8, 0xff, 0xd9]).write(to: file)
        do {
            _ = try await client.uploadMedia(from: file)
            XCTFail("Upload must remain blocked")
        } catch let error as DesktopBridgeError {
            XCTAssertEqual(error, .shutterReleaseUnconfirmed)
        }
        let requests = await transport.requests()
        XCTAssertEqual(requests.count, 3)
    }

    func testInitialUnknownStatusAdoptsResponsibilityAndRetryUsesOnlySavedStop() async throws {
        let transport = BridgeShutterTransport()
        let client = try await initializedClient(transport)
        await transport.enqueueJSON(path: statusPath, body: unknown)
        let status = try await client.status()
        XCTAssertNil(status.bulbExposureActive)
        XCTAssertEqual(status.shutterReleaseUnconfirmed, true)
        await assertPending(client)
        await transport.enqueueJSON(method: "POST", path: stopPath, body: released)
        try await client.retryShutterRelease()
        await assertIdle(client)
        let requests = await transport.requests()
        XCTAssertEqual(requests.map(\.path), ["/health", "/v1/session", statusPath, stopPath])
    }

    func testInitialActiveStatusAdoptsStopEvenWithoutBulbModeOrCapability() async throws {
        let transport = BridgeShutterTransport()
        let client = try await initializedClient(transport)
        await transport.enqueueJSON(path: statusPath, body: #"{"mode":"Photo","bulbExposureActive":true}"#)
        let status = try await client.status()
        XCTAssertEqual(status.bulbExposureActive, true)
        let state = await client.shutterReleaseState()
        XCTAssertTrue(state.releaseRequired)
        XCTAssertFalse(state.releaseUnconfirmed)
        await transport.enqueueJSON(method: "POST", path: stopPath, body: #"{"bulbExposureActive":false}"#)
        try await client.retryShutterRelease()
        await assertIdle(client)
    }

    func testFalseFalseBeforeAnyStopCannotEraseAmbiguousStart() async throws {
        let transport = BridgeShutterTransport()
        let client = try await initializedClient(transport)
        await transport.enqueueFailure(method: "POST", path: startPath)
        await assertThrows { _ = try await client.startBulbExposure() }
        await transport.enqueueJSON(path: statusPath, body: released)
        let status = try await client.status()
        XCTAssertEqual(status.shutterReleaseUnconfirmed, true)
        XCTAssertNil(status.bulbExposureActive)
        await assertPending(client)
    }

    func testStrictLiteralProofRejectsNumbersStringsNullMissingAndActiveTrue() async throws {
        let invalidProofs = [
            #"{}"#,
            #"{"bulbExposureActive":false}"#,
            #"{"bulbExposureActive":0,"shutterReleaseUnconfirmed":false}"#,
            #"{"bulbExposureActive":false,"shutterReleaseUnconfirmed":0}"#,
            #"{"bulbExposureActive":false,"shutterReleaseUnconfirmed":"false"}"#,
            #"{"bulbExposureActive":"false","shutterReleaseUnconfirmed":false}"#,
            #"{"bulbExposureActive":null,"shutterReleaseUnconfirmed":false}"#,
            #"{"bulbExposureActive":false,"shutterReleaseUnconfirmed":null}"#,
            #"{"bulbExposureActive":true,"shutterReleaseUnconfirmed":false}"#,
            #"{"bulbExposureActive":false,"shutterReleaseUnconfirmed":true}"#,
        ]
        for proof in invalidProofs {
            let transport = BridgeShutterTransport()
            let client = try await initializedClient(transport)
            await transport.enqueueFailure(method: "POST", path: startPath)
            await assertThrows { _ = try await client.startBulbExposure() }
            await transport.enqueueJSON(method: "POST", path: stopPath, body: proof)
            await transport.enqueueJSON(path: statusPath, body: proof)
            await assertThrows { try await client.retryShutterRelease() }
            await assertPending(client)
            let requests = await transport.requests()
            XCTAssertEqual(requests.filter { $0.path == startPath }.count, 1, proof)
            XCTAssertEqual(requests.filter { $0.path == stopPath }.count, 1, proof)
            XCTAssertEqual(requests.map(\.path), ["/health", "/v1/session", startPath, stopPath, statusPath], proof)
        }
    }

    func testFailedStopCanBeProvedByOneFreshSameSessionStatusWithoutFullSnapshot() async throws {
        let transport = BridgeShutterTransport()
        let client = try await initializedClient(transport)
        await transport.enqueueFailure(method: "POST", path: startPath)
        await assertThrows { _ = try await client.startBulbExposure() }
        await transport.enqueueJSON(method: "POST", path: stopPath, status: 502, body: releaseError)
        await transport.enqueueJSON(path: statusPath, body: released)
        try await client.retryShutterRelease()
        await assertIdle(client)
        let requests = await transport.requests()
        XCTAssertEqual(requests.map(\.path), ["/health", "/v1/session", startPath, stopPath, statusPath])
        XCTAssertEqual(requests.last?.headers.first { $0.key.lowercased() == "cache-control" }?.value, "no-cache")
    }

    func testLostStopAndFailedStatusKeepOriginalStopErrorAndResponsibility() async throws {
        let transport = BridgeShutterTransport()
        let client = try await initializedClient(transport)
        await transport.enqueueJSON(method: "POST", path: startPath, body: active)
        _ = try await client.startBulbExposure()
        await transport.enqueueJSON(method: "POST", path: stopPath, status: 502, body: releaseError)
        await transport.enqueueFailure(method: "GET", path: statusPath)
        do {
            try await client.retryShutterRelease()
            XCTFail("Expected the original stop error")
        } catch let error as DesktopBridgeError {
            guard case .http(let code, let method, _, let kind, _, _, _) = error else {
                return XCTFail("Unexpected error: \(error)")
            }
            XCTAssertEqual(code, 502)
            XCTAssertEqual(method, "POST")
            XCTAssertEqual(kind, "SHUTTER_RELEASE_UNCONFIRMED")
        }
        await assertPending(client)
    }

    func testNormalLegacyStartAndStopRemainAvailable() async throws {
        let transport = BridgeShutterTransport()
        let client = try await initializedClient(transport)
        await transport.enqueueJSON(method: "POST", path: startPath, body: #"{"bulbExposureActive":true}"#)
        let started = try await client.startBulbExposure()
        XCTAssertEqual(started.bulbExposureActive, true)
        XCTAssertEqual(started.shutterReleaseUnconfirmed, false)
        await transport.enqueueJSON(method: "POST", path: stopPath, body: #"{"bulbExposureActive":false}"#)
        let stopped = try await client.stopBulbExposure()
        XCTAssertEqual(stopped.bulbExposureActive, false)
        await assertIdle(client)
    }

    func testNewProtocolStartCannotDowngradeToLegacyStopProof() async throws {
        let transport = BridgeShutterTransport()
        let client = try await initializedClient(transport)
        await transport.enqueueJSON(method: "POST", path: startPath, body: active)
        _ = try await client.startBulbExposure()
        await transport.enqueueJSON(method: "POST", path: stopPath, body: #"{"bulbExposureActive":false}"#)
        await transport.enqueueJSON(path: statusPath, body: #"{"bulbExposureActive":false}"#)
        await assertThrows { try await client.retryShutterRelease() }
        await assertPending(client)
    }

    func testLegacyCompatibilityIsRevokedAfterAmbiguousStop() async throws {
        let transport = BridgeShutterTransport()
        let client = try await initializedClient(transport)
        await transport.enqueueJSON(method: "POST", path: startPath, body: #"{"bulbExposureActive":true}"#)
        _ = try await client.startBulbExposure()
        await transport.enqueueFailure(method: "POST", path: stopPath)
        await assertThrows { _ = try await client.stopBulbExposure() }
        await transport.enqueueJSON(method: "POST", path: stopPath, body: #"{"bulbExposureActive":false}"#)
        await transport.enqueueJSON(path: statusPath, body: #"{"bulbExposureActive":false}"#)
        await assertThrows { try await client.retryShutterRelease() }
        await assertPending(client)
        await transport.enqueueJSON(path: statusPath, body: released)
        _ = try await client.status()
        await assertIdle(client)
    }

    func testMalformedOrNonActiveSuccessfulStartRemainsUnknown() async throws {
        for body in ["not JSON", "{}", released, unknown, #"{"bulbExposureActive":1}"#] {
            let transport = BridgeShutterTransport()
            let client = try await initializedClient(transport)
            await transport.enqueueJSON(method: "POST", path: startPath, body: body)
            await assertThrows { _ = try await client.startBulbExposure() }
            await assertPending(client)
            await transport.enqueueJSON(method: "POST", path: stopPath, body: released)
            try await client.retryShutterRelease()
            await assertIdle(client)
        }
    }

    func testCancelledStopWithLateProofRemainsPendingUntilAnotherStop() async throws {
        let transport = BridgeShutterTransport()
        let client = try await initializedClient(transport)
        await transport.enqueueJSON(method: "POST", path: startPath, body: active)
        _ = try await client.startBulbExposure()
        await transport.enqueueHold(method: "POST", path: stopPath, key: "stop")
        let task = Task { try await client.retryShutterRelease() }
        try await waitForHold("stop", on: transport)
        task.cancel()
        await transport.resume("stop", body: released)
        await assertThrows { try await task.value }
        await assertPending(client)
        let beforeRetry = await transport.requests()
        XCTAssertFalse(beforeRetry.contains { $0.path == statusPath })
        await transport.enqueueJSON(method: "POST", path: stopPath, body: released)
        try await client.retryShutterRelease()
        await assertIdle(client)
    }

    func testStatusStartedBeforeStopCannotSupplyLateReleaseProof() async throws {
        let transport = BridgeShutterTransport()
        let client = try await initializedClient(transport)
        await transport.enqueueFailure(method: "POST", path: startPath)
        await assertThrows { _ = try await client.startBulbExposure() }
        await transport.enqueueHold(path: statusPath, key: "old-status")
        let oldStatus = Task { try await client.status() }
        try await waitForHold("old-status", on: transport)
        await transport.enqueueFailure(method: "POST", path: stopPath)
        await assertThrows { _ = try await client.stopBulbExposure() }
        await transport.resume("old-status", body: released)
        let result = try await oldStatus.value
        XCTAssertNil(result.bulbExposureActive)
        XCTAssertEqual(result.shutterReleaseUnconfirmed, true)
        await assertPending(client)
        await transport.enqueueJSON(path: statusPath, body: released)
        _ = try await client.status()
        await assertIdle(client)
    }

    func testPreStopActiveStatusCannotRelockAfterReleaseAcknowledgement() async throws {
        let transport = BridgeShutterTransport()
        let client = try await initializedClient(transport)
        await transport.enqueueJSON(method: "POST", path: startPath, body: active)
        _ = try await client.startBulbExposure()
        await transport.enqueueHold(path: statusPath, key: "old-status")
        let oldStatus = Task { try await client.status() }
        try await waitForHold("old-status", on: transport)
        await transport.enqueueJSON(method: "POST", path: stopPath, body: released)
        _ = try await client.stopBulbExposure()
        await transport.resume("old-status", body: active)
        let result = try await oldStatus.value
        XCTAssertEqual(result.bulbExposureActive, false)
        XCTAssertEqual(result.shutterReleaseUnconfirmed, false)
        await assertIdle(client)
    }

    func testOldStatusCannotClearNewStartResponsibilityWithinSameSession() async throws {
        let transport = BridgeShutterTransport()
        let client = try await initializedClient(transport)
        await transport.enqueueJSON(method: "POST", path: startPath, body: active)
        _ = try await client.startBulbExposure()
        await transport.enqueueHold(path: statusPath, key: "old-status")
        let oldStatus = Task { try await client.status() }
        try await waitForHold("old-status", on: transport)
        await transport.enqueueJSON(method: "POST", path: stopPath, body: released)
        _ = try await client.stopBulbExposure()
        await transport.enqueueFailure(method: "POST", path: startPath)
        await assertThrows { _ = try await client.startBulbExposure() }
        await transport.resume("old-status", body: released)
        _ = try await oldStatus.value
        await assertPending(client)
    }

    func testConcurrentStopsDoNotDispatchTwoReleaseRequests() async throws {
        let transport = BridgeShutterTransport()
        let client = try await initializedClient(transport)
        await transport.enqueueJSON(path: statusPath, body: unknown)
        _ = try await client.status()
        await transport.enqueueHold(method: "POST", path: stopPath, key: "stop")
        let first = Task { try await client.retryShutterRelease() }
        try await waitForHold("stop", on: transport)
        let second = Task { try await client.retryShutterRelease() }
        await Task.yield()
        await transport.resume("stop", body: released)
        try await first.value
        try await second.value
        let requests = await transport.requests()
        XCTAssertEqual(requests.filter { $0.path == stopPath }.count, 1)
        await assertIdle(client)
    }

    func testCloseWaitsForDispatchedStartAndRejectsNewStartBeforeDelete() async throws {
        let transport = BridgeShutterTransport()
        let client = try await initializedClient(transport)
        await transport.enqueueHold(method: "POST", path: startPath, key: "start")
        let start = Task { try await client.startBulbExposure() }
        try await waitForHold("start", on: transport)
        await transport.enqueueJSON(method: "DELETE", path: "/v1/session/session-a", status: 502, body: releaseError)
        let close = Task { await client.closeWithShutterReleaseState() }
        try await waitForClosing(client)
        await assertThrows { _ = try await client.startBulbExposure() }
        let whileStarting = await transport.requests()
        XCTAssertFalse(whileStarting.contains { $0.method == "DELETE" })
        await transport.resume("start", body: active)
        await assertThrows { _ = try await start.value }
        let warning = await close.value
        XCTAssertFalse(warning.releaseRequired)
        XCTAssertTrue(warning.releaseUnconfirmed)
        XCTAssertNil(warning.bulbExposureActive)
        await assertIdle(client)
        let requests = await transport.requests()
        XCTAssertEqual(requests.last?.method, "DELETE")
        XCTAssertEqual(requests.last?.path, "/v1/session/session-a")
    }

    func testCancelledPendingStartCloseKeepsCommandsInOriginalSessionAcrossReinitialize() async throws {
        // Behavioral coverage for both reused and different server IDs. The held
        // transport proves ordering before settlement, not a caller scheduling race.
        for replacementID in ["session-b", "session-a"] {
            let transport = BridgeShutterTransport()
            let client = try await initializedClient(transport)
            await transport.enqueueHold(method: "POST", path: startPath, key: "old-start")
            let start = Task { try await client.startBulbExposure() }
            try await waitForHold("old-start", on: transport)
            let stop = Task { try await client.stopBulbExposure() }
            try await waitForHoldCancellation("old-start", on: transport)
            let startStillHeld = await transport.isHeld("old-start")
            XCTAssertTrue(startStillHeld, "Cancellation must not silently settle the transport gate")
            // DELETE responds immediately after Start settles; no extra gate delays
            // retirement or gives old caller continuations a manufactured window.
            await transport.enqueueJSON(method: "DELETE", path: "/v1/session/session-a", status: 204, body: "")
            await enqueueInitialization(transport, id: replacementID)
            let replacement = Task {
                let closed = await client.closeWithShutterReleaseState()
                try await client.initialize()
                return closed
            }
            try await waitForClosing(client)
            await assertThrows { try await client.initialize() }
            let beforeStartSettles = await transport.requests()
            XCTAssertFalse(beforeStartSettles.contains { $0.method == "DELETE" || $0.path.hasSuffix("/bulb/stop") })
            XCTAssertEqual(beforeStartSettles.filter { $0.method != "GET" }.map { "\($0.method) \($0.path)" }, [
                "POST /v1/session", "POST \(startPath)",
            ])
            await transport.resume("old-start", body: active)
            try await waitForRequest(method: "POST", path: "/v1/session", count: 2, on: transport)
            let closed = try await replacement.value
            XCTAssertEqual(closed, .idle)
            await assertThrows { _ = try await start.value }
            do {
                _ = try await stop.value
                XCTFail("A Stop waiting for Start must be invalidated by Close")
            } catch {
                XCTAssertEqual(error as? DesktopBridgeError, .sessionChanged)
            }

            let replacementStart = "/v1/session/\(replacementID)/bulb/start"
            let replacementStop = "/v1/session/\(replacementID)/bulb/stop"
            await transport.enqueueJSON(method: "POST", path: replacementStart, body: active)
            _ = try await client.startBulbExposure()
            await transport.enqueueHold(method: "POST", path: replacementStop, key: "new-stop")
            let newStop = Task { try await client.stopBulbExposure() }
            try await waitForHold("new-stop", on: transport)
            await assertThrows { _ = try await client.startBulbExposure() }
            let whileReplacementStops = await transport.requests()
            XCTAssertEqual(whileReplacementStops.filter { $0.path.hasSuffix("/bulb/stop") }.map(\.path), [replacementStop])
            await transport.resume("new-stop", body: released)
            _ = try await newStop.value
            await assertIdle(client)
            let commands = await transport.requests().filter { $0.method != "GET" }.map { "\($0.method) \($0.path)" }
            XCTAssertEqual(commands, [
                "POST /v1/session", "POST \(startPath)", "DELETE /v1/session/session-a",
                "POST /v1/session", "POST \(replacementStart)", "POST \(replacementStop)",
            ])
        }
    }

    func testHeldStopResponseCoalescesSecondStopAndBlocksCloseUntilSettlement() async throws {
        let transport = BridgeShutterTransport()
        let client = try await initializedClient(transport)
        await transport.enqueueJSON(method: "POST", path: startPath, body: active)
        _ = try await client.startBulbExposure()
        await transport.enqueueHold(method: "POST", path: stopPath, key: "old-stop")
        let firstStop = Task { try await client.stopBulbExposure() }
        try await waitForHold("old-stop", on: transport)
        let secondEntered = expectation(description: "second Stop enters the client while its wire response is held")
        let secondStop = Task {
            try await client.recoveryStop(onEntry: { secondEntered.fulfill() })
        }
        await fulfillment(of: [secondEntered], timeout: 3)
        let beforeClose = await transport.requests()
        XCTAssertEqual(beforeClose.filter { $0.path == stopPath }.count, 1)
        XCTAssertFalse(beforeClose.contains { $0.method == "DELETE" })

        await transport.enqueueJSON(method: "DELETE", path: "/v1/session/session-a", status: 204, body: "")
        await enqueueInitialization(transport, id: "session-b")
        let replacement = Task {
            let closed = await client.closeWithShutterReleaseState()
            try await client.initialize()
            return closed
        }
        try await waitForClosing(client)
        await assertThrows { try await client.initialize() }
        await assertThrows { _ = try await client.startBulbExposure() }
        let stopStillHeld = await transport.isHeld("old-stop")
        XCTAssertTrue(stopStillHeld)
        let beforeStopSettles = await transport.requests()
        XCTAssertEqual(beforeStopSettles.filter { $0.path == stopPath }.count, 1, "A second Stop shares the held release")
        XCTAssertFalse(beforeStopSettles.contains { $0.method == "DELETE" }, "Close must await the actual Stop response")
        XCTAssertEqual(beforeStopSettles.filter { $0.method != "GET" }.map { "\($0.method) \($0.path)" }, [
            "POST /v1/session", "POST \(startPath)", "POST \(stopPath)",
        ])

        await transport.resume("old-stop", body: released)
        try await waitForRequest(method: "POST", path: "/v1/session", count: 2, on: transport)
        let closed = try await replacement.value
        XCTAssertEqual(closed, .idle)
        await assertThrows { _ = try await firstStop.value }
        await assertThrows { _ = try await secondStop.value }
        await assertIdle(client)
        let replacementStart = "/v1/session/session-b/bulb/start"
        let replacementStop = "/v1/session/session-b/bulb/stop"
        await transport.enqueueJSON(method: "POST", path: replacementStart, body: active)
        let started = try await client.startBulbExposure()
        XCTAssertEqual(started.bulbExposureActive, true)
        await transport.enqueueJSON(method: "POST", path: replacementStop, body: released)
        let stopped = try await client.stopBulbExposure()
        XCTAssertEqual(stopped.bulbExposureActive, false)
        await assertIdle(client)
        let commands = await transport.requests().filter { $0.method != "GET" }.map { "\($0.method) \($0.path)" }
        XCTAssertEqual(commands, [
            "POST /v1/session", "POST \(startPath)", "POST \(stopPath)", "DELETE /v1/session/session-a",
            "POST /v1/session", "POST \(replacementStart)", "POST \(replacementStop)",
        ])
    }

    func testCloseAfterInFlightStopAcknowledgementDoesNotRestoreAnObligation() async throws {
        let transport = BridgeShutterTransport()
        let client = try await initializedClient(transport)
        await transport.enqueueJSON(method: "POST", path: startPath, body: active)
        _ = try await client.startBulbExposure()
        await transport.enqueueHold(method: "POST", path: stopPath, key: "stop")
        let stop = Task { try await client.stopBulbExposure() }
        try await waitForHold("stop", on: transport)
        await transport.enqueueJSON(method: "DELETE", path: "/v1/session/session-a", status: 204, body: "")
        let close = Task { await client.closeWithShutterReleaseState() }
        try await waitForClosing(client)
        await transport.resume("stop", body: released)
        await assertThrows { _ = try await stop.value }
        let outcome = await close.value
        XCTAssertEqual(outcome, .idle)
        await assertIdle(client)
        let requests = await transport.requests()
        XCTAssertEqual(Array(requests.suffix(2)).map(\.path), [stopPath, "/v1/session/session-a"])
    }

    func testFailedCloseBecomesPreviousWarningAndNeverTransplantsStopToReplacement() async throws {
        let transport = BridgeShutterTransport()
        let client = try await initializedClient(transport)
        await transport.enqueueFailure(method: "POST", path: startPath)
        await assertThrows { _ = try await client.startBulbExposure() }
        await transport.enqueueJSON(method: "DELETE", path: "/v1/session/session-a", status: 502, body: releaseError)
        let warning = await client.closeWithShutterReleaseState()
        XCTAssertFalse(warning.releaseRequired)
        XCTAssertTrue(warning.releaseUnconfirmed)
        await assertIdle(client)
        let repeatedClose = await client.closeWithShutterReleaseState()
        XCTAssertEqual(repeatedClose, warning, "Repeated close must retain the original cleanup outcome")
        await enqueueInitialization(transport, id: "session-b")
        try await client.initialize()
        try await client.retryShutterRelease()
        await transport.enqueueJSON(method: "POST", path: "/v1/session/session-b/bulb/start", body: active)
        _ = try await client.startBulbExposure()
        let requests = await transport.requests()
        XCTAssertFalse(requests.contains { $0.path.hasSuffix("/bulb/stop") })
        XCTAssertEqual(requests.last?.path, "/v1/session/session-b/bulb/start")
    }

    func testCloseUnconfirmedServerErrorWarnsEvenWithoutPreviousLocalStart() async throws {
        let transport = BridgeShutterTransport()
        let client = try await initializedClient(transport)
        await transport.enqueueJSON(method: "DELETE", path: "/v1/session/session-a", status: 502, body: releaseError)
        let warning = await client.closeWithShutterReleaseState()
        XCTAssertTrue(warning.releaseUnconfirmed)
        XCTAssertFalse(warning.releaseRequired)
        await assertIdle(client)
    }

    func testLateStatusFromClosedSessionCannotAffectReplacementEvenWhenServerReusesID() async throws {
        let transport = BridgeShutterTransport()
        let client = try await initializedClient(transport)
        await transport.enqueueHold(path: statusPath, key: "old-status")
        let oldStatus = Task { try await client.status() }
        try await waitForHold("old-status", on: transport)
        await transport.enqueueJSON(method: "DELETE", path: "/v1/session/session-a", status: 204, body: "")
        _ = await client.closeWithShutterReleaseState()
        await enqueueInitialization(transport, id: "session-a")
        try await client.initialize()
        await transport.enqueueFailure(method: "POST", path: startPath)
        await assertThrows { _ = try await client.startBulbExposure() }
        await transport.resume("old-status", body: released)
        await assertThrows { _ = try await oldStatus.value }
        await assertPending(client)
    }

    func testExplicitServerRecoveryErrorFromNonBulbMutationAlsoClosesGate() async throws {
        let transport = BridgeShutterTransport()
        let client = try await initializedClient(transport)
        await transport.enqueueJSON(
            method: "POST", path: "/v1/session/session-a/power/sleep", status: 409, body: releaseError
        )
        await assertThrows { try await client.sleepCamera() }
        await assertPending(client)
        await assertThrows { _ = try await client.captureStill() }
        let requests = await transport.requests()
        XCTAssertEqual(requests.count, 3)
    }

    func testClosingDuringInitializeRetiresLateSessionWithoutInstallingIt() async throws {
        let transport = BridgeShutterTransport()
        await transport.enqueueJSON(path: "/health", body: #"{"ok":true,"service":"open-eos-control-bridge"}"#)
        await transport.enqueueHold(method: "POST", path: "/v1/session", key: "initialize")
        let client = try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", transport: transport)
        let initialize = Task { try await client.initialize() }
        try await waitForHold("initialize", on: transport)
        _ = await client.closeWithShutterReleaseState()
        await assertThrows { try await client.initialize() }
        await transport.enqueueJSON(method: "DELETE", path: "/v1/session/late-session", status: 204, body: "")
        await transport.resume("initialize", status: 201, body: #"{"id":"late-session","engine":"ccapi"}"#)
        await assertThrows { try await initialize.value }
        await assertThrows { _ = try await client.startBulbExposure() }
        await enqueueInitialization(transport, id: "session-b")
        try await client.initialize()
        await assertIdle(client)
        let requests = await transport.requests()
        XCTAssertEqual(requests.map(\.path), ["/health", "/v1/session", "/v1/session/late-session", "/health", "/v1/session"])
    }

    private func initializedClient(_ transport: BridgeShutterTransport) async throws -> DesktopBridgeClient {
        await enqueueInitialization(transport, id: "session-a")
        let client = try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", transport: transport)
        try await client.initialize()
        return client
    }

    private func enqueueInitialization(_ transport: BridgeShutterTransport, id: String) async {
        await transport.enqueueJSON(path: "/health", body: #"{"ok":true,"service":"open-eos-control-bridge"}"#)
        await transport.enqueueJSON(method: "POST", path: "/v1/session", status: 201, body: "{\"id\":\"\(id)\",\"engine\":\"ccapi\"}")
    }

    private func assertPending(_ client: DesktopBridgeClient, file: StaticString = #filePath, line: UInt = #line) async {
        let state = await client.shutterReleaseState()
        XCTAssertTrue(state.releaseRequired, file: file, line: line)
        XCTAssertTrue(state.releaseUnconfirmed, file: file, line: line)
        XCTAssertNil(state.bulbExposureActive, file: file, line: line)
    }

    private func assertIdle(_ client: DesktopBridgeClient, file: StaticString = #filePath, line: UInt = #line) async {
        let state = await client.shutterReleaseState()
        XCTAssertEqual(state, .idle, file: file, line: line)
    }

    private func assertThrows(
        file: StaticString = #filePath,
        line: UInt = #line,
        _ action: () async throws -> Void
    ) async {
        do {
            try await action()
            XCTFail("Expected operation to fail closed", file: file, line: line)
        } catch {}
    }

    private func waitForHold(_ key: String, on transport: BridgeShutterTransport) async throws {
        for _ in 0..<500 {
            if await transport.isHeld(key) { return }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTFail("Transport did not suspend \(key)")
        throw URLError(.timedOut)
    }

    private func waitForRequest(
        method: String, path: String, count: Int, on transport: BridgeShutterTransport
    ) async throws {
        for _ in 0..<500 {
            let requests = await transport.requests()
            if requests.filter({ $0.method == method && $0.path == path }).count >= count { return }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTFail("Transport did not receive \(count) requests for \(method) \(path)")
        throw URLError(.timedOut)
    }

    private func waitForClosing(_ client: DesktopBridgeClient) async throws {
        for _ in 0..<500 {
            do { try await client.initialize() }
            catch DesktopBridgeError.sessionChanged { return }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTFail("Close did not begin")
        throw URLError(.timedOut)
    }

    private func waitForHoldCancellation(_ key: String, on transport: BridgeShutterTransport) async throws {
        for _ in 0..<500 {
            if await transport.wasHoldCancelled(key) { return }
            try await Task.sleep(for: .milliseconds(10))
        }
        XCTFail("Stop did not cancel the owned pending Start")
        throw URLError(.timedOut)
    }
}

// Test-only actor entry signal; the production client has no synchronization hook.
private extension DesktopBridgeClient {
    func recoveryStop(onEntry: @Sendable () -> Void) async throws -> CameraStatus {
        onEntry()
        return try await stopBulbExposure()
    }
}

private actor BridgeShutterTransport: CameraHTTPTransport {
    private enum Outcome: Sendable {
        case response(CameraHTTPResponse)
        case failure
        case hold(String)
    }

    private struct Stub: Sendable {
        let method: String
        let path: String
        let outcome: Outcome
    }

    private var stubs: [Stub] = []
    private var recorded: [RecordedRequest] = []
    private var cancelledSends = 0
    private var held: [String: CheckedContinuation<CameraHTTPResponse, Error>] = [:]
    private var cancelledHolds = Set<String>()

    func enqueueJSON(method: String = "GET", path: String, status: Int = 200, body: String) {
        stubs.append(Stub(
            method: method, path: path,
            outcome: .response(CameraHTTPResponse(statusCode: status, body: Data(body.utf8)))
        ))
    }

    func enqueueFailure(method: String, path: String) {
        stubs.append(Stub(method: method, path: path, outcome: .failure))
    }

    func enqueueHold(method: String = "GET", path: String, key: String) {
        stubs.append(Stub(method: method, path: path, outcome: .hold(key)))
    }

    func isHeld(_ key: String) -> Bool { held[key] != nil }
    func wasHoldCancelled(_ key: String) -> Bool { cancelledHolds.contains(key) }
    private func recordHoldCancellation(_ key: String) { cancelledHolds.insert(key) }

    func resume(_ key: String, status: Int = 200, body: String) {
        held.removeValue(forKey: key)?.resume(returning: CameraHTTPResponse(statusCode: status, body: Data(body.utf8)))
    }

    func requests() -> [RecordedRequest] { recorded }
    func cancelledSendCount() -> Int { cancelledSends }

    func send(_ request: URLRequest) async throws -> CameraHTTPResponse {
        // Model URLSession rejecting a request before transmission when its
        // caller was already cancelled; suspended replies below still run late.
        if Task.isCancelled {
            cancelledSends += 1
            throw CancellationError()
        }
        let method = request.httpMethod ?? "GET"
        let path = request.url?.path ?? ""
        recorded.append(RecordedRequest(
            method: method, path: path, headers: request.allHTTPHeaderFields ?? [:],
            body: request.httpBody, timeoutInterval: request.timeoutInterval
        ))
        guard !stubs.isEmpty else { throw MockTransportError.missingResponse("\(method) \(path)") }
        let stub = stubs.removeFirst()
        guard stub.method == method, stub.path == path else {
            throw MockTransportError.unexpectedRequest(expected: "\(stub.method) \(stub.path)", actual: "\(method) \(path)")
        }
        switch stub.outcome {
        case .response(let response): return response
        case .failure: throw URLError(.networkConnectionLost)
        case .hold(let key):
            // Observe cancellation but deliberately withhold settlement until the
            // test supplies a late response. Cancellation never opens this gate.
            return try await withTaskCancellationHandler {
                try await withCheckedThrowingContinuation { held[key] = $0 }
            } onCancel: {
                Task { await self.recordHoldCancellation(key) }
            }
        }
    }

    func download(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse {
        throw MockTransportError.missingResponse("Unexpected download")
    }

    func upload(
        _ request: URLRequest,
        from fileURL: URL,
        progress: @escaping CameraMediaProgressHandler
    ) async throws -> CameraHTTPUploadResponse {
        let result = try await send(request)
        return CameraHTTPUploadResponse(statusCode: result.statusCode, headers: result.headers, body: result.body)
    }
}
