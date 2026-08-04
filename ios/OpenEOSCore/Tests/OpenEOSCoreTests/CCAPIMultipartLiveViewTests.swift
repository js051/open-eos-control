import Foundation
import XCTest
@testable import OpenEOSCore

final class CCAPIMultipartLiveViewTests: XCTestCase {
    private let jpeg = Data([0xFF, 0xD8, 0x21, 0xFF, 0xD9])

    func testParserAcceptsQuotedBoundaryLFAndArbitraryChunks() throws {
        let boundary = try CCAPIMultipartLiveView.boundary(
            from: "multipart/x-mixed-replace; charset=binary; boundary=\"canon\""
        )
        let body = multipart(boundary: boundary, frame: jpeg)
        var parser = CCAPIMultipartParser(boundary: boundary)
        var frames: [Data] = []

        for chunk in body.chunked(maximum: 3) {
            frames.append(contentsOf: try parser.append(chunk))
        }
        try parser.finish()

        XCTAssertEqual(frames, [jpeg])
    }

    func testParserRejectsMissingBoundaryInvalidLengthAndOversizeBeforeAllocation() throws {
        XCTAssertThrowsError(
            try CCAPIMultipartLiveView.boundary(from: "multipart/x-mixed-replace")
        )
        var invalid = CCAPIMultipartParser(boundary: "b")
        XCTAssertThrowsError(
            try invalid.append(
                Data("--b\nContent-Type: image/jpeg\nContent-Length: -1\n\nx\n--b--\n".utf8)
            )
        )
        var oversized = CCAPIMultipartParser(boundary: "b")
        XCTAssertThrowsError(
            try oversized.append(
                Data(
                    (
                        "--b\nContent-Type: image/jpeg\nContent-Length: " +
                            "\(CCAPIMultipartLiveView.maximumFrameBytes + 1)\n\n"
                    ).utf8
                )
            )
        )
    }

    func testClientUsesAdvertisedMultipartLifecycleAndObservesOnlyARealFrame() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(path: "/ccapi", body: Self.discovery)
        await transport.enqueue(method: "POST", path: "/ccapi/ver110/shooting/liveview", body: Data())
        await transport.enqueue(
            path: "/ccapi/ver110/shooting/liveview/multipart",
            headers: ["content-type": "multipart/x-mixed-replace;boundary=canon"],
            body: multipart(boundary: "canon", frame: jpeg)
        )
        await transport.enqueueJSON(
            method: "DELETE",
            path: "/ccapi/ver110/shooting/liveview/multipart",
            body: "{}"
        )
        await transport.enqueue(method: "DELETE", path: "/ccapi/ver110/shooting/liveview", body: Data())
        let client = try CCAPIClient(
            baseURL: "http://192.168.1.2:8080",
            mode: .camera,
            transport: transport
        )

        let capabilities = try await client.capabilities()
        XCTAssertTrue(capabilities.matrix.supports(.liveViewMultipart))
        XCTAssertEqual(capabilities.liveView.sources, [.ccapiMultipart, .ccapiJPEGPolling])
        XCTAssertFalse(capabilities.evidence.observedFeatures.contains(.liveViewMultipart))

        try await client.startLiveView(LiveViewRequest(fps: 15, source: .auto))
        let active = await client.currentLiveViewSource()
        XCTAssertEqual(active, .ccapiMultipart)
        let frame = try await client.liveViewFrame(cacheKey: 1)
        XCTAssertEqual(frame.data, jpeg)
        XCTAssertEqual(frame.contentType, "image/jpeg")
        XCTAssertTrue(frame.sourceURL.path.hasSuffix("/shooting/liveview/multipart"))
        let observed = try await client.capabilities()
        XCTAssertTrue(observed.evidence.observedFeatures.contains(.liveViewMultipart))
        await client.stopLiveView()

        let requests = await transport.requests()
        XCTAssertEqual(
            requests.map { "\($0.method) \($0.path)" },
            [
                "GET /ccapi",
                "POST /ccapi/ver110/shooting/liveview",
                "GET /ccapi/ver110/shooting/liveview/multipart",
                "DELETE /ccapi/ver110/shooting/liveview/multipart",
                "DELETE /ccapi/ver110/shooting/liveview",
            ]
        )
    }

    func testClientDoesNotCombineMultipartLifecycleAcrossAPIVersions() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(
            path: "/ccapi",
            body: """
            {
              "ver100":[{"path":"/shooting/liveview","post":true,"delete":true}],
              "ver110":[{"path":"/shooting/liveview/multipart","get":true,"delete":true}]
            }
            """
        )
        let client = try CCAPIClient(
            baseURL: "http://192.168.1.2:8080",
            mode: .camera,
            transport: transport
        )

        let capabilities = try await client.capabilities()
        XCTAssertFalse(capabilities.matrix.supports(.liveViewMultipart))
        XCTAssertTrue(capabilities.matrix.planned.contains(.liveViewMultipart))
        do {
            try await client.startLiveView(LiveViewRequest(source: .ccapiMultipart))
            XCTFail("Expected unsupported cross-version multipart lifecycle")
        } catch {
            XCTAssertEqual(error as? CCAPIError, .unsupported(.liveViewMultipart))
        }
        let requests = await transport.requests()
        XCTAssertEqual(requests.count, 1)
    }

    private func multipart(boundary: String, frame: Data) -> Data {
        var value = Data(
            "--\(boundary)\nContent-Type: image/jpeg\nContent-Length: \(frame.count)\n\n".utf8
        )
        value.append(frame)
        value.append(Data("\n--\(boundary)--\n".utf8))
        return value
    }

    private static let discovery = """
        {
          "ver110":[
            {"path":"/shooting/liveview","post":true,"delete":true},
            {"path":"/shooting/liveview/flip","get":true},
            {"path":"/shooting/liveview/multipart","get":true,"delete":true}
          ]
        }
        """
}

private extension Data {
    func chunked(maximum: Int) -> [Data] {
        stride(from: startIndex, to: endIndex, by: maximum).map { start in
            Data(self[start..<Swift.min(start + maximum, endIndex)])
        }
    }
}
