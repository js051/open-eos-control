"""Structural guards only: no Swift/native runtime claims."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / 'ios/OpenEOSControl/App/CameraAppState.swift'


def drive(text):
    return text.split('    func driveFocus(direction:', 1)[1].split('    func setLiveViewMagnification(', 1)[0]


def assert_publication_owner(method):
    assert method.index('let lifetime = liveViewLifetime') < method.index('try await session.driveFocus(')
    assert method.count('guard generation == sessionGeneration, lifetime == liveViewLifetime else { return }') == 2
    success, failure = method.split('} catch {', 1)
    assert success.index('lifetime == liveViewLifetime') < success.index('showFocusMarker(x: result.direction')
    assert failure.index('lifetime == liveViewLifetime') < failure.index('record(error)')


class FocusDriveOwnerSourceTests(unittest.TestCase):
    def test_success_and_failure_require_captured_live_view_owner(self):
        assert_publication_owner(drive(APP.read_text()))

    def test_retired_publication_does_not_leak_focus_category(self):
        method = drive(APP.read_text())
        self.assertIn('defer { end(.focus, generation: generation) }', method)
        self.assertNotIn('end(.liveView', method)
        self.assertNotIn('retryLiveViewStop', method)
        self.assertEqual(method.count('try await session.driveFocus('), 1)

    def test_removing_either_lifetime_guard_is_rejected(self):
        method = drive(APP.read_text())
        assert_publication_owner(method)
        guard = 'guard generation == sessionGeneration, lifetime == liveViewLifetime else { return }'
        for index in (0, 1):
            offset = method.index(guard) if index == 0 else method.rindex(guard)
            mutated = method[:offset] + 'guard generation == sessionGeneration else { return }' + method[offset + len(guard):]
            with self.assertRaises((AssertionError, ValueError)):
                assert_publication_owner(mutated)

    def test_background_and_terminal_retirement_change_same_session_lifetime(self):
        source = APP.read_text()
        retirement = source.split('private func retireLiveView(reason:', 1)[1].split('private func beginLiveViewRenderWatch', 1)[0]
        self.assertIn('liveViewLifetime = retirement', retirement)
        self.assertIn('clearLiveViewPresentation()', retirement)
        self.assertIn('if let reason { lastError = reason }', retirement)
        self.assertIn('retireLiveView(reason: error.localizedDescription)', source)
        background = source.split('func setApplicationActive(', 1)[1].split('func startLiveView()', 1)[0]
        self.assertIn('retireLiveView(reason: nil)', background)


if __name__ == '__main__':
    unittest.main()
