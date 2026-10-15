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

def _assert_rtp_decoder_readiness(client, native):
    start = section(client, 'private func startRTPLiveView(', 'private func isLiveViewSizeRejection(')
    assert start.index('sendLiveViewStart(') < start.index('session.waitUntilReady()') < start.index('requireLiveViewInputReady(owner: stop.id, decoded: true)') < start.index('activeLiveViewSource = .ccapiRTP')
    # The probe is shared by the controller, with a real default and injectable tests.
    assert native.count('private let readinessDecoder: IOSRTPVideoProcessor') == 1
    assert 'readinessDecoder: IOSRTPVideoProcessor = IOSRTPVideoProcessor()' in native
    assert 'self.readinessDecoder = readinessDecoder' in native
    probe = section(native, 'func submitReadinessProbe(', 'fileprivate func cancelReadinessProbe(')
    assert 'deliveryOwner == sessionID && terminalVideoFailure == nil' in probe
    assert probe.count('guard readinessProbeOwner == sessionID,') == 2
    assert 'guard self.readinessProbeOwner == sessionID,' in probe
    submit = probe.index('return readinessDecoder.submit(')
    assert probe.index('await videoProcessor.retireAndWait()') < probe.index('await readinessDecoder.retireAndWait()') < submit
    assert 'lut: nil, sps: sps, decodeOnly: true)' in probe
    assert submit < probe.index('await self.readinessDecoder.retireAndWait()') < probe.index('completion(result)')
    first = section(native, 'private func decodeFirstFrame(', 'private func scheduleVideoWatchdog(')
    assert 'guard !stateLock.synchronized({ closed || videoFailureReported })' in first
    assert 'guard let self, !self.stateLock.synchronized({ self.closed || self.videoFailureReported })' in first
    assert first.index('await sink.submitReadinessProbe(') < first.index('guard case .decoded = result else') < first.index('self.readiness.decodedFrame()')
    assert native.count('self.readiness.decodedFrame()') == 1
    assert 'accessUnit.keyFrame' in native and 'self.latestSPS != nil, self.latestPPS != nil' in native
    cancel = section(native, 'fileprivate func cancelReadinessProbe(', 'func setEventHandler(')
    assert 'self.readinessProbeOwner == sessionID' in cancel and 'self.readinessDecoder.invalidate()' in cancel
    close = section(native, 'private func closeSynchronously(', 'private func acceptVideo(')
    assert 'readiness.fail(CancellationError())' in close and 'sink.cancelReadinessProbe(sessionID: sessionID)' in close
    wait = section(native, 'func waitUntilReady()', 'func setTargetFPS(')
    assert wait.index('try await readiness.wait()') < wait.index('if terminal.1 {') < wait.index('if terminal.0 { throw CancellationError() }')
    assert 'onCancel: { self.closeSynchronously() }' in wait
    assert 'self.lastValidVideoAt' in native and '5_000_000_000' in native


def _assert_decode_only_precedes_processing(value):
    process = section(value, 'private func process(', 'private final class DecodedOutput')
    actual_decode = process.index('VTDecompressionSessionDecodeFrame(')
    completed = process.index('if image != nil { decodedOutput.completedAt = ProcessInfo.processInfo.systemUptime }')
    status = process.index('guard status == noErr, decodedOutput.status == noErr else { return .failed }')
    pixels = process.index('guard let decoded = decodedOutput.buffer, let decodedAt = decodedOutput.completedAt else { return .backpressure }')
    decode_only = process.index('if request.decodeOnly { return .decoded(at: decodedAt) }')
    present = process.index('guard request.present else { return .decoded(at: decodedAt) }')
    color = process.index('if request.lut != nil && !nativeRTPPreviewColorSupported(')
    metal = process.index('MTLCreateSystemDefaultDevice()')
    assert actual_decode < completed < status < pixels < decode_only < present < color < metal
    assert process.index('nativeRTPPreviewColorSupported(') > decode_only
    assert process.index('CVPixelBufferPoolCreate(') > decode_only
    assert process.index('context.render(') > decode_only
    assert process.index('if request.lut == nil {') < process.index('output = decoded') < metal
    assert 'case decoded(at: TimeInterval)' in value
    # Readiness returns decoder evidence, never a frame or a renderer operation.
    assert '.frame(' not in process[:decode_only]


