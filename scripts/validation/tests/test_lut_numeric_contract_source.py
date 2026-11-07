"""Shared-fixture/source checks, never a substitute for native image execution."""

import json
import math
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
APP = ROOT / "ios/OpenEOSControl/App"


class LutNumericContractSourceTests(unittest.TestCase):
    def fixture(self):
        raw = (ROOT / "contracts/live-view-lut/v1/numeric-vectors.json").read_bytes()
        self.assertLessEqual(len(raw), 64 * 1024)
        data = json.loads(raw)
        self.assertEqual(data["schemaVersion"], 1)
        self.assertEqual(data["colorEncoding"], "srgb-display-values")
        self.assertEqual(data["interpolation"], "trilinear")
        self.assertEqual(data["tableOrder"], "red-fastest")
        self.assertEqual(data["cpuAbsoluteTolerance"], 0.000002)
        self.assertEqual(len(data["vectors"]), 12)
        self.assertEqual(len({v["id"] for v in data["vectors"]}), 12)
        return data

    def test_fixed_expectations_match_independent_weighted_corner_oracle(self):
        data = self.fixture()
        tables = {lut["id"]: lut for lut in data["luts"]}
        for vector in data["vectors"]:
            lut = tables[vector["lutId"]]
            size = lut["size"]
            self.assertIn(size, (2, 3))
            self.assertEqual(len(lut["values"]), size**3 * 3)
            self.assertTrue(all(math.isfinite(v) for v in lut["values"]))
            positions = [
                min(1, max(0, (v - lo) / (hi - lo))) * (size - 1)
                for v, lo, hi in zip(vector["inputRgb"], lut["domainMin"], lut["domainMax"], strict=True)
            ]
            corners = [[(math.floor(v), 1 - (v % 1)), (min(size - 1, math.floor(v) + 1), v % 1)] for v in positions]
            total = [0.0, 0.0, 0.0]
            for red, rw in corners[0]:
                for green, gw in corners[1]:
                    for blue, bw in corners[2]:
                        index = (red + size * green + size * size * blue) * 3
                        for channel in range(3):
                            total[channel] += lut["values"][index + channel] * rw * gw * bw
            for actual, expected in zip(total, vector["expectedRgb"], strict=True):
                self.assertAlmostEqual(min(1, max(0, actual)), expected, places=10, msg=vector["id"])

    def test_byte_vectors_are_fixed_and_cover_all_six_table_shapes(self):
        data = self.fixture()
        vectors = [v for v in data["vectors"] if "inputRgb8" in v]
        self.assertEqual(len(vectors), 6)
        self.assertEqual({v["lutId"] for v in vectors}, {lut["id"] for lut in data["luts"]})
        for vector in vectors:
            for value, encoded in zip(vector["inputRgb"], vector["inputRgb8"], strict=True):
                self.assertAlmostEqual(value, encoded / 255, places=12)
            self.assertEqual([math.floor(v * 255 + 0.5) for v in vector["expectedRgb"]], vector["expectedRgb8"])

    def test_decoded_and_native_routes_share_numeric_filter_and_context(self):
        cube = (APP / "CubeLut.swift").read_text()
        native = (APP / "IOSRTPVideoProcessing.swift").read_text()
        decoded = cube.split("func renderCubeLutPreview(", 1)[1].split("\nlet maximumCubeLutBytes", 1)[0]
        self.assertIn("applyCubeLutPreview(input, lut: lut)", decoded)
        self.assertIn("format: .RGBA8, colorSpace: cubeLutPreviewOutputColorSpace", decoded)
        self.assertIn("let value = cubeLutPreviewContext()", cube)
        self.assertIn("cubeLutPreviewContext(device: device)", native)
        self.assertIn("applyCubeLutPreview(input, lut: lut)", native)
        self.assertNotIn('CIFilter(name: "CIColorCube")', cube)

    def test_native_tests_load_same_resource_and_require_actual_gpu(self):
        project = (ROOT / "ios/OpenEOSControl/project.yml").read_text()
        tests = (ROOT / "ios/OpenEOSControl/Tests/CubeLutNumericContractTests.swift").read_text()
        self.assertIn("../../contracts/live-view-lut/v1/numeric-vectors.json", project)
        self.assertIn('url(forResource: "numeric-vectors", withExtension: "json")', tests)
        self.assertIn("XCTUnwrap(MTLCreateSystemDefaultDevice()", tests)
        self.assertIn("renderCubeLutPreview(data: png, lut: lut)", tests)
        self.assertIn("nativeRTPApplyLut(CIImage(cgImage: source), lut: lut)", tests)
        self.assertIn("CGImageSourceCreateImageAtIndex", tests)
        self.assertNotIn("XCTSkip", tests)


if __name__ == "__main__":
    unittest.main()
