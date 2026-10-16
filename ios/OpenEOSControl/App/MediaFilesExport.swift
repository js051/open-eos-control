import Foundation
import SwiftUI
import UIKit
import Darwin

/// A bounded metadata snapshot, not a byte hash or a durability guarantee.
struct MediaFilesExportSourceIdentity: Equatable, Sendable {
    let device: Int64
    let inode: UInt64
    let byteCount: Int64
    let modifiedSeconds: Int64
    let modifiedNanoseconds: Int64
    let changedSeconds: Int64
    let changedNanoseconds: Int64

    static func read(_ url: URL) -> MediaFilesExportSourceIdentity? {
        guard url.isFileURL, url.standardizedFileURL == url.resolvingSymlinksInPath() else { return nil }
        var attributes = stat()
        guard url.path.withCString({ Darwin.lstat($0, &attributes) }) == 0,
              (attributes.st_mode & mode_t(S_IFMT)) == mode_t(S_IFREG),
              attributes.st_nlink == 1, attributes.st_size > 0,
              FileManager.default.isReadableFile(atPath: url.path) else { return nil }
        return MediaFilesExportSourceIdentity(device: Int64(attributes.st_dev), inode: UInt64(attributes.st_ino),
            byteCount: Int64(attributes.st_size), modifiedSeconds: Int64(attributes.st_mtimespec.tv_sec),
            modifiedNanoseconds: Int64(attributes.st_mtimespec.tv_nsec), changedSeconds: Int64(attributes.st_ctimespec.tv_sec),
            changedNanoseconds: Int64(attributes.st_ctimespec.tv_nsec))
    }
}

/// These are observations about one explicit Files picker, never preparation
/// receipts, verified external bytes, or evidence that a cloud provider synced.
enum MediaFilesExportStatus: Equatable {
    case providerReported, cancelled, unconfirmed, sourceUnavailable, historyUnavailable
}

enum MediaFilesExportHistoryStatus: String, Codable, Sendable {
    case started, providerReported, cancelled, unconfirmed, sourceUnavailable
}

/// Batch observations only. No camera IDs, source URLs, destination URLs,
/// persistent grants, byte-success claims, or automatic retry capability.
struct MediaFilesExportHistoryRecord: Codable, Sendable, Identifiable, Equatable {
    let id: UUID
    let filenames: [String]
    let startedAt: Date
    var completedAt: Date?
    var status: MediaFilesExportHistoryStatus
    var reportedCount: Int?
}

struct MediaFilesExportResult: Equatable {
    let attemptID: UUID
    let expectedCount: Int
    let reportedCount: Int?
    let status: MediaFilesExportStatus
}

struct MediaFilesExportAttempt: Identifiable {
    let id = UUID()
    let payload: MediaBatchExportPayload
}

enum MediaFilesExportEvent: Equatable, Sendable {
    case reported(count: Int)
    case cancelled
    case unconfirmed
    case sourceUnavailable
}

/// Presentation ownership is screen-local; production admission and observations
/// are separately persisted before/after the picker. Neither proves saved bytes.
@MainActor
final class MediaFilesExportController: ObservableObject {
    @Published private(set) var presentation: MediaFilesExportAttempt?
    @Published private(set) var result: MediaFilesExportResult?
    @Published private(set) var historyWarning = false
    @Published private var activeID: UUID?
    private var expectedCount = 0
    private var pickerAttached = false
    private var admissionTask: Task<Void, Never>?
    private var historyTask: Task<Void, Never>?
    private var history: (MediaDeliveryStore, MediaFilesExportHistoryTicket)?
    private var historyDidChange: (() -> Void)?
    private var waitingForHistory = false
    private var pickerEnded = false

    var isActive: Bool { activeID != nil }

