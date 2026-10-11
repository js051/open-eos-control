import Foundation

#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// One URLSession task, a bounded streamed file, and default platform TLS trust.
/// This session is isolated from the manual transport's redirect behavior.
final class CameraForegroundHTTPTask: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private let configuration: URLSessionConfiguration
    private let maximumBytes: Int64
    private let temporaryDirectory: URL
    private let progress: CameraMediaProgressHandler
    private var continuation: CheckedContinuation<CameraHTTPDownloadResponse, Error>?
    private var session: URLSession?
    private var task: URLSessionDataTask?
    private var file: FileHandle?
    private var fileURL: URL?
    private var receivedBytes: Int64 = 0
    private var statusCode: Int?
    private var headers: [String: String] = [:]
    private var cancellationRequested = false

    init(
        configuration: URLSessionConfiguration,
        maximumBytes: Int64,
        temporaryDirectory: URL = FileManager.default.temporaryDirectory,
        progress: @escaping CameraMediaProgressHandler
    ) {
        self.configuration = configuration.copy() as! URLSessionConfiguration
        self.maximumBytes = maximumBytes
        self.temporaryDirectory = temporaryDirectory
        self.progress = progress
        super.init()
    }

    func run(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse {
        guard request.httpMethod == "GET", request.value(forHTTPHeaderField: "Range") == nil,
              maximumBytes > 0, request.timeoutInterval > 0, request.timeoutInterval.isFinite else {
            throw CCAPIError.invalidResponse("Invalid strict foreground read request.")
        }
        return try await withTaskCancellationHandler {
            try Task.checkCancellation()
            return try await withCheckedThrowingContinuation { continuation in
                lock.lock()
                if cancellationRequested {
                    lock.unlock()
                    continuation.resume(throwing: CancellationError())
                    return
                }
                let url = temporaryDirectory
                    .appendingPathComponent("open-eos-foreground-\(UUID().uuidString)")
                do {
                    guard FileManager.default.createFile(atPath: url.path, contents: nil) else {
                        throw CCAPIError.invalidResponse("Could not create a foreground download file.")
                    }
                    file = try FileHandle(forWritingTo: url)
                } catch {
                    try? FileManager.default.removeItem(at: url)
                    lock.unlock()
                    continuation.resume(throwing: error)
                    return
                }
                self.continuation = continuation
                fileURL = url
                configuration.timeoutIntervalForRequest = request.timeoutInterval
                configuration.timeoutIntervalForResource = request.timeoutInterval
                configuration.waitsForConnectivity = false
                configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
                configuration.urlCache = nil
                let session = URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
                let task = session.dataTask(with: request)
                self.session = session
                self.task = task
                lock.unlock()
                task.resume()
            }
        } onCancel: {
            self.cancel()
        }
    }

    private func cancel() {
        lock.lock()
        cancellationRequested = true
        lock.unlock()
        finish(error: CancellationError())
    }

    func urlSession(
        _ session: URLSession,
        task: URLSessionTask,
        willPerformHTTPRedirection response: HTTPURLResponse,
        newRequest request: URLRequest,
        completionHandler: @escaping (URLRequest?) -> Void
    ) {
        completionHandler(nil)
        finish(error: CCAPIError.invalidResponse("Strict foreground reads do not follow redirects."))
    }

    func urlSession(
        _ session: URLSession,
        dataTask: URLSessionDataTask,
        didReceive response: URLResponse,
        completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
    ) {
        guard let response = response as? HTTPURLResponse, response.statusCode == 200,
              response.value(forHTTPHeaderField: "Content-Range") == nil else {
            completionHandler(.cancel)
            finish(error: CCAPIError.invalidResponse("Strict foreground reads require HTTP 200 without Content-Range."))
            return
        }
        let headers = response.allHeaderFields.reduce(into: [String: String]()) { result, entry in
            result[String(describing: entry.key).lowercased()] = String(describing: entry.value)
        }
        // The streamed decoded body is bounded even when Content-Length is absent
        // or describes an encoded representation. Validation is repeated at EOF.
        lock.lock()
        let active = continuation != nil
        if active {
            statusCode = response.statusCode
            self.headers = headers
        }
        lock.unlock()
        completionHandler(active ? .allow : .cancel)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        lock.lock()
        guard continuation != nil, let file else { lock.unlock(); return }
        guard Int64(data.count) <= maximumBytes - receivedBytes else {
            lock.unlock()
            finish(error: CCAPIError.invalidResponse("Strict foreground response exceeded its byte limit."))
            return
        }
        do {
            try file.write(contentsOf: data)
            receivedBytes += Int64(data.count)
            let bytes = receivedBytes
            lock.unlock()
            progress(CameraMediaTransferProgress(bytesTransferred: bytes, totalBytes: maximumBytes))
        } catch {
            lock.unlock()
            finish(error: error)
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        finish(error: error)
    }

    private func finish(error: Error?) {
        lock.lock()
        guard let continuation = continuation else { lock.unlock(); return }
        self.continuation = nil
        let session = session
        let task = task
        let file = file
        let url = fileURL
        let status = statusCode
        let headers = headers
        let cancelled = cancellationRequested || (error as? URLError)?.code == .cancelled
        self.session = nil
        self.task = nil
        self.file = nil
        self.fileURL = nil
        lock.unlock()
        task?.cancel()
        session?.invalidateAndCancel()

        do {
            try file?.close()
            if cancelled { throw CancellationError() }
            if let error { throw error }
            guard let url, let status else {
                throw CCAPIError.invalidResponse("Strict foreground response ended without HTTP headers.")
            }
            let response = CameraHTTPDownloadResponse(statusCode: status, headers: headers, temporaryFileURL: url)
            _ = try CameraHTTPResponseIntegrity.validate(response, requestMethod: "GET")
            continuation.resume(returning: response)
        } catch {
            if let url { try? FileManager.default.removeItem(at: url) }
            continuation.resume(throwing: error)
        }
    }
}
