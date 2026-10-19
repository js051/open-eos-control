"""Retired Android screen wiring guards; these do not execute Compose or an Android runtime."""

import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
UI = ROOT / "android/app/src/main/java/dev/openeos/control/ui"


class AndroidMediaScreenOwnerSourceTests(unittest.TestCase):
    def test_owner_remains_above_keyed_content_and_is_checked_at_dispatch(self):
        source = (UI / "MediaScreen.kt").read_text()
        self.assertLess(
            source.index("val latestState by rememberUpdatedState(state)"), source.index("key(MediaScreenSessionKey(")
        )
        self.assertIn("MediaMetadataOwner(latestState.info, latestState.mediaSessionGeneration)", source)
        gate = (UI / "MediaScreenActionGate.kt").read_text()
        self.assertIn("val current = currentOwner()", gate)
        self.assertIn("if (owner.matches(current.connection, current.generation)) action()", gate)

    def test_all_transfer_preview_and_cleanup_callbacks_use_original_screen_owner(self):
        source = (UI / "MediaScreen.kt").read_text()
        body = source.split("private fun MediaScreenContent(", 1)[1].split("    var pendingDelete", 1)[0]
        self.assertIn(
            "MediaScreenActionGate(MediaMetadataOwner(state.info, state.mediaSessionGeneration), currentOwner)", body
        )
        self.assertIn("val actions = unownedActions.copy(", body)
        for name, args, call in (
            ("downloadMedia", "item -> ", "item"),
            ("downloadMediaBatch", "items -> ", "items"),
            ("saveMediaToPhone", "items -> ", "items"),
            ("openInSerein", "items -> ", "items"),
            ("cancelMediaDownload", "", ""),
            ("uploadMedia", "", ""),
            ("cancelMediaUpload", "", ""),
            ("refreshMedia", "", ""),
            ("cancelMediaLibraryLoad", "", ""),
            ("openMediaPreview", "item -> ", "item"),
            ("previewAdjacentMedia", "items, direction -> ", "items, direction"),
            ("closeMediaPreview", "", ""),
            ("loadMediaThumbnail", "item -> ", "item"),
            ("cancelMediaThumbnail", "item -> ", "item"),
        ):
            self.assertIn(f"{name} = {{ {args}actionGate.dispatch {{ unownedActions.{name}({call}) }} }}", body)
        # All descendants receive this guarded copy. No later call bypasses it.
        content = source.split("    var pendingDelete", 1)[1]
        self.assertNotIn("unownedActions.", content)
        self.assertIn("onPreview = actions.openMediaPreview", content)
        self.assertIn("onDismiss = actions.closeMediaPreview", content)
        self.assertIn("onCancelDownload = actions.cancelMediaDownload", content)
        self.assertIn("actions = actions,", content)

    def test_picker_result_owner_checks_are_preserved(self):
        picker = (UI / "CameraMediaPickerRequests.kt").read_text()
        self.assertIn("request.generation == generation && request.connection === connection", picker)
        launchers = (UI / "CameraMediaPickerLaunchers.kt").read_text()
        self.assertIn("viewModel.beginMediaPicker(CameraMediaPickerKind.DOWNLOAD_DOCUMENT, listOf(item))", launchers)
        self.assertIn(
            "viewModel.completeMediaPicker(context, CameraMediaPickerKind.DOWNLOAD_DOCUMENT, id, uri)", launchers
        )

    def test_saved_gallery_handoff_remains_detached_from_camera_session(self):
        screen = (UI / "MediaScreen.kt").read_text()
        bindings = screen.split("val actions = unownedActions.copy(", 1)[1].split("    var pendingDelete", 1)[0]
        self.assertNotIn("openSavedJpegs", bindings)
        self.assertIn("onClick = actions.openSavedJpegs", screen)
        app = (UI / "OpenEosControlApp.kt").read_text()
        self.assertIn("openSavedJpegs = { showSavedJpegs = true }", app)
        self.assertIn("onSend = { viewModel.sendSavedJpegs(context, savedJpegs.selectedIds) }", app)


if __name__ == "__main__":
    unittest.main()
