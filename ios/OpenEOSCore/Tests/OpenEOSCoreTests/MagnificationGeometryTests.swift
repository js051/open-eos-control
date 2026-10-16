import Foundation
import XCTest
@testable import OpenEOSCore
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Production CCAPI methods with deterministic synthetic HTTP/RTP peers. These
/// tests do not establish native decoder, physical display or camera validation.
final class MagnificationGeometryTests: XCTestCase {
    private let newRect = ["x": 220, "y": 350, "width": 200, "height": 160]

    func testOneFiveTenOneReacquiresNativeGeometry() async throws {
        var root = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        for _ in 0..<4 { root.deleteLastPathComponent() }
        let bytes = try Data(contentsOf: root.appendingPathComponent("contracts/magnification-geometry/v1/geometry-transition-vectors.json"))
        let fixture = try XCTUnwrap(JSONSerialization.jsonObject(with: bytes) as? [String: Any])
        let vectors = try XCTUnwrap(fixture["vectors"] as? [[String: Any]])
        XCTAssertEqual(vectors.count, 12)
        for source in [LiveViewSource.ccapiRTP, .ccapiMultipart, .ccapiJPEGPolling] {
            let (client, peer) = try await started(source)
            var preceding = 1
            for (index, vector) in vectors.enumerated() {
                let magnification = try XCTUnwrap(vector["observedMagnification"] as? Int)
                let rect = try XCTUnwrap(vector["reportedGeometry"] as? [String: Int])
                let point = try XCTUnwrap(vector["normalized"] as? [String: Double])
                let expected = try XCTUnwrap(vector["expectedCameraPoint"] as? [String: Int])
                if magnification != preceding {
                    _ = try await client.setLiveViewMagnification(try XCTUnwrap(LiveViewMagnification(rawValue: magnification)))
                    preceding = magnification
                }
                await peer.setGeometry(rect)
                if source == .ccapiJPEGPolling, index % 3 == 0 {
                    _ = try await client.liveViewFrame(cacheKey: Int64(index + 1))
                }
                let x = try XCTUnwrap(point["x"]), y = try XCTUnwrap(point["y"])
                _ = try await client.tapFocus(x: x, y: y)
                try await assertLastPoint(peer, expected)
                _ = try await client.clickWhiteBalance(x: x, y: y)
                try await assertLastPoint(peer, expected)
            }
            let details = await peer.count("GET detail")
            XCTAssertEqual(details, 4, "One authoritative detail read per crop, never scale the preceding crop")
            let writes = await peer.coordinateCount()
            XCTAssertEqual(writes, 24)
            await client.stopLiveView()
        }
    }

    func testControlsDuringMagnificationSendNothing() async throws {
        let (client, peer) = try await started(.ccapiRTP)
        _ = try await client.tapFocus(x: 0.25, y: 0.75)
        await peer.holdNext("PUT zoom")
        let zoom = Task { try await client.setLiveViewMagnification(.x5) }
        try await waitHeld(peer, "PUT zoom")
        let before = await peer.coordinateCount(), details = await peer.count("GET detail")
        do { _ = try await client.tapFocus(x: 0.25, y: 0.75); XCTFail("Zoom revokes Tap AF") } catch {}
        do { _ = try await client.clickWhiteBalance(x: 0.25, y: 0.75); XCTFail("Zoom revokes Click WB") } catch {}
        do { _ = try await client.setLiveViewMagnification(.x10); XCTFail("No overlapping zoom") } catch {}
        let after = await peer.coordinateCount(), afterDetails = await peer.count("GET detail")
        XCTAssertEqual(after, before); XCTAssertEqual(afterDetails, details)
        await peer.setGeometry(newRect); await peer.release("PUT zoom")
        _ = try await zoom.value
        _ = try await client.tapFocus(x: 0.25, y: 0.75)
        try await assertLastPoint(peer, ["x": 270, "y": 470])
        await client.stopLiveView()
    }

