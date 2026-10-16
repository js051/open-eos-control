import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Isolated one-shot DELETE. No redirect, authentication replay, body resupply,
/// fallback endpoint or application retry. Wire behavior still requires Apple tests.
final class CameraHTTPDeleteTask: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private let configuration: URLSessionConfiguration
    private var session: URLSession?
    private var task: URLSessionUploadTask?
    private var continuation: CheckedContinuation<CameraHTTPResponse, Error>?
    private var response: HTTPURLResponse?
    private var body = CameraHTTPResponseBodyAccumulator()
    private var suppliedBody = false
    private var cancelled = false

    init(configuration: URLSessionConfiguration) {
        self.configuration = configuration.copy() as! URLSessionConfiguration
        super.init()
    }
    func run(_ request: URLRequest) async throws -> CameraHTTPResponse {
        guard request.httpMethod == "DELETE", request.httpBody == nil, request.httpBodyStream == nil else {
            throw CameraMediaDeleteError.ineligible
        }
        return try await withTaskCancellationHandler {
            try Task.checkCancellation()
            return try await withCheckedThrowingContinuation { continuation in
                lock.lock()
                guard !cancelled else { lock.unlock(); continuation.resume(throwing: CancellationError()); return }
                self.continuation = continuation
                configuration.waitsForConnectivity = false
                configuration.urlCache = nil
                configuration.urlCredentialStorage = nil
                configuration.httpShouldSetCookies = false
                configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
                var value = request
                value.setValue("0", forHTTPHeaderField: "Content-Length")
                // A streamed task asks for its source. It receives it once; a
                // replay cannot obtain a new stream, including after lost ACK.
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
        lock.unlock()
        completionHandler(allowed ? InputStream(data: Data()) : nil)
    }
    func urlSession(_ session: URLSession, task: URLSessionTask,
                    willPerformHTTPRedirection response: HTTPURLResponse, newRequest request: URLRequest,
                    completionHandler: @escaping (URLRequest?) -> Void) { completionHandler(nil) }
    func urlSession(_ session: URLSession, task: URLSessionTask, didReceive challenge: URLAuthenticationChallenge,
                    completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        // Keep default TLS trust. Credentials are already on the original request;
        // never respond to an authentication challenge by replaying a DELETE.
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
        lock.lock(); let accepted = body.append(data); lock.unlock()
        if !accepted { finish(error: CameraMediaDeleteError.unconfirmed) }
    }
    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) { finish(error: error) }
    private func finish(error: Error?) {
        lock.lock()
        guard let continuation else { lock.unlock(); return }
        self.continuation = nil
        let response = response, body = body.data, cancelled = cancelled
        let activeSession = session, activeTask = task
        session = nil; task = nil
        lock.unlock()
        activeTask?.cancel(); activeSession?.finishTasksAndInvalidate()
        do {
            if let error { throw error }
            guard let response else {
                if cancelled { throw CancellationError() }
                throw CameraMediaDeleteError.unconfirmed
            }
            let headers = response.allHeaderFields.reduce(into: [String: String]()) {
                $0[String(describing: $1.key)] = String(describing: $1.value)
            }
            let result = CameraHTTPResponse(statusCode: response.statusCode, headers: headers, body: body)
            try CameraHTTPResponseIntegrity.validate(result, requestMethod: "DELETE")
            continuation.resume(returning: result)
        } catch { continuation.resume(throwing: error) }
    }
}
