import Foundation
import XCTest

@testable import OpenEOSCore

final class DesktopBridgeMediaFolderTests: XCTestCase {
    func testListingPreservesOpaquePairsAndLeavesLegacyOrMalformedPairsBrowsable() async throws {
        let transport = MockCameraHTTPTransport()
        let client = try await makeClient(transport)
        let folderID = " synthetic:gphoto:card%2Fone+ "
        let folderLabel = " /card 1/旅行+ "
        let invalidPairs: [[String: Any]] = [
            [:],
            ["folderId": "id"],
            ["folderLabel": "/folder"],
            ["folderId": 7, "folderLabel": "/folder"],
            ["folderId": "id", "folderLabel": true],
            ["folderId": NSNull(), "folderLabel": "/folder"],
            ["folderId": "id", "folderLabel": NSNull()],
            ["folderId": " ", "folderLabel": "/folder"],
            ["folderId": "id", "folderLabel": "\u{3000}"],
            ["folderId": String(repeating: "📷", count: 2_048) + "a", "folderLabel": "/folder"],
            ["folderId": "id", "folderLabel": String(repeating: "📷", count: 512) + "a"],
            ["folderId": "id\u{0}", "folderLabel": "/folder"],
            ["folderId": "id", "folderLabel": "/folder\u{1F}"],
            ["folderId": "id\u{7F}", "folderLabel": "/folder"],
            ["folderId": "id", "folderLabel": "/folder\u{9F}"],
        ]
        var payloads: [[String: Any]] = [
            ["id": "card-one", "name": "IMG.JPG", "kind": "image", "folderId": folderID, "folderLabel": folderLabel],
            ["id": "card-two", "name": "IMG.JPG", "kind": "image", "folderId": "synthetic:card-two", "folderLabel": folderLabel],
            ["id": "maximum-length", "name": "MAX.JPG", "kind": "image", "folderId": String(repeating: "📷", count: 2_048), "folderLabel": String(repeating: "📷", count: 512)],
        ]
        for (index, pair) in invalidPairs.enumerated() {
            var payload = pair
            payload["id"] = "unknown-\(index)"
            payload["name"] = "IMG_\(index).JPG"
            payload["kind"] = "image"
            payloads.append(payload)
        }
        // A path-shaped legacy ID is not evidence of a folder on the Bridge transport.
        payloads.append(["id": "/ccapi/ver140/contents/card1/IMG.JPG", "name": "IMG.JPG", "kind": "image"])
        await transport.enqueue(path: "/v1/session/session_folders/media", body: try JSONSerialization.data(withJSONObject: ["items": payloads]))
        let progress = MediaListProgressRecorder()

        let items = try await client.listMedia { await progress.record($0) }

        XCTAssertEqual(items.count, payloads.count)
        XCTAssertEqual(items.map(\.id), payloads.compactMap { $0["id"] as? String })
        XCTAssertEqual(items[0].folder?.id, folderID)
        XCTAssertEqual(items[0].folder?.label, folderLabel)
        XCTAssertEqual(items[1].folder?.label, folderLabel)
        XCTAssertNotEqual(items[0].folder, items[1].folder)
        XCTAssertEqual(items[2].folder?.id.utf16.count, 4_096)
        XCTAssertEqual(items[2].folder?.label.utf16.count, 1_024)
        XCTAssertTrue(items.dropFirst(3).allSatisfy { $0.folder == nil })
        let snapshots = await progress.values()
        XCTAssertEqual(snapshots, [items])
        let requests = await transport.requests()
        XCTAssertEqual(requests.map(\.path), ["/health", "/v1/session", "/v1/session/session_folders/media"])
    }

    func testInfoAndMutationUseFreshPairedProvenanceAndDoNotCarryMissingFieldsForward() async throws {
        let transport = MockCameraHTTPTransport()
        let client = try await makeClient(transport)
        await transport.enqueueJSON(
            path: "/v1/session/session_folders/media",
            body: #"{"items":[{"id":"image","name":"IMG.JPG","folderId":"listed","folderLabel":"/listed"}]}"#
        )
        await transport.enqueueJSON(
            path: "/v1/session/session_folders/media/image/info",
            body: #"{"id":"image","name":"IMG.JPG","folderId":"info","folderLabel":"/info"}"#
        )
        await transport.enqueueJSON(
            method: "PUT", path: "/v1/session/session_folders/media/image/rating",
            body: #"{"id":"image","name":"IMG.JPG","rating":5,"folderId":"mutated","folderLabel":"/mutated"}"#
        )
        await transport.enqueueJSON(
            path: "/v1/session/session_folders/media/image/info",
            body: #"{"id":"image","name":"IMG.JPG"}"#
        )
        await transport.enqueueJSON(
            method: "PUT", path: "/v1/session/session_folders/media/image/rating",
            body: #"{"id":"image","name":"IMG.JPG","rating":0,"folderId":"incomplete"}"#
        )
        await transport.enqueueJSON(
            method: "PUT", path: "/v1/session/session_folders/media/image/archive",
            body: #"{"id":"image","name":"IMG.JPG","archived":true}"#
        )

        let items = try await client.listMedia()
        let listed = try XCTUnwrap(items.first)
        let info = try await client.mediaInfo(listed)
        let rated = try await client.setMediaRating(info, rating: 5)
        let unknownInfo = try await client.mediaInfo(rated)
        let unknownMutation = try await client.setMediaRating(rated, rating: 0)
        let legacyMutation = try await client.setMediaArchive(rated, enabled: true)

        XCTAssertEqual(listed.folder?.id, "listed")
        XCTAssertEqual(info.folder, CameraMediaFolder(id: "info", label: "/info"))
        XCTAssertEqual(rated.folder, CameraMediaFolder(id: "mutated", label: "/mutated"))
        XCTAssertNil(unknownInfo.folder)
        XCTAssertNil(unknownMutation.folder)
        XCTAssertNil(legacyMutation.folder)
        let requests = await transport.requests()
        XCTAssertEqual(requests.count, 8)
        XCTAssertEqual(requests.filter { $0.method == "PUT" }.count, 3)
    }

