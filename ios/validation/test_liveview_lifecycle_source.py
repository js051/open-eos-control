"""Source assertions only: no Swift, native decoder, renderer, networking or camera execution."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CORE = ROOT / 'ios/OpenEOSCore/Sources/OpenEOSCore'
APP = ROOT / 'ios/OpenEOSControl/App'

def core(name):
    return (CORE / name).read_text()

def app(name):
    return (APP / name).read_text()

def section(text, start, end):
    return text.split(start, 1)[1].split(end, 1)[0]

def test_shared_policy_is_consumed_by_real_source_publication_and_fallback():
    fixture = json.loads((ROOT / 'contracts/liveview-lifecycle/v1/liveview-vectors.json').read_text())
    assert len(fixture['cases']) == 99
    policy = core('CameraLiveViewLifecyclePolicy.swift')
    assert 'stop == "pending"' in policy
    assert 'CFGetTypeID(value) == CFBooleanGetTypeID()' in policy
    value = core('CCAPIClient.swift')
    assert value.count('CameraLiveViewLifecyclePolicy.evaluate(') == 2
    assert 'guard fallback["canFallback"] == true' in value
    assert 'guard policy["canPublishInputReady"] == true' in value
    assert 'policy["canPublishDecoded"] == true' in app('CameraAppState.swift')

def test_rtp_camera_ack_waits_for_actual_decoder_gate_before_source_is_active():
    value = section(core('CCAPIClient.swift'), 'private func startRTPLiveView(', 'private func isLiveViewSizeRejection(')
    assert value.index('sendLiveViewStart(') < value.index('session.waitUntilReady()') < value.index('activeLiveViewSource = .ccapiRTP')
    native = app('IOSCcapiRTP.swift')
    assert 'private let readinessDecoder = IOSRTPVideoProcessor()' in native
    assert 'accessUnit.keyFrame' in native and 'self.latestSPS != nil, self.latestPPS != nil' in native
    assert 'guard case .decoded = result' in native
    assert 'readinessDecoder.invalidate()' in native and 'readiness.fail(CancellationError())' in native
    assert 'self.lastValidVideoAt' in native and '5_000_000_000' in native
    assert 'if terminal.1 {' in native and 'if terminal.0 { throw CancellationError() }' in native

def test_decode_only_output_precedes_optional_color_lut_metal_processing():
    value = app('IOSRTPVideoProcessing.swift')
    assert 'request.decodeOnly || nativeRTPPreviewColorSupported' in value
    assert value.index('let decoded = decodedOutput.buffer') < value.index('if request.decodeOnly { return .decoded }') < value.index('MTLCreateSystemDefaultDevice()')
    assert 'Decode-only readiness, never renderer/presentation evidence' in value

def test_jpeg_start_has_retained_validated_frame_without_recursive_start_or_stop():
    value = section(core('CCAPIClient.swift'), 'private func startJPEGLiveView(', 'private func startCCAPILiveView(')
    assert value.index('firstJPEGFrame(owner: stop.id)') < value.index('activeLiveViewSource = .ccapiJPEGPolling')
    assert 'pendingJPEGFrame = first' in value
    assert 'startLiveView(' not in value and 'retryLiveViewStop(' not in value
    assert value.index('releasePendingLiveView()') < value.index('candidate = LiveViewRequest')
    assert 'guard !liveViewStopUnconfirmed' in core('CCAPIClient.swift')
    assert 'decodedJPEGValidator: { IOSLiveViewJPEGDecoder.validates($0) }' in app('CameraAppState.swift')

def test_maintenance_frame_uses_one_strict_read_and_preserves_control_cap():
    value = section(core('CCAPIClient.swift'), 'private func readJPEGLiveViewFrame(', 'public func foregroundMediaReadsAvailable')
    assert 'transport.sendMaintenanceLiveViewFrame(request)' in value
    assert 'maintenance ? Array(paths.prefix(1)) : paths' in value
    assert 'let attempts = maintenance ? 0' in value
    assert 'error is CancellationError || maintenance' in value
    assert 'maximumResponseBytes: CCAPIMultipartLiveView.maximumFrameBytes' in core('CameraHTTPTransport.swift')
    strict = core('CameraHTTPMaintenanceTask.swift')
    assert 'maximumResponseBytes: Int = 64 * 1024' in strict
    assert 'completionHandler(nil)' in strict and '.cancelAuthenticationChallenge' in strict

def test_multipart_wait_is_bounded_cancellable_and_terminal_before_buffer():
    value = core('CCAPIMultipartLiveView.swift')
    assert 'timeoutNanoseconds: UInt64 = 5_000_000_000' in value
    assert 'Task.checkCancellation()' in value and 'ContinuousClock.now < deadline' in value
    take = section(value, 'private func takeFrame()', 'func nextFrame(')
    assert take.index('if let terminal') < take.index('if let frame')
    assert 'if parser.isClosed' in value and 'func checkUsable()' in value
    assert 'iterator.next()' not in value

def test_runtime_reader_failure_retires_once_and_clears_stale_presentation():
    value = app('CameraAppState.swift')
    loop = section(value, 'private func beginLiveViewLoop(', 'private func stopLiveViewLoop(')
    assert 'reader == liveViewReaderID' in loop and 'lifetime == liveViewLifetime' in loop
    assert 'IOSLiveViewJPEGDecoder.validates(frame.data)' in loop
    assert 'retireLiveView(reason: error.localizedDescription)' in loop
    assert 'break // Terminal reader errors' in loop
    clear = section(value, 'private func clearLiveViewPresentation()', 'private var automaticLiveViewStopBlocked')
    for token in ['liveViewData = nil', 'activeLiveViewSource = nil', 'resetLiveViewMetrics()', 'rtpController.retireDelivery()']:
        assert token in clear

def test_background_cancels_pending_owner_without_foreground_session_reuse():
    value = app('CameraAppState.swift')
    background = section(value, 'func setApplicationActive(', 'func startLiveView()')
    assert 'retireLiveView(reason: nil)' in background
    assert 'resumeLiveViewAfterBulb' not in background
    retire = section(value, 'private func retireLiveView(', 'private func beginLiveViewRenderWatch(')
    assert 'pendingStart?.cancel()' in retire and 'if !deferStop' in retire
    assert 'self.liveViewLifetime == retirement' in retire and 'await pendingStart?.value' in retire
    assert 'startLiveView(' not in retire

def test_candidate_rtp_failure_is_retained_until_selected_source_publish():
    value = app('CameraAppState.swift')
    event = section(value, 'private func handleRTPEvent(', 'private func showShutterFlash(')
    assert 'pendingLiveViewStartTask' not in event
    assert 'if source == .ccapiRTP, let failure = rtpController.currentVideoFailure()' in value
    assert 'terminalVideoFailure = message' in app('IOSCcapiRTP.swift')
    assert 'terminalVideoFailure = nil' in app('IOSCcapiRTP.swift')

def test_renderer_evidence_does_not_claim_fresh_pixels_from_non_nil_readback():
    value = app('CameraAppState.swift')
    assert 'Detect absent/unavailable output, not freshness of retained renderer pixels' in value
    assert 'hasUsableRendererOutput()' in value
    assert 'Earlier OS versions can only prove' in app('IOSCcapiRTP.swift')

def test_native_regressions_cover_background_decode_cancel_and_unknown_stop():
    core_tests = (ROOT / 'ios/OpenEOSCore/Tests/OpenEOSCoreTests/LiveViewLifecycleTests.swift').read_text()
    app_tests = (ROOT / 'ios/OpenEOSControl/Tests/LiveViewStopRecoveryTests.swift').read_text()
    for token in ['testRTPACKWithoutDecodedFrame', 'testRTPWaitCancellation', 'testFirstSizeRejectionNeverRetriesStart', 'testMultipartReaderCancellation']:
        assert token in core_tests
    for token in ['testBackgroundDuringPendingStart', 'testRuntimeLossClearsLastImage', 'testCompleteJPEGEnvelopeWithoutDecodedImage']:
        assert token in app_tests


def test_local_retirement_closes_receivers_without_dropping_camera_stop_or_recording_safety():
    local = section(core('CCAPIClient.swift'), 'public func retireLiveViewReader()', 'private func sendLiveViewStop(')
    assert 'await closeLiveViewResources()' in local
    assert 'pendingLiveViewStop = nil' not in local and 'transport.send' not in local
    assert 'multipartSession?.close()' in local and 'await native.close()' in local
    assert 'closeSynchronously(retireDelivery: false)' in app('IOSCcapiRTP.swift')
    retirement = section(app('CameraAppState.swift'), 'private var automaticLiveViewStopBlocked', 'private func beginLiveViewRenderWatch(')
    assert 'recordingStopRequired' in retirement and 'busyOperations.contains(.recording)' in retirement
    assert retirement.index('await session.retireLiveViewReader()') < retirement.index('if !deferStop')


def test_jpeg_decode_evidence_forces_bounded_bitmap_output():
    value = app('IOSLiveViewJPEGDecoder.swift')
    assert 'maximumPixels = 8_847_360' in value and 'maximumEncodedBytes = 12 * 1024 * 1024' in value
    assert 'CGImageSourceGetStatusAtIndex(source, 0) == .statusComplete' in value
    assert value.index('w * h <= maximumPixels') < value.index('CGContext(data: nil') < value.index('context.draw(image')
    assert 'context.makeImage() != nil' in value


def test_ordinary_jpeg_uses_existing_bounded_read_task_and_rejects_partial_status():
    value = section(core('CCAPIClient.swift'), 'private func readJPEGLiveViewFrame(', 'public func foregroundMediaReadsAvailable')
    assert 'transport.sendLiveViewFrame(request)' in value
    assert 'transport.send(request)' not in value
    assert 'response.statusCode == 200, response.header("content-range") == nil' in value
    transport = section(core('CameraHTTPTransport.swift'), 'public func sendLiveViewFrame(', 'public func sendMaintenanceLiveViewFrame(')
    assert 'CameraHTTPMaintenanceTask(configuration: streamConfiguration' in transport
    assert 'maximumResponseBytes: CCAPIMultipartLiveView.maximumFrameBytes' in transport
