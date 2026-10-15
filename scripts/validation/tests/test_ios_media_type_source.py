"""Bounded iOS source/fixture checks, not Swift execution or physical evidence."""

import hashlib
import json
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
APP = ROOT / "ios/OpenEOSControl/App"
CORE = ROOT / "ios/OpenEOSCore/Sources/OpenEOSCore"
TESTS = APP.parent / "Tests"
FIXTURE_HASH = "d24efab5bb73d5edc1d222395322f4295c4c31a3ae180d8b3d9293ce4e4b7d14"


class IOSMediaTypeSourceTests(unittest.TestCase):
    def test_fixed_fixture_and_native_resource_use_actual_production_callers(self):
        raw = (ROOT / "contracts/media-type/v1/media-type-vectors.json").read_bytes()
        self.assertEqual(len(raw), 22_867)
        self.assertEqual(hashlib.sha256(raw).hexdigest(), FIXTURE_HASH)
        fixture = json.loads(raw)
        self.assertEqual(fixture["schemaVersion"], 1)
        self.assertEqual(len(fixture["cases"]), 71)
        self.assertEqual(len(fixture["freshMetadataCases"]), 15)
        self.assertEqual(len(fixture["selectionCases"]), 4)
        self.assertEqual(len({case["id"] for case in fixture["cases"]}), 71)
        self.assertEqual(len(fixture["mimeAliasCases"]), 19)
        project = (APP.parent / "project.yml").read_text()
        self.assertEqual(project.count("../../contracts/media-type/v1/media-type-vectors.json"), 1)
        self.assertNotIn("../../contracts/media-type/v1/README.md", project)
        tests = (TESTS / "MediaTypeContractTests.swift").read_text()
        for actual in ("CameraMediaType.classify(", "mediaIsPhoto(", "mediaIsVideo(",
                       "ForegroundMediaPlan.isJPEG(", "mediaItemsForDisplay(",
                       "plan.observeInventory(", "plan.observeFresh(", "selectLatestMediaItem(",
                       "CameraMediaType.admitsFreshAutomaticJPEG(", "CameraMediaType.declaredMIME(", FIXTURE_HASH):
            self.assertIn(actual, tests)
        self.assertNotIn("XCTSkip", tests)

    def test_single_classifier_has_explicit_conflict_unknown_and_separate_strict_gate(self):
        source = (CORE / "CameraMediaType.swift").read_text()
        for required in ("case photo, video, audio, other, unknown, conflict",
                         "guard validMIME(mime) else { return .unknown }",
                         "families.count > 1", "families.first ?? .unknown",
                         "asciiLowercase", "omittingEmptySubsequences: false",
                         'item.kind == "image"', 'normalizedMIME(mime) == "image/jpeg"',
                         'dot > item.name.startIndex',
                         'guard let mime = item.contentType, !mime.isEmpty else { return true }',
                         'original.id.utf8.elementsEqual(metadata.id.utf8)',
                         'original.name.utf8.elementsEqual(metadata.name.utf8)',
                         'isAutomaticJPEG(original) && isAutomaticJPEG(metadata)'):
            self.assertIn(required, source)
        gate = source.split("public static func isAutomaticJPEG(", 1)[1].split("public static", 1)[0]
        self.assertNotIn('"image/jpg"', gate)
        self.assertNotIn(".lowercased()", gate)
        self.assertIn('classify(item) == .photo', gate)
        self.assertIn('$0.value <= 31 || (127...159).contains($0.value)', source)
        self.assertLess(source.index('containsMIMEControl(item.contentType)'),
                        source.index('let mime = normalizedMIME(item.contentType)'))
        aliases = source.split("public static func declaredMIME(", 1)[1].split("private static func", 1)[0]
        for key in ("content_type", "contentType", "contenttype", "mimeType", "mimetype"):
            self.assertIn(f'"{key}"', aliases)
        self.assertIn('result.map({ Data($0.utf8) }) != value.map({ Data($0.utf8) })', aliases)
        self.assertNotIn('fallback', aliases)

    def test_gallery_review_thumbnail_and_preview_cannot_treat_not_video_as_photo(self):
        library = (APP / "MediaLibrary.swift").read_text()
        self.assertIn("case .photos: mediaIsPhoto(item)", library)
        self.assertIn("CameraMediaType.classify($0) == (kind == .video ? .video : .photo)", library)
        for name in ("MediaLibrary.swift", "MediaView.swift", "CameraAppState.swift"):
            self.assertNotIn("return !mediaIsVideo(", (APP / name).read_text())
            self.assertNotIn("case .photos: !mediaIsVideo(", (APP / name).read_text())
        state = (APP / "CameraAppState.swift").read_text()
        thumb = state.split("func loadMediaThumbnail(", 1)[1].split("func openMediaPreview(", 1)[0]
        self.assertLess(thumb.index("guard mediaIsPhoto(item) || mediaIsVideo(item)"),
                        thumb.index("mediaThumbnailCache.touch"))
        self.assertIn("supports(.mediaThumbnail)", thumb)
        self.assertIn("mediaIsPhoto(item) && item.previewAvailable && supports(.mediaPreview)", state)
        self.assertIn("video ? supports(.mediaDownload)", state)
        self.assertIn("latestMediaThumbnailLoading = visual && supports(.mediaThumbnail)", state)
        self.assertIn("let current = mediaPreviewItem, mediaIsPhoto(current)", state)
        update = state.split("private func applyUpdatedMedia(", 1)[1].split("private func resetMediaThumbnails", 1)[0]
        self.assertIn("mediaThumbnailCache.removeValue(for: item.id)", update)
        self.assertIn("if !mediaIsPhoto(item)", update)
        self.assertIn("mediaPreviewImageTask?.cancel()", update)
        self.assertIn("if !mediaIsVideo(item)", update)
        self.assertIn("mediaVideoPlayback?.close()", update)
        view = (APP / "MediaView.swift").read_text()
        self.assertIn("switch CameraMediaType.classify(item)", view)
        self.assertIn("mediaIsPhoto(item) && item.previewAvailable && camera.supports(.mediaPreview)", view)
        self.assertIn("let item = camera.mediaPreviewItem, mediaIsVideo(item)", view)

    def test_native_guards_run_before_requests_without_expanding_transport_support(self):
        ccapi = (CORE / "CCAPIClient.swift").read_text()
        bridge = (CORE / "DesktopBridgeClient.swift").read_text()
        info = bridge.split("public func mediaInfo(", 1)[1].split("public func setMediaProtection(", 1)[0]
        self.assertIn("throw DesktopBridgeError.invalidResponse", info)
        stream = ccapi.split("public func openMediaStream(", 1)[1].split("private static func", 1)[0]
        self.assertLess(stream.index("CameraMediaType.classify(item) == .video"),
                        stream.index("ensureInitialized()"))
        self.assertIn("observedFeatures.contains(.mediaDownload)", stream)
        preview = ccapi.split("public func mediaPreview(", 1)[1].split("public func mediaInfo(", 1)[0]
        self.assertIn("CameraMediaType.classify(item) == .photo, previewEligible", preview)
        self.assertIn("Self.isCCAPIDisplayPreviewPath(item.id)", preview)
        self.assertIn('let imageKind = ["image", "raw"].contains(item.kind.lowercased())', preview)
        ticket = bridge.split("public func beginMediaPlayback(", 1)[1].split("public func openMediaStream(", 1)[0]
        self.assertLess(ticket.index("CameraMediaType.classify(item) == .video"), ticket.index("requestJSON("))
        self.assertIn("CameraMediaType.classify(item) == .photo, item.previewAvailable", bridge)
        for source in (ccapi, bridge):
            self.assertIn("[.photo, .video].contains(CameraMediaType.classify(item))", source)
        self.assertIn("CameraMediaType.admitsVideoStream(item, contentType:",
                      (CORE / "CameraMediaStreaming.swift").read_text())
        session = (APP / "CameraSession.swift").read_text()
        self.assertIn("CameraMediaType.classify(playbackItem) == .video", session)
        self.assertIn("catch let error as CameraMediaMetadataError", session)
        self.assertIn("if case .invalidResponse = error { throw error }", session)

    def test_fresh_adapter_preserves_declared_types_and_guards_before_original(self):
        ccapi = (CORE / "CCAPIClient.swift").read_text()
        fresh = ccapi.split("public func foregroundMediaInfo(", 1)[1]
        fresh = fresh.split("public func downloadForegroundOriginal(", 1)[0]
        self.assertLess(fresh.index("foregroundJSON("), fresh.index("try parseMediaInfo(canonical, body: body)"))
        self.assertIn('kind: Self.mediaKind(path)', fresh)
        self.assertLess(fresh.index("freshName?.utf8.elementsEqual(item.name.utf8)"),
                        fresh.index('Self.foregroundInteger(body["filesize"])'))
        self.assertIn("admitsFreshAutomaticJPEG(original: item, metadata: metadata, fresh: true)", fresh)
        parser = ccapi.split("private func parseMediaInfo(", 1)[1].split("private static func parseMediaArchive", 1)[0]
        for fields in ('keys: ["id"]', 'keys: ["name", "filename"]', 'keys: ["kind"]',
                       'CameraMediaType.declaredMIME(in: body)',
                       "else { throw CameraMediaMetadataError.invalidMetadata }",
                       "return present ? result : fallback"):
            self.assertIn(fields, parser)
        bridge = (CORE / "DesktopBridgeClient.swift").read_text()
        self.assertIn('contentType = try CameraMediaType.declaredMIME(in: item)', bridge)
        self.assertNotIn('contentType: item.nonEmptyString("contentType")', bridge)
        self.assertIn('contentType: try CameraMediaType.declaredMIME(in: item)', ccapi)
        original = ccapi.split("public func downloadForegroundOriginal(", 1)[1].split("private func", 1)[0]
        self.assertLess(original.index("CameraMediaType.isAutomaticJPEG(item)"),
                        original.index("strictTransport.downloadForeground"))
        plan = (APP / "ForegroundMediaPlan.swift").read_text()
        self.assertIn("CameraMediaType.admitsFreshAutomaticJPEG(original: pending[0].item, "
                      "metadata: item, fresh: true)",
                      plan)
        for required in ("size > 0", "firstSize == size", "observationInterval", "known.formUnion(current)",
                         "pendingLifetime", "resultLimit", "CameraMediaType.isAutomaticJPEG(item)"):
            self.assertIn(required, plan)
        controller = (APP / "ForegroundMediaController.swift").read_text()
        self.assertIn("catch CameraForegroundMediaError.freshSizeUnavailable", controller)
        self.assertIn("try plan.observeUnreadySize(at: now())", controller)
        after_prepare = controller.split("try await actions.prepare(fresh, size)", 1)[1]
        self.assertNotIn("freshInfo(", after_prepare)
        self.assertIn("try plan.recordPrepared(fresh)", after_prepare)
        self.assertIn("try Task.checkCancellation()", after_prepare)

    def test_real_native_regressions_cover_adapter_drift_and_zero_transfer(self):
        core_tests = ROOT / "ios/OpenEOSCore/Tests/OpenEOSCoreTests"
        for filename, method in (
            (core_tests / "CameraMediaTypeTests.swift",
             "testUnknownConflictingAndAudioNeverReachNativeImageOrStreamRequests"),
            (core_tests / "ForegroundMediaReadTests.swift", "testFreshMetadataCannotHideChangedIdentityNameKindOrMIME"),
            (core_tests / "ForegroundMediaReadTests.swift",
             "testStrictOriginalEntryPointRejectsNonJPEGWithoutGETOrFile"),
            (TESTS / "ForegroundMediaControllerTests.swift",
             "testSecondFreshReadRejectsChangedTypeOrNameBeforePreparation"),
            (TESTS / "ForegroundMediaIntegrationTests.swift",
             "testFreshTypeDriftStopsBeforeOriginalRetentionOrFilesCopyWithoutResend"),
            (TESTS / "ForegroundMediaIntegrationTests.swift",
             "testUnreadyFreshSizeWaitsAndRequiresTwoNewPositiveObservations"),
            (TESTS / "ForegroundMediaIntegrationTests.swift",
             "testUnreadyFreshSizeExpiresWithoutTransferOrNewDeadline"),
        ):
            self.assertIn(method, filename.read_text())


if __name__ == "__main__":
    unittest.main()