def test_rtp_camera_ack_waits_for_actual_decoder_gate_before_source_is_active():
    _assert_rtp_decoder_readiness(core('CCAPIClient.swift'), app('IOSCcapiRTP.swift'))


def test_decode_only_output_precedes_optional_color_lut_metal_processing():
    _assert_decode_only_precedes_processing(app('IOSRTPVideoProcessing.swift'))

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

def _assert_renderer_progress_evidence(value, native, progress):
    assert 'hasUsableRendererOutput' not in value + native
    watch = section(value, 'private func beginLiveViewRenderWatch()', 'func captureStill(')
    assert watch.index('self.rtpController.videoProgressFailure() != nil') < watch.index('self.retireLiveView(reason:')
    assert 'lastFrameAt' not in watch
    observe = section(native, 'private func observePresentation(', 'private func beginPresentationPolling(')
    assert 'deliveryOwner == owner && applicationActive && terminalVideoFailure == nil' in observe
    for start, end in [
        ('guard IOSRTPPixelMonitor.supported else', 'guard let pixels = readDisplayedPixels(layer) else'),
        ('guard let pixels = readDisplayedPixels(layer) else', 'guard let output = retainedOutputs.first'),
    ]:
        unknown = section(observe, start, end)
        assert 'progress.recordUnverifiablePresentation(' in unknown
        assert 'videoPresentation.presentedFrame = nil' in unknown
        assert 'recordPresented(' not in unknown
    identity = observe.index('$0.frame.pixelBuffer === pixels')
    owned = observe.index('progress.owns(output.token) else')
    confirmed = observe.index('progress.recordPresented(output.token, at: now)')
    assert identity < owned < confirmed
    mismatch = observe[owned:confirmed]
    assert 'progress.recordUnverifiablePresentation(' in mismatch
    assert 'videoPresentation.presentedFrame = nil' in mismatch
    assert 'guard output.token == progress.submitted else' in observe
    assert 'recordDecoded(token, at: frame.decodedAt)' in native
    assert 'static let maximumGap: TimeInterval = 5' in progress
    presented = section(progress, 'mutating func recordPresented(', 'mutating func recordUnverifiablePresentation(')
    assert 'guard owns(frame), let submitted, frame.sequence <= submitted.sequence' in presented
    assert 'now >= (lastPresentedAt ?? startedAt ?? now)' in presented
    sequence = presented.index('presented.map({ frame.sequence > $0.sequence }) ?? true else { return false }')
    assert sequence < presented.index('presented = frame; lastPresentedAt = now')
    unknown = section(progress, 'mutating func recordUnverifiablePresentation(', 'mutating func failure(')
    # Ignore prose comments: unknown evidence may not renew or erase either clock.
    unknown_code = '\n'.join(line.split('//', 1)[0] for line in unknown.splitlines())
    assert 'state = .unknown' in unknown_code
    assert 'lastPresentedAt =' not in unknown_code and 'presentationExpectedAt =' not in unknown_code
    failure = progress.split('mutating func failure(', 1)[1]
    assert 'now - (lastDecodedAt ?? startedAt) >= Self.maximumGap' in failure
    assert 'now - (lastSubmittedAt ?? startedAt) >= Self.maximumGap' in failure
    assert 'submitted.sequence > (presented?.sequence ?? 0), now - deadline >= Self.maximumGap' in failure
    for result in ['.decodedOutputStalled', '.outputUnavailable', '.presentationStalled', '.rendererFailed']:
        assert result in failure


def test_renderer_evidence_does_not_claim_fresh_pixels_from_non_nil_readback():
    _assert_renderer_progress_evidence(app('CameraAppState.swift'), app('IOSCcapiRTP.swift'), app('IOSRTPPresentationProgress.swift'))

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
