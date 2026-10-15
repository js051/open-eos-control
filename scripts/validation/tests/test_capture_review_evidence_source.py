"""Source/resource guards only; native ViewModel and Compose execution remain separate gates."""

import hashlib
import json
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
UI = ROOT / "android/app/src/main/java/dev/openeos/control/ui"
DATA = ROOT / "android/app/src/main/java/dev/openeos/control/data"
TESTS = ROOT / "android/app/src/test/java/dev/openeos/control/ui"


class CaptureReviewEvidenceSourceTests(unittest.TestCase):
    def test_fixed_contract_bytes_packaging_and_formal_loader_are_pinned(self):
        data = (ROOT / "contracts/capture-review-evidence/v1/review-evidence-vectors.json").read_bytes()
        self.assertEqual(len(data), 25745)
        digest = hashlib.sha256(data).hexdigest()
        self.assertEqual(digest, "aab83ace169997c2f9788ea45f2863513f0a0d99e3211143c0e60475eb7ec7b1")
        fixture = json.loads(data)
        self.assertEqual(len(fixture["cases"]), 106)
        self.assertEqual(len(fixture["retryCases"]), 2)
        self.assertEqual(len(fixture["retirementCases"]), 4)
        loader = (TESTS / "CaptureReviewEvidenceFixtureLoader.kt").read_text()
        self.assertIn(digest, loader)
        self.assertIn('getResourceAsStream("/review-evidence-vectors.json")', loader)
        self.assertIn("readNBytes(64 * 1024 + 1)", loader)
        self.assertIn('resources.srcDir("../../contracts/capture-review-evidence/v1")',
                      (ROOT / "android/app/build.gradle.kts").read_text())

    def test_invocation_evidence_is_immutable_fresh_and_cancelled_reads_are_not_counted(self):
        source = (UI / "MediaReviewSelection.kt").read_text()
        self.assertIn("val observedBeforeReview = previousIds.toSet()", source)
        self.assertIn("var evidence = CaptureReviewEvidence()", source)
        self.assertIn("val successfulReads: Int", source)
        self.assertIn("val failedReads: Int", source)
        self.assertIn("val lastCompletedRead: CaptureReviewRead?", source)
        self.assertIn("coroutineContext.ensureActive()\n            evidence = evidence.completed", source)
        self.assertIn("return CaptureReviewResult.Found(candidate, evidence)", source)
        self.assertIn("evidence.successfulReads > 0", source)
        self.assertIn("lastCompletedRead == CaptureReviewRead.FAILURE", source)
        self.assertNotIn("repository.", source)

    def test_viewmodel_preserves_generation_snapshot_read_bound_and_read_only_retry(self):
        source = (UI / "CameraViewModel.kt").read_text()
        retry = source.split("    fun retryCaptureReview()", 1)[1].split("    private fun refreshCaptureReview()", 1)[0]
        review = source.split("    private fun refreshCaptureReview()", 1)[1].split(
            "    private fun refreshCaptureReview(items:", 1)[0]
        for command in ["captureStill(", "toggleRecording(", "autofocus(", "halfPressShutter("]:
            self.assertNotIn(command, retry + review)
        self.assertIn("longArrayOf(250L, 750L, 1_500L)", source)
        self.assertIn("previousIds = attempt?.previousIds.orEmpty()", review)
        self.assertIn("intent = attempt?.intent ?: MediaReviewIntent.RECENT", review)
        self.assertIn("if (!stillOwnsReview()) return@launch", review)
        self.assertIn("sessionGeneration == cameraSessionGeneration && _uiState.value.info === connection", review)
        self.assertIn("job.isCancelled && stillOwnsReview()", review)
        self.assertIn("terminalResultAccepted = true\n            when (result)", review)
        self.assertIn("if (terminalResultAccepted) it.captureReviewStatus else state.captureReviewStatus", review)
        self.assertIn("captureReviewObservation = result.observation", review)
        self.assertIn("captureReviewEvidence = result.evidence", review)
        beginning = source.split("    private fun beginCaptureReviewLoad()", 1)[1].split(
            "    private suspend fun publishCaptureReview", 1)[0]
        self.assertNotIn("captureReviewEvidence =", beginning)
        self.assertNotIn("captureReviewObservation =", beginning)
        self.assertNotIn("captureReviewItem =", beginning)

    def test_ui_surfaces_localized_dual_fact_message_without_replacing_read_failure(self):
        key = "capture_review_no_candidate_read_failed"
        messages = []
        for locale in ["values", "values-zh-rTW"]:
            root = ET.parse(ROOT / f"android/app/src/main/res/{locale}/strings.xml").getroot()
            strings = {entry.attrib["name"]: entry.text for entry in root.findall("string")}
            messages.append(strings[key])
            self.assertNotEqual(strings[key], strings["capture_review_not_ready"])
            self.assertNotEqual(strings[key], strings["capture_review_read_failed"])
        self.assertIn("The last list read failed", messages[0])
        self.assertIn("最後一次清單讀取失敗", messages[1])
        for filename in ["CameraComponents.kt", "CaptureReviewStatusDialog.kt"]:
            source = (UI / filename).read_text()
            self.assertIn(f"R.string.{key}", source)
            self.assertIn("state.captureReviewEvidence.noCandidateWithFinalReadFailure", source)
            self.assertIn("R.string.capture_review_read_failed", source)
        ui_test = (
            ROOT / "android/app/src/androidTest/java/dev/openeos/control/ui/CameraCaptureReviewUiTest.kt"
        ).read_text()
        self.assertIn("mixedFinalFailureShowsBothFactsInButtonAndDialogForBothLanguages", ui_test)
        self.assertIn("assertEquals(0, commands)", ui_test)

    def test_malformed_envelopes_and_identities_cannot_become_successful_empty_lists(self):
        source = (DATA / "CcapiClient.kt").read_text()
        simulator = source.split("    private suspend fun listSimulatorMedia()", 1)[1].split(
            "    private fun parseSimulatorMediaItem", 1)[0]
        self.assertIn('getJSONArray("items")', simulator)
        self.assertIn('item.opt("id") is String && item.opt("name") is String', simulator)
        self.assertIn("checkNotNull(parseSimulatorMediaItem(item))", simulator)
        self.assertNotIn("filterNotNull", simulator)
        self.assertNotIn("return emptyList()", simulator)
        listing = source.split("    private suspend fun listContentPaths(", 1)[1].split(
            "    private suspend fun getInventoryPageInfo", 1)[0]
        self.assertIn("pageInfo?.inventoryPageCount() ?: 0", listing)
        self.assertNotIn("contentPaths(strict = strict)", listing)
        self.assertNotIn("contentPaths(reverse = true, strict = strict)", listing)
        native_tests = (TESTS / "CameraCaptureReviewRecoveryTest.kt").read_text()
        for name in ["zeroCompletedReadCancellationPreservesPriorTerminalOutcomeAndEvidence",
                     "allFailedRetryStartsFreshAfterMixedReadEvidenceAndNeverResendsCommands",
                     "malformedSimulatorListingsRemainUnreadAndValidEmptyListingIsSuccessful",
                     "malformedNativePathListingsRemainUnreadAndValidEmptyListingIsSuccessful"]:
            self.assertIn(name, native_tests)


if __name__ == "__main__":
    unittest.main()
