import Foundation
import XCTest
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
@testable import OpenEOSCore

/// Injected-transport counterexamples through the production client. Native Swift
/// compilation/execution must be recorded separately from supplementary source checks.
final class CCAPIPartialControlReceiptTests: XCTestCase {
    private let partialReplies = [
        CameraHTTPResponse(statusCode: 206),
        CameraHTTPResponse(statusCode: 200, headers: ["Content-Range": "bytes 0-0/2"], body: Data("x".utf8)),
        CameraHTTPResponse(statusCode: 200, headers: ["cOnTeNt-RaNgE": ""]),
        CameraHTTPResponse(statusCode: 204, headers: ["content-range": ""]),
    ]
    private let fullReplies = [
        CameraHTTPResponse(statusCode: 200),
        CameraHTTPResponse(statusCode: 200, body: Data("OK".utf8)),
        CameraHTTPResponse(statusCode: 200, body: Data("{\"ok\":true}".utf8)),
        CameraHTTPResponse(statusCode: 204),
    ]

    func testReceiptPolicyMatchesOnlyExistingPhysicalControlMethodsAndSuffixes() {
        let routes: [(String, String)] = [
            ("POST", "/shooting/control/shutterbutton"),
            ("POST", "/shooting/control/shutterbutton/manual"),
            ("PUT", "/shooting/control/shutterbutton/manual"),
            ("POST", "/shooting/control/af"),
            ("POST", "/shooting/liveview"),
            ("DELETE", "/shooting/liveview"),
            ("POST", "/shooting/liveview/rtp"),
            ("DELETE", "/shooting/liveview/multipart"),
        ]
        for (method, suffix) in routes {
            for version in ["ver110", "ver130"] {
                let path = "/ccapi/\(version)" + suffix
                XCTAssertTrue(CCAPIClient.requiresCompleteControlReceipt(method: method, path: path))
                XCTAssertFalse(CCAPIClient.requiresCompleteControlReceipt(method: "GET", path: path))
                XCTAssertFalse(CCAPIClient.requiresCompleteControlReceipt(method: method, path: path + "/other"))
            }
        }
        for (method, path) in [("PUT", "/shooting/control/af"), ("POST", "/shooting/liveview/multipart"),
                               ("DELETE", "/shooting/liveview/rtp"), ("PUT", "/shooting/settings/iso"),
                               ("GET", "/contents/card1/SYNTHETIC.JPG"), ("POST", "/shooting/control/recbutton")] {
            XCTAssertFalse(CCAPIClient.requiresCompleteControlReceipt(method: method, path: "/ccapi/ver130" + path))
        }
    }

    func testPartialBulbPressAndReleaseRetainExactOwnerUntilExplicitFullRelease() async throws {
        for method in ["POST", "PUT"] {
            for partial in partialReplies {
                let peer = PartialControlPeer(.bulb, method: method, starts: [partial], stops: [partial])
                let client = try peer.client()
                await fails { try await client.startBulbExposure() }
                await unknown(client)
                let before = await peer.writes()
                XCTAssertEqual(before.map(\.action), ["full_press", "release"])
                let beforeRetry = await peer.requests()
                await fails { try await client.startBulbExposure() }
                await fails { try await client.captureStill() }
                let blocked = await peer.requests()
                XCTAssertEqual(blocked, beforeRetry)
                try await client.retryShutterRelease()
                let retry = Array(await peer.requests().dropFirst(beforeRetry.count))
                XCTAssertEqual(retry.count, 1)
                XCTAssertEqual(retry.first?.method, method)
                XCTAssertEqual(retry.first?.path, before.last?.path)
                XCTAssertEqual(retry.first?.body, before.last?.body)
                let released = await client.shutterReleaseState()
                XCTAssertEqual(released, .idle)
            }
        }
    }

    func testPartialBulbStopAfterFullPressCannotClearReleaseWarning() async throws {
        for partial in partialReplies {
            let peer = PartialControlPeer(.bulb, stops: [partial])
            let client = try peer.client()
            _ = try await client.startBulbExposure()
            await fails { try await client.retryShutterRelease() }
            await unknown(client)
            try await client.retryShutterRelease()
            let writes = await peer.writes()
            XCTAssertEqual(writes.map(\.action), ["full_press", "release", "release"])
            XCTAssertEqual(writes[1].body, writes[2].body)
            let released = await client.shutterReleaseState()
            XCTAssertEqual(released, .idle)
        }
    }