    func testOldGeometryCompletionCannotDispatch() async throws {
        for whiteBalance in [false, true] {
            let (client, peer) = try await started(.ccapiRTP)
            await peer.holdNext("GET detail")
            let stale = Task {
                if whiteBalance { _ = try await client.clickWhiteBalance(x: 0.25, y: 0.75) }
                else { _ = try await client.tapFocus(x: 0.25, y: 0.75) }
            }
            try await waitHeld(peer, "GET detail")
            _ = try await client.setLiveViewMagnification(.x5)
            await peer.setGeometry(newRect)
            _ = try await client.tapFocus(x: 0.25, y: 0.75)
            await peer.release("GET detail")
            do { try await stale.value; XCTFail("Old geometry cannot authorize a command") }
            catch { XCTAssertTrue(error is CancellationError) }
            _ = try await client.clickWhiteBalance(x: 0.25, y: 0.75)
            try await assertLastPoint(peer, ["x": 270, "y": 470])
            let writes = await peer.coordinateCount(), details = await peer.count("GET detail")
            XCTAssertEqual(writes, 2); XCTAssertEqual(details, 2)
            await client.stopLiveView()
        }
    }

    func testOldJPEGCompletionCannotPublish() async throws {
        let (client, peer) = try await started(.ccapiJPEGPolling)
        _ = try await client.liveViewFrame(cacheKey: 1) // Consume the retained first frame.
        await peer.holdNext("GET detail")
        let stale = Task { try await client.liveViewFrame(cacheKey: 2) }
        try await waitHeld(peer, "GET detail")
        _ = try await client.setLiveViewMagnification(.x5)
        await peer.setGeometry(newRect)
        _ = try await client.liveViewFrame(cacheKey: 3)
        await peer.release("GET detail")
        do { _ = try await stale.value; XCTFail("Old image and geometry cannot publish") }
        catch { XCTAssertTrue(error is CancellationError) }
        _ = try await client.tapFocus(x: 0.25, y: 0.75)
        try await assertLastPoint(peer, ["x": 270, "y": 470])
        await client.stopLiveView()
    }

    func testFailedReadbackNeverRestoresGeometry() async throws {
        for failure in MagnificationPeer.Failure.allCases {
            let (client, peer) = try await started(.ccapiRTP)
            _ = try await client.tapFocus(x: 0.25, y: 0.75)
            await peer.failNextZoom(failure)
            do { _ = try await client.setLiveViewMagnification(.x5); XCTFail("Expected \(failure)") } catch {}
            await peer.setGeometry(newRect)
            _ = try await client.tapFocus(x: 0.25, y: 0.75)
            try await assertLastPoint(peer, ["x": 270, "y": 470])
            let details = await peer.count("GET detail"); XCTAssertEqual(details, 2)
            await client.stopLiveView()
        }
    }

    func testCancelledMagnificationRequiresFreshGeometryEvenAfterLateSuccess() async throws {
        let (client, peer) = try await started(.ccapiRTP)
        _ = try await client.tapFocus(x: 0.25, y: 0.75)
        await peer.holdNext("GET zoom")
        let zoom = Task { try await client.setLiveViewMagnification(.x5) }
        try await waitHeld(peer, "GET zoom")
        zoom.cancel(); await peer.release("GET zoom")
        do { _ = try await zoom.value; XCTFail("Cancellation cannot publish late success") }
        catch { XCTAssertTrue(error is CancellationError) }
        await peer.setGeometry(newRect)
        _ = try await client.tapFocus(x: 0.25, y: 0.75)
        try await assertLastPoint(peer, ["x": 270, "y": 470])
        let details = await peer.count("GET detail"); XCTAssertEqual(details, 2)
        await client.stopLiveView()
    }

    func testRetiredSessionReadCannotPolluteReplacement() async throws {
        let (client, peer) = try await started(.ccapiRTP)
        await peer.holdNext("GET detail")
        let stale = Task { try await client.tapFocus(x: 0.25, y: 0.75) }
        try await waitHeld(peer, "GET detail")
        await client.stopLiveView()
        await peer.setGeometry(newRect)
        try await client.startLiveView(.init(source: .ccapiMultipart))
        _ = try await client.tapFocus(x: 0.25, y: 0.75)
        await peer.release("GET detail")
        do { _ = try await stale.value; XCTFail("Retired source cannot dispatch") }
        catch { XCTAssertTrue(error is CancellationError) }
        _ = try await client.clickWhiteBalance(x: 0.25, y: 0.75)
        try await assertLastPoint(peer, ["x": 270, "y": 470])
        let writes = await peer.coordinateCount(); XCTAssertEqual(writes, 2)
        await client.stopLiveView()
    }

