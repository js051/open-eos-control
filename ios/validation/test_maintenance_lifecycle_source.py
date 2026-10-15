"""Source/fixture audit ONLY. No Swift, URLSession, simulator or camera execution."""
import hashlib
import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CORE = ROOT / "ios/OpenEOSCore/Sources/OpenEOSCore"
APP = ROOT / "ios/OpenEOSControl/App"

def src(name, directory=CORE):
    return (directory / name).read_text()

def section(text, start, end):
    return text.split(start, 1)[1].split(end, 1)[0]

def test_shared_vectors_are_complete_and_used_by_native_tests():
    path = ROOT / "contracts/maintenance-lifecycle/v1/maintenance-vectors.json"
    vectors = json.loads(path.read_text())
    assert [len(vectors[k]) for k in ["clock", "wire", "ownership"]] == [32, 28, 70]
    tests = (ROOT / "ios/OpenEOSCore/Tests/OpenEOSCoreTests/MaintenanceEvidenceTests.swift").read_text()
    for key in ["clock", "wire", "ownership"]:
        assert f'vectors("{key}")' in tests
    assert "try await client.performMaintenance(attempt)" in tests
    assert "CameraMaintenancePolicy.canRestore(" in tests

def test_transport_has_one_bounded_body_no_redirect_authentication_replay():
    value = src("CameraHTTPMaintenanceTask.swift")
    for token in ['maximumBytes = 64 * 1024', '!suppliedBody && !cancelled',
                  'completionHandler(nil)', '.cancelAuthenticationChallenge',
                  'configuration.urlCache = nil', 'configuration.urlCredentialStorage = nil',
                  'configuration.timeoutIntervalForResource = request.timeoutInterval',
                  'CameraHTTPResponseIntegrity.validate', 'if let error { throw error }']:
        assert token in value
    assert 'func sendMaintenance' in src("CameraHTTPTransport.swift")
    assert 'CameraHTTPMaintenanceTask(configuration: streamConfiguration).run(request)' in src("CameraHTTPTransport.swift")

def test_bridge_consumes_original_response_before_cancellation_and_never_falls_back():
    text = src("DesktopBridgeClient.swift")
    command = section(text, 'public func performMaintenance(', 'public func observeMaintenance(')
    assert command.index('try attempt.markDispatched()') < command.index('transport.sendMaintenance(request)')
    assert command.index('CameraMaintenanceWire.outcome') < command.index('// The old invocation')
    assert command.index('// The old invocation') < command.rindex('try Task.checkCancellation()')
    assert '["maintenance", intent.operation.rawValue, "outcome"]' in command
    assert command.count('transport.sendMaintenance(request)') == 1
    for forbidden in ['clock", "sync', 'power", "sleep', 'performLiveViewMaintenance', 'connectSnapshot', 'startLiveView(']:
        assert forbidden not in command
    assert 'stopped.statusCode == 204' in command
    assert 'attempt.stop(.unknown); throw error' in command

def test_core_recovery_is_required_get_only_and_never_synthesizes_reachability():
    for name, end in [('CCAPIClient.swift', 'private func maintenanceRequest('),
                      ('DesktopBridgeClient.swift', 'private static func maintenanceBody(')]:
        read = section(src(name), 'public func observeMaintenance(', end)
        for forbidden in ['ensureInitialized(', 'initialize(', 'connectSnapshot(', 'startLiveView(',
                          'retryLiveViewStop(', 'method: .post', 'method: .put', 'method: "POST"',
                          'status()', 'firstJSON(', 'attempt.responded()']:
            assert forbidden not in read
        assert 'method: .get' in read or 'method: "GET"' in read
        assert 'attempt.observationUnavailable()' in read
    direct = section(src('CCAPIClient.swift'), 'public func performMaintenance(', 'public func observeMaintenance(')
    assert direct.index('attempt.responded()') < direct.index('try await observeMaintenance(')
    assert 'earliestEpochSeconds: epoch - 10' in direct
    assert 'latestEpochSeconds: epoch + 10' in direct
    assert 'isDaylightSavingTime' in direct
    assert 'expectedStatus: 204' in direct

