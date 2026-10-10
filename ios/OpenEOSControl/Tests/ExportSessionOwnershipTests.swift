import CryptoKit
import Foundation
import OpenEOSCore
import XCTest

@testable import OpenEOSControl

/// Protocol fixtures only: no sockets, physical cameras, or system clipboard.
/// A capabilities reply intentionally ignores cancellation until its named gate
/// is released. Awaiting the exact export Task proves the late reply settled.
@MainActor
final class ExportSessionOwnershipTests: XCTestCase {
    func testRetiredPreparationCannotExportOrMixReplacementSessionIncludingSameModel() async throws {
        for (bridge, kind) in exportCases {
            for sameModel in [false, true] {
                for fails in [false, true] {
                    let modelA = "Synthetic export A"
                    let modelB = sameModel ? modelA : "Synthetic export B"
                    let oldPeer = ExportOwnerPeer(owner: "A", model: modelA)
                    let newPeer = ExportOwnerPeer(owner: "B", model: modelB)
                    try await withFixture([oldPeer, newPeer], bridge: bridge) { fixture in
                        let state = fixture.state
                        try await fixture.connect()
                        state.monitorSettings.histogramVisible = true
                        state.setOperatorConfirmation(.stillCapture, confirmed: true)
                        await oldPeer.holdCapabilities("old-export", fails: fails)
                        let old = fixture.start(kind)
                        try await waitForGate("old-export", peer: oldPeer)

                        await state.disconnect()
                        try await fixture.connect()
                        state.monitorSettings.histogramVisible = false
                        state.monitorSettings.waveformVisible = true
                        state.reportCubeLutRenderFailure()
                        let replacement = try XCTUnwrap(state.snapshot)
                        let replacementError = state.lastError
                        let readsBeforeRelease = await newPeer.capabilityReadCount()
                        XCTAssertEqual(replacement.info.serial, "SYNTHETIC-B")
                        XCTAssertTrue(state.operatorConfirmedFeatures.isEmpty)

                        await oldPeer.release("old-export")
                        assertCancelled(await old.result)

                        XCTAssertEqual(state.snapshot, replacement)
                        XCTAssertEqual(state.lastError, replacementError)
                        XCTAssertFalse(state.monitorSettings.histogramVisible)
                        XCTAssertTrue(state.monitorSettings.waveformVisible)
                        XCTAssertTrue(state.operatorConfirmedFeatures.isEmpty)
                        XCTAssertTrue(state.busyOperations.isEmpty)
                        let readsAfterRelease = await newPeer.capabilityReadCount()
                        XCTAssertEqual(readsAfterRelease, readsBeforeRelease,
                                       "A's export must not query B to finish its report")
                        let fresh = try await fixture.start(kind).value
                        let sink = ExportPublicationSink()
                        XCTAssertTrue(sink.publish(fresh, state: state))
                        XCTAssertEqual(sink.text, fresh.text)
                        XCTAssertTrue(sink.copied)
                    }
                }
            }
        }
    }

    func testOfflinePreviewRetiresAnExportWaitingForCapabilities() async throws {
        for (bridge, kind) in exportCases {
            let peer = ExportOwnerPeer(owner: "A")
            try await withFixture([peer], bridge: bridge) { fixture in
                try await fixture.connect()
                await peer.holdCapabilities("export")
                let pending = fixture.start(kind)
                try await waitForGate("export", peer: peer)

                fixture.state.openOfflinePreview()
                let preview = fixture.state.snapshot
                await peer.release("export")
                assertCancelled(await pending.result)

                XCTAssertTrue(fixture.state.isPreview)
                XCTAssertEqual(fixture.state.snapshot, preview)
                XCTAssertEqual(fixture.state.physicalValidation.sessionStatus, .offlinePreview)
                let diagnostic = try await fixture.start(.diagnostic).value
                XCTAssertTrue(fixture.state.canPublishExport(diagnostic))
                // Offline preview deliberately reuses this marker as its serial.
                // The existing sanitizer redacts every occurrence, including the
                // API-version field; export ownership must preserve that privacy.
                XCTAssertFalse(diagnostic.text.contains("offline-preview"))
                if !bridge {
                    XCTAssertTrue(diagnostic.text.contains("apiVersions=[redacted]"))
                }
                XCTAssertTrue(diagnostic.text.contains("mediaItemCount=3"))
            }
        }
    }

