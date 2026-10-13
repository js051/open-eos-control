import Foundation
import SwiftUI

/// Local receipts describe preparation in the app, never saving by a destination.
/// Opening this screen and checking its copies do not contact a camera.
struct MediaDeliveryHistoryView: View {
    @EnvironmentObject private var camera: CameraAppState
    @EnvironmentObject private var language: AppLanguageStore
    @Environment(\.dismiss) private var dismiss
    @State private var exportPayload: MediaBatchExportPayload?
    @State private var preparingExport = false
    @State private var clearing = false
    @State private var showClearConfirmation = false
    @State private var shareUnavailable = false
    @State private var presentationID = UUID()
    @State private var isVisible = false
    @State private var selectedOrigins = Set<MediaDeliveryOrigin>()

    private var records: [MediaDeliveryRecord] {
        camera.mediaDeliverySnapshot.records.sorted {
            if $0.startedAt == $1.startedAt { return $0.id.uuidString < $1.id.uuidString }
            return $0.startedAt > $1.startedAt
        }
    }
    private var availableIDs: [UUID] {
        records.filter { $0.status == .prepared && $0.availability == .available }.map(\.id)
    }
    private var worklists: [MediaDeliveryPlannedBatchRecord] {
        camera.mediaDeliverySnapshot.plannedBatches.sorted {
            if $0.createdAt == $1.createdAt { return $0.id.uuidString < $1.id.uuidString }
            return $0.createdAt > $1.createdAt
        }
    }
    private var queuedCount: Int {
        worklists.reduce(0) { $0 + $1.rows.filter { $0.receipt == nil }.count }
    }
    private var legacyRecords: [MediaDeliveryRecord] {
        let plannedIDs = Set(worklists.flatMap { $0.rows.map(\.id) })
        return records.filter { !plannedIDs.contains($0.id) }
    }
    private var hasWarning: Bool {
        camera.mediaDeliveryHistoryWarning || camera.mediaDeliverySnapshot.warning != nil
    }
    private var working: Bool { preparingExport || clearing }
    private var actionsDisabled: Bool { working || camera.mediaDeliveryHistoryLoading }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text(language.string("media_delivery_history_help"))
                    Text(language.string("media_delivery_history_limits"))
                        .font(.caption)
                        .foregroundStyle(Color.cameraSecondaryText)
                    Text(language.format("media_delivery_history_counts", records.count, availableIDs.count))
                        .font(.callout)
                        .accessibilityIdentifier("media-delivery-history-counts")
                    Text(language.format("media_worklist_counts", worklists.count, queuedCount))
                        .font(.callout)
                        .accessibilityIdentifier("media-delivery-worklist-counts")
                    Text(language.string("media_worklist_help"))
                        .font(.caption)
                        .foregroundStyle(Color.cameraSecondaryText)
                    retainedSize
                }

                if camera.connected {
                    Section {
                        Text(language.string("media_worklist_continue_help"))
                            .font(.caption)
                        Button(language.format("media_worklist_continue_selected", selectedOrigins.count)) {
                            let origins = worklists.flatMap { batch in
                                batch.rows.compactMap { row -> MediaDeliveryOrigin? in
                                    let origin = MediaDeliveryOrigin(batchID: batch.id, rowID: row.id)
                                    return selectedOrigins.contains(origin) ? origin : nil
                                }
                            }
                            if camera.beginMediaWorklistContinuation(origins: origins) { selectedOrigins = [] }
                        }
                        .disabled(actionsDisabled || selectedOrigins.isEmpty || !camera.canBeginMediaWorklistContinuation)
                        .accessibilityIdentifier("media-worklist-continue-selected")
                    }
                    Section {
                        Button {
                            camera.activeSheet = .foregroundImport
                        } label: {
                            Label(language.string("foreground_media_title"), systemImage: "photo.badge.arrow.down")
                                .fixedSize(horizontal: false, vertical: true)
                                .frame(minHeight: 44, alignment: .leading)
                        }
                        .disabled(actionsDisabled)
                        .accessibilityIdentifier("media-delivery-history-foreground-button")
                    }
                }

                if hasWarning {
                    Section {
                        Label(language.string("media_delivery_history_warning"), systemImage: "exclamationmark.triangle")
                            .accessibilityIdentifier("media-delivery-history-warning")
                    }
                }
                if camera.mediaBatchWorklistWarning {
                    Section {
                        Text(language.string("media_worklist_recording_warning"))
                            .accessibilityIdentifier("media-delivery-worklist-warning")
                    }
                }

                if camera.mediaWorklistContinuationUnavailable {
                    Section {
                        Text(language.string("media_worklist_continue_unavailable"))
                            .accessibilityIdentifier("media-worklist-continue-unavailable")
                    }
                }
                if camera.mediaBatchState == .running || !camera.mediaBatchResults.isEmpty {
                    Section {
                        MediaBatchPanel(displayedItems: [], showsSelectionControls: false)
                    }
                }

                if shareUnavailable {
                    Section {
                        Text(language.string("media_delivery_history_share_unavailable"))
                            .accessibilityIdentifier("media-delivery-history-share-unavailable")
                    }
                }

                Section {
                    if camera.mediaDeliveryHistoryLoading {
                        ProgressView(language.string("media_delivery_history_loading"))
                            .tint(Color.cameraAccent)
                            .accessibilityIdentifier("media-delivery-history-loading")
                    }
                    if preparingExport {
                        ProgressView(language.string("media_delivery_history_checking_share"))
                            .tint(Color.cameraAccent)
                            .accessibilityIdentifier("media-delivery-history-checking-share")
                    }
                    Button {
                        share(ids: availableIDs)
                    } label: {
                        Label(language.string("media_delivery_history_share_all"), systemImage: "square.and.arrow.up")
                            .fixedSize(horizontal: false, vertical: true)
                            .frame(minHeight: 44, alignment: .leading)
                    }
                    .disabled(actionsDisabled || availableIDs.isEmpty)
                    .accessibilityIdentifier("media-delivery-history-share-all")

                    Button {
                        Task { await camera.refreshMediaDeliveryHistory() }
                    } label: {
                        Label(language.string("media_delivery_history_refresh"), systemImage: "arrow.clockwise")
                            .frame(minHeight: 44, alignment: .leading)
                    }
                    .disabled(actionsDisabled)
                    .accessibilityIdentifier("media-delivery-history-refresh")
                }

                ForEach(worklists) { worklist in
                    Section {
                        ForEach(Array(worklist.rows.enumerated()), id: \.element.id) { index, row in
                            VStack(alignment: .leading, spacing: 8) {
                                Text(language.format("media_worklist_position", index + 1, worklist.rows.count))
                                    .font(.caption.monospacedDigit())
                                    .foregroundStyle(Color.cameraSecondaryText)
                                if let origin = row.origin, let label = originLabel(origin) {
                                    Text(label)
                                        .font(.caption)
                                        .foregroundStyle(Color.cameraSecondaryText)
                                }
                                if row.canContinue && camera.connected {
                                    let origin = MediaDeliveryOrigin(batchID: worklist.id, rowID: row.id)
                                    Toggle(language.string("media_worklist_select_row"), isOn: Binding(
                                        get: { selectedOrigins.contains(origin) },
                                        set: { selected in
                                            if selected {
                                                selectedOrigins = Set(selectedOrigins.filter { $0.batchID == origin.batchID })
                                                selectedOrigins.insert(origin)
                                            } else { selectedOrigins.remove(origin) }
                                        }
                                    ))
                                    .disabled(actionsDisabled || !camera.canBeginMediaWorklistContinuation)
                                    .accessibilityIdentifier("media-worklist-select-\(row.id.uuidString)")
                                }
                                if let receipt = row.receipt {
                                    historyRow(receipt)
                                } else {
                                    Text(verbatim: row.filename)
                                        .font(.headline)
                                        .fixedSize(horizontal: false, vertical: true)
                                    Text(language.string("media_worklist_queued"))
                                        .accessibilityIdentifier("media-delivery-worklist-queued-\(row.id.uuidString)")
                                }
                            }
                            .accessibilityElement(children: .contain)
                            .accessibilityIdentifier("media-delivery-worklist-row-\(row.id.uuidString)")
                        }
                    } header: {
                        Text(language.format("media_worklist_created", dateLabel(worklist.createdAt)))
                    }
                }

                if records.isEmpty && worklists.isEmpty && !camera.mediaDeliveryHistoryLoading && !hasWarning {
                    Section {
                        Text(language.string("media_delivery_history_empty"))
                            .foregroundStyle(Color.cameraSecondaryText)
                            .accessibilityIdentifier("media-delivery-history-empty")
                    }
                } else if !legacyRecords.isEmpty {
                    Section {
                        ForEach(legacyRecords) { record in
                            historyRow(record)
                        }
                    } header: {
                        Text(language.string("media_worklist_legacy_attempts"))
                    }
                }

                Section {
                    if clearing {
                        ProgressView(language.string("media_delivery_history_clearing"))
                            .accessibilityIdentifier("media-delivery-history-clearing")
                    }
                    Button(role: .destructive) {
                        showClearConfirmation = true
                    } label: {
                        Label(language.string("media_delivery_history_clear"), systemImage: "trash")
                            .fixedSize(horizontal: false, vertical: true)
                            .frame(minHeight: 44, alignment: .leading)
                    }
                    .disabled(actionsDisabled || (records.isEmpty && worklists.isEmpty && camera.mediaDeliverySnapshot.retainedBytes == 0 && !hasWarning))
                    .accessibilityIdentifier("media-delivery-history-clear")
                }
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .foregroundStyle(Color.cameraText)
            .background(Color.cameraSurface)
            .navigationTitle(language.string("media_delivery_history_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(language.string("done")) { dismiss() }
                        .disabled(working)
                        .accessibilityIdentifier("media-delivery-history-done")
                }
            }
            .confirmationDialog(
                language.string("media_delivery_history_clear_title"),
                isPresented: $showClearConfirmation,
                titleVisibility: .visible
            ) {
                Button(language.string("media_delivery_history_clear_confirm"), role: .destructive) {
                    let owner = presentationID
                    clearing = true
                    shareUnavailable = false
                    Task {
                        await camera.clearMediaDeliveryHistory()
                        guard isVisible, presentationID == owner else { return }
                        clearing = false
                    }
                }
                .disabled(actionsDisabled)
                .accessibilityIdentifier("media-delivery-history-clear-confirm")
                Button(language.string("cancel"), role: .cancel) {}
                    .accessibilityIdentifier("media-delivery-history-clear-cancel")
            } message: {
                Text(language.string("media_delivery_history_clear_message"))
            }
        }
        .accessibilityIdentifier("media-delivery-history")
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
        .interactiveDismissDisabled(working)
        .onAppear {
            presentationID = UUID()
            isVisible = true
        }
        .onDisappear {
            isVisible = false
            presentationID = UUID()
            preparingExport = false
            clearing = false
            selectedOrigins = []
        }
        .onChange(of: camera.mediaLibrarySessionID) { _ in selectedOrigins = [] }
        .onChange(of: camera.mediaDeliverySnapshot.epoch) { _ in selectedOrigins = [] }
        .task { await camera.refreshMediaDeliveryHistory() }
        .sheet(isPresented: Binding(
            get: { camera.mediaWorklistContinuation != nil },
            set: { if !$0 { camera.cancelMediaWorklistContinuation() } }
        )) {
            MediaWorklistContinuationView()
        }
        .sheet(item: $exportPayload, onDismiss: {
            Task { await camera.refreshMediaDeliveryHistory() }
        }) { payload in
            MediaBatchActivityView(payload: payload)
        }
    }

    @ViewBuilder private var retainedSize: some View {
        if let bytes = camera.mediaDeliverySnapshot.retainedBytes {
            Text(language.format("media_delivery_history_retained_bytes", bytes))
                .font(.caption.monospacedDigit())
                .accessibilityIdentifier("media-delivery-history-retained-bytes")
        } else {
            Text(language.string("media_delivery_history_retained_unknown"))
                .font(.caption)
                .accessibilityIdentifier("media-delivery-history-retained-unknown")
        }
    }

    private func historyRow(_ record: MediaDeliveryRecord) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(verbatim: record.filename)
                .font(.headline)
                .fixedSize(horizontal: false, vertical: true)
            Text(language.string(statusKey(record)))
                .accessibilityIdentifier("media-delivery-history-status-\(record.id.uuidString)")
            Text(language.string(availabilityKey(record)))
                .font(.callout.weight(.semibold))
                .accessibilityIdentifier("media-delivery-history-availability-\(record.id.uuidString)")
            if let reasonKey = reasonKey(record) {
                Text(language.string(reasonKey))
                    .font(.caption)
                    .foregroundStyle(Color.cameraSecondaryText)
            }
            Text(language.format("media_delivery_history_started", dateLabel(record.startedAt)))
                .font(.caption)
                .foregroundStyle(Color.cameraSecondaryText)
            if let completedAt = record.completedAt {
                Text(language.format("media_delivery_history_finished", dateLabel(completedAt)))
                    .font(.caption)
                    .foregroundStyle(Color.cameraSecondaryText)
            }
            if let bytes = record.actualBytes {
                Text(language.format("media_delivery_history_original_bytes", bytes))
                    .font(.caption.monospacedDigit())
                    .foregroundStyle(Color.cameraSecondaryText)
            }
            if record.status == .prepared && record.availability == .available {
                Button {
                    share(ids: [record.id])
                } label: {
                    Label(language.string("media_delivery_history_share_one"), systemImage: "square.and.arrow.up")
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(minHeight: 44, alignment: .leading)
                }
                .buttonStyle(.bordered)
                .disabled(actionsDisabled)
                .accessibilityLabel(language.format("media_delivery_history_share_named", record.filename))
                .accessibilityIdentifier("media-delivery-history-share-\(record.id.uuidString)")
            }
        }
        .padding(.vertical, 6)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("media-delivery-history-record-\(record.id.uuidString)")
    }

    private func share(ids: [UUID]) {
        guard isVisible, !actionsDisabled, !ids.isEmpty else { return }
        let owner = presentationID
        preparingExport = true
        shareUnavailable = false
        Task {
            let payload = await camera.makeStoredMediaExportPayload(ids: ids)
            guard isVisible, presentationID == owner else { return }
            preparingExport = false
            if let payload {
                exportPayload = payload
            } else {
                shareUnavailable = true
            }
        }
    }

    private func originLabel(_ origin: MediaDeliveryOrigin) -> String? {
        guard let batch = worklists.first(where: { $0.id == origin.batchID }),
              let index = batch.rows.firstIndex(where: { $0.id == origin.rowID }) else { return nil }
        return language.format("media_worklist_origin", dateLabel(batch.createdAt), index + 1, batch.rows[index].filename)
    }

    private func dateLabel(_ date: Date) -> String {
        date.formatted(Date.FormatStyle(date: .abbreviated, time: .shortened).locale(language.locale))
    }

    private func statusKey(_ record: MediaDeliveryRecord) -> String {
        switch record.status {
        case .started: "media_delivery_history_status_started"
        case .prepared: "media_delivery_history_status_prepared"
        case .failed: "media_delivery_history_status_failed"
        case .cancelled: "media_delivery_history_status_cancelled"
        case .unknown: "media_delivery_history_status_unknown"
        }
    }

    private func availabilityKey(_ record: MediaDeliveryRecord) -> String {
        switch record.availability {
        case .available: "media_delivery_history_availability_available"
        case .missing: "media_delivery_history_availability_missing"
        case .changed: "media_delivery_history_availability_changed"
        case .unsafe: "media_delivery_history_availability_unsafe"
        case .notRetained: "media_delivery_history_availability_notRetained"
        }
    }

    private func reasonKey(_ record: MediaDeliveryRecord) -> String? {
        switch record.reason {
        case .interrupted: "media_delivery_history_reason_interrupted"
        case .downloadFailed: "media_delivery_history_reason_downloadFailed"
        case .cancelled: "media_delivery_history_reason_cancelled"
        case .retentionUnavailable: "media_delivery_history_reason_retentionUnavailable"
        case nil: nil
        }
    }
}
