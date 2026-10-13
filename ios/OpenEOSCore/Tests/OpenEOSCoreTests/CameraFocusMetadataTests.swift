import Foundation
import XCTest
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
@testable import OpenEOSCore

final class CameraFocusMetadataTests: XCTestCase {
    func testCanonicalPacketSelectsFramesDecodesStatusAndClipsGeometry() throws {
        let root = fixture(frames: [frame(status: 0x21), frame(status: 0x14, selected: 0),
            frame(status: 0xf1), frame(status: 0x12, x: 50)])
        let info = try XCTUnwrap(CameraFocusMetadata.canonPacket(packet(root)))
        XCTAssertEqual(info.frames.count, 3)
        XCTAssertEqual(info.frames[0].status, .focused)
        XCTAssertEqual(info.frames[0].kind, .face)
        XCTAssertEqual(info.frames[0].left, 0.1, accuracy: 0.0001)
        XCTAssertEqual(info.frames[0].top, 0.1, accuracy: 0.0001)
        XCTAssertEqual(info.frames[1].status, .unknown, "Unknown high bits never confirm optical focus")
        XCTAssertEqual(info.frames[2].left, 0)
        XCTAssertEqual(info.frames[2].status, .unfocused)
        XCTAssertEqual(info.imageAspectRatio, 2)
    }

    func testEveryKnownStatusAndUnknownLowBitsAreDistinct() throws {
        let info = try XCTUnwrap(CameraFocusMetadata.canonJSON(fixture(frames: (0...5).map { frame(status: 0x10 + $0) })))
        XCTAssertEqual(info.frames.map(\.status), [.standby, .focused, .unfocused, .servoOff, .focusing, .unknown])
    }

    func testMagnificationHidesFramesAndGeometryMissingIsUnknown() throws {
        let info = try XCTUnwrap(CameraFocusMetadata.canonJSON(fixture(zoom: 5)))
        XCTAssertTrue(info.frames.isEmpty)
        XCTAssertFalse(info.matchesImage(aspectRatio: 2))
        let bridgeZoom = try XCTUnwrap(CameraFocusMetadata.bridgeJSON([
            "magnification": 5, "imageAspectRatio": 2, "frames": ["non-overlay metadata"]]))
        XCTAssertTrue(bridgeZoom.frames.isEmpty)
        XCTAssertNil(try CameraFocusMetadata.canonJSON([:]))
        var root = fixture()
        root["liveviewdata"] = ["image": ["positionx": 0, "positiony": 0, "positionwidth": 0, "positionheight": 10],
            "zoom": ["magnification": 1], "afframe": []]
        XCTAssertNil(try CameraFocusMetadata.canonJSON(root))
    }

    func testBoolStringFractionAndOutOfRangeNumbersNeverBecomeValidFrames() throws {
        var invalidSelection = frame(); invalidSelection["select"] = true
        var invalidStatus = frame(); invalidStatus["status"] = "17"
        var fraction = frame(); fraction["x"] = 100.5
        var outside = frame(); outside["x"] = Int64.max
        var zero = frame(); zero["width"] = 0
        let info = try XCTUnwrap(CameraFocusMetadata.canonJSON(fixture(frames: [invalidSelection, invalidStatus, fraction, outside, zero])))
        XCTAssertTrue(info.frames.isEmpty)
    }

    func testTruncatedWrongTypeLengthFooterAndOversizedPacketsAreRejected() throws {
        let valid = try packet(fixture())
        var type = valid; type[2] = 0
        var length = valid; length[6] &+= 1
        var footer = valid; footer[footer.count - 1] = 0
        for payload in [Data(), valid.dropLast(), type, length, footer, Data(repeating: 0, count: CameraFocusMetadata.maximumBytes + 1)] {
            XCTAssertThrowsError(try CameraFocusMetadata.canonPacket(Data(payload)))
        }
        XCTAssertThrowsError(try CameraFocusMetadata.canonJSON(fixture(frames: Array(repeating: frame(), count: 513))))
    }

    func testBridgeOptionalNullUnknownAndMalformedAreConservative() throws {
        XCTAssertNil(try CameraFocusMetadata.bridgeJSON(NSNull()))
        let info = try XCTUnwrap(CameraFocusMetadata.bridgeJSON([
            "magnification": 1, "imageAspectRatio": 2.0,
            "frames": [["left": 0.1, "top": 0.2, "right": 0.5, "bottom": 0.6, "status": "FOCUSED", "kind": "FUTURE"]]]))
        XCTAssertEqual(info.frames.first?.status, .unknown)
        XCTAssertThrowsError(try CameraFocusMetadata.bridgeJSON(["magnification": true, "imageAspectRatio": 2, "frames": []]))
        XCTAssertThrowsError(try CameraFocusMetadata.bridgeJSON(["magnification": 1, "imageAspectRatio": 2,
            "frames": [["left": -0.1, "top": 0, "right": 1, "bottom": 1, "status": "FOCUSED", "kind": "NORMAL"]]]))
    }