    func testHeldAFPartialReceiptsRetainExactOwnerAndIgnoreWrongOwnerStop() async throws {
        for partialStart in [false, true] {
            for partial in partialReplies {
                let peer = PartialControlPeer(.heldAF, starts: partialStart ? [partial] : [], stops: [partial])
                let client = try peer.client()
                let owner = UUID()
                if partialStart { await fails { try await client.startHeldAutofocus(owner: owner) } }
                else {
                    try await client.startHeldAutofocus(owner: owner)
                    await fails { try await client.stopHeldAutofocus(owner: owner) }
                }
                await unknown(client)
                let before = await peer.requests()
                try await client.stopHeldAutofocus(owner: UUID())
                let ignored = await peer.requests()
                XCTAssertEqual(ignored, before)
                try await client.stopHeldAutofocus(owner: owner)
                let retry = Array(await peer.requests().dropFirst(before.count))
                XCTAssertEqual(retry.count, 1)
                XCTAssertEqual(retry.first?.action, "stop")
                XCTAssertEqual(retry.first?.path, before.last?.path)
                XCTAssertEqual(retry.first?.body, before.last?.body)
                let released = await client.shutterReleaseState()
                XCTAssertEqual(released, .idle)
            }
        }
    }

    func testShortControlPartialPressAndReleaseRequireOnlyStoredReleaseRetry() async throws {
        for control in [PartialControl.halfPress, .pulseAF, .manualCapture] {
            for partial in partialReplies {
                let peer = PartialControlPeer(control, starts: [partial], stops: [partial])
                let client = try peer.client()
                try await client.initialize()
                await fails { try await control.run(client) }
                await unknown(client)
                let before = await peer.requests()
                try await client.retryShutterRelease()
                let retry = Array(await peer.requests().dropFirst(before.count))
                XCTAssertEqual(retry.count, 1)
                XCTAssertEqual(retry.first?.path, before.last?.path)
                XCTAssertEqual(retry.first?.body, before.last?.body)
                let released = await client.shutterReleaseState()
                XCTAssertEqual(released, .idle)
            }
        }
    }

    func testDirectCaptureRejectsPartialAcknowledgementWithoutReplayingCapture() async throws {
        for partial in partialReplies {
            let peer = PartialControlPeer(.directCapture, starts: [partial])
            let client = try peer.client()
            await fails { try await client.captureStill() }
            let writes = await peer.writes()
            XCTAssertEqual(writes.count, 1)
            XCTAssertEqual(writes.first?.path, "/ccapi/ver130/shooting/control/shutterbutton")
        }
    }

    func testLiveViewPartialReceiptsRetainOriginalDeleteOrPostOffUntilExplicitFullACK() async throws {
        for delete in [false, true] {
            for partialStart in [false, true] {
                for partial in partialReplies {
                    let peer = PartialControlPeer(.liveView, deleteLiveView: delete,
                        starts: partialStart ? [partial] : [], stops: [partial])
                    let client = try peer.client()
                    if partialStart { await fails { try await client.startLiveView(.init(source: .ccapiJPEGPolling)) } }
                    else {
                        try await client.startLiveView(.init(source: .ccapiJPEGPolling))
                        await fails { try await client.retryLiveViewStop() }
                    }
                    let retained = await client.liveViewStopState()
                    XCTAssertEqual(retained, .init(stopRequired: true, stopUnconfirmed: true, source: .ccapiJPEGPolling))
                    let source = await client.currentLiveViewSource()
                    XCTAssertNil(source)
                    let before = await peer.requests()
                    await fails { try await client.startLiveView(.init(source: .ccapiJPEGPolling)) }
                    let blocked = await peer.requests()
                    XCTAssertEqual(blocked, before)
                    try await client.retryLiveViewStop()
                    let retry = Array(await peer.requests().dropFirst(before.count))
                    XCTAssertEqual(retry.count, 1)
                    XCTAssertEqual(retry.first?.method, delete ? "DELETE" : "POST")
                    XCTAssertEqual(retry.first?.path, before.last?.path)
                    XCTAssertEqual(retry.first?.body, before.last?.body)
                    let released = await client.liveViewStopState()
                    XCTAssertEqual(released, .idle)
                }
            }
        }
    }

