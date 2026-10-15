import Foundation
import XCTest

@testable import OpenEOSCore

final class OriginalMediaDownloadIntegrityTests: XCTestCase {
    private let original = Data([0xFF, 0xD8, 0xFF, 0xD9])

    func testOriginalDownloadRejectsUnexpectedPartialAndOtherSuccessfulStatuses() async throws {
        let cases: [(Int, [String: String])] = [
            (206, [:]),
            (206, ["Content-Range": "bytes 0-3/8", "Content-Length": "4"]),
            (200, ["Content-Range": "bytes 0-3/8", "Content-Length": "4"]),
            (201, [:]),
            (204, [:]),
            (205, [:]),
        ]
        for kind in DownloadClientKind.allCases {
            for (status, headers) in cases {
                let fixture = try await DownloadFixture.make(kind)
                defer { fixture.removeDirectory() }
                await fixture.transport.enqueueDownload(
                    path: fixture.paths[0], status: status, headers: headers, body: original
                )

                await assertRejected(fixture)

                // Unexpected partial/framing responses must not try another variant.
                let requests = await fixture.transport.requests()
                XCTAssertEqual(requests.last?.path, fixture.paths[0])
                XCTAssertEqual(downloadCount(fixture, requests: requests), 1)
            }
        }
    }

    func testOriginalDownloadRejectsShortOversizedMalformedAndConflictingLengths() async throws {
        for kind in DownloadClientKind.allCases {
            for length in ["3", "5", "", "-4", "+4", "4.0", "4,5", "4,", "9223372036854775808"] {
                let fixture = try await DownloadFixture.make(kind)
                defer { fixture.removeDirectory() }
                await fixture.transport.enqueueDownload(
                    path: fixture.paths[0], headers: ["Content-Length": length], body: original
                )

                await assertRejected(fixture, messageContaining: "Content-Length")

                let requests = await fixture.transport.requests()
                XCTAssertEqual(downloadCount(fixture, requests: requests), 1)
            }
        }
    }

    func testOriginalDownloadRejectsEmptySuccess() async throws {
        for kind in DownloadClientKind.allCases {
            let fixture = try await DownloadFixture.make(kind)
            defer { fixture.removeDirectory() }
            await fixture.transport.enqueueDownload(
                path: fixture.paths[0], headers: ["Content-Length": "0"], body: Data()
            )

            await assertRejected(fixture)
        }
    }

    func testOriginalDownloadUsesComparableResponseLengthAndKeepsMetadataAsAHint() async throws {
        let headerCases: [[String: String]] = [
            [:],
            ["Content-Length": "4"],
            ["Content-Length": " 004\t"],
            ["Content-Length": "4, 4", "Content-Encoding": " Identity\t"],
            ["Content-Length": "32", "Content-Encoding": "gzip"],
            ["Content-Length": "32", "Transfer-Encoding": "chunked"],
        ]
        for kind in DownloadClientKind.allCases {
            for headers in headerCases {
                // Listing metadata can be stale; the current HTTP response is the framing authority.
                let fixture = try await DownloadFixture.make(kind, listedSize: 999)
                defer { fixture.removeDirectory() }
                await fixture.transport.enqueueDownload(path: fixture.paths[0], headers: headers, body: original)
                let progress = DownloadProgressRecorder()

                let result = try await fixture.client.download(
                    fixture.item, to: fixture.destination, progress: progress.record
                )

                XCTAssertEqual(try Data(contentsOf: fixture.destination), original)
                XCTAssertEqual(result.bytesTransferred, Int64(original.count))
                XCTAssertEqual(progress.values().last, CameraMediaTransferProgress(bytesTransferred: 4, totalBytes: 4))
                await assertTemporaryFilesRemoved(fixture)
            }
        }
    }

