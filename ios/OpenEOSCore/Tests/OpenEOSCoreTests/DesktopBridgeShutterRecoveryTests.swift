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
    private let startPath = "/v1/session/session-a/bulb/start/owned"
    private let ownedStopPath = "/v1/session/session-a/bulb/stop/owned"
    private let stopPath = "/v1/session/session-a/bulb/stop"
    private let statusPath = "/v1/session/session-a/status"

    func testBridgeFocusInfoReadsOptionalPublicSchemaAndNullWithoutCommandSideEffects() async throws {
        let peer = BridgeShutterTransport()
        let client = try await initializedClient(peer)
        try await enableFocus(client, peer)
        await peer.enqueueJSON(method: "POST", path: "/v1/session/session-a/liveview/start", body: #"{"active":true}"#)
        try await client.startLiveView()
        let path = "/v1/session/session-a/liveview/focus-info"
        await peer.enqueueJSON(path: path, body: focusInfoJSON)
        let info = try await client.liveViewFocusInfo()
        XCTAssertEqual(info?.frames.first?.status, .focused)
        await peer.enqueueJSON(path: path, body: "null")
        let unavailable = try await client.liveViewFocusInfo()
        XCTAssertNil(unavailable)
        await peer.enqueueJSON(path: path, body: #"{"frames":[],"magnification":true,"imageAspectRatio":2}"#)
        await assertThrows { _ = try await client.liveViewFocusInfo() }
        let reads = await peer.requests().filter { $0.path == path }
        XCTAssertEqual(reads.count, 3)
        XCTAssertTrue(reads.allSatisfy { $0.method == "GET" && $0.timeoutInterval == 2 })
        let focusCommands = await peer.requests().filter { $0.path.contains("/focus/auto") }
        XCTAssertTrue(focusCommands.isEmpty)
    }

    func testBridgeFocusInfoLateReplyCannotCrossRecycledSessionID() async throws {
        let peer = BridgeShutterTransport()
        let client = try await initializedClient(peer)
        try await enableFocus(client, peer)
        await peer.enqueueJSON(method: "POST", path: "/v1/session/session-a/liveview/start", body: #"{"active":true}"#)
        try await client.startLiveView()
        await peer.enqueueHold(path: "/v1/session/session-a/liveview/focus-info", key: "old-focus-info")
        let old = Task { try await client.liveViewFocusInfo() }
        try await waitForHold("old-focus-info", on: peer)
        await peer.enqueueJSON(method: "POST", path: "/v1/session/session-a/liveview/stop", body: #"{"active":false,"liveViewStopUnconfirmed":false}"#)
        await peer.enqueueJSON(method: "DELETE", path: "/v1/session/session-a", status: 204, body: "")
        _ = await client.closeWithShutterReleaseState()
        await enqueueInitialization(peer, id: "session-a")
        try await client.initialize()
        try await enableFocus(client, peer)
        await peer.enqueueJSON(method: "POST", path: "/v1/session/session-a/liveview/start", body: #"{"active":true}"#)
        try await client.startLiveView()
        await peer.resume("old-focus-info", body: focusInfoJSON)
        let stale = try await old.value
        XCTAssertNil(stale)
        await peer.enqueueJSON(path: "/v1/session/session-a/liveview/focus-info", body: focusInfoJSON)
        let current = try await client.liveViewFocusInfo()
        XCTAssertEqual(current?.frames.first?.status, .focused)
    }

    private var focusInfoJSON: String {
        #"{"frames":[{"left":0.1,"top":0.2,"right":0.3,"bottom":0.4,"status":"FOCUSED","kind":"NORMAL"}],"magnification":1,"imageAspectRatio":2}"#
    }

    func testHeldAFOptionalCapabilitiesFailClosedBeforeAnyRequest() async throws {
        for raw in ["{}", #"{"heldAutofocusSupported":"true","focusInfoSupported":1,"supported":["AUTOFOCUS","LIVE_VIEW"]}"#] {
            let peer = BridgeShutterTransport()
            let client = try await initializedClient(peer)
            await peer.enqueueJSON(path: "/v1/session/session-a/capabilities", body: raw)
            let capabilities = try await client.capabilities()
            XCTAssertFalse(capabilities.heldAutofocusSupported)
            XCTAssertFalse(capabilities.liveView.focusInfoSupported)
            let before = await peer.requests().count
            await assertThrows { try await client.startHeldAutofocus(owner: UUID()) }
            let info = try await client.liveViewFocusInfo()
            XCTAssertNil(info)
            let after = await peer.requests().count
            XCTAssertEqual(after, before)
        }
    }

    func testHeldAFExactOwnerStopOnlyAndNoStatusReadback() async throws {
        let peer = BridgeShutterTransport()
        let client = try await initializedClient(peer)
        try await enableFocus(client, peer)
        let owner = UUID()
        let next = UUID()
        await peer.enqueueJSON(method: "POST", path: focusStartPath, body: focusAck(owner, active: true))
        try await client.startHeldAutofocus(owner: owner)
        let holding = await client.shutterReleaseState()
        XCTAssertTrue(holding.releaseRequired)
        XCTAssertFalse(holding.releaseUnconfirmed)
        await assertThrows { _ = try await client.autofocus() }
        await assertThrows { _ = try await client.startBulbExposure() }
        await assertThrows { try await client.startHeldAutofocus(owner: next) }
        try await client.stopHeldAutofocus(owner: UUID())
        await peer.enqueueJSON(method: "POST", path: focusStopPath, body: focusAck(owner, active: false))
        try await client.stopHeldAutofocus(owner: owner)
        await assertIdle(client)
        await peer.enqueueJSON(method: "POST", path: focusStartPath, body: focusAck(next, active: true))
        try await client.startHeldAutofocus(owner: next)
        try await client.stopHeldAutofocus(owner: owner)
        let writes = await peer.requests().filter { $0.path.contains("/focus/") }
        XCTAssertEqual(writes.map(\.path), [focusStartPath, focusStopPath, focusStartPath])
        let first = try XCTUnwrap(writes.first?.body)
        XCTAssertEqual((try JSONSerialization.jsonObject(with: first) as? [String: String])?["ownerId"], owner.uuidString)
        await peer.enqueueJSON(method: "POST", path: focusStopPath, body: focusAck(next, active: false))
        try await client.stopHeldAutofocus(owner: next)
        await assertIdle(client)
    }

    func testHeldAFMalformedStopAndFailedStopKeepOnlyOriginalRetry() async throws {
        for outcome in ["lost", "{}", #"{"ownerId":"wrong","active":false,"stopUnconfirmed":false}"#] {
            let peer = BridgeShutterTransport()
            let client = try await initializedClient(peer)
            try await enableFocus(client, peer)
            let owner = UUID()
            await peer.enqueueJSON(method: "POST", path: focusStartPath, body: focusAck(owner, active: true))
            try await client.startHeldAutofocus(owner: owner)
            if outcome == "lost" { await peer.enqueueFailure(method: "POST", path: focusStopPath) }
            else { await peer.enqueueJSON(method: "POST", path: focusStopPath, body: outcome) }
            await assertThrows { try await client.stopHeldAutofocus(owner: owner) }
            let pending = await client.shutterReleaseState()
            XCTAssertTrue(pending.releaseRequired)
            XCTAssertTrue(pending.releaseUnconfirmed)
            await assertThrows { try await client.startHeldAutofocus(owner: UUID()) }
            // A shared server warning must never invent a bulb stop for the AF owner.
            await peer.enqueueJSON(path: statusPath, body: unknown)
            _ = try await client.status()
            await peer.enqueueJSON(method: "POST", path: focusStopPath, body: focusAck(owner, active: false))
            try await client.retryShutterRelease()
            await assertIdle(client)
            let writes = await peer.requests().filter { $0.method == "POST" && $0.path != "/v1/session" }
            XCTAssertEqual(writes.map(\.path), [focusStartPath, focusStopPath, focusStopPath])
        }
    }

    func testHeldAFLateUnknownStatusCannotRecreateBulbReleaseAfterStopACK() async throws {
        let peer = BridgeShutterTransport()
        let client = try await initializedClient(peer)
        try await enableFocus(client, peer)
        let owner = UUID()
        await peer.enqueueJSON(method: "POST", path: focusStartPath, body: focusAck(owner, active: true))
        try await client.startHeldAutofocus(owner: owner)
        await peer.enqueueHold(path: statusPath, key: "old-af-status")
        let status = Task { try await client.status() }
        try await waitForHold("old-af-status", on: peer)
        await peer.enqueueJSON(method: "POST", path: focusStopPath, body: focusAck(owner, active: false))
        try await client.stopHeldAutofocus(owner: owner)
        await peer.resume("old-af-status", body: unknown)
        let lateStatus = try await status.value
        XCTAssertEqual(lateStatus.shutterReleaseUnconfirmed, false)
        await assertIdle(client)
        let before = await peer.requests().count
        try await client.retryShutterRelease()
        let after = await peer.requests().count
        XCTAssertEqual(after, before, "Old AF status must not invent a bulb stop")
    }

    func testHeldAFStartStaysHeldThroughCloseAndCannotClearReusedSessionTask() async throws {
        let peer = BridgeShutterTransport()
        let client = try await initializedClient(peer)
        try await enableFocus(client, peer)
        let old = UUID()
        await peer.enqueueHold(method: "POST", path: focusStartPath, key: "focus-start")
        let start = Task { try await client.startHeldAutofocus(owner: old) }
        try await waitForHold("focus-start", on: peer)
        await peer.enqueueJSON(method: "POST", path: focusStopPath, body: focusAck(old, active: false))
        await peer.enqueueJSON(method: "DELETE", path: "/v1/session/session-a", status: 204, body: "")
        let close = Task { await client.closeWithShutterReleaseState() }
        try await waitForHoldCancellation("focus-start", on: peer)
        try await waitForRequest(method: "DELETE", path: "/v1/session/session-a", count: 1, on: peer)
        let closed = await close.value
        XCTAssertTrue(closed.releaseUnconfirmed, "Unsettled Start remains a previous-connection warning")
        XCTAssertFalse(closed.releaseRequired)
        let stillHeld = await peer.isHeld("focus-start")
        XCTAssertTrue(stillHeld, "Retirement must finish before ignored-cancellation Start settles")
        // The server may recycle a session ID: local generation still owns every response.
        await enqueueInitialization(peer, id: "session-a")
        try await client.initialize()
        try await enableFocus(client, peer)
        let current = UUID()
        await peer.enqueueHold(method: "POST", path: focusStartPath, key: "new-focus-start")
        let newStart = Task { try await client.startHeldAutofocus(owner: current) }
        try await waitForHold("new-focus-start", on: peer)
        await peer.resume("focus-start", body: focusAck(old, active: true))
        await assertThrows { try await start.value }
        try await client.stopHeldAutofocus(owner: old)
        let held = await client.shutterReleaseState()
        XCTAssertTrue(held.releaseRequired)
        await peer.enqueueJSON(method: "POST", path: focusStopPath, body: focusAck(current, active: false))
        try await client.stopHeldAutofocus(owner: current)
        let stillReserved = await client.shutterReleaseState()
        XCTAssertTrue(stillReserved.releaseRequired, "Old callback must not clear the new Start reservation")
        await assertThrows { _ = try await client.autofocus() }
        await peer.resume("new-focus-start", body: focusAck(current, active: true))
        await assertThrows { try await newStart.value }
        await assertIdle(client)
        let cancelled = await peer.cancelledSendCount()
        XCTAssertEqual(cancelled, 0, "Compensating stop does not inherit caller cancellation")
    }

    func testHeldAFPendingStartStopACKRetainsLiveMutationReservation() async throws {
        let peer = BridgeShutterTransport(), owner = UUID()
        let client = try await initializedClient(peer)
        try await enableFocus(client, peer)
        await peer.enqueueHold(method: "POST", path: focusStartPath, key: "pending-start")
        let start = Task { try await client.startHeldAutofocus(owner: owner) }
        try await waitForHold("pending-start", on: peer)
        await peer.enqueueJSON(method: "POST", path: focusStopPath, body: focusAck(owner, active: false))
        try await client.stopHeldAutofocus(owner: owner)
        let pending = await client.shutterReleaseState()
        XCTAssertTrue(pending.releaseRequired)
        XCTAssertTrue(pending.releaseUnconfirmed)
        let before = await peer.requests().count
        await assertThrows { _ = try await client.autofocus() }
        await assertThrows { _ = try await client.startBulbExposure() }
        await assertThrows { try await client.startHeldAutofocus(owner: UUID()) }
        try await client.stopHeldAutofocus(owner: owner)
        let after = await peer.requests().count
        XCTAssertEqual(before, after, "No repeated owner or mutation may evict an unresolved Start tombstone")
        await peer.resume("pending-start", body: focusAck(owner, active: true))
        await assertThrows { try await start.value }
        await assertIdle(client)
    }

    func testHeldAF409IsNotProofAndOnlyOwnerMatchedRetryClearsWarning() async throws {
        let peer = BridgeShutterTransport(), owner = UUID()
        let client = try await initializedClient(peer)
        try await enableFocus(client, peer)
        await peer.enqueueJSON(method: "POST", path: focusStartPath, body: focusAck(owner, active: true))
        try await client.startHeldAutofocus(owner: owner)
        await peer.enqueueJSON(method: "POST", path: focusStopPath, status: 409, body: focusAck(owner, active: false))
        await assertThrows { try await client.stopHeldAutofocus(owner: owner) }
        let pending = await client.shutterReleaseState()
        XCTAssertTrue(pending.releaseRequired && pending.releaseUnconfirmed)
        await assertThrows { _ = try await client.autofocus() }
        await peer.enqueueJSON(method: "POST", path: focusStopPath, body: focusAck(owner, active: false))
        try await client.retryShutterRelease()
        await assertIdle(client)
    }

    func testHeldAFStopTimeoutRetiresWhileBothTransportRepliesRemainHeld() async throws {
        let peer = BridgeShutterTransport(), owner = UUID()
        let client = try await initializedClient(peer)
        try await enableFocus(client, peer)
        await peer.enqueueHold(method: "POST", path: focusStartPath, key: "never-start")
        let start = Task { try await client.startHeldAutofocus(owner: owner) }
        try await waitForHold("never-start", on: peer)
        await peer.enqueueHold(method: "POST", path: focusStopPath, key: "never-stop")
        await peer.enqueueJSON(method: "DELETE", path: "/v1/session/session-a", status: 204, body: "")
        let stop = Task { try await client.stopHeldAutofocus(owner: owner) }
        try await waitForHold("never-stop", on: peer)
        let retired = expectation(description: "Bounded AF cleanup retires without transport settlement")
        let close = Task {
            let result = await client.closeWithShutterReleaseState()
            retired.fulfill()
            return result
        }
        try await waitForHold("never-stop", on: peer)
        await fulfillment(of: [retired], timeout: 20)
        let startHeld = await peer.isHeld("never-start"), stopHeld = await peer.isHeld("never-stop")
        XCTAssertTrue(startHeld && stopHeld)
        // Release only after asserting bounded retirement, also draining on regression.
        await peer.resume("never-stop", body: focusAck(owner, active: false))
        await peer.resume("never-start", body: focusAck(owner, active: true))
        let closed = await close.value
        XCTAssertTrue(closed.releaseUnconfirmed)
        XCTAssertFalse(closed.releaseRequired)
        await assertThrows { try await start.value }
        await assertThrows { try await stop.value }
        let writes = await peer.requests().filter { $0.path == focusStopPath }
        XCTAssertEqual(writes.count, 1, "Close and Start compensation coalesce the same owner")
    }

    func testHeldAFMaintenanceCloseRetiresWithStartStillHeldAnd409Warning() async throws {
        let peer = BridgeShutterTransport(), owner = UUID()
        let client = try await initializedClient(peer)
        // A failed outcome still owns the strict maintenance retirement route.
        await peer.enqueueJSON(method: "POST", path: "/v1/session/session-a/maintenance/sensor-cleaning/outcome", body: "{}")
        await assertThrows { try await client.performMaintenance(CameraMaintenanceAttempt(operation: .sensorCleaning)) }
        try await enableFocus(client, peer)
        await peer.enqueueHold(method: "POST", path: focusStartPath, key: "maintenance-focus")
        let start = Task { try await client.startHeldAutofocus(owner: owner) }
        try await waitForHold("maintenance-focus", on: peer)
        await peer.enqueueJSON(method: "POST", path: focusStopPath, status: 409, body: focusAck(owner, active: false))
        let retirement = "/v1/session/session-a/maintenance/control/session"
        await peer.enqueueJSON(method: "DELETE", path: retirement, status: 204, body: "")
        let close = Task { await client.closeWithShutterReleaseState() }
        try await waitForRequest(method: "DELETE", path: retirement, count: 1, on: peer)
        let result = await close.value
        XCTAssertTrue(result.releaseUnconfirmed)
        XCTAssertFalse(result.releaseRequired)
        let stillHeld = await peer.isHeld("maintenance-focus")
        XCTAssertTrue(stillHeld)
        await peer.resume("maintenance-focus", body: focusAck(owner, active: true))
        await assertThrows { try await start.value }
        let trace = await peer.requests()
        XCTAssertFalse(trace.contains { $0.method == "DELETE" && $0.path == "/v1/session/session-a" })
        XCTAssertEqual(trace.filter { $0.path == focusStopPath }.count, 1)
    }

    func testHeldAFConfirmedReleaseStillUsesBoundedOriginalRetirement() async throws {
        let peer = BridgeShutterTransport(), owner = UUID()
        let client = try await initializedClient(peer)
        try await enableFocus(client, peer)
        await peer.enqueueJSON(method: "POST", path: focusStartPath, body: focusAck(owner, active: true))
        try await client.startHeldAutofocus(owner: owner)
        await peer.enqueueJSON(method: "POST", path: focusStopPath, body: focusAck(owner, active: false))
        try await client.stopHeldAutofocus(owner: owner)
        await peer.enqueueHold(method: "DELETE", path: "/v1/session/session-a", key: "focus-retirement")
        let retired = expectation(description: "AF-used session retirement has total deadline")
        let close = Task {
            defer { retired.fulfill() }
            return await client.closeWithShutterReleaseState()
        }
        try await waitForHold("focus-retirement", on: peer)
        await fulfillment(of: [retired], timeout: 20)
        let stillHeld = await peer.isHeld("focus-retirement")
        XCTAssertTrue(stillHeld)
        let warning = await client.connectionRetirementUnconfirmed
        XCTAssertTrue(warning)
        await peer.resume("focus-retirement", status: 204, body: "")
        _ = await close.value
        let retainedWarning = await client.connectionRetirementUnconfirmed
        XCTAssertTrue(retainedWarning, "Late data-only 204 cannot clear retired warning")
    }

    private var focusStartPath: String { "/v1/session/session-a/focus/auto/start" }
    private var focusStopPath: String { "/v1/session/session-a/focus/auto/stop" }
    private func focusAck(_ owner: UUID, active: Bool) -> String {
        "{\"ownerId\":\"\(owner.uuidString)\",\"active\":\(active),\"stopUnconfirmed\":false}"
    }
    private func enableFocus(_ client: DesktopBridgeClient, _ peer: BridgeShutterTransport) async throws {
        await peer.enqueueJSON(path: "/v1/session/session-a/capabilities", body:
            #"{"heldAutofocusSupported":true,"focusInfoSupported":true,"supported":["AUTOFOCUS","LIVE_VIEW"]}"#)
        let capabilities = try await client.capabilities()
        XCTAssertTrue(capabilities.heldAutofocusSupported)
        XCTAssertTrue(capabilities.liveView.focusInfoSupported)
    }

    func testUntouchedLegacyStatusPreservesMissingShutterFields() async throws {
        let transport = BridgeShutterTransport()
        let client = try await initializedClient(transport)
        await transport.enqueueJSON(path: statusPath, body: #"{"connected":true}"#)
        let status = try await client.status()
        XCTAssertNil(status.bulbExposureActive)
        XCTAssertNil(status.shutterReleaseUnconfirmed)
        await assertIdle(client)
    }

    func testAllCameraMutationRoutesFailClosedWhileReadsAndSafetyStopsRemainAvailable() async throws {
        let transport = BridgeShutterTransport()
        let client = try await initializedClient(transport)
        await transport.enqueueJSON(method: "POST", path: "/v1/session/session-a/liveview/start", body: #"{"active":true}"#)
        try await client.startLiveView()
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
        XCTAssertEqual(beforeReads.count, 4)
        await transport.enqueueJSON(path: "/v1/session/session-a/info", body: #"{"model":"Synthetic Camera"}"#)
        _ = try await client.info()
        await transport.enqueueJSON(path: "/v1/session/session-a/capabilities", body: #"{"supported":["EVENT_POLLING"]}"#)
        _ = try await client.capabilities()
        await transport.enqueueJSON(method: "POST", path: "/v1/session/session-a/recording/stop", body: released)
        _ = try await client.stopRecording()
        await transport.enqueueJSON(method: "POST", path: "/v1/session/session-a/liveview/stop", body: #"{"active":false,"liveViewStopUnconfirmed":false}"#)
        await client.stopLiveView()
        await transport.enqueueJSON(method: "DELETE", path: "/v1/session/session-a/events", status: 204, body: "")
        await client.stopEventPolling()
        // A different stop route's response is never Bulb release proof.
        await assertPending(client)
        let requests = await transport.requests()
        XCTAssertEqual(requests.count, 9)
        XCTAssertEqual(requests.filter { $0.path == "/v1/session/session-a/liveview/stop" }.count, 1)
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
            await transport.enqueueJSON(path: statusPath, body: unknown)
            _ = try await client.status()
            await transport.enqueueJSON(method: "POST", path: stopPath, body: proof)
            await transport.enqueueJSON(path: statusPath, body: proof)
            await assertThrows { try await client.retryShutterRelease() }
            await assertPending(client)
            let requests = await transport.requests()
            XCTAssertEqual(requests.filter { $0.path == startPath }.count, 0, proof)
            XCTAssertEqual(requests.filter { $0.path == stopPath }.count, 1, proof)
            XCTAssertEqual(requests.map(\.path), ["/health", "/v1/session", statusPath, stopPath, statusPath], proof)
        }
    }

    func testFailedStopCanBeProvedByOneFreshSameSessionStatusWithoutFullSnapshot() async throws {
        let transport = BridgeShutterTransport()
        let client = try await initializedClient(transport)
        await transport.enqueueJSON(path: statusPath, body: unknown)
        _ = try await client.status()
        await transport.enqueueJSON(method: "POST", path: stopPath, status: 502, body: releaseError)
        await transport.enqueueJSON(path: statusPath, body: released)
        try await client.retryShutterRelease()
        await assertIdle(client)
        let requests = await transport.requests()
        XCTAssertEqual(requests.map(\.path), ["/health", "/v1/session", statusPath, stopPath, statusPath])
        XCTAssertEqual(requests.last?.headers.first { $0.key.lowercased() == "cache-control" }?.value, "no-cache")
    }

    func testLostStopAndFailedStatusKeepOriginalStopErrorAndResponsibility() async throws {
        let transport = BridgeShutterTransport()
        let client = try await initializedClient(transport)
        await transport.enqueueJSON(path: statusPath, body: unknown)
        _ = try await client.status()
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

    func testCloseUnconfirmedServerErrorWarnsEvenWithoutPreviousLocalStart() async throws {
        let transport = BridgeShutterTransport()
        let client = try await initializedClient(transport)
        await transport.enqueueJSON(method: "DELETE", path: "/v1/session/session-a", status: 502, body: releaseError)
        let warning = await client.closeWithShutterReleaseState()
        XCTAssertTrue(warning.releaseUnconfirmed)
        XCTAssertFalse(warning.releaseRequired)
        await assertIdle(client)
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

    // Authored owned-protocol fixtures; native Swift execution is still required.
    private func ownedAck(_ owner: DesktopBridgeBulbOwner, outcome: String, status: String? = nil) -> String {
        let observation = status.map { ",\"status\":\($0)" } ?? ""
        return "{\"ownerId\":\"\(owner.id.uuidString)\",\"outcome\":\"\(outcome)\"\(observation)}"
    }

    func testOwnedStartAndStopUseExactTokenAndBoundedRequests() async throws {
        let peer = BridgeShutterTransport()
        let client = try await initializedClient(peer)
        let owner = try await client.prepareOwnedBulb()
        await peer.enqueueJSON(method: "POST", path: startPath, body: ownedAck(owner, outcome: "started", status: active))
        let started = try await client.startOwnedBulb(owner)
        XCTAssertEqual(started.bulbExposureActive, true)
        await peer.enqueueJSON(method: "POST", path: ownedStopPath, body: ownedAck(owner, outcome: "released", status: released))
        let stopped = try await client.stopOwnedBulb(owner)
        XCTAssertEqual(stopped?.bulbExposureActive, false)
        await assertIdle(client)
        let writes = await peer.requests().filter { $0.path.contains("/bulb/") }
        XCTAssertEqual(writes.map(\.path), [startPath, ownedStopPath])
        for request in writes {
            let data = try XCTUnwrap(request.body)
            let body = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: String])
            XCTAssertEqual(body, ["ownerId": owner.id.uuidString])
            XCTAssertEqual(request.timeoutInterval, 15)
        }
    }

    func testPendingIgnoringCancellationStartDoesNotBlockStopOrPublishLateSuccess() async throws {
        let peer = BridgeShutterTransport()
        let client = try await initializedClient(peer)
        let owner = try await client.prepareOwnedBulb()
        await peer.enqueueHold(method: "POST", path: startPath, key: "owned-start")
        let start = Task { try await client.startOwnedBulb(owner) }
        try await waitForHold("owned-start", on: peer)
        start.cancel()
        await peer.enqueueJSON(method: "POST", path: ownedStopPath, body: ownedAck(owner, outcome: "released", status: released))
        _ = try await client.stopOwnedBulb(owner)
        let stillHeld = await peer.isHeld("owned-start")
        XCTAssertTrue(stillHeld, "Stop must settle before the cancellation-ignoring Start transport")
        await assertIdle(client)
        await peer.resume("owned-start", body: ownedAck(owner, outcome: "started", status: active))
        await assertThrows { _ = try await start.value }
        await assertIdle(client)
    }

    func testConcurrentOwnedStopsShareOneWireRequestEvenWhenCallerCancels() async throws {
        let peer = BridgeShutterTransport()
        let client = try await initializedClient(peer)
        let owner = try await client.prepareOwnedBulb()
        await peer.enqueueHold(method: "POST", path: ownedStopPath, key: "owned-stop")
        let first = Task { try await client.stopOwnedBulb(owner) }
        try await waitForHold("owned-stop", on: peer)
        let entered = expectation(description: "second owned Stop entered")
        let second = Task { try await client.ownedRecoveryStop(owner, onEntry: { entered.fulfill() }) }
        await fulfillment(of: [entered], timeout: 3)
        first.cancel()
        await peer.resume("owned-stop", body: ownedAck(owner, outcome: "cancelled-before-start"))
        let firstReceipt = try await first.value
        let secondReceipt = try await second.value
        XCTAssertNil(firstReceipt)
        XCTAssertNil(secondReceipt)
        let requests = await peer.requests()
        XCTAssertEqual(requests.filter { $0.path == ownedStopPath }.count, 1)
        await assertTerminalWithoutObservation(client)
    }

    func testForeignTokenAndRepeatedStartCannotDispatch() async throws {
        let peer = BridgeShutterTransport()
        let client = try await initializedClient(peer)
        let owner = try await client.prepareOwnedBulb()
        let foreign = DesktopBridgeBulbOwner(id: UUID(), generation: owner.generation,
            startURL: owner.startURL, stopURL: owner.stopURL)
        await assertThrows { _ = try await client.startOwnedBulb(foreign) }
        await assertThrows { _ = try await client.stopOwnedBulb(foreign) }
        await peer.enqueueFailure(method: "POST", path: startPath)
        await assertThrows { _ = try await client.startOwnedBulb(owner) }
        await assertThrows { _ = try await client.startOwnedBulb(owner) }
        await assertThrows { _ = try await client.prepareOwnedBulb() }
        let requests = await peer.requests()
        XCTAssertEqual(requests.map(\.path), ["/health", "/v1/session", startPath])
        await assertPending(client)
    }

    func testOwnedFailedStopCannotBeClearedByDualFalseGET() async throws {
        let peer = BridgeShutterTransport()
        let client = try await initializedClient(peer)
        let owner = try await client.prepareOwnedBulb()
        await peer.enqueueFailure(method: "POST", path: ownedStopPath)
        await assertThrows { try await client.retryShutterRelease() }
        await peer.enqueueJSON(path: statusPath, body: released)
        let observation = try await client.status()
        XCTAssertEqual(observation.shutterReleaseUnconfirmed, true)
        await assertPending(client)
        await peer.enqueueJSON(method: "POST", path: ownedStopPath, body: ownedAck(owner, outcome: "cancelled-before-start"))
        try await client.retryShutterRelease()
        await assertTerminalWithoutObservation(client)
        let paths = await peer.requests().map(\.path)
        XCTAssertEqual(paths, ["/health", "/v1/session", ownedStopPath, statusPath, ownedStopPath])
    }

    func testOwnedReceiptsRetainNewerSharedWarning() async throws {
        let proofs: [String?] = [nil, released]
        for proof in proofs {
            let peer = BridgeShutterTransport()
            let client = try await initializedClient(peer)
            let owner = try await client.prepareOwnedBulb()
            await peer.enqueueHold(method: "POST", path: ownedStopPath, key: "cached-stop")
            let stop = Task { try await client.stopOwnedBulb(owner) }
            try await waitForHold("cached-stop", on: peer)
            await peer.enqueueJSON(path: statusPath, body: unknown)
            _ = try await client.status()
            await peer.resume("cached-stop", body: ownedAck(owner, outcome: "released", status: proof))
            let receipt = try await stop.value
            XCTAssertNil(receipt)
            let fresh = await client.ownedBulbReleaseHasFreshStatus
            XCTAssertFalse(fresh)
            await assertPending(client)
            await peer.enqueueJSON(method: "POST", path: stopPath, body: released)
            try await client.retryShutterRelease()
            await assertIdle(client)
        }
    }

    func testOwnedEnvelopesRejectAmbiguousJSONAndNullStatus() async throws {
        for starting in [true, false] {
            for kind in ["duplicate-owner", "duplicate-outcome", "duplicate-status", "invalid-utf8", "trailing-json", "null-status", "cancelled-with-status"] {
                let peer = BridgeShutterTransport()
                let client = try await initializedClient(peer)
                let owner = try await client.prepareOwnedBulb()
                let outcome = starting ? "started" : "released"
                let proof = starting ? active : released
                let valid = ownedAck(owner, outcome: outcome, status: proof)
                var raw = valid
                switch kind {
                case "duplicate-owner": raw = "{\"ownerId\":\"\(owner.id.uuidString)\"," + valid.dropFirst()
                case "duplicate-outcome": raw = "{\"outcome\":\"\(outcome)\"," + valid.dropFirst()
                case "duplicate-status": raw = "{\"status\":\(proof)," + valid.dropFirst()
                case "trailing-json": raw += "{}"
                case "null-status": raw = ownedAck(owner, outcome: outcome, status: "null")
                case "cancelled-with-status": raw = ownedAck(owner, outcome: "cancelled-before-start", status: released)
                default: break
                }
                var data = Data(raw.utf8)
                if kind == "invalid-utf8" { data.append(0xff) }
                await peer.enqueueRaw(method: "POST", path: starting ? startPath : ownedStopPath, body: data)
                await assertThrows {
                    if starting { _ = try await client.startOwnedBulb(owner) }
                    else { _ = try await client.stopOwnedBulb(owner) }
                }
                await assertPending(client)
            }
        }
    }

    func testActiveGETAfterOwnedStartRemainsKnownAndCachedReceiptPreservesObservation() async throws {
        let peer = BridgeShutterTransport()
        let client = try await initializedClient(peer)
        let owner = try await client.prepareOwnedBulb()
        await peer.enqueueJSON(method: "POST", path: startPath, body: ownedAck(owner, outcome: "started", status: active))
        _ = try await client.startOwnedBulb(owner)
        await peer.enqueueJSON(path: statusPath, body: active)
        let status = try await client.status()
        XCTAssertEqual(status.bulbExposureActive, true)
        XCTAssertEqual(status.shutterReleaseUnconfirmed, false)
        let known = await client.shutterReleaseState()
        XCTAssertTrue(known.releaseRequired)
        XCTAssertFalse(known.releaseUnconfirmed)
        XCTAssertEqual(known.bulbExposureActive, true)
        await peer.enqueueJSON(method: "POST", path: ownedStopPath, body: ownedAck(owner, outcome: "released"))
        let receipt = try await client.stopOwnedBulb(owner)
        XCTAssertNil(receipt)
        let retained = await client.shutterReleaseState()
        XCTAssertTrue(retained.releaseRequired)
        XCTAssertFalse(retained.releaseUnconfirmed)
        XCTAssertEqual(retained.bulbExposureActive, true)
        let fresh = await client.ownedBulbReleaseHasFreshStatus
        XCTAssertFalse(fresh)
        await peer.enqueueJSON(method: "POST", path: stopPath, body: released)
        try await client.retryShutterRelease()
        await assertIdle(client)
    }

    func testOwnedInvalidAcknowledgementsAndResponseBoundRemainPending() async throws {
        for kind in ["wrong-owner", "wrong-outcome", "missing-proof", "numeric-proof", "oversized", "partial"] {
            let peer = BridgeShutterTransport()
            let client = try await initializedClient(peer)
            let owner = try await client.prepareOwnedBulb()
            var body = ownedAck(owner, outcome: "released", status: released)
            var status = 200
            switch kind {
            case "wrong-owner": body = body.replacingOccurrences(of: owner.id.uuidString, with: UUID().uuidString)
            case "wrong-outcome": body = ownedAck(owner, outcome: "started", status: released)
            case "missing-proof": body = ownedAck(owner, outcome: "released", status: #"{"bulbExposureActive":false}"#)
            case "numeric-proof": body = ownedAck(owner, outcome: "released", status: #"{"bulbExposureActive":0,"shutterReleaseUnconfirmed":false}"#)
            case "oversized": body += String(repeating: " ", count: 65_537)
            default: status = 206
            }
            await peer.enqueueJSON(method: "POST", path: ownedStopPath, status: status, body: body)
            await assertThrows { _ = try await client.stopOwnedBulb(owner) }
            await assertPending(client)
        }
    }

    func testOwnedCloseRetiresSessionAfterFailedStopAndFencesRecycledID() async throws {
        let peer = BridgeShutterTransport()
        let client = try await initializedClient(peer)
        let owner = try await client.prepareOwnedBulb()
        await peer.enqueueHold(method: "POST", path: startPath, key: "retired-start")
        let start = Task { try await client.startOwnedBulb(owner) }
        try await waitForHold("retired-start", on: peer)
        await peer.enqueueFailure(method: "POST", path: ownedStopPath)
        await peer.enqueueJSON(method: "DELETE", path: "/v1/session/session-a", status: 204, body: "")
        let warning = await client.closeWithShutterReleaseState()
        XCTAssertTrue(warning.releaseUnconfirmed)
        XCTAssertFalse(warning.releaseRequired)
        await enqueueInitialization(peer, id: "session-a")
        try await client.initialize()
        let current = try await client.prepareOwnedBulb()
        await peer.resume("retired-start", body: ownedAck(owner, outcome: "started", status: active))
        await assertThrows { _ = try await start.value }
        await assertThrows { _ = try await client.stopOwnedBulb(owner) }
        await assertPending(client)
        await peer.enqueueJSON(method: "POST", path: ownedStopPath, body: ownedAck(current, outcome: "cancelled-before-start"))
        try await client.retryShutterRelease()
        await assertTerminalWithoutObservation(client)
        let writes = await peer.requests().filter { $0.method != "GET" }
        XCTAssertEqual(writes.map(\.path), ["/v1/session", startPath, ownedStopPath, "/v1/session/session-a", "/v1/session", ownedStopPath])
    }

    func testReceiptOnlyOwnedClosePreservesUnknownActivityAndCancelledCallerCleanup() async throws {
        let peer = BridgeShutterTransport()
        let client = try await initializedClient(peer)
        let owner = try await client.prepareOwnedBulb()
        await peer.enqueueJSON(method: "POST", path: ownedStopPath, body: ownedAck(owner, outcome: "cancelled-before-start"))
        await peer.enqueueJSON(method: "DELETE", path: "/v1/session/session-a", status: 204, body: "")
        let close = Task {
            withUnsafeCurrentTask { $0?.cancel() }
            return await client.closeWithShutterReleaseState()
        }
        let outcome = await close.value
        XCTAssertEqual(outcome, CameraShutterReleaseState(bulbExposureActive: nil))
        let cancelled = await peer.cancelledSendCount()
        XCTAssertEqual(cancelled, 0)
        let requests = await peer.requests()
        XCTAssertEqual(requests.map(\.path), ["/health", "/v1/session", ownedStopPath, "/v1/session/session-a"])
        await assertTerminalWithoutObservation(client)
        let repeated = await client.closeWithShutterReleaseState()
        XCTAssertEqual(repeated, outcome)
        let after = await peer.requests().count
        XCTAssertEqual(after, requests.count)
    }

    func testPreStopActiveStatusCannotRelockAfterOwnedReleaseAcknowledgement() async throws {
        let peer = BridgeShutterTransport()
        let client = try await initializedClient(peer)
        let owner = try await client.prepareOwnedBulb()
        await peer.enqueueJSON(method: "POST", path: startPath, body: ownedAck(owner, outcome: "started", status: active))
        _ = try await client.startOwnedBulb(owner)
        await peer.enqueueHold(path: statusPath, key: "old-owned-status")
        let status = Task { try await client.status() }
        try await waitForHold("old-owned-status", on: peer)
        await peer.enqueueJSON(method: "POST", path: ownedStopPath, body: ownedAck(owner, outcome: "released", status: released))
        _ = try await client.stopOwnedBulb(owner)
        await peer.resume("old-owned-status", body: active)
        let stale = try await status.value
        XCTAssertEqual(stale.bulbExposureActive, false)
        XCTAssertEqual(stale.shutterReleaseUnconfirmed, false)
        await assertIdle(client)
    }

    func testFailedStopBeforeStartConsumesAdmissionAndOnlySameOwnerRetryRemains() async throws {
        let peer = BridgeShutterTransport()
        let client = try await initializedClient(peer)
        let owner = try await client.prepareOwnedBulb()
        await peer.enqueueFailure(method: "POST", path: ownedStopPath)
        await assertThrows { _ = try await client.stopOwnedBulb(owner) }
        await assertThrows { _ = try await client.startOwnedBulb(owner) }
        await assertThrows { _ = try await client.prepareOwnedBulb() }
        await assertPending(client)
        await peer.enqueueJSON(method: "POST", path: ownedStopPath, body: ownedAck(owner, outcome: "cancelled-before-start"))
        try await client.retryShutterRelease()
        await assertTerminalWithoutObservation(client)
        let requests = await peer.requests()
        XCTAssertEqual(requests.map(\.path), ["/health", "/v1/session", ownedStopPath, ownedStopPath])
        for request in requests.filter({ $0.path == ownedStopPath }) {
            let body = try XCTUnwrap(request.body)
            let payload = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: String])
            XCTAssertEqual(payload["ownerId"], owner.id.uuidString)
        }
    }

    func testGETBegunDuringOwnedStopCannotRecreateObligationAfterTerminalReceipt() async throws {
        let proofs: [String?] = [nil, released]
        for proof in proofs {
            let peer = BridgeShutterTransport()
            let client = try await initializedClient(peer)
            let owner = try await client.prepareOwnedBulb()
            await peer.enqueueHold(method: "POST", path: ownedStopPath, key: "terminal-stop")
            let stop = Task { try await client.stopOwnedBulb(owner) }
            try await waitForHold("terminal-stop", on: peer)
            await peer.enqueueHold(path: statusPath, key: "during-stop-status")
            let status = Task { try await client.status() }
            try await waitForHold("during-stop-status", on: peer)
            await peer.resume("terminal-stop", body: ownedAck(owner, outcome: "released", status: proof))
            _ = try await stop.value
            if proof == nil { await assertTerminalWithoutObservation(client) }
            else { await assertIdle(client) }
            await peer.resume("during-stop-status", body: unknown)
            let late = try await status.value
            XCTAssertEqual(late.shutterReleaseUnconfirmed, false)
            XCTAssertEqual(late.bulbExposureActive, proof == nil ? nil : false)
            if proof == nil { await assertTerminalWithoutObservation(client) }
            else { await assertIdle(client) }
            let beforeRetry = await peer.requests().count
            try await client.retryShutterRelease()
            let afterRetry = await peer.requests().count
            XCTAssertEqual(afterRetry, beforeRetry)
        }
    }

    func testReceiptOnlyTerminalHasNoDutyWithoutInventingInactiveObservation() async throws {
        for outcome in ["released", "cancelled-before-start"] {
            let peer = BridgeShutterTransport()
            let client = try await initializedClient(peer)
            let owner = try await client.prepareOwnedBulb()
            await peer.enqueueJSON(method: "POST", path: ownedStopPath, body: ownedAck(owner, outcome: outcome))
            let receipt = try await client.stopOwnedBulb(owner)
            XCTAssertNil(receipt)
            await assertTerminalWithoutObservation(client)
            let fresh = await client.ownedBulbReleaseHasFreshStatus
            XCTAssertFalse(fresh)
            let before = await peer.requests().count
            try await client.retryShutterRelease()
            let after = await peer.requests().count
            XCTAssertEqual(after, before, "Receipt-only terminal does not invent a retry obligation")
            _ = try await client.prepareOwnedBulb()
            await assertPending(client)
        }
    }

    func testFreshDualFalseGETAfterReceiptOnlyTerminalRestoresKnownInactiveState() async throws {
        let peer = BridgeShutterTransport()
        let client = try await initializedClient(peer)
        let owner = try await client.prepareOwnedBulb()
        await peer.enqueueJSON(method: "POST", path: ownedStopPath, body: ownedAck(owner, outcome: "released"))
        _ = try await client.stopOwnedBulb(owner)
        await assertTerminalWithoutObservation(client)
        await peer.enqueueJSON(path: statusPath, body: released)
        let fresh = try await client.status()
        XCTAssertEqual(fresh.bulbExposureActive, false)
        XCTAssertEqual(fresh.shutterReleaseUnconfirmed, false)
        await assertIdle(client)
    }

    private func assertTerminalWithoutObservation(
        _ client: DesktopBridgeClient, file: StaticString = #filePath, line: UInt = #line
    ) async {
        let state = await client.shutterReleaseState()
        XCTAssertFalse(state.releaseRequired, file: file, line: line)
        XCTAssertFalse(state.releaseUnconfirmed, file: file, line: line)
        XCTAssertNil(state.bulbExposureActive, file: file, line: line)
    }

    // Authored native regression: deliberately exercises the real 15-second
    // production deadline rather than injecting a shorter test-only timeout.
    func testRetirementAfterReceiptOnlyOwnerUsesProductionDeadlineWithoutJoiningTransport() async throws {
        let peer = BridgeShutterTransport()
        let client = try await initializedClient(peer)
        let owner = try await client.prepareOwnedBulb()
        await peer.enqueueJSON(method: "POST", path: ownedStopPath,
                               body: ownedAck(owner, outcome: "cancelled-before-start"))
        _ = try await client.stopOwnedBulb(owner)
        await assertTerminalWithoutObservation(client)
        await peer.enqueueHold(method: "DELETE", path: "/v1/session/session-a", key: "retired-owner-delete")
        let finished = expectation(description: "retirement returns at production total deadline")
        let close = Task {
            defer { finished.fulfill() }
            return await client.closeWithShutterReleaseState()
        }
        try await waitForHold("retired-owner-delete", on: peer)
        await fulfillment(of: [finished], timeout: 20)
        let state = await close.value
        XCTAssertEqual(state, CameraShutterReleaseState(bulbExposureActive: nil))
        let unconfirmed = await client.connectionRetirementUnconfirmed
        XCTAssertTrue(unconfirmed)
        let stillHeld = await peer.isHeld("retired-owner-delete")
        XCTAssertTrue(stillHeld, "Cancellation-ignoring producer must not hold Close")
        await peer.resume("retired-owner-delete", status: 204, body: "")
        let requests = await peer.requests()
        XCTAssertEqual(requests.filter { $0.path == ownedStopPath }.count, 1)
        XCTAssertEqual(requests.filter { $0.method == "DELETE" }.count, 1)
        XCTAssertEqual(requests.last?.timeoutInterval, 15)
    }

    func testBoundedExchangeExpiresWithoutWaitingForIgnoringCancellationTransport() async throws {
        let peer = BridgeShutterTransport()
        await peer.enqueueHold(path: "/bounded", key: "bounded")
        let request = URLRequest(url: URL(string: "http://127.0.0.1/bounded")!)
        let finished = expectation(description: "absolute deadline completes exchange")
        let exchange = Task {
            defer { finished.fulfill() }
            do {
                _ = try await BridgeBoundedExchange.run(seconds: 0.1) { try await peer.send(request) }
                XCTFail("Expected deadline")
            } catch { XCTAssertEqual((error as? URLError)?.code, .timedOut) }
        }
        try await waitForHold("bounded", on: peer)
        await fulfillment(of: [finished], timeout: 2)
        let held = await peer.isHeld("bounded")
        XCTAssertTrue(held)
        await peer.resume("bounded", body: "{}")
        await exchange.value
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
    func ownedRecoveryStop(_ owner: DesktopBridgeBulbOwner, onEntry: @Sendable () -> Void) async throws -> CameraStatus? {
        onEntry()
        return try await stopOwnedBulb(owner)
    }

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

    func enqueueRaw(method: String, path: String, body: Data) {
        stubs.append(Stub(method: method, path: path,
            outcome: .response(CameraHTTPResponse(statusCode: 200, body: body))))
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
