"""Structural resource/ownership guards; these do not execute iOS or native rendering."""

import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
APP = ROOT / "ios/OpenEOSControl/App"


class NativeRtpLutSourceTests(unittest.TestCase):
    def test_actual_decoder_filter_and_native_renderer_are_connected(self):
        pipeline = (APP / "IOSRTPVideoProcessing.swift").read_text()
        session = (APP / "IOSCcapiRTP.swift").read_text()
        self.assertIn("VTDecompressionSessionDecodeFrame", pipeline)
        self.assertIn("applyCubeLutPreview(input, lut: lut)", pipeline)
        self.assertIn('CIFilter(name: "CIColorCubeWithColorSpace")', (APP / "CubeLut.swift").read_text())
        self.assertIn("context.render(image, to: target", pipeline)
        self.assertIn("CMSampleBufferCreateReadyWithImageBuffer", pipeline)
        self.assertIn("output.enqueue(frame.sampleBuffer)", session)
        self.assertIn("self.pixelMonitor.frameAvailable(token: token, format: frame.format,", session)
        self.assertIn("buffer: frame.pixelBuffer, outputAt: frame.decodedAt", session)
        self.assertIn("$0.frame.pixelBuffer === pixels", session)
        self.assertNotIn("if videoProcessingRequired &&", session)

    def test_physical_work_pool_and_delivery_queues_are_bounded(self):
        pipeline = (APP / "IOSRTPVideoProcessing.swift").read_text()
        session = (APP / "IOSCcapiRTP.swift").read_text()
        self.assertIn("guard inFlight == nil", pipeline)
        self.assertIn("kCVPixelBufferPoolAllocationThresholdKey: 3", pipeline)
        self.assertIn("guard pending < 4", pipeline)
        self.assertIn("if owns(ticket) { completion(result) }", pipeline)
        self.assertIn("videoProcessingGeneration == generation", session)
        self.assertIn("deliveryContinuity.admit(sequence: deliverySequence", session)

    def test_extended_cube_values_are_preserved_until_after_interpolation(self):
        pipeline = (APP / "CubeLut.swift").read_text()
        tests = (APP.parent / "Tests/RTPNativeLutRenderingTests.swift").read_text()
        self.assertIn("CGColorSpace(name: CGColorSpace.extendedSRGB)", pipeline)
        self.assertIn(".workingColorSpace: cubeLutWorkingColorSpace", pipeline)
        self.assertIn('cube.setValue(cubeLutWorkingColorSpace, forKey: "inputColorSpace")', pipeline)
        self.assertIn('cube.setValue(lut.rgbaData, forKey: "inputCubeData")', pipeline)
        self.assertIn("outputClamp.setValue(interpolated, forKey: kCIInputImageKey)", pipeline)
        self.assertLess(pipeline.index("let interpolated = cube.outputImage"), pipeline.index("outputClamp.setValue"))
        self.assertIn("testGpuPreservesNegativeAndOverRangeCubeNodesUntilAfterInterpolation", tests)
        self.assertIn("let context = nativeRTPPreviewContext(device: device)", tests)
        self.assertIn("[2 * $0 - 0.5, 0.25, 1.5 - 2 * $2]", tests)

    def test_display_throttling_preserves_reference_decode_and_does_not_reapply_lut_to_scopes(self):
        session = (APP / "IOSCcapiRTP.swift").read_text()
        monitor = (APP / "IOSRTPPixelMonitoring.swift").read_text()
        pipeline = (APP / "IOSRTPVideoProcessing.swift").read_text()
        self.assertIn("present: presentFrame", session)
        self.assertIn("guard output.isReadyForMoreMediaData", session)
        self.assertIn("guard request.present else { return .decoded(at: decodedAt) }", pipeline)
        self.assertLess(pipeline.index("VTDecompressionSessionDecodeFrame"), pipeline.index("guard request.present"))
        self.assertNotIn("applyCubeLut", monitor)
        self.assertNotIn("cubeLut = nil", monitor)

    def test_presentation_requires_exact_retained_identity_and_fresh_progress(self):
        session = (APP / "IOSCcapiRTP.swift").read_text()
        progress = (APP / "IOSRTPPresentationProgress.swift").read_text()
        state = (APP / "CameraAppState.swift").read_text()
        self.assertIn("retainedOutputs.count > 2", session)
        self.assertIn("$0.frame.pixelBuffer === pixels", session)
        self.assertIn("recordPresented(output.token, at: now)", session)
        self.assertIn("frame.sequence > $0.sequence", progress)
        self.assertIn("case presentationStalled", progress)
        self.assertIn("case decodedOutputStalled", progress)
        self.assertIn("recordUnverifiablePresentation", progress)
        self.assertIn("recordDecoded(token, at: frame.decodedAt)", session)
        self.assertIn("decodedOutput.completedAt = ProcessInfo.processInfo.systemUptime", (APP / "IOSRTPVideoProcessing.swift").read_text())
        self.assertNotIn("hasUsableRendererOutput", session + state)
        self.assertIn("self.rtpController.videoProgressFailure() != nil", state)
        watch = state.split("private func beginLiveViewRenderWatch()", 1)[1].split("func captureStill", 1)[0]
        self.assertNotIn("lastFrameAt", watch)
        self.assertIn("self.retireLiveView(reason:", watch)

    def test_sampled_scopes_and_spatial_overlays_have_separate_identity_gates(self):
        monitor = (APP / "IOSRTPPixelMonitoring.swift").read_text()
        surface = (APP / "LiveViewSurface.swift").read_text()
        self.assertIn("self.sameConfiguration(currentOutput, token)", monitor)
        self.assertIn("frame == analysisFrame, frame == currentOutput, frame == presentedFrame", monitor)
        self.assertIn('evidence: "surface-submitted"', monitor)
        self.assertIn("frameAgeMs: (completedAt - outputAt) * 1000", monitor)
        self.assertIn("currentOverlayAnalysis", surface)
        self.assertIn("frame: nativeVideo.presentedFrame, owner: monitorOwner", surface)
        self.assertIn("isOwnerActive(analysisFrame)", monitor)
        self.assertIn("self.isOwnerActive(token)", monitor)
        self.assertNotIn("displayedPixelBuffer()", monitor)
        self.assertNotIn("frameSubmitted", monitor)

    def test_startup_probe_and_replacement_wait_for_physical_retirement(self):
        session = (APP / "IOSCcapiRTP.swift").read_text()
        pipeline = (APP / "IOSRTPVideoProcessing.swift").read_text()
        native = (APP.parent / "Tests/RTPNativeLutRenderingTests.swift").read_text()
        probe = session.split("func submitReadinessProbe", 1)[1].split("func cancelReadinessProbe", 1)[0]
        self.assertLess(probe.index("await videoProcessor.retireAndWait()"), probe.index("return readinessDecoder.submit"))
        self.assertLess(probe.index("await readinessDecoder.retireAndWait()"), probe.index("return readinessDecoder.submit"))
        self.assertLess(probe.index("await self.readinessDecoder.retireAndWait()"), probe.index("completion(result)"))
        self.assertIn("guard !self.closed, self.decodedFirstFrame else", session)
        self.assertEqual(session.count("private let readinessDecoder:"), 1)
        self.assertIn("while busy { await Task.yield() }", pipeline)
        self.assertIn("decoderResourceChanged(false)", pipeline)
        self.assertIn("XCTAssertEqual(resources.maximumActive, 1", native)
        self.assertIn("XCTAssertEqual(resources.active, 0", native)

    def test_terminal_latch_precedes_failure_delivery_and_late_enqueue(self):
        session = (APP / "IOSCcapiRTP.swift").read_text()
        emit = session.split("func emit(_ event:", 1)[1].split("private func configureAudio", 1)[0]
        self.assertIn("guard deliveryOwner == owner, terminalVideoFailure == nil", emit)
        self.assertLess(emit.index("terminalVideoFailure = message"), emit.index("handler?(delivery)"))
        self.assertIn("let enqueued = self.audioStateLock.synchronized", session)
        self.assertIn("self.terminalVideoFailure == nil else { return false }", session)
        self.assertIn("IOSRTPPixelMonitor(isOwnerActive:", session)

    def test_native_sources_and_shared_vectors_are_bound_to_xcode_targets(self):
        project = (APP.parent / "project.yml").read_text()
        native = (APP.parent / "Tests/RTPPresentationProgressTests.swift").read_text()
        gate = (APP / "IOSRTPMonitorEvidence.swift").read_text()
        self.assertIn("- path: App", project)
        self.assertIn("- path: Tests", project)
        self.assertIn("- path: ../../contracts/rtp-presentation/v1/monitor-vectors.json", project)
        binding = project.split("- path: ../../contracts/rtp-presentation/v1/monitor-vectors.json", 1)[1].split("- path:", 1)[0]
        self.assertIn("buildPhase: resources", binding)
        self.assertIn('url(forResource: "monitor-vectors", withExtension: "json")', native)
        self.assertIn("XCTAssertEqual(contract.cases.count, 112)", native)
        self.assertIn("evaluateRtpMonitorEvidence(vector.input)", native)
        self.assertIn("decodeIfPresent(type, forKey: key)", gate)
        self.assertIn("if invalid { active = false }", gate)
        self.assertIn("9_007_199_254_740_991", gate)


if __name__ == "__main__":
    unittest.main()
