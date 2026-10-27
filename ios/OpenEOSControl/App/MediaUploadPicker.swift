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

/// The gallery owns one UIKit presenter; each opening owns an immutable
/// picker/Coordinator pair. Clearing an opening never removes its presenter.
@MainActor
struct MediaUploadPicker: View {
    let selection: MediaUploadSelection?
    let completion: (MediaUploadSelection, [URL]?) -> Void

    var body: some View { Host(selection: selection, completion: completion) }

    private struct Host: UIViewControllerRepresentable {
        let selection: MediaUploadSelection?
        let completion: (MediaUploadSelection, [URL]?) -> Void
        func makeUIViewController(context: Context) -> Presenter { Presenter() }
        func updateUIViewController(_ controller: Presenter, context: Context) {
            controller.update(selection: selection, completion: completion)
        }
        static func dismantleUIViewController(_ controller: Presenter, coordinator: ()) {
            controller.dismantle()
        }
    }

    final class Presenter: UIViewController {
        /// Injection is limited to the actual public UIKit ownership boundaries.
        /// Tests hold these receipts; they do not pretend to drain DocumentManager.
        @MainActor
        struct Presentation {
            var isReady: (Presenter) -> Bool
            var present: (Presenter, UIDocumentPickerViewController, @escaping () -> Void) -> Void
            var retire: (UIDocumentPickerViewController, PickerFixtureJournal.Ticket, @escaping () -> Void) -> Void
            static let system = Self(
                isReady: { host in
                    guard host.isViewLoaded, host.view.window != nil else { return false }
                    var ancestor: UIViewController? = host
                    while let controller = ancestor {
                        guard controller.presentedViewController == nil,
                              !controller.isBeingPresented, !controller.isBeingDismissed else { return false }
                        ancestor = controller.parent
                    }
                    return true
                },
                present: { host, picker, completion in
                    // Loading a real provider view belongs to real UIKit presentation,
                    // not to a Session whose ownership receipts may be substituted.
                    picker.view.accessibilityIdentifier = "media-upload-document-picker"
                    host.present(picker, animated: true, completion: completion)
                },
                retire: { picker, ticket, completion in
                    MediaUploadPicker.retire(picker, ticket: ticket, completion: completion)
                }
            )
        }

        private struct Opening {
            let selection: MediaUploadSelection
            let completion: (MediaUploadSelection, [URL]?) -> Void
        }
        @MainActor
        final class Session {
            let picker: UIDocumentPickerViewController
            let coordinator: Coordinator
            var presentationCompleted = false
            var retirementRequested = false
            var retirementStarted = false
            var retired = false
            var delivery: (() -> Void)?
            init(selection: MediaUploadSelection, completion: @escaping (MediaUploadSelection, [URL]?) -> Void) {
                coordinator = Coordinator(selection: selection, completion: completion)
                picker = UIDocumentPickerViewController(forOpeningContentTypes: [.data], asCopy: false)
                picker.allowsMultipleSelection = false
                picker.delegate = coordinator
                // Request a non-removing presentation. UIKit may adapt its effective
                // style; the gallery host's lifetime is governed by ownership below.
                picker.modalPresentationStyle = .overFullScreen
                #if DEBUG
                if ProcessInfo.processInfo.environment["OEC_UPLOAD_JOURNEY_FIXTURE"] != nil {
                    picker.directoryURL = try? BridgeUploadJourneyFixture.seedPickerFile().deletingLastPathComponent()
                }
                #endif
                MediaUploadPicker.trace(.controllerCreated, picker, ticket: selection.fixtureTicket)
            }
        }

        private let presentation: Presentation
        private var pending: Opening?
        private(set) var active: Session?
        private var lastRetiredSelection: MediaUploadSelection?
        private var lastFixtureTicket = PickerFixtureJournal.Ticket.disabled
        private var dismantled = false

