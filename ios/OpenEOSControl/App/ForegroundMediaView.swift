import Foundation
import SwiftUI

/// Opening this sheet is informational. Only its Start button admits a run.
struct ForegroundMediaView: View {
    @EnvironmentObject private var camera: CameraAppState

    var body: some View {
        ForegroundMediaContents(controller: camera.foregroundMedia)
    }
}

private struct ForegroundMediaContents: View {
    @EnvironmentObject private var camera: CameraAppState
    @EnvironmentObject private var language: AppLanguageStore
    @Environment(\.dismiss) private var dismiss
    @ObservedObject var controller: ForegroundMediaController
    @State private var exportPayload: MediaBatchExportPayload?
    @State private var shareUnavailable = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                List {
                    Section {
                        Text(language.string("foreground_media_help"))
                        Text(language.string("foreground_media_foreground_help"))
                            .font(.callout)
                            .foregroundStyle(Color.cameraSecondaryText)
                    }

                    Section {
                        Text(language.string(ForegroundMediaText.stateKey(controller.state)))
                            .font(.headline)
                            .accessibilityIdentifier("foreground-media-status")
                        Text(language.format("foreground_media_counts", controller.preparedCount, controller.pendingCount))
                            .font(.callout.monospacedDigit())
                            .accessibilityIdentifier("foreground-media-counts")
                        if let name = controller.currentName {
                            Text(verbatim: shortFilename(name))
                                .lineLimit(2)
                                .truncationMode(.middle)
                                .accessibilityIdentifier("foreground-media-current-name")
                        }
                        if let reason = controller.reason {
                            Text(language.string(ForegroundMediaText.reasonKey(reason)))
                                .font(.callout)
                                .accessibilityIdentifier("foreground-media-stop-reason")
                        }
                        if !controller.isRunning && !camera.canStartForegroundMedia {
                            Text(language.string("foreground_media_start_unavailable"))
                                .font(.caption)
                                .foregroundStyle(Color.cameraSecondaryText)
                                .accessibilityIdentifier("foreground-media-start-unavailable")
                        }
                    }

                    Section {
                        Button {
                            camera.activeSheet = .deliveryHistory
                        } label: {
                            Label(language.string("media_delivery_history_title"), systemImage: "clock.arrow.circlepath")
                                .fixedSize(horizontal: false, vertical: true)
                                .frame(minHeight: 44, alignment: .leading)
                        }
                        .accessibilityIdentifier("foreground-media-history-button")
                        Text(language.string("foreground_media_destination_help"))
                            .font(.caption)
                            .foregroundStyle(Color.cameraSecondaryText)
                    }

                    if camera.foregroundTemporaryCount > 0 {
                        Section {
                            Text(language.format("foreground_media_temporary_count", camera.foregroundTemporaryCount))
                                .accessibilityIdentifier("foreground-media-temporary-count")
                            Text(language.string("foreground_media_temporary_help"))
                                .font(.caption)
                                .foregroundStyle(Color.cameraSecondaryText)
                            Button {
                                let payload = camera.makeForegroundMediaExportPayload()
                                shareUnavailable = payload == nil
                                exportPayload = payload
                            } label: {
                                Label(language.string("foreground_media_share_temporary"), systemImage: "square.and.arrow.up")
                                    .fixedSize(horizontal: false, vertical: true)
                                    .frame(minHeight: 44, alignment: .leading)
                            }
                            .disabled(controller.isRunning)
                            .accessibilityIdentifier("foreground-media-share-temporary")
                        }
                    }

                    if shareUnavailable {
                        Section {
                            Text(language.string("foreground_media_share_unavailable"))
                                .accessibilityIdentifier("foreground-media-share-unavailable")
                        }
                    }

                    Section {
                        DisclosureGroup(language.string("foreground_media_limits_title")) {
                            Text(language.string("foreground_media_limits_files"))
                            Text(language.string("foreground_media_limits_history"))
                            Text(language.string("foreground_media_limits_stop"))
                        }
                        .font(.callout)
                        .accessibilityIdentifier("foreground-media-limits")
                    }
                }
                .listStyle(.insetGrouped)
                .scrollContentBackground(.hidden)

