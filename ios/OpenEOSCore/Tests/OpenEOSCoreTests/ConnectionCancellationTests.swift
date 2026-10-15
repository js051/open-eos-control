import Foundation
import XCTest
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
@testable import OpenEOSCore

/// Native protocol fixtures. Each gate ignores cancellation until explicitly
/// released; cleanup requests reject cancelled tasks so context inheritance is tested.
final class ConnectionCancellationTests: XCTestCase {
    func testSharedSessionCreationRejectionVectorsSurviveCancellation() async throws {
        var root = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { root.deleteLastPathComponent() }
        let data = try Data(contentsOf: root.appendingPathComponent("contracts/connection-lifecycle/v1/session-creation-rejection-vectors.json"))
        let vectors = try JSONDecoder().decode(CreationVectors.self, from: data).vectors
        XCTAssertEqual(vectors.count, 49)
        for vector in vectors {
            let peer = ConnectionCancelPeer(creation: vector.response, holdPath: "/v1/session")
            let client = try DesktopBridgeClient(baseURL: "http://old-bridge.invalid:18181", transport: peer)
            let opening = Task { try await client.initialize() }
            do {
                try await waitForGate(peer)
                opening.cancel()
                _ = await client.closeWithShutterReleaseState()
                await peer.release()
                await assertFailure(opening, name: vector.id)
                let unconfirmed = await client.connectionRetirementUnconfirmed
                XCTAssertEqual(unconfirmed, !vector.creationRejected, vector.id)
                let safety = await client.connectionRetirementSafetyWarnings
                let expectedSafety = DesktopBridgeClient.ConnectionRetirementSafetyWarning(rawValue: vector.code)
                XCTAssertEqual(safety, Set(expectedSafety.map { [$0] } ?? []), vector.id)
                let requests = await peer.requests()
                XCTAssertEqual(requests.map { $0.url!.path }, ["/health", "/v1/session"], vector.id)
                if unconfirmed {
                    do { try await client.initialize(); XCTFail("Unknown owner cannot be reopened: \(vector.id)") }
                    catch { XCTAssertEqual(error as? DesktopBridgeError, .sessionChanged, vector.id) }
                    let after = await peer.requests()
                    XCTAssertEqual(after.count, requests.count, vector.id)
                }
            } catch {
                opening.cancel(); await peer.release(); _ = await opening.result
                throw error
            }
        }
    }

