import Foundation
import XCTest
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
@testable import OpenEOSCore

final class CameraRecordingLifecycleTests: XCTestCase {
    private func vectors() throws -> [String: Any] {
        var root = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { root.deleteLastPathComponent() }
        return try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf:
            root.appendingPathComponent("contracts/recording-lifecycle/v1/recording-vectors.json"))) as? [String: Any])
    }
    func testSharedRecordingObservationAndOwnershipVectors() throws {
        let root = try vectors()
        for vector in try XCTUnwrap(root["observations"] as? [[String: Any]]) {
            let body = try XCTUnwrap(vector["response"] as? [String: Any])
            XCTAssertEqual(CameraRecordingWire.observation(body, engine: "ccapi") != nil,
                vector["valid"] as? Bool, vector["name"] as? String ?? "")
        }
        for vector in try XCTUnwrap(root["ownership"] as? [[String: Any]]) {
            let input = try XCTUnwrap(vector["input"] as? [String: Any])
            let expected = try XCTUnwrap(vector["expected"] as? [String: Bool])
            let name = vector["name"] as? String ?? ""
            let sameOwner = input["sameOwner"] as! Bool, sameAttempt = input["sameAttempt"] as! Bool
            let cancelled = input["cancelled"] as! Bool, retired = input["retired"] as! Bool
            let starting = input["operation"] as? String == "start"
            let observation: CameraRecordingObservation = (input["recording"] as? Bool).map {
                .observed($0, source: "ccapi-simulator")
            } ?? .unavailable
            let response = try XCTUnwrap(CameraRecordingResponseEvidence(rawValue: input["responseEvidence"] as! String))
            XCTAssertEqual(CameraRecordingPolicy.canPublish(sameOwner: sameOwner, sameAttempt: sameAttempt,
                cancelled: cancelled, retired: retired), expected["canPublish"], name)
            XCTAssertEqual(CameraRecordingPolicy.stopRequired(previous: input["priorStopRequired"] as! Bool,
                starting: starting, response: response, observation: observation, sameOwner: sameOwner,
                sameAttempt: sameAttempt, cancelled: cancelled, retired: retired), expected["stopRequired"], name)
            XCTAssertEqual(CameraRecordingPolicy.canReviewStoppedVideo(sameOwner: sameOwner, sameAttempt: sameAttempt,
                cancelled: cancelled, retired: retired, starting: starting, observation: observation), expected["canReview"], name)
            XCTAssertEqual(expected["retryStart"], false, "No recording path has automatic Start retry: \(name)")
        }
    }

    func testWireRejectsDuplicateVersionFloatForeignSourceAndWrongNestedOwner() throws {
        let valid = #"{"version":1,"engine":"ccapi","operation":"stop","responseEvidence":"response-received","observation":{"version":1,"engine":"ccapi","observationEvidence":"observed","recording":false,"source":"ccapi-simulator"}}"#
        let body = try CameraRecordingWire.decode(Data(valid.utf8))
        XCTAssertNotNil(CameraRecordingWire.outcome(body, engine: "ccapi", starting: false))
        XCTAssertNil(CameraRecordingWire.outcome(body, engine: "ccapi", starting: true))
        for payload in [valid.replacingOccurrences(of: "\"version\":1", with: "\"version\":1.0"),
                        valid.replacingOccurrences(of: "ccapi-simulator", with: "gphoto-config"),
                        valid.replacingOccurrences(of: "\"observation\":{\"version\":1,\"engine\":\"ccapi\"", with: "\"observation\":{\"version\":1,\"engine\":\"usb-ptp\"")] {
            XCTAssertNil(CameraRecordingWire.outcome(try CameraRecordingWire.decode(Data(payload.utf8)), engine: "ccapi", starting: false))
        }
        XCTAssertThrowsError(try CameraRecordingWire.decode(Data(valid.replacingOccurrences(of: "\"version\":1", with: "\"version\":1,\"version\":1").utf8)))
    }

    func testCanonicalAcknowledgementHasExactStatusAndEmptyObjectContract() {
        for (status, body, accepted) in [(200, "", true), (200, "{}", true), (204, "", true),
            (204, "{}", false), (201, "{}", false), (202, "{}", false), (205, "", false),
            (206, "{}", false), (200, "{", false), (200, "[]", false),
            (200, #"{"recording":false}"#, false), (200, #"{"x":1,"x":1}"#, false)] {
            XCTAssertEqual(CameraRecordingWire.validDirectAcknowledgement(
                CameraHTTPResponse(statusCode: status, body: Data(body.utf8)), simulator: false), accepted)
        }
        XCTAssertFalse(CameraRecordingWire.validDirectAcknowledgement(
            CameraHTTPResponse(statusCode: 200, headers: ["content-range": "bytes 0-1/9"], body: Data("{}".utf8)), simulator: false))
    }

    func testOriginalOutcomeCannotBeUpgradedByLaterObservation() throws {
        let attempt = CameraRecordingAttempt(starting: true)
        try attempt.admit(owner: UUID(), starting: true)
        try attempt.markDispatched()
        attempt.complete()
        let original = attempt.snapshot
        attempt.recordResponse(.responseReceived)
        attempt.complete(.observed(false, source: "ccapi-simulator"))
        XCTAssertEqual(attempt.snapshot, original)
        XCTAssertEqual(original.responseEvidence, .unconfirmed)
        XCTAssertEqual(original.observation, .unavailable)
        XCTAssertThrowsError(try attempt.markDispatched())
    }

    func testBridgeLostStartFreshFalseRecoveryDoesNotReplayOrUpgradeOriginal() async throws {
        let peer = RecordingLifecyclePeer()
        let client = try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", transport: peer)
        try await client.initialize()
        await peer.setLostReply(true)
        let attempt = CameraRecordingAttempt(starting: true)
        do { _ = try await client.recordingOutcome(starting: true, attempt: attempt); XCTFail("Expected uncertainty") }
        catch { XCTAssertEqual(error as? DesktopBridgeError, .recordingCommandUnconfirmed(starting: true)) }
        let original = attempt.snapshot
        let duplicate = CameraRecordingAttempt(starting: true)
        do { _ = try await client.recordingOutcome(starting: true, attempt: duplicate); XCTFail("Unknown Start is Stop-only") }
        catch { XCTAssertEqual(duplicate.snapshot.responseEvidence, .notSent) }
        await peer.setObserved(false)
        let observed = try await client.recordingObservation()
        XCTAssertEqual(observed.observedRecording, false)
        XCTAssertEqual(attempt.snapshot, original)
        XCTAssertEqual(original.responseEvidence, .unconfirmed)
        let evidence = await client.recordingCommandEvidence
        XCTAssertNil(evidence)
        let requests = await peer.requests()
        XCTAssertEqual(requests.filter { $0.contains("/recording/") },
            ["POST /v1/session/synthetic/recording/start/outcome", "GET /v1/session/synthetic/recording/observation"])
    }

    func testBridgeStopResponseUnavailablePermitsDeliberateStartWithoutInventingFalse() async throws {
        let peer = RecordingLifecyclePeer()
        let client = try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", transport: peer)
        try await client.initialize()
        let start = try await client.recordingOutcome(starting: true, attempt: CameraRecordingAttempt(starting: true))
        XCTAssertEqual(start.responseEvidence, .responseReceived)
        let stop = try await client.recordingOutcome(starting: false, attempt: CameraRecordingAttempt(starting: false))
        XCTAssertEqual(stop.responseEvidence, .responseReceived)
        XCTAssertNil(stop.observation.observedRecording)
        let evidence = await client.recordingCommandEvidence
        XCTAssertNil(evidence)
        _ = try await client.recordingOutcome(starting: true, attempt: CameraRecordingAttempt(starting: true))
        let requests = await peer.requests()
        XCTAssertEqual(requests.filter { $0.hasPrefix("POST") && $0.contains("/recording/") }.count, 3)
    }

    func testDirectCanonicalAcknowledgementNeverBecomesObservedBoolean() async throws {
        let peer = RecordingLifecyclePeer(canonical: true)
        let client = try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: .camera, transport: peer)
        for starting in [true, false, true] {
            let outcome = try await client.recordingOutcome(starting: starting, attempt: CameraRecordingAttempt(starting: starting))
            XCTAssertEqual(outcome.responseEvidence, .responseReceived)
            XCTAssertEqual(outcome.observation, .unavailable)
        }
        let observation = try await client.recordingObservation()
        XCTAssertEqual(observation, .unavailable)
        let requests = await peer.requests()
        XCTAssertEqual(requests.filter { $0.contains("recbutton") }.count, 3)
        XCTAssertFalse(requests.contains { $0.hasPrefix("GET") && $0 != "GET /ccapi" })
    }

    func testCancelledStopACKRetainsOnlySameSessionStopRouteAfterCapabilityLoss() async throws {
        let peer = RecordingLifecyclePeer(canonical: true)
        await peer.holdInitialDiscovery()
        let client = try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: .camera, transport: peer)
        // Existing discovery calls may overlap. Hold the first advertisement,
        // then let Start initialize against a valid REC route before it retires.
        let oldDiscovery = Task { try await client.initialize() }
        defer { Task { await peer.releaseInitialDiscovery() } }
        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: .seconds(5))
        while !(await peer.initialDiscoveryWaiting()), clock.now < deadline { await Task.yield() }
        let waiting = await peer.initialDiscoveryWaiting()
        guard waiting else { oldDiscovery.cancel(); XCTFail("Initial discovery did not reach its gate"); return }
        _ = try await client.recordingOutcome(starting: true, attempt: CameraRecordingAttempt(starting: true))
        let stop = Task {
            let outcome = try await client.recordingOutcome(starting: false, attempt: CameraRecordingAttempt(starting: false))
            // Deterministically cancel after ACK/fresh-read settlement but before
            // the consumer's publication decision, preserving its prior duty.
            withUnsafeCurrentTask { $0?.cancel() }
            return outcome
        }
        let stopped = try await stop.value
        XCTAssertEqual(stopped.responseEvidence, .responseReceived)
        XCTAssertTrue(CameraRecordingPolicy.stopRequired(previous: true, starting: false,
            response: stopped.responseEvidence, observation: stopped.observation, cancelled: true))
        let clientDuty = await client.recordingCommandEvidence
        XCTAssertNil(clientDuty, "Resolved client duty must not erase the separate Stop authorization")
        await peer.releaseInitialDiscovery()
        try await oldDiscovery.value // The current advertisement now has no REC route.
        let newStart = CameraRecordingAttempt(starting: true)
        do { _ = try await client.recordingOutcome(starting: true, attempt: newStart); XCTFail("Retained Stop cannot authorize Start") }
        catch { XCTAssertEqual(error as? CCAPIError, .unsupported(.videoRecording)) }
        XCTAssertEqual(newStart.snapshot.responseEvidence, .notSent)
        let explicitStop = try await client.recordingOutcome(starting: false, attempt: CameraRecordingAttempt(starting: false))
        XCTAssertEqual(explicitStop.responseEvidence, .responseReceived)
        await client.close()
        let retired = CameraRecordingAttempt(starting: false)
        do { _ = try await client.recordingOutcome(starting: false, attempt: retired); XCTFail("Retired owner must not send Stop") }
        catch { XCTAssertEqual(retired.snapshot.responseEvidence, .notSent) }
        let replacement = try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: .camera, transport: peer)
        let foreign = CameraRecordingAttempt(starting: false)
        do { _ = try await replacement.recordingOutcome(starting: false, attempt: foreign); XCTFail("Replacement must not inherit Stop route") }
        catch { XCTAssertEqual(error as? CCAPIError, .unsupported(.videoRecording)) }
        XCTAssertEqual(foreign.snapshot.responseEvidence, .notSent)
        let actions = await peer.recordingActions()
        XCTAssertEqual(actions, ["start", "stop", "stop"], "Only deliberate commands; no Start replay or cross-owner Stop")
    }

    func testFirstRejectedStopRetainsValidatedRouteAfterCapabilityLoss() async throws {
        let peer = RecordingLifecyclePeer(canonical: true)
        await peer.holdInitialDiscovery()
        let client = try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: .camera, transport: peer)
        let oldDiscovery = Task { try await client.initialize() }
        defer { Task { await peer.releaseInitialDiscovery() } }
        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: .seconds(5))
        while !(await peer.initialDiscoveryWaiting()), clock.now < deadline { await Task.yield() }
        let waiting = await peer.initialDiscoveryWaiting()
        guard waiting else { oldDiscovery.cancel(); XCTFail("Initial discovery did not reach its gate"); return }
        // Recording began on the camera body. The first app command is Stop,
        // so there is no earlier admitted command route to restore.
        await peer.setCanonicalStatus(503)
        let rejected = try await client.recordingOutcome(starting: false, attempt: CameraRecordingAttempt(starting: false))
        XCTAssertEqual(rejected.responseEvidence, .rejected)
        XCTAssertTrue(CameraRecordingPolicy.stopRequired(previous: true, starting: false,
            response: rejected.responseEvidence, observation: rejected.observation))
        await peer.releaseInitialDiscovery()
        try await oldDiscovery.value
        await peer.setCanonicalStatus(204)
        let deliberate = try await client.recordingOutcome(starting: false, attempt: CameraRecordingAttempt(starting: false))
        XCTAssertEqual(deliberate.responseEvidence, .responseReceived)
        let actions = await peer.recordingActions()
        XCTAssertEqual(actions, ["stop", "stop"], "Rejection retains Stop authorization, with no automatic replay")
    }

    func testCancelledNotSentStartPreservesNoDutyAndAllowsDeliberateNewStart() async throws {
        let peer = RecordingLifecyclePeer()
        let client = try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", transport: peer)
        try await client.initialize()
        await peer.setCommandOutcome(response: "not-sent", cancelled: true)
        let attempt = CameraRecordingAttempt(starting: true)
        do { _ = try await client.recordingOutcome(starting: true, attempt: attempt); XCTFail("Expected cancellation") }
        catch { XCTAssertTrue(error is CancellationError) }
        XCTAssertEqual(attempt.snapshot.responseEvidence, .notSent)
        let duty = await client.recordingCommandEvidence
        XCTAssertNil(duty, "Known preflight/no-op not-sent must not leave a fabricated Start duty")
        await peer.setCommandOutcome(response: "response-received", cancelled: false)
        let deliberate = try await client.recordingOutcome(starting: true, attempt: CameraRecordingAttempt(starting: true))
        XCTAssertEqual(deliberate.responseEvidence, .responseReceived)
        XCTAssertEqual(attempt.snapshot.responseEvidence, .notSent)
        let requests = await peer.requests()
        XCTAssertEqual(requests.filter { $0.contains("/recording/") }, [
            "POST /v1/session/synthetic/recording/start/outcome",
            "POST /v1/session/synthetic/recording/start/outcome"
        ], "Each explicit call dispatches once; cancellation never causes replay")
    }

    func testBridgeOnlyScopedRejectionHeadersResolveAnIncompleteCommand() async throws {
        for status in [200, 206, 307, 400, 401, 403, 404, 405, 408, 409, 410, 422, 429, 500, 502, 503] {
            let rejected = [400, 401, 403, 404, 405, 409, 410, 422, 429].contains(status)
            let peer = RecordingLifecyclePeer(outcomeStatus: status, incompleteCommand: true)
            let client = try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", transport: peer)
            try await client.initialize()
            let attempt = CameraRecordingAttempt(starting: true)
            do {
                _ = try await client.recordingOutcome(starting: true, attempt: attempt)
                XCTAssertTrue(rejected)
            } catch {
                XCTAssertFalse(rejected)
                XCTAssertEqual(error as? DesktopBridgeError, .recordingCommandUnconfirmed(starting: true))
            }
            XCTAssertEqual(attempt.snapshot.responseEvidence, rejected ? .rejected : .unconfirmed)
            XCTAssertNil(attempt.snapshot.observation.observedRecording)
            let duty = await client.recordingCommandEvidence
            XCTAssertEqual(duty != nil, !rejected)
            let requests = await peer.requests()
            XCTAssertEqual(requests.filter { $0.contains("/recording/") }, ["POST /v1/session/synthetic/recording/start/outcome"])
        }
    }

    func testBridgeMalformedOutcomeUsesNoLegacyFallback() async throws {
        for payload in ["{}", "[]", #"{"recording":false}"#] {
            let peer = RecordingLifecyclePeer(rawOutcome: payload)
            let client = try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", transport: peer)
            try await client.initialize()
            let attempt = CameraRecordingAttempt(starting: true)
            do { _ = try await client.recordingOutcome(starting: true, attempt: attempt); XCTFail("Invalid outcome") }
            catch { XCTAssertEqual(error as? DesktopBridgeError, .recordingCommandUnconfirmed(starting: true)) }
            XCTAssertEqual(attempt.snapshot.responseEvidence, .unconfirmed)
            let requests = await peer.requests()
            XCTAssertEqual(requests.filter { $0.contains("/recording/") }, ["POST /v1/session/synthetic/recording/start/outcome"])
        }
    }
}

