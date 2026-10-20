"""Structural resource/ownership guards; these do not execute iOS or native rendering."""

import base64
import hashlib
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
APP = ROOT / "ios/OpenEOSControl/App"


class IOSNativeRtpLutSourceTests(unittest.TestCase):
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
        self.assertIn("rgbaData = data", pipeline)
        self.assertIn("previewEncoding = CubeLutPreviewEncoding.make(values: values, originalData: data)", pipeline)
        self.assertIn("Self(rgbaData: originalData, scale: [1, 1, 1], bias: [0, 0, 0])", pipeline)
        self.assertIn('cube.setValue(encoding.rgbaData, forKey: "inputCubeData")', pipeline)
        self.assertIn("(Double(values[index + channel]) - Double(bias[channel])) / spans[channel]", pipeline)
        self.assertIn("guard floatSpan.isFinite, floatSpan > 0 else { return nil }", pipeline)
        self.assertIn("restore.setValue(interpolated, forKey: kCIInputImageKey)", pipeline)
        self.assertIn("outputClamp.setValue(restored, forKey: kCIInputImageKey)", pipeline)
        self.assertLess(pipeline.index("let interpolated = cube.outputImage"), pipeline.index("restore.setValue"))
        self.assertLess(pipeline.index("restored = output"), pipeline.index("outputClamp.setValue"))
        for channel in range(3):
            self.assertIn(f"CGFloat(encoding.scale[{channel}])", pipeline)
            self.assertIn(f"CGFloat(encoding.bias[{channel}])", pipeline)
        self.assertIn('restore.setValue(CIVector(x: 0, y: 0, z: 0, w: 1), forKey: "inputAVector")', pipeline)
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
        self.assertIn(
            "decodedOutput.completedAt = ProcessInfo.processInfo.systemUptime",
            (APP / "IOSRTPVideoProcessing.swift").read_text(),
        )
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
        self.assertLess(
            probe.index("await videoProcessor.retireAndWait()"), probe.index("return readinessDecoder.submit")
        )
        self.assertLess(
            probe.index("await readinessDecoder.retireAndWait()"), probe.index("return readinessDecoder.submit")
        )
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
        binding = project.split("- path: ../../contracts/rtp-presentation/v1/monitor-vectors.json", 1)[1].split(
            "- path:", 1
        )[0]
        self.assertIn("buildPhase: resources", binding)
        self.assertIn('url(forResource: "monitor-vectors", withExtension: "json")', native)
        self.assertIn("XCTAssertEqual(contract.cases.count, 112)", native)
        self.assertIn("evaluateRtpMonitorEvidence(vector.input)", native)
        self.assertIn("decodeIfPresent(type, forKey: key)", gate)
        self.assertIn("if invalid { active = false }", gate)
        self.assertIn("9_007_199_254_740_991", gate)


"""Structural resource/ownership guards; these do not execute Android or the GPU."""


ROOT = Path(__file__).resolve().parents[3]
DATA = ROOT / "android/app/src/main/java/dev/openeos/control/data"


