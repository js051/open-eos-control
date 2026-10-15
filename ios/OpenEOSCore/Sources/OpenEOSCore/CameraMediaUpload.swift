import Foundation

/// Evidence for one file submission. A list observation is deliberately absent.
public enum CameraMediaUploadOutcome: String, Sendable, Equatable {
    case notSent = "not-sent"
    case uncertain
    case verified = "confirmed"
}

public struct CameraMediaUploadEvidence: Sendable {
    public let outcome: CameraMediaUploadOutcome
    public let receipt: CameraMediaItem?
}

/// Synchronous boundary shared by the caller and transport. Cancelling before
/// dispatch prevents entry; cancelling afterward cannot prove camera rollback.
public final class CameraMediaUploadAttempt: @unchecked Sendable {
    public let id = UUID()
    private let lock = NSLock()
    private var cancelled = false
    private var dispatched = false
    private var verifiedItem: CameraMediaItem?
    public init() {}
    public func cancel() { lock.lock(); cancelled = true; lock.unlock() }
    public var evidence: CameraMediaUploadEvidence {
        lock.lock(); defer { lock.unlock() }
        return CameraMediaUploadEvidence(
            outcome: CameraMediaUploadPolicy.outcome(dispatched: dispatched, verifiedReceipt: verifiedItem != nil),
            receipt: verifiedItem)
    }
    public var receipt: CameraMediaItem? { evidence.receipt }
    public var outcome: CameraMediaUploadOutcome { evidence.outcome }
    func markDispatched() throws {
        lock.lock(); defer { lock.unlock() }
        guard !cancelled, !dispatched else { throw CancellationError() }
        dispatched = true
    }
    func recordVerified(_ item: CameraMediaItem) {
        lock.lock(); defer { lock.unlock() }
        guard dispatched, verifiedItem == nil else { return }
        // A valid response belongs to this invocation even after user cancellation.
        verifiedItem = item
    }
}

/// Receipt fields are checked separately from the lenient media-list parser.
/// This is server response verification, not a content checksum.
enum CameraMediaUploadReceipt {
    static func matchesFilename(_ lhs: String, _ rhs: String) -> Bool {
        func asciiFold(_ value: String) -> [UInt8] {
            value.utf8.map { (65...90).contains($0) ? $0 + 32 : $0 }
        }
        return asciiFold(lhs) == asciiFold(rhs)
    }

    static func decode(
        _ response: CameraHTTPUploadResponse, filename: String, size: Int64,
        responseAttemptID: UUID, expectedAttemptID: UUID
    ) throws -> [String: Any] {
        guard responseAttemptID == expectedAttemptID, response.statusCode == 201, !filename.isEmpty, size > 0, size <= 4_294_967_295,
              response.body.count <= 512 * 1024 else { throw invalid() }
        let object = try decodeForegroundJSONObject(response.body)
        guard let id = object["id"] as? String, !id.isEmpty,
              let kind = object["kind"] as? String, !kind.isEmpty,
              let name = object["name"] as? String, !name.isEmpty, matchesFilename(name, filename) else { throw invalid() }
        // NSNumber can collapse 7.0/7e0 to an integer. Inspect the original token.
        var scanner = try RootTokens(response.body)
        guard let token = try scanner.token(named: "sizeBytes"),
              !token.isEmpty, token.allSatisfy({ (48...57).contains($0) }),
              let declared = Int64(String(decoding: token, as: UTF8.self)), declared == size else { throw invalid() }
        return object
    }

    static func mediaItem(
        _ response: CameraHTTPUploadResponse, filename: String, size: Int64,
        responseAttemptID: UUID, expectedAttemptID: UUID
    ) throws -> CameraMediaItem {
        let object = try decode(response, filename: filename, size: size,
            responseAttemptID: responseAttemptID, expectedAttemptID: expectedAttemptID)
        // Do not run the lenient list parser (which trims opaque identifiers)
        // after this strict verifier. The receipt preserves exact required fields;
        // optional gallery metadata belongs to a separate current-owner read.
        return CameraMediaItem(id: object["id"] as! String, name: object["name"] as! String,
            kind: object["kind"] as! String, sizeBytes: size)
    }

    private static func invalid() -> DesktopBridgeError {
        .invalidResponse("Desktop Bridge upload verification failed: no valid receipt for this file.")
    }

