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

/// REC-only transport evidence. A received HTTP status survives a failed body,
/// while callers must never treat an incomplete success body as a command ACK.
public struct CameraHTTPRecordingResponse: Sendable {
    public let response: CameraHTTPResponse
    public let bodyComplete: Bool
    public let cancelled: Bool

    public init(response: CameraHTTPResponse, bodyComplete: Bool = true, cancelled: Bool = false) {
        self.response = response
        self.bodyComplete = bodyComplete
        self.cancelled = cancelled
    }
}

public struct CameraHTTPDownloadResponse: Sendable {
    public let statusCode: Int
    public let headers: [String: String]
    public let temporaryFileURL: URL
    // Capture every exposed declaration before case-insensitive lookup collapses keys.
    let contentTypeValues: [String]

    public init(statusCode: Int, headers: [String: String] = [:], temporaryFileURL: URL) {
        self.statusCode = statusCode
        contentTypeValues = headers.filter { $0.key.lowercased() == "content-type" }.map(\.value)
        self.headers = headers.reduce(into: [:]) { result, entry in
            result[entry.key.lowercased()] = entry.value
        }
        self.temporaryFileURL = temporaryFileURL
    }

    public func header(_ name: String) -> String? {
        headers[name.lowercased()]
    }

    func readPrefix(limit: Int) throws -> Data {
        guard limit > 0 else { return Data() }
        let handle = try FileHandle(forReadingFrom: temporaryFileURL)
        defer { try? handle.close() }
        return try handle.read(upToCount: limit) ?? Data()
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
    func sendMediaDelete(_ request: URLRequest) async throws -> CameraHTTPResponse
    func sendShootingSetting(_ request: URLRequest) async throws -> CameraHTTPResponse
    func sendCaptureConfiguration(_ request: URLRequest) async throws -> CameraHTTPResponse
    func sendMaintenance(_ request: URLRequest) async throws -> CameraHTTPResponse
    func sendRecordingCommand(_ request: URLRequest) async throws -> CameraHTTPRecordingResponse
    func sendRecordingStatus(_ request: URLRequest) async throws -> CameraHTTPResponse
    func openStream(_ request: URLRequest) async throws -> CameraHTTPStreamResponse
    func openMaintenanceStream(_ request: URLRequest) async throws -> CameraHTTPStreamResponse
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
    /// Custom transports must preserve one logical send. Production URLSession
    /// uses its dedicated no-redirect, non-replayable DELETE task below.
    func sendMediaDelete(_ request: URLRequest) async throws -> CameraHTTPResponse {
        try await send(request)
    }

    /// Deterministic/custom transports keep one invocation; production uses the
    /// scoped no-replay task. No fallback to ordinary send after any failure.
    func sendCaptureConfiguration(_ request: URLRequest) async throws -> CameraHTTPResponse {
        try await send(request)
    }

    /// One logical ordinary-setting invocation; production forbids redirect/auth replay.
    func sendShootingSetting(_ request: URLRequest) async throws -> CameraHTTPResponse {
        try await send(request)
    }

    /// One bounded maintenance exchange; never replay a command after failure.
    func sendMaintenance(_ request: URLRequest) async throws -> CameraHTTPResponse {
        try await send(request)
    }

    func sendRecordingCommand(_ request: URLRequest) async throws -> CameraHTTPRecordingResponse {
        // Existing custom transports retain their send/cancellation contract.
        // There is exactly one invocation, never a fallback after failure.
        CameraHTTPRecordingResponse(response: try await send(request))
    }

    /// Read-only recovery uses one bounded exchange, never a command replay.
    func sendRecordingStatus(_ request: URLRequest) async throws -> CameraHTTPResponse {
        try await send(request)
    }

    func openMaintenanceStream(_ request: URLRequest) async throws -> CameraHTTPStreamResponse {
        try await openStream(request)
    }

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

/// Checks only framing that remains comparable after URLSession has decoded a response.
/// Encoded/transfer-decoded responses still rely on URLSession's framing validation.
enum CameraHTTPResponseIntegrity {
    static func validate(_ response: CameraHTTPResponse, requestMethod: String) throws {
        try validate(
            statusCode: response.statusCode, headers: response.headers,
            decodedBodyBytes: Int64(response.body.count), requestMethod: requestMethod
        )
    }

    @discardableResult
    static func validate(_ response: CameraHTTPDownloadResponse, requestMethod: String) throws -> Int64 {
        let values = try response.temporaryFileURL.resourceValues(forKeys: [.isRegularFileKey, .fileSizeKey])
        guard values.isRegularFile == true, let size = values.fileSize, size >= 0 else {
            throw CCAPIError.invalidResponse("Camera download did not return a regular file with a known size.")
        }
        try validate(
            statusCode: response.statusCode, headers: response.headers,
            decodedBodyBytes: Int64(size), requestMethod: requestMethod
        )
        return Int64(size)
    }

    static func validateOriginalMedia(_ response: CameraHTTPDownloadResponse) throws -> Int64 {
        // Original downloads never request a byte range. Even a 200 carrying
        // Content-Range cannot establish that this is the complete representation.
        guard response.statusCode == 200, response.header("content-range") == nil else {
            throw CCAPIError.invalidResponse("Original media download requires HTTP 200 without Content-Range.")
        }
        let size = try validate(response, requestMethod: "GET")
        guard size > 0 else {
            throw CCAPIError.invalidResponse("Original media download returned an empty file.")
        }
        // CameraMediaItem.sizeBytes comes from unversioned listing metadata. It is
        // a progress hint, not an authoritative length or a content checksum.
        return size
    }

    private static func validate(
        statusCode: Int,
        headers: [String: String],
        decodedBodyBytes: Int64,
        requestMethod: String
    ) throws {
        let method = requestMethod.uppercased()
        // RFC 9112 §6.3: these responses have no HTTP message body to compare.
        guard method != "HEAD", !(100..<200).contains(statusCode),
              statusCode != 204, statusCode != 304,
              !(method == "CONNECT" && (200..<300).contains(statusCode)) else { return }
        // Transfer-Encoding takes precedence over Content-Length. URLSession removes
        // transfer framing and may decompress content before returning Data, so neither
        // of those wire lengths can safely be compared with the decoded body's count.
        guard headers["transfer-encoding"] == nil else { return }
        let optionalWhitespace = CharacterSet(charactersIn: " \t")
        if let encoding = headers["content-encoding"],
           encoding.trimmingCharacters(in: optionalWhitespace).lowercased() != "identity" { return }
        guard let field = headers["content-length"] else { return }

        // RFC 9110 §8.6 permits normalization of identical, combined length values.
        // Reject signs, empty/conflicting values and overflow rather than losing precision.
        var expected: Int64?
        for component in field.split(separator: ",", omittingEmptySubsequences: false) {
            let value = component.trimmingCharacters(in: optionalWhitespace)
            guard !value.isEmpty, value.utf8.allSatisfy({ (48...57).contains($0) }),
                  let length = Int64(value), expected == nil || expected == length else {
                throw CCAPIError.invalidResponse("Camera HTTP response contained an invalid Content-Length.")
            }
            expected = length
        }
        guard let expected, expected == decodedBodyBytes else {
            throw CCAPIError.invalidResponse("Camera HTTP response body did not match Content-Length.")
        }
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
        let result = CameraHTTPResponse(statusCode: http.statusCode, headers: Self.headers(http), body: data)
        try CameraHTTPResponseIntegrity.validate(result, requestMethod: request.httpMethod ?? "GET")
        return result
    }

    public func sendMediaDelete(_ request: URLRequest) async throws -> CameraHTTPResponse {
        try await CameraHTTPDeleteTask(configuration: streamConfiguration).run(request)
    }

    public func sendShootingSetting(_ request: URLRequest) async throws -> CameraHTTPResponse {
        try await CameraHTTPShootingSettingTask(configuration: streamConfiguration).run(request)
    }

    public func sendCaptureConfiguration(_ request: URLRequest) async throws -> CameraHTTPResponse {
        try await CameraHTTPCaptureConfigurationTask(configuration: streamConfiguration).run(request)
    }

    public func sendMaintenance(_ request: URLRequest) async throws -> CameraHTTPResponse {
        try await CameraHTTPMaintenanceTask(configuration: streamConfiguration).run(request)
    }

    public func sendRecordingCommand(_ request: URLRequest) async throws -> CameraHTTPRecordingResponse {
        try await sendRecordingCommand(request, onResponseHeaders: nil)
    }

    // Read-only observation for deterministic wire tests. It neither changes the
    // request nor provides an alternate send path or response-body implementation.
    func sendRecordingCommand(
        _ request: URLRequest, onResponseHeaders: (@Sendable (Int) -> Void)?
    ) async throws -> CameraHTTPRecordingResponse {
        try await CameraHTTPRecordingTask(configuration: streamConfiguration, onResponseHeaders: onResponseHeaders).run(request)
    }

    public func sendRecordingStatus(_ request: URLRequest) async throws -> CameraHTTPResponse {
        guard request.httpMethod == "GET", request.httpBody == nil else {
            throw CCAPIError.invalidResponse("Recording recovery must be read-only.")
        }
        let exchange = try await CameraHTTPRecordingTask(configuration: streamConfiguration).run(request)
        if exchange.cancelled { throw CancellationError() }
        guard exchange.bodyComplete else { throw CCAPIError.invalidResponse("Recording observation did not complete.") }
        return exchange.response
    }

    public func openMaintenanceStream(_ request: URLRequest) async throws -> CameraHTTPStreamResponse {
        let handle = CameraHTTPStreamTaskHandle(configuration: streamConfiguration,
                                                boundedListing: true, maintenanceStream: true)
        return try await withTaskCancellationHandler {
            try Task.checkCancellation()
            return try await handle.start(request)
        } onCancel: { handle.cancel() }
    }

    public func openStream(_ request: URLRequest) async throws -> CameraHTTPStreamResponse {
        let handle = CameraHTTPStreamTaskHandle(
            configuration: streamConfiguration,
            boundedListing: request.value(forHTTPHeaderField: "Accept") == BridgeMediaListingDecoder.mediaType
        )
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
                        let size = try CameraHTTPResponseIntegrity.validate(
                            CameraHTTPDownloadResponse(
                                statusCode: http.statusCode, headers: Self.headers(http), temporaryFileURL: url
                            ),
                            requestMethod: request.httpMethod ?? "GET"
                        )
                        let retainedURL = FileManager.default.temporaryDirectory
                            .appendingPathComponent("open-eos-download-\(UUID().uuidString)")
                        try FileManager.default.moveItem(at: url, to: retainedURL)
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

extension URLSessionCameraHTTPTransport: CameraForegroundHTTPTransport {
    public func sendForeground(
        _ request: URLRequest,
        maximumResponseBytes: Int
    ) async throws -> CameraHTTPResponse {
        let response = try await CameraForegroundHTTPTask(
            configuration: streamConfiguration, maximumBytes: Int64(maximumResponseBytes), progress: { _ in }
        ).run(request)
        defer { try? FileManager.default.removeItem(at: response.temporaryFileURL) }
        try Task.checkCancellation()
        return CameraHTTPResponse(
            statusCode: response.statusCode, headers: response.headers,
            body: try Data(contentsOf: response.temporaryFileURL)
        )
    }

    public func downloadForeground(
        _ request: URLRequest,
        maximumBytes: Int64,
        progress: @escaping CameraMediaProgressHandler
    ) async throws -> CameraHTTPDownloadResponse {
        try await CameraForegroundHTTPTask(
            configuration: streamConfiguration, maximumBytes: maximumBytes, progress: progress
        ).run(request)
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
    private var cancellationRequested = false
    private let maximumBytes: Int?
    private let boundedListing: Bool
    private let maintenanceStream: Bool
    private var maintenanceHeaderDeadline: Task<Void, Never>?
    private var receivedBytes = 0
    private var terminalResultRecorded = false
    private var completedNormally = false

    init(configuration: URLSessionConfiguration, maximumBytes: Int? = nil, boundedListing: Bool = false, maintenanceStream: Bool = false) {
        self.maintenanceStream = maintenanceStream
        self.maximumBytes = maximumBytes
        self.boundedListing = boundedListing
        self.configuration = configuration.copy() as! URLSessionConfiguration
        let pair = AsyncThrowingStream<Data, Error>.makeStream(
            bufferingPolicy: boundedListing ? .bufferingOldest(64) : .unbounded
        )
        chunks = pair.stream
        chunksContinuation = pair.continuation
        super.init()
    }

    func start(_ request: URLRequest) async throws -> CameraHTTPStreamResponse {
        try await withCheckedThrowingContinuation { continuation in
            lock.lock()
            responseContinuation = continuation
            if maintenanceStream {
                configuration.waitsForConnectivity = false
                configuration.urlCache = nil
                configuration.urlCredentialStorage = nil
                configuration.httpShouldSetCookies = false
                configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
                configuration.timeoutIntervalForRequest = request.timeoutInterval
                // Header wait has a wall deadline, independent of idle timeout.
                // Once headers are delivered, restore owns the remaining first-frame
                // budget; an established stream keeps its normal long lifetime.
            }
            let session = URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
            let task = session.dataTask(with: request)
            self.session = session
            self.task = task
            if maintenanceStream {
                let seconds = min(max(request.timeoutInterval, 0.001), 15)
                maintenanceHeaderDeadline = Task { [weak self] in
                    do { try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000)) }
                    catch { return }
                    self?.expireMaintenanceHeaders()
                }
            }
            let shouldCancel = cancellationRequested
            lock.unlock()
            if shouldCancel { task.cancel() } else { task.resume() }
        }
    }

    private func expireMaintenanceHeaders() {
        lock.lock()
        guard maintenanceStream, !responseDelivered, !terminalResultRecorded else { lock.unlock(); return }
        terminalResultRecorded = true
        let continuation = responseContinuation; responseContinuation = nil
        let activeTask = task; task = nil
        let activeSession = session; session = nil
        maintenanceHeaderDeadline = nil
        lock.unlock()
        activeTask?.cancel(); activeSession?.invalidateAndCancel()
        continuation?.resume(throwing: URLError(.timedOut))
        chunksContinuation.finish(throwing: URLError(.timedOut))
    }

    func recordingBodyCompleted(collectedBytes: Int) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        // AsyncThrowingStream iteration can end early on task cancellation.
        // Require the producer's terminal success and every delivered body byte.
        return terminalResultRecorded && completedNormally && receivedBytes == collectedBytes
    }

    func cancel() {
        lock.lock()
        cancellationRequested = true
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
        guard !terminalResultRecorded else { lock.unlock(); completionHandler(.cancel); return }
        let continuation = responseContinuation
        responseContinuation = nil
        responseDelivered = true
        let deadline = maintenanceHeaderDeadline; maintenanceHeaderDeadline = nil
        lock.unlock()
        deadline?.cancel()
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

    func urlSession(
        _ session: URLSession, task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        completionHandler(boundedListing ? nil : request)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didReceive challenge: URLAuthenticationChallenge,
                    completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        if maintenanceStream, challenge.protectionSpace.authenticationMethod != NSURLAuthenticationMethodServerTrust {
            completionHandler(.cancelAuthenticationChallenge, nil)
        } else { completionHandler(.performDefaultHandling, nil) }
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        lock.lock()
        let exceedsLimit = maximumBytes.map { data.count > $0 - receivedBytes } ?? false
        if maximumBytes != nil, !exceedsLimit { receivedBytes += data.count }
        lock.unlock()
        guard !exceedsLimit else {
            finish(error: CCAPIError.invalidResponse("Recording response exceeded the 32 KiB limit."), cancelTask: true)
            return
        }
        if boundedListing {
            guard data.count <= 1024 * 1024 else {
                finish(error: DesktopBridgeError.invalidResponse("Listing transport chunk exceeded its byte limit."), cancelTask: true)
                return
            }
            // Bound queued storage to 64 × 64 KiB. Overflow is a failure, never loss.
            var start = data.startIndex
            while start < data.endIndex {
                let end = data.index(start, offsetBy: min(65_536, data.distance(from: start, to: data.endIndex)))
                switch chunksContinuation.yield(Data(data[start..<end])) {
                case .enqueued: break
                case .dropped:
                    finish(error: DesktopBridgeError.invalidResponse("Listing reader fell behind its bounded buffer."), cancelTask: true)
                    return
                case .terminated: return
                @unknown default: return
                }
                start = end
            }
        } else {
            chunksContinuation.yield(data)
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        finish(error: error)
    }

    private func finish(error: Error?, cancelTask: Bool = false) {
        lock.lock()
        let continuation = responseContinuation
        responseContinuation = nil
        let delivered = responseDelivered
        if !terminalResultRecorded {
            terminalResultRecorded = true
            completedNormally = delivered && error == nil
        }
        let activeTask = task
        task = nil
        let session = session
        self.session = nil
        let deadline = maintenanceHeaderDeadline; maintenanceHeaderDeadline = nil
        lock.unlock()
        deadline?.cancel()

        if !delivered {
            continuation?.resume(throwing: error ?? CCAPIError.invalidResponse("Camera stream ended before HTTP headers."))
        }
        if let error {
            chunksContinuation.finish(throwing: error)
        } else {
            chunksContinuation.finish()
        }
        if cancelTask {
            activeTask?.cancel()
            session?.invalidateAndCancel()
        } else {
            session?.finishTasksAndInvalidate()
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
        _ session: URLSession, task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        // Never retransmit selected personal bytes to a redirect destination,
        // even for 307/308. The first POST remains possibly dispatched.
        completionHandler(nil)
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
        // A complete error-free response wins a racing cancel request. The
        // caller still validates the exact receipt before declaring success.
        // Partial/cancelled responses with an error remain uncertain.
        if error == nil, let status {
            progress(CameraMediaTransferProgress(bytesTransferred: totalBytes ?? 0, totalBytes: totalBytes))
            continuation.resume(returning: CameraHTTPUploadResponse(statusCode: status, headers: headers, body: body))
        } else if cancelled {
            continuation.resume(throwing: CancellationError())
        } else if let error {
            continuation.resume(throwing: error)
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
