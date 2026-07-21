import Foundation
import XCTest

@testable import OpenEOSCore

final class CCAPIClientTests: XCTestCase {
    private let discovery = """
    {
      "ver100": [
        {"path":"/deviceinformation","get":true},
        {"path":"/devicestatus/batterylist","get":true},
        {"path":"/devicestatus/storage","get":true},
        {"path":"/shooting/settings","get":true},
        {"path":"/shooting/settings/iso","put":true},
        {"path":"/shooting/settings/tv","put":true},
        {"path":"/shooting/settings/av","put":true},
        {"path":"/shooting/settings/wb","put":true},
        {"path":"/shooting/settings/meteringmode","put":true},
        {"path":"/shooting/control/shutterbutton","post":true},
        {"path":"/shooting/control/shutterbutton/manual","put":true},
        {"path":"/shooting/control/recbutton","post":true},
        {"path":"/shooting/control/afpoint","put":true},
        {"path":"/shooting/liveview","get":true,"post":true,"delete":true},
        {"path":"/shooting/liveview/flip","get":true},
        {"path":"/shooting/liveview/flipdetail","get":true},
        {"path":"/contents","get":true}
      ]
    }
    """

    private let settings = """
    {
      "iso":{"value":"800","ability":["100","800","1600"]},
      "tv":{"value":"1/50","ability":["1/50","1/100"]},
      "av":{"value":"2.8","ability":["2.8","4.0"]},
      "wb":{"value":"auto","ability":["auto","daylight"]},
      "meteringmode":{"value":"evaluative","ability":["evaluative","spot"]},
      "readonly":{"value":"fixed","ability":["fixed"]}
    }
    """

    func testDiscoverySnapshotBuildsCapabilitiesFromAdvertisedOperations() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(path: "/ccapi", body: discovery)
        await transport.enqueueJSON(
            path: "/ccapi/ver100/deviceinformation",
            body: #"{"productname":"Canon EOS R6 Mark III","serialnumber":"TEST-SERIAL-0001","version":"1.4.0"}"#
        )
        await enqueueStatus(on: transport)
        let client = try CCAPIClient(baseURL: "http://192.168.1.2:8080", mode: .camera, transport: transport)

        let snapshot = try await client.connectSnapshot()

