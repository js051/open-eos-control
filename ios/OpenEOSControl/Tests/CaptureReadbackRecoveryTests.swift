import Foundation
import OpenEOSCore
import XCTest

@testable import OpenEOSControl

/// Simulator capture has a required status GET. Canon's optional GETs do not
/// reproduce this failure boundary, so these tests use the real simulator client.
@MainActor
final class CaptureReadbackRecoveryTests: XCTestCase {
    func testAcknowledgedCaptureKeepsKnownIDsAndRetriesOnlyReadsAfterStatusFailure() async throws {
        let peer = CaptureReadbackPeer()
        let state = makeState([peer])
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        try await connect(state)
        await peer.plan("media", .items([.a, .b, .libraryOnly]))
        state.startMediaLibraryLoad()
        try await waitUntil { state.mediaLibraryLoadStatus == .complete }
        await peer.plan("status", .lost)
        for items in [[ReadbackItem.b, .a], [.libraryOnly], [.b], [.a, .b]] {
            await peer.plan("media", .items(items))
        }
        let before = await peer.count("media")
        let observedStatus = state.status
        await state.captureStill()
        await state.latestMediaTask?.value
        XCTAssertEqual(state.status, observedStatus, "The command ACK must not replace the observed camera snapshot")
        XCTAssertEqual(state.captureReviewState, .notReady)
        XCTAssertEqual(state.latestMediaItem?.id, "A")
        XCTAssertTrue(state.latestMediaIsPrevious)
        XCTAssertTrue(state.canRetryCaptureMediaReview)
        XCTAssertFalse(state.shutterFlash)
        let warning = try XCTUnwrap(state.lastError)
        let after = await peer.count("media")
        XCTAssertEqual(after - before, 4, "Preserve the existing bounded review without a pre-capture scan")

        await peer.plan("media", .items([.a, .b, .libraryOnly, .new]), gate: "retry")
        state.retryCaptureMediaReview()
        let retry = try XCTUnwrap(state.latestMediaTask)
        try await waitForGate("retry", peer: peer)
        state.retryCaptureMediaReview()
        let heldReads = await peer.count("media")
        XCTAssertEqual(heldReads - after, 1, "Repeated retry must share one read-only owner")
        await peer.release("retry")
        await retry.value
        XCTAssertEqual(state.captureReviewState, .available)
        XCTAssertEqual(state.latestMediaItem?.id, "NEW", "Future-dated baseline IDs cannot hide the older-dated new item")
        XCTAssertEqual(state.lastError, warning)
        XCTAssertFalse(state.shutterFlash)
        let commands = await peer.captureCount()
        XCTAssertEqual(commands, 1)
        let requests = await peer.requestsAfterCapture()
        XCTAssertTrue(requests.allSatisfy { $0.hasPrefix("GET ") }, "Recovery must never reshoot or mutate the camera")
    }

    func testUnreadAndEmptyListsRemainRetryableForAnAcknowledgedCaptureWithEmptyBaseline() async throws {
        let peer = CaptureReadbackPeer()
        await peer.plan("media", .items([]))
        let state = makeState([peer])
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        try await connect(state)
        XCTAssertNil(state.latestMediaItem)
        await peer.plan("status", .malformed)
        for _ in 0..<4 { await peer.plan("media", .lost) }
        await state.captureStill()
        await state.latestMediaTask?.value
        XCTAssertEqual(state.captureReviewState, .readFailed)
        XCTAssertTrue(state.canRetryCaptureMediaReview)
        let warning = try XCTUnwrap(state.lastError)
        for _ in 0..<4 { await peer.plan("media", .items([])) }
        state.retryCaptureMediaReview()
        await state.latestMediaTask?.value
        XCTAssertEqual(state.captureReviewState, .notReady)
        XCTAssertTrue(state.canRetryCaptureMediaReview)
        XCTAssertNil(state.latestMediaItem)
        XCTAssertEqual(state.lastError, warning)
        await peer.plan("media", .items([.new]))
        state.retryCaptureMediaReview()
        await state.latestMediaTask?.value
        XCTAssertEqual(state.latestMediaItem?.id, "NEW")
        XCTAssertEqual(state.captureReviewState, .available)
        let commands = await peer.captureCount()
        XCTAssertEqual(commands, 1)
    }

    func testSuccessfulCaptureStillFlashesAndPublishesNewMedia() async throws {
        let peer = CaptureReadbackPeer()
        let state = makeState([peer])
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        try await connect(state)
        await peer.plan("media", .items([.a, .b, .new]))
        await state.captureStill()
        XCTAssertTrue(state.shutterFlash)
        await state.latestMediaTask?.value
        XCTAssertEqual(state.latestMediaItem?.id, "NEW")
        XCTAssertEqual(state.captureReviewState, .available)
        XCTAssertNil(state.lastError)
        let commands = await peer.captureCount()
        XCTAssertEqual(commands, 1)
    }