    func testCompletedExportIsRejectedAtPublicationAfterDisconnectAndSameModelReconnect() async throws {
        for (bridge, kind) in exportCases {
            let peers = [ExportOwnerPeer(owner: "A"), ExportOwnerPeer(owner: "B")]
            try await withFixture(peers, bridge: bridge) { fixture in
                let state = fixture.state
                try await fixture.connect()
                let completed = try await fixture.start(kind).value
                XCTAssertTrue(state.canPublishExport(completed))
                let sink = ExportPublicationSink()

                // Retirement is synchronous: there is deliberately no await
                // between requestDisconnect and the publication check/write.
                state.requestDisconnect()
                XCTAssertFalse(state.canPublishExport(completed))
                XCTAssertFalse(sink.publish(completed, state: state))
                XCTAssertEqual(sink.text, "untouched")
                XCTAssertFalse(sink.copied)

                await state.disconnect()
                try await fixture.connect()
                XCTAssertEqual(state.info?.serial, "SYNTHETIC-B")
                XCTAssertFalse(sink.publish(completed, state: state))
                XCTAssertEqual(sink.text, "untouched")
                XCTAssertFalse(sink.copied)
                let replacement = try await fixture.start(kind).value
                XCTAssertTrue(sink.publish(replacement, state: state))
                XCTAssertEqual(sink.text, replacement.text)
                XCTAssertEqual(sink.writeCount, 1)
                XCTAssertTrue(sink.copied)
            }
        }
    }

    func testCurrentDiagnosticUsesMonitoringCapturedBeforeCapabilitiesAwait() async throws {
        for bridge in [false, true] {
            for fails in [false, true] {
                let peer = ExportOwnerPeer(owner: "current")
                try await withFixture([peer], bridge: bridge) { fixture in
                    let state = fixture.state
                    try await fixture.connect()
                    state.monitorSettings = initialMonitoring
                    await peer.holdCapabilities("diagnostic", fails: fails)
                    let pending = fixture.start(.diagnostic)
                    try await waitForGate("diagnostic", peer: peer)
                    state.monitorSettings = LiveViewMonitorSettings()
                    await peer.release("diagnostic")
                    let report = try await pending.value

                    XCTAssertTrue(state.canPublishExport(report))
                    XCTAssertTrue(report.text.contains("camera=Synthetic export owner"))
                    for line in initialMonitoringLines {
                        XCTAssertTrue(report.text.components(separatedBy: "\n").contains(line), line)
                    }
                    XCTAssertEqual(state.monitorSettings, LiveViewMonitorSettings())
                    let next = try await fixture.start(.diagnostic).value
                    XCTAssertTrue(next.text.contains("monitorHistogram=false"))
                    XCTAssertTrue(next.text.contains("monitorFrameGuide=off"))
                }
            }
        }
    }