    @discardableResult
    func beginUnrecordedForTesting(_ payload: MediaBatchExportPayload?) -> Bool {
        // The pure presentation transition is also exercised by coordinator tests.
        // All production entry points use beginRecorded, never this direct path.
        guard let attempt = reserve(payload) else { return false }
        presentation = attempt
        return true
    }

    private func reserve(_ payload: MediaBatchExportPayload?) -> MediaFilesExportAttempt? {
        // Even after a result, wait for actual teardown before admitting another
        // picker. Its coordinator, not this state, owns the presented sources.
        guard activeID == nil else { return nil }
        guard let payload, Self.sourcesAvailable(payload) else {
            result = MediaFilesExportResult(attemptID: UUID(), expectedCount: payload?.fileURLs.count ?? 0,
                reportedCount: nil, status: .sourceUnavailable)
            return nil
        }
        let attempt = MediaFilesExportAttempt(payload: payload)
        activeID = attempt.id
        expectedCount = payload.fileURLs.count
        pickerAttached = false
        pickerEnded = false
        historyWarning = false
        result = nil
        return attempt
    }

    @discardableResult
    func beginRecorded(_ payload: MediaBatchExportPayload?, store: MediaDeliveryStore?,
                       isCurrent: @escaping () -> Bool = { true }, didChange: @escaping () -> Void = {}) -> Bool {
        guard let attempt = reserve(payload) else { return false }
        guard let store else {
            failAdmission(attempt.id)
            return false
        }
        historyDidChange = didChange
        admissionTask = Task { [weak self] in
            do {
                let snapshot = await store.snapshot()
                guard !Task.isCancelled, isCurrent(), let epoch = snapshot.epoch else {
                    self?.failAdmission(attempt.id)
                    return
                }
                let ticket = try await store.beginFilesExportHistory(attemptID: attempt.id,
                    filenames: attempt.payload.fileURLs.map(\.lastPathComponent), expectedEpoch: epoch)
                guard let self else {
                    try? await store.finishFilesExportHistory(ticket, event: .unconfirmed)
                    return
                }
                guard self.activeID == attempt.id, !Task.isCancelled, isCurrent() else {
                    try? await store.finishFilesExportHistory(ticket, event: .unconfirmed)
                    self.failAdmission(attempt.id)
                    didChange()
                    return
                }
                self.history = (store, ticket)
                self.admissionTask = nil
                // Deferred admission must revalidate the same pinned sources.
                guard Self.sourcesAvailable(attempt.payload) else {
                    self.receive(.sourceUnavailable, for: attempt.id)
                    return
                }
                self.presentation = attempt
                didChange()
            } catch {
                self?.failAdmission(attempt.id)
                didChange()
            }
        }
        return true
    }

    private func failAdmission(_ id: UUID) {
        guard activeID == id else { return }
        result = MediaFilesExportResult(attemptID: id, expectedCount: expectedCount,
            reportedCount: nil, status: .historyUnavailable)
        historyWarning = true
        admissionTask = nil
        presentationEnded(id)
    }

    func retirePendingAdmission() {
        guard presentation == nil, admissionTask != nil else { return }
        admissionTask?.cancel()
        // Retain ownership until the actor acknowledges admission or failure.
    }

    /// Deterministic completion barrier for tests; no polling or provider access.
    func settleHistory() async {
        await admissionTask?.value
        await historyTask?.value
    }

    /// Lease constructors restrict sources to owned app files. Recheck the leaf
    /// without opening bytes or following a symlink; no external URL is inspected.
    static func sourcesAvailable(_ payload: MediaBatchExportPayload) -> Bool {
        !payload.fileURLs.isEmpty && payload.fileURLs.count == payload.filesExportSourceIdentities.count
            && zip(payload.fileURLs, payload.filesExportSourceIdentities).allSatisfy { url, expected in
            guard let expected else { return false }
            return MediaFilesExportSourceIdentity.read(url) == expected
        }
    }

    @discardableResult
    func attachPicker(for id: UUID) -> Bool {
        guard activeID == id, presentation?.id == id, !pickerAttached else { return false }
        pickerAttached = true
        return true
    }

