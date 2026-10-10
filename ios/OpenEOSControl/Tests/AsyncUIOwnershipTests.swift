import Foundation
import OpenEOSCore
import XCTest

@testable import OpenEOSControl

/// Injected discovery, import, and expiry work never opens a socket or a file.
/// One default-loader control reads only its own small temporary .cube files.
/// Named gates deliberately ignore cancellation. Each assertion follows the
/// exact operation task's completion, and cleanup drains every held task.
@MainActor
final class AsyncUIOwnershipTests: XCTestCase {
    func testRetiredScanCannotPublishAfterDirectConfigurationChangesOrDisconnect() async throws {
        for change in AsyncUIScanChange.allCases {
            for fails in [false, true] {
                try await withFixture { fixture in
                    let state = fixture.state
                    if change == .disconnectWithEmptyToken { state.bridgeToken = "" }
                    let request = AsyncUIBridgeRequest(url: state.bridgeURL, token: state.bridgeToken)
                    await fixture.discovery.enqueue("old", outcome: scanOutcome(fails: fails, owner: "old"))
                    let old = fixture.scan()
                    try await waitForGate("old", in: fixture.discovery)

                    change.apply(to: state)
                    XCTAssertFalse(state.isBusy(.scan), "\(change) must retire scan admission synchronously")
                    state.reportCubeLutRenderFailure()
                    let saved = ScanPresentation(state)
                    await fixture.discovery.release("old")
                    await old.value

                    saved.assertPreserved(in: state)
                    XCTAssertTrue(state.bridgeCameras.isEmpty)
                    XCTAssertNil(state.selectedBridgeCameraID)
                    let requests = await fixture.discovery.requests()
                    XCTAssertEqual(requests, [request])
                }
            }
        }
    }

    func testRetiredScanCannotFinishReplacementScanOrOverwriteItsWarning() async throws {
        for change in AsyncUIScanChange.replacementCases {
            for fails in [false, true] {
                try await withFixture { fixture in
                    let state = fixture.state
                    if change == .disconnectWithEmptyToken { state.bridgeToken = "" }
                    await fixture.discovery.enqueue("old", outcome: scanOutcome(fails: fails, owner: "old"))
                    let old = fixture.scan()
                    try await waitForGate("old", in: fixture.discovery)
                    change.apply(to: state)
                    await fixture.discovery.enqueue("new", outcome: .success(cameras("new")))
                    let new = fixture.scan()
                    try await waitForGate("new", in: fixture.discovery)
                    XCTAssertTrue(state.isBusy(.scan))
                    state.reportCubeLutRenderFailure()
                    let saved = ScanPresentation(state)

                    await fixture.discovery.release("old")
                    await old.value

                    saved.assertPreserved(in: state)
                    XCTAssertTrue(state.isBusy(.scan), "Retired \(change) must not end the newer scan")
                    await fixture.discovery.release("new")
                    await new.value
                    XCTAssertEqual(state.bridgeCameras, cameras("new"))
                    XCTAssertEqual(state.selectedBridgeCameraID, "new-first")
                    XCTAssertNil(state.lastError)
                    XCTAssertFalse(state.isBusy(.scan))
                    let requests = await fixture.discovery.requests()
                    XCTAssertEqual(requests.count, 2)
                    XCTAssertEqual(requests.last, AsyncUIBridgeRequest(url: state.bridgeURL,
                                                                     token: state.bridgeToken))
                }
            }
        }
    }

    func testUnchangedConfigurationAndDuplicateScanKeepTheOriginalOwner() async throws {
        try await withFixture { fixture in
            let state = fixture.state
            await fixture.discovery.enqueue("current", outcome: .success(cameras("current")))
            let current = fixture.scan()
            try await waitForGate("current", in: fixture.discovery)

            let url = state.bridgeURL
            let token = state.bridgeToken
            let mode = state.connectionMode
            state.bridgeURL = url
            state.bridgeToken = token
            state.connectionMode = mode
            state.setBridgeURL(url)
            state.setConnectionMode(mode)
            let duplicate = fixture.scan()
            await duplicate.value

            XCTAssertTrue(state.isBusy(.scan))
            let requests = await fixture.discovery.requests()
            XCTAssertEqual(requests.count, 1)
            await fixture.discovery.release("current")
            await current.value
            XCTAssertEqual(state.bridgeCameras, cameras("current"))
            XCTAssertNil(state.lastError)
            XCTAssertFalse(state.isBusy(.scan))
        }
    }