    func testCurrentPhysicalRecordKeepsCapturedInfoEvidenceTransportAndDiagnosticHash() async throws {
        let peer = ExportOwnerPeer(owner: "current")
        try await withFixture([peer], bridge: true) { fixture in
            let state = fixture.state
            try await fixture.connect()
            state.monitorSettings = initialMonitoring
            state.setOperatorConfirmation(.stillCapture, confirmed: true)
            XCTAssertEqual(state.physicalValidation.sessionStatus, .ready)
            XCTAssertEqual(state.operatorConfirmedFeatures, [.stillCapture])
            let capturedDiagnostic = try await fixture.start(.diagnostic).value.text
            await peer.holdCapabilities("physical")
            let pending = fixture.start(.physical)
            try await waitForGate("physical", peer: peer)

            // Update the same live session while export is suspended. The held
            // response retains its original evidence, while refresh publishes
            // a different model and evidence into CameraAppState.
            state.monitorSettings = LiveViewMonitorSettings()
            state.setOperatorConfirmation(.stillCapture, confirmed: false)
            await peer.advanceEvidence()
            await state.refresh()
            state.setOperatorConfirmation(.cameraClockSync, confirmed: true)
            state.connectionMode = .ccapi
            XCTAssertEqual(state.info?.model, "Synthetic refreshed owner")
            XCTAssertEqual(state.operatorConfirmedFeatures, [.cameraClockSync])
            await peer.release("physical")
            let record = try await pending.value

            XCTAssertTrue(state.canPublishExport(record))
            XCTAssertTrue(record.text.contains("- Camera model: Synthetic export owner"))
            XCTAssertTrue(record.text.contains("- Transport: DESKTOP_BRIDGE"))
            XCTAssertTrue(record.text.contains("| STILL_CAPTURE | true | true | true |"))
            XCTAssertTrue(record.text.contains("| CAMERA_CLOCK_SYNC | true | false | false |"))
            XCTAssertFalse(record.text.contains("Synthetic refreshed owner"))
            XCTAssertFalse(record.text.contains("SYNTHETIC-current"))
            XCTAssertFalse(record.text.contains("127.0.0.1"))
            XCTAssertEqual(state.info?.model, "Synthetic refreshed owner")
            XCTAssertEqual(state.operatorConfirmedFeatures, [.cameraClockSync])

            // Only generation time can differ from the preceding diagnostic.
            // Read that exported field instead of depending on wall-clock timing.
            let generatedAt = try recordField("- Generated at: ", in: record.text)
            let expectedDiagnostic = capturedDiagnostic.components(separatedBy: "\n").map {
                $0.hasPrefix("generatedAt=") ? "generatedAt=\(generatedAt)" : $0
            }.joined(separator: "\n")
            let digest = SHA256.hash(data: Data(expectedDiagnostic.utf8))
                .map { String(format: "%02x", $0) }.joined()
            XCTAssertTrue(record.text.contains("- Diagnostic SHA-256: `\(digest)`"),
                          "The record hash must bind the original diagnostic and monitoring snapshot")
        }
    }

    func testCancellationDuringPreparationRejectsLateSuccessAndFallback() async throws {
        for (bridge, kind) in exportCases {
            for fails in [false, true] {
                let peer = ExportOwnerPeer(owner: "current")
                try await withFixture([peer], bridge: bridge) { fixture in
                    try await fixture.connect()
                    let snapshot = fixture.state.snapshot
                    await peer.holdCapabilities("cancelled", fails: fails)
                    let pending = fixture.start(kind)
                    try await waitForGate("cancelled", peer: peer)
                    pending.cancel()
                    await peer.release("cancelled")
                    assertCancelled(await pending.result)

                    XCTAssertEqual(fixture.state.snapshot, snapshot)
                    XCTAssertTrue(fixture.state.connected)
                    let fresh = try await fixture.start(kind).value
                    XCTAssertTrue(fixture.state.canPublishExport(fresh))
                }
            }
        }
    }

    func testAlreadyCancelledCallerCannotPrepareOrPublishCurrentExport() async throws {
        for (bridge, kind) in exportCases {
            let peer = ExportOwnerPeer(owner: "current")
            try await withFixture([peer], bridge: bridge) { fixture in
                try await fixture.connect()
                let completed = try await fixture.start(kind).value
                let reads = await peer.capabilityReadCount()
                let pending = fixture.start(kind)
                // Both creation and cancellation execute on this main actor
                // before the new task can begin its export.
                pending.cancel()
                assertCancelled(await pending.result)
                let readsAfterCancellation = await peer.capabilityReadCount()
                XCTAssertEqual(readsAfterCancellation, reads)

                let sink = ExportPublicationSink()
                let publication = Task { sink.publish(completed, state: fixture.state) }
                publication.cancel()
                let published = await publication.value
                XCTAssertFalse(published)
                XCTAssertEqual(sink.text, "untouched")
                XCTAssertFalse(sink.copied)
                XCTAssertEqual(sink.writeCount, 0)
                XCTAssertTrue(fixture.state.canPublishExport(completed),
                              "Cancelling one caller must not retire the current camera")
            }
        }
    }

