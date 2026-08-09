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
        {"path":"/functions/datetime","get":true,"put":true},
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
        {"path":"/contents","get":true,"put":true,"delete":true}
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

    private let deviceStatusDiscovery = #"{"ver100":[{"path":"/devicestatus/batterylist","get":true},{"path":"/devicestatus/storage","get":true},{"path":"/shooting/information/recordable","get":true},{"path":"/devicestatus/lens","get":true},{"path":"/devicestatus/temperature","get":true},{"path":"/shooting/settings","get":true},{"path":"/shooting/control/shutterbutton","post":true},{"path":"/shooting/control/recbutton","post":true}]}"#

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
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.bulbExposure))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.shutterHalfPress))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.videoRecording))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.tapFocus))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.clickWhiteBalance))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.mediaDownload))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.mediaDelete))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.mediaProtect))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.mediaRating))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.mediaRotate))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.mediaThumbnail))
        XCTAssertFalse(snapshot.capabilities.matrix.planned.contains(.mediaThumbnail))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.mediaPreview))
        XCTAssertFalse(snapshot.capabilities.matrix.planned.contains(.mediaPreview))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.focusDrive))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.cameraClockSync))
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

    func testDiscoveryLoadsCanonDeveloperAPIListWhenRootOmitsOperations() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(path: "/ccapi", body: #"{"value":"No list of APIs"}"#)
        await transport.enqueueJSON(path: "/ccapi/ver100/topurlfordev", body: discovery)
        await transport.enqueueJSON(path: "/ccapi/ver100/shooting/settings", body: settings)
        let client = try CCAPIClient(
            baseURL: "http://192.168.1.2:8080",
            mode: .camera,
            transport: transport
        )

        try await client.initialize()
        let capabilities = try await client.capabilities()

        XCTAssertTrue(capabilities.matrix.supports(.stillCapture))
        XCTAssertTrue(capabilities.matrix.supports(.videoRecording))
        XCTAssertTrue(capabilities.matrix.supports(.mediaBrowser))
        XCTAssertEqual(
            capabilities.evidence.source,
            "GET /ccapi/ver100/topurlfordev (Canon developer API fallback)"
        )
        XCTAssertTrue(
            capabilities.evidence.advertisedCommands.contains(
                "POST /ccapi/ver100/shooting/control/shutterbutton"
            )
        )
        XCTAssertEqual(capabilities.evidence.discoveryTrace.map(\.outcome), ["NO_API_LIST", "OPERATIONS"])
        XCTAssertEqual(
            capabilities.evidence.discoveryTrace.map(\.endpoint),
            ["GET /ccapi", "GET /ccapi/ver100/topurlfordev"]
        )
        XCTAssertGreaterThan(capabilities.evidence.discoveryTrace.last?.advertisedOperationCount ?? 0, 0)
        let requests = await transport.requests()
        XCTAssertEqual(requests.map(\.path), [
            "/ccapi",
            "/ccapi/ver100/topurlfordev",
            "/ccapi/ver100/shooting/settings",
        ])
    }

    func testDiscoveryLoadsDeveloperAPIListWhenFirmwareReturnsVersionWithoutCommands() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(
            path: "/ccapi",
            body: #"{"api":["/ccapi/ver100"],"version":"ver100","ver100":[]}"#
        )
        await transport.enqueueJSON(path: "/ccapi/ver100/topurlfordev", body: discovery)
        await transport.enqueueJSON(path: "/ccapi/ver100/shooting/settings", body: settings)
        let client = try CCAPIClient(
            baseURL: "http://192.168.1.2:8080",
            mode: .camera,
            transport: transport
        )

        try await client.initialize()
        let capabilities = try await client.capabilities()

        XCTAssertTrue(capabilities.matrix.supports(.liveView))
        XCTAssertTrue(capabilities.matrix.supports(.stillCapture))
        XCTAssertTrue(capabilities.matrix.supports(.videoRecording))
        XCTAssertFalse(capabilities.evidence.advertisedCommands.isEmpty)
        XCTAssertEqual(
            capabilities.evidence.source,
            "GET /ccapi/ver100/topurlfordev (Canon developer API fallback)"
        )
        XCTAssertEqual(capabilities.evidence.discoveryTrace.map(\.outcome), ["ZERO_OPERATIONS", "OPERATIONS"])
        XCTAssertEqual(capabilities.evidence.discoveryTrace.first?.protocolVersions, ["ver100"])
        XCTAssertEqual(capabilities.evidence.discoveryTrace.first?.advertisedOperationCount, 0)
        let requests = await transport.requests()
        XCTAssertEqual(requests.map(\.path), [
            "/ccapi",
            "/ccapi/ver100/topurlfordev",
            "/ccapi/ver100/shooting/settings",
        ])
    }

    func testDiscoveryRejectsEmptyDeveloperAPIListWithoutInventingCapabilities() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(path: "/ccapi", body: #"{"ver100":[]}"#)
        await transport.enqueueJSON(path: "/ccapi/ver100/topurlfordev", body: #"{"ver100":[]}"#)
        await transport.enqueueJSON(path: "/ccapi/", status: 404, body: "{}")
        await transport.enqueueJSON(path: "/ccapi/ver110/deviceinformation", status: 404, body: "{}")
        await transport.enqueueJSON(path: "/ccapi/ver100/deviceinformation", status: 404, body: "{}")
        let client = try CCAPIClient(
            baseURL: "http://192.168.1.2:8080",
            mode: .camera,
            transport: transport
        )

        do {
            try await client.initialize()
            XCTFail("Expected discovery failure")
        } catch {
            XCTAssertTrue(error.localizedDescription.contains("did not advertise any valid operations"))
        }
        let requests = await transport.requests()
        XCTAssertEqual(requests.map(\.path), [
            "/ccapi",
            "/ccapi/ver100/topurlfordev",
            "/ccapi",
            "/ccapi/ver110/deviceinformation",
            "/ccapi/ver100/deviceinformation",
        ])
    }

    func testDiscoveryReportsDeveloperAPIFailureWithoutInventingCapabilities() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(path: "/ccapi", body: #"{"value":"No list of APIs"}"#)
        await transport.enqueueJSON(path: "/ccapi/ver100/topurlfordev", status: 503, body: "camera busy")
        await transport.enqueueJSON(path: "/ccapi/", status: 404, body: "{}")
        await transport.enqueueJSON(path: "/ccapi/ver110/deviceinformation", status: 404, body: "{}")
        await transport.enqueueJSON(path: "/ccapi/ver100/deviceinformation", status: 404, body: "{}")
        let client = try CCAPIClient(
            baseURL: "http://192.168.1.2:8080",
            mode: .camera,
            transport: transport
        )

        do {
            try await client.initialize()
            XCTFail("Expected discovery failure")
        } catch {
            XCTAssertTrue(error.localizedDescription.contains("/ccapi/ver100/topurlfordev"))
        }
        let requests = await transport.requests()
        XCTAssertEqual(requests.map(\.path), [
            "/ccapi",
            "/ccapi/ver100/topurlfordev",
            "/ccapi",
            "/ccapi/ver110/deviceinformation",
            "/ccapi/ver100/deviceinformation",
        ])
    }

    func testDiscoveryTraceRetainsIdentityFallbackWithoutInventingOperations() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(path: "/ccapi", status: 404, body: #"{"message":"missing"}"#)
        await transport.enqueueJSON(path: "/ccapi/", status: 404, body: #"{"message":"missing"}"#)
        await transport.enqueueJSON(
            path: "/ccapi/ver110/deviceinformation",
            body: #"{"productname":"Canon EOS R6 Mark III","serialnumber":"TEST-SERIAL-0001"}"#
        )
        let client = try CCAPIClient(
            baseURL: "http://192.168.1.2:8080",
            mode: .camera,
            transport: transport
        )

        try await client.initialize()
        let capabilities = try await client.capabilities()

        XCTAssertEqual(
            capabilities.evidence.discoveryTrace.map(\.outcome),
            ["HTTP_ERROR", "HTTP_ERROR", "IDENTITY"]
        )
        XCTAssertEqual(capabilities.evidence.discoveryTrace.last?.httpStatus, 200)
        XCTAssertEqual(
            capabilities.evidence.discoveryTrace.last?.responseKeys,
            ["productname", "serialnumber"]
        )
        XCTAssertTrue(capabilities.evidence.advertisedCommands.isEmpty)
        XCTAssertFalse(capabilities.matrix.supports(.stillCapture))
    }

    func testEventPollingRequiresAdvertisedGetDeleteLifecycle() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(
            path: "/ccapi",
            body: #"{"ver110":[{"path":"/event/polling","get":true,"delete":true}]}"#
        )
        await transport.enqueueJSON(
            path: "/ccapi/ver110/event/polling?timeout=long",
            body: #"{"shootingsettings":{"iso":{"value":"1600"}}}"#
        )
        await transport.enqueue(
            method: "DELETE",
            path: "/ccapi/ver110/event/polling",
            status: 204,
            body: Data()
        )
        let client = try CCAPIClient(
            baseURL: "http://192.168.1.2:8080",
            mode: .camera,
            transport: transport
        )

        try await client.initialize()
        let capabilities = try await client.capabilities()
        let event = try await client.pollEvent()
        await client.stopEventPolling()

        XCTAssertTrue(capabilities.matrix.supports(.eventPolling))
        XCTAssertEqual(event.changedKeys, ["shootingsettings"])
        let requests = await transport.requests()
        XCTAssertEqual(requests.map(\.path), [
            "/ccapi",
            "/ccapi/ver110/event/polling?timeout=long",
            "/ccapi/ver110/event/polling",
        ])
        XCTAssertEqual(requests[1].timeoutInterval, 40)
        XCTAssertEqual(requests[2].timeoutInterval, 5)
    }

    func testIncompleteEventLifecycleIsPlannedButUnsupported() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(
            path: "/ccapi",
            body: #"{"ver110":[{"path":"/event/polling","get":true}]}"#
        )
        let client = try CCAPIClient(
            baseURL: "http://192.168.1.2:8080",
            mode: .camera,
            transport: transport
        )

        try await client.initialize()
        let capabilities = try await client.capabilities()

        XCTAssertFalse(capabilities.matrix.supports(.eventPolling))
        XCTAssertTrue(capabilities.matrix.planned.contains(.eventPolling))
        do {
            _ = try await client.pollEvent()
            XCTFail("Expected unsupported event polling")
        } catch {
            XCTAssertEqual(error as? CCAPIError, .unsupported(.eventPolling))
        }
        let requests = await transport.requests()
        XCTAssertEqual(requests.map(\.path), ["/ccapi"])
    }

    func testSimulatorEventPollingAdvancesSequence() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(
            path: "/ccapi/events?after=0",
            body: #"{"sequence":2,"keys":["contents","shootingsettings"]}"#
        )
        await transport.enqueueJSON(
            path: "/ccapi/events?after=2",
            body: #"{"sequence":2,"keys":[]}"#
        )
        let client = try CCAPIClient(
            baseURL: "http://127.0.0.1:18080",
            mode: .simulator,
            transport: transport
        )

        let first = try await client.pollEvent()
        let second = try await client.pollEvent()

        XCTAssertEqual(first.changedKeys, ["contents", "shootingsettings"])
        XCTAssertTrue(second.changedKeys.isEmpty)
        let requests = await transport.requests()
        XCTAssertEqual(requests.map(\.path), ["/ccapi/events?after=0", "/ccapi/events?after=2"])
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

    func testDirectCCAPIPreviewUsesCanonDisplayQueryAndRejectsVideo() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(path: "/ccapi", body: discovery)
        let path = "/ccapi/ver100/contents/card1/100CANON/IMG_0001.JPG"
        let jpeg = Data([0xFF, 0xD8, 0xFF, 0x02, 0xFF, 0xD9])
        await transport.enqueue(
            path: "\(path)?kind=display",
            headers: ["content-type": "application/octet-stream"],
            body: jpeg
        )
        let client = try CCAPIClient(baseURL: "http://192.168.1.2:8080", mode: .camera, transport: transport)

        let preview = try await client.mediaPreview(
            CameraMediaItem(id: "\(path)?kind=main", name: "IMG_0001.CR3", kind: "raw")
        )
        XCTAssertEqual(preview.data, jpeg)
        XCTAssertEqual(preview.contentType, "image/jpeg")

        do {
            _ = try await client.mediaPreview(CameraMediaItem(id: path, name: "MVI_0001.MP4", kind: "video"))
            XCTFail("Expected video preview rejection")
        } catch {
            XCTAssertEqual(
                error as? CCAPIError,
                .invalidResponse("Display preview is available only for camera image items.")
            )
        }
        let requests = await transport.requests()
        XCTAssertEqual(requests.map(\.path), ["/ccapi", "\(path)?kind=display"])
    }

    func testDirectCCAPIPreviewRejectsOversizedResponse() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(path: "/ccapi", body: discovery)
        let path = "/ccapi/ver100/contents/card1/100CANON/IMG_0001.JPG"
        await transport.enqueue(
            path: "\(path)?kind=display",
            headers: ["content-type": "image/jpeg"],
            body: Data(repeating: 0x01, count: 32 * 1024 * 1024 + 1)
        )
        let client = try CCAPIClient(baseURL: "http://192.168.1.2:8080", mode: .camera, transport: transport)

        do {
            _ = try await client.mediaPreview(CameraMediaItem(id: path, name: "IMG_0001.JPG", kind: "image"))
            XCTFail("Expected bounded preview failure")
        } catch {
            XCTAssertTrue(error.localizedDescription.contains("exceeded"))
        }
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

    func testClockSyncUsesCanonRFC1123PayloadAndVerifiesReadback() async throws {
        let transport = MockCameraHTTPTransport()
        let now = Date()
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.timeZone = .current
        formatter.dateFormat = "EEE, dd MMM yyyy HH:mm:ss Z"
        let cameraClock = formatter.string(from: now)
        let daylight = TimeZone.current.isDaylightSavingTime(for: now)
        let response = "{\"datetime\":\"\(cameraClock)\",\"dst\":\(daylight)}"
        await transport.enqueueJSON(path: "/ccapi", body: discovery)
        await transport.enqueueJSON(path: "/ccapi/ver100/shooting/settings", body: settings)
        await transport.enqueueJSON(method: "PUT", path: "/ccapi/ver100/functions/datetime", body: response)
        await transport.enqueueJSON(path: "/ccapi/ver100/functions/datetime", body: response)
        await enqueueStatus(on: transport)
        let client = try CCAPIClient(
            baseURL: "http://192.168.1.2:8080",
            mode: .camera,
            transport: transport
        )

        let capabilities = try await client.capabilities()
        let status = try await client.syncCameraClock()

        XCTAssertTrue(capabilities.matrix.supports(.cameraClockSync))
        XCTAssertTrue(status.connected)
        let requests = await transport.requests()
        let write = try XCTUnwrap(requests.first { $0.path.hasSuffix("/functions/datetime") && $0.method == "PUT" })
        let body = try XCTUnwrap(write.body)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Any])
        let rawDateTime = try XCTUnwrap(json["datetime"] as? String)
        XCTAssertNotNil(
            rawDateTime.range(
                of: #"^[A-Z][a-z]{2}, \d{2} [A-Z][a-z]{2} \d{4} \d{2}:\d{2}:\d{2} [+-]\d{4}$"#,
                options: .regularExpression
            )
        )
        XCTAssertNotNil(json["dst"] as? Bool)
        let observedCapabilities = try await client.capabilities()
        XCTAssertTrue(observedCapabilities.evidence.observedFeatures.contains(.cameraClockSync))
    }

    func testClockSyncDoesNotCombineReadAndWriteAcrossAPIVersions() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(
            path: "/ccapi",
            body: """
            {
              "ver100": [
                {"path":"/shooting/settings","get":true},
                {"path":"/functions/datetime","get":true}
              ],
              "ver110": [{"path":"/functions/datetime","put":true}]
            }
            """
        )
        await transport.enqueueJSON(path: "/ccapi/ver100/shooting/settings", body: settings)
        let client = try CCAPIClient(
            baseURL: "http://192.168.1.2:8080",
            mode: .camera,
            transport: transport
        )

        let capabilities = try await client.capabilities()

        XCTAssertFalse(capabilities.matrix.supports(.cameraClockSync))
        XCTAssertTrue(capabilities.matrix.planned.contains(.cameraClockSync))
        do {
            _ = try await client.syncCameraClock()
            XCTFail("Expected unsupported camera clock synchronization")
        } catch {
            XCTAssertEqual(error as? CCAPIError, .unsupported(.cameraClockSync))
        }
        let requests = await transport.requests()
        XCTAssertEqual(requests.count, 2)
    }

    func testSensorCleaningUsesAdvertisedCanonPostAndExactBooleanPayload() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(
            path: "/ccapi",
            body: #"{"ver110":[{"path":"/functions/sensorcleaning","post":true}]}"#
        )
        await transport.enqueueJSON(
            method: "POST",
            path: "/ccapi/ver110/functions/sensorcleaning",
            body: "{}"
        )
        let client = try CCAPIClient(
            baseURL: "http://192.168.1.2:8080",
            mode: .camera,
            transport: transport
        )

        let capabilities = try await client.capabilities()
        try await client.cleanSensor(autoPowerOff: true)

        XCTAssertTrue(capabilities.matrix.supports(.sensorCleaning))
        let requests = await transport.requests()
        let request = try XCTUnwrap(requests.first { $0.path.hasSuffix("/functions/sensorcleaning") })
        let body = try XCTUnwrap(request.body)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Any])
        XCTAssertEqual(json["autopoweroff"] as? Bool, true)
        let observedCapabilities = try await client.capabilities()
        XCTAssertTrue(observedCapabilities.evidence.observedFeatures.contains(.sensorCleaning))
    }

    func testSensorCleaningRejectsNoncanonicalSuccessStatus() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(
            path: "/ccapi",
            body: #"{"ver100":[{"path":"/functions/sensorcleaning","post":true}]}"#
        )
        await transport.enqueue(
            method: "POST",
            path: "/ccapi/ver100/functions/sensorcleaning",
            status: 204,
            body: Data()
        )
        let client = try CCAPIClient(
            baseURL: "http://192.168.1.2:8080",
            mode: .camera,
            transport: transport
        )

        do {
            try await client.cleanSensor(autoPowerOff: false)
            XCTFail("Expected Canon sensor cleaning to require HTTP 200")
        } catch {
            XCTAssertTrue(error.localizedDescription.contains("expected HTTP 200"))
        }
        let capabilities = try await client.capabilities()
        XCTAssertFalse(capabilities.evidence.observedFeatures.contains(.sensorCleaning))
    }

    func testUnadvertisedSensorCleaningFailsWithoutSendingACommand() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(
            path: "/ccapi",
            body: #"{"ver100":[{"path":"/deviceinformation","get":true}]}"#
        )
        let client = try CCAPIClient(
            baseURL: "http://192.168.1.2:8080",
            mode: .camera,
            transport: transport
        )

        do {
            try await client.cleanSensor(autoPowerOff: false)
            XCTFail("Expected unsupported sensor cleaning")
        } catch {
            XCTAssertEqual(error as? CCAPIError, .unsupported(.sensorCleaning))
        }
        let requests = await transport.requests()
        XCTAssertEqual(requests.count, 1)
        let capabilities = try await client.capabilities()
        XCTAssertTrue(capabilities.matrix.planned.contains(.sensorCleaning))
        XCTAssertFalse(capabilities.evidence.observedFeatures.contains(.sensorCleaning))
    }

    func testSimulatorSensorCleaningUsesBackedEndpoint() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(path: "/ccapi/capabilities", body: "{}")
        await transport.enqueue(
            method: "POST",
            path: "/ccapi/sensor-cleaning",
            status: 204,
            body: Data()
        )
        let client = try CCAPIClient(
            baseURL: "http://127.0.0.1:18080",
            mode: .simulator,
            transport: transport
        )

        let capabilities = try await client.capabilities()
        try await client.cleanSensor(autoPowerOff: false)

        XCTAssertTrue(capabilities.matrix.supports(.sensorCleaning))
        let requests = await transport.requests()
        let request = try XCTUnwrap(requests.first { $0.path == "/ccapi/sensor-cleaning" })
        let body = try XCTUnwrap(request.body)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Any])
        XCTAssertEqual(json["autopoweroff"] as? Bool, false)
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

    func testSimulatorFocusDriveUsesContractAndIsAdvertised() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(
            path: "/ccapi/capabilities",
            body: #"{"iso":["800"],"shutter":["1/50"],"aperture":["2.8"],"white_balance":["auto"]}"#
        )
        await transport.enqueueJSON(
            method: "POST",
            path: "/ccapi/focus/drive",
            body: #"{"ok":true,"direction":"near","step":"large"}"#
        )
        let client = try CCAPIClient(
            baseURL: "http://127.0.0.1:18080",
            mode: .simulator,
            transport: transport
        )

        let capabilities = try await client.capabilities()
        let result = try await client.driveFocus(direction: .near, step: .large)

        XCTAssertTrue(capabilities.matrix.supports(.focusDrive))
        XCTAssertTrue(capabilities.matrix.supports(.bulbExposure))
        XCTAssertFalse(capabilities.matrix.planned.contains(.focusDrive))
        XCTAssertEqual(result, FocusDriveResult(accepted: true, direction: .near, step: .large))
        let requests = await transport.requests()
        let request = try XCTUnwrap(requests.first { $0.path == "/ccapi/focus/drive" })
        let body = try XCTUnwrap(request.body)
        let json = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: String])
        XCTAssertEqual(json, ["direction": "near", "step": "large"])
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

    func testBulbExposureUsesManualPressAndReleaseWithoutPollingWhilePressed() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(path: "/ccapi", body: discovery)
        await enqueueStatus(on: transport)
        await transport.enqueue(
            method: "PUT",
            path: "/ccapi/ver100/shooting/control/shutterbutton/manual",
            status: 204,
            body: Data()
        )
        await transport.enqueue(
            method: "PUT",
            path: "/ccapi/ver100/shooting/control/shutterbutton/manual",
            status: 204,
            body: Data()
        )
        await enqueueStatus(on: transport)
        let client = try CCAPIClient(baseURL: "http://192.168.1.2:8080", mode: .camera, transport: transport)

        let started = try await client.startBulbExposure()
        XCTAssertEqual(started.bulbExposureActive, true)
        let startRequests = await transport.requests()
        XCTAssertEqual(startRequests.last?.path, "/ccapi/ver100/shooting/control/shutterbutton/manual")

        let stopped = try await client.stopBulbExposure()
        XCTAssertEqual(stopped.bulbExposureActive, false)

        let commands = await transport.requests().filter { $0.path.contains("shutterbutton/manual") }
        XCTAssertEqual(commands.map(\.method), ["PUT", "PUT"])
        let payloads = try commands.map { request -> [String: Any] in
            let body = try XCTUnwrap(request.body)
            return try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Any])
        }
        XCTAssertEqual(payloads[0]["action"] as? String, "full_press")
        XCTAssertEqual(payloads[0]["af"] as? Bool, false)
        XCTAssertEqual(payloads[1]["action"] as? String, "release")
        XCTAssertEqual(payloads[1]["af"] as? Bool, false)
        let remainingResponses = await transport.remainingResponses()
        XCTAssertEqual(remainingResponses, 0)
    }

    func testFailedBulbPressStillAttemptsRelease() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(path: "/ccapi", body: discovery)
        await enqueueStatus(on: transport)
        await transport.enqueueJSON(
            method: "PUT",
            path: "/ccapi/ver100/shooting/control/shutterbutton/manual",
            status: 503,
            body: #"{"message":"press response lost"}"#
        )
        await transport.enqueue(
            method: "PUT",
            path: "/ccapi/ver100/shooting/control/shutterbutton/manual",
            status: 204,
            body: Data()
        )
        let client = try CCAPIClient(baseURL: "http://192.168.1.2:8080", mode: .camera, transport: transport)

        do {
            _ = try await client.startBulbExposure()
            XCTFail("Expected Bulb press failure")
        } catch {
            XCTAssertTrue(error.localizedDescription.contains("503"))
        }

        let commands = await transport.requests().filter { $0.path.contains("shutterbutton/manual") }
        let actions = try commands.map { request -> String in
            let body = try XCTUnwrap(request.body)
            let json = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Any])
            return try XCTUnwrap(json["action"] as? String)
        }
        XCTAssertEqual(actions, ["full_press", "release"])
        let remainingResponses = await transport.remainingResponses()
        XCTAssertEqual(remainingResponses, 0)
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
            body: #"{"connected":true,"battery":{"level":82,"status":"good"},"recordable_shots":2418,"remaining_recording_seconds":7200,"media":{"available":true,"remaining_minutes":120,"total_bytes":128000000000,"free_bytes":84000000000,"free_images":2418,"devices":2},"exposure":{"iso":"800","shutter":"1/50","aperture":"2.8","white_balance":"click"}}"#
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
        XCTAssertEqual(status.recordableShots, 2_418)
        XCTAssertEqual(status.remainingRecordingSeconds, 7_200)
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

    func testCanonZoomRequiresMatchingGetPostAndWritesIntegerValue() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(
            path: "/ccapi",
            body: #"{"ver100":[{"path":"/devicestatus/batterylist","get":true},{"path":"/devicestatus/storage","get":true},{"path":"/shooting/settings","get":true},{"path":"/shooting/control/zoom","get":true,"post":true}]}"#
        )
        await transport.enqueueJSON(path: "/ccapi/ver100/shooting/settings", body: "{}")
        await transport.enqueueJSON(
            path: "/ccapi/ver100/shooting/control/zoom",
            body: #"{"value":50,"ability":{"min":0,"max":100,"step":25}}"#
        )
        await transport.enqueueJSON(
            method: "POST",
            path: "/ccapi/ver100/shooting/control/zoom",
            body: #"{"value":75}"#
        )
        await transport.enqueueJSON(
            path: "/ccapi/ver100/devicestatus/batterylist",
            body: #"{"batterylist":[{"level":89}]}"#
        )
        await transport.enqueueJSON(
            path: "/ccapi/ver100/devicestatus/storage",
            body: #"{"storagelist":[{"name":"card1","spacesize":32000000000}]}"#
        )
        await transport.enqueueJSON(path: "/ccapi/ver100/shooting/settings", body: "{}")
        await transport.enqueueJSON(
            path: "/ccapi/ver100/shooting/control/zoom",
            body: #"{"value":75,"ability":{"min":0,"max":100,"step":25}}"#
        )
        let client = try CCAPIClient(baseURL: "http://192.168.1.2:8080", mode: .camera, transport: transport)

        let capabilities = try await client.capabilities()
        XCTAssertEqual(capabilities.setting("zoom")?.values, ["0", "25", "50", "75", "100"])
        XCTAssertEqual(capabilities.setting("zoom")?.value, "50")
        XCTAssertTrue(capabilities.matrix.supports(.zoomControl))

        _ = try await client.setSetting(key: "zoom", value: "75")

        let requests = await transport.requests()
        let write = try XCTUnwrap(requests.first {
            $0.method == "POST" && $0.path == "/ccapi/ver100/shooting/control/zoom"
        })
        let body = try XCTUnwrap(write.body)
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Any])
        XCTAssertEqual(object["value"] as? Int, 75)
    }

    func testCanonZoomIsHiddenWithoutMatchingPost() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(
            path: "/ccapi",
            body: #"{"ver100":[{"path":"/shooting/settings","get":true},{"path":"/shooting/control/zoom","get":true}]}"#
        )
        await transport.enqueueJSON(path: "/ccapi/ver100/shooting/settings", body: "{}")
        let client = try CCAPIClient(baseURL: "http://192.168.1.2:8080", mode: .camera, transport: transport)

        let capabilities = try await client.capabilities()

        XCTAssertNil(capabilities.setting("zoom"))
        XCTAssertFalse(capabilities.matrix.supports(.zoomControl))
        XCTAssertTrue(capabilities.matrix.planned.contains(.zoomControl))
        let requestCount = await transport.requests().count
        XCTAssertEqual(requestCount, 2)
    }

    func testCanonSoundRecordingLevelRequiresMatchingGetPutAndWritesIntegerValue() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(
            path: "/ccapi",
            body: #"{"ver100":[{"path":"/devicestatus/batterylist","get":true},{"path":"/devicestatus/storage","get":true},{"path":"/shooting/settings","get":true},{"path":"/shooting/settings/soundrecording/level","get":true,"put":true}]}"#
        )
        await transport.enqueueJSON(path: "/ccapi/ver100/shooting/settings", body: "{}")
        await transport.enqueueJSON(
            path: "/ccapi/ver100/shooting/settings/soundrecording/level",
            body: #"{"value":32,"ability":{"min":0,"max":63,"step":1}}"#
        )
        for _ in 0..<2 {
            await transport.enqueueJSON(path: "/ccapi/ver100/shooting/settings", body: "{}")
            await transport.enqueueJSON(
                path: "/ccapi/ver100/shooting/settings/soundrecording/level",
                body: #"{"value":32,"ability":{"min":0,"max":63,"step":1}}"#
            )
        }
        await transport.enqueueJSON(
            method: "PUT",
            path: "/ccapi/ver100/shooting/settings/soundrecording/level",
            body: #"{"value":48}"#
        )
        await enqueueStatus(on: transport)
        await transport.enqueueJSON(
            path: "/ccapi/ver100/shooting/settings/soundrecording/level",
            body: #"{"value":48,"ability":{"min":0,"max":63,"step":1}}"#
        )
        let client = try CCAPIClient(
            baseURL: "http://192.168.1.2:8080",
            mode: .camera,
            transport: transport
        )

        let capabilities = try await client.capabilities()
        XCTAssertEqual(capabilities.setting("soundrecordinglevel")?.value, "32")
        XCTAssertEqual(capabilities.setting("soundrecordinglevel")?.values, (0...63).map(String.init))
        XCTAssertTrue(capabilities.matrix.supports(.soundRecordingLevelControl))
        XCTAssertTrue(capabilities.evidence.writableSettings.contains("soundrecordinglevel"))
        let requestCount = await transport.requests().count
        do {
            _ = try await client.setSetting(key: "soundrecordinglevel", value: "64")
            XCTFail("Expected an unadvertised sound-recording level to be rejected")
        } catch {
            XCTAssertEqual(
                error as? CCAPIError,
                .invalidSetting(key: "soundrecordinglevel", value: "64")
            )
        }
        let requestsAfterRejection = await transport.requests()
        XCTAssertEqual(requestsAfterRejection.count, requestCount + 2)
        XCTAssertFalse(requestsAfterRejection.contains(where: { $0.method == "PUT" }))

        _ = try await client.setSetting(key: "soundrecordinglevel", value: "48")

        let requests = await transport.requests()
        let write = try XCTUnwrap(requests.first {
            $0.method == "PUT" && $0.path == "/ccapi/ver100/shooting/settings/soundrecording/level"
        })
        let body = try XCTUnwrap(write.body)
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Any])
        XCTAssertEqual(object["value"] as? Int, 48)
    }

    func testCanonSoundRecordingLevelRejectsMalformedRanges() async throws {
        let responses = [
            #"{"value":false,"ability":{"min":0,"max":63,"step":1}}"#,
            #"{"value":32.0,"ability":{"min":0,"max":63,"step":1}}"#,
            #"{"value":32,"ability":{"min":0,"max":1000,"step":1}}"#,
            #"{"value":32,"ability":{"min":0,"max":63,"step":0}}"#,
            #"{"value":33,"ability":{"min":0,"max":63,"step":2}}"#,
            #"{"value":32,"ability":{"min":32,"max":32,"step":1}}"#,
        ]

        for response in responses {
            let transport = MockCameraHTTPTransport()
            await transport.enqueueJSON(
                path: "/ccapi",
                body: #"{"ver100":[{"path":"/shooting/settings","get":true},{"path":"/shooting/settings/soundrecording/level","get":true,"put":true}]}"#
            )
            await transport.enqueueJSON(path: "/ccapi/ver100/shooting/settings", body: "{}")
            await transport.enqueueJSON(
                path: "/ccapi/ver100/shooting/settings/soundrecording/level",
                body: response
            )
            let client = try CCAPIClient(
                baseURL: "http://192.168.1.2:8080",
                mode: .camera,
                transport: transport
            )

            let capabilities = try await client.capabilities()

            XCTAssertNil(capabilities.setting("soundrecordinglevel"))
            XCTAssertFalse(capabilities.matrix.supports(.soundRecordingLevelControl))
            XCTAssertTrue(capabilities.matrix.planned.contains(.soundRecordingLevelControl))
        }
    }

    func testCanonSoundRecordingLevelDoesNotCombineGetPutAcrossVersions() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(
            path: "/ccapi",
            body: #"{"ver100":[{"path":"/shooting/settings","get":true},{"path":"/shooting/settings/soundrecording/level","get":true}],"ver110":[{"path":"/shooting/settings/soundrecording/level","put":true}]}"#
        )
        await transport.enqueueJSON(path: "/ccapi/ver100/shooting/settings", body: "{}")
        let client = try CCAPIClient(
            baseURL: "http://192.168.1.2:8080",
            mode: .camera,
            transport: transport
        )

        let capabilities = try await client.capabilities()

        XCTAssertNil(capabilities.setting("soundrecordinglevel"))
        XCTAssertFalse(capabilities.matrix.supports(.soundRecordingLevelControl))
        let requests = await transport.requests()
        XCTAssertEqual(requests.count, 2)
        XCTAssertFalse(requests.contains(where: { $0.path.contains("soundrecording/level") }))
    }

    func testCanonSoundRecordingControlsRequireMatchingPairAndRefreshBeforeStringWrite() async throws {
        let transport = MockCameraHTTPTransport()
        let path = "/ccapi/ver100/shooting/settings/soundrecording/windfilter"
        let advertised = #"{"value":"auto","ability":["auto","enable","disable"]}"#
        await transport.enqueueJSON(
            path: "/ccapi",
            body: #"{"ver100":[{"path":"/devicestatus/batterylist","get":true},{"path":"/devicestatus/storage","get":true},{"path":"/shooting/settings","get":true},{"path":"/shooting/settings/soundrecording/windfilter","get":true,"put":true}]}"#
        )
        await transport.enqueueJSON(path: "/ccapi/ver100/shooting/settings", body: "{}")
        await transport.enqueueJSON(path: path, body: advertised)
        await transport.enqueueJSON(path: "/ccapi/ver100/shooting/settings", body: "{}")
        await transport.enqueueJSON(path: path, body: #"{"value":"auto","ability":["auto","disable"]}"#)
        await transport.enqueueJSON(path: "/ccapi/ver100/shooting/settings", body: "{}")
        await transport.enqueueJSON(path: path, body: advertised)
        await transport.enqueueJSON(method: "PUT", path: path, body: #"{"value":"enable"}"#)
        await enqueueStatus(on: transport)
        await transport.enqueueJSON(path: path, body: #"{"value":"enable","ability":["auto","enable","disable"]}"#)
        let client = try CCAPIClient(
            baseURL: "http://192.168.1.2:8080",
            mode: .camera,
            transport: transport
        )

        let capabilities = try await client.capabilities()
        XCTAssertEqual(capabilities.setting("windfilter")?.values, ["auto", "enable", "disable"])
        XCTAssertTrue(capabilities.matrix.supports(.soundRecordingControl))
        XCTAssertTrue(capabilities.evidence.writableSettings.contains("windfilter"))

        do {
            _ = try await client.setSetting(key: "windfilter", value: "enable")
            XCTFail("Expected a stale wind-filter option to be rejected")
        } catch {
            XCTAssertEqual(error as? CCAPIError, .invalidSetting(key: "windfilter", value: "enable"))
        }

        _ = try await client.setSetting(key: "windfilter", value: "enable")

        let requests = await transport.requests()
        let write = try XCTUnwrap(requests.first { $0.method == "PUT" && $0.path == path })
        let body = try XCTUnwrap(write.body)
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Any])
        XCTAssertEqual(object["value"] as? String, "enable")
    }

    func testCanonSoundRecordingControlsRejectMalformedStringAbilities() async throws {
        let responses = [
            #"{"value":"on","ability":["enable","disable"]}"#,
            #"{"value":"auto","ability":["auto","auto"]}"#,
            #"{"value":"auto","ability":["auto"]}"#,
            #"{"value":"auto","ability":["auto",1]}"#,
            #"{"value":1,"ability":["auto","disable"]}"#,
        ]

        for response in responses {
            let transport = MockCameraHTTPTransport()
            await transport.enqueueJSON(
                path: "/ccapi",
                body: #"{"ver100":[{"path":"/shooting/settings","get":true},{"path":"/shooting/settings/soundrecording/attenuator","get":true,"put":true}]}"#
            )
            await transport.enqueueJSON(path: "/ccapi/ver100/shooting/settings", body: "{}")
            await transport.enqueueJSON(
                path: "/ccapi/ver100/shooting/settings/soundrecording/attenuator",
                body: response
            )
            let client = try CCAPIClient(
                baseURL: "http://192.168.1.2:8080",
                mode: .camera,
                transport: transport
            )

            let capabilities = try await client.capabilities()

            XCTAssertNil(capabilities.setting("attenuator"))
            XCTAssertFalse(capabilities.matrix.supports(.soundRecordingControl))
            XCTAssertTrue(capabilities.matrix.planned.contains(.soundRecordingControl))
        }
    }

    func testCanonSoundRecordingControlsDoNotCombineGetPutAcrossVersions() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(
            path: "/ccapi",
            body: #"{"ver100":[{"path":"/shooting/settings","get":true},{"path":"/shooting/settings/soundrecording","get":true}],"ver110":[{"path":"/shooting/settings/soundrecording","put":true}]}"#
        )
        await transport.enqueueJSON(path: "/ccapi/ver100/shooting/settings", body: "{}")
        let client = try CCAPIClient(
            baseURL: "http://192.168.1.2:8080",
            mode: .camera,
            transport: transport
        )

        let capabilities = try await client.capabilities()

        XCTAssertNil(capabilities.setting("soundrecording"))
        XCTAssertFalse(capabilities.matrix.supports(.soundRecordingControl))
        let requests = await transport.requests()
        XCTAssertEqual(requests.count, 2)
        XCTAssertFalse(requests.contains(where: { $0.path.hasSuffix("/soundrecording") }))
    }

    func testCanonSoundRecordingControlsDoNotTreatAggregateSettingsAsEndpointGet() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(
            path: "/ccapi",
            body: #"{"ver100":[{"path":"/shooting/settings","get":true},{"path":"/shooting/settings/soundrecording/windfilter","put":true}]}"#
        )
        await transport.enqueueJSON(
            path: "/ccapi/ver100/shooting/settings",
            body: #"{"windfilter":{"value":"auto","ability":["auto","enable","disable"]}}"#
        )
        let client = try CCAPIClient(
            baseURL: "http://192.168.1.2:8080",
            mode: .camera,
            transport: transport
        )

        let capabilities = try await client.capabilities()

        XCTAssertNil(capabilities.setting("windfilter"))
        XCTAssertFalse(capabilities.matrix.supports(.soundRecordingControl))
        let requests = await transport.requests()
        XCTAssertEqual(requests.count, 2)
    }

    func testCanonFocusBracketingRequiresExactPairsAndWritesIntegerAfterRefresh() async throws {
        let transport = MockCameraHTTPTransport()
        let rootPath = "/ccapi/ver100/shooting/settings/focusbracketing"
        let smoothingPath = "\(rootPath)/exposuresmoothing"
        let shotsPath = "\(rootPath)/numberofshots"
        let incrementPath = "\(rootPath)/focusincrement"
        let root = #"{"value":"disable","ability":["enable","disable"]}"#
        let smoothing = #"{"value":"disable","ability":["enable","disable"]}"#
        let shots = #"{"value":100,"ability":{"min":2,"max":999,"step":1}}"#
        let increment = #"{"value":4,"ability":{"min":1,"max":10,"step":1}}"#
        await transport.enqueueJSON(
            path: "/ccapi",
            body: #"{"ver100":[{"path":"/devicestatus/batterylist","get":true},{"path":"/devicestatus/storage","get":true},{"path":"/shooting/settings","get":true},{"path":"/shooting/settings/focusbracketing","get":true,"put":true},{"path":"/shooting/settings/focusbracketing/numberofshots","get":true,"put":true},{"path":"/shooting/settings/focusbracketing/focusincrement","get":true,"put":true},{"path":"/shooting/settings/focusbracketing/exposuresmoothing","get":true,"put":true}]}"#
        )
        await transport.enqueueJSON(path: "/ccapi/ver100/shooting/settings", body: "{}")
        for (path, body) in [(rootPath, root), (smoothingPath, smoothing), (shotsPath, shots), (incrementPath, increment)] {
            await transport.enqueueJSON(path: path, body: body)
        }
        await transport.enqueueJSON(path: "/ccapi/ver100/shooting/settings", body: "{}")
        for (path, body) in [(rootPath, root), (smoothingPath, smoothing), (shotsPath, shots), (incrementPath, increment)] {
            await transport.enqueueJSON(path: path, body: body)
        }
        await transport.enqueueJSON(method: "PUT", path: shotsPath, body: #"{"value":250}"#)
        await enqueueStatus(on: transport)
        for (path, body) in [
            (rootPath, root),
            (smoothingPath, smoothing),
            (shotsPath, #"{"value":250,"ability":{"min":2,"max":999,"step":1}}"#),
            (incrementPath, increment),
        ] {
            await transport.enqueueJSON(path: path, body: body)
        }
        let client = try CCAPIClient(
            baseURL: "http://192.168.1.2:8080",
            mode: .camera,
            transport: transport
        )

        let capabilities = try await client.capabilities()

        XCTAssertTrue(capabilities.matrix.supports(.focusBracketingControl))
        XCTAssertEqual(capabilities.setting("focusbracketing")?.values, ["enable", "disable"])
        XCTAssertEqual(capabilities.setting("focusbracketingnumberofshots")?.values, (2...999).map(String.init))
        XCTAssertEqual(capabilities.setting("focusbracketingfocusincrement")?.values, (1...10).map(String.init))
        XCTAssertTrue(capabilities.evidence.writableSettings.contains("focusbracketing"))

        _ = try await client.setSetting(key: "focusbracketingnumberofshots", value: "250")

        let requests = await transport.requests()
        let write = try XCTUnwrap(requests.first { $0.method == "PUT" && $0.path == shotsPath })
        let body = try XCTUnwrap(write.body)
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Any])
        XCTAssertEqual(object["value"] as? Int, 250)
        XCTAssertGreaterThanOrEqual(requests.filter { $0.path == shotsPath }.count, 3)
    }

    func testCanonFocusBracketingMalformedRootHidesGroupWithoutChildReads() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(
            path: "/ccapi",
            body: #"{"ver100":[{"path":"/shooting/settings","get":true},{"path":"/shooting/settings/focusbracketing","get":true,"put":true},{"path":"/shooting/settings/focusbracketing/numberofshots","get":true,"put":true},{"path":"/shooting/settings/focusbracketing/focusincrement","get":true,"put":true},{"path":"/shooting/settings/focusbracketing/exposuresmoothing","get":true,"put":true}]}"#
        )
        await transport.enqueueJSON(
            path: "/ccapi/ver100/shooting/settings",
            body: #"{"focusbracketing":{"value":"disable","ability":["enable","disable"]}}"#
        )
        await transport.enqueueJSON(
            path: "/ccapi/ver100/shooting/settings/focusbracketing",
            body: #"{"value":"disable","ability":["disable","disable"]}"#
        )
        let client = try CCAPIClient(
            baseURL: "http://192.168.1.2:8080",
            mode: .camera,
            transport: transport
        )

        let capabilities = try await client.capabilities()

        XCTAssertFalse(capabilities.matrix.supports(.focusBracketingControl))
        XCTAssertTrue(capabilities.matrix.planned.contains(.focusBracketingControl))
        XCTAssertFalse(capabilities.settings.contains { $0.key.hasPrefix("focusbracketing") })
        let requests = await transport.requests()
        XCTAssertEqual(requests.filter { $0.path.contains("/focusbracketing") }.count, 1)
    }

    func testCanonFocusBracketingDoesNotCombineGetPutAcrossVersions() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(
            path: "/ccapi",
            body: #"{"ver100":[{"path":"/shooting/settings","get":true},{"path":"/shooting/settings/focusbracketing","get":true}],"ver110":[{"path":"/shooting/settings/focusbracketing","put":true}]}"#
        )
        await transport.enqueueJSON(
            path: "/ccapi/ver100/shooting/settings",
            body: #"{"focusbracketing":{"value":"disable","ability":["enable","disable"]}}"#
        )
        let client = try CCAPIClient(
            baseURL: "http://192.168.1.2:8080",
            mode: .camera,
            transport: transport
        )

        let capabilities = try await client.capabilities()

        XCTAssertFalse(capabilities.matrix.supports(.focusBracketingControl))
        XCTAssertNil(capabilities.setting("focusbracketing"))
        let requests = await transport.requests()
        XCTAssertEqual(requests.count, 2)
    }

    func testCanonDeviceFunctionSettingsRequirePairsRefreshAndWriteAdvertisedValue() async throws {
        let transport = MockCameraHTTPTransport()
        let beepPath = "/ccapi/ver100/functions/beep"
        let displayPath = "/ccapi/ver100/functions/displayoff"
        let beep = #"{"value":"enable","ability":["enable","disable","disabletouch"]}"#
        let display = #"{"value":"60","ability":["10","20","30","60","120","180"]}"#
        await transport.enqueueJSON(
            path: "/ccapi",
            body: #"{"ver100":[{"path":"/devicestatus/batterylist","get":true},{"path":"/devicestatus/storage","get":true},{"path":"/shooting/settings","get":true},{"path":"/functions/beep","get":true,"put":true},{"path":"/functions/displayoff","get":true,"put":true}]}"#
        )
        for _ in 0..<2 {
            await transport.enqueueJSON(path: "/ccapi/ver100/shooting/settings", body: "{}")
            await transport.enqueueJSON(path: beepPath, body: beep)
            await transport.enqueueJSON(path: displayPath, body: display)
        }
        await transport.enqueueJSON(method: "PUT", path: beepPath, body: #"{"value":"disabletouch"}"#)
        await enqueueStatus(on: transport)
        await transport.enqueueJSON(
            path: beepPath,
            body: #"{"value":"disabletouch","ability":["enable","disable","disabletouch"]}"#
        )
        await transport.enqueueJSON(path: displayPath, body: display)
        let client = try CCAPIClient(
            baseURL: "http://192.168.1.2:8080",
            mode: .camera,
            transport: transport
        )

        let capabilities = try await client.capabilities()

        XCTAssertEqual(capabilities.setting("beep")?.values, ["enable", "disable", "disabletouch"])
        XCTAssertEqual(capabilities.setting("displayoff")?.value, "60")
        XCTAssertTrue(capabilities.evidence.writableSettings.contains("beep"))
        XCTAssertTrue(capabilities.evidence.writableSettings.contains("displayoff"))

        _ = try await client.setSetting(key: "beep", value: "disabletouch")

        let requests = await transport.requests()
        let write = try XCTUnwrap(requests.first { $0.method == "PUT" && $0.path == beepPath })
        let body = try XCTUnwrap(write.body)
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: String])
        XCTAssertEqual(object, ["value": "disabletouch"])
        XCTAssertGreaterThanOrEqual(requests.filter { $0.path == beepPath }.count, 4)
    }

    func testCanonDeviceFunctionSettingsRejectMalformedAndCrossVersionContracts() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(
            path: "/ccapi",
            body: #"{"ver100":[{"path":"/shooting/settings","get":true},{"path":"/functions/beep","get":true},{"path":"/functions/displayoff","get":true,"put":true}],"ver110":[{"path":"/functions/beep","put":true}]}"#
        )
        await transport.enqueueJSON(path: "/ccapi/ver100/shooting/settings", body: "{}")
        await transport.enqueueJSON(
            path: "/ccapi/ver100/functions/displayoff",
            body: #"{"value":"60","ability":["60","future"]}"#
        )
        let client = try CCAPIClient(
            baseURL: "http://192.168.1.2:8080",
            mode: .camera,
            transport: transport
        )

        let capabilities = try await client.capabilities()

        XCTAssertNil(capabilities.setting("beep"))
        XCTAssertNil(capabilities.setting("displayoff"))
        let requests = await transport.requests()
        XCTAssertFalse(requests.contains { $0.path.hasSuffix("/functions/beep") })
        XCTAssertEqual(requests.count, 3)
    }

    func testSimulatorDeviceFunctionSettingsUseBackedEndpoint() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(
            path: "/ccapi/capabilities",
            body: #"{"iso":["800"],"shutter":["1/50"],"aperture":["2.8"],"white_balance":["auto"],"beep":{"value":"enable","ability":["enable","disable","disabletouch"]},"displayoff":{"value":"60","ability":["10","20","30","60","120","180"]}}"#
        )
        await transport.enqueue(method: "PUT", path: "/ccapi/device-settings/beep", status: 204, body: Data())
        await transport.enqueueJSON(
            path: "/ccapi/status",
            body: #"{"connected":true,"battery":{"level":82,"status":"good"},"media":{"available":true,"remaining_minutes":120},"exposure":{"iso":"800","shutter":"1/50","aperture":"2.8","white_balance":"auto"}}"#
        )
        let client = try CCAPIClient(
            baseURL: "http://127.0.0.1:18080",
            mode: .simulator,
            transport: transport
        )

        let capabilities = try await client.capabilities()
        _ = try await client.setSetting(key: "beep", value: "disabletouch")

        XCTAssertEqual(capabilities.setting("beep")?.value, "enable")
        XCTAssertEqual(capabilities.setting("displayoff")?.value, "60")
        let requests = await transport.requests()
        let request = try XCTUnwrap(requests.first { $0.path == "/ccapi/device-settings/beep" })
        let body = try XCTUnwrap(request.body)
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: String])
        XCTAssertEqual(object, ["value": "disabletouch"])
    }

    func testSimulatorAutoPowerOffSeparatesTimedSettingAndSleepAction() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(
            path: "/ccapi/capabilities",
            body: #"{"iso":["800"],"shutter":["1/50"],"aperture":["2.8"],"white_balance":["auto"],"autopoweroff":{"value":"180","ability":["30","60","120","180","300","600","disable","immediately"]}}"#
        )
        await transport.enqueue(method: "POST", path: "/ccapi/camera-sleep", status: 204, body: Data())
        let client = try CCAPIClient(
            baseURL: "http://127.0.0.1:18080",
            mode: .simulator,
            transport: transport
        )

        let capabilities = try await client.capabilities()
        try await client.sleepCamera()

        XCTAssertEqual(
            capabilities.setting("autopoweroff")?.values,
            ["30", "60", "120", "180", "300", "600", "disable"]
        )
        XCTAssertFalse(capabilities.setting("autopoweroff")?.values.contains("immediately") == true)
        XCTAssertTrue(capabilities.matrix.supports(.cameraSleep))
        let requests = await transport.requests()
        XCTAssertTrue(requests.contains { $0.method == "POST" && $0.path == "/ccapi/camera-sleep" })
    }

    func testCanonAutoPowerOffUsesFreshAbilityAndSeparateImmediateAction() async throws {
        let transport = MockCameraHTTPTransport()
        let path = "/ccapi/ver100/functions/autopoweroff"
        let response = #"{"value":"180","ability":["30","60","120","180","300","600","disable","immediately"]}"#
        await transport.enqueueJSON(
            path: "/ccapi",
            body: #"{"ver100":[{"path":"/shooting/settings","get":true},{"path":"/functions/autopoweroff","get":true,"put":true}]}"#
        )
        for _ in 0..<2 {
            await transport.enqueueJSON(path: "/ccapi/ver100/shooting/settings", body: "{}")
            await transport.enqueueJSON(path: path, body: response)
        }
        await transport.enqueue(method: "PUT", path: path, status: 202, body: Data("{}".utf8))
        let client = try CCAPIClient(
            baseURL: "http://192.168.1.2:8080",
            mode: .camera,
            transport: transport
        )

        let capabilities = try await client.capabilities()
        try await client.sleepCamera()

        XCTAssertEqual(
            capabilities.setting("autopoweroff")?.values,
            ["30", "60", "120", "180", "300", "600", "disable"]
        )
        XCTAssertTrue(capabilities.matrix.supports(.cameraSleep))
        let requests = await transport.requests()
        let write = try XCTUnwrap(requests.first { $0.method == "PUT" && $0.path == path })
        let body = try XCTUnwrap(write.body)
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: String])
        XCTAssertEqual(object, ["value": "immediately"])
        XCTAssertGreaterThanOrEqual(requests.filter { $0.path == path }.count, 3)
    }

    func testCanonCameraSleepRequiresAcceptedStatus() async throws {
        let transport = MockCameraHTTPTransport()
        let path = "/ccapi/ver100/functions/autopoweroff"
        let response = #"{"value":"180","ability":["30","60","120","180","300","600","disable","immediately"]}"#
        await transport.enqueueJSON(
            path: "/ccapi",
            body: #"{"ver100":[{"path":"/shooting/settings","get":true},{"path":"/functions/autopoweroff","get":true,"put":true}]}"#
        )
        for _ in 0..<2 {
            await transport.enqueueJSON(path: "/ccapi/ver100/shooting/settings", body: "{}")
            await transport.enqueueJSON(path: path, body: response)
        }
        await transport.enqueue(method: "PUT", path: path, status: 200, body: Data("{}".utf8))
        let client = try CCAPIClient(
            baseURL: "http://192.168.1.2:8080",
            mode: .camera,
            transport: transport
        )

        _ = try await client.capabilities()
        do {
            try await client.sleepCamera()
            XCTFail("Expected camera sleep to require Canon's HTTP 202 acceptance")
        } catch let error as CCAPIError {
            guard case let .invalidResponse(message) = error else {
                return XCTFail("Unexpected camera sleep error: \(error)")
            }
            XCTAssertTrue(message.contains("expected HTTP 202"))
        }
    }

    func testCanonAutoPowerOffWithoutImmediateAbilityHidesSleepOnly() async throws {
        let transport = MockCameraHTTPTransport()
        let path = "/ccapi/ver100/functions/autopoweroff"
        let response = #"{"value":"180","ability":["30","60","180","disable"]}"#
        await transport.enqueueJSON(
            path: "/ccapi",
            body: #"{"ver100":[{"path":"/shooting/settings","get":true},{"path":"/functions/autopoweroff","get":true,"put":true}]}"#
        )
        for _ in 0..<2 {
            await transport.enqueueJSON(path: "/ccapi/ver100/shooting/settings", body: "{}")
            await transport.enqueueJSON(path: path, body: response)
        }
        let client = try CCAPIClient(
            baseURL: "http://192.168.1.2:8080",
            mode: .camera,
            transport: transport
        )

        let capabilities = try await client.capabilities()

        XCTAssertNotNil(capabilities.setting("autopoweroff"))
        XCTAssertFalse(capabilities.matrix.supports(.cameraSleep))
        XCTAssertTrue(capabilities.matrix.planned.contains(.cameraSleep))
        do {
            try await client.sleepCamera()
            XCTFail("Expected camera sleep to require immediately in the live ability")
        } catch {
            XCTAssertEqual(error as? CCAPIError, .unsupported(.cameraSleep))
        }
        let remainingResponses = await transport.remainingResponses()
        XCTAssertEqual(remainingResponses, 0)
    }

    func testCanonMovieSettingsRequireExactPairsAndWriteStringAfterRefresh() async throws {
        let transport = MockCameraHTTPTransport()
        let qualityPath = "/ccapi/ver100/shooting/settings/moviequality"
        let highFrameRatePath = "/ccapi/ver110/shooting/settings/highframerate"
        let croppingPath = "/ccapi/ver110/shooting/settings/moviecropping"
        let formatPath = "/ccapi/ver110/shooting/settings/movieformat"
        let quality = #"{"value":"3840x2160_5994_ipb_standard","ability":["3840x2160_5994_ipb_standard","1920x1080_2997_ipb_standard"]}"#
        let toggle = #"{"value":"disable","ability":["enable","disable"]}"#
        let format = #"{"value":"mp4","ability":["raw","mp4"]}"#
        await transport.enqueueJSON(
            path: "/ccapi",
            body: #"{"ver100":[{"path":"/devicestatus/batterylist","get":true},{"path":"/devicestatus/storage","get":true},{"path":"/shooting/settings","get":true},{"path":"/shooting/settings/moviequality","get":true,"put":true}],"ver110":[{"path":"/shooting/settings/highframerate","get":true,"put":true},{"path":"/shooting/settings/moviecropping","get":true,"put":true},{"path":"/shooting/settings/movieformat","get":true,"put":true}]}"#
        )
        for _ in 0..<2 {
            await transport.enqueueJSON(path: "/ccapi/ver100/shooting/settings", body: "{}")
            for (path, body) in [
                (qualityPath, quality),
                (highFrameRatePath, toggle),
                (croppingPath, toggle),
                (formatPath, format),
            ] {
                await transport.enqueueJSON(path: path, body: body)
            }
        }
        await transport.enqueueJSON(method: "PUT", path: formatPath, body: #"{"value":"raw"}"#)
        await enqueueStatus(on: transport)
        for (path, body) in [
            (qualityPath, quality),
            (highFrameRatePath, toggle),
            (croppingPath, toggle),
            (formatPath, #"{"value":"raw","ability":["raw","mp4"]}"#),
        ] {
            await transport.enqueueJSON(path: path, body: body)
        }
        let client = try CCAPIClient(
            baseURL: "http://192.168.1.2:8080",
            mode: .camera,
            transport: transport
        )

        let capabilities = try await client.capabilities()

        XCTAssertTrue(capabilities.matrix.supports(.movieSettingsControl))
        XCTAssertEqual(
            capabilities.setting("moviequality")?.values,
            ["3840x2160_5994_ipb_standard", "1920x1080_2997_ipb_standard"]
        )
        XCTAssertEqual(capabilities.setting("highframerate")?.values, ["enable", "disable"])
        XCTAssertEqual(capabilities.setting("moviecropping")?.value, "disable")
        XCTAssertEqual(capabilities.setting("movieformat")?.values, ["raw", "mp4"])
        XCTAssertTrue(
            Set(["moviequality", "highframerate", "moviecropping", "movieformat"])
                .isSubset(of: Set(capabilities.evidence.writableSettings))
        )

        _ = try await client.setSetting(key: "movieformat", value: "raw")

        let requests = await transport.requests()
        let write = try XCTUnwrap(requests.first { $0.method == "PUT" && $0.path == formatPath })
        let body = try XCTUnwrap(write.body)
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Any])
        XCTAssertEqual(object["value"] as? String, "raw")
        XCTAssertGreaterThanOrEqual(requests.filter { $0.path == formatPath }.count, 3)
    }

    func testCanonMovieSettingsDoNotCombineVersionsOrTrustAggregateOnly() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(
            path: "/ccapi",
            body: #"{"ver100":[{"path":"/shooting/settings","get":true},{"path":"/shooting/settings/moviequality","get":true}],"ver110":[{"path":"/shooting/settings/moviequality","put":true}]}"#
        )
        await transport.enqueueJSON(
            path: "/ccapi/ver100/shooting/settings",
            body: #"{"moviequality":{"value":"3840x2160_5994_ipb_standard","ability":["3840x2160_5994_ipb_standard","1920x1080_2997_ipb_standard"]}}"#
        )
        let client = try CCAPIClient(
            baseURL: "http://192.168.1.2:8080",
            mode: .camera,
            transport: transport
        )

        let capabilities = try await client.capabilities()

        XCTAssertFalse(capabilities.matrix.supports(.movieSettingsControl))
        XCTAssertTrue(capabilities.matrix.planned.contains(.movieSettingsControl))
        XCTAssertNil(capabilities.setting("moviequality"))
        let requests = await transport.requests()
        XCTAssertEqual(requests.count, 2)
    }

    func testCanonMovieModeRequiresMatchingGetPostAndWritesAction() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(
            path: "/ccapi",
            body: #"{"ver100":[{"path":"/devicestatus/batterylist","get":true},{"path":"/devicestatus/storage","get":true},{"path":"/shooting/settings","get":true},{"path":"/shooting/control/moviemode","get":true,"post":true}]}"#
        )
        await transport.enqueueJSON(path: "/ccapi/ver100/shooting/settings", body: "{}")
        await transport.enqueueJSON(
            path: "/ccapi/ver100/shooting/control/moviemode",
            body: #"{"status":"off"}"#
        )
        await transport.enqueue(
            method: "POST",
            path: "/ccapi/ver100/shooting/control/moviemode",
            status: 204,
            body: Data()
        )
        await transport.enqueueJSON(
            path: "/ccapi/ver100/devicestatus/batterylist",
            body: #"{"batterylist":[{"level":89}]}"#
        )
        await transport.enqueueJSON(
            path: "/ccapi/ver100/devicestatus/storage",
            body: #"{"storagelist":[{"name":"card1","spacesize":32000000000}]}"#
        )
        await transport.enqueueJSON(path: "/ccapi/ver100/shooting/settings", body: "{}")
        await transport.enqueueJSON(
            path: "/ccapi/ver100/shooting/control/moviemode",
            body: #"{"status":"on"}"#
        )
        let client = try CCAPIClient(baseURL: "http://192.168.1.2:8080", mode: .camera, transport: transport)

        let capabilities = try await client.capabilities()
        XCTAssertEqual(capabilities.setting("moviemode")?.values, ["off", "on"])
        XCTAssertEqual(capabilities.setting("moviemode")?.value, "off")
        XCTAssertTrue(capabilities.matrix.supports(.movieModeControl))

        _ = try await client.setSetting(key: "moviemode", value: "on")

        let requests = await transport.requests()
        let write = try XCTUnwrap(requests.first {
            $0.method == "POST" && $0.path == "/ccapi/ver100/shooting/control/moviemode"
        })
        let body = try XCTUnwrap(write.body)
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: Any])
        XCTAssertEqual(object["action"] as? String, "on")
    }

    func testCanonMovieModeStaysPlannedWithoutMatchingPostOrValidStatus() async throws {
        let missingPost = MockCameraHTTPTransport()
        await missingPost.enqueueJSON(
            path: "/ccapi",
            body: #"{"ver100":[{"path":"/shooting/settings","get":true},{"path":"/shooting/control/moviemode","get":true}]}"#
        )
        await missingPost.enqueueJSON(path: "/ccapi/ver100/shooting/settings", body: "{}")
        let missingPostClient = try CCAPIClient(
            baseURL: "http://192.168.1.2:8080",
            mode: .camera,
            transport: missingPost
        )

        var capabilities = try await missingPostClient.capabilities()
        XCTAssertNil(capabilities.setting("moviemode"))
        XCTAssertFalse(capabilities.matrix.supports(.movieModeControl))
        XCTAssertTrue(capabilities.matrix.planned.contains(.movieModeControl))
        let requestCount = await missingPost.requests().count
        XCTAssertEqual(requestCount, 2)

        let invalidStatus = MockCameraHTTPTransport()
        await invalidStatus.enqueueJSON(
            path: "/ccapi",
            body: #"{"ver100":[{"path":"/shooting/settings","get":true},{"path":"/shooting/control/moviemode","get":true,"post":true}]}"#
        )
        await invalidStatus.enqueueJSON(path: "/ccapi/ver100/shooting/settings", body: "{}")
        await invalidStatus.enqueueJSON(
            path: "/ccapi/ver100/shooting/control/moviemode",
            body: #"{"status":"recording"}"#
        )
        let invalidStatusClient = try CCAPIClient(
            baseURL: "http://192.168.1.2:8080",
            mode: .camera,
            transport: invalidStatus
        )

        capabilities = try await invalidStatusClient.capabilities()
        XCTAssertNil(capabilities.setting("moviemode"))
        XCTAssertFalse(capabilities.matrix.supports(.movieModeControl))
    }

    func testCanonCardSelectionRequiresMatchingGetPutAndWritesOnlyAdvertisedValue() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(
            path: "/ccapi",
            body: #"{"ver100":[{"path":"/devicestatus/batterylist","get":true},{"path":"/devicestatus/storage","get":true},{"path":"/shooting/settings","get":true},{"path":"/functions/cardselection/stillimage","get":true,"put":true},{"path":"/functions/cardselection/movie","get":true,"put":true}]}"#
        )
        await transport.enqueueJSON(path: "/ccapi/ver100/shooting/settings", body: "{}")
        await transport.enqueueJSON(
            path: "/ccapi/ver100/functions/cardselection/stillimage",
            body: #"{"value":"card1","ability":["none","card1","card2"]}"#
        )
        await transport.enqueueJSON(
            path: "/ccapi/ver100/functions/cardselection/movie",
            body: #"{"value":"card2","ability":["card1","card2"]}"#
        )
        for _ in 0..<2 {
            await transport.enqueueJSON(path: "/ccapi/ver100/shooting/settings", body: "{}")
            await transport.enqueueJSON(
                path: "/ccapi/ver100/functions/cardselection/stillimage",
                body: #"{"value":"card1","ability":["none","card1","card2"]}"#
            )
            await transport.enqueueJSON(
                path: "/ccapi/ver100/functions/cardselection/movie",
                body: #"{"value":"card2","ability":["card1","card2"]}"#
            )
        }
        await transport.enqueueJSON(
            method: "PUT",
            path: "/ccapi/ver100/functions/cardselection/stillimage",
            body: #"{"value":"card2"}"#
        )
        await transport.enqueueJSON(
            path: "/ccapi/ver100/devicestatus/batterylist",
            body: #"{"batterylist":[{"level":89}]}"#
        )
        await transport.enqueueJSON(
            path: "/ccapi/ver100/devicestatus/storage",
            body: #"{"storagelist":[{"name":"card1","spacesize":32000000000}]}"#
        )
        await transport.enqueueJSON(path: "/ccapi/ver100/shooting/settings", body: "{}")
        await transport.enqueueJSON(
            path: "/ccapi/ver100/functions/cardselection/stillimage",
            body: #"{"value":"card2","ability":["none","card1","card2"]}"#
        )
        await transport.enqueueJSON(
            path: "/ccapi/ver100/functions/cardselection/movie",
            body: #"{"value":"card2","ability":["card1","card2"]}"#
        )
        let client = try CCAPIClient(
            baseURL: "http://192.168.1.2:8080",
            mode: .camera,
            transport: transport
        )

        let capabilities = try await client.capabilities()

        XCTAssertEqual(capabilities.setting("cardselectionstillimage")?.value, "card1")
        XCTAssertEqual(
            capabilities.setting("cardselectionstillimage")?.values,
            ["none", "card1", "card2"]
        )
        XCTAssertEqual(capabilities.setting("cardselectionmovie")?.value, "card2")
        XCTAssertTrue(capabilities.matrix.supports(.cardSelectionControl))
        XCTAssertTrue(capabilities.evidence.writableSettings.contains("cardselectionstillimage"))
        let requestCount = await transport.requests().count
        do {
            _ = try await client.setSetting(key: "cardselectionstillimage", value: "card3")
            XCTFail("Expected an unadvertised card value to be rejected")
        } catch {
            XCTAssertEqual(
                error as? CCAPIError,
                .invalidSetting(key: "cardselectionstillimage", value: "card3")
            )
        }
        let requestsAfterRejection = await transport.requests()
        XCTAssertEqual(requestsAfterRejection.count, requestCount + 3)
        XCTAssertFalse(requestsAfterRejection.contains(where: { $0.method == "PUT" }))

        _ = try await client.setSetting(key: "cardselectionstillimage", value: "card2")

        let requests = await transport.requests()
        let write = try XCTUnwrap(requests.first {
            $0.method == "PUT" && $0.path == "/ccapi/ver100/functions/cardselection/stillimage"
        })
        let body = try XCTUnwrap(write.body)
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: String])
        XCTAssertEqual(object, ["value": "card2"])
    }

    func testCanonCardSelectionRejectsMalformedAbilityAndCrossVersionPairing() async throws {
        let malformed = MockCameraHTTPTransport()
        await malformed.enqueueJSON(
            path: "/ccapi",
            body: #"{"ver100":[{"path":"/shooting/settings","get":true},{"path":"/functions/cardselection/stillimage","get":true,"put":true}]}"#
        )
        await malformed.enqueueJSON(path: "/ccapi/ver100/shooting/settings", body: "{}")
        await malformed.enqueueJSON(
            path: "/ccapi/ver100/functions/cardselection/stillimage",
            body: #"{"value":"card1","ability":["card1","card1"]}"#
        )
        let malformedClient = try CCAPIClient(
            baseURL: "http://192.168.1.2:8080",
            mode: .camera,
            transport: malformed
        )

        var capabilities = try await malformedClient.capabilities()

        XCTAssertNil(capabilities.setting("cardselectionstillimage"))
        XCTAssertFalse(capabilities.matrix.supports(.cardSelectionControl))
        XCTAssertTrue(capabilities.matrix.planned.contains(.cardSelectionControl))

        let crossVersion = MockCameraHTTPTransport()
        await crossVersion.enqueueJSON(
            path: "/ccapi",
            body: #"{"ver100":[{"path":"/shooting/settings","get":true},{"path":"/functions/cardselection/stillimage","get":true}],"ver110":[{"path":"/functions/cardselection/stillimage","put":true}]}"#
        )
        await crossVersion.enqueueJSON(path: "/ccapi/ver100/shooting/settings", body: "{}")
        let crossVersionClient = try CCAPIClient(
            baseURL: "http://192.168.1.2:8080",
            mode: .camera,
            transport: crossVersion
        )

        capabilities = try await crossVersionClient.capabilities()

        XCTAssertNil(capabilities.setting("cardselectionstillimage"))
        XCTAssertFalse(capabilities.matrix.supports(.cardSelectionControl))
        let crossVersionRequestCount = await crossVersion.requests().count
        XCTAssertEqual(crossVersionRequestCount, 2)
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
        let progress = DownloadProgressRecorder()
        defer { try? FileManager.default.removeItem(at: destination) }

        let result = try await client.downloadMedia(
            CameraMediaItem(id: path, name: "IMG_0001.CR3", kind: "raw", sizeBytes: 4),
            to: destination,
            progress: { progress.record($0) }
        )

        XCTAssertEqual(try Data(contentsOf: destination), media)
        XCTAssertEqual(result.bytesTransferred, 4)
        XCTAssertEqual(result.contentType, "image/x-canon-cr3")
        XCTAssertEqual(
            progress.values().last,
            CameraMediaTransferProgress(bytesTransferred: 4, totalBytes: 4)
        )
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

    func testMediaMetadataUsesAdvertisedCanonPutAndVerifiesReadback() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(path: "/ccapi", body: discovery)
        let path = "/ccapi/ver100/contents/card1/100CANON/IMG_0001.JPG"
        let item = CameraMediaItem(id: path, name: "IMG_0001.JPG", kind: "image")
        await transport.enqueueJSON(
            path: "\(path)?kind=info",
            body: #"{"filesize":1234,"protect":"disable","rating":"off","rotate":"0","lastmodifieddate":"2026-08-05T10:00:00+08:00"}"#
        )
        await transport.enqueueJSON(method: "PUT", path: path, body: "{}")
        await transport.enqueueJSON(
            path: "\(path)?kind=info",
            body: #"{"protect":"enable","rating":"off","rotate":"0"}"#
        )
        await transport.enqueueJSON(method: "PUT", path: path, body: "{}")
        await transport.enqueueJSON(
            path: "\(path)?kind=info",
            body: #"{"protect":"enable","rating":"5","rotate":"0"}"#
        )
        await transport.enqueueJSON(method: "PUT", path: path, body: "{}")
        await transport.enqueueJSON(
            path: "\(path)?kind=info",
            body: #"{"protect":"enable","rating":"5","rotate":"270"}"#
        )
        let client = try CCAPIClient(baseURL: "http://192.168.1.2:8080", mode: .camera, transport: transport)

        let info = try await client.mediaInfo(item)
        XCTAssertEqual(info.sizeBytes, 1_234)
        XCTAssertEqual(info.protected, false)
        XCTAssertEqual(info.rating, 0)
        XCTAssertEqual(info.rotationDegrees, 0)
        let protected = try await client.setMediaProtection(info, enabled: true)
        let rated = try await client.setMediaRating(protected, rating: 5)
        let rotated = try await client.setMediaRotation(rated, degrees: 270)
        XCTAssertEqual(protected.protected, true)
        XCTAssertEqual(rated.rating, 5)
        XCTAssertEqual(rotated.rotationDegrees, 270)

        let writes = await transport.requests().filter { $0.method == "PUT" && $0.path == path }
        let payloads = try writes.map { request -> [String: String] in
            let body = try XCTUnwrap(request.body)
            return try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: String])
        }
        XCTAssertEqual(payloads[0], ["action": "protect", "value": "enable"])
        XCTAssertEqual(payloads[1], ["action": "rating", "value": "5"])
        XCTAssertEqual(payloads[2], ["action": "rotate", "value": "270"])
        await transport.enqueueJSON(path: "/ccapi/ver100/shooting/settings", body: settings)
        let capabilities = try await client.capabilities()
        XCTAssertTrue(capabilities.evidence.observedFeatures.contains(.mediaProtect))
        XCTAssertTrue(capabilities.evidence.observedFeatures.contains(.mediaRating))
        XCTAssertTrue(capabilities.evidence.observedFeatures.contains(.mediaRotate))
    }

    func testMediaMetadataRequiresAdvertisedContentsPut() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(
            path: "/ccapi",
            body: #"{"ver100":[{"path":"/contents","get":true}]}"#
        )
        let client = try CCAPIClient(baseURL: "http://192.168.1.2:8080", mode: .camera, transport: transport)
        let item = CameraMediaItem(
            id: "/ccapi/ver100/contents/card1/IMG_0001.JPG",
            name: "IMG_0001.JPG",
            kind: "image"
        )

        do {
            _ = try await client.setMediaRating(item, rating: 4)
            XCTFail("Expected unsupported media rating")
        } catch {
            XCTAssertEqual(error as? CCAPIError, .unsupported(.mediaRating))
        }
        let requests = await transport.requests()
        XCTAssertEqual(requests.map(\.method), ["GET"])
    }

    func testDeviceStatusUsesAdvertisedStrictCanonLensAndTemperaturePayloads() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(path: "/ccapi", body: deviceStatusDiscovery)
        await enqueueDeviceStatus(
            on: transport,
            temperature: "frameratedown_and_restrictionmovierecording"
        )
        let client = try CCAPIClient(baseURL: "http://192.168.1.2:8080", mode: .camera, transport: transport)

        let status = try await client.status()
        let capabilities = try await client.capabilities()

        XCTAssertEqual(status.lens, LensStatus(mounted: true, name: "RF24-105mm F4 L IS USM"))
        XCTAssertEqual(status.temperature, .frameRateDownAndRestrictionMovieRecording)
        XCTAssertEqual(status.recordableShots, 2_418)
        XCTAssertNil(status.remainingRecordingSeconds)
        XCTAssertEqual(status.temperature?.frameRateReduced, true)
        XCTAssertEqual(status.temperature?.movieRecordingAllowed, false)
        XCTAssertTrue(capabilities.matrix.supports(.lensStatus))
        XCTAssertTrue(capabilities.matrix.supports(.temperatureStatus))
        XCTAssertTrue(capabilities.matrix.supports(.recordableStatus))
    }

    func testMalformedAdvertisedDeviceStatusRemainsPlanned() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(path: "/ccapi", body: deviceStatusDiscovery)
        await enqueueDeviceStatus(
            on: transport,
            recordable: #"{"recordableshots":true,"remainingtime":-1}"#,
            lens: #"{"mount":"true","name":"RF24-105mm"}"#,
            temperature: "hot"
        )
        let client = try CCAPIClient(baseURL: "http://192.168.1.2:8080", mode: .camera, transport: transport)

        let status = try await client.status()
        let capabilities = try await client.capabilities()

        XCTAssertNil(status.lens)
        XCTAssertNil(status.temperature)
        XCTAssertNil(status.recordableShots)
        XCTAssertNil(status.remainingRecordingSeconds)
        XCTAssertFalse(capabilities.matrix.supports(.lensStatus))
        XCTAssertFalse(capabilities.matrix.supports(.temperatureStatus))
        XCTAssertFalse(capabilities.matrix.supports(.recordableStatus))
        XCTAssertTrue(capabilities.matrix.planned.contains(.lensStatus))
        XCTAssertTrue(capabilities.matrix.planned.contains(.temperatureStatus))
        XCTAssertTrue(capabilities.matrix.planned.contains(.recordableStatus))
    }

    func testOversizedAdvertisedLensNameRemainsPlanned() async throws {
        let transport = MockCameraHTTPTransport()
        let oversizedName = String(repeating: "R", count: 513)
        await transport.enqueueJSON(path: "/ccapi", body: deviceStatusDiscovery)
        await enqueueDeviceStatus(
            on: transport,
            lens: "{\"mount\":true,\"name\":\"" + oversizedName + "\"}"
        )
        let client = try CCAPIClient(baseURL: "http://192.168.1.2:8080", mode: .camera, transport: transport)

        let status = try await client.status()
        let capabilities = try await client.capabilities()

        XCTAssertNil(status.lens)
        XCTAssertFalse(capabilities.matrix.supports(.lensStatus))
        XCTAssertTrue(capabilities.matrix.planned.contains(.lensStatus))
    }

    func testTemperatureRestrictionRefreshPreventsStillCaptureCommand() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(
            path: "/ccapi",
            body: #"{"ver100":[{"path":"/devicestatus/temperature","get":true},{"path":"/shooting/control/shutterbutton","post":true}]}"#
        )
        await transport.enqueueJSON(
            path: "/ccapi/ver100/devicestatus/temperature",
            body: #"{"status":"disablerelease"}"#
        )
        let client = try CCAPIClient(baseURL: "http://192.168.1.2:8080", mode: .camera, transport: transport)

        do {
            _ = try await client.captureStill()
            XCTFail("Expected temperature restriction")
        } catch {
            XCTAssertEqual(error as? CCAPIError, .temperatureRestriction(.stillCapture))
        }
        await transport.enqueueJSON(
            path: "/ccapi/ver100/devicestatus/temperature",
            body: #"{"status":"hot"}"#
        )
        do {
            _ = try await client.captureStill()
            XCTFail("Expected the last valid temperature restriction to remain active")
        } catch {
            XCTAssertEqual(error as? CCAPIError, .temperatureRestriction(.stillCapture))
        }

        let requests = await transport.requests()
        XCTAssertEqual(requests.map(\.method), ["GET", "GET", "GET"])
    }

    func testTemperatureRestrictionDoesNotBlockRecordingStop() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(path: "/ccapi", body: deviceStatusDiscovery)
        await transport.enqueueJSON(
            path: "/ccapi/ver100/devicestatus/temperature",
            body: #"{"status":"normal"}"#
        )
        await transport.enqueue(method: "POST", path: "/ccapi/ver100/shooting/control/recbutton", status: 204)
        await enqueueDeviceStatus(on: transport)
        await transport.enqueue(method: "POST", path: "/ccapi/ver100/shooting/control/recbutton", status: 204)
        await enqueueDeviceStatus(on: transport, temperature: "restrictionmovierecording")
        let client = try CCAPIClient(baseURL: "http://192.168.1.2:8080", mode: .camera, transport: transport)

        let started = try await client.startRecording()
        XCTAssertEqual(started.recording, true)
        let stopped = try await client.stopRecording()
        XCTAssertEqual(stopped.recording, false)

        let stop = (await transport.requests()).last { request in
            request.method == "POST" && request.path == "/ccapi/ver100/shooting/control/recbutton"
        }
        let stopBody = try XCTUnwrap(stop?.body)
        let stopJSON = try XCTUnwrap(JSONSerialization.jsonObject(with: stopBody) as? [String: String])
        XCTAssertEqual(stopJSON, ["action": "stop"])
    }

    func testCanonTemperatureStatusesExposeDocumentedRestrictions() {
        XCTAssertEqual(CameraTemperatureStatus.allCases.count, 12)
        XCTAssertFalse(CameraTemperatureStatus.disableLiveView.liveViewAllowed)
        XCTAssertFalse(CameraTemperatureStatus.disableRelease.stillCaptureAllowed)
        XCTAssertFalse(CameraTemperatureStatus.restrictionMovieRecording.movieRecordingAllowed)
        XCTAssertTrue(CameraTemperatureStatus.frameRateDown.frameRateReduced)
        XCTAssertTrue(CameraTemperatureStatus.stillQualityWarning.stillQualityWarning)
        XCTAssertTrue(CameraTemperatureStatus.warning.temperatureWarning)
        XCTAssertTrue(CameraTemperatureStatus.normal.isNormal)
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
        let windowsHome = "C:/" + "Users/private/capture.jpg"
        let networkHome = "\\\\" + "PRIVATE-SERVER\\private\\capture.jpg"
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
                observedFeatures: [.cameraIdentity, .liveView],
                discoveryTrace: [
                    CameraDiscoveryAttempt(
                        endpoint: "GET /ccapi",
                        outcome: "NO_API_LIST",
                        httpStatus: 200,
                        responseKeys: ["value"]
                    ),
                    CameraDiscoveryAttempt(
                        endpoint: "GET /ccapi/ver100/topurlfordev",
                        outcome: "OPERATIONS",
                        httpStatus: 200,
                        responseKeys: ["ver100"],
                        protocolVersions: ["ver100"],
                        advertisedOperationCount: 17
                    ),
                ]
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
            lastError: "Camera PRIVATE-CAMERA-SERIAL failed at \(windowsHome), \(networkHome) and /private/var/mobile/frame.jpg",
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
        XCTAssertFalse(report.contains("C:/" + "Users"))
        XCTAssertFalse(report.contains("PRIVATE-SERVER"))
        XCTAssertFalse(report.contains("/private/var"))
        XCTAssertTrue(report.contains("[local-path]"))
        XCTAssertTrue(report.contains("capabilitySource=GET /ccapi"))
        XCTAssertTrue(report.contains("discoveryAttemptCount=2"))
        XCTAssertTrue(report.contains("discoveryAttempt1=endpoint=GET /ccapi; outcome=NO_API_LIST"))
        XCTAssertTrue(report.contains("discoveryAttempt2=endpoint=GET /ccapi/ver100/topurlfordev; outcome=OPERATIONS"))
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

    private func enqueueDeviceStatus(
        on transport: MockCameraHTTPTransport,
        recordable: String = #"{"recordableshots":2418,"remainingtime":null}"#,
        lens: String = #"{"mount":true,"name":"RF24-105mm F4 L IS USM"}"#,
        temperature: String = "normal"
    ) async {
        await transport.enqueueJSON(
            path: "/ccapi/ver100/devicestatus/batterylist",
            body: #"{"batterylist":[{"level":89}]}"#
        )
        await transport.enqueueJSON(
            path: "/ccapi/ver100/devicestatus/storage",
            body: #"{"storagelist":[{"name":"card1","spacesize":32000000000}]}"#
        )
        await transport.enqueueJSON(
            path: "/ccapi/ver100/shooting/information/recordable",
            body: recordable
        )
        await transport.enqueueJSON(path: "/ccapi/ver100/devicestatus/lens", body: lens)
        await transport.enqueueJSON(
            path: "/ccapi/ver100/devicestatus/temperature",
            body: "{\"status\":\"\(temperature)\"}"
        )
        await transport.enqueueJSON(path: "/ccapi/ver100/shooting/settings", body: settings)
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