    func testWrongItemInfoAndMutationAreRejectedBeforeAdoptingTheirFolder() async throws {
        let transport = MockCameraHTTPTransport()
        let client = try await makeClient(transport)
        let wrongItem = #"{"id":"other-image","name":"IMG.JPG","rating":5,"folderId":"other-folder","folderLabel":"/other"}"#
        await transport.enqueueJSON(path: "/v1/session/session_folders/media/image/info", body: wrongItem)
        await transport.enqueueJSON(method: "PUT", path: "/v1/session/session_folders/media/image/rating", body: wrongItem)
        let item = CameraMediaItem(id: "image", name: "IMG.JPG", kind: "image", folder: CameraMediaFolder(id: "original", label: "/original"))

        do {
            _ = try await client.mediaInfo(item)
            XCTFail("Expected mismatched media information to be rejected")
        } catch let error as DesktopBridgeError {
            guard case .invalidResponse = error else { return XCTFail("Unexpected error: \(error)") }
        }
        do {
            _ = try await client.setMediaRating(item, rating: 5)
            XCTFail("Expected mismatched mutation response to be rejected")
        } catch let error as DesktopBridgeError {
            guard case .invalidResponse = error else { return XCTFail("Unexpected error: \(error)") }
        }
        let requests = await transport.requests()
        XCTAssertEqual(requests.count, 4)
    }

    func testCanonicallyEquivalentMediaIDsAreRejectedInInfoAndMutationResponses() async throws {
        let transport = MockCameraHTTPTransport()
        let client = try await makeClient(transport)
        let requestedID = "caf\u{E9}"
        let returnedID = "cafe\u{301}"
        let body = try JSONSerialization.data(withJSONObject: [
            "id": returnedID, "name": "IMG.JPG", "rating": 5,
            "folderId": "other-folder", "folderLabel": "/other",
        ])
        await transport.enqueue(path: "/v1/session/session_folders/media/\(requestedID)/info", body: body)
        await transport.enqueue(method: "PUT", path: "/v1/session/session_folders/media/\(requestedID)/rating", body: body)
        let item = CameraMediaItem(id: requestedID, name: "IMG.JPG", kind: "image")

        do {
            _ = try await client.mediaInfo(item)
            XCTFail("Canonically equivalent IDs with different bytes must not match")
        } catch let error as DesktopBridgeError {
            guard case .invalidResponse = error else { return XCTFail("Unexpected error: \(error)") }
        }
        do {
            _ = try await client.setMediaRating(item, rating: 5)
            XCTFail("Canonically equivalent IDs with different bytes must not match")
        } catch let error as DesktopBridgeError {
            guard case .invalidResponse = error else { return XCTFail("Unexpected error: \(error)") }
        }
        let requests = await transport.requests()
        XCTAssertEqual(requests.count, 4)
    }

    private func makeClient(_ transport: MockCameraHTTPTransport) async throws -> DesktopBridgeClient {
        await transport.enqueueJSON(
            path: "/health",
            body: #"{"ok":true,"service":"open-eos-control-bridge","version":"0.1.0","authRequired":false,"loopbackOnly":false,"engines":{}}"#
        )
        await transport.enqueueJSON(
            method: "POST", path: "/v1/session", status: 201,
            body: #"{"id":"session_folders","engine":"libgphoto2","camera":{"id":"gphoto2:synthetic","model":"Synthetic camera","port":"usb:001,007","engine":"libgphoto2"}}"#
        )
        let client = try DesktopBridgeClient(baseURL: "http://192.168.1.10:18181", cameraID: "gphoto2:synthetic", transport: transport)
        try await client.initialize()
        return client
    }
}
