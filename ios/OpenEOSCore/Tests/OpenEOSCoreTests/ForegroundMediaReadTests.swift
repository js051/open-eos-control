import Foundation
import XCTest

#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

@testable import OpenEOSCore

final class ForegroundMediaReadTests: XCTestCase {
    private let root = "/ccapi/ver140/contents"
    private var itemPath: String { "\(root)/card1/100CANON/IMG_TEST.JPG" }
    // Fixed synthetic one-pixel vector from the shared JPEG contract.
    private let original = Data(base64Encoded: "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAUDBAQEAwUEBAQFBQUGBwwIBwcHBw8LCwkMEQ8SEhEPERETFhwXExQaFRERGCEYGh0dHx8fExciJCIeJBweHx7/2wBDAQUFBQcGBw4ICA4eFBEUHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh4eHh7/wAARCAABAAEDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwD4yooooA//2Q==")!

    func testOnlyExplicitStrictTransportsOnInitializedDirectCamerasAreAvailable() async throws {
        let legacy = MockCameraHTTPTransport()
        await discovery(legacy)
        let client = try CCAPIClient(baseURL: "http://192.0.2.1:8080", mode: .camera, transport: legacy)
        try await client.initialize()
        let available = await client.foregroundMediaReadsAvailable()
        XCTAssertFalse(available)
        await assertFailure(.unsupported) { try await client.foregroundMediaInventory() }
        let requests = await legacy.requests()
        XCTAssertEqual(requests.map(\.path), ["/ccapi"])

        let strict = StrictForegroundFixtureTransport()
        let uninitialized = try CCAPIClient(baseURL: "http://192.0.2.1:8080", mode: .camera, transport: strict)
        await assertFailure(.unsupported) { try await uninitialized.foregroundMediaInventory() }
        let simulator = try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: .simulator, transport: strict)
        try await simulator.initialize()
        await assertFailure(.unsupported) { try await simulator.foregroundMediaInventory() }
        let bridge = try DesktopBridgeClient(baseURL: "http://192.0.2.2:18181", transport: strict)
        await assertFailure(.unsupported) { try await bridge.foregroundMediaInventory() }
        let strictRequests = await strict.base.requests()
        XCTAssertTrue(strictRequests.isEmpty)
    }

    func testInventoryWalksEveryPageAndContainerAndPreservesRawIdentityBytes() async throws {
        let (client, transport) = try await makeClient()
        let folder = "\(root)/card1"
        let identities = ["\(folder)/caf\u{00E9}.JPG", "\(folder)/cafe\u{0301}.JPG", "\(folder)/%41.JPG"]
        await transport.base.enqueueJSON(path: "\(root)?kind=number", body: #"{"pagenumber":2}"#)
        await paths([folder, root], at: "\(root)?page=1", transport: transport)
        await paths([identities[0]], at: "\(root)?page=2", transport: transport)
        await transport.base.enqueueJSON(path: "\(folder)?kind=number", body: #"{"pagenumber":1}"#)
        await paths([identities[0], identities[1], identities[2], root], at: "\(folder)?page=1", transport: transport)

        let items = try await client.foregroundMediaInventory()

        XCTAssertEqual(items.map { Data($0.id.utf8) }, identities.map { Data($0.utf8) })
        XCTAssertTrue(items.allSatisfy { $0.sizeBytes == nil })
        let requests = await transport.base.requests()
        XCTAssertEqual(requests.map(\.path), ["/ccapi", "\(root)?kind=number", "\(root)?page=1", "\(root)?page=2",
                                               "\(folder)?kind=number", "\(folder)?page=1"])
        XCTAssertTrue(requests.allSatisfy { $0.method == "GET" })
        XCTAssertTrue(requests.dropFirst().allSatisfy { $0.timeoutInterval > 0 && $0.timeoutInterval <= 10 })
        let limits = await transport.responseLimits()
        XCTAssertEqual(limits, Array(repeating: 2 * 1024 * 1024, count: 5))
    }

    func testExactly1000IdentitiesAreCompleteBut1001NeverReturnsAPrefix() async throws {
        for count in [1_000, 1_001] {
            let (client, transport) = try await makeClient()
            await transport.base.enqueueJSON(path: "\(root)?kind=number", body: #"{"pagenumber":1}"#)
            await paths((0..<count).map { "\(root)/IMG_\($0).JPG" }, at: "\(root)?page=1", transport: transport)
            if count == 1_000 {
                let items = try await client.foregroundMediaInventory()
                XCTAssertEqual(items.count, count)
            } else {
                await assertFailure(.inventoryLimitExceeded) { try await client.foregroundMediaInventory() }
            }
        }
    }

    func testPositiveUnpagedAndZeroPageEvidenceRequireAnExplicitTypedPathArray() async throws {
        let (client, transport) = try await makeClient()
        await paths([], at: "\(root)?kind=number", transport: transport)
        let empty = try await client.foregroundMediaInventory()
        XCTAssertTrue(empty.isEmpty)
        await transport.base.enqueueJSON(path: "\(root)?kind=number", body: #"{"pagenumber":0}"#)
        await paths([itemPath], at: root, transport: transport)
        let items = try await client.foregroundMediaInventory()
        XCTAssertEqual(items.map(\.id), [itemPath])
    }

    func testMalformedCountsAndPagesFailClosedWithoutFallbackOrDroppedEntries() async throws {
        let malformedCounts = ["{}", #"{"pagenumber":null}"#, #"{"pagenumber":-1}"#,
                               #"{"pagenumber":true}"#, #"{"pagenumber":"1"}"#, #"{"pagenumber":1.5}"#,
                               #"{"pagenumber":9223372036854775808}"#]
        for body in malformedCounts {
            let (client, transport) = try await makeClient()
            await transport.base.enqueueJSON(path: "\(root)?kind=number", body: body)
            await assertFailure { try await client.foregroundMediaInventory() }
            let requests = await transport.base.requests()
            XCTAssertEqual(requests.count, 2)
        }
        for body in ["{}", #"{"path":null}"#, #"{"path":[1]}"#, #"{"path":["",null]}"#] {
            let (client, transport) = try await makeClient()
            await transport.base.enqueueJSON(path: "\(root)?kind=number", body: #"{"pagenumber":1}"#)
            await transport.base.enqueueJSON(path: "\(root)?page=1", body: body)
            await assertFailure { try await client.foregroundMediaInventory() }
        }
    }

    func testDuplicateMembersCannotEstablishCompleteInventoryOrFreshSize() async throws {
        for body in [#"{"pagenumber":2,"pagenumber":0}"#,
                     #"{"path":[null],"path":[]}"#,
                     #"{"pagenumber":0,"page\u006eumber":0}"#] {
            let (client, transport) = try await makeClient()
            await transport.base.enqueueJSON(path: "\(root)?kind=number", body: body)
            await assertFailure(.invalidInventory) { try await client.foregroundMediaInventory() }
            let requests = await transport.base.requests()
            XCTAssertEqual(requests.map(\.path), ["/ccapi", "\(root)?kind=number"])
        }
        let (client, transport) = try await makeClient()
        await transport.base.enqueueJSON(path: "\(root)?kind=number", body: #"{"pagenumber":1}"#)
        await transport.base.enqueueJSON(path: "\(root)?page=1", body: #"{"path":[null],"path":[]}"#)
        await assertFailure(.invalidInventory) { try await client.foregroundMediaInventory() }
        let requests = await transport.base.requests()
        XCTAssertEqual(requests.map(\.path), ["/ccapi", "\(root)?kind=number", "\(root)?page=1"])

        let item = CameraMediaItem(id: itemPath, name: "IMG_TEST.JPG", kind: "image", sizeBytes: 999, contentType: "image/jpeg")
        await transport.base.enqueueJSON(path: "\(itemPath)?kind=info", body: #"{"filesize":9,"filesize":4}"#)
        await assertFailure(.invalidInventory) { try await client.foregroundMediaInfo(item) }
        let metadataRequests = await transport.base.requests()
        XCTAssertEqual(metadataRequests.count, requests.count + 1)
        XCTAssertEqual(metadataRequests.last?.path, "\(itemPath)?kind=info")
        XCTAssertTrue(metadataRequests.allSatisfy { $0.method == "GET" })
    }

    func testUnsafeAndVariantPathsCannotBecomeForegroundIdentities() async throws {
        for path in ["http://untrusted.invalid\(itemPath)", "\(root)/%2e%2e/IMG.JPG",
                     "\(root)/IMG.JPG?kind=display", "\(root)/IMG.JPG#fragment", "\(root)/bad%00.JPG",
                     "/ccapi/ver140/deviceinformation", "/ccapi/ver140/contents-extra/IMG.JPG"] {
            let (client, transport) = try await makeClient()
            await paths([path], at: "\(root)?kind=number", transport: transport)
            await assertFailure { try await client.foregroundMediaInventory() }
        }
    }

    func testFailedLaterPageCannotReturnEarlierItemsAndDoesNotRetry() async throws {
        let (client, transport) = try await makeClient()
        await transport.base.enqueueJSON(path: "\(root)?kind=number", body: #"{"pagenumber":2}"#)
        await paths([itemPath], at: "\(root)?page=1", transport: transport)
        await transport.base.enqueueJSON(path: "\(root)?page=2", status: 503, body: "{}")
        await assertFailure { try await client.foregroundMediaInventory() }
        let requests = await transport.base.requests()
        XCTAssertEqual(requests.count, 4)
        XCTAssertEqual(requests.last?.path, "\(root)?page=2")
    }

    func testPageAndContainerFanoutAndJSONBodiesAreBounded() async throws {
        let (pagesClient, pagesTransport) = try await makeClient()
        await pagesTransport.base.enqueueJSON(path: "\(root)?kind=number", body: #"{"pagenumber":512}"#)
        await assertFailure(.inventoryLimitExceeded) { try await pagesClient.foregroundMediaInventory() }
        let pageRequests = await pagesTransport.base.requests()
        XCTAssertEqual(pageRequests.count, 2)

        let (foldersClient, foldersTransport) = try await makeClient()
        await paths((0..<512).map { "\(root)/folder\($0)" }, at: "\(root)?kind=number", transport: foldersTransport)
        await assertFailure(.inventoryLimitExceeded) { try await foldersClient.foregroundMediaInventory() }
        let folderRequests = await foldersTransport.base.requests()
        XCTAssertEqual(folderRequests.count, 2)

        let (largeClient, largeTransport) = try await makeClient()
        await largeTransport.base.enqueue(path: "\(root)?kind=number", body: Data(repeating: 32, count: 2 * 1024 * 1024 + 1))
        await assertFailure { try await largeClient.foregroundMediaInventory() }
    }

    func testExpiredMonotonicBudgetAndPostResponseCancellationReturnNoInventory() async throws {
        let (client, transport) = try await makeClient()
        await assertFailure(.deadlineExceeded) { try await client.foregroundMediaInventory(timeout: .zero) }
        let requests = await transport.base.requests()
        XCTAssertEqual(requests.count, 1)
        await paths([itemPath], at: "\(root)?kind=number", transport: transport)
        await transport.setCancelAfterJSON()
        let task = Task { try await client.foregroundMediaInventory() }
        switch await task.result {
        case .success: XCTFail("Cancelled inventory returned a baseline")
        case .failure(let error): XCTAssertTrue(error is CancellationError)
        }
    }

    func testOverallDeadlineIsCheckedAfterTransportReturnAndPassedToIndividualRequest() async throws {
        let (client, transport) = try await makeClient()
        await paths([], at: "\(root)?kind=number", transport: transport)
        await transport.setResponseDelay(.milliseconds(40))
        await assertFailure(.deadlineExceeded) {
            try await client.foregroundMediaInventory(timeout: .milliseconds(20))
        }
        let requests = await transport.base.requests()
        XCTAssertEqual(requests.count, 2)
        XCTAssertGreaterThan(requests[1].timeoutInterval, 0)
        XCTAssertLessThanOrEqual(requests[1].timeoutInterval, 0.02)
    }

    func testExactly512RequestsCanFinishButNo513thRequestIsIssued() async throws {
        for extraContainer in [false, true] {
            let (client, transport) = try await makeClient()
            await transport.base.enqueueJSON(path: "\(root)?kind=number", body: #"{"pagenumber":511}"#)
            for page in 1...511 {
                await paths(page == 511 && extraContainer ? ["\(root)/new-folder"] : [],
                            at: "\(root)?page=\(page)", transport: transport)
            }
            if extraContainer {
                await assertFailure(.inventoryLimitExceeded) { try await client.foregroundMediaInventory() }
            } else {
                let items = try await client.foregroundMediaInventory()
                XCTAssertTrue(items.isEmpty)
            }
            let requests = await transport.base.requests()
            XCTAssertEqual(requests.count, 513, "One discovery plus exactly 512 inventory requests")
            XCTAssertEqual(requests.last?.path, "\(root)?page=511")
        }
    }

    func testFreshSizeNeverFallsBackToListedMetadata() async throws {
        let (client, transport) = try await makeClient()
        let item = CameraMediaItem(id: itemPath, name: "IMG_TEST.JPG", kind: "image", sizeBytes: 999, contentType: "image/jpeg")
        for body in ["{}", #"{"filesize":0}"#, #"{"filesize":-1}"#, #"{"filesize":true}"#,
                     #"{"filesize":"4 bytes"}"#, #"{"filesize":"4"}"#, #"{"filesize":4.5}"#,
                     #"{"filesize":9223372036854775808}"#] {
            await transport.base.enqueueJSON(path: "\(itemPath)?kind=info", body: body)
            await assertFailure(.freshSizeUnavailable) { try await client.foregroundMediaInfo(item) }
        }
        await transport.base.enqueueJSON(path: "\(itemPath)?kind=info", body: #"{"filesize":4}"#)
        let fresh = try await client.foregroundMediaInfo(item)
        XCTAssertEqual(fresh.sizeBytes, 4)
        XCTAssertNil(fresh.contentType, "No MIME declaration in fresh info must not reuse the listing value")
        XCTAssertEqual(Data(fresh.id.utf8), Data(item.id.utf8))
        // The ordinary metadata path keeps its historical fallback behavior.
        await transport.base.enqueueJSON(path: "\(itemPath)?kind=info", body: "{}")
        let manual = try await client.mediaInfo(item)
        XCTAssertEqual(manual.sizeBytes, 999)
    }

    func testOriginalIsOneCanonicalGETWithExactFreshByteCount() async throws {
        for size in [original.count - 1, original.count, original.count + 1] {
            let (client, transport) = try await makeClient()
            let directory = try makeDirectory()
            defer { try? FileManager.default.removeItem(at: directory) }
            let destination = directory.appendingPathComponent("original.JPG")
            let item = CameraMediaItem(id: itemPath, name: "IMG_TEST.JPG", kind: "image", sizeBytes: Int64(size))
            await transport.base.enqueueDownload(path: itemPath, body: original)
            if size == original.count {
                let result = try await client.downloadForegroundOriginal(item, expectedBytes: Int64(original.count), to: destination)
                XCTAssertEqual(result.bytesTransferred, Int64(original.count))
                XCTAssertEqual(try Data(contentsOf: destination), original)
            } else {
                await assertFailure(.originalSizeMismatch) {
                    try await client.downloadForegroundOriginal(item, expectedBytes: Int64(size), to: destination)
                }
                XCTAssertFalse(FileManager.default.fileExists(atPath: destination.path))
            }
            let requests = await transport.base.requests()
            XCTAssertEqual(requests.map(\.path), ["/ccapi", itemPath])
            XCTAssertNil(requests.last?.headers["Range"])
            await assertNoTemporaryFiles(transport)
        }
    }

    func testFreshMetadataCannotHideChangedIdentityNameKindOrMIME() async throws {
        let (client, transport) = try await makeClient()
        let item = CameraMediaItem(id: itemPath, name: "IMG_TEST.JPG", kind: "image", sizeBytes: 999, contentType: "image/jpeg")
        let bodies = [
            #"{"filesize":4,"id":"other"}"#, #"{"filesize":4,"name":"OTHER.JPG"}"#,
            #"{"filesize":4,"filename":"img.jpg"}"#, #"{"filesize":4,"kind":"video"}"#,
            #"{"filesize":4,"kind":"raw"}"#, #"{"filesize":4,"kind":"IMAGE"}"#,
            #"{"filesize":4,"contenttype":"video/mp4"}"#, #"{"filesize":4,"contentType":"image/jpg"}"#,
            #"{"filesize":4,"mimetype":" "}"#, #"{"filesize":4,"mimeType":"image/"}"#,
            #"{"filesize":4,"contentType":1}"#,
            #"{"filesize":4,"contentType":"image/jpeg","contenttype":"video/mp4"}"#,
            #"{"filesize":4,"name":"IMG.JPG","filename":"OTHER.JPG"}"#,
        ]
        for body in bodies {
            await transport.base.enqueueJSON(path: "\(itemPath)?kind=info", body: body)
            do {
                _ = try await client.foregroundMediaInfo(item)
                XCTFail("Fresh contradiction was hidden: \(body)")
            } catch {
                XCTAssertEqual(error as? CameraMediaMetadataError, .invalidMetadata)
            }
        }
        for body in [#"{"filesize":4,"contenttype":" IMAGE/JPEG ; quality=90"}"#,
                     #"{"filesize":4,"contenttype":""}"#, #"{"filesize":4,"contenttype":null}"#] {
            await transport.base.enqueueJSON(path: "\(itemPath)?kind=info", body: body)
            let metadata = try await client.foregroundMediaInfo(item)
            XCTAssertTrue(CameraMediaType.admitsFreshAutomaticJPEG(original: item, metadata: metadata, fresh: true))
            XCTAssertEqual(metadata.sizeBytes, 4)
        }
        let requests = await transport.base.requests()
        XCTAssertEqual(requests.count, 1 + bodies.count + 3, "Only discovery plus one GET per explicit fresh-info call")
        XCTAssertTrue(requests.allSatisfy { $0.method == "GET" })
        XCTAssertTrue(requests.dropFirst().allSatisfy { $0.path == "\(itemPath)?kind=info" })
    }

    func testStrictOriginalEntryPointRejectsNonJPEGWithoutGETOrFile() async throws {
        let (client, transport) = try await makeClient()
        let directory = try makeDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let destination = directory.appendingPathComponent("original.JPG")
        for mime in ["image/jpg", " ", "application/octet-stream", "video/mp4"] {
            let item = CameraMediaItem(id: itemPath, name: "IMG_TEST.JPG", kind: "image", sizeBytes: 4, contentType: mime)
            do {
                _ = try await client.downloadForegroundOriginal(item, expectedBytes: 4, to: destination)
                XCTFail("Ineligible MIME reached original transfer")
            } catch {
                XCTAssertEqual(error as? CameraMediaMetadataError, .invalidMetadata)
            }
        }
        let requests = await transport.base.requests()
        XCTAssertEqual(requests.map(\.path), ["/ccapi"])
        XCTAssertFalse(FileManager.default.fileExists(atPath: destination.path))
    }

    func testOriginalHTTPTextAndPartialFailuresNeverTryVariants() async throws {
        let cases: [(Int, [String: String], Data)] = [
            (302, ["Location": "\(itemPath)?kind=main"], original), (404, [:], original),
            (206, [:], original), (200, ["Content-Range": "bytes 0-3/8"], original),
            (200, ["Content-Type": "application/json"], Data("null".utf8)), (200, [:], Data()),
        ]
        for (status, headers, body) in cases {
            let (client, transport) = try await makeClient()
            let directory = try makeDirectory()
            defer { try? FileManager.default.removeItem(at: directory) }
            let destination = directory.appendingPathComponent("original.JPG")
            let item = CameraMediaItem(id: itemPath, name: "IMG_TEST.JPG", kind: "image", sizeBytes: Int64(original.count))
            await transport.base.enqueueDownload(path: itemPath, status: status, headers: headers, body: body)
            await assertFailure { try await client.downloadForegroundOriginal(item, expectedBytes: Int64(original.count), to: destination) }
            XCTAssertFalse(FileManager.default.fileExists(atPath: destination.path))
            let requests = await transport.base.requests()
            XCTAssertEqual(requests.count, 2)
            await assertNoTemporaryFiles(transport)
        }
    }

    func testOriginalCancellationCleansTransportFileAndPreservesExistingDestination() async throws {
        let (client, transport) = try await makeClient()
        let directory = try makeDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let destination = directory.appendingPathComponent("original.JPG")
        let item = CameraMediaItem(id: itemPath, name: "IMG_TEST.JPG", kind: "image", sizeBytes: Int64(original.count))
        await transport.base.enqueueDownload(path: itemPath, body: original)
        let task = Task {
            try await client.downloadForegroundOriginal(item, expectedBytes: Int64(original.count), to: destination) { _ in
                withUnsafeCurrentTask { $0?.cancel() }
            }
        }
        switch await task.result {
        case .success: XCTFail("Cancelled original was published")
        case .failure(let error): XCTAssertTrue(error is CancellationError)
        }
        XCTAssertFalse(FileManager.default.fileExists(atPath: destination.path))
        await assertNoTemporaryFiles(transport)
        let existing = Data("existing file".utf8)
        try existing.write(to: destination)
        await assertFailure { try await client.downloadForegroundOriginal(item, expectedBytes: Int64(original.count), to: destination) }
        XCTAssertEqual(try Data(contentsOf: destination), existing)
        let requests = await transport.base.requests()
        XCTAssertEqual(requests.count, 2)
    }

    func testFailedPublicationCleansOnlyItsOwnedTemporaryFile() async throws {
        for collision in [false, true] {
            let (client, transport) = try await makeClient()
            let directory = try makeDirectory()
            defer { try? FileManager.default.removeItem(at: directory) }
            let destination = directory.appendingPathComponent(collision ? "original.JPG" : "missing/original.JPG")
            let item = CameraMediaItem(id: itemPath, name: "IMG_TEST.JPG", kind: "image", sizeBytes: Int64(original.count))
            let otherFile = Data("other owner".utf8)
            await transport.base.enqueueDownload(path: itemPath, body: original)
            await assertFailure {
                try await client.downloadForegroundOriginal(item, expectedBytes: Int64(original.count), to: destination) { _ in
                    if collision { try? otherFile.write(to: destination) }
                }
            }
            if collision { XCTAssertEqual(try Data(contentsOf: destination), otherFile) }
            else { XCTAssertFalse(FileManager.default.fileExists(atPath: destination.path)) }
            await assertNoTemporaryFiles(transport)
        }
    }

    func testResponseMIMEIsIndependentOfFreshMetadataAndCannotPublishOrRetry() async throws {
        let headers: [[String: String]] = [[:], ["Content-Type": "image/jpeg"],
            ["Content-Type": "application/octet-stream"], ["Content-Type": " IMAGE/JPEG ; note=synthetic "],
            ["Content-Type": ""], ["Content-Type": " "], ["Content-Type": "image/png"],
            ["Content-Type": "image/jpeg", "content-type": "image/jpeg"],
            ["Content-Type": "image/jpeg, image/jpeg"], ["Content-Type": "image/jpeg; broken"]]
        for (index, fields) in headers.enumerated() {
            let (client, transport) = try await makeClient()
            let directory = try makeDirectory()
            defer { try? FileManager.default.removeItem(at: directory) }
            let destination = directory.appendingPathComponent("original.JPG")
            let item = CameraMediaItem(id: itemPath, name: "IMG_TEST.JPG", kind: "image",
                                       sizeBytes: Int64(original.count), contentType: "image/jpeg")
            await transport.base.enqueueDownload(path: itemPath, headers: fields, body: original)
            if index < 4 {
                _ = try await client.downloadForegroundOriginal(item, expectedBytes: Int64(original.count), to: destination)
                XCTAssertEqual(try Data(contentsOf: destination), original)
            } else {
                await assertFailure { try await client.downloadForegroundOriginal(item, expectedBytes: Int64(original.count), to: destination) }
                XCTAssertFalse(FileManager.default.fileExists(atPath: destination.path))
            }
            let requests = await transport.base.requests()
            XCTAssertEqual(requests.map(\.path), ["/ccapi", itemPath])
            await assertNoTemporaryFiles(transport)
        }
    }

    func testOriginalEncodedLimitRejectsBeforeCameraGET() async throws {
        let (client, transport) = try await makeClient()
        let directory = try makeDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let size = Int64(CameraOriginalJPEG.maximumBytes) + 1
        let item = CameraMediaItem(id: itemPath, name: "IMG_TEST.JPG", kind: "image", sizeBytes: size)
        await assertFailure { try await client.downloadForegroundOriginal(item, expectedBytes: size,
            to: directory.appendingPathComponent("original.JPG")) }
        let requests = await transport.base.requests()
        XCTAssertEqual(requests.map(\.path), ["/ccapi"])
    }

    private func makeClient() async throws -> (CCAPIClient, StrictForegroundFixtureTransport) {
        let transport = StrictForegroundFixtureTransport()
        await discovery(transport.base)
        let client = try CCAPIClient(baseURL: "http://192.0.2.1:8080", mode: .camera, transport: transport)
        try await client.initialize()
        return (client, transport)
    }

    private func discovery(_ transport: MockCameraHTTPTransport) async {
        await transport.enqueueJSON(path: "/ccapi", body: #"{"ver140":[{"path":"/contents","get":true}]}"#)
    }

    private func paths(_ paths: [String], at path: String, transport: StrictForegroundFixtureTransport) async {
        let body = try! JSONSerialization.data(withJSONObject: ["path": paths])
        await transport.base.enqueue(path: path, body: body)
    }

    private func makeDirectory() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url
    }

    private func assertNoTemporaryFiles(_ transport: StrictForegroundFixtureTransport) async {
        for url in await transport.base.downloadedTemporaryURLs() {
            XCTAssertFalse(FileManager.default.fileExists(atPath: url.path))
        }
    }

    private func assertFailure<T>(
        _ expected: CameraForegroundMediaError? = nil,
        operation: () async throws -> T,
        file: StaticString = #filePath,
        line: UInt = #line
    ) async {
        do {
            _ = try await operation()
            XCTFail("Expected strict foreground failure", file: file, line: line)
        } catch {
            if let expected { XCTAssertEqual(error as? CameraForegroundMediaError, expected, file: file, line: line) }
        }
    }
}

/// Explicit strict opt-in: the shared legacy mock intentionally does not conform.
private actor StrictForegroundFixtureTransport: CameraForegroundHTTPTransport {
    nonisolated let base = MockCameraHTTPTransport()
    private var limits: [Int] = []
    private var cancelAfterJSON = false
    private var responseDelay: Duration = .zero
    func setCancelAfterJSON() { cancelAfterJSON = true }
    func setResponseDelay(_ delay: Duration) { responseDelay = delay }
    func responseLimits() -> [Int] { limits }
    func send(_ request: URLRequest) async throws -> CameraHTTPResponse { try await base.send(request) }
    func download(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse { try await base.download(request) }
    func sendForeground(_ request: URLRequest, maximumResponseBytes: Int) async throws -> CameraHTTPResponse {
        limits.append(maximumResponseBytes)
        let response = try await base.send(request)
        if responseDelay > .zero { try await Task.sleep(for: responseDelay) }
        if cancelAfterJSON { withUnsafeCurrentTask { $0?.cancel() } }
        return response
    }
    func downloadForeground(
        _ request: URLRequest, maximumBytes: Int64, progress: @escaping CameraMediaProgressHandler
    ) async throws -> CameraHTTPDownloadResponse {
        // Deliberately noncooperative about limits/cancellation to exercise the
        // client's independent integrity/ownership checks after transport return.
        try await base.download(request, progress: progress)
    }
}
