import Foundation
import XCTest
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
@testable import OpenEOSCore

final class CaptureConfigurationEvidenceTests: XCTestCase {
    private func vectors(_ section: String, file: String = "capture-configuration-vectors.json") throws -> [[String: Any]] {
        var root = URL(fileURLWithPath: #filePath)
        for _ in 0..<5 { root.deleteLastPathComponent() }
        let data = try Data(contentsOf: root.appendingPathComponent("contracts/capture-configuration/v1/" + file))
        return try XCTUnwrap((JSONSerialization.jsonObject(with: data) as? [String: Any])?[section] as? [[String: Any]])
    }
    func testAll49SharedBridgeReceiptVectors() throws {
        let cases = try vectors("receipt")
        XCTAssertEqual(cases.count, 49)
        for row in cases {
            let input = try XCTUnwrap(row["input"] as? [String: Any])
            let operation = try XCTUnwrap(CameraCaptureConfigurationOperation(rawValue: input["operation"] as! String))
            let field = (input["field"] as? String).flatMap(CameraFileNamingField.init(rawValue:))
            let attempt = CameraCaptureConfigurationAttempt(operation: operation, requestedValue: input["requestedValue"] as! String, field: field)
            let detail: [String: Any] = ["code": input["code"]!, "engine": input["engine"]!, "feature": input["feature"]!,
                                         "captureConfigReceipt": input["receipt"]!]
            let result = CameraCaptureConfigurationReceipt.validate(status: input["status"] as! Int, detail: detail, invocation: attempt.snapshot)
            XCTAssertEqual(result != nil, row["valid"] as? Bool, row["id"] as! String)
        }
    }
    func testAll68SharedExactReceiptVectors() throws {
        let cases = try vectors("receipt", file: "exact-value-vectors.json")
        XCTAssertEqual(cases.count, 68)
        for row in cases {
            let input = row["input"] as! [String: Any]
            let attempt = CameraCaptureConfigurationAttempt(
                operation: CameraCaptureConfigurationOperation(rawValue: input["operation"] as! String)!,
                requestedValue: input["requestedValue"] as! String,
                field: (input["field"] as? String).flatMap(CameraFileNamingField.init(rawValue:)))
            let detail: [String: Any] = ["code": input["code"]!, "engine": input["engine"]!, "feature": input["feature"]!,
                                         "captureConfigReceipt": input["receipt"]!]
            XCTAssertEqual(CameraCaptureConfigurationReceipt.validate(status: input["status"] as! Int,
                detail: detail, invocation: attempt.snapshot) != nil, row["valid"] as? Bool, row["id"] as! String)
        }
    }

    func testAll13SharedExactNamingVectorsThroughFreshBridgeAndCanonReads() async throws {
        let cases = try vectors("naming", file: "exact-value-vectors.json")
        XCTAssertEqual(cases.count, 13)
        for row in cases {
            let value = row["value"] as! [String: Any]
            for bridge in [false, true] {
                let attempt = CameraCaptureConfigurationAttempt(operation: .setFileNaming, requestedValue: "IMG_", field: .stillUserSetting1)
                var valid = false
                if bridge {
                    let (client, transport) = try await bridgeClient()
                    await transport.enqueue(path: "/v1/session/config/capture-configuration/set-file-naming",
                        body: try JSONSerialization.data(withJSONObject: ["operation": "set-file-naming", "fileNaming": value]))
                    do { try await client.refreshCaptureConfiguration(attempt); valid = true } catch {}
                } else {
                    let (client, transport) = try await namingClient()
                    await Self.enqueueCanonicalNamingValue(transport, value: value)
                    do { try await client.refreshCaptureConfiguration(attempt); valid = true } catch {}
                }
                XCTAssertEqual(valid, row["valid"] as? Bool, "\(row["id"]!) bridge=\(bridge)")
                XCTAssertEqual(attempt.snapshot.writeEvidence, .notSent)
            }
        }
    }

    func testAll7SharedExactDirectorySelectionVectorsThroughBridgeAndCanonReads() async throws {
        let cases = try vectors("directorySelection", file: "exact-value-vectors.json")
        XCTAssertEqual(cases.count, 7)
        for row in cases {
            for bridge in [false, true] {
                let attempt = CameraCaptureConfigurationAttempt(operation: .createDirectory, requestedValue: "ABCDE")
                var valid = false
                if bridge {
                    let (client, transport) = try await bridgeClient()
                    await transport.enqueue(path: "/v1/session/config/capture-configuration/create-directory",
                        body: try JSONSerialization.data(withJSONObject: ["operation": "create-directory", "directorySelection": row["value"]!]))
                    do { try await client.refreshCaptureConfiguration(attempt); valid = true } catch {}
                } else {
                    let transport = MockCameraHTTPTransport()
                    await transport.enqueueJSON(path: "/ccapi", body: Self.directoryDiscovery)
                    await transport.enqueue(path: Self.directoryPath, body: try JSONSerialization.data(withJSONObject: row["value"]!))
                    let client = try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: .camera, transport: transport)
                    do { try await client.refreshCaptureConfiguration(attempt); valid = true } catch {}
                }
                XCTAssertEqual(valid, row["valid"] as? Bool, "\(row["id"]!) bridge=\(bridge)")
                XCTAssertEqual(attempt.snapshot.writeEvidence, .notSent)
            }
        }
    }

