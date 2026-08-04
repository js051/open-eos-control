import Foundation
import XCTest

@testable import OpenEOSCore

final class DesktopBridgeClientTests: XCTestCase {
    private let health = #"{"ok":true,"service":"open-eos-control-bridge","version":"0.1.0","authRequired":true,"loopbackOnly":false,"engines":{}}"#
    private let status = #"{"connected":true,"battery":{"level":82,"status":"good"},"recording":false,"mode":"Manual","recordableShots":120,"remainingRecordingSeconds":3600,"media":{"available":true,"totalBytes":1000,"freeBytes":800,"freeImages":123,"devices":1},"exposure":{"iso":"400","shutter":"1/50","aperture":"2.8","whiteBalance":"Auto"},"raw":{"transport":"usb","recordable":{"recordableshots":120,"remainingtime":3600}}}"#
    private let capabilities = #"{"profile":{"modelName":"Canon EOS R6 Mark III","family":"EOS_R","priority":"PRIMARY"},"supported":["CAMERA_IDENTITY","DESKTOP_BRIDGE","USB_DIAGNOSTICS","LIVE_VIEW","LIVE_VIEW_MAGNIFICATION","STILL_CAPTURE","BULB_EXPOSURE","AUTOFOCUS","SHUTTER_HALF_PRESS","VIDEO_RECORDING","TAP_FOCUS","CLICK_WHITE_BALANCE","FOCUS_DRIVE","EXPOSURE_CONTROL","SENSOR_CLEANING","CAMERA_SLEEP","MEDIA_BROWSER","MEDIA_THUMBNAIL","MEDIA_PREVIEW","MEDIA_DOWNLOAD","MEDIA_DELETE"],"planned":["LIVE_VIEW_RTP","STILL_CAPTURE"],"reasons":{"LIVE_VIEW_RTP":"No verified decoder."},"liveView":{"sources":["DESKTOP_BRIDGE_STREAM"],"defaultSource":"DESKTOP_BRIDGE_STREAM","sizes":["MEDIUM","LARGE"],"defaultSize":"MEDIUM","minFps":1,"maxFps":12},"settings":[{"key":"iso","label":"ISO Speed","value":"400","values":["100","400","800"]}],"evidence":{"source":"libgphoto2","protocolVersions":["gphoto2 2.5.33"],"advertisedCommands":["POST /capture?token=secret"],"writableSettings":["iso"],"observedFeatures":["BATTERY_STATUS"],"truncated":false}}"#

    func testDiscoveryValidatesServiceAndUsesBearerAuthentication() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(path: "/health", body: health)
        await transport.enqueueJSON(
            path: "/v1/cameras",
            body: #"{"cameras":[{"id":"gphoto2:dXNi","model":"Canon EOS R6 Mark III","port":"usb:001,007","engine":"libgphoto2"}]}"#
        )
        let client = try DesktopBridgeClient(
            baseURL: "http://192.168.1.10:18181/",
            token: "bridge-secret",
            transport: transport
        )

        let cameras = try await client.discoverCameras()

