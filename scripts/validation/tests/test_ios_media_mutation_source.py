"""iOS metadata batch source/wiring guards, NOT native Swift execution."""
import hashlib
import json
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
CORE = ROOT / 'ios/OpenEOSCore/Sources/OpenEOSCore'
APP = ROOT / 'ios/OpenEOSControl/App'
TESTS = ROOT / 'ios/OpenEOSControl/Tests'


class IOSMediaMutationSourceTests(unittest.TestCase):
    def test_shared_corpus_and_native_resource_are_fixed(self):
        path = ROOT / 'contracts/media-mutation/v1/media-mutation-vectors.json'
        raw = path.read_bytes()
        self.assertEqual(hashlib.sha256(raw).hexdigest(), '2d30173f8abb0538f1aa320cfce24e2fb0e01f86ef6fc54db93a052ed2fdd401')
        data = json.loads(raw)
        self.assertEqual([len(data[k]) for k in ['metadataVectors', 'readbackVectors', 'batchVectors', 'rosterVectors']], [200, 66, 10, 3])
        project = (APP.parent / 'project.yml').read_text()
        self.assertEqual(project.count('../../contracts/media-mutation/v1/media-mutation-vectors.json'), 1)
        self.assertIn('createIntermediateGroups: false\n        buildPhase: resources', project)

    def test_scoped_raw_tokens_are_retained_before_foundation_numeric_coercion(self):
        source = (CORE / 'CameraMediaMutation.swift').read_text()
        for token in ['decodeForegroundJSONObject(data)', 'depth <= 512', 'data.count <= 64 * 1024 * 1024',
                      'scanner.invalid.reversed()', 'bytes.replaceSubrange', 'CFBooleanGetTypeID()',
                      '!["f", "d"].contains', 'text.utf8.elementsEqual(String($0).utf8)',
                      '["0", "1", "2", "3", "4", "5"]', '["0", "90", "180", "270"]']:
            self.assertIn(token, source)
        self.assertNotIn('.intValue', source)
        self.assertNotIn('doubleValue', source)
        canonical = source.split('static func canonRating', 1)[1].split('enum CameraMediaMetadataJSON', 1)[0]
        self.assertNotIn('stringValue', canonical)
        self.assertIn('guard let text = raw as? String', canonical)

    def test_all_media_entrypoints_use_scoped_decoder_without_generic_parser_edits(self):
        bridge = (CORE / 'DesktopBridgeClient.swift').read_text()
        ccapi = (CORE / 'CCAPIClient.swift').read_text()
        decoder = (CORE / 'BridgeMediaListingDecoder.swift').read_text()
        self.assertIn('CameraMediaMetadataJSON.decode(data)', decoder)
        self.assertIn('CameraMediaMetadataJSON.decode(data)', bridge)
        # The Bridge parser now has dedicated setting/configuration branches.
        # Inspect the scoped metadata branch and its ordinary JSON fallback,
        # rather than requiring the earlier equivalent ternary spelling.
        bridge_parser = bridge.split('private func requestJSON(', 1)[1].split('private func requestOK(', 1)[0]
        metadata_branch = bridge_parser.split('} else if mediaMetadata {', 1)[1].split('guard let object', 1)[0]
        self.assertIn('decoded = try? CameraMediaMetadataJSON.decode(response.body)', metadata_branch)
        self.assertIn('} else {\n            decoded = try? JSONSerialization.jsonObject(with: response.body)', metadata_branch)
        self.assertLess(metadata_branch.index('CameraMediaMetadataJSON.decode(response.body)'),
                        metadata_branch.index('JSONSerialization.jsonObject(with: response.body)'))
        self.assertIn('mediaMetadata ? CameraMediaMetadataJSON.decode(response.body)', ccapi)
        for source in (bridge, ccapi):
            self.assertIn('CameraMediaMetadataValue.boolean', source)
            self.assertIn('CameraMediaMetadataValue.integer', source)
            self.assertIn('maximumBytes: 1_048_576', source)

    def test_dispatch_is_once_and_atomically_retired(self):
        marker = (CORE / 'CameraMediaMutation.swift').read_text()
        self.assertIn('guard !retired, !sent else { throw CancellationError() }', marker)
        self.assertIn('public func cancel() { lock.lock(); retired = true; lock.unlock() }', marker)
        ccapi = (CORE / 'CCAPIClient.swift').read_text().split('private func modifyMedia(', 1)[1].split('private func mediaPath(', 1)[0]
        self.assertEqual(ccapi.count('transport.send(request)'), 1)
        self.assertLess(ccapi.index('try attempt?.markDispatched()'), ccapi.index('transport.send(request)'))
        self.assertLess(ccapi.index('transport.send(request)'), ccapi.index('for readAttempt in 0..<3'))
        self.assertIn('target.confirms(latest, original: item)', ccapi)
        bridge = (CORE / 'DesktopBridgeClient.swift').read_text().split('private func updateMediaMetadata(', 1)[1].split('public func mediaThumbnail(', 1)[0]
        self.assertEqual(bridge.count('requestJSON('), 1)
        self.assertIn('target.confirms(updated, original: item)', bridge)

    def test_roster_keeps_first_byte_exact_identity_and_refreshes_loaded_metadata(self):
        model = (APP / 'MediaMetadataBatch.swift').read_text()
        for token in ['[Data: CameraMediaItem]', 'Set<Data>()', 'selection.filter',
                      'seen.insert(mediaItemIdentity($0)).inserted', 'item: current ?? $0', 'loadedAtConfirmation: current != nil']:
            self.assertIn(token, model)
        self.assertNotIn('.prefix(', model)
        self.assertNotIn('.sorted', model)

    def test_engine_retains_all_rows_stops_on_uncertainty_and_has_no_mutation_retry(self):
        source = (APP / 'CameraAppState.swift').read_text()
        batch = source.split('var canPrepareMediaMetadataBatch:', 1)[1].split('var canStartMediaBatchDownload:', 1)[0]
        for token in ['draft.rows.count > 1_000', 'mediaMetadataBatches.append(run)',
                      'draft.sessionID != mediaLibrarySessionID', 'sessionGeneration == draft.generation',
                      '.unconfirmed', '.failedNotSent', '.notStarted', '.unknownMetadata',
                      'No mutation retry or automatic continuation', 'draft.target.confirms(updated, original: entry.item)']:
            self.assertIn(token, batch)
        self.assertEqual(batch.count('session.mutateMedia('), 1)
        self.assertNotIn('listMedia(', batch)
        self.assertNotIn('mediaInfo(', batch)
        self.assertNotIn('downloadMedia(', batch)
        cancel = batch.split('func cancelMediaMetadataBatch()', 1)[1].split('private func retireMediaMetadataBatch()', 1)[0]
        self.assertNotIn('mediaMetadataBatchTask?.cancel()', cancel)
        retire = batch.split('private func retireMediaMetadataBatch()', 1)[1].split('private func performMediaMetadataBatch', 1)[0]
        self.assertIn('mediaMetadataAttempt?.cancel()', retire)
        self.assertNotIn('mediaMetadataBatches = []', retire)

    def test_writable_veto_is_operation_specific_and_never_creates_capability(self):
        source = (CORE / 'CameraMediaMutation.swift').read_text()
        for field in ['ratingWritable', 'protectionWritable', 'rotationWritable', 'archiveWritable']:
            self.assertIn(f'item.{field} != false', source)
            for client in ['CCAPIClient.swift', 'DesktopBridgeClient.swift']:
                self.assertIn(f'CameraMediaMetadataValue.boolean(item["{field}"])', (CORE / client).read_text())
        batch = (APP / 'CameraAppState.swift').read_text()
        self.assertIn('supported: supports(draft.target.feature)', batch)
        model = (APP / 'MediaMetadataBatch.swift').read_text()
        self.assertIn('guard supported else', model)
        self.assertIn('target.isWritable(snapshot), target.isWritable(current)', model)

    def test_ui_reviews_frozen_roster_and_exposes_results_in_both_languages(self):
        panel = (APP / 'MediaMetadataBatchPanel.swift').read_text()
        for token in ['ForEach(value.rows)', 'camera.startMediaMetadataBatch(value)',
                      'camera.cancelMediaMetadataBatch()', 'Text(row.item.id)', 'batch.ownerRetired',
                      '.rating($0)', '.rotation($0)', '.protection(true)', '.protection(false)', '.archive(true)', '.archive(false)']:
            self.assertIn(token, panel)
        self.assertIn('MediaMetadataBatchPanel(', (APP / 'MediaBatchPanel.swift').read_text())
        languages = []
        for language in ['en', 'zh-Hant']:
            text = (APP / f'Resources/{language}.lproj/Localizable.strings').read_text()
            keys = re.findall(r'^"(media_mutation_[^"]+)"\s*=', text, re.MULTILINE)
            self.assertEqual(len(keys), len(set(keys)))
            self.assertGreaterEqual(len(keys), 30)
            languages.append(set(keys))
        self.assertEqual(*languages)

    def test_accessibility_actions_keep_same_draft_and_capability_guards(self):
        panel = (APP / 'MediaMetadataBatchPanel.swift').read_text()
        self.assertIn('if dynamicTypeSize.isAccessibilitySize {', panel)
        self.assertIn('Menu(language.string("media_mutation_menu"))', panel)
        accessible = panel.split('private var accessibleActions:', 1)[1].split('private func action(', 1)[0]
        for token in ['expansionButton', 'ForEach(0...5', 'ForEach([0, 90, 180, 270]',
                      'accessibleAction(.protection(true))', 'accessibleAction(.protection(false))',
                      'accessibleAction(.archive(true))', 'accessibleAction(.archive(false))',
                      '!camera.supports(.mediaRating)', '!camera.supports(.mediaRotate)',
                      '!camera.canPrepareMediaMetadataBatch']:
            self.assertIn(token, accessible)
        self.assertNotIn('startMediaMetadataBatch', accessible)
        self.assertIn('.accessibilityIdentifier(id)', accessible)
        self.assertIn('expanded.wrappedValue.toggle()', accessible)
        self.assertNotIn('.accessibilityElement(children: .contain)', accessible)
        action = panel.split('private func action(', 1)[1]
        self.assertIn('camera.makeMediaMetadataBatchDraft(target, displayed: displayedItems)', action)
        self.assertIn('.disabled(!camera.supports(target.feature))', action)
        self.assertIn('if draft != nil {', action)

    def test_ui_journey_reaches_review_cancel_and_confirm_in_both_languages(self):
        ui = (APP.parent / 'UITests/OpenEOSControlUITests.swift').read_text()
        test = ui.split('func testCanonicalMetadataBatchReviewCancellationAndExactReadbackInBothLanguages()', 1)[1].split('func testCanonicalBatchPreparesTwoOriginals', 1)[0]
        for token in ['"english"', '"traditionalChinese"', 'media-mutation-menu',
                      'media-mutation-action-rating-5', 'media-mutation-review-cancel',
                      'media-mutation-confirm', 'Cancelling review must not write', 'media-mutation-result-']:
            self.assertIn(token, test)
        self.assertLess(test.index('Cancelling review must not write'), test.index('media-mutation-confirm'))

    def test_total_history_admission_is_bounded_before_any_append_and_clear_is_explicit(self):
        model = (APP / 'MediaMetadataBatch.swift').read_text()
        self.assertIn('static let maximumBatches = 20', model)
        self.assertIn('static let maximumRows = 2_000', model)
        source = (APP / 'CameraAppState.swift').read_text()
        start = source.split('func startMediaMetadataBatch(', 1)[1].split('var mediaMetadataHistoryRowCount:', 1)[0]
        self.assertLess(start.index('draft.historyEpoch != mediaMetadataHistoryEpoch'), start.index('mediaMetadataBatches.append(run)'))
        self.assertLess(start.index('MediaMetadataBatchHistory.maximumRows - mediaMetadataHistoryRowCount'), start.index('mediaMetadataBatches.append(run)'))
        reject = start.split('if let rejection {', 1)[1].split('mediaMetadataBatchRejection = nil', 1)[0]
        self.assertNotIn('append', reject)
        self.assertIn('return false', reject)
        clear = source.split('func clearMediaMetadataBatchHistory(', 1)[1].split('func cancelMediaMetadataBatch()', 1)[0]
        for token in ['guard confirmed, canClearMediaMetadataHistory', 'mediaMetadataBatches.remove(at: index)', 'mediaMetadataHistoryEpoch = UUID()']:
            self.assertIn(token, clear)
        self.assertIn('!mediaMetadataBatches.contains { $0.running || $0.rows.contains { $0.status == .applying } }', source)
        self.assertNotIn('Set<UUID>', clear)
        panel = (APP / 'MediaMetadataBatchPanel.swift').read_text()
        for token in ['LazyVStack', '.alert(', 'media_mutation_history_clear_warning', 'media-mutation-history-clear-cancel',
                      'media-mutation-history-clear-confirm', 'camera.canClearMediaMetadataHistory', 'camera.mediaMetadataBatchRejection']:
            self.assertIn(token, panel)
        ui = (APP.parent / 'UITests/OpenEOSControlUITests.swift').read_text()
        self.assertIn('Cancelling history removal retains the finished batch', ui)
        self.assertIn('Clearing a result record must not undo camera changes', ui)
        native = (TESTS / 'MediaMutationContractTests.swift').read_text()
        for name in ['testHistoryBatchAdmissionIsBoundedAcrossOwnersAndClearInvalidatesOldReviews',
                     'testHistoryTotalRowAdmissionRejectsWholeBatchWithoutDroppingPriorResults',
                     'testHistoryCannotClearWhileRunningAndUncertainRecordsRequireExplicitConfirmation',
                     'testRetiredInflightHistoryClearDoesNotApplyLateReadbackOrReplay']:
            self.assertIn(name, native)

    def test_partial_mutation_cache_merge_preserves_veto_without_changing_raw_client_contract(self):
        client = (CORE / 'DesktopBridgeClient.swift').read_text().split('private func updateMediaMetadata(', 1)[1].split('public func mediaThumbnail(', 1)[0]
        self.assertIn('return updated', client)
        self.assertNotIn('mergingConfirmedField', client)
        app = (APP / 'CameraAppState.swift').read_text()
        self.assertIn('applyUpdatedMedia(target.mergingConfirmedField(into: current))', app)
        self.assertIn('applyUpdatedMedia(draft.target.mergingConfirmedField(into: current))', app)
        model = (APP / 'MediaMetadataBatch.swift').read_text().split('func mergingConfirmedField(', 1)[1].split('var actionID:', 1)[0]
        for field in ['writable', 'ratingWritable', 'protectionWritable', 'rotationWritable', 'archiveWritable', 'folder']:
            self.assertIn(f'{field}: item.{field}', model)
        native = (TESTS / 'MediaMutationContractTests.swift').read_text()
        self.assertIn('testBridgePartialMutationResponsePreservesUnrelatedFieldsAndWritableVetoes', native)
        self.assertIn('Single-item updates use the same safe merge', native)
        existing = (CORE.parent.parent / 'Tests/OpenEOSCoreTests/DesktopBridgeMediaFolderTests.swift').read_text()
        self.assertIn('testInfoAndMutationUseFreshPairedProvenanceAndDoNotCarryMissingFieldsForward', existing)
        self.assertIn('XCTAssertNil(unknownMutation.folder)', existing)

    def test_single_metadata_callbacks_carry_opening_owner_before_async_execution(self):
        view = (APP / 'MediaView.swift').read_text()
        for token in ['private struct MediaMetadataPresentation: Identifiable', 'let owner: UUID',
                      'MediaMetadataView(itemID: presentation.itemID, owner: presentation.owner)',
                      'MediaMetadataPresentation(itemID: item.id, owner: metadataOwner)',
                      'guard pending.owner == camera.mediaLibrarySessionID else { return }']:
            self.assertIn(token, view)
        calls = [line for line in view.splitlines() if re.search(r'camera.setMedia(Protection|Rating|Rotation|Archive)\(', line)]
        self.assertEqual(len(calls), 6)
        self.assertTrue(all('owner: owner' in line and 'mediaLibrarySessionID' not in line for line in calls))
        app = (APP / 'CameraAppState.swift').read_text()
        helper = app.split('private func updateMediaMetadata(', 1)[1].split('func startMediaDownload(', 1)[0]
        self.assertLess(helper.index('guard owner == mediaLibrarySessionID'), helper.index('begin(.media)'))
        self.assertLess(helper.index('guard owner == mediaLibrarySessionID, !Task.isCancelled, let session'), helper.index('try await update(session, attempt)'))
        self.assertIn('guard owner == mediaLibrarySessionID, generation == sessionGeneration', helper)
        self.assertIn('singleMediaMetadataAttempt?.cancel()', app)
        for name in ['Protection', 'Rating', 'Rotation', 'Archive']:
            declaration = re.search(rf'func setMedia{name}\([^\n]+', app).group(0)
            self.assertIn('owner: UUID)', declaration)
            self.assertNotIn('owner: UUID =', declaration)
        native = (TESTS / 'MediaMutationContractTests.swift').read_text()
        self.assertIn('testDelayedSingleMetadataCallbacksCannotMutateReplacementOwnerWithSameID', native)
        self.assertIn('owner: openingOwner', native)
        self.assertIn('Replacement camera must receive zero PUTs', native)
        self.assertIn('Same-owner single-item actions remain usable', native)

    def test_single_metadata_ui_uses_same_writable_policy_and_localized_reason(self):
        view = (APP / 'MediaView.swift').read_text().split('private struct MediaMetadataView: View', 1)[1].split('private struct MediaPreviewView: View', 1)[0]
        for operation, field in [('protection', 'protected'), ('archive', 'archived')]:
            for value in ['true', 'false']:
                self.assertIn(f'enabled: CameraMediaMutation.{operation}({value}).isWritable(item) && item.{field} != {value}', view)
        self.assertIn('|| !CameraMediaMutation.rotation(0).isWritable(item)', view)
        self.assertIn('|| !CameraMediaMutation.rating(rating).isWritable(item) || item.rating == rating', view)
        for feature in ['mediaProtect', 'mediaRating', 'mediaRotate', 'mediaArchive']:
            self.assertIn(f'if camera.supports(.{feature})', view)
        self.assertIn('if camera.supports(.mediaArchive), item.archived != nil', view)
        for target in ['protection(true)', 'rating(0)', 'rotation(0)', 'archive(true)']:
            self.assertIn(f'metadataWritableReason(item, target: .{target})', view)
        reason = view.split('private func metadataWritableReason', 1)[1].split('private func protectionLabel', 1)[0]
        self.assertIn('if !target.isWritable(item)', reason)
        self.assertIn('language.string("media_metadata_not_writable")', reason)
        for locale in ['en', 'zh-Hant']:
            strings = (APP / f'Resources/{locale}.lproj/Localizable.strings').read_text()
            self.assertEqual(strings.count('"media_metadata_not_writable" = '), 1)
        app = (APP / 'CameraAppState.swift').read_text()
        self.assertIn('supports(target.feature), target.isWritable(current), begin(.media)', app)
        native = (TESTS / 'MediaMutationContractTests.swift').read_text()
        self.assertIn('testSingleMetadataWritablePolicyKeepsUnknownAllowedAndVetoesOnlyMatchingField', native)
        self.assertIn('for global in [nil, true, false] as [Bool?]', native)
        self.assertIn('for field in [nil, true, false] as [Bool?]', native)

    def test_native_suite_calls_production_clients_and_executor_and_does_not_claim_run(self):
        native = (TESTS / 'MediaMutationContractTests.swift').read_text()
        for token in ['NOT_RUN', 'client.mediaInfo(', 'client.listMedia(', 'simulator.listMedia(',
                      'session.mutateMedia(', 'state.startMediaMetadataBatch(', 'state.mediaMetadataBatchTask',
                      'CameraMediaMutationAttempt()', 'state.requestDisconnect()', 'state?.cancelMediaMetadataBatch()',
                      'testRosterRefreshesStaleSelectionFromExactLoadedItemBeforeEligibility',
                      'testOverLimitRejectsEntireBatchWithoutSilentlyTruncating']:
            self.assertIn(token, native)
        self.assertNotRegex(native, r'XCT\w+\([^\n]*await ')


if __name__ == '__main__':
    unittest.main()
