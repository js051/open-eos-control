import Foundation
import XCTest
@testable import OpenEOSCore
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Deterministic protocol/owner tests. Native VideoToolbox execution is a separate target.
final class LiveViewLifecycleTests: XCTestCase {
    private let root = "/ccapi/ver130/shooting/liveview"
    private let jpeg = Data([0xff, 0xd8, 7, 0xff, 0xd9])
    private let discovery = #"{"ver130":[{"path":"/shooting/liveview","post":true,"delete":true},{"path":"/shooting/liveview/flip","get":true},{"path":"/shooting/liveview/rtpsessiondesc","get":true},{"path":"/shooting/liveview/rtp","post":true}]}"#
    private let sdp = "v=0\r\nm=video 12000 RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\n"

    func testSharedLifecycleVectorsExerciseProductionPolicy() throws {
        var root = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        for _ in 0..<4 { root.deleteLastPathComponent() }
        let data = try Data(contentsOf: root.appendingPathComponent("contracts/liveview-lifecycle/v1/liveview-vectors.json"))
        let fixture = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        let cases = try XCTUnwrap(fixture["cases"] as? [[String: Any]])
        XCTAssertEqual(cases.count, 99)
        for test in cases {
            let input = try XCTUnwrap(test["input"] as? [String: Any])
            let expected = try XCTUnwrap(test["expected"] as? [String: Bool])
            XCTAssertEqual(CameraLiveViewLifecyclePolicy.evaluate(input), expected, test["name"] as? String ?? "unnamed")
        }
    }

    func testReadinessRequiresDecodedOutputAndTerminalFailureOverridesEarlierReady() async throws {
        let gate = CCAPIRTPReadinessGate()
        do { try await gate.wait(timeoutNanoseconds: 1_000_000); XCTFail("ACK/bind are not video") } catch {}
        gate.decodedFrame()
        try await gate.wait(timeoutNanoseconds: 1_000_000)
        gate.fail(URLError(.networkConnectionLost))
        do { try await gate.wait(); XCTFail("Ready cannot erase terminal failure") } catch {}
    }

    func testReadinessWaitCancellationDoesNotPublishLateDecodedOutput() async {
        let gate = CCAPIRTPReadinessGate()
        let task = Task { try await gate.wait() }
        task.cancel(); gate.decodedFrame()
        do { try await task.value; XCTFail("Cancelled owner cannot publish") }
        catch { XCTAssertTrue(error is CancellationError) }
    }

    func testRTPACKWithoutDecodedFrameCleansOriginalStopBeforeAUTOFallback() async throws {
        for stopFails in [false, true] {
            let peer = MockCameraHTTPTransport(), native = LifecycleNativeSession(fail: true)
            await peer.enqueueJSON(path: "/ccapi", body: discovery)
            await peer.enqueue(path: root + "/rtpsessiondesc", body: Data(sdp.utf8))
            await peer.enqueue(method: "POST", path: root + "/rtp", status: 204, body: Data())
            await peer.enqueue(method: "POST", path: root + "/rtp", status: stopFails ? 503 : 204, body: Data())
            if !stopFails {
                await peer.enqueue(method: "POST", path: root, status: 204, body: Data())
                await peer.enqueue(path: root + "/flip?t=0", headers: ["content-type": "image/jpeg"], body: jpeg)
            }
            let client = try CCAPIClient(baseURL: "http://192.0.2.1:8080", mode: .camera,
                rtpDestinationAddress: "192.0.2.2", rtpSessionFactory: LifecycleNativeFactory(session: native), transport: peer)
            do {
                try await client.startLiveView(.init(source: .auto))
                XCTAssertFalse(stopFails)
                let source = await client.currentLiveViewSource(); XCTAssertEqual(source, .ccapiJPEGPolling)
                let frame = try await client.liveViewFrame(cacheKey: 99); XCTAssertEqual(frame.data, jpeg)
            } catch { XCTAssertTrue(stopFails); XCTAssertEqual(error as? CCAPIError, .liveViewStopUnconfirmed) }
            let requests = await peer.requests()
            XCTAssertEqual(requests.map(\.path), ["/ccapi", root + "/rtpsessiondesc", root + "/rtp", root + "/rtp"]
                + (stopFails ? [] : [root, root + "/flip?t=0"]))
            let payload = try XCTUnwrap(JSONSerialization.jsonObject(with: XCTUnwrap(requests[3].body)) as? [String: String])
            XCTAssertEqual(payload, ["action": "stop", "ipaddress": ""])
            let closed = await native.closed; XCTAssertTrue(closed)
            let stop = await client.liveViewStopState(); XCTAssertEqual(stop.stopUnconfirmed, stopFails)
        }
    }

