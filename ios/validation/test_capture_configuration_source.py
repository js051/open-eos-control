"""Source/fixture audit only. These tests do not compile Swift or execute iOS."""
from pathlib import Path
import hashlib
import json
import re
import subprocess

ROOT = Path(__file__).resolve().parents[2]
CORE = ROOT / "ios/OpenEOSCore/Sources/OpenEOSCore"
APP = ROOT / "ios/OpenEOSControl/App"


def source(name, directory=CORE):
    return (directory / name).read_text()


def section(text, start, end):
    return text.split(start, 1)[1].split(end, 1)[0]


def test_shared_fixture_consumer_uses_all_sections():
    fixture = json.loads((ROOT / "contracts/capture-configuration/v1/capture-configuration-vectors.json").read_text())
    tests = (ROOT / "ios/OpenEOSCore/Tests/OpenEOSCoreTests/CaptureConfigurationEvidenceTests.swift").read_text()
    for key in ["receipt", "outcome", "ownership", "recovery"]:
        assert f'vectors("{key}")' in tests
        assert len(fixture[key]) == len({case["id"] for case in fixture[key]})
    assert len(fixture["outcome"]) == 48
    assert len(fixture["ownership"]) == 128
    assert len(fixture["recovery"]) == 16


def test_existing_delete_cohort_is_byte_unchanged():
    for path in ["ios/OpenEOSCore/Sources/OpenEOSCore/CameraHTTPDeleteTask.swift",
                 "ios/OpenEOSCore/Sources/OpenEOSCore/CameraMediaDelete.swift",
                 "ios/OpenEOSControl/App/MediaDeleteJourney.swift",
                 "ios/OpenEOSControl/App/MediaDeletePanel.swift"]:
        original = subprocess.check_output(["git", "show", f"HEAD:{path}"], cwd=ROOT)
        assert (ROOT / path).read_bytes() == original


def test_direct_ack_precedes_post_write_read_and_cancellation():
    text = source("CCAPIClient.swift")
    directory = section(text, "public func createDirectory(", "public func setFileNaming(")
    assert directory.index("attempt.acknowledge(created)") < directory.index("try Task.checkCancellation()")
    naming = section(text, "public func setFileNaming(", "public func refreshCaptureConfiguration(")
    assert naming.index("attempt.acknowledge(value)", naming.index("responseMatches")) < naming.index("loadFileNaming(force: true")
    assert "attempt.observe(naming)" in naming
    assert 'guard updated.value(for: field) == value' in naming


def test_direct_recovery_uses_only_bounded_reads_and_strict_existing_validators():
    text = source("CCAPIClient.swift")
    recovery = section(text, "public func refreshCaptureConfiguration(", "public func sleepCamera(")
    for forbidden in ["method: .post", "method: .put", "method: .delete", "createDirectory(", "setFileNaming(", "mediaItems", "loadShootingSettings("]:
        assert forbidden not in recovery
    assert "Self.validatedDirectorySelectionSetting(raw)" in recovery
    assert "Self.validatedCanonicalFileNaming(responses)" in recovery
    assert "for endpoint in Self.fileNamingEndpoints" in recovery
    assert "ContinuousClock.now.advanced(by: .seconds(15))" in recovery
    assert "maximumBytes: 64 * 1024" in recovery


def test_bridge_receipt_consumed_before_late_cancel_owner_check():
    text = source("DesktopBridgeClient.swift")
    request = section(text, "private func requestJSON(", "private func requestOK(")
    observed = request.index("Self.observeCaptureConfigurationResponse(response")
    assert observed < request.index("if scopedToSession {\n            try Task.checkCancellation()")
    assert request.index("try CameraHTTPResponseIntegrity.validate") < observed
    assert request.index("guard response.body.count <= 64 * 1024") < observed
    assert "try captureConfigurationAttempt?.markDispatched()" in request


def test_bridge_fresh_recovery_has_no_cached_fallback():
    text = section(source("DesktopBridgeClient.swift"), "public func refreshCaptureConfiguration(", "private static func captureDirectorySelection(")
    assert '["capture-configuration", outcome.operation.rawValue]' in text
    assert 'method: "GET"' in text
    assert "timeoutInterval: 15, maximumBytes: 64 * 1024" in text
    assert "capabilities()" not in text
    assert "parseFileNaming(raw)" in text


def test_scoped_transport_denies_replay_and_streams_bounded_complete_responses():
    text = source("CameraHTTPCaptureConfigurationTask.swift")
    for required in ["!suppliedBody && !cancelled", "completionHandler(nil)", ".cancelAuthenticationChallenge",
                     "configuration.timeoutIntervalForResource = request.timeoutInterval",
                     "Self.maximumBytes - body.count", "if let error { throw error }",
                     "CameraHTTPResponseIntegrity.validate", "uploadTask(withStreamedRequest:"]:
        assert required in text
    assert text.count("task.resume()") == 1
    assert "static let maximumBytes = 64 * 1024" in text
    assert "CameraHTTPDeleteTask" not in text
    transport = source("CameraHTTPTransport.swift")
    assert "CameraHTTPCaptureConfigurationTask(configuration: streamConfiguration).run(request)" in transport


