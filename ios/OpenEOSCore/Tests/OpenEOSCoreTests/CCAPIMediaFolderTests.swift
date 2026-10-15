import Foundation
import XCTest

@testable import OpenEOSCore

final class CCAPIMediaFolderTests: XCTestCase {
    func testSuccessfulListingKeepsCardsDistinctAndPublishesEncodedProvenanceOnEveryPage() async throws {
        let transport = MockCameraHTTPTransport()
        await enqueueDiscovery(transport)
        let root = "/ccapi/ver140/contents"
        let firstCard = "\(root)/card1/100CANON"
        let secondCard = "\(root)/card2/100CANON"
        let encodedFolder = "\(root)/card1/%E6%97%85%E8%A1%8C%20A+%252F"
        await transport.enqueueJSON(path: "\(root)?kind=number", body: #"{"pagenumber":2}"#)
        await transport.enqueue(
            path: "\(root)?page=1&order=desc",
            body: try JSONSerialization.data(withJSONObject: ["path": [
                "\(firstCard)/IMG_0001.JPG",
                "http://192.168.1.2:8080\(secondCard)/IMG_0001.JPG",
            ]])
        )
        await transport.enqueue(
            path: "\(root)?page=2&order=desc",
            body: try JSONSerialization.data(withJSONObject: ["path": [
                "\(encodedFolder)/IMG_0002.CR3?kind=main",
                "\(root)/IMG_ROOT.JPG",
                "\(root)/card1/A%2FB/IMG_0003.JPG",
                "\(root)/card1/A/B/IMG_0003.JPG",
            ]])
        )
        let client = try makeClient(transport)
        let progress = MediaListProgressRecorder()

        let items = try await client.listMedia { await progress.record($0) }

        XCTAssertEqual(items.map(\.id), [
            "\(firstCard)/IMG_0001.JPG", "\(secondCard)/IMG_0001.JPG",
            "\(encodedFolder)/IMG_0002.CR3", "\(root)/IMG_ROOT.JPG",
            "\(root)/card1/A%2FB/IMG_0003.JPG", "\(root)/card1/A/B/IMG_0003.JPG",
        ])
        XCTAssertEqual(items.map(\.folder), [
            CameraMediaFolder(id: firstCard, label: "/card1/100CANON"),
            CameraMediaFolder(id: secondCard, label: "/card2/100CANON"),
            CameraMediaFolder(id: encodedFolder, label: "/card1/旅行 A+%2F"),
            CameraMediaFolder(id: root, label: "/"),
            CameraMediaFolder(id: "\(root)/card1/A%2FB", label: "/card1/A/B"),
            CameraMediaFolder(id: "\(root)/card1/A/B", label: "/card1/A/B"),
        ])
        XCTAssertEqual(items[0].name, items[1].name)
        XCTAssertNotEqual(items[0].folder, items[1].folder)
        XCTAssertEqual(items[4].folder?.label, items[5].folder?.label)
        XCTAssertNotEqual(items[4].folder, items[5].folder)
        let snapshots = await progress.values()
        XCTAssertEqual(snapshots, [Array(items.prefix(2)), items])
        let requests = await transport.requests()
        XCTAssertEqual(requests.map(\.path), [
            "/ccapi", "\(root)?kind=number", "\(root)?page=1&order=desc", "\(root)?page=2&order=desc",
        ])
    }

    func testOnlyTheExactEncodedContentsPrefixEstablishesFolderProvenance() async throws {
        let transport = MockCameraHTTPTransport()
        await enqueueDiscovery(transport)
        let root = "/ccapi/ver140/contents"
        let paths = [
            "/ccapi/ver140/other/contents/IMG_0001.JPG",
            "/ccapi/ver140/%63ontents/card1/IMG_0002.JPG",
            "/ccapi/ver140/contents-extra/IMG_0003.JPG",
            "/ccapi/verx/contents/card1/IMG_0004.JPG",
            "/ccapi/ver140/Contents/card1/IMG_0005.JPG",
            "/ccapi/ver140%2Fcontents/card1/IMG_0006.JPG",
            "\(root)/card1/bad%00folder/IMG_0007.JPG",
            "\(root)/card1/bad%C2%9Ffolder/IMG_0008.JPG",
            "\(root)/card1/" + String(repeating: "a", count: 1_025) + "/IMG_0009.JPG",
        ]
        await transport.enqueueJSON(path: "\(root)?kind=number", body: #"{"pagenumber":1}"#)
        await transport.enqueue(
            path: "\(root)?page=1&order=desc",
            body: try JSONSerialization.data(withJSONObject: ["path": paths])
        )
        let client = try makeClient(transport)

        let items = try await client.listMedia()

        XCTAssertEqual(items.map(\.id), paths)
        XCTAssertTrue(items.allSatisfy { $0.folder == nil })
        let requests = await transport.requests()
        XCTAssertEqual(requests.count, 3)
    }

    func testInfoAndMutationReadbackPreserveOnlyPreviouslyKnownListingProvenance() async throws {
        let transport = MockCameraHTTPTransport()
        await enqueueDiscovery(transport)
        let root = "/ccapi/ver140/contents"
        let path = "\(root)/card1/100CANON/IMG.JPG"
        await transport.enqueueJSON(path: "\(root)?kind=number", body: #"{"pagenumber":1}"#)
        await transport.enqueue(path: "\(root)?page=1&order=desc", body: try JSONSerialization.data(withJSONObject: ["path": [path]]))
        await transport.enqueueJSON(path: "\(path)?kind=info", body: #"{"filesize":1234,"rating":"off"}"#)
        await transport.enqueueJSON(method: "PUT", path: path, body: "{}")
        await transport.enqueueJSON(path: "\(path)?kind=info", body: #"{"rating":"5"}"#)
        // An info response cannot promote a caller-provided path to observed listing provenance.
        await transport.enqueueJSON(
            path: "\(path)?kind=info",
            body: #"{"rating":"5","folderId":"untrusted-info-field","folderLabel":"/untrusted"}"#
        )
        let client = try makeClient(transport)

        let items = try await client.listMedia()
        let listed = try XCTUnwrap(items.first)
        let info = try await client.mediaInfo(listed)
        let rated = try await client.setMediaRating(info, rating: 5)
        let callerOnly = CameraMediaItem(id: listed.id, name: listed.name, kind: listed.kind)
        let unknownInfo = try await client.mediaInfo(callerOnly)

        XCTAssertEqual(listed.folder, CameraMediaFolder(id: "\(root)/card1/100CANON", label: "/card1/100CANON"))
        XCTAssertEqual(info.folder, listed.folder)
        XCTAssertEqual(info.sizeBytes, 1_234)
        XCTAssertEqual(rated.folder, listed.folder)
        XCTAssertEqual(rated.rating, 5)
        XCTAssertNil(unknownInfo.folder)
        let requests = await transport.requests()
        XCTAssertEqual(requests.count, 7)
        XCTAssertEqual(requests.filter { $0.method == "PUT" }.count, 1)
        XCTAssertEqual(requests.filter { $0.path.hasSuffix("?kind=info") }.count, 3)
    }

    func testSimulatorPathShapedIDsAndOptionalFolderFieldsRemainUnknown() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(
            path: "/ccapi/media",
            body: #"{"items":[{"id":"/ccapi/ver140/contents/card1/IMG.JPG","name":"IMG.JPG","kind":"image","folderId":"synthetic-folder","folderLabel":"/synthetic"}]}"#
        )
        let client = try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: .simulator, transport: transport)
        let progress = MediaListProgressRecorder()

        let items = try await client.listMedia { await progress.record($0) }

        XCTAssertEqual(items.count, 1)
        XCTAssertNil(items.first?.folder)
        let snapshots = await progress.values()
        XCTAssertEqual(snapshots, [items])
        let requests = await transport.requests()
        XCTAssertEqual(requests.map(\.path), ["/ccapi/media"])
    }

    private func enqueueDiscovery(_ transport: MockCameraHTTPTransport) async {
        await transport.enqueueJSON(path: "/ccapi", body: #"{"ver140":[{"path":"/contents","get":true,"put":true}]}"#)
    }

    private func makeClient(_ transport: MockCameraHTTPTransport) throws -> CCAPIClient {
        try CCAPIClient(baseURL: "http://192.168.1.2:8080", mode: .camera, transport: transport)
    }
}