    func testCCAPIOriginalDownloadPreservesHTTPAndTextVariantFallbacks() async throws {
        let fixture = try await DownloadFixture.make(.ccapi)
        defer { fixture.removeDirectory() }
        await fixture.transport.enqueueDownload(
            path: fixture.paths[0], status: 404, body: Data("missing variant".utf8)
        )
        await fixture.transport.enqueueDownload(
            path: fixture.paths[1], headers: ["Content-Type": "application/json"], body: Data("{}".utf8)
        )
        await fixture.transport.enqueueDownload(
            path: fixture.paths[2], headers: ["Content-Length": "4"], body: original
        )

        let result = try await fixture.client.download(fixture.item, to: fixture.destination)

        XCTAssertEqual(try Data(contentsOf: result.fileURL), original)
        let requests = await fixture.transport.requests()
        XCTAssertEqual(requests.filter { fixture.paths.contains($0.path) }.map(\.path), fixture.paths)
        await assertTemporaryFilesRemoved(fixture)
    }

    func testOriginalDownloadCancellationAfterTransportReturnsDoesNotPublishOrRetry() async throws {
        let responses: [(Int, [String: String], Data)] = [
            (200, [:], original),
            (200, ["Content-Type": "application/json"], Data("{}".utf8)),
            (404, [:], Data("missing variant".utf8)),
        ]
        for kind in DownloadClientKind.allCases {
            for (status, headers, body) in responses {
                let fixture = try await DownloadFixture.make(kind)
                defer { fixture.removeDirectory() }
                await fixture.transport.enqueueDownload(
                    path: fixture.paths[0], status: status, headers: headers, body: body
                )

                // The mock's default progress callback runs after writing the temporary file,
                // then returns it even when the task is cancelled, like a noncooperative transport.
                let task = Task {
                    try await fixture.client.download(fixture.item, to: fixture.destination) { _ in
                        withUnsafeCurrentTask { $0?.cancel() }
                    }
                }
                switch await task.result {
                case .success: XCTFail("Cancelled download was published")
                case .failure(let error): XCTAssertTrue(error is CancellationError, "\(error)")
                }

                XCTAssertFalse(FileManager.default.fileExists(atPath: fixture.destination.path))
                let requests = await fixture.transport.requests()
                XCTAssertEqual(downloadCount(fixture, requests: requests), 1)
                await assertTemporaryFilesRemoved(fixture)
            }
        }
    }

    func testOriginalDownloadPreservesExistingDestinationWithoutRequestingBytes() async throws {
        for kind in DownloadClientKind.allCases {
            let fixture = try await DownloadFixture.make(kind)
            defer { fixture.removeDirectory() }
            let existing = Data("existing user file".utf8)
            try existing.write(to: fixture.destination)

            do {
                _ = try await fixture.client.download(fixture.item, to: fixture.destination)
                XCTFail("Expected destination-exists failure")
            } catch {
                switch error {
                case CCAPIError.destinationExists, DesktopBridgeError.destinationExists: break
                default: XCTFail("Unexpected error: \(error)")
                }
            }

            XCTAssertEqual(try Data(contentsOf: fixture.destination), existing)
            let requests = await fixture.transport.requests()
            XCTAssertEqual(downloadCount(fixture, requests: requests), 0)
        }
    }

    func testOriginalDownloadCleansTemporaryFileWhenPublicationFails() async throws {
        for kind in DownloadClientKind.allCases {
            let fixture = try await DownloadFixture.make(kind)
            defer { fixture.removeDirectory() }
            await fixture.transport.enqueueDownload(path: fixture.paths[0], body: original)
            let unavailableDestination = fixture.directory.appendingPathComponent("missing-parent/original.JPG")

            do {
                _ = try await fixture.client.download(fixture.item, to: unavailableDestination)
                XCTFail("Expected file move failure")
            } catch {
                XCTAssertFalse(error is CancellationError)
            }

            XCTAssertFalse(FileManager.default.fileExists(atPath: unavailableDestination.path))
            await assertTemporaryFilesRemoved(fixture)
        }
    }

    func testOriginalDownloadPreservesDestinationCreatedWhileTransportWasRunning() async throws {
        for kind in DownloadClientKind.allCases {
            let fixture = try await DownloadFixture.make(kind)
            defer { fixture.removeDirectory() }
            await fixture.transport.enqueueDownload(path: fixture.paths[0], body: original)
            let existing = Data("newly created user file".utf8)

            do {
                _ = try await fixture.client.download(fixture.item, to: fixture.destination) { _ in
                    try? existing.write(to: fixture.destination)
                }
                XCTFail("Expected the existing destination to prevent publication")
            } catch {
                XCTAssertFalse(error is CancellationError)
            }

            XCTAssertEqual(try Data(contentsOf: fixture.destination), existing)
            await assertTemporaryFilesRemoved(fixture)
        }
    }