    func testCurrentScanKeepsValidSelectionFallsBackAndClearsAnEmptyResult() async throws {
        try await withFixture { fixture in
            let state = fixture.state
            state.selectedBridgeCameraID = "current-second"
            state.reportCubeLutRenderFailure()
            try await completeScan("selected", cameras: cameras("current"), fixture: fixture)
            XCTAssertEqual(state.selectedBridgeCameraID, "current-second")
            XCTAssertEqual(state.bridgeCameras, cameras("current"))
            XCTAssertNil(state.lastError)
            XCTAssertFalse(state.isBusy(.scan))

            state.selectedBridgeCameraID = "missing"
            try await completeScan("fallback", cameras: cameras("current"), fixture: fixture)
            XCTAssertEqual(state.selectedBridgeCameraID, "current-first")
            try await completeScan("empty", cameras: [], fixture: fixture)
            XCTAssertTrue(state.bridgeCameras.isEmpty)
            XCTAssertNil(state.selectedBridgeCameraID)
            XCTAssertNil(state.lastError)
            XCTAssertFalse(state.isBusy(.scan))
        }
    }

    func testCurrentScanFailureClearsPriorResultsAndReportsTheRealError() async throws {
        try await withFixture { fixture in
            let state = fixture.state
            try await completeScan("seed", cameras: cameras("seed"), fixture: fixture)
            state.reportCubeLutRenderFailure()
            await fixture.discovery.enqueue("failure", outcome: .failure("current discovery failed"))
            let current = fixture.scan()
            try await waitForGate("failure", in: fixture.discovery)
            await fixture.discovery.release("failure")
            await current.value

            XCTAssertTrue(state.bridgeCameras.isEmpty)
            XCTAssertNil(state.selectedBridgeCameraID)
            XCTAssertEqual(state.lastError, "current discovery failed")
            XCTAssertFalse(state.isBusy(.scan))
        }
    }

    func testCanceledScanDiscardsLateSuccessFailureAndCancellationWithoutClearingPriorResults() async throws {
        let outcomes: [AsyncUIOutcome<[DesktopBridgeCamera]>] = [
            .success(cameras("canceled")), .failure("canceled discovery failed"), .cancelled,
        ]
        for outcome in outcomes {
            try await withFixture { fixture in
                let state = fixture.state
                try await completeScan("seed", cameras: cameras("seed"), fixture: fixture)
                state.reportCubeLutRenderFailure()
                let savedCameras = state.bridgeCameras
                let savedSelection = state.selectedBridgeCameraID
                let savedError = state.lastError
                await fixture.discovery.enqueue("canceled", outcome: outcome)
                let canceled = fixture.scan()
                try await waitForGate("canceled", in: fixture.discovery)
                canceled.cancel()
                await fixture.discovery.release("canceled")
                await canceled.value

                XCTAssertEqual(state.bridgeCameras, savedCameras)
                XCTAssertEqual(state.selectedBridgeCameraID, savedSelection)
                XCTAssertEqual(state.lastError, savedError)
                XCTAssertFalse(state.isBusy(.scan))
            }
        }
    }

    func testPreCanceledScanDoesNotEnterDiscoveryOrRetireAnActiveScan() async throws {
        for active in [false, true] {
            try await withFixture { fixture in
                let state = fixture.state
                var current: Task<Void, Never>?
                if active {
                    await fixture.discovery.enqueue("current", outcome: .success(cameras("current")))
                    current = fixture.scan()
                    try await waitForGate("current", in: fixture.discovery)
                }
                state.reportCubeLutRenderFailure()
                let saved = ScanPresentation(state)
                let canceled = fixture.scan(preCanceled: true)
                await canceled.value

                saved.assertPreserved(in: state)
                let requests = await fixture.discovery.requests()
                XCTAssertEqual(requests.count, active ? 1 : 0)
                if let current {
                    await fixture.discovery.release("current")
                    await current.value
                    XCTAssertEqual(state.bridgeCameras, cameras("current"))
                    XCTAssertFalse(state.isBusy(.scan))
                }
            }
        }
    }

