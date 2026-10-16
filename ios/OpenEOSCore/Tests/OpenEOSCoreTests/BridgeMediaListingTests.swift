import Foundation
import XCTest
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
@testable import OpenEOSCore

/// Synthetic protocol/transport fixtures, not physical-camera evidence.
final class BridgeMediaListingTests: XCTestCase {
    func testFragmentedUnicodeAndChunkedSnapshotsPreserveEveryOpaqueIdentity() throws {
        let ids = [" 影像%2F+ ", "caf\u{e9}", "cafe\u{301}"] + (0..<1_001).map { "image-\($0)" }
        var decoder = BridgeMediaListingDecoder(sessionID: "session", limit: nil)
        var sequence = 0
        var snapshots = [[CameraMediaItem]]()
        func feed(_ value: [String: Any]) throws {
            for byte in try line(value) {
                if let snapshot = try decoder.consume(byte, parseItem: parse) { snapshots.append(snapshot) }
            }
        }
        try feed(record("start", sequence, ["limit": NSNull()]))
        for offset in stride(from: 0, to: ids.count, by: 128) {
            sequence += 1
            let end = min(offset + 128, ids.count)
            try feed(record("snapshot", sequence, ["revision": 1, "offset": offset, "total": ids.count,
                "items": ids[offset..<end].map(item), "last": end == ids.count]))
            XCTAssertEqual(snapshots.count, end == ids.count ? 1 : 0)
        }
        try feed(record("complete", sequence + 1, ["revision": 1, "count": ids.count, "scopeComplete": true]))
        let result = try decoder.finish()
        XCTAssertEqual(result.map { Data($0.id.utf8) }, ids.map { Data($0.utf8) })
        XCTAssertEqual(snapshots.count, 1)
    }

    func testMalformedBindingsTerminalFramingAndResourcesNeverComplete() throws {
        let good = frames()
        var cases = [[Data]]()
        cases.append(try good.dropLast().map(line))
        cases.append(try good.map(line) + [Data("\n".utf8)])
        var truncated = try good.map(line); truncated[2].removeLast(); cases.append(truncated)
        for (index, key, value) in [
            (0, "limit", 1 as Any), (0, "requestId", ""), (1, "requestId", "other"),
            (1, "sessionId", "other"), (1, "scope", "recent"), (1, "seq", true),
            (1, "seq", 3), (1, "seq", 9_007_199_254_740_992 as Int64),
            (1, "revision", 2), (1, "offset", 1), (1, "total", 100_001),
            (1, "last", false), (1, "items", [item("same"), item("same")]),
            (2, "count", 2), (2, "revision", 2), (2, "scopeComplete", 1), (2, "type", "unexpected"),
        ] {
            var broken = good; broken[index][key] = value; cases.append(try broken.map(line))
        }
        cases.append([Data([0xff, 10])])
        cases.append([Data((String(repeating: " ", count: 65_536) + "\n").utf8)])
        cases.append([Data("{\"type\":\"start\",\"type\":\"start\"}\n".utf8)])
        for bytes in cases {
            var decoder = BridgeMediaListingDecoder(sessionID: "session", limit: nil)
            XCTAssertThrowsError(try {
                for chunk in bytes { for byte in chunk { _ = try decoder.consume(byte, parseItem: parse) } }
                _ = try decoder.finish()
            }())
        }
    }

    func testErrorDuringUnfinishedRevisionRetainsOnlyLastPublishedSnapshot() throws {
        var decoder = BridgeMediaListingDecoder(sessionID: "session", limit: nil)
        var published = [[CameraMediaItem]]()
        let data = Array(frames().prefix(2)) + [
            record("snapshot", 2, ["revision": 2, "offset": 0, "total": 2, "items": [item("new")], "last": false]),
            record("error", 3, ["code": "RESOURCE_LIMIT", "message": "Synthetic limit."]),
        ]
        for value in data { for byte in try line(value) {
            if let snapshot = try decoder.consume(byte, parseItem: parse) { published.append(snapshot) }
        } }
        XCTAssertEqual(published.map { $0.map(\.id) }, [["image"]])
        XCTAssertThrowsError(try decoder.finish())
        XCTAssertThrowsError(try decoder.consume(10, parseItem: parse))
    }

