import Foundation
import OpenEOSCore
import SwiftUI
import UIKit

struct MediaView: View {
    @EnvironmentObject private var camera: CameraAppState
    @EnvironmentObject private var language: AppLanguageStore
    let controlRotation: Double
    @State private var pendingDeletion: CameraMediaItem?

    var body: some View {
        VStack(spacing: 0) {
            header
            Divider().overlay(Color.cameraBorder)
            if camera.isBusy(.media), camera.mediaItems.isEmpty {
                Spacer()
                ProgressView().tint(Color.cameraAccent)
                Spacer()
            } else if camera.mediaItems.isEmpty {
                ContentUnavailableView("no_media", systemImage: "photo.on.rectangle.angled")
                    .frame(maxHeight: .infinity)
            } else {
                ScrollView {
                    LazyVStack(spacing: 0) {
                        ForEach(camera.mediaItems) { item in
                            mediaRow(item)
                                .task(id: item.id) { await camera.loadMediaThumbnail(item) }
                            Divider().overlay(Color.cameraBorder)
                        }
                    }
                    .padding(.horizontal, 16)
                }
            }
        }
        .safeAreaPadding(.top, 2)
        .background(Color.cameraBackground)
        .task {
            if camera.mediaItems.isEmpty { await camera.loadMedia() }
        }
        .onDisappear { camera.closeMediaPreview() }
        .fullScreenCover(
            isPresented: Binding(
                get: { camera.mediaPreviewItem != nil },
                set: { if !$0 { camera.closeMediaPreview() } }
            )
        ) {
            MediaPreviewView(controlRotation: controlRotation)
                .environmentObject(camera)
                .environmentObject(language)
        }
        .alert(
            language.string("delete_media_title"),
            isPresented: Binding(
                get: { pendingDeletion != nil },
                set: { if !$0 { pendingDeletion = nil } }
            ),
            presenting: pendingDeletion
        ) { item in
            Button(language.string("cancel"), role: .cancel) {
                pendingDeletion = nil
            }
            Button(language.string("delete"), role: .destructive) {
                pendingDeletion = nil
                Task { await camera.deleteMedia(item) }
            }
        } message: { item in
            Text(language.format("delete_media_confirmation", item.name))
        }
    }

    private var header: some View {
        HStack(spacing: 8) {
            Button {
                camera.screen = .control
            } label: {
                RotatingControl(degrees: controlRotation) {
                    Image(systemName: "chevron.left").accessibilityLabel(Text("back_to_camera"))
                }
            }
            .buttonStyle(CameraIconButtonStyle())
            .accessibilityIdentifier("media-back-button")
            RotatingControl(degrees: controlRotation) {
                VStack(alignment: .leading, spacing: 1) {
                    Text("camera_media").font(.headline)
                    Text(language.format("media_count_format", camera.mediaItems.count))
                        .font(.caption)
                        .foregroundStyle(Color.cameraSecondaryText)
                    if let name = camera.deletedMediaName {
                        Text(language.format("media_deleted", name))
                            .font(.caption)
                            .foregroundStyle(Color.cameraStatus)
                            .lineLimit(1)
                    }
                }
            }
            Spacer()
            Button {
                Task { await camera.loadMedia() }
            } label: {
                RotatingControl(degrees: controlRotation) {
                    Image(systemName: "arrow.clockwise").accessibilityLabel(Text("refresh_media"))
                }
            }
            .buttonStyle(CameraIconButtonStyle())
            .disabled(camera.isBusy(.media))
        }
        .foregroundStyle(Color.cameraText)
        .padding(.horizontal, 10)
        .frame(minHeight: 56)
    }

    private func mediaRow(_ item: CameraMediaItem) -> some View {
        HStack(spacing: 12) {
            mediaThumbnail(item)
            VStack(alignment: .leading, spacing: 4) {
                Text(item.name)
                    .font(.callout.weight(.semibold))
                    .foregroundStyle(Color.cameraText)
                    .lineLimit(2)
                HStack(spacing: 6) {
                    Text(item.kind.uppercased())
                    if let size = item.sizeBytes {
                        Text(ByteCountFormatter.string(fromByteCount: size, countStyle: .file))
                    }
                }
                .font(.caption)
                .foregroundStyle(Color.cameraSecondaryText)
            }
            Spacer(minLength: 4)
            mediaActions(item)
        }
        .frame(minHeight: 76)
    }

