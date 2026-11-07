import Foundation
import XCTest

#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

@testable import OpenEOSCore

final class CameraConnectionFailureTests: XCTestCase {
    private let endpoint = "http://camera.invalid:8080"
    private let discovery = #"{"ver100":[{"path":"/deviceinformation","get":true}]}"#
    private let discoveryPaths = ["/ccapi", "/ccapi/", "/ccapi/ver110/deviceinformation", "/ccapi/ver100/deviceinformation"]

    func testInvalidCameraAndBridgeURLsProduceOnlyInvalidAddressReason() {
        for address in ["not a URL", "ftp://camera.invalid", "http://camera.invalid/ccapi",
                        "http://synthetic:private@camera.invalid", "http://camera.invalid?token=synthetic"] {
            XCTAssertThrowsError(try CCAPIClient(baseURL: address)) {
                XCTAssertEqual(CameraConnectionFailureReason.classify($0), .invalidAddress)
            }
        }
        XCTAssertThrowsError(try DesktopBridgeClient(baseURL: "not a URL")) {
            XCTAssertEqual(CameraConnectionFailureReason.classify($0), .invalidAddress)
        }
    }

    func testAllMissingDiscoveryEndpointsStayMissing() async throws {
        let transport = ConnectionReasonTransport(plans: Array(repeating: .http(404), count: 4))
        let client = try CCAPIClient(baseURL: endpoint, mode: .camera, transport: transport)
        await assertFailure(client, reason: .endpointNotFound, reasons: [.endpointNotFound])
        let paths = await transport.paths()
        XCTAssertEqual(paths, discoveryPaths)
    }

    func testAuthenticationSurvivesLaterMissingFallbacksWithExactRequestCredentials() async throws {
        for status in [401, 403] {
            let transport = ConnectionReasonTransport(plans: [.http(status), .http(404), .http(404), .http(404)])
            let client = try CCAPIClient(baseURL: endpoint, mode: .camera, username: "synthetic",
                                         password: "fixture", transport: transport)
            await assertFailure(client, reason: .authenticationRejected,
                                reasons: [.authenticationRejected, .endpointNotFound])
            let requests = await transport.requests()
            XCTAssertEqual(requests.map { $0.url?.absoluteString ?? "" }, discoveryPaths.map { endpoint + $0 })
            XCTAssertTrue(requests.allSatisfy { $0.httpMethod == "GET" })
            XCTAssertTrue(requests.allSatisfy { $0.url?.host == "camera.invalid" && $0.url?.port == 8080 })
            let expected = "Basic " + Data("synthetic:fixture".utf8).base64EncodedString()
            XCTAssertEqual(requests.map { $0.value(forHTTPHeaderField: "Authorization") ?? "" }, Array(repeating: expected, count: 4))
        }
    }