    func testBridgePublishesBeforeTerminalAndUsesOnlyOneAuthenticatedGET() async throws {
        let peer = ListingPeer()
        let client = try await makeClient(peer)
        let progress = MediaListProgressRecorder()
        let listing = Task { try await client.listMedia { await progress.record($0) } }
        try await wait { await peer.count() == 1 }
        for value in frames().prefix(2) { await peer.feed(0, try line(value), bytewise: true) }
        try await wait { await progress.values().count == 1 }
        await peer.feed(0, try line(frames()[2]))
        await peer.finish(0)
        let result = try await listing.value
        XCTAssertEqual(result.map(\.id), ["image"])
        let requests = await peer.listingRequests()
        XCTAssertEqual(requests.count, 1)
        XCTAssertEqual(requests[0].value(forHTTPHeaderField: "Accept"), BridgeMediaListingDecoder.mediaType)
        XCTAssertEqual(requests[0].value(forHTTPHeaderField: "Authorization"), "Bearer synthetic-token")
        XCTAssertNil(requests[0].url?.query)
    }

    func testCancellationAndSameIDReplacementRejectLateProgressAndCompletion() async throws {
        for cancel in [true, false] {
            let peer = ListingPeer()
            let client = try await makeClient(peer)
            let progress = MediaListProgressRecorder()
            let listing = Task { try await client.listMedia { await progress.record($0) } }
            try await wait { await peer.count() == 1 }
            for value in frames().prefix(2) { await peer.feed(0, try line(value)) }
            try await wait { await progress.values().count == 1 }
            if cancel { listing.cancel() } else { await client.close(); try await client.initialize() }
            await peer.feed(0, try line(record("snapshot", 2, ["revision": 2, "offset": 0, "total": 1,
                "items": [item("stale")], "last": true])))
            await peer.feed(0, try line(record("complete", 3, ["revision": 2, "count": 1, "scopeComplete": true])))
            await peer.finish(0)
            do { _ = try await listing.value; XCTFail("Retired listing completed") } catch {}
            let values = await progress.values()
            XCTAssertEqual(values.map { $0.map(\.id) }, [["image"]])
            if !cancel {
                let replacement = Task { try await client.listMedia() }
                try await wait { await peer.count() == 2 }
                for value in frames() { await peer.feed(1, try line(value)) }
                await peer.finish(1)
                let result = try await replacement.value
                XCTAssertEqual(result.map(\.id), ["image"])
            }
        }
    }

    func testLegacyJSONAndTruncatedStreamNeverSendFallbackRequest() async throws {
        for legacy in [true, false] {
            let peer = ListingPeer(contentType: legacy ? "application/json" : BridgeMediaListingDecoder.mediaType)
            let client = try await makeClient(peer)
            let progress = MediaListProgressRecorder()
            let listing = Task { try await client.listMedia { await progress.record($0) } }
            try await wait { await peer.count() == 1 }
            if legacy { await peer.feed(0, Data("{\"items\":[{\"id\":\"image\",\"name\":\"IMG.JPG\"}]}".utf8)) }
            else { for value in frames().prefix(2) { await peer.feed(0, try line(value)) } }
            await peer.finish(0)
            if legacy { _ = try await listing.value }
            else { do { _ = try await listing.value; XCTFail("EOF completed") } catch {} }
            let requests = await peer.listingRequests()
            let values = await progress.values()
            XCTAssertEqual(requests.count, 1)
            XCTAssertEqual(values.map { $0.map(\.id) }, [["image"]])
        }
    }