def test_app_uses_returned_naming_without_extra_capabilities_get():
    text = section(source("CameraAppState.swift", APP), "private func performCaptureConfiguration(", "func recoverCaptureConfiguration(")
    assert "session.capabilities()" not in text
    assert "session.setFileNaming(field: field, value: value, attempt: attempt)" in text
    assert "let generation = sessionGeneration" in text
    assert "let configurationOwner = connectionConfigurationOwner" in text
    assert "targetSelection:" in text
    assert "captureConfigurationHistory.count > 20" in text


def test_recovery_ui_and_history_have_owner_and_attempt_guards():
    text = source("CameraAppState.swift", APP)
    recovery = section(text, "func recoverCaptureConfiguration(", "private func finishCaptureConfiguration(")
    assert "session.refreshCaptureConfiguration(attempt)" in recovery
    assert "session.createDirectory" not in recovery and "session.setFileNaming" not in recovery
    finish = section(text, "private func finishCaptureConfiguration(", "func sleepCamera()")
    assert "firstIndex(where: { $0.id == record.id })" in finish
    assert "record.owner == sessionGeneration" in finish
    assert "record.configurationOwner == connectionConfigurationOwner" in finish
    assert "captureConfigurationActiveID == record.id" in finish
    assert "recovering || captureConfigurationCurrentID == record.id" in finish
    assert finish.index("CameraCaptureConfigurationPolicy.canPublish") < finish.index("captureConfigurationTask = nil")


def test_drafts_are_frozen_before_task_and_never_cleared_on_submission():
    text = source("CameraSheets.swift", APP)
    directory = section(text, "private var directoryControlRow:", "private var fileNamingRow:")
    assert 'directoryName = ""' not in directory
    assert directory.index("let name = directoryName") < directory.index("Task { await camera.createDirectory")
    naming = section(text, "private func fileNamingField(", "private func loadCurrentValues()")
    assert naming.index("let requested = value.wrappedValue") < naming.index("Task { await camera.setFileNaming")
    assert "if loadedOwner != camera.mediaLibrarySessionID { loadCurrentValues() }" in text
    assert "!camera.canStartCaptureConfiguration" in naming


def test_current_ui_explains_unknown_ack_suffix_and_read_only_recovery_in_both_languages():
    panel = source("CaptureConfigurationPanel.swift", APP)
    for required in ["capture-config-refresh", "capture-config-new-request", "capture-config-cancel", "capture-config-history",
                     "capture_config_suffix_limits", "capture_config_current_value"]:
        assert required in panel
    keys = set(re.findall(r'"(capture_config_[a-z_]+)"', panel + source("CaptureConfigurationJourney.swift", APP)))
    for language in ["en", "zh-Hant"]:
        strings = (APP / f"Resources/{language}.lproj/Localizable.strings").read_text()
        for key in keys:
            assert f'"{key}" =' in strings


def test_exact_ascii_and_full_match_validation_close_terminal_newline_hole():
    text = source("CameraCaptureConfiguration.swift")
    assert "bytes.count == 5" in text and "bytes.count == 8" in text
    assert "value.range(of: pattern, options: .regularExpression) == (value.startIndex..<value.endIndex)" in text
    assert 'CFGetTypeID(version) != CFBooleanGetTypeID()' in text
    assert 'requested.utf8.elementsEqual(invocation.requestedValue.utf8)' in text
    assert 'receipt["field"] == nil' in text
    assert 'CameraCaptureConfigurationValue.naming(field, value: acknowledged)' in text


def test_native_wire_tests_are_present_without_claiming_they_ran():
    tests = (ROOT / "ios/OpenEOSCore/Tests/OpenEOSCoreTests/URLSessionCaptureConfigurationTests.swift").read_text()
    for case in ["redirect307", "redirect308", "authentication", "lostACK", "truncated", "oversized", "malformed", "heldPartial"]:
        assert case in tests
    assert "Source validation on Linux does not execute" in tests
    assert "headerRecorded" in tests


def test_swift_changed_files_have_no_conflict_markers_or_whitespace_errors():
    output = subprocess.check_output(["git", "diff", "--check"], cwd=ROOT, text=True)
    assert not output
    files = subprocess.check_output(["git", "diff", "--name-only", "--", "ios"], cwd=ROOT, text=True).splitlines()
    files += subprocess.check_output(["git", "ls-files", "--others", "--exclude-standard", "--", "ios"], cwd=ROOT, text=True).splitlines()
    for file in files:
        if file.endswith(".swift"):
            text = (ROOT / file).read_text()
            assert not re.search(r"^(<<<<<<<|=======|>>>>>>>)", text, re.MULTILINE)


