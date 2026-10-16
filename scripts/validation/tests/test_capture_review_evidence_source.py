"""Fixed contract and iOS structural guards. Does not compile Swift or run XCTest."""

import hashlib
import itertools
import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
APP = ROOT / "ios/OpenEOSControl/App"
IOS_TESTS = APP.parent / "Tests"
SHA256 = "aab83ace169997c2f9788ea45f2863513f0a0d99e3211143c0e60475eb7ec7b1"


class IOSCaptureReviewEvidenceSourceTests(unittest.TestCase):
    def test_fixed_fixture_covers_all_patterns_shapes_intents_and_boundaries(self):
        raw = (ROOT / "contracts/capture-review-evidence/v1/review-evidence-vectors.json").read_bytes()
        self.assertEqual(len(raw), 25_745)
        self.assertLessEqual(len(raw), 32_768)
        self.assertEqual(hashlib.sha256(raw).hexdigest(), SHA256)
        fixture = json.loads(raw)
        self.assertEqual(fixture["schemaVersion"], 1)
        self.assertEqual(fixture["maximumReads"], 4)
        self.assertEqual(fixture["retryDelaysMillis"], [250, 750, 1500])
        self.assertEqual(fixture["previousIds"], ["OLD_A", "OLD_B"])
        cases = {case["id"]: case for case in fixture["cases"]}
        self.assertEqual(len(cases), 106)
        for intent, shape, pattern in itertools.product(
            ["photo", "video"], ["empty", "known-only", "wrong-kind"], itertools.product("SF", repeat=4)
        ):
            key = f"{intent}-{shape}-{''.join(pattern)}"
            case = cases[key]
            self.assertEqual(case["intent"], intent)
            self.assertEqual(case["reads"], [shape if read == "S" else "failure" for read in pattern])
            expected = case["expected"]
            self.assertEqual(expected["successfulReads"], pattern.count("S"))
            self.assertEqual(expected["failedReads"], pattern.count("F"))
            self.assertEqual(expected["lastCompletedRead"], "success" if pattern[-1] == "S" else "failure")
            self.assertEqual(expected["observation"], "no-candidate-observed" if "S" in pattern else "unread")
            self.assertIsNone(expected["candidateId"])
        for intent in ["photo", "video"]:
            for round_number in range(1, 5):
                case = cases[f"{intent}-found-round-{round_number}"]
                self.assertEqual(len(case["reads"]), round_number)
                self.assertEqual(case["reads"][-1], "found")
                self.assertEqual(case["expected"]["observation"], "found")
                self.assertEqual(case["expected"]["candidateId"], "NEW_PHOTO" if intent == "photo" else "NEW_VIDEO")
            case = cases[f"{intent}-invalid-response-is-not-success"]
            self.assertIn("malformed", case["reads"])
            self.assertEqual(case["expected"]["successfulReads"], 0)
            self.assertEqual(case["expected"]["failedReads"], 4)
        self.assertEqual(len(fixture["retryCases"]), 2)
        self.assertEqual(len(fixture["retirementCases"]), 4)
        self.assertTrue(all(not row["publish"] and row["preservePrevious"] for row in fixture["retirementCases"]))

    def test_native_resource_has_exact_hash_and_executes_actual_app_state(self):
        contract = (IOS_TESTS / "CaptureReviewEvidenceTests.swift").read_text()
        journeys = (IOS_TESTS / "CaptureMediaJourneyTests.swift").read_text()
        project = (APP.parent / "project.yml").read_text()
        self.assertEqual(project.count("../../contracts/capture-review-evidence/v1/review-evidence-vectors.json"), 1)
        self.assertIn(SHA256, contract)
        self.assertIn('url(forResource: "review-evidence-vectors", withExtension: "json")', contract)
        self.assertIn("guard size > 0 && size <= 32_768", contract)
        self.assertIn("XCTAssertEqual(data.count, 25_745)", contract)
        self.assertIn("JSONDecoder().decode(Self.self, from: data)", contract)
        for actual in ("for vector in fixture.cases", "state.captureStill()", "state.toggleRecording()",
                       "state.retryCaptureMediaReview()", "state.captureReviewOutcome", "XCTAssertEqual(checked, 106)",
                       "XCTAssertEqual(checked, 4)", "CaptureReviewEvidenceContract.load()",
                       "XCTAssertEqual(state.latestMediaItem, previous",
                       "requests.allSatisfy { $0.hasPrefix(\"GET \") }"):
            self.assertIn(actual, journeys)
        self.assertIn(
            'case .malformed: return CameraHTTPResponse(statusCode: 200, body: Data(#"{"items":{}}"#.utf8))',
            journeys,
        )
        self.assertNotIn("XCTSkip", contract)
        self.assertNotIn("XCTSkip", journeys)

    def test_independent_read_evidence_is_current_invocation_and_preserves_zero_read_previous(self):
        library = (APP / "MediaLibrary.swift").read_text()
        evidence = library.split("struct CaptureReviewReadEvidence:", 1)[1].split("enum CaptureReviewKind:", 1)[0]
        for required in ("successfulReads += 1", "failedReads += 1", "lastCompletedRead = read",
                         "case noCandidateObserved", "case unread"):
            self.assertIn(required, library)
        self.assertIn("successfulReads + failedReads", evidence)
        self.assertIn("readEvidence.successfulReads > 0 ? .noCandidateObserved : .unread", evidence)
        self.assertIn("readEvidence.completedReads == 0 ? .notReady : .readFailed", evidence)
        self.assertIn("observation == .noCandidateObserved && readEvidence.lastCompletedRead == .failure", evidence)
        state = (APP / "CameraAppState.swift").read_text()
        load = state.split("private func loadLatestMedia(", 1)[1].split("private func publishLatestMedia(", 1)[0]
        self.assertIn("var readEvidence = CaptureReviewReadEvidence()", load)
        self.assertIn("Task.isCancelled || retiredRead || readEvidence.completedReads == 0", load)
        self.assertIn("captureReviewOutcome = previousOutcome", load)
        self.assertIn("captureReviewState = previousState == .readFailed ? .readFailed : .notReady", load)
        self.assertNotIn("lastReadFailed", load)
        self.assertLess(
            load.index("let items = try await session.listMedia"), load.index("readEvidence.record(.success)")
        )
        self.assertLess(load.index("if let item = selected"), load.index("found: true"))
        self.assertNotIn("lastError", load)
        prepare = (
            state.split("private func prepareCaptureMediaReview(", 1)[1]
            .split("private func startLatestMediaRefresh(", 1)[0]
        )
        self.assertIn("captureReviewOutcome = CaptureReviewOutcome()", prepare)

    def test_retirement_guards_both_completed_outcomes_and_deferred_publication(self):
        state = (APP / "CameraAppState.swift").read_text()
        load = state.split("private func loadLatestMedia(", 1)[1].split("private func publishLatestMedia(", 1)[0]
        deferred = load.split("defer {", 1)[1].split("let attempts", 1)[0]
        self.assertIn("if generation == latestMediaGeneration", deferred)
        self.assertIn("Task.isCancelled || retiredRead || readEvidence.completedReads == 0", deferred)
        retired = deferred.split("if Task.isCancelled || retiredRead", 1)[1].split("} else {", 1)[0]
        self.assertIn("captureReviewOutcome = previousOutcome", retired)
        self.assertNotIn("CaptureReviewOutcome(readEvidence:", retired)
        busy = load.split("guard !isBusy(.media), !mediaLibraryLoading else {", 1)[1].split("}", 1)[0]
        self.assertIn("retiredRead = true", busy)
        success = (
            load.split("let items = try await session.listMedia", 1)[1]
            .split("readEvidence.record(.success)", 1)[0]
        )
        self.assertIn("guard generation == latestMediaGeneration, !Task.isCancelled", success)
        failure = load.split("} catch is CancellationError {", 1)[1].split("readEvidence.record(.failure)", 1)[0]
        self.assertIn("retiredRead = true", failure)
        self.assertIn("guard generation == latestMediaGeneration, !Task.isCancelled", failure)
        self.assertIn("(error as? URLError)?.code == .cancelled", failure)
        for method in (
            "testZeroCompletedReadRetryPreservesEveryPreviousTerminalOutcomeAndEvidence",
            "testCancelledReviewInflightSuccessFailureAndCancelledErrorPublishNoOutcome",
            "testReviewTransportCancellationWithoutTaskCancellationDoesNotBecomeReadFailure",
            "testCancelledOrReplacedReviewDuringBackoffCannotPublishCompletedLocalReads",
            "testReplacedReviewOwnerRetiresLateSuccessAndFailureEvidence",
            "testReplacedSessionReviewCannotChangeNewSessionOutcomeOrPreviousMedia",
            "testFreshReviewPreemptedBeforeFirstReadDoesNotFabricateFailure",
            "testMediaBusyAfterOneCompletedRetryReadPreservesPreviousTerminalEvidence",
            "testCancelledRetryInFlightOrBackoffRestoresPriorMixedTerminalOutcome",
        ):
            self.assertIn(method, (IOS_TESTS / "CaptureMediaJourneyTests.swift").read_text())

    def test_bounded_read_delay_defaults_and_no_camera_mutations_remain(self):
        state = (APP / "CameraAppState.swift").read_text()
        load = state.split("private func loadLatestMedia(", 1)[1].split("private func publishLatestMedia(", 1)[0]
        self.assertIn("latestMediaRetryDelays: [UInt64] = [250_000_000, 750_000_000, 1_500_000_000]", state)
        self.assertIn("Self.latestMediaRetryDelays.count + 1", load)
        self.assertIn("Task.sleep(nanoseconds: Self.latestMediaRetryDelays[attempt])", load)
        self.assertIn("latestMediaReviewDelay: (@Sendable (UInt64) async throws -> Void)? = nil", state)
        self.assertIn("guard !isBusy(.media), !mediaLibraryLoading", load)
        self.assertIn("afterCaptureFrom: items, previousIDs: review.previousIDs, kind: review.kind", load)
        for forbidden in ("captureStill(", "toggleRecording(", "startRecording(", "stopRecording(", "autofocus(",
                          "latestMediaItem =", "mediaCaptureDate("):
            self.assertNotIn(forbidden, load)

    def test_simulator_and_bridge_adapters_reject_missing_or_wrong_type_list_envelopes(self):
        core = ROOT / "ios/OpenEOSCore"
        ccapi = (core / "Sources/OpenEOSCore/CCAPIClient.swift").read_text()
        listing = ccapi.split("public func listMedia(", 1)[1].split("private func mediaItems(", 1)[0]
        simulator = listing.split("if resolvedMode == .simulator {", 1)[1].split("guard supports(.get", 1)[0]
        self.assertIn('guard let rawItems = value.array("items") else {', simulator)
        self.assertIn(
            'throw CCAPIError.invalidResponse("Simulator media response must contain an items array.")', simulator
        )
        self.assertIn("let allItems = try rawItems.map", simulator)
        self.assertIn('let id = item["id"] as? String', simulator)
        self.assertIn('let name = item["name"] as? String', simulator)
        self.assertNotIn("rawItems.objects", simulator)
        self.assertNotIn('id: item.string("id")', simulator)
        self.assertNotIn("?? []", simulator)
        bridge = (core / "Sources/OpenEOSCore/DesktopBridgeClient.swift").read_text()
        listing = bridge.split("public func listMedia(", 1)[1].split("private func requireMediaListingOwner(", 1)[0]
        self.assertIn('let raw = body["items"] as? [Any] else {', listing)
        self.assertIn(
            'throw DesktopBridgeError.invalidResponse("Desktop Bridge returned invalid media JSON.")', listing
        )
        self.assertIn("let items = try raw.map", listing)
        self.assertIn("let item = Self.parseMediaItem(object),", listing)
        self.assertIn("identities.insert(Data(item.id.utf8)).inserted else", listing)
        self.assertIn("raw.count <= BridgeMediaListingDecoder.maximumItems", listing)
        self.assertIn("maximumItems.map({ raw.count <= $0 }) ?? true", listing)
        self.assertNotIn("Array(items.prefix", listing)
        self.assertNotIn("raw.compactMap", listing)
        paths = ccapi.split("private func contentPaths(", 1)[1].split("private func contentPage(", 1)[0]
        self.assertEqual(paths.count("try Self.validatedContentPaths("), 6)
        self.assertNotIn('.array("path")?.strings ?? []', paths)
        self.assertIn('Self.strictInteger(pageInfo["pagenumber"])', paths)
        self.assertIn('if pageInfo["pagenumber"] == nil', paths)
        self.assertIn('guard let paths = value["path"] as? [String]', paths)
        tests = (core / "Tests/OpenEOSCoreTests/CCAPIClientTests.swift").read_text()
        self.assertIn("testSimulatorMediaListRejectsMissingOrWrongTypeItemsWithoutPublishingEmptySuccess", tests)
        self.assertIn("testSimulatorMediaListAcceptsExplicitEmptyItemsArrayAsSuccessfulRead", tests)
        self.assertIn("XCTAssertTrue(snapshots.isEmpty)", tests)
        self.assertIn("XCTAssertEqual(snapshots, [[]])", tests)
        for method in ("testSimulatorMediaListRejectsMalformedItemsBeforeTruncatingOrPublishing",
                       "testRealMediaListRejectsMalformedPathEnvelopesBeforePublishing",
                       "testRealMediaListRejectsMalformedPageCountWithoutInventingZero",
                       "testRealMediaListKeepsUnsupportedCountAndValidUnpagedResponseFallbacks"):
            self.assertIn(method, tests)
        self.assertIn("testBridgeMalformedItemsNeverBecomeSuccessfulEmptyReviewReads",
                      (IOS_TESTS / "CaptureMediaJourneyTests.swift").read_text())

    def test_localized_ui_keeps_both_observation_and_last_read_warning(self):
        view = (APP / "CameraControlView.swift").read_text()
        self.assertIn("localizationKey(for: camera.captureReviewState, outcome: camera.captureReviewOutcome)", view)
        self.assertIn("camera.captureReviewState == .notReady,", view)
        self.assertIn("camera.captureReviewOutcome.showsLastReadFailureWarning", view)
        self.assertIn('accessibilityIdentifier("capture-review-last-read-failed")', view)
        library = (APP / "MediaLibrary.swift").read_text()
        self.assertIn('return localizationKey(suffix: "not_checked")', library)
        for language in ["en", "zh-Hant"]:
            source = (APP / f"Resources/{language}.lproj/Localizable.strings").read_text()
            entries = re.findall(r'^"([^"]+)" = "(.*)";$', source, re.MULTILINE)
            for prefix in ["capture_review", "recording_review"]:
                for suffix in ["notReady", "readFailed", "last_read_failed", "not_checked"]:
                    matches = [value for key, value in entries if key == f"{prefix}_{suffix}"]
                    self.assertEqual(len(matches), 1)
                    self.assertTrue(matches[0])
                    if suffix == "last_read_failed":
                        self.assertIn("last" if language == "en" else "最後一次", matches[0])
                        self.assertIn("failed" if language == "en" else "失敗", matches[0])