def test_model_keeps_initial_proof_independent_from_later_observation():
    value = src('CameraMaintenance.swift')
    for field in ['commandEvidence', 'clockReadEvidence', 'verifiedClock', 'clockReference',
                  'observationEvidence', 'observedClock', 'stopEvidence', 'restoreEvidence']:
        assert field in value
    observation = section(value, 'func observe(_ clock:', 'func reachable(')
    assert 'if verifying {' in observation
    assert 'outcome.commandEvidence =' not in observation
    assert 'latest - earliest <= 120' in value
    assert 'CFGetTypeID(value) != CFBooleanGetTypeID()' in value
    assert 'CFGetTypeID(value) == CFBooleanGetTypeID()' in value
    assert 'sample.utcOffsetMinutes == utcOffsetMinutes' in value
    assert 'sample.daylightSaving == daylightSaving' in value

def test_app_separates_read_restore_and_preserves_owner_history():
    value = src('CameraAppState.swift', APP)
    lifecycle = section(value, 'private func performMaintenance(', 'func toggleBulbExposure()')
    original = section(lifecycle, 'confirmation: MaintenanceConfirmation?', 'func cancelMaintenance()')
    assert 'confirmation.owner == sessionGeneration' in original
    assert 'confirmation.configurationOwner == connectionConfigurationOwner' in original
    assert 'confirmation.operation == operation' in original
    assert 'maintenanceHistory.count > 20' in original
    for forbidden in ['await disconnect()', 'connectSnapshot()', 'await startLiveView(', 'beginEventLoop(']:
        assert forbidden not in original
    read = section(value, 'func recoverMaintenance(', 'func canRestoreMaintenance(')
    assert 'session.observeMaintenance(attempt)' in read
    for forbidden in ['performMaintenance(', 'startLiveView(', 'connectSnapshot(', 'beginEventLoop(', 'disconnect()']:
        assert forbidden not in read
    restore = section(value, 'func restoreMaintenance(', 'private func finishMaintenance(')
    assert restore.index('session.observeMaintenance(attempt)') < restore.index('session.restoreMaintenanceLiveView(attempt)')
    assert 'if record.hadLiveView {' in restore
    for token in ['sameMaintenanceOwner(record)', 'maintenanceActiveID == id', '!self.recordingStopRequired',
                  '!self.bulbExposureActive', '!self.shutterReleaseRequired', '!self.liveViewStopState.stopUnconfirmed']:
        assert token in restore
    finish = section(value, 'private func finishMaintenance(', 'func toggleBulbExposure()')
    assert finish.index('maintenanceHistory[index].outcome = attempt.snapshot') < finish.index('guard sameOwner')
    assert 'guard !cancelled else { return }' in finish
    assert 'await disconnect()' not in finish

def test_product_confirmation_and_localization_are_wired():
    sheets = src('CameraSheets.swift', APP)
    assert 'camera.maintenanceConfirmation(for: .sleep)' in sheets
    assert 'camera.maintenanceConfirmation(for: .sensorCleaning)' in sheets
    assert 'camera.sleepCamera(confirmation: confirmation)' in sheets
    assert 'camera.cleanSensor(autoPowerOff: true, confirmation: confirmation)' in sheets
    assert 'camera.cancelMaintenance()' in sheets
    assert 'MaintenancePanel()' in sheets
    assert sheets.count('.disabled(!camera.canStartMaintenance)') == 3
    en = src('Resources/en.lproj/Localizable.strings', APP)
    zh = src('Resources/zh-Hant.lproj/Localizable.strings', APP)
    keys = lambda text: set(re.findall(r'^"(maintenance_[^"]+)"\s*=', text, re.M))
    assert keys(en) == keys(zh)
    assert len(keys(en)) >= 30
    assert 'does not prove cleaning or sleep completed' in en

