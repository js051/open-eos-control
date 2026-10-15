"""Bounded fixture and iOS source guards; these never execute Swift or native UI."""

import hashlib
import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
APP = ROOT / "ios/OpenEOSControl/App"
TESTS = APP.parent / "Tests"
FIXTURE_SHA256 = "8eb57e04446478fe523c1e8cd08c0cc57b524d9fcb7f63845fc28d6d02f57e9a"


class MediaDateReviewSourceTests(unittest.TestCase):
    def test_fixed_fixture_bounds_schema_and_actual_swift_callers(self):
        raw = (ROOT / "contracts/media-date-review/v1/date-review-vectors.json").read_bytes()
        self.assertEqual(len(raw), 26_467)
        self.assertLessEqual(len(raw), 32_768)
        self.assertEqual(hashlib.sha256(raw).hexdigest(), FIXTURE_SHA256)
        fixture = json.loads(raw)
        self.assertEqual(fixture["schemaVersion"], 1)
        self.assertEqual(fixture["displayZones"], ["UTC", "Asia/Taipei", "America/Los_Angeles"])
        self.assertEqual(fixture["kinds"], ["instant", "floating", "date-only", "unknown"])
        self.assertEqual(fixture["unknownAndTies"], "loaded-order")
        self.assertEqual(len(fixture["vectors"]), 48)
        self.assertEqual(len({v["id"] for v in fixture["vectors"]}), 48)
        self.assertEqual(len(fixture["sortItems"]), 8)
        self.assertEqual(len(fixture["sortCases"]), 9)
        self.assertEqual(len(fixture["reviewCases"]), 4)
        for vector in fixture["vectors"]:
            self.assertLessEqual(len((vector["raw"] or "").encode()), 128)
            self.assertEqual(set(vector["display"]), set(fixture["displayZones"]))
            self.assertEqual(vector["instantMillis"] is not None, vector["kind"] == "instant")
            for display in vector["display"].values():
                self.assertEqual(display["dateKey"] is not None, vector["kind"] != "unknown")
                self.assertEqual(display["timeKey"] is not None, vector["kind"] in ("instant", "floating"))
        tests = (TESTS / "MediaDateReviewContractTests.swift").read_text()
        for actual in ("mediaCaptureDate(", "mediaItemsForDisplay(", "mediaDateGroups(",
                       "mediaDateFilterSummary(", "mediaRatingFilterSummary(", "mediaFolderFilterSummary(",
                       "selectLatestMediaItem(", ".cardText(", ".detailText(", "compareMediaCaptureDates("):
            self.assertIn(actual, tests)
        self.assertIn(FIXTURE_SHA256, tests)
        self.assertIn("XCTAssertEqual(count, 144)", tests)
        self.assertIn("XCTAssertEqual(count, 2_016)", tests)
        self.assertNotIn("XCTSkip", tests)

    def test_fixture_is_individually_mapped_without_readme_bundle_collisions(self):
        project = (APP.parent / "project.yml").read_text()
        self.assertEqual(project.count("../../contracts/media-date-review/v1/date-review-vectors.json"), 1)
        self.assertIn('url(forResource: "date-review-vectors", withExtension: "json")',
                      (TESTS / "MediaDateReviewContractTests.swift").read_text())
        self.assertNotRegex(project, r"path: .*contracts(?:\s|/[^\n]*README\.md)")

    def test_strict_integer_parser_and_bounded_named_zone_validation_remain(self):
        parser = (APP / "MediaCaptureDate.swift").read_text()
        for required in ("case instant, floating, dateOnly", "value.utf8.count <= 128", "match.range == fullRange",
                         "(1...9_999).contains(year)", "hours <= 18", "hours != 18 || minutes == 0",
                         "namedZone.secondsFromGMT(for: date) == offset", "knownNamedZones.contains(zoneName)",
                         "TimeZone.knownTimeZoneIdentifiers", "guard field(9) == nil",
                         "minimumMillis..<maximumExclusiveMillis", "millis % dayMillis < 0",
                         "daysBeforeYear(year)", "floatingClockMillis: clock"):
            self.assertIn(required, parser)
        self.assertLess(parser.index("trimmingCharacters(in:"), parser.index("value.utf8.count <= 128"))
        for forbidden in ("ISO8601DateFormatter(", "DateFormatter(", "Calendar(identifier:", ".current", ".date(from:"):
            self.assertNotIn(forbidden, parser)
        self.assertIn("{1,64}", parser)

    def test_gallery_and_worklist_share_explicit_zone_precision_and_localization(self):
        library = (APP / "MediaLibrary.swift").read_text()
        view = (APP / "MediaView.swift").read_text()
        worklist = (APP / "MediaWorklistContinuationView.swift").read_text()
        self.assertNotIn("MediaDateParsers", library)
        self.assertNotIn("func mediaCaptureDate(_ item: CameraMediaItem) -> Date?", library)
        for filename in APP.glob("*.swift"):
            for call in re.findall(r"mediaCaptureDate\(([^\n]*)", filename.read_text()):
                self.assertIn("displayZone:", call, filename.name)
        for source in (view, worklist):
            self.assertIn("displayZone: camera.mediaDateDisplayZone", source)
            self.assertIn('language.string("media_time_zone_unknown")', source)
        for name in ("mediaItemsForDisplay", "mediaDateFilterSummary",
                     "mediaRatingFilterSummary", "mediaFolderFilterSummary"):
            call = view.split(name + "(", 1)[1].split(")", 1)[0]
            self.assertIn("displayZone: camera.mediaDateDisplayZone", call)
        self.assertIn("mediaDateGroups(displayedMedia, displayZone: camera.mediaDateDisplayZone)", view)
        self.assertNotIn(".formatted(date:", view)
        self.assertNotIn("let captured = item.captureTime", worklist)
        for language, text in (("en", "Time zone unknown"), ("zh-Hant", "時區未知")):
            strings = (APP / f"Resources/{language}.lproj/Localizable.strings").read_text()
            self.assertEqual(strings.count('"media_time_zone_unknown" = '), 1)
            self.assertIn(f'"media_time_zone_unknown" = "{text}";', strings)

    def test_literal_range_and_snapshot_refresh_preserve_owners_and_dst_tests(self):
        library = (APP / "MediaLibrary.swift").read_text()
        state = (APP / "CameraAppState.swift").read_text()
        root = (APP / "RootView.swift").read_text()
        view = (APP / "MediaView.swift").read_text()
        for required in ("let startDayKey: String", "let endDayKey: String", "contains(dayKey:",
                         "startDayCarrier", "endDayCarrier", "contains(captureDate: Date)"):
            self.assertIn(required, library)
        match = library.split("private func mediaMatchesDateRange(", 1)[1].split("\n}", 1)[0]
        self.assertIn("dateRange.contains(dayKey:", match)
        self.assertNotIn("contains(captureDate:", match)
        self.assertIn("private let dateFilterTimeZone = MediaCaptureDate.carrierTimeZone", view)
        self.assertIn("calendar = Calendar(identifier: .iso8601)", view)
        self.assertIn("dateFilterStart = camera.mediaDateRange?.startDayCarrier", view)
        self.assertIn("dateFilterEnd = camera.mediaDateRange?.endDayCarrier", view)
        self.assertIn(".NSSystemTimeZoneDidChange", root)
        self.assertIn("if active { refreshMediaDateDisplayZone() }", state)
        refresh = state.split("func refreshMediaDateDisplayZone(", 1)[1].split("\n    }", 1)[0]
        for forbidden in ("mediaDateRange =", "mediaLibrarySessionID =", "selectedMediaItems =", "Task", "session."):
            self.assertNotIn(forbidden, refresh)
        self.assertIn("guard owner == mediaLibrarySessionID else { return false }", state)
        self.assertIn("testViewerZoneRefreshPreservesLiteralRangeSelectionAndSessionOwners",
                      (TESTS / "MediaDateFilterOwnershipTests.swift").read_text())
        existing = (TESTS / "MediaLibraryTests.swift").read_text()
        self.assertIn("testMediaDateRangeSpringForwardUsesTwentyThreeHourDay", existing)
        self.assertIn("testMediaDateRangeFallBackIncludesBothRepeatedHoursAndFullLastDay", existing)

    def test_total_tuple_and_loaded_order_ties(self):
        source = (APP / "MediaLibrary.swift").read_text()
        compare = source.split("func compareMediaCaptureDates(", 1)[1].split("\nfunc mediaImagePanBounds", 1)[0]
        self.assertLess(compare.index("leftDay != rightDay"), compare.index("left.kind != right.kind"))
        self.assertLess(compare.index("left.kind != right.kind"), compare.index("left.instantMillis"))
        self.assertIn("right.dateKey == nil ? .orderedSame : .orderedDescending", compare)
        self.assertIn("guard leftValue != rightValue else { return .orderedSame }", compare)
        self.assertNotIn("naturalMediaName", compare)
        self.assertNotIn("dayCarrier", compare)
        self.assertIn("compared == .orderedSame ? left.loadedIndex < right.loadedIndex", source)

    def test_date_sort_parses_once_per_loaded_index_and_groups_avoid_cow(self):
        library = (APP / "MediaLibrary.swift").read_text()
        display = library.split("func mediaItemsForDisplay(", 1)[1].split("struct MediaDateGroup:", 1)[0]
        self.assertEqual(display.count("mediaCaptureDate("), 1)
        self.assertIn("let requiresDate = dateSort || dateRange != nil", display)
        self.assertIn("let date = requiresDate ? mediaCaptureDate", display)
        self.assertIn("MediaDisplayItem(loadedIndex: index, item: item, date: date)", display)
        comparator = display.split("return filtered.sorted", 1)[1]
        self.assertNotIn("mediaCaptureDate(", comparator)
        self.assertIn("compareMediaCaptureDates(left.date, right.date", comparator)
        groups = library.split("func mediaDateGroups(", 1)[1].split("func mediaCaptureDate(", 1)[0]
        self.assertIn("if !groups.isEmpty && groups[groups.count - 1].dayKey == key", groups)
        self.assertNotIn("let last = groups.last", groups)
        tests = (TESTS / "MediaDateReviewContractTests.swift").read_text()
        self.assertIn("testTwentyThousandUnknownRowsKeepLoadedOrderAndOneGroup", tests)
        parser = (APP / "MediaCaptureDate.swift").read_text()
        self.assertIn('let text = "\\(dateKey) \\(timeKey.prefix(5))"', parser)
        self.assertIn("[GMT+0800]", tests)

    def test_review_camera_order_bounded_read_only_owner_and_zero_resend(self):
        source = (APP / "MediaLibrary.swift").read_text()
        recent = source.split("func selectLatestMediaItem(_ items:", 1)[1].split("\n}", 1)[0]
        self.assertIn("items.first", recent)
        after = source.split("func selectLatestMediaItem(\n", 1)[1].split("\n}", 1)[0]
        self.assertIn("!previousIDs.contains($0.id) && mediaIsVideo($0) == (kind == .video)", after)
        for forbidden in ("mediaCaptureDate", ".max", "mediaItemsForDisplay", "sort:", ".captureTime", ".name"):
            self.assertNotIn(forbidden, recent + after)
        state = (APP / "CameraAppState.swift").read_text()
        load = state.split("private func loadLatestMedia(", 1)[1].split("private func publishLatestMedia(", 1)[0]
        for required in ("generation == latestMediaGeneration, !Task.isCancelled",
                         "Self.latestMediaRetryDelays.count + 1",
                         "session.listMedia(maximumItems: Self.latestMediaRequestItemCount)",
                         "afterCaptureFrom: items, previousIDs: review.previousIDs, kind: review.kind",
                         "catch is CancellationError"):
            self.assertIn(required, load)
        for forbidden in ("captureStill(", "toggleRecording(", "startRecording(",
                          "stopRecording(", "mediaLibraryScope"):
            self.assertNotIn(forbidden, load)
        self.assertIn("static let latestMediaRequestItemCount = 8", state)
        self.assertIn("observedMediaIDs.union(mediaItems.map(\\.id))", state)
        journeys = (TESTS / "CaptureMediaJourneyTests.swift").read_text()
        self.assertIn("testPhotoReviewKeepsFirstUnknownDatePhotoAheadOfFutureVideoAndLaterDatedPhoto", journeys)
        self.assertIn("testRecentReviewRetainsFirstUnknownDateRegardlessOfFutureCameraMetadata", journeys)


if __name__ == "__main__":
    unittest.main()