        init(presentation: Presentation? = nil) {
            self.presentation = presentation ?? .system
            super.init(nibName: nil, bundle: nil)
        }
        required init?(coder: NSCoder) { fatalError("init(coder:) is unavailable") }
        deinit { PickerFixtureJournal.record(.presenterDeinit, ticket: lastFixtureTicket) }
        override func loadView() {
            view = UIView()
            view.backgroundColor = .clear
        }
        override func viewDidAppear(_ animated: Bool) {
            super.viewDidAppear(animated)
            advance()
        }
        override func viewDidDisappear(_ animated: Bool) {
            super.viewDidDisappear(animated)
            if let active {
                MediaUploadPicker.trace(.presenterDisappeared, active.picker,
                    ticket: active.coordinator.selection.fixtureTicket)
            }
        }

        func update(selection: MediaUploadSelection?, completion: @escaping (MediaUploadSelection, [URL]?) -> Void) {
            guard !dismantled else { return }
            if let selection, selection == active?.coordinator.selection { return }
            if let selection, selection == pending?.selection { advance(); return }
            // SwiftUI may update with the consumed ticket before reflecting selection=nil.
            pending = selection.flatMap { $0 == lastRetiredSelection ? nil : Opening(selection: $0, completion: completion) }
            if let active {
                active.coordinator.invalidate()
                requestRetirement(active)
            } else { advance() }
        }

        func dismantle() {
            guard !dismantled else { return }
            dismantled = true
            pending = nil
            if let active {
                MediaUploadPicker.trace(.dismantle, active.picker, ticket: active.coordinator.selection.fixtureTicket)
                active.coordinator.invalidate()
                requestRetirement(active)
            }
        }

        private func advance() {
            guard !dismantled, active == nil, let opening = pending, presentation.isReady(self) else { return }
            pending = nil
            let session = Session(selection: opening.selection, completion: opening.completion)
            active = session
            let ticket = opening.selection.fixtureTicket
            session.coordinator.identityProbe = IdentityProbe(ticket: ticket, requestedHost: self,
                expectedPicker: session.picker)
            lastFixtureTicket = ticket
            session.coordinator.retirement = { [weak self, weak session] completion in
                guard let self, let session else { return }
                self.requestRetirement(session, delivery: completion)
            }
            MediaUploadPicker.trace(.presentationRequested, session.picker, ticket: ticket)
            // UIKit's completion owns this host/session through presentation, including
            // external SwiftUI removal. This is ownership, not a diagnostic delay.
            presentation.present(self, session.picker) { [self, session] in
                guard active === session, !session.presentationCompleted else { return }
                session.presentationCompleted = true
                MediaUploadPicker.trace(.presentationCompleted, session.picker, ticket: ticket,
                    flag: session.picker.presentingViewController != nil)
                session.coordinator.identityProbe?.captureOriginalPresenter(session.picker.presentingViewController)
                session.coordinator.traceIdentity(session.picker)
                if session.retirementRequested { requestRetirement(session) }
            }
        }

        private func requestRetirement(_ session: Session, delivery: (() -> Void)? = nil) {
            guard active === session, !session.retired else { return }
            if let delivery, session.delivery == nil { session.delivery = delivery }
            session.retirementRequested = true
            // Do not overlap dismiss with an in-flight public presentation transition.
            guard session.presentationCompleted, !session.retirementStarted else { return }
            session.retirementStarted = true
            let ticket = session.coordinator.selection.fixtureTicket
            presentation.retire(session.picker, ticket) { [self, session] in
                guard active === session, !session.retired else { return }
                session.retired = true
                lastRetiredSelection = session.coordinator.selection
                active = nil
                let delivery = session.delivery
                session.delivery = nil
                PickerFixtureJournal.record(.sessionRetired, ticket: ticket)
                // This flag describes app ownership only, never remote-service lifetime.
                PickerFixtureJournal.record(.presenterRetained, ticket: ticket, flag: !dismantled)
                delivery?()
                advance()
            }
        }
    }

