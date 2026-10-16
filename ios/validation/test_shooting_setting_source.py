"""Linux source/fixture checks only: no Swift compilation, URLSession or camera execution."""
from pathlib import Path
import hashlib
import json
import re
import subprocess

ROOT = Path(__file__).resolve().parents[2]
CORE = ROOT / "ios/OpenEOSCore/Sources/OpenEOSCore"
APP = ROOT / "ios/OpenEOSControl/App"


def source(name, root=CORE):
    return (root / name).read_text()


def section(text, start, end):
    return text.split(start, 1)[1].split(end, 1)[0]


def test_common_vectors_have_exact_counts_and_native_consumers():
    path = ROOT / "contracts/shooting-setting/v1/shooting-setting-vectors.json"
    assert hashlib.sha256(path.read_bytes()).hexdigest() == "31d0ee62c2875490a26d54dad0d435a990d074d7fc04598d34a52963a07d8864"
    vectors = json.loads(path.read_text())
    assert {key: len(vectors[key]) for key in ["wire", "ownership", "outcome"]} == {"wire": 24, "ownership": 64, "outcome": 24}
    tests = (ROOT / "ios/OpenEOSCore/Tests/OpenEOSCoreTests/ShootingSettingEvidenceTests.swift").read_text()
    for key in ["wire", "ownership", "outcome"]:
        assert f'vectors("{key}")' in tests


def test_capture_configuration_sealed_model_transport_journey_and_panel_unchanged():
    for file in ["ios/OpenEOSCore/Sources/OpenEOSCore/CameraCaptureConfiguration.swift",
                 "ios/OpenEOSCore/Sources/OpenEOSCore/CameraHTTPCaptureConfigurationTask.swift",
                 "ios/OpenEOSControl/App/CaptureConfigurationJourney.swift",
                 "ios/OpenEOSControl/App/CaptureConfigurationPanel.swift"]:
        assert (ROOT / file).read_bytes() == subprocess.check_output(["git", "show", f"HEAD:{file}"], cwd=ROOT)


def test_app_uses_one_scoped_write_and_no_capabilities_status_followup():
    text = section(source("CameraAppState.swift", APP), "func setSetting(key:", "func recoverShootingSetting(")
    assert text.count("session.applyShootingSetting(") == 1
    assert "session.capabilities" not in text and "session.status" not in text and "session.setSetting" not in text
    for guard in ["canStartShootingSetting", "setting.accepts(value)", "sessionGeneration", "connectionConfigurationOwner"]:
        assert guard in text
    assert "shootingSettingHistory.count > 20" in text


def test_history_recovery_is_explicit_read_only_and_does_not_steal_newest_intent():
    text = section(source("CameraAppState.swift", APP), "func recoverShootingSetting(", "private func finishShootingSetting(")
    assert "session.refreshShootingSetting(attempt)" in text
    assert "applyShootingSetting" not in text and "shootingSettingCurrentID =" not in text
    assert "canRecoverShootingSetting(id)" in text
    assert "let observation = shootingSettingObservationRevision" in text


def test_late_receipt_precedes_owner_guard_without_releasing_replacement_busy():
    text = section(source("CameraAppState.swift", APP), "private func finishShootingSetting(", "private func prepareCaptureMediaReview(")
    assert text.index("shootingSettingHistory[index].outcome = attempt.snapshot") < text.index("guard sameOwner, sameAttempt")
    assert text.index("guard sameOwner, sameAttempt") < text.index("shootingSettingTask = nil")
    assert "sameObservation: sameObservation" in text and "cancelled: cancelled" in text
    assert "observed.value" in text and "requestedValue" not in text


def test_refresh_and_event_reads_cannot_overtake_setting_owner():
    text = source("CameraAppState.swift", APP)
    begin = section(text, "private func begin(", "private func end(")
    assert "shootingSettingTask != nil, operation == .directory || operation == .setting || operation == .refresh" in begin
    event = section(text, "private func stableEventSnapshot(", "private func refreshMediaAfterEvent(")
    assert "while !busyOperations.isEmpty" in event
    assert "revision == operationRevision, busyOperations.isEmpty" in event
    assert "shootingSettingObservationRevision &+= 1" in text


def test_draft_is_exact_value_revision_and_editor_owned():
    text = source("CameraSheets.swift", APP)
    rows = section(text, "private func textSettingRow(", "private func rangeSettingValue(")
    assert "camera.lastError == nil" not in rows
    assert "pendingRangeIndices" not in text
    assert 'pendingRangeValues[setting.key] = setting.values[index]' in rows
    assert rows.count("settingDraftRevisions[setting.key] == revision") == 2
    assert rows.count("owner == settingEditorOwner, sessionOwner == camera.mediaLibrarySessionID") == 2
    assert "set: { textDrafts[setting.key] = $0; settingDraftRevisions[setting.key] = UUID() }" in rows
    assert "let selected = pendingRangeValues[setting.key]" in rows
    assert ".onDisappear { camera.cancelShootingSetting(); settingEditorOwner = UUID() }" in text