        XCTAssertEqual(snapshot.info.model, "Canon EOS R6 Mark III")
        XCTAssertEqual(snapshot.status.batteryLevel, 89)
        XCTAssertEqual(snapshot.status.exposure.shutter, "1/50")
        XCTAssertEqual(snapshot.capabilities.profile.priority, .primary)
        XCTAssertEqual(snapshot.capabilities.setting("iso")?.values, ["100", "800", "1600"])
        XCTAssertEqual(snapshot.capabilities.setting("meteringmode")?.values, ["evaluative", "spot"])
        XCTAssertNil(snapshot.capabilities.setting("readonly"))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.stillCapture))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.shutterHalfPress))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.videoRecording))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.tapFocus))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.mediaDownload))
        XCTAssertFalse(snapshot.capabilities.matrix.supports(.focusDrive))
        XCTAssertEqual(snapshot.capabilities.evidence.source, "GET /ccapi")
        XCTAssertEqual(snapshot.capabilities.evidence.protocolVersions, ["ver100"])
        XCTAssertTrue(
            snapshot.capabilities.evidence.advertisedCommands.contains(
                "POST /ccapi/ver100/shooting/control/shutterbutton"
            )
        )
        XCTAssertTrue(snapshot.capabilities.evidence.writableSettings.contains("iso"))
        XCTAssertFalse(snapshot.capabilities.evidence.truncated)
        let remainingResponses = await transport.remainingResponses()
        XCTAssertEqual(remainingResponses, 0)
    }

    func testStillCaptureUsesAdvertisedPostAndAutofocusPayload() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(path: "/ccapi", body: discovery)
        await transport.enqueue(method: "POST", path: "/ccapi/ver100/shooting/control/shutterbutton", status: 204, body: Data())
        await enqueueStatus(on: transport)
        let client = try CCAPIClient(baseURL: "http://192.168.1.2:8080", mode: .camera, transport: transport)

        _ = try await client.captureStill()

        let requests = await transport.requests()
        let request = try XCTUnwrap(requests.first { $0.path.contains("shutterbutton") })
        let body = try XCTUnwrap(request.body)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Any])
        XCTAssertEqual(json["af"] as? Bool, true)
    }

    func testUnadvertisedCaptureFailsWithoutSendingACommand() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(path: "/ccapi", body: #"{"ver100":[{"path":"/deviceinformation","get":true}]}"#)
        let client = try CCAPIClient(baseURL: "http://192.168.1.2:8080", mode: .camera, transport: transport)

        do {
            _ = try await client.captureStill()
            XCTFail("Expected unsupported capture")
        } catch {
            XCTAssertEqual(error as? CCAPIError, .unsupported(.stillCapture))
        }
        let requestCount = await transport.requests().count
        XCTAssertEqual(requestCount, 1)
    }

    func testReadOnlySettingsWrongShutterMethodAndIncompleteLiveViewStayUnavailable() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(
            path: "/ccapi",
            body: #"{"ver100":[{"path":"/shooting/settings","get":true},{"path":"/shooting/control/shutterbutton","put":true},{"path":"/shooting/liveview","post":true},{"path":"/shooting/liveview/flip","get":true}]}"#
        )
        await transport.enqueueJSON(path: "/ccapi/ver100/shooting/settings", body: settings)
        let client = try CCAPIClient(baseURL: "http://192.168.1.2:8080", mode: .camera, transport: transport)

        let capabilities = try await client.capabilities()
        let requestCount = await transport.requests().count

        XCTAssertFalse(capabilities.matrix.supports(.exposureControl))
        XCTAssertFalse(capabilities.matrix.supports(.advancedSettings))
        XCTAssertFalse(capabilities.matrix.supports(.stillCapture))
        XCTAssertFalse(capabilities.matrix.supports(.liveView))
        XCTAssertTrue(capabilities.settings.isEmpty)

        do {
            _ = try await client.captureStill()
            XCTFail("Expected unsupported capture")
        } catch {
            XCTAssertEqual(error as? CCAPIError, .unsupported(.stillCapture))
        }
        do {
            _ = try await client.setSetting(key: "iso", value: "1600")
            XCTFail("Expected read-only setting rejection")
        } catch {
            XCTAssertEqual(error as? CCAPIError, .invalidSetting(key: "iso", value: "1600"))
        }
        do {
            try await client.startLiveView()
            XCTFail("Expected incomplete Live View rejection")
        } catch {
            XCTAssertEqual(error as? CCAPIError, .unsupported(.liveView))
        }
        let finalRequestCount = await transport.requests().count
        XCTAssertEqual(finalRequestCount, requestCount)
    }

    func testCapabilityEvidenceIsBoundedAndRemovesQueries() async throws {
        let longSegment = String(repeating: "x", count: 600)
        let entries = (0..<300).map { index in
            #"{"path":"/diagnostics/item\#(index)/\#(longSegment)?token=secret","get":true}"#
        }.joined(separator: ",")
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(path: "/ccapi", body: "{\"ver100\":[\(entries)]}")
        let client = try CCAPIClient(baseURL: "http://192.168.1.2:8080", mode: .camera, transport: transport)

        let capabilities = try await client.capabilities()
        let evidence = capabilities.evidence

        XCTAssertEqual(evidence.advertisedCommands.count, 256)
        XCTAssertTrue(evidence.truncated)
        XCTAssertTrue(evidence.advertisedCommands.allSatisfy { !$0.contains("?") && !$0.contains("secret") })
        XCTAssertTrue(
            evidence.advertisedCommands.allSatisfy { $0.count <= 512 }
        )
    }

    func testHalfPressUsesAdvertisedMethodAndAlwaysReleases() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(
            path: "/ccapi",
            body: #"{"ver110":[{"path":"/shooting/control/shutterbutton/manual","put":true}]}"#
        )
        await transport.enqueue(method: "PUT", path: "/ccapi/ver110/shooting/control/shutterbutton/manual", status: 204, body: Data())
        await transport.enqueue(method: "PUT", path: "/ccapi/ver110/shooting/control/shutterbutton/manual", status: 204, body: Data())
        await enqueueStatus(on: transport, prefix: "/ccapi/ver110")
        let client = try CCAPIClient(baseURL: "http://192.168.1.2:8080", mode: .camera, transport: transport)

        _ = try await client.halfPressShutter()

        let requests = await transport.requests()
        let commands = requests.filter { $0.path.contains("shutterbutton/manual") }
        XCTAssertEqual(commands.map(\.method), ["PUT", "PUT"])
        let actions = try commands.map { request -> String in
            let body = try XCTUnwrap(request.body)
            let json = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Any])
            return try XCTUnwrap(json["action"] as? String)
        }
        XCTAssertEqual(actions, ["half_press", "release"])
    }

    func testLiveViewRetriesWithoutSizeAfter400AndFallsBackToFlipDetail() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(path: "/ccapi", body: discovery)
        await transport.enqueueJSON(
            method: "POST",
            path: "/ccapi/ver100/shooting/liveview",
            status: 400,
            body: #"{"message":"Invalid parameter"}"#
        )
        await transport.enqueue(method: "POST", path: "/ccapi/ver100/shooting/liveview", status: 204, body: Data())
        await transport.enqueue(method: "GET", path: "/ccapi/ver100/shooting/liveview/flip?t=7", status: 404, body: Data("not found".utf8))
        let jpeg = Data([0xFF, 0xD8, 0x05, 0x06, 0xFF, 0xD9])
        let multipart = Data("--frame\r\n\r\n".utf8) + jpeg + Data("\r\n--frame\r\n".utf8)
        await transport.enqueue(
            method: "GET",
            path: "/ccapi/ver100/shooting/liveview/flipdetail?kind=image&t=7",
            headers: ["content-type": "multipart/x-mixed-replace; boundary=frame"],
            body: multipart
        )
        let client = try CCAPIClient(baseURL: "http://192.168.1.2:8080", mode: .camera, transport: transport)

        try await client.startLiveView(LiveViewRequest(fps: 15, size: .large))
        let frame = try await client.liveViewFrame(cacheKey: 7)

        XCTAssertEqual(frame.data, jpeg)
        let requests = await transport.requests()
        let startBodies = requests.filter { $0.path == "/ccapi/ver100/shooting/liveview" }.compactMap(\.body)
        XCTAssertEqual(startBodies.count, 2)
        let fallback = try XCTUnwrap(JSONSerialization.jsonObject(with: startBodies[1]) as? [String: Any])
        XCTAssertEqual(fallback["cameradisplay"] as? String, "on")
        XCTAssertNil(fallback["liveviewsize"])
    }

    func testSettingRejectsValueNotAdvertisedByCamera() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(path: "/ccapi", body: discovery)
        await transport.enqueueJSON(path: "/ccapi/ver100/shooting/settings", body: settings)
        let client = try CCAPIClient(baseURL: "http://192.168.1.2:8080", mode: .camera, transport: transport)

        _ = try await client.capabilities()
        do {
            _ = try await client.setSetting(key: "iso", value: "51200")
            XCTFail("Expected invalid setting value")
        } catch {
            XCTAssertEqual(error as? CCAPIError, .invalidSetting(key: "iso", value: "51200"))
        }
        let requestCount = await transport.requests().count
        XCTAssertEqual(requestCount, 2)
    }

    func testSettingWritesOnlyAnAdvertisedValueToDiscoveredPath() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(path: "/ccapi", body: discovery)
        await transport.enqueueJSON(path: "/ccapi/ver100/shooting/settings", body: settings)
        await transport.enqueue(method: "PUT", path: "/ccapi/ver100/shooting/settings/iso", status: 204, body: Data())
        await enqueueStatus(on: transport)
        let client = try CCAPIClient(baseURL: "http://192.168.1.2:8080", mode: .camera, transport: transport)

        _ = try await client.capabilities()
        _ = try await client.setSetting(key: "iso", value: "1600")

        let requests = await transport.requests()
        let write = try XCTUnwrap(requests.first { $0.method == "PUT" })
        let body = try XCTUnwrap(write.body)
        let value = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: String])
        XCTAssertEqual(value, ["value": "1600"])
    }

    func testMediaDownloadRejectsCrossOriginCameraResource() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(path: "/ccapi", body: discovery)
        let client = try CCAPIClient(baseURL: "http://192.168.1.2:8080", mode: .camera, transport: transport)
        let destination = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        let item = CameraMediaItem(
            id: "http://attacker.invalid/ccapi/ver100/contents/IMG_0001.JPG",
            name: "IMG_0001.JPG",
            kind: "image"
        )

        do {
            _ = try await client.downloadMedia(item, to: destination)
            XCTFail("Expected same-origin validation")
        } catch {
            XCTAssertEqual(
                error as? CCAPIError,
                .outsideCameraOrigin("http://attacker.invalid/ccapi/ver100/contents/IMG_0001.JPG")
            )
        }
    }

    func testMediaDownloadRetriesMainVariantAndMovesTemporaryFile() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(path: "/ccapi", body: discovery)
        let path = "/ccapi/ver100/contents/card1/100CANON/IMG_0001.CR3"
        await transport.enqueueDownload(
            path: path,
            status: 200,
            body: Data(#"{"kind":"metadata"}"#.utf8)
        )
        let media = Data([9, 8, 7, 6])
        await transport.enqueueDownload(
            path: "\(path)?kind=main",
            headers: ["content-type": "image/x-canon-cr3"],
            body: media
        )
        let client = try CCAPIClient(baseURL: "http://192.168.1.2:8080", mode: .camera, transport: transport)
        let destination = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: destination) }

        let result = try await client.downloadMedia(
            CameraMediaItem(id: path, name: "IMG_0001.CR3", kind: "raw"),
            to: destination
        )

        XCTAssertEqual(try Data(contentsOf: destination), media)
        XCTAssertEqual(result.bytesTransferred, 4)
        XCTAssertEqual(result.contentType, "image/x-canon-cr3")
        let downloadPaths = (await transport.requests()).map(\.path).filter { $0.contains("IMG_0001.CR3") }
        XCTAssertEqual(downloadPaths, [path, "\(path)?kind=main"])
    }

    func testBasicAuthenticationIsSentButDiagnosticReportRedactsSecrets() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(path: "/ccapi", body: discovery)
        let client = try CCAPIClient(
            baseURL: "http://192.168.1.2:8080",
            mode: .camera,
            username: "camera-user",
            password: "secret",
            transport: transport
        )

        try await client.initialize()
        let requests = await transport.requests()
        let request = try XCTUnwrap(requests.first)
        XCTAssertTrue(request.headers.values.contains { $0.hasPrefix("Basic ") })
        let report = await client.diagnosticReport(
            snapshot: nil,
            lastError: "Authorization: Basic abc password=secret token=abc"
        )
        XCTAssertFalse(report.contains("camera-user"))
        XCTAssertFalse(report.contains("secret"))
        XCTAssertFalse(report.contains("Basic abc"))
        XCTAssertTrue(report.contains("[redacted]"))
    }

    func testDiagnosticReportIncludesCapabilityEvidence() throws {
        let capabilities = CameraCapabilities(
            settings: [],
            matrix: CapabilityMatrix(),
            liveView: LiveViewCapabilities(),
            profile: CameraProfile.from(modelName: "Canon EOS R6 Mark III"),
            evidence: CameraCapabilityEvidence(
                source: "GET /ccapi",
                protocolVersions: ["ver100"],
                advertisedCommands: ["POST /ccapi/ver100/shooting/control/shutterbutton"],
                writableSettings: ["iso", "tv"]
            )
        )
        let snapshot = CameraSnapshot(
            info: CameraInfo(model: "Canon EOS R6 Mark III", serial: "redacted", api: "ccapi"),
            status: CameraStatus(),
            capabilities: capabilities
        )

        let report = CCAPIDiagnosticReport.make(
            baseURL: try XCTUnwrap(URL(string: "http://192.168.1.2:8080")),
            mode: .camera,
            versions: ["/ccapi/ver100"],
            snapshot: snapshot
        )

        XCTAssertTrue(report.contains("capabilitySource=GET /ccapi"))
        XCTAssertTrue(report.contains("advertisedCommandCount=1"))
        XCTAssertTrue(report.contains("POST /ccapi/ver100/shooting/control/shutterbutton"))
        XCTAssertTrue(report.contains("writableSettings=iso, tv"))
    }

    private func enqueueStatus(on transport: MockCameraHTTPTransport, prefix: String = "/ccapi/ver100") async {
        await transport.enqueueJSON(path: "\(prefix)/devicestatus/batterylist", body: #"{"batterylist":[{"level":89}]}"#)
        await transport.enqueueJSON(
            path: "\(prefix)/devicestatus/storage",
            body: #"{"storagelist":[{"name":"card1","spacesize":32000000000}]}"#
        )
        await transport.enqueueJSON(path: "\(prefix)/shooting/settings", body: settings)
    }
}
