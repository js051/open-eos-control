import Foundation
import XCTest

#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

@testable import OpenEOSCore

final class RecordingReadbackClientTests: XCTestCase {
    func testBridgeMapsOnlyAcknowledgedReadbackCodeWithTheRequestedDirection() async throws {
        for starting in [true, false] {
            let peer = RecordingBoundaryTransport(mode: .bridge, commandFault: .acknowledgedReadback)
            let client = try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", cameraID: "synthetic", transport: peer)
            try await client.initialize()
            do {
                _ = starting ? try await client.startRecording() : try await client.stopRecording()
                XCTFail("Expected the precise Bridge ACK/readback classification")
            } catch {
                XCTAssertEqual(error as? DesktopBridgeError, .recordingStatusReadbackFailed(starting: starting))
            }
            let actions = await peer.actions()
            XCTAssertEqual(actions, [starting ? "start" : "stop"])
            let reads = await peer.statusReadCount()
            XCTAssertEqual(reads, 0, "The client must not automatically recheck or replay REC")
        }
    }

    func testBridgeCommandRejectionLostResponseAndCancellationDoNotInventAnAcknowledgement() async throws {
        for fault in [RecordingBoundaryTransport.Fault.http, .network, .malformed, .cancelled] {
            for starting in [true, false] {
                let peer = RecordingBoundaryTransport(mode: .bridge, commandFault: fault)
                let client = try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", cameraID: "synthetic", transport: peer)
                try await client.initialize()
                do {
                    _ = starting ? try await client.startRecording() : try await client.stopRecording()
                    XCTFail("Expected an unacknowledged command failure")
                } catch {
                    if let error = error as? DesktopBridgeError, case .recordingStatusReadbackFailed = error {
                        XCTFail("A generic error cannot establish that the camera acknowledged REC")
                    }
                    if fault == .cancelled { XCTAssertTrue(error is CancellationError) }
                }
                let actions = await peer.actions()
                XCTAssertEqual(actions, [starting ? "start" : "stop"])
            }
        }
    }

