import Foundation
import XCTest
@testable import OpenEOSCore

final class CCAPIFocusInfoClientTests: XCTestCase {
    func testFocusInfoUsesOnlyAdvertisedCanonicalGETAndSeparateBinaryPacket() async throws {
        let peer = MockCameraHTTPTransport()
        await peer.enqueueJSON(path: "/ccapi", body: discovery)
        let client = try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: .camera, transport: peer)
        let capabilities = try await client.capabilities()
        XCTAssertTrue(capabilities.liveView.focusInfoSupported)
        let beforeLiveView = try await client.liveViewFocusInfo()
        XCTAssertNil(beforeLiveView)
        await peer.enqueue(method: "POST", path: "/ccapi/ver130/shooting/liveview", body: Data())
        try await client.startLiveView()
        let json = Data(#"{"liveviewdata":{"image":{"positionx":0,"positiony":0,"positionwidth":1000,"positionheight":500},"zoom":{"magnification":1},"afframe":[{"select":1,"status":17,"x":100,"y":100,"width":200,"height":100}]}}"#.utf8)
        let length = UInt32(json.count)
        let packet = Data([0xff, 0, 1, UInt8((length >> 24) & 255), UInt8((length >> 16) & 255), UInt8((length >> 8) & 255), UInt8(length & 255)]) + json + Data([0xff, 0xff])
        await peer.enqueue(path: "/ccapi/ver130/shooting/liveview/flipdetail?kind=info", body: packet)
        let info = try await client.liveViewFocusInfo()
        XCTAssertEqual(info?.frames.first?.status, .focused)
        let requests = await peer.requests()
        let request = try XCTUnwrap(requests.last)
        XCTAssertEqual(request.method, "GET")
        XCTAssertEqual(request.path, "/ccapi/ver130/shooting/liveview/flipdetail?kind=info")
        XCTAssertEqual(request.timeoutInterval, 2)
        XCTAssertEqual(request.headers["Accept"], "application/octet-stream")
        await peer.enqueue(method: "DELETE", path: "/ccapi/ver130/shooting/liveview", body: Data())
        await client.stopLiveView()
        let afterStop = try await client.liveViewFocusInfo()
        XCTAssertNil(afterStop)
    }

    func testMissingOrWrongMethodFocusInfoIsNotAdvertisedAndNeverProbed() async throws {
        for api in [#"{"ver130":[{"path":"/shooting/liveview","post":true},{"path":"/shooting/liveview/flip","get":true}]}"#,
            #"{"ver130":[{"path":"/shooting/liveview/flipdetail","post":true}]}"#] {
            let peer = MockCameraHTTPTransport()
            await peer.enqueueJSON(path: "/ccapi", body: api)
            let client = try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: .camera, transport: peer)
            let capabilities = try await client.capabilities()
            XCTAssertFalse(capabilities.liveView.focusInfoSupported)
            let info = try await client.liveViewFocusInfo()
            XCTAssertNil(info)
            let requests = await peer.requests()
            XCTAssertEqual(requests.map(\.path), ["/ccapi"])
        }
    }

    func testDetailedFocusInfoWithoutUsableLiveViewSourceIsNotAdvertisedOrProbed() async throws {
        let peer = MockCameraHTTPTransport()
        await peer.enqueueJSON(path: "/ccapi", body:
            #"{"ver130":[{"path":"/shooting/liveview/flipdetail","get":true}]}"#)
        let client = try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: .camera, transport: peer)
        let capabilities = try await client.capabilities()
        XCTAssertTrue(capabilities.liveView.sources.isEmpty)
        XCTAssertFalse(capabilities.liveView.focusInfoSupported)
        let info = try await client.liveViewFocusInfo()
        XCTAssertNil(info)
        let requests = await peer.requests()
        XCTAssertEqual(requests.map(\.path), ["/ccapi"])
    }

    private var discovery: String {
        #"{"ver130":[{"path":"/shooting/liveview","post":true,"delete":true},{"path":"/shooting/liveview/flip","get":true},{"path":"/shooting/liveview/flipdetail","get":true}]}"#
    }
}
