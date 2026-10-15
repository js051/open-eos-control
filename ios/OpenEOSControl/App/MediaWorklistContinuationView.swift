import Foundation
import OpenEOSCore
import SwiftUI

/// Choosing a row or opening a source menu performs no camera I/O. The only
/// authorization is the final confirmation, consumed by CameraAppState once.
struct MediaWorklistContinuationView: View {
    @EnvironmentObject private var camera: CameraAppState
    @EnvironmentObject private var language: AppLanguageStore
    @State private var acknowledgedUnknown = false
    @State private var confirmedShareMeaning = false
    @State private var showConfirmation = false

    private var ready: Bool {
        camera.canConfirmMediaWorklistContinuation && confirmedShareMeaning
            && (camera.mediaWorklistContinuation?.requiresUnknownAcknowledgement != true || acknowledgedUnknown)
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text(language.string("media_worklist_binding_help"))
                    Text(language.string("media_worklist_loaded_help"))
                        .font(.caption).foregroundStyle(Color.cameraSecondaryText)
                    if camera.mediaItems.isEmpty {
                        Text(language.string("media_worklist_no_loaded_originals"))
                            .accessibilityIdentifier("media-worklist-no-loaded-originals")
                    }
                }
                if let draft = camera.mediaWorklistContinuation {
                    ForEach(Array(draft.rows.enumerated()), id: \.element.id) { index, row in
                        Section {
                            Text(verbatim: row.filename)
                                .font(.headline)
                                .fixedSize(horizontal: false, vertical: true)
                            Text(language.string(row.status.map { "media_delivery_history_status_" + $0.rawValue } ?? "media_worklist_queued"))
                                .font(.caption)
                            Picker(language.string("media_worklist_choose_original"), selection: Binding<Data?>(
                                get: {
                                    camera.mediaWorklistContinuation?.rows.first { $0.origin == row.origin }?.item.map(mediaItemIdentity)
                                },
                                set: { _ = camera.bindMediaWorklistOriginal(origin: row.origin, identity: $0) }
                            )) {
                                Text(language.string("media_worklist_no_binding")).tag(Optional<Data>.none)
                                ForEach(Array(camera.mediaItems.enumerated()), id: \.offset) { sourceIndex, item in
                                    Text(verbatim: sourceLabel(item, position: sourceIndex + 1))
                                        .tag(Optional(mediaItemIdentity(item)))
                                        .disabled(draft.rows.contains { $0.origin != row.origin && $0.item.map(mediaItemIdentity) == mediaItemIdentity(item) })
                                }
                            }
                            .pickerStyle(.navigationLink)
                            .accessibilityIdentifier("media-worklist-binding-\(row.origin.rowID.uuidString)")
                            if let item = row.item {
                                Text(language.format("media_worklist_mapping", row.filename, sourceLabel(item)))
                                    .font(.callout)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                        } header: {
                            Text(language.format("media_worklist_position", index + 1, draft.rows.count))
                        }
                    }
                    if draft.requiresUnknownAcknowledgement {
                        Section {
                            Text(language.string("media_worklist_unknown_warning"))
                            Toggle(language.string("media_worklist_unknown_acknowledgement"), isOn: $acknowledgedUnknown)
                                .accessibilityIdentifier("media-worklist-unknown-acknowledgement")
                        }
                    }
                }
                Section {
                    Text(language.string("media_worklist_share_semantics"))
                    Toggle(language.string("media_worklist_share_acknowledgement"), isOn: $confirmedShareMeaning)
                        .accessibilityIdentifier("media-worklist-share-acknowledgement")
                    Button(language.string("media_worklist_review_confirm")) { showConfirmation = true }
                        .disabled(!ready)
                        .accessibilityIdentifier("media-worklist-review-confirm")
                }
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .foregroundStyle(Color.cameraText)
            .background(Color.cameraSurface)
            .navigationTitle(language.string("media_worklist_continue_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(language.string("cancel")) { camera.cancelMediaWorklistContinuation() }
                        .accessibilityIdentifier("media-worklist-cancel-binding")
                }
            }
            .confirmationDialog(language.string("media_worklist_confirm_title"),
                                isPresented: $showConfirmation, titleVisibility: .visible) {
                Button(language.string("media_worklist_confirm_prepare")) {
                    _ = camera.confirmMediaWorklistContinuation(
                        confirmedMappingAndShare: confirmedShareMeaning, acknowledgedUnknown: acknowledgedUnknown)
                }
                .disabled(!ready)
                .accessibilityIdentifier("media-worklist-confirm-prepare")
                Button(language.string("cancel"), role: .cancel) { camera.cancelMediaWorklistContinuation() }
            } message: {
                Text(language.format("media_worklist_confirm_message", camera.mediaWorklistContinuation?.rows.count ?? 0))
            }
        }
        .accessibilityIdentifier("media-worklist-continuation")
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
        .onChange(of: camera.mediaWorklistContinuation?.rows.map { $0.item.map(mediaItemIdentity) }) { _ in
            acknowledgedUnknown = false
            confirmedShareMeaning = false
        }
    }

    private func sourceLabel(_ item: CameraMediaItem, position: Int? = nil) -> String {
        // Only already-loaded descriptive context helps distinguish choices.
        // None of it proves equivalence to the historical row or is persisted.
        let prefix = position.map { "\($0). " } ?? ""
        let kind: String
        switch item.kind.lowercased() {
        case "image", "photo": kind = language.string("media_filter_photos")
        case "video", "movie": kind = language.string("media_filter_videos")
        case "raw": kind = "RAW"
        default: kind = item.kind
        }
        var fields = [prefix + item.name, language.format("media_worklist_source_kind", kind)]
        if let folder = item.folder { fields.append(language.format("media_worklist_source_folder", folder.label)) }
        if let captured = mediaCaptureDate(item, displayZone: camera.mediaDateDisplayZone)
            .detailText(unknownTimeZone: language.string("media_time_zone_unknown")) {
            fields.append(language.format("media_worklist_source_captured", captured))
        }
        if let size = item.sizeBytes {
            fields.append(language.format("media_worklist_source_size", ByteCountFormatter.string(fromByteCount: size, countStyle: .file)))
        }
        return fields.joined(separator: " · ")
    }
}
