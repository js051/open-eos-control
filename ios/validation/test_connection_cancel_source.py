"""Source/fixture guards only. These do not compile or execute Swift or iOS UI."""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / "ios/OpenEOSControl/App"
CORE = ROOT / "ios/OpenEOSCore/Sources/OpenEOSCore"


def section(text, start, end):
    return text.split(start, 1)[1].split(end, 1)[0]


def test_cancel_is_synchronous_preserves_fields_and_does_not_retry():
    source = (APP / "CameraAppState.swift").read_text()
    cancel = section(source, "    func cancelConnection()", "    /// The cleanup Task")
    for token in ["guard canCancelConnection", "opening?.cancel()", "sessionGeneration = UUID()",
                  "connectionAttemptOwner = nil", "session = nil", "end(.connect)", "retireConnectingSession(retirement"]:
        assert token in cancel
    assert "await " not in cancel
    for token in ['password = ""', 'bridgeToken = ""', 'selectedBridgeCameraID = nil', 'prepareDisconnect()', 'await connect()']:
        assert token not in cancel


def test_retry_waits_for_actual_opening_and_cleanup_and_unknown_outcome_blocks_it():
    source = (APP / "CameraAppState.swift").read_text()
    connect = section(source, "    func connect()", "    /// Revoke publication synchronously")
    assert connect.count("previousConnectionCleanupWarnings.isEmpty") == 2
    assert connect.index("for cleanup in precedingCleanup { await cleanup.value }") < connect.index("newSession = try sessionFactory()")
    assert "connectionAttemptOwner == owner" in connect
    assert "configurationOwner == connectionConfigurationOwner" in connect
    assert "defer {" in connect and "end(.connect, generation: generation)" in connect
    assert "onCancel: {\n                    opening.cancel()" in connect
    assert "retireConnectingSession(retirement, generation: generation)" in connect
    assert "previousConnectionCleanupWarnings.remove" not in source


def test_retirement_caches_its_exact_owner_and_accumulates_safety_evidence():
    source = (APP / "CameraAppState.swift").read_text()
    retire = section(source, "    private func retireConnectingSession", "    func refreshMediaDateDisplayZone")
    assert "if let cleanup = retirement.cleanup { return cleanup }" in retire
    assert "retirement.cleanup = cleanup" in retire
    assert "let closingSession = retirement.session" in retire
    assert "let opening = retirement.opening" in retire
    assert retire.index("let firstRelease = await closingSession.closeWithShutterReleaseState()") < retire.index("_ = await opening.result")
    assert retire.index("_ = await opening.result") < retire.index("pendingConnectionCleanup.remove(generation)")
    for token in ["firstRelease.releaseRequired || firstRelease.releaseUnconfirmed", "firstRecording != nil || recording != nil",
                  "firstLiveView.stopUnconfirmed || liveView.stopUnconfirmed", "firstMaintenance || maintenance"]:
        assert token in retire
    assert "self.session" not in retire and "session = nil" not in retire


def test_late_bridge_allocation_uses_independent_awaited_exact_id_cleanup():
    source = (CORE / "DesktopBridgeClient.swift").read_text()
    initialize = section(source, "    public func initialize()", "    private static func isDefinitiveSessionRejection")
    assert "guard !isClosing, !connectionRetirementUnconfirmed" in initialize
    assert 'body["id"] as? String' in initialize
    assert '(1...128).contains(id.utf8.count)' in initialize
    assert 'id.utf8.allSatisfy' in initialize
    assert 'let deletion = Task { try await requestOK(endpoint(["v1", "session", id])' in initialize
    assert "try await deletion.value" in initialize
    assert "connectionRetirementUnconfirmed = true" in initialize
    assert "try? await requestOK" not in initialize
    assert "preserveConnectionRejection: true" in initialize
    assert "let creation = Task { () async throws -> CreatedConnection in" in initialize
    assert "created = try await creation.value" in initialize
    assert "error is SessionCreationNotDispatched" in initialize
    assert "creation.cancel()" not in initialize


