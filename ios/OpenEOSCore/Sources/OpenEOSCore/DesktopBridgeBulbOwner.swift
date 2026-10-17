import Foundation

/// Immutable command identity. Only the admitting client can use this token.
public struct DesktopBridgeBulbOwner: Equatable, Sendable {
    public let id: UUID
    let generation: UUID
    let startURL: URL
    let stopURL: URL
}

/// A total response deadline, including custom transports that ignore cancellation.
/// The detached exchange returns data only; a late result cannot publish client state.
final class BridgeBoundedExchange: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<CameraHTTPResponse, Error>?
    private var result: Result<CameraHTTPResponse, Error>?
    private var worker: Task<Void, Never>?
    private var timer: Task<Void, Never>?

    static func run(seconds: Double = 15,
                    operation: @escaping @Sendable () async throws -> CameraHTTPResponse) async throws -> CameraHTTPResponse {
        let gate = BridgeBoundedExchange()
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                gate.start(continuation, seconds: seconds, operation: operation)
            }
        } onCancel: { gate.finish(.failure(CancellationError())) }
    }

    private func start(_ continuation: CheckedContinuation<CameraHTTPResponse, Error>, seconds: Double,
                       operation: @escaping @Sendable () async throws -> CameraHTTPResponse) {
        lock.lock()
        if let result { lock.unlock(); continuation.resume(with: result); return }
        self.continuation = continuation
        worker = Task.detached {
            do { self.finish(.success(try await operation())) }
            catch { self.finish(.failure(error)) }
        }
        timer = Task.detached {
            do { try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000)) }
            catch { return }
            self.finish(.failure(URLError(.timedOut)))
        }
        lock.unlock()
    }

    private func finish(_ value: Result<CameraHTTPResponse, Error>) {
        lock.lock()
        guard result == nil else { lock.unlock(); return }
        result = value
        let continuation = continuation, worker = worker, timer = timer
        self.continuation = nil; self.worker = nil; self.timer = nil
        lock.unlock()
        worker?.cancel(); timer?.cancel()
        continuation?.resume(with: value)
    }
}
