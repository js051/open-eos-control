import Foundation
import OpenEOSCore
import XCTest

@testable import OpenEOSControl

/// Production CCAPI clients over a controlled wire peer, with no real camera or socket.
@MainActor
final class ShortShutterRecoveryStateTests: XCTestCase {
    func testCaptureAndFocusStayMutuallyBusyUntilTheOwnedShortControlSettles() async throws {
        for control in [ShortShutterControl.manualStill, .manualHalfPress, .manualAutofocus,
                        .simulatorHalfPress, .simulatorAutofocus] {
            let peer = ShortShutterPeer(control: control)
            let state = makeState([peer])
            defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
            try await connect(state)
            await peer.plan("press", .normal, gate: "owned-press")
            let owner = Task { await perform(control, state: state) }
            try await waitForGate("owned-press", peer: peer)
            XCTAssertTrue(state.isBusy(.capture), "The shutter UI must show the same interlock enforced by admission")
            XCTAssertTrue(state.isBusy(.focus))
            let before = await peer.mutations()
            await state.captureStill()
            await state.autofocus()
            await state.halfPressShutter()
            let after = await peer.mutations()
            XCTAssertEqual(after, before, "No second capture/focus command may enter Core while this press is pending")
            XCTAssertNil(state.lastError, "A disabled action must not create a spurious rejected-command alert")

            // An ordinary status refresh can still observe the pending stop. The
            // original command's eventual stop ACK must clear this transient UI.
            await state.refresh()
            XCTAssertTrue(state.shutterReleaseRequired)
            XCTAssertTrue(state.shutterReleaseUnconfirmed)
            await peer.release("owned-press")
            await owner.value
            XCTAssertEqual(state.shutterReleaseState, .idle)
            XCTAssertFalse(state.isBusy(.capture))
            XCTAssertFalse(state.isBusy(.focus))
            XCTAssertNil(state.lastError)
            let settled = await peer.commands()
            XCTAssertEqual(settled.map(\.action), [control.pressAction, control.stopAction])
            await perform(control, state: state)
            let next = await peer.commands()
            XCTAssertEqual(next.map(\.action), [control.pressAction, control.stopAction, control.pressAction, control.stopAction])
            await state.disconnect()
        }
    }

    func testExplicitStopBypassesNormalFocusAdmissionAndOwnsLateCancellationCleanup() async throws {
        for control in [ShortShutterControl.simulatorHalfPress, .simulatorAutofocus] {
            let peer = ShortShutterPeer(control: control)
            let state = makeState([peer])
            defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
            try await connect(state)
            await peer.plan("press", .normal, gate: "press")
            let focus = Task { await perform(control, state: state) }
            try await waitForGate("press", peer: peer)
            await state.refresh()
            XCTAssertTrue(state.busyOperations.contains(.focus))
            XCTAssertTrue(state.isBusy(.capture))
            XCTAssertTrue(state.canRetryShutterRelease, "Safe Stop remains available while normal shutter/focus admission is blocked")
            await peer.plan("stop", .normal, gate: "stop")
            await peer.plan("status", .normal, gate: "stop-status")
            let retry = Task { await state.retryShutterRelease() }
            try await waitUntil { state.busyOperations.contains(.capture) }
            let before = await peer.commands()
            XCTAssertEqual(before.map(\.action), [control.pressAction], "Stop must not overtake an in-flight press")
            await peer.release("press")
            try await waitForGate("stop", peer: peer)
            XCTAssertFalse(state.canRetryShutterRelease)
            await peer.release("stop")
            try await waitForGate("stop-status", peer: peer)
            await focus.value
            XCTAssertEqual(state.shutterReleaseState, .idle)
            XCTAssertTrue(state.busyOperations.contains(.capture))
            XCTAssertNil(state.lastError, "The cancelled focus handler cannot replace the explicit Stop owner's result")
            await peer.release("stop-status")
            await retry.value
            XCTAssertFalse(state.isBusy(.capture))
            XCTAssertFalse(state.isBusy(.focus))
            XCTAssertFalse(state.shutterFlash)
            let commands = await peer.commands()
            XCTAssertEqual(commands.map(\.action), [control.pressAction, control.stopAction])
            await state.disconnect()
        }
    }