private actor RecordingLifecyclePeer: CameraHTTPTransport {
    private let canonical: Bool
    private let rawOutcome: String?
    private let outcomeStatus: Int
    private let incompleteCommand: Bool
    private var canonicalStatus = 204
    private var commandCancelled = false
    private var commandResponse = "response-received"
    private var lostReply = false
    private var observed: Bool?
    private var sent: [String] = []
    private var actions: [String] = []
    private var holdFirstDiscovery = false
    private var discoveryCount = 0
    private var discoveryGate: CheckedContinuation<Void, Never>?
    private var recordingCapabilityRetired = false
    init(canonical: Bool = false, rawOutcome: String? = nil, outcomeStatus: Int = 200, incompleteCommand: Bool = false) {
        self.canonical = canonical; self.rawOutcome = rawOutcome
        self.outcomeStatus = outcomeStatus; self.incompleteCommand = incompleteCommand
    }
    func sendRecordingCommand(_ request: URLRequest) async throws -> CameraHTTPRecordingResponse {
        CameraHTTPRecordingResponse(response: try await send(request), bodyComplete: !incompleteCommand, cancelled: commandCancelled)
    }
    func setCanonicalStatus(_ status: Int) { canonicalStatus = status }
    func holdInitialDiscovery() { holdFirstDiscovery = true }
    func initialDiscoveryWaiting() -> Bool { discoveryGate != nil }
    func releaseInitialDiscovery() { let gate = discoveryGate; discoveryGate = nil; gate?.resume() }
    func recordingActions() -> [String] { actions }
    func setCommandOutcome(response: String, cancelled: Bool) { commandResponse = response; commandCancelled = cancelled }
    func setLostReply(_ value: Bool) { lostReply = value }
    func setObserved(_ value: Bool?) { observed = value }
    func requests() -> [String] { sent }
    private func observation() -> [String: Any] {
        ["version": 1, "engine": "ccapi", "observationEvidence": observed == nil ? "unavailable" : "observed",
         "recording": observed.map { $0 as Any } ?? NSNull(), "source": observed == nil ? NSNull() : "ccapi-simulator" as Any]
    }
    func send(_ request: URLRequest) async throws -> CameraHTTPResponse {
        let path = request.url!.path, method = request.httpMethod ?? "GET"
        sent.append("\(method) \(path)")
        let body: [String: Any]
        if path == "/health" { body = ["ok": true, "service": "open-eos-control-bridge", "version": "0.13.0"] }
        else if path == "/v1/session" { body = ["id": "synthetic", "engine": "ccapi"] }
        else if path == "/ccapi", canonical {
            discoveryCount += 1
            if holdFirstDiscovery, discoveryCount == 1 {
                await withCheckedContinuation { discoveryGate = $0 }
                recordingCapabilityRetired = true
            }
            body = recordingCapabilityRetired
                ? ["ver100": [["path": "/deviceinformation", "get": true]]]
                : ["ver100": [["path": "/shooting/control/recbutton", "post": true]]]
        }
        else if path.hasSuffix("recbutton"), canonical {
            if let bytes = request.httpBody, let payload = try JSONSerialization.jsonObject(with: bytes) as? [String: Any],
               let action = payload["action"] as? String { actions.append(action) }
            return CameraHTTPResponse(statusCode: canonicalStatus)
        }
        else if path.hasSuffix("/recording/observation") { body = observation() }
        else if path.hasSuffix("/outcome") {
            if lostReply { throw URLError(.networkConnectionLost) }
            if let rawOutcome { return CameraHTTPResponse(statusCode: 200, body: Data(rawOutcome.utf8)) }
            body = ["version": 1, "engine": "ccapi", "operation": path.contains("/start/") ? "start" : "stop",
                    "responseEvidence": commandResponse, "observation": observation()]
        } else { throw URLError(.unsupportedURL) }
        return CameraHTTPResponse(statusCode: path.hasSuffix("/outcome") ? outcomeStatus : 200, body: try JSONSerialization.data(withJSONObject: body))
    }
    func download(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse { throw URLError(.unsupportedURL) }
}