class AndroidNativeRtpLutSourceTests(unittest.TestCase):
    def test_fixed_h264_vui_fully_tags_bt709_without_changing_original_pixels(self):
        source = (
            ROOT / "android/app/src/androidTest/java/dev/openeos/control/data/NativeRtpLutRenderingTest.kt"
        ).read_text()
        encoded = re.search(r'Base64.getDecoder\(\).decode\("([A-Za-z0-9+/=]+)"\)', source)
        self.assertIsNotNone(encoded)
        payload = base64.b64decode(encoded.group(1), validate=True)
        self.assertEqual(
            hashlib.sha256(payload).hexdigest(), "b48b0a7e9a6a54e6b3f34298ef8cbde86034fe1ed850bb3035f1d35c43d88fdf"
        )
        journey = (
            ROOT / "android/app/src/androidTest/java/dev/openeos/control/data/AndroidRtpPresentationJourneyTest.kt"
        ).read_text()
        journey_encoded = re.search(r'Base64.getDecoder\(\).decode\("([A-Za-z0-9+/=]+)"\)', journey)
        self.assertIsNotNone(journey_encoded)
        self.assertEqual(base64.b64decode(journey_encoded.group(1), validate=True), payload)
        units = [nal for nal in re.split(b"\x00\x00(?:\x00)?\x01", payload) if nal]
        self.assertEqual([nal[0] & 31 for nal in units], [7, 8, 5])
        # The original PPS and IDR are unchanged. Only the SPS color description changed.
        self.assertEqual(
            hashlib.sha256(units[1]).hexdigest(), "8c597ccf6b30196213ac90a0a77aa2fdbe81c8f4671b98f1e45ab23cf6120561"
        )
        self.assertEqual(
            hashlib.sha256(units[2]).hexdigest(), "bababe1c97a0947745672fb7e993c535b57609e997faec2f92f5661abf75be6d"
        )
        rbsp = units[0].replace(b"\x00\x00\x03", b"\x00\x00")
        bits = "".join(f"{byte:08b}" for byte in rbsp)
        offset = 0

        def take(count):
            nonlocal offset
            self.assertLessEqual(offset + count, len(bits))
            value = int(bits[offset : offset + count], 2) if count else 0
            offset += count
            return value

        def ue():
            zeros = 0
            while take(1) == 0:
                zeros += 1
                self.assertLess(zeros, 32)
            return (1 << zeros) - 1 + take(zeros)

        # Parse this fixed high-profile 4:2:0 SPS, failing if its shape changes.
        self.assertEqual((take(8), take(8)), (0x67, 100))
        take(16)  # Constraint flags and level.
        self.assertEqual((ue(), ue(), ue(), ue()), (0, 1, 0, 0))  # SPS id, 420, 8-bit luma/chroma.
        self.assertEqual((take(1), take(1)), (0, 0))  # No bypass or scaling matrix.
        self.assertEqual((ue(), ue(), ue(), take(1)), (0, 2, 0, 0))  # Frame number, POC, refs, gaps.
        self.assertEqual((ue(), ue(), take(1), take(1), take(1)), (3, 2, 1, 1, 0))  # 64x48, no crop.
        self.assertEqual((take(1), take(1), take(1)), (1, 0, 0))  # VUI present; no SAR/overscan.
        self.assertEqual((take(1), take(3), take(1), take(1)), (1, 5, 0, 1))  # Limited-range color description.
        self.assertEqual((take(8), take(8), take(8)), (1, 1, 1))  # BT.709 primaries, transfer, matrix.
        self.assertIn("assertFixtureColorFormat(format)", source)
        for key, value in (
            ("STANDARD", "STANDARD_BT709"),
            ("RANGE", "RANGE_LIMITED"),
            ("TRANSFER", "TRANSFER_SDR_VIDEO"),
        ):
            self.assertIn(f"MediaFormat.KEY_COLOR_{key} to MediaFormat.COLOR_{value}", source)
        self.assertIn('outputFormat != "unobserved"', source)

    def test_decoder_targets_gpu_input_and_sampler_targets_visible_surface(self):
        session = (DATA / "AndroidCcapiRtpSession.kt").read_text()
        gpu = (DATA / "NativeRtpLutRenderer.kt").read_text()
        self.assertIn("codec.configure(createNativeRtpAvcFormat(configuration, codec), codecSurface, null, 0)", session)
        self.assertIn("renderer.inputSurface()", session)
        self.assertIn("eglCreateWindowSurface(display, config, target", gpu)
        self.assertIn("source.getTransformMatrix(transform)", gpu)
        self.assertIn("eglSwapBuffers(display, window)", gpu)
        self.assertNotIn("target.release()", gpu)
        self.assertIn("lutRenderer?.close()", session)
        self.assertLess(session.index("codec?.release()"), session.index("lutRenderer?.close()"))

    def test_retirement_is_bounded_and_drained_before_replacement(self):
        gpu = (DATA / "NativeRtpLutRenderer.kt").read_text()
        session = (DATA / "AndroidCcapiRtpSession.kt").read_text()
        self.assertIn("pendingDraw.compareAndSet(false, true)", gpu)
        self.assertIn("presentationGate.configure(false)", gpu)
        self.assertIn("retired.await()", gpu)
        self.assertIn("withContext(NonCancellable)", gpu)
        self.assertIn("rtpSurfaceWriters.launch(scope, target, previous)", session)
        registry = (DATA / "NativeVideoLut.kt").read_text()
        self.assertIn("java.util.IdentityHashMap<Any, Job>()", registry)
        self.assertIn("priorWriter?.cancel()", registry)
        self.assertIn(
            "withContext(NonCancellable) { previous?.join(); if (additional !== previous) additional?.join() }",
            registry,
        )
        self.assertIn("currentCoroutineContext().ensureActive()", registry)
        self.assertIn("launchDrainedNativeVideoJob(scope, previous, priorWriter, block)", registry)
        self.assertIn("if (writers[target] === replacement) writers.remove(target)", registry)
        gate = (DATA / "NativeRtpDecoderSeed.kt").read_text()
        self.assertIn("val inputGate = NativeRtpDecoderInputGate(configuration)", session)
        self.assertIn("inputGate.admit(it.identity, renderingGeneration.get()", session)
        self.assertIn("continuity.admit(identity.frameId, keyFrame)", gate)
        self.assertIn("continuity.dropped()", gate)
        self.assertIn("inputGate.dropped()", session)

    def test_float_lut_interpolates_before_clamping_and_color_guard_precedes_presentation(self):
        gpu = (DATA / "NativeRtpLutRenderer.kt").read_text()
        session = (DATA / "AndroidCcapiRtpSession.kt").read_text()
        self.assertIn("GLES30.GL_RGBA32F", gpu)
        self.assertIn("texelFetch(cube", gpu)
        self.assertIn("nativeLutH264ProfileSupported(configuration.csd0)", session)
        self.assertIn("NativeVideoLutStatus.UNSUPPORTED_COLOR", session)
        self.assertIn("decodeSurface(targetSurface, generation, null, bypassGpu = true)", session)
        fallback = session.split("catch (fallback: LutFallback)", 1)[1].split("private suspend fun decodeSurface", 1)[0]
        self.assertIn("if (ownsDecoder(targetSurface, generation))", fallback)
        owner = session.split("private fun ownsDecoder(", 1)[1].split("private suspend fun awaitDecoderSeed", 1)[0]
        self.assertIn("!closed.get() && !terminalFailure.get() && decoderGeneration.get() == generation", owner)
        self.assertIn("surface.get() === targetSurface", owner)
        self.assertIn("targetSurface == null || targetSurface.isValid", owner)
        self.assertIn("videoLutState.value = fallback.reason", fallback)

    def test_native_fixture_retains_numeric_assertions_and_bounded_failure_evidence(self):
        fixture = (
            ROOT / "android/app/src/androidTest/java/dev/openeos/control/data/NativeRtpLutRenderingTest.kt"
        ).read_text()
        self.assertIn("kotlin.math.abs(before[channel] - expected[index][channel]) <= 18", fixture)
        self.assertIn("kotlin.math.abs(after[channel] - before[2 - channel]) <= 3", fixture)
        self.assertIn("kotlin.math.abs(transformed[channel] - numeric[channel] * 255f) <= 6f", fixture)
        self.assertIn("positions.joinToString", fixture)
        self.assertIn("val format = codec.outputFormat", fixture)
        self.assertIn("outputFormat = describeFormat(format)", fixture)
        self.assertIn("decoder = codec.name.take(96)", fixture)
        self.assertIn("NATIVE_RTP_LUT ${evidence.joinToString", fixture)
        self.assertNotIn("format.toString()", fixture)
        self.assertNotIn("takeScreenshot", fixture)

    def test_native_fixture_and_production_preserve_the_system_decoder_choice(self):
        fixture = (
            ROOT / "android/app/src/androidTest/java/dev/openeos/control/data/NativeRtpLutRenderingTest.kt"
        ).read_text()
        session = (DATA / "AndroidCcapiRtpSession.kt").read_text()
        factory = (DATA / "AndroidNativeRtpDecoder.kt").read_text()
        self.assertIn("codec = createNativeRtpAvcDecoder()", fixture)
        self.assertIn("codec = createNativeRtpAvcDecoder()", session)
        self.assertNotIn("MediaCodec.createDecoderByType", fixture)
        self.assertIn(
            'internal fun createNativeRtpAvcDecoder(): MediaCodec = MediaCodec.createDecoderByType("video/avc")',
            factory,
        )
        self.assertEqual(factory.count("MediaCodec.createDecoderByType"), 1)
        for source in (factory, session, fixture):
            self.assertNotIn("MediaCodec.createByCodecName", source)
            self.assertNotIn("MediaCodecList", source)
        self.assertNotIn("Build.HARDWARE", factory)
        self.assertFalse((DATA / "NativeRtpDecoderSelection.kt").exists())

    def test_one_access_unit_remains_a_live_output_contract_without_eos_or_extra_input(self):
        fixture = (
            ROOT / "android/app/src/androidTest/java/dev/openeos/control/data/NativeRtpLutRenderingTest.kt"
        ).read_text()
        journey = (
            ROOT / "android/app/src/androidTest/java/dev/openeos/control/data/AndroidRtpPresentationJourneyTest.kt"
        ).read_text()
        session = (DATA / "AndroidCcapiRtpSession.kt").read_text()
        single = journey.split(
            "@Test fun oneAccessUnitDrainsDecodedOutputWithoutWaitingForAnotherPacket()", 1
        )[1].split("@Test", 1)[0]
        self.assertEqual(single.count("sender.send("), 1)
        self.assertIn("session.awaitReady(4_000)", single)
        self.assertIn("assertEquals(1L, session.videoStatus.accessUnitsReceived)", single)
        self.assertIn("assertEquals(1L, session.videoStatus.decodedFrames)", single)
        self.assertLess(single.index("session.awaitReady"), single.index("session.close()"))
        first_picture = fixture.split("private suspend fun render(", 1)[1].split(
            "if (swap && overrideLut == null)", 1
        )[0]
        self.assertEqual(first_picture.count("codec.queueInputBuffer("), 1)
        self.assertIn("codec.queueInputBuffer(index, 0, picture.size, 1_000, 0)", first_picture)
        self.assertIn('assertEquals("Decoder/GPU did not present fixture", 0L, completed.count)', first_picture)
        self.assertIn("withTimeoutOrNull(DECODER_OUTPUT_POLL_MILLIS) { accessUnits.receive() }", session)
        factory_source = (DATA / "AndroidNativeRtpDecoder.kt").read_text()
        for source in (factory_source, session, fixture, journey):
            self.assertNotIn("BUFFER_FLAG_END_OF_STREAM", source)
        self.assertNotIn("queueInputBuffer", factory_source)

    def test_native_fixture_retires_prior_captures_when_a_later_phase_fails(self):
        fixture = (
            ROOT / "android/app/src/androidTest/java/dev/openeos/control/data/NativeRtpLutRenderingTest.kt"
        ).read_text()
        self.assertLess(
            fixture.index("val captured = mutableListOf<Bitmap>()"), fixture.index('render(target, "identity"')
        )
        self.assertIn("captured.forEach(Bitmap::recycle)", fixture)
        self.assertIn("if (primaryFailure != null || retired.isFailure) captured?.recycle()", fixture)
        self.assertIn("primaryFailure?.addSuppressed(cleanupFailure) ?: throw cleanupFailure", fixture)


if __name__ == "__main__":
    unittest.main()
