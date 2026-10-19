import Foundation
import OpenEOSCore
import SwiftUI

struct MediaMetadataBatchPanel: View {
    @EnvironmentObject private var camera: CameraAppState
    @EnvironmentObject private var language: AppLanguageStore
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var actionsExpanded = false
    @State private var ratingExpanded = false
    @State private var rotationExpanded = false
    let displayedItems: [CameraMediaItem]
    let showsControls: Bool
    @State private var draft: MediaMetadataBatchDraft?
    @State private var historyToClear: UUID?
    @State private var showingHistoryClearConfirmation = false

    var body: some View {
        // Keep the bounded command header addressable while history rows remain lazy.
        VStack(alignment: .leading, spacing: 8) {
            if showsControls {
                if dynamicTypeSize.isAccessibilitySize {
                    accessibleActions
                } else {
                    Menu(language.string("media_mutation_menu")) {
                        Menu(language.string("media_mutation_rating_menu")) {
                            ForEach(0...5, id: \.self) { action(.rating($0)) }
                        }.disabled(!camera.supports(.mediaRating))
                            .accessibilityIdentifier("media-mutation-rating-menu")
                        action(.protection(true)); action(.protection(false))
                        Menu(language.string("media_mutation_rotation_menu")) {
                            ForEach([0, 90, 180, 270], id: \.self) { action(.rotation($0)) }
                        }.disabled(!camera.supports(.mediaRotate))
                            .accessibilityIdentifier("media-mutation-rotation-menu")
                        action(.archive(true)); action(.archive(false))
                    }
                    .disabled(!camera.canPrepareMediaMetadataBatch)
                    .accessibilityIdentifier("media-mutation-menu")
                }
            }
            if let rejection = camera.mediaMetadataBatchRejection {
                Text(language.format("media_mutation_rejected_count", rejection.selectedCount))
                    .font(.headline)
                Text(language.string("media_mutation_rejection_" + rejection.reason.rawValue))
                    .font(.caption)
                    .accessibilityIdentifier("media-mutation-rejection")
            }
            if !camera.mediaMetadataBatches.isEmpty {
                Text(language.format("media_mutation_history_capacity", camera.mediaMetadataBatches.count,
                    MediaMetadataBatchHistory.maximumBatches, camera.mediaMetadataHistoryRowCount,
                    MediaMetadataBatchHistory.maximumRows)).font(.caption)
            }
            ForEach(camera.mediaMetadataBatches.reversed()) { batch in
                LazyVStack(alignment: .leading, spacing: 6) {
                    Text(batch.target.label(language)).font(.headline)
                    Text(language.format("media_batch_selection_count", batch.rows.count, batch.hiddenCount, batch.unloadedCount))
                        .font(.caption)
                    Text(language.string("media_mutation_result_notice")).font(.caption).foregroundStyle(Color.cameraSecondaryText)
                    if batch.ownerRetired {
                        Text(language.string("media_mutation_previous_session")).font(.caption)
                    }
                    if batch.running {
                        ProgressView()
                        Button(language.string(batch.cancellationRequested ? "media_batch_cancelling" : "media_mutation_cancel")) {
                            camera.cancelMediaMetadataBatch()
                        }.disabled(batch.cancellationRequested)
                            .accessibilityIdentifier("media-mutation-cancel")
                    }
                    if !batch.running {
                        Button(language.string("media_mutation_history_clear"), role: .destructive) {
                            historyToClear = batch.id
                            showingHistoryClearConfirmation = true
                        }.disabled(!camera.canClearMediaMetadataHistory)
                            .accessibilityIdentifier("media-mutation-history-clear-\(batch.id)")
                    }
                    ForEach(batch.rows) { row in
                        VStack(alignment: .leading, spacing: 2) {
                            Text(row.item.name).lineLimit(2)
                            Text(row.item.id).font(.caption2).textSelection(.enabled)
                            Text(language.string("media_mutation_status_" + row.status.rawValue)).font(.caption)
                            if let reason = row.reason {
                                Text(language.string("media_mutation_reason_" + reason.rawValue))
                                    .font(.caption).foregroundStyle(Color.cameraSecondaryText)
                            }
                        }
                        .accessibilityElement(children: .combine)
                        .accessibilityIdentifier("media-mutation-result-\(row.item.id)")
                    }
                }
            }
        }
        .alert(language.string("media_mutation_history_clear_title"),
               isPresented: $showingHistoryClearConfirmation, presenting: historyToClear) { id in
            Button(language.string("cancel"), role: .cancel) { historyToClear = nil }
                .accessibilityIdentifier("media-mutation-history-clear-cancel")
            Button(language.string("media_mutation_history_clear_confirm"), role: .destructive) {
                _ = camera.clearMediaMetadataBatchHistory(id, confirmed: true)
                historyToClear = nil
            }.accessibilityIdentifier("media-mutation-history-clear-confirm")
        } message: { _ in
            Text(language.string("media_mutation_history_clear_warning"))
        }
        .sheet(item: $draft) { value in
            NavigationStack {
                List {
                    Section {
                        Text(value.target.label(language)).font(.headline)
                        Text(language.format("media_batch_selection_count", value.rows.count, value.hiddenCount, value.unloadedCount))
                        Text(language.string("media_mutation_confirmation"))
                        if value.rows.count > 1_000 { Text(language.string("media_mutation_reason_limit")) }
                    }
                    ForEach(value.rows) { row in
                        VStack(alignment: .leading) {
                            Text(row.item.name)
                            Text(row.item.id).font(.caption).textSelection(.enabled)
                            if !row.loadedAtConfirmation { Text(language.string("media_mutation_reason_unloaded")).font(.caption) }
                        }
                    }
                }
                .navigationTitle(language.string("media_mutation_review"))
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button(language.string("cancel")) { draft = nil }
                            .accessibilityIdentifier("media-mutation-review-cancel")
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button(language.string("media_mutation_apply")) {
                            _ = camera.startMediaMetadataBatch(value)
                            draft = nil
                        }
                        .accessibilityIdentifier("media-mutation-confirm")
                    }
                }
            }
        }
    }

    // Large text uses the surrounding media ScrollView rather than a deep
    // transient native menu. All choices still create the same owned draft.
    private var accessibleActions: some View {
        VStack(alignment: .leading, spacing: 12) {
            expansionButton("media_mutation_menu", id: "media-mutation-menu", expanded: $actionsExpanded)
            if actionsExpanded {
                expansionButton("media_mutation_rating_menu", id: "media-mutation-rating-menu", expanded: $ratingExpanded)
                    .disabled(!camera.supports(.mediaRating))
                if ratingExpanded {
                    ForEach(0...5, id: \.self) { accessibleAction(.rating($0)) }
                }
                accessibleAction(.protection(true)); accessibleAction(.protection(false))
                expansionButton("media_mutation_rotation_menu", id: "media-mutation-rotation-menu", expanded: $rotationExpanded)
                    .disabled(!camera.supports(.mediaRotate))
                if rotationExpanded {
                    ForEach([0, 90, 180, 270], id: \.self) { accessibleAction(.rotation($0)) }
                }
                accessibleAction(.archive(true)); accessibleAction(.archive(false))
            }
        }
        .disabled(!camera.canPrepareMediaMetadataBatch)
    }

    // Give the actual toggle button its ID, rather than an AX container that
    // might propagate an identifier to its descendants.
    private func expansionButton(_ title: String, id: String, expanded: Binding<Bool>) -> some View {
        Button { expanded.wrappedValue.toggle() } label: {
            HStack {
                Text(language.string(title)).fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 8)
                Image(systemName: expanded.wrappedValue ? "chevron.up" : "chevron.down")
                    .accessibilityHidden(true)
            }
            .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityValue(language.string(expanded.wrappedValue ? "media_mutation_expanded" : "media_mutation_collapsed"))
        .accessibilityIdentifier(id)
    }

    private func accessibleAction(_ target: CameraMediaMutation) -> some View {
        action(target)
            .buttonStyle(.plain)
            .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
            .fixedSize(horizontal: false, vertical: true)
    }

    private func action(_ target: CameraMediaMutation) -> some View {
        Button(target.label(language)) {
            draft = camera.makeMediaMetadataBatchDraft(target, displayed: displayedItems)
            if draft != nil {
                actionsExpanded = false
                ratingExpanded = false
                rotationExpanded = false
            }
        }.disabled(!camera.supports(target.feature))
            .accessibilityIdentifier("media-mutation-action-" + target.actionID)
    }
}