def test_native_async_and_wire_tests_are_authored_not_execution_claims():
    app = (ROOT / 'ios/OpenEOSControl/Tests/MaintenanceJourneyTests.swift').read_text()
    wire = (ROOT / 'ios/OpenEOSCore/Tests/OpenEOSCoreTests/URLSessionMaintenanceTests.swift').read_text()
    for token in ['testRetiredResponseCannotClearReplacementBusy', 'testCancelRetainsLateResponse',
                  'testUnknownCommandReadRecovery', 'testEventStopFailure', 'testSleepAndPowerOff',
                  'testKeepPowerCleaning', 'testRecordingAndBulb']:
        assert token in app
    for token in ['redirect307', 'redirect308', 'authentication', 'lostACK', 'truncated', 'oversized', 'heldPartial',
                  'transport.sendMaintenance(request)', 'XCTAssertEqual(peer.targets()']:
        assert token in wire

def test_sealed_delete_capture_and_setting_models_remain_unchanged():
    for path in ['ios/OpenEOSCore/Sources/OpenEOSCore/CameraHTTPDeleteTask.swift',
                 'ios/OpenEOSCore/Sources/OpenEOSCore/CameraMediaDelete.swift',
                 'ios/OpenEOSCore/Sources/OpenEOSCore/CameraCaptureConfiguration.swift',
                 'ios/OpenEOSCore/Sources/OpenEOSCore/CameraHTTPCaptureConfigurationTask.swift',
                 'ios/OpenEOSCore/Sources/OpenEOSCore/CameraShootingSetting.swift',
                 'ios/OpenEOSCore/Sources/OpenEOSCore/CameraHTTPShootingSettingTask.swift',
                 'ios/OpenEOSControl/App/CaptureConfigurationJourney.swift',
                 'ios/OpenEOSControl/App/ShootingSettingJourney.swift',
                 'ios/OpenEOSControl/App/MediaDeleteJourney.swift']:
        assert (ROOT / path).read_bytes() == subprocess.check_output(['git', 'show', f'HEAD:{path}'], cwd=ROOT)

def test_every_maintenance_control_dependency_uses_owned_strict_transport():
    direct = src('CCAPIClient.swift')
    bridge = src('DesktopBridgeClient.swift')
    for text in [direct, bridge]:
        command = section(text, 'public func performMaintenance(', 'public func observeMaintenance(')
        assert 'stopMaintenanceLiveView(attempt' in command
        assert 'try await retryLiveViewStop()' not in command
        start = section(text, 'public func restoreMaintenanceLiveView(',
                        'private func checkMaintenanceLiveViewOwner(' if text == direct else 'public func captureStill(')
        for forbidden in ['Task.detached', 'try await startLiveView(', 'releasePendingLiveView()',
                          'transport.send(', 'ensureInitialized()', 'for fallbackSize']:
            assert forbidden not in start
        assert 'attempt.validateRestore()' in start
        assert 'attempt.bindOwner(' in start
        stop = section(text, 'private func stopMaintenanceLiveView(', 'public func restoreMaintenanceLiveView(')
        assert 'try attempt.checkActive()' in stop
        assert 'Task.detached' not in stop and 'transport.send(' not in stop
        assert 'maintenanceOwned = true' in stop
        assert 'Revision == revision' in stop
        retry = section(text, 'public func retryLiveViewStop()', 'private func performLiveViewStop()')
        assert retry.index('maintenanceOwned == true') < retry.index('Task.detached')
        assert 'stopMaintenanceLiveView(CameraMaintenanceAttempt(' in retry
    transfer = section(direct, 'private func maintenanceLiveViewTransfer(', 'private func stopMaintenanceLiveView(')
    assert 'request.timeoutInterval = 15' in transfer
    assert 'transport.sendMaintenance(request)' in transfer
    assert transfer.index('transport.sendMaintenance(request)') < transfer.rindex('try attempt.checkActive()')
    for route in ['["maintenance", "control", "events"]', '["maintenance", "control", "liveview", "stop"]',
                  '["maintenance", "control", "liveview", "start"]']:
        assert route in bridge
    stop = section(bridge, 'private func stopMaintenanceLiveView(', 'public func restoreMaintenanceLiveView(')
    assert 'Self.strictBool(body["liveViewStopUnconfirmed"]) == false' in stop
    assert 'Self.strictBool(body["active"]) == false' in stop
    assert 'liveViewRevision = UUID()' in section(bridge, 'if scopedToSession, liveViewStatusRevision', 'if scopedToSession, !shutterOperation')