"""Source/resource guards only; native ViewModel and Compose execution remain separate gates."""

import hashlib
import json
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
UI = ROOT / "android/app/src/main/java/dev/openeos/control/ui"
DATA = ROOT / "android/app/src/main/java/dev/openeos/control/data"
ANDROID_TESTS = ROOT / "android/app/src/test/java/dev/openeos/control/ui"


class AndroidCaptureReviewEvidenceSourceTests(unittest.TestCase):
    def test_fixed_contract_bytes_packaging_and_formal_loader_are_pinned(self):
        data = (ROOT / "contracts/capture-review-evidence/v1/review-evidence-vectors.json").read_bytes()
        self.assertEqual(len(data), 25745)
        digest = hashlib.sha256(data).hexdigest()
        self.assertEqual(digest, "aab83ace169997c2f9788ea45f2863513f0a0d99e3211143c0e60475eb7ec7b1")
        fixture = json.loads(data)
        self.assertEqual(len(fixture["cases"]), 106)
        self.assertEqual(len(fixture["retryCases"]), 2)
        self.assertEqual(len(fixture["retirementCases"]), 4)
        loader = (ANDROID_TESTS / "CaptureReviewEvidenceFixtureLoader.kt").read_text()
        self.assertIn(digest, loader)
        self.assertIn('getResourceAsStream("/review-evidence-vectors.json")', loader)
        self.assertIn("readNBytes(64 * 1024 + 1)", loader)
        build = (ROOT / "android/app/build.gradle.kts").read_text()
        for binding in ['"../../contracts/capture-review-evidence/v1/review-evidence-vectors.json"',
                        'from(unitContractFixtures.map { file(it) })', 'into(unitContractDirectory)',
                        'duplicatesStrategy = DuplicatesStrategy.FAIL',
                        'doFirst { checkContractFixtures(unitContractFixtures) }',
                        'sourceSets.getByName("test").resources.srcDir(unitContractDirectory)',
                        'dependsOn(prepareUnitContractFixtures, prepareAndroidContractFixtures)']:
            self.assertIn(binding, build)

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
        native_tests = (ANDROID_TESTS / "CameraCaptureReviewRecoveryTest.kt").read_text()
        for name in ["zeroCompletedReadCancellationPreservesPriorTerminalOutcomeAndEvidence",
                     "allFailedRetryStartsFreshAfterMixedReadEvidenceAndNeverResendsCommands",
                     "malformedSimulatorListingsRemainUnreadAndValidEmptyListingIsSuccessful",
                     "malformedNativePathListingsRemainUnreadAndValidEmptyListingIsSuccessful"]:
            self.assertIn(name, native_tests)

if __name__ == "__main__":
    unittest.main()