    func testRejectedLostAndCancelledCaptureResponsesDoNotCreateAcknowledgedReview() async throws {
        for outcome in [CaptureReadbackPeer.Outcome.rejected, .lost, .cancelled, .urlCancelled] {
            let peer = CaptureReadbackPeer()
            let state = makeState([peer])
            defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
            try await connect(state)
            let before = await peer.count("media")
            await peer.plan("capture", outcome)
            await state.captureStill()
            XCTAssertEqual(state.captureReviewState, .idle)
            XCTAssertFalse(state.canRetryCaptureMediaReview)
            XCTAssertFalse(state.latestMediaIsPrevious)
            XCTAssertFalse(state.shutterFlash)
            XCTAssertNotNil(state.lastError)
            XCTAssertEqual(state.latestMediaItem?.id, "A")
            let after = await peer.count("media")
            let commands = await peer.captureCount()
            XCTAssertEqual(after, before)
            XCTAssertEqual(commands, 1)
            XCTAssertNil(state.latestMediaTask)
        }
    }

    func testStatusReadCancellationIsNotPromotedToAcknowledgedFailureReview() async throws {
        for outcome in [CaptureReadbackPeer.Outcome.cancelled, .urlCancelled, .lost, .rejected] {
            let peer = CaptureReadbackPeer()
            let state = makeState([peer])
            defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
            try await connect(state)
            let before = await peer.count("media")
            await peer.plan("status", outcome, gate: "cancelled-status")
            let capture = Task { await state.captureStill() }
            try await waitForGate("cancelled-status", peer: peer)
            capture.cancel()
            await peer.release("cancelled-status")
            await capture.value
            XCTAssertEqual(state.captureReviewState, .idle)
            XCTAssertFalse(state.canRetryCaptureMediaReview)
            XCTAssertFalse(state.shutterFlash)
            XCTAssertNotNil(state.lastError)
            let after = await peer.count("media")
            let commands = await peer.captureCount()
            XCTAssertEqual(after, before)
            XCTAssertEqual(commands, 1)
        }
    }

    func testRetiredStatusSuccessOrFailureCannotTakeReplacementCaptureReview() async throws {
        for outcome in [CaptureReadbackPeer.Outcome.normal, .lost] {
            let oldPeer = CaptureReadbackPeer(owner: "old")
            let newPeer = CaptureReadbackPeer(owner: "new")
            let state = makeState([oldPeer, newPeer])
            defer { state.requestDisconnect(); Task { await oldPeer.releaseAll(); await newPeer.releaseAll() } }
            try await connect(state)
            await oldPeer.plan("status", outcome, gate: "retired-status")
            let retired = Task { await state.captureStill() }
            try await waitForGate("retired-status", peer: oldPeer)
            await state.disconnect()
            try await connect(state)
            await newPeer.plan("status", .lost)
            await newPeer.plan("media", .items([.a, .b, .new]), gate: "current-review")
            await state.captureStill()
            let review = try XCTUnwrap(state.latestMediaTask)
            try await waitForGate("current-review", peer: newPeer)
            let snapshot = state.snapshot
            let warning = state.lastError
            let reads = await newPeer.count("media")
            await oldPeer.release("retired-status")
            await retired.value
            XCTAssertEqual(state.snapshot, snapshot)
            XCTAssertEqual(state.lastError, warning)
            XCTAssertEqual(state.captureReviewState, .searching)
            XCTAssertTrue(state.latestMediaIsPrevious)
            XCTAssertFalse(state.shutterFlash)
            XCTAssertFalse(review.isCancelled)
            let readsAfter = await newPeer.count("media")
            XCTAssertEqual(readsAfter, reads)
            await newPeer.release("current-review")
            await review.value
            XCTAssertEqual(state.captureReviewState, .available)
            XCTAssertEqual(state.latestMediaItem?.id, "NEW")
            let oldCommands = await oldPeer.captureCount()
            let newCommands = await newPeer.captureCount()
            XCTAssertEqual(oldCommands, 1)
            XCTAssertEqual(newCommands, 1)
        }
    }