    func testExpiredIdenticalMarkerCannotClearItsNewerReplacement() async throws {
        for disconnect in [false, true] {
            try await withFixture { fixture in
                let state = fixture.state
                state.openOfflinePreview()
                await fixture.delay.enqueue("old", outcome: .success(()))
                let old = try await fixture.focus()
                try await waitForGate("old", in: fixture.delay)
                let marker = try XCTUnwrap(state.focusMarker)
                if disconnect {
                    state.requestDisconnect()
                    XCTAssertNil(state.focusMarker)
                    state.openOfflinePreview()
                }
                await fixture.delay.enqueue("new", outcome: .success(()))
                let new = try await fixture.focus()
                try await waitForGate("new", in: fixture.delay)
                XCTAssertEqual(state.focusMarker, marker, "The replacement deliberately has the same value")

                await fixture.delay.release("old")
                await old.value
                XCTAssertEqual(state.focusMarker, marker)
                XCTAssertNotNil(state.focusMarkerExpiryTask)
                await fixture.delay.release("new")
                await new.value
                XCTAssertNil(state.focusMarker, "The current marker must still expire")
            }
        }
    }

    func testDisconnectClearsMarkerImmediatelyAndItsLateExpiryCannotChangeUI() async throws {
        try await withFixture { fixture in
            let state = fixture.state
            state.openOfflinePreview()
            await fixture.delay.enqueue("expiry", outcome: .success(()))
            let expiry = try await fixture.focus()
            try await waitForGate("expiry", in: fixture.delay)
            state.requestDisconnect()
            XCTAssertNil(state.focusMarker)
            state.reportCubeLutRenderFailure()
            let savedError = state.lastError
            await fixture.delay.release("expiry")
            await expiry.value

            XCTAssertNil(state.focusMarker)
            XCTAssertEqual(state.lastError, savedError)
            XCTAssertFalse(state.isPreview)
            XCTAssertTrue(state.busyOperations.isEmpty)
        }
    }

    func testCanceledMarkerDelayCannotExpireTheCurrentMarker() async throws {
        try await withFixture { fixture in
            let state = fixture.state
            state.openOfflinePreview()
            await fixture.delay.enqueue("expiry", outcome: .success(()))
            let expiry = try await fixture.focus()
            try await waitForGate("expiry", in: fixture.delay)
            let marker = try XCTUnwrap(state.focusMarker)
            expiry.cancel()
            await fixture.delay.release("expiry")
            await expiry.value

            XCTAssertEqual(state.focusMarker, marker)
        }
    }

    func testRetiredLutImportCannotOverwriteANewerImportOrItsWarning() async throws {
        for fails in [false, true] {
            for completeNewFirst in [false, true] {
                try await withFixture { fixture in
                    let state = fixture.state
                    let oldLut = lut("old")
                    let newLut = lut("new")
                    await fixture.importer.enqueue("old", outcome: fails ? .failure("old parse failed") : .success(oldLut))
                    let old = fixture.importLut("old")
                    try await waitForGate("old", in: fixture.importer)
                    await fixture.importer.enqueue("new", outcome: .success(newLut))
                    let new = fixture.importLut("new")
                    try await waitForGate("new", in: fixture.importer)
                    if completeNewFirst {
                        await fixture.importer.release("new")
                        await new.value
                        XCTAssertEqual(state.monitorSettings.cubeLut, newLut)
                    }
                    state.reportCubeLutRenderFailure()
                    let savedLut = state.monitorSettings.cubeLut
                    let savedError = state.lastError
                    await fixture.importer.release("old")
                    await old.value

                    XCTAssertEqual(state.monitorSettings.cubeLut, savedLut)
                    XCTAssertEqual(state.lastError, savedError)
                    if !completeNewFirst {
                        await fixture.importer.release("new")
                        await new.value
                        XCTAssertNil(state.lastError)
                    }
                    XCTAssertEqual(state.monitorSettings.cubeLut, newLut)
                    let requests = await fixture.importer.requests()
                    XCTAssertEqual(requests.map(\.lastPathComponent), ["old.cube", "new.cube"])
                }
            }
        }
    }

