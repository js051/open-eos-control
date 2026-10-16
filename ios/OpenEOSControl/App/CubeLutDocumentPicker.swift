import SwiftUI
import UniformTypeIdentifiers
import UIKit

struct CubeLutPickerTicket: Identifiable {
    let id: UUID
}

/// Each presented controller retains its own immutable ticket. A delayed
/// delegate callback cannot accidentally borrow a later presentation's owner.
@MainActor
struct CubeLutDocumentPicker: UIViewControllerRepresentable {
    let ticket: CubeLutPickerTicket
    let completion: (UUID, Result<URL?, Error>) -> Void

    func makeCoordinator() -> Coordinator {
        Coordinator(owner: ticket.id, completion: completion)
    }

    func makeUIViewController(context: Context) -> UIDocumentPickerViewController {
        let picker = UIDocumentPickerViewController(
            forOpeningContentTypes: [UTType(filenameExtension: "cube") ?? .plainText, .plainText],
            asCopy: false
        )
        picker.allowsMultipleSelection = false
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ controller: UIDocumentPickerViewController, context: Context) {}

    static func dismantleUIViewController(_ controller: UIDocumentPickerViewController, coordinator: Coordinator) {
        coordinator.finish(.success(nil))
        controller.delegate = nil
    }

    @MainActor
    final class Coordinator: NSObject, UIDocumentPickerDelegate {
        private let owner: UUID
        private let completion: (UUID, Result<URL?, Error>) -> Void
        private var completed = false

        init(owner: UUID, completion: @escaping (UUID, Result<URL?, Error>) -> Void) {
            self.owner = owner
            self.completion = completion
        }

        func documentPicker(_ controller: UIDocumentPickerViewController, didPickDocumentsAt urls: [URL]) {
            finish(.success(urls.first))
        }

        func documentPickerWasCancelled(_ controller: UIDocumentPickerViewController) {
            finish(.success(nil))
        }

        func finish(_ result: Result<URL?, Error>) {
            guard !completed else { return }
            completed = true
            completion(owner, result)
        }
    }
}