    func testLatePOSTSuccessDeletesOnlyReturnedIDWithOriginalAuthAndFreshCancellationContext() async throws {
        for deleteStatus in [204, 200, 202, 206, 404, 503] {
            let peer = ConnectionCancelPeer(creation: .json(201, #"{"id":"old-session","engine":"ccapi"}"#),
                                            holdPath: "/v1/session", deleteStatus: deleteStatus)
            let client = try DesktopBridgeClient(baseURL: "http://old-bridge.invalid:18181", token: "synthetic-old-token", transport: peer)
            let opening = Task { try await client.connectSnapshot() }
            do {
                try await waitForGate(peer)
                opening.cancel()
                _ = await client.closeWithShutterReleaseState()
                await peer.release()
                await assertFailure(opening, name: "late success")
                _ = await client.closeWithShutterReleaseState()
                _ = await client.closeWithShutterReleaseState()
                let unconfirmed = await client.connectionRetirementUnconfirmed
                XCTAssertEqual(unconfirmed, deleteStatus != 204)
                let requests = await peer.requests()
                XCTAssertEqual(requests.map { $0.url!.path }, ["/health", "/v1/session", "/v1/session/old-session"])
                let deletion = try XCTUnwrap(requests.last)
                XCTAssertEqual(deletion.httpMethod, "DELETE")
                XCTAssertEqual(deletion.url?.host, "old-bridge.invalid")
                XCTAssertEqual(deletion.value(forHTTPHeaderField: "Authorization"), "Bearer synthetic-old-token")
                let cancelledDeletes = await peer.cancelledDeletionCount()
                XCTAssertEqual(cancelledDeletes, 0)
                XCTAssertFalse(requests.contains { $0.url!.path.hasSuffix("/info") })
            } catch {
                opening.cancel(); await peer.release(); _ = await opening.result
                throw error
            }
        }
    }

    func testLateCreationAndDeletionSafetyCodesRemainWarningsWithoutReplayableCommands() async throws {
        for code in ["SHUTTER_RELEASE_UNCONFIRMED", "LIVE_VIEW_STOP_UNCONFIRMED", "RECORDING_COMMAND_UNCONFIRMED"] {
            for lateDelete in [false, true] {
                let failure = ConnectionCancelPeer.Reply.json(409, "{\"error\":{\"code\":\"\(code)\"}}")
                let peer = ConnectionCancelPeer(
                    creation: lateDelete ? .json(201, #"{"id":"old-session","engine":"ccapi"}"#) : failure,
                    holdPath: "/v1/session", deletion: lateDelete ? failure : nil)
                let client = try DesktopBridgeClient(baseURL: "http://old-bridge.invalid:18181", transport: peer)
                let opening = Task { try await client.initialize() }
                do {
                    try await waitForGate(peer)
                    opening.cancel()
                    _ = await client.closeWithShutterReleaseState()
                    await peer.release()
                    await assertFailure(opening, name: code)
                    _ = await client.closeWithShutterReleaseState()
                    let warnings = await client.connectionRetirementSafetyWarnings
                    XCTAssertEqual(warnings.map(\.rawValue), [code])
                    let unconfirmed = await client.connectionRetirementUnconfirmed
                    XCTAssertTrue(unconfirmed)
                    let shutter = await client.shutterReleaseState()
                    let recording = await client.recordingCommandEvidence
                    let liveView = await client.liveViewStopState()
                    XCTAssertFalse(shutter.releaseRequired, "Safety code cannot invent an unknown Stop target")
                    XCTAssertNil(recording, "Retired warning is not a replayable recording command")
                    XCTAssertFalse(liveView.stopRequired)
                    let requests = await peer.requests()
                    XCTAssertEqual(requests.map { $0.url!.path }, lateDelete
                        ? ["/health", "/v1/session", "/v1/session/old-session"] : ["/health", "/v1/session"])
                    XCTAssertFalse(requests.contains { $0.url!.path.hasSuffix("/stop") })
                } catch {
                    opening.cancel(); await peer.release(); _ = await opening.result
                    throw error
                }
            }
        }
    }

    func testCancellationBeforeHealthReturnsDoesNotCreateSession() async throws {
        let peer = ConnectionCancelPeer(holdPath: "/health")
        let client = try DesktopBridgeClient(baseURL: "http://bridge.invalid:18181", transport: peer)
        let opening = Task { try await client.connectSnapshot() }
        do {
            try await waitForGate(peer)
            opening.cancel()
            _ = await client.closeWithShutterReleaseState()
            await peer.release()
            await assertFailure(opening, name: "before POST")
            let requests = await peer.requests()
            XCTAssertEqual(requests.map { $0.url!.path }, ["/health"])
            let unconfirmed = await client.connectionRetirementUnconfirmed
            XCTAssertFalse(unconfirmed)
        } catch {
            opening.cancel(); await peer.release(); _ = await opening.result
            throw error
        }
    }

    func testDirectDiscoveryAndMetadataCancellationDoNotContinueToLaterReadsOrFallback() async throws {
        for path in ["/ccapi", "/ccapi/ver100/deviceinformation"] {
            for fails in [false, true] {
                let peer = ConnectionCancelPeer(holdPath: path, heldFailure: fails)
                let client = try CCAPIClient(baseURL: "http://camera.invalid:8080", mode: .camera, transport: peer)
                let opening = Task { try await client.connectSnapshot() }
                do {
                    try await waitForGate(peer)
                    opening.cancel()
                    _ = await client.closeWithShutterReleaseState()
                    await peer.release()
                    await assertFailure(opening, name: path)
                    let requests = await peer.requests()
                    XCTAssertEqual(requests.map { $0.url!.path }, path == "/ccapi" ? ["/ccapi"] : ["/ccapi", path])
                    XCTAssertTrue(requests.allSatisfy { $0.httpMethod == "GET" })
                } catch {
                    opening.cancel(); await peer.release(); _ = await opening.result
                    throw error
                }
            }
        }
    }

    func testUnknownCreationEvidenceCannotBeForgedByWhitespaceOrTruncatedBodies() async throws {
        let cases: [ConnectionCancelPeer.Reply] = [
            .json(503, #"{"error":{"code":" ENGINE_UNAVAILABLE ","engine":"ccapi"}}"#),
            .json(502, #"{"error":{"code":"CCAPI_DISCOVERY_FAILED","engine":" ccapi "}}"#),
            .json(502, #"{"error":{"code":"CCAPI_DISCOVERY_FAILED","engine":"ccapi"}}"#, ["content-length": "10000"]),
            .json(502, #"{"error":{"code":"CCAPI_DISCOVERY_FAILED","engine":"ccapi"}"#),
            .json(206, #"{"id":"not-a-complete-owner"}"#),
            .json(201, #"{"id":"not-a-complete-owner"}"#, ["content-range": "bytes 0-12/100"]),
        ]
        for reply in cases {
            let peer = ConnectionCancelPeer(creation: reply)
            let client = try DesktopBridgeClient(baseURL: "http://bridge.invalid:18181", transport: peer)
            do { try await client.initialize(); XCTFail("Incomplete creation must not become success") }
            catch { }
            let unconfirmed = await client.connectionRetirementUnconfirmed
            XCTAssertTrue(unconfirmed)
            let requests = await peer.requests()
            XCTAssertEqual(requests.count, 2)
            XCTAssertFalse(requests.contains { $0.httpMethod == "DELETE" }, "Never invent a session ID from partial data")
        }
    }

    func testSessionIdentityMustBeBoundedASCIIWithoutCoercionOrTrimming() async throws {
        let identities: [Any] = ["", " old-session", "old-session ", "old/session", "../session", "會話", 123,
                                 String(repeating: "a", count: 129), NSNull()]
        for identity in identities {
            let body = String(decoding: try JSONSerialization.data(withJSONObject: ["id": identity]), as: UTF8.self)
            let peer = ConnectionCancelPeer(creation: .json(201, body))
            let client = try DesktopBridgeClient(baseURL: "http://bridge.invalid:18181", transport: peer)
            do { try await client.initialize(); XCTFail("Malformed ID must not become a resource owner") }
            catch { }
            let unconfirmed = await client.connectionRetirementUnconfirmed
            XCTAssertTrue(unconfirmed)
            let requests = await peer.requests()
            XCTAssertEqual(requests.count, 2)
            XCTAssertFalse(requests.contains { $0.httpMethod == "DELETE" })
        }
        for identity in ["A", String(repeating: "a", count: 128), "Synthetic_1-2"] {
            let body = String(decoding: try JSONSerialization.data(withJSONObject: ["id": identity]), as: UTF8.self)
            let peer = ConnectionCancelPeer(creation: .json(201, body))
            let client = try DesktopBridgeClient(baseURL: "http://bridge.invalid:18181", transport: peer)
            try await client.initialize()
            _ = await client.closeWithShutterReleaseState()
            let unconfirmed = await client.connectionRetirementUnconfirmed
            XCTAssertFalse(unconfirmed)
            let requests = await peer.requests()
            XCTAssertEqual(requests.last?.url?.path, "/v1/session/" + identity)
        }
    }

    private func waitForGate(_ peer: ConnectionCancelPeer) async throws {
        let deadline = ContinuousClock.now.advanced(by: .seconds(5))
        repeat {
            if await peer.waiting() { return }
            await Task.yield()
        } while ContinuousClock.now < deadline
        XCTFail("Connection did not reach its controlled protocol gate")
        throw URLError(.timedOut)
    }

    private func assertFailure<T: Sendable>(_ task: Task<T, Error>, name: String,
                                  file: StaticString = #filePath, line: UInt = #line) async {
        switch await task.result {
        case .success: XCTFail("Retired connection succeeded: \(name)", file: file, line: line)
        case .failure: break
        }
    }
}

private struct CreationVectors: Decodable {
    let vectors: [Vector]
    struct Vector: Decodable {
        let id: String
        let status: Int?
        let code: String
        let engine: String?
        let creationRejected: Bool
        var response: ConnectionCancelPeer.Reply {
            guard let status else { return .lost }
            if id == "malformed-success" { return .json(status, "{}") }
            var detail: [String: Any] = ["code": code, "message": "synthetic"]
            if let engine { detail["engine"] = engine }
            let data = try! JSONSerialization.data(withJSONObject: ["error": detail])
            return .json(status, String(decoding: data, as: UTF8.self))
        }
    }
}

private actor ConnectionCancelPeer: CameraHTTPTransport {
    enum Reply: Sendable {
        case json(Int, String, [String: String] = [:])
        case lost
        func response() throws -> CameraHTTPResponse {
            switch self {
            case let .json(status, body, headers): return CameraHTTPResponse(statusCode: status, headers: headers, body: Data(body.utf8))
            case .lost: throw URLError(.networkConnectionLost)
            }
        }
    }
    private let creation: Reply
    private var holdPath: String?
    private let heldFailure: Bool
    private let deleteStatus: Int
    private let deletion: Reply?
    private var continuation: CheckedContinuation<Void, Never>?
    private var recorded: [URLRequest] = []
    private var cancelledDeletes = 0

    init(creation: Reply = .json(201, #"{"id":"old-session","engine":"ccapi"}"#),
         holdPath: String? = nil, heldFailure: Bool = false, deleteStatus: Int = 204, deletion: Reply? = nil) {
        self.creation = creation; self.holdPath = holdPath
        self.heldFailure = heldFailure; self.deleteStatus = deleteStatus; self.deletion = deletion
    }

    func send(_ request: URLRequest) async throws -> CameraHTTPResponse {
        recorded.append(request)
        let path = request.url!.path
        if path == holdPath {
            holdPath = nil
            await withCheckedContinuation { continuation = $0 }
            if heldFailure { throw URLError(.networkConnectionLost) }
        }
        if path == "/v1/session" {
            // Production lifecycle transport is cancellation-cooperative. The
            // dispatched creation owner must outlive cancellation of initialize.
            try Task.checkCancellation()
        }
        if request.httpMethod == "DELETE" {
            if Task.isCancelled { cancelledDeletes += 1 }
            try Task.checkCancellation()
            return try (deletion ?? .json(deleteStatus, "")).response()
        }
        switch path {
        case "/health": return try Reply.json(200, #"{"ok":true,"service":"open-eos-control-bridge"}"#).response()
        case "/v1/session": return try creation.response()
        case "/v1/session/old-session/info": return try Reply.json(200, #"{"connected":true,"model":"Synthetic EOS"}"#).response()
        case "/v1/session/old-session/status": return try Reply.json(200, #"{"connected":true,"recording":false}"#).response()
        case "/v1/session/old-session/capabilities": return try Reply.json(200, #"{"supported":[]}"#).response()
        case "/ccapi": return try Reply.json(200, #"{"ver100":[{"path":"/deviceinformation","get":true},{"path":"/devicestatus/battery","get":true}]}"#).response()
        case "/ccapi/ver100/deviceinformation": return try Reply.json(200, #"{"productname":"Synthetic EOS"}"#).response()
        default: throw URLError(.unsupportedURL)
        }
    }

    func download(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse { throw URLError(.unsupportedURL) }
    func waiting() -> Bool { continuation != nil }
    func release() { continuation?.resume(); continuation = nil }
    func requests() -> [URLRequest] { recorded }
    func cancelledDeletionCount() -> Int { cancelledDeletes }
}