    func testClearLutRetiresPendingImportSuccessAndFailureWithoutClearingANewerWarning() async throws {
        for fails in [false, true] {
            try await withFixture { fixture in
                let state = fixture.state
                let seed = lut("seed")
                try await completeImport("seed", lut: seed, fixture: fixture)
                await fixture.importer.enqueue("old", outcome: fails ? .failure("old parse failed") : .success(lut("old")))
                let old = fixture.importLut("old")
                try await waitForGate("old", in: fixture.importer)

                state.clearCubeLut()
                XCTAssertNil(state.monitorSettings.cubeLut)
                state.reportCubeLutRenderFailure()
                let savedError = state.lastError
                await fixture.importer.release("old")
                await old.value

                XCTAssertNil(state.monitorSettings.cubeLut)
                XCTAssertEqual(state.lastError, savedError)
                let next = lut("next")
                try await completeImport("next", lut: next, fixture: fixture)
                XCTAssertEqual(state.monitorSettings.cubeLut, next)
                XCTAssertNil(state.lastError)
            }
        }
    }

    func testCurrentLutFailurePreservesTheAppliedLutAndReportsItsOwnError() async throws {
        try await withFixture { fixture in
            let state = fixture.state
            let seed = lut("seed")
            try await completeImport("seed", lut: seed, fixture: fixture)
            state.reportCubeLutRenderFailure()
            let previousError = state.lastError
            await fixture.importer.enqueue("failure", outcome: .failure("current parse failed"))
            let current = fixture.importLut("failure")
            try await waitForGate("failure", in: fixture.importer)
            await fixture.importer.release("failure")
            await current.value

            XCTAssertEqual(state.monitorSettings.cubeLut, seed)
            XCTAssertNotEqual(state.lastError, previousError)
            XCTAssertTrue(state.lastError?.contains("current parse failed") == true)
            XCTAssertTrue(state.busyOperations.isEmpty)
        }
    }

    func testNewPickerErrorRetiresAnOlderParseAndPreservesTheAppliedLut() async throws {
        for fails in [false, true] {
            try await withFixture { fixture in
                let state = fixture.state
                let seed = lut("seed")
                try await completeImport("seed", lut: seed, fixture: fixture)
                await fixture.importer.enqueue("old", outcome: fails ? .failure("old parse failed") : .success(lut("old")))
                let old = fixture.importLut("old")
                try await waitForGate("old", in: fixture.importer)

                state.reportCubeLutImportError(AsyncUIFailure(message: "new picker failed"))
                let pickerError = state.lastError
                XCTAssertTrue(pickerError?.contains("new picker failed") == true)
                XCTAssertEqual(state.monitorSettings.cubeLut, seed)
                await fixture.importer.release("old")
                await old.value

                XCTAssertEqual(state.monitorSettings.cubeLut, seed)
                XCTAssertEqual(state.lastError, pickerError)
            }
        }
    }

    func testCanceledLutImportDiscardsLateSuccessFailureAndCancellation() async throws {
        let outcomes: [AsyncUIOutcome<CubeLut>] = [
            .success(lut("canceled")), .failure("canceled parse failed"), .cancelled,
        ]
        for outcome in outcomes {
            try await withFixture { fixture in
                let state = fixture.state
                let seed = lut("seed")
                try await completeImport("seed", lut: seed, fixture: fixture)
                state.reportCubeLutRenderFailure()
                let savedError = state.lastError
                await fixture.importer.enqueue("canceled", outcome: outcome)
                let canceled = fixture.importLut("canceled")
                try await waitForGate("canceled", in: fixture.importer)
                canceled.cancel()
                await fixture.importer.release("canceled")
                await canceled.value

                XCTAssertEqual(state.monitorSettings.cubeLut, seed)
                XCTAssertEqual(state.lastError, savedError)
            }
        }
    }