    func testDeveloperFallbackAuthenticationIsPreserved() async throws {
        let transport = ConnectionReasonTransport(plans: [
            .json(#"{"value":"No list of APIs"}"#), .http(401), .http(404), .http(404), .http(404),
        ])
        let client = try CCAPIClient(baseURL: endpoint, mode: .camera, transport: transport)
        await assertFailure(client, reason: .authenticationRejected,
                            reasons: [.authenticationRejected, .endpointNotFound])
        let paths = await transport.paths()
        XCTAssertEqual(paths, ["/ccapi", "/ccapi/ver100/topurlfordev"] + Array(discoveryPaths.dropFirst()))
    }

    func testTLSAndNetworkEvidenceSurvivesMissingFallbacks() async throws {
        let cases: [(URLError.Code, CameraConnectionFailureReason)] = [
            (.secureConnectionFailed, .tlsError), (.serverCertificateUntrusted, .tlsError),
            (.cannotFindHost, .hostNotFound), (.dnsLookupFailed, .hostNotFound),
            (.timedOut, .timeout), (.cannotConnectToHost, .unreachable),
            (.networkConnectionLost, .unreachable), (.notConnectedToInternet, .unreachable),
        ]
        for (code, reason) in cases {
            let transport = ConnectionReasonTransport(plans: [.urlError(code), .http(404), .http(404), .http(404)])
            let client = try CCAPIClient(baseURL: endpoint, mode: .camera, transport: transport)
            await assertFailure(client, reason: reason, reasons: [reason, .endpointNotFound])
        }
    }

    func testUnknownAndForgedServerTextCannotClaimAuthentication() async throws {
        XCTAssertEqual(CameraConnectionFailureReason.classify(ForgedConnectionMessage()), .unknown)
        XCTAssertEqual(CameraConnectionFailureReason.classify(CCAPIError.discoveryFailed(["HTTP 401 password rejected"])),
                       .discoveryFailed)
        let error = DesktopBridgeError.http(statusCode: 502, method: "GET", url: endpoint,
                                           code: "AUTHENTICATION_REJECTED", message: "HTTP 401 token rejected",
                                           feature: nil, engine: "ccapi")
        XCTAssertEqual(CameraConnectionFailureReason.classify(error), .httpError)
        let transport = ConnectionReasonTransport(plans: [.unknown, .http(404), .http(404), .http(404)])
        let client = try CCAPIClient(baseURL: endpoint, mode: .camera, transport: transport)
        await assertFailure(client, reason: .unknown, reasons: [.unknown, .endpointNotFound])
    }

    func testSuccessfulDiscoveryAndIdentityFallbackWinAfterAuthenticationFailure() async throws {
        let scenarios: [[ConnectionReasonTransport.Plan]] = [
            [.http(401), .json(discovery)],
            [.http(403), .http(404), .json(#"{"productname":"Synthetic EOS"}"#)],
        ]
        for plans in scenarios {
            let transport = ConnectionReasonTransport(plans: plans)
            let client = try CCAPIClient(baseURL: endpoint, mode: .camera, transport: transport)
            try await client.initialize()
            let requests = await transport.requests()
            XCTAssertEqual(requests.count, plans.count)
        }
    }

    func testExplicitCorrectedRetryHasNoOldFailureEvidence() async throws {
        let transport = ConnectionReasonTransport(plans: [.http(401), .http(404), .http(404), .http(404), .json(discovery)])
        let client = try CCAPIClient(baseURL: endpoint, mode: .camera, transport: transport)
        await assertFailure(client, reason: .authenticationRejected,
                            reasons: [.authenticationRejected, .endpointNotFound])
        let beforeRetry = await transport.requests()
        XCTAssertEqual(beforeRetry.count, 4)
        try await client.initialize()
        let afterRetry = await transport.paths()
        XCTAssertEqual(afterRetry, discoveryPaths + ["/ccapi"])
    }

    func testConnectIdentityReadPreservesAuthenticationBeforeMissingFallback() async throws {
        let transport = ConnectionReasonTransport(plans: [
            .json(#"{"ver110":[{"path":"/deviceinformation","get":true}],"ver100":[{"path":"/deviceinformation","get":true}]}"#),
            .http(401), .http(404),
        ])
        let client = try CCAPIClient(baseURL: endpoint, mode: .camera, transport: transport)
        do {
            _ = try await client.connectSnapshot()
            XCTFail("Expected the required identity read to fail")
        } catch {
            XCTAssertEqual(CameraConnectionFailureReason.classify(error), .authenticationRejected)
            guard case let CCAPIError.connectionReadFailed(_, reasons) = error else {
                return XCTFail("Expected typed identity failure evidence")
            }
            XCTAssertEqual(reasons, [.authenticationRejected, .endpointNotFound])
        }
        let paths = await transport.paths()
        XCTAssertEqual(paths, ["/ccapi", "/ccapi/ver110/deviceinformation", "/ccapi/ver100/deviceinformation"])
    }

    func testCancelledIdentityReadDoesNotRunItsFallback() async throws {
        let transport = ConnectionReasonTransport(plans: [
            .json(#"{"ver110":[{"path":"/deviceinformation","get":true}],"ver100":[{"path":"/deviceinformation","get":true}]}"#),
            .urlError(.cancelled), .http(404),
        ])
        let client = try CCAPIClient(baseURL: endpoint, mode: .camera, transport: transport)
        do {
            _ = try await client.connectSnapshot()
            XCTFail("Expected cancellation")
        } catch {
            XCTAssertTrue(CameraConnectionFailureReason.isCancellation(error))
            XCTAssertNil(CameraConnectionFailureReason.classify(error))
        }
        let paths = await transport.paths()
        XCTAssertEqual(paths, ["/ccapi", "/ccapi/ver110/deviceinformation"])
    }

    func testCancellationDoesNotRunFallbackOrProduceRecoveryReason() async throws {
        let plans: [ConnectionReasonTransport.Plan] = [.cancelled, .urlError(.cancelled)]
        for plan in plans {
            let transport = ConnectionReasonTransport(plans: [plan, .json(discovery)])
            let client = try CCAPIClient(baseURL: endpoint, mode: .camera, transport: transport)
            do {
                try await client.initialize()
                XCTFail("Expected cancellation")
            } catch {
                XCTAssertTrue(CameraConnectionFailureReason.isCancellation(error))
                XCTAssertNil(CameraConnectionFailureReason.classify(error))
            }
            let paths = await transport.paths()
            XCTAssertEqual(paths, ["/ccapi"])
        }
    }

    private func assertFailure(_ client: CCAPIClient, reason: CameraConnectionFailureReason,
                               reasons: Set<CameraConnectionFailureReason>,
                               file: StaticString = #filePath, line: UInt = #line) async {
        do {
            try await client.initialize()
            XCTFail("Expected discovery failure", file: file, line: line)
        } catch {
            XCTAssertEqual(CameraConnectionFailureReason.classify(error), reason, file: file, line: line)
            guard case let CCAPIError.discoveryFailedWithReasons(_, evidence) = error else {
                return XCTFail("Expected typed terminal discovery evidence", file: file, line: line)
            }
            XCTAssertEqual(evidence, reasons, file: file, line: line)
            XCTAssertLessThanOrEqual(evidence.count, CameraConnectionFailureReason.allCases.count, file: file, line: line)
        }
    }
}

private struct ForgedConnectionMessage: LocalizedError {
    var errorDescription: String? { "HTTP 401 unauthorized password token TLS timeout" }
}

private actor ConnectionReasonTransport: CameraHTTPTransport {
    enum Plan: Sendable {
        case http(Int), json(String), urlError(URLError.Code), unknown, cancelled
    }
    private var plans: [Plan]
    private var recorded: [URLRequest] = []

    init(plans: [Plan]) { self.plans = plans }

    func send(_ request: URLRequest) async throws -> CameraHTTPResponse {
        recorded.append(request)
        guard !plans.isEmpty else { throw ForgedConnectionMessage() }
        switch plans.removeFirst() {
        case let .http(status):
            return CameraHTTPResponse(statusCode: status, body: Data(#"{"message":"HTTP 401 password rejected"}"#.utf8))
        case let .json(json): return CameraHTTPResponse(statusCode: 200, body: Data(json.utf8))
        case let .urlError(code): throw URLError(code)
        case .unknown: throw ForgedConnectionMessage()
        case .cancelled: throw CancellationError()
        }
    }

    func download(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse { throw ForgedConnectionMessage() }
    func paths() -> [String] { recorded.map { URLComponents(url: $0.url!, resolvingAgainstBaseURL: false)?.path ?? "" } }
    func requests() -> [URLRequest] { recorded }
}