def test_restore_freezes_concrete_direct_configuration_and_has_bounded_stream_readiness():
    direct = src('CCAPIClient.swift')
    command = section(direct, 'public func performMaintenance(', 'public func observeMaintenance(')
    assert 'configureLiveViewRestoreRequest(activeLiveViewSource.map' in command
    assert 'size: activeLiveViewSize, source: $0' in command
    start = section(direct, 'public func restoreMaintenanceLiveView(', 'private func checkMaintenanceLiveViewOwner(')
    assert 'frozen.source != .auto' in start
    assert 'transport.openMaintenanceStream(read)' in start
    assert 'Self.maintenanceFirstFrame(session, deadline: readinessDeadline)' in start
    assert 'ContinuousClock.now.advanced(by: .seconds(15))' in start
    assert 'ContinuousClock().sleep(until: deadline)' in direct
    transport = src('CameraHTTPTransport.swift')
    assert 'boundedListing: true, maintenanceStream: true' in transport
    assert 'if maintenanceStream, challenge.protectionSpace.authenticationMethod != NSURLAuthenticationMethodServerTrust' in transport
    assert 'completionHandler(boundedListing ? nil : request)' in transport
    app = section(src('CameraAppState.swift', APP), 'func restoreMaintenance(', 'private func finishMaintenance(')
    assert 'session.restoreMaintenanceLiveView(attempt)' in app
    assert 'effectiveRequestedLiveViewSource()' not in app
    assert 'requestedFPS' not in app
    assert 'await self.startLiveView(' not in app
    finish = section(src('CameraAppState.swift', APP), 'private func finishMaintenance(', 'func toggleBulbExposure()')
    assert 'if stop.stopUnconfirmed || !liveViewStopState.stopUnconfirmed' in finish

def test_dependency_cancellation_and_no_replay_native_coverage_and_delete_peer():
    tests = (ROOT / 'ios/OpenEOSCore/Tests/OpenEOSCoreTests/MaintenanceControlTests.swift').read_text()
    for case in ['testCancelDuringStopPropagates', 'testCancelDuringRestoreStart',
                 'testLateStopAndStartCannotClearNewerSameSessionWarning',
                 'testRedirectAuthenticationAndFailureNeverReplay', 'testAttemptCannotRestoreOrObserveThroughDifferentClientOwner']:
        assert case in tests
    wire = (ROOT / 'ios/OpenEOSCore/Tests/OpenEOSCoreTests/URLSessionMaintenanceTests.swift').read_text()
    assert '["GET", "POST", "PUT", "DELETE"].contains' in wire
    app = (ROOT / 'ios/OpenEOSControl/Tests/MaintenanceJourneyTests.swift').read_text()
    for case in ['testCancelDuringMaintenanceStop', 'testCancelDuringRestoreStart', 'testRetiredRestoreStart']:
        assert case in app