    func testNewFocusPacketsRejectUTF16UTF32AndMalformedUTF8() throws {
        let bridge = #"{"frames":[],"magnification":1,"imageAspectRatio":2}"#
        let canon = String(decoding: try JSONSerialization.data(withJSONObject: fixture()), as: UTF8.self)
        for encoding in [String.Encoding.utf16, .utf16BigEndian, .utf16LittleEndian,
                         .utf32, .utf32BigEndian, .utf32LittleEndian] {
            XCTAssertThrowsError(try CameraFocusMetadata.bridgePacket(try XCTUnwrap(bridge.data(using: encoding))))
            let encodedCanon = try XCTUnwrap(canon.data(using: encoding))
            XCTAssertThrowsError(try CameraFocusMetadata.canonPacket(rawPacket(encodedCanon)))
        }
        let malformed = Data([0x7b, 0x22, 0xc3, 0x28, 0x22, 0x7d])
        XCTAssertThrowsError(try CameraFocusMetadata.bridgePacket(malformed))
        XCTAssertThrowsError(try CameraFocusMetadata.canonPacket(rawPacket(malformed)))
        XCTAssertNotNil(try CameraFocusMetadata.bridgePacket(Data(bridge.utf8)))
        XCTAssertNotNil(try CameraFocusMetadata.canonPacket(rawPacket(Data(canon.utf8))))
    }

    func testOverlayRejectsAspectMismatchFutureTimestampAndOneSecondOldData() throws {
        let info = try XCTUnwrap(CameraFocusMetadata.canonJSON(fixture()))
        XCTAssertTrue(info.matchesImage(aspectRatio: 2))
        XCTAssertFalse(info.matchesImage(aspectRatio: 1.5))
        XCTAssertFalse(info.matchesImage(aspectRatio: .nan))
        let time = Date(timeIntervalSince1970: 100)
        XCTAssertTrue(CameraFocusInfo.isFresh(sampledAt: time, now: time.addingTimeInterval(0.999)))
        XCTAssertFalse(CameraFocusInfo.isFresh(sampledAt: time, now: time.addingTimeInterval(1)))
        XCTAssertFalse(CameraFocusInfo.isFresh(sampledAt: time, now: time.addingTimeInterval(-0.001)))
    }

    func testBoundedReadCancelsOnSuccessFailureAndByteOverflow() async throws {
        for size in [8, CameraFocusMetadata.maximumBytes + 1] {
            let peer = FocusStreamPeer(size: size)
            let request = URLRequest(url: URL(string: "http://127.0.0.1/focus")!)
            do {
                let data = try await CameraFocusMetadata.boundedRead(request, transport: peer)
                XCTAssertEqual(data.count, 8)
            } catch { XCTAssertGreaterThan(size, CameraFocusMetadata.maximumBytes) }
            XCTAssertTrue(peer.cancelled)
        }
    }

    private func frame(status: Int = 0x11, selected: Int = 1, x: Int = 200) -> [String: Any] {
        ["select": selected, "status": status, "x": x, "y": 100, "width": 200, "height": 100]
    }
    private func fixture(zoom: Int = 1, frames: [[String: Any]]? = nil) -> [String: Any] {
        ["liveviewdata": ["image": ["positionx": 100, "positiony": 50, "positionwidth": 1000, "positionheight": 500],
            "zoom": ["magnification": zoom], "afframe": frames ?? [frame()]]]
    }
    private func packet(_ root: [String: Any]) throws -> Data {
        let json = try JSONSerialization.data(withJSONObject: root)
        return rawPacket(json)
    }
    private func rawPacket(_ json: Data) -> Data {
        let length = UInt32(json.count)
        return Data([0xff, 0, 1, UInt8((length >> 24) & 255), UInt8((length >> 16) & 255), UInt8((length >> 8) & 255), UInt8(length & 255)]) + json + Data([0xff, 0xff])
    }
}

private final class FocusStreamPeer: CameraHTTPTransport, @unchecked Sendable {
    let size: Int
    private let lock = NSLock()
    private var didCancel = false
    var cancelled: Bool { lock.lock(); defer { lock.unlock() }; return didCancel }
    init(size: Int) { self.size = size }
    func send(_ request: URLRequest) async throws -> CameraHTTPResponse { throw URLError(.unsupportedURL) }
    func download(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse { throw URLError(.unsupportedURL) }
    func openStream(_ request: URLRequest) async throws -> CameraHTTPStreamResponse {
        CameraHTTPStreamResponse(statusCode: 200, headers: [:], chunks: AsyncThrowingStream { continuation in
            continuation.yield(Data(repeating: 0, count: size))
            continuation.finish()
        }, cancel: { self.lock.lock(); self.didCancel = true; self.lock.unlock() })
    }
}