    func testRTPPartialStartAndStopRetainExactOwnerUntilExplicitFullACK() async throws {
        for partialStart in [false, true] {
            for partial in partialReplies {
                let peer = PartialControlPeer(.rtp, starts: partialStart ? [partial] : [], stops: [partial])
                let native = PartialReceiptRTPSession()
                let client = try CCAPIClient(baseURL: "http://192.0.2.1:8080", mode: .camera,
                    rtpDestinationAddress: "192.0.2.2", rtpSessionFactory: PartialReceiptRTPFactory(session: native), transport: peer)
                if partialStart { await fails { try await client.startLiveView(.init(source: .ccapiRTP)) } }
                else {
                    try await client.startLiveView(.init(source: .ccapiRTP))
                    await fails { try await client.retryLiveViewStop() }
                }
                let retained = await client.liveViewStopState()
                XCTAssertEqual(retained, .init(stopRequired: true, stopUnconfirmed: true, source: .ccapiRTP))
                let closed = await native.closed
                XCTAssertTrue(closed)
                let before = await peer.requests()
                try await client.retryLiveViewStop()
                let retry = Array(await peer.requests().dropFirst(before.count))
                XCTAssertEqual(retry.count, 1)
                XCTAssertEqual(retry.first?.method, "POST")
                XCTAssertEqual(retry.first?.path, before.last?.path)
                XCTAssertEqual(retry.first?.body, before.last?.body)
                XCTAssertEqual(retry.first?.action, "stop")
                let released = await client.liveViewStopState()
                XCTAssertEqual(released, .idle)
            }
        }
    }

    func testFull200EmptyPlainJSONAnd204RepliesRemainValidForPhysicalControls() async throws {
        for full in fullReplies {
            for control in [PartialControl.bulb, .heldAF, .directCapture, .liveView] {
                let peer = PartialControlPeer(control, starts: [full], stops: [full])
                let client = try peer.client()
                switch control {
                case .bulb:
                    _ = try await client.startBulbExposure()
                    try await client.retryShutterRelease()
                case .heldAF:
                    let owner = UUID()
                    try await client.startHeldAutofocus(owner: owner)
                    try await client.stopHeldAutofocus(owner: owner)
                case .liveView:
                    try await client.startLiveView(.init(source: .ccapiJPEGPolling))
                    try await client.retryLiveViewStop()
                default: _ = try await client.captureStill()
                }
                let shutter = await client.shutterReleaseState()
                let liveView = await client.liveViewStopState()
                XCTAssertEqual(shutter, .idle)
                XCTAssertEqual(liveView, .idle)
            }
        }
    }

    func testPartialGETFrameStillUsesItsExistingCompleteImageContract() async throws {
        let peer = PartialControlPeer(.liveView)
        let client = try peer.client()
        try await client.startLiveView(.init(source: .ccapiJPEGPolling))
        _ = try await client.liveViewFrame(cacheKey: 1) // Consume the retained first frame.
        await peer.usePartialFrames()
        await fails { try await client.liveViewFrame(cacheKey: 2) }
        let writes = await peer.writes()
        XCTAssertEqual(writes.count, 1, "Read failure must not dispatch another physical Start")
        try await client.retryLiveViewStop()
    }

    func testUnrelatedDiscoveryGETRetainsExistingPartialResponseContract() async throws {
        let peer = PartialControlPeer(.bulb, partialDiscovery: true)
        let client = try peer.client()
        try await client.initialize()
        let reads = await peer.requests()
        XCTAssertEqual(reads.first?.path, "/ccapi")
        XCTAssertTrue(reads.allSatisfy { $0.method == "GET" })
    }

    func testNon2xxControlStillReportsExistingHTTPRejection() async throws {
        let peer = PartialControlPeer(.directCapture,
            starts: [.init(statusCode: 409, headers: ["Content-Range": ""], body: Data("busy".utf8))])
        let client = try peer.client()
        do { _ = try await client.captureStill(); XCTFail("Expected HTTP rejection") }
        catch let CCAPIError.http(status, _, _, body) { XCTAssertEqual(status, 409); XCTAssertEqual(body, "busy") }
        catch { XCTFail("Existing HTTP rejection changed to \(error)") }
    }

    private func fails<T>(_ operation: () async throws -> T, file: StaticString = #filePath, line: UInt = #line) async {
        do { _ = try await operation(); XCTFail("Expected incomplete or blocked control to fail", file: file, line: line) }
        catch {}
    }
    private func unknown(_ client: CCAPIClient, file: StaticString = #filePath, line: UInt = #line) async {
        let state = await client.shutterReleaseState()
        XCTAssertTrue(state.releaseRequired, file: file, line: line)
        XCTAssertTrue(state.releaseUnconfirmed, file: file, line: line)
        XCTAssertNil(state.bulbExposureActive, file: file, line: line)
    }
}

private enum PartialControl: Sendable {
    case bulb, heldAF, halfPress, pulseAF, manualCapture, directCapture, liveView, rtp
    var suffix: String {
        switch self {
        case .heldAF, .pulseAF: return "/shooting/control/af"
        case .directCapture: return "/shooting/control/shutterbutton"
        case .liveView: return "/shooting/liveview"
        case .rtp: return "/shooting/liveview/rtp"
        default: return "/shooting/control/shutterbutton/manual"
        }
    }
    func run(_ client: CCAPIClient) async throws -> CameraStatus {
        switch self {
        case .halfPress: return try await client.halfPressShutter()
        case .pulseAF: return try await client.autofocus()
        default: return try await client.captureStill(autofocus: false)
        }
    }
}

