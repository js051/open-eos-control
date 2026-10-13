import Foundation
import OpenEOSCore
import XCTest

@testable import OpenEOSControl

@MainActor
final class RecordingReadbackRecoveryTests: XCTestCase {
    func testAcknowledgedStartFailureKeepsObservedStatusAndNextExplicitActionOnlyStops() async throws {
        for bridge in [true, false] {
            let peer = RecordingRecoveryPeer()
            let state = makeState([peer], bridge: bridge)
            defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
            try await connect(state)
            await armReadbackFailure(peer, bridge: bridge, action: "start")
            await state.toggleRecording()
            XCTAssertEqual(state.status?.recording, false, "An ACK cannot overwrite the observed snapshot")
            XCTAssertFalse(state.recording)
            XCTAssertEqual(state.recordingReadbackRecovery?.starting, true)
            XCTAssertTrue(state.recordingStopRequired)
            XCTAssertEqual(state.captureMode, .video)
            await state.setCaptureMode(.photo)
            await state.captureStill()
            XCTAssertEqual(state.captureMode, .video)
            XCTAssertFalse(state.isBusy(.recording))
            await state.toggleRecording()
            XCTAssertNil(state.recordingReadbackRecovery)
            XCTAssertEqual(state.status?.recording, false)
            let actions = await peer.actions()
            XCTAssertEqual(actions, ["start", "stop"])
            let photos = await peer.count("capture")
            XCTAssertEqual(photos, 0)
        }
    }

    func testStopReadbackFailureRefreshPreservesOriginalVideoBoundaryWithoutAnotherREC() async throws {
        let peer = RecordingRecoveryPeer(recording: true, mediaSupported: true)
        await peer.setItems([.oldVideo, .oldPhoto])
        let state = makeState([peer])
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        try await connect(state)
        let initialReads = await peer.count("media")
        await peer.plan("stop", .acknowledgedReadback)
        await state.toggleRecording()
        XCTAssertEqual(state.status?.recording, true)
        XCTAssertEqual(state.recordingReadbackRecovery?.starting, false)
        XCTAssertEqual(state.captureReviewState, .idle)
        let readsBeforeRefresh = await peer.count("media")
        XCTAssertEqual(readsBeforeRefresh, initialReads, "No listing is authorized by an unconfirmed stop")
        await peer.setItems([.newPhoto, .oldVideo, .newVideo, .oldPhoto])
        await state.refresh()
        await state.latestMediaTask?.value
        XCTAssertNil(state.recordingReadbackRecovery)
        XCTAssertEqual(state.status?.recording, false)
        XCTAssertEqual(state.captureReviewKind, .video)
        XCTAssertEqual(state.captureReviewState, .available)
        XCTAssertEqual(state.latestMediaItem?.id, "NEW-VIDEO")
        let actions = await peer.actions()
        XCTAssertEqual(actions, ["stop"])
    }

    func testUnknownStopRemainsStopOnlyAndKnownFalseStartsReview() async throws {
        let peer = RecordingRecoveryPeer(recording: true, mediaSupported: true)
        await peer.setItems([.oldVideo])
        let state = makeState([peer])
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        try await connect(state)
        await peer.plan("stop", .status(nil))
        await state.toggleRecording()
        XCTAssertNil(state.status?.recording)
        XCTAssertEqual(state.recordingReadbackRecovery?.starting, false)
        let originalRecovery = state.recordingReadbackRecovery
        await peer.plan("status", .status(nil))
        await state.refresh()
        XCTAssertEqual(state.recordingReadbackRecovery, originalRecovery)
        await peer.plan("status", .lostResponse)
        await state.refresh()
        XCTAssertEqual(state.recordingReadbackRecovery, originalRecovery)
        await peer.setItems([.newPhoto, .oldVideo, .newVideo])
        await state.toggleRecording()
        await state.latestMediaTask?.value
        XCTAssertNil(state.recordingReadbackRecovery)
        XCTAssertEqual(state.latestMediaItem?.id, "NEW-VIDEO")
        let actions = await peer.actions()
        XCTAssertEqual(actions, ["stop", "stop"])
    }

