import Foundation
import XCTest
@testable import OpenEOSCore

#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Controlled actor reentrancy through production CCAPI methods. These source
/// tests are not evidence of the UI incident or a physical-camera reproduction.
final class CCAPISettingsSnapshotOwnershipTests: XCTestCase {
    func testPausedStatusRefreshKeepsCompleteMovieModeRouteAndAllowsExplicitPOST() async throws {
        let peer = SettingsSnapshotPeer()
        let client = try makeClient(peer)
        let initial = try await client.capabilities()
        XCTAssertEqual(initial.setting("moviemode")?.value, "off")
        XCTAssertEqual(initial.liveView.currentMagnification, .x5)
        await peer.holdNextRead(.aggregate, gate: "refresh")
        let refresh = Task { try await client.status() }
        defer { refresh.cancel(); Task { await peer.releaseAll() } }
        try await wait { await peer.waiting("refresh") }

        let whileRefreshing = try await client.capabilities()
        XCTAssertEqual(whileRefreshing.setting("moviemode")?.values, ["off", "on"])
        XCTAssertEqual(whileRefreshing.liveView.currentMagnification, .x5)
        XCTAssertTrue(whileRefreshing.matrix.supports(.liveViewMagnification))
        let mutation = Task { try await client.setSetting(key: "moviemode", value: "on") }
        defer { mutation.cancel() }
        _ = try await completed(mutation)
        let writes = await peer.movieWrites()
        XCTAssertEqual(writes.count, 1, "A paused refresh must not cause invalidSetting before POST")
        let body = try XCTUnwrap(writes.first?.body)
        let action = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: String])
        XCTAssertEqual(action, ["action": "on"])
        await peer.release("refresh")
        _ = try await completed(refresh)
        let final = try await client.capabilities()
        XCTAssertEqual(final.setting("moviemode")?.value, "on")
        XCTAssertEqual(final.liveView.currentMagnification, .x5)
    }

    func testOlderSettingsRefreshCannotOverwriteNewerCompleteReadSnapshot() async throws {
        let peer = SettingsSnapshotPeer()
        let client = try makeClient(peer)
        _ = try await client.capabilities()
        await peer.holdNextRead(.movieMode, gate: "old-movie-value")
        let old = Task { try await client.status() }
        defer { old.cancel(); Task { await peer.releaseAll() } }
        try await wait { await peer.waiting("old-movie-value") }
        // The held response has already captured "off". A newer refresh sees on.
        await peer.setMovieStatus("on")
        let newer = Task { try await client.status() }
        defer { newer.cancel() }
        _ = try await completed(newer)
        let beforeOld = try await client.capabilities()
        XCTAssertEqual(beforeOld.setting("moviemode")?.value, "on")
        await peer.release("old-movie-value")
        _ = try await completed(old)
        let afterOld = try await client.capabilities()
        XCTAssertEqual(afterOld.setting("moviemode")?.value, "on")
        XCTAssertEqual(afterOld.setting("iso")?.values, ["100", "200"])
        let writes = await peer.movieWrites()
        XCTAssertTrue(writes.isEmpty, "Read ownership must never synthesize a setting write")
    }

    func testCancelledSettingsRefreshKeepsPreviousCompleteRoutesValuesAndFeatures() async throws {
        for endpoint in [SettingsSnapshotPeer.ReadEndpoint.aggregate, .movieMode] {
            let peer = SettingsSnapshotPeer()
            let client = try makeClient(peer)
            let initial = try await client.capabilities()
            await peer.holdNextRead(endpoint, gate: "cancelled-refresh")
            let refresh = Task { try await client.status() }
            defer { refresh.cancel(); Task { await peer.releaseAll() } }
            try await wait { await peer.waiting("cancelled-refresh") }
            refresh.cancel()
            await peer.release("cancelled-refresh")
            do { _ = try await completed(refresh); XCTFail("Cancelled refresh must not publish") }
            catch { XCTAssertTrue(error is CancellationError) }
            let retained = try await client.capabilities()
            XCTAssertEqual(retained.setting("moviemode"), initial.setting("moviemode"))
            XCTAssertEqual(retained.setting("iso"), initial.setting("iso"))
            XCTAssertEqual(retained.liveView.magnifications, initial.liveView.magnifications)
            XCTAssertEqual(retained.liveView.currentMagnification, initial.liveView.currentMagnification)
            let mutation = Task { try await client.setSetting(key: "moviemode", value: "on") }
            defer { mutation.cancel() }
            _ = try await completed(mutation)
            let writes = await peer.movieWrites()
            XCTAssertEqual(writes.count, 1)
        }
    }

    func testCompletedRefreshRemovesInvalidMovieModeInsteadOfKeepingStaleWritePermission() async throws {
        let peer = SettingsSnapshotPeer()
        let client = try makeClient(peer)
        let initial = try await client.capabilities()
        XCTAssertNotNil(initial.setting("moviemode"))
        await peer.setMovieStatus("not-an-advertised-mode")
        _ = try await client.status()
        let refreshed = try await client.capabilities()
        XCTAssertNil(refreshed.setting("moviemode"))
        XCTAssertNotNil(refreshed.setting("iso"))
        do {
            _ = try await client.setSetting(key: "moviemode", value: "on")
            XCTFail("A fully refreshed invalid setting must fail closed")
        } catch {
            XCTAssertEqual(error as? CCAPIError, .invalidSetting(key: "moviemode", value: "on"))
        }
        let writes = await peer.movieWrites()
        XCTAssertTrue(writes.isEmpty)
    }

    func testFirstUncachedReadCompletesWithFreshSnapshotWhileNewerReadIsStillPending() async throws {
        let peer = SettingsSnapshotPeer()
        let client = try makeClient(peer)
        try await client.initialize()
        await peer.holdNextRead(.aggregate, gate: "first")
        let first = Task { try await client.capabilities() }
        defer { first.cancel(); Task { await peer.releaseAll() } }
        try await wait { await peer.waiting("first") }
        await peer.holdNextRead(.aggregate, gate: "newer")
        let newer = Task { try await client.status() }
        defer { newer.cancel() }
        try await wait { await peer.waiting("newer") }
        await peer.release("first")
        let capabilities = try await completed(first)
        XCTAssertNotNil(capabilities.setting("moviemode"), "No nil-cache fallback before the newer read completes")
        XCTAssertEqual(capabilities.setting("iso")?.value, "100")
        let stillWaiting = await peer.waiting("newer")
        XCTAssertTrue(stillWaiting)
        await peer.release("newer")
        _ = try await completed(newer)
    }

    func testForcedAbilityValidationUsesFreshResponseWhenNewerPassiveRefreshIsPending() async throws {
        for nowAllowed in [false, true] {
            let peer = SettingsSnapshotPeer()
            let client = try makeClient(peer)
            await peer.setCardAbility(nowAllowed ? ["none", "card1"] : ["card1", "card2"])
            _ = try await client.capabilities()
            await peer.setCardAbility(nowAllowed ? ["card1", "card2"] : ["none", "card1"])
            await peer.holdNextRead(.cardSelection, gate: "fresh-ability")
            let mutation = Task { try await client.setSetting(key: "cardselectionstillimage", value: "card2") }
            defer { mutation.cancel(); Task { await peer.releaseAll() } }
            try await wait { await peer.waiting("fresh-ability") }
            await peer.holdNextRead(.aggregate, gate: "passive")
            let passive = Task { try await client.status() }
            defer { passive.cancel() }
            try await wait { await peer.waiting("passive") }
            await peer.release("fresh-ability")
            if nowAllowed { _ = try await completed(mutation) }
            else {
                do { _ = try await completed(mutation); XCTFail("Old cached ability cannot authorize the write") }
                catch { XCTAssertEqual(error as? CCAPIError, .invalidSetting(key: "cardselectionstillimage", value: "card2")) }
            }
            let writes = await peer.cardWrites()
            XCTAssertEqual(writes.count, nowAllowed ? 1 : 0)
            await peer.release("passive")
            _ = try await completed(passive)
        }
    }

    func testReadRetiredByMutationCannotReturnOldOrNilCacheWhileCommandReadbackIsPending() async throws {
        let peer = SettingsSnapshotPeer()
        let client = try makeClient(peer)
        _ = try await client.capabilities()
        await peer.holdNextRead(.movieMode, gate: "old-value")
        let old = Task { try await client.status() }
        defer { old.cancel(); Task { await peer.releaseAll() } }
        try await wait { await peer.waiting("old-value") }
        await peer.holdNextRead(.aggregate, gate: "readback")
        let mutation = Task { try await client.setSetting(key: "moviemode", value: "on") }
        defer { mutation.cancel() }
        try await wait { await peer.waiting("readback") }
        let sent = await peer.movieWrites()
        XCTAssertEqual(sent.count, 1)
        await peer.release("old-value")
        do {
            _ = try await completed(old)
            XCTFail("Retired read cannot pretend old or missing cache is a fresh response")
        } catch {
            guard let failure = error as? CCAPIError, case .invalidResponse = failure else {
                XCTFail("Expected a closed settings-read gate, got \(error)")
                throw error
            }
        }
        await peer.release("readback")
        _ = try await completed(mutation)
        let final = try await client.capabilities()
        XCTAssertEqual(final.setting("moviemode")?.value, "on")
    }

    private func makeClient(_ peer: SettingsSnapshotPeer) throws -> CCAPIClient {
        try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: .camera, transport: peer)
    }

    private func wait(_ condition: () async -> Bool) async throws {
        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: .seconds(3))
        while clock.now < deadline {
            if await condition() { return }
            try await Task.sleep(nanoseconds: 1_000_000)
        }
        XCTFail("Controlled settings response did not reach its expected state")
        throw SettingsSnapshotTestFailure.deadline
    }

    /// An observer bounds failure without a task group waiting indefinitely for
    /// a cancellation-ignoring fixture. Test defers permanently drain all gates.
    private func completed<Value: Sendable>(_ task: Task<Value, Error>) async throws -> Value {
        let result = SettingsSnapshotResult<Value>()
        Task { await result.finish(await task.result) }
        do { try await wait { await result.value != nil } }
        catch { task.cancel(); throw error }
        let completedResult = await result.value
        return try XCTUnwrap(completedResult).get()
    }
}