    func testLateMagnificationReadbackCannotPublishToReplacement() async throws {
        let (client, peer) = try await started(.ccapiRTP)
        await peer.holdNext("GET zoom")
        let stale = Task { try await client.setLiveViewMagnification(.x5) }
        try await waitHeld(peer, "GET zoom")
        await client.stopLiveView()
        try await client.startLiveView(.init(source: .ccapiMultipart))
        _ = try await client.setLiveViewMagnification(.x10)
        await peer.setGeometry(newRect)
        _ = try await client.tapFocus(x: 0.25, y: 0.75)
        await peer.release("GET zoom")
        do { _ = try await stale.value; XCTFail("Retired readback cannot publish") }
        catch { XCTAssertTrue(error is CancellationError) }
        let capabilities = try await client.capabilities()
        XCTAssertEqual(capabilities.liveView.currentMagnification, .x10)
        _ = try await client.clickWhiteBalance(x: 0.25, y: 0.75)
        try await assertLastPoint(peer, ["x": 270, "y": 470])
        let details = await peer.count("GET detail"); XCTAssertEqual(details, 1)
        await client.stopLiveView()
    }

    func testMalformedGeometrySendsNoCoordinateMutation() async throws {
        let invalid: [String] = [
            #"{"positionx":-1,"positiony":0,"positionwidth":100,"positionheight":100}"#,
            #"{"positionx":0,"positiony":-1,"positionwidth":100,"positionheight":100}"#,
            #"{"positionx":true,"positiony":0,"positionwidth":100,"positionheight":100}"#,
            #"{"positionx":0.5,"positiony":0,"positionwidth":100,"positionheight":100}"#,
            #"{"positionx":0,"positiony":0,"positionwidth":0,"positionheight":100}"#,
            #"{"positionx":0,"positiony":0,"positionwidth":100,"positionheight":-1}"#,
            #"{"positionx":2147483647,"positiony":0,"positionwidth":100,"positionheight":100}"#,
            #"{"positionx":0,"positiony":0,"positionwidth":9223372036854775808,"positionheight":100}"#,
            #"{"positionx":"0","positiony":0,"positionwidth":100,"positionheight":100}"#,
            #"{}"#,
        ]
        for imageJSON in invalid {
            let (client, peer) = try await started(.ccapiRTP)
            await peer.setImageJSON(imageJSON)
            do { _ = try await client.tapFocus(x: 0.25, y: 0.75); XCTFail(imageJSON) } catch {}
            do { _ = try await client.clickWhiteBalance(x: 0.25, y: 0.75); XCTFail(imageJSON) } catch {}
            let writes = await peer.coordinateCount(); XCTAssertEqual(writes, 0)
            await client.stopLiveView()
        }
    }

    func testIncompleteDetailCannotAuthorizeCoordinatesAndFreshRetryRecovers() async throws {
        for kind in ["partial", "contentRange", "truncated", "trailing", "failed", "oversized"] {
            let (client, peer) = try await started(.ccapiRTP)
            await peer.invalidDetail(kind)
            do { _ = try await client.tapFocus(x: 0.25, y: 0.75); XCTFail(kind) } catch {}
            let before = await peer.coordinateCount(); XCTAssertEqual(before, 0)
            await peer.setGeometry(newRect)
            _ = try await client.tapFocus(x: 0.25, y: 0.75)
            try await assertLastPoint(peer, ["x": 270, "y": 470])
            await client.stopLiveView()
        }
    }

    private func started(_ source: LiveViewSource) async throws -> (CCAPIClient, MagnificationPeer) {
        let peer = MagnificationPeer()
        let client = try CCAPIClient(baseURL: "http://192.0.2.1:8080", mode: .camera,
            rtpDestinationAddress: "192.0.2.2", rtpSessionFactory: MagnificationRTPFactory(), transport: peer)
        _ = try await client.capabilities()
        try await client.startLiveView(.init(source: source))
        return (client, peer)
    }

