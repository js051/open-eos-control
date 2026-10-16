"""Source guards only; the restored picker still requires native Compose execution."""

import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]


class MediaPickerTerminalSourceTests(unittest.TestCase):
    def test_restored_folder_accounts_for_stopped_rows_and_waits_for_retirement(self):
        source = (
            ROOT / "android/app/src/androidTest/java/dev/openeos/control/ui/CameraMediaPickerSessionTest.kt"
        ).read_text()
        case = source.split("fun restoredFolderRequestKeepsItsRosterAndStopsAfterFirstProviderFailure()", 1)[1]
        case = case.split("private fun newViewModel", 1)[0]
        for required in (
            "assertEquals(2, items.size)",
            "state.lastMediaBatchResult != null && !state.isBusy(CameraOperation.MEDIA)",
            "state.mediaBatchProgress == null && state.mediaSaveFeedback.values.none { it.isPending }",
            "assertEquals(items.size, result.totalItems)",
            "assertEquals(0, result.succeededItems)",
            "assertEquals(listOf(items.first().name), result.failedItemNames)",
            "assertEquals(1, result.failedItems)",
            "assertEquals(1, result.notStartedItems)",
            "assertEquals(items.map(CameraMediaItem::id).toSet(), state.mediaSaveFeedback.keys)",
            "assertTrue(state.mediaSaveFeedback[items.first().id] is MediaSaveFeedback.Failed)",
            "assertEquals(MediaSaveFeedback.NotStarted, state.mediaSaveFeedback[items[1].id])",
            "assertTrue(cameraA.originalReads.isEmpty())",
            "assertTrue(cameraB.originalReads.isEmpty())",
        ):
            self.assertIn(required, case)


if __name__ == "__main__":
    unittest.main()