private enum SettingsSnapshotTestFailure: Error { case deadline, unexpectedDownload }

private actor SettingsSnapshotResult<Value: Sendable> {
    private(set) var value: Result<Value, Error>?
    func finish(_ result: Result<Value, Error>) { value = result }
}

private actor SettingsSnapshotPeer: CameraHTTPTransport {
    enum ReadEndpoint: Sendable, Equatable { case aggregate, movieMode, cardSelection }
    private var movieStatus = "off"
    private var cardAbility = ["card1", "card2"]
    private var currentCard = "card1"
    private var nextGates: [String: String] = [:]
    private var gates: [String: CheckedContinuation<Void, Never>] = [:]
    private var draining = false
    private var recorded: [RecordedRequest] = []
    private let prefix = "/ccapi/ver100"

    func setMovieStatus(_ value: String) { movieStatus = value }
    func setCardAbility(_ value: [String]) { cardAbility = value }
    func holdNextRead(_ endpoint: ReadEndpoint, gate: String) {
        let suffix: String
        switch endpoint {
        case .aggregate: suffix = "/shooting/settings"
        case .movieMode: suffix = "/shooting/control/moviemode"
        case .cardSelection: suffix = "/functions/cardselection/stillimage"
        }
        nextGates[prefix + suffix] = gate
    }
    func waiting(_ gate: String) -> Bool { gates[gate] != nil }
    func release(_ gate: String) { gates.removeValue(forKey: gate)?.resume() }
    func releaseAll() {
        draining = true
        nextGates.removeAll()
        let held = gates
        gates.removeAll()
        for gate in held.values { gate.resume() }
    }
    func cardWrites() -> [RecordedRequest] {
        recorded.filter { $0.method == "PUT" && $0.path == prefix + "/functions/cardselection/stillimage" }
    }
    func movieWrites() -> [RecordedRequest] {
        recorded.filter { $0.method == "POST" && $0.path == prefix + "/shooting/control/moviemode" }
    }

    func download(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse {
        throw SettingsSnapshotTestFailure.unexpectedDownload
    }

    func send(_ request: URLRequest) async throws -> CameraHTTPResponse {
        let path = request.url!.path
        let method = request.httpMethod ?? "GET"
        recorded.append(RecordedRequest(method: method, path: path, headers: request.allHTTPHeaderFields ?? [:],
            body: request.httpBody, timeoutInterval: request.timeoutInterval))
        let body: Data
        if path == "/ccapi" {
            body = Data(#"{"ver100":[{"path":"/devicestatus/batterylist","get":true},{"path":"/devicestatus/storage","get":true},{"path":"/shooting/settings","get":true},{"path":"/shooting/settings/iso","get":true,"put":true},{"path":"/shooting/settings/lvzoom","get":true,"put":true},{"path":"/shooting/control/moviemode","get":true,"post":true},{"path":"/functions/cardselection/stillimage","get":true,"put":true}]}"#.utf8)
        } else if path == prefix + "/devicestatus/batterylist" {
            body = Data(#"{"batterylist":[{"level":90}]}"#.utf8)
        } else if path == prefix + "/devicestatus/storage" {
            body = Data(#"{"storagelist":[{"name":"card1","spacesize":1024}]}"#.utf8)
        } else if path == prefix + "/shooting/settings" {
            body = Data(#"{"iso":{"value":"100","ability":["100","200"]}}"#.utf8)
        } else if path == prefix + "/shooting/settings/lvzoom" {
            body = Data(#"{"value":"5","ability":["1","5","10"]}"#.utf8)
        } else if path == prefix + "/functions/cardselection/stillimage" {
            if method == "PUT" {
                let payload = try JSONSerialization.jsonObject(with: request.httpBody ?? Data()) as? [String: String]
                currentCard = payload?["value"] ?? "invalid"
                return CameraHTTPResponse(statusCode: 200, body: Data("{}".utf8))
            }
            body = try JSONSerialization.data(withJSONObject: ["value": currentCard, "ability": cardAbility])
        } else if path == prefix + "/shooting/control/moviemode" {
            if method == "POST" {
                let payload = try JSONSerialization.jsonObject(with: request.httpBody ?? Data()) as? [String: String]
                movieStatus = payload?["action"] ?? "invalid"
                return CameraHTTPResponse(statusCode: 204, body: Data())
            }
            body = try JSONSerialization.data(withJSONObject: ["status": movieStatus])
        } else {
            return CameraHTTPResponse(statusCode: 404, body: Data("{}".utf8))
        }
        // Capture response bytes before suspending, so late reads really carry
        // the old wire state even after a newer request or command completes.
        if method == "GET", let gate = nextGates.removeValue(forKey: path), !draining {
            await withCheckedContinuation { gates[gate] = $0 }
        }
        return CameraHTTPResponse(statusCode: 200, body: body)
    }
}
