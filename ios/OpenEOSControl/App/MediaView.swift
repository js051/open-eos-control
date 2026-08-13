import Foundation
import AVKit
import OpenEOSCore
import SwiftUI
import UIKit
import UniformTypeIdentifiers

struct MediaView: View {
    @EnvironmentObject private var camera: CameraAppState
    @EnvironmentObject private var language: AppLanguageStore
    let controlRotation: Double
    @State private var pendingDeletion: CameraMediaItem?
    @State private var metadataItemID: String?
    @State private var isFileImporterPresented = false
    @State private var mediaFilter = MediaFilter.all
    @State private var mediaSort = MediaSort.newest

    private let mediaColumns = [
        GridItem(.adaptive(minimum: 156, maximum: 240), spacing: 10),
    ]

    var body: some View {
        VStack(spacing: 0) {
            header
            Divider().overlay(Color.cameraBorder)
            if camera.activeMediaUploadName != nil || camera.mediaUploadError != nil || camera.uploadedMediaName != nil {
                uploadStatus
            }
            if camera.isBusy(.media), camera.mediaItems.isEmpty {
                Spacer()
                ProgressView().tint(Color.cameraAccent)
                Spacer()
            } else if camera.mediaItems.isEmpty {
                ContentUnavailableView("no_media", systemImage: "photo.on.rectangle.angled")
                    .frame(maxHeight: .infinity)
            } else {
                mediaToolbar
                if displayedMedia.isEmpty {
                    ContentUnavailableView("no_media", systemImage: "line.3.horizontal.decrease.circle")
                        .frame(maxHeight: .infinity)
                } else {
                    ScrollView {
                        LazyVStack(alignment: .leading, spacing: 12) {
                            ForEach(mediaGroups) { group in
                                Text(group.title)
                                    .font(.caption.weight(.semibold))
                                    .foregroundStyle(Color.cameraSecondaryText)
                                    .padding(.top, 4)
                                LazyVGrid(columns: mediaColumns, alignment: .leading, spacing: 10) {
                                    ForEach(group.items) { item in
                                        mediaCard(item)
                                            .task(id: item.id) { await camera.loadMediaThumbnail(item) }
                                    }
                                }
                            }
                        }
                        .padding(.horizontal, 12)
                        .padding(.bottom, 20)
                    }
                }
            }
        }
        .safeAreaPadding(.top, 2)
        .background(Color.cameraBackground)
        .task {
            if camera.mediaItems.isEmpty { await camera.loadMedia() }
        }
        .fileImporter(
            isPresented: $isFileImporterPresented,
            allowedContentTypes: [.data],
            allowsMultipleSelection: false
        ) { result in
            guard case let .success(urls) = result,
                  let url = urls.first,
                  url.startAccessingSecurityScopedResource()
            else { return }
            if !camera.startMediaUpload(url, securityScoped: true) {
                url.stopAccessingSecurityScopedResource()
            }
        }
        .onDisappear { camera.closeMediaPreview() }
        .fullScreenCover(
            isPresented: Binding(
                get: { camera.mediaPreviewItem != nil },
                set: { if !$0 { camera.closeMediaPreview() } }
            )
        ) {
            MediaPreviewView(
                items: displayedMedia.filter(canPreview),
                controlRotation: controlRotation
            )
                .environmentObject(camera)
                .environmentObject(language)
        }
        .sheet(
            isPresented: Binding(
                get: { metadataItemID != nil },
                set: { if !$0 { metadataItemID = nil } }
            )
        ) {
            if let metadataItemID {
                MediaMetadataView(itemID: metadataItemID) { item in
                    self.metadataItemID = nil
                    pendingDeletion = item
                }
                .environmentObject(camera)
                .environmentObject(language)
            }
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
                    Text(language.format("media_filtered_count_format", displayedMedia.count, camera.mediaItems.count))
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
            if camera.supports(.mediaUpload) {
                uploadControl
            }
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

    private var mediaToolbar: some View {
        HStack(spacing: 10) {
            Picker(language.string("media_filter"), selection: $mediaFilter) {
                ForEach(MediaFilter.allCases) { filter in
                    Text(language.string(filter.localizationKey)).tag(filter)
                }
            }
            .pickerStyle(.segmented)
            .accessibilityIdentifier("media-filter")

            Menu {
                Picker(language.string("media_sort"), selection: $mediaSort) {
                    ForEach(MediaSort.allCases) { sort in
                        Label(language.string(sort.localizationKey), systemImage: sort.systemImage).tag(sort)
                    }
                }
            } label: {
                Image(systemName: "arrow.up.arrow.down")
                    .frame(width: 48, height: 48)
                    .accessibilityLabel(Text("media_sort"))
            }
            .foregroundStyle(Color.cameraText)
            .accessibilityIdentifier("media-sort")
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(Color.cameraSurface)
    }

    @ViewBuilder
    private var uploadControl: some View {
        if camera.activeMediaUploadName != nil {
            Button { camera.cancelMediaUpload() } label: {
                RotatingControl(degrees: controlRotation) {
                    Image(systemName: "xmark.circle.fill")
                        .accessibilityLabel(Text("cancel_media_upload"))
                }
            }
            .buttonStyle(CameraIconButtonStyle())
            .foregroundStyle(Color.cameraRecording)
            .accessibilityIdentifier("cancel-media-upload")
        } else {
            Button { isFileImporterPresented = true } label: {
                RotatingControl(degrees: controlRotation) {
                    Image(systemName: "arrow.up.circle")
                        .accessibilityLabel(Text("upload_media"))
                }
            }
            .buttonStyle(CameraIconButtonStyle())
            .foregroundStyle(Color.cameraAccent)
            .accessibilityIdentifier("upload-media-button")
        }
    }

    @ViewBuilder
    private var uploadStatus: some View {
        VStack(alignment: .leading, spacing: 5) {
            if let name = camera.activeMediaUploadName, let progress = camera.mediaUploadProgress {
                HStack(spacing: 8) {
                    Text(language.format("uploading_media", name))
                        .font(.caption.weight(.semibold))
                        .lineLimit(1)
                    Spacer()
                    Button { camera.cancelMediaUpload() } label: {
                        Image(systemName: "xmark.circle.fill")
                            .accessibilityLabel(Text("cancel_media_upload"))
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(Color.cameraRecording)
                    .accessibilityIdentifier("cancel-media-upload-status")
                }
                if let fraction = progress.fractionCompleted {
                    ProgressView(value: fraction).tint(Color.cameraAccent)
                } else {
                    ProgressView().tint(Color.cameraAccent)
                }
                Text(mediaUploadProgressLabel(progress))
                    .font(.caption2)
                    .foregroundStyle(Color.cameraSecondaryText)
            } else if let error = camera.mediaUploadError {
                Label(error, systemImage: "exclamationmark.triangle")
                    .font(.caption)
                    .foregroundStyle(Color.cameraWarning)
                    .lineLimit(3)
                    .accessibilityIdentifier("media-upload-error")
            } else if let name = camera.uploadedMediaName {
                Label(language.format("upload_complete", name), systemImage: "checkmark.circle.fill")
                    .font(.caption)
                    .foregroundStyle(Color.cameraStatus)
                    .accessibilityIdentifier("media-upload-complete")
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .background(Color.cameraSurface)
    }

    private func mediaUploadProgressLabel(_ progress: CameraMediaTransferProgress) -> String {
        let transferred = ByteCountFormatter.string(fromByteCount: progress.bytesTransferred, countStyle: .file)
        guard let total = progress.totalBytes, let fraction = progress.fractionCompleted else {
            return language.format("media_upload_progress_unknown", transferred)
        }
        let totalText = ByteCountFormatter.string(fromByteCount: total, countStyle: .file)
        return language.format(
            "media_upload_progress_known",
            transferred,
            totalText,
            Int((fraction * 100).rounded())
        )
    }

    private func mediaCard(_ item: CameraMediaItem) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            mediaThumbnail(item)
                .frame(maxWidth: .infinity)
                .aspectRatio(4 / 3, contentMode: .fit)
            VStack(alignment: .leading, spacing: 4) {
                Text(item.name)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(Color.cameraText)
                    .lineLimit(1)
                    .truncationMode(.middle)
                HStack(spacing: 6) {
                    Text(item.kind.uppercased())
                    if let size = item.sizeBytes {
                        Text(ByteCountFormatter.string(fromByteCount: size, countStyle: .file))
                    }
                }
                .font(.caption)
                .foregroundStyle(Color.cameraSecondaryText)
                if camera.activeMediaDownloadID == item.id,
                   let progress = camera.mediaDownloadProgress {
                    mediaDownloadProgress(progress)
                }
                if let captureTime = mediaCaptureDate(item) {
                    Text(captureTime.formatted(date: .omitted, time: .shortened))
                        .font(.caption2)
                        .foregroundStyle(Color.cameraSecondaryText)
                }
                HStack(spacing: 0) {
                    Spacer(minLength: 0)
                    mediaActions(item)
                }
            }
            .padding(8)
        }
        .background(Color.cameraSurface)
        .clipShape(RoundedRectangle(cornerRadius: 6))
    }

    @ViewBuilder
    private func mediaThumbnail(_ item: CameraMediaItem) -> some View {
        if canPreview(item) {
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
            if mediaIsVideo(item) {
                Image(systemName: "play.fill")
                    .font(.headline)
                    .foregroundStyle(.white)
                    .frame(width: 44, height: 44)
                    .background(.black.opacity(0.62), in: Circle())
                    .accessibilityHidden(true)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .clipped()
    }

    @ViewBuilder
    private func mediaActions(_ item: CameraMediaItem) -> some View {
        let metadataSupported = camera.supports(.mediaProtect) ||
            camera.supports(.mediaRating) || camera.supports(.mediaRotate) || camera.supports(.mediaArchive)
        HStack(spacing: 2) {
            if camera.supports(.mediaDownload) {
                downloadAction(item)
            }
            if metadataSupported {
                Button {
                    metadataItemID = item.id
                } label: {
                    Image(systemName: "ellipsis")
                        .frame(width: 48, height: 48)
                        .accessibilityLabel(Text(language.format("media_actions", item.name)))
                }
                .foregroundStyle(Color.cameraText)
                .disabled(camera.isBusy(.media))
                .accessibilityIdentifier("media-actions-\(item.id)")
            } else if camera.supports(.mediaDelete) {
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
                    .accessibilityIdentifier("download-complete-\(item.id)")
            } else if camera.activeMediaDownloadID == item.id {
                Button {
                    camera.cancelMediaDownload()
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .frame(width: 48, height: 48)
                        .accessibilityLabel(Text("cancel_media_download"))
                }
                .foregroundStyle(Color.cameraRecording)
                .accessibilityIdentifier("cancel-media-download-\(item.id)")
            } else {
                Button {
                    camera.startMediaDownload(item)
                } label: {
                    Image(systemName: "arrow.down.circle")
                        .frame(width: 48, height: 48)
                        .accessibilityLabel(Text("download_media"))
                }
                .foregroundStyle(Color.cameraAccent)
                .disabled(camera.isBusy(.media))
                .accessibilityIdentifier("download-media-\(item.id)")
            }
        }
    }

    private func mediaDownloadProgress(_ progress: CameraMediaTransferProgress) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            if let fraction = progress.fractionCompleted {
                ProgressView(value: fraction)
                    .progressViewStyle(.linear)
                    .tint(Color.cameraAccent)
            } else {
                ProgressView()
                    .controlSize(.small)
                    .tint(Color.cameraAccent)
            }
            Text(mediaDownloadProgressLabel(progress))
                .font(.caption2)
                .foregroundStyle(Color.cameraSecondaryText)
                .lineLimit(1)
                .accessibilityIdentifier("media-download-progress")
        }
        .padding(.top, 2)
    }

    private func mediaDownloadProgressLabel(_ progress: CameraMediaTransferProgress) -> String {
        let transferred = ByteCountFormatter.string(fromByteCount: progress.bytesTransferred, countStyle: .file)
        guard let total = progress.totalBytes, let fraction = progress.fractionCompleted else {
            return language.format("media_download_progress_unknown", transferred)
        }
        let totalText = ByteCountFormatter.string(fromByteCount: total, countStyle: .file)
        return language.format(
            "media_download_progress_known",
            transferred,
            totalText,
            Int((fraction * 100).rounded())
        )
    }

    private func mediaIcon(_ kind: String) -> String {
        switch kind.lowercased() {
        case "video": "film"
        case "raw": "camera.aperture"
        default: "photo"
        }
    }

    private func canPreview(_ item: CameraMediaItem) -> Bool {
        guard !camera.isPreview else { return false }
        if mediaIsVideo(item) {
            return camera.supports(.mediaDownload)
        }
        return item.previewAvailable && camera.supports(.mediaPreview)
    }

    private var displayedMedia: [CameraMediaItem] {
        mediaItemsForDisplay(camera.mediaItems, filter: mediaFilter, sort: mediaSort)
    }

    private var mediaGroups: [MediaGroup] {
        var groups: [MediaGroup] = []
        for item in displayedMedia {
            let title: String
            if mediaSort == .name {
                title = item.name.first.map { String($0).uppercased() } ?? "#"
            } else {
                title = mediaCaptureDate(item)?.formatted(date: .abbreviated, time: .omitted)
                    ?? language.string("media_unknown_date")
            }
            if groups.last?.title == title {
                groups[groups.count - 1].items.append(item)
            } else {
                groups.append(MediaGroup(id: "\(groups.count)-\(title)", title: title, items: [item]))
            }
        }
        return groups
    }

}

private struct MediaGroup: Identifiable {
    let id: String
    let title: String
    var items: [CameraMediaItem]
}

private struct MediaMetadataView: View {
    @EnvironmentObject private var camera: CameraAppState
    @EnvironmentObject private var language: AppLanguageStore
    let itemID: String
    let onDelete: (CameraMediaItem) -> Void

    private var item: CameraMediaItem? {
        camera.mediaItems.first { $0.id == itemID }
    }

    var body: some View {
        ScrollView {
            if let item {
                VStack(alignment: .leading, spacing: 18) {
                    Text(item.name)
                        .font(.headline)
                        .lineLimit(1)
                        .truncationMode(.middle)

                    if camera.isBusy(.media) {
                        ProgressView().tint(Color.cameraAccent).frame(maxWidth: .infinity)
                    }

                    if camera.supports(.mediaProtect) {
                        metadataHeader(
                            language.string("media_protection"),
                            value: protectionLabel(item.protected)
                        )
                        HStack(spacing: 12) {
                            metadataIconButton(
                                systemName: "lock",
                                label: language.format("protect_media", item.name),
                                selected: item.protected == true,
                                enabled: item.protected != true
                            ) { Task { await camera.setMediaProtection(item, enabled: true) } }
                            metadataIconButton(
                                systemName: "lock.open",
                                label: language.format("unprotect_media", item.name),
                                selected: item.protected == false,
                                enabled: item.protected != false
                            ) { Task { await camera.setMediaProtection(item, enabled: false) } }
                        }
                    }

                    if camera.supports(.mediaRating) {
                        metadataHeader(
                            language.string("media_rating"),
                            value: item.rating.map { language.format("media_rating_value", $0) }
                                ?? language.string("media_metadata_unknown")
                        )
                        HStack(spacing: 0) {
                            ratingButton(item, rating: 0, systemName: "star.slash")
                            ForEach(1...5, id: \.self) { rating in
                                ratingButton(item, rating: rating, systemName: "star.fill")
                            }
                        }
                        .frame(maxWidth: .infinity)
                    }

                    if camera.supports(.mediaRotate) {
                        metadataHeader(
                            language.string("media_rotation"),
                            value: item.rotationDegrees.map { language.format("media_rotation_value", $0) }
                                ?? language.string("media_metadata_unknown")
                        )
                        Picker(
                            language.string("media_rotation"),
                            selection: Binding(
                                get: { item.rotationDegrees ?? -1 },
                                set: { value in Task { await camera.setMediaRotation(item, degrees: value) } }
                            )
                        ) {
                            ForEach([0, 90, 180, 270], id: \.self) { value in
                                Text("\(value)°").tag(value)
                            }
                        }
                        .pickerStyle(.segmented)
                        .disabled(camera.isBusy(.media))
                    }

                    if camera.supports(.mediaArchive), item.archived != nil {
                        metadataHeader(
                            language.string("media_archive"),
                            value: archiveLabel(item.archived)
                        )
                        HStack(spacing: 12) {
                            metadataIconButton(
                                systemName: "archivebox",
                                label: language.format("archive_media", item.name),
                                selected: item.archived == true,
                                enabled: item.archived != true
                            ) { Task { await camera.setMediaArchive(item, enabled: true) } }
                            metadataIconButton(
                                systemName: "arrow.up.bin",
                                label: language.format("unarchive_media", item.name),
                                selected: item.archived == false,
                                enabled: item.archived != false
                            ) { Task { await camera.setMediaArchive(item, enabled: false) } }
                        }
                    }

                    if camera.supports(.mediaDelete) {
                        Divider().overlay(Color.cameraBorder)
                        Button(role: .destructive) { onDelete(item) } label: {
                            Label(language.format("delete_media_named", item.name), systemImage: "trash")
                                .frame(maxWidth: .infinity, minHeight: 48)
                        }
                        .disabled(camera.isBusy(.media))
                        .accessibilityIdentifier("delete-media-\(item.id)")
                    }
                }
                .padding(20)
            }
        }
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
        .background(Color.cameraBackground)
        .task(id: itemID) {
            guard !camera.isPreview, let item else { return }
            await camera.loadMediaInfo(item)
        }
    }

    private func metadataHeader(_ title: String, value: String) -> some View {
        HStack {
            Text(title).font(.subheadline.weight(.semibold)).foregroundStyle(Color.cameraText)
            Spacer()
            Text(value).font(.caption).foregroundStyle(Color.cameraSecondaryText).lineLimit(1)
        }
    }

    private func protectionLabel(_ value: Bool?) -> String {
        guard let value else { return language.string("media_metadata_unknown") }
        return language.string(value ? "media_protected" : "media_unprotected")
    }

    private func archiveLabel(_ value: Bool?) -> String {
        guard let value else { return language.string("media_metadata_unknown") }
        return language.string(value ? "media_archived" : "media_unarchived")
    }

    private func metadataIconButton(
        systemName: String,
        label: String,
        selected: Bool,
        enabled: Bool,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Image(systemName: systemName)
                .frame(width: 48, height: 48)
                .accessibilityLabel(Text(label))
        }
        .buttonStyle(CameraIconButtonStyle())
        .foregroundStyle(selected ? Color.cameraAccent : Color.cameraText)
        .disabled(camera.isBusy(.media) || !enabled)
    }

    private func ratingButton(_ item: CameraMediaItem, rating: Int, systemName: String) -> some View {
        Button {
            Task { await camera.setMediaRating(item, rating: rating) }
        } label: {
            Image(systemName: systemName)
                .frame(maxWidth: .infinity, minHeight: 48)
                .accessibilityLabel(Text(language.format("set_media_rating", item.name, rating)))
        }
        .foregroundStyle((item.rating ?? 0) >= rating && rating > 0 ? Color.cameraWarning : Color.cameraSecondaryText)
        .disabled(camera.isBusy(.media) || item.rating == rating)
    }
}

private struct MediaPreviewView: View {
    @EnvironmentObject private var camera: CameraAppState
    @EnvironmentObject private var language: AppLanguageStore
    let items: [CameraMediaItem]
    let controlRotation: Double
    @State private var imageScale = 1.0
    @State private var settledImageScale = 1.0

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if let playback = camera.mediaVideoPlayback {
                CameraVideoPreview(playback: playback)
            } else if let data = camera.mediaPreviewData, let image = UIImage(data: data) {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .padding(.vertical, 64)
                    .scaleEffect(imageScale)
                    .gesture(
                        MagnifyGesture()
                            .onChanged { imageScale = min(6, max(1, settledImageScale * $0.magnification)) }
                            .onEnded { _ in settledImageScale = imageScale }
                    )
                    .onTapGesture(count: 2) {
                        withAnimation(.easeOut(duration: 0.18)) {
                            imageScale = imageScale > 1 ? 1 : 2
                            settledImageScale = imageScale
                        }
                    }
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
                HStack {
                    previewNavigationButton(systemName: "chevron.left", offset: -1)
                    Spacer()
                    previewNavigationButton(systemName: "chevron.right", offset: 1)
                }
                .padding(.horizontal, 6)
                Spacer()
            }
        }
    }

    @ViewBuilder
    private func previewNavigationButton(systemName: String, offset: Int) -> some View {
        let destination = adjacentItem(offset: offset)
        Button {
            guard let destination else { return }
            imageScale = 1
            settledImageScale = 1
            Task { await camera.openMediaPreview(destination) }
        } label: {
            Image(systemName: systemName)
                .font(.title2.weight(.semibold))
                .frame(width: 48, height: 64)
                .background(.black.opacity(0.48), in: RoundedRectangle(cornerRadius: 6))
                .accessibilityLabel(Text(language.string(offset < 0 ? "previous_media" : "next_media")))
        }
        .buttonStyle(.plain)
        .foregroundStyle(.white)
        .disabled(destination == nil)
        .opacity(destination == nil ? 0 : 1)
    }

    private func adjacentItem(offset: Int) -> CameraMediaItem? {
        guard let currentID = camera.mediaPreviewItem?.id,
              let index = items.firstIndex(where: { $0.id == currentID }) else { return nil }
        let destination = index + offset
        guard items.indices.contains(destination) else { return nil }
        return items[destination]
    }
}

private struct CameraVideoPreview: View {
    @EnvironmentObject private var language: AppLanguageStore
    @ObservedObject var playback: CameraMediaPlayback

    var body: some View {
        ZStack {
            VideoPlayer(player: playback.player)
                .ignoresSafeArea()
                .onAppear { playback.play() }
                .onDisappear { playback.pause() }
            if playback.errorMessage != nil {
                Text(language.string("media_video_unavailable"))
                    .foregroundStyle(Color.cameraSecondaryText)
                    .padding(24)
                    .background(.black.opacity(0.72), in: RoundedRectangle(cornerRadius: 6))
            }
        }
    }
}
