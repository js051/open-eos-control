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
        let observedAfterStart = try await client.capabilities()
        XCTAssertTrue(observedAfterStart.evidence.observedFeatures.contains(.liveViewMultipart))
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

    func testPostOnlyGeneralLifecycleUsesMultipartAndAlwaysSendsPostOffAfterReader503() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(path: "/ccapi", body: Self.postOnlyDiscovery)
        let streamBody = multipart(boundary: "canon", frame: jpeg)
        await transport.enqueue(
            method: "POST",
            path: "/ccapi/ver130/shooting/liveview",
            status: 204,
            body: Data()
        )
        await transport.enqueue(
            method: "GET",
            path: "/ccapi/ver130/shooting/liveview/multipart",
            headers: ["content-type": "multipart/x-mixed-replace;boundary=canon"],
            body: streamBody
        )
        await transport.enqueue(
            method: "DELETE",
            path: "/ccapi/ver130/shooting/liveview/multipart",
            status: 503,
            body: #"{"message":"Mode not supported"}"#.data(using: .utf8)!
        )
        await transport.enqueue(
            method: "POST",
            path: "/ccapi/ver130/shooting/liveview",
            status: 204,
            body: Data()
        )
        let client = try CCAPIClient(
            baseURL: "http://192.168.1.2:8080",
            mode: .camera,
            transport: transport
        )

        let capabilities = try await client.capabilities()
        XCTAssertEqual(capabilities.liveView.sources, [.ccapiMultipart])
        try await client.startLiveView(LiveViewRequest(source: .auto))
        let activeSource = await client.currentLiveViewSource()
        XCTAssertEqual(activeSource, .ccapiMultipart)
        let frame = try await client.liveViewFrame(cacheKey: 31)
        XCTAssertEqual(frame.data, jpeg)
        await client.stopLiveView()

        let requests = await transport.requests()
        XCTAssertEqual(requests.map { "\($0.method) \($0.path)" }, [
            "GET /ccapi",
            "POST /ccapi/ver130/shooting/liveview",
            "GET /ccapi/ver130/shooting/liveview/multipart",
            "DELETE /ccapi/ver130/shooting/liveview/multipart",
            "POST /ccapi/ver130/shooting/liveview",
        ])
        let stopBody = try XCTUnwrap(requests.last?.body)
        let stop = try XCTUnwrap(JSONSerialization.jsonObject(with: stopBody) as? [String: String])
        XCTAssertEqual(stop, ["liveviewsize": "off", "cameradisplay": "on"])
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

    func testAutoFallsBackToJPEGPollingWhenMultipartFirstFrameIsInvalid() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(path: "/ccapi", body: Self.discovery)
        await transport.enqueue(method: "POST", path: "/ccapi/ver110/shooting/liveview", body: Data())
        await transport.enqueue(
            path: "/ccapi/ver110/shooting/liveview/multipart",
            headers: ["content-type": "multipart/x-mixed-replace;boundary=canon"],
            body: Data("--canon\nContent-Type: image/jpeg\nContent-Length: 3\n\nno\n--canon--\n".utf8)
        )
        await transport.enqueueJSON(
            method: "DELETE",
            path: "/ccapi/ver110/shooting/liveview/multipart",
            body: "{}"
        )
        await transport.enqueue(method: "DELETE", path: "/ccapi/ver110/shooting/liveview", body: Data())
        await transport.enqueue(method: "POST", path: "/ccapi/ver110/shooting/liveview", body: Data())
        let client = try CCAPIClient(
            baseURL: "http://192.168.1.2:8080",
            mode: .camera,
            transport: transport
        )

        try await client.startLiveView(LiveViewRequest(source: .auto))

        let active = await client.currentLiveViewSource()
        XCTAssertEqual(active, .ccapiJPEGPolling)
        let capabilities = try await client.capabilities()
        XCTAssertFalse(capabilities.evidence.observedFeatures.contains(.liveViewMultipart))
        XCTAssertTrue(capabilities.evidence.observedFeatures.contains(.liveViewJPEGPolling))
        let requests = await transport.requests()
        XCTAssertEqual(
            requests.map { "\($0.method) \($0.path)" },
            [
                "GET /ccapi",
                "POST /ccapi/ver110/shooting/liveview",
                "GET /ccapi/ver110/shooting/liveview/multipart",
                "DELETE /ccapi/ver110/shooting/liveview/multipart",
                "DELETE /ccapi/ver110/shooting/liveview",
                "POST /ccapi/ver110/shooting/liveview",
            ]
        )
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

    private static let postOnlyDiscovery = """
    {
      "ver130":[
        {"path":"/shooting/liveview","post":true},
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
