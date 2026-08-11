import Foundation
import XCTest
@testable import OpenEOSCore

final class CCAPIClientRTPTests: XCTestCase {
    func testRTPRequiresAdvertisedEndpointsDestinationAndNativeFactory() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(path: "/ccapi", body: Self.discovery)
        let client = try CCAPIClient(
            baseURL: "http://192.168.1.2:8080",
            mode: .camera,
            transport: transport
        )

        let capabilities = try await client.capabilities()

        XCTAssertFalse(capabilities.matrix.supports(.liveViewRTP))
        XCTAssertEqual(capabilities.liveView.sources, [.ccapiJPEGPolling])
    }

    func testStartsAndStopsAdvertisedCanonRTPWithExactPayloads() async throws {
        let transport = MockCameraHTTPTransport()
        let nativeSession = MockRTPSession()
        let factory = MockRTPSessionFactory(session: nativeSession)
        await transport.enqueueJSON(path: "/ccapi", body: Self.discovery)
        await transport.enqueue(
            path: "/ccapi/ver130/shooting/liveview/rtpsessiondesc",
            headers: ["content-type": "application/sdp"],
            body: Data(Self.canonSDP.utf8)
        )
        await transport.enqueue(
            method: "POST",
            path: "/ccapi/ver130/shooting/liveview/rtp",
            status: 204,
            body: Data()
        )
        await transport.enqueue(
            method: "POST",
            path: "/ccapi/ver130/shooting/liveview/rtp",
            status: 204,
            body: Data()
        )
        let client = try CCAPIClient(
            baseURL: "http://192.168.1.2:8080",
            mode: .camera,
            rtpDestinationAddress: "192.168.1.3",
            rtpSessionFactory: factory,
            transport: transport
        )

        let capabilities = try await client.capabilities()
        XCTAssertTrue(capabilities.matrix.supports(.liveViewRTP))
        XCTAssertEqual(capabilities.liveView.sources, [.ccapiRTP, .ccapiJPEGPolling])

        try await client.startLiveView(LiveViewRequest(fps: 24, size: .large, source: .auto))
        let activeSource = await client.currentLiveViewSource()
        let activeURL = await client.currentNativeLiveViewSourceURL()
        XCTAssertEqual(activeSource, .ccapiRTP)
        XCTAssertEqual(activeURL?.absoluteString, "rtp://192.168.1.3:12000")
        await client.stopLiveView()

        let state = await nativeSession.state()
        XCTAssertTrue(state.started)
        XCTAssertTrue(state.closed)
        XCTAssertEqual(state.targetFPS, 24)
        let factoryState = await factory.state()
        XCTAssertEqual(factoryState.destinationAddress, "192.168.1.3")
        XCTAssertEqual(factoryState.description?.video.port, 12_000)

        let requests = await transport.requests()
        let commands = try requests.filter { $0.path.hasSuffix("/shooting/liveview/rtp") }.map { request in
            try XCTUnwrap(JSONSerialization.jsonObject(with: XCTUnwrap(request.body)) as? [String: String])
        }
        XCTAssertEqual(commands.count, 2)
        XCTAssertEqual(commands[0], ["action": "start", "ipaddress": "192.168.1.3"])
        XCTAssertEqual(commands[1], ["action": "stop", "ipaddress": ""])
    }

    func testAutomaticRTPFailureCleansUpAndFallsBackToJPEG() async throws {
        let transport = MockCameraHTTPTransport()
        let nativeSession = MockRTPSession(startError: TestError.listenerFailed)
        let factory = MockRTPSessionFactory(session: nativeSession)
        await transport.enqueueJSON(path: "/ccapi", body: Self.discovery)
        await transport.enqueue(
            path: "/ccapi/ver130/shooting/liveview/rtpsessiondesc",
            headers: ["content-type": "application/sdp"],
            body: Data(Self.canonSDP.utf8)
        )
        await transport.enqueue(
            method: "POST",
            path: "/ccapi/ver130/shooting/liveview/rtp",
            status: 204,
            body: Data()
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
            rtpDestinationAddress: "192.168.1.3",
            rtpSessionFactory: factory,
            transport: transport
        )

        try await client.startLiveView(LiveViewRequest(fps: 15, size: .medium, source: .auto))

        let activeSource = await client.currentLiveViewSource()
        let nativeState = await nativeSession.state()
        XCTAssertEqual(activeSource, .ccapiJPEGPolling)
        XCTAssertTrue(nativeState.closed)
        let requests = await transport.requests()
        XCTAssertEqual(
            requests.map { "\($0.method) \($0.path)" },
            [
                "GET /ccapi",
                "GET /ccapi/ver130/shooting/liveview/rtpsessiondesc",
                "POST /ccapi/ver130/shooting/liveview/rtp",
                "POST /ccapi/ver130/shooting/liveview",
            ]
        )
    }

    func testAutomaticRTPHTTPStartFailureStopsRTPAndFallsBackToPostOnlyJPEG() async throws {
        let transport = MockCameraHTTPTransport()
        let nativeSession = MockRTPSession()
        let factory = MockRTPSessionFactory(session: nativeSession)
        await transport.enqueueJSON(path: "/ccapi", body: Self.postOnlyDiscovery)
        await transport.enqueue(
            path: "/ccapi/ver130/shooting/liveview/rtpsessiondesc",
            headers: ["content-type": "application/sdp"],
            body: Data(Self.canonSDP.utf8)
        )
        await transport.enqueue(
            method: "POST",
            path: "/ccapi/ver130/shooting/liveview/rtp",
            status: 503,
            body: #"{"message":"Mode not supported"}"#.data(using: .utf8)!
        )
        await transport.enqueue(
            method: "POST",
            path: "/ccapi/ver130/shooting/liveview/rtp",
            status: 204,
            body: Data()
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
            rtpDestinationAddress: "192.168.1.3",
            rtpSessionFactory: factory,
            transport: transport
        )

        try await client.startLiveView(LiveViewRequest(source: .auto))

        let activeSource = await client.currentLiveViewSource()
        XCTAssertEqual(activeSource, .ccapiJPEGPolling)
        let nativeState = await nativeSession.state()
        XCTAssertTrue(nativeState.closed)
        let requests = await transport.requests()
        XCTAssertEqual(requests.map { "\($0.method) \($0.path)" }, [
            "GET /ccapi",
            "GET /ccapi/ver130/shooting/liveview/rtpsessiondesc",
            "POST /ccapi/ver130/shooting/liveview/rtp",
            "POST /ccapi/ver130/shooting/liveview/rtp",
            "POST /ccapi/ver130/shooting/liveview",
        ])
        let rtpCommands = try requests
            .filter { $0.path.hasSuffix("/shooting/liveview/rtp") }
            .map { request in
                try XCTUnwrap(JSONSerialization.jsonObject(with: XCTUnwrap(request.body)) as? [String: String])
            }
        XCTAssertEqual(rtpCommands, [
            ["action": "start", "ipaddress": "192.168.1.3"],
            ["action": "stop", "ipaddress": ""],
        ])
    }

    func testAutomaticRTPFailurePrefersSameVersionPostOnlyMultipartLifecycle() async throws {
        let transport = MockCameraHTTPTransport()
        let nativeSession = MockRTPSession()
        let factory = MockRTPSessionFactory(session: nativeSession)
        let jpeg = Data([0xFF, 0xD8, 0x31, 0x32, 0xFF, 0xD9])
        await transport.enqueueJSON(path: "/ccapi", body: Self.postOnlyMultipartDiscovery)
        await transport.enqueue(
            path: "/ccapi/ver130/shooting/liveview/rtpsessiondesc",
            headers: ["content-type": "application/sdp"],
            body: Data(Self.canonSDP.utf8)
        )
        await transport.enqueue(
            method: "POST",
            path: "/ccapi/ver130/shooting/liveview/rtp",
            status: 503,
            body: #"{"message":"Mode not supported"}"#.data(using: .utf8)!
        )
        await transport.enqueue(method: "POST", path: "/ccapi/ver130/shooting/liveview/rtp", status: 204, body: Data())
        await transport.enqueue(method: "POST", path: "/ccapi/ver130/shooting/liveview", status: 204, body: Data())
        await transport.enqueue(
            method: "GET",
            path: "/ccapi/ver130/shooting/liveview/multipart",
            headers: ["content-type": "multipart/x-mixed-replace;boundary=canon"],
            body: multipart(boundary: "canon", frame: jpeg)
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
            rtpDestinationAddress: "192.168.1.3",
            rtpSessionFactory: factory,
            transport: transport
        )

        try await client.startLiveView(LiveViewRequest(source: .auto))
        let activeSource = await client.currentLiveViewSource()
        XCTAssertEqual(activeSource, .ccapiMultipart)
        let frame = try await client.liveViewFrame(cacheKey: 32)
        XCTAssertEqual(frame.data, jpeg)
        await client.stopLiveView()

        let requests = await transport.requests()
        XCTAssertEqual(requests.map { "\($0.method) \($0.path)" }, [
            "GET /ccapi",
            "GET /ccapi/ver130/shooting/liveview/rtpsessiondesc",
            "POST /ccapi/ver130/shooting/liveview/rtp",
            "POST /ccapi/ver130/shooting/liveview/rtp",
            "POST /ccapi/ver130/shooting/liveview",
            "GET /ccapi/ver130/shooting/liveview/multipart",
            "DELETE /ccapi/ver130/shooting/liveview/multipart",
            "POST /ccapi/ver130/shooting/liveview",
        ])
        let nativeState = await nativeSession.state()
        XCTAssertTrue(nativeState.closed)
    }

    func testRTPCoordinateFocusFetchesRealCanonGeometryBeforeWriting() async throws {
        let transport = MockCameraHTTPTransport()
        let nativeSession = MockRTPSession()
        let factory = MockRTPSessionFactory(session: nativeSession)
        let discovery = Self.discovery.replacingOccurrences(
            of: #"{"path":"/shooting/liveview/flip","get":true},"#,
            with: #"{"path":"/shooting/liveview/flip","get":true},{"path":"/shooting/liveview/flipdetail","get":true},{"path":"/shooting/liveview/afframeposition","put":true},"#
        )
        await transport.enqueueJSON(path: "/ccapi", body: discovery)
        await transport.enqueue(
            path: "/ccapi/ver130/shooting/liveview/rtpsessiondesc",
            headers: ["content-type": "application/sdp"],
            body: Data(Self.canonSDP.utf8)
        )
        await transport.enqueue(method: "POST", path: "/ccapi/ver130/shooting/liveview/rtp", status: 204, body: Data())
        await transport.enqueue(
            path: "/ccapi/ver130/shooting/liveview/flipdetail?kind=both",
            headers: ["content-type": "application/octet-stream"],
            body: detailedLiveView()
        )
        await transport.enqueue(
            method: "PUT",
            path: "/ccapi/ver130/shooting/liveview/afframeposition",
            status: 204,
            body: Data()
        )
        await transport.enqueue(method: "POST", path: "/ccapi/ver130/shooting/liveview/rtp", status: 204, body: Data())
        let client = try CCAPIClient(
            baseURL: "http://192.168.1.2:8080",
            mode: .camera,
            rtpDestinationAddress: "192.168.1.3",
            rtpSessionFactory: factory,
            transport: transport
        )

        try await client.startLiveView(LiveViewRequest(source: .ccapiRTP))
        _ = try await client.tapFocus(x: 0.25, y: 0.75)
        await client.stopLiveView()

        let requests = await transport.requests()
        let focusRequest = try XCTUnwrap(
            requests.first { $0.path.hasSuffix("/shooting/liveview/afframeposition") }
        )
        let body = try XCTUnwrap(focusRequest.body)
        let payload = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Int])
        XCTAssertEqual(payload, ["positionx": 1_600, "positiony": 3_200])
    }

    private enum TestError: Error, Sendable {
        case listenerFailed
    }

    private actor MockRTPSession: CCAPIRTPSession {
        struct State: Sendable {
            let started: Bool
            let closed: Bool
            let targetFPS: Int
        }

        nonisolated let sourceURL = URL(string: "rtp://192.168.1.3:12000")!
        private let startError: TestError?
        private var started = false
        private var closed = false
        private var targetFPS = 0

        init(startError: TestError? = nil) {
            self.startError = startError
        }

        func start() async throws {
            if let startError { throw startError }
            started = true
        }

        func setTargetFPS(_ fps: Int) async {
            targetFPS = fps
        }

        func close() async {
            closed = true
        }

        func state() -> State {
            State(started: started, closed: closed, targetFPS: targetFPS)
        }
    }

    private actor MockRTPSessionFactory: CCAPIRTPSessionFactory {
        struct State: Sendable {
            let description: CCAPIRTPSessionDescription?
            let destinationAddress: String?
        }

        private let session: MockRTPSession
        private var description: CCAPIRTPSessionDescription?
        private var destinationAddress: String?

        init(session: MockRTPSession) {
            self.session = session
        }

        func makeSession(
            description: CCAPIRTPSessionDescription,
            destinationAddress: String
        ) async -> any CCAPIRTPSession {
            self.description = description
            self.destinationAddress = destinationAddress
            return session
        }

        func state() -> State {
            State(description: description, destinationAddress: destinationAddress)
        }
    }

    private static let discovery = """
    {
      "ver130": [
        {"path":"/shooting/liveview","post":true,"delete":true},
        {"path":"/shooting/liveview/flip","get":true},
        {"path":"/shooting/liveview/rtpsessiondesc","get":true},
        {"path":"/shooting/liveview/rtp","post":true}
      ]
    }
    """

    private static let postOnlyDiscovery = """
    {
      "ver130": [
        {"path":"/shooting/liveview","post":true},
        {"path":"/shooting/liveview/flip","get":true},
        {"path":"/shooting/liveview/rtpsessiondesc","get":true},
        {"path":"/shooting/liveview/rtp","post":true}
      ]
    }
    """

    private static let postOnlyMultipartDiscovery = """
    {
      "ver130": [
        {"path":"/shooting/liveview","post":true},
        {"path":"/shooting/liveview/multipart","get":true,"delete":true},
        {"path":"/shooting/liveview/rtpsessiondesc","get":true},
        {"path":"/shooting/liveview/rtp","post":true}
      ]
    }
    """

    private static let canonSDP = """
    v=0
    o=- 0 0 IN IP4 192.168.1.2
    s=RTP Session
    c=IN IP4 0.0.0.0
    t=0 0
    m=video 12000 RTP/AVP 103
    a=rtpmap:103 H264/90000
    m=audio 12010 RTP/AVP 106
    a=rtpmap:106 MP4A-LATM/48000
    """

    private func detailedLiveView() -> Data {
        let jpeg = Data([0xFF, 0xD8, 0x01, 0x02, 0xFF, 0xD9])
        let info = Data(
            #"{"liveview":{"image":{"positionx":100,"positiony":200,"positionwidth":6000,"positionheight":4000}}}"#.utf8
        )
        return detailPacket(type: 0x00, payload: jpeg) + detailPacket(type: 0x01, payload: info)
    }

    private func multipart(boundary: String, frame: Data) -> Data {
        var value = Data(
            "--\(boundary)\nContent-Type: image/jpeg\nContent-Length: \(frame.count)\n\n".utf8
        )
        value.append(frame)
        value.append(Data("\n--\(boundary)--\n".utf8))
        return value
    }

    private func detailPacket(type: UInt8, payload: Data) -> Data {
        var size = UInt32(payload.count).bigEndian
        var result = Data([0xFF, 0x00, type])
        withUnsafeBytes(of: &size) { result.append(contentsOf: $0) }
        result.append(payload)
        result.append(contentsOf: [0xFF, 0xFF])
        return result
    }
}
