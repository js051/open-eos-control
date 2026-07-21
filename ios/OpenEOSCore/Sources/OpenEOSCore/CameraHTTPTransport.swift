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
        let (url, response) = try await session.download(for: request)
        let http = try Self.httpResponse(response)
        return CameraHTTPDownloadResponse(
            statusCode: http.statusCode,
            headers: Self.headers(http),
            temporaryFileURL: url
        )
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
