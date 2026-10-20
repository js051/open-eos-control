"""Production binding guards; packet/runtime behavior is covered separately by Kotlin tests."""

import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[3]
DATA = ROOT / "android/app/src/main/java/dev/openeos/control/data"


class H264TransportSourceTests(unittest.TestCase):
    def test_receiver_keeps_stream_cursor_when_rendering_generation_retires(self):
        source = (DATA / "AndroidCcapiRtpSession.kt").read_text()
        receiver = source.split("private suspend fun receivePackets(", 1)[1].split(
            "private fun readinessFailureMessage(", 1
        )[0]
        self.assertEqual(receiver.count("H264RtpDepacketizer("), 1)
        self.assertIn("val depacketizer = H264RtpDepacketizer(", receiver)
        self.assertIn("depacketizer.resetAfterDiscontinuity()", receiver)
        self.assertIn("inputGeneration != receiveGeneration", receiver)
        self.assertIn("h264Configurations.prepare(accessUnit)", receiver)

    def test_explicit_reset_keeps_sequence_and_waits_for_a_real_boundary(self):
        source = (DATA / "CcapiRtp.kt").read_text()
        reset = source.split("fun resetAfterDiscontinuity()", 1)[1].split(
            "private fun appendStapA", 1
        )[0]
        self.assertIn("pendingDiscontinuity = true", reset)
        self.assertIn("awaitingBoundary = true", reset)
        self.assertNotIn("expectedSequence = null", reset)
        self.assertIn("discontinuity = pendingDiscontinuity", source)
        self.assertIn("if (completed?.keyFrame == true) pendingDiscontinuity = false", source)

    def test_resolver_latches_loss_before_parsing_configuration_only_traffic(self):
        source = (DATA / "H264DecoderConfiguration.kt").read_text()
        prepare = source.split("fun prepare(accessUnit:", 1)[1].split(
            "private fun prepareChecked", 1
        )[0]
        self.assertIn("if (accessUnit.discontinuity)", prepare)
        self.assertIn("discardPendingMedia()", prepare)
        self.assertIn("needsRandomAccess = true", prepare)
        self.assertLess(prepare.index("needsRandomAccess = true"), prepare.index("prepareChecked(accessUnit)"))
        self.assertIn("if (needsRandomAccess && !keyFrame) return null", source)
        self.assertIn("if (keyFrame) needsRandomAccess = false", source)


if __name__ == "__main__":
    unittest.main()
