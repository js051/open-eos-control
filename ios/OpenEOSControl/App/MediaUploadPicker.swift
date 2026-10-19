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
    func makeUIViewController(context: Context) -> Presenter {
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
        return Presenter(picker: picker, coordinator: context.coordinator)
    }
    func updateUIViewController(_ controller: Presenter, context: Context) {}
    static func dismantleUIViewController(_ controller: Presenter, coordinator: Coordinator) {
        trace(.dismantle, controller.picker)
        coordinator.invalidate()
        controller.retire()
    }

    /// UIKit owns a real modal presentation instead of embedding the document
    /// service controller as SwiftUI sheet content. Keep its owner alive through
    /// dismissal, then deliver the immutable ticket and let SwiftUI close us.
    final class Presenter: UIViewController {
        let picker: UIDocumentPickerViewController
        private let coordinator: Coordinator
        private var presentedPicker = false
        private var retired = false

        init(picker: UIDocumentPickerViewController, coordinator: Coordinator) {
            self.picker = picker
            self.coordinator = coordinator
            super.init(nibName: nil, bundle: nil)
            coordinator.retirement = { [weak self] completion in
                self?.retire(completion: completion)
            }
        }
        required init?(coder: NSCoder) { fatalError("init(coder:) is unavailable") }
        override func viewDidAppear(_ animated: Bool) {
            super.viewDidAppear(animated)
            guard !presentedPicker, !retired else { return }
            presentedPicker = true
            // Keep the SwiftUI ticket host visible in the hierarchy; covering
            // it must not trigger its external-removal cancellation boundary.
            picker.modalPresentationStyle = .overFullScreen
            present(picker, animated: true)
        }
        func retire(completion: (() -> Void)? = nil) {
            retired = true
            let retirement = Retirement(
                isPresented: { [picker] in picker.presentingViewController != nil },
                dismiss: { [picker] done in picker.dismiss(animated: true, completion: done) },
                completion: { completion?() }
            )
            // A provider may already be dismissing when it calls the delegate.
            // A cancelled/unregistered transition is not a dismissal receipt.
            if picker.isBeingDismissed, let transition = picker.transitionCoordinator {
                retirement.observe { done in
                    transition.animate(alongsideTransition: nil) { context in done(context.isCancelled) }
                }
            } else {
                retirement.dismiss()
            }
        }
    }

    /// The same decision boundary is exercised with held transition receipts in
    /// tests. A callback alone is insufficient: the modal relationship must end.
    final class Retirement {
        private let isPresented: () -> Bool
        private let requestDismiss: (@escaping () -> Void) -> Void
        private let completion: () -> Void
        private var dismissRequested = false
        private var completed = false
        init(isPresented: @escaping () -> Bool,
             dismiss: @escaping (@escaping () -> Void) -> Void,
             completion: @escaping () -> Void) {
            self.isPresented = isPresented
            self.requestDismiss = dismiss
            self.completion = completion
        }
        func observe(_ register: (@escaping (Bool) -> Void) -> Bool) {
            let registered = register { [self] cancelled in
                if cancelled || isPresented() { dismiss() }
                else { finish() }
            }
            if !registered { dismiss() }
        }
        func dismiss() {
            guard !completed, !dismissRequested else { return }
            guard isPresented() else { finish(); return }
            dismissRequested = true
            requestDismiss { [self] in
                guard !isPresented() else { return }
                finish()
            }
        }
        private func finish() {
            guard !completed else { return }
            completed = true
            completion()
        }
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
        private var invalidated = false
        private var completed = false
        var retirement: (@escaping () -> Void) -> Void = { $0() }
        func invalidate() { invalidated = true }
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
            guard !delivered, !invalidated else { return }
            delivered = true
            retirement { [self, controller] in
                guard !invalidated, !completed else { return }
                completed = true
                MediaUploadPicker.trace(.completionBegin, controller)
                completion(selection, urls)
                MediaUploadPicker.trace(.completionEnd, controller)
            }
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