    func testPreCanceledLutImportDoesNotReadOrRetireAnActiveImport() async throws {
        for active in [false, true] {
            try await withFixture { fixture in
                let state = fixture.state
                let currentLut = lut("current")
                var current: Task<Void, Never>?
                if active {
                    await fixture.importer.enqueue("current", outcome: .success(currentLut))
                    current = fixture.importLut("current")
                    try await waitForGate("current", in: fixture.importer)
                }
                state.reportCubeLutRenderFailure()
                let savedError = state.lastError
                let canceled = fixture.importLut("canceled", preCanceled: true)
                await canceled.value

                XCTAssertNil(state.monitorSettings.cubeLut)
                XCTAssertEqual(state.lastError, savedError)
                let requests = await fixture.importer.requests()
                XCTAssertEqual(requests.count, active ? 1 : 0)
                if let current {
                    await fixture.importer.release("current")
                    await current.value
                    XCTAssertEqual(state.monitorSettings.cubeLut, currentLut)
                    XCTAssertNil(state.lastError)
                }
            }
        }
    }

    func testDisconnectRetainsAppliedLutAndDoesNotRetireTheLatestImport() async throws {
        for fails in [false, true] {
            try await withFixture { fixture in
                let state = fixture.state
                state.openOfflinePreview()
                let seed = lut("seed")
                let latest = lut("latest")
                try await completeImport("seed", lut: seed, fixture: fixture)
                state.monitorSettings.histogramVisible = true
                await fixture.importer.enqueue("latest", outcome: fails ? .failure("latest parse failed") : .success(latest))
                let pending = fixture.importLut("latest")
                try await waitForGate("latest", in: fixture.importer)

                await state.disconnect()
                XCTAssertFalse(state.isPreview)
                XCTAssertEqual(state.monitorSettings.cubeLut, seed)
                XCTAssertTrue(state.monitorSettings.histogramVisible)
                await fixture.importer.release("latest")
                await pending.value

                XCTAssertEqual(state.monitorSettings.cubeLut, fails ? seed : latest)
                XCTAssertTrue(state.monitorSettings.histogramVisible)
                if fails {
                    XCTAssertTrue(state.lastError?.contains("latest parse failed") == true)
                } else {
                    XCTAssertNil(state.lastError)
                }
                await state.disconnect()
                XCTAssertEqual(state.monitorSettings.cubeLut, fails ? seed : latest)
            }
        }
    }

    func testDefaultLutLoaderImportsASmallFileAndRejectsInvalidUTF8() async throws {
        let suite = "AsyncUIOwnershipTests.default-loader.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("AsyncUIOwnershipTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer {
            defaults.removePersistentDomain(forName: suite)
            try? FileManager.default.removeItem(at: directory)
        }
        let valid = directory.appendingPathComponent("identity.cube")
        try Data("""
            LUT_3D_SIZE 2
            0 0 0
            1 0 0
            0 1 0
            1 1 0
            0 0 1
            1 0 1
            0 1 1
            1 1 1
            """.utf8).write(to: valid)
        let invalid = directory.appendingPathComponent("invalid.cube")
        try Data([0xC3, 0x28]).write(to: invalid)
        let state = CameraAppState(defaults: defaults)
        state.reportCubeLutRenderFailure()

        await state.importCubeLut(from: valid)
        let imported = try XCTUnwrap(state.monitorSettings.cubeLut)
        XCTAssertEqual(imported.name, "identity")
        XCTAssertEqual(imported.size, 2)
        XCTAssertEqual(imported.sample(red: 0.25, green: 0.5, blue: 0.75), [0.25, 0.5, 0.75])
        XCTAssertNil(state.lastError)

        await state.importCubeLut(from: invalid)
        XCTAssertEqual(state.monitorSettings.cubeLut, imported)
        XCTAssertTrue(state.lastError?.contains("not valid UTF-8") == true)
    }

    private func cameras(_ owner: String) -> [DesktopBridgeCamera] {
        ["first", "second"].map {
            DesktopBridgeCamera(id: "\(owner)-\($0)", model: "Synthetic \(owner) \($0)",
                                port: "synthetic:\($0)", engine: "fixture")
        }
    }

    private func scanOutcome(fails: Bool, owner: String) -> AsyncUIOutcome<[DesktopBridgeCamera]> {
        fails ? .failure("\(owner) discovery failed") : .success(cameras(owner))
    }