    func testDisconnectedAndConnectingTicketsRetireAcrossConnectFailureAndReplacement() async throws {
        for bridge in [false, true] {
            for fails in [false, true] {
                let oldPeer = ExportOwnerPeer(owner: "A")
                let newPeer = ExportOwnerPeer(owner: "B")
                try await withFixture([oldPeer, newPeer], bridge: bridge) { fixture in
                    let state = fixture.state
                    let disconnected = try await fixture.start(.diagnostic).value
                    await oldPeer.holdCapabilities("connecting", fails: fails)
                    let connection = fixture.startConnection()
                    try await waitForGate("connecting", peer: oldPeer)
                    XCTAssertFalse(state.canPublishExport(disconnected))
                    let connecting = try await fixture.start(.diagnostic).value
                    XCTAssertTrue(state.canPublishExport(connecting))
                    await oldPeer.release("connecting")
                    await connection.value
                    XCTAssertEqual(state.connected, !fails)
                    // The same A may retain a point-in-time connecting report.
                    // A failed attempt must retire it when its session is cleared.
                    XCTAssertEqual(state.canPublishExport(connecting), !fails)
                    await state.disconnect()
                    let between = try await fixture.start(.diagnostic).value
                    try await fixture.connect()
                    XCTAssertEqual(state.info?.serial, "SYNTHETIC-B")
                    let sink = ExportPublicationSink()
                    for retired in [disconnected, connecting, between] {
                        XCTAssertFalse(sink.publish(retired, state: state))
                    }
                    XCTAssertEqual(sink.text, "untouched")
                    XCTAssertFalse(sink.copied)
                    XCTAssertEqual(sink.writeCount, 0)
                    let fresh = try await fixture.start(.diagnostic).value
                    XCTAssertTrue(sink.publish(fresh, state: state))
                }
            }
        }
    }

    func testPhysicalExportStillRejectsDisconnectedSimulatorAndOfflinePreview() async throws {
        for bridge in [false, true] {
            let peer = ExportOwnerPeer(owner: "simulator", simulated: true)
            try await withFixture([peer], bridge: bridge) { fixture in
                let state = fixture.state
                XCTAssertEqual(state.physicalValidation.sessionStatus, .disconnected)
                assertPhysicalCameraRequired(await fixture.start(.physical).result)
                let disconnected = try await fixture.start(.diagnostic).value
                XCTAssertTrue(state.canPublishExport(disconnected))

                try await fixture.connect()
                XCTAssertEqual(state.physicalValidation.sessionStatus, .simulator)
                XCTAssertFalse(state.canPublishExport(disconnected))
                let reads = await peer.capabilityReadCount()
                assertPhysicalCameraRequired(await fixture.start(.physical).result)
                let readsAfterRejection = await peer.capabilityReadCount()
                XCTAssertEqual(readsAfterRejection, reads,
                               "Ineligible physical evidence must fail before a diagnostic request")
                let simulated = try await fixture.start(.diagnostic).value
                XCTAssertTrue(state.canPublishExport(simulated))

                state.openOfflinePreview()
                XCTAssertFalse(state.canPublishExport(simulated))
                XCTAssertEqual(state.physicalValidation.sessionStatus, .offlinePreview)
                assertPhysicalCameraRequired(await fixture.start(.physical).result)
                await state.disconnect()
                XCTAssertEqual(state.physicalValidation.sessionStatus, .disconnected)
                assertPhysicalCameraRequired(await fixture.start(.physical).result)
            }
        }
    }

    private var exportCases: [(Bool, ExportOwnerKind)] {
        [(false, .diagnostic), (true, .diagnostic), (true, .physical)]
    }

    private var initialMonitoring: LiveViewMonitorSettings {
        LiveViewMonitorSettings(histogramVisible: true, waveformVisible: true,
                                zebraThresholdPercent: 95, falseColorEnabled: true,
                                focusPeakingEnabled: true, frameGuide: .ratio2x39,
                                safeAreaVisible: true, desqueeze: .x1_5)
    }