def test_foreground_and_user_refresh_cannot_resume_a_maintenance_hold():
    app = src('CameraAppState.swift', APP)
    for begin, end in [('private func resumeLiveViewAfterBulb(', 'private func resetLiveViewMetrics('),
                       ('func setAutoRefresh(', 'func setRTPAudioEnabled('),
                       ('private func beginLiveViewLoop(', 'private func stopLiveViewLoop('),
                       ('private func beginEventLoop(', 'private func stopEventLoop(')]:
        assert '!maintenanceControlsPaused' in section(app, begin, end)
    tests = (ROOT / 'ios/OpenEOSControl/Tests/MaintenanceJourneyTests.swift').read_text()
    assert 'testBackgroundForegroundAndRefreshCannotResumePausedMaintenance' in tests
    for locale in ['en', 'zh-Hant']:
        strings = src(f'Resources/{locale}.lproj/Localizable.strings', APP)
        assert 'Ends the CCAPI connection and immediately' not in strings
        assert '結束 CCAPI 連線並立即' not in strings

def test_sleep_preparation_uses_frozen_required_strict_read_without_discovery():
    command = section(src('CCAPIClient.swift'), 'public func performMaintenance(', 'public func observeMaintenance(')
    for forbidden in ['ensureInitialized()', 'loadShootingSettings()', 'invalidateShootingSettingsCache()', 'firstJSON(']:
        assert forbidden not in command
    assert 'guard initialized, !closing' in command
    assert 'let frozenSleepPath = cameraSleepPath' in command
    assert 'maintenanceRequest(path: frozenSleepPath, method: .get' in command
    assert 'operations.contains(CCAPIOperation(method: .get, path: frozenSleepPath))' in command
    assert 'operations.contains(CCAPIOperation(method: .put, path: frozenSleepPath))' in command
    assert 'setting.array("ability")?.strings.contains(Self.autoPowerOffImmediately) == true' in command
    assert 'path = frozenSleepPath; method = .put' in command


def test_headers_have_wall_deadline_and_late_headers_cannot_resurrect_stream():
    transport = src('CameraHTTPTransport.swift')
    start = section(transport, 'func start(_ request: URLRequest) async throws -> CameraHTTPStreamResponse', 'func recordingBodyCompleted(')
    for token in ['maintenanceHeaderDeadline = Task', 'min(max(request.timeoutInterval, 0.001), 15)',
                  'self?.expireMaintenanceHeaders()', '!responseDelivered, !terminalResultRecorded',
                  'terminalResultRecorded = true', 'activeTask?.cancel(); activeSession?.invalidateAndCancel()',
                  'continuation?.resume(throwing: URLError(.timedOut))']:
        assert token in start
    headers = section(transport, 'didReceive response: URLResponse,', 'willPerformHTTPRedirection')
    assert 'guard !terminalResultRecorded' in headers
    assert 'deadline?.cancel()' in headers
    tests = (ROOT / 'ios/OpenEOSCore/Tests/OpenEOSCoreTests/URLSessionMaintenanceTests.swift').read_text()
    assert 'testTricklingMultipartHeadersHaveWallDeadlineBeforeStreamHandoff' in tests
    assert 'self.slowHeaders = slowHeaders' in tests
    assert 'self.trickleHeader(connection)' in tests