    func ownsPresentation(_ id: UUID) -> Bool { activeID == id && presentation?.id == id }

    func receive(_ event: MediaFilesExportEvent, for id: UUID) {
        guard activeID == id, result?.attemptID != id else { return }
        let status: MediaFilesExportStatus
        var reportedCount: Int? = nil
        switch event {
        case .reported(let count):
            reportedCount = (0...100).contains(count) ? count : nil
            // Apple documents destination URLs, not ordered per-input receipts.
            // A partial/empty/mismatched callback leaves the entire mapping unknown.
            status = count > 0 && count == expectedCount ? .providerReported : .unconfirmed
        case .cancelled: status = .cancelled
        case .unconfirmed: status = .unconfirmed
        case .sourceUnavailable: status = .sourceUnavailable
        }
        result = MediaFilesExportResult(attemptID: id, expectedCount: expectedCount,
            reportedCount: reportedCount, status: status)
        if let (store, ticket) = history {
            history = nil
            waitingForHistory = true
            historyTask = Task { [weak self] in
                do { try await store.finishFilesExportHistory(ticket, event: event) }
                catch { self?.historyWarning = true }
                guard let self, self.activeID == id else { return }
                self.waitingForHistory = false
                self.historyTask = nil
                self.historyDidChange?()
                self.releaseIfSettled(id)
            }
        }
        presentation = nil
        if !pickerAttached { presentationEnded(id) }
    }

    func dismissPresentation(_ id: UUID) {
        guard activeID == id else { return }
        receive(.unconfirmed, for: id)
    }

    func presentationEnded(_ id: UUID) {
        guard activeID == id else { return }
        if result?.attemptID != id { receive(.unconfirmed, for: id) }
        // The coordinator still pins its snapshot until UIKit/SwiftUI releases
        // it, even if this owner or the camera has already moved on.
        presentation = nil
        pickerEnded = true
        releaseIfSettled(id)
    }

    private func releaseIfSettled(_ id: UUID) {
        guard activeID == id, pickerEnded, !waitingForHistory else { return }
        activeID = nil
        expectedCount = 0
        pickerAttached = false
        historyDidChange = nil
    }
}

/// Strongly retains the exact prepared files through all delegate callbacks and
/// actual controller teardown. It never retains or reads a destination URL.
@MainActor
final class MediaFilesExportCoordinator: NSObject, UIDocumentPickerDelegate, UIAdaptivePresentationControllerDelegate {
    let attempt: MediaFilesExportAttempt
    private let owner: MediaFilesExportController
    private let attached: Bool
    private var settled = false

    init(attempt: MediaFilesExportAttempt, owner: MediaFilesExportController) {
        self.attempt = attempt
        self.owner = owner
        attached = owner.attachPicker(for: attempt.id)
        super.init()
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        // Do not infer per-file identity from ordering or names, or store provider
        // paths. Duplicate destinations are also an unconfirmed mapping.
        let unique = Set(urls).count == urls.count
        finish(unique ? .reported(count: urls.count) : .unconfirmed)
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) { finish(.cancelled) }
    func presentationControllerDidDismiss(_ presentationController: UIPresentationController) { finish(.unconfirmed) }

    func finish(_ event: MediaFilesExportEvent) {
        guard attached, !settled else { return }
        settled = true
        owner.receive(event, for: attempt.id)
    }

    func presentationFailure() -> MediaFilesExportEvent? {
        guard attached, owner.ownsPresentation(attempt.id), !settled else { return .unconfirmed }
        return MediaFilesExportController.sourcesAvailable(attempt.payload) ? nil : .sourceUnavailable
    }

    func dismantle() {
        guard attached else { return }
        // Programmatic teardown has no guaranteed adaptive-dismiss callback.
        // Never synthesize either cancellation or successful copying here.
        finish(.unconfirmed)
        owner.presentationEnded(attempt.id)
    }
}