    private static func retire(_ picker: UIDocumentPickerViewController,
                               ticket: PickerFixtureJournal.Ticket, completion: @escaping () -> Void) {
        let retirement = Retirement(
            isPresented: { [picker] in picker.presentingViewController != nil },
            dismiss: { [picker] done in
                picker.dismiss(animated: true) {
                    PickerFixtureJournal.record(.dismissCompleted, ticket: ticket)
                    done()
                }
            }, completion: completion, fixtureTicket: ticket
        )
        // A cancelled/unregistered transition is not a dismissal receipt.
        if picker.isBeingDismissed, let transition = picker.transitionCoordinator {
            PickerFixtureJournal.record(.retirementObserved, ticket: ticket)
            retirement.observe { done in
                transition.animate(alongsideTransition: nil) { context in
                    PickerFixtureJournal.record(.transitionCompleted, ticket: ticket, flag: context.isCancelled)
                    done(context.isCancelled)
                }
            }
        } else { retirement.dismiss() }
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

    /// Fixture-only, weak observations of public UIKit relationships. A snapshot
    /// is not a remote-service retirement or controller-deallocation receipt.
    @MainActor
    final class IdentityProbe {
        private weak var requestedHost: UIViewController?
        private weak var expectedPicker: UIViewController?
        private weak var originalActualPresenter: UIViewController?
        private var originalPresenterCaptured = false
        private var originalPresenterKnown = false

        init?(ticket: PickerFixtureJournal.Ticket, requestedHost: UIViewController,
              expectedPicker: UIViewController) {
            guard ticket.isEnabled else { return nil }
            self.requestedHost = requestedHost
            self.expectedPicker = expectedPicker
        }

        func captureOriginalPresenter(_ presenter: UIViewController?) {
            guard !originalPresenterCaptured else { return }
            originalPresenterCaptured = true
            originalPresenterKnown = presenter != nil
            originalActualPresenter = presenter
        }

        func snapshot(picker: UIViewController?, actualPresenter: UIViewController?)
            -> PickerFixtureJournal.IdentitySnapshot {
            let host = requestedHost
            var ancestor = host?.parent
            var visited = 0
            var currentIsHostAncestor = false
            while let controller = ancestor, visited < 32 {
                if let actualPresenter, actualPresenter === controller { currentIsHostAncestor = true }
                ancestor = controller.parent
                visited += 1
            }
            return PickerFixtureJournal.IdentitySnapshot(
                pickerMatches: matches(picker, expectedPicker),
                requestedHostAlive: host != nil,
                requestedHostAttached: host?.viewIfLoaded?.window != nil,
                originalPresenterKnown: originalPresenterKnown,
                originalPresenterAlive: originalActualPresenter != nil,
                currentIsRequestedHost: matches(actualPresenter, host),
                currentIsHostAncestor: currentIsHostAncestor,
                ancestryComplete: host != nil && ancestor == nil,
                currentIsOriginalPresenter: matches(actualPresenter, originalActualPresenter)
            )
        }

        private func matches(_ lhs: UIViewController?, _ rhs: UIViewController?) -> Bool {
            guard let lhs, let rhs else { return false }
            return lhs === rhs
        }
    }

    @MainActor
    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        let selection: MediaUploadSelection
        private let fixtureTicket: PickerFixtureJournal.Ticket
        private let completion: (MediaUploadSelection, [URL]?) -> Void
        private var delivered = false
        private var invalidated = false
        private var completed = false
        var identityProbe: IdentityProbe?
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
            traceIdentity(controller)
            defer { PickerFixtureJournal.record(.delegateReturning, ticket: fixtureTicket) }
            finish(urls, controller: controller)
        }
        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
            MediaUploadPicker.trace(.cancelled, controller, ticket: fixtureTicket)
            traceIdentity(controller)
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
                traceIdentity(controller)
            }
        }
        func traceIdentity(_ controller: UIDocumentPickerViewController) {
            guard let identityProbe else { return }
            let actualPresenter = controller.presentingViewController
            PickerFixtureJournal.recordIdentity(identityProbe.snapshot(picker: controller, actualPresenter: actualPresenter),
                ticket: fixtureTicket, presented: actualPresenter != nil,
                transitioning: controller.transitionCoordinator != nil, dismissing: controller.isBeingDismissed)
        }
    }
}