    private func assertRejected(_ fixture: DownloadFixture, messageContaining: String? = nil) async {
        do {
            _ = try await fixture.client.download(fixture.item, to: fixture.destination)
            XCTFail("Invalid original download was published")
        } catch {
            switch error {
            case CCAPIError.invalidResponse, DesktopBridgeError.invalidResponse: break
            default: XCTFail("Unexpected error: \(error)")
            }
            if let messageContaining { XCTAssertTrue(error.localizedDescription.contains(messageContaining)) }
        }
        XCTAssertFalse(FileManager.default.fileExists(atPath: fixture.destination.path))
        await assertTemporaryFilesRemoved(fixture)
    }

    private func assertTemporaryFilesRemoved(_ fixture: DownloadFixture) async {
        let temporaryFiles = await fixture.transport.downloadedTemporaryURLs()
        XCTAssertFalse(temporaryFiles.isEmpty)
        for url in temporaryFiles {
            XCTAssertFalse(FileManager.default.fileExists(atPath: url.path), "Temporary download was retained")
            try? FileManager.default.removeItem(at: url)
        }
    }

    private func downloadCount(_ fixture: DownloadFixture, requests: [RecordedRequest]) -> Int {
        requests.filter { fixture.paths.contains($0.path) }.count
    }
}

private enum DownloadClientKind: CaseIterable {
    case ccapi, bridge
}

private enum DownloadClient: Sendable {
    case ccapi(CCAPIClient)
    case bridge(DesktopBridgeClient)

    func download(
        _ item: CameraMediaItem,
        to destination: URL,
        progress: @escaping CameraMediaProgressHandler = { _ in }
    ) async throws -> CameraMediaDownload {
        switch self {
        case .ccapi(let client): return try await client.downloadMedia(item, to: destination, progress: progress)
        case .bridge(let client): return try await client.downloadMedia(item, to: destination, progress: progress)
        }
    }
}

private struct DownloadFixture: Sendable {
    let client: DownloadClient
    let transport: MockCameraHTTPTransport
    let item: CameraMediaItem
    let paths: [String]
    let directory: URL
    var destination: URL { directory.appendingPathComponent("original.JPG") }

    static func make(_ kind: DownloadClientKind, listedSize: Int64? = nil) async throws -> DownloadFixture {
        let transport = MockCameraHTTPTransport()
        let client: DownloadClient
        let id: String
        let paths: [String]
        switch kind {
        case .ccapi:
            await transport.enqueueJSON(
                path: "/ccapi", body: #"{"ver100":[{"path":"/contents","get":true}]}"#
            )
            let ccapi = try CCAPIClient(baseURL: "http://192.0.2.1:8080", mode: .camera, transport: transport)
            try await ccapi.initialize()
            client = .ccapi(ccapi)
            id = "/ccapi/ver100/contents/card1/100CANON/IMG_TEST.JPG"
            paths = [id, "\(id)?kind=main", "\(id)?type=main"]
        case .bridge:
            await transport.enqueueJSON(
                path: "/health", body: #"{"ok":true,"service":"open-eos-control-bridge","version":"0.1.0"}"#
            )
            await transport.enqueueJSON(
                method: "POST", path: "/v1/session", status: 201,
                body: #"{"id":"download_test","engine":"libgphoto2"}"#
            )
            let bridge = try DesktopBridgeClient(baseURL: "http://192.0.2.2:18181", transport: transport)
            try await bridge.initialize()
            client = .bridge(bridge)
            id = "test-original"
            paths = ["/v1/session/download_test/media/\(id)"]
        }
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return DownloadFixture(
            client: client, transport: transport,
            item: CameraMediaItem(id: id, name: "IMG_TEST.JPG", kind: "image", sizeBytes: listedSize),
            paths: paths, directory: directory
        )
    }

    func removeDirectory() {
        try? FileManager.default.removeItem(at: directory)
    }
}
