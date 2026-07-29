import Foundation

#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public struct CameraHTTPResponse: Sendable {
    public let statusCode: Int
    public let headers: [String: String]
    public let body: Data

    public init(statusCode: Int, headers: [String: String] = [:], body: Data = Data()) {
        self.statusCode = statusCode
        self.headers = headers.reduce(into: [:]) { result, entry in
            result[entry.key.lowercased()] = entry.value
        }
        self.body = body
    }

    public func header(_ name: String) -> String? {
        headers[name.lowercased()]
    }
}

public struct CameraHTTPDownloadResponse: Sendable {
    public let statusCode: Int
    public let headers: [String: String]
    public let temporaryFileURL: URL

    public init(statusCode: Int, headers: [String: String] = [:], temporaryFileURL: URL) {
        self.statusCode = statusCode
        self.headers = headers.reduce(into: [:]) { result, entry in
            result[entry.key.lowercased()] = entry.value
        }
        self.temporaryFileURL = temporaryFileURL
    }

    public func header(_ name: String) -> String? {
        headers[name.lowercased()]
    }
}

public protocol CameraHTTPTransport: Sendable {
    func send(_ request: URLRequest) async throws -> CameraHTTPResponse
    func download(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse
    func download(
        _ request: URLRequest,
        progress: @escaping CameraMediaProgressHandler
    ) async throws -> CameraHTTPDownloadResponse
}

public extension CameraHTTPTransport {
    func download(
        _ request: URLRequest,
        progress: @escaping CameraMediaProgressHandler
    ) async throws -> CameraHTTPDownloadResponse {
        let response = try await download(request)
        let size = Int64(
            (try? response.temporaryFileURL.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
        )
        progress(CameraMediaTransferProgress(bytesTransferred: size, totalBytes: size))
        return response
    }
}

public final class URLSessionCameraHTTPTransport: CameraHTTPTransport, @unchecked Sendable {
    private let session: URLSession

    public init(configuration: URLSessionConfiguration = .ephemeral) {
        configuration.waitsForConnectivity = true
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.timeoutIntervalForRequest = 10
        configuration.timeoutIntervalForResource = 120
        configuration.httpMaximumConnectionsPerHost = 2
        configuration.httpShouldSetCookies = false
        configuration.urlCache = nil
        configuration.urlCredentialStorage = nil
        session = URLSession(configuration: configuration)
    }

    public func send(_ request: URLRequest) async throws -> CameraHTTPResponse {
        let (data, response) = try await session.data(for: request)
        let http = try Self.httpResponse(response)
        return CameraHTTPResponse(statusCode: http.statusCode, headers: Self.headers(http), body: data)
    }

    public func download(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse {
        try await download(request, progress: { _ in })
    }

    public func download(
        _ request: URLRequest,
        progress: @escaping CameraMediaProgressHandler
    ) async throws -> CameraHTTPDownloadResponse {
        let handle = CameraHTTPDownloadTaskHandle()
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                let task = session.downloadTask(with: request) { url, response, error in
                    let cancellationRequested = handle.finish()
                    if cancellationRequested || Self.isCancellation(error) {
                        if let url { try? FileManager.default.removeItem(at: url) }
                        continuation.resume(throwing: CancellationError())
                        return
                    }
                    if let error {
                        if let url { try? FileManager.default.removeItem(at: url) }
                        continuation.resume(throwing: error)
                        return
                    }
                    guard let url, let response else {
                        continuation.resume(
                            throwing: CCAPIError.invalidResponse("Camera download did not return a file and HTTP response.")
                        )
                        return
                    }

                    do {
                        let http = try Self.httpResponse(response)
                        let retainedURL = FileManager.default.temporaryDirectory
                            .appendingPathComponent("open-eos-download-\(UUID().uuidString)")
                        try FileManager.default.moveItem(at: url, to: retainedURL)
                        let size = Int64(
                            (try? retainedURL.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0
                        )
                        let expected = response.expectedContentLength > 0
                            ? response.expectedContentLength
                            : size
                        progress(CameraMediaTransferProgress(bytesTransferred: size, totalBytes: expected))
                        continuation.resume(
                            returning: CameraHTTPDownloadResponse(
                                statusCode: http.statusCode,
                                headers: Self.headers(http),
                                temporaryFileURL: retainedURL
                            )
                        )
                    } catch {
                        try? FileManager.default.removeItem(at: url)
                        continuation.resume(throwing: error)
                    }
                }
                let observation = task.progress.observe(\.completedUnitCount, options: [.initial, .new]) {
                    observed, _ in
                    let total = observed.totalUnitCount > 0 ? observed.totalUnitCount : nil
                    progress(
                        CameraMediaTransferProgress(
                            bytesTransferred: observed.completedUnitCount,
                            totalBytes: total
                        )
                    )
                }
                handle.install(task: task, observation: observation)
            }
        } onCancel: {
            handle.cancel()
        }
    }

    private static func isCancellation(_ error: Error?) -> Bool {
        (error as? URLError)?.code == .cancelled
    }

    private static func httpResponse(_ response: URLResponse) throws -> HTTPURLResponse {
        guard let http = response as? HTTPURLResponse else {
            throw CCAPIError.invalidResponse("Camera response was not HTTP.")
        }
        return http
    }

    private static func headers(_ response: HTTPURLResponse) -> [String: String] {
        response.allHeaderFields.reduce(into: [:]) { result, entry in
            result[String(describing: entry.key).lowercased()] = String(describing: entry.value)
        }
    }
}

private final class CameraHTTPDownloadTaskHandle: @unchecked Sendable {
    private let lock = NSLock()
    private var task: URLSessionDownloadTask?
    private var observation: NSKeyValueObservation?
    private var cancellationRequested = false

    func install(task: URLSessionDownloadTask, observation: NSKeyValueObservation) {
        lock.lock()
        self.task = task
        self.observation = observation
        let shouldCancel = cancellationRequested
        lock.unlock()

        if shouldCancel {
            task.cancel()
        } else {
            task.resume()
        }
    }

    func cancel() {
        lock.lock()
        cancellationRequested = true
        let task = task
        lock.unlock()
        task?.cancel()
    }

    func finish() -> Bool {
        lock.lock()
        let cancellationRequested = cancellationRequested
        let observation = observation
        task = nil
        self.observation = nil
        lock.unlock()
        observation?.invalidate()
        return cancellationRequested
    }
}