                Divider().overlay(Color.cameraBorder)
                runAction
                    .padding(.horizontal, 20)
                    .padding(.vertical, 8)
            }
            .foregroundStyle(Color.cameraText)
            .background(Color.cameraSurface)
            .navigationTitle(language.string("foreground_media_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(language.string("done")) { dismiss() }
                        .accessibilityIdentifier("foreground-media-done")
                }
            }
        }
        .accessibilityIdentifier("foreground-media")
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
        .sheet(item: $exportPayload) { payload in
            MediaBatchActivityView(payload: payload)
        }
    }

    @ViewBuilder private var runAction: some View {
        if controller.isRunning {
            Button {
                camera.stopForegroundMedia()
            } label: {
                Label(language.string(controller.state == .stopping ? "foreground_media_stopping" : "foreground_media_stop"), systemImage: "stop.fill")
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, minHeight: 48)
            }
            .buttonStyle(.borderedProminent)
            .tint(Color.cameraWarning)
            .foregroundStyle(Color.cameraBackground)
            .disabled(controller.state == .stopping)
            .accessibilityIdentifier("foreground-media-stop")
        } else {
            Button {
                shareUnavailable = false
                _ = camera.startForegroundMedia()
            } label: {
                Label(language.string("foreground_media_start"), systemImage: "play.fill")
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, minHeight: 48)
            }
            .buttonStyle(.borderedProminent)
            .tint(Color.cameraAccent)
            .disabled(!camera.canStartForegroundMedia)
            .accessibilityIdentifier("foreground-media-start")
        }
    }

    private func shortFilename(_ name: String) -> String {
        let filename = (name as NSString).lastPathComponent
        let printable = filename.components(separatedBy: .controlCharacters).joined()
        return String(printable.prefix(120))
    }
}

/// Observes the nested controller directly. No space is reserved while idle,
/// and shutter recovery always owns the safety footer when it is needed.
struct ForegroundMediaBanner: View {
    @EnvironmentObject private var camera: CameraAppState
    @EnvironmentObject private var language: AppLanguageStore
    @ObservedObject var controller: ForegroundMediaController

    var body: some View {
        if controller.isRunning && !camera.shutterReleaseRequired && !camera.previousShutterReleaseUnconfirmed {
            VStack(alignment: .leading, spacing: 4) {
                Text(language.string(controller.state == .stopping ? "foreground_media_stopping" : "foreground_media_banner_title"))
                    .font(.caption.weight(.semibold))
                    .accessibilityIdentifier("foreground-media-banner-status")
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 12) { bannerActions }
                    VStack(spacing: 4) { bannerActions }
                }
            }
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .frame(maxWidth: .infinity, alignment: .leading)
            .foregroundStyle(Color.cameraText)
            .background(Color.cameraSurface)
            .accessibilityIdentifier("foreground-media-banner")
        }
    }

    @ViewBuilder private var bannerActions: some View {
        Button {
            camera.activeSheet = .foregroundImport
        } label: {
            Text(language.string("foreground_media_details"))
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, minHeight: 44)
        }
        .buttonStyle(.bordered)
        .accessibilityIdentifier("foreground-media-banner-details")

        Button {
            camera.stopForegroundMedia()
        } label: {
            Label(language.string(controller.state == .stopping ? "foreground_media_stopping" : "foreground_media_stop"), systemImage: "stop.fill")
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, minHeight: 44)
        }
        .buttonStyle(.borderedProminent)
        .tint(Color.cameraWarning)
        .foregroundStyle(Color.cameraBackground)
        .disabled(controller.state == .stopping)
        .accessibilityIdentifier("foreground-media-banner-stop")
    }
}

private enum ForegroundMediaText {
    static func stateKey(_ state: ForegroundMediaController.State) -> String {
        switch state {
        case .idle: "foreground_media_state_idle"
        case .checking: "foreground_media_state_checking"
        case .baseline: "foreground_media_state_baseline"
        case .watching: "foreground_media_state_watching"
        case .waiting: "foreground_media_state_waiting"
        case .stabilizing: "foreground_media_state_stabilizing"
        case .preparing: "foreground_media_state_preparing"
        case .stopping: "foreground_media_stopping"
        case .stopped: "foreground_media_state_stopped"
        case .failed: "foreground_media_state_failed"
        }
    }

    static func reasonKey(_ reason: ForegroundMediaController.StopReason) -> String {
        switch reason {
        case .user: "foreground_media_reason_user"
        case .background: "foreground_media_reason_background"
        case .session: "foreground_media_reason_session"
        case .unsupported: "foreground_media_reason_unsupported"
        case .inventory: "foreground_media_reason_inventory"
        case .metadata: "foreground_media_reason_metadata"
        case .storage: "foreground_media_reason_storage"
        case .transfer: "foreground_media_reason_transfer"
        case .limit: "foreground_media_reason_limit"
        }
    }
}