    func testRequiredSimulatorReadFailurePreservesAckAndReadOnlyRecoveryNeverReplaysREC() async throws {
        for starting in [true, false] {
            for fault in [RecordingBoundaryTransport.Fault.http, .network, .malformed] {
                let peer = RecordingBoundaryTransport(mode: .simulator, readFault: fault)
                let client = try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: .simulator, transport: peer)
                do {
                    _ = starting ? try await client.startRecording() : try await client.stopRecording()
                    XCTFail("The required simulator status read must fail after the command ACK")
                } catch {
                    XCTAssertEqual(error as? CCAPIError, .recordingStatusReadbackFailed(starting: starting))
                }
                await peer.clearReadFault()
                let refreshed = try await client.status()
                XCTAssertEqual(refreshed.recording, starting)
                let actions = await peer.actions()
                XCTAssertEqual(actions, [starting ? "start" : "stop"])
                let reads = await peer.statusReadCount()
                XCTAssertEqual(reads, 2)
            }
        }
    }

    func testDirectCommandFailureIsOutsideTheAcknowledgedReadbackBoundary() async throws {
        for mode in [RecordingBoundaryTransport.Mode.simulator, .canonical] {
            for fault in [RecordingBoundaryTransport.Fault.http, .network, .cancelled] {
                let peer = RecordingBoundaryTransport(mode: mode, commandFault: fault)
                let client = try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: mode == .canonical ? .camera : .simulator, transport: peer)
                do {
                    _ = try await client.startRecording()
                    XCTFail("Expected the command itself to fail")
                } catch {
                    if let error = error as? CCAPIError, case .recordingStatusReadbackFailed = error {
                        XCTFail("A lost or rejected command response has no known ACK")
                    }
                    if fault == .cancelled { XCTAssertTrue(error is CancellationError) }
                }
                let reads = await peer.statusReadCount()
                XCTAssertEqual(reads, 0)
                if fault == .http {
                    let evidence = await client.recordingCommandEvidence
                    let physicalRecording = await peer.cameraRecording()
                    XCTAssertNil(evidence)
                    XCTAssertFalse(physicalRecording)
                }
            }
        }
    }

    func testAppliedStartWithLostResponseHasUnknownEvidenceAndCanonicalFalseCacheCannotResolveIt() async throws {
        for mode in [RecordingBoundaryTransport.Mode.simulator, .canonical] {
            let peer = RecordingBoundaryTransport(mode: mode)
            let client = try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: mode == .canonical ? .camera : .simulator, transport: peer)
            _ = try await client.stopRecording() // Seed the client's ACK cache false.
            await peer.setCommandFault(.network)
            do {
                _ = try await client.startRecording()
                XCTFail("Expected command result uncertainty")
            } catch {
                XCTAssertEqual(error as? CCAPIError, .recordingCommandUnconfirmed(starting: true))
            }
            let physicalRecording = await peer.cameraRecording()
            XCTAssertTrue(physicalRecording, "The peer applied Start independently before dropping its reply")
            let refreshed = try await client.status()
            if mode == .canonical { XCTAssertNil(refreshed.recording, "Canonical status cannot expose an ACK direction as observation") }
            let evidence = await client.recordingCommandEvidence
            XCTAssertEqual(evidence, .unconfirmed(starting: true))
            let actions = await peer.actions()
            XCTAssertEqual(actions, ["stop", "start"], "Reading status never replays REC")
        }
    }

    func testBridgeUnknownCodeRequiresExactStatusFeatureAndEngine() async throws {
        for (status, feature, engine, accepted) in [
            (502, "VIDEO_RECORDING", "ccapi", true),
            (503, "VIDEO_RECORDING", "ccapi", false),
            (502, "STILL_CAPTURE", "ccapi", false),
            (502, "VIDEO_RECORDING", "gphoto2", false),
        ] {
            let peer = RecordingBoundaryTransport(mode: .bridge, commandFault: .unconfirmed,
                unknownStatus: status, unknownFeature: feature, unknownEngine: engine)
            let client = try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", transport: peer)
            try await client.initialize()
            do {
                _ = try await client.startRecording()
                XCTFail("Expected an error response")
            } catch {
                if accepted {
                    XCTAssertEqual(error as? DesktopBridgeError, .recordingCommandUnconfirmed(starting: true))
                } else if let error = error as? DesktopBridgeError, case .http = error {
                    // A similarly named unrelated failure retains its original classification.
                } else { XCTFail("Unknown evidence must be scoped to the exact REC contract") }
            }
            let evidence = await client.recordingCommandEvidence
            XCTAssertEqual(evidence, accepted ? .unconfirmed(starting: true) : nil)
        }
    }

    func testCancellationAfterSendKeepsEvidenceAndBeforeSendDoesNotSendREC() async throws {
        for mode in [RecordingBoundaryTransport.Mode.simulator, .canonical] {
            let peer = RecordingBoundaryTransport(mode: mode, commandFault: .cancelled)
            let client = try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: mode == .canonical ? .camera : .simulator, transport: peer)
            do { _ = try await client.startRecording(); XCTFail("Expected cancellation") }
            catch { XCTAssertTrue(error is CancellationError) }
            let evidence = await client.recordingCommandEvidence
            XCTAssertEqual(evidence, .unconfirmed(starting: true))
            let physicalRecording = await peer.cameraRecording()
            XCTAssertTrue(physicalRecording)

            let freshPeer = RecordingBoundaryTransport(mode: mode)
            let fresh = try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: mode == .canonical ? .camera : .simulator, transport: freshPeer)
            let task = Task {
                withUnsafeCurrentTask { $0?.cancel() }
                return try await fresh.startRecording()
            }
            do { _ = try await task.value; XCTFail("Expected cancellation before send") }
            catch { XCTAssertTrue(error is CancellationError) }
            let sent = await freshPeer.actions()
            let freshEvidence = await fresh.recordingCommandEvidence
            XCTAssertEqual(sent, [])
            XCTAssertNil(freshEvidence)
        }
    }

    func testPreflightFailureHasNoCommandAttemptEvidence() async throws {
        let peer = RecordingBoundaryTransport(mode: .canonical, failPreflight: true)
        let direct = try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: .camera, transport: peer)
        do { _ = try await direct.startRecording(); XCTFail("Expected discovery failure") }
        catch {
            if let error = error as? CCAPIError, case .recordingCommandUnconfirmed = error {
                XCTFail("A failed preflight cannot establish an attempted REC")
            }
        }
        let directEvidence = await direct.recordingCommandEvidence
        let directActions = await peer.actions()
        XCTAssertNil(directEvidence)
        XCTAssertEqual(directActions, [])

        let bridgePeer = RecordingBoundaryTransport(mode: .bridge)
        let bridge = try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", transport: bridgePeer)
        do { _ = try await bridge.startRecording(); XCTFail("Expected uninitialized session") }
        catch { XCTAssertEqual(error as? DesktopBridgeError, .notInitialized) }
        let bridgeEvidence = await bridge.recordingCommandEvidence
        let bridgeActions = await bridgePeer.actions()
        XCTAssertNil(bridgeEvidence)
        XCTAssertEqual(bridgeActions, [])
    }

    func testRetirementCoversStartAdmissionBetweenEarlierEvidenceReadAndClose() async throws {
        for admittedBeforeClose in [false, true] {
            let peer = RecordingBoundaryTransport(mode: .canonical, commandFault: .network, advertiseTemperature: true)
            defer { Task { await peer.releaseAll() } }
            let client = try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: .camera, transport: peer)
            try await client.initialize()
            await peer.holdNext("temperature")
            await peer.holdNext("start")
            let start = Task { try await client.startRecording() }
            try await waitForGate("temperature", peer: peer)
            let earlierEvidence = await client.recordingCommandEvidence
            XCTAssertNil(earlierEvidence, "Start is still in preflight, before command admission")
            if admittedBeforeClose {
                // The exact gap: an earlier evidence read was nil, then the
                // preflight continuation admits REC before Close gets the actor.
                await peer.release("temperature")
                try await waitForGate("start", peer: peer)
            }
            _ = await client.closeWithShutterReleaseState()
            let retiredEvidence = await client.recordingCommandEvidence
            XCTAssertEqual(retiredEvidence, admittedBeforeClose ? .unconfirmed(starting: true) : nil)
            if !admittedBeforeClose { await peer.release("temperature") }
            await peer.release("start")
            do { _ = try await start.value; XCTFail("A retired Start cannot publish success") }
            catch { /* Closed admission or lost already-sent reply. */ }
            let actions = await peer.actions()
            XCTAssertEqual(actions, admittedBeforeClose ? ["start"] : [], "Closing prevents every later REC admission")
        }
    }

    func testBridgeInvalidGatewayEnvelopeKeepsUnknownAfterAppliedREC() async throws {
        for fault in [RecordingBoundaryTransport.Fault.emptyGateway, .malformedGateway, .partialGateway] {
            let peer = RecordingBoundaryTransport(mode: .bridge, commandFault: fault)
            let client = try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", transport: peer)
            try await client.initialize()
            do { _ = try await client.startRecording(); XCTFail("Expected unknown REC result") }
            catch { XCTAssertEqual(error as? DesktopBridgeError, .recordingCommandUnconfirmed(starting: true)) }
            let physicalRecording = await peer.cameraRecording()
            let evidence = await client.recordingCommandEvidence
            XCTAssertTrue(physicalRecording)
            XCTAssertEqual(evidence, .unconfirmed(starting: true))
        }
    }

    func testBridgeReceivedCompleteRejectionClearsAttemptBeforePropagatingCancellation() async throws {
        let peer = RecordingBoundaryTransport(mode: .bridge, commandFault: .http)
        defer { Task { await peer.releaseAll() } }
        let client = try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", transport: peer)
        try await client.initialize()
        await peer.holdNext("start")
        let start = Task { try await client.startRecording() }
        try await waitForGate("start", peer: peer)
        start.cancel()
        await peer.release("start")
        do { _ = try await start.value; XCTFail("Expected cancellation propagation") }
        catch { XCTAssertTrue(error is CancellationError) }
        let evidence = await client.recordingCommandEvidence
        let physicalRecording = await peer.cameraRecording()
        let actions = await peer.actions()
        XCTAssertNil(evidence, "A received complete rejection must not become unknown because its caller cancelled")
        XCTAssertFalse(physicalRecording)
        XCTAssertEqual(actions, ["start"])
    }

    private func waitForGate(_ route: String, peer: RecordingBoundaryTransport) async throws {
        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: .seconds(5))
        repeat {
            if await peer.waiting(route) { return }
            await Task.yield()
        } while clock.now < deadline
        XCTFail("Expected recording boundary gate \(route)")
        throw URLError(.timedOut)
    }

    func testBridgeAckLikeErrorAtNon502IsRejectionIncludingCancelledCaller() async throws {
        for status in [401, 403, 500, 503] {
            for starting in [false, true] {
                for cancelled in [false, true] {
                    let action = starting ? "start" : "stop"
                    let peer = RecordingBoundaryTransport(mode: .bridge, commandFault: .acknowledgedReadback, acknowledgementStatus: status)
                    defer { Task { await peer.releaseAll() } }
                    let client = try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", transport: peer)
                    try await client.initialize()
                    await peer.holdNext(action)
                    let command = Task { starting ? try await client.startRecording() : try await client.stopRecording() }
                    try await waitForGate(action, peer: peer)
                    if cancelled { command.cancel() }
                    await peer.release(action)
                    do { _ = try await command.value; XCTFail("Expected rejection") }
                    catch {
                        if cancelled { XCTAssertTrue(error is CancellationError) }
                        else if case let DesktopBridgeError.http(code, _, _, _, _, _, _) = error { XCTAssertEqual(code, status) }
                        else { XCTFail("Non-502 ACK-like code cannot assert ACK: \(error)") }
                    }
                    let evidence = await client.recordingCommandEvidence
                    let actions = await peer.actions()
                    XCTAssertNil(evidence)
                    XCTAssertEqual(actions, [action])
                }
            }
        }
    }

    func testBridgeRecordingSuccessRequiresBooleanOrNullableRecordingField() async throws {
        let invalid = [#"{"recording":0}"#, #"{"recording":1}"#, #"{"recording":2}"#,
            #"{"recording":"false"}"#, #"{"recording":[]}"#, #"{"recording":{}}"#, "[]", "null", "0", #""false""#]
        for payload in invalid {
            for starting in [false, true] {
                let peer = RecordingBoundaryTransport(mode: .bridge, commandBody: payload)
                let client = try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", transport: peer)
                try await client.initialize()
                do {
                    _ = starting ? try await client.startRecording() : try await client.stopRecording()
                    XCTFail("Malformed REC response cannot be accepted: \(payload)")
                } catch { XCTAssertEqual(error as? DesktopBridgeError, .recordingCommandUnconfirmed(starting: starting)) }
                let evidence = await client.recordingCommandEvidence
                XCTAssertEqual(evidence, .unconfirmed(starting: starting))
            }
        }
        let controls: [(String, Bool?)] = [(#"{"recording":true}"#, true), (#"{"recording":false}"#, false),
            (#"{"recording":null}"#, nil), ("{}", nil)]
        for (payload, recording) in controls {
            for starting in [false, true] {
                let peer = RecordingBoundaryTransport(mode: .bridge, commandBody: payload)
                let client = try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", transport: peer)
                try await client.initialize()
                let status = starting ? try await client.startRecording() : try await client.stopRecording()
                XCTAssertEqual(status.recording, recording)
            }
        }
    }

    func testRecordingErrorProtocolStringsAreMatchedRawWithoutWhitespacePromotion() async throws {
        typealias Control = (fault: RecordingBoundaryTransport.Fault, code: String, feature: String, engine: String)
        let unknown = "RECORDING_COMMAND_UNCONFIRMED"
        let ack = "RECORDING_STATUS_READBACK_FAILED"
        let controls: [Control] = [
            (.unconfirmed, " " + unknown, "VIDEO_RECORDING", "ccapi"),
            (.unconfirmed, unknown + " ", "VIDEO_RECORDING", "ccapi"),
            (.unconfirmed, unknown, " VIDEO_RECORDING", "ccapi"),
            (.unconfirmed, unknown, "VIDEO_RECORDING ", "ccapi"),
            (.unconfirmed, unknown, "VIDEO_RECORDING", " ccapi"),
            (.unconfirmed, unknown, "VIDEO_RECORDING", "ccapi "),
            (.acknowledgedReadback, " " + ack, "VIDEO_RECORDING", "ccapi"),
            (.acknowledgedReadback, ack + " ", "VIDEO_RECORDING", "ccapi"),
        ]
        for control in controls {
            for starting in [false, true] {
                for cancelled in [false, true] {
                    let action = starting ? "start" : "stop"
                    let peer = RecordingBoundaryTransport(mode: .bridge, commandFault: control.fault,
                        unknownFeature: control.feature, unknownEngine: control.engine, wireCode: control.code)
                    defer { Task { await peer.releaseAll() } }
                    let client = try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", transport: peer)
                    try await client.initialize()
                    await peer.holdNext(action)
                    let command = Task { starting ? try await client.startRecording() : try await client.stopRecording() }
                    try await waitForGate(action, peer: peer)
                    if cancelled { command.cancel() }
                    await peer.release(action)
                    do { _ = try await command.value; XCTFail("Expected ordinary unrecognized HTTP error") }
                    catch {
                        if cancelled { XCTAssertTrue(error is CancellationError) }
                        else if case let DesktopBridgeError.http(_, _, _, code, _, _, _) = error { XCTAssertEqual(code, control.code) }
                        else { XCTFail("Whitespace-padded strings must not acquire typed REC evidence") }
                    }
                    let evidence = await client.recordingCommandEvidence
                    let actions = await peer.actions()
                    XCTAssertNil(evidence)
                    XCTAssertEqual(actions, [action])
                }
            }
        }
    }

    func testMalformedRecordingStatusNeverManufacturesFalseAndSimulatorPreservesItsPriorAck() async throws {
        for field in ["0", "1", "2", #""false""#, #""true""#, "[]", "{}"] {
            let payload = #"{"battery":{},"media":{},"exposure":{},"recording":FIELD}"#.replacingOccurrences(of: "FIELD", with: field)
            let bridgePeer = RecordingBoundaryTransport(mode: .bridge, readBody: payload)
            let bridge = try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", transport: bridgePeer)
            try await bridge.initialize()
            do { _ = try await bridge.status(); XCTFail("Malformed status must not become a recording observation") }
            catch { guard case DesktopBridgeError.invalidResponse = error else { return XCTFail("Unexpected status error: \(error)") } }
            for starting in [false, true] {
                let peer = RecordingBoundaryTransport(mode: .simulator, readBody: payload)
                let client = try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: .simulator, transport: peer)
                do {
                    _ = starting ? try await client.startRecording() : try await client.stopRecording()
                    XCTFail("The command ACK cannot turn malformed required status into false")
                } catch { XCTAssertEqual(error as? CCAPIError, .recordingStatusReadbackFailed(starting: starting)) }
                let evidence = await client.recordingCommandEvidence
                let actions = await peer.actions()
                XCTAssertEqual(evidence, .acknowledged(starting: starting))
                XCTAssertEqual(actions, [starting ? "start" : "stop"])
            }
        }
    }

    func testBooleanNullAndMissingRecordingStatusRemainValidForBridgeAndSimulator() async throws {
        let controls: [(String?, Bool?)] = [("true", true), ("false", false), ("null", nil), (nil, nil)]
        for (field, expected) in controls {
            let suffix = field.map { #", "recording":"# + $0 } ?? ""
            let payload = #"{"battery":{},"media":{},"exposure":{}"# + suffix + "}"
            for mode in [RecordingBoundaryTransport.Mode.bridge, .simulator] {
                let peer = RecordingBoundaryTransport(mode: mode, readBody: payload)
                let status: CameraStatus
                if mode == .bridge {
                    let client = try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", transport: peer)
                    try await client.initialize()
                    status = try await client.status()
                } else {
                    status = try await CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: .simulator, transport: peer).status()
                }
                XCTAssertEqual(status.recording, expected)
            }
        }
    }

    func testCanonicalOptionalReadFailuresRemainNegativeControls() async throws {
        for fault in [RecordingBoundaryTransport.Fault.http, .network, .malformed] {
            for starting in [true, false] {
                let peer = RecordingBoundaryTransport(mode: .canonical, readFault: fault)
                let client = try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: .camera, transport: peer)
                let status = starting ? try await client.startRecording() : try await client.stopRecording()
                // Command acknowledgement is not independent camera state.
                XCTAssertNil(status.recording)
                let actions = await peer.actions()
                XCTAssertEqual(actions, [starting ? "start" : "stop"])
                let reads = await peer.statusReadCount()
                XCTAssertGreaterThan(reads, 0)
            }
        }
    }

    func testPostAckCancellationKeepsItsExistingCancellationSemantics() async throws {
        for mode in [RecordingBoundaryTransport.Mode.simulator, .canonical] {
            let peer = RecordingBoundaryTransport(mode: mode, readFault: .cancelled)
            let client = try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: mode == .canonical ? .camera : .simulator, transport: peer)
            do {
                _ = try await client.startRecording()
                XCTFail("Expected cancellation to propagate after the ACK")
            } catch {
                XCTAssertTrue(error is CancellationError)
            }
            let evidence = await client.recordingCommandEvidence
            XCTAssertEqual(evidence, .acknowledged(starting: true), "An already received ACK remains separate from cancellation")
            let actions = await peer.actions()
            XCTAssertEqual(actions, ["start"])
        }
    }
}

