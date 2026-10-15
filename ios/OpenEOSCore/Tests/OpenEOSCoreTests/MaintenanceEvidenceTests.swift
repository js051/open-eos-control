import Foundation
import XCTest
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
@testable import OpenEOSCore

final class MaintenanceEvidenceTests: XCTestCase {
    private func vectors(_ section: String) throws -> [[String: Any]] {
        var root = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { root.deleteLastPathComponent() }
        let data = try Data(contentsOf: root.appendingPathComponent("contracts/maintenance-lifecycle/v1/maintenance-vectors.json"))
        return try XCTUnwrap((JSONSerialization.jsonObject(with: data) as? [String: Any])?[section] as? [[String: Any]])
    }
    func testAll32SharedClockVectors() throws {
        let rows = try vectors("clock"); XCTAssertEqual(rows.count, 32)
        for row in rows {
            let reference = CameraMaintenanceWire.reference(row["reference"])
            let sample = CameraMaintenanceWire.clock(row["observation"])
            XCTAssertEqual(reference != nil && sample != nil, row["valid"] as? Bool, row["name"] as! String)
            XCTAssertEqual(reference.flatMap { reference in sample.map { reference.matches($0) } } ?? false,
                           row["matched"] as? Bool, row["name"] as! String)
        }
    }
    func testAll28SharedWireVectorsThroughActualBridgeClient() async throws {
        let rows = try vectors("wire"); XCTAssertEqual(rows.count, 28)
        for row in rows {
            let transport = MockCameraHTTPTransport()
            let client = try await bridge(transport)
            let operation = CameraMaintenanceOperation(rawValue: row["operation"] as! String)!
            let attempt = CameraMaintenanceAttempt(operation: operation, autoPowerOff: row["autoPowerOff"] as! Bool)
            await transport.enqueue(method: "POST", path: "/v1/session/maintenance/maintenance/\(operation.rawValue)/outcome",
                status: row["status"] as! Int, body: try JSONSerialization.data(withJSONObject: row["response"]!))
            do { try await client.performMaintenance(attempt) } catch {}
            XCTAssertEqual(attempt.snapshot.commandEvidence == .responded, row["expectedResponse"] as? Bool, row["name"] as! String)
            let actualRead = attempt.snapshot.clockReadEvidence == .notRead ? "unavailable" : attempt.snapshot.clockReadEvidence.rawValue
            XCTAssertEqual(actualRead, row["expectedClockRead"] as? String, row["name"] as! String)
            let requests = await transport.requests()
            XCTAssertEqual(requests.filter { $0.path.contains("/maintenance/") && $0.method == "POST" }.count, 1)
            XCTAssertFalse(requests.contains { $0.path.hasSuffix("/clock/sync") || $0.path.hasSuffix("/power/sleep") })
        }
    }
    func testAll70SharedOwnershipVectors() throws {
        let rows = try vectors("ownership"); XCTAssertEqual(rows.count, 70)
        for row in rows {
            let input = row["input"] as! [String: Any]
            func flag(_ key: String) -> Bool { input[key] as! Bool }
            XCTAssertEqual(CameraMaintenancePolicy.canPublish(sameOwner: flag("sameOwner"), sameAttempt: flag("sameAttempt"),
                retired: flag("retired"), cancelled: flag("cancelled")), row["canPublish"] as? Bool)
            XCTAssertEqual(CameraMaintenancePolicy.canRestore(sameOwner: flag("sameOwner"), sameAttempt: flag("sameAttempt"),
                cancelled: flag("cancelled"), retired: flag("retired"), responseReceived: flag("responseReceived"),
                stopConfirmed: flag("stopConfirmed"), operation: CameraMaintenanceOperation(rawValue: input["operation"] as! String)!,
                autoPowerOff: flag("autoPowerOff"), wasLive: flag("wasLive"), freshReachable: flag("freshReachable"),
                safetyClear: flag("safetyClear")), row["canRestore"] as? Bool)
        }
    }
    func testCancellationRetainsLateResponseButDisallowsSecondSend() throws {
        let attempt = CameraMaintenanceAttempt(operation: .clockSync)
        try attempt.markDispatched(); attempt.cancel(); attempt.responded()
        XCTAssertEqual(attempt.snapshot.commandEvidence, .responded)
        XCTAssertThrowsError(try attempt.markDispatched())
        let cancelledBefore = CameraMaintenanceAttempt(operation: .sleep)
        cancelledBefore.cancel(); XCTAssertThrowsError(try cancelledBefore.markDispatched())
        XCTAssertEqual(cancelledBefore.snapshot.commandEvidence, .notSent)
    }
    func testLaterClockReadCannotRewriteInitialProofOrUpgradeUnknownCommand() throws {
        let attempt = CameraMaintenanceAttempt(operation: .clockSync)
        try attempt.markDispatched(); attempt.responded()
        attempt.reference(.init(earliestEpochSeconds: 990, latestEpochSeconds: 1010, utcOffsetMinutes: 480, daylightSaving: false))
        attempt.observe(.init(epochSeconds: 1000, utcOffsetMinutes: 480, daylightSaving: false), verifying: true)
        attempt.observe(.init(epochSeconds: 5000, utcOffsetMinutes: nil, daylightSaving: nil))
        attempt.observationUnavailable()
        XCTAssertEqual(attempt.snapshot.clockReadEvidence, .matched)
        XCTAssertEqual(attempt.snapshot.verifiedClock?.epochSeconds, 1000)
        XCTAssertEqual(attempt.snapshot.observedClock?.epochSeconds, 5000)
        XCTAssertEqual(attempt.snapshot.observationEvidence, .unavailable)
        let unknown = CameraMaintenanceAttempt(operation: .clockSync)
        try unknown.markDispatched(); unknown.observe(.init(epochSeconds: 1000, utcOffsetMinutes: nil, daylightSaving: nil))
        XCTAssertEqual(unknown.snapshot.commandEvidence, .unknown)
        XCTAssertEqual(unknown.snapshot.clockReadEvidence, .notRead)
    }
    func testDirectClockResponseSurvivesRequiredReadFailureAndRecoverySendsOnlyGet() async throws {
        let transport = MockCameraHTTPTransport()
        let client = try CCAPIClient(baseURL: "http://192.0.2.1:8080", mode: .camera, transport: transport)
        let date = Date(), zone = TimeZone.current
        let body = try JSONSerialization.data(withJSONObject: ["datetime": CameraMaintenanceClock.formatter(timeZone: zone).string(from: date),
                                                             "dst": zone.isDaylightSavingTime(for: date)])
        await transport.enqueueJSON(path: "/ccapi", body: #"{"ver100":[{"path":"/functions/datetime","get":true,"put":true}]}"#)
        await transport.enqueue(method: "PUT", path: "/ccapi/ver100/functions/datetime", body: body)
        await transport.enqueue(path: "/ccapi/ver100/functions/datetime", status: 503)
        try await client.initialize()
        let attempt = CameraMaintenanceAttempt(operation: .clockSync)
        do { try await client.performMaintenance(attempt); XCTFail("Read failure must remain visible") } catch {}
        XCTAssertEqual(attempt.snapshot.commandEvidence, .responded)
        XCTAssertEqual(attempt.snapshot.clockReadEvidence, .unavailable)
        await transport.enqueue(path: "/ccapi/ver100/functions/datetime", body: body)
        try await client.observeMaintenance(attempt)
        let requests = await transport.requests()
        XCTAssertEqual(requests.map(\.method), ["GET", "PUT", "GET", "GET"])
        XCTAssertEqual(attempt.snapshot.clockReadEvidence, .unavailable, "Later read is separate from initial verification")
        XCTAssertEqual(attempt.snapshot.observationEvidence, .observed)
    }
    func testDirectClockValidatesZoneDSTAndInclusiveTenSecondWindow() throws {
        let sample = CameraMaintenanceClock.parseCanon(["datetime": "Tue, 14 Nov 2023 22:13:20 +0000", "dst": false])
        XCTAssertEqual(sample?.epochSeconds, 1_700_000_000)
        XCTAssertNil(CameraMaintenanceClock.parseCanon(["datetime": "Tue, 14 Nov 2023 22:13:20 +0000\n", "dst": false]))
        XCTAssertNil(CameraMaintenanceClock.parseCanon(["datetime": "Tue, 14 Nov 2023 22:13:20 +0000", "dst": 0]))
        XCTAssertNil(CameraMaintenanceClock.parseCanon(["datetime": "Tue, 31 Feb 2023 22:13:20 +0000", "dst": false]))
    }
    func testBridgeObservationIsOneGetAndDoesNotPromoteUnknownCommand() async throws {
        let transport = MockCameraHTTPTransport(), client = try await bridge(transport)
        let attempt = CameraMaintenanceAttempt(operation: .sensorCleaning)
        try attempt.markDispatched()
        await transport.enqueueJSON(path: "/v1/session/maintenance/maintenance/sensor-cleaning",
            body: #"{"version":1,"engine":"ccapi","operation":"sensor-cleaning","observationEvidence":"reachable","clockObservation":null}"#)
        try await client.observeMaintenance(attempt)
        let requests = await transport.requests()
        XCTAssertEqual(requests.last?.method, "GET")
        XCTAssertEqual(attempt.snapshot.commandEvidence, .unknown)
        XCTAssertEqual(attempt.snapshot.observationEvidence, .reachable)
    }
    func testInvalidFramingOrDuplicateKeysNeverBecomesCommandProof() async throws {
        for body in [#"{"version":1,"version":1,"engine":"ccapi","operation":"sleep","autoPowerOff":false,"responseEvidence":"response-received"}"#,
                     String(repeating: " ", count: 65_537)] {
            let transport = MockCameraHTTPTransport(), client = try await bridge(transport)
            let attempt = CameraMaintenanceAttempt(operation: .sleep)
            await transport.enqueueJSON(method: "POST", path: "/v1/session/maintenance/maintenance/sleep/outcome", body: body)
            do { try await client.performMaintenance(attempt); XCTFail("Malformed response must fail") } catch {}
            XCTAssertEqual(attempt.snapshot.commandEvidence, .unknown)
        }
    }
    func testLexicalFloatingNumbersAndUnsignedOverflowCannotBecomeIntegerProof() throws {
        for token in ["1.0", "1e0", "18446744073709551615"] {
            let body = try decodeMaintenanceJSONObject(Data(("{\"version\":" + token + ",\"engine\":\"ccapi\",\"operation\":\"sleep\",\"autoPowerOff\":false,\"responseEvidence\":\"response-received\"}").utf8))
            let attempt = CameraMaintenanceAttempt(operation: .sleep); try attempt.markDispatched()
            XCTAssertFalse(CameraMaintenanceWire.outcome(body, engine: "ccapi", attempt: attempt))
            XCTAssertEqual(attempt.snapshot.commandEvidence, .unknown)
        }
        let value = try decodeMaintenanceJSONObject(Data(#"{"epochSeconds":1700000000.0,"utcOffsetMinutes":480,"daylightSaving":false}"#.utf8))
        XCTAssertNil(CameraMaintenanceWire.clock(value))
    }

    private func bridge(_ transport: MockCameraHTTPTransport) async throws -> DesktopBridgeClient {
        await transport.enqueueJSON(path: "/health", body: #"{"ok":true,"service":"open-eos-control-bridge","version":"0.13.0"}"#)
        await transport.enqueueJSON(method: "POST", path: "/v1/session", body: #"{"id":"maintenance","engine":"ccapi"}"#)
        let client = try DesktopBridgeClient(baseURL: "http://192.0.2.1:18181", transport: transport)
        try await client.initialize(); return client
    }
}