def test_new_change_gate_and_retired_history_prevent_automatic_replay():
    text = source("CameraAppState.swift", APP)
    assert "(isPreview || shootingSettingCurrentID == nil)" in text
    retire = section(text, "private func retireShootingSetting(", "@discardableResult\n    func setSetting(")
    assert "cancelShootingSetting()" in retire and "retired = true" in retire
    assert "session.apply" not in retire
    assert text.count("retireShootingSetting()") == 3  # declaration and two owner changes


def test_bridge_additive_endpoint_never_falls_back():
    text = section(source("DesktopBridgeClient.swift"), "public func applyShootingSetting(", "public func createDirectory(")
    assert '["settings", key, "outcome"]' in text and '["settings", key]' in text
    assert "postJSON" not in text and "setSetting(" not in text and "capabilities(" not in text
    assert 'timeoutInterval: 15, maximumBytes: 64 * 1024' in text
    request = section(source("DesktopBridgeClient.swift"), "private func requestJSON(", "private func requestOK(")
    assert request.index("CameraShootingSettingWire.observe") < request.index("if scopedToSession {\n            try Task.checkCancellation()")
    assert "shootingSettingAttempt, response.statusCode == 200" in request
    assert "decodeShootingSettingJSONObject" in request


def test_strict_wire_observation_cannot_erase_base_response_or_upgrade_unknown():
    text = source("CameraShootingSetting.swift")
    wire = section(text, "static func observe(_ body:", "func decodeShootingSettingJSONObject(")
    assert wire.index("attempt.responded()") < wire.index('let read = body["readbackEvidence"]')
    assert 'else { return true }' in wire
    assert 'requested.utf8.elementsEqual(outcome.requestedValue.utf8)' in wire
    assert 'version.intValue == 1' in text and 'CFGetTypeID(version) != CFBooleanGetTypeID()' in text
    assert 'values.count <= 1024' in text and 'value.unicodeScalars.count <= 512' in text
    assert '(1...64).contains(bytes.count)' in text
    observe = section(text, "func observe(_ setting:", "public enum CameraShootingSettingPolicy")
    assert "writeEvidence =" not in observe


def test_direct_preflight_is_fresh_single_target_and_freezes_route():
    text = source("CCAPIClient.swift")
    write = section(text, "private func writeShootingSetting(", "private func shootingSettingAliases(")
    assert "readShootingSettingObject(path: path, baseKey: baseKey, field: structured?.field)" in write
    assert "fresh.values.contains(where:" in write
    assert 'selected[baseKey] = structured == nil' in write
    assert "settings = selected" in write and "cachedSettings = selected" not in write
    assert "attempt?.readRoute?.writePath" in write
    helper = section(text, "private func putSettingValue(", "private func structuredSettingParts(")
    assert "for path in paths" not in helper
    assert "shootingSettingAttempt?.readRoute?.writePath" in helper
    assert "var updated = current" in helper and 'updated[field] = integerValue' in helper
    assert "activeFormats.allSatisfy" in helper


def test_scoped_direct_recovery_is_bounded_and_has_no_card_or_write_fanout():
    text = section(source("CCAPIClient.swift"), "public func refreshShootingSetting(", "private func shootingSettingObservation(")
    for prohibited in ["loadShootingSettings", "capabilities()", "method: .put", "method: .post", "method: .patch", "mediaItems", "storage"]:
        assert prohibited not in text
    assert 'ContinuousClock.now.advanced(by: .seconds(15))' in text
    assert 'maximumBytes: 64 * 1024' in text and "guard ContinuousClock.now < deadline" in text
    assert 'attempt.observe(observed)' in text


def test_transport_is_nonreplayable_complete_and_bounded():
    text = source("CameraHTTPShootingSettingTask.swift")
    for required in ['["GET", "POST", "PUT", "PATCH"]', '!suppliedBody && !cancelled', 'completionHandler(nil)',
                     '.cancelAuthenticationChallenge', 'if let error { throw error }', 'CameraHTTPResponseIntegrity.validate',
                     'uploadTask(withStreamedRequest:', 'Self.maximumBytes - body.count']:
        assert required in text
    assert text.count('task.resume()') == 1
    assert "static let maximumBytes = 64 * 1024" in text
    assert "CameraHTTPCaptureConfigurationTask" not in text


