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
        {"path":"/shooting/settings/stillimagequality","put":true},
        {"path":"/shooting/settings/wbshift","put":true},
        {"path":"/shooting/settings/meteringmode","put":true},
        {"path":"/shooting/control/shutterbutton","post":true},
        {"path":"/shooting/control/shutterbutton/manual","put":true},
        {"path":"/shooting/control/af","post":true},
        {"path":"/shooting/control/recbutton","post":true},
        {"path":"/shooting/liveview/afframeposition","put":true},
        {"path":"/shooting/liveview/clickwb","post":true},
        {"path":"/shooting/control/drivefocus","post":true},
        {"path":"/shooting/liveview","get":true,"post":true,"delete":true},
        {"path":"/shooting/liveview/flip","get":true},
        {"path":"/shooting/liveview/flipdetail","get":true},
        {"path":"/contents","get":true,"delete":true}
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
      "stillimagequality":{
        "value":{"raw":"none","jpeg":"large_fine"},
        "ability":{"raw":["none","raw","craw"],"jpeg":["none","large_fine","large_normal"]}
      },
      "wbshift":{
        "value":{"ba":0,"mg":0},
        "ability":{"ba":{"min":-9,"max":9,"step":1},"mg":{"min":-9,"max":9,"step":1}}
      },
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
        XCTAssertEqual(snapshot.status.storageTotalBytes, 192_000_000_000)
        XCTAssertEqual(snapshot.status.storageFreeBytes, 96_000_000_000)
        XCTAssertEqual(snapshot.status.storageFreeImages, 2_400)
        XCTAssertEqual(snapshot.status.storageDeviceCount, 2)
        XCTAssertEqual(snapshot.capabilities.profile.priority, .primary)
        XCTAssertEqual(snapshot.capabilities.setting("iso")?.values, ["100", "800", "1600"])
        XCTAssertEqual(snapshot.capabilities.setting("meteringmode")?.values, ["evaluative", "spot"])
        XCTAssertEqual(snapshot.capabilities.setting("stillimagequality.raw")?.values, ["none", "raw", "craw"])
        XCTAssertEqual(snapshot.capabilities.setting("stillimagequality.jpeg")?.value, "large_fine")
        XCTAssertEqual(snapshot.capabilities.setting("wbshift.ba")?.values, (-9...9).map(String.init))
        XCTAssertNil(snapshot.capabilities.setting("readonly"))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.stillCapture))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.shutterHalfPress))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.videoRecording))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.tapFocus))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.clickWhiteBalance))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.mediaDownload))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.mediaDelete))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.mediaThumbnail))
        XCTAssertFalse(snapshot.capabilities.matrix.planned.contains(.mediaThumbnail))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.focusDrive))
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

    func testDiscoveryAcceptsSameOriginURLEntriesAndRejectsUnsafeOperations() async throws {
        let transport = MockCameraHTTPTransport()
        let fullURLDiscovery = """
        {
          "ver100": [
            {"url":"http://192.168.1.2:8080/ccapi/ver100/deviceinformation","get":true},
            {"url":"http://192.168.1.2:8080/ccapi/ver100/devicestatus/storage?token=secret","get":true},
            {"url":"http://192.168.1.2:8080/ccapi/ver100/shooting/settings","get":true},
            {"url":"http://192.168.1.2:8080/ccapi/ver100/shooting/settings/iso","put":true},
            {"url":"http://192.168.1.2:8080/ccapi/ver100/shooting/control/shutterbutton","post":true},
            {"url":"http://attacker.invalid/ccapi/ver100/shooting/control/recbutton","post":true},
            {"url":"http://192.168.1.2:8080/ccapi/ver100/ignored/../shooting/control/recbutton","post":true}
          ]
        }
        """
        await transport.enqueueJSON(path: "/ccapi", body: fullURLDiscovery)
        await transport.enqueueJSON(
            path: "/ccapi/ver100/deviceinformation",
            body: #"{"productname":"Canon EOS R6 Mark III","serialnumber":"redacted","version":"1.4.0"}"#
        )
        await enqueueStatus(on: transport)
        let client = try CCAPIClient(
            baseURL: "http://192.168.1.2:8080",
            mode: .camera,
            transport: transport
        )

        let snapshot = try await client.connectSnapshot()

        XCTAssertEqual(snapshot.status.mediaAvailable, true)
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.storageStatus))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.exposureControl))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.stillCapture))
        XCTAssertFalse(snapshot.capabilities.matrix.supports(.videoRecording))
        XCTAssertTrue(
            snapshot.capabilities.evidence.advertisedCommands.contains(
                "GET /ccapi/ver100/devicestatus/storage"
            )
        )
        XCTAssertTrue(
            snapshot.capabilities.evidence.advertisedCommands.contains(
                "POST /ccapi/ver100/shooting/control/shutterbutton"
            )
        )
        XCTAssertTrue(
            snapshot.capabilities.evidence.advertisedCommands.allSatisfy {
                !$0.contains("secret") && !$0.contains("attacker")
            }
        )
        let remainingResponses = await transport.remainingResponses()
        XCTAssertEqual(remainingResponses, 0)
    }

    func testDirectCCAPIThumbnailUsesCanonKindQueryAndSniffsImageType() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(path: "/ccapi", body: discovery)
        let path = "/ccapi/ver100/contents/card1/100CANON/IMG_0001.JPG"
        let jpeg = Data([0xFF, 0xD8, 0xFF, 0x01, 0xFF, 0xD9])
        await transport.enqueue(
            path: "\(path)?kind=thumbnail",
            headers: ["content-type": "application/octet-stream"],
            body: jpeg
        )
        let client = try CCAPIClient(baseURL: "http://192.168.1.2:8080", mode: .camera, transport: transport)
        let item = CameraMediaItem(id: "\(path)?kind=main", name: "IMG_0001.JPG", kind: "image")

        let thumbnail = try await client.mediaThumbnail(item)

        XCTAssertEqual(thumbnail.data, jpeg)
        XCTAssertEqual(thumbnail.contentType, "image/jpeg")
        let requests = await transport.requests()
        XCTAssertEqual(requests.map(\.path), ["/ccapi", "\(path)?kind=thumbnail"])
    }

    func testDirectCCAPIThumbnailRejectsOversizedResponse() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(path: "/ccapi", body: discovery)
        let path = "/ccapi/ver100/contents/card1/100CANON/IMG_0001.JPG"
        await transport.enqueue(
            path: "\(path)?kind=thumbnail",
            headers: ["content-type": "image/jpeg"],
            body: Data(repeating: 0x01, count: 8 * 1024 * 1024 + 1)
        )
        let client = try CCAPIClient(baseURL: "http://192.168.1.2:8080", mode: .camera, transport: transport)

        do {
            _ = try await client.mediaThumbnail(CameraMediaItem(id: path, name: "IMG_0001.JPG", kind: "image"))
            XCTFail("Expected bounded thumbnail failure")
        } catch {
            XCTAssertTrue(error.localizedDescription.contains("exceeded"))
        }
    }

    func testDirectCCAPIThumbnailRejectsCrossOriginBeforeFetching() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(path: "/ccapi", body: discovery)
        let client = try CCAPIClient(baseURL: "http://192.168.1.2:8080", mode: .camera, transport: transport)
        let value = "http://attacker.invalid/ccapi/ver100/contents/IMG_0001.JPG"

        do {
            _ = try await client.mediaThumbnail(CameraMediaItem(id: value, name: "IMG_0001.JPG", kind: "image"))
            XCTFail("Expected same-origin validation")
        } catch {
            XCTAssertEqual(error as? CCAPIError, .outsideCameraOrigin(value))
        }
        let requests = await transport.requests()
        XCTAssertEqual(requests.map(\.path), ["/ccapi"])
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
        let capabilities = try await client.capabilities()
        XCTAssertTrue(capabilities.evidence.observedFeatures.contains(.stillCapture))
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
        let capabilities = try await client.capabilities()
        XCTAssertFalse(capabilities.evidence.observedFeatures.contains(.stillCapture))
    }

    func testFocusDriveUsesAdvertisedPostAndCanonValue() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(path: "/ccapi", body: discovery)
        await transport.enqueue(
            method: "POST",
            path: "/ccapi/ver100/shooting/control/drivefocus",
            status: 204,
            body: Data()
        )
        let client = try CCAPIClient(baseURL: "http://192.168.1.2:8080", mode: .camera, transport: transport)

        let result = try await client.driveFocus(direction: .far, step: .large)

        XCTAssertEqual(result, FocusDriveResult(accepted: true, direction: .far, step: .large))
        let requests = await transport.requests()
        let request = try XCTUnwrap(requests.first { $0.path.contains("drivefocus") })
        XCTAssertEqual(request.method, "POST")
        let body = try XCTUnwrap(request.body)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: String])
        XCTAssertEqual(json, ["value": "far3"])
    }

    func testUnadvertisedFocusDriveFailsWithoutSendingACommand() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(
            path: "/ccapi",
            body: #"{"ver100":[{"path":"/deviceinformation","get":true}]}"#
        )
        let client = try CCAPIClient(baseURL: "http://192.168.1.2:8080", mode: .camera, transport: transport)

        do {
            _ = try await client.driveFocus(direction: .near, step: .small)
            XCTFail("Expected unsupported focus drive")
        } catch {
            XCTAssertEqual(error as? CCAPIError, .unsupported(.focusDrive))
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

    func testAutofocusUsesAdvertisedCanonStartAndStopActions() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(
            path: "/ccapi",
            body: #"{"ver110":[{"path":"/shooting/control/af","post":true}]}"#
        )
        await transport.enqueue(method: "POST", path: "/ccapi/ver110/shooting/control/af", status: 204, body: Data())
        await transport.enqueue(method: "POST", path: "/ccapi/ver110/shooting/control/af", status: 204, body: Data())
        await enqueueStatus(on: transport, prefix: "/ccapi/ver110")
        let client = try CCAPIClient(baseURL: "http://192.168.1.2:8080", mode: .camera, transport: transport)

        _ = try await client.autofocus()

        let commands = await transport.requests().filter { $0.path.contains("/shooting/control/af") }
        XCTAssertEqual(commands.map(\.method), ["POST", "POST"])
        let actions = try commands.map { request -> String in
            let body = try XCTUnwrap(request.body)
            let json = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Any])
            return try XCTUnwrap(json["action"] as? String)
        }
        XCTAssertEqual(actions, ["start", "stop"])
    }

    func testFailedAutofocusStartStillSendsStop() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(
            path: "/ccapi",
            body: #"{"ver110":[{"path":"/shooting/control/af","post":true}]}"#
        )
        await transport.enqueueJSON(
            method: "POST",
            path: "/ccapi/ver110/shooting/control/af",
            status: 503,
            body: #"{"message":"focus failed"}"#
        )
        await transport.enqueue(method: "POST", path: "/ccapi/ver110/shooting/control/af", status: 204, body: Data())
        let client = try CCAPIClient(baseURL: "http://192.168.1.2:8080", mode: .camera, transport: transport)

        do {
            _ = try await client.autofocus()
            XCTFail("Expected autofocus failure")
        } catch {
            XCTAssertTrue(error.localizedDescription.contains("503"))
        }

        let requests = await transport.requests()
        let commands = requests.filter { $0.path.contains("/shooting/control/af") }
        let actions = try commands.map { request -> String in
            let body = try XCTUnwrap(request.body)
            let json = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Any])
            return try XCTUnwrap(json["action"] as? String)
        }
        XCTAssertEqual(actions, ["start", "stop"])
    }

    func testUnadvertisedAutofocusFailsWithoutACommand() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(path: "/ccapi", body: #"{"ver100":[{"path":"/deviceinformation","get":true}]}"#)
        let client = try CCAPIClient(baseURL: "http://192.168.1.2:8080", mode: .camera, transport: transport)

        do {
            _ = try await client.autofocus()
            XCTFail("Expected unsupported autofocus")
        } catch {
            XCTAssertEqual(error as? CCAPIError, .unsupported(.autofocus))
        }
        let requestCount = await transport.requests().count
        XCTAssertEqual(requestCount, 1)
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
        let jpeg = Data([0xFF, 0xD8, 0x05, 0x06, 0xFF, 0xD9])
        await transport.enqueue(
            method: "GET",
            path: "/ccapi/ver100/shooting/liveview/flipdetail?kind=both&t=7",
            headers: ["content-type": "application/octet-stream"],
            body: detailedLiveView(jpeg: jpeg)
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

    func testTapFocusUsesCanonImagePositionCoordinates() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(path: "/ccapi", body: discovery)
        let jpeg = Data([0xFF, 0xD8, 0x01, 0x02, 0xFF, 0xD9])
        await transport.enqueue(
            method: "GET",
            path: "/ccapi/ver100/shooting/liveview/flipdetail?kind=both&t=9",
            headers: ["content-type": "application/octet-stream"],
            body: detailedLiveView(jpeg: jpeg)
        )
        await transport.enqueue(
            method: "PUT",
            path: "/ccapi/ver100/shooting/liveview/afframeposition",
            status: 204,
            body: Data()
        )
        let client = try CCAPIClient(baseURL: "http://192.168.1.2:8080", mode: .camera, transport: transport)

        try await client.initialize()
        _ = try await client.liveViewFrame(cacheKey: 9)
        let result = try await client.tapFocus(x: 0.25, y: 0.75)

        XCTAssertEqual(result, FocusResult(accepted: true, x: 0.25, y: 0.75))
        let requests = await transport.requests()
        let focus = try XCTUnwrap(requests.first { $0.path.hasSuffix("/shooting/liveview/afframeposition") })
        let body = try XCTUnwrap(focus.body)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Int])
        XCTAssertEqual(json, ["positionx": 1600, "positiony": 3200])
    }

    func testClickWhiteBalanceUsesCanonImagePositionCoordinates() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(path: "/ccapi", body: discovery)
        let jpeg = Data([0xFF, 0xD8, 0x01, 0x02, 0xFF, 0xD9])
        await transport.enqueue(
            method: "GET",
            path: "/ccapi/ver100/shooting/liveview/flipdetail?kind=both&t=10",
            headers: ["content-type": "application/octet-stream"],
            body: detailedLiveView(jpeg: jpeg)
        )
        await transport.enqueue(
            method: "POST",
            path: "/ccapi/ver100/shooting/liveview/clickwb",
            status: 204,
            body: Data()
        )
        await enqueueStatus(on: transport)
        let client = try CCAPIClient(baseURL: "http://192.168.1.2:8080", mode: .camera, transport: transport)

        try await client.initialize()
        _ = try await client.liveViewFrame(cacheKey: 10)
        let status = try await client.clickWhiteBalance(x: 0.4, y: 0.6)

        XCTAssertEqual(status.exposure.whiteBalance, "auto")
        let requests = await transport.requests()
        let click = try XCTUnwrap(requests.first { $0.path.hasSuffix("/shooting/liveview/clickwb") })
        let body = try XCTUnwrap(click.body)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Int])
        XCTAssertEqual(json, ["positionx": 2500, "positiony": 2600])
    }

    func testSimulatorClickWhiteBalanceUsesTheCommandResponse() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(
            method: "POST",
            path: "/ccapi/whitebalance/click",
            body: #"{"connected":true,"battery":{"level":82,"status":"good"},"media":{"available":true,"remaining_minutes":120,"total_bytes":128000000000,"free_bytes":84000000000,"free_images":2418,"devices":2},"exposure":{"iso":"800","shutter":"1/50","aperture":"2.8","white_balance":"click"}}"#
        )
        let client = try CCAPIClient(
            baseURL: "http://127.0.0.1:18080",
            mode: .simulator,
            transport: transport
        )

        let status = try await client.clickWhiteBalance(x: 0.4, y: 0.6)

        XCTAssertEqual(status.exposure.whiteBalance, "click")
        XCTAssertEqual(status.storageFreeImages, 2_418)
        XCTAssertEqual(status.storageDeviceCount, 2)
        let requests = await transport.requests()
        XCTAssertEqual(requests.map(\.path), ["/ccapi/whitebalance/click"])
        let body = try XCTUnwrap(requests.first?.body)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Any])
        XCTAssertEqual(json["x"] as? Double, 0.4)
        XCTAssertEqual(json["y"] as? Double, 0.6)
    }

    func testTapFocusWithoutDetailedFrameSendsNoCommand() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(path: "/ccapi", body: discovery)
        let client = try CCAPIClient(baseURL: "http://192.168.1.2:8080", mode: .camera, transport: transport)

        try await client.initialize()
        do {
            _ = try await client.tapFocus(x: 0.25, y: 0.75)
            XCTFail("Expected missing Live View metadata")
        } catch {
            XCTAssertTrue(error.localizedDescription.contains("detailed Live View frame"))
        }
        do {
            _ = try await client.clickWhiteBalance(x: 0.25, y: 0.75)
            XCTFail("Expected missing Live View metadata")
        } catch {
            XCTAssertTrue(error.localizedDescription.contains("detailed Live View frame"))
        }
        let requests = await transport.requests()
        XCTAssertEqual(requests.count, 1)
    }

    func testTapFocusNeedsBothAdvertisedEndpointAndDetailedLiveView() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(
            path: "/ccapi",
            body: #"{"ver100":[{"path":"/shooting/liveview","post":true,"delete":true},{"path":"/shooting/liveview/afframeposition","put":true},{"path":"/shooting/liveview/clickwb","post":true}]}"#
        )
        let client = try CCAPIClient(baseURL: "http://192.168.1.2:8080", mode: .camera, transport: transport)

        let capabilities = try await client.capabilities()

        XCTAssertFalse(capabilities.matrix.supports(.tapFocus))
        XCTAssertTrue(capabilities.matrix.planned.contains(.tapFocus))
        XCTAssertFalse(capabilities.matrix.supports(.clickWhiteBalance))
        XCTAssertTrue(capabilities.matrix.planned.contains(.clickWhiteBalance))
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

    func testStillImageQualityWritesCanonObjectAndPreservesCompanionFormat() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(path: "/ccapi", body: discovery)
        await transport.enqueueJSON(path: "/ccapi/ver100/shooting/settings", body: settings)
        await transport.enqueue(
            method: "PUT",
            path: "/ccapi/ver100/shooting/settings/stillimagequality",
            status: 204,
            body: Data()
        )
        await enqueueStatus(on: transport)
        let client = try CCAPIClient(baseURL: "http://192.168.1.2:8080", mode: .camera, transport: transport)

        _ = try await client.capabilities()
        do {
            _ = try await client.setSetting(key: "stillimagequality.jpeg", value: "none")
            XCTFail("Expected all-disabled image quality to be rejected")
        } catch {
            XCTAssertEqual(
                error as? CCAPIError,
                .invalidSetting(key: "stillimagequality.jpeg", value: "none")
            )
        }
        _ = try await client.setSetting(key: "stillimagequality.raw", value: "raw")

        let requests = await transport.requests()
        let request = try XCTUnwrap(requests.first {
            $0.method == "PUT" && $0.path == "/ccapi/ver100/shooting/settings/stillimagequality"
        })
        let body = try XCTUnwrap(request.body)
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Any])
        let value = try XCTUnwrap(object["value"] as? [String: String])
        XCTAssertEqual(value, ["raw": "raw", "jpeg": "large_fine"])
    }

    func testWhiteBalanceShiftWritesIntegerObjectAndPreservesCompanionAxis() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(path: "/ccapi", body: discovery)
        await transport.enqueueJSON(path: "/ccapi/ver100/shooting/settings", body: settings)
        await transport.enqueue(
            method: "PUT",
            path: "/ccapi/ver100/shooting/settings/wbshift",
            status: 204,
            body: Data()
        )
        await enqueueStatus(on: transport)
        let client = try CCAPIClient(baseURL: "http://192.168.1.2:8080", mode: .camera, transport: transport)

        _ = try await client.capabilities()
        do {
            _ = try await client.setSetting(key: "wbshift.ba", value: "10")
            XCTFail("Expected out-of-range WB shift to be rejected")
        } catch {
            XCTAssertEqual(error as? CCAPIError, .invalidSetting(key: "wbshift.ba", value: "10"))
        }
        _ = try await client.setSetting(key: "wbshift.ba", value: "9")

        let requests = await transport.requests()
        let request = try XCTUnwrap(requests.first {
            $0.method == "PUT" && $0.path == "/ccapi/ver100/shooting/settings/wbshift"
        })
        let body = try XCTUnwrap(request.body)
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Any])
        let value = try XCTUnwrap(object["value"] as? [String: Int])
        XCTAssertEqual(value, ["ba": 9, "mg": 0])
    }

    func testWhiteBalanceShiftHidesMalformedOrUnboundedRanges() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(
            path: "/ccapi",
            body: #"{"ver100":[{"path":"/shooting/settings","get":true},{"path":"/shooting/settings/wbshift","put":true}]}"#
        )
        await transport.enqueueJSON(
            path: "/ccapi/ver100/shooting/settings",
            body: #"{"wbshift":{"value":{"ba":0,"mg":0},"ability":{"ba":{"min":-1000,"max":1000,"step":1},"mg":{"min":-9,"max":9,"step":0}}}}"#
        )
        let client = try CCAPIClient(baseURL: "http://192.168.1.2:8080", mode: .camera, transport: transport)

        let capabilities = try await client.capabilities()

        XCTAssertFalse(capabilities.settings.contains { $0.key.hasPrefix("wbshift.") })
    }

    func testWhiteBalanceShiftRequiresCompleteIntegerCurrentValue() async throws {
        let malformedSettings = [
            #"{"wbshift":{"value":{"ba":0},"ability":{"ba":{"min":-9,"max":9,"step":1},"mg":{"min":-9,"max":9,"step":1}}}}"#,
            #"{"wbshift":{"value":{"ba":0.5,"mg":0},"ability":{"ba":{"min":-9,"max":9,"step":1},"mg":{"min":-9,"max":9,"step":1}}}}"#,
            #"{"wbshift":{"value":{"ba":false,"mg":0},"ability":{"ba":{"min":-9,"max":9,"step":1},"mg":{"min":-9,"max":9,"step":1}}}}"#,
        ]
        for body in malformedSettings {
            let transport = MockCameraHTTPTransport()
            await transport.enqueueJSON(
                path: "/ccapi",
                body: #"{"ver100":[{"path":"/shooting/settings","get":true},{"path":"/shooting/settings/wbshift","put":true}]}"#
            )
            await transport.enqueueJSON(path: "/ccapi/ver100/shooting/settings", body: body)
            let client = try CCAPIClient(
                baseURL: "http://192.168.1.2:8080",
                mode: .camera,
                transport: transport
            )

            let capabilities = try await client.capabilities()

            XCTAssertFalse(capabilities.settings.contains { $0.key.hasPrefix("wbshift.") })
        }
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

    func testMediaDeleteUsesAdvertisedDeleteOnExactCameraPath() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(path: "/ccapi", body: discovery)
        let path = "/ccapi/ver100/contents/card1/100CANON/IMG_0001.JPG"
        await transport.enqueue(method: "DELETE", path: path, status: 204, body: Data())
        let client = try CCAPIClient(baseURL: "http://192.168.1.2:8080", mode: .camera, transport: transport)

        try await client.deleteMedia(
            CameraMediaItem(id: "http://192.168.1.2:8080\(path)?kind=main", name: "IMG_0001.JPG", kind: "image")
        )

        let requests = await transport.requests()
        XCTAssertEqual(requests.map(\.method), ["GET", "DELETE"])
        XCTAssertEqual(requests.last?.path, path)
    }

    func testMediaDeleteRequiresAdvertisedDeleteWithoutSendingCommand() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(
            path: "/ccapi",
            body: #"{"ver100":[{"path":"/contents","get":true}]}"#
        )
        let client = try CCAPIClient(baseURL: "http://192.168.1.2:8080", mode: .camera, transport: transport)

        do {
            try await client.deleteMedia(
                CameraMediaItem(id: "/ccapi/ver100/contents/card1/IMG_0001.JPG", name: "IMG_0001.JPG", kind: "image")
            )
            XCTFail("Expected unsupported media deletion")
        } catch {
            XCTAssertEqual(error as? CCAPIError, .unsupported(.mediaDelete))
        }
        let requests = await transport.requests()
        XCTAssertEqual(requests.map(\.method), ["GET"])
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
            matrix: CapabilityMatrix(supported: [.cameraIdentity, .liveView, .stillCapture]),
            liveView: LiveViewCapabilities(),
            profile: CameraProfile.from(modelName: "Canon EOS R6 Mark III"),
            evidence: CameraCapabilityEvidence(
                source: "GET /ccapi",
                protocolVersions: ["ver100"],
                advertisedCommands: ["POST /ccapi/ver100/shooting/control/shutterbutton"],
                writableSettings: ["iso", "tv"],
                observedFeatures: [.cameraIdentity, .liveView]
            )
        )
        let snapshot = CameraSnapshot(
            info: CameraInfo(model: "Canon EOS R6 Mark III", serial: "PRIVATE-CAMERA-SERIAL", api: "ccapi"),
            status: CameraStatus(
                mediaAvailable: true,
                storageTotalBytes: 64_000,
                storageFreeBytes: 32_000,
                storageFreeImages: 1_234,
                storageDeviceCount: 1
            ),
            capabilities: capabilities
        )

        let report = CCAPIDiagnosticReport.make(
            baseURL: try XCTUnwrap(URL(string: "http://192.168.1.2:8080")),
            mode: .camera,
            versions: ["/ccapi/ver100"],
            snapshot: snapshot,
            lastError: "Camera PRIVATE-CAMERA-SERIAL rejected a request",
            metadata: DiagnosticReportMetadata(
                productVersion: "9.8.7-test",
                generatedAt: "2026-07-29T00:00:00Z"
            )
        )

        XCTAssertTrue(report.contains("reportSchema=1"))
        XCTAssertTrue(report.contains("generatedAt=2026-07-29T00:00:00Z"))
        XCTAssertTrue(report.contains("productVersion=9.8.7-test"))
        XCTAssertTrue(report.contains("serial=[redacted]"))
        XCTAssertFalse(report.contains("PRIVATE-CAMERA-SERIAL"))
        XCTAssertTrue(report.contains("capabilitySource=GET /ccapi"))
        XCTAssertTrue(report.contains("advertisedCommandCount=1"))
        XCTAssertTrue(report.contains("POST /ccapi/ver100/shooting/control/shutterbutton"))
        XCTAssertTrue(report.contains("writableSettings=iso, tv"))
        XCTAssertTrue(report.contains("observedFeatures=CAMERA_IDENTITY, LIVE_VIEW"))
        XCTAssertTrue(report.contains("advertisedFeatureCount=3"))
        XCTAssertTrue(report.contains("observedFeatureCount=2"))
        XCTAssertTrue(report.contains("validatedAdvertisedFeatureCount=2"))
        XCTAssertTrue(report.contains("unverifiedAdvertisedFeatures=STILL_CAPTURE"))
        XCTAssertTrue(report.contains("observedWithoutAdvertisement=none"))
        XCTAssertTrue(report.contains("storageTotalBytes=64000"))
        XCTAssertTrue(report.contains("storageFreeBytes=32000"))
        XCTAssertTrue(report.contains("storageFreeImages=1234"))
        XCTAssertTrue(report.contains("storageDevices=1"))
    }

    private func enqueueStatus(on transport: MockCameraHTTPTransport, prefix: String = "/ccapi/ver100") async {
        await transport.enqueueJSON(path: "\(prefix)/devicestatus/batterylist", body: #"{"batterylist":[{"level":89}]}"#)
        await transport.enqueueJSON(
            path: "\(prefix)/devicestatus/storage",
            body: #"{"storagelist":[{"name":"card1","maxsize":64000000000,"spacesize":32000000000,"freeimages":-1},{"name":"card2","capacity":128000000000,"freebytes":64000000000,"remainingimages":2400}]}"#
        )
        await transport.enqueueJSON(path: "\(prefix)/shooting/settings", body: settings)
    }

    private func detailedLiveView(jpeg: Data) -> Data {
        let info = Data(
            #"{"liveview":{"image":{"positionx":100,"positiony":200,"positionwidth":6000,"positionheight":4000}}}"#.utf8
        )
        return detailPacket(type: 0x00, payload: jpeg) + detailPacket(type: 0x01, payload: info)
    }

    private func detailPacket(type: UInt8, payload: Data) -> Data {
        let size = UInt32(payload.count).bigEndian
        var result = Data([0xFF, 0x00, type])
        withUnsafeBytes(of: size) { result.append(contentsOf: $0) }
        result.append(payload)
        result.append(contentsOf: [0xFF, 0xFF])
        return result
    }
}