    func testRejectedExplicitStopPreservesEarlierAcknowledgementAndVideoBoundary() async throws {
        let peer = RecordingRecoveryPeer(recording: true, mediaSupported: true)
        await peer.setItems([.oldVideo, .oldPhoto])
        let state = makeState([peer])
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        try await connect(state)
        await peer.plan("stop", .acknowledgedReadback)
        await state.toggleRecording()
        let recovery = try XCTUnwrap(state.recordingReadbackRecovery)
        await peer.plan("stop", .rejected)
        await state.toggleRecording()
        XCTAssertEqual(state.recordingReadbackRecovery, recovery)
        XCTAssertNotNil(state.lastError)
        await peer.setItems([.oldVideo, .newPhoto, .newVideo])
        await state.refresh()
        await state.latestMediaTask?.value
        XCTAssertNil(state.recordingReadbackRecovery)
        XCTAssertEqual(state.latestMediaItem?.id, "NEW-VIDEO")
        let actions = await peer.actions()
        XCTAssertEqual(actions, ["stop", "stop"])
    }

    func testFreshActiveReadResolvesStopUncertaintyWithoutStartingVideoReview() async throws {
        let peer = RecordingRecoveryPeer(recording: true, mediaSupported: true)
        let state = makeState([peer])
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        try await connect(state)
        await peer.plan("stop", .acknowledgedReadback)
        await state.toggleRecording()
        let before = await peer.count("media")
        await peer.plan("status", .status(true))
        await state.refresh()
        XCTAssertNil(state.recordingReadbackRecovery)
        XCTAssertTrue(state.recording)
        XCTAssertTrue(state.recordingStopRequired)
        XCTAssertEqual(state.captureReviewState, .idle)
        let after = await peer.count("media")
        XCTAssertEqual(after, before)
        let actions = await peer.actions()
        XCTAssertEqual(actions, ["stop"])
    }

    func testStartFalseWarnsWithoutPendingWhileNullStaysUnknown() async throws {
        for value in [false, nil] as [Bool?] {
            let peer = RecordingRecoveryPeer()
            let state = makeState([peer])
            defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
            try await connect(state)
            await peer.plan("start", .status(value))
            await state.toggleRecording()
            XCTAssertEqual(state.status?.recording, value)
            XCTAssertFalse(state.recording)
            if value == nil {
                XCTAssertEqual(state.recordingReadbackRecovery?.starting, true)
                XCTAssertTrue(state.recordingStopRequired)
            } else {
                XCTAssertNil(state.recordingReadbackRecovery)
                XCTAssertFalse(state.recordingStopRequired)
                XCTAssertNotNil(state.lastError)
            }
        }
    }

    func testFreshStartRecoveryReadIsReadOnlyAndNeverStartsStopMediaReview() async throws {
        for value in [true, false] {
            let peer = RecordingRecoveryPeer(mediaSupported: true)
            let state = makeState([peer])
            defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
            try await connect(state)
            await peer.plan("start", .acknowledgedReadback)
            await state.toggleRecording()
            let listsBefore = await peer.count("media")
            await peer.setItems([.newVideo])
            await peer.plan("status", .status(value))
            await state.refresh()
            XCTAssertNil(state.recordingReadbackRecovery)
            XCTAssertEqual(state.status?.recording, value)
            if !value { XCTAssertNotNil(state.lastError) }
            XCTAssertEqual(state.captureReviewState, .idle)
            let listsAfter = await peer.count("media")
            XCTAssertEqual(listsAfter, listsBefore)
            let actions = await peer.actions()
            XCTAssertEqual(actions, ["start"])
        }
    }

    func testRejectedLostAndCancelledCommandResponsesNeverBecomeAcknowledged() async throws {
        for outcome in [RecordingRecoveryPeer.Outcome.rejected, .lostResponse, .cancelled] {
            let peer = RecordingRecoveryPeer()
            let state = makeState([peer])
            defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
            try await connect(state)
            await peer.plan("start", outcome)
            await state.toggleRecording()
            XCTAssertNil(state.recordingReadbackRecovery)
            XCTAssertEqual(state.status?.recording, false)
            XCTAssertNotNil(state.lastError)
            let actions = await peer.actions()
            XCTAssertEqual(actions, ["start"], "No failure automatically replays the command")
        }
    }

