import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import XCTest
@testable import OpenEOSCore

/// Authored native tests. Linux source checks do not execute these tests.
final class ShootingSettingEvidenceTests: XCTestCase {
    private func vectors(_ section: String) throws -> [[String: Any]] {
        var root = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { root.deleteLastPathComponent() }
        let data = try Data(contentsOf: root.appendingPathComponent("contracts/shooting-setting/v1/shooting-setting-vectors.json"))
        return try XCTUnwrap((JSONSerialization.jsonObject(with: data) as? [String: Any])?[section] as? [[String: Any]])
    }
    func testAll24SharedWireVectorsThroughActualBridgeClient() async throws {
        let rows = try vectors("wire")
        XCTAssertEqual(rows.count, 24)
        for row in rows {
            let input = row["input"] as! [String: Any]
            let (client, peer) = try await bridgeClient()
            let key = input["key"] as! String, requested = input["requestedValue"] as! String
            await peer.enqueue(method: "POST", path: "/v1/session/setting/settings/" + key + "/outcome",
                status: input["status"] as! Int, body: try JSONSerialization.data(withJSONObject: input["response"]!))
            let attempt = CameraShootingSettingAttempt(key: key, requestedValue: requested)
            do { try await client.applyShootingSetting(key: key, value: requested, attempt: attempt) } catch {}
            let expected = row["expected"] as! [String: String]
            XCTAssertEqual(attempt.snapshot.writeEvidence.rawValue, expected["writeEvidence"], row["id"] as! String)
            XCTAssertEqual(attempt.snapshot.readbackEvidence.rawValue, expected["readbackEvidence"], row["id"] as! String)
            let requests = await peer.requests()
            XCTAssertEqual(requests.filter { $0.path.contains("/settings/") }.count, 1)
        }
    }
    func testAll64SharedOwnershipVectors() throws {
        let rows = try vectors("ownership")
        XCTAssertEqual(rows.count, 64)
        for row in rows {
            let input = row["input"] as! [String: Bool], expected = row["expected"] as! [String: Bool]
            XCTAssertEqual(CameraShootingSettingPolicy.canPublish(sameOwner: input["sameOwner"]!, sameAttempt: input["sameAttempt"]!,
                sameObservation: true, retired: false, cancelled: input["cancelled"]!), expected["canPublishCurrent"])
            XCTAssertEqual(CameraShootingSettingPolicy.canConsumeDraft(sameOwner: input["sameOwner"]!, sameAttempt: input["sameAttempt"]!,
                sameDraftRevision: input["sameDraftRevision"]!, responded: input["responseReceived"]!, matched: input["readbackMatched"]!,
                retired: false, cancelled: input["cancelled"]!), expected["canConsumeDraft"])
        }
    }
    func testAll24SharedIndependentOutcomeVectors() throws {
        let rows = try vectors("outcome")
        XCTAssertEqual(rows.count, 24)
        for row in rows {
            let input = row["input"] as! [String: Any], expected = row["expected"] as! [String: String]
            let attempt = CameraShootingSettingAttempt(key: "iso", requestedValue: "800")
            if input["dispatched"] as! Bool { try attempt.markDispatched() }
            if input["responseReceived"] as! Bool { attempt.responded() }
            switch input["readStatus"] as! String {
            case "unavailable": attempt.readbackUnavailable()
            case "success": attempt.observe(Self.setting(input["readbackMatches"] as! Bool ? "800" : "100"))
            default: break
            }
            XCTAssertEqual(attempt.snapshot.writeEvidence.rawValue, expected["writeEvidence"])
            XCTAssertEqual(attempt.snapshot.readbackEvidence.rawValue, expected["readbackEvidence"])
        }
    }
    func testOneDispatchAndCancellationCannotEraseResponseOrUpgradeUnknownFromRead() throws {
        let attempt = CameraShootingSettingAttempt(key: "iso", requestedValue: "800")
        try attempt.markDispatched()
        XCTAssertThrowsError(try attempt.markDispatched())
        attempt.cancel()
        attempt.responded()
        XCTAssertEqual(attempt.snapshot.writeEvidence, .responded)
        let unknown = CameraShootingSettingAttempt(key: "iso", requestedValue: "800")
        try unknown.markDispatched(); unknown.observe(Self.setting("800"))
        XCTAssertEqual(unknown.snapshot.writeEvidence, .unknown)
        XCTAssertEqual(unknown.snapshot.readbackEvidence, .matched)
    }
    func testStrictJSONRejectsDuplicateTruncatedNonUTF8AndLoneSurrogate() {
        for raw in [#"{"version":1,"version":1}"#, #"{"nested":{"value":"a","value":"b"}}"#, "{", #"{"value":NaN}"#, #"{"value":"\ud800"}"#] {
            XCTAssertThrowsError(try decodeShootingSettingJSONObject(Data(raw.utf8)), raw)
        }
        XCTAssertThrowsError(try decodeShootingSettingJSONObject(Data([0xff, 0xfe, 0x7b, 0x00, 0x7d, 0x00])))
        XCTAssertThrowsError(try decodeShootingSettingJSONObject(Data(repeating: 32, count: 65_537)))
    }
    func testDirectResponseSurvivesReadFailureAndManualRecoveryDoesNotWrite() async throws {
        let (client, peer) = try await directClient()
        await peer.enqueueJSON(path: Self.aggregate, body: Self.iso("100")) // scoped preflight
        await peer.enqueue(method: "PUT", path: Self.isoPath, status: 204, body: Data())
        await peer.enqueue(path: Self.aggregate, status: 503)
        let attempt = CameraShootingSettingAttempt(key: "iso", requestedValue: "800")
        do { try await client.applyShootingSetting(key: "iso", value: "800", attempt: attempt); XCTFail() } catch {}
        XCTAssertEqual(attempt.snapshot.writeEvidence, .responded)
        XCTAssertEqual(attempt.snapshot.readbackEvidence, .unavailable)
        await peer.enqueueJSON(path: Self.aggregate, body: Self.iso("800"))
        try await client.refreshShootingSetting(attempt)
        XCTAssertEqual(attempt.snapshot.writeEvidence, .responded)
        XCTAssertEqual(attempt.snapshot.readbackEvidence, .matched)
        let requests = await peer.requests()
        XCTAssertEqual(requests.filter { $0.method != "GET" }.count, 1)
        XCTAssertFalse(requests.contains { $0.path.contains("storage") || $0.path.contains("/contents") || $0.path.hasSuffix("/status") })
    }
    func testDirectUnknownWriteHasNoAliasFallbackAndMatchingGETCannotProveResponse() async throws {
        let (client, peer) = try await directClient()
        await peer.enqueueJSON(path: Self.aggregate, body: Self.iso("100"))
        await peer.enqueue(method: "PUT", path: Self.isoPath, status: 503)
        let attempt = CameraShootingSettingAttempt(key: "iso", requestedValue: "800")
        do { try await client.applyShootingSetting(key: "iso", value: "800", attempt: attempt); XCTFail() } catch {}
        await peer.enqueueJSON(path: Self.aggregate, body: Self.iso("800"))
        try await client.refreshShootingSetting(attempt)
        XCTAssertEqual(attempt.snapshot.writeEvidence, .unknown)
        XCTAssertEqual(attempt.snapshot.readbackEvidence, .matched)
        let requests = await peer.requests()
        XCTAssertEqual(requests.filter { $0.method != "GET" }.count, 1)
    }
    func testDirectFreshAbilityClosesDispatchWhenValueRemoved() async throws {
        let (client, peer) = try await directClient()
        await peer.enqueueJSON(path: Self.aggregate, body: #"{"iso":{"value":"100","ability":["100","400"]}}"#)
        let attempt = CameraShootingSettingAttempt(key: "iso", requestedValue: "800")
        do { try await client.applyShootingSetting(key: "iso", value: "800", attempt: attempt); XCTFail() } catch {}
        XCTAssertEqual(attempt.snapshot.writeEvidence, .notSent)
        let requests = await peer.requests()
        XCTAssertTrue(requests.allSatisfy { $0.method == "GET" })
    }
    func testDirectStructuredWriteUsesFreshSiblingAndOneRoute() async throws {
        let peer = MockCameraHTTPTransport()
        await peer.enqueueJSON(path: "/ccapi", body: #"{"ver100":[{"path":"/shooting/settings","get":true},{"path":"/shooting/settings/wbshift","put":true}]}"#)
        await peer.enqueueJSON(path: Self.aggregate, body: Self.shift(ba: 0, mg: 0))
        let client = try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: .camera, transport: peer)
        _ = try await client.capabilities()
        await peer.enqueueJSON(path: Self.aggregate, body: Self.shift(ba: 0, mg: -2))
        await peer.enqueue(method: "PUT", path: "/ccapi/ver100/shooting/settings/wbshift", status: 204, body: Data())
        await peer.enqueueJSON(path: Self.aggregate, body: Self.shift(ba: 9, mg: -2))
        let attempt = CameraShootingSettingAttempt(key: "wbshift.ba", requestedValue: "9")
        try await client.applyShootingSetting(key: "wbshift.ba", value: "9", attempt: attempt)
        let requests = await peer.requests()
        let writes = requests.filter { $0.method == "PUT" }
        XCTAssertEqual(writes.count, 1)
        let body = try XCTUnwrap(writes.first?.body)
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Any])
        XCTAssertEqual(object["value"] as? [String: Int], ["ba": 9, "mg": -2])
        XCTAssertEqual(attempt.snapshot.readbackEvidence, .matched)
    }
    func testMalformedStructuredSiblingsFailBeforeAnyWrite() async throws {
        let initial = #"{"stillimagequality":{"value":{"raw":"none","jpeg":"large_fine"},"ability":{"raw":["none","raw"],"jpeg":["none","large_fine"]}}}"#
        let invalid = [
            #"{"value":{"raw":"none","jpeg":3},"ability":{"raw":["none","raw"],"jpeg":["none","large_fine"]}}"#,
            #"{"value":{"raw":"none","jpeg":"unadvertised"},"ability":{"raw":["none","raw"],"jpeg":["none","large_fine"]}}"#,
            #"{"value":{"raw":"none","jpeg":"large_fine","unknown":"x"},"ability":{"raw":["none","raw"],"jpeg":["none","large_fine"],"unknown":["x"]}}"#,
            #"{"value":{"raw":"none","jpeg":"large_fine"},"ability":{"raw":["none","raw"]}}"#,
        ]
        for raw in invalid {
            let peer = MockCameraHTTPTransport()
            await peer.enqueueJSON(path: "/ccapi", body: #"{"ver100":[{"path":"/shooting/settings","get":true},{"path":"/shooting/settings/stillimagequality","put":true}]}"#)
            await peer.enqueueJSON(path: Self.aggregate, body: initial)
            let client = try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: .camera, transport: peer)
            _ = try await client.capabilities()
            await peer.enqueueJSON(path: Self.aggregate, body: "{\"stillimagequality\":\(raw)}")
            let attempt = CameraShootingSettingAttempt(key: "stillimagequality.raw", requestedValue: "raw")
            do { try await client.applyShootingSetting(key: "stillimagequality.raw", value: "raw", attempt: attempt); XCTFail() } catch {}
            XCTAssertEqual(attempt.snapshot.writeEvidence, .notSent)
            let requests = await peer.requests()
            XCTAssertTrue(requests.allSatisfy { $0.method == "GET" })
        }
    }

    func testBridgeUnavailableKeepsResponseAndFreshRecoveryIsOnlyGET() async throws {
        let (client, peer) = try await bridgeClient()
        await peer.enqueueJSON(method: "POST", path: Self.bridgeOutcome, body: Self.bridgeBody("unavailable", setting: "null"))
        let attempt = CameraShootingSettingAttempt(key: "iso", requestedValue: "800")
        try await client.applyShootingSetting(key: "iso", value: "800", attempt: attempt)
        XCTAssertEqual(attempt.snapshot.writeEvidence, .responded)
        XCTAssertEqual(attempt.snapshot.readbackEvidence, .unavailable)
        await peer.enqueueJSON(path: "/v1/session/setting/settings/iso", body: "{\"version\":1,\"engine\":\"ccapi\",\"setting\":\(Self.bridgeSetting)}")
        try await client.refreshShootingSetting(attempt)
        XCTAssertEqual(attempt.snapshot.readbackEvidence, .matched)
        let requests = await peer.requests()
        XCTAssertEqual(requests.filter { $0.path.contains("/settings/") }.map(\.method), ["POST", "GET"])
    }
    func testBridgeUnknownEndpointOrLegacyStatusCannotCauseFallback() async throws {
        for status in [200, 201, 404, 503] {
            let (client, peer) = try await bridgeClient()
            await peer.enqueueJSON(method: "POST", path: Self.bridgeOutcome, status: status, body: #"{"exposure":{"iso":"800"}}"#)
            let attempt = CameraShootingSettingAttempt(key: "iso", requestedValue: "800")
            do { try await client.applyShootingSetting(key: "iso", value: "800", attempt: attempt); XCTFail() } catch {}
            XCTAssertEqual(attempt.snapshot.writeEvidence, .unknown)
            let requests = await peer.requests()
            XCTAssertEqual(requests.filter { $0.path.contains("/settings/") }.map(\.path), [Self.bridgeOutcome])
        }
    }
    private func directClient() async throws -> (CCAPIClient, MockCameraHTTPTransport) {
        let peer = MockCameraHTTPTransport()
        await peer.enqueueJSON(path: "/ccapi", body: #"{"ver100":[{"path":"/shooting/settings","get":true},{"path":"/shooting/settings/iso","put":true}]}"#)
        await peer.enqueueJSON(path: Self.aggregate, body: Self.iso("100"))
        let client = try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: .camera, transport: peer)
        _ = try await client.capabilities()
        return (client, peer)
    }
    private func bridgeClient() async throws -> (DesktopBridgeClient, MockCameraHTTPTransport) {
        let peer = MockCameraHTTPTransport()
        await peer.enqueueJSON(path: "/health", body: #"{"ok":true,"service":"open-eos-control-bridge","version":"0.13.0"}"#)
        await peer.enqueueJSON(method: "POST", path: "/v1/session", body: #"{"id":"setting","engine":"ccapi"}"#)
        let client = try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", transport: peer)
        try await client.initialize()
        return (client, peer)
    }
    private static let aggregate = "/ccapi/ver100/shooting/settings"
    private static let isoPath = aggregate + "/iso"
    private static let bridgeOutcome = "/v1/session/setting/settings/iso/outcome"
    private static let bridgeSetting = #"{"key":"iso","label":"ISO","value":"800","values":["100","800"],"inputKind":"choice"}"#
    private static func bridgeBody(_ read: String, setting: String) -> String {
        "{\"version\":1,\"engine\":\"ccapi\",\"key\":\"iso\",\"requestedValue\":\"800\",\"writeEvidence\":\"response-received\",\"readbackEvidence\":\"\(read)\",\"setting\":\(setting)}"
    }
    private static func iso(_ value: String) -> String { "{\"iso\":{\"value\":\"\(value)\",\"ability\":[\"100\",\"800\"]}}" }
    private static func setting(_ value: String) -> CameraSetting { CameraSetting(key: "iso", label: "ISO", value: value, values: ["100", "800"]) }
    private static func shift(ba: Int, mg: Int) -> String {
        "{\"wbshift\":{\"value\":{\"ba\":\(ba),\"mg\":\(mg)},\"ability\":{\"ba\":{\"min\":-9,\"max\":9,\"step\":1},\"mg\":{\"min\":-9,\"max\":9,\"step\":1}}}}"
    }

    func testCardFreshUnknownAbilityBlocksBothKeysBeforeAnyPut() async throws {
        for card in Self.cardSelectionCases {
            try await assertCardPrewriteRejection(card,
                fresh: #"{"value":"card1","ability":["card1","card2","card3"]}"#,
                marker: "CARD-FRESH-UNKNOWN-NOT-SENT")
        }
    }

    func testCardFreshSingleChoiceBlocksBothKeysBeforeAnyPut() async throws {
        for card in Self.cardSelectionCases {
            try await assertCardPrewriteRejection(card,
                fresh: #"{"value":"card2","ability":["card2"]}"#,
                marker: "CARD-FRESH-SINGLE-NOT-SENT")
        }
    }

    func testCardAcknowledgedInvalidReadbackStaysUnavailableForBothKeys() async throws {
        for card in Self.cardSelectionCases {
            let (client, peer, capabilities) = try await cardSelectionClient(card)
            await enqueueCardWrite(peer, card: card, readback: Self.cardUnknownReadback)
            let attempt = CameraShootingSettingAttempt(key: card.key, requestedValue: "card2")
            let failure = await cardApplyError(client, card: card, requested: "card2", attempt: attempt)
            let outcome = attempt.snapshot
            XCTAssertEqual(outcome.writeEvidence, .responded)
            XCTAssertEqual(outcome.readbackEvidence, .unavailable, "CARD-ACK-INVALID-UNAVAILABLE:\(card.key)")
            XCTAssertNil(outcome.observedSetting)
            XCTAssertEqual(failure as? CCAPIError, .invalidResponse("Camera returned an invalid current setting value or ability."))
            // Exercise the production publication helper, not a substitute UI policy.
            let published = outcome.observedSetting.map { capabilities.applyingShootingSetting($0) } ?? capabilities
            XCTAssertEqual(published.setting(card.key)?.values, ["none", "card1", "card2"])
            XCTAssertFalse(published.setting(card.key)?.accepts("card3") ?? true, "CARD-ACK-NO-CARD3-PUBLICATION:\(card.key)")
            await assertCardRequests(peer, card: card, actions: ["GET", "PUT", "GET"])
            try await assertCardWriteBody(peer, value: "card2")
            let remaining = await peer.remainingResponses()
            XCTAssertEqual(remaining, 0)
        }
    }

    func testCardLostAckInvalidManualReadStaysUnknownForBothKeys() async throws {
        for card in Self.cardSelectionCases {
            let (client, peer, capabilities) = try await cardSelectionClient(card, loseAcknowledgement: true)
            await enqueueCardWrite(peer, card: card)
            let attempt = CameraShootingSettingAttempt(key: card.key, requestedValue: "card2")
            let failure = await cardApplyError(client, card: card, requested: "card2", attempt: attempt)
            assertCardLostAck(failure, attempt: attempt)
            await assertCardRequests(peer, card: card, actions: ["GET", "PUT"])
            await peer.enqueueJSON(path: card.path, body: Self.cardUnknownReadback)
            let readFailure = await cardRefreshError(client, attempt: attempt)
            let outcome = attempt.snapshot
            XCTAssertEqual(outcome.writeEvidence, .unknown)
            XCTAssertEqual(outcome.readbackEvidence, .unavailable, "CARD-UNKNOWN-INVALID-UNAVAILABLE:\(card.key)")
            XCTAssertNil(outcome.observedSetting)
            XCTAssertEqual(readFailure as? CCAPIError, .invalidResponse("Camera returned an invalid current setting value or ability."))
            let published = outcome.observedSetting.map { capabilities.applyingShootingSetting($0) } ?? capabilities
            XCTAssertFalse(published.setting(card.key)?.accepts("card3") ?? true)
            await assertCardRequests(peer, card: card, actions: ["GET", "PUT", "GET"])
            try await assertCardWriteBody(peer, value: "card2")
            let remaining = await peer.remainingResponses()
            XCTAssertEqual(remaining, 0)
        }
    }

    func testCardStillValidRawValueUsesExactlyOnePut() async throws {
        let card = Self.cardSelectionCases[0]
        let (client, peer, _) = try await cardSelectionClient(card)
        await enqueueCardWrite(peer, card: card, readback: Self.cardBody("card2"))
        let attempt = CameraShootingSettingAttempt(key: card.key, requestedValue: "card2")
        try await client.applyShootingSetting(key: card.key, value: "card2", attempt: attempt)
        XCTAssertEqual(attempt.snapshot.writeEvidence, .responded)
        XCTAssertEqual(attempt.snapshot.readbackEvidence, .matched)
        XCTAssertEqual(attempt.snapshot.observedSetting?.value, "card2")
        await assertCardRequests(peer, card: card, actions: ["GET", "PUT", "GET"])
        try await assertCardWriteBody(peer, value: "card2")
        let remaining = await peer.remainingResponses()
        XCTAssertEqual(remaining, 0)
    }

    func testCardMovieNoneRawValueUsesExactlyOnePut() async throws {
        let card = Self.cardSelectionCases[1]
        let (client, peer, _) = try await cardSelectionClient(card)
        await enqueueCardWrite(peer, card: card, readback: Self.cardBody("none"))
        let attempt = CameraShootingSettingAttempt(key: card.key, requestedValue: "none")
        try await client.applyShootingSetting(key: card.key, value: "none", attempt: attempt)
        XCTAssertEqual(attempt.snapshot.writeEvidence, .responded)
        XCTAssertEqual(attempt.snapshot.readbackEvidence, .matched)
        XCTAssertEqual(attempt.snapshot.observedSetting?.value, "none")
        await assertCardRequests(peer, card: card, actions: ["GET", "PUT", "GET"])
        try await assertCardWriteBody(peer, value: "none")
        let remaining = await peer.remainingResponses()
        XCTAssertEqual(remaining, 0)
    }

    func testCardLostAckValidManualReadNeverProvesResponseOrReplays() async throws {
        for card in Self.cardSelectionCases {
            let (client, peer, _) = try await cardSelectionClient(card, loseAcknowledgement: true)
            await enqueueCardWrite(peer, card: card)
            let attempt = CameraShootingSettingAttempt(key: card.key, requestedValue: "card2")
            let failure = await cardApplyError(client, card: card, requested: "card2", attempt: attempt)
            assertCardLostAck(failure, attempt: attempt)
            await assertCardRequests(peer, card: card, actions: ["GET", "PUT"])
            await peer.enqueueJSON(path: card.path, body: Self.cardBody("card2"))
            try await client.refreshShootingSetting(attempt)
            XCTAssertEqual(attempt.snapshot.writeEvidence, .unknown)
            XCTAssertEqual(attempt.snapshot.readbackEvidence, .matched)
            XCTAssertEqual(attempt.snapshot.observedSetting?.value, "card2")
            await assertCardRequests(peer, card: card, actions: ["GET", "PUT", "GET"])
            try await assertCardWriteBody(peer, value: "card2")
            let remaining = await peer.remainingResponses()
            XCTAssertEqual(remaining, 0)
        }
    }

    func testCardMalformedRawTypesDuplicatesAndMissingCurrentBlockBothKeys() async throws {
        let malformed = [
            #"{"value":2,"ability":["card1","card2"]}"#,
            #"{"value":"card1","ability":["card1",2,"card2"]}"#,
            #"{"value":"card1","ability":["card1","card2","card2"]}"#,
            #"{"value":"none","ability":["card1","card2"]}"#,
            #"{"ability":["card1","card2"]}"#,
        ]
        for card in Self.cardSelectionCases {
            for fresh in malformed {
                try await assertCardPrewriteRejection(card, fresh: fresh, marker: "CARD-MALFORMED-NOT-SENT")
            }
        }
    }

    private struct CardSelectionCase {
        let key: String
        let suffix: String
        var path: String { "/ccapi/ver100" + suffix }
        var setup: [String] { ["GET /ccapi", "GET " + ShootingSettingEvidenceTests.aggregate, "GET " + path] }
    }
    private static let cardSelectionCases = [
        CardSelectionCase(key: "cardselectionstillimage", suffix: "/functions/cardselection/stillimage"),
        CardSelectionCase(key: "cardselectionmovie", suffix: "/functions/cardselection/movie"),
    ]
    private static let cardUnknownReadback = #"{"value":"card2","ability":["card1","card2","card3"]}"#
    private static func cardBody(_ value: String) -> String {
        "{\"value\":\"\(value)\",\"ability\":[\"none\",\"card1\",\"card2\"]}"
    }
    private func cardSelectionClient(_ card: CardSelectionCase, loseAcknowledgement: Bool = false) async throws
        -> (CCAPIClient, MockCameraHTTPTransport, CameraCapabilities) {
        let peer = MockCameraHTTPTransport()
        await peer.enqueueJSON(path: "/ccapi", body:
            "{\"ver100\":[{\"path\":\"/shooting/settings\",\"get\":true},{\"path\":\"\(card.suffix)\",\"get\":true,\"put\":true}]}")
        await peer.enqueueJSON(path: Self.aggregate, body: "{}")
        await peer.enqueueJSON(path: card.path, body: Self.cardBody("card1"))
        let transport: any CameraHTTPTransport
        if loseAcknowledgement { transport = CardSelectionLostACKTransport(peer: peer) }
        else { transport = peer }
        let client = try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: .camera, transport: transport)
        let capabilities = try await client.capabilities()
        XCTAssertEqual(capabilities.setting(card.key)?.values, ["none", "card1", "card2"])
        XCTAssertEqual(capabilities.setting(card.key)?.value, "card1")
        await assertCardRequests(peer, card: card, actions: [])
        return (client, peer, capabilities)
    }
    private func enqueueCardWrite(_ peer: MockCameraHTTPTransport, card: CardSelectionCase, readback: String? = nil) async {
        await peer.enqueueJSON(path: card.path, body: Self.cardBody("card1"))
        await peer.enqueue(method: "PUT", path: card.path, status: 204, body: Data())
        if let readback { await peer.enqueueJSON(path: card.path, body: readback) }
    }
    private func cardApplyError(_ client: CCAPIClient, card: CardSelectionCase, requested: String,
                                attempt: CameraShootingSettingAttempt) async -> Error? {
        do {
            try await client.applyShootingSetting(key: card.key, value: requested, attempt: attempt)
            return nil
        } catch { return error } // Every caller checks the exact error; missing/unexpected stubs cannot pass.
    }
    private func cardRefreshError(_ client: CCAPIClient, attempt: CameraShootingSettingAttempt) async -> Error? {
        do { try await client.refreshShootingSetting(attempt); return nil } catch { return error }
    }
    private func assertCardPrewriteRejection(_ card: CardSelectionCase, fresh: String, marker: String) async throws {
        let (client, peer, _) = try await cardSelectionClient(card)
        await peer.enqueueJSON(path: card.path, body: fresh)
        // Provision all unfixed-path responses so baseline failure is the assertion, never a fixture error.
        await peer.enqueue(method: "PUT", path: card.path, status: 204, body: Data())
        await peer.enqueueJSON(path: card.path, body: Self.cardBody("card2"))
        let attempt = CameraShootingSettingAttempt(key: card.key, requestedValue: "card2")
        let failure = await cardApplyError(client, card: card, requested: "card2", attempt: attempt)
        XCTAssertEqual(attempt.snapshot.writeEvidence, .notSent, "\(marker):\(card.key)")
        XCTAssertNil(attempt.snapshot.observedSetting)
        XCTAssertEqual(failure as? CCAPIError, .invalidSetting(key: card.key, value: "card2"))
        await assertCardRequests(peer, card: card, actions: ["GET"])
        let requests = await peer.requests()
        XCTAssertEqual(requests.filter { $0.method == "PUT" }.count, 0)
        let remaining = await peer.remainingResponses()
        XCTAssertEqual(remaining, 2)
    }
    private func assertCardLostAck(_ failure: Error?, attempt: CameraShootingSettingAttempt) {
        XCTAssertEqual((failure as? URLError)?.code, .networkConnectionLost)
        XCTAssertEqual(attempt.snapshot.writeEvidence, .unknown)
        XCTAssertEqual(attempt.snapshot.readbackEvidence, .unavailable)
        XCTAssertNil(attempt.snapshot.observedSetting)
    }
    private func assertCardRequests(_ peer: MockCameraHTTPTransport, card: CardSelectionCase, actions: [String]) async {
        let requests = await peer.requests()
        XCTAssertEqual(requests.map { "\($0.method) \($0.path)" }, card.setup + actions.map { "\($0) \(card.path)" })
        XCTAssertTrue(requests.filter { $0.method == "GET" }.allSatisfy { $0.body == nil || $0.body?.isEmpty == true })
    }
    private func assertCardWriteBody(_ peer: MockCameraHTTPTransport, value: String) async throws {
        let requests = await peer.requests()
        let writes = requests.filter { $0.method == "PUT" }
        XCTAssertEqual(writes.count, 1)
        let body = try XCTUnwrap(writes.first?.body)
        XCTAssertEqual(try JSONSerialization.jsonObject(with: body) as? [String: String], ["value": value])
    }
}

/// Only loses a recorded synthetic PUT response. It is not an explicit camera rejection and opens no socket.
private struct CardSelectionLostACKTransport: CameraHTTPTransport {
    let peer: MockCameraHTTPTransport
    func send(_ request: URLRequest) async throws -> CameraHTTPResponse { try await peer.send(request) }
    func sendShootingSetting(_ request: URLRequest) async throws -> CameraHTTPResponse {
        let response = try await peer.send(request)
        if request.httpMethod == "PUT" { throw URLError(.networkConnectionLost) }
        return response
    }
    func download(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse { try await peer.download(request) }
}
