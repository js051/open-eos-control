"""Source guards only; these do not compile or execute production Swift."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CORE = ROOT / "ios/OpenEOSCore/Sources/OpenEOSCore"
APP = ROOT / "ios/OpenEOSControl/App"


def section(text, start, end):
    return text.split(start, 1)[1].split(end, 1)[0]


def test_zoom_retires_geometry_before_dispatch_and_does_not_restore_on_failure():
    source = (CORE / "CCAPIClient.swift").read_text()
    zoom = section(source, "public func setLiveViewMagnification(", "public func liveViewFrame(")
    direct = zoom.split("if liveViewMagnifications.isEmpty", 1)[1]
    assert direct.index("invalidateLiveViewGeometry()") < direct.index("try await requestOK(")
    assert "liveViewMagnificationAttempt = attempt" in direct
    assert "defer {" in direct and "liveViewMagnificationAttempt == attempt" in direct
    assert direct.count("invalidateLiveViewGeometry()") == 2
    assert "latestLiveViewGeometry =" not in direct
    assert "try requireLiveViewGeometryOwner(owner, allowMagnification: true)" in direct


def test_geometry_read_is_bound_to_source_session_revision_and_complete_bounded_response():
    source = (CORE / "CCAPIClient.swift").read_text()
    read = section(source, "private func ensureLiveViewGeometryForNativeStream(", "private func supportsCompleteLiveView(")
    assert "owner: LiveViewGeometryOwner" in read
    assert read.count("try requireLiveViewGeometryOwner(owner)") >= 2
    assert read.index("transport.sendLiveViewFrame(request)") < read.rindex("try requireLiveViewGeometryOwner(owner)") < read.index("latestLiveViewGeometry = geometry")
    assert "response.statusCode == 200" in read and 'response.header("content-range") == nil' in read
    assert "CameraHTTPResponseIntegrity.validate" in read
    assert "CCAPIMultipartLiveView.maximumFrameBytes" in read


def test_coordinate_dispatch_checks_captured_owner_after_async_geometry_read():
    source = (CORE / "CCAPIClient.swift").read_text()
    for start, end in [("public func tapFocus(", "public func clickWhiteBalance("),
                       ("public func clickWhiteBalance(", "public func driveFocus(")]:
        method = section(source, start, end)
        assert method.index("let geometryOwner = liveViewGeometryOwner") < method.index("try await ensureInitialized()")
        assert "ensureLiveViewGeometryForNativeStream(owner: geometryOwner)" in method
        assert "sendCoordinateCommand(" in method and "owner: geometryOwner" in method
    dispatch = section(source, "private func sendCoordinateCommand(", "private func ensureLiveViewGeometryForNativeStream(")
    assert dispatch.index("let command = try request(") < dispatch.index("try requireLiveViewGeometryOwner(owner)") < dispatch.index("transport.send(command)")
    assert dispatch.count("try requireLiveViewGeometryOwner(owner)") == 2


def test_old_jpeg_multipart_and_focus_reads_cannot_publish_after_zoom():
    source = (CORE / "CCAPIClient.swift").read_text()
    frame = section(source, "public func liveViewFrame(", "private func requireLiveViewInputReady(")
    assert "let geometryOwner = liveViewGeometryOwner" in frame
    assert frame.count("try requireLiveViewGeometryOwner(geometryOwner)") >= 2
    jpeg = section(source, "private func readJPEGLiveViewFrame(", "public func foregroundMediaReadsAvailable")
    assert "let geometryOwner = liveViewGeometryOwner" in jpeg
    assert jpeg.index("try requireLiveViewGeometryOwner(geometryOwner)") < jpeg.index("latestLiveViewGeometry = nil")
    assert jpeg.index("transport.sendLiveViewFrame(request)") < jpeg.rindex("try requireLiveViewGeometryOwner(geometryOwner)") < jpeg.index("latestLiveViewGeometry = geometry")
    focus = section(source, "public func liveViewFocusInfo(", "public func autofocus(")
    assert "let geometryOwner = liveViewGeometryOwner" in focus
    assert focus.count("liveViewGeometryIsCurrent(geometryOwner)") == 2


def test_geometry_owner_rejects_retired_session_source_and_revision():
    source = (CORE / "CCAPIClient.swift").read_text()
    owner = section(source, "private var liveViewGeometryOwner:", "private func cameraLiveViewPosition(")
    for token in ["!closing", "owner.session == pendingLiveViewStop?.id", "owner.source == activeLiveViewSource",
                  "owner.revision == liveViewGeometryRevision", "!liveViewMagnificationIsCurrent"]:
        assert token in owner
    invalidation = section(owner, "private func invalidateLiveViewGeometry()", "private func requireLiveViewGeometryOwner(")
    for token in ["liveViewGeometryRevision = UUID()", "latestLiveViewGeometry = nil", "pendingJPEGFrame = nil",
                  "pendingMultipartFrame = nil", "multipartSession?.discardPendingFrame()"]:
        assert token in invalidation


def test_malformed_negative_and_overflowing_geometry_never_becomes_authoritative():
    source = (CORE / "CCAPIClient.swift").read_text()
    parser = section(source, "private static func parseLiveViewGeometry(", "private var liveViewGeometryOwner:")
    for token in ['strictInteger(image["positionx"])', 'strictInteger(image["positiony"])',
                  'strictInteger(image["positionwidth"])', 'strictInteger(image["positionheight"])',
                  "x >= 0", "y >= 0", "width > 0", "height > 0", "Int32.max"]:
        assert token in parser
    packet = section(source, "private static func parseDetailedLiveView(", "private static func parseLiveViewGeometry(")
    assert "offset == bytes.count" in packet


def test_app_retires_reader_and_restarts_only_same_liveview_owner():
    source = (APP / "CameraAppState.swift").read_text()
    zoom = section(source, "func setLiveViewMagnification(", "enum CaptureModeTraceStage:")
    assert zoom.index("stopLiveViewLoop()") < zoom.index("try await session.setLiveViewMagnification(")
    assert "let lifetime = liveViewLifetime" in zoom
    assert zoom.count("lifetime == liveViewLifetime") >= 3
    assert "if autoRefresh, applicationActive, activeLiveViewSource != .ccapiRTP" in zoom
    assert "beginLiveViewLoop(session: session)" in zoom
    for start, end in [("func tapFocus(", "func clickWhiteBalance("), ("func clickWhiteBalance(", "func driveFocus(")]:
        control = section(source, start, end)
        assert "!isBusy(.liveView)" in control
        assert control.count("revision == liveViewCoordinateRevision") >= 2


def test_authored_native_regressions_cover_sequence_races_failures_and_replacement():
    tests = (ROOT / "ios/OpenEOSCore/Tests/OpenEOSCoreTests/MagnificationGeometryTests.swift").read_text()
    for token in ["testOneFiveTenOneReacquiresNativeGeometry", "testControlsDuringMagnificationSendNothing",
                  "testOldGeometryCompletionCannotDispatch", "testOldJPEGCompletionCannotPublish",
                  "testFailedReadbackNeverRestoresGeometry", "testRetiredSessionReadCannotPolluteReplacement",
                  "testMalformedGeometrySendsNoCoordinateMutation", "testLateMagnificationReadbackCannotPublishToReplacement"]:
        assert token in tests


def test_app_requires_new_local_output_without_stopping_camera_or_audio():
    source = (APP / "CameraAppState.swift").read_text()
    zoom = section(source, "func setLiveViewMagnification(", "enum CaptureModeTraceStage:")
    for token in ["liveViewCoordinateFrameRequired = true", "liveViewData = nil", "focusMarker = nil",
                  "resetLiveViewMetrics()", "rtpController.beginMagnificationChange()"]:
        assert zoom.index(token) < zoom.index("try await session.setLiveViewMagnification(")
    assert "rtpController.finishMagnificationChange(nativePause)" in zoom
    assert "stopLiveView()" not in zoom and "retireDelivery()" not in zoom
    assert "setAudioEnabled" not in zoom and "setRenderingEnabled" not in zoom
    gate = section(source, "var liveViewCoordinateControlsReady:", "@Published private(set) var liveViewMagnification")
    assert "rtpController.hasCurrentCoordinateOutput" in gate
    assert "liveViewData != nil && lastFrameAt != nil" in gate
    loop = section(source, "private func beginLiveViewLoop(", "private func stopLiveViewLoop(")
    assert "liveViewMagnificationLifetime != liveViewLifetime" in loop
    surface = (APP / "LiveViewSurface.swift").read_text()
    assert "guard camera.liveViewCoordinateControlsReady else { return false }" in surface


def test_rtp_receiver_stamps_revision_before_mainactor_handoff_and_resets_output():
    source = (APP / "IOSCcapiRTP.swift").read_text()
    receiver = section(source, "private func receiveVideo(on", "private func decodeFirstFrame(")
    assert receiver.index("let geometryRevision = sink.currentVideoGeometryRevision()") < receiver.index("connection.receiveMessage") < receiver.index("self.depacketizer.accept(data, revision: geometryRevision") < receiver.index("Task { @MainActor")
    assert "geometryRevision: geometryRevision" in receiver
    enqueue = section(source, "func enqueue(", "func currentVideoFailure(")
    assert "geometryRevision == videoGeometryRevision" in enqueue
    assert "magnificationPause?.session != sessionID" in enqueue
    boundary = section(source, "func beginMagnificationChange()", "func currentVideoGeometryRevision()")
    assert boundary.count("videoGeometryRevision = UUID()") == 2
    assert boundary.count("resetRenderer(removingImage: true)") == 2
    for token in ["pause.id == id", "deliveryOwner == pause.session", "pixelReadbackBarrier == nil",
                  "progress.owns(submitted)", "submitted.epoch == videoProcessingGeneration"]:
        assert token in boundary
    assert "setMonitoringActive" not in boundary and "retireAudioLocked" not in boundary


def test_pending_magnification_gates_only_its_exact_owner():
    source = (CORE / "CCAPIClient.swift").read_text()
    current = section(source, "private var liveViewMagnificationIsCurrent:", "private func liveViewGeometryIsCurrent(")
    for token in ["let owner = liveViewMagnificationOwner", "owner.session == pendingLiveViewStop?.id",
                  "owner.source == activeLiveViewSource", "owner.revision == liveViewGeometryRevision"]:
        assert token in current
    zoom = section(source, "public func setLiveViewMagnification(", "public func liveViewFrame(")
    assert "guard !liveViewMagnificationIsCurrent" in zoom
    assert "if liveViewMagnificationAttempt == attempt" in zoom
    tests = (ROOT / "ios/OpenEOSCore/Tests/OpenEOSCoreTests/MagnificationGeometryTests.swift").read_text()
    replacement = section(tests, "func testLateMagnificationReadbackCannotPublishToReplacement", "func testMalformedGeometry")
    assert replacement.index("try await client.startLiveView") < replacement.index("client.setLiveViewMagnification(.x10)") < replacement.index('peer.release("GET zoom")')


def test_native_authored_app_checks_include_stale_output_and_refresh_toggle():
    tests = (ROOT / "ios/OpenEOSControl/Tests/ControlResponseOwnershipTests.swift").read_text()
    for token in ["testMagnificationRetiresVisibleFrameUntilFreshReaderAndBlocksCoordinates",
                  "testPreMagnificationCoordinateCompletionCannotRestoreMarkerOrClearNewError",
                  "waitForImage(newImage", 'waitForGate("old-frame"', 'waitForGate("new-frame"',
                  "setAutoRefresh(false); await state.setAutoRefresh(true)", "XCTAssertEqual(stops, 0)"]:
        assert token in tests
    rtp_tests = (ROOT / "ios/OpenEOSControl/Tests/RTPEventDeliveryOwnershipTests.swift").read_text()
    assert "testMagnificationRetiresQueuedVideoAndPreservesAudioSessionOwner" in rtp_tests
    assert "testOldMagnificationFinishCannotReleaseNewPauseOrResetReplacementOwner" in rtp_tests
    assert "[1, 6]" in rtp_tests


def test_shared_fixture_coordinates_are_authoritative_not_ratio_estimates():
    import hashlib
    import json
    data = (ROOT / "contracts/magnification-geometry/v1/geometry-transition-vectors.json").read_bytes()
    assert hashlib.sha256(data).hexdigest() == "e73afb6875e8d1819e2e57d2e0f509ece850f68fb69fc925e5b88cfeffcd7609"
    vectors = json.loads(data)["vectors"]
    assert len(vectors) == 12
    # Fixture arithmetic, not production Swift execution.
    for row in vectors:
        r, n, e = row["reportedGeometry"], row["normalized"], row["expectedCameraPoint"]
        assert min(r["x"] + int(n["x"] * r["width"]), r["x"] + r["width"] - 1) == e["x"]
        assert min(r["y"] + int(n["y"] * r["height"]), r["y"] + r["height"] - 1) == e["y"]


def test_removed_guards_are_rejected_by_source_contracts():
    from unittest.mock import patch
    native = APP / "IOSCcapiRTP.swift"
    core = CORE / "CCAPIClient.swift"
    app = APP / "CameraAppState.swift"
    original = Path.read_text
    cases = [
        (core, "invalidateLiveViewGeometry()", "retireNothing()", test_zoom_retires_geometry_before_dispatch_and_does_not_restore_on_failure),
        (core, "owner: LiveViewGeometryOwner", "owner: OtherOwner", test_geometry_read_is_bound_to_source_session_revision_and_complete_bounded_response),
        (core, "try requireLiveViewGeometryOwner(owner)", "try checkNothing(owner)", test_geometry_read_is_bound_to_source_session_revision_and_complete_bounded_response),
        (core, 'response.header("content-range") == nil', "true", test_geometry_read_is_bound_to_source_session_revision_and_complete_bounded_response),
        (core, "owner.session == pendingLiveViewStop?.id", "true", test_geometry_owner_rejects_retired_session_source_and_revision),
        (core, "owner.source == activeLiveViewSource", "true", test_geometry_owner_rejects_retired_session_source_and_revision),
        (core, "owner.revision == liveViewGeometryRevision", "true", test_geometry_owner_rejects_retired_session_source_and_revision),
        (core, "offset == bytes.count", "true", test_malformed_negative_and_overflowing_geometry_never_becomes_authoritative),
        (app, "liveViewData = nil", "preserveOldImage()", test_app_requires_new_local_output_without_stopping_camera_or_audio),
        (app, "liveViewMagnificationLifetime != liveViewLifetime", "true", test_app_requires_new_local_output_without_stopping_camera_or_audio),
        (native, "geometryRevision == videoGeometryRevision", "true", test_rtp_receiver_stamps_revision_before_mainactor_handoff_and_resets_output),
        (native, "pixelReadbackBarrier == nil", "true", test_rtp_receiver_stamps_revision_before_mainactor_handoff_and_resets_output),
        (native, "submitted.epoch == videoProcessingGeneration", "true", test_rtp_receiver_stamps_revision_before_mainactor_handoff_and_resets_output),
        (native, "pause.id == id", "true", test_rtp_receiver_stamps_revision_before_mainactor_handoff_and_resets_output),
        (APP / "IOSRTPGeometryDepacketizer.swift", "guard admitted == settled", "guard true", test_rtp_partial_packet_assembly_is_retired_across_crop_boundary),
        (APP / "IOSRTPGeometryDepacketizer.swift", "depacketizer = CCAPIH264RTPDepacketizer(payloadType: payloadType)", "preserveOldParser()", test_rtp_partial_packet_assembly_is_retired_across_crop_boundary),
        (CORE / "CCAPIMultipartLiveView.swift", "return crossed ? [] : frames", "return frames", test_multipart_partial_parts_and_inflight_receive_keep_revision_and_framing),
        (CORE / "CCAPIMultipartLiveView.swift", "if crossed { parser.discardPendingFrame() }", "if crossed { preservePart() }", test_multipart_partial_parts_and_inflight_receive_keep_revision_and_framing),
        (CORE / "CCAPIMultipartLiveView.swift", "self?.currentGeometryRevision()", "sameRevision()", test_multipart_partial_parts_and_inflight_receive_keep_revision_and_framing),
    ]
    for target, before, after, check in cases:
        baseline = original(target)
        assert before in baseline
        mutated = baseline.replace(before, after)
        def read(path, *args, **kwargs):
            return mutated if path == target else original(path, *args, **kwargs)
        with patch.object(Path, "read_text", read):
            try:
                check()
            except (AssertionError, ValueError, IndexError):
                continue
        raise AssertionError(f"Missing guard was not caught: {target.name}: {before}")


def test_rtp_partial_packet_assembly_is_retired_across_crop_boundary():
    source = (APP / "IOSRTPGeometryDepacketizer.swift").read_text()
    accept = section(source, "func accept(", "private func retire(")
    assert accept.index("guard admitted == current") < accept.index("depacketizer.accept(data)") < accept.index("guard admitted == settled")
    assert "if revision != current { retire(to: current) }" in accept
    assert "retire(to: settled)" in accept
    retire = source.split("private func retire(", 1)[1]
    assert "depacketizer = CCAPIH264RTPDepacketizer(payloadType: payloadType)" in retire
    client = (APP / "IOSCcapiRTP.swift").read_text()
    assert "private lazy var depacketizer = IOSRTPGeometryDepacketizer" in client
    tests = (ROOT / "ios/OpenEOSControl/Tests/RTPGeometryDepacketizerTests.swift").read_text()
    assert "testPartialOldAccessUnitCannotBeRelabeledByNewCropFinalFragment" in tests
    assert "testReceiveAdmittedBeforeZoomIsDiscardedAndFreshReceiveRecovers" in tests
    assert "testBoundaryCrossingDuringAssemblyCannotReturnCompletedOldUnit" in tests


def test_multipart_partial_parts_and_inflight_receive_keep_revision_and_framing():
    source = (CORE / "CCAPIMultipartLiveView.swift").read_text()
    partial = section(source, "mutating func discardPendingFrame()", "var isClosed:")
    assert "case .headers, .body: discardCurrentPart = true" in partial
    assert "case .boundary: if !buffer.isEmpty" in partial
    assert "if !discardCurrentPart { frames.append(frame) }" in source
    assert "discardCurrentPart = false" in source
    owner = section(source, "struct CCAPIMultipartGeometryParser", "final class CCAPIMultipartLiveViewSession")
    assert owner.count("if crossed { parser.discardPendingFrame() }") == 2
    assert "let crossed = admitted != revision" in owner
    assert "return crossed ? [] : frames" in owner
    assert "revision = UUID()" in owner
    worker = section(source, "worker = Task", "private func currentGeometryRevision")
    assert worker.index("self?.currentGeometryRevision()") < worker.index("iterator.next()") < worker.index("self?.receive(chunk, revision: revision)")
    receive = section(source, "private func receive(", "private func finishStream")
    assert receive.index("lock.lock(); defer { lock.unlock() }") < receive.index("parser.append(chunk, revision: revision)")
    discard = section(source, "func discardPendingFrame() {\n        lock", "func checkUsable")
    assert "frame = nil" in discard and "parser.retire()" in discard
    tests = (ROOT / "ios/OpenEOSCore/Tests/OpenEOSCoreTests/MultipartGeometryOwnershipTests.swift").read_text()
    for token in ["testEveryPartialBoundaryHeaderAndBodyRetainsFramingButDropsOldPart",
                  "testReceiveAdmittedBeforeRetirementCannotPublishOrLeaveRelabeledPartialPart",
                  "testCompletedMailboxRetirementDoesNotDiscardNextNewPart",
                  "testRetiredPartStillEnforcesJPEGAndTerminalIntegrity"]:
        assert token in tests
