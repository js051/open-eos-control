import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Scoped to camera maintenance on the existing HTTP transport. One body
/// source, no redirect/authentication replay, bounded complete response evidence.
/// This does not assert physical-camera or native wire acceptance without tests.
final class CameraHTTPMaintenanceTask: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private let configuration: URLSessionConfiguration
    private var session: URLSession?
    private var task: URLSessionUploadTask?
    private var continuation: CheckedContinuation<CameraHTTPResponse, Error>?
    private var response: HTTPURLResponse?
    private var body = Data()
    private var source = Data()
    private var method = "GET"
    private var suppliedBody = false
    private var cancelled = false
    private static let maximumBytes = 64 * 1024

    init(configuration: URLSessionConfiguration) {
        self.configuration = configuration.copy() as! URLSessionConfiguration
        super.init()
    }
    func run(_ request: URLRequest) async throws -> CameraHTTPResponse {
        guard ["GET", "POST", "PUT", "DELETE"].contains(request.httpMethod ?? ""), request.httpBodyStream == nil,
              request.timeoutInterval > 0, request.timeoutInterval.isFinite,
              (request.httpBody?.count ?? 0) <= Self.maximumBytes else {
            throw CCAPIError.invalidResponse("Invalid camera maintenance request.")
        }
        return try await withTaskCancellationHandler {
            try Task.checkCancellation()
            return try await withCheckedThrowingContinuation { continuation in
                lock.lock()
                guard !cancelled else { lock.unlock(); continuation.resume(throwing: CancellationError()); return }
                self.continuation = continuation
                source = request.httpBody ?? Data()
                method = request.httpMethod ?? "GET"
                configuration.waitsForConnectivity = false
                configuration.urlCache = nil
                configuration.urlCredentialStorage = nil
                configuration.httpShouldSetCookies = false
                configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
                configuration.timeoutIntervalForRequest = request.timeoutInterval
                configuration.timeoutIntervalForResource = request.timeoutInterval
                var value = request
                value.httpBody = nil
                value.setValue(String(source.count), forHTTPHeaderField: "Content-Length")
                value.setValue("no-cache", forHTTPHeaderField: "Cache-Control")
                let session = URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
                let task = session.uploadTask(withStreamedRequest: value)
                self.session = session; self.task = task
                lock.unlock()
                task.resume()
            }
        } onCancel: { self.cancel() }
    }
    private func cancel() {
        lock.lock(); cancelled = true; let task = task; lock.unlock()
        task?.cancel()
    }
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    needNewBodyStream completionHandler: @escaping (InputStream?) -> Void) {
        lock.lock()
        let allowed = !suppliedBody && !cancelled
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
        lock.lock(); self.response = response as? HTTPURLResponse; lock.unlock()
        completionHandler(response is HTTPURLResponse ? .allow : .cancel)
    }
    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
        lock.lock()
        let accepted = data.count <= Self.maximumBytes - body.count
        if accepted { body.append(data) }
        lock.unlock()
        if !accepted { finish(error: CCAPIError.invalidResponse("Camera maintenance response exceeded 64 KiB.")) }
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) { finish(error: error) }
    private func finish(error: Error?) {
        lock.lock()
        guard let continuation else { lock.unlock(); return }
        self.continuation = nil
        let response = response, body = body, method = method, cancelled = cancelled
        let activeSession = session, activeTask = task
        session = nil; task = nil; source = Data()
        lock.unlock()
        activeTask?.cancel(); activeSession?.finishTasksAndInvalidate()
        do {
            // Only an intact EOF can yield ACK bytes. A full response completed
            // just before cancel may still settle its original attempt.
            if let error { throw error }
            guard let response else {
                if cancelled { throw CancellationError() }
                throw CCAPIError.invalidResponse("Camera maintenance response did not complete.")
            }
            let headers = response.allHeaderFields.reduce(into: [String: String]()) {
                $0[String(describing: $1.key)] = String(describing: $1.value)
            }
            let result = CameraHTTPResponse(statusCode: response.statusCode, headers: headers, body: body)
            try CameraHTTPResponseIntegrity.validate(result, requestMethod: method)
            continuation.resume(returning: result)
        } catch { continuation.resume(throwing: error) }
    }
}