    /// JSON grammar and duplicate keys are already validated above. Walk only
    /// bounded validated tokens; never search nested members or quoted text.
    private struct RootTokens {
        let bytes: [UInt8]
        var index = 0
        init(_ data: Data) throws {
            guard var text = String(data: data, encoding: .utf8) else { throw invalid() }
            if text.unicodeScalars.first?.value == 0xFEFF { text.removeFirst() }
            bytes = Array(text.utf8)
        }
        mutating func whitespace() { while index < bytes.count && [9, 10, 13, 32].contains(bytes[index]) { index += 1 } }
        mutating func string() throws -> Range<Int> {
            whitespace(); let start = index
            guard index < bytes.count, bytes[index] == 34 else { throw invalid() }
            index += 1
            while index < bytes.count {
                let byte = bytes[index]; index += 1
                if byte == 34 { return start..<index }
                if byte == 92 { index += 1 }
            }
            throw invalid()
        }
        mutating func value(depth: Int = 0) throws {
            whitespace()
            guard depth <= 512, index < bytes.count else { throw invalid() }
            if bytes[index] == 34 { _ = try string(); return }
            if bytes[index] == 123 || bytes[index] == 91 {
                let close: UInt8 = bytes[index] == 123 ? 125 : 93
                index += 1; whitespace()
                while index < bytes.count && bytes[index] != close {
                    if bytes[index] == 44 || bytes[index] == 58 { index += 1 }
                    else { try value(depth: depth + 1) }
                    whitespace()
                }
                guard index < bytes.count else { throw invalid() }; index += 1
            } else {
                while index < bytes.count && ![9, 10, 13, 32, 44, 93, 125].contains(bytes[index]) { index += 1 }
            }
        }
        mutating func token(named target: String) throws -> [UInt8]? {
            whitespace(); guard index < bytes.count, bytes[index] == 123 else { throw invalid() }
            index += 1; whitespace()
            while index < bytes.count && bytes[index] != 125 {
                let keyToken = try string()
                let key = try JSONSerialization.jsonObject(with: Data(bytes[keyToken]), options: [.fragmentsAllowed]) as? String
                whitespace(); index += 1; whitespace()
                let start = index; try value()
                if key == target { return Array(bytes[start..<index]) }
                whitespace(); if index < bytes.count && bytes[index] == 44 { index += 1 }
            }
            return nil
        }
    }
}

/// Shared admission/publication decisions used by the app, independent of UUID
/// representation. Availability is consumed by the admitting owner exactly once.
public enum CameraMediaUploadPolicy {
    public static func outcome(dispatched: Bool, verifiedReceipt: Bool) -> CameraMediaUploadOutcome {
        verifiedReceipt ? .verified : (dispatched ? .uncertain : .notSent)
    }
    public static func owns<Owner: Equatable, Token: Equatable>(
        owner: Owner, currentOwner: Owner?, token: Token, currentToken: Token?
    ) -> Bool { owner == currentOwner && token == currentToken }
    public static func admits<Owner: Equatable, Token: Equatable>(
        owner: Owner, currentOwner: Owner?, token: Token, currentToken: Token?,
        singleUseAvailable: Bool, supported: Bool, busy: Bool, sourceSelected: Bool
    ) -> Bool {
        owns(owner: owner, currentOwner: currentOwner, token: token, currentToken: currentToken)
            && singleUseAvailable && supported && !busy && sourceSelected
    }
}

/// A private immutable copy is fully staged before any HTTP upload is entered.
/// Its lifetime is independent of the provider URL and ends after transport settles.
final class CameraMediaUploadStagedFile {
    let directory: URL
    let url: URL
    let size: Int64
    private init(directory: URL, url: URL, size: Int64) { self.directory = directory; self.url = url; self.size = size }
    deinit { try? FileManager.default.removeItem(at: directory) }
    static func create(from source: URL) throws -> CameraMediaUploadStagedFile {
        try Task.checkCancellation()
        let values = try source.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey])
        guard values.isRegularFile == true, values.isSymbolicLink != true,
              let expected = values.fileSize, expected > 0, Int64(expected) <= 4_294_967_295 else {
            throw DesktopBridgeError.invalidResponse("The selected upload is not a supported regular file.")
        }
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("oec-upload-" + UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false)
        do {
            let url = directory.appendingPathComponent(source.lastPathComponent)
            guard FileManager.default.createFile(atPath: url.path, contents: nil) else { throw CocoaError(.fileWriteUnknown) }
            let input = try FileHandle(forReadingFrom: source)
            defer { try? input.close() }
            let output = try FileHandle(forWritingTo: url)
            defer { try? output.close() }
            var count: Int64 = 0
            while true {
                try Task.checkCancellation()
                let bytes = try input.read(upToCount: 1024 * 1024) ?? Data()
                if bytes.isEmpty { break }
                count += Int64(bytes.count)
                guard count <= Int64(expected) else { throw CocoaError(.fileReadCorruptFile) }
                try output.write(contentsOf: bytes)
            }
            guard count == Int64(expected) else { throw CocoaError(.fileReadCorruptFile) }
            try output.synchronize()
            try Task.checkCancellation()
            return CameraMediaUploadStagedFile(directory: directory, url: url, size: count)
        } catch { try? FileManager.default.removeItem(at: directory); throw error }
    }
}