def test_exact_value_fixture_is_consumed_by_native_actual_clients():
    path = ROOT / "contracts/capture-configuration/v1/exact-value-vectors.json"
    assert hashlib.sha256(path.read_bytes()).hexdigest() == "0ce969f4faaf795daae1fcc680807f1841313ef487ad3fefb1f8a682a92263c7"
    data = json.loads(path.read_text())
    assert {key: len(data[key]) for key in ["receipt", "naming", "directorySelection"]} == {"receipt": 68, "naming": 13, "directorySelection": 7}
    tests = (ROOT / "ios/OpenEOSCore/Tests/OpenEOSCoreTests/CaptureConfigurationEvidenceTests.swift").read_text()
    for key in ["receipt", "naming", "directorySelection"]:
        assert f'vectors("{key}", file: "exact-value-vectors.json")' in tests
    assert "enqueueCanonicalNamingValue" in tests
    assert "client.refreshCaptureConfiguration(attempt)" in tests


def test_directory_ack_uses_only_canonical_read_not_aggregate_cache():
    text = section(source("CCAPIClient.swift"), "public func createDirectory(", "public func setFileNaming(")
    assert "try await refreshCaptureConfiguration(attempt)" in text
    assert "loadShootingSettings" not in text
    assert 'response["directoryname"] as? String' in text
    tests = (ROOT / "ios/OpenEOSCore/Tests/OpenEOSCoreTests/CaptureConfigurationEvidenceTests.swift").read_text()
    assert "testDirectoryAggregateSettingCannotSubstituteForFailedCanonicalReadback" in tests


def test_bridge_receipt_binds_frozen_exact_actual_engine():
    text = source("DesktopBridgeClient.swift")
    # Session creation now crosses an owned Sendable result. Preserve the exact,
    # untrimmed engine through both links instead of accepting normalized evidence.
    assert 'rawEngine: body["engine"] as? String' in text
    assert 'captureConfigurationSessionEngine = created.rawEngine' in text
    request = section(text, "private func requestJSON(", "private func requestOK(")
    assert request.index("let captureConfigurationEngine = captureConfigurationSessionEngine") < request.index("await transport.sendCaptureConfiguration")
    assert "connectedEngine: captureConfigurationEngine" in request
    observer = section(text, "private static func observeCaptureConfigurationResponse(", "public func syncCameraClock(")
    assert 'else if connectedEngine == "ccapi"' in observer


def test_cancelled_settlement_keeps_evidence_but_does_not_publish_late_snapshot():
    text = section(source("CameraAppState.swift", APP), "private func finishCaptureConfiguration(", "func sleepCamera()")
    assert text.index("captureConfigurationHistory[index].outcome = attempt.snapshot") < text.index("if !cancelled, let snapshot")
    assert text.index("end(record.outcome.operation") < text.index("if !cancelled, let snapshot")
    assert "if !current.cancelled" not in text
    tests = (ROOT / "ios/OpenEOSControl/Tests/ControlResponseOwnershipTests.swift").read_text()
    assert "testCancelledLateNamingSuccessRetainsACKWithoutMergingUntilFreshRecovery" in tests


def test_partial_http_status_cannot_supply_capture_configuration_evidence():
    for file in ["CCAPIClient.swift", "DesktopBridgeClient.swift"]:
        text = section(source(file), "private func requestJSON(", "private func request")
        assert 'response.statusCode != 206, response.header("content-range") == nil' in text


def test_history_recovery_preserves_newest_record_draft_and_creation_ack():
    text = source("CameraAppState.swift", APP)
    recovery = section(text, "func recoverCaptureConfiguration(", "private func finishCaptureConfiguration(")
    assert "captureConfigurationHistory.first(where: { $0.id == id })" in recovery
    assert "canRecoverCaptureConfiguration(id)" in recovery
    assert "captureConfigurationCurrentID =" not in recovery
    assert "reviseCaptureConfigurationDraft" not in recovery
    assert "recovering: true" in recovery
    finish = section(text, "private func finishCaptureConfiguration(", "func sleepCamera()")
    assert "if !cancelled, !recovering, current.outcome.operation == .createDirectory" in finish
    assert "canRecoverCaptureConfiguration(record.id)" in source("CaptureConfigurationPanel.swift", APP)
    assert "if !camera.supports(.directoryControl), !camera.captureConfigurationHistory.isEmpty" in source("CameraSheets.swift", APP)


def test_monotonic_read_budget_rejects_late_final_get_before_publication():
    read = section(source("CCAPIClient.swift"), "private func readCaptureFileNaming()", "public func sleepCamera()")
    assert "ContinuousClock.now.duration(to: deadline)" in read
    last_read = read.index("responses[endpoint.field] = try await requestJSON")
    assert last_read < read.index("guard ContinuousClock.now < deadline", last_read) < read.index("return Self.validatedCanonicalFileNaming")
    for file in ["CCAPIClient.swift", "DesktopBridgeClient.swift"]:
        request = section(source(file), "private func requestJSON(", "private func request")
        assert request.index("await transport.sendCaptureConfiguration") < request.index("if let readDeadline, ContinuousClock.now >= readDeadline")
    tests = (ROOT / "ios/OpenEOSCore/Tests/OpenEOSCoreTests/CaptureConfigurationEvidenceTests.swift").read_text()
    assert "testLateFinalNamingGETCannotPublishAfterGroupDeadline" in tests