    func testEveryShortControlRetainsExactStopOnlyRecoveryThroughRepeatedFailureThenACK() async throws {
        for control in ShortShutterControl.allCases {
            for method in (control == .autofocus || control.simulator ? ["POST"] : ["POST", "PUT"]) {
                let peer = ShortShutterPeer(control: control, method: method)
                let state = makeState([peer])
                defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
                try await connect(state)
                await peer.plan("stop", .lost)
                await perform(control, state: state)
                assertStopOnly(state)
                XCTAssertNotNil(state.lastError)
                XCTAssertFalse(state.shutterFlash)
                XCTAssertEqual(state.captureReviewState, .idle)
                let first = await peer.commands()
                let mutations = await peer.mutations()
                XCTAssertEqual(first.map(\.action), [control.pressAction, control.stopAction])

                state.clearError()
                await state.refresh()
                assertStopOnly(state)
                await state.captureStill()
                await state.autofocus()
                await state.halfPressShutter()
                await state.setSetting(key: "iso", value: "200")
                state.captureMode = .video
                await state.toggleRecording()
                let blocked = await peer.commands()
                let blockedMutations = await peer.mutations()
                XCTAssertEqual(blocked, first, "Unconfirmed short release must lock all new presses and camera mutations")
                XCTAssertEqual(blockedMutations, mutations)

                await peer.plan("stop", .lost)
                await state.retryShutterRelease()
                assertStopOnly(state)
                // The same recovery entry remains stop-only even after mode drift.
                await state.toggleBulbExposure()
                XCTAssertEqual(state.shutterReleaseState, .idle)
                XCTAssertFalse(state.shutterReleaseUnconfirmed, "\(control) \(method): the stop ACK clears the App's authoritative warning")
                if control.simulator {
                    // This peer deliberately omits the optional status flag. A
                    // later sparse read remains unknown on the wire; it cannot
                    // revoke the independently acknowledged stop above.
                    XCTAssertNil(state.status?.shutterReleaseUnconfirmed, "\(control) \(method): preserve the sparse Simulator status contract")
                } else {
                    XCTAssertEqual(state.status?.shutterReleaseUnconfirmed, false, "\(control) \(method): canonical status includes local release authority")
                }
                XCTAssertFalse(state.canRetryShutterRelease)
                XCTAssertFalse(state.shutterFlash, "Releasing AF or a short press is not an acknowledged Bulb photograph")
                XCTAssertNil(state.bulbStartedAt)
                let recovered = await peer.commands()
                XCTAssertEqual(recovered.map(\.action), [control.pressAction] + Array(repeating: control.stopAction, count: 3))
                XCTAssertEqual(Set(recovered.dropFirst().map(\.path)), [peer.stopPath])
                XCTAssertEqual(Set(recovered.dropFirst().map(\.method)), [control.simulator ? "POST" : method])
                if control.usesManualShutter {
                    XCTAssertTrue(recovered.dropFirst().allSatisfy { $0.autofocus == false })
                }
                XCTAssertTrue(recovered.dropFirst().allSatisfy { !$0.cancelled })
                await state.retryShutterRelease()
                let repeated = await peer.commands()
                XCTAssertEqual(repeated, recovered, "An idle Stop must not send a fresh press or another release")
                await state.disconnect()
            }
        }
    }

    func testSuccessfulShortControlsClearResponsibilityAndKeepTheirExistingSuccessUI() async throws {
        for control in ShortShutterControl.allCases {
            let peer = ShortShutterPeer(control: control)
            let state = makeState([peer])
            defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
            try await connect(state)
            await perform(control, state: state)
            XCTAssertEqual(state.shutterReleaseState, .idle)
            XCTAssertNil(state.lastError)
            XCTAssertFalse(state.bulbExposureActive)
            XCTAssertNil(state.bulbStartedAt)
            XCTAssertTrue(state.busyOperations.isEmpty)
            if control == .manualStill { XCTAssertTrue(state.shutterFlash) }
            else { XCTAssertEqual(state.focusMarker, FocusMarker(x: 0.5, y: 0.5, accepted: true)) }
            let commands = await peer.commands()
            XCTAssertEqual(commands.map(\.action), [control.pressAction, control.stopAction])
            await state.disconnect()
        }
    }