    private func lut(_ name: String) -> CubeLut {
        CubeLut(name: name, size: 2, domainMin: [0, 0, 0], domainMax: [1, 1, 1],
                values: [0, 0, 0, 1, 0, 0, 0, 1, 0, 1, 1, 0,
                         0, 0, 1, 1, 0, 1, 0, 1, 1, 1, 1, 1])
    }

    private func completeScan(_ name: String, cameras: [DesktopBridgeCamera], fixture: AsyncUIFixture) async throws {
        await fixture.discovery.enqueue(name, outcome: .success(cameras))
        let task = fixture.scan()
        try await waitForGate(name, in: fixture.discovery)
        await fixture.discovery.release(name)
        await task.value
    }

    private func completeImport(_ name: String, lut: CubeLut, fixture: AsyncUIFixture) async throws {
        await fixture.importer.enqueue(name, outcome: .success(lut))
        let task = fixture.importLut(name)
        try await waitForGate(name, in: fixture.importer)
        await fixture.importer.release(name)
        await task.value
    }

    private func waitForGate<Input: Sendable, Value: Sendable>(
        _ name: String, in gate: AsyncUIGate<Input, Value>
    ) async throws {
        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: .seconds(5))
        repeat {
            if await gate.waiting(name) { return }
            await Task.yield()
        } while clock.now < deadline
        XCTFail("Expected controlled operation did not enter gate \(name)")
        throw URLError(.timedOut)
    }

    private func withFixture(body: (AsyncUIFixture) async throws -> Void) async throws {
        let fixture = AsyncUIFixture()
        do {
            try await body(fixture)
        } catch {
            await fixture.cleanup()
            throw error
        }
        await fixture.cleanup()
    }

    @MainActor
    private struct ScanPresentation {
        let cameras: [DesktopBridgeCamera]
        let selection: String?
        let error: String?
        let busy: Set<CameraOperation>

        init(_ state: CameraAppState) {
            cameras = state.bridgeCameras
            selection = state.selectedBridgeCameraID
            error = state.lastError
            busy = state.busyOperations
        }

        func assertPreserved(in state: CameraAppState, file: StaticString = #filePath, line: UInt = #line) {
            XCTAssertEqual(state.bridgeCameras, cameras, file: file, line: line)
            XCTAssertEqual(state.selectedBridgeCameraID, selection, file: file, line: line)
            XCTAssertEqual(state.lastError, error, file: file, line: line)
            XCTAssertEqual(state.busyOperations, busy, file: file, line: line)
        }
    }
}

private enum AsyncUIScanChange: CaseIterable {
    case url, token, mode, urlRoundTrip, tokenRoundTrip, modeRoundTrip
    case disconnect, disconnectWithEmptyToken, offlinePreview

    static let replacementCases: [Self] = [
        .url, .token, .urlRoundTrip, .tokenRoundTrip, .modeRoundTrip, .disconnect, .disconnectWithEmptyToken,
    ]

    @MainActor
    func apply(to state: CameraAppState) {
        switch self {
        case .url: state.bridgeURL = "http://127.0.0.1:18182"
        case .token: state.bridgeToken = "synthetic-replacement-token"
        case .mode: state.connectionMode = .ccapi
        case .urlRoundTrip:
            let original = state.bridgeURL
            state.bridgeURL = "http://127.0.0.1:18182"
            state.bridgeURL = original
        case .tokenRoundTrip:
            let original = state.bridgeToken
            state.bridgeToken = "synthetic-replacement-token"
            state.bridgeToken = original
        case .modeRoundTrip:
            state.connectionMode = .ccapi
            state.connectionMode = .desktopBridge
        case .disconnect, .disconnectWithEmptyToken: state.requestDisconnect()
        case .offlinePreview: state.openOfflinePreview()
        }
    }
}

private struct AsyncUIBridgeRequest: Equatable, Sendable {
    let url: String
    let token: String
}

@MainActor
private final class AsyncUIFixture {
    static let bridgeURL = "http://127.0.0.1:18181"
    static let bridgeToken = "synthetic-test-token"
    let state: CameraAppState
    let discovery: AsyncUIGate<AsyncUIBridgeRequest, [DesktopBridgeCamera]>
    let importer: AsyncUIGate<URL, CubeLut>
    let delay: AsyncUIGate<Void, Void>
    private let suite = "AsyncUIOwnershipTests.\(UUID().uuidString)"
    private let defaults: UserDefaults
    private var tasks: [Task<Void, Never>] = []

