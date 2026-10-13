import Foundation
import OpenEOSCore
import SwiftUI
import UIKit

struct MediaBatchPanel: View {
    @EnvironmentObject private var camera: CameraAppState
    @EnvironmentObject private var language: AppLanguageStore
    let displayedItems: [CameraMediaItem]
    var showsSelectionControls = true
    @State private var exportPayload: MediaBatchExportPayload?
    @StateObject private var filesExporter = MediaFilesExportController()

    private var hiddenCount: Int {
        let visible = Set(displayedItems.map { Data($0.id.utf8) })
        let loaded = Set(camera.mediaItems.map { Data($0.id.utf8) })
        return camera.selectedMediaItems.filter {
            let id = Data($0.id.utf8)
            return loaded.contains(id) && !visible.contains(id)
        }.count
    }

    private var unloadedCount: Int {
        let loaded = Set(camera.mediaItems.map { Data($0.id.utf8) })
        return camera.selectedMediaItems.filter { !loaded.contains(Data($0.id.utf8)) }.count
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if showsSelectionControls {
                Text(language.format("media_batch_selection_count", camera.selectedMediaItems.count, hiddenCount, unloadedCount))
                    .font(.caption)
                    .accessibilityIdentifier("media-batch-selection-count")
                ViewThatFits(in: .horizontal) {
                    HStack { selectionActions }
                    VStack(alignment: .leading) { selectionActions }
                }
                if camera.isPreview {
                    Text(language.string("media_batch_offline_notice"))
                        .font(.caption).foregroundStyle(Color.cameraSecondaryText)
                }
            }
            if camera.mediaBatchState == .running {
                ProgressView().tint(Color.cameraAccent)
                if let progress = camera.mediaBatchProgress {
                    Text(language.format("media_batch_bytes", progress.bytesTransferred))
                        .font(.caption.monospacedDigit())
                }
                Button(language.string(camera.mediaBatchCancellationRequested ? "media_batch_cancelling" : "media_batch_cancel")) {
                    camera.cancelMediaBatchDownload()
                }
                .disabled(camera.mediaBatchCancellationRequested)
                .accessibilityIdentifier("media-batch-cancel")
            } else if showsSelectionControls {
                Button(language.string("media_batch_prepare")) {
                    _ = camera.startMediaBatchDownload()
                }
                .disabled(!camera.canStartMediaBatchDownload)
                .accessibilityIdentifier("media-batch-prepare")
            }
            if !camera.mediaBatchResults.isEmpty {
                Text(language.string("media_batch_result_notice"))
                    .font(.caption)
                    .foregroundStyle(Color.cameraSecondaryText)
                ForEach(camera.mediaBatchResults) { result in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(result.item.name).lineLimit(2)
                        Text(language.string("media_batch_status_" + result.status.rawValue))
                            .font(.caption)
                        if let error = result.errorMessage {
                            Text(error).font(.caption).foregroundStyle(Color.cameraSecondaryText)
                        }
                    }
                    .accessibilityElement(children: .combine)
                    .accessibilityIdentifier("media-batch-result-\(result.item.id)")
                }
                Button(language.string("media_batch_share_ready")) {
                    exportPayload = camera.makeMediaBatchExportPayload()
                }
                .disabled(camera.mediaBatchState == .running || !camera.mediaBatchResults.contains { $0.completedFileURL != nil })
                .accessibilityIdentifier("media-batch-share-ready")
                Button {
                    filesExporter.begin(camera.makeMediaBatchExportPayload())
                } label: {
                    Label(language.string("media_files_export_ready"), systemImage: "folder")
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(minHeight: 44, alignment: .leading)
                }
                .disabled(filesExporter.isActive || camera.mediaBatchState == .running || !camera.mediaBatchResults.contains { $0.completedFileURL != nil })
                .accessibilityIdentifier("media-batch-export-files")
            }
            if camera.mediaBatchResults.contains(where: { $0.completedFileURL != nil }) || filesExporter.result != nil {
                MediaFilesExportStatusView(controller: filesExporter)
            }
            if camera.mediaDeliveryHistoryWarning {
                Text(language.string("media_delivery_history_batch_warning"))
                    .font(.caption)
                    .foregroundStyle(Color.cameraSecondaryText)
                    .accessibilityIdentifier("media-batch-history-warning")
            }
            if camera.mediaBatchWorklistWarning {
                Text(language.string("media_worklist_recording_warning"))
                    .font(.caption)
                    .foregroundStyle(Color.cameraSecondaryText)
                    .accessibilityIdentifier("media-batch-worklist-warning")
            }
            if showsSelectionControls {
                Button {
                    camera.activeSheet = .deliveryHistory
                } label: {
                    Label(language.string("media_delivery_history_title"), systemImage: "clock.arrow.circlepath")
                        .frame(minHeight: 44, alignment: .leading)
                }
                .accessibilityIdentifier("media-batch-delivery-history-button")
            }
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .sheet(item: $exportPayload) { payload in
            MediaBatchActivityView(payload: payload)
        }
        .mediaFilesExport(filesExporter)
    }

    @ViewBuilder private var selectionActions: some View {
        Button(language.string("media_batch_select_visible")) {
            camera.selectMediaItems(displayedItems)
        }
        .disabled(displayedItems.isEmpty)
        .accessibilityIdentifier("media-batch-select-visible")
        Button(language.string("media_batch_clear_selection")) {
            camera.clearMediaSelection()
        }
        .disabled(camera.selectedMediaItems.isEmpty)
        .accessibilityIdentifier("media-batch-clear-selection")
    }
}

/// The coordinator retains the immutable file leases for the actual activity
/// controller lifetime, independently of a camera reset or a later batch.
/// A completed activity is not proof that an external provider saved the bytes.
struct MediaBatchActivityView: UIViewControllerRepresentable {
    let payload: MediaBatchExportPayload

    final class Coordinator {
        let payload: MediaBatchExportPayload
        init(_ payload: MediaBatchExportPayload) { self.payload = payload }
    }

    func makeCoordinator() -> Coordinator { Coordinator(payload) }

    func makeUIViewController(context: Context) -> UIActivityViewController {
        let controller = UIActivityViewController(activityItems: payload.fileURLs, applicationActivities: nil)
        let lease = context.coordinator
        controller.completionWithItemsHandler = { _, _, _, _ in
            // Keep leases alive through the system completion callback. Never
            // turn a provider's completion boolean into a saved-file receipt.
            withExtendedLifetime(lease) {}
        }
        return controller
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
