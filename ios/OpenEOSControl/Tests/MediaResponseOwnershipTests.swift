import Foundation
import OpenEOSCore
import XCTest

@testable import OpenEOSControl

/// Every held response deliberately ignores cancellation. Assertions follow the
/// exact owner task's completion, rather than treating a delay as evidence.
@MainActor
final class MediaResponseOwnershipTests: XCTestCase {
    func testRetiredInfoAndEveryMetadataResponseCannotOwnReplacementSession() async throws {
        for bridge in [false, true] {
            for operation in OwnershipOperation.metadataAndInfo {
                for fails in [false, true] {
                    try await assertRetiredOperation(operation, bridge: bridge, fails: fails)
                }
            }
        }
    }

    func testRetiredDeletePreservesSameIDViewerDownloadAndReplacementMediaOwner() async throws {
        for bridge in [false, true] {
            for fails in [false, true] {
                try await assertRetiredOperation(.delete, bridge: bridge, fails: fails)
            }
        }
    }

    func testCurrentOwnerInfoAndAllMetadataActionsStillPublishReadback() async throws {
        for bridge in [false, true] {
            let peer = MediaOwnershipPeer()
            let state = makeState([peer], bridge: bridge)
            defer { state.requestDisconnect(); Task { await peer.releaseAll() } }
            try await connectAndLoad(state)
            let item = try XCTUnwrap(state.latestMediaItem)
            await state.openMediaPreview(item)
            for operation in OwnershipOperation.metadataAndInfo {
                await perform(operation, state: state, item: item)
                let updated = await peer.currentItem()
                XCTAssertEqual(state.mediaItems, [updated])
                XCTAssertEqual(state.latestMediaItem, updated)
                XCTAssertEqual(state.mediaPreviewItem, updated)
                XCTAssertNil(state.lastError)
                XCTAssertFalse(state.isBusy(.media))
            }
            let mutations = await peer.mutationOperations()
            XCTAssertEqual(mutations, [.protection, .rating, .rotation, .archive])
        }
    }

    func testRetiredUploadResponseCannotReconcileOrFinishReplacementUpload() async throws {
        for failure in [nil, URLError.Code.networkConnectionLost, .cancelled] {
            let oldPeer = MediaOwnershipPeer()
            let newPeer = MediaOwnershipPeer(rating: 2)
            let state = makeState([oldPeer, newPeer])
            let file = try uploadFile("OLD-UPLOAD.JPG")
            let replacementFile = try uploadFile("NEW-UPLOAD.JPG")
            defer {
                state.requestDisconnect()
                Task { await oldPeer.releaseAll(); await newPeer.releaseAll() }
                removeUpload(file); removeUpload(replacementFile)
            }
            try await connectAndLoad(state)
            await oldPeer.enqueue(.upload, gate: "old-upload", error: failure)
            XCTAssertTrue(state.startMediaUpload(file))
            let old = try XCTUnwrap(state.mediaUploadTask)
            try await waitForGate("old-upload", peer: oldPeer)
            let oldListings = await oldPeer.count(.listing)
            await state.disconnect()
            try await connectAndLoad(state)
            let preserved = try await prepareViewerAndWarning(state, peer: newPeer)
            await newPeer.enqueue(.upload, gate: "new-upload")
            XCTAssertTrue(state.startMediaUpload(replacementFile))
            let replacement = try XCTUnwrap(state.mediaUploadTask)
            try await waitForGate("new-upload", peer: newPeer)
            try await waitUntil { state.mediaUploadProgress?.bytesTransferred == 1 }

            await oldPeer.release("old-upload")
            await old.value

            assertPreserved(preserved, state: state)
            XCTAssertEqual(state.activeMediaUploadName, replacementFile.lastPathComponent)
            XCTAssertEqual(state.mediaUploadProgress?.bytesTransferred, 1)
            XCTAssertNil(state.uploadedMediaName)
            XCTAssertNil(state.mediaUploadError)
            XCTAssertFalse(replacement.isCancelled)
            let after = await oldPeer.count(.listing)
            XCTAssertEqual(after, oldListings, "A retired session must never begin cancellation reconciliation")
            let uploads = await oldPeer.count(.upload)
            XCTAssertEqual(uploads, 1)
            await newPeer.release("new-upload")
            await replacement.value
            XCTAssertEqual(state.uploadedMediaName, replacementFile.lastPathComponent)
        }
    }