        XCTAssertEqual(
            cameras,
            [DesktopBridgeCamera(id: "gphoto2:dXNi", model: "Canon EOS R6 Mark III", port: "usb:001,007", engine: "libgphoto2")]
        )
        let requests = await transport.requests()
        XCTAssertEqual(requests.map(\.path), ["/health", "/v1/cameras"])
        XCTAssertTrue(requests.allSatisfy { request in
            request.headers.first { $0.key.caseInsensitiveCompare("Authorization") == .orderedSame }?.value
                == "Bearer bridge-secret"
        })
    }

    func testSessionCoversControlLiveViewMediaAndCloseContract() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(path: "/health", body: health)
        await transport.enqueueJSON(
            method: "POST",
            path: "/v1/session",
            status: 201,
            body: #"{"id":"session_123","engine":"libgphoto2","camera":{"id":"gphoto2:dXNi","model":"Canon EOS R6 Mark III","port":"usb:001,007","engine":"libgphoto2"}}"#
        )
        await transport.enqueueJSON(
            path: "/v1/session/session_123/info",
            body: #"{"connected":true,"model":"Canon EOS R6 Mark III","serial":"TEST-SERIAL-0001","api":"gphoto2","manufacturer":"Canon.Inc","deviceVersion":"3-1.0.0","engineVersion":"gphoto2 2.5.33"}"#
        )
        await transport.enqueueJSON(path: "/v1/session/session_123/status", body: status)
        await transport.enqueueJSON(path: "/v1/session/session_123/capabilities", body: capabilities)
        await enqueueStatus(method: "POST", path: "/v1/session/session_123/settings/iso", on: transport)
        await enqueueStatus(method: "POST", path: "/v1/session/session_123/clock/sync", on: transport)
        await transport.enqueue(
            method: "POST",
            path: "/v1/session/session_123/maintenance/sensor-cleaning",
            status: 204
        )
        await enqueueStatus(method: "POST", path: "/v1/session/session_123/capture/still", on: transport)
        await transport.enqueueJSON(
            method: "POST",
            path: "/v1/session/session_123/bulb/start",
            body: status.replacingOccurrences(of: #""recording":false"#, with: #""recording":false,"bulbExposureActive":true"#)
        )
        await transport.enqueueJSON(
            method: "POST",
            path: "/v1/session/session_123/bulb/stop",
            body: status.replacingOccurrences(of: #""recording":false"#, with: #""recording":false,"bulbExposureActive":false"#)
        )
        await enqueueStatus(method: "POST", path: "/v1/session/session_123/focus/auto", on: transport)
        await enqueueStatus(method: "POST", path: "/v1/session/session_123/shutter/half-press", on: transport)
        await enqueueStatus(method: "POST", path: "/v1/session/session_123/recording/start", on: transport)
        await enqueueStatus(method: "POST", path: "/v1/session/session_123/recording/stop", on: transport)
        await transport.enqueueJSON(
            method: "POST",
            path: "/v1/session/session_123/focus/tap",
            body: #"{"accepted":true,"x":0.25,"y":0.75}"#
        )
        await enqueueStatus(method: "POST", path: "/v1/session/session_123/whitebalance/click", on: transport)
        await transport.enqueueJSON(
            method: "POST",
            path: "/v1/session/session_123/focus/drive",
            body: #"{"accepted":true,"direction":"NEAR","step":"LARGE"}"#
        )
        await transport.enqueueJSON(
            method: "POST",
            path: "/v1/session/session_123/liveview/start",
            body: #"{"active":true,"requestedFps":12}"#
        )
        await transport.enqueueJSON(
            method: "POST",
            path: "/v1/session/session_123/liveview/magnification",
            body: #"{"accepted":true,"value":5}"#
        )
        let jpeg = Data([0xFF, 0xD8, 0x01, 0x02, 0xFF, 0xD9])
        await transport.enqueue(
            path: "/v1/session/session_123/liveview/frame?t=9",
            headers: ["content-type": "image/jpeg"],
            body: jpeg
        )
        await transport.enqueueJSON(
            path: "/v1/session/session_123/media",
            body: #"{"items":[{"id":"gphoto2:YWJj","name":"IMG_0001.JPG","kind":"image","sizeBytes":6,"captureTime":"2026-07-21T10:08:24+08:00","contentType":"image/jpeg","previewAvailable":true}]}"#
        )
        let thumbnailJPEG = Data([0xFF, 0xD8, 0x04, 0x02, 0xFF, 0xD9])
        await transport.enqueue(
            path: "/v1/session/session_123/media/gphoto2:YWJj/thumbnail",
            headers: ["content-type": "image/jpeg"],
            body: thumbnailJPEG
        )
        let previewJPEG = Data([0xFF, 0xD8, 0x08, 0x06, 0xFF, 0xD9])
        await transport.enqueue(
            path: "/v1/session/session_123/media/gphoto2:YWJj/preview",
            headers: ["content-type": "image/jpeg"],
            body: previewJPEG
        )
        await transport.enqueueDownload(
            path: "/v1/session/session_123/media/gphoto2:YWJj",
            headers: ["content-type": "image/jpeg"],
            body: Data("jpeg!!".utf8)
        )
        await transport.enqueue(method: "DELETE", path: "/v1/session/session_123/media/gphoto2:YWJj", status: 204)
        await transport.enqueueJSON(
            method: "POST",
            path: "/v1/session/session_123/liveview/stop",
            body: #"{"active":false}"#
        )
        await transport.enqueue(method: "POST", path: "/v1/session/session_123/power/sleep", status: 204)
        await transport.enqueue(method: "DELETE", path: "/v1/session/session_123", status: 204)

        let client = try DesktopBridgeClient(
            baseURL: "http://192.168.1.10:18181",
            token: "bridge-secret",
            cameraID: "gphoto2:dXNi",
            transport: transport
        )
        let snapshot = try await client.connectSnapshot()

        XCTAssertEqual(snapshot.info.model, "Canon EOS R6 Mark III")
        XCTAssertEqual(snapshot.status.batteryLevel, 82)
        XCTAssertEqual(snapshot.status.storageTotalBytes, 1_000)
        XCTAssertEqual(snapshot.status.storageFreeBytes, 800)
        XCTAssertEqual(snapshot.status.storageFreeImages, 123)
        XCTAssertEqual(snapshot.status.storageDeviceCount, 1)
        XCTAssertEqual(snapshot.status.recordableShots, 120)
        XCTAssertEqual(snapshot.status.remainingRecordingSeconds, 3_600)
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.desktopBridge))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.usbDiagnostics))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.focusDrive))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.bulbExposure))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.liveViewMagnification))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.clickWhiteBalance))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.mediaThumbnail))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.mediaPreview))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.sensorCleaning))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.cameraSleep))
        XCTAssertFalse(snapshot.capabilities.matrix.planned.contains(.stillCapture))
        XCTAssertEqual(snapshot.capabilities.liveView.sources, [.desktopBridgeStream])
        XCTAssertEqual(snapshot.capabilities.liveView.maximumFPS, 12)
        XCTAssertEqual(snapshot.capabilities.evidence.advertisedCommands, ["POST /capture"])
        XCTAssertTrue(snapshot.capabilities.evidence.observedFeatures.contains(.batteryStatus))

        _ = try await client.setSetting(key: "iso", value: "800")
        _ = try await client.syncCameraClock()
        try await client.cleanSensor(autoPowerOff: false)
        _ = try await client.captureStill()
        let bulbStarted = try await client.startBulbExposure()
        let bulbStopped = try await client.stopBulbExposure()
        XCTAssertEqual(bulbStarted.bulbExposureActive, true)
        XCTAssertEqual(bulbStopped.bulbExposureActive, false)
        _ = try await client.autofocus()
        _ = try await client.halfPressShutter()
        _ = try await client.startRecording()
        _ = try await client.stopRecording()
        let tapResult = try await client.tapFocus(x: 0.25, y: 0.75)
        XCTAssertEqual(tapResult, FocusResult(accepted: true, x: 0.25, y: 0.75))
        let clickWhiteBalanceStatus = try await client.clickWhiteBalance(x: 0.4, y: 0.6)
        XCTAssertTrue(clickWhiteBalanceStatus.connected)
        let driveResult = try await client.driveFocus(direction: .near, step: .large)
        XCTAssertEqual(
            driveResult,
            FocusDriveResult(accepted: true, direction: .near, step: .large)
        )
        try await client.startLiveView(LiveViewRequest(fps: 12, size: .large, source: .desktopBridgeStream))
        let magnification = try await client.setLiveViewMagnification(.x5)
        XCTAssertEqual(
            magnification,
            LiveViewMagnificationResult(accepted: true, magnification: .x5)
        )
        let frame = try await client.liveViewFrame(cacheKey: 9)
        XCTAssertEqual(frame.data, jpeg)

        let media = try await client.listMedia()
        let item = try XCTUnwrap(media.first)
        XCTAssertTrue(item.previewAvailable)
        let thumbnail = try await client.mediaThumbnail(item)
        XCTAssertEqual(thumbnail.data, thumbnailJPEG)
        XCTAssertEqual(thumbnail.contentType, "image/jpeg")
        let preview = try await client.mediaPreview(item)
        XCTAssertEqual(preview.data, previewJPEG)
        XCTAssertEqual(preview.contentType, "image/jpeg")
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let destination = directory.appendingPathComponent(item.name)
        let progress = DownloadProgressRecorder()
        let download = try await client.downloadMedia(
            item,
            to: destination,
            progress: { progress.record($0) }
        )
        XCTAssertEqual(download.bytesTransferred, 6)
        XCTAssertEqual(try Data(contentsOf: destination), Data("jpeg!!".utf8))
        XCTAssertEqual(
            progress.values().last,
            CameraMediaTransferProgress(bytesTransferred: 6, totalBytes: 6)
        )
        try await client.deleteMedia(item)
        await client.stopLiveView()
        try await client.sleepCamera()
        await client.close()

        let requests = await transport.requests()
        let sessionBody = try XCTUnwrap(requests.first { $0.path == "/v1/session" }?.body)
        let sessionJSON = try XCTUnwrap(JSONSerialization.jsonObject(with: sessionBody) as? [String: Any])
        XCTAssertEqual(sessionJSON["cameraId"] as? String, "gphoto2:dXNi")
        XCTAssertEqual(sessionJSON["profileHint"] as? String, "Canon EOS R6 Mark III")
        XCTAssertTrue(requests.contains { $0.path.hasSuffix("/bulb/start") })
        XCTAssertTrue(requests.contains { $0.path.hasSuffix("/bulb/stop") })
        XCTAssertTrue(requests.contains { $0.path.hasSuffix("/clock/sync") && $0.method == "POST" })
        let cleaningRequest = try XCTUnwrap(
            requests.first { $0.path.hasSuffix("/maintenance/sensor-cleaning") && $0.method == "POST" }
        )
        let cleaningBody = try XCTUnwrap(cleaningRequest.body)
        let cleaningJSON = try XCTUnwrap(JSONSerialization.jsonObject(with: cleaningBody) as? [String: Any])
        XCTAssertEqual(cleaningJSON["autoPowerOff"] as? Bool, false)
        XCTAssertTrue(requests.contains { $0.path.hasSuffix("/power/sleep") && $0.method == "POST" })

        let settingBody = try XCTUnwrap(requests.first { $0.path.hasSuffix("/settings/iso") }?.body)
        XCTAssertEqual(
            (try XCTUnwrap(JSONSerialization.jsonObject(with: settingBody) as? [String: Any]))["value"] as? String,
            "800"
        )
        let liveViewBody = try XCTUnwrap(requests.first { $0.path.hasSuffix("/liveview/start") }?.body)
        let liveViewJSON = try XCTUnwrap(JSONSerialization.jsonObject(with: liveViewBody) as? [String: Any])
        XCTAssertEqual(liveViewJSON["fps"] as? Int, 12)
        XCTAssertEqual(liveViewJSON["size"] as? String, "LARGE")
        XCTAssertEqual(liveViewJSON["source"] as? String, "DESKTOP_BRIDGE_STREAM")
        let magnificationBody = try XCTUnwrap(
            requests.first { $0.path.hasSuffix("/liveview/magnification") }?.body
        )
        let magnificationJSON = try XCTUnwrap(
            JSONSerialization.jsonObject(with: magnificationBody) as? [String: Any]
        )
        XCTAssertEqual(magnificationJSON["value"] as? Int, 5)
        let clickBody = try XCTUnwrap(requests.first { $0.path.hasSuffix("/whitebalance/click") }?.body)
        let clickJSON = try XCTUnwrap(JSONSerialization.jsonObject(with: clickBody) as? [String: Any])
        XCTAssertEqual(clickJSON["x"] as? Double, 0.4)
        XCTAssertEqual(clickJSON["y"] as? Double, 0.6)
        let remainingResponses = await transport.remainingResponses()
        XCTAssertEqual(remainingResponses, 0)
    }

    func testEventPollingUsesBridgeLifecycleAndBoundedResponse() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(path: "/health", body: health)
        await transport.enqueueJSON(
            method: "POST",
            path: "/v1/session",
            status: 201,
            body: #"{"id":"session_events","engine":"ccapi","camera":{"id":"ccapi:test","model":"Canon EOS R6 Mark III","port":"network","engine":"ccapi"}}"#
        )
        let eventCapabilities = capabilities.replacingOccurrences(
            of: #""supported":["#,
            with: #""supported":["EVENT_POLLING","#
        )
        await transport.enqueueJSON(
            path: "/v1/session/session_events/capabilities",
            body: eventCapabilities
        )
        await transport.enqueueJSON(
            path: "/v1/session/session_events/events",
            body: #"{"changedKeys":["shootingsettings","contents"]}"#
        )
        await transport.enqueue(
            method: "DELETE",
            path: "/v1/session/session_events/events",
            status: 204,
            body: Data()
        )
        let client = try DesktopBridgeClient(
            baseURL: "http://192.168.1.10:18181",
            cameraID: "ccapi:test",
            transport: transport
        )

        try await client.initialize()
        let parsed = try await client.capabilities()
        let event = try await client.pollEvent()
        await client.stopEventPolling()

        XCTAssertTrue(parsed.matrix.supports(.eventPolling))
        XCTAssertEqual(event.changedKeys, ["shootingsettings", "contents"])
        let requests = await transport.requests()
        XCTAssertEqual(requests.suffix(2).map(\.path), [
            "/v1/session/session_events/events",
            "/v1/session/session_events/events",
        ])
        XCTAssertEqual(requests[3].timeoutInterval, 40)
        XCTAssertEqual(requests[4].timeoutInterval, 5)
    }

    func testStructuredErrorAndDiagnosticsNeverExposeToken() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(path: "/health", body: health)
        await transport.enqueueJSON(
            path: "/v1/cameras",
            status: 401,
            body: #"{"error":{"code":"AUTHENTICATION_REQUIRED","message":"Provide a token.","feature":"DESKTOP_BRIDGE","engine":"libgphoto2"}}"#
        )
        let client = try DesktopBridgeClient(
            baseURL: "http://192.168.1.10:18181",
            token: "bridge-secret",
            transport: transport
        )

        do {
            _ = try await client.discoverCameras()
            XCTFail("Expected authentication error")
        } catch let DesktopBridgeError.http(statusCode, method, _, code, message, feature, engine) {
            XCTAssertEqual(statusCode, 401)
            XCTAssertEqual(method, "GET")
            XCTAssertEqual(code, "AUTHENTICATION_REQUIRED")
            XCTAssertEqual(message, "Provide a token.")
            XCTAssertEqual(feature, "DESKTOP_BRIDGE")
            XCTAssertEqual(engine, "libgphoto2")
        }

        let report = await client.diagnosticReport(
            snapshot: nil,
            lastError: "Authorization: Bearer bridge-secret token=bridge-secret"
        )
        XCTAssertFalse(report.contains("bridge-secret"))
        XCTAssertTrue(report.contains("[redacted]"))
        XCTAssertTrue(report.contains("transport=DESKTOP_BRIDGE"))
    }

    func testDesktopBridgeReportRedactsSerialAndCarriesValidationMetadata() throws {
        let windowsHome = "C:" + "\\Users\\private\\capture.jpg"
        let networkHome = "\\\\" + "PRIVATE-SERVER\\private\\capture.jpg"
        let snapshot = CameraSnapshot(
            info: CameraInfo(
                model: "Canon EOS R6 Mark III",
                serial: "PRIVATE-BRIDGE-CAMERA-SERIAL",
                api: "gphoto2"
            ),
            status: CameraStatus(),
            capabilities: CameraCapabilities(
                settings: [],
                matrix: CapabilityMatrix(supported: [.cameraIdentity, .stillCapture]),
                liveView: LiveViewCapabilities(),
                profile: CameraProfile.from(modelName: "Canon EOS R6 Mark III"),
                evidence: CameraCapabilityEvidence(observedFeatures: [.cameraIdentity])
            )
        )

        let report = DesktopBridgeDiagnosticReport.make(
            baseURL: try XCTUnwrap(URL(string: "http://192.168.1.10:18181")),
            bridgeVersion: "0.1.0",
            engine: "libgphoto2",
            snapshot: snapshot,
            lastError: "Camera PRIVATE-BRIDGE-CAMERA-SERIAL failed at \(windowsHome) and \(networkHome)",
            metadata: DiagnosticReportMetadata(
                productVersion: "9.8.7-test",
                generatedAt: "2026-07-29T00:00:00Z"
            )
        )

        XCTAssertTrue(report.contains("reportSchema=1"))
        XCTAssertTrue(report.contains("generatedAt=2026-07-29T00:00:00Z"))
        XCTAssertTrue(report.contains("productVersion=9.8.7-test"))
        XCTAssertTrue(report.contains("serial=[redacted]"))
        XCTAssertFalse(report.contains("PRIVATE-BRIDGE-CAMERA-SERIAL"))
        XCTAssertFalse(report.contains("C:" + "\\Users"))
        XCTAssertFalse(report.contains("PRIVATE-SERVER"))
        XCTAssertTrue(report.contains("[local-path]"))
        XCTAssertTrue(report.contains("validatedAdvertisedFeatureCount=1"))
        XCTAssertTrue(report.contains("unverifiedAdvertisedFeatures=STILL_CAPTURE"))
    }

    func testRejectsCredentialsQueryAndSubpathsInBaseURL() {
        for value in [
            "http://user:password@192.168.1.10:18181",
            "http://192.168.1.10:18181?token=secret",
            "http://192.168.1.10:18181/api",
            "ftp://192.168.1.10:18181",
        ] {
            XCTAssertThrowsError(try DesktopBridgeClient(baseURL: value)) { error in
                guard case DesktopBridgeError.invalidBaseURL = error else {
                    return XCTFail("Expected invalid base URL for \(value), got \(error)")
                }
            }
        }
    }

    private func enqueueStatus(method: String, path: String, on transport: MockCameraHTTPTransport) async {
        await transport.enqueueJSON(method: method, path: path, body: status)
    }
}