    private var initialMonitoringLines: [String] {
        ["monitorHistogram=true", "monitorWaveform=true", "monitorZebra=95",
         "monitorFalseColor=true", "monitorFocusPeaking=true", "monitorFrameGuide=ratio2x39",
         "monitorSafeArea=true", "monitorDesqueeze=x1_5"]
    }

    private func recordField(_ prefix: String, in record: String) throws -> String {
        let line = try XCTUnwrap(record.components(separatedBy: "\n").first { $0.hasPrefix(prefix) })
        return String(line.dropFirst(prefix.count))
    }

    private func assertCancelled(_ result: Result<CameraSessionExport, Error>,
                                 file: StaticString = #filePath, line: UInt = #line) {
        switch result {
        case .success: XCTFail("A retired or cancelled preparation must not return an export", file: file, line: line)
        case let .failure(error): XCTAssertTrue(error is CancellationError, "\(error)", file: file, line: line)
        }
    }

    private func assertPhysicalCameraRequired(_ result: Result<CameraSessionExport, Error>,
                                              file: StaticString = #filePath, line: UInt = #line) {
        switch result {
        case let .failure(error):
            guard let validationError = error as? PhysicalValidationRecord.ValidationError else {
                XCTFail("Expected physicalCameraRequired, got \(error)", file: file, line: line)
                return
            }
            switch validationError {
            case .physicalCameraRequired: break
            }
        case .success: XCTFail("Ineligible session exported physical evidence", file: file, line: line)
        }
    }

    private func waitForGate(_ gate: String, peer: ExportOwnerPeer) async throws {
        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: .seconds(5))
        repeat {
            if await peer.waiting(gate) { return }
            await Task.yield()
        } while clock.now < deadline
        XCTFail("Expected capabilities request did not enter gate \(gate)")
        throw URLError(.timedOut)
    }

    private func withFixture(_ peers: [ExportOwnerPeer], bridge: Bool,
                             body: (ExportOwnerFixture) async throws -> Void) async throws {
        let fixture = ExportOwnerFixture(peers: peers, bridge: bridge)
        do {
            try await body(fixture)
        } catch {
            await fixture.cleanup()
            throw error
        }
        await fixture.cleanup()
    }
}

private enum ExportOwnerKind {
    case diagnostic, physical
}

@MainActor
private final class ExportPublicationSink {
    private(set) var text = "untouched"
    private(set) var copied = false
    private(set) var writeCount = 0

    func publish(_ export: CameraSessionExport, state: CameraAppState) -> Bool {
        guard state.canPublishExport(export) else { return false }
        text = export.text
        copied = true
        writeCount += 1
        return true
    }
}

@MainActor
private final class ExportOwnerFixture {
    let state: CameraAppState
    private let peers: [ExportOwnerPeer]
    private let defaults: UserDefaults
    private let suite = "ExportSessionOwnershipTests.\(UUID().uuidString)"
    private var exports: [Task<CameraSessionExport, Error>] = []
    private var connections: [Task<Void, Never>] = []

    init(peers: [ExportOwnerPeer], bridge: Bool) {
        self.peers = peers
        defaults = UserDefaults(suiteName: suite)!
        var remaining = peers
        state = CameraAppState(defaults: defaults, sessionFactory: {
            let peer = remaining.removeFirst()
            return bridge
                ? .desktopBridge(try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", transport: peer))
                : .ccapi(try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: .simulator, transport: peer))
        })
        state.connectionMode = bridge ? .desktopBridge : .ccapi
        state.autoRefresh = false
    }

    func connect() async throws {
        await state.connect()
        XCTAssertTrue(state.connected)
        _ = try XCTUnwrap(state.snapshot)
        await state.latestMediaTask?.value
        XCTAssertTrue(state.busyOperations.isEmpty)
    }

    func start(_ kind: ExportOwnerKind) -> Task<CameraSessionExport, Error> {
        let task = Task {
            switch kind {
            case .diagnostic: return try await state.diagnosticReport()
            case .physical: return try await state.physicalValidationRecord()
            }
        }
        exports.append(task)
        return task
    }

    func startConnection() -> Task<Void, Never> {
        let task = Task { await state.connect() }
        connections.append(task)
        return task
    }