    func testRetiredPostUploadAndCancellationListingsCannotPublishIntoReplacement() async throws {
        for cancelled in [false, true] {
            for fails in [false, true] {
                let oldPeer = MediaOwnershipPeer()
                let newPeer = MediaOwnershipPeer(rating: 2)
                let state = makeState([oldPeer, newPeer])
                let file = try uploadFile("OLD-LIST.JPG")
                let replacementFile = try uploadFile("NEW-LIST.JPG")
                defer {
                    state.requestDisconnect()
                    Task { await oldPeer.releaseAll(); await newPeer.releaseAll() }
                    removeUpload(file); removeUpload(replacementFile)
                }
                try await connectAndLoad(state)
                await oldPeer.enqueue(.upload, gate: "upload")
                await oldPeer.enqueue(.listing, gate: "old-list", error: fails ? .networkConnectionLost : nil)
                XCTAssertTrue(state.startMediaUpload(file))
                let old = try XCTUnwrap(state.mediaUploadTask)
                try await waitForGate("upload", peer: oldPeer)
                if cancelled { state.cancelMediaUpload() }
                await oldPeer.release("upload")
                try await waitForGate("old-list", peer: oldPeer)
                await state.disconnect()
                try await connectAndLoad(state)
                let preserved = try await prepareViewerAndWarning(state, peer: newPeer)
                await newPeer.enqueue(.upload, gate: "new-upload")
                XCTAssertTrue(state.startMediaUpload(replacementFile))
                let replacement = try XCTUnwrap(state.mediaUploadTask)
                try await waitForGate("new-upload", peer: newPeer)

                await oldPeer.release("old-list")
                await old.value

                assertPreserved(preserved, state: state)
                XCTAssertEqual(state.activeMediaUploadName, replacementFile.lastPathComponent)
                XCTAssertNil(state.uploadedMediaName)
                XCTAssertNil(state.mediaUploadError)
                XCTAssertFalse(replacement.isCancelled)
                let oldUploads = await oldPeer.count(.upload)
                XCTAssertEqual(oldUploads, 1, "Reconciliation must never retransmit the file")
                await newPeer.release("new-upload")
                await replacement.value
                XCTAssertEqual(state.uploadedMediaName, replacementFile.lastPathComponent)
            }
        }
    }

    func testSameOwnerCancellationReconcilesOriginalSessionWithoutRetransmission() async throws {
        for failure in [nil, URLError.Code.cancelled] {
            let peer = MediaOwnershipPeer()
            let state = makeState([peer])
            let file = try uploadFile("CANCELLED-ACK.JPG")
            defer { state.requestDisconnect(); Task { await peer.releaseAll() }; removeUpload(file) }
            try await connectAndLoad(state)
            let before = await peer.count(.listing)
            await peer.enqueue(.upload, gate: "upload", error: failure)
            XCTAssertTrue(state.startMediaUpload(file))
            let upload = try XCTUnwrap(state.mediaUploadTask)
            try await waitForGate("upload", peer: peer)
            state.cancelMediaUpload()
            await peer.release("upload")
            await upload.value

            XCTAssertEqual(state.uploadedMediaName, file.lastPathComponent)
            XCTAssertTrue(state.mediaItems.contains { $0.name == file.lastPathComponent })
            XCTAssertEqual(state.mediaLibraryLoadStatus, .complete)
            XCTAssertNil(state.mediaUploadError)
            XCTAssertNil(state.lastError)
            XCTAssertNil(state.mediaUploadTask)
            XCTAssertFalse(state.isBusy(.media))
            let uploads = await peer.count(.upload)
            let listings = await peer.count(.listing)
            XCTAssertEqual(uploads, 1)
            XCTAssertEqual(listings - before, 1, "Exactly one read reconciles the original Bridge session")
        }
    }