    private func assertLastPoint(_ peer: MagnificationPeer, _ expected: [String: Int],
                                 file: StaticString = #filePath, line: UInt = #line) async throws {
        let request = await peer.lastCoordinate()
        let body = try XCTUnwrap(request?.body, file: file, line: line)
        let payload = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Int], file: file, line: line)
        XCTAssertEqual(payload, ["positionx": expected["x"]!, "positiony": expected["y"]!], file: file, line: line)
    }

    private func waitHeld(_ peer: MagnificationPeer, _ key: String) async throws {
        let deadline = ContinuousClock.now.advanced(by: .seconds(2))
        while !(await peer.isHeld(key)) {
            guard ContinuousClock.now < deadline else { throw URLError(.timedOut) }
            await Task.yield()
        }
    }
}

private actor MagnificationRTPSession: CCAPIRTPSession {
    nonisolated let sourceURL = URL(string: "rtp://192.0.2.2:12000")!
    func start() async throws {}
    func waitUntilReady() async throws { try Task.checkCancellation() }
    func setTargetFPS(_ fps: Int) async {}
    func close() async {}
}

private struct MagnificationRTPFactory: CCAPIRTPSessionFactory {
    func makeSession(description: CCAPIRTPSessionDescription, destinationAddress: String) async throws -> any CCAPIRTPSession {
        MagnificationRTPSession()
    }
}

