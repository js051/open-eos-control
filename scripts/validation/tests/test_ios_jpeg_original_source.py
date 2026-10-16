"""iOS original-JPEG wiring/source guards only; never Swift or ImageIO execution."""
import base64
import hashlib
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
CORE = ROOT / 'ios/OpenEOSCore/Sources/OpenEOSCore'
APP = ROOT / 'ios/OpenEOSControl/App'
TESTS = ROOT / 'ios/OpenEOSControl/Tests'
HASH = '639e85317c0d5fbb72f4f5f8ebccfa696593c7c41881f43900021ccd30f1f6d1'


class IOSJPEGOriginalSourceTests(unittest.TestCase):
    def test_frozen_shared_corpus_and_native_resource_are_exact(self):
        path = ROOT / 'contracts/jpeg-original/v1/jpeg-original-vectors.json'
        raw = path.read_bytes()
        self.assertEqual(len(raw), 310772)
        self.assertEqual(hashlib.sha256(raw).hexdigest(), HASH)
        contract = json.loads(raw)
        self.assertEqual(len(contract['byteVectors']), 71)
        self.assertEqual(len(contract['mimeVectors']), 34)
        self.assertEqual(len(contract['headerVectors']), 6)
        for vector in contract['byteVectors']:
            data = base64.b64decode(vector['base64'], validate=True)
            self.assertEqual(len(data), vector['byteCount'])
            self.assertEqual(hashlib.sha256(data).hexdigest(), vector['sha256'])
        project = (APP.parent / 'project.yml').read_text()
        self.assertEqual(project.count('../../contracts/jpeg-original/v1/jpeg-original-vectors.json'), 1)
        self.assertIn('- path: ../../contracts/jpeg-original/v1/jpeg-original-vectors.json\n'
                      '        createIntermediateGroups: false\n        buildPhase: resources', project)

    def test_only_automatic_original_calls_validator_after_eof_before_publish(self):
        source = (CORE / 'CCAPIClient.swift').read_text()
        method = source.split('public func downloadForegroundOriginal(', 1)[1]
        method = method.split('private func foregroundTransport()', 1)[0]
        ordered = ['CameraMediaType.isAutomaticJPEG(item)', 'CameraOriginalJPEG.maximumBytes',
                   'strictTransport.downloadForeground(',
                   'defer { try? FileManager.default.removeItem(at: response.temporaryFileURL) }',
                   'CameraHTTPResponseIntegrity.validateOriginalMedia(response)', 'size == expectedBytes',
                   'CameraOriginalJPEG.validate(', 'try budget.remaining()', 'try FileManager.default.moveItem(',
                   'return CameraMediaDownload(']
        last = -1
        for token in ordered:
            current = method.index(token, last + 1)
            self.assertGreater(current, last)
            last = current
        self.assertEqual(method.count('strictTransport.downloadForeground('), 1)
        self.assertNotIn('freshInfo(', method)
        self.assertNotIn('shutter', method.lower())
        self.assertEqual(source.count('CameraOriginalJPEG.validate('), 1)
        self.assertNotIn('CameraOriginalJPEG', (CORE / 'DesktopBridgeClient.swift').read_text())
        transport = (CORE / 'CameraForegroundHTTPTask.swift').read_text()
        finish = transport.split('private func finish(error:', 1)[1]
        self.assertLess(finish.index('try file?.close()'), finish.index('continuation.resume(returning: response)'))

    def test_mime_evidence_is_preserved_before_normalized_lookup(self):
        source = (CORE / 'CameraHTTPTransport.swift').read_text()
        download = source.split('public struct CameraHTTPDownloadResponse:', 1)[1]
        download = download.split('public struct CameraHTTPUploadResponse:', 1)[0]
        self.assertLess(download.index('contentTypeValues = headers.filter'),
                        download.index('self.headers = headers.reduce'))
        foreground = (CORE / 'CameraForegroundHTTPTask.swift').read_text()
        self.assertIn('result[String(describing: entry.key)]', foreground)
        validator = (CORE / 'CameraOriginalJPEG.swift').read_text()
        for token in ('values.count <= 1', '!value.isEmpty', '(32...126).contains($0)',
                      'value.utf8.count <= maximumMIMEBytes', 'index < bytes.count', 'guard closed',
                      'token() == nil', '"image/jpeg", "application/octet-stream"'):
            self.assertIn(token, validator)
        self.assertNotIn('split(separator: ";")', validator)

    def test_closed_file_read_is_bounded_unmapped_and_identity_checked(self):
        source = (CORE / 'CameraOriginalJPEG.swift').read_text()
        method = source.split('static func validate(fileURL:', 1)[1].split('private static func sameFile', 1)[0]
        for token in ('expectedBytes <= Int64(maximumBytes)', 'O_RDONLY | O_NOFOLLOW | O_CLOEXEC',
                      'fstat(descriptor, &before)', 'mode_t(S_IFREG)', 'before.st_size == expectedBytes',
                      'read(upToCount: min(64 * 1024', 'read(upToCount: 1)',
                      'fstat(descriptor, &after)', 'lstat(fileURL.path, &path)',
                      'sameFile(before, after)', 'sameFile(before, path)', 'Task.checkCancellation()'):
            self.assertIn(token, method)
        for forbidden in ('Data(contentsOf:', '.mappedIfSafe', 'write(', 'removeItem(', 'UIImage('):
            self.assertNotIn(forbidden, source)

    def test_native_decode_requires_jpeg_complete_status_and_real_bounded_pixels(self):
        source = (CORE / 'CameraOriginalJPEG.swift').read_text()
        decoder = source.split('static func decode(', 1)[1].split('private struct Component', 1)[0]
        self.assertEqual(decoder.count('CGImageSourceGetStatus(source) == .statusComplete'), 2)
        self.assertEqual(decoder.count('CGImageSourceGetStatusAtIndex(source, 0) == .statusComplete'), 2)
        for token in ('CGImageSourceCreateWithData', 'CGImageSourceGetCount(source) == 1', '"public.jpeg"',
                      'kCGImagePropertyPixelWidth', 'kCGImagePropertyPixelHeight',
                      'kCGImageSourceCreateThumbnailFromImageAlways: true',
                      'kCGImageSourceCreateThumbnailFromImageIfAbsent: false',
                      'kCGImageSourceThumbnailMaxPixelSize: decodeLongestSide',
                      'kCGImageSourceShouldCacheImmediately: true', 'CGImageSourceCreateThumbnailAtIndex',
                      'image.width <= maximumDecodedSide', 'image.height <= maximumDecodedSide',
                      'CGContext(', 'raster.draw(image', 'raster.data != nil', 'throw Failure.unsupported'):
            self.assertIn(token, decoder)
        self.assertNotIn('CGImageDestination', source)

    def test_parser_bounds_and_coding_declarations_remain_explicit(self):
        source = (CORE / 'CameraOriginalJPEG.swift').read_text()
        for token in ('maximumBytes = 64 * 1024 * 1024', 'maximumPixels: Int64 = 64_000_000',
                      'maximumSide = 16_384', 'maximumMarkers = 4_096', 'maximumScans = 256',
                      'decodeLongestSide = 1_024', 'maximumDecodedSide = 2_048',
                      'bytes[0] == 0xFF, bytes[1] == 0xD8', 'position == bytes.count',
                      'length <= bytes.count - position', 'frame == nil', 'bytes[start] == 8',
                      '[1, 3, 4].contains(count)', 'case 0xC0, 0xC1, 0xC2:',
                      'markers <= maximumMarkers', 'scans <= maximumScans',
                      'guard value > 0', 'guard available > 0', 'symbols.insert(symbol).inserted',
                      'selected.insert(id).inserted', 'quantization[component.quantization]',
                      'huffman.contains(dc)', 'huffman.contains(0x10 | ac)',
                      'ss == 0, se == 63, ah == 0, al == 0',
                      'restartInterval > 0, sinceRestart > 0, marker == nextRestart',
                      'count > 0, sinceRestart > 0', 'sequentialSeen.contains',
                      'case 0xE0...0xEF, 0xFE: break', 'throw Failure.unsupported'):
            self.assertIn(token, source)

    def test_native_tests_assert_real_decode_counterexamples_without_skips(self):
        source = (TESTS / 'JPEGOriginalContractTests.swift').read_text()
        for token in (HASH, 'CameraOriginalJPEG.validateContentTypes(', 'CameraOriginalJPEG.validateStructure(',
                      'CameraOriginalJPEG.validate(fileURL:', 'CameraOriginalJPEG.decode(data, dimensions:',
                      'testPrematureEntropyEOIMustFailActualImageIODecodeDespiteValidStructure',
                      'testDimensionBoundaryIsCheckedBeforeAnyNativeDecode',
                      'testClosedFileSymlinkLengthAndPreCancelledInputsFailWithoutModification',
                      'XCTAssertEqual(try Data(contentsOf: url), data'):
            self.assertIn(token, source)
        self.assertNotIn('XCTSkip', source)
        self.assertNotIn('XCTExpectFailure', source)

    def test_recovery_tests_preserve_files_history_and_exclude_all_old_ids(self):
        source = (TESTS / 'ForegroundMediaIntegrationTests.swift').read_text()
        name = 'func testInvalidOriginalKeepsPriorFilesAndFailedHistoryThenExplicitReenableExcludesOldIDs()'
        method = source.split(name, 1)[1]
        method = method.split('func testFreshTypeDrift', 1)[0]
        for token in ('["bytes", "response-mime"]', 'foregroundMedia.state, .failed',
                      'foregroundMedia.preparedCount, 1', 'foregroundMedia.copiedCount, 1',
                      'foregroundTemporaryCount, 0', 'failed.externalAttempts.count, 1',
                      'baselineRequests, stopped', 'finalOriginals.count, 3',
                      'requests.allSatisfy { $0.method == "GET" }', 'Data(contentsOf: goodURL)',
                      'mediaDeliverySnapshot.records', 'returnedTemporaryFiles()'):
            self.assertIn(token, method)
        self.assertIn('static let original = try! JPEGOriginalTestVectors.bytes("rgb-baseline")', source)
        state = (APP / 'CameraAppState.swift').read_text()
        failure = state.split('if !validOriginal {', 1)[1].split('// finishPrepared', 1)[0]
        self.assertIn('finishFailure(ticket, receiptID: receipt, cancelled: cancelled)', failure)
        self.assertIn('foregroundMediaOwner == owner, sessionGeneration == generation', failure)
        self.assertIn('await refreshMediaDeliveryHistory()', failure)

    def test_manual_and_plan_gates_still_preserve_existing_scope(self):
        self.assertNotIn('CameraOriginalJPEG', (CORE / 'DesktopBridgeClient.swift').read_text())
        plan = (APP / 'ForegroundMediaPlan.swift').read_text()
        for token in ('firstSize == size', 'observationInterval', 'known.formUnion(current)',
                      'CameraMediaType.admitsFreshAutomaticJPEG', 'baselineEstablished'):
            self.assertIn(token, plan)
        controller = (APP / 'ForegroundMediaController.swift').read_text()
        self.assertIn('try await actions.prepare(fresh, size)', controller)
        self.assertIn('try plan.recordPrepared(fresh)', controller)
        after_prepare = controller.split('try await actions.prepare(fresh, size)', 1)[1]
        self.assertNotIn('freshInfo(', after_prepare)
        self.assertIn('state = .failed', after_prepare)
        self.assertNotIn('CameraOriginalJPEG', (APP / 'ForegroundFilesDelivery.swift').read_text())


if __name__ == '__main__':
    unittest.main()
