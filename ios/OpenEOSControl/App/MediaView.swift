import Foundation
import OpenEOSCore
import SwiftUI

struct MediaView: View {
    @EnvironmentObject private var camera: CameraAppState
    @EnvironmentObject private var language: AppLanguageStore
    let controlRotation: Double

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
        .accessibilityIdentifier("media-view")
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
            RotatingControl(degrees: controlRotation) {
                VStack(alignment: .leading, spacing: 1) {
                    Text("camera_media").font(.headline)
                    Text(language.format("media_count_format", camera.mediaItems.count))
                        .font(.caption)
                        .foregroundStyle(Color.cameraSecondaryText)
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
            Image(systemName: mediaIcon(item.kind))
                .font(.title3)
                .foregroundStyle(Color.cameraAccent)
                .frame(width: 40, height: 48)
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
        .frame(minHeight: 76)
    }

    private func mediaIcon(_ kind: String) -> String {
        switch kind.lowercased() {
        case "video": "film"
        case "raw": "camera.aperture"
        default: "photo"
        }
    }
}
