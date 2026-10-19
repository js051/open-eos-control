import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// One REC exchange on the existing HTTP transport. The body is supplied once;
/// redirects, authentication replay, caching, and unbounded response time are forbidden.
/// Header evidence survives an interrupted body without manufacturing an ACK.
final class CameraHTTPRecordingTask: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private let configuration: URLSessionConfiguration
    private let onResponseHeaders: (@Sendable (Int) -> Void)?
    private var session: URLSession?
    private var task: URLSessionTask?
    private var deadline: Task<Void, Never>?
    private var continuation: CheckedContinuation<CameraHTTPRecordingResponse, Error>?
    private var response: HTTPURLResponse?
    private var body = Data()
    private var source = Data()
    private var method = "GET"
    private var suppliedBody = false
    private var cancelled = false
    static let maximumBytes = 32 * 1024

    init(configuration: URLSessionConfiguration, onResponseHeaders: (@Sendable (Int) -> Void)? = nil) {
        self.configuration = configuration.copy() as! URLSessionConfiguration
        self.onResponseHeaders = onResponseHeaders
        super.init()
    }

    func run(_ request: URLRequest) async throws -> CameraHTTPRecordingResponse {
        guard ["GET", "POST", "PUT"].contains(request.httpMethod ?? ""), request.httpBodyStream == nil,
              request.timeoutInterval > 0, request.timeoutInterval.isFinite,
              (request.httpMethod != "GET" || (request.httpBody?.isEmpty ?? true)),
              (request.httpBody?.count ?? 0) <= Self.maximumBytes else {
            throw CCAPIError.invalidResponse("Invalid recording exchange.")
        }
        return try await withTaskCancellationHandler {
            try Task.checkCancellation()
            return try await withCheckedThrowingContinuation { continuation in
                lock.lock()
                guard !cancelled else { lock.unlock(); continuation.resume(throwing: CancellationError()); return }
                self.continuation = continuation
                source = request.httpBody ?? Data()
                method = request.httpMethod ?? "GET"
                let seconds = min(request.timeoutInterval, 10)
                configuration.waitsForConnectivity = false
                configuration.urlCache = nil
                configuration.urlCredentialStorage = nil
                configuration.httpShouldSetCookies = false
                configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
                configuration.timeoutIntervalForRequest = seconds
                configuration.timeoutIntervalForResource = seconds
                var value = request
                value.timeoutInterval = seconds
                value.httpBody = nil
                value.cachePolicy = .reloadIgnoringLocalCacheData
                value.setValue(method == "GET" ? nil : String(source.count), forHTTPHeaderField: "Content-Length")
                value.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
                let session = URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
                // Apple URLSession rejects streamed upload tasks for GET before dispatch.
                // Keep read-only requests on this same strict delegate/session.
                let task: URLSessionTask = method == "GET"
                    ? session.dataTask(with: value)
                    : session.uploadTask(withStreamedRequest: value)
                self.session = session
                self.task = task
                deadline = Task { [weak self] in
                    do { try await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000)) }
                    catch { return }
                    self?.finish(error: URLError(.timedOut))
                }
                lock.unlock()
                task.resume()
            }
        } onCancel: { self.cancel() }
    }

    private func cancel() {
        lock.lock(); cancelled = true; let running = continuation != nil; lock.unlock()
        if running { finish(error: CancellationError()) }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask,
                    needNewBodyStream completionHandler: @escaping (InputStream?) -> Void) {
        lock.lock()
        let allowed = !suppliedBody && !cancelled && continuation != nil
        if allowed { suppliedBody = true }
        let bytes = source
        lock.unlock()
        completionHandler(allowed ? InputStream(data: bytes) : nil)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }

    func urlSession(_ session: URLSession, task: URLSessionTask, didReceive challenge: URLAuthenticationChallenge,
                    completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        if challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust {
            completionHandler(.performDefaultHandling, nil)
        } else { completionHandler(.cancelAuthenticationChallenge, nil) }
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive response: URLResponse,
                    completionHandler: @escaping (URLSession.ResponseDisposition) -> Void) {
        lock.lock()
        let active = continuation != nil
        if active { self.response = response as? HTTPURLResponse }
        lock.unlock()
        if active, let http = response as? HTTPURLResponse { onResponseHeaders?(http.statusCode) }
        completionHandler(active && response is HTTPURLResponse ? .allow : .cancel)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        lock.lock()
        let accepted = continuation != nil && data.count <= Self.maximumBytes - body.count
        if accepted { body.append(data) }
        lock.unlock()
        if !accepted { finish(error: CCAPIError.invalidResponse("Recording response exceeded 32 KiB.")) }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        finish(error: error)
    }

    private func finish(error: Error?) {
        lock.lock()
        guard let continuation else { lock.unlock(); return }
        self.continuation = nil
        let response = response, body = body, method = method, cancelled = cancelled
        let activeSession = session, activeTask = task, activeDeadline = deadline
        session = nil; task = nil; deadline = nil; source = Data()
        lock.unlock()
        activeDeadline?.cancel()
        activeTask?.cancel()
        activeSession?.invalidateAndCancel()
        guard let response else {
            continuation.resume(throwing: error ?? CCAPIError.invalidResponse("Recording response had no HTTP headers."))
            return
        }
        let headers = response.allHeaderFields.reduce(into: [String: String]()) {
            $0[String(describing: $1.key)] = String(describing: $1.value)
        }
        let result = CameraHTTPResponse(statusCode: response.statusCode, headers: headers, body: body)
        let intact: Bool
        do {
            if let error { throw error }
            try CameraHTTPResponseIntegrity.validate(result, requestMethod: method)
            guard result.statusCode != 206, result.header("content-range") == nil else {
                throw CCAPIError.invalidResponse("Recording requires a complete response.")
            }
            intact = true
        } catch { intact = false }
        continuation.resume(returning: CameraHTTPRecordingResponse(
            response: result, bodyComplete: intact,
            cancelled: cancelled || error is CancellationError || (error as? URLError)?.code == .cancelled
        ))
    }
}