    func testCancelledShortPressStillPublishesItsFailedStopAndExplicitRetryIsUncancelled() async throws {
        for control in ShortShutterControl.allCases {
            let peer = ShortShutterPeer(control: control)
            let state = makeState([peer])
            defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
            try await connect(state)
            await peer.plan("press", .normal, gate: "cancelled-press")
            await peer.plan("stop", .lost)
            let task = Task { await perform(control, state: state) }
            try await waitForGate("cancelled-press", peer: peer)
            task.cancel()
            await peer.release("cancelled-press")
            await task.value
            assertStopOnly(state)
            XCTAssertNotNil(state.lastError)
            await state.retryShutterRelease()
            XCTAssertEqual(state.shutterReleaseState, .idle)
            let commands = await peer.commands()
            XCTAssertEqual(commands.map(\.action), [control.pressAction, control.stopAction, control.stopAction])
            XCTAssertTrue(commands.dropFirst().allSatisfy { !$0.cancelled })
            await state.disconnect()
        }
    }

    func testShortReleaseACKStaysIdleWhenItsSubsequentRequiredStatusReadFails() async throws {
        for control in [ShortShutterControl.simulatorHalfPress, .simulatorAutofocus] {
            let peer = ShortShutterPeer(control: control)
            let state = makeState([peer])
            defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
            try await connect(state)
            await peer.plan("stop", .lost)
            await perform(control, state: state)
            assertStopOnly(state)
            await peer.plan("status", .lost)
            await state.retryShutterRelease()
            XCTAssertEqual(state.shutterReleaseState, .idle, "The stop ACK is authoritative even when its later read fails")
            XCTAssertEqual(state.status?.shutterReleaseUnconfirmed, false)
            XCTAssertFalse(state.canRetryShutterRelease)
            XCTAssertFalse(state.shutterFlash)
            XCTAssertNotNil(state.lastError)
            let commands = await peer.commands()
            XCTAssertEqual(commands.map(\.action), [control.pressAction, control.stopAction, control.stopAction])
            await state.disconnect()
        }
    }

    func testRetiredShortStatusCannotOverwriteReplacementReleaseWarningOrBusyOwner() async throws {
        for control in [ShortShutterControl.simulatorHalfPress, .simulatorAutofocus] {
            for outcome in [ShortShutterPeer.Outcome.normal, .lost] {
                let oldPeer = ShortShutterPeer(control: control, owner: "old")
                let newPeer = ShortShutterPeer(control: control, owner: "new")
                let state = makeState([oldPeer, newPeer])
                defer { state.requestDisconnect(); Task { await oldPeer.releaseAll(); await newPeer.releaseAll() } }
                try await connect(state)
                await oldPeer.plan("status", outcome, gate: "retired-status")
                let old = Task { await perform(control, state: state) }
                try await waitForGate("retired-status", peer: oldPeer)
                await state.disconnect()
                try await connect(state)
                await newPeer.plan("stop", .lost)
                await perform(control, state: state)
                assertStopOnly(state)
                await newPeer.plan("stop", .normal, gate: "current-stop")
                let retry = Task { await state.retryShutterRelease() }
                try await waitForGate("current-stop", peer: newPeer)
                let snapshot = state.snapshot
                let release = state.shutterReleaseState
                state.reportCubeLutRenderFailure()
                let error = state.lastError
                await oldPeer.release("retired-status")
                await old.value
                XCTAssertEqual(state.snapshot, snapshot)
                XCTAssertEqual(state.shutterReleaseState, release)
                XCTAssertEqual(state.lastError, error)
                XCTAssertTrue(state.isBusy(.capture), "The old focus defer cannot finish a replacement Stop")
                XCTAssertFalse(state.canRetryShutterRelease)
                await newPeer.release("current-stop")
                await retry.value
                XCTAssertEqual(state.shutterReleaseState, .idle)
                let oldCommands = await oldPeer.commands()
                let newCommands = await newPeer.commands()
                XCTAssertEqual(oldCommands.map(\.action), [control.pressAction, control.stopAction])
                XCTAssertEqual(newCommands.map(\.action), [control.pressAction, control.stopAction, control.stopAction])
                await state.disconnect()
            }
        }
    }