    func testExplicitRTPDecodeFailureNeverFallsBack() async throws {
        let peer = MockCameraHTTPTransport(), native = LifecycleNativeSession(fail: true)
        await peer.enqueueJSON(path: "/ccapi", body: discovery)
        await peer.enqueue(path: root + "/rtpsessiondesc", body: Data(sdp.utf8))
        for _ in 0..<2 { await peer.enqueue(method: "POST", path: root + "/rtp", status: 204, body: Data()) }
        let client = try CCAPIClient(baseURL: "http://192.0.2.1:8080", mode: .camera,
            rtpDestinationAddress: "192.0.2.2", rtpSessionFactory: LifecycleNativeFactory(session: native), transport: peer)
        do { try await client.startLiveView(.init(source: .ccapiRTP)); XCTFail("No decoded video") } catch {}
        let source = await client.currentLiveViewSource(); XCTAssertNil(source)
        let requests = await peer.requests(); XCTAssertEqual(requests.count, 4)
    }

    func testRTPWaitCancellationRetainsFailedStopAndNeverStartsJPEG() async throws {
        let peer = MockCameraHTTPTransport(), native = LifecycleNativeSession(fail: false)
        await peer.enqueueJSON(path: "/ccapi", body: discovery)
        await peer.enqueue(path: root + "/rtpsessiondesc", body: Data(sdp.utf8))
        await peer.enqueue(method: "POST", path: root + "/rtp", status: 204, body: Data())
        await peer.enqueue(method: "POST", path: root + "/rtp", status: 503, body: Data())
        let client = try CCAPIClient(baseURL: "http://192.0.2.1:8080", mode: .camera,
            rtpDestinationAddress: "192.0.2.2", rtpSessionFactory: LifecycleNativeFactory(session: native), transport: peer)
        let start = Task { try await client.startLiveView(.init(source: .auto)) }
        try await wait { await native.waiting }
        let before = await client.currentLiveViewSource(); XCTAssertNil(before)
        start.cancel()
        do { try await start.value; XCTFail("Cancellation cannot become fallback success") }
        catch { XCTAssertTrue(error is CancellationError) }
        let state = await client.liveViewStopState(); XCTAssertTrue(state.stopUnconfirmed)
        let requests = await peer.requests(); XCTAssertEqual(requests.count, 4)
    }

    func testJPEGDecoderRejectionRetainsCleanupAndDoesNotPublishMarkersAsFrame() async throws {
        let peer = MockCameraHTTPTransport()
        await peer.enqueueJSON(path: "/ccapi", body: discovery)
        await peer.enqueue(method: "POST", path: root, status: 204, body: Data())
        await peer.enqueue(path: root + "/flip?t=0", headers: ["content-type": "image/jpeg"], body: jpeg)
        await peer.enqueue(method: "DELETE", path: root, status: 204, body: Data())
        let client = try CCAPIClient(baseURL: "http://192.0.2.1:8080", mode: .camera,
            decodedJPEGValidator: { _ in false }, transport: peer)
        do { try await client.startLiveView(.init(source: .ccapiJPEGPolling)); XCTFail("Rejected decode") } catch {}
        let source = await client.currentLiveViewSource(); XCTAssertNil(source)
        let state = await client.liveViewStopState(); XCTAssertEqual(state, .idle)
        let requests = await peer.requests(); XCTAssertEqual(requests.map(\.method), ["GET", "POST", "GET", "DELETE"])
    }

