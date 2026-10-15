import CryptoKit
import Foundation
import XCTest
@testable import OpenEOSCore
@testable import OpenEOSControl

/// Production policy + clients + App executor; synthetic peer only. Native
/// execution requires Apple tools and is NOT_RUN on the Linux authoring host.
@MainActor final class MediaDeleteJourneyTests: XCTestCase {
    private func cases(_ group: String) throws -> [[String: Any]] {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "delete-outcome-vectors", withExtension: "json"))
        let data = try Data(contentsOf: url)
        XCTAssertEqual(SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined(), "f42c67bc2b80185ac2198d00a59a9188b4424a4e6fa21a0f9df69f26fef15a68")
        let object = try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        return try XCTUnwrap(object[group] as? [[String: Any]])
    }
    func testAll528SharedPolicyVectorsUseProductionDecisions() throws {
        let counts = ["eligibility": 216, "admission": 24, "outcome": 32, "httpReceipt": 192, "selection": 54, "roster": 10]
        for (group, count) in counts {
            let rows = try cases(group)
            XCTAssertEqual(rows.count, count)
            for row in rows {
                let id = row["id"] as! String, input = row["input"] as! [String: Any], expected = row["expected"] as! [String: Any]
                switch group {
                case "eligibility":
                    let result = CameraMediaDeletePolicy.eligibility(source: input["source"] as! String, loaded: input["loaded"] as! Bool,
                        capability: input["capability"] as? Bool, protected: input["protected"] as? Bool, writable: input["writable"] as? Bool)
                    XCTAssertEqual(result.allowed, expected["allowed"] as! Bool, id)
                    XCTAssertEqual(result.reason, expected["reason"] as! String, id)
                    XCTAssertEqual(result.qualificationUnknown, expected["qualificationUnknown"] as! Bool, id)
                case "admission":
                    XCTAssertEqual(CameraMediaDeletePolicy.admits(openingOwner: input["openingOwner"] as! String,
                        currentOwner: input["currentOwner"] as? String, confirmed: input["confirmed"] as! Bool,
                        consumed: input["consumed"] as! Bool, busy: input["busy"] as! Bool), expected["allowed"] as! Bool, id)
                case "outcome":
                    XCTAssertEqual(CameraMediaDeletePolicy.outcome(started: input["started"] as! Bool, dispatched: input["dispatched"] as! Bool,
                        confirmedReceipt: input["confirmedReceipt"] as! Bool, rejectedReceipt: input["rejectedReceipt"] as! Bool,
                        skippedReason: input["skippedReason"] as? String).rawValue, expected["status"] as! String, id)
                case "httpReceipt":
                    XCTAssertEqual(CameraMediaDeletePolicy.httpOutcome(status: input["status"] as! Int, responseComplete: input["responseComplete"] as! Bool,
                        framingValid: input["framingValid"] as! Bool, bodyBytes: input["bodyBytes"] as! Int).rawValue, expected["status"] as! String, id)
                case "selection":
                    let consume = CameraMediaDeletePolicy.consumesSelection(operationOwner: input["operationOwner"] as! String,
                        currentOwner: input["currentOwner"] as? String, operationToken: input["operationSelectionToken"] as? String,
                        currentToken: input["currentSelectionToken"] as? String)
                    let confirmed = Set((input["confirmedIds"] as! [String]).map { Data($0.utf8) })
                    let selected = (input["selectedIds"] as! [String]).filter { !consume || !confirmed.contains(Data($0.utf8)) }
                    XCTAssertEqual(selected.map { Data($0.utf8) }, (expected["selectedIds"] as! [String]).map { Data($0.utf8) }, id)
                default:
                    let result = CameraMediaDeletePolicy.roster(input["selectedIds"] as! [String])
                    XCTAssertEqual(result.allowed, expected["allowed"] as! Bool, id)
                    XCTAssertEqual(result.reason, expected["reason"] as! String, id)
                    XCTAssertEqual(result.ids.map { Data($0.utf8) }, (expected["ids"] as! [String]).map { Data($0.utf8) }, id)
                }
            }
        }
    }
    func testAll12BatchVectorsThroughActualAppAndBothHTTPClients() async throws {
        let vectors = try cases("batch")
        XCTAssertEqual(vectors.count, 12)
        for bridge in [false, true] {
            for vector in vectors {
                let input = vector["input"] as! [String: Any], expected = vector["expected"] as! [String: Any]
                let responses = input["responses"] as! [String], eligible = input["eligible"] as! [Bool]
                let items = eligible.enumerated().map { media("gphoto2:row-\($0.offset)", protected: !$0.element) }
                let peer = DeleteFixturePeer(items: items, responses: responses)
                let box = DeleteStateBox()
                let state = makeState(peer, bridge: bridge, beforeItem: { index in
                    if input["cancelBeforeIndex"] as? Int == index { box.state?.cancelMediaDelete() }
                }, beforeDispatch: { index, attempt in if responses[index] == "not-sent" { attempt.cancel() } })
                box.state = state
                defer { state.requestDisconnect() }
                try await connect(state)
                state.selectMediaItems(state.mediaItems)
                let draft = try XCTUnwrap(state.makeMediaDeleteDraft(displayed: [], owner: state.mediaLibrarySessionID))
                XCTAssertFalse(state.startMediaDelete(draft, confirmed: false))
                XCTAssertTrue(state.startMediaDelete(draft, confirmed: true))
                XCTAssertFalse(state.startMediaDelete(draft, confirmed: true), "A repeated callback cannot dispatch twice")
                await state.mediaDeleteTask?.value
                let run = try XCTUnwrap(state.mediaDeleteRuns.last)
                XCTAssertEqual(run.rows.map { $0.status.rawValue }, expected["statuses"] as! [String], vector["id"] as! String)
                let writes = await peer.writes()
                XCTAssertEqual(items.map { item in writes.filter { Data($0.utf8) == Data(item.id.utf8) }.count }, expected["dispatches"] as! [Int])
                XCTAssertFalse(run.running)
                let confirmed = Set(run.rows.filter { $0.status == .confirmed }.map { Data($0.item.id.utf8) })
                XCTAssertEqual(state.selectedMediaItems.map { Data($0.id.utf8) }, items.filter { !confirmed.contains(Data($0.id.utf8)) }.map { Data($0.id.utf8) })
            }
        }
    }
    func testFrozenSourceRosterCancelAndReplacementSelectionTokens() async throws {
        let a = media("gphoto2:é"), b = media("gphoto2:e\u{301}"), host = media("gphoto2-host:U1lOVEhFVElDLkpQRw==")
        let box = DeleteStateBox()
        let peer = DeleteFixturePeer(items: [a, b, host], responses: ["confirmed", "rejected", "uncertain"], onWrite: { id in
            if id.utf8.elementsEqual(a.id.utf8), let state = box.state {
                state.toggleMediaSelection(a); state.toggleMediaSelection(a) // Same ID, newly explicit selection.
            }
        })
        let state = makeState(peer); box.state = state
        defer { state.requestDisconnect() }
        try await connect(state)
        state.selectMediaItems(state.mediaItems)
        let draft = try XCTUnwrap(state.makeMediaDeleteDraft(displayed: [b], owner: state.mediaLibrarySessionID))
        XCTAssertEqual(draft.hiddenCount, 2)
        XCTAssertEqual(draft.rows.map(\.source), [.cameraCard, .cameraCard, .desktopHost])
        XCTAssertTrue(state.startMediaDelete(draft, confirmed: true))
        await state.mediaDeleteTask?.value
        XCTAssertTrue(state.isMediaSelected(a), "Successful old attempt cannot erase a new selection token")
        XCTAssertTrue(state.isMediaSelected(b)); XCTAssertTrue(state.isMediaSelected(host))
        let run = try XCTUnwrap(state.mediaDeleteRuns.last)
        state.inspectMediaDelete(run.id)
        try await wait { state.mediaLibraryLoadStatus == .complete }
        XCTAssertEqual(state.mediaDeleteRuns.last?.rows.map(\.status), [.confirmed, .rejected, .uncertain], "List presence/absence cannot rewrite receipts")
        await state.disconnect()
        XCTAssertTrue(state.mediaDeleteRuns.last?.ownerRetired == true)
        XCTAssertFalse(state.canInspectMediaDelete(run))
        try await connect(state)
        let before = await peer.writes()
        XCTAssertFalse(state.startMediaDelete(draft, confirmed: true))
        state.inspectMediaDelete(run.id)
        let after = await peer.writes()
        XCTAssertEqual(before, after)
    }
    func testUnknownSourceProtectedAndUnloadedNeverDispatchOrUnprotect() async throws {
        let items = [media("opaque"), media("gphoto2:protected", protected: true), media("gphoto2:readonly", writable: false), media("gphoto2:gone")]
        let peer = DeleteFixturePeer(items: items)
        let state = makeState(peer)
        defer { state.requestDisconnect() }
        try await connect(state)
        state.selectMediaItems(state.mediaItems)
        await peer.replaceItems(Array(items.prefix(3)))
        state.startMediaLibraryLoad()
        try await wait { state.mediaLibraryLoadStatus == .complete }
        let draft = try XCTUnwrap(state.makeMediaDeleteDraft(displayed: [], owner: state.mediaLibrarySessionID))
        XCTAssertEqual(draft.unloadedCount, 1)
        XCTAssertTrue(state.startMediaDelete(draft, confirmed: true))
        await state.mediaDeleteTask?.value
        XCTAssertEqual(state.mediaDeleteRuns.last?.rows.map(\.status), Array(repeating: .skipped, count: 4))
        let writes = await peer.writes(); XCTAssertTrue(writes.isEmpty)
    }
    func testCancelledInflightConfirmedResponseWinsButNoRemainingDispatchAndOldOwnerLateReplyIsolated() async throws {
        for retire in [false, true] {
            let box = DeleteStateBox()
            let peer = DeleteFixturePeer(items: [media("gphoto2:a"), media("gphoto2:b")], onWrite: { _ in
                if retire { box.state?.requestDisconnect() } else { box.state?.cancelMediaDelete() }
            })
            let state = makeState(peer); box.state = state
            defer { state.requestDisconnect() }
            try await connect(state)
            state.selectMediaItems(state.mediaItems)
            let draft = try XCTUnwrap(state.makeMediaDeleteDraft(displayed: [], owner: state.mediaLibrarySessionID))
            XCTAssertTrue(state.startMediaDelete(draft, confirmed: true))
            let task = state.mediaDeleteTask
            await task?.value
            XCTAssertEqual(state.mediaDeleteRuns.last?.rows.map(\.status), [.confirmed, .notStarted])
            let writes = await peer.writes(); XCTAssertEqual(writes, ["gphoto2:a"])
            XCTAssertEqual(state.mediaDeleteRuns.last?.ownerRetired, retire)
        }
    }
    func testRetiredReceiptBeforeResponseAndAfterClientRecordCannotMutateReplacementOwner() async throws {
        for bridge in [false, true] {
            for pauseAfterClientRecord in [false, true] {
                for response in ["confirmed", "rejected", "uncertain"] {
                    for historyDisposition in (response == "confirmed" ? ["keep", "clear", "evict"] : ["keep"]) {
                        let a = media("gphoto2:a"), b = media("gphoto2:b")
                        let oldGate = DeleteFixtureGate(), replacementGate = DeleteFixtureGate()
                        let oldPeer = DeleteFixturePeer(items: [a, b], responses: [response], onWrite: { _ in
                            if !pauseAfterClientRecord { await oldGate.pause() }
                        })
                        let replacementPeer = DeleteFixturePeer(items: [a, b], responses: ["uncertain"], onWrite: { _ in
                            await replacementGate.pause()
                        })
                        var connections = 0, pausedAfterRecord = false
                        let state = CameraAppState(defaults: UserDefaults(suiteName: "RetiredDelete.\(UUID())")!, sessionFactory: {
                            let peer = connections == 0 ? oldPeer : replacementPeer
                            connections += 1
                            return bridge ? .desktopBridge(try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", transport: peer))
                                : .ccapi(try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: .simulator, transport: peer))
                        }, mediaDeleteAfterResponse: {
                            if pauseAfterClientRecord && !pausedAfterRecord {
                                pausedAfterRecord = true
                                await oldGate.pause()
                            }
                        })
                        state.autoRefresh = false
                        defer { state.requestDisconnect() }
                        try await connect(state)
                        state.selectMediaItems(state.mediaItems)
                        let oldDraft = try XCTUnwrap(state.makeMediaDeleteDraft(displayed: [], owner: state.mediaLibrarySessionID))
                        XCTAssertTrue(state.startMediaDelete(oldDraft, confirmed: true))
                        let oldTask = state.mediaDeleteTask
                        try await waitForGate(oldGate)
                        await state.disconnect()
                        XCTAssertTrue(state.mediaDeleteRuns.first?.ownerRetired == true)
                        if pauseAfterClientRecord && response == "confirmed" {
                            XCTAssertEqual(state.mediaDeleteRuns.first?.rows.first?.status, .confirmed, "Retirement preserves an already recorded ACK")
                        }
                        if historyDisposition == "clear" { XCTAssertTrue(state.clearMediaDeleteHistory(oldDraft.id, confirmed: true)) }
                        try await connect(state)
                        state.selectMediaItems(state.mediaItems)
                        if historyDisposition == "evict" {
                            for _ in 0..<20 {
                                let skipped = try XCTUnwrap(state.makeMediaDeleteDraft(items: [media("unloaded")], displayed: [], owner: state.mediaLibrarySessionID))
                                XCTAssertTrue(state.startMediaDelete(skipped, confirmed: true))
                                await state.mediaDeleteTask?.value
                            }
                        }
                        let replacementOwner = state.mediaLibrarySessionID
                        let replacementDraft = try XCTUnwrap(state.makeMediaDeleteDraft(displayed: [], owner: replacementOwner))
                        let blockedDraft = try XCTUnwrap(state.makeMediaDeleteDraft(displayed: [], owner: replacementOwner))
                        XCTAssertTrue(state.startMediaDelete(replacementDraft, confirmed: true))
                        let replacementTask = state.mediaDeleteTask
                        try await waitForGate(replacementGate)
                        let currentIDs = state.mediaItems.map { Data($0.id.utf8) }
                        let selectedIDs = state.selectedMediaItems.map { Data($0.id.utf8) }
                        let currentBusy = state.busyOperations
                        let retainedRunIDs = state.mediaDeleteRuns.map(\.id)
                        XCTAssertFalse(state.startMediaDelete(blockedDraft, confirmed: true))
                        XCTAssertEqual(state.mediaDeleteRuns.map(\.id), retainedRunIDs, "Busy admission cannot evict or shift an active batch")
                        await oldGate.release()
                        await oldTask?.value
                        XCTAssertEqual(state.mediaLibrarySessionID, replacementOwner)
                        XCTAssertEqual(state.mediaItems.map { Data($0.id.utf8) }, currentIDs)
                        XCTAssertEqual(state.selectedMediaItems.map { Data($0.id.utf8) }, selectedIDs)
                        XCTAssertEqual(state.busyOperations, currentBusy)
                        XCTAssertTrue(state.isBusy(.media), "Old completion must not release the replacement operation lock")
                        XCTAssertNil(state.deletedMediaName)
                        XCTAssertTrue(state.mediaDeleteRuns.last?.running == true)
                        XCTAssertTrue(state.mediaDeleteRuns.last?.rows.first?.applying == true)
                        XCTAssertFalse(state.mediaDeleteRuns.last?.rows.first?.receiptSettled == true)
                        if historyDisposition != "keep" {
                            XCTAssertFalse(state.mediaDeleteRuns.contains { $0.id == oldDraft.id }, "Late receipt cannot resurrect cleared history or evicted history")
                        } else {
                            let oldRun = try XCTUnwrap(state.mediaDeleteRuns.first { $0.id == oldDraft.id })
                            let outcome: CameraMediaDeleteOutcome = response == "confirmed" ? .confirmed : response == "rejected" ? .rejected : .uncertain
                            XCTAssertEqual(oldRun.rows.map(\.status), [outcome, .notStarted])
                            XCTAssertTrue(oldRun.rows[0].receiptSettled)
                            XCTAssertTrue(oldRun.ownerRetired)
                            XCTAssertFalse(oldRun.running)
                        }
                        let oldWrites = await oldPeer.writes(), newWrites = await replacementPeer.writes()
                        XCTAssertEqual(oldWrites, [a.id], "Retired owner cannot dispatch remaining B")
                        XCTAssertEqual(newWrites, [a.id], "Old evidence cannot dispatch in the replacement session")
                        await replacementGate.release()
                        await replacementTask?.value
                    }
                }
            }
        }
    }

    private func waitForGate(_ gate: DeleteFixtureGate) async throws {
        let clock = ContinuousClock(), deadline = ContinuousClock().now.advanced(by: .seconds(5))
        while !(await gate.hasEntered()), clock.now < deadline { await Task.yield() }
        let entered = await gate.hasEntered()
        XCTAssertTrue(entered)
    }

    func testHistoryEvictsWholeOldestBatchAndClearOrEvictionInvalidatesOldReview() async throws {
        let peer = DeleteFixturePeer(items: [media("opaque")])
        let state = makeState(peer)
        defer { state.requestDisconnect() }
        try await connect(state)
        state.selectMediaItems(state.mediaItems)
        var drafts: [MediaDeleteDraft] = []
        for _ in 0..<21 {
            let draft = try XCTUnwrap(state.makeMediaDeleteDraft(displayed: [], owner: state.mediaLibrarySessionID))
            drafts.append(draft)
            XCTAssertTrue(state.startMediaDelete(draft, confirmed: true)); await state.mediaDeleteTask?.value
        }
        XCTAssertEqual(state.mediaDeleteRuns.count, 20)
        XCTAssertEqual(state.mediaDeleteRuns.map(\.id), Array(drafts.suffix(20)).map(\.id))
        XCTAssertFalse(state.startMediaDelete(drafts[0], confirmed: true), "Evicted confirmation cannot be replayed")
        XCTAssertEqual(state.mediaDeleteRejection, .staleReview)
        let stale = try XCTUnwrap(state.makeMediaDeleteDraft(displayed: [], owner: state.mediaLibrarySessionID))
        let id = try XCTUnwrap(state.mediaDeleteRuns.first?.id)
        XCTAssertFalse(state.clearMediaDeleteHistory(id, confirmed: false))
        XCTAssertTrue(state.clearMediaDeleteHistory(id, confirmed: true))
        XCTAssertFalse(state.startMediaDelete(stale, confirmed: true))
        XCTAssertEqual(state.mediaDeleteRejection, .staleReview)
        let writes = await peer.writes(); XCTAssertTrue(writes.isEmpty)
    }
    func testHistoryRowLimitEvictsWholeThousandRowBatchWithoutTruncating() async throws {
        let items = (0..<1000).map { media("opaque-\($0)") }
        let peer = DeleteFixturePeer(items: items)
        let state = makeState(peer)
        defer { state.requestDisconnect() }
        try await connect(state)
        var drafts: [MediaDeleteDraft] = []
        for count in [1000, 1000, 1] {
            let draft = try XCTUnwrap(state.makeMediaDeleteDraft(items: Array(items.prefix(count)), displayed: [], owner: state.mediaLibrarySessionID))
            drafts.append(draft)
            XCTAssertTrue(state.startMediaDelete(draft, confirmed: true)); await state.mediaDeleteTask?.value
        }
        XCTAssertEqual(state.mediaDeleteRuns.map(\.id), Array(drafts.suffix(2)).map(\.id))
        XCTAssertEqual(state.mediaDeleteRuns.map { $0.rows.count }, [1000, 1])
        XCTAssertEqual(state.mediaDeleteRuns.reduce(0) { $0 + $1.rows.count }, 1001)
        let writes = await peer.writes(); XCTAssertTrue(writes.isEmpty)
    }
    private func media(_ id: String, protected: Bool? = false, writable: Bool? = nil) -> CameraMediaItem {
        CameraMediaItem(id: id, name: "SYNTHETIC.JPG", kind: "image", protected: protected, writable: writable)
    }
    private func makeState(_ peer: DeleteFixturePeer, bridge: Bool = true,
                           beforeItem: (@MainActor (Int) -> Void)? = nil,
                           beforeDispatch: (@MainActor (Int, CameraMediaDeleteAttempt) -> Void)? = nil) -> CameraAppState {
        let state = CameraAppState(defaults: UserDefaults(suiteName: "DeleteFixtures.\(UUID())")!, sessionFactory: {
            bridge ? .desktopBridge(try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", transport: peer))
                : .ccapi(try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: .simulator, transport: peer))
        }, mediaDeleteBeforeItem: beforeItem, mediaDeleteBeforeDispatch: beforeDispatch)
        state.autoRefresh = false
        return state
    }
    private func connect(_ state: CameraAppState) async throws {
        await state.connect(); XCTAssertTrue(state.connected)
        await state.latestMediaTask?.value
        state.startMediaLibraryLoad()
        try await wait { state.mediaLibraryLoadStatus == .complete }
    }
    private func wait(_ condition: () -> Bool) async throws {
        let clock = ContinuousClock(), deadline = ContinuousClock().now.advanced(by: .seconds(5))
        while !condition(), clock.now < deadline { await Task.yield() }
        XCTAssertTrue(condition())
    }
}
@MainActor private final class DeleteStateBox { weak var state: CameraAppState? }