def test_shared_49_vectors_match_source_classifier_constants_and_precedence():
    # Extract constants from source, then check the shared fixture expectations.
    # This does NOT execute the production Swift classifier.
    source = (CORE / "DesktopBridgeClient.swift").read_text()
    classifier = section(source, "    private static func isDefinitiveSessionRejection", "    public func shutterReleaseState")
    statuses = set(map(int, re.search(r"if \[([0-9, ]+)\]\.contains\(status\)", classifier).group(1).split(",")))
    safety = set(re.search(r'if \[((?:"[A-Z_]+",? ?)+)\]\.contains\(code\)', classifier).group(1).replace('"', '').replace(' ', '').split(','))
    assert classifier.index(".contains(code) { return false }") < classifier.index(".contains(status) { return true }")
    assert 'code == "ENGINE_UNAVAILABLE"' in classifier
    assert 'status == 502 && code == "CCAPI_DISCOVERY_FAILED" && engine == "ccapi"' in classifier
    fixture = json.loads((ROOT / "contracts/connection-lifecycle/v1/session-creation-rejection-vectors.json").read_text())
    assert len(fixture["vectors"]) == 49
    for case in fixture["vectors"]:
        rejected = case["code"] not in safety and (case["status"] in statuses
            or case["status"] in (501, 503) and case["code"] == "ENGINE_UNAVAILABLE"
            or case["status"] == 502 and case["code"] == "CCAPI_DISCOVERY_FAILED" and case["engine"] == "ccapi")
        assert rejected == case["creationRejected"], case["id"]


def test_connection_wire_path_reuses_bounded_no_redirect_no_auth_replay_transport():
    transport = (CORE / "CameraHTTPTransport.swift").read_text()
    production = section(transport, "    public func sendConnectionLifecycle", "    public func sendMediaDelete")
    assert "CameraHTTPMaintenanceTask(configuration: streamConfiguration).run(request)" in production
    assert "try?" not in production and "session.data" not in production
    wire = (CORE / "CameraHTTPMaintenanceTask.swift").read_text()
    assert "completionHandler(nil)" in wire
    assert ".cancelAuthenticationChallenge" in wire
    assert "!suppliedBody && !cancelled" in wire
    assert "maximumResponseBytes: Int = 64 * 1024" in wire
    bridge = (CORE / "DesktopBridgeClient.swift").read_text()
    assert bridge.count("transport.sendConnectionLifecycle(request)") == 2
    assert "response.statusCode == 204" in bridge and "response.body.isEmpty" in bridge
    assert "recordingCommand != nil || preserveConnectionRejection" in bridge


def test_connection_view_cancel_retry_and_warning_are_localized_and_wired():
    view = (APP / "ConnectionView.swift").read_text()
    assert 'Button("connection_cancel", role: .cancel) { camera.cancelConnection() }' in view
    assert 'accessibilityIdentifier("connection-cancel-button")' in view
    assert 'camera.connectionIsWaitingForCleanup ? "connection_waiting_cleanup"' in view
    assert 'camera.connectionWasCancelled || camera.connectionRecovery?.operation == .connect' in view
    root = (APP / "RootView.swift").read_text()
    assert 'Text("connection_cleanup_pending")' in root
    assert 'Text("connection_cleanup_unconfirmed")' in root
    keys = {"connection_cancel", "connection_waiting_cleanup", "connection_cancelled_help",
            "connection_cleanup_pending", "connection_cleanup_unconfirmed"}
    for language in ("en", "zh-Hant"):
        entries = re.findall(r'^"([^"]+)" = "((?:[^"\\]|\\.)*)";$', (APP / f"Resources/{language}.lproj/Localizable.strings").read_text(), re.M)
        for key in keys:
            assert len([value for name, value in entries if name == key and value]) == 1, (language, key)


def test_background_and_preview_revoke_opening_without_automatic_reconnect():
    source = (APP / "CameraAppState.swift").read_text()
    background = section(source, "    func setApplicationActive", "    func startLiveView")
    assert "cancelConnection()" in background and "await connect()" not in background
    preview = section(source, "    func openOfflinePreview()", "    func requestDisconnect()")
    assert "requestDisconnect()" in preview
    disconnect = section(source, "    private func prepareDisconnect()", "    func refreshRecordingObservation()")
    assert "connectionSnapshotTask?.cancel()" in disconnect
    assert "connectingSessionRetirement = nil" in disconnect
    for filename in ("CCAPIClient.swift", "DesktopBridgeClient.swift"):
        snapshot = section((CORE / filename).read_text(), "    public func connectSnapshot()", "\n    public func ")
        assert snapshot.count("Task.checkCancellation()") == 4


