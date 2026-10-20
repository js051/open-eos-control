import Foundation
import OpenEOSCore
import SwiftUI
import UIKit
import UniformTypeIdentifiers

struct MediaUploadSelection: Identifiable, Equatable {
    let id = UUID()
    let owner: UUID
    let fixtureTicket = PickerFixtureJournal.makeTicket()
    // Observation must not participate in the original admission identity.
    static func == (lhs: Self, rhs: Self) -> Bool {
        lhs.id == rhs.id && lhs.owner == rhs.owner
    }
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
        Self.trace(.controllerCreated, picker, ticket: selection.fixtureTicket)
        return Presenter(picker: picker, coordinator: context.coordinator)
    }
    func updateUIViewController(_ controller: Presenter, context: Context) {}
    static func dismantleUIViewController(_ controller: Presenter, coordinator: Coordinator) {
        trace(.dismantle, controller.picker, ticket: coordinator.selection.fixtureTicket)
        coordinator.invalidate()
        controller.retire()
    }

    /// UIKit owns a real modal presentation instead of embedding the document
    /// service controller as SwiftUI sheet content. Keep its owner alive through
    /// dismissal, then deliver the immutable ticket and let SwiftUI close us.
    final class Presenter: UIViewController {
        let picker: UIDocumentPickerViewController
        private let coordinator: Coordinator
        private let fixtureTicket: PickerFixtureJournal.Ticket
        private var presentedPicker = false
        private var retired = false

        init(picker: UIDocumentPickerViewController, coordinator: Coordinator) {
            self.picker = picker
            self.coordinator = coordinator
            fixtureTicket = coordinator.selection.fixtureTicket
            super.init(nibName: nil, bundle: nil)
            coordinator.retirement = { [weak self] completion in
                self?.retire(completion: completion)
            }
        }
        required init?(coder: NSCoder) { fatalError("init(coder:) is unavailable") }
        deinit { PickerFixtureJournal.record(.presenterDeinit, ticket: fixtureTicket) }
        override func viewDidDisappear(_ animated: Bool) {
            super.viewDidDisappear(animated)
            MediaUploadPicker.trace(.presenterDisappeared, picker, ticket: fixtureTicket)
        }
        override func viewDidAppear(_ animated: Bool) {
            super.viewDidAppear(animated)
            guard !presentedPicker, !retired else { return }
            presentedPicker = true
            // Keep the SwiftUI ticket host visible in the hierarchy; covering
            // it must not trigger its external-removal cancellation boundary.
            picker.modalPresentationStyle = .overFullScreen
            #if DEBUG && OEC_REQUIRE_SIMULATOR_E2E
            let ticket = fixtureTicket
            if ticket.isEnabled {
                MediaUploadPicker.trace(.presentationRequested, picker, ticket: ticket)
                // Observers are weak; a diagnostic completion must not extend owner lifetime.
                present(picker, animated: true) { [weak self, weak picker] in
                    guard let picker else {
                        PickerFixtureJournal.record(.presentationCompleted, ticket: ticket)
                        return
                    }
                    MediaUploadPicker.trace(.presentationCompleted, picker, ticket: ticket,
                        flag: self?.presentedViewController === picker)
                }
                return
            }
            #endif
            present(picker, animated: true)
        }
        func retire(completion: (() -> Void)? = nil) {
            retired = true
            let retirement = Retirement(
                isPresented: { [picker] in picker.presentingViewController != nil },
                dismiss: { [picker, fixtureTicket] done in
                    guard fixtureTicket.isEnabled else { picker.dismiss(animated: true, completion: done); return }
                    picker.dismiss(animated: true) {
                        PickerFixtureJournal.record(.dismissCompleted, ticket: fixtureTicket)
                        done()
                    }
                },
                completion: { completion?() }, fixtureTicket: fixtureTicket
            )
            // A provider may already be dismissing when it calls the delegate.
            // A cancelled/unregistered transition is not a dismissal receipt.
            if picker.isBeingDismissed, let transition = picker.transitionCoordinator {
                PickerFixtureJournal.record(.retirementObserved, ticket: fixtureTicket)
                retirement.observe { [fixtureTicket] done in
                    transition.animate(alongsideTransition: nil) { context in
                        PickerFixtureJournal.record(.transitionCompleted, ticket: fixtureTicket, flag: context.isCancelled)
                        done(context.isCancelled)
                    }
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
        private let fixtureTicket: PickerFixtureJournal.Ticket
        private var dismissRequested = false
        private var completed = false
        init(isPresented: @escaping () -> Bool,
             dismiss: @escaping (@escaping () -> Void) -> Void,
             completion: @escaping () -> Void, fixtureTicket: PickerFixtureJournal.Ticket = .disabled) {
            self.isPresented = isPresented
            self.requestDismiss = dismiss
            self.completion = completion
            self.fixtureTicket = fixtureTicket
        }
        func observe(_ register: (@escaping (Bool) -> Void) -> Bool) {
            let registered = register { [self] cancelled in
                if cancelled || isPresented() { dismiss() }
                else { finish() }
            }
            PickerFixtureJournal.record(.transitionRegistered, ticket: fixtureTicket, flag: registered)
            if !registered { dismiss() }
        }
        func dismiss() {
            guard !completed, !dismissRequested else { return }
            guard isPresented() else {
                PickerFixtureJournal.record(.retirementNoModal, ticket: fixtureTicket)
                finish(); return
            }
            PickerFixtureJournal.record(.retirementDismiss, ticket: fixtureTicket)
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

    private static func trace(_ stage: PickerFixtureJournal.Stage, _ controller: UIDocumentPickerViewController,
                              ticket: PickerFixtureJournal.Ticket, flag: Bool = false) {
        PickerFixtureJournal.record(stage, ticket: ticket, presented: controller.presentingViewController != nil,
            transitioning: controller.transitionCoordinator != nil, dismissing: controller.isBeingDismissed, flag: flag)
    }

    @MainActor
    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let selection: MediaUploadSelection
        private let fixtureTicket: PickerFixtureJournal.Ticket
        private let completion: (MediaUploadSelection, [URL]?) -> Void
        private var delivered = false
        private var invalidated = false
        private var completed = false
        var retirement: (@escaping () -> Void) -> Void = { $0() }
        func invalidate() { invalidated = true }
        deinit { PickerFixtureJournal.record(.coordinatorDeinit, ticket: fixtureTicket) }
        init(selection: MediaUploadSelection, completion: @escaping (MediaUploadSelection, [URL]?) -> Void) {
            self.selection = selection
            fixtureTicket = selection.fixtureTicket
            self.completion = completion
            PickerFixtureJournal.record(.coordinatorCreated, ticket: fixtureTicket)
        }
        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            MediaUploadPicker.trace(.selected, controller, ticket: fixtureTicket)
            defer { PickerFixtureJournal.record(.delegateReturning, ticket: fixtureTicket) }
            finish(urls, controller: controller)
        }
        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
            MediaUploadPicker.trace(.cancelled, controller, ticket: fixtureTicket)
            defer { PickerFixtureJournal.record(.delegateReturning, ticket: fixtureTicket) }
            finish(nil, controller: controller)
        }
        private func finish(_ urls: [URL]?, controller: UIDocumentPickerViewController) {
            guard !delivered, !invalidated else { return }
            delivered = true
            retirement { [self, controller] in
                guard !invalidated, !completed else { return }
                completed = true
                MediaUploadPicker.trace(.completionBegin, controller, ticket: fixtureTicket)
                completion(selection, urls)
                MediaUploadPicker.trace(.completionEnd, controller, ticket: fixtureTicket)
            }
        }
    }
}