    func testUploadCancelledOrRetiredBeforeEntrySendsNeitherPostNorReconciliation() async throws {
        for retire in [false, true] {
            let peer = MediaOwnershipPeer()
            let state = makeState([peer])
            let file = try uploadFile("QUEUED.JPG")
            defer { state.requestDisconnect(); Task { await peer.releaseAll() }; removeUpload(file) }
            try await connectAndLoad(state)
            let before = await peer.count(.listing)
            XCTAssertTrue(state.startMediaUpload(file))
            let queued = try XCTUnwrap(state.mediaUploadTask)
            // No suspension: invalidate the MainActor task before its first entry.
            if retire { state.requestDisconnect() }
            else { state.cancelMediaUpload() }
            await queued.value
            let uploads = await peer.count(.upload)
            let listings = await peer.count(.listing)
            XCTAssertEqual(uploads, 0)
            XCTAssertEqual(listings, before)
            XCTAssertNil(state.uploadedMediaName)
            XCTAssertNil(state.mediaUploadError)
            XCTAssertNil(state.mediaUploadTask)
            XCTAssertNil(state.activeMediaUploadName)
            XCTAssertFalse(state.isBusy(.media))
        }
    }

    func testObsoleteUploadListingCannotPublishAfterScopeChangeOrRoundTrip() async throws {
        for cancelled in [false, true] {
            for roundTrip in [false, true] {
                for fails in [false, true] {
                    let peer = MediaOwnershipPeer()
                    let state = makeState([peer])
                    let file = try uploadFile("SCOPE.JPG")
                    defer { state.requestDisconnect(); Task { await peer.releaseAll() }; removeUpload(file) }
                    try await connectAndLoad(state)
                    let originalItems = state.mediaItems
                    await peer.enqueue(.upload, gate: "upload")
                    await peer.enqueue(.listing, gate: "old-scope", error: fails ? .networkConnectionLost : nil)
                    XCTAssertTrue(state.startMediaUpload(file))
                    let upload = try XCTUnwrap(state.mediaUploadTask)
                    try await waitForGate("upload", peer: peer)
                    if cancelled { state.cancelMediaUpload() }
                    await peer.release("upload")
                    try await waitForGate("old-scope", peer: peer)
                    state.setMediaLibraryScope(.all)
                    if roundTrip { state.setMediaLibraryScope(.recent) }
                    XCTAssertEqual(state.mediaLibraryLoadStatus, .notLoaded)
                    await peer.release("old-scope")
                    await upload.value

                    XCTAssertEqual(state.mediaLibraryScope, roundTrip ? .recent : .all)
                    XCTAssertEqual(state.mediaItems, originalItems)
                    XCTAssertEqual(state.mediaLibraryLoadStatus, .notLoaded)
                    XCTAssertFalse(state.mediaLibraryHasMore)
                    XCTAssertNil(state.mediaUploadError)
                    XCTAssertNil(state.lastError)
                    XCTAssertFalse(state.isBusy(.media))
                    if !fails || !cancelled {
                        XCTAssertEqual(state.uploadedMediaName, file.lastPathComponent)
                    }
                    state.startMediaLibraryLoad()
                    try await waitUntil { state.mediaLibraryLoadStatus == .complete }
                    XCTAssertTrue(state.mediaItems.contains { $0.name == file.lastPathComponent })
                    let limits = await peer.listLimits()
                    XCTAssertEqual(limits.suffix(2).map { $0 ?? "all" }, ["61", roundTrip ? "61" : "all"])
                    let uploads = await peer.count(.upload)
                    XCTAssertEqual(uploads, 1)
                }
            }
        }
    }

    private func assertRetiredOperation(_ operation: OwnershipOperation, bridge: Bool, fails: Bool) async throws {
        let oldPeer = MediaOwnershipPeer()
        let newPeer = MediaOwnershipPeer(rating: 2)
        let state = makeState([oldPeer, newPeer], bridge: bridge)
        defer { state.requestDisconnect(); Task { await oldPeer.releaseAll(); await newPeer.releaseAll() } }
        try await connectAndLoad(state)
        let oldItem = try XCTUnwrap(state.latestMediaItem)
        await oldPeer.enqueue(operation, gate: "retired", error: fails ? .networkConnectionLost : nil)
        let old = Task { await perform(operation, state: state, item: oldItem) }
        try await waitForGate("retired", peer: oldPeer)
        await state.disconnect()
        try await connectAndLoad(state)
        let preserved = try await prepareViewerAndWarning(state, peer: newPeer)
        XCTAssertEqual(preserved.item.id, oldItem.id, "The fixture must exercise an ID collision")
        XCTAssertNotEqual(preserved.item, oldItem)
        await newPeer.enqueue(.info, gate: "replacement")
        let replacement = Task { await state.loadMediaInfo(preserved.item) }
        try await waitForGate("replacement", peer: newPeer)
        await oldPeer.release("retired")
        await old.value

        assertPreserved(preserved, state: state)
        XCTAssertNil(state.deletedMediaName)
        let replacementHeld = await newPeer.waiting("replacement")
        XCTAssertTrue(replacementHeld)
        let mutations = await oldPeer.mutationOperations()
        XCTAssertEqual(mutations, operation == .info ? [] : [operation])
        await newPeer.release("replacement")
        await replacement.value
        XCTAssertFalse(state.isBusy(.media))
        XCTAssertNil(state.lastError)
    }