    func cleanup() async {
        connections.forEach { $0.cancel() }
        exports.forEach { $0.cancel() }
        for peer in peers { await peer.releaseAll() }
        for export in exports { _ = await export.result }
        for connection in connections { await connection.value }
        await state.disconnect()
        defaults.removePersistentDomain(forName: suite)
    }
}

/// The non-simulator branch supplies physical-shaped evidence solely to exercise
/// export formatting and ownership. It is never physical-device validation.
private actor ExportOwnerPeer: CameraHTTPTransport {
    private struct Plan {
        let gate: String
        let fails: Bool
    }

    private let owner: String
    private var model: String
    private let simulated: Bool
    private var observed: [CameraFeature] = [.stillCapture]
    private var plans: [Plan] = []
    private var gates: [String: CheckedContinuation<Void, Never>] = [:]
    private var capabilityReads = 0
    private var drained = false

    init(owner: String, model: String = "Synthetic export owner", simulated: Bool = false) {
        self.owner = owner
        self.model = model
        self.simulated = simulated
    }

    func holdCapabilities(_ gate: String, fails: Bool = false) {
        plans.append(Plan(gate: gate, fails: fails))
    }

    func waiting(_ gate: String) -> Bool { gates[gate] != nil }
    func capabilityReadCount() -> Int { capabilityReads }
    func release(_ gate: String) { gates.removeValue(forKey: gate)?.resume() }
    func releaseAll() {
        drained = true
        plans.removeAll()
        let pending = gates
        gates.removeAll()
        for continuation in pending.values { continuation.resume() }
    }

    func advanceEvidence() {
        model = "Synthetic refreshed owner"
        observed = [.cameraClockSync]
    }

    func send(_ request: URLRequest) async throws -> CameraHTTPResponse {
        let path = request.url!.path
        let method = request.httpMethod ?? "GET"
        let direct = path.hasPrefix("/ccapi/")
        let prefix = direct ? "/ccapi" : "/v1/session/\(owner)"
        let object: [String: Any]
        var plan: Plan?
        if path == "/health", method == "GET" {
            object = ["ok": true, "service": "open-eos-control-bridge", "version": "0.13.0"]
        } else if path == "/v1/session", method == "POST" {
            object = ["id": owner, "engine": "ccapi"]
        } else if path == prefix, method == "DELETE" {
            object = [:]
        } else if path == prefix + "/info", method == "GET" {
            object = ["connected": true, "model": model, "serial": "SYNTHETIC-\(owner)",
                      "api": simulated ? "simulated-export-fixture" : "export-fixture"]
        } else if path == prefix + "/status", method == "GET" {
            object = ["connected": true, "recording": false, "mode": "Synthetic \(owner)",
                      "battery": ["level": 73], "media": ["available": true],
                      "exposure": ["iso": "100", "shutter": "1/100", "aperture": "4.0"]]
        } else if path == prefix + "/capabilities", method == "GET" {
            capabilityReads += 1
            plan = plans.isEmpty ? nil : plans.removeFirst()
            object = direct ? [:] : [
                "supported": [CameraFeature.stillCapture.rawValue, CameraFeature.cameraClockSync.rawValue],
                "evidence": ["source": simulated ? "simulated export fixture" : "synthetic export fixture",
                             "observedFeatures": observed.map(\.rawValue)],
            ]
        } else if path == prefix + "/media", method == "GET" {
            object = ["items": []]
        } else if path == prefix + "/events", method == "GET" {
            throw CancellationError() // End the simulator's unused event task.
        } else if path == prefix + "/events", method == "DELETE" {
            object = [:]
        } else if path == prefix + "/liveview/stop", method == "POST" {
            object = [:]
        } else {
            throw URLError(.unsupportedURL)
        }
        // Capture the response before suspension, including its original owner.
        let body = try JSONSerialization.data(withJSONObject: object)
        if let plan {
            if !drained { await withCheckedContinuation { gates[plan.gate] = $0 } }
            if plan.fails { throw URLError(.networkConnectionLost) }
        }
        return CameraHTTPResponse(statusCode: 200, body: body)
    }

    func download(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse {
        throw URLError(.unsupportedURL)
    }
}