    @ViewBuilder
    private func mediaThumbnail(_ item: CameraMediaItem) -> some View {
        if item.previewAvailable, camera.supports(.mediaPreview), !camera.isPreview {
            Button {
                Task { await camera.openMediaPreview(item) }
            } label: {
                mediaThumbnailContent(item)
            }
            .buttonStyle(.plain)
            .disabled(camera.isBusy(.media))
            .accessibilityLabel(Text(language.format("preview_media", item.name)))
            .accessibilityIdentifier("preview-media-\(item.id)")
        } else {
            mediaThumbnailContent(item)
        }
    }

    private func mediaThumbnailContent(_ item: CameraMediaItem) -> some View {
        ZStack {
            Color.cameraSurfaceRaised
            if let data = camera.mediaThumbnails[item.id], let image = UIImage(data: data) {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
                    .accessibilityLabel(Text(language.format("media_thumbnail", item.name)))
            } else if camera.loadingMediaThumbnailIDs.contains(item.id) {
                ProgressView().tint(Color.cameraAccent).controlSize(.small)
            } else {
                Image(systemName: mediaIcon(item.kind))
                    .font(.title3)
                    .foregroundStyle(Color.cameraAccent)
            }
        }
        .frame(width: 56, height: 56)
        .clipShape(RoundedRectangle(cornerRadius: 4))
    }

    @ViewBuilder
    private func mediaActions(_ item: CameraMediaItem) -> some View {
        HStack(spacing: 2) {
            if camera.supports(.mediaDownload) {
                downloadAction(item)
            }
            if camera.supports(.mediaDelete) {
                Button {
                    pendingDeletion = item
                } label: {
                    Image(systemName: "trash")
                        .frame(width: 48, height: 48)
                        .accessibilityLabel(Text("delete_media"))
                }
                .foregroundStyle(Color.cameraRecording)
                .disabled(camera.isBusy(.media))
                .accessibilityIdentifier("delete-media-\(item.id)")
            }
        }
    }

    @ViewBuilder
    private func downloadAction(_ item: CameraMediaItem) -> some View {
        Group {
            if camera.downloadedFileName == item.name, let url = camera.downloadedFileURL {
                ShareLink(item: url) {
                    Image(systemName: "square.and.arrow.up")
                        .frame(width: 48, height: 48)
                        .accessibilityLabel(Text("save_media"))
                }
                .foregroundStyle(Color.cameraStatus)
            } else if camera.downloadedFileName == item.name, camera.isPreview {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundStyle(Color.cameraStatus)
                    .frame(width: 48, height: 48)
                    .accessibilityLabel(Text("download_complete"))
            } else {
                Button {
                    Task { await camera.downloadMedia(item) }
                } label: {
                    if camera.isBusy(.media) {
                        ProgressView().tint(Color.cameraAccent).frame(width: 48, height: 48)
                    } else {
                        Image(systemName: "arrow.down.circle")
                            .frame(width: 48, height: 48)
                            .accessibilityLabel(Text("download_media"))
                    }
                }
                .foregroundStyle(Color.cameraAccent)
                .disabled(camera.isBusy(.media))
            }
        }
    }

    private func mediaIcon(_ kind: String) -> String {
        switch kind.lowercased() {
        case "video": "film"
        case "raw": "camera.aperture"
        default: "photo"
        }
    }
}

private struct MediaPreviewView: View {
    @EnvironmentObject private var camera: CameraAppState
    @EnvironmentObject private var language: AppLanguageStore
    let controlRotation: Double

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if let data = camera.mediaPreviewData, let image = UIImage(data: data) {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .padding(.vertical, 64)
                    .accessibilityLabel(Text(language.format("media_preview_content", camera.mediaPreviewItem?.name ?? "")))
            } else if camera.mediaPreviewLoading {
                ProgressView().tint(Color.cameraAccent).controlSize(.large)
            } else {
                Text("media_preview_unavailable")
                    .foregroundStyle(Color.cameraSecondaryText)
                    .padding(24)
            }

            VStack(spacing: 0) {
                HStack(spacing: 8) {
                    Button { camera.closeMediaPreview() } label: {
                        RotatingControl(degrees: controlRotation) {
                            Image(systemName: "xmark")
                                .frame(width: 48, height: 48)
                                .accessibilityLabel(Text("close_media_preview"))
                        }
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("close-media-preview")
                    Text(camera.mediaPreviewItem?.name ?? "")
                        .font(.callout.weight(.semibold))
                        .lineLimit(1)
                        .truncationMode(.middle)
                    Spacer(minLength: 8)
                    Text(camera.mediaPreviewItem?.kind.uppercased() ?? "")
                        .font(.caption)
                        .foregroundStyle(Color.cameraSecondaryText)
                }
                .foregroundStyle(Color.white)
                .padding(.horizontal, 8)
                .frame(minHeight: 56)
                Spacer()
            }
        }
    }
}
