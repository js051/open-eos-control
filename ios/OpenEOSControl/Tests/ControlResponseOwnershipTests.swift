import Foundation
import OpenEOSCore
import XCTest

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
            (.directory, .capabilities), (.naming, .capabilities), (.setting, .capabilities),
            (.clean, .info), (.clean, .status), (.clean, .capabilities),
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
                    XCTAssertEqual(state.status?.mode, "current command")
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
                    XCTAssertFalse(state.connected)
                    XCTAssertNil(state.activeLiveViewSource)
                    await assertOriginalClose(peer, bridge: bridge)
                case .clean:
                    XCTAssertTrue(state.connected)
                    XCTAssertEqual(state.status?.mode, "current command")
                    XCTAssertNotNil(state.activeLiveViewSource)
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
                    // Existing recovery starts Live View and may clear its error.
                    XCTAssertNotNil(state.activeLiveViewSource)
                    if bridge {
                        let starts = await peer.count(.liveViewStart)
                        XCTAssertEqual(starts, 2, "The current owner must retain maintenance recovery")
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
            (.directory, .capabilities), (.naming, .capabilities), (.setting, .capabilities),
            (.clean, .info), (.clean, .status), (.clean, .capabilities),
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
                await peer.enqueue(boundary, error: .networkConnectionLost)
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

    func testCurrentOwnerMaintenanceRecoveryRestartsItsOwnEventLoop() async throws {
        let cases: [(ControlOwnerAction, Bool)] = [(.sleep, true), (.clean, false), (.clean, true), (.cleanAndPowerOff, true)]
        for bridge in [false, true] {
            for (action, fails) in cases {
                let peer = ControlOwnerPeer(owner: "current", events: true)
                let state = makeState([peer], bridge: bridge)
                defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
                await peer.enqueue(.events, gate: "original-event")
                try await connectAndPrepare(state)
                try await waitForGate("original-event", peer: peer)
                await peer.enqueue(.events, gate: "recovered-event")
                await peer.enqueue(.events, gate: "next-event")
                await peer.enqueue(action.wire(bridge: bridge), error: fails ? .networkConnectionLost : nil)
                await perform(action, state: state)
                try await waitForGate("recovered-event", peer: peer)
                XCTAssertTrue(state.connected)
                XCTAssertNotNil(state.activeLiveViewSource)
                XCTAssertFalse(state.isBusy(action.category))
                await peer.setMode("current recovery event")
                await peer.release("original-event")
                await peer.release("recovered-event")
                try await waitForGate("next-event", peer: peer)
                XCTAssertEqual(state.status?.mode, "current recovery event")
            }
        }
    }

    func testSensorRecoveryLiveViewCompletionCannotClearReplacementWarning() async throws {
        for fails in [false, true] {
            let oldPeer = ControlOwnerPeer(owner: "A")
            let newPeer = ControlOwnerPeer(owner: "B")
            let state = makeState([oldPeer, newPeer], bridge: true)
            defer { state.requestDisconnect(); Task { await oldPeer.releaseAll(); await newPeer.releaseAll() } }
            try await connectAndPrepare(state)
            await oldPeer.enqueue(.liveViewStart, gate: "old-recovery", error: fails ? .networkConnectionLost : nil)
            let old = Task { await state.cleanSensor(autoPowerOff: false) }
            try await waitForGate("old-recovery", peer: oldPeer)
            await state.disconnect()
            try await connectAndPrepare(state)
            state.reportCubeLutRenderFailure()
            let saved = PreservedControl(state)
            await oldPeer.release("old-recovery")
            await old.value

            assertPreserved(saved, state: state)
            XCTAssertTrue(state.busyOperations.isEmpty)
            let starts = await newPeer.count(.liveViewStart)
            XCTAssertEqual(starts, 1)
        }
    }

    func testRetiredCancelledAutofocusAndHalfPressStillReleaseOnlyOriginalCamera() async throws {
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
            await state.disconnect()
            try await connectAndPrepare(state)
            state.reportCubeLutRenderFailure()
            let saved = PreservedControl(state)
            await oldPeer.release("press")
            await old.value

            assertPreserved(saved, state: state)
            let oldReleases = await oldPeer.count(.shutterRelease)
            let newReleases = await newPeer.count(.shutterRelease)
            XCTAssertEqual(oldReleases, 1, "A's required safety release must survive retirement and cancellation")
            XCTAssertEqual(newReleases, 0, "Safety cleanup must never be redirected to B")
            XCTAssertTrue(state.busyOperations.isEmpty)
        }
    }

    private func assertRetired(_ action: ControlOwnerAction, bridge: Bool, fails: Bool,
                               readback: ControlOwnerWire? = nil) async throws {
        let oldPeer = ControlOwnerPeer(owner: "A", recording: action == .recordingStop)
        let newPeer = ControlOwnerPeer(owner: "B", recording: action == .recordingStop)
        let state = makeState([oldPeer, newPeer], bridge: bridge)
        defer { state.requestDisconnect(); Task { await oldPeer.releaseAll(); await newPeer.releaseAll() } }
        try await connectAndPrepare(state)
        await oldPeer.enqueue(action.wire(bridge: bridge), gate: "old-command",
                              error: readback == nil && fails ? .networkConnectionLost : nil)
        let old = Task { await perform(action, state: state) }
        try await waitForGate("old-command", peer: oldPeer)
        if let readback {
            // File naming performs its own capability preflight. Arm only after
            // the mutation has entered so this is the app's later readback.
            await oldPeer.enqueue(readback, gate: "old-readback", error: fails ? .networkConnectionLost : nil)
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

        await oldPeer.release(readback == nil ? "old-command" : "old-readback")
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
        case .setting: await state.setSetting(key: "iso", value: "800")
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
    case health, open, close, info, status, capabilities, events, stopEvents, media, liveViewStart
    case clock, directory, naming, sleep, clean, autofocus, halfPress, shutterRelease
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
        var object: [String: Any] = [:]
        switch wire {
        case .health:
            object = ["ok": true, "service": "open-eos-control-bridge", "version": "0.13.0"]
        case .open:
            object = ["id": owner, "engine": "ccapi"]
        case .info:
            object = ["connected": true, "model": "Synthetic control \(owner)", "serial": "SYNTHETIC-\(owner)", "api": "fixture"]
        case .status:
            object = statusJSON()
        case .capabilities:
            object = capabilitiesJSON(simulator: simulator)
        case .events:
            guard events, !drained, plan.gate != nil else { throw CancellationError() }
            object = ["changedKeys": ["mode"], "keys": ["mode"], "sequence": count(.events)]
        case .media:
            object = ["items": []]
        case .clock, .autofocus, .halfPress:
            mode = "\(owner) command"
            object = statusJSON()
        case .directory:
            directory = "101ABCDE"
            object = ["name": "ABCDE", "directoryname": "ABCDE"]
        case .naming:
            naming = "NEW_"
            object = namingJSON()
        case .clean:
            mode = "\(owner) command"
        case .recordingStart, .recordingStop:
            recording = wire == .recordingStart
            object = statusJSON()
        case .setting:
            iso = "800"
            object = statusJSON()
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
        case .close, .stopEvents, .liveViewStart, .sleep, .shutterRelease:
            break
        }
        if let gate = plan.gate, !drained { await withCheckedContinuation { gates[gate] = $0 } }
        if let error = plan.error { throw URLError(error) }
        return CameraHTTPResponse(statusCode: 200, body: try JSONSerialization.data(withJSONObject: object))
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
        case ("GET", "/status"): return .status
        case ("GET", "/capabilities"): return .capabilities
        case ("GET", "/events"): return .events
        case ("DELETE", "/events"): return .stopEvents
        case ("GET", "/media"): return .media
        case ("POST", "/liveview/start"): return .liveViewStart
        case ("POST", "/clock/sync"): return .clock
        case ("POST", "/directory"), ("POST", "/directories"): return .directory
        case ("PUT", "/file-naming/still-user-setting-1"): return .naming
        case ("POST", "/camera-sleep"), ("POST", "/power/sleep"): return .sleep
        case ("POST", "/sensor-cleaning"), ("POST", "/maintenance/sensor-cleaning"): return .clean
        case ("POST", "/focus/auto"): return .autofocus
        case ("POST", "/shutter/half-press"): return .halfPress
        case ("POST", "/shutter/release"): return .shutterRelease
        case ("POST", "/record/start"), ("POST", "/recording/start"): return .recordingStart
        case ("POST", "/record/stop"), ("POST", "/recording/stop"): return .recordingStop
        case ("POST", "/focus/tap"): return .tap
        case ("POST", "/whitebalance/click"): return .whiteBalance
        case ("POST", "/focus/drive"): return .drive
        case ("POST", "/liveview/magnification"): return .magnification
        case ("PATCH", "/exposure"), ("POST", "/settings/iso"): return .setting
        default: throw URLError(.unsupportedURL)
        }
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

    private func capabilitiesJSON(simulator: Bool) -> [String: Any] {
        let liveView: [String: Any] = ["sources": ["DESKTOP_BRIDGE_STREAM"], "defaultSource": "DESKTOP_BRIDGE_STREAM",
            "sizes": ["MEDIUM"], "defaultSize": "MEDIUM", "magnifications": [1, 5, 10],
            "currentMagnification": magnification, "minFps": 1, "maxFps": 12]
        if simulator {
            return ["iso": ["100", "800"], "shutter": ["1/100"], "aperture": ["4.0"], "white_balance": ["auto", "click"],
                    "directoryselection": ["value": directory, "ability": ["100EOSXX", "101ABCDE"]],
                    "autopoweroff": ["value": "60", "ability": ["60", "disable", "immediately"]],
                    "fileNaming": namingJSON(), "liveView": liveView]
        }
        var supported: [CameraFeature] = [.cameraClockSync, .directoryControl, .fileNamingControl, .cameraSleep,
            .sensorCleaning, .liveView, .liveViewMagnification, .autofocus, .shutterHalfPress, .videoRecording,
            .tapFocus, .clickWhiteBalance, .focusDrive, .exposureControl]
        if events { supported.append(.eventPolling) }
        return ["supported": supported.map(\.rawValue), "fileNaming": namingJSON(), "liveView": liveView,
                "settings": [["key": "iso", "value": iso, "values": ["100", "800"]],
                             ["key": "directoryselection", "value": directory, "values": ["100EOSXX", "101ABCDE"]]]]
    }
}
