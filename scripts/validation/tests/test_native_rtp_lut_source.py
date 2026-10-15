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
        self.assertIn('CIFilter(name: "CIColorCubeWithColorSpace")', pipeline)
        self.assertIn("context.render(image, to: target", pipeline)
        self.assertIn("CMSampleBufferCreateReadyWithImageBuffer", pipeline)
        self.assertIn("output.enqueue(frame.sampleBuffer)", session)
        self.assertIn("suppliedBuffer: IOSRTPPixelMonitor.supported ? nil : frame.pixelBuffer", session)

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
        pipeline = (APP / "IOSRTPVideoProcessing.swift").read_text()
        tests = (APP.parent / "Tests/RTPNativeLutRenderingTests.swift").read_text()
        self.assertIn("CGColorSpace(name: CGColorSpace.extendedSRGB)", pipeline)
        self.assertIn(".workingColorSpace: nativeRTPLutWorkingColorSpace", pipeline)
        self.assertIn('cube.setValue(nativeRTPLutWorkingColorSpace, forKey: "inputColorSpace")', pipeline)
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
        self.assertIn("kCMSampleAttachmentKey_DoNotDisplay", session)
        self.assertIn("present: presentFrame", session)
        self.assertIn("guard renderer.isReadyForMoreMediaData", session)
        self.assertLess(pipeline.index("VTDecompressionSessionDecodeFrame"), pipeline.index("guard request.present"))
        self.assertNotIn("applyCubeLut", monitor)
        self.assertNotIn("cubeLut = nil", monitor)


if __name__ == "__main__":
    unittest.main()