    func testFirstSizeRejectionNeverRetriesStartWhenOriginalStopIsUnknown() async throws {
        let peer = MockCameraHTTPTransport()
        await peer.enqueueJSON(path: "/ccapi", body: discovery)
        await peer.enqueue(method: "POST", path: root, status: 204, body: Data())
        await peer.enqueueJSON(path: root + "/flip?t=0", status: 503, body: #"{"message":"Mode not supported"}"#)
        await peer.enqueue(method: "DELETE", path: root, status: 503, body: Data())
        let client = try CCAPIClient(baseURL: "http://192.0.2.1:8080", mode: .camera, transport: peer)
        do { try await client.startLiveView(.init(size: .medium, source: .auto)); XCTFail("Unknown Stop") }
        catch { XCTAssertEqual(error as? CCAPIError, .liveViewStopUnconfirmed) }
        let requests = await peer.requests(); XCTAssertEqual(requests.map(\.method), ["GET", "POST", "GET", "DELETE"])
    }

    func testMultipartDecodedJPEGRejectionStopsOriginalBeforeJPEGFallback() async throws {
        let peer = MockCameraHTTPTransport()
        let discovery = #"{"ver130":[{"path":"/shooting/liveview","post":true,"delete":true},{"path":"/shooting/liveview/flip","get":true},{"path":"/shooting/liveview/multipart","get":true,"delete":true}]}"#
        await peer.enqueueJSON(path: "/ccapi", body: discovery)
        await peer.enqueue(method: "POST", path: root, status: 204, body: Data())
        let rejected = Data([0xff, 0xd8, 0xff, 0xd9])
        await peer.enqueueLiveStream(path: root + "/multipart", headers: ["content-type": "multipart/x-mixed-replace;boundary=b"],
            body: Data("--b\r\nContent-Type: image/jpeg\r\nContent-Length: 4\r\n\r\n".utf8) + rejected)
        await peer.enqueue(method: "DELETE", path: root + "/multipart", status: 204, body: Data())
        await peer.enqueue(method: "DELETE", path: root, status: 204, body: Data())
        await peer.enqueue(method: "POST", path: root, status: 204, body: Data())
        await peer.enqueue(path: root + "/flip?t=0", headers: ["content-type": "image/jpeg"], body: jpeg)
        let client = try CCAPIClient(baseURL: "http://192.0.2.1:8080", mode: .camera,
            decodedJPEGValidator: { $0.count > 4 }, transport: peer)
        try await client.startLiveView(.init(source: .auto))
        let source = await client.currentLiveViewSource(); XCTAssertEqual(source, .ccapiJPEGPolling)
        let requests = await peer.requests()
        XCTAssertEqual(requests.map(\.method), ["GET", "POST", "GET", "DELETE", "DELETE", "POST", "GET"])
    }

    func testMultipartAlreadyClosedCandidateCannotPublishRetainedFrame() async throws {
        let output = AsyncThrowingStream<Data, Error>.makeStream()
        output.continuation.yield(Data("--b\r\nContent-Type: image/jpeg\r\nContent-Length: 5\r\n\r\n".utf8)
            + jpeg + Data("\r\n--b--\r\n".utf8))
        output.continuation.finish()
        let session = CCAPIMultipartLiveViewSession(response: .init(statusCode: 200, chunks: output.stream), boundary: "b")
        defer { session.close() }
        for _ in 0..<20 { await Task.yield() }
        do {
            _ = try await session.validatedFirstFrame(); try session.checkUsable()
            XCTFail("A known EOF cannot publish an already retired source")
        } catch {}
    }

    func testLocalReaderRetirementRetainsExactStopWithoutCameraCommand() async throws {
        let peer = MockCameraHTTPTransport()
        await peer.enqueueJSON(path: "/ccapi", body: discovery)
        await peer.enqueue(method: "POST", path: root, status: 204, body: Data())
        await peer.enqueue(path: root + "/flip?t=0", headers: ["content-type": "image/jpeg"], body: jpeg)
        let client = try CCAPIClient(baseURL: "http://192.0.2.1:8080", mode: .camera, transport: peer)
        try await client.startLiveView(.init(source: .ccapiJPEGPolling))
        let before = await peer.requests()
        await client.retireLiveViewReader()
        let retired = await peer.requests(), stop = await client.liveViewStopState()
        XCTAssertEqual(retired.count, before.count)
        XCTAssertTrue(stop.stopRequired); XCTAssertFalse(stop.stopUnconfirmed)
        let source = await client.currentLiveViewSource(); XCTAssertNil(source)
        await peer.enqueue(method: "DELETE", path: root, status: 204, body: Data())
        try await client.retryLiveViewStop()
        let after = await peer.requests(); XCTAssertEqual(after.last?.method, "DELETE")
        XCTAssertEqual(after.count, before.count + 1)
    }

    func testPartialHTTPJPEGIsNeverPublishedEvenWithCompleteImageEnvelope() async throws {
        let peer = MockCameraHTTPTransport()
        await peer.enqueueJSON(path: "/ccapi", body: discovery)
        await peer.enqueue(method: "POST", path: root, status: 204, body: Data())
        await peer.enqueue(path: root + "/flip?t=0", status: 206,
            headers: ["content-type": "image/jpeg", "content-range": "bytes 0-4/10"], body: jpeg)
        await peer.enqueue(method: "DELETE", path: root, status: 204, body: Data())
        let client = try CCAPIClient(baseURL: "http://192.0.2.1:8080", mode: .camera, transport: peer)
        do { try await client.startLiveView(.init(source: .ccapiJPEGPolling)); XCTFail("Partial HTTP image") } catch {}
        let source = await client.currentLiveViewSource(); XCTAssertNil(source)
        let requests = await peer.requests(); XCTAssertEqual(requests.map(\.method), ["GET", "POST", "GET", "DELETE"])
    }

    func testMultipartReaderCancellationDoesNotTerminateSourceAndEOFNeverReplaysFrame() async throws {
        let output = AsyncThrowingStream<Data, Error>.makeStream()
        let response = CameraHTTPStreamResponse(statusCode: 200, chunks: output.stream,
            cancel: { output.continuation.finish(throwing: CancellationError()) })
        let session = CCAPIMultipartLiveViewSession(response: response, boundary: "b")
        defer { session.close() }
        let cancelled = Task { try await session.nextFrame() }; cancelled.cancel()
        do { _ = try await cancelled.value; XCTFail("Cancelled reader") } catch { XCTAssertTrue(error is CancellationError) }
        output.continuation.yield(Data("--b\r\nContent-Type: image/jpeg\r\nContent-Length: 5\r\n\r\n".utf8) + jpeg)
        let first = try await session.nextFrame(); XCTAssertEqual(first, jpeg)
        output.continuation.finish()
        do { _ = try await session.nextFrame(timeoutNanoseconds: 100_000_000); XCTFail("EOF must be terminal") } catch {}
        do { _ = try await session.nextFrame(timeoutNanoseconds: 1_000_000); XCTFail("Do not replay last frame") } catch {}
    }

    func testMultipartFirstAndSubsequentFramesHaveBoundedDeadline() async throws {
        let output = AsyncThrowingStream<Data, Error>.makeStream()
        let session = CCAPIMultipartLiveViewSession(response: .init(statusCode: 200, chunks: output.stream,
            cancel: { output.continuation.finish() }), boundary: "b")
        defer { session.close() }
        do { _ = try await session.nextFrame(timeoutNanoseconds: 1_000_000); XCTFail("No indefinite read") } catch {}
        output.continuation.yield(Data("--b\r\nContent-Type: image/jpeg\r\nContent-Length: 5\r\n\r\n".utf8) + jpeg)
        do { _ = try await session.nextFrame(); XCTFail("Late frame cannot revive timed-out source") } catch {}
    }

    private func wait(_ predicate: () async -> Bool) async throws {
        let deadline = ContinuousClock.now.advanced(by: .seconds(2))
        while ContinuousClock.now < deadline { if await predicate() { return }; await Task.yield() }
        XCTFail("Fixture did not reach its gate"); throw URLError(.timedOut)
    }
}

private actor LifecycleNativeSession: CCAPIRTPSession {
    nonisolated let sourceURL = URL(string: "rtp://192.0.2.2:12000")!
    private let gate = CCAPIRTPReadinessGate()
    private let fail: Bool
    private(set) var waiting = false
    private(set) var closed = false
    init(fail: Bool) { self.fail = fail }
    func start() async throws {}
    func waitUntilReady() async throws {
        waiting = true
        if fail { throw CCAPIError.invalidResponse("Synthetic first-decode failure") }
        try await gate.wait()
    }
    func setTargetFPS(_ fps: Int) async {}
    func close() async { closed = true; gate.fail(CancellationError()) }
}
private struct LifecycleNativeFactory: CCAPIRTPSessionFactory {
    let session: LifecycleNativeSession
    func makeSession(description: CCAPIRTPSessionDescription, destinationAddress: String) async throws -> any CCAPIRTPSession { session }
}
