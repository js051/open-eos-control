import CryptoKit
import Foundation
import OpenEOSCore
import XCTest
@testable import OpenEOSControl

/// Uses production clients and CameraAppState, never a translated Swift algorithm.
/// Source inspection is not native evidence: Xcode/Swift execution remains NOT_RUN.
@MainActor
final class MediaMutationContractTests: XCTestCase {
    private func vectors(_ name: String) throws -> [[String: Any]] {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "media-mutation-vectors", withExtension: "json"))
        let data = try Data(contentsOf: url)
        XCTAssertEqual(SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined(),
                       "2d30173f8abb0538f1aa320cfce24e2fb0e01f86ef6fc54db93a052ed2fdd401")
        let object = try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        return try XCTUnwrap(object[name] as? [[String: Any]])
    }

    func testSingleMetadataWritablePolicyKeepsUnknownAllowedAndVetoesOnlyMatchingField() {
        let targets: [CameraMediaMutation] = [.rating(3), .protection(true), .rotation(90), .archive(true)]
        for global in [nil, true, false] as [Bool?] {
            for field in [nil, true, false] as [Bool?] {
                for vetoIndex in targets.indices {
                    let item = CameraMediaItem(
                        id: "writable-policy", name: "SYNTHETIC.JPG", kind: "image", archived: false,
                        writable: global,
                        ratingWritable: vetoIndex == 0 ? field : nil,
                        protectionWritable: vetoIndex == 1 ? field : nil,
                        rotationWritable: vetoIndex == 2 ? field : nil,
                        archiveWritable: vetoIndex == 3 ? field : nil
                    )
                    for index in targets.indices {
                        XCTAssertEqual(targets[index].isWritable(item),
                            global != false && (index != vetoIndex || field != false),
                            "Global false vetoes all fields; only explicit field false vetoes that operation")
                    }
                }
            }
        }
    }

    func testAll200MetadataVectorsThroughActualBridgeAndCCAPIParsers() async throws {
        let fixtures = try vectors("metadataVectors")
        XCTAssertEqual(fixtures.count, 200)
        for vector in fixtures {
            let id = try XCTUnwrap(vector["id"] as? String)
            let canon = vector["profile"] as? String == "canon"
            let field = try XCTUnwrap(vector["field"] as? String)
            let key = fieldKey(field, canon: canon)
            let fragment = (vector["rawValueJSON"] as? String).map { ",\"\(key)\":\($0)" } ?? ""
            let body = Data("{\"id\":\"item-a\",\"name\":\"SYNTHETIC.JPG\",\"kind\":\"image\"\(fragment)}".utf8)
            let peer = MutationFixturePeer(rawInfo: body, rawListing: Data("{\"items\":[\(String(decoding: body, as: UTF8.self))]}".utf8))
            let item: CameraMediaItem
            if canon {
                let client = try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: .simulator, transport: peer)
                item = try await client.mediaInfo(media("item-a"))
            } else {
                let client = try await bridge(peer)
                item = try await client.mediaInfo(media("item-a"))
                let listing = try await client.listMedia()
                let listed = try XCTUnwrap(listing.first)
                XCTAssertEqual(value(listed, field: field), value(item, field: field), id + " listing")
                // Simulator listing has the same normalized scalar profile but Canon key names.
                let simulatorFragment = (vector["rawValueJSON"] as? String).map { ",\"\(fieldKey(field, canon: true))\":\($0)" } ?? ""
                let simulatorPeer = MutationFixturePeer(rawListing: Data("{\"items\":[{\"id\":\"item-a\",\"name\":\"SYNTHETIC.JPG\",\"kind\":\"image\"\(simulatorFragment)}]}".utf8))
                let simulator = try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: .simulator, transport: simulatorPeer)
                let simulatorListing = try await simulator.listMedia()
                let listedSimulator = try XCTUnwrap(simulatorListing.first)
                XCTAssertEqual(value(listedSimulator, field: field), value(item, field: field), id + " simulator listing")
            }
            if vector["known"] as? Bool == true {
                XCTAssertEqual(value(item, field: field), vector["value"] as? NSNumber, id)
            } else { XCTAssertNil(value(item, field: field), id) }
        }
    }

    func testAll66ReadbackVectorsRequireExactIdentityAndRequestedValueWithOneWrite() async throws {
        let fixtures = try vectors("readbackVectors")
        XCTAssertEqual(fixtures.count, 66)
        for vector in fixtures {
            let id = try XCTUnwrap(vector["id"] as? String)
            let canon = vector["profile"] as? String == "canon"
            let operation = try XCTUnwrap(vector["operation"] as? String)
            let target = try mutation(operation, value: vector["target"])
            let requested = try XCTUnwrap(vector["requestedId"] as? String)
            var fields = ["\"name\":\"SYNTHETIC.JPG\"", "\"kind\":\"image\""]
            if vector["returnedIdPresent"] as? Bool == true {
                let raw = try JSONSerialization.data(withJSONObject: vector["returnedId"] ?? NSNull(), options: [.fragmentsAllowed])
                fields.append("\"id\":\(String(decoding: raw, as: UTF8.self))")
            }
            if let raw = vector["rawValueJSON"] as? String { fields.append("\"\(fieldKey(operation, canon: canon))\":\(raw)") }
            let peer = MutationFixturePeer(rawInfo: Data(("{" + fields.joined(separator: ",") + "}").utf8))
            let session: CameraSession = canon
                ? .ccapi(try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: .simulator, transport: peer))
                : .desktopBridge(try await bridge(peer))
            let attempt = CameraMediaMutationAttempt()
            var confirmed = false
            do {
                let updated = try await session.mutateMedia(media(requested), target: target, attempt: attempt)
                confirmed = target.confirms(updated, original: media(requested))
            } catch { confirmed = false }
            XCTAssertEqual(confirmed, vector["outcome"] as? String == "confirmed", id)
            XCTAssertTrue(attempt.dispatched, id)
            let writes = await peer.writeIDs()
            XCTAssertEqual(writes.map { Data($0.utf8) }, [Data(requested.utf8)], id)
            let reads = await peer.infoReadCount()
            XCTAssertLessThanOrEqual(reads, canon ? 3 : 0, id)
        }
    }

    func testAllTenSharedBatchVectorsThroughProductionExecutorOnBothTransports() async throws {
        let fixtures = try vectors("batchVectors")
        XCTAssertEqual(fixtures.count, 10)
        for canon in [false, true] {
            for vector in fixtures {
                let id = try XCTUnwrap(vector["id"] as? String)
                let events = try XCTUnwrap(vector["events"] as? [String])
                let box = MutationStateBox()
                let items = events.enumerated().map { index, event in
                    media("row-\(index)", writable: event == "failed-not-sent" ? false : nil)
                }
                let peer = MutationFixturePeer(items: items, events: events, onWrite: { index in
                    if events[index].hasPrefix("cancel-during") { box.state?.cancelMediaMetadataBatch() }
                    if events[index] == "owner-lost-during" { box.state?.requestDisconnect() }
                })
                let state = makeState(peer, canon: canon, beforeItem: { index in
                    if events[index] == "cancel-before" { box.state?.cancelMediaMetadataBatch() }
                })
                box.state = state
                defer { state.requestDisconnect() }
                try await connect(state)
                state.selectMediaItems(state.mediaItems)
                let originalDraft = try XCTUnwrap(state.makeMediaMetadataBatchDraft(.rating(3), displayed: Array(items.prefix(1))))
                // Model inventory no longer loaded without losing selected roster entries.
                let rows = originalDraft.rows.enumerated().map { index, row in
                    MediaMetadataBatchRow(item: row.item, loadedAtConfirmation: events[index] != "not-started")
                }
                let draft = MediaMetadataBatchDraft(target: originalDraft.target, sessionID: originalDraft.sessionID,
                    generation: originalDraft.generation, historyEpoch: originalDraft.historyEpoch, rows: rows, hiddenCount: originalDraft.hiddenCount)
                XCTAssertTrue(state.startMediaMetadataBatch(draft), id)
                let task = state.mediaMetadataBatchTask
                await task?.value
                let run = try XCTUnwrap(state.mediaMetadataBatches.last)
                XCTAssertEqual(run.rows.map { outcome($0.status) }, vector["outcomes"] as? [String], id)
                let writes = await peer.writeIDs()
                XCTAssertEqual(writes.compactMap { Int($0.replacingOccurrences(of: "row-", with: "")) }, vector["dispatchedIndices"] as? [Int], id)
                XCTAssertFalse(run.running, id)
                XCTAssertEqual(run.rows.map { Data($0.item.id.utf8) }, items.map { Data($0.id.utf8) }, id)
                XCTAssertEqual(Set(writes.map { Data($0.utf8) }).count, writes.count, id + " no retry")
            }
        }
    }

    func testThreeRosterVectorsUseProductionSnapshotWithExactUnicodeIdentities() throws {
        let fixtures = try vectors("rosterVectors")
        XCTAssertEqual(fixtures.count, 3)
        for vector in fixtures {
            let selected = try XCTUnwrap(vector["selectedIds"] as? [String]).map { media($0) }
            let loaded = try XCTUnwrap(vector["loadedIds"] as? [String]).map { media($0) }
            let visible = Set(try XCTUnwrap(vector["visibleIds"] as? [String]).map { Data($0.utf8) })
            let rows = MediaMetadataBatchDraft.rows(selection: selected, loaded: loaded)
            XCTAssertEqual(rows.map { Data($0.item.id.utf8) }, (vector["rosterIds"] as? [String])?.map { Data($0.utf8) })
            XCTAssertEqual(rows.filter(\.loadedAtConfirmation).map { Data($0.item.id.utf8) },
                           (vector["eligibleIds"] as? [String])?.map { Data($0.utf8) })
            XCTAssertEqual(rows.filter { $0.loadedAtConfirmation && !visible.contains(Data($0.item.id.utf8)) }.count, vector["hiddenCount"] as? Int)
            XCTAssertEqual(rows.filter { !$0.loadedAtConfirmation }.count, vector["unloadedCount"] as? Int)
        }
    }

    func testAll25EligibilityAndTwoStaleSnapshotVectorsUseProductionParserAndPolicy() async throws {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "media-mutation-eligibility-vectors", withExtension: "json"))
        let data = try Data(contentsOf: url)
        XCTAssertEqual(SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined(),
                       "c7c6e27e7455ff12989cea4fab48f97fd103344ff442987a37cc4a32e3f99a1d")
        let corpus = try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])
        let fixtures = try XCTUnwrap(corpus["vectors"] as? [[String: Any]])
        XCTAssertEqual(fixtures.count, 25)
        for vector in fixtures {
            let item = try await parsedItem(try XCTUnwrap(vector["item"] as? [String: Any]))
            let requested = try XCTUnwrap(vector["requestedId"] as? String)
            let snapshot = item.id.utf8.elementsEqual(requested.utf8) ? item : media(requested)
            let operation = try XCTUnwrap(vector["operation"] as? String)
            let target = try mutation(operation, value: operation == "rating" || operation == "rotation" ? NSNumber(value: 0) : NSNumber(value: true))
            let reason = MediaMetadataBatchEligibility.reason(snapshot: snapshot,
                current: vector["loaded"] as? Bool == true ? item : nil, target: target, supported: vector["supported"] as? Bool == true)
            XCTAssertEqual(reason == nil, vector["eligible"] as? Bool, vector["id"] as? String ?? "")
        }
        let snapshots = try XCTUnwrap(corpus["snapshotVectors"] as? [[String: Any]])
        XCTAssertEqual(snapshots.count, 2)
        for vector in snapshots {
            let selected = try await parsedItem(try XCTUnwrap(vector["selected"] as? [String: Any]))
            let loaded = try await parsedItem(try XCTUnwrap(vector["loaded"] as? [String: Any]))
            let rows = MediaMetadataBatchDraft.rows(selection: [selected], loaded: [loaded])
            let row = try XCTUnwrap(rows.first)
            let target: CameraMediaMutation = vector["operation"] as? String == "rating" ? .rating(3) : .archive(true)
            XCTAssertEqual(row.item, loaded)
            let reason = MediaMetadataBatchEligibility.reason(snapshot: row.item, current: loaded, target: target, supported: true)
            XCTAssertEqual(reason == nil, vector["eligible"] as? Bool)
        }
    }

    func testRawDuplicateAndEscapedNumericFieldsNeverConfirmMalformedReadback() async throws {
        for field in [#""rating":3,"rating":3"#, #""rating":3e0"#, #""ra\u0074ing":3.0000000000000000001"#,
                      #""rating":3.0"#, #""rating":true"#] {
            let peer = MutationFixturePeer(rawInfo: Data((#"{"id":"item-a","name":"SYNTHETIC.JPG","# + field + "}").utf8))
            let session = CameraSession.desktopBridge(try await bridge(peer))
            let attempt = CameraMediaMutationAttempt()
            do { _ = try await session.mutateMedia(media("item-a"), target: .rating(3), attempt: attempt); XCTFail("Malformed readback must be uncertain") }
            catch { XCTAssertTrue(attempt.dispatched) }
            let writes = await peer.writeIDs()
            XCTAssertEqual(writes, ["item-a"])
        }
    }

    func testRawScannerPositiveEscapesAndOversizeDepthBoundaries() async throws {
        let valid = Data(#"{"id":"item-a","name":"SYNTHETIC.JPG","ra\u0074ing":3,"nested":{"array":[{"value":"escaped\\\"quote"}]}}"#.utf8)
        let positive = MutationFixturePeer(rawInfo: valid)
        let positiveSession = CameraSession.desktopBridge(try await bridge(positive))
        let confirmed = try await positiveSession.mutateMedia(media("item-a"), target: .rating(3), attempt: CameraMediaMutationAttempt())
        XCTAssertEqual(confirmed.rating, 3)
        let tooDeep = Data((#"{"id":"item-a","name":"SYNTHETIC.JPG","rating":3,"nested":"#
            + String(repeating: "[", count: 513) + "0" + String(repeating: "]", count: 513) + "}").utf8)
        let tooLarge = Data((#"{"id":"item-a","name":"SYNTHETIC.JPG","rating":3,"padding":""#
            + String(repeating: "x", count: 1_048_576) + "\"}").utf8)
        for body in [tooDeep, tooLarge] {
            let peer = MutationFixturePeer(rawInfo: body)
            let session = CameraSession.desktopBridge(try await bridge(peer))
            let attempt = CameraMediaMutationAttempt()
            do { _ = try await session.mutateMedia(media("item-a"), target: .rating(3), attempt: attempt); XCTFail("Oversized/deep response must not confirm") }
            catch { XCTAssertTrue(attempt.dispatched) }
            let writes = await peer.writeIDs()
            XCTAssertEqual(writes, ["item-a"])
        }
    }

    private func parsedItem(_ fields: [String: Any]) async throws -> CameraMediaItem {
        var body = fields
        body["name"] = "SYNTHETIC.JPG"
        body["kind"] = "image"
        let id = try XCTUnwrap(body["id"] as? String)
        let peer = MutationFixturePeer(rawInfo: try JSONSerialization.data(withJSONObject: body))
        let client = try await bridge(peer)
        return try await client.mediaInfo(media(id))
    }

    func testRosterRefreshesStaleSelectionFromExactLoadedItemBeforeEligibility() throws {
        let stale = CameraMediaItem(id: "a", name: "OLD.JPG", kind: "image", archived: false, writable: true)
        let current = CameraMediaItem(id: "a", name: "NEW.JPG", kind: "image", archived: nil, writable: false)
        let rows = MediaMetadataBatchDraft.rows(selection: [stale], loaded: [current])
        XCTAssertEqual(rows.first?.item, current)
        XCTAssertTrue(rows.first?.loadedAtConfirmation == true)
        let unloaded = MediaMetadataBatchDraft.rows(selection: [stale], loaded: [])
        XCTAssertEqual(unloaded.first?.item, stale)
        XCTAssertFalse(unloaded.first?.loadedAtConfirmation ?? true)
    }

    func testAllTargetsAndExplicitSecondOperationDoNotReplayPreviousRows() async throws {
        for canon in [false, true] {
            let peer = MutationFixturePeer(items: [media("a"), media("b")])
            let state = makeState(peer, canon: canon)
            defer { state.requestDisconnect() }
            try await connect(state)
            state.selectMediaItems(state.mediaItems)
            let targets: [CameraMediaMutation] = [.rating(0), .rating(5), .protection(true), .protection(false),
                .rotation(0), .rotation(90), .rotation(180), .rotation(270), .archive(true), .archive(false)]
            for target in targets {
                let draft = try XCTUnwrap(state.makeMediaMetadataBatchDraft(target, displayed: []))
                XCTAssertTrue(state.startMediaMetadataBatch(draft))
                await state.mediaMetadataBatchTask?.value
                XCTAssertEqual(state.mediaMetadataBatches.last?.rows.map(\.status), [.confirmed, .confirmed])
                XCTAssertFalse(state.startMediaMetadataBatch(draft), "A consumed confirmation cannot replay")
            }
            let writes = await peer.writeIDs()
            XCTAssertEqual(writes.count, targets.count * 2)
            XCTAssertEqual(state.mediaMetadataBatches.count, targets.count)
        }
    }

    func testUnloadedUnknownArchiveAndNotWritableRowsAreRetainedWithoutWrites() async throws {
        let peer = MutationFixturePeer(items: [media("known"), media("unknown", archived: nil), media("denied", writable: false)])
        let state = makeState(peer)
        defer { state.requestDisconnect() }
        try await connect(state)
        state.selectMediaItems(state.mediaItems)
        let draft = try XCTUnwrap(state.makeMediaMetadataBatchDraft(.archive(true), displayed: []))
        XCTAssertTrue(state.startMediaMetadataBatch(draft))
        await state.mediaMetadataBatchTask?.value
        XCTAssertEqual(state.mediaMetadataBatches.last?.rows.map(\.status), [.confirmed, .notStarted, .failedNotSent])
        let writes = await peer.writeIDs()
        XCTAssertEqual(writes, ["known"])
    }

    func testSpecificWritableVetoStopsMatchingOperationOnlyAndSurvivesReadback() async throws {
        for canon in [false, true] {
            let original = CameraMediaItem(id: "denied-rating", name: "SYNTHETIC.JPG", kind: "image", archived: false, ratingWritable: false)
            let peer = MutationFixturePeer(items: [original])
            let state = makeState(peer, canon: canon)
            defer { state.requestDisconnect() }
            try await connect(state)
            state.selectMediaItems(state.mediaItems)
            let rating = try XCTUnwrap(state.makeMediaMetadataBatchDraft(.rating(3), displayed: []))
            XCTAssertTrue(state.startMediaMetadataBatch(rating))
            await state.mediaMetadataBatchTask?.value
            XCTAssertEqual(state.mediaMetadataBatches.last?.rows.map(\.status), [.failedNotSent])
            let before = await peer.writeIDs()
            XCTAssertTrue(before.isEmpty)
            let protection = try XCTUnwrap(state.makeMediaMetadataBatchDraft(.protection(true), displayed: []))
            XCTAssertTrue(state.startMediaMetadataBatch(protection))
            await state.mediaMetadataBatchTask?.value
            XCTAssertEqual(state.mediaMetadataBatches.last?.rows.map(\.status), [.confirmed])
            XCTAssertEqual(state.mediaItems.first?.ratingWritable, false)
            let after = await peer.writeIDs()
            XCTAssertEqual(after, ["denied-rating"])
        }
    }

    func testOverLimitRejectsEntireBatchWithoutSilentlyTruncating() async throws {
        let peer = MutationFixturePeer(items: [media("a")])
        let state = makeState(peer)
        defer { state.requestDisconnect() }
        try await connect(state)
        state.selectMediaItems(state.mediaItems)
        let owner = try XCTUnwrap(state.makeMediaMetadataBatchDraft(.rating(3), displayed: []))
        let rows = (0...1_000).map { MediaMetadataBatchRow(item: media("limit-\($0)"), loadedAtConfirmation: true) }
        let draft = MediaMetadataBatchDraft(target: .rating(3), sessionID: owner.sessionID, generation: owner.generation, historyEpoch: owner.historyEpoch, rows: rows, hiddenCount: 0)
        XCTAssertFalse(state.startMediaMetadataBatch(draft))
        XCTAssertTrue(state.mediaMetadataBatches.isEmpty)
        XCTAssertEqual(state.mediaMetadataBatchRejection?.selectedCount, 1_001)
        XCTAssertEqual(state.mediaMetadataBatchRejection?.reason, .limit)
        XCTAssertEqual(draft.rows.count, 1_001, "The rejected review is not silently truncated")
        for _ in 0..<100 { XCTAssertFalse(state.startMediaMetadataBatch(draft)) }
        XCTAssertTrue(state.mediaMetadataBatches.isEmpty, "Repeated rejection cannot grow history")
        let writes = await peer.writeIDs()
        XCTAssertTrue(writes.isEmpty)
    }

    func testBridgePartialMutationResponsePreservesUnrelatedFieldsAndWritableVetoes() async throws {
        for extra in ["", #","ratingWritable":null"#, #","ratingWritable":true,"rotationWritable":true,"archiveWritable":true,"rating":5,"archived":true"#] {
            let original = CameraMediaItem(id: "a", name: "ORIGINAL.JPG", kind: "image", sizeBytes: 123,
                captureTime: "2026:10:15 00:00:00", previewAvailable: true, protected: false, rating: 1,
                rotationDegrees: 90, archived: false, contentType: "image/jpeg", widthPixels: 2, heightPixels: 3,
                folder: CameraMediaFolder(id: "folder", label: "SYNTHETIC"), writable: true,
                ratingWritable: false, protectionWritable: true, rotationWritable: false, archiveWritable: false)
            let body = Data((#"{"id":"a","name":"PARTIAL.JPG","protected":true"# + extra + "}").utf8)
            let peer = MutationFixturePeer(items: [original], rawInfo: body)
            let state = makeState(peer)
            defer { state.requestDisconnect() }
            try await connect(state)
            state.selectMediaItems(state.mediaItems)
            let loaded = try XCTUnwrap(state.mediaItems.first)
            let draft = try XCTUnwrap(state.makeMediaMetadataBatchDraft(.protection(true), displayed: []))
            XCTAssertTrue(state.startMediaMetadataBatch(draft))
            await state.mediaMetadataBatchTask?.value
            let updated = try XCTUnwrap(state.mediaItems.first)
            XCTAssertEqual(state.mediaMetadataBatches.last?.rows.first?.status, .confirmed)
            XCTAssertEqual(updated.protected, true)
            XCTAssertEqual(updated.name, loaded.name)
            XCTAssertEqual(updated.kind, loaded.kind)
            XCTAssertEqual(updated.rating, loaded.rating)
            XCTAssertEqual(updated.archived, loaded.archived)
            XCTAssertEqual(updated.rotationDegrees, loaded.rotationDegrees)
            XCTAssertEqual(updated.sizeBytes, loaded.sizeBytes)
            XCTAssertEqual(updated.captureTime, loaded.captureTime)
            XCTAssertEqual(updated.previewAvailable, loaded.previewAvailable)
            XCTAssertEqual(updated.contentType, loaded.contentType)
            XCTAssertEqual(updated.widthPixels, loaded.widthPixels)
            XCTAssertEqual(updated.heightPixels, loaded.heightPixels)
            XCTAssertEqual(updated.folder, loaded.folder)
            XCTAssertEqual(updated.writable, loaded.writable)
            XCTAssertEqual(updated.ratingWritable, false)
            XCTAssertEqual(updated.protectionWritable, true)
            XCTAssertEqual(updated.rotationWritable, false)
            XCTAssertEqual(updated.archiveWritable, false)
            XCTAssertEqual(state.selectedMediaItems.first, updated)
            let rating = try XCTUnwrap(state.makeMediaMetadataBatchDraft(.rating(3), displayed: []))
            XCTAssertTrue(state.startMediaMetadataBatch(rating))
            await state.mediaMetadataBatchTask?.value
            XCTAssertEqual(state.mediaMetadataBatches.last?.rows.first?.status, .failedNotSent)
            let writes = await peer.writeIDs()
            XCTAssertEqual(writes, ["a"], "Partial unrelated readback cannot erase an existing rating veto")
            await state.setMediaProtection(updated, enabled: true, owner: state.mediaLibrarySessionID)
            XCTAssertEqual(state.mediaItems.first?.ratingWritable, false, "Single-item updates use the same safe merge")
            await state.setMediaRating(media("a"), rating: 3, owner: state.mediaLibrarySessionID) // Stale caller lacks the current veto.
            let afterSingle = await peer.writeIDs()
            XCTAssertEqual(afterSingle, ["a", "a"], "Current loaded veto also blocks stale single-item callers")
        }
    }

    func testHistoryBatchAdmissionIsBoundedAcrossOwnersAndClearInvalidatesOldReviews() async throws {
        let peer = MutationFixturePeer(items: [media("a")])
        let state = makeState(peer)
        defer { state.requestDisconnect() }
        try await connect(state)
        state.selectMediaItems(state.mediaItems)
        var firstDraft: MediaMetadataBatchDraft?
        for index in 0..<MediaMetadataBatchHistory.maximumBatches {
            if index == 10 {
                await state.disconnect()
                try await connect(state)
                state.selectMediaItems(state.mediaItems)
            }
            let draft = try XCTUnwrap(state.makeMediaMetadataBatchDraft(.rating(-1), displayed: []))
            if firstDraft == nil { firstDraft = draft }
            XCTAssertFalse(state.startMediaMetadataBatch(draft))
        }
        XCTAssertEqual(state.mediaMetadataBatches.count, 20)
        XCTAssertEqual(state.mediaMetadataHistoryRowCount, 20)
        for _ in 0..<100 {
            let rejected = try XCTUnwrap(state.makeMediaMetadataBatchDraft(.rating(3), displayed: []))
            XCTAssertFalse(state.startMediaMetadataBatch(rejected))
        }
        XCTAssertEqual(state.mediaMetadataBatches.count, 20)
        XCTAssertEqual(state.mediaMetadataBatchRejection?.reason, .capacity)
        let staleReview = try XCTUnwrap(state.makeMediaMetadataBatchDraft(.rating(3), displayed: []))
        let first = try XCTUnwrap(firstDraft)
        XCTAssertFalse(state.clearMediaMetadataBatchHistory(first.id, confirmed: false))
        XCTAssertEqual(state.mediaMetadataBatches.count, 20)
        XCTAssertTrue(state.clearMediaMetadataBatchHistory(first.id, confirmed: true))
        XCTAssertEqual(state.mediaMetadataBatches.count, 19)
        XCTAssertFalse(state.startMediaMetadataBatch(first))
        XCTAssertFalse(state.startMediaMetadataBatch(staleReview))
        XCTAssertEqual(state.mediaMetadataBatchRejection?.reason, .staleReview)
        XCTAssertEqual(state.mediaMetadataBatches.count, 19, "Stale reviews do not append rejection history")
        let fresh = try XCTUnwrap(state.makeMediaMetadataBatchDraft(.rating(3), displayed: []))
        XCTAssertTrue(state.startMediaMetadataBatch(fresh))
        await state.mediaMetadataBatchTask?.value
        XCTAssertEqual(state.mediaMetadataBatches.count, 20)
        XCTAssertEqual(state.mediaMetadataBatches.last?.rows.first?.status, .confirmed)
        let writes = await peer.writeIDs()
        XCTAssertEqual(writes, ["a"])
    }

    func testHistoryTotalRowAdmissionRejectsWholeBatchWithoutDroppingPriorResults() async throws {
        let peer = MutationFixturePeer(items: [media("a")])
        let state = makeState(peer)
        defer { state.requestDisconnect() }
        try await connect(state)
        state.selectMediaItems(state.mediaItems)
        let owner = try XCTUnwrap(state.makeMediaMetadataBatchDraft(.rating(3), displayed: []))
        let rows = (0..<1_000).map { MediaMetadataBatchRow(item: media("unloaded-\($0)"), loadedAtConfirmation: false) }
        for _ in 0..<2 {
            let draft = MediaMetadataBatchDraft(target: owner.target, sessionID: owner.sessionID,
                generation: owner.generation, historyEpoch: owner.historyEpoch, rows: rows, hiddenCount: 0)
            XCTAssertTrue(state.startMediaMetadataBatch(draft))
            await state.mediaMetadataBatchTask?.value
        }
        XCTAssertEqual(state.mediaMetadataBatches.count, 2)
        XCTAssertEqual(state.mediaMetadataHistoryRowCount, 2_000)
        XCTAssertFalse(state.startMediaMetadataBatch(owner))
        XCTAssertEqual(state.mediaMetadataBatchRejection?.selectedCount, 1)
        XCTAssertEqual(state.mediaMetadataBatchRejection?.reason, .capacity)
        XCTAssertEqual(state.mediaMetadataHistoryRowCount, 2_000)
        XCTAssertTrue(state.mediaMetadataBatches.allSatisfy { $0.rows.allSatisfy { $0.status == .notStarted && $0.reason == .unloaded } })
        let writes = await peer.writeIDs()
        XCTAssertTrue(writes.isEmpty)
    }

    func testHistoryCannotClearWhileRunningAndUncertainRecordsRequireExplicitConfirmation() async throws {
        let box = MutationStateBox()
        let peer = MutationFixturePeer(items: [media("a")], events: ["uncertain"], onWrite: { _ in
            guard let state = box.state else { return }
            XCTAssertFalse(state.canClearMediaMetadataHistory)
            for run in state.mediaMetadataBatches {
                XCTAssertFalse(state.clearMediaMetadataBatchHistory(run.id, confirmed: true), "Do not shift an active executor index")
            }
        })
        let state = makeState(peer)
        box.state = state
        defer { state.requestDisconnect() }
        try await connect(state)
        state.selectMediaItems(state.mediaItems)
        let confirmed = try XCTUnwrap(state.makeMediaMetadataBatchDraft(.protection(true), displayed: []))
        XCTAssertTrue(state.startMediaMetadataBatch(confirmed))
        await state.mediaMetadataBatchTask?.value
        let uncertain = try XCTUnwrap(state.makeMediaMetadataBatchDraft(.rating(3), displayed: []))
        XCTAssertTrue(state.startMediaMetadataBatch(uncertain))
        await state.mediaMetadataBatchTask?.value
        XCTAssertEqual(state.mediaMetadataBatches.map { $0.rows[0].status }, [.confirmed, .unconfirmed])
        await state.disconnect()
        XCTAssertFalse(state.clearMediaMetadataBatchHistory(uncertain.id, confirmed: false))
        XCTAssertEqual(state.mediaMetadataBatches.map { $0.rows[0].status }, [.confirmed, .unconfirmed])
        let before = await peer.writeIDs()
        XCTAssertTrue(state.clearMediaMetadataBatchHistory(uncertain.id, confirmed: true))
        XCTAssertEqual(state.mediaMetadataBatches.map { $0.rows[0].status }, [.confirmed])
        XCTAssertFalse(state.startMediaMetadataBatch(uncertain), "Cleared uncertain work cannot replay")
        let after = await peer.writeIDs()
        XCTAssertEqual(after, before, "Clearing is not a camera command or undo")
    }

    func testRetiredInflightHistoryClearDoesNotApplyLateReadbackOrReplay() async throws {
        let box = MutationStateBox()
        let peer = MutationFixturePeer(items: [media("a")], onWrite: { _ in
            guard let state = box.state, let id = state.mediaMetadataBatches.last?.id else { return }
            state.requestDisconnect()
            XCTAssertEqual(state.mediaMetadataBatches.last?.rows.first?.status, .unconfirmed)
            XCTAssertTrue(state.mediaMetadataBatches.last?.ownerRetired == true)
            XCTAssertFalse(state.clearMediaMetadataBatchHistory(id, confirmed: false))
            XCTAssertTrue(state.clearMediaMetadataBatchHistory(id, confirmed: true))
        })
        let state = makeState(peer)
        box.state = state
        defer { state.requestDisconnect() }
        try await connect(state)
        state.selectMediaItems(state.mediaItems)
        let draft = try XCTUnwrap(state.makeMediaMetadataBatchDraft(.rating(3), displayed: []))
        XCTAssertTrue(state.startMediaMetadataBatch(draft))
        let task = state.mediaMetadataBatchTask
        await task?.value
        XCTAssertTrue(state.mediaMetadataBatches.isEmpty, "Late readback must not resurrect an explicitly cleared old-owner record")
        XCTAssertFalse(state.startMediaMetadataBatch(draft))
        XCTAssertEqual(state.mediaMetadataBatchRejection?.reason, .staleReview)
        XCTAssertTrue(state.mediaMetadataBatches.isEmpty)
        let writes = await peer.writeIDs()
        XCTAssertEqual(writes, ["a"])
    }

    func testSelectionChangesCannotExpandOrShrinkConfirmedDraftAndStaleDraftCannotSend() async throws {
        let peer = MutationFixturePeer(items: [media("a"), media("b"), media("c")])
        let state = makeState(peer)
        defer { state.requestDisconnect() }
        try await connect(state)
        state.selectMediaItems(Array(state.mediaItems.prefix(2)))
        let draft = try XCTUnwrap(state.makeMediaMetadataBatchDraft(.protection(true), displayed: []))
        state.clearMediaSelection()
        state.selectMediaItems(Array(state.mediaItems.suffix(1)))
        XCTAssertTrue(state.startMediaMetadataBatch(draft))
        await state.mediaMetadataBatchTask?.value
        let writes = await peer.writeIDs()
        XCTAssertEqual(writes, ["a", "b"])
        let stale = try XCTUnwrap(state.makeMediaMetadataBatchDraft(.rating(3), displayed: []))
        await state.disconnect()
        XCTAssertFalse(state.startMediaMetadataBatch(stale))
        XCTAssertEqual(state.mediaMetadataBatches.first?.rows.map(\.status), [.confirmed, .confirmed])
        XCTAssertEqual(state.mediaMetadataBatches.last?.rows.map(\.status), [.notStarted])
        let afterStale = await peer.writeIDs()
        XCTAssertEqual(afterStale, writes)
    }

    func testDelayedSingleMetadataCallbacksCannotMutateReplacementOwnerWithSameID() async throws {
        for canon in [false, true] {
            let oldPeer = MutationFixturePeer(items: [media("collision")])
            let replacement = CameraMediaItem(id: "collision", name: "REPLACEMENT.JPG", kind: "image",
                protected: false, rating: 2, rotationDegrees: 90, archived: false)
            let newPeer = MutationFixturePeer(items: [replacement])
            var peers = [oldPeer, newPeer]
            let state = CameraAppState(defaults: UserDefaults(suiteName: "DelayedMetadata.\(UUID())")!, sessionFactory: {
                let peer = peers.removeFirst()
                return canon
                    ? .ccapi(try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: .simulator, transport: peer))
                    : .desktopBridge(try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", transport: peer))
            })
            state.autoRefresh = false
            defer { state.requestDisconnect() }
            try await connect(state)
            let oldItem = try XCTUnwrap(state.mediaItems.first)
            let openingOwner = state.mediaLibrarySessionID
            // These stand in for already-created sheet Tasks/Picker callbacks.
            // Do not read a new owner when the delayed callbacks eventually run.
            let delayed: [@MainActor () async -> Void] = [
                { await state.setMediaProtection(oldItem, enabled: true, owner: openingOwner) },
                { await state.setMediaRating(oldItem, rating: 5, owner: openingOwner) },
                { await state.setMediaRotation(oldItem, degrees: 270, owner: openingOwner) },
                { await state.setMediaArchive(oldItem, enabled: true, owner: openingOwner) },
            ]
            await state.disconnect()
            try await connect(state)
            XCTAssertNotEqual(state.mediaLibrarySessionID, openingOwner)
            XCTAssertEqual(state.mediaItems.first?.id, oldItem.id)
            state.selectMediaItems(state.mediaItems)
            let items = state.mediaItems
            let selected = state.selectedMediaItems
            let latest = state.latestMediaItem
            let snapshot = state.snapshot
            let error = state.lastError
            let busy = state.busyOperations
            for callback in delayed { await callback() }
            XCTAssertEqual(state.mediaItems, items)
            XCTAssertEqual(state.selectedMediaItems, selected)
            XCTAssertEqual(state.latestMediaItem, latest)
            XCTAssertEqual(state.snapshot, snapshot)
            XCTAssertEqual(state.lastError, error)
            XCTAssertEqual(state.busyOperations, busy, "Stale intent must be rejected before begin")
            XCTAssertTrue(state.mediaMetadataBatches.isEmpty)
            let oldWrites = await oldPeer.writeIDs()
            let newWrites = await newPeer.writeIDs()
            XCTAssertTrue(oldWrites.isEmpty)
            XCTAssertTrue(newWrites.isEmpty, "Replacement camera must receive zero PUTs from delayed old-owner callbacks")

            let currentOwner = state.mediaLibrarySessionID
            let current = try XCTUnwrap(state.mediaItems.first)
            await state.setMediaProtection(current, enabled: true, owner: currentOwner)
            await state.setMediaRating(current, rating: 5, owner: currentOwner)
            await state.setMediaRotation(current, degrees: 270, owner: currentOwner)
            await state.setMediaArchive(current, enabled: true, owner: currentOwner)
            let writes = await newPeer.writeIDs()
            XCTAssertEqual(writes, Array(repeating: "collision", count: 4), "Same-owner single-item actions remain usable")
            let updated = try XCTUnwrap(state.mediaItems.first)
            XCTAssertEqual(updated.protected, true)
            XCTAssertEqual(updated.rating, 5)
            XCTAssertEqual(updated.rotationDegrees, 270)
            XCTAssertEqual(updated.archived, true)
            XCTAssertEqual(updated.name, replacement.name)
            XCTAssertNil(state.lastError)
        }
    }

    func testPreDispatchCancelledMarkerDoesNotSendAndInvalidTargetsCannotDispatch() async throws {
        for canon in [false, true] {
            let peer = MutationFixturePeer(items: [media("a")])
            let session: CameraSession = canon
                ? .ccapi(try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: .simulator, transport: peer))
                : .desktopBridge(try await bridge(peer))
            for target in [CameraMediaMutation.rating(3), .rating(-1), .rating(6), .rotation(45)] {
                let attempt = CameraMediaMutationAttempt()
                attempt.cancel()
                do { _ = try await session.mutateMedia(media("a"), target: target, attempt: attempt); XCTFail("Expected safe preflight rejection") }
                catch { XCTAssertFalse(attempt.dispatched) }
            }
            let writes = await peer.writeIDs()
            XCTAssertTrue(writes.isEmpty)
        }
    }

    private func fieldKey(_ field: String, canon: Bool) -> String {
        switch field {
        case "rotation": return canon ? "rotate" : "rotationDegrees"
        case "protection": return canon ? "protect" : "protected"
        case "archive": return canon ? "archive" : "archived"
        default: return "rating"
        }
    }
    private func value(_ item: CameraMediaItem, field: String) -> NSNumber? {
        switch field {
        case "rating": return item.rating.map { NSNumber(value: $0) }
        case "rotation": return item.rotationDegrees.map { NSNumber(value: $0) }
        case "protection": return item.protected.map { NSNumber(value: $0) }
        case "archive": return item.archived.map { NSNumber(value: $0) }
        default: return nil
        }
    }
    private func mutation(_ operation: String, value: Any?) throws -> CameraMediaMutation {
        switch operation {
        case "rating": return .rating(try XCTUnwrap(value as? Int))
        case "rotation": return .rotation(try XCTUnwrap(value as? Int))
        case "protection": return .protection(try XCTUnwrap(value as? Bool))
        default: return .archive(try XCTUnwrap(value as? Bool))
        }
    }
    private func outcome(_ status: MediaMetadataBatchStatus) -> String {
        switch status {
        case .confirmed: "confirmed"
        case .unconfirmed: "uncertain"
        case .failedNotSent: "failed-not-sent"
        case .notStarted: "not-started"
        case .applying: "invalid-in-flight-terminal"
        }
    }
    private func media(_ id: String, archived: Bool? = false, writable: Bool? = nil) -> CameraMediaItem {
        CameraMediaItem(id: id, name: "SYNTHETIC.JPG", kind: "image", protected: false, rating: 0,
            rotationDegrees: 0, archived: archived, writable: writable)
    }
    private func bridge(_ peer: MutationFixturePeer) async throws -> DesktopBridgeClient {
        let client = try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", transport: peer)
        try await client.initialize()
        return client
    }
    private func makeState(_ peer: MutationFixturePeer, canon: Bool = false,
                           beforeItem: (@MainActor (Int) -> Void)? = nil) -> CameraAppState {
        let state = CameraAppState(defaults: UserDefaults(suiteName: "MutationFixtures.\(UUID())")!, sessionFactory: {
            canon ? .ccapi(try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: .simulator, transport: peer))
                : .desktopBridge(try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", transport: peer))
        }, mediaMetadataBeforeItem: beforeItem)
        state.autoRefresh = false
        return state
    }
    private func connect(_ state: CameraAppState) async throws {
        await state.connect()
        XCTAssertTrue(state.connected)
        await state.latestMediaTask?.value
        state.startMediaLibraryLoad()
        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: .seconds(5))
        while state.mediaLibraryLoadStatus != .complete && clock.now < deadline { await Task.yield() }
        XCTAssertEqual(state.mediaLibraryLoadStatus, .complete)
    }
}

@MainActor private final class MutationStateBox { weak var state: CameraAppState? }

private actor MutationFixturePeer: CameraHTTPTransport {
    private var items: [CameraMediaItem]
    private let rawInfo: Data?
    private let rawListing: Data?
    private let events: [String]
    private let onWrite: (@MainActor @Sendable (Int) -> Void)?
    private var writes: [String] = []
    private var reads = 0
    private var uncertainIDs = Set<Data>()
    init(items: [CameraMediaItem] = [], rawInfo: Data? = nil, rawListing: Data? = nil,
         events: [String] = [], onWrite: (@MainActor @Sendable (Int) -> Void)? = nil) {
        self.items = items; self.rawInfo = rawInfo; self.rawListing = rawListing
        self.events = events; self.onWrite = onWrite
    }
    func writeIDs() -> [String] { writes }
    func infoReadCount() -> Int { reads }
    func download(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse { throw URLError(.unsupportedURL) }
    func send(_ request: URLRequest) async throws -> CameraHTTPResponse {
        let url = request.url!
        let path = url.path
        let method = request.httpMethod ?? "GET"
        let canon = path.hasPrefix("/ccapi")
        if path.hasSuffix("/events"), method == "GET" { throw CancellationError() }
        if method == "DELETE", path == "/v1/session/synthetic-mutation" { return CameraHTTPResponse(statusCode: 204) }
        if path == "/health" { return json(["ok": true, "service": "open-eos-control-bridge", "version": "0.13.0"]) }
        if path == "/v1/session" { return json(["id": "synthetic-mutation", "engine": "ccapi"], status: 201) }
        if path.hasSuffix("/media") { return rawListing.map { CameraHTTPResponse(statusCode: 200, body: $0) } ?? json(["items": items.map { mediaJSON($0, canon: canon, info: false) }]) }
        if path.contains("/media/") {
            let parts = url.pathComponents
            let marker = parts.firstIndex(of: "media")!
            let id = parts[marker + 1]
            let index = items.firstIndex { $0.id.utf8.elementsEqual(id.utf8) }
            if method == "PUT" {
                writes.append(id)
                if let index {
                    await onWrite?(index)
                    let event = events.indices.contains(index) ? events[index] : "confirmed"
                    if event == "cancel-during-error" { throw URLError(.networkConnectionLost) }
                    if event == "uncertain" { uncertainIDs.insert(Data(id.utf8)) }
                    let payload = try JSONSerialization.jsonObject(with: request.httpBody!) as! [String: Any]
                    let operation = canon ? payload["action"] as! String : url.lastPathComponent
                    let old = items[index]
                    let number = (payload["value"] as? Int) ?? Int(payload["value"] as? String ?? "") ?? 0
                    let flag = (payload["enabled"] as? Bool) ?? (payload["value"] as? String == "enable")
                    items[index] = CameraMediaItem(id: id, name: old.name, kind: old.kind,
                        protected: ["protect", "protection"].contains(operation) ? flag : old.protected,
                        rating: operation == "rating" ? number : old.rating,
                        rotationDegrees: ["rotate", "rotation"].contains(operation) ? ((payload["degrees"] as? Int) ?? number) : old.rotationDegrees,
                        archived: operation == "archive" ? flag : old.archived, writable: old.writable,
                        ratingWritable: old.ratingWritable,
                        protectionWritable: old.protectionWritable,
                        rotationWritable: old.rotationWritable,
                        archiveWritable: old.archiveWritable)
                }
                if !canon, let rawInfo { return CameraHTTPResponse(statusCode: 200, body: rawInfo) }
                if canon { return json([:]) }
                return json(index.map { mediaJSON(items[$0], canon: false, info: true) } ?? [:])
            }
            let kind = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first(where: { $0.name == "kind" })?.value
            if kind == "info" || path.hasSuffix("/info") {
                reads += 1
                if let rawInfo { return CameraHTTPResponse(statusCode: 200, body: rawInfo) }
                return json(index.map { mediaJSON(items[$0], canon: canon, info: true) } ?? [:])
            }
            throw URLError(.unsupportedURL)
        }
        if path.hasSuffix("/capabilities") {
            return json(canon ? [:] : ["supported": ["MEDIA_BROWSER", "MEDIA_RATING", "MEDIA_PROTECT", "MEDIA_ROTATE", "MEDIA_ARCHIVE"]])
        }
        if path.hasSuffix("/info") { return json(["connected": true, "model": "Synthetic mutation", "serial": "SYNTHETIC-MUTATION", "api": "fixture"]) }
        if path.hasSuffix("/status") { return json(["connected": true, "recording": false, "mode": "Manual", "battery": [:], "media": [:], "exposure": [:]]) }
        return json([:])
    }
    private func mediaJSON(_ item: CameraMediaItem, canon: Bool, info: Bool) -> [String: Any] {
        let rating = uncertainIDs.contains(Data(item.id.utf8)) ? 2 : item.rating
        var result: [String: Any] = ["id": item.id, "name": item.name, "kind": item.kind]
        result["sizeBytes"] = item.sizeBytes
        result["captureTime"] = item.captureTime
        result["previewAvailable"] = item.previewAvailable
        result["contentType"] = item.contentType
        result["widthPixels"] = item.widthPixels
        result["heightPixels"] = item.heightPixels
        result["folderId"] = item.folder?.id
        result["folderLabel"] = item.folder?.label
        if canon && info {
            result["rating"] = rating.map { $0 == 0 ? "off" : String($0) }
            result["rotate"] = item.rotationDegrees.map(String.init)
            result["protect"] = item.protected.map { $0 ? "enable" : "disable" }
            result["archive"] = item.archived.map { $0 ? "enable" : "disable" }
        } else {
            result["rating"] = rating
            result[canon ? "rotate" : "rotationDegrees"] = item.rotationDegrees
            result[canon ? "protect" : "protected"] = item.protected
            result[canon ? "archive" : "archived"] = item.archived
        }
        result["writable"] = item.writable
        result["ratingWritable"] = item.ratingWritable
        result["protectionWritable"] = item.protectionWritable
        result["rotationWritable"] = item.rotationWritable
        result["archiveWritable"] = item.archiveWritable
        return result
    }
    private func json(_ object: [String: Any], status: Int = 200) -> CameraHTTPResponse {
        CameraHTTPResponse(statusCode: status, body: try! JSONSerialization.data(withJSONObject: object))
    }
}