/// Actual clients consume HTTP responses here. Only the Bridge's exact new code
/// asserts its server observed an ACK; a network failure never asserts that fact.
private actor RecordingBoundaryTransport: CameraHTTPTransport {
    enum Mode: Equatable, Sendable { case bridge, simulator, canonical }
    enum Fault: Equatable, Sendable { case http, network, malformed, cancelled, acknowledgedReadback, unconfirmed, emptyGateway, malformedGateway, partialGateway }
    let mode: Mode
    private var commandFault: Fault?
    private let wireCode: String?
    private let readBody: String?
    private let commandBody: String?
    private let acknowledgementStatus: Int
    private let advertiseTemperature: Bool
    private var heldRoutes = Set<String>()
    private var gates: [String: CheckedContinuation<Void, Never>] = [:]
    private let failPreflight: Bool
    private let unknownStatus: Int
    private let unknownFeature: String
    private let unknownEngine: String
    private var readFault: Fault?
    private var recording = false
    private var commands: [String] = []
    private var reads = 0

    init(mode: Mode, commandFault: Fault? = nil, readFault: Fault? = nil,
         unknownStatus: Int = 502, unknownFeature: String = "VIDEO_RECORDING", unknownEngine: String = "ccapi", failPreflight: Bool = false, advertiseTemperature: Bool = false, acknowledgementStatus: Int = 502, commandBody: String? = nil, wireCode: String? = nil, readBody: String? = nil) {
        self.wireCode = wireCode
        self.readBody = readBody
        self.acknowledgementStatus = acknowledgementStatus
        self.commandBody = commandBody
        self.advertiseTemperature = advertiseTemperature
        self.failPreflight = failPreflight
        self.unknownStatus = unknownStatus
        self.unknownFeature = unknownFeature
        self.unknownEngine = unknownEngine
        self.mode = mode
        self.commandFault = commandFault
        self.readFault = readFault
    }
    func holdNext(_ route: String) { heldRoutes.insert(route) }
    func waiting(_ route: String) -> Bool { gates[route] != nil }
    func release(_ route: String) { gates.removeValue(forKey: route)?.resume() }
    func releaseAll() {
        heldRoutes.removeAll()
        let pending = gates
        gates.removeAll()
        for gate in pending.values { gate.resume() }
    }
    private func suspendIfHeld(_ route: String) async {
        if heldRoutes.remove(route) != nil { await withCheckedContinuation { gates[route] = $0 } }
    }
    func actions() -> [String] { commands }
    func cameraRecording() -> Bool { recording }
    func setCommandFault(_ fault: Fault?) { commandFault = fault }
    func statusReadCount() -> Int { reads }
    func clearReadFault() { readFault = nil }

    func send(_ request: URLRequest) async throws -> CameraHTTPResponse {
        let path = request.url!.path
        let method = request.httpMethod ?? "GET"
        if failPreflight, method == "GET" { throw URLError(.networkConnectionLost) }
        if path == "/health" {
            return try json(["ok": true, "service": "open-eos-control-bridge", "version": "0.13.0"])
        }
        if path == "/v1/session", method == "POST" { return try json(["id": "synthetic", "engine": "ccapi"]) }
        if path == "/ccapi" {
            var operations: [[String: Any]] = [["path": "/shooting/control/recbutton", "post": true]]
            if advertiseTemperature { operations.append(["path": "/devicestatus/temperature", "get": true]) }
            return try json(["ver100": operations])
        }
        if path.hasSuffix("/devicestatus/temperature") {
            await suspendIfHeld("temperature")
            return try json(["status": "normal"])
        }
        if method == "POST" {
            let direction: String
            if mode == .canonical {
                let body = try XCTUnwrap(request.httpBody)
                let object = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: String])
                direction = try XCTUnwrap(object["action"])
                XCTAssertEqual(path, "/ccapi/ver100/shooting/control/recbutton")
            } else {
                direction = request.url!.lastPathComponent
                XCTAssertEqual(path, mode == .bridge ? "/v1/session/synthetic/recording/\(direction)" : "/ccapi/record/\(direction)")
            }
            commands.append(direction)
            // Independent camera state changes before the transport loses its reply.
            if commandFault != .http { recording = direction == "start" }
            await suspendIfHeld(direction)
            if let commandFault { return try fail(commandFault) }
            if let commandBody { return CameraHTTPResponse(statusCode: 200, body: Data(commandBody.utf8)) }
            return mode == .canonical ? CameraHTTPResponse(statusCode: 204) : try json(status())
        }
        guard method == "GET" else { throw URLError(.unsupportedURL) }
        reads += 1
        if let readFault { return try fail(readFault) }
        if let readBody { return CameraHTTPResponse(statusCode: 200, body: Data(readBody.utf8)) }
        return try json(status())
    }

    func download(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse { throw URLError(.unsupportedURL) }

    private func fail(_ fault: Fault) throws -> CameraHTTPResponse {
        switch fault {
        case .network: throw URLError(.networkConnectionLost)
        case .cancelled: throw CancellationError()
        case .malformed: return CameraHTTPResponse(statusCode: 200, body: Data("not-json".utf8))
        case .emptyGateway: return CameraHTTPResponse(statusCode: 502)
        case .malformedGateway: return CameraHTTPResponse(statusCode: 502, body: Data("broken-error-body".utf8))
        case .partialGateway: return try json(["error": ["code": "CCAPI_HTTP_ERROR"]], statusCode: 502)
        case .unconfirmed:
            return try json(["error": ["code": wireCode ?? "RECORDING_COMMAND_UNCONFIRMED", "message": "Synthetic lost camera response",
                "feature": unknownFeature, "engine": unknownEngine]], statusCode: unknownStatus)
        case .http, .acknowledgedReadback:
            return try json(["error": ["code": wireCode ?? (fault == .acknowledgedReadback ? "RECORDING_STATUS_READBACK_FAILED" : "COMMAND_REJECTED"), "message": "Synthetic failure"]], statusCode: fault == .acknowledgedReadback ? acknowledgementStatus : 502)
        }
    }
    private func status() -> [String: Any] {
        ["connected": true, "recording": recording, "mode": "Manual", "battery": ["level": 82, "status": "good"],
         "media": ["available": true], "exposure": ["iso": "100", "shutter": "1/100", "aperture": "4.0", "white_balance": "auto", "whiteBalance": "auto"]]
    }
    private func json(_ object: [String: Any], statusCode: Int = 200) throws -> CameraHTTPResponse {
        CameraHTTPResponse(statusCode: statusCode, body: try JSONSerialization.data(withJSONObject: object))
    }
}
