import Foundation
import OpenEOSCore
import Darwin

/// Camera IDs are opaque wire values. Swift String equality normalizes Unicode.
func mediaItemIdentity(_ item: CameraMediaItem) -> Data { Data(item.id.utf8) }

enum MediaBatchState: String {
    case idle, running, completed, failed, cancelled
}

enum MediaBatchItemStatus: String {
    case notStarted, downloading, ready, failed, cancelledOrUnknown
}

struct MediaBatchResult: Identifiable {
    let id: UUID
    let item: CameraMediaItem
    var status: MediaBatchItemStatus = .notStarted
    var errorMessage: String?
    var ownedFile: MediaBatchOwnedFile?

    init(item: CameraMediaItem) {
        id = UUID()
        self.item = item
    }

    var completedFileURL: URL? { status == .ready ? ownedFile?.fileURL : nil }
}

/// A presentation keeps this immutable snapshot until the system exporter ends.
/// Resetting the camera or preparing another batch cannot delete its source files.
struct MediaBatchExportPayload: Identifiable {
    let id = UUID()
    let fileURLs: [URL]
    private let ownedFiles: [MediaBatchOwnedFile]
    private let storedFiles: [MediaDeliveryFileLease]

    init?(results: [MediaBatchResult]) {
        let files = results.compactMap { result in
            result.status == .ready ? result.ownedFile : nil
        }
        guard !files.isEmpty else { return nil }
        ownedFiles = files
        storedFiles = []
        fileURLs = files.map(\.fileURL)
    }

    init(ownedFile: MediaBatchOwnedFile) {
        ownedFiles = [ownedFile]
        storedFiles = []
        fileURLs = [ownedFile.fileURL]
    }

    init?(storedFiles: [MediaDeliveryFileLease]) {
        guard !storedFiles.isEmpty else { return nil }
        ownedFiles = []
        self.storedFiles = storedFiles
        fileURLs = storedFiles.map(\.fileURL)
    }
}

/// Each lease can remove only its own freshly created UUID directory. No caller
/// can adopt an arbitrary URL or ask this object to clean a parent directory.
final class MediaBatchOwnedFile: @unchecked Sendable {
    let fileURL: URL
    private let directory: URL

    private init(directory: URL, filename: String) {
        self.directory = directory
        fileURL = directory.appendingPathComponent(filename, isDirectory: false)
    }

    static func create(filename: String, directoryID: UUID = UUID()) throws -> MediaBatchOwnedFile {
        let root = FileManager.default.temporaryDirectory.resolvingSymlinksInPath()
            .appendingPathComponent("OpenEOSControl", isDirectory: true)
            .appendingPathComponent("BatchMedia", isDirectory: true)
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        let directory = root.appendingPathComponent(directoryID.uuidString, isDirectory: true)
        // mkdir atomically rejects EEXIST, including an existing directory or
        // symlink. FileManager.createDirectory may accept an existing directory.
        let created = directory.path.withCString { Darwin.mkdir($0, mode_t(S_IRWXU)) }
        guard created == 0 else {
            throw POSIXError(POSIXErrorCode(rawValue: errno) ?? .EIO)
        }
        return MediaBatchOwnedFile(directory: directory, filename: safeFilename(filename))
    }

    static func safeFilename(_ value: String) -> String {
        let basename = value.split(whereSeparator: { $0 == "/" || $0 == "\\" }).last.map(String.init) ?? ""
        let forbidden = CharacterSet.controlCharacters.union(CharacterSet(charactersIn: ":/\\"))
        let cleaned = String(basename.unicodeScalars.map { forbidden.contains($0) ? "_" : String($0) }.joined())
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleaned.isEmpty, cleaned != ".", cleaned != ".." else { return "original-media" }
        // Bound UTF-8 bytes, not character count, to stay within filesystem limits.
        var limited = ""
        for character in cleaned {
            let next = String(character)
            guard limited.utf8.count + next.utf8.count <= 200 else { break }
            limited += next
        }
        return limited.isEmpty ? "original-media" : limited
    }

    deinit { try? FileManager.default.removeItem(at: directory) }
}
