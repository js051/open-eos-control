import OpenEOSCore
import SwiftUI

struct MediaDeleteReview: View {
    @EnvironmentObject private var camera: CameraAppState
    @EnvironmentObject private var language: AppLanguageStore
    @Environment(\.dismiss) private var dismiss
    let draft: MediaDeleteDraft

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text(language.string("media_delete_warning"))
                    Text(draft.ownerLabel)
                    Text(language.format("media_batch_selection_count", draft.rows.count, draft.hiddenCount, draft.unloadedCount))
                    Text(language.string("media_delete_cancel_notice")).font(.caption)
                    if draft.rows.count > MediaDeleteHistory.maximumBatchRows {
                        Text(language.string("media_delete_reason_limit"))
                    }
                }
                ForEach(draft.rows) { row in
                    VStack(alignment: .leading, spacing: 4) {
                        Text(row.item.name)
                        Text(row.item.id).font(.caption).textSelection(.enabled)
                        Text(language.string("media_delete_source_" + row.source.rawValue)).font(.subheadline)
                        if let folder = row.item.folder { Text(folder.label).font(.caption) }
                        if let reason = row.skipReason {
                            Text(language.string("media_delete_reason_" + reason.rawValue)).font(.caption)
                        } else if row.qualificationUnknown {
                            Text(language.string("media_delete_qualification_unknown")).font(.caption)
                        }
                    }.accessibilityIdentifier("media-delete-review-row-\(row.item.id)")
                }
            }
            .navigationTitle(language.string("media_delete_review"))
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(language.string("cancel")) { dismiss() }
                        .accessibilityIdentifier("media-delete-review-cancel")
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(language.string("media_delete_confirm"), role: .destructive) {
                        _ = camera.startMediaDelete(draft, confirmed: true)
                        dismiss()
                    }.disabled(draft.owner != camera.mediaLibrarySessionID || !camera.canPrepareMediaDelete || draft.rows.count > MediaDeleteHistory.maximumBatchRows)
                        .accessibilityIdentifier("media-delete-confirm")
                }
            }
            .onChange(of: camera.mediaLibrarySessionID) { _, _ in dismiss() }
        }
    }
}

struct MediaDeletePanel: View {
    @EnvironmentObject private var camera: CameraAppState
    @EnvironmentObject private var language: AppLanguageStore
    let displayedItems: [CameraMediaItem]
    let showsControls: Bool
    @State private var draft: MediaDeleteDraft?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if showsControls && camera.supports(.mediaDelete) {
                Button(language.string("media_delete_selected"), role: .destructive) {
                    draft = camera.makeMediaDeleteDraft(displayed: displayedItems, owner: camera.mediaLibrarySessionID)
                }.disabled(!camera.canPrepareMediaDelete || camera.selectedMediaItems.isEmpty)
                    .accessibilityIdentifier("media-delete-selected")
            }
            if let reason = camera.mediaDeleteRejection {
                Text(language.string("media_delete_reason_" + reason.rawValue)).font(.caption)
                    .accessibilityIdentifier("media-delete-rejection")
            }
            if let run = camera.mediaDeleteRuns.last {
                Text(language.string("media_delete_history")).font(.headline)
                Text(language.format("media_delete_summary", run.rows.filter { $0.status == .confirmed }.count,
                    run.rows.filter { $0.status == .rejected }.count, run.rows.filter { $0.status == .uncertain }.count))
                    .font(.caption).accessibilityIdentifier("media-delete-summary")
                if run.running {
                    ProgressView()
                    Button(language.string("media_delete_cancel_remaining")) { camera.cancelMediaDelete() }
                        .disabled(run.cancellationRequested).accessibilityIdentifier("media-delete-cancel")
                }
                Button(language.string("media_delete_view_results")) { camera.activeSheet = .deleteHistory }
                    .accessibilityIdentifier("media-delete-view-results")
            }
        }
        .sheet(item: $draft) { MediaDeleteReview(draft: $0) }
        .onChange(of: camera.mediaLibrarySessionID) { _, _ in draft = nil }
    }
}

struct MediaDeleteHistoryView: View {
    @EnvironmentObject private var camera: CameraAppState
    @EnvironmentObject private var language: AppLanguageStore
    @Environment(\.dismiss) private var dismiss
    @State private var clearing: UUID?

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text(language.string("media_delete_recovery_notice"))
                    Text(language.string("media_delete_history_lifetime")).font(.caption)
                }
                ForEach(camera.mediaDeleteRuns.reversed()) { run in
                    Section {
                        Text(run.ownerLabel).font(.headline)
                        Text(run.owner.uuidString).font(.caption2).textSelection(.enabled)
                        Text(language.format("media_batch_selection_count", run.rows.count, run.hiddenCount, run.unloadedCount)).font(.caption)
                        if run.ownerRetired { Text(language.string("media_delete_previous_owner")).font(.caption) }
                        if run.running {
                            Button(language.string("media_delete_cancel_remaining")) { camera.cancelMediaDelete() }
                                .disabled(run.cancellationRequested)
                        }
                        Button(language.string("media_delete_inspect")) {
                            camera.inspectMediaDelete(run.id)
                            dismiss()
                        }.disabled(!camera.canInspectMediaDelete(run))
                            .accessibilityIdentifier("media-delete-inspect-\(run.id)")
                        if run.inspected { Text(language.string("media_delete_inspected_notice")).font(.caption) }
                        ForEach(run.rows) { row in
                            VStack(alignment: .leading, spacing: 3) {
                                Text(row.item.name)
                                Text(row.item.id).font(.caption2).textSelection(.enabled)
                                Text(language.string("media_delete_source_" + row.source.rawValue)).font(.caption)
                                if let folder = row.item.folder { Text(folder.label).font(.caption2) }
                                Text(language.string(row.applying ? "media_delete_applying" : "media_delete_status_" + row.status.rawValue))
                                    .font(.caption)
                                if let reason = row.reason { Text(language.string("media_delete_reason_" + reason.rawValue)).font(.caption) }
                            }.accessibilityIdentifier("media-delete-result-\(row.item.id)")
                        }
                        Button(language.string("media_delete_clear"), role: .destructive) { clearing = run.id }
                            .disabled(!camera.canClearMediaDeleteHistory)
                    }
                }
            }
            .navigationTitle(language.string("media_delete_history"))
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button(language.string("dismiss")) { dismiss() } } }
            .alert(language.string("media_delete_clear"), isPresented: Binding(get: { clearing != nil }, set: { if !$0 { clearing = nil } })) {
                Button(language.string("cancel"), role: .cancel) { clearing = nil }
                Button(language.string("media_delete_clear"), role: .destructive) {
                    if let id = clearing { _ = camera.clearMediaDeleteHistory(id, confirmed: true) }
                    clearing = nil
                }
            } message: { Text(language.string("media_delete_clear_warning")) }
        }
    }
}
