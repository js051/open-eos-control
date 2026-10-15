"""Integrity checks only: these do not execute a JPEG parser or native decoder."""
from __future__ import annotations

import base64
import hashlib
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
CONTRACT = ROOT / 'contracts/jpeg-original/v1/jpeg-original-vectors.json'
EXPECTED_SHA256 = '639e85317c0d5fbb72f4f5f8ebccfa696593c7c41881f43900021ccd30f1f6d1'


class JPEGOriginalContractIntegrityTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.document = json.loads(CONTRACT.read_text(encoding='utf-8'))

    def test_fixed_bytes_and_schema(self):
        self.assertEqual(hashlib.sha256(CONTRACT.read_bytes()).hexdigest(), EXPECTED_SHA256)
        self.assertEqual(self.document['schemaVersion'], 1)
        self.assertEqual(self.document['contract'], 'automatic-original-jpeg-v1')

    def test_bounded_policy(self):
        self.assertEqual(self.document['policy'], {
            'maxEncodedBytes': 64 * 1024 * 1024,
            'maxPixels': 64_000_000,
            'maxDimension': 16384,
            'maxMarkers': 4096,
            'maxScans': 256,
            'allowedSofMarkers': [192, 193, 194],
            'precision': 8,
            'allowedComponentCounts': [1, 3, 4],
            'decodeTargetLongest': 1024,
            'maxDecodedDimension': 2048,
            'maxMimeBytes': 4096,
        })

    def test_fixed_counts_and_unique_ids(self):
        for field, count in [('byteVectors', 71), ('mimeVectors', 34), ('headerVectors', 6)]:
            rows = self.document[field]
            self.assertEqual(len(rows), count)
            self.assertEqual(len({row['id'] for row in rows}), count)

    def test_byte_hashes_and_truth_types(self):
        for row in self.document['byteVectors']:
            with self.subTest(row=row['id']):
                body = base64.b64decode(row['base64'], validate=True)
                self.assertEqual(len(body), row['byteCount'])
                self.assertEqual(hashlib.sha256(body).hexdigest(), row['sha256'])
                self.assertIs(type(row['structuralValid']), bool)
                self.assertIs(type(row['decodedValid']), bool)
                if row['decodedValid']:
                    self.assertTrue(row['structuralValid'])
                    self.assertGreater(row['width'], 0)
                    self.assertGreater(row['height'], 0)

    def test_metadata_is_not_decoder_proof(self):
        rows = self.document['byteVectors']
        self.assertEqual(sum(row['decodedValid'] for row in rows), 11)
        framed_partial = [row for row in rows if row['structuralValid'] and not row['decodedValid']]
        self.assertEqual({row['id'] for row in framed_partial}, {
            f'premature-entropy-eoi-{count}' for count in (1, 3, 10, 100)
        })
        for row in framed_partial:
            body = base64.b64decode(row['base64'])
            self.assertTrue(body.startswith(b'\xff\xd8'))
            self.assertTrue(body.endswith(b'\xff\xd9'))

    def test_present_empty_header_fails_closed(self):
        rows = {row['id']: row for row in self.document['mimeVectors']}
        self.assertTrue(rows['absent']['valid'])
        self.assertIsNone(rows['absent']['raw'])
        self.assertFalse(rows['empty']['valid'])
        self.assertFalse(rows['spaces']['valid'])
        self.assertFalse(rows['comma-joined']['valid'])
        headers = {row['id']: row for row in self.document['headerVectors']}
        self.assertFalse(headers['duplicate-identical']['valid'])
        self.assertFalse(headers['duplicate-conflicting']['valid'])

    def test_fixture_coverage_keeps_native_formats_and_limits(self):
        ids = {row['id'] for row in self.document['byteVectors']}
        self.assertTrue({
            'rgb-progressive', 'cmyk-baseline', 'sof1-eight-bit', 'exif-orientation6',
            'restart-markers', 'opaque-marker-bytes', 'side-over-cap', 'pixels-over-cap',
            'too-many-markers', 'missing-huffman-tables', 'missing-quantization-tables',
            'noninterleaved-sampling-four-by-four',
        }.issubset(ids))


if __name__ == '__main__':
    unittest.main()