def test_native_tests_are_authored_without_claiming_execution():
    tests = (ROOT / "ios/OpenEOSCore/Tests/OpenEOSCoreTests/URLSessionShootingSettingTests.swift").read_text()
    for required in ["redirect307", "redirect308", "authentication", "lostResponse", "truncated", "oversized", "malformed", "heldPartial", '"PATCH"']:
        assert required in tests
    assert "Source validation on Linux does not execute" in tests
    app_tests = (ROOT / "ios/OpenEOSControl/Tests/ControlResponseOwnershipTests.swift").read_text()
    for required in ["testSettingResponseSurvivesReadFailure", "testSettingDoubleApplyCancelAndLateResponse", "testManualRefreshCannotPublishPrewrite", "testSettingUnknownMatchingRecovery", "testSettingHistoryIsBounded"]:
        assert required in app_tests


def test_recovery_and_outcome_ui_is_translated_and_accessible():
    text = source("ShootingSettingPanel.swift", APP) + source("ShootingSettingJourney.swift", APP) + source("CameraSheets.swift", APP)
    keys = set(re.findall(r'"(shooting_setting_[a-z_]+)"', text))
    for language in ["en", "zh-Hant"]:
        strings = source(f"Resources/{language}.lproj/Localizable.strings", APP)
        for key in keys:
            assert f'"{key}" =' in strings
    for identifier in ["shooting-setting-outcome", "shooting-setting-refresh", "shooting-setting-cancel", "shooting-setting-new"]:
        assert identifier in text
    assert "shooting-setting-open-result" in source("CameraControlView.swift", APP)


def test_diff_has_no_conflict_markers_or_whitespace_errors():
    assert subprocess.check_output(["git", "diff", "--check"], cwd=ROOT, text=True) == ""
    paths = subprocess.check_output(["git", "diff", "--name-only", "--", "ios"], cwd=ROOT, text=True).splitlines()
    paths += subprocess.check_output(["git", "ls-files", "--others", "--exclude-standard", "--", "ios"], cwd=ROOT, text=True).splitlines()
    for path in paths:
        if path.endswith(".swift"):
            assert not re.search(r"^(<<<<<<<|=======|>>>>>>>)", (ROOT / path).read_text(), re.MULTILINE)


def test_complete_structured_siblings_are_validated_before_preserving_them():
    text = source("CCAPIClient.swift")
    write = section(text, "private func writeShootingSetting(", "private func shootingSettingAliases(")
    assert write.index("!validShootingSettingSiblings(raw") < write.index("settings = selected")
    validator = section(text, "private func validShootingSettingSiblings(", "private func shootingSettingObservation(")
    for required in ["Set(current.keys) == Set(ability.keys)", "Set(current.keys) == Set(Self.wbShiftFields)",
                     "Set(current.keys).isSubset(of: Set(Self.imageQualityFields))", "let value = current[field] as? String",
                     "values.contains(where:", "Self.boundedIntegerRangeValues(range).contains(String(value))"]:
        assert required in validator


def test_every_ordinary_selector_requires_explicit_apply():
    sheets = source("CameraSheets.swift", APP)
    rail = section(sheets, "private var valueRail:", "private var applySelection:")
    assert "choiceDrafts[key] = value" in rail and "draftRevisions[key] = UUID()" in rail
    assert "setSetting(" not in rail
    choice = section(sheets, "private func settingRow(", "private func applyChoiceDraft(")
    assert "choiceDrafts[setting.key] = value" in choice
    assert "setSetting(" not in choice and 'Button("apply") { applyChoiceDraft(setting) }' in choice
    range_binding = section(sheets, "private func rangeSettingRow(", "return VStack(alignment: .leading, spacing: 6)")
    assert "pendingRangeValues[setting.key] = setting.values[index]" in range_binding
    assert "setSetting(" not in range_binding
    mode = section(source("CameraControlView.swift", APP), "private struct CaptureModePicker:", "private struct ExposureStrip:")
    selection = section(mode, 'Picker("capture_mode", selection: Binding(', '.pickerStyle(.segmented)')
    assert "draft = mode" in selection and "revision = UUID()" in selection
    assert "setCaptureMode(" not in selection
    assert 'Button("apply")' in mode and 'camera.setCaptureMode(selected, draftRevision: submittedRevision)' in mode
    ui = (ROOT / "ios/OpenEOSControl/UITests/OpenEOSControlUITests.swift").read_text()
    assert 'app.buttons["exposure-setting-apply"]' in ui
    assert 'app.buttons["capture-mode-apply"]' in ui
