import Foundation
import OpenEOSCore
import SwiftUI
import UIKit
import UniformTypeIdentifiers

struct MediaUploadSelection: Identifiable, Equatable {
    let id = UUID()
    let owner: UUID
}

struct MediaUploadResult {
    let attemptID: UUID
    let owner: UUID
    let filename: String
    let outcome: CameraMediaUploadOutcome
    let receipt: CameraMediaItem?
    var refreshFailed = false
}

/// The granted URL is acquired only after one-shot, opening-owner admission.
/// Tests inject counters through the same production lifecycle boundary.
struct MediaUploadFileAccess {
    var acquire: (URL) -> Bool
    var release: (URL) -> Void
    static let securityScoped = Self(
        acquire: { $0.startAccessingSecurityScopedResource() },
        release: { $0.stopAccessingSecurityScopedResource() }
    )
    static let unscoped = Self(acquire: { _ in true }, release: { _ in })
}

/// A controller/Coordinator captures an immutable opening ticket. SwiftUI may
/// rebuild its view or present a second picker before an old OS callback arrives.
/// Updating a coordinator to the newest closure would silently redirect the file.
@MainActor
struct MediaUploadPicker: UIViewControllerRepresentable {
    let selection: MediaUploadSelection
    let completion: (MediaUploadSelection, [URL]?) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(selection: selection, completion: completion) }
    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.data], asCopy: false)
        picker.allowsMultipleSelection = false
        picker.delegate = context.coordinator
        #if DEBUG
        if ProcessInfo.processInfo.environment["OEC_UPLOAD_JOURNEY_FIXTURE"] != nil {
            picker.directoryURL = try? BridgeUploadJourneyFixture.seedPickerFile().deletingLastPathComponent()
        }
        #endif
        picker.view.accessibilityIdentifier = "media-upload-document-picker"
        Self.trace(.controllerCreated, picker)
        return picker
    }
    func updateUIViewController(_ controller: UIDocumentPickerViewController, context: Context) {}
    static func dismantleUIViewController(_ controller: UIDocumentPickerViewController, coordinator: Coordinator) {
        trace(.dismantle, controller)
    }

    private static func trace(_ stage: PickerFixtureJournal.Stage, _ controller: UIDocumentPickerViewController) {
        PickerFixtureJournal.record(stage, presented: controller.presentingViewController != nil,
            transitioning: controller.transitionCoordinator != nil, dismissing: controller.isBeingDismissed)
    }

    @MainActor
    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let selection: MediaUploadSelection
        private let completion: (MediaUploadSelection, [URL]?) -> Void
        private var delivered = false
        init(selection: MediaUploadSelection, completion: @escaping (MediaUploadSelection, [URL]?) -> Void) {
            self.selection = selection
            self.completion = completion
            PickerFixtureJournal.record(.coordinatorCreated)
        }
        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            MediaUploadPicker.trace(.selected, controller)
            finish(urls, controller: controller)
        }
        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
            MediaUploadPicker.trace(.cancelled, controller)
            finish(nil, controller: controller)
        }
        private func finish(_ urls: [URL]?, controller: UIDocumentPickerViewController) {
            guard !delivered else { return }
            delivered = true
            MediaUploadPicker.trace(.completionBegin, controller)
            completion(selection, urls)
            MediaUploadPicker.trace(.completionEnd, controller)
        }
    }
}

/// Fixed synthetic stages only. Ordinary builds never write this journal.
private enum PickerFixtureJournal {
    enum Stage: String {
        case coordinatorCreated = "coordinator-created"
        case controllerCreated = "controller-created"
        case selected, cancelled, dismantle
        case completionBegin = "completion-begin"
        case completionEnd = "completion-end"
    }
    static func record(_ stage: Stage, presented: Bool = false,
                       transitioning: Bool = false, dismissing: Bool = false) {
        #if DEBUG && OEC_REQUIRE_SIMULATOR_E2E
        guard let scenario = ProcessInfo.processInfo.environment["OEC_UPLOAD_JOURNEY_FIXTURE"],
              ["success", "cancel", "lost-ack"].contains(scenario),
              let cache = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask).first else { return }
        let url = cache.appendingPathComponent("eos-picker-lifecycle.txt")
        do {
            let old: Data
            if FileManager.default.fileExists(atPath: url.path) {
                let values = try url.resourceValues(forKeys: [.isSymbolicLinkKey, .fileSizeKey])
                guard values.isSymbolicLink != true, (values.fileSize ?? Int.max) <= 16_384 else { return }
                old = try Data(contentsOf: url)
            } else { old = Data() }
            guard let previous = String(data: old, encoding: .utf8) else { return }
            var lines = previous.split(separator: "\n").map(String.init)
            lines.append("\(scenario) \(stage.rawValue) presented=\(presented ? 1 : 0) transitioning=\(transitioning ? 1 : 0) dismissing=\(dismissing ? 1 : 0)")
            let bytes = Data((lines.suffix(128).joined(separator: "\n") + "\n").utf8)
            guard bytes.count <= 16_384 else { return }
            try bytes.write(to: url, options: .atomic)
        } catch { /* Diagnostic failure cannot alter picker admission or outcomes. */ }
        #endif
    }
}
