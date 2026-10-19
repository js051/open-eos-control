"""USB listing wiring guards only; no Android SDK, USB device or native execution."""

import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
MAIN = ROOT / "android/app/src/main/java/dev/openeos/control"


class AndroidUsbListingCompletionSourceTests(unittest.TestCase):
    def listing(self):
        source = (MAIN / "data/UsbPtpCameraBackend.kt").read_text()
        return source.split("    override suspend fun listMedia(", 1)[1].split("    // Only the latest listing", 1)[0]

    def test_recent_budget_counts_accepted_media_not_raw_handles(self):
        listing = self.listing()
        self.assertIn("val remainingItems = maximumItems?.let { (it - hostItems.size).coerceAtLeast(0) }", listing)
        self.assertIn("for (handle in handles)", listing)
        gate = "if (remainingItems != null && objectInfos.size >= remainingItems) break"
        self.assertIn(gate, listing)
        self.assertLess(listing.index(gate), listing.index("val objectInfo = ptp.objectInfo(handle)"))
        self.assertIn(
            "objectInfo.objectFormat != PtpObjectFormat.ASSOCIATION && objectInfo.filename.isNotBlank()", listing
        )
        self.assertNotIn("values.take(", listing)

    def test_required_read_failure_precedes_optional_metadata_and_terminal_completion(self):
        listing = self.listing()
        failure = "firstFailure?.let { throw it }"
        self.assertIn(failure, listing)
        self.assertLess(listing.index(failure), listing.index("val ratingSamples"))
        self.assertLess(listing.index(failure), listing.index("return completeMediaOperation"))
        self.assertNotIn("items.isEmpty() && firstFailure", listing)
        self.assertNotIn("&& hostItems.isEmpty()", listing)

    def test_progress_and_cancellation_remain_separate_from_terminal_success(self):
        listing = self.listing()
        self.assertIn("objectInfos.size % MEDIA_LIST_PROGRESS_BATCH_SIZE == 0", listing)
        self.assertIn("onProgress(hostItems + objectInfos.map", listing)
        self.assertIn("catch (exception: CancellationException) {\n                throw exception", listing)
        self.assertIn("if (firstFailure == null) firstFailure = exception", listing)
        self.assertIn("maximumItems?.let(listed::take) ?: listed", listing)
        self.assertNotIn("retry", listing.lower())

    def test_viewmodel_marks_only_successful_return_complete_and_retains_error_state(self):
        source = (MAIN / "ui/CameraViewModel.kt").read_text()
        section = source.split("    fun refreshMedia()", 1)[1].split("    fun openCaptureReview()", 1)[0]
        self.assertLess(
            section.index("repository.listMedia(maximumItemsFor(scope))"),
            section.index("mediaLibraryLoadStatus = MediaLibraryLoadStatus.COMPLETE"),
        )
        self.assertIn("mediaLibraryLoadStatus = MediaLibraryLoadStatus.FAILED", section)
        self.assertIn("MediaLibraryBatch(take(RECENT_MEDIA_ITEMS), size > RECENT_MEDIA_ITEMS)", source)


if __name__ == "__main__":
    unittest.main()