private actor DeleteFixturePeer: CameraHTTPTransport {
    private var items: [CameraMediaItem]
    private let originalIDs: [Data]
    private let responses: [String]
    private let onWrite: (@MainActor @Sendable (String) async -> Void)?
    private var sent: [String] = []
    init(items: [CameraMediaItem], responses: [String] = [], onWrite: (@MainActor @Sendable (String) async -> Void)? = nil) {
        self.items = items; originalIDs = items.map { Data($0.id.utf8) }; self.responses = responses; self.onWrite = onWrite
    }
    func writes() -> [String] { sent }
    func replaceItems(_ value: [CameraMediaItem]) { items = value }
    func download(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse { throw URLError(.unsupportedURL) }
    func send(_ request: URLRequest) async throws -> CameraHTTPResponse {
        let url = request.url!, path = request.url!.path, method = request.httpMethod ?? "GET"
        if path == "/health" { return json(["ok": true, "service": "open-eos-control-bridge", "version": "synthetic"]) }
        if path == "/v1/session" { return json(["id": "synthetic-delete", "engine": "libgphoto2"], status: 201) }
        if path.hasSuffix("/events") { throw CancellationError() }
        if method == "DELETE", path.contains("/media/") {
            let marker = url.pathComponents.firstIndex(of: "media")!
            let id = url.pathComponents[marker + 1]
            sent.append(id)
            await onWrite?(id)
            let index = originalIDs.firstIndex(of: Data(id.utf8))!
            let result = responses.indices.contains(index) ? responses[index] : "confirmed"
            if result == "uncertain" { throw URLError(.networkConnectionLost) }
            if result == "rejected" { return json(["error": "protected"], status: 403) }
            items.removeAll { Data($0.id.utf8) == Data(id.utf8) }
            return CameraHTTPResponse(statusCode: 204)
        }
        if method == "DELETE" { return CameraHTTPResponse(statusCode: 204) }
        if path.hasSuffix("/media") {
            return json(["items": items.map { item -> [String: Any] in
                var row: [String: Any] = ["id": item.id, "name": item.name, "kind": item.kind]
                row[path.hasPrefix("/ccapi") ? "protect" : "protected"] = item.protected
                row["writable"] = item.writable
                return row
            }])
        }
        if path.hasSuffix("/capabilities") { return json(path.hasPrefix("/ccapi") ? [:] : ["supported": ["MEDIA_BROWSER", "MEDIA_DELETE"]]) }
        if path.hasSuffix("/info") { return json(["connected": true, "model": "Synthetic delete", "serial": "SYNTHETIC", "api": "fixture"]) }
        if path.hasSuffix("/status") { return json(["connected": true, "recording": false, "mode": "Manual", "battery": [:], "media": [:], "exposure": [:]]) }
        return json([:])
    }
    private func json(_ value: [String: Any], status: Int = 200) -> CameraHTTPResponse {
        CameraHTTPResponse(statusCode: status, body: try! JSONSerialization.data(withJSONObject: value))
    }
}

/// Deliberately ignores task cancellation to deliver an already-dispatched peer
/// response after owner retirement. It never performs network or file I/O.
private actor DeleteFixtureGate {
    private var entered = false
    private var released = false
    private var continuation: CheckedContinuation<Void, Never>?
    func hasEntered() -> Bool { entered }
    func pause() async {
        entered = true
        if released { return }
        await withCheckedContinuation { continuation = $0 }
    }
    func release() {
        released = true
        continuation?.resume()
        continuation = nil
    }
}