private actor PartialControlPeer: CameraHTTPTransport {
    private let control: PartialControl
    private let method: String
    private let deleteLiveView: Bool
    private let partialDiscovery: Bool
    private var starts: [CameraHTTPResponse]
    private var stops: [CameraHTTPResponse]
    private var recorded: [ShutterRecoveryRequest] = []
    private var partialFrames = false
    init(_ control: PartialControl, method: String = "POST", deleteLiveView: Bool = true, partialDiscovery: Bool = false,
         starts: [CameraHTTPResponse] = [], stops: [CameraHTTPResponse] = []) {
        self.control = control; self.method = method; self.deleteLiveView = deleteLiveView
        self.partialDiscovery = partialDiscovery
        self.starts = starts; self.stops = stops
    }
    nonisolated func client() throws -> CCAPIClient {
        try CCAPIClient(baseURL: "http://192.0.2.1:8080", mode: .camera, transport: self)
    }
    func requests() -> [ShutterRecoveryRequest] { recorded }
    func writes() -> [ShutterRecoveryRequest] { recorded.filter(\.isMutation) }
    func usePartialFrames() { partialFrames = true }
    func send(_ request: URLRequest) async throws -> CameraHTTPResponse {
        let record = ShutterRecoveryRequest(method: request.httpMethod ?? "GET", path: request.url?.path ?? "",
            body: request.httpBody ?? Data(), taskWasCancelled: Task.isCancelled)
        recorded.append(record)
        if record.method != "GET" {
            guard record.path == "/ccapi/ver130" + control.suffix else { throw URLError(.unsupportedURL) }
            let payload = record.body.isEmpty ? [:] :
                (try JSONSerialization.jsonObject(with: record.body) as? [String: Any] ?? [:])
            let stop = record.action == "release" || record.action == "stop" || record.method == "DELETE" ||
                payload["liveviewsize"] as? String == "off"
            return stop ? (stops.isEmpty ? .init(statusCode: 204) : stops.removeFirst()) :
                (starts.isEmpty ? .init(statusCode: 204) : starts.removeFirst())
        }
        if record.path.hasSuffix("/liveview/flip") {
            return CameraHTTPResponse(statusCode: partialFrames ? 206 : 200,
                headers: partialFrames ? ["Content-Range": "bytes 0-0/2"] : [:], body: CCAPILiveViewTestFixture.jpeg)
        }
        if record.path.hasSuffix("/liveview/rtpsessiondesc") {
            let sdp = "v=0\no=- 0 0 IN IP4 192.0.2.1\ns=fixture\nc=IN IP4 0.0.0.0\nt=0 0\nm=video 12000 RTP/AVP 96\na=rtpmap:96 H264/90000\n"
            return CameraHTTPResponse(statusCode: 200, body: Data(sdp.utf8))
        }
        let body: String
        if record.path == "/ccapi" || record.path == "/ccapi/" {
            body = """
            {"ver130":[{"path":"\(control.suffix)","\(method.lowercased())":true,"delete":\(control == .liveView && deleteLiveView)},
            {"path":"/shooting/settings","get":true},
            {"path":"/shooting/liveview/flip","get":true},
            {"path":"/shooting/liveview/rtpsessiondesc","get":true}]}
            """
        } else if record.path == "/ccapi/ver130/shooting/settings" {
            body = #"{"iso":{"value":"100","ability":["100"]},"tv":{"value":"bulb","ability":["bulb"]}}"#
        } else { throw URLError(.unsupportedURL) }
        let ranged = partialDiscovery && (record.path == "/ccapi" || record.path == "/ccapi/")
        return CameraHTTPResponse(statusCode: ranged ? 206 : 200,
            headers: ranged ? ["Content-Range": ""] : [:], body: Data(body.utf8))
    }
    func download(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse { throw URLError(.unsupportedURL) }
}

private actor PartialReceiptRTPSession: CCAPIRTPSession {
    nonisolated let sourceURL = URL(string: "rtp://192.0.2.2:12000")!
    private(set) var closed = false
    func start() async throws {}
    func waitUntilReady() async throws { try Task.checkCancellation() }
    func setTargetFPS(_ fps: Int) async {}
    func close() async { closed = true }
}

private struct PartialReceiptRTPFactory: CCAPIRTPSessionFactory {
    let session: PartialReceiptRTPSession
    func makeSession(description: CCAPIRTPSessionDescription, destinationAddress: String) async throws -> any CCAPIRTPSession { session }
}
