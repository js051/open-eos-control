"""Android media mutation structural guards; not Android/native or HTTP execution."""
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
BASE = ROOT / "android/app/src/main"
DATA = BASE / "java/dev/openeos/control/data"
UI = BASE / "java/dev/openeos/control/ui"


class AndroidMediaMutationSourceTests(unittest.TestCase):
    def test_no_generic_setting_decoder_changes_or_fabricated_media_values(self):
        bridge = (DATA / "DesktopBridgeClient.kt").read_text()
        ccapi = (DATA / "CcapiClient.kt").read_text()
        self.assertIn('protected = normalizedMediaMetadataBoolean(item.opt("protected"))', bridge)
        self.assertIn('archived = normalizedMediaMetadataBoolean(item.opt("archived"))', bridge)
        self.assertIn('rotationDegrees = normalizedMediaMetadataInteger(item.opt("rotationDegrees"), MEDIA_ROTATIONS)', bridge)
        self.assertIn('rotationDegrees = normalizedMediaMetadataInteger(item.opt("rotate"), MEDIA_ROTATIONS)', ccapi)
        self.assertIn('private fun JSONObject.optNullableBoolean', bridge)
        self.assertIn('canonicalMediaMetadataFlag(body.opt("protect"))', ccapi)
        self.assertIn('canonicalMediaMetadataRating(body.opt("rating"))', ccapi)
        self.assertIn('canonicalMediaMetadataRotation(body.opt("rotate"))', ccapi)

    def test_all_bridge_targets_and_canon_identity_must_match_before_evidence(self):
        bridge = (DATA / "DesktopBridgeClient.kt").read_text()
        section = bridge[bridge.index('    suspend fun setMediaProtection('):bridge.index('    suspend fun mediaThumbnail(')]
        for field in ('PROTECTION', 'ARCHIVE', 'RATING', 'ROTATION'):
            self.assertIn(f'MediaMetadataTarget(MediaMetadataField.{field}', section)
        self.assertLess(section.index('check(target.matches('), section.index('observedFeatures.add(feature)'))
        ccapi = (DATA / "CcapiClient.kt").read_text()
        self.assertIn('if (target.matches(item.id, latest.metadataReadback()))', ccapi)

    def test_exact_frozen_roster_current_eligibility_and_owner_fences(self):
        vm = (UI / "CameraViewModel.kt").read_text()
        batch = vm[vm.index('    private fun updateMediaMetadataBatch('):vm.index('    internal fun beginMediaPicker(')]
        self.assertIn('items.distinctBy(CameraMediaItem::id).toList()', batch)
        self.assertIn('selectedItems.size > MAX_MEDIA_METADATA_BATCH_ITEMS', batch)
        self.assertIn('current.mediaItems.firstOrNull { it.id == request.id }', batch)
        self.assertIn('mediaMetadataPreflight(target.field, current.supports(feature)', batch)
        self.assertIn('generation == cameraSessionGeneration && _uiState.value.info === state.info', batch)
        self.assertIn('if (!isCurrent()) current else current.copy(', batch)
        self.assertNotIn('retryMediaRead', batch)
        self.assertNotIn('repository.mediaInfo', batch)
        screen = (UI / "MediaScreen.kt").read_text()
        self.assertIn('batchMetadataSelection?.let { frozenItems ->', screen)
        self.assertIn('actions.setMediaRatingBatch(frozenItems, it)', screen)
        self.assertIn('hiddenCount = roster.hiddenCount', screen)
        self.assertIn('unloadedCount = roster.unloadedCount', screen)

    def test_retired_ui_callbacks_and_vm_selection_both_require_original_owner(self):
        vm = (UI / "CameraViewModel.kt").read_text()
        screen = (UI / "MediaScreen.kt").read_text()
        self.assertIn('items is MediaMetadataSelection && !items.owner.matches(state.info, state.mediaSessionGeneration)', vm)
        self.assertIn('val latestState by rememberUpdatedState(state)', screen)
        self.assertIn('frozenItems.owner.matches(it.connection, it.generation)', screen)
        self.assertIn('onRate = { if (canDispatch()) actions.setMediaRatingBatch', screen)
        self.assertIn('selectedIds.mapNotNull(selectedSnapshots::get)', screen)
        self.assertNotIn('selectedIds = selectedIds.intersect', screen)
        self.assertIn('previousMediaMetadataBatch = mediaMetadataBatch?.retiredMetadataReceipt()', vm)
        self.assertIn('state.previousMediaMetadataBatch?.let', screen)

    def test_media_wire_guard_precedes_platform_decode_and_does_not_change_settings(self):
        for filename in ('DesktopBridgeClient.kt', 'CcapiClient.kt'):
            source = (DATA / filename).read_text()
            self.assertIn('strictMediaJsonLimit: Int? = null', source)
            self.assertIn('strictMediaJsonLimit = MAX_MEDIA_METADATA_JSON_BYTES', source)
            self.assertIn('readStrictMediaJsonText(input, it.contentLength(), strictMediaJsonLimit)', source)
            self.assertIn('if (strictMediaJsonLimit != null) StrictMediaJson(body).validate()', source)
        self.assertIn('StrictMediaJson(text).validate()', (DATA / 'BridgeMediaListingDecoder.kt').read_text())
        self.assertIn('val key = string()', (DATA / 'StrictMediaJson.kt').read_text())
        self.assertIn('check(keys.add(key))', (DATA / 'StrictMediaJson.kt').read_text())

    def test_mutation_only_merges_confirmed_field_preserving_writable_veto(self):
        vm = (UI / 'CameraViewModel.kt').read_text()
        self.assertIn('current.withVerifiedMediaMetadata(target, updated)', vm)
        self.assertIn('current.withVerifiedMetadata(target, readback)', vm)
        helper = (DATA / 'MediaMetadataItem.kt').read_text()
        for field in ('protected', 'archived', 'rating', 'rotationDegrees'):
            self.assertIn(f'copy({field} = readback.{field})', helper)
        self.assertNotIn('ratingWritable =', helper)

    def test_single_path_uses_current_exact_id_evidence_and_guards_retired_ui_owner(self):
        vm = (UI / 'CameraViewModel.kt').read_text()
        section = vm[vm.index('    private fun updateMediaMetadata('):vm.index('    fun cancelMediaMetadataBatch(')]
        self.assertIn('currentMediaMetadataItem(', section)
        self.assertIn('item, current.mediaItems, current.mediaPreviewItem, current.captureReviewItem', section)
        self.assertIn('!eligible(state, currentItem(state))', section)
        self.assertLess(section.index('if (!eligible(current, selected)) return@runCamera'), section.index('val updated = update(selected)'))
        screen = (UI / 'MediaScreen.kt').read_text()
        for action in ('Protection', 'Archived', 'Rating', 'Rotation'):
            self.assertIn(f'if (canDispatchSingle()) actions.setMedia{action}(item, it)', screen)

    def test_download_executor_and_uncertainty_remain_separate(self):
        vm = (UI / "CameraViewModel.kt").read_text()
        generic = vm[vm.index('internal suspend fun executeMediaBatch('):vm.index('internal suspend fun <Result> retryMediaRead(')]
        self.assertIn('if (operation == MediaBatchOperation.DOWNLOAD) break', generic)
        self.assertNotIn('MediaMetadataOutcome', generic)
        core = (UI / "MediaMetadataBatch.kt").read_text()
        self.assertEqual(1, core.count('val updated = action(request)'))
        self.assertIn('request.target.matches(request.id, readback(updated))', core)
        self.assertIn('publish(false)', core)
        self.assertNotIn('DownloadHistory', core)
        self.assertNotIn('DELETE', core)

    def test_bilingual_receipts_do_not_call_uncertain_failed_or_safe_to_retry(self):
        required = {'media_metadata_frozen_scope', 'media_metadata_summary', 'media_metadata_cancel',
                    'media_metadata_uncertain_help', 'media_metadata_row', 'media_metadata_target'}
        for folder in ('values', 'values-zh-rTW'):
            entries = {item.attrib['name']: item.text for item in ET.parse(BASE / 'res' / folder / 'strings.xml').getroot() if item.tag == 'string'}
            self.assertTrue(required <= entries.keys())
        component = (UI / 'MediaSelectionComponents.kt').read_text()
        self.assertIn('MediaMetadataOutcome.UNCERTAIN -> R.string.media_metadata_uncertain', component)
        self.assertIn('heightIn(max = 200.dp)', component)
        self.assertIn('$rowTagPrefix-${row.request.id}', component)


if __name__ == '__main__':
    unittest.main()
