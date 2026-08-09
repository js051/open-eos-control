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

public struct CameraHTTPUploadResponse: Sendable {
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

public struct CameraHTTPStreamResponse: Sendable {
    public let statusCode: Int
    public let headers: [String: String]
    public let chunks: AsyncThrowingStream<Data, Error>
    private let cancelAction: @Sendable () -> Void

    public init(
        statusCode: Int,
        headers: [String: String] = [:],
        chunks: AsyncThrowingStream<Data, Error>,
        cancel: @escaping @Sendable () -> Void = {}
    ) {
        self.statusCode = statusCode
        self.headers = headers.reduce(into: [:]) { result, entry in
            result[entry.key.lowercased()] = entry.value
        }
        self.chunks = chunks
        cancelAction = cancel
    }

    public func header(_ name: String) -> String? {
        headers[name.lowercased()]
    }

    public func cancel() {
        cancelAction()
    }
}

public protocol CameraHTTPTransport: Sendable {
    func send(_ request: URLRequest) async throws -> CameraHTTPResponse
    func openStream(_ request: URLRequest) async throws -> CameraHTTPStreamResponse
    func download(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse
    func download(
        _ request: URLRequest,
        progress: @escaping CameraMediaProgressHandler
    ) async throws -> CameraHTTPDownloadResponse
    func upload(
        _ request: URLRequest,
        from fileURL: URL,
        progress: @escaping CameraMediaProgressHandler
    ) async throws -> CameraHTTPUploadResponse
}

public extension CameraHTTPTransport {
    func openStream(_ request: URLRequest) async throws -> CameraHTTPStreamResponse {
        let response = try await send(request)
        let chunks = AsyncThrowingStream<Data, Error> { continuation in
            if !response.body.isEmpty { continuation.yield(response.body) }
            continuation.finish()
        }
        return CameraHTTPStreamResponse(
            statusCode: response.statusCode,
            headers: response.headers,
            chunks: chunks
        )
    }

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

    func upload(
        _ request: URLRequest,
        from fileURL: URL,
        progress: @escaping CameraMediaProgressHandler
    ) async throws -> CameraHTTPUploadResponse {
        throw CCAPIError.invalidResponse("This HTTP transport does not support file uploads.")
    }
}

struct CameraHTTPResponseBodyAccumulator {
    static let maximumBytes = 32 * 1024

    private(set) var data = Data()

    @discardableResult
    mutating func append(_ chunk: Data) -> Bool {
        guard chunk.count <= Self.maximumBytes - data.count else { return false }
        data.append(chunk)
        return true
    }
}

public final class URLSessionCameraHTTPTransport: CameraHTTPTransport, @unchecked Sendable {
    private let session: URLSession
    private let streamConfiguration: URLSessionConfiguration

    public init(configuration: URLSessionConfiguration = .ephemeral) {
        configuration.waitsForConnectivity = true
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.timeoutIntervalForRequest = 10
        configuration.timeoutIntervalForResource = 120
        configuration.httpMaximumConnectionsPerHost = 4
        configuration.httpShouldSetCookies = false
        configuration.urlCache = nil
        configuration.urlCredentialStorage = nil
        streamConfiguration = configuration.copy() as! URLSessionConfiguration
        session = URLSession(configuration: configuration)
    }

    public func send(_ request: URLRequest) async throws -> CameraHTTPResponse {
        let (data, response) = try await session.data(for: request)
        let http = try Self.httpResponse(response)
        return CameraHTTPResponse(statusCode: http.statusCode, headers: Self.headers(http), body: data)
    }