/// UIKit can retain a dismissed controller beyond SwiftUI dismantling it. The
/// controller itself therefore owns the coordinator and its immutable leases.
final class MediaFilesExportDocumentPicker: UIDocumentPickerViewController {
    var exportCoordinator: MediaFilesExportCoordinator?
}

struct MediaFilesExportPicker: UIViewControllerRepresentable {
    let attempt: MediaFilesExportAttempt
    let owner: MediaFilesExportController

    func makeCoordinator() -> MediaFilesExportCoordinator {
        MediaFilesExportCoordinator(attempt: attempt, owner: owner)
    }

    func makeUIViewController(context: Context) -> UIViewController {
        if let failure = context.coordinator.presentationFailure() {
            let coordinator = context.coordinator
            // Leave SwiftUI's update before publishing a failure. No picker is
            // created and no invalid source is handed to a document provider.
            Task { @MainActor in coordinator.finish(failure) }
            return UIViewController()
        }
        let picker = MediaFilesExportDocumentPicker(forExporting: attempt.payload.fileURLs, asCopy: true)
        picker.exportCoordinator = context.coordinator
        picker.delegate = context.coordinator
        picker.presentationController?.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ controller: UIViewController, context: Context) {}

    static func dismantleUIViewController(_ controller: UIViewController, coordinator: MediaFilesExportCoordinator) {
        coordinator.dismantle()
        (controller as? UIDocumentPickerViewController)?.delegate = nil
        controller.presentationController?.delegate = nil
    }
}

private struct MediaFilesExportPresentation: ViewModifier {
    @ObservedObject var controller: MediaFilesExportController

    func body(content: Content) -> some View {
        let presentationID = controller.presentation?.id
        return content.sheet(item: Binding(
            get: { controller.presentation },
            set: { value in
                if case .none = value, let presentationID { controller.dismissPresentation(presentationID) }
            }
        )) { attempt in
            MediaFilesExportPicker(attempt: attempt, owner: controller)
        }
        .onDisappear { controller.retirePendingAdmission() }
    }
}

extension View {
    func mediaFilesExport(_ controller: MediaFilesExportController) -> some View {
        modifier(MediaFilesExportPresentation(controller: controller))
    }
}

struct MediaFilesExportStatusView: View {
    @EnvironmentObject private var language: AppLanguageStore
    @ObservedObject var controller: MediaFilesExportController

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if controller.isActive && controller.presentation == nil && controller.result == nil {
                ProgressView(language.string("media_files_export_recording"))
                    .accessibilityIdentifier("media-files-export-recording")
            }
            if let result = controller.result {
                Text(message(result))
                    .font(.callout)
                    .accessibilityIdentifier("media-files-export-result")
            }
            if controller.historyWarning {
                Text(language.string("media_files_export_history_warning"))
                    .font(.caption).foregroundStyle(Color.cameraSecondaryText)
                    .accessibilityIdentifier("media-files-export-history-warning")
            }
            Text(language.string("media_files_export_help"))
                .font(.caption)
                .foregroundStyle(Color.cameraSecondaryText)
                .accessibilityIdentifier("media-files-export-help")
        }
        .fixedSize(horizontal: false, vertical: true)
    }

    private func message(_ result: MediaFilesExportResult) -> String {
        switch result.status {
        case .providerReported:
            language.format("media_files_export_reported", result.expectedCount)
        case .cancelled:
            language.string("media_files_export_cancelled")
        case .sourceUnavailable:
            language.string("media_files_export_source_unavailable")
        case .historyUnavailable:
            language.string("media_files_export_history_unavailable")
        case .unconfirmed:
            if let count = result.reportedCount {
                language.format("media_files_export_mismatch", count, result.expectedCount)
            } else {
                language.string("media_files_export_unconfirmed")
            }
        }
    }
}