    func testLegacyRejectsInvalidRowsDuplicateIdentitiesAndExcessLimitWithoutPublishing() async throws {
        let valid = "{\"id\":\"image\",\"name\":\"IMG.JPG\",\"kind\":\"image\"}"
        let cases: [(String, String, Int?)] = [
            ("non-object row", "{\"items\":[\(valid),null]}", nil),
            ("invalid row", "{\"items\":[\(valid),{\"id\":\"broken\",\"kind\":\"image\"}]}", nil),
            ("duplicate identity", "{\"items\":[\(valid),\(valid)]}", nil),
            ("escaped duplicate identity", "{\"items\":[\(valid),{\"id\":\"\\u0069mage\",\"name\":\"OTHER.JPG\",\"kind\":\"image\"}]}", nil),
            ("requested limit", "{\"items\":[\(valid),{\"id\":\"other\",\"name\":\"OTHER.JPG\",\"kind\":\"image\"}]}", 1),
        ]
        for (name, body, limit) in cases {
            let peer = ListingPeer(contentType: "application/json")
            let client = try await makeClient(peer)
            let progress = MediaListProgressRecorder()
            let listing = Task { try await client.listMedia(maximumItems: limit) { await progress.record($0) } }
            try await wait { await peer.count() == 1 }
            await peer.feed(0, Data(body.utf8))
            await peer.finish(0)
            do { _ = try await listing.value; XCTFail("Accepted \(name)") }
            catch { guard case DesktopBridgeError.invalidResponse = error else { throw error } }
            let published = await progress.values()
            let requests = await peer.listingRequests()
            XCTAssertTrue(published.isEmpty, "A failed legacy response cannot replace a previous partial: \(name)")
            XCTAssertEqual(requests.count, 1, "No fallback traversal: \(name)")
        }
    }

    func testLegacyFullCardItemBoundaryRejectsOverflowWithoutTruncation() async throws {
        for count in [100_000, 100_001] {
            let peer = ListingPeer(contentType: "application/json")
            let client = try await makeClient(peer)
            let progress = MediaListProgressRecorder()
            let listing = Task { try await client.listMedia { await progress.record($0) } }
            try await wait { await peer.count() == 1 }
            let rows = (0..<count).map { item("image-\($0)") }
            await peer.feed(0, try JSONSerialization.data(withJSONObject: ["items": rows]))
            await peer.finish(0)
            if count == 100_000 {
                let result = try await listing.value
                XCTAssertEqual(result.count, count)
                XCTAssertEqual(result.last?.id, "image-99999")
            } else {
                do { _ = try await listing.value; XCTFail("Accepted legacy item overflow") }
                catch { guard case DesktopBridgeError.invalidResponse = error else { throw error } }
            }
            let published = await progress.values()
            let requests = await peer.listingRequests()
            XCTAssertEqual(published.map(\.count), count == 100_000 ? [count] : [])
            XCTAssertEqual(requests.count, 1)
            XCTAssertNil(requests[0].url?.query)
        }
    }

    func testLegacyEmptyAndExactLimitPreserveOpaqueUnicodeIdentities() async throws {
        let ids = [" 影像%2F+ ", "caf\u{e9}", "cafe\u{301}"]
        for expected in [[], ids] {
            let peer = ListingPeer(contentType: "application/json")
            let client = try await makeClient(peer)
            let progress = MediaListProgressRecorder()
            let listing = Task { try await client.listMedia(maximumItems: 3) { await progress.record($0) } }
            try await wait { await peer.count() == 1 }
            await peer.feed(0, try JSONSerialization.data(withJSONObject: ["items": expected.map(item)]))
            await peer.finish(0)
            let result = try await listing.value
            let published = await progress.values()
            let requests = await peer.listingRequests()
            XCTAssertEqual(result.map { Data($0.id.utf8) }, expected.map { Data($0.utf8) })
            XCTAssertEqual(published.count, 1)
            XCTAssertEqual(requests.count, 1)
            XCTAssertEqual(requests[0].url?.query, "limit=3")
        }
    }

    private func makeClient(_ peer: ListingPeer) async throws -> DesktopBridgeClient {
        let client = try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", token: "synthetic-token", transport: peer)
        try await client.initialize()
        return client
    }
    private func wait(_ condition: () async -> Bool) async throws {
        for _ in 0..<3_000 { if await condition() { return }; try await Task.sleep(nanoseconds: 1_000_000) }
        throw DesktopBridgeError.invalidResponse("Synthetic listing fixture timed out.")
    }
    private func parse(_ value: [String: Any]) -> CameraMediaItem? {
        guard let id = value["id"] as? String, let name = value["name"] as? String else { return nil }
        return CameraMediaItem(id: id, name: name, kind: "image")
    }
    private func item(_ id: String) -> [String: Any] { ["id": id, "name": "影像📷.JPG", "kind": "image"] }
    private func record(_ type: String, _ seq: Int, _ extra: [String: Any]) -> [String: Any] {
        ["type": type, "seq": seq, "requestId": "request", "sessionId": "session", "scope": "full"].merging(extra) { _, new in new }
    }
    private func frames() -> [[String: Any]] {
        [record("start", 0, ["limit": NSNull()]),
         record("snapshot", 1, ["revision": 1, "offset": 0, "total": 1, "items": [item("image")], "last": true]),
         record("complete", 2, ["revision": 1, "count": 1, "scopeComplete": true])]
    }
    private func line(_ object: [String: Any]) throws -> Data { try JSONSerialization.data(withJSONObject: object) + Data([10]) }
}