    public func openStream(_ request: URLRequest) async throws -> CameraHTTPStreamResponse {
        let handle = CameraHTTPStreamTaskHandle(configuration: streamConfiguration)
        return try await withTaskCancellationHandler {
            try await handle.start(request)
        } onCancel: {
            handle.cancel()
        }
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

    public func upload(
        _ request: URLRequest,
        from fileURL: URL,
        progress: @escaping CameraMediaProgressHandler
    ) async throws -> CameraHTTPUploadResponse {
        let handle = CameraHTTPUploadTaskHandle()
        return try await withTaskCancellationHandler {
            try await handle.start(
                request: request,
                fileURL: fileURL,
                configuration: streamConfiguration,
                progress: progress
            )
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

private final class CameraHTTPStreamTaskHandle: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let configuration: URLSessionConfiguration
    private let lock = NSLock()
    private let chunks: AsyncThrowingStream<Data, Error>
    private let chunksContinuation: AsyncThrowingStream<Data, Error>.Continuation
    private var responseContinuation: CheckedContinuation<CameraHTTPStreamResponse, Error>?
    private var session: URLSession?
    private var task: URLSessionDataTask?
    private var responseDelivered = false

    init(configuration: URLSessionConfiguration) {
        self.configuration = configuration.copy() as! URLSessionConfiguration
        let pair = AsyncThrowingStream<Data, Error>.makeStream()
        chunks = pair.stream
        chunksContinuation = pair.continuation
        super.init()
    }

    func start(_ request: URLRequest) async throws -> CameraHTTPStreamResponse {
        try await withCheckedThrowingContinuation { continuation in
            lock.lock()
            responseContinuation = continuation
            let session = URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
            let task = session.dataTask(with: request)
            self.session = session
            self.task = task
            lock.unlock()
            task.resume()
        }
    }

    func cancel() {
        lock.lock()
        let task = task
        let session = session
        lock.unlock()
        task?.cancel()
        session?.invalidateAndCancel()
    }

    func urlSession(
        _ session: URLSession,
        dataTask: URLSessionDataTask,
        didReceive response: URLResponse,
        completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
    ) {
        guard let http = response as? HTTPURLResponse else {
            finish(error: CCAPIError.invalidResponse("Camera stream response was not HTTP."))
            completionHandler(.cancel)
            return
        }
        let headers = http.allHeaderFields.reduce(into: [String: String]()) { result, entry in
            result[String(describing: entry.key).lowercased()] = String(describing: entry.value)
        }
        lock.lock()
        let continuation = responseContinuation
        responseContinuation = nil
        responseDelivered = true
        lock.unlock()
        continuation?.resume(
            returning: CameraHTTPStreamResponse(
                statusCode: http.statusCode,
                headers: headers,
                chunks: chunks,
                cancel: { [self] in cancel() }
            )
        )
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        chunksContinuation.yield(data)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        finish(error: error)
    }

    private func finish(error: Error?) {
        lock.lock()
        let continuation = responseContinuation
        responseContinuation = nil
        let delivered = responseDelivered
        task = nil
        let session = session
        self.session = nil
        lock.unlock()

        if !delivered {
            continuation?.resume(throwing: error ?? CCAPIError.invalidResponse("Camera stream ended before HTTP headers."))
        }
        if let error {
            chunksContinuation.finish(throwing: error)
        } else {
            chunksContinuation.finish()
        }
        session?.finishTasksAndInvalidate()
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

private final class CameraHTTPUploadTaskHandle: NSObject, URLSessionTaskDelegate, URLSessionDataDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private var session: URLSession?
    private var task: URLSessionUploadTask?
    private var continuation: CheckedContinuation<CameraHTTPUploadResponse, Error>?
    private var responseStatusCode: Int?
    private var responseHeaders: [String: String] = [:]
    private var responseBody = CameraHTTPResponseBodyAccumulator()
    private var cancellationRequested = false
    private var progress: CameraMediaProgressHandler = { _ in }
    private var totalBytes: Int64?

    func start(
        request: URLRequest,
        fileURL: URL,
        configuration: URLSessionConfiguration,
        progress: @escaping CameraMediaProgressHandler
    ) async throws -> CameraHTTPUploadResponse {
        let size = Int64((try? fileURL.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
        guard size >= 0 else {
            throw CCAPIError.invalidResponse("The upload file size is invalid.")
        }
        return try await withCheckedThrowingContinuation { continuation in
            lock.lock()
            self.continuation = continuation
            self.progress = progress
            totalBytes = size
            let session = URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
            let task = session.uploadTask(with: request, fromFile: fileURL)
            self.session = session
            self.task = task
            let shouldCancel = cancellationRequested
            lock.unlock()
            if shouldCancel { task.cancel() } else { task.resume() }
        }
    }

    func cancel() {
        lock.lock()
        cancellationRequested = true
        let task = task
        lock.unlock()
        task?.cancel()
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        didSendBodyData bytesSent: Int64,
        totalBytesSent: Int64,
        totalBytesExpectedToSend: Int64
    ) {
        let total = totalBytesExpectedToSend > 0 ? totalBytesExpectedToSend : totalBytes
        progress(CameraMediaTransferProgress(bytesTransferred: totalBytesSent, totalBytes: total))
    }

    func urlSession(
        _ session: URLSession,
        dataTask: URLSessionDataTask,
        didReceive response: URLResponse,
        completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
    ) {
        guard let http = response as? HTTPURLResponse else {
            finish(error: CCAPIError.invalidResponse("Upload response was not HTTP."))
            completionHandler(.cancel)
            return
        }
        lock.lock()
        responseStatusCode = http.statusCode
        responseHeaders = http.allHeaderFields.reduce(into: [:]) { result, entry in
            result[String(describing: entry.key).lowercased()] = String(describing: entry.value)
        }
        lock.unlock()
        completionHandler(.allow)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        lock.lock()
        let accepted = responseBody.append(data)
        lock.unlock()
        guard accepted else {
            finish(error: CCAPIError.invalidResponse("Upload response exceeded the 32 KiB limit."))
            return
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        lock.lock()
        guard let continuation = self.continuation else {
            lock.unlock()
            return
        }
        let cancelled = cancellationRequested || (error as? URLError)?.code == .cancelled
        self.continuation = nil
        let status = responseStatusCode
        let headers = responseHeaders
        let body = responseBody.data
        let activeSession = self.session
        self.session = nil
        self.task = nil
        lock.unlock()

        activeSession?.finishTasksAndInvalidate()
        if cancelled {
            continuation.resume(throwing: CancellationError())
        } else if let error {
            continuation.resume(throwing: error)
        } else if let status {
            progress(CameraMediaTransferProgress(bytesTransferred: totalBytes ?? 0, totalBytes: totalBytes))
            continuation.resume(returning: CameraHTTPUploadResponse(statusCode: status, headers: headers, body: body))
        } else {
            continuation.resume(throwing: CCAPIError.invalidResponse("Upload ended without an HTTP response."))
        }
    }

    private func finish(error: Error) {
        lock.lock()
        guard let continuation = self.continuation else {
            lock.unlock()
            return
        }
        self.continuation = nil
        let activeTask = self.task
        let activeSession = self.session
        self.session = nil
        self.task = nil
        lock.unlock()
        activeTask?.cancel()
        activeSession?.invalidateAndCancel()
        continuation.resume(throwing: error)
    }
}