    func testOldRefreshCannotClearNewAcknowledgedStartRecovery() async throws {
        let peer = RecordingRecoveryPeer()
        let state = makeState([peer])
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        try await connect(state)
        await peer.plan("status", .normal, gate: "old-refresh")
        let refresh = Task { await state.refresh() }
        try await waitForGate("old-refresh", peer: peer)
        await peer.plan("start", .acknowledgedReadback)
        await state.toggleRecording()
        let recovery = try XCTUnwrap(state.recordingReadbackRecovery)
        await peer.release("old-refresh")
        await refresh.value
        XCTAssertEqual(state.recordingReadbackRecovery, recovery)
        XCTAssertEqual(state.status?.recording, false)
        XCTAssertTrue(state.recordingStopRequired)
    }

    func testRefreshDuringRECDoesNotReadOrReplaceItsLaterResponse() async throws {
        for initiallyRecording in [true, false] {
            for outcome in [RecordingRecoveryPeer.Outcome.normal, .acknowledgedReadback] {
                let peer = RecordingRecoveryPeer(recording: initiallyRecording)
                let state = makeState([peer])
                defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
                try await connect(state)
                await peer.plan(initiallyRecording ? "stop" : "start", outcome, gate: "command")
                let command = Task { await state.toggleRecording() }
                try await waitForGate("command", peer: peer)
                let readsBefore = await peer.count("status")
                // Without the REC interlock, this old read starts DURING the
                // command and arrives after its newer success/recovery state.
                await peer.plan("status", .status(initiallyRecording), gate: "during-refresh")
                var refreshFinished = false
                let refresh = Task { await state.refresh(); refreshFinished = true }
                let clock = ContinuousClock()
                let deadline = clock.now.advanced(by: .seconds(5))
                while !refreshFinished, !(await peer.waiting("during-refresh")), clock.now < deadline {
                    await Task.yield()
                }
                let readEntered = await peer.waiting("during-refresh")
                guard refreshFinished || readEntered else {
                    XCTFail("The controlled refresh neither returned nor entered its gate")
                    throw URLError(.timedOut)
                }
                await peer.release("command")
                await command.value
                let currentSnapshot = state.snapshot
                let currentRecovery = state.recordingReadbackRecovery
                await peer.release("during-refresh")
                await refresh.value
                XCTAssertTrue(refreshFinished)
                let readsAfter = await peer.count("status")
                XCTAssertEqual(readsAfter, readsBefore, "An in-flight REC must prevent a new status request")
                XCTAssertEqual(state.snapshot, currentSnapshot)
                XCTAssertEqual(state.recordingReadbackRecovery, currentRecovery)
                let actions = await peer.actions()
                XCTAssertEqual(actions, [initiallyRecording ? "stop" : "start"])
            }
        }
    }

    func testRecoveryRefreshOnlyReadsStatusWithoutCapabilitiesOrLiveViewWrites() async throws {
        for initiallyRecording in [true, false] {
            let peer = RecordingRecoveryPeer(recording: initiallyRecording)
            let state = makeState([peer])
            defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
            try await connect(state)
            await peer.plan(initiallyRecording ? "stop" : "start", .acknowledgedReadback)
            await state.toggleRecording()
            let before = await peer.requestRoutes()
            await peer.restrictMovieStart()
            await state.refresh()
            let after = await peer.requestRoutes()
            XCTAssertEqual(Array(after.dropFirst(before.count)), ["status"])
            XCTAssertNil(state.recordingReadbackRecovery)
            XCTAssertEqual(state.status?.recording, !initiallyRecording)
            XCTAssertTrue(state.supports(.videoRecording), "Recovery must not fetch changed capabilities or clamp FPS")
        }
    }

