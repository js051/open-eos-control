import Foundation

/// Allocated when a file selection is accepted, before scheduling async work.
struct CubeLutImportSelection: Sendable {
    let owner: UUID
    let url: URL
}

/// One physical reader and one latest pending slot, rather than an actor queue
/// of file reads. Replaced pending work is resumed without opening its URL.
/// Cancellation is cooperative: an OS read already in progress may return first.
final class CubeLutFileReader: @unchecked Sendable {
    typealias Read = @Sendable (URL, @Sendable () throws -> Void) throws -> CubeLut
    private final class Request: @unchecked Sendable {
        let url: URL
        var continuation: CheckedContinuation<CubeLut, Error>?
        var cancelled = false
        init(url: URL) { self.url = url }
    }

    // All request/pump state, including request fields, is protected by lock.
    private let lock = NSLock()
    private var active: Request?
    private var pending: Request?
    private var pumping = false
    private let read: Read

    init() { self.read = Self.readFile }
    init(read: @escaping Read) { self.read = read }

    /// Resource accounting for stopping UI and focused tests; no URL is exposed.
    var bufferedRequestCounts: (active: Int, pending: Int, retiring: Int) {
        lock.lock()
        defer { lock.unlock() }
        return (active == nil ? 0 : 1, pending == nil ? 0 : 1, active?.cancelled == true ? 1 : 0)
    }

    func load(_ url: URL) async throws -> CubeLut {
        let request = Request(url: url)
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                enqueue(request, continuation: continuation)
            }
        } onCancel: {
            self.cancel(request)
        }
    }

    private func enqueue(_ request: Request, continuation: CheckedContinuation<CubeLut, Error>) {
        lock.lock()
        if request.cancelled {
            lock.unlock()
            continuation.resume(throwing: CancellationError())
            return
        }
        request.continuation = continuation
        let replaced = pending?.continuation
        pending?.continuation = nil
        pending?.cancelled = true
        pending = request
        let start = !pumping
        pumping = true
        lock.unlock()
        replaced?.resume(throwing: CancellationError())
        if start { Task.detached(priority: .userInitiated) { self.pump() } }
    }

    private func cancel(_ request: Request) {
        lock.lock()
        request.cancelled = true
        let continuation: CheckedContinuation<CubeLut, Error>?
        if pending === request {
            pending = nil
            continuation = request.continuation
            request.continuation = nil
        } else {
            // The active request is drained by the physical reader.
            continuation = nil
        }
        lock.unlock()
        continuation?.resume(throwing: CancellationError())
    }

    private func checkCancellation(_ request: Request) throws {
        lock.lock()
        let cancelled = request.cancelled
        lock.unlock()
        if cancelled { throw CancellationError() }
    }

    private func pump() {
        while true {
            lock.lock()
            guard let request = pending else {
                pumping = false
                lock.unlock()
                return
            }
            pending = nil
            active = request
            lock.unlock()
            let result = Result {
                try self.checkCancellation(request)
                let lut = try read(request.url) { try self.checkCancellation(request) }
                try self.checkCancellation(request)
                return lut
            }
            lock.lock()
            active = nil
            let continuation = request.continuation
            request.continuation = nil
            let cancelled = request.cancelled
            lock.unlock()
            if cancelled { continuation?.resume(throwing: CancellationError()) }
            else { continuation?.resume(with: result) }
        }
    }

    private static func readFile(_ url: URL, checkCancellation: @Sendable () throws -> Void) throws -> CubeLut {
        try checkCancellation()
        let accessed = url.startAccessingSecurityScopedResource()
        defer { if accessed { url.stopAccessingSecurityScopedResource() } }
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        let text = try readBoundedCubeLutText(read: { count in
            try handle.read(upToCount: count) ?? Data()
        }, checkCancellation: checkCancellation)
        try checkCancellation()
        let lut = try parseCubeLut(text, fallbackName: url.lastPathComponent)
        try checkCancellation()
        return lut
    }
}

/// No size hint is trusted. Unknown-size/growing files use bounded chunks and
/// at most one extra byte to detect overflow. The limit includes any UTF-8 BOM.
func readBoundedCubeLutText(
    read: (Int) throws -> Data,
    checkCancellation: () throws -> Void = { try Task.checkCancellation() }
) throws -> String {
    var data = Data()
    while true {
        try checkCancellation()
        let remaining = maximumCubeLutBytes - data.count
        let chunk = try read(min(64 * 1024, remaining + 1))
        try checkCancellation()
        if chunk.isEmpty { break }
        guard chunk.count <= remaining else {
            throw CubeLutError.invalid("3D LUT exceeds the 16 MiB limit.")
        }
        data.append(chunk)
    }
    let utf8BOM: [UInt8] = [0xEF, 0xBB, 0xBF]
    if data.starts(with: utf8BOM) {
        data.removeFirst(utf8BOM.count)
        guard !data.starts(with: utf8BOM) else {
            throw CubeLutError.invalid("The selected LUT has repeated UTF-8 byte-order marks.")
        }
    }
    guard let text = String(data: data, encoding: .utf8) else {
        throw CubeLutError.invalid("The selected LUT is not valid UTF-8 text.")
    }
    return text
}