    private struct PreservedMedia {
        let item: CameraMediaItem
        let items: [CameraMediaItem]
        let file: URL
        let warning: String
        let snapshot: CameraSnapshot?
    }

    private func prepareViewerAndWarning(_ state: CameraAppState, peer: MediaOwnershipPeer) async throws -> PreservedMedia {
        let item = try XCTUnwrap(state.latestMediaItem)
        await state.openMediaPreview(item)
        state.startMediaDownload(item)
        let download = try XCTUnwrap(state.mediaDownloadTask)
        await download.value
        let file = try XCTUnwrap(state.downloadedFile(for: item))
        await peer.enqueue(.info, error: .cannotFindHost)
        await state.loadMediaInfo(item)
        return PreservedMedia(item: item, items: state.mediaItems, file: file,
            warning: try XCTUnwrap(state.lastError), snapshot: state.snapshot)
    }

    private func assertPreserved(_ saved: PreservedMedia, state: CameraAppState,
                                 file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(state.mediaItems, saved.items, file: file, line: line)
        XCTAssertEqual(state.latestMediaItem, saved.item, file: file, line: line)
        XCTAssertEqual(state.mediaPreviewItem, saved.item, file: file, line: line)
        XCTAssertEqual(state.mediaPreviewData, MediaOwnershipPeer.imageBytes, file: file, line: line)
        XCTAssertEqual(state.downloadedFile(for: saved.item), saved.file, file: file, line: line)
        XCTAssertTrue(FileManager.default.fileExists(atPath: saved.file.path), file: file, line: line)
        XCTAssertEqual(state.lastError, saved.warning, file: file, line: line)
        XCTAssertEqual(state.snapshot, saved.snapshot, file: file, line: line)
        XCTAssertEqual(state.mediaLibraryLoadStatus, .complete, file: file, line: line)
        XCTAssertTrue(state.isBusy(.media), file: file, line: line)
    }

    private func perform(_ operation: OwnershipOperation, state: CameraAppState, item: CameraMediaItem) async {
        switch operation {
        case .info: await state.loadMediaInfo(item)
        case .protection: await state.setMediaProtection(item, enabled: true)
        case .rating: await state.setMediaRating(item, rating: 5)
        case .rotation: await state.setMediaRotation(item, degrees: 270)
        case .archive: await state.setMediaArchive(item, enabled: true)
        case .delete: await state.deleteMedia(item)
        default: XCTFail("Unexpected app operation")
        }
    }

    private func makeState(_ peers: [MediaOwnershipPeer], bridge: Bool = true) -> CameraAppState {
        var remaining = peers
        let defaults = UserDefaults(suiteName: "MediaResponseOwnershipTests.\(UUID().uuidString)")!
        let state = CameraAppState(defaults: defaults, sessionFactory: {
            let peer = remaining.removeFirst()
            return bridge
                ? .desktopBridge(try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", transport: peer))
                : .ccapi(try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: .simulator, transport: peer))
        })
        state.autoRefresh = false
        return state
    }

    private func connectAndLoad(_ state: CameraAppState) async throws {
        await state.connect()
        XCTAssertTrue(state.connected)
        await state.latestMediaTask?.value
        _ = try XCTUnwrap(state.latestMediaItem)
        state.startMediaLibraryLoad()
        try await waitUntil { state.mediaLibraryLoadStatus == .complete }
    }

    private func waitForGate(_ gate: String, peer: MediaOwnershipPeer) async throws {
        try await waitUntil { await peer.waiting(gate) }
    }