    init() {
        let discovery = AsyncUIGate<AsyncUIBridgeRequest, [DesktopBridgeCamera]>()
        let importer = AsyncUIGate<URL, CubeLut>()
        let delay = AsyncUIGate<Void, Void>()
        self.discovery = discovery
        self.importer = importer
        self.delay = delay
        defaults = UserDefaults(suiteName: suite)!
        state = CameraAppState(
            defaults: defaults,
            bridgeCameraDiscovery: { url, token in
                try await discovery.run(AsyncUIBridgeRequest(url: url, token: token))
            },
            cubeLutLoader: { url in try await importer.run(url) },
            focusMarkerDelay: { try await delay.run(()) }
        )
        state.connectionMode = .desktopBridge
        state.bridgeURL = Self.bridgeURL
        state.bridgeToken = Self.bridgeToken
        state.autoRefresh = false
    }

    func scan(preCanceled: Bool = false) -> Task<Void, Never> {
        let task = Task { await state.scanBridgeCameras() }
        // Main-actor isolation guarantees cancellation before the task enters.
        if preCanceled { task.cancel() }
        tasks.append(task)
        return task
    }

    func importLut(_ name: String, preCanceled: Bool = false) -> Task<Void, Never> {
        let url = URL(fileURLWithPath: "/synthetic/\(name).cube")
        let task = Task { await state.importCubeLut(from: url) }
        if preCanceled { task.cancel() }
        tasks.append(task)
        return task
    }

    func focus() async throws -> Task<Void, Never> {
        await state.autofocus()
        let task = try XCTUnwrap(state.focusMarkerExpiryTask)
        tasks.append(task)
        return task
    }

    func cleanup() async {
        tasks.forEach { $0.cancel() }
        await discovery.drain()
        await importer.drain()
        await delay.drain()
        for task in tasks { await task.value }
        await state.disconnect()
        let unexpectedDiscovery = await discovery.unexpectedRequestCount()
        let unexpectedImports = await importer.unexpectedRequestCount()
        let unexpectedDelays = await delay.unexpectedRequestCount()
        XCTAssertEqual(unexpectedDiscovery, 0)
        XCTAssertEqual(unexpectedImports, 0)
        XCTAssertEqual(unexpectedDelays, 0)
        defaults.removePersistentDomain(forName: suite)
    }
}

private enum AsyncUIOutcome<Value: Sendable>: Sendable {
    case success(Value)
    case failure(String)
    case cancelled

    func get() throws -> Value {
        switch self {
        case let .success(value): return value
        case let .failure(message): throw AsyncUIFailure(message: message)
        case .cancelled: throw CancellationError()
        }
    }
}

private struct AsyncUIFailure: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

/// Cancellation cannot release these continuations. Tests explicitly deliver
/// late work, await its caller, and only then inspect the visible state.
private actor AsyncUIGate<Input: Sendable, Value: Sendable> {
    private struct Plan {
        let name: String
        let outcome: AsyncUIOutcome<Value>
    }
    private var plans: [Plan] = []
    private var held: [String: CheckedContinuation<Void, Never>] = [:]
    private var received: [Input] = []
    private var draining = false
    private var unexpected = 0

    func enqueue(_ name: String, outcome: AsyncUIOutcome<Value>) {
        plans.append(Plan(name: name, outcome: outcome))
    }

    func run(_ input: Input) async throws -> Value {
        received.append(input)
        guard !plans.isEmpty else {
            unexpected += 1
            throw AsyncUIFailure(message: "Unexpected fixture request")
        }
        let plan = plans.removeFirst()
        if !draining {
            await withCheckedContinuation { continuation in
                held[plan.name] = continuation
            }
        }
        return try plan.outcome.get()
    }

    func waiting(_ name: String) -> Bool { held[name] != nil }
    func requests() -> [Input] { received }
    func unexpectedRequestCount() -> Int { unexpected }

    func release(_ name: String) {
        held.removeValue(forKey: name)?.resume()
    }

    func drain() {
        draining = true
        let continuations = Array(held.values)
        held.removeAll()
        for continuation in continuations { continuation.resume() }
    }
}