    func testAll48SharedIndependentEvidenceVectors() throws {
        let cases = try vectors("outcome")
        XCTAssertEqual(cases.count, 48)
        for row in cases {
            let input = row["input"] as! [String: Any]
            let operation = CameraCaptureConfigurationOperation(rawValue: input["operation"] as! String)!
            let attempt = CameraCaptureConfigurationAttempt(operation: operation,
                requestedValue: operation == .createDirectory ? "ABCDE" : "NEW_",
                field: operation == .setFileNaming ? .stillUserSetting1 : nil)
            if input["dispatched"] as! Bool { try attempt.markDispatched() }
            if input["validAcknowledgment"] as! Bool { attempt.acknowledge(attempt.snapshot.requestedValue) }
            switch input["readStatus"] as! String {
            case "unavailable": attempt.readbackUnavailable()
            case "success":
                if operation == .createDirectory {
                    attempt.observe(CameraSetting(key: "directoryselection", label: "Directory", value: "101ABCDE", values: ["101ABCDE"]))
                } else { attempt.observe(Self.naming(prefix: input["readbackMatches"] as! Bool ? "NEW_" : "OLD_")) }
            default: break
            }
            let expected = row["expected"] as! [String: String]
            XCTAssertEqual(attempt.snapshot.writeEvidence.rawValue, expected["writeEvidence"], row["id"] as! String)
            XCTAssertEqual(attempt.snapshot.readbackEvidence.rawValue, expected["readbackEvidence"], row["id"] as! String)
        }
    }
    func testAll128SharedOwnershipVectors() throws {
        let cases = try vectors("ownership")
        XCTAssertEqual(cases.count, 128)
        for row in cases {
            let input = row["input"] as! [String: Bool]
            let expected = row["expected"] as! [String: Bool]
            XCTAssertEqual(CameraCaptureConfigurationPolicy.canPublish(sameOwner: input["sameOwner"]!,
                sameAttempt: input["sameAttempt"]!, retired: input["retired"]!), expected["canPublishCurrent"], row["id"] as! String)
            XCTAssertEqual(CameraCaptureConfigurationPolicy.canConsumeDraft(sameOwner: input["sameOwner"]!,
                sameAttempt: input["sameAttempt"]!, retired: input["retired"]!, sameDraftRevision: input["sameDraftRevision"]!,
                acknowledged: input["validAcknowledgment"]!, matched: input["readbackMatched"]!, cancelled: input["cancelled"]!),
                expected["canConsumeDraft"], row["id"] as! String)
        }
    }
    func testAll16SharedReadOnlyRecoveryVectors() throws {
        let cases = try vectors("recovery")
        XCTAssertEqual(cases.count, 16)
        for row in cases {
            let input = row["input"] as! [String: Bool]
            XCTAssertEqual(CameraCaptureConfigurationPolicy.canRecover(sameOwner: input["sameOwner"]!,
                writeActive: input["writeActive"]!, readActive: input["readActive"]!, hasRecord: input["hasRecord"]!),
                row["allowed"] as? Bool, row["id"] as! String)
            XCTAssertEqual(row["writes"] as? Int, 0)
        }
    }
    func testCaptureValuesRejectTerminalNewlineAndNonASCIIWithoutWeakeningRangeRules() {
        for value in ["ABCDE\n", "ABCDE\r\n", "ABCDE\u{2028}", "ＡBCDE", "ABCDE ", "ABCDE\0"] {
            XCTAssertFalse(CameraCaptureConfigurationValue.directorySuffix(value), value)
            XCTAssertFalse(CameraCaptureConfigurationValue.directorySuffix(value, allowEmpty: true), value)
            XCTAssertFalse(CameraCaptureConfigurationValue.directorySelection("100" + value), value)
        }
        let naming = Self.naming(prefix: "NEW_")
        for field in CameraFileNamingField.allCases {
            let original = naming.value(for: field)
            XCTAssertTrue(naming.accepts(field, value: original))
            XCTAssertFalse(naming.accepts(field, value: original + "\n"))
            XCTAssertFalse(naming.accepts(field, value: original + "\r\n"))
        }
    }

