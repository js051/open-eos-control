import Foundation
import SwiftUI
import UIKit
import UniformTypeIdentifiers

struct ForegroundFolderSelection: Identifiable {
    let id = UUID()
    let generation: UUID
}

/// Selection grants only this run's folder access. Dismissal, backgrounding and
/// reconnection invalidate the token; a late delegate cannot silently start.
@MainActor
final class ForegroundFolderCoordinator: NSObject, UIDocumentPickerDelegate, UIAdaptivePresentationControllerDelegate {
    private let selectionID: UUID
    private weak var camera: CameraAppState?
    private var settled = false

    init(selectionID: UUID, camera: CameraAppState) {
        self.selectionID = selectionID
        self.camera = camera
        super.init()
    }

    func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
        guard !settled else { return }
        settled = true
        if urls.count == 1, let folder = urls.first {
            _ = camera?.acceptForegroundFolder(folder, selectionID: selectionID)
        }
        camera?.cancelForegroundFolder(selectionID)
    }

    func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) { cancel() }
    func presentationControllerDidDismiss(_ presentationController: UIPresentationController) { cancel() }
    func cancel() {
        guard !settled else { return }
        settled = true
        camera?.cancelForegroundFolder(selectionID)
    }
}

struct ForegroundFolderPicker: UIViewControllerRepresentable {
    let selection: ForegroundFolderSelection
    let camera: CameraAppState

    @MainActor
    static func presentationBinding(camera: CameraAppState) -> Binding<ForegroundFolderSelection?> {
        let presentedID = camera.foregroundFolderSelection?.id
        return Binding(get: { camera.foregroundFolderSelection }, set: { value in
            // SwiftUI may deliver a dismissal after background/re-entry. The
            // old binding must never cancel a replacement picker selection.
            if value == nil, let presentedID { camera.cancelForegroundFolder(presentedID) }
        })
    }

    func makeCoordinator() -> ForegroundFolderCoordinator {
        ForegroundFolderCoordinator(selectionID: selection.id, camera: camera)
    }

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(forOpeningContentTypes: [.folder], asCopy: false)
        picker.allowsMultipleSelection = false
        picker.delegate = context.coordinator
        picker.presentationController?.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ controller: UIDocumentPickerViewController, context: Context) {}

    static func dismantleUIViewController(_ controller: UIDocumentPickerViewController,
                                         coordinator: ForegroundFolderCoordinator) {
        coordinator.cancel()
        controller.delegate = nil
        controller.presentationController?.delegate = nil
    }
}