    private func assertStopOnly(_ state: CameraAppState, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertTrue(state.shutterReleaseRequired, file: file, line: line)
        XCTAssertTrue(state.shutterReleaseUnconfirmed, file: file, line: line)
        XCTAssertNil(state.shutterReleaseState.bulbExposureActive, file: file, line: line)
        XCTAssertFalse(state.bulbExposureActive, file: file, line: line)
        XCTAssertNil(state.bulbStartedAt, file: file, line: line)
        XCTAssertTrue(state.canRetryShutterRelease, file: file, line: line)
        XCTAssertFalse(state.canChangeShutterAutofocus, file: file, line: line)
        XCTAssertFalse(state.canRetryCaptureMediaReview, file: file, line: line)
        XCTAssertTrue(state.busyOperations.isEmpty, file: file, line: line)
    }

    private func perform(_ control: ShortShutterControl, state: CameraAppState) async {
        switch control {
        case .manualStill: await state.captureStill()
        case .manualHalfPress, .simulatorHalfPress: await state.halfPressShutter()
        case .autofocus, .manualAutofocus, .simulatorAutofocus: await state.autofocus()
        }
    }

    private func makeState(_ peers: [ShortShutterPeer]) -> CameraAppState {
        let defaults = UserDefaults(suiteName: "ShortShutterRecoveryStateTests.\(UUID().uuidString)")!
        var index = 0
        let state = CameraAppState(defaults: defaults, sessionFactory: {
            let peer = peers[min(index, peers.count - 1)]
            index += 1
            return .ccapi(try CCAPIClient(baseURL: "http://127.0.0.1:18080",
                mode: peer.control.simulator ? .simulator : .camera, transport: peer))
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

    private func waitForGate(_ gate: String, peer: ShortShutterPeer) async throws {
        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: .seconds(5))
        repeat {
            if await peer.waiting(gate) { return }
            await Task.yield()
        } while clock.now < deadline
        XCTFail("Expected short-control request gate \(gate)")
        throw URLError(.timedOut)
    }

    private func waitUntil(_ condition: @MainActor () -> Bool) async throws {
        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: .seconds(5))
        repeat {
            if condition() { return }
            await Task.yield()
        } while clock.now < deadline
        XCTFail("Expected short-control ownership state")
        throw URLError(.timedOut)
    }
}

private enum ShortShutterControl: CaseIterable, Sendable {
    case manualStill, manualHalfPress, autofocus, manualAutofocus, simulatorHalfPress, simulatorAutofocus
    var simulator: Bool { self == .simulatorHalfPress || self == .simulatorAutofocus }
    var usesManualShutter: Bool { !simulator && self != .autofocus }
    var pressAction: String { self == .manualStill ? "full_press" : self == .autofocus ? "start" : "half_press" }
    var stopAction: String { self == .autofocus ? "stop" : "release" }
}

private struct ShortShutterCommand: Equatable, Sendable {
    let path: String
    let method: String
    let action: String
    let autofocus: Bool?
    let cancelled: Bool
}

private actor ShortShutterPeer: CameraHTTPTransport {
    enum Outcome: Sendable { case normal, lost }
    private struct Plan { let outcome: Outcome; let gate: String? }
    nonisolated let control: ShortShutterControl
    nonisolated let stopPath: String
    private let method: String
    private let owner: String
    private let version = "ver130"
    private var plans: [String: [Plan]] = [:]
    private var gates: [String: CheckedContinuation<Void, Never>] = [:]
    private var recorded: [ShortShutterCommand] = []
    private var writes: [String] = []
    private var drained = false

    init(control: ShortShutterControl, method: String = "PUT", owner: String = "current") {
        self.control = control
        self.method = control == .autofocus ? "POST" : method
        self.owner = owner
        stopPath = control.simulator ? "/ccapi/shutter/release"
            : "/ccapi/ver130/shooting/control/\(control == .autofocus ? "af" : "shutterbutton/manual")"
    }
    func plan(_ route: String, _ outcome: Outcome, gate: String? = nil) {
        plans[route, default: []].append(Plan(outcome: outcome, gate: gate))
    }
    func commands() -> [ShortShutterCommand] { recorded }
    func mutations() -> [String] { writes }
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
        if method != "GET" { writes.append("\(method) \(path)") }
        let payload = request.httpBody.flatMap { try? JSONSerialization.jsonObject(with: $0) as? [String: Any] }
        let action = payload?["action"] as? String
            ?? (path.hasSuffix("/half-press") ? "half_press" : path.hasSuffix("/shutter/release") ? "release" : nil)
        let route: String
        if let action, method != "GET" {
            recorded.append(ShortShutterCommand(path: path, method: method, action: action,
                autofocus: payload?["af"] as? Bool, cancelled: Task.isCancelled))
            route = action == "release" || action == "stop" ? "stop" : "press"
        } else if path == "/ccapi/status" || path.hasSuffix("/devicestatus/batterylist") { route = "status" }
        else { route = path }
        let plan = plans[route]?.isEmpty == false ? plans[route]!.removeFirst() : Plan(outcome: .normal, gate: nil)
        if let gate = plan.gate, !drained { await withCheckedContinuation { gates[gate] = $0 } }
        if case .lost = plan.outcome { throw URLError(.networkConnectionLost) }
        if path == "/ccapi/events" { throw CancellationError() }
        if route == "press" || route == "stop" {
            return CameraHTTPResponse(statusCode: control.simulator ? 200 : 204, body: Data("{}".utf8))
        }
        let body: String
        switch path {
        case "/ccapi", "/ccapi/":
            body = """
            {"\(version)":[
              {"path":"/deviceinformation","get":true},
              {"path":"/shooting/control/\(control == .autofocus ? "af" : "shutterbutton/manual")","\(self.method.lowercased())":true},
              {"path":"/devicestatus/batterylist","get":true},
              {"path":"/devicestatus/storage","get":true},
              {"path":"/shooting/settings","get":true},
              {"path":"/shooting/settings/iso","put":true}
            ]}
            """
        case "/ccapi/\(version)/deviceinformation":
            body = #"{"productname":"Synthetic \#(owner) camera","serialnumber":"SYNTHETIC","version":"fixture"}"#
        case "/ccapi/\(version)/devicestatus/batterylist":
            body = #"{"batterylist":[{"level":90,"status":"normal"}]}"#
        case "/ccapi/\(version)/devicestatus/storage":
            body = #"{"storagelist":[{"name":"card1","status":"ready","freespace":1000000}]}"#
        case "/ccapi/\(version)/shooting/settings":
            body = #"{"iso":{"value":"100","ability":["100","200"]},"tv":{"value":"1/100","ability":["1/100"]}}"#
        case "/ccapi/info":
            body = #"{"connected":true,"model":"Synthetic \#(owner) camera","serial":"SYNTHETIC","api":"fixture"}"#
        case "/ccapi/status":
            body = #"{"connected":true,"mode":"Manual","recording":false,"battery":{},"media":{},"exposure":{}}"#
        case "/ccapi/capabilities": body = #"{"iso":["100","200"]}"#
        case "/ccapi/media": body = #"{"items":[]}"#
        case "/ccapi/liveview/stop": body = "{}"
        default: return CameraHTTPResponse(statusCode: 404, body: Data("{}".utf8))
        }
        return CameraHTTPResponse(statusCode: 200, body: Data(body.utf8))
    }
    func download(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse { throw URLError(.unsupportedURL) }
}
