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
        return picker
    }
    func updateUIViewController(_ controller: UIDocumentPickerViewController, context: Context) {}

    @MainActor
    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let selection: MediaUploadSelection
        private let completion: (MediaUploadSelection, [URL]?) -> Void
        private var delivered = false
        init(selection: MediaUploadSelection, completion: @escaping (MediaUploadSelection, [URL]?) -> Void) {
            self.selection = selection
            self.completion = completion
        }
        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) { finish(urls) }
        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) { finish(nil) }
        private func finish(_ urls: [URL]?) {
            guard !delivered else { return }
            delivered = true
            completion(selection, urls)
        }
    }
}