private actor ListingPeer: CameraHTTPTransport {
    let contentType: String
    private var continuations = [AsyncThrowingStream<Data, Error>.Continuation]()
    private var requests = [URLRequest]()
    init(contentType: String = BridgeMediaListingDecoder.mediaType) { self.contentType = contentType }
    func send(_ request: URLRequest) async throws -> CameraHTTPResponse {
        if request.url?.path == "/health" {
            return .init(statusCode: 200, body: Data("{\"service\":\"open-eos-control-bridge\"}".utf8))
        }
        return .init(statusCode: 200, body: Data("{\"id\":\"session\",\"engine\":\"ccapi\"}".utf8))
    }
    func openStream(_ request: URLRequest) async throws -> CameraHTTPStreamResponse {
        requests.append(request)
        let pair = AsyncThrowingStream<Data, Error>.makeStream()
        continuations.append(pair.continuation)
        // Intentionally permits late data after cancel to exercise owner guards.
        return .init(statusCode: 200, headers: ["content-type": contentType], chunks: pair.stream)
    }
    func download(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse { throw CancellationError() }
    func feed(_ index: Int, _ data: Data, bytewise: Bool = false) {
        if bytewise { for byte in data { continuations[index].yield(Data([byte])) } }
        else { continuations[index].yield(data) }
    }
    func finish(_ index: Int) { continuations[index].finish() }
    func count() -> Int { requests.count }
    func listingRequests() -> [URLRequest] { requests }
}

final class BridgeListingTransportBufferTests: XCTestCase {
    func testSlowOptInReaderFailsOnOverflowWhileOrdinaryStreamRetainsAllBytes() async throws {
        for listing in [true, false] {
            let configuration = URLSessionConfiguration.ephemeral
            configuration.protocolClasses = [ListingBurstProtocol.self]
            let transport = URLSessionCameraHTTPTransport(configuration: configuration)
            let url = URL(string: "http://listing-fixture.invalid/\(UUID().uuidString)")!
            var request = URLRequest(url: url)
            request.setValue(listing ? BridgeMediaListingDecoder.mediaType : "application/octet-stream", forHTTPHeaderField: "Accept")
            let response = try await transport.openStream(request)
            defer { response.cancel() }
            if listing {
                // The source withholds EOF. Cancellation proves the bounded producer
                // rejected backlog before this test starts draining its queue.
                for _ in 0..<3_000 {
                    if ListingBurstProtocol.wasStopped(url) { break }
                    try await Task.sleep(nanoseconds: 1_000_000)
                }
                XCTAssertTrue(ListingBurstProtocol.wasStopped(url))
                do {
                    for try await _ in response.chunks {}
                    XCTFail("Dropped listing bytes were accepted as a normal EOF")
                } catch {
                    guard case DesktopBridgeError.invalidResponse = error else {
                        return XCTFail("Expected listing overflow failure: \(error)")
                    }
                }
            } else {
                var bytes = 0
                for try await chunk in response.chunks { bytes += chunk.count }
                XCTAssertEqual(bytes, 80 * 65_536, "Ordinary streams retain their existing policy")
            }
        }
    }
}

private final class ListingBurstProtocol: URLProtocol, @unchecked Sendable {
    private static let lock = NSLock()
    private static var stopped = Set<URL>()
    static func wasStopped(_ url: URL) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return stopped.contains(url)
    }
    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: "HTTP/1.1",
            headerFields: ["Content-Type": "application/octet-stream"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        for _ in 0..<80 { client?.urlProtocol(self, didLoad: Data(repeating: 32, count: 65_536)) }
        if request.value(forHTTPHeaderField: "Accept") != BridgeMediaListingDecoder.mediaType {
            client?.urlProtocolDidFinishLoading(self)
        }
    }
    override func stopLoading() {
        Self.lock.lock(); defer { Self.lock.unlock() }
        Self.stopped.insert(request.url!)
    }
}
