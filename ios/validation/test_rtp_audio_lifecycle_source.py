"""Source/wiring assertions only. Does not compile Swift or execute an audio device."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
APP = ROOT / 'ios/OpenEOSControl/App'
TESTS = ROOT / 'ios/OpenEOSControl/Tests'


def read(name):
    return (APP / name).read_text()


def section(text, start, end):
    return text.split(start, 1)[1].split(end, 1)[0]


class RTPAudioSourceTests(unittest.TestCase):
    def test_native_callback_is_player_rendered_not_consumed_or_schedule_count(self):
        native = read('IOSCcapiRTPAudio.swift')
        self.assertIn('completionCallbackType: .dataRendered', native)
        self.assertNotIn('completionCallbackType: .dataConsumed', native)
        self.assertNotIn('playedSampleFrames', native)
        callback = section(native, 'output.schedule(pcm)', 'return result(format, inputs: inputs, pcm: frames, submitted: frames)')
        for required in ['self.token == expected', 'self.outputIncarnation == scheduledIncarnation',
                         'removeValue(forKey: bufferID)', 'renderedAt < deadline',
                         'completion == .rendered', 'self.output.supportsRenderProgress']:
            self.assertIn(required, callback)
        self.assertIn('self.callbackQueue.async', callback)

    def test_owner_and_epoch_fence_queue_and_status(self):
        controller = read('IOSCcapiRTP.swift')
        submit = section(controller, 'func submitAudio(', 'private func recordAudioResult(')
        self.assertIn('activeAudioSessionID == sessionID', submit)
        self.assertIn('let token = activeAudioToken', submit)
        self.assertIn('self.isActiveAudioToken(token)', submit)
        self.assertIn('consume(accessUnit, token: token, expiresAt: expiresAt)', submit)
        self.assertIn('delivery.audioEpoch != audioEpoch', controller)
        self.assertIn('activeAudioToken != token', controller)
        self.assertIn('audioEpoch: audioEpoch', controller)

    def test_output_retirement_precedes_stop_and_decoder_drain_is_serial(self):
        pipeline = read('IOSCcapiRTPAudio.swift')
        retire = section(pipeline, 'private func retireOutputLocked()', 'private func result(')
        self.assertLess(retire.index('invalidateOutputLocked()'), retire.index('nativeQueue.async'))
        self.assertLess(retire.index('nativeQueue.async'), retire.index('output.stop()'))
        invalidate = section(pipeline, 'private func invalidateOutputLocked()', 'private func retireOutputLocked()')
        self.assertIn('outputIncarnation = UUID()', invalidate)
        self.assertIn('outputExpiryWatch?.cancel()', invalidate)
        controller = read('IOSCcapiRTP.swift')
        retire = section(controller, 'private func retireAudioLocked(', 'private func beginAudioWatchLocked(')
        self.assertIn('activeAudioToken = nil', retire)
        self.assertIn('audioPipeline.retire()', retire)
        self.assertIn('audioQueue.async { [audioPipeline] in audioPipeline.resetRetiredDecoder() }', retire)
        self.assertNotIn('unlessSessionReplaced', controller)

    def test_queue_capacity_is_global_not_reset_by_each_enable(self):
        controller = read('IOSCcapiRTP.swift')
        self.assertEqual(controller.count('queuedAudioAccessUnits = 0'), 1)
        self.assertIn('queuedAudioAccessUnits < maximumQueuedAudioAccessUnits', controller)
        self.assertIn('maximumQueuedAudioAccessUnits = 8', controller)
        pipeline = read('IOSCcapiRTPAudio.swift')
        self.assertIn('maximumScheduledAudioBuffers = 8', pipeline)
        self.assertIn('maximumPendingDecoderInputs = 64', pipeline)

    def test_delayed_converter_pcm_is_drained_without_end_of_stream(self):
        pipeline = read('IOSCcapiRTPAudio.swift')
        drain = section(pipeline, 'func drain() throws', 'private func configure(')
        self.assertIn('return try convert(nil)', drain)
        self.assertIn('inputStatus.pointee = .noDataNow', drain)
        self.assertNotIn('inputStatus.pointee = .endOfStream', drain)
        controller = read('IOSCcapiRTP.swift')
        self.assertIn('private func queueAudioDrain()', controller)
        self.assertIn('self.audioPipeline.drain(token: token)', controller)
        self.assertIn('queueAudioDrain()\n            publishAudioStatus', controller)

    def test_decoded_and_scheduled_pcm_keep_original_one_second_expiry(self):
        pipeline = read('IOSCcapiRTPAudio.swift')
        self.assertIn('pendingDecoderInputs.append((accessUnit.format.framesPerPacket, expiresAt))', pipeline)
        self.assertIn('let expiresAt = oldest.expiresAt', pipeline)
        self.assertIn('pendingBuffers[bufferID] = expiresAt', pipeline)
        self.assertIn('armOutputExpiryLocked()', pipeline)
        expiry = section(pipeline, 'private func expireOutputIfNeededLocked()', 'private func armOutputExpiryLocked()')
        self.assertIn('now() >= $0', expiry)
        self.assertIn('retireOutputLocked()', expiry)
        self.assertIn('maximumAudioAccessUnitAge: TimeInterval = 1', read('IOSCcapiRTP.swift'))

    def test_five_second_deadlines_use_pcm_and_render_events_only(self):
        controller = read('IOSCcapiRTP.swift')
        self.assertIn('audioPCMStallTimeout: TimeInterval = 5', controller)
        self.assertIn('audioFirstRenderTimeout: TimeInterval = 5', controller)
        self.assertIn('audioRenderStallTimeout: TimeInterval = 5', controller)
        poll = section(controller, 'func pollAudioProgress(', 'private func failAudio(')
        self.assertIn('lastAudioPCMAt ?? began', poll)
        self.assertIn('snapshot.lastRenderedAt', poll)
        self.assertIn('!snapshot.supportsRenderProgress', poll)
        self.assertIn('audioStatus.outputProgress = .unknown', poll)
        self.assertNotIn('lastPacketAt', poll)
        self.assertNotIn('accessUnitsReceived', poll)
        self.assertIn('lastAudioPCMAt = snapshot.lastDecodedAt', poll)
        pipeline = read('IOSCcapiRTPAudio.swift')
        self.assertLess(pipeline.index('lastDecodedAt = now()'), pipeline.index('return try nativeQueue.sync'))

    def test_background_mute_failure_require_manual_enable_and_do_not_fail_video(self):
        controller = read('IOSCcapiRTP.swift')
        background = section(controller, 'func setApplicationActive(', 'func audioListenerReady(')
        self.assertEqual(background.count('if !active { audioRequested = false }'), 2)
        retire = section(controller, 'private func retireAudioLocked(', 'private func beginAudioWatchLocked(')
        self.assertIn('audioRequested = false', retire)
        self.assertIn('audioStatus.requested = false', retire)
        audio = section(controller, 'func submitAudio(', '@MainActor\n    func enqueue(')
        self.assertNotIn('emit(.failed', audio)
        failure = section(controller, 'private func failAudio(', '@discardableResult\n    private func updateAudioStatus(')
        self.assertNotIn('emit(.failed', failure)

    def test_native_output_cleans_up_partial_prepare_and_deactivates_owned_session(self):
        output = section(read('IOSCcapiRTPAudio.swift'), 'final class IOSAVAudioEngineOutput:', 'private func audioError(')
        self.assertIn('self.engine = engine', output)
        self.assertIn('self.player = player', output)
        self.assertIn('} catch {\n            stop()\n            throw error', output)
        self.assertIn('player?.stop()', output)
        self.assertIn('engine?.stop()', output)
        self.assertIn('engine?.detach(player)', output)
        self.assertIn('if ownsAudioSession', output)

    def test_ui_diagnostics_and_toggle_do_not_claim_audibility(self):
        state = read('CameraAppState.swift')
        self.assertIn('rtpAudioRequested = status.requested', state)
        self.assertNotIn('rtpAudioPlayedFrames', state)
        for field in ['rtpAudioDecoderInputs', 'rtpAudioDecodedFrames', 'rtpAudioSubmittedFrames',
                      'rtpAudioPlayerRenderedFrames', 'rtpAudioOutputProgress', 'rtpAudioAudibility=unknown']:
            self.assertIn(field, state)
        self.assertIn('guard status.outputProgress == .rendering', read('CameraSheets.swift'))
        for locale in ['en', 'zh-Hant']:
            self.assertIn('"camera_audio_output_unknown"', read(f'Resources/{locale}.lproj/Localizable.strings'))

    def test_native_operations_cannot_hold_control_state_lock(self):
        pipeline = read('IOSCcapiRTPAudio.swift')
        self.assertIn('private let nativeQueue = DispatchQueue(', pipeline)
        retire = section(pipeline, 'private func retireOutputLocked()', 'private func result(')
        self.assertIn('nativeQueue.async', retire)
        self.assertNotIn('nativeQueue.sync', retire)
        snapshot = section(pipeline, 'func snapshot(for expected:', 'private func expireOutputIfNeededLocked()')
        for forbidden in ['output.prepare(', 'output.schedule(', 'output.stop()', 'nativeQueue.sync']:
            self.assertNotIn(forbidden, snapshot)
        controller = read('IOSCcapiRTP.swift')
        self.assertIn('audioStatus.retiringResources = audioPipeline.isRetiring', controller)
        self.assertIn('decoderRetirementPending = decoderOwner != nil', pipeline)
        self.assertIn('retirementPending || decoderRetirementPending', pipeline)
        self.assertIn('if status.retiringResources', read('CameraSheets.swift'))

    def test_production_tests_are_registered_and_real_decoder_fixture_is_preserved(self):
        tests = (TESTS / 'RTPAudioLifecycleTests.swift').read_text()
        self.assertGreaterEqual(len(re.findall(r'func test\w+\(', tests)), 19)
        for method in ['testMuteRevokesInflightAndQueuedAUAcrossImmediateReenable',
                       'testOldDecodeFailureCannotDisableNewEpoch',
                       'testScheduledBufferExpiryFlushesBeforeFiveSecondFailureDeadline',
                       'testLastInputDelayedPCMDrainsWithoutAnyFurtherAU',
                       'testRealAACFixtureTraversesProductionPipelineAndNoDataNowDrain',
                       'testUnsupportedRenderObservationStaysUnknownWhileFreshPCMContinues',
                       'testOutputPreparationFailureRetiresResourcesWithoutFailingVideo',
                       'testBlockedNativePrepareCannotBlockWatchdogOrCreateReplacementOutput',
                       'testBlockedNativeScheduleAllowsImmediateLogicalMuteAndFencesLateCompletion',
                       'testBlockedNativeStopKeepsResourcesRetiringAndReplacementWaits']:
            self.assertIn(method, tests)
        self.assertIn('IOSCcapiRTPController(audioPipeline: pipeline', tests)
        self.assertIn('let pipeline = IOSCcapiRTPAudioPipeline(output: output', tests)
        project = (ROOT / 'ios/OpenEOSControl/project.yml').read_text()
        self.assertIn('- path: Tests', project)
        self.assertIn('- OpenEOSControlTests', project)
        self.assertIn('testRealLatmFixtureDecodesToPCMWithAppleAACDecoder', (TESTS / 'CameraAppTests.swift').read_text())


if __name__ == '__main__':
    unittest.main(verbosity=2)