    private func waitUntil(_ condition: @MainActor () async -> Bool) async throws {
        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: .seconds(5))
        repeat {
            if await condition() { return }
            await Task.yield()
        } while clock.now < deadline
        XCTFail("Expected fixture gate or production state did not arrive")
        throw URLError(.timedOut)
    }

    private func uploadFile(_ name: String) throws -> URL {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let file = directory.appendingPathComponent(name)
        try MediaOwnershipPeer.imageBytes.write(to: file)
        return file
    }

    private func removeUpload(_ file: URL) { try? FileManager.default.removeItem(at: file.deletingLastPathComponent()) }
}

private enum OwnershipOperation: String, Sendable {
    case info, protection, rating, rotation, archive, delete, listing, upload, preview, thumbnail, download
    static let metadataAndInfo: [Self] = [.info, .protection, .rating, .rotation, .archive]
}

/// An in-memory peer for both direct CCAPI simulator and Bridge wire contracts.
/// Only synthetic files are changed. Events are cancelled immediately so no
/// unrelated polling or live-view work can race the controlled media requests.
private actor MediaOwnershipPeer: CameraHTTPTransport {
    static let imageBytes = Data([0xFF, 0xD8, 0xFF, 0xE0, 3, 4, 5, 0xFF, 0xD9])
    private struct Plan {
        let gate: String?
        let error: URLError.Code?
    }
    private var plans: [OwnershipOperation: [Plan]] = [:]
    private var gates: [String: CheckedContinuation<Void, Never>] = [:]
    private var operations: [OwnershipOperation] = []
    private var limits: [String?] = []
    private var item: CameraMediaItem
    private var uploaded: [CameraMediaItem] = []

    init(rating: Int = 0) {
        item = CameraMediaItem(id: "collision", name: "COLLISION.JPG", kind: "image",
            sizeBytes: Int64(Self.imageBytes.count), previewAvailable: true,
            protected: false, rating: rating, rotationDegrees: 0, archived: false,
            contentType: "image/jpeg")
    }

    func enqueue(_ operation: OwnershipOperation, gate: String? = nil, error: URLError.Code? = nil) {
        plans[operation, default: []].append(Plan(gate: gate, error: error))
    }
    func currentItem() -> CameraMediaItem { item }
    func count(_ operation: OwnershipOperation) -> Int { operations.filter { $0 == operation }.count }
    func listLimits() -> [String?] { limits }
    func mutationOperations() -> [OwnershipOperation] {
        operations.filter { [.protection, .rating, .rotation, .archive, .delete, .upload].contains($0) }
    }
    func waiting(_ gate: String) -> Bool { gates[gate] != nil }
    func release(_ gate: String) { gates.removeValue(forKey: gate)?.resume() }
    func releaseAll() {
        let pending = gates
        gates.removeAll()
        for continuation in pending.values { continuation.resume() }
    }
    private func take(_ operation: OwnershipOperation) -> Plan {
        operations.append(operation)
        return plans[operation]?.isEmpty == false
            ? plans[operation]!.removeFirst() : Plan(gate: nil, error: nil)
    }
    private func finish(_ plan: Plan) async throws {
        if let gate = plan.gate { await withCheckedContinuation { gates[gate] = $0 } }
        if let error = plan.error { throw URLError(error) }
    }

    func send(_ request: URLRequest) async throws -> CameraHTTPResponse {
        let path = request.url!.path
        let method = request.httpMethod ?? "GET"
        let query = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?.queryItems ?? []
        let kind = query.first { $0.name == "kind" }?.value
        let simulator = path.hasPrefix("/ccapi")
        var operation: OwnershipOperation?
        var object: [String: Any] = [:]
        if path.hasSuffix("/events") { throw CancellationError() }
        if path == "/health" {
            object = ["ok": true, "service": "open-eos-control-bridge", "version": "0.13.0"]
        } else if path == "/v1/session" {
            object = ["id": "synthetic-owner", "engine": "ccapi"]
        } else if path.hasSuffix("/media") {
            operation = .listing
            limits.append(query.first { $0.name == "limit" }?.value)
            object = ["items": ([item] + uploaded).map { mediaJSON($0, simulator: simulator) }]
        } else if path.contains("/media/") {
            if method == "DELETE" { operation = .delete }
            else if method == "PUT" {
                let payload = try JSONSerialization.jsonObject(with: request.httpBody!) as! [String: Any]
                let action = simulator ? payload["action"] as! String : path.components(separatedBy: "/").last!
                switch action {
                case "protect", "protection": operation = .protection
                case "rating": operation = .rating
                case "rotate", "rotation": operation = .rotation
                case "archive": operation = .archive
                default: throw URLError(.unsupportedURL)
                }
                item = CameraMediaItem(id: item.id, name: item.name, kind: item.kind,
                    sizeBytes: item.sizeBytes, previewAvailable: true,
                    protected: operation == .protection ? true : item.protected,
                    rating: operation == .rating ? 5 : item.rating,
                    rotationDegrees: operation == .rotation ? 270 : item.rotationDegrees,
                    archived: operation == .archive ? true : item.archived, contentType: item.contentType)
                object = simulator ? [:] : mediaJSON(item, simulator: false)
            } else if kind == "thumbnail" || path.hasSuffix("/thumbnail") { operation = .thumbnail }
            else if kind == "display" || path.hasSuffix("/preview") { operation = .preview }
            else {
                operation = .info
                object = simulator
                    ? ["filesize": item.sizeBytes!, "protect": item.protected == true ? "enable" : "disable",
                       "rating": item.rating == 0 ? "off" : String(item.rating!), "rotate": String(item.rotationDegrees!),
                       "archive": item.archived == true ? "enable" : "disable", "contenttype": "image/jpeg"]
                    : mediaJSON(item, simulator: false)
            }
        } else if path.hasSuffix("/info") {
            object = ["connected": true, "model": "Synthetic media owner", "serial": "SYNTHETIC-OWNER", "api": "fixture"]
        } else if path.hasSuffix("/capabilities") {
            object = simulator ? [:] : ["supported": ["MEDIA_BROWSER", "MEDIA_THUMBNAIL", "MEDIA_PREVIEW",
                "MEDIA_DOWNLOAD", "MEDIA_UPLOAD", "MEDIA_DELETE", "MEDIA_PROTECT", "MEDIA_RATING", "MEDIA_ROTATE", "MEDIA_ARCHIVE"]]
        } else if path.hasSuffix("/status") {
            object = ["connected": true, "recording": false, "mode": "Manual",
                "battery": [String: Any](), "media": [String: Any](), "exposure": [String: Any]()]
        }
        if let operation {
            let plan = take(operation)
            try await finish(plan)
            if operation == .thumbnail || operation == .preview {
                return CameraHTTPResponse(statusCode: 200, headers: ["content-type": "image/jpeg"], body: Self.imageBytes)
            }
        }
        return CameraHTTPResponse(statusCode: 200, body: try JSONSerialization.data(withJSONObject: object))
    }

    func download(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse {
        let plan = take(.download)
        try await finish(plan)
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try Self.imageBytes.write(to: file)
        return CameraHTTPDownloadResponse(statusCode: 200, headers: ["content-type": "image/jpeg"], temporaryFileURL: file)
    }

    func upload(_ request: URLRequest, from fileURL: URL,
                progress: @escaping CameraMediaProgressHandler) async throws -> CameraHTTPUploadResponse {
        let plan = take(.upload)
        let result = CameraMediaItem(id: "uploaded-\(fileURL.lastPathComponent)", name: fileURL.lastPathComponent,
            kind: "image", sizeBytes: Int64(Self.imageBytes.count), previewAvailable: true)
        uploaded.append(result) // The peer may accept bytes even when its reply is cancelled/lost.
        progress(CameraMediaTransferProgress(bytesTransferred: 1, totalBytes: result.sizeBytes))
        try await finish(plan)
        progress(CameraMediaTransferProgress(bytesTransferred: result.sizeBytes!, totalBytes: result.sizeBytes))
        return CameraHTTPUploadResponse(statusCode: 201,
            body: try JSONSerialization.data(withJSONObject: mediaJSON(result, simulator: false)))
    }

    private func mediaJSON(_ item: CameraMediaItem, simulator: Bool) -> [String: Any] {
        var json: [String: Any] = ["id": item.id, "name": item.name, "kind": item.kind,
            "previewAvailable": item.previewAvailable]
        json[simulator ? "size_bytes" : "sizeBytes"] = item.sizeBytes
        json[simulator ? "protect" : "protected"] = item.protected
        json["rating"] = item.rating
        json[simulator ? "rotate" : "rotationDegrees"] = item.rotationDegrees
        json[simulator ? "archive" : "archived"] = item.archived
        json[simulator ? "content_type" : "contentType"] = item.contentType
        return json
    }
}