    func testEventRecoveryOnlyReadsStatusWithoutCapabilitiesOrLiveViewWrites() async throws {
        let peer = RecordingRecoveryPeer(events: true)
        await peer.plan("events", .normal, gate: "event")
        let state = makeState([peer])
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        try await connect(state)
        try await waitForGate("event", peer: peer)
        await peer.plan("start", .acknowledgedReadback)
        await state.toggleRecording()
        let before = await peer.requestRoutes()
        await peer.restrictMovieStart()
        await peer.release("event")
        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: .seconds(5))
        while state.recordingReadbackRecovery != nil, clock.now < deadline { await Task.yield() }
        XCTAssertNil(state.recordingReadbackRecovery)
        XCTAssertTrue(state.recording)
        let after = await peer.requestRoutes()
        XCTAssertEqual(Array(after.dropFirst(before.count)).filter { $0 != "events" }, ["status"])
        XCTAssertTrue(state.supports(.videoRecording))
    }

    func testRefreshForEarlierPendingCommandCannotClearReplacementStopRecovery() async throws {
        let peer = RecordingRecoveryPeer()
        let state = makeState([peer])
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        try await connect(state)
        await peer.plan("start", .acknowledgedReadback)
        await state.toggleRecording()
        let earlier = try XCTUnwrap(state.recordingReadbackRecovery)
        await peer.plan("status", .status(false), gate: "earlier-refresh")
        let refresh = Task { await state.refresh() }
        try await waitForGate("earlier-refresh", peer: peer)
        await peer.plan("stop", .acknowledgedReadback)
        await state.toggleRecording()
        let replacement = try XCTUnwrap(state.recordingReadbackRecovery)
        XCTAssertNotEqual(earlier.commandID, replacement.commandID)
        XCTAssertFalse(replacement.starting)
        await peer.release("earlier-refresh")
        await refresh.value
        XCTAssertEqual(state.recordingReadbackRecovery, replacement)
        let actions = await peer.actions()
        XCTAssertEqual(actions, ["start", "stop"])
    }

    func testRetiredSuccessAndAcknowledgedFailureCannotOwnNewSessionRecovery() async throws {
        for initiallyRecording in [true, false] {
            for outcome in [RecordingRecoveryPeer.Outcome.normal, .acknowledgedReadback] {
                let oldPeer = RecordingRecoveryPeer(owner: "old", recording: initiallyRecording)
                let newPeer = RecordingRecoveryPeer(owner: "new")
                let state = makeState([oldPeer, newPeer])
                defer { state.requestDisconnect(); Task { await oldPeer.releaseAll(); await newPeer.releaseAll() } }
                try await connect(state)
                await oldPeer.plan(initiallyRecording ? "stop" : "start", outcome, gate: "retired")
                let retired = Task { await state.toggleRecording() }
                try await waitForGate("retired", peer: oldPeer)
                await state.disconnect()
                XCTAssertNil(state.recordingReadbackRecovery)
                try await connect(state)
                await newPeer.plan("start", .acknowledgedReadback)
                await state.toggleRecording()
                let replacement = try XCTUnwrap(state.recordingReadbackRecovery)
                let snapshot = state.snapshot
                state.reportCubeLutRenderFailure()
                let currentError = state.lastError
                await oldPeer.release("retired")
                await retired.value
                XCTAssertEqual(state.recordingReadbackRecovery, replacement)
                XCTAssertEqual(state.snapshot, snapshot)
                XCTAssertEqual(state.lastError, currentError)
                XCTAssertFalse(state.isBusy(.recording))
                let actions = await newPeer.actions()
                XCTAssertEqual(actions, ["start"])
            }
        }
    }

    func testCallerCancellationPreservesBridgeCancellationContractWithoutInventingAck() async throws {
        let peer = RecordingRecoveryPeer()
        let state = makeState([peer])
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        try await connect(state)
        await peer.plan("start", .acknowledgedReadback, gate: "acknowledged")
        let command = Task { await state.toggleRecording() }
        try await waitForGate("acknowledged", peer: peer)
        command.cancel()
        await peer.release("acknowledged")
        await command.value
        // The existing Bridge client checks cancellation before consuming a
        // scoped response. The App therefore has no typed ACK evidence here.
        XCTAssertNil(state.recordingReadbackRecovery)
        XCTAssertNotNil(state.lastError)
        XCTAssertEqual(state.status?.recording, false)
        XCTAssertFalse(state.isBusy(.recording))
        let actions = await peer.actions()
        XCTAssertEqual(actions, ["start"])
    }

    func testAlreadyCancelledCallerSendsNoREC() async throws {
        let peer = RecordingRecoveryPeer()
        let state = makeState([peer])
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        try await connect(state)
        let command = Task {
            withUnsafeCurrentTask { $0?.cancel() }
            await state.toggleRecording()
        }
        await command.value
        let actions = await peer.actions()
        XCTAssertEqual(actions, [])
        XCTAssertNil(state.recordingReadbackRecovery)
        XCTAssertFalse(state.isBusy(.recording))
    }

    func testPendingStopRemainsAvailableWhenMovieStartIsRestricted() async throws {
        let peer = RecordingRecoveryPeer(recording: true, movieRestricted: true)
        let state = makeState([peer])
        defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
        try await connect(state)
        await peer.plan("stop", .acknowledgedReadback)
        await state.toggleRecording()
        await peer.plan("status", .status(nil))
        await state.refresh()
        XCTAssertFalse(state.supports(.videoRecording))
        XCTAssertFalse(state.movieRecordingTemperatureAllowed)
        XCTAssertTrue(state.recordingStopRequired)
        await state.toggleRecording()
        let actions = await peer.actions()
        XCTAssertEqual(actions, ["stop", "stop"])
        XCTAssertNil(state.recordingReadbackRecovery)
    }

    private func makeState(_ peers: [RecordingRecoveryPeer], bridge: Bool = true) -> CameraAppState {
        var remaining = peers
        let suite = "RecordingReadbackRecoveryTests.\(UUID().uuidString)"
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

    private func connect(_ state: CameraAppState) async throws {
        await state.connect()
        XCTAssertTrue(state.connected)
        _ = try XCTUnwrap(state.snapshot)
        await state.latestMediaTask?.value
        XCTAssertTrue(state.busyOperations.isEmpty)
    }

    private func armReadbackFailure(_ peer: RecordingRecoveryPeer, bridge: Bool, action: String) async {
        if bridge { await peer.plan(action, .acknowledgedReadback) }
        else { await peer.plan("status", .lostResponse) }
    }

    private func waitForGate(_ name: String, peer: RecordingRecoveryPeer) async throws {
        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: .seconds(5))
        repeat {
            if await peer.waiting(name) { return }
            await Task.yield()
        } while clock.now < deadline
        XCTFail("Expected transport gate \(name)")
        throw URLError(.timedOut)
    }
}