/// Each held response is captured before suspension and deliberately ignores
/// cancellation. Releasing it models an actual late old crop/ACK, not a new read.
private actor MagnificationPeer: CameraHTTPTransport {
    enum Failure: CaseIterable, Equatable, Sendable { case rejectedPut, lostPut, failedReadback, malformedReadback, mismatchedReadback }
    private var magnification = 1
    private var imageJSON = #"{"positionx":100,"positiony":200,"positionwidth":1000,"positionheight":800}"#
    private var nextFailure: Failure?
    private var detailFailure: String?
    private var records: [(key: String, request: RecordedRequest)] = []
    private var armed = Set<String>()
    private var held: [String: CheckedContinuation<Void, Never>] = [:]
    private let jpeg = Data([0xff, 0xd8, 7, 0xff, 0xd9])

    func setGeometry(_ rect: [String: Int]) {
        imageJSON = "{\"positionx\":\(rect["x"]!),\"positiony\":\(rect["y"]!),\"positionwidth\":\(rect["width"]!),\"positionheight\":\(rect["height"]!)}"
        detailFailure = nil
    }
    func setImageJSON(_ value: String) { imageJSON = value }
    func failNextZoom(_ value: Failure) { nextFailure = value }
    func invalidDetail(_ value: String) { detailFailure = value }
    func holdNext(_ key: String) { armed.insert(key) }
    func isHeld(_ key: String) -> Bool { held[key] != nil }
    func release(_ key: String) { held.removeValue(forKey: key)?.resume() }
    func count(_ key: String) -> Int { records.filter { $0.key == key }.count }
    func coordinateCount() -> Int { records.filter { ["PUT focus", "POST wb"].contains($0.key) }.count }
    func lastCoordinate() -> RecordedRequest? { records.last { ["PUT focus", "POST wb"].contains($0.key) }?.request }

    func send(_ request: URLRequest) async throws -> CameraHTTPResponse {
        let method = request.httpMethod ?? "GET", path = request.url?.path ?? ""
        let key: String
        if path.hasSuffix("/lvzoom") { key = method + " zoom" }
        else if path.hasSuffix("/flipdetail") { key = method + " detail" }
        else if path.hasSuffix("/afframeposition") { key = method + " focus" }
        else if path.hasSuffix("/clickwb") { key = method + " wb" }
        else { key = method + " " + path }
        records.append((key, RecordedRequest(method: method, path: path, headers: request.allHTTPHeaderFields ?? [:],
                                            body: request.httpBody, timeoutInterval: request.timeoutInterval)))
        let response: CameraHTTPResponse
        if path == "/ccapi" {
            response = json(Self.discovery)
        } else if key == "PUT zoom" {
            if nextFailure == .lostPut { nextFailure = nil; throw URLError(.networkConnectionLost) }
            if nextFailure == .rejectedPut { nextFailure = nil; response = json("{}", status: 503) }
            else {
                let value = try XCTUnwrap(JSONSerialization.jsonObject(with: request.httpBody ?? Data()) as? [String: String])
                magnification = Int(value["value"] ?? "") ?? 1
                response = json("{}")
            }
        } else if key == "GET zoom" {
            if nextFailure == .failedReadback { nextFailure = nil; response = json("{}", status: 500) }
            else if nextFailure == .malformedReadback { nextFailure = nil; response = json("{malformed") }
            else if nextFailure == .mismatchedReadback { nextFailure = nil; response = json(#"{"value":"1","ability":["1","5","10"]}"#) }
            else { response = json("{\"value\":\"\(magnification)\",\"ability\":[\"1\",\"5\",\"10\"]}") }
        } else if key == "GET detail" {
            var body = packet(0, jpeg) + packet(1, Data("{\"liveviewdata\":{\"image\":\(imageJSON)}}".utf8))
            var status = 200, headers = ["content-type": "application/octet-stream"]
            switch detailFailure {
            case "partial": status = 206
            case "contentRange": headers["content-range"] = "bytes 0-1/2"
            case "truncated": body.removeLast()
            case "trailing": body.append(0x42)
            case "failed": status = 503
            case "oversized": body = Data(repeating: 0, count: CCAPIMultipartLiveView.maximumFrameBytes + 1)
            default: break
            }
            detailFailure = nil
            response = CameraHTTPResponse(statusCode: status, headers: headers, body: body)
        } else if path.hasSuffix("/rtpsessiondesc") {
            response = CameraHTTPResponse(statusCode: 200, body: Data("v=0\r\nm=video 12000 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\n".utf8))
        } else if path.hasSuffix("/flip") {
            response = CameraHTTPResponse(statusCode: 200, headers: ["content-type": "image/jpeg"], body: jpeg)
        } else { response = json("{}") }
        if armed.remove(key) != nil { await withCheckedContinuation { held[key] = $0 } }
        return response
    }

    func openStream(_ request: URLRequest) async throws -> CameraHTTPStreamResponse {
        _ = try await send(request)
        let pair = AsyncThrowingStream<Data, Error>.makeStream()
        var body = Data("--canon\r\nContent-Type: image/jpeg\r\nContent-Length: \(jpeg.count)\r\n\r\n".utf8)
        body.append(jpeg); body.append(Data("\r\n".utf8))
        pair.continuation.yield(body)
        return CameraHTTPStreamResponse(statusCode: 200, headers: ["content-type": "multipart/x-mixed-replace;boundary=canon"],
            chunks: pair.stream, cancel: { pair.continuation.finish(throwing: CancellationError()) })
    }

    private func json(_ body: String, status: Int = 200) -> CameraHTTPResponse {
        CameraHTTPResponse(statusCode: status, headers: ["content-type": "application/json"], body: Data(body.utf8))
    }
    private func packet(_ type: UInt8, _ payload: Data) -> Data {
        let length = UInt32(payload.count)
        return Data([0xff, 0, type, UInt8((length >> 24) & 255), UInt8((length >> 16) & 255),
                     UInt8((length >> 8) & 255), UInt8(length & 255)]) + payload + Data([0xff, 0xff])
    }
    private static let discovery = #"{"ver130":[{"path":"/shooting/settings/lvzoom","get":true,"put":true},{"path":"/shooting/liveview","post":true,"delete":true},{"path":"/shooting/liveview/flip","get":true},{"path":"/shooting/liveview/flipdetail","get":true},{"path":"/shooting/liveview/multipart","get":true,"delete":true},{"path":"/shooting/liveview/rtpsessiondesc","get":true},{"path":"/shooting/liveview/rtp","post":true},{"path":"/shooting/liveview/afframeposition","put":true},{"path":"/shooting/liveview/clickwb","post":true}]}"#
}
