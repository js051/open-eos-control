import Foundation
import XCTest

#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

@testable import OpenEOSCore

/// Intentionally self-contained and limited to APIs that predate shutter recovery.
/// Copy this file alone to the unfixed baseline to reproduce the missing-release regression.
/// This is injected-transport evidence; URLSessionShutterWireTests supplies actual TCP evidence.
final class CCAPIBulbReleaseBaselineTests: XCTestCase {
    func testPublicStopStillReleasesAfterLostStartAndFailedCompensation() async throws {
        for (version, method) in [("ver110", "PUT"), ("ver130", "POST")] {
            let transport = BaselineBulbFaultTransport(version: version, method: method)
            let client = try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: .camera, transport: transport)
            await assertLostStart(client)
            let afterStart = await transport.commands()
            XCTAssertEqual(afterStart.map(\.action), ["full_press", "release"])

            _ = try await client.stopBulbExposure()

            let commands = await transport.commands()
            assertOriginalRelease(commands, version: version, method: method)
        }
    }

    func testPublicCloseStillReleasesAfterLostStartAndFailedCompensation() async throws {
        for (version, method) in [("ver110", "PUT"), ("ver130", "POST")] {
            let transport = BaselineBulbFaultTransport(version: version, method: method)
            let client = try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: .camera, transport: transport)
            await assertLostStart(client)
            let afterStart = await transport.commands()
            XCTAssertEqual(afterStart.map(\.action), ["full_press", "release"])

            await client.close()

            let commands = await transport.commands()
            assertOriginalRelease(commands, version: version, method: method)
        }
    }

    private func assertLostStart(_ client: CCAPIClient, file: StaticString = #filePath, line: UInt = #line) async {
        do {
            _ = try await client.startBulbExposure()
            XCTFail("The full press was received, but its response and the first release response were lost", file: file, line: line)
        } catch {}
    }

    private func assertOriginalRelease(
        _ commands: [BaselineBulbCommand],
        version: String,
        method: String,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        XCTAssertEqual(commands.map(\.action), ["full_press", "release", "release"], file: file, line: line)
        XCTAssertEqual(commands.map(\.method), [method, method, method], file: file, line: line)
        XCTAssertTrue(commands.allSatisfy { $0.path == "/ccapi/\(version)/shooting/control/shutterbutton/manual" },
                      file: file, line: line)
        XCTAssertTrue(commands.allSatisfy { !$0.autofocus }, file: file, line: line)
    }
}

private struct BaselineBulbCommand: Sendable {
    let method: String
    let path: String
    let action: String
    let autofocus: Bool
}

private actor BaselineBulbFaultTransport: CameraHTTPTransport {
    private let version: String
    private let method: String
    private var received: [BaselineBulbCommand] = []
    private var releaseCount = 0

    init(version: String, method: String) {
        self.version = version
        self.method = method
    }

    func send(_ request: URLRequest) async throws -> CameraHTTPResponse {
        let path = request.url?.path ?? ""
        if request.httpMethod == "GET" {
            let body: String
            if path == "/ccapi" {
                body = """
                {"\(version)":[
                  {"path":"/shooting/control/shutterbutton/manual","\(method.lowercased())":true},
                  {"path":"/devicestatus/batterylist","get":true},
                  {"path":"/devicestatus/storage","get":true},
                  {"path":"/shooting/settings","get":true}
                ]}
                """
            } else {
                body = "{}"
            }
            return CameraHTTPResponse(statusCode: 200, body: Data(body.utf8))
        }
        let data = request.httpBody ?? Data()
        let body = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        let action = body?["action"] as? String ?? "missing"
        received.append(BaselineBulbCommand(
            method: request.httpMethod ?? "missing", path: path,
            action: action, autofocus: body?["af"] as? Bool ?? true
        ))
        if action == "full_press" { throw URLError(.networkConnectionLost) }
        if action == "release" {
            releaseCount += 1
            if releaseCount == 1 { throw URLError(.networkConnectionLost) }
            return CameraHTTPResponse(statusCode: 204)
        }
        return CameraHTTPResponse(statusCode: 400)
    }

    func download(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse {
        throw URLError(.unsupportedURL)
    }

    func commands() -> [BaselineBulbCommand] { received }
}
