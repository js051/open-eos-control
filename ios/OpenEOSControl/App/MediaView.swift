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
    @State private var pendingPreviewMetadataItemID: String?
    @State private var isFileImporterPresented = false
    @State private var mediaFilter = MediaFilter.all
    @State private var mediaSort = MediaSort.newest
    @State private var isFolderFilterPresented = false
    @State private var folderFilterOwner: UUID?
    @State private var folderFilterDraft = MediaFolderFilter.all
    @State private var isRatingFilterPresented = false
    @State private var ratingFilterOwner: UUID?
    @State private var ratingFilterDraft = MediaRatingFilter.all
    @State private var isDateFilterPresented = false
    @State private var dateFilterOwner: UUID?
    @State private var dateFilterStart = Date()
    @State private var dateFilterEnd = Date()
    @State private var dateFilterTimeZone = TimeZone.current

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
            ScrollView {
                VStack(spacing: 0) {
                    mediaToolbar
                    MediaBatchPanel(displayedItems: displayedMedia)
                    if camera.mediaLibraryLoading, camera.mediaItems.isEmpty {
                        ProgressView("loading_media").tint(Color.cameraAccent)
                            .accessibilityIdentifier("media-library-loading-empty")
                            .frame(minHeight: 180)
                    } else if camera.mediaItems.isEmpty, camera.mediaLibraryLoadStatus == .cancelled {
                        ContentUnavailableView("media_load_cancelled", systemImage: "pause.circle")
                            .frame(minHeight: 180)
                    } else if camera.mediaItems.isEmpty, camera.mediaLibraryLoadStatus == .failed {
                        ContentUnavailableView("media_load_failed", systemImage: "exclamationmark.triangle")
                            .frame(minHeight: 180)
                    } else if camera.mediaItems.isEmpty {
                        ContentUnavailableView("no_media", systemImage: "photo.on.rectangle.angled")
                            .frame(minHeight: 180)
                    } else if displayedMedia.isEmpty {
                        ContentUnavailableView("no_media", systemImage: "line.3.horizontal.decrease.circle")
                            .frame(minHeight: 180)
                    } else {
                        LazyVStack(alignment: .leading, spacing: 12) {
                            ForEach(mediaGroups) { group in
                                if let title = group.title {
                                    Text(title)
                                        .font(.caption.weight(.semibold))
                                        .foregroundStyle(Color.cameraSecondaryText)
                                        .padding(.top, 4)
                                }
                                LazyVGrid(columns: mediaColumns, alignment: .leading, spacing: 10) {
                                    ForEach(group.items, id: \.idBytes) { item in
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
            if camera.mediaItems.isEmpty { camera.startMediaLibraryLoad() }
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
        .onChange(of: camera.mediaLibrarySessionID) { _, _ in
            isDateFilterPresented = false
            dateFilterOwner = nil
            isRatingFilterPresented = false
            ratingFilterOwner = nil
            isFolderFilterPresented = false
            folderFilterOwner = nil
        }
        .sheet(isPresented: $isDateFilterPresented) { dateFilterSheet }
        .sheet(isPresented: $isRatingFilterPresented) { ratingFilterSheet }
        .sheet(isPresented: $isFolderFilterPresented) { folderFilterSheet }
        .fullScreenCover(
            isPresented: Binding(
                get: { camera.mediaPreviewItem != nil },
                set: { if !$0 { camera.closeMediaPreview() } }
            ),
            onDismiss: {
                guard let pendingPreviewMetadataItemID else { return }
                self.pendingPreviewMetadataItemID = nil
                metadataItemID = pendingPreviewMetadataItemID
            }
        ) {
            MediaPreviewView(
                items: displayedMedia.filter(canPreview),
                controlRotation: controlRotation,
                onActions: { item in
                    pendingPreviewMetadataItemID = item.id
                    camera.closeMediaPreview()
                }
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
                camera.cancelMediaLibraryLoad()
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
                    Text("camera_media")
                        .font(.headline)
                        .lineLimit(1)
                    HStack(spacing: 5) {
                        if camera.mediaLibraryLoading, !camera.mediaItems.isEmpty {
                            ProgressView()
                                .controlSize(.mini)
                                .tint(Color.cameraAccent)
                                .accessibilityLabel(Text("loading_media"))
                                .accessibilityIdentifier("media-library-loading-progressive")
                        }
                        Text(mediaLibrarySummary)
                            .lineLimit(1)
                            .truncationMode(.tail)
                            .accessibilityIdentifier(
                                "media-library-summary-\(camera.mediaLibraryLoadStatus.rawValue.lowercased())"
                            )
                    }
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
            .frame(maxWidth: .infinity, alignment: .leading)
            if camera.supports(.mediaUpload) {
                uploadControl
            }
            Button {
                if camera.mediaLibraryLoadCancellable {
                    camera.cancelMediaLibraryLoad()
                } else if !camera.mediaLibraryLoading {
                    camera.startMediaLibraryLoad()
                }
            } label: {
                RotatingControl(degrees: controlRotation) {
                    Image(systemName: camera.mediaLibraryLoadCancellable ? "xmark.circle" : "arrow.clockwise")
                        .accessibilityLabel(
                            Text(camera.mediaLibraryLoadCancellable ? "cancel_loading_media" : "refresh_media")
                        )
                }
            }
            .buttonStyle(CameraIconButtonStyle())
            .disabled(
                (camera.mediaLibraryLoading && !camera.mediaLibraryLoadCancellable) ||
                    (!camera.mediaLibraryLoading && camera.isBusy(.media))
            )
            .accessibilityIdentifier(camera.mediaLibraryLoadCancellable ? "cancel-media-library-load" : "refresh-media")
            .fixedSize()
        }
        .foregroundStyle(Color.cameraText)
        .padding(.horizontal, 10)
        .frame(minHeight: 56)
    }

    private var mediaToolbar: some View {
        VStack(spacing: 8) {
            Picker(
                language.string("media_scope"),
                selection: Binding(
                    get: { camera.mediaLibraryScope },
                    set: { camera.setMediaLibraryScope($0) }
                )
            ) {
                ForEach(MediaLibraryScope.allCases) { scope in
                    Text(language.string(scope.localizationKey)).tag(scope)
                }
            }
            .pickerStyle(.segmented)
            .accessibilityIdentifier("media-scope")

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
            VStack(alignment: .leading, spacing: 6) {
                Button {
                    folderFilterOwner = camera.mediaLibrarySessionID
                    folderFilterDraft = camera.mediaFolderFilter
                    isFolderFilterPresented = true
                } label: {
                    Label(language.string("media_folder_filter"), systemImage: "folder")
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .frame(minHeight: 44)
                }
                .accessibilityIdentifier("media-folder-filter")
                if camera.mediaFolderFilter != .all {
                    Text(folderFilterLabel(camera.mediaFolderFilter))
                        .font(.caption)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityIdentifier("media-folder-applied")
                    Text(folderFilterSummary)
                        .font(.caption)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityIdentifier("media-folder-summary")
                    Button(language.string("media_folder_clear")) {
                        camera.setMediaFolderFilter(.all, owner: camera.mediaLibrarySessionID)
                    }
                    .frame(minHeight: 44)
                    .accessibilityIdentifier("media-folder-clear")
                }
            }
            VStack(alignment: .leading, spacing: 6) {
                Button {
                    ratingFilterOwner = camera.mediaLibrarySessionID
                    ratingFilterDraft = camera.mediaRatingFilter
                    isRatingFilterPresented = true
                } label: {
                    Label(language.string("media_rating_filter"), systemImage: "star")
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .frame(minHeight: 44)
                }
                .accessibilityIdentifier("media-rating-filter")
                if camera.mediaRatingFilter != .all || mediaSort == .ratingHigh || mediaSort == .ratingLow {
                    Text(language.string(camera.mediaRatingFilter.localizationKey))
                        .font(.caption)
                        .accessibilityIdentifier("media-rating-applied")
                    Text(ratingFilterSummary)
                        .font(.caption)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityIdentifier("media-rating-summary")
                }
                if camera.mediaRatingFilter != .all {
                    Button(language.string("media_rating_clear")) {
                        camera.setMediaRatingFilter(.all, owner: camera.mediaLibrarySessionID)
                    }
                    .frame(minHeight: 44)
                    .accessibilityIdentifier("media-rating-clear")
                }
            }
            VStack(alignment: .leading, spacing: 6) {
                Button {
                    dateFilterOwner = camera.mediaLibrarySessionID
                    dateFilterTimeZone = camera.mediaDateRange?.timeZone ?? .current
                    dateFilterStart = camera.mediaDateRange?.startDate ?? Date()
                    dateFilterEnd = camera.mediaDateRange?.endDate ?? dateFilterStart
                    isDateFilterPresented = true
                } label: {
                    Label(language.string("media_date_range"), systemImage: "calendar")
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .frame(minHeight: 44)
                }
                .accessibilityIdentifier("media-date-range")
                if let range = camera.mediaDateRange {
                    Text(dateRangeLabel(range))
                        .font(.caption)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityIdentifier("media-date-range-applied")
                    Text(dateFilterSummary)
                        .font(.caption)
                        .fixedSize(horizontal: false, vertical: true)
                        .accessibilityIdentifier("media-date-range-summary")
                    Button(language.string("media_date_clear")) {
                        camera.setMediaDateRange(nil, owner: camera.mediaLibrarySessionID)
                    }
                    .frame(minHeight: 44)
                    .accessibilityIdentifier("media-date-range-clear")
                }
            }
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
            .disabled(camera.mediaLibraryLoading)
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
                    Button {
                        camera.toggleMediaSelection(item)
                    } label: {
                        Image(systemName: camera.isMediaSelected(item) ? "checkmark.circle.fill" : "circle")
                            .frame(minWidth: 44, minHeight: 44)
                    }
                    .accessibilityLabel(language.format("media_batch_select_item", item.name))
                    .accessibilityValue(language.string(camera.isMediaSelected(item) ? "media_batch_selected" : "media_batch_not_selected"))
                    .accessibilityIdentifier("media-select-\(item.id)")
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
        HStack(spacing: 2) {
            if camera.supports(.mediaDownload) {
                downloadAction(item)
            }
            if camera.supports(.mediaBrowser) {
                Button {
                    metadataItemID = item.id
                } label: {
                    Image(systemName: "ellipsis")
                        .frame(width: 48, height: 48)
                        .accessibilityLabel(Text(language.format("media_actions", item.name)))
                }
                .foregroundStyle(Color.cameraText)
                .disabled(camera.mediaLibraryLoading || camera.isBusy(.media))
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
                .disabled(camera.mediaLibraryLoading || camera.isBusy(.media))
                .accessibilityIdentifier("delete-media-\(item.id)")
            }
        }
    }

    @ViewBuilder
    private func downloadAction(_ item: CameraMediaItem) -> some View {
        Group {
            if let url = camera.downloadedFile(for: item) {
                ShareLink(item: url) {
                    Image(systemName: "square.and.arrow.up")
                        .frame(width: 48, height: 48)
                        .accessibilityLabel(Text("save_media"))
                }
                .foregroundStyle(Color.cameraStatus)
            } else if camera.isMediaDownloaded(item), camera.isPreview {
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

    private var dateFilterCalendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = dateFilterTimeZone
        return calendar
    }

    private var dateFilterDraft: MediaDateRange? {
        MediaDateRange(startDate: dateFilterStart, endDate: dateFilterEnd, timeZone: dateFilterTimeZone)
    }

    private var dateFilterSheet: some View {
        NavigationStack {
            Form {
                Section {
                    DatePicker(language.string("media_date_start"), selection: $dateFilterStart, displayedComponents: .date)
                        .accessibilityIdentifier("media-date-start")
                    DatePicker(language.string("media_date_end"), selection: $dateFilterEnd, displayedComponents: .date)
                        .accessibilityIdentifier("media-date-end")
                    Text(language.format("media_date_timezone", dateFilterTimeZone.identifier))
                    if dateFilterDraft == nil {
                        Text(language.string("media_date_invalid"))
                            .foregroundStyle(.red)
                            .accessibilityIdentifier("media-date-invalid")
                    }
                }
                Section {
                    Text(language.string("media_date_loaded_only"))
                    Text(language.string("media_date_unknown_excluded"))
                }
            }
            .environment(\.timeZone, dateFilterTimeZone)
            .environment(\.calendar, dateFilterCalendar)
            .environment(\.locale, language.locale)
            .navigationTitle(language.string("media_date_range"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(language.string("cancel")) { isDateFilterPresented = false }
                        .accessibilityIdentifier("media-date-cancel")
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(language.string("apply")) {
                        guard let owner = dateFilterOwner, let range = dateFilterDraft else { return }
                        camera.setMediaDateRange(range, owner: owner)
                        isDateFilterPresented = false
                    }
                    .disabled(dateFilterDraft == nil || dateFilterOwner != camera.mediaLibrarySessionID)
                    .accessibilityIdentifier("media-date-apply")
                }
            }
        }
    }

    private var folderChoices: [CameraMediaFolder] {
        var folders = mediaFolderOptions(camera.mediaItems).map(\.folder)
        if case let .folder(selected) = camera.mediaFolderFilter,
           !folders.contains(where: { mediaFolderIdentity($0) == mediaFolderIdentity(selected) }) {
            folders.append(selected)
        }
        return folders
    }

    private func folderFilterLabel(_ filter: MediaFolderFilter) -> String {
        switch filter {
        case .all: return language.string("media_folder_all")
        case .unknown: return language.string("media_folder_unknown")
        case let .folder(folder):
            if let current = mediaFolderOptions(camera.mediaItems).first(where: { $0.id == mediaFolderIdentity(folder) }) {
                return folderChoiceLabel(current.folder)
            }
            return folder.label + " · " + language.string("media_folder_missing")
        }
    }

    private func folderChoiceLabel(_ folder: CameraMediaFolder) -> String {
        let duplicates = folderChoices.filter { $0.label == folder.label }
        guard duplicates.count > 1, let index = duplicates.firstIndex(where: { mediaFolderIdentity($0) == mediaFolderIdentity(folder) }) else {
            return folder.label
        }
        return language.format("media_folder_duplicate", folder.label, index + 1)
    }

    private func folderChoiceButton(_ filter: MediaFolderFilter, label: String, identifier: String) -> some View {
        Button { folderFilterDraft = filter } label: {
            HStack {
                Text(label).lineLimit(3)
                Spacer()
                if folderFilterDraft == filter { Image(systemName: "checkmark") }
            }
            .frame(minHeight: 44)
        }
        .accessibilityLabel(Text(label))
        .accessibilityIdentifier(identifier)
        .accessibilityAddTraits(folderFilterDraft == filter ? [.isSelected] : [])
    }

    private var folderFilterSheet: some View {
        NavigationStack {
            Form {
                Section {
                    folderChoiceButton(.all, label: language.string("media_folder_all"), identifier: "media-folder-choice-all")
                    folderChoiceButton(.unknown, label: language.string("media_folder_unknown"), identifier: "media-folder-choice-unknown")
                    ForEach(folderChoices, id: \.folderViewIdentity) { folder in
                        folderChoiceButton(.folder(folder), label: folderChoiceLabel(folder),
                            identifier: "media-folder-choice-known-\(folder.id)")
                    }
                }
                Section {
                    Text(language.string("media_folder_loaded_only"))
                    if case let .folder(selected) = camera.mediaFolderFilter,
                       !mediaFolderOptions(camera.mediaItems).contains(where: { $0.id == mediaFolderIdentity(selected) }) {
                        Text(language.string("media_folder_missing"))
                    }
                }
            }
            .navigationTitle(language.string("media_folder_filter"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(language.string("cancel")) { isFolderFilterPresented = false }
                        .accessibilityIdentifier("media-folder-cancel")
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(language.string("apply")) {
                        guard let owner = folderFilterOwner else { return }
                        camera.setMediaFolderFilter(folderFilterDraft, owner: owner)
                        isFolderFilterPresented = false
                    }
                    .disabled(folderFilterOwner != camera.mediaLibrarySessionID)
                    .accessibilityIdentifier("media-folder-apply")
                }
            }
        }
    }

    private var folderFilterSummary: String {
        let summary = mediaFolderFilterSummary(camera.mediaItems, filter: mediaFilter,
            dateRange: camera.mediaDateRange, ratingFilter: camera.mediaRatingFilter,
            folderFilter: camera.mediaFolderFilter)
        return language.format("media_folder_summary", summary.matchedCount, summary.loadedCount,
            summary.unknownFolderCount) + " " + language.string("media_folder_loaded_only")
    }

    private var ratingFilterSheet: some View {
        NavigationStack {
            Form {
                Section {
                    ForEach(MediaRatingFilter.allCases) { filter in
                        Button {
                            ratingFilterDraft = filter
                        } label: {
                            HStack {
                                Text(language.string(filter.localizationKey))
                                Spacer()
                                if ratingFilterDraft == filter { Image(systemName: "checkmark") }
                            }
                            .frame(minHeight: 44)
                        }
                        .accessibilityIdentifier("media-rating-choice-\(filter.rawValue)")
                        .accessibilityAddTraits(ratingFilterDraft == filter ? [.isSelected] : [])
                    }
                }
                Section {
                    Text(language.string("media_rating_loaded_only"))
                    Text(language.string("media_rating_unknown_help"))
                }
            }
            .navigationTitle(language.string("media_rating_filter"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(language.string("cancel")) { isRatingFilterPresented = false }
                        .accessibilityIdentifier("media-rating-cancel")
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(language.string("apply")) {
                        guard let owner = ratingFilterOwner else { return }
                        camera.setMediaRatingFilter(ratingFilterDraft, owner: owner)
                        isRatingFilterPresented = false
                    }
                    .disabled(ratingFilterOwner != camera.mediaLibrarySessionID)
                    .accessibilityIdentifier("media-rating-apply")
                }
            }
        }
    }

    private var ratingFilterSummary: String {
        let summary = mediaRatingFilterSummary(camera.mediaItems, filter: mediaFilter,
            dateRange: camera.mediaDateRange, ratingFilter: camera.mediaRatingFilter, folderFilter: camera.mediaFolderFilter)
        return language.format("media_rating_summary", summary.matchedCount, summary.loadedCount,
                               summary.unknownRatingCount) + " " + language.string("media_rating_loaded_only")
    }

    private func dateRangeLabel(_ range: MediaDateRange) -> String {
        let formatter = DateFormatter()
        formatter.locale = language.locale
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.timeZone = range.timeZone
        formatter.dateStyle = .medium
        formatter.timeStyle = .none
        return language.format("media_date_range_applied", formatter.string(from: range.startDate),
                               formatter.string(from: range.endDate), range.timeZone.identifier)
    }

    private var dateFilterSummary: String {
        let summary = mediaDateFilterSummary(camera.mediaItems, filter: mediaFilter, dateRange: camera.mediaDateRange,
            ratingFilter: camera.mediaRatingFilter, folderFilter: camera.mediaFolderFilter)
        return language.format("media_date_summary", summary.matchedCount, summary.loadedCount,
                               summary.unknownDateCount) + " " + language.string("media_date_loaded_only")
    }

    private var displayedMedia: [CameraMediaItem] {
        mediaItemsForDisplay(camera.mediaItems, filter: mediaFilter, sort: mediaSort, dateRange: camera.mediaDateRange,
            ratingFilter: camera.mediaRatingFilter, folderFilter: camera.mediaFolderFilter)
    }

    private var mediaLibrarySummary: String {
        switch camera.mediaLibraryLoadStatus {
        case .notLoaded:
            language.format("media_not_loaded_count_format", camera.mediaItems.count)
        case .loading:
            language.format("media_loading_count_format", camera.mediaItems.count)
        case .complete:
            if camera.mediaLibraryScope == .recent {
                language.format(
                    camera.mediaLibraryHasMore
                        ? "media_recent_more_count_format"
                        : "media_recent_count_format",
                    camera.mediaItems.count
                )
            } else {
                language.format("media_filtered_count_format", displayedMedia.count, camera.mediaItems.count)
            }
        case .cancelled:
            language.format("media_cancelled_count_format", camera.mediaItems.count)
        case .failed:
            language.format("media_failed_count_format", camera.mediaItems.count)
        }
    }

    private var mediaGroups: [MediaGroup] {
        if mediaSort == .camera || mediaSort == .ratingHigh || mediaSort == .ratingLow {
            return displayedMedia.isEmpty
                ? []
                : [MediaGroup(id: "camera", title: nil, items: displayedMedia)]
        }
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
    let title: String?
    var items: [CameraMediaItem]
}

private func mediaDimensionsText(_ item: CameraMediaItem) -> String? {
    guard let width = item.widthPixels, width > 0,
          let height = item.heightPixels, height > 0 else { return nil }
    return "\(width) x \(height)"
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

                    metadataHeader(
                        language.string("media_format"),
                        value: item.contentType ?? item.kind.uppercased()
                    )
                    if let dimensions = mediaDimensionsText(item) {
                        metadataHeader(language.string("media_dimensions"), value: dimensions)
                    }
                    if let size = item.sizeBytes {
                        metadataHeader(
                            language.string("media_file_size"),
                            value: ByteCountFormatter.string(fromByteCount: size, countStyle: .file)
                        )
                    }
                    if let date = mediaCaptureDate(item) {
                        metadataHeader(
                            language.string("media_captured_at"),
                            value: date.formatted(date: .abbreviated, time: .shortened)
                        )
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
                        .disabled(camera.mediaLibraryLoading || camera.isBusy(.media))
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
                        .disabled(camera.mediaLibraryLoading || camera.isBusy(.media))
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
        .disabled(camera.mediaLibraryLoading || camera.isBusy(.media) || !enabled)
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
        .disabled(camera.mediaLibraryLoading || camera.isBusy(.media) || item.rating == rating)
    }
}

private struct MediaPreviewView: View {
    @EnvironmentObject private var camera: CameraAppState
    @EnvironmentObject private var language: AppLanguageStore
    let items: [CameraMediaItem]
    let controlRotation: Double
    let onActions: (CameraMediaItem) -> Void

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if let playback = camera.mediaVideoPlayback,
               let item = camera.mediaPreviewItem {
                CameraVideoPreview(
                    playback: playback,
                    item: item,
                    downloadEnabled: camera.supports(.mediaDownload) && !camera.isBusy(.media),
                    onRetry: {
                        Task { await camera.openMediaPreview(item) }
                    },
                    onDownload: {
                        camera.startMediaDownload(item)
                    }
                )
            } else if let item = camera.mediaPreviewItem,
                      let data = camera.mediaPreviewData,
                      let image = UIImage(data: data) {
                ZoomableMediaImage(
                    image: image,
                    itemID: item.id,
                    controlRotation: controlRotation
                )
                    .padding(.horizontal, 12)
                    .padding(.vertical, 72)
                    .accessibilityLabel(Text(language.format("media_preview_content", camera.mediaPreviewItem?.name ?? "")))
            } else if camera.mediaPreviewLoading {
                ProgressView().tint(Color.cameraAccent).controlSize(.large)
            } else {
                VStack(spacing: 12) {
                    Text("media_preview_unavailable")
                        .foregroundStyle(Color.cameraSecondaryText)
                    if let item = camera.mediaPreviewItem, !mediaIsVideo(item), item.previewAvailable {
                        Button {
                            Task { await camera.retryMediaPreview() }
                        } label: {
                            Label("retry_media_preview", systemImage: "arrow.clockwise")
                                .frame(minWidth: 44, minHeight: 44)
                        }
                        .disabled(!camera.canRetryMediaPreview)
                        .accessibilityIdentifier("retry-media-preview")
                    }
                }
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
                    if let item = camera.mediaPreviewItem {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(item.name)
                                .font(.callout.weight(.semibold))
                                .lineLimit(1)
                                .truncationMode(.middle)
                            HStack(spacing: 6) {
                                Text(positionText(for: item))
                                    .accessibilityIdentifier("media-preview-position")
                                Text(metadataText(for: item))
                                    .lineLimit(1)
                                    .truncationMode(.tail)
                            }
                            .font(.caption)
                            .foregroundStyle(Color.cameraSecondaryText)
                        }
                    }
                    Spacer(minLength: 8)
                    if let item = camera.mediaPreviewItem {
                        if camera.supports(.mediaDownload) {
                            previewDownloadAction(item)
                        }
                        if actionsAvailable {
                            Button {
                                onActions(item)
                            } label: {
                                RotatingControl(degrees: controlRotation) {
                                    Image(systemName: "ellipsis")
                                        .frame(width: 48, height: 48)
                                        .accessibilityLabel(Text(language.format("media_actions", item.name)))
                                }
                            }
                            .buttonStyle(.plain)
                            .disabled(camera.isBusy(.media))
                            .accessibilityIdentifier("media-preview-actions-\(item.id)")
                        }
                    }
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
        .alert(
            Text("operation_failed"),
            isPresented: Binding(
                get: { camera.lastError != nil && camera.mediaPreviewItem != nil && !camera.shutterReleaseRequired },
                set: { if !$0 { camera.clearError() } }
            )
        ) {
            Button("dismiss", role: .cancel) { camera.clearError() }
        } message: {
            Text(camera.lastError ?? "")
        }
    }

    @ViewBuilder
    private func previewNavigationButton(systemName: String, offset: Int) -> some View {
        let destination = adjacentItem(offset: offset)
        Button {
            guard let destination else { return }
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
        .accessibilityIdentifier(offset < 0 ? "media-preview-previous" : "media-preview-next")
    }

    private func adjacentItem(offset: Int) -> CameraMediaItem? {
        guard let currentID = camera.mediaPreviewItem?.id,
              let index = items.firstIndex(where: { $0.id == currentID }) else { return nil }
        let destination = index + offset
        guard items.indices.contains(destination) else { return nil }
        return items[destination]
    }

    private var actionsAvailable: Bool {
        camera.supports(.mediaBrowser) || camera.supports(.mediaProtect) || camera.supports(.mediaRating) ||
            camera.supports(.mediaRotate) || camera.supports(.mediaArchive) ||
            camera.supports(.mediaDelete)
    }

    private func positionText(for item: CameraMediaItem) -> String {
        guard let index = items.firstIndex(where: { $0.id == item.id }) else {
            // A bounded or filtered listing is not proof that the selected file was deleted.
            return language.string("media_viewer_outside_list")
        }
        return language.format("media_viewer_position_format", index + 1, items.count)
    }

    private func metadataText(for item: CameraMediaItem) -> String {
        var values = [item.kind.uppercased()]
        if let dimensions = mediaDimensionsText(item) { values.append(dimensions) }
        if let size = item.sizeBytes {
            values.append(ByteCountFormatter.string(fromByteCount: size, countStyle: .file))
        }
        return values.joined(separator: " · ")
    }

    @ViewBuilder
    private func previewDownloadAction(_ item: CameraMediaItem) -> some View {
        if let url = camera.downloadedFile(for: item) {
            ShareLink(item: url) {
                RotatingControl(degrees: controlRotation) {
                    Image(systemName: "square.and.arrow.up")
                        .frame(width: 48, height: 48)
                        .accessibilityLabel(Text("save_media"))
                }
            }
            .accessibilityIdentifier("media-preview-share-\(item.id)")
        } else if camera.isMediaDownloaded(item), camera.isPreview {
            RotatingControl(degrees: controlRotation) {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundStyle(Color.cameraStatus)
                    .frame(width: 48, height: 48)
                    .accessibilityLabel(Text("download_complete"))
            }
            .accessibilityIdentifier("media-preview-download-complete-\(item.id)")
        } else if camera.activeMediaDownloadID == item.id {
            Button {
                camera.cancelMediaDownload()
            } label: {
                RotatingControl(degrees: controlRotation) {
                    Image(systemName: "xmark.circle.fill")
                        .frame(width: 48, height: 48)
                        .accessibilityLabel(Text("cancel_media_download"))
                }
            }
            .buttonStyle(.plain)
            .foregroundStyle(Color.cameraRecording)
            .accessibilityIdentifier("media-preview-cancel-download-\(item.id)")
        } else {
            Button {
                camera.startMediaDownload(item)
            } label: {
                RotatingControl(degrees: controlRotation) {
                    Image(systemName: "arrow.down.circle")
                        .frame(width: 48, height: 48)
                        .accessibilityLabel(Text("download_media"))
                }
            }
            .buttonStyle(.plain)
            .foregroundStyle(Color.cameraAccent)
            .disabled(camera.isBusy(.media))
            .accessibilityIdentifier("media-preview-download-\(item.id)")
        }
    }
}

private struct ZoomableMediaImage: View {
    @EnvironmentObject private var language: AppLanguageStore
    let image: UIImage
    let itemID: String
    let controlRotation: Double
    @State private var scale: CGFloat = 1
    @State private var settledScale: CGFloat = 1
    @State private var offset = CGSize.zero
    @State private var settledOffset = CGSize.zero

    var body: some View {
        GeometryReader { proxy in
            Image(uiImage: image)
                .resizable()
                .scaledToFit()
                .frame(width: proxy.size.width, height: proxy.size.height)
                .scaleEffect(scale)
                .offset(offset)
                .contentShape(Rectangle())
                .gesture(magnificationGesture(viewport: proxy.size))
                .simultaneousGesture(dragGesture(viewport: proxy.size))
                .onTapGesture(count: 2) {
                    withAnimation(.easeOut(duration: 0.18)) {
                        if scale > 1 {
                            resetTransform()
                        } else {
                            scale = 2.5
                            settledScale = scale
                        }
                    }
                }
                .accessibilityIdentifier("media-preview-image")
                .overlay(alignment: .bottomTrailing) {
                    if scale > 1 {
                        Button {
                            withAnimation(.easeOut(duration: 0.18)) { resetTransform() }
                        } label: {
                            RotatingControl(degrees: controlRotation) {
                                Image(systemName: "arrow.counterclockwise")
                                    .frame(width: 48, height: 48)
                                    .accessibilityLabel(Text(language.string("reset_media_zoom")))
                            }
                        }
                        .buttonStyle(.plain)
                        .foregroundStyle(.white)
                        .background(.black.opacity(0.62), in: RoundedRectangle(cornerRadius: 6))
                        .accessibilityIdentifier("reset-media-zoom")
                    }
                }
        }
        .clipped()
        .onChange(of: itemID) { _, _ in resetTransform() }
    }

    private func magnificationGesture(viewport: CGSize) -> some Gesture {
        MagnifyGesture()
            .onChanged { value in
                scale = min(6, max(1, settledScale * value.magnification))
                offset = clampMediaImageOffset(
                    settledOffset,
                    scale: scale,
                    viewport: viewport,
                    image: image.size
                )
            }
            .onEnded { _ in
                settledScale = scale
                offset = clampMediaImageOffset(
                    offset,
                    scale: scale,
                    viewport: viewport,
                    image: image.size
                )
                settledOffset = offset
            }
    }

    private func dragGesture(viewport: CGSize) -> some Gesture {
        DragGesture(minimumDistance: 2)
            .onChanged { value in
                guard scale > 1 else { return }
                offset = clampMediaImageOffset(
                    CGSize(
                        width: settledOffset.width + value.translation.width,
                        height: settledOffset.height + value.translation.height
                    ),
                    scale: scale,
                    viewport: viewport,
                    image: image.size
                )
            }
            .onEnded { _ in settledOffset = offset }
    }

    private func resetTransform() {
        scale = 1
        settledScale = 1
        offset = .zero
        settledOffset = .zero
    }
}

private struct CameraVideoPreview: View {
    @EnvironmentObject private var language: AppLanguageStore
    @ObservedObject var playback: CameraMediaPlayback
    let item: CameraMediaItem
    let downloadEnabled: Bool
    let onRetry: () -> Void
    let onDownload: () -> Void

    var body: some View {
        ZStack {
            VideoPlayer(player: playback.player)
                .ignoresSafeArea()
                .onAppear { playback.play() }
                .onDisappear { playback.pause() }
            if playback.isPreparingFallback {
                VStack(spacing: 10) {
                    ProgressView()
                        .tint(.white)
                    Text(language.string("media_video_preparing"))
                }
                .foregroundStyle(.white)
                .padding(24)
                .background(.black.opacity(0.72), in: RoundedRectangle(cornerRadius: 6))
            } else if let failure = playback.failure {
                VStack(spacing: 8) {
                    Text(videoFailureText(failure))
                        .multilineTextAlignment(.center)
                    if failure == .unsupportedFormat {
                        Text(language.string("media_video_download_hint"))
                            .font(.caption)
                            .multilineTextAlignment(.center)
                    }
                    if failure.retryable {
                        Button(action: onRetry) {
                            Label(language.string("retry_media_video"), systemImage: "arrow.clockwise")
                                .frame(minHeight: 48)
                        }
                        .buttonStyle(.bordered)
                        .accessibilityIdentifier("retry-media-video")
                    }
                    if downloadEnabled {
                        Button(action: onDownload) {
                            Label(language.string("download_original"), systemImage: "arrow.down.circle")
                                .frame(minHeight: 48)
                        }
                        .buttonStyle(.bordered)
                        .accessibilityIdentifier("download-original-video")
                    }
                }
                .foregroundStyle(Color.cameraSecondaryText)
                .padding(24)
                .background(.black.opacity(0.72), in: RoundedRectangle(cornerRadius: 6))
            }
        }
    }

    private func videoFailureText(_ failure: CameraMediaPlaybackFailure) -> String {
        language.format(videoFailureKey(failure), cameraVideoContainerLabel(item.name))
    }

    private func videoFailureKey(_ failure: CameraMediaPlaybackFailure) -> String {
        switch failure {
        case .unsupportedFormat:
            return "media_video_unsupported_format"
        case .incompleteRange:
            return "media_video_incomplete"
        case .storageUnavailable:
            return "media_video_storage_unavailable"
        case .transport:
            return "media_video_unavailable"
        }
    }
}


private extension CameraMediaFolder {
    var folderViewIdentity: Data { mediaFolderIdentity(self) }
}

private extension CameraMediaItem {
    var idBytes: Data { Data(id.utf8) }
}