private struct RecordingRecoveryItem: Sendable {
    let id: String
    let video: Bool
    let date: String
    static let oldVideo = Self(id: "OLD-VIDEO", video: true, date: "2035-01-01T00:00:00Z")
    static let oldPhoto = Self(id: "OLD-PHOTO", video: false, date: "2034-01-01T00:00:00Z")
    static let newPhoto = Self(id: "NEW-PHOTO", video: false, date: "2040-01-01T00:00:00Z")
    static let newVideo = Self(id: "NEW-VIDEO", video: true, date: "2020-01-01T00:00:00Z")
    var json: [String: Any] {
        ["id": id, "name": "\(id).\(video ? "MP4" : "JPG")", "kind": video ? "video" : "photo",
         "captureTime": date, "previewAvailable": false]
    }
}

/// The real Bridge/CCAPI clients run over a controlled HTTP transport. Gates
/// deliberately permit late replies, so correctness cannot depend on cancel().
private actor RecordingRecoveryPeer: CameraHTTPTransport {
    enum Outcome: Sendable {
        case normal, acknowledgedReadback, rejected, lostResponse, cancelled, status(Bool?)
    }
    private struct Plan { let outcome: Outcome; let gate: String? }
    private let owner: String
    private let mediaSupported: Bool
    private let events: Bool
    private var recording: Bool?
    private var movieRestricted = false
    private var items: [RecordingRecoveryItem] = []
    private var plans: [String: [Plan]] = [:]
    private var gates: [String: CheckedContinuation<Void, Never>] = [:]
    private var requests: [String] = []
    private var drained = false

    init(owner: String = "synthetic", recording: Bool = false, mediaSupported: Bool = false, movieRestricted: Bool = false, events: Bool = false) {
        self.owner = owner
        self.recording = recording
        self.mediaSupported = mediaSupported
        self.movieRestricted = movieRestricted
        self.events = events
    }
    func plan(_ route: String, _ outcome: Outcome, gate: String? = nil) {
        plans[route, default: []].append(Plan(outcome: outcome, gate: gate))
    }
    func setItems(_ values: [RecordingRecoveryItem]) { items = values }
    func restrictMovieStart() { movieRestricted = true }
    func actions() -> [String] { requests.filter { $0 == "start" || $0 == "stop" } }
    func requestRoutes() -> [String] { requests }
    func count(_ route: String) -> Int { requests.filter { $0 == route }.count }
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
        let method = request.httpMethod ?? "GET"
        let simulator = path.hasPrefix("/ccapi/")
        let route: String
        if path == "/health" { route = "health" }
        else if path == "/v1/session", method == "POST" { route = "open" }
        else if method == "DELETE" { route = "close" }
        else if path.hasSuffix("/capture/still") || path.hasSuffix("/shoot") { route = "capture" }
        else if path.hasSuffix("/liveview/stop") { route = "close" }
        else { route = request.url!.lastPathComponent }
        if route == "start" || route == "stop" {
            let expected = simulator ? "/ccapi/record/\(route)" : "/v1/session/\(owner)/recording/\(route)"
            guard method == "POST", path == expected else { throw URLError(.unsupportedURL) }
        }
        requests.append(route)
        let plan = plans[route]?.isEmpty == false ? plans[route]!.removeFirst() : Plan(outcome: .normal, gate: nil)
        var body: [String: Any] = [:]
        switch route {
        case "health": body = ["ok": true, "service": "open-eos-control-bridge", "version": "0.13.0"]
        case "open": body = ["id": owner, "engine": "ccapi"]
        case "info": body = ["connected": true, "model": "Synthetic recording camera", "serial": "SYNTHETIC", "api": "fixture"]
        case "status": body = status()
        case "capabilities":
            if simulator {
                body = ["iso": ["100"], "shutter": ["1/100"], "aperture": ["4.0"], "white_balance": ["auto"]]
            } else {
                var features: [CameraFeature] = [.stillCapture]
                if !movieRestricted { features.append(.videoRecording) }
                if mediaSupported { features.append(.mediaBrowser) }
                if events { features.append(.eventPolling) }
                body = ["supported": features.map(\.rawValue), "settings": []]
            }
        case "start", "stop":
            switch plan.outcome {
            case .normal, .acknowledgedReadback: recording = route == "start"
            default: break
            }
            body = status()
        case "media": body = ["items": items.map(\.json)]
        case "events":
            guard events, plan.gate != nil, !drained else { throw CancellationError() }
            body = ["changedKeys": ["recording"], "keys": ["recording"], "sequence": count("events")]
        case "close", "capture": break
        default: throw URLError(.unsupportedURL)
        }
        if case let .status(value) = plan.outcome {
            body = status()
            body["recording"] = value.map { $0 as Any } ?? NSNull()
        }
        // Capture the response before suspension to model a truly stale snapshot.
        let response = try JSONSerialization.data(withJSONObject: body)
        if let gate = plan.gate, !drained { await withCheckedContinuation { gates[gate] = $0 } }
        switch plan.outcome {
        case .lostResponse: throw URLError(.networkConnectionLost)
        case .cancelled: throw CancellationError()
        case .rejected, .acknowledgedReadback:
            let code: String
            if case .acknowledgedReadback = plan.outcome { code = "RECORDING_STATUS_READBACK_FAILED" }
            else { code = "COMMAND_REJECTED" }
            return CameraHTTPResponse(statusCode: 502, body: try JSONSerialization.data(withJSONObject: ["error": ["code": code, "message": "Synthetic failure"]]))
        case .normal, .status:
            return CameraHTTPResponse(statusCode: 200, body: response)
        }
    }
    func download(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse { throw URLError(.unsupportedURL) }

    private func status() -> [String: Any] {
        var body: [String: Any] = ["connected": true, "recording": recording.map { $0 as Any } ?? NSNull(), "mode": "Manual",
            "battery": ["level": 82, "status": "good"], "media": ["available": true],
            "exposure": ["iso": "100", "shutter": "1/100", "aperture": "4.0", "white_balance": "auto", "whiteBalance": "auto"]]
        if movieRestricted { body["temperature"] = "restrictionmovierecording" }
        return body
    }
}