    private func makeState(_ peers: [CaptureReadbackPeer]) -> CameraAppState {
        let defaults = UserDefaults(suiteName: "CaptureReadbackRecoveryTests.\(UUID().uuidString)")!
        var index = 0
        let state = CameraAppState(defaults: defaults, sessionFactory: {
            let peer = peers[min(index, peers.count - 1)]
            index += 1
            return .ccapi(try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: .simulator, transport: peer))
        })
        state.autoRefresh = false
        return state
    }
    private func connect(_ state: CameraAppState) async throws {
        await state.connect()
        XCTAssertTrue(state.connected)
        _ = try XCTUnwrap(state.snapshot)
        await state.latestMediaTask?.value
    }
    private func waitUntil(_ condition: @MainActor () -> Bool) async throws {
        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: .seconds(5))
        repeat {
            if condition() { return }
            await Task.yield()
        } while clock.now < deadline
        XCTFail("Expected capture review state")
        throw URLError(.timedOut)
    }
    private func waitForGate(_ gate: String, peer: CaptureReadbackPeer) async throws {
        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: .seconds(5))
        repeat {
            if await peer.waiting(gate) { return }
            await Task.yield()
        } while clock.now < deadline
        XCTFail("Expected capture request gate \(gate)")
        throw URLError(.timedOut)
    }
}

private struct ReadbackItem: Sendable {
    let id: String
    let date: String
    static let a = Self(id: "A", date: "2035-01-01T00:00:00Z")
    static let b = Self(id: "B", date: "2034-01-01T00:00:00Z")
    static let libraryOnly = Self(id: "LIBRARY", date: "2040-01-01T00:00:00Z")
    static let new = Self(id: "NEW", date: "2020-01-01T00:00:00Z")
    var json: [String: Any] { ["id": id, "name": "\(id).JPG", "kind": "image", "capture_time": date] }
}

private actor CaptureReadbackPeer: CameraHTTPTransport {
    enum Outcome: Sendable { case normal, lost, rejected, cancelled, urlCancelled, malformed, items([ReadbackItem]) }
    private struct Plan { let outcome: Outcome; let gate: String? }
    private let owner: String
    private var plans: [String: [Plan]] = [:]
    private var gates: [String: CheckedContinuation<Void, Never>] = [:]
    private var requests: [String] = []
    private var counts: [String: Int] = [:]
    private var captureEnd = 0
    private var drained = false

    init(owner: String = "current") { self.owner = owner }
    func plan(_ route: String, _ outcome: Outcome, gate: String? = nil) {
        plans[route, default: []].append(Plan(outcome: outcome, gate: gate))
    }
    func count(_ route: String) -> Int { counts[route, default: 0] }
    func captureCount() -> Int { count("capture") }
    func requestsAfterCapture() -> [String] { Array(requests.dropFirst(captureEnd)) }
    func waiting(_ gate: String) -> Bool { gates[gate] != nil }
    func release(_ gate: String) { gates.removeValue(forKey: gate)?.resume() }
    func releaseAll() {
        drained = true
        let pending = gates
        gates.removeAll()
        for gate in pending.values { gate.resume() }
    }
    func send(_ request: URLRequest) async throws -> CameraHTTPResponse {
        let path = request.url!.path
        requests.append("\(request.httpMethod ?? "GET") \(path)")
        let route = path == "/ccapi/capture/still" ? "capture" : request.url!.lastPathComponent
        counts[route, default: 0] += 1
        if route == "capture" { captureEnd = requests.count }
        let plan = plans[route]?.isEmpty == false ? plans[route]!.removeFirst() : Plan(outcome: .normal, gate: nil)
        if let gate = plan.gate, !drained { await withCheckedContinuation { gates[gate] = $0 } }
        switch plan.outcome {
        case .lost: throw URLError(.networkConnectionLost)
        case .cancelled: throw CancellationError()
        case .urlCancelled: throw URLError(.cancelled)
        case .rejected: return CameraHTTPResponse(statusCode: 409, body: Data("{}".utf8))
        case .malformed: return CameraHTTPResponse(statusCode: 200, body: Data("not-json".utf8))
        default: break
        }
        let body: [String: Any]
        switch route {
        case "info": body = ["connected": true, "model": "Synthetic \(owner) camera", "serial": "SYNTHETIC", "api": "fixture"]
        case "status": body = ["connected": true, "mode": "Manual", "recording": false, "battery": [:], "media": [:], "exposure": [:]]
        case "capabilities": body = ["iso": ["100"], "shutter": ["1/100"], "aperture": ["4.0"]]
        case "media":
            if case let .items(items) = plan.outcome { body = ["items": items.map(\.json)] }
            else { body = ["items": [ReadbackItem.a, .b].map(\.json)] }
        case "thumbnail": return CameraHTTPResponse(statusCode: 200, headers: ["content-type": "image/jpeg"], body: Data([0xFF, 0xD8, 0xFF, 0xD9]))
        case "events": throw CancellationError()
        case "capture", "stop": body = [:]
        default: throw URLError(.unsupportedURL)
        }
        return CameraHTTPResponse(statusCode: 200, body: try JSONSerialization.data(withJSONObject: body))
    }
    func download(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse { throw URLError(.unsupportedURL) }
}