    func testCancellationBlocksDispatchButCannotEraseValidLateAcknowledgment() throws {
        let before = CameraCaptureConfigurationAttempt(operation: .createDirectory, requestedValue: "ABCDE")
        before.cancel()
        XCTAssertThrowsError(try before.markDispatched())
        before.acknowledge("ABCDE")
        XCTAssertEqual(before.snapshot.writeEvidence, .notSent)
        let after = CameraCaptureConfigurationAttempt(operation: .createDirectory, requestedValue: "ABCDE")
        try after.markDispatched(); after.cancel(); after.acknowledge("OTHER")
        XCTAssertEqual(after.snapshot.writeEvidence, .acknowledged)
        XCTAssertEqual(after.snapshot.acknowledgedValue, "OTHER")
        XCTAssertThrowsError(try after.markDispatched())
    }

    func testDirectDirectoryACKSurvivesUnavailableReadbackAndRecoverySendsOneGET() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(path: "/ccapi", body: Self.directoryDiscovery)
        await transport.enqueueJSON(path: "/ccapi/ver100/shooting/settings", body: "{}")
        await transport.enqueueJSON(path: Self.directoryPath, body: Self.selection)
        let client = try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: .camera, transport: transport)
        _ = try await client.capabilities()
        await transport.enqueueJSON(method: "POST", path: Self.createPath, body: #"{"directoryname":"OTHER"}"#)
        await transport.enqueueJSON(path: Self.directoryPath, status: 503, body: "{}")
        let attempt = CameraCaptureConfigurationAttempt(operation: .createDirectory, requestedValue: "ABCDE")
        do { _ = try await client.createDirectory(name: "ABCDE", attempt: attempt); XCTFail("Expected unavailable readback") } catch {}
        XCTAssertEqual(attempt.snapshot.writeEvidence, .acknowledged)
        XCTAssertEqual(attempt.snapshot.acknowledgedValue, "OTHER")
        XCTAssertEqual(attempt.snapshot.readbackEvidence, .unavailable)
        let before = await transport.requests().count
        await transport.enqueueJSON(path: Self.directoryPath, body: Self.selection)
        try await client.refreshCaptureConfiguration(attempt)
        let requests = await transport.requests()
        XCTAssertEqual(Array(requests.dropFirst(before)).map(\.method), ["GET"])
        XCTAssertEqual(Array(requests.dropFirst(before)).map(\.path), [Self.directoryPath])
        XCTAssertEqual(requests.filter { $0.method == "POST" }.count, 1)
        XCTAssertEqual(attempt.snapshot.readbackEvidence, .refreshed)
        XCTAssertEqual(attempt.snapshot.acknowledgedValue, "OTHER", "Current selection cannot reinterpret the suffix ACK")
    }

    func testDirectoryAggregateSettingCannotSubstituteForFailedCanonicalReadback() async throws {
        let transport = MockCameraHTTPTransport()
        let discovery = Self.directoryDiscovery.replacingOccurrences(of: "{\"path\":\"/shooting/settings\",\"get\":true}",
            with: "{\"path\":\"/shooting/settings\",\"get\":true},{\"path\":\"/shooting/settings/directoryselection\",\"put\":true}")
        await transport.enqueueJSON(path: "/ccapi", body: discovery)
        await transport.enqueueJSON(path: "/ccapi/ver100/shooting/settings", body: #"{"directoryselection":{"value":"100EOSXX","ability":["100EOSXX"]}}"#)
        await transport.enqueueJSON(path: Self.directoryPath, body: Self.selection)
        let client = try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: .camera, transport: transport)
        _ = try await client.capabilities()
        await transport.enqueueJSON(method: "POST", path: Self.createPath, body: #"{"directoryname":"ABCDE"}"#)
        await transport.enqueueJSON(path: Self.directoryPath, status: 503, body: "{}")
        let attempt = CameraCaptureConfigurationAttempt(operation: .createDirectory, requestedValue: "ABCDE")
        do { _ = try await client.createDirectory(name: "ABCDE", attempt: attempt); XCTFail("Aggregate state is not fresh proof") } catch {}
        XCTAssertEqual(attempt.snapshot.writeEvidence, .acknowledged)
        XCTAssertEqual(attempt.snapshot.readbackEvidence, .unavailable)
        XCTAssertNil(attempt.snapshot.directorySelection)
        let requests = await transport.requests()
        XCTAssertEqual(requests.filter { $0.path == "/ccapi/ver100/shooting/settings" }.count, 1,
            "Post-write verification must read only the canonical advertised directory route")
    }

    func testDirectNamingACKUnavailableThenMismatchAndMatchNeverReplaysWrite() async throws {
        let (client, transport) = try await namingClient()
        await transport.enqueueJSON(method: "PUT", path: Self.prefixPath, body: #"{"usersetting1":"NEW_"}"#)
        await transport.enqueueJSON(path: Self.namingPaths[0], status: 503, body: "{}")
        let attempt = CameraCaptureConfigurationAttempt(operation: .setFileNaming, requestedValue: "NEW_", field: .stillUserSetting1)
        do { _ = try await client.setFileNaming(field: .stillUserSetting1, value: "NEW_", attempt: attempt); XCTFail("Expected failure") } catch {}
        XCTAssertEqual(attempt.snapshot.writeEvidence, .acknowledged)
        XCTAssertEqual(attempt.snapshot.readbackEvidence, .unavailable)
        for prefix in ["OLD_", "NEW_"] {
            let before = await transport.requests().count
            await Self.enqueueNaming(transport, prefix: prefix)
            try await client.refreshCaptureConfiguration(attempt)
            let reads = Array(await transport.requests().dropFirst(before))
            XCTAssertEqual(reads.map(\.path), Self.namingPaths)
            XCTAssertTrue(reads.allSatisfy { $0.method == "GET" && $0.timeoutInterval <= 15 })
            XCTAssertEqual(attempt.snapshot.readbackEvidence, prefix == "NEW_" ? .matched : .mismatch)
        }
        let writes = await transport.requests().filter { $0.method == "PUT" }.count
        XCTAssertEqual(writes, 1)
    }

    func testInvalidNamingACKRemainsUnknownEvenAfterMatchingCompleteRead() async throws {
        let (client, transport) = try await namingClient()
        await transport.enqueueJSON(method: "PUT", path: Self.prefixPath, body: #"{"usersetting1":"OTHER"}"#)
        let attempt = CameraCaptureConfigurationAttempt(operation: .setFileNaming, requestedValue: "NEW_", field: .stillUserSetting1)
        do { _ = try await client.setFileNaming(field: .stillUserSetting1, value: "NEW_", attempt: attempt); XCTFail("Expected invalid ACK") } catch {}
        XCTAssertEqual(attempt.snapshot.writeEvidence, .unknown)
        await Self.enqueueNaming(transport, prefix: "NEW_")
        try await client.refreshCaptureConfiguration(attempt)
        XCTAssertEqual(attempt.snapshot.writeEvidence, .unknown)
        XCTAssertEqual(attempt.snapshot.readbackEvidence, .matched)
    }

    func testNamingReadbackMismatchKeepsValidACKAndCompleteObservedState() async throws {
        let (client, transport) = try await namingClient()
        await transport.enqueueJSON(method: "PUT", path: Self.prefixPath, body: #"{"usersetting1":"NEW_"}"#)
        await Self.enqueueNaming(transport, prefix: "OLD_")
        let attempt = CameraCaptureConfigurationAttempt(operation: .setFileNaming, requestedValue: "NEW_", field: .stillUserSetting1)
        do { _ = try await client.setFileNaming(field: .stillUserSetting1, value: "NEW_", attempt: attempt); XCTFail("Expected mismatch") } catch {}
        XCTAssertEqual(attempt.snapshot.writeEvidence, .acknowledged)
        XCTAssertEqual(attempt.snapshot.readbackEvidence, .mismatch)
        XCTAssertEqual(attempt.snapshot.fileNaming?.stillUserSetting1, "OLD_")
    }

    func testBridgeReceiptsRetainACKButStillThrowAndRecoveryNeverUsesCachedCapabilities() async throws {
        for valid in [false, true] {
            let (client, transport) = try await bridgeClient()
            let requested = valid ? "ABCDE" : "WRONG"
            await transport.enqueueJSON(method: "POST", path: "/v1/session/config/directories", status: 502,
                body: "{\"error\":{\"code\":\"CAPTURE_CONFIG_READBACK_FAILED\",\"engine\":\"ccapi\",\"feature\":\"DIRECTORY_CONTROL\",\"captureConfigReceipt\":{\"version\":1,\"operation\":\"create-directory\",\"stage\":\"acknowledged\",\"requestedValue\":\"\(requested)\",\"acknowledgedValue\":\"OTHER\"}}}")
            let attempt = CameraCaptureConfigurationAttempt(operation: .createDirectory, requestedValue: "ABCDE")
            do { _ = try await client.createDirectory(name: "ABCDE", attempt: attempt); XCTFail("502 must remain failure") } catch {}
            XCTAssertEqual(attempt.snapshot.writeEvidence, valid ? .acknowledged : .unknown)
            let before = await transport.requests().count
            await transport.enqueueJSON(path: "/v1/session/config/capture-configuration/create-directory",
                body: #"{"operation":"create-directory","directorySelection":{"value":"100EOSXX","ability":["100EOSXX"]}}"#)
            try await client.refreshCaptureConfiguration(attempt)
            let requests = await transport.requests()
            XCTAssertEqual(Array(requests.dropFirst(before)).map(\.method), ["GET"])
            XCTAssertEqual(attempt.snapshot.writeEvidence, valid ? .acknowledged : .unknown)
            XCTAssertEqual(attempt.snapshot.readbackEvidence, .refreshed)
        }
    }

    /// Intentionally takes over 15 seconds when run natively. The custom
    /// transport ignores request.timeoutInterval to test the caller's own budget.
    func testLateFinalNamingGETCannotPublishAfterGroupDeadline() async throws {
        let inner = MockCameraHTTPTransport()
        let operations = Self.namingPaths.map {
            ["path": String($0.dropFirst("/ccapi/ver100".count)), "get": true, "put": true] as [String: Any]
        }
        await inner.enqueue(path: "/ccapi", body: try JSONSerialization.data(withJSONObject: ["ver100": operations]))
        await Self.enqueueNaming(inner, prefix: "NEW_")
        let transport = CaptureConfigurationDelayedFinalReadTransport(inner: inner, finalPath: Self.namingPaths.last!)
        let client = try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: .camera, transport: transport)
        let attempt = CameraCaptureConfigurationAttempt(operation: .setFileNaming, requestedValue: "NEW_", field: .stillUserSetting1)
        try attempt.markDispatched()
        attempt.acknowledge("NEW_")
        do { try await client.refreshCaptureConfiguration(attempt); XCTFail("Late final GET must be rejected") } catch {}
        XCTAssertEqual(attempt.snapshot.writeEvidence, .acknowledged)
        XCTAssertEqual(attempt.snapshot.readbackEvidence, .unavailable)
        XCTAssertNil(attempt.snapshot.fileNaming)
        let requests = await inner.requests()
        XCTAssertEqual(requests.map(\.method), Array(repeating: "GET", count: 8))
        XCTAssertEqual(requests.filter { Self.namingPaths.contains($0.path) }.count, 7)
    }

    func testOversizedBridgeACKCannotEscapeScopedResponseBound() async throws {
        let (client, transport) = try await bridgeClient()
        await transport.enqueue(method: "POST", path: "/v1/session/config/directories",
            body: try JSONSerialization.data(withJSONObject: ["name": "ABCDE", "padding": String(repeating: "x", count: 65_536)]))
        let attempt = CameraCaptureConfigurationAttempt(operation: .createDirectory, requestedValue: "ABCDE")
        do { _ = try await client.createDirectory(name: "ABCDE", attempt: attempt); XCTFail("Oversized ACK") } catch {}
        XCTAssertEqual(attempt.snapshot.writeEvidence, .unknown)
        XCTAssertNil(attempt.snapshot.acknowledgedValue)
    }

    func testPartialHTTPStatusCannotEstablishACKEvenWithCompleteLookingJSON() async throws {
        for bridge in [false, true] {
            let attempt = CameraCaptureConfigurationAttempt(operation: .createDirectory, requestedValue: "ABCDE")
            if bridge {
                let (client, transport) = try await bridgeClient()
                await transport.enqueueJSON(method: "POST", path: "/v1/session/config/directories", status: 206,
                    body: #"{"name":"ABCDE"}"#)
                do { _ = try await client.createDirectory(name: "ABCDE", attempt: attempt); XCTFail("Partial response") } catch {}
            } else {
                let transport = MockCameraHTTPTransport()
                await transport.enqueueJSON(path: "/ccapi", body: Self.directoryDiscovery)
                await transport.enqueueJSON(method: "POST", path: Self.createPath, status: 206,
                    body: #"{"directoryname":"ABCDE"}"#)
                let client = try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: .camera, transport: transport)
                do { _ = try await client.createDirectory(name: "ABCDE", attempt: attempt); XCTFail("Partial response") } catch {}
            }
            XCTAssertEqual(attempt.snapshot.writeEvidence, .unknown)
            XCTAssertNil(attempt.snapshot.acknowledgedValue)
        }
    }

    func testBridgeReceiptRequiresExactActualConnectedCCAPIEngine() async throws {
        for engine in ["ccapi", "libgphoto2", "auto", "ccapi\n", " ccapi", ""] {
            let transport = MockCameraHTTPTransport()
            await transport.enqueueJSON(path: "/health", body: #"{"ok":true,"service":"open-eos-control-bridge","version":"0.13.0"}"#)
            await transport.enqueue(method: "POST", path: "/v1/session",
                body: try JSONSerialization.data(withJSONObject: ["id": "config", "engine": engine]))
            let client = try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", transport: transport)
            try await client.initialize()
            await transport.enqueueJSON(method: "POST", path: "/v1/session/config/directories", status: 502,
                body: #"{"error":{"code":"CAPTURE_CONFIG_READBACK_FAILED","engine":"ccapi","feature":"DIRECTORY_CONTROL","captureConfigReceipt":{"version":1,"operation":"create-directory","stage":"acknowledged","requestedValue":"ABCDE","acknowledgedValue":"OTHER"}}}"#)
            let attempt = CameraCaptureConfigurationAttempt(operation: .createDirectory, requestedValue: "ABCDE")
            do { _ = try await client.createDirectory(name: "ABCDE", attempt: attempt); XCTFail("502 must remain failure") } catch {}
            XCTAssertEqual(attempt.snapshot.writeEvidence, engine == "ccapi" ? .acknowledged : .unknown, engine)
        }
    }

    func testOldBridgeReadback404DoesNotFallBackOrWrite() async throws {
        let (client, transport) = try await bridgeClient()
        let attempt = CameraCaptureConfigurationAttempt(operation: .createDirectory, requestedValue: "ABCDE")
        await transport.enqueueJSON(path: "/v1/session/config/capture-configuration/create-directory", status: 404, body: "{}")
        do { try await client.refreshCaptureConfiguration(attempt); XCTFail("Old Bridge must remain unavailable") } catch {}
        XCTAssertEqual(attempt.snapshot.writeEvidence, .notSent)
        XCTAssertEqual(attempt.snapshot.readbackEvidence, .unavailable)
        let requests = await transport.requests()
        XCTAssertEqual(requests.count, 3)
        XCTAssertFalse(requests.contains { $0.path.contains("capabilities") || $0.path.contains("directories") })
    }

    func testBridgeACKArrivingAfterCancellationSettlesOriginalAttempt() async throws {
        let inner = MockCameraHTTPTransport()
        await Self.enqueueBridgeInitialization(inner)
        await inner.enqueueJSON(method: "POST", path: "/v1/session/config/directories", body: #"{"name":"OTHER"}"#)
        let transport = CaptureConfigurationGateTransport(inner: inner)
        let client = try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", transport: transport)
        try await client.initialize()
        let attempt = CameraCaptureConfigurationAttempt(operation: .createDirectory, requestedValue: "ABCDE")
        let task = Task { try await client.createDirectory(name: "ABCDE", attempt: attempt) }
        let deadline = ContinuousClock.now.advanced(by: .seconds(5))
        while !(await transport.waiting), ContinuousClock.now < deadline { await Task.yield() }
        let waiting = await transport.waiting
        XCTAssertTrue(waiting)
        attempt.cancel(); task.cancel(); await transport.release()
        do { _ = try await task.value; XCTFail("Cancelled caller must stay cancelled") } catch {}
        XCTAssertEqual(attempt.snapshot.writeEvidence, .acknowledged)
        XCTAssertEqual(attempt.snapshot.acknowledgedValue, "OTHER")
        let writes = await inner.requests().filter { $0.path.hasSuffix("/directories") }.count
        XCTAssertEqual(writes, 1)
    }

    private func namingClient() async throws -> (CCAPIClient, MockCameraHTTPTransport) {
        let transport = MockCameraHTTPTransport()
        let operations = Self.namingPaths.map { ["path": String($0.dropFirst("/ccapi/ver100".count)), "get": true, "put": true] as [String: Any] }
        let discovery = try JSONSerialization.data(withJSONObject: ["ver100": [["path": "/shooting/settings", "get": true]] + operations])
        await transport.enqueue(path: "/ccapi", body: discovery)
        await transport.enqueueJSON(path: "/ccapi/ver100/shooting/settings", body: "{}")
        await Self.enqueueNaming(transport, prefix: "OLD_")
        let client = try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: .camera, transport: transport)
        _ = try await client.capabilities()
        return (client, transport)
    }
    private func bridgeClient() async throws -> (DesktopBridgeClient, MockCameraHTTPTransport) {
        let transport = MockCameraHTTPTransport()
        await Self.enqueueBridgeInitialization(transport)
        let client = try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181", transport: transport)
        try await client.initialize()
        return (client, transport)
    }
    private static func enqueueBridgeInitialization(_ transport: MockCameraHTTPTransport) async {
        await transport.enqueueJSON(path: "/health", body: #"{"ok":true,"service":"open-eos-control-bridge","version":"0.13.0"}"#)
        await transport.enqueueJSON(method: "POST", path: "/v1/session", body: #"{"id":"config","engine":"ccapi"}"#)
    }
    private static let createPath = "/ccapi/ver100/functions/directory/createdirectory"
    private static let directoryPath = "/ccapi/ver100/functions/directory/directoryselection"
    private static let selection = #"{"value":"100EOSXX","ability":["100EOSXX","101ABCDE"]}"#
    private static let directoryDiscovery = #"{"ver100":[{"path":"/shooting/settings","get":true},{"path":"/functions/directory/createdirectory","post":true},{"path":"/functions/directory/directoryselection","get":true,"put":true}]}"#
    private static let namingPaths = ["stills/filename", "stills/usersetting1", "stills/usersetting2", "movies/index", "movies/reelnum", "movies/clipnum", "movies/userdefined"].map { "/ccapi/ver100/functions/filename/" + $0 }
    private static var prefixPath: String { namingPaths[1] }
    private static func enqueueNaming(_ transport: MockCameraHTTPTransport, prefix: String) async {
        let bodies = [#"{"value":"preset_code","ability":["preset_code","usersetting1","usersetting2"]}"#,
            "{\"usersetting1\":\"\(prefix)\"}", #"{"usersetting2":"EOS"}"#, #"{"index":"A_"}"#,
            #"{"value":1,"ability":{"min":1,"max":9999,"step":1}}"#,
            #"{"value":1,"ability":{"min":1,"max":999,"step":1}}"#, #"{"userdefined":"EOSXX"}"#]
        for (path, body) in zip(namingPaths, bodies) { await transport.enqueueJSON(path: path, body: body) }
    }
    private static func enqueueCanonicalNamingValue(_ transport: MockCameraHTTPTransport, value: [String: Any]) async {
        let reel = value["movieReelRange"] as! [String: Any]
        let clip = value["movieClipRange"] as! [String: Any]
        let bodies: [[String: Any]] = [
            ["value": value["stillFilenameMode"]!, "ability": value["stillFilenameModeOptions"]!],
            ["usersetting1": value["stillUserSetting1"]!], ["usersetting2": value["stillUserSetting2"]!],
            ["index": value["movieIndex"]!],
            ["value": value["movieReelNumber"]!, "ability": ["min": reel["minimum"]!, "max": reel["maximum"]!, "step": reel["step"]!]],
            ["value": value["movieClipNumber"]!, "ability": ["min": clip["minimum"]!, "max": clip["maximum"]!, "step": clip["step"]!]],
            ["userdefined": value["movieUserDefined"]!],
        ]
        for (path, body) in zip(namingPaths, bodies) {
            await transport.enqueue(path: path, body: try! JSONSerialization.data(withJSONObject: body))
        }
    }

    private static func naming(prefix: String) -> CameraFileNaming {
        CameraFileNaming(stillFilenameMode: "preset_code", stillFilenameModeOptions: ["preset_code"],
            stillUserSetting1: prefix, stillUserSetting2: "EOS", movieIndex: "A_", movieReelNumber: 1,
            movieReelRange: CameraIntegerRange(minimum: 1, maximum: 9999, step: 1), movieClipNumber: 1,
            movieClipRange: CameraIntegerRange(minimum: 1, maximum: 999, step: 1), movieUserDefined: "EOSXX")
    }
}

private actor CaptureConfigurationGateTransport: CameraHTTPTransport {
    let inner: MockCameraHTTPTransport
    private var continuation: CheckedContinuation<Void, Never>?
    var waiting: Bool { continuation != nil }
    init(inner: MockCameraHTTPTransport) { self.inner = inner }
    func send(_ request: URLRequest) async throws -> CameraHTTPResponse {
        let result = try await inner.send(request)
        if request.url?.path.hasSuffix("/directories") == true {
            await withCheckedContinuation { continuation = $0 }
        }
        return result
    }
    func release() { continuation?.resume(); continuation = nil }
    func download(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse { throw URLError(.unsupportedURL) }
}

private actor CaptureConfigurationDelayedFinalReadTransport: CameraHTTPTransport {
    let inner: MockCameraHTTPTransport
    let finalPath: String
    init(inner: MockCameraHTTPTransport, finalPath: String) { self.inner = inner; self.finalPath = finalPath }
    func send(_ request: URLRequest) async throws -> CameraHTTPResponse {
        let response = try await inner.send(request)
        if request.url?.path == finalPath { try await Task.sleep(for: .milliseconds(15_100)) }
        return response
    }
    func download(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse { throw URLError(.unsupportedURL) }
}