def test_maintenance_close_retires_locally_without_skipping_owned_release_or_falling_back():
    direct = src('CCAPIClient.swift'); bridge = src('DesktopBridgeClient.swift')
    close_direct = section(direct, 'private func finishMaintenanceClose()', 'private func maintenanceLiveViewTransfer(')
    close_bridge = section(bridge, 'private func finishMaintenanceClose()', 'private func stopMaintenanceLiveView(')
    for value in [close_direct, close_bridge]:
        assert 'activeMaintenanceAttempt?.cancel()' in value
        assert 'allowClosing: true' in value
        assert 'let releaseSafe =' in value
        assert 'if releaseSafe, maintenanceEventStopRequired' in value
        assert 'if releaseSafe, pendingLiveViewStop != nil' in value or 'if releaseSafe, liveViewStop != nil' in value
        assert value.index('releasePendingShutter()' if value == close_direct else 'releaseFocusHold(hold)') < value.index('stopMaintenanceLiveView(')
        for forbidden in ['await stopLiveView()', 'await stopEventPolling()', 'requestOK(', 'endpoint(["v1", "session", id])']:
            assert forbidden not in value
    assert 'multipartSession?.close()' in close_direct and 'await native.close()' in close_direct
    assert 'retiredLiveViewSource = pendingLiveViewStop?.source' in close_direct
    assert 'url: release.stopURL' in close_bridge
    assert 'Self.provesShutterReleased(body)' in close_bridge
    assert '["maintenance", "control", "session"]' in close_bridge
    assert 'response.statusCode == 204, response.body.isEmpty' in close_bridge
    assert 'maintenanceRetirementUnconfirmed = true' in close_bridge
    assert 'if liveViewStop != nil || maintenanceEventStopRequired { liveViewStopUnconfirmed = true }' in close_bridge
    owned = section(direct, 'private func sendOwnedShutterCommand(', 'public func startHeldAutofocus(')
    assert 'if maintenanceLifecycleOwned && closing' in owned
    assert 'request.timeoutInterval = 15' in owned and 'transport.sendMaintenance(request)' in owned
    assert 'shutterOperation && isClosing && maintenanceLifecycleOwned' in bridge
    assert 'previousMaintenanceRetirementWarnings' in src('RootView.swift', APP)
    tests = (ROOT / 'ios/OpenEOSCore/Tests/OpenEOSCoreTests/MaintenanceControlTests.swift').read_text()
    assert 'testCloseAfterRestoreStillReleasesFrozenBulbAndHeldAutofocusOwners' in tests
    assert 'testLocalRetirementFailureDoesNotBecomeStopProofOrTriggerFallback' in tests


def test_current_safety_evidence_is_rechecked_at_command_and_restore_dispatch():
    model = src('CameraMaintenance.swift')
    assert 'func revokeMutationSafety()' in model and 'func checkMutationSafety()' in model
    direct = src('CCAPIClient.swift'); bridge = src('DesktopBridgeClient.swift')
    command = section(direct, 'private func maintenanceRequest(', 'private func finishMaintenanceClose(')
    assert command.index('requireMaintenanceMutationSafety(attempt)') < command.index('attempt.markDispatched()') < command.index('transport.sendMaintenance(request)')
    command = section(bridge, 'public func performMaintenance(', 'public func observeMaintenance(')
    assert command.index('requireMaintenanceMutationSafety(attempt)') < command.index('attempt.markDispatched()')
    for text in [direct, bridge]:
        helper = section(text, 'private func requireMaintenanceMutationSafety(', 'private func checkMaintenanceLiveViewOwner(' if text == direct else 'private func finishMaintenanceClose(')
        assert 'attempt.checkMutationSafety()' in helper
        assert 'recordingCommandEvidence == nil' in helper
        restore = section(text, 'public func restoreMaintenanceLiveView(', 'private func checkMaintenanceLiveViewOwner(' if text == direct else 'public func captureStill(')
        assert restore.count('requireMaintenanceMutationSafety(attempt)') >= 2
    restore = section(bridge, 'public func restoreMaintenanceLiveView(', 'public func captureStill(')
    assert 'Self.strictBool(body["liveViewStopUnconfirmed"]) == false' in restore
    app = src('CameraAppState.swift', APP)
    assert app.count('revokeUnsafeMaintenanceMutation()') >= 5
    assert 'maintenanceAttempts[id]?.revokeMutationSafety()' in app
    tests = (ROOT / 'ios/OpenEOSCore/Tests/OpenEOSCoreTests/MaintenanceControlTests.swift').read_text()
    assert 'testNewRecordingDuringStopBlocksMainDispatchWithoutAdditionalStatusRead' in tests
    app_tests = (ROOT / 'ios/OpenEOSControl/Tests/MaintenanceJourneyTests.swift').read_text()
    assert 'XCTAssertTrue(state.canRetryLiveViewStop' in app_tests
    assert 'testExplicitCloseReportsLocalRetirementFailureWithoutGenericDeleteFallback' in app_tests