def test_native_test_inventory_is_authored_but_not_claimed_executed():
    app_tests = (ROOT / "ios/OpenEOSControl/Tests/ConnectionCancellationTests.swift").read_text()
    core_tests = (ROOT / "ios/OpenEOSCore/Tests/OpenEOSCoreTests/ConnectionCancellationTests.swift").read_text()
    assert len(re.findall(r"    func test", app_tests)) == 10
    assert len(re.findall(r"    func test", core_tests)) == 7
    for token in ["Old finally cannot unlock B", "New session cannot overlap old DELETE", "A cancelled explicit retry cannot wake up",
                  "Editing or cancelling cannot dismiss an unknown resource owner", "Cleanup must not inherit"]:
        assert token in app_tests
    assert 'XCTAssertEqual(vectors.count, 49)' in core_tests
    assert "testSessionIdentityMustBeBoundedASCIIWithoutCoercionOrTrimming" in core_tests


def test_retired_safety_codes_are_evidence_without_replayable_stop_ownership():
    source = (CORE / "DesktopBridgeClient.swift").read_text()
    evidence = section(source, "    private func preserveConnectionRetirementSafetyWarning", "    private static func isDefinitiveSessionRejection")
    assert "ConnectionRetirementSafetyWarning(rawValue: code)" in evidence
    assert "connectionRetirementSafetyWarnings.insert(warning)" in evidence
    assert "adoptShutterRelease" not in evidence and "sessionEndpoint" not in evidence
    request_json = section(source, "    private func requestJSON", "    private func requestOK")
    assert "if preserveConnectionRejection { preserveConnectionRetirementSafetyWarning(error) }" in request_json
    request_ok = section(source, "    private func requestOK", "    private func makeRequest")
    assert "preservingProtocolStrings: connectionRetirement" in request_ok
    assert "if connectionRetirement { preserveConnectionRetirementSafetyWarning(error) }" in request_ok
    assert "if !connectionRetirement, scopedToSession" in request_ok
    assert request_ok.index("CameraHTTPResponseIntegrity.validate") < request_ok.index("preserveConnectionRetirementSafetyWarning(error)")
    app = (APP / "CameraAppState.swift").read_text()
    assert app.count("closingSession.connectionRetirementSafetyWarnings()") == 2
    warning_merge = section(app, "    private func preservePreviousConnectionSafetyWarnings", "    private func preservePreviousLiveViewWarning")
    for warning in ("shutterRelease", "recordingCommand", "liveViewStop"):
        assert f"warnings.contains(.{warning})" in warning_merge
    assert "releaseUnconfirmed: true" in warning_merge and "releaseRequired: true" not in warning_merge
    assert "await " not in warning_merge


def test_scan_ui_and_direct_admission_are_blocked_by_pending_and_unknown_retirement():
    source = (APP / "CameraAppState.swift").read_text()
    gate = section(source, "    var canScanBridgeCameras", "    var canConnect")
    assert "pendingConnectionCleanup.isEmpty && previousConnectionCleanupWarnings.isEmpty" in gate
    scan = section(source, "    func scanBridgeCameras()", "    func connect()")
    assert scan.index("canScanBridgeCameras, begin(.scan)") < scan.index("bridgeCameraDiscovery(requestURL, requestToken)")
    view = (APP / "ConnectionView.swift").read_text()
    assert ".disabled(!camera.canScanBridgeCameras)" in view
    native = (ROOT / "ios/OpenEOSControl/Tests/ConnectionCancellationTests.swift").read_text()
    for assertion in ("Pending POST must not overlap discovery", "Pending DELETE must not overlap discovery",
                      "Unknown cleanup must not reach Bridge discovery", "Cleanup completion cannot automatically scan"):
        assert assertion in native
