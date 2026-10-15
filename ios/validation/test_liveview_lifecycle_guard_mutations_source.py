"""In-memory negative source fixtures only; no Swift/native/product code executes.

Each fixture first passes the actual source through the exact production-source
assertion, then removes or bypasses one real guard and requires rejection.
"""
import runpy
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / 'ios/OpenEOSControl/App'
CORE = ROOT / 'ios/OpenEOSCore/Sources/OpenEOSCore'
GUARDS = runpy.run_path(str(Path(__file__).with_name('test_liveview_lifecycle_source.py')))


def replace_once(source, old, new):
    if source.count(old) != 1:
        raise AssertionError(f'Negative fixture anchor must match once: {old!r}')
    return source.replace(old, new, 1)


class LiveViewSourceGuardMutationTests(unittest.TestCase):
    def test_readiness_guard_removal_is_rejected(self):
        client = (CORE / 'CCAPIClient.swift').read_text()
        native = (APP / 'IOSCcapiRTP.swift').read_text()
        check = GUARDS['_assert_rtp_decoder_readiness']
        check(client, native)
        fixtures = [
            ('skip actual readiness wait', 'client', 'try await session.waitUntilReady()', '/* omitted readiness wait */'),
            ('publish without decoded policy', 'client', 'try requireLiveViewInputReady(owner: stop.id, decoded: true)', '/* omitted decoded policy */'),
            ('accept nondecoded probe', 'native', 'guard case .decoded = result else', 'if false'),
            ('skip physical probe retirement', 'native', 'await self.readinessDecoder.retireAndWait()', '/* omitted physical retirement */'),
            ('lose close cancellation', 'native', 'readiness.fail(CancellationError())', '/* omitted gate cancellation */'),
            ('accept late terminal probe', 'native', 'guard let self, !self.stateLock.synchronized({ self.closed || self.videoFailureReported }) else { return }', 'guard let self else { return }'),
        ]
        for name, target, old, new in fixtures:
            with self.subTest(name=name):
                changed = replace_once(client if target == 'client' else native, old, new)
                with self.assertRaises((AssertionError, ValueError, IndexError)):
                    check(changed if target == 'client' else client, changed if target == 'native' else native)

    def test_decode_only_guard_removal_is_rejected(self):
        value = (APP / 'IOSRTPVideoProcessing.swift').read_text()
        check = GUARDS['_assert_decode_only_precedes_processing']
        check(value)
        fixtures = [
            ('ignore decoder status', 'guard status == noErr, decodedOutput.status == noErr else { return .failed }', '/* ignored decode status */'),
            ('claim readiness without pixels', 'guard let decoded = decodedOutput.buffer, let decodedAt = decodedOutput.completedAt else { return .backpressure }', '/* absent decoder-output guard */'),
            ('Metal before decode-only return', 'if request.decodeOnly { return .decoded(at: decodedAt) }', 'let prematureDevice = MTLCreateSystemDefaultDevice()\n        if request.decodeOnly { return .decoded(at: decodedAt) }'),
            ('rejuvenate decoder timestamp at delivery', 'if request.decodeOnly { return .decoded(at: decodedAt) }', 'if request.decodeOnly { return .decoded(at: ProcessInfo.processInfo.systemUptime) }'),
            ('gate unfiltered readiness on LUT color', 'if request.lut != nil && !nativeRTPPreviewColorSupported(', 'if !nativeRTPPreviewColorSupported('),
            ('claim completion without actual output', 'if image != nil { decodedOutput.completedAt = ProcessInfo.processInfo.systemUptime }', 'decodedOutput.completedAt = ProcessInfo.processInfo.systemUptime'),
        ]
        for name, old, new in fixtures:
            with self.subTest(name=name):
                changed = replace_once(value, old, new)
                with self.assertRaises((AssertionError, ValueError, IndexError)):
                    check(changed)

    def test_renderer_identity_and_progress_guard_removal_is_rejected(self):
        state = (APP / 'CameraAppState.swift').read_text()
        native = (APP / 'IOSCcapiRTP.swift').read_text()
        progress = (APP / 'IOSRTPPresentationProgress.swift').read_text()
        check = GUARDS['_assert_renderer_progress_evidence']
        check(state, native, progress)
        fixtures = [
            ('non-nil readback replaces exact identity', 'native', '$0.frame.pixelBuffer === pixels', 'true'),
            ('accept retired output owner', 'native', 'progress.owns(output.token) else', 'true else'),
            ('duplicate frame renews progress', 'progress', 'presented.map({ frame.sequence > $0.sequence })', 'presented.map({ frame.sequence >= $0.sequence })'),
            ('unknown readback renews presentation clock', 'progress', 'state = .unknown', 'state = .unknown; lastPresentedAt = now'),
            ('AppState ignores progress failure', 'state', 'self.rtpController.videoProgressFailure() != nil', 'false'),
            ('receipt time replaces actual decode completion', 'native', 'recordDecoded(token, at: frame.decodedAt)', 'recordDecoded(token, at: now)'),
        ]
        sources = {'state': state, 'native': native, 'progress': progress}
        for name, target, old, new in fixtures:
            with self.subTest(name=name):
                changed = dict(sources)
                changed[target] = replace_once(sources[target], old, new)
                with self.assertRaises((AssertionError, ValueError, IndexError)):
                    check(changed['state'], changed['native'], changed['progress'])


if __name__ == '__main__':
    unittest.main()
