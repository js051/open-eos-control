import Foundation
import XCTest

@testable import OpenEOSCore

final class DesktopBridgeClientTests: XCTestCase {
    private let health = #"{"ok":true,"service":"open-eos-control-bridge","version":"0.1.0","authRequired":true,"loopbackOnly":false,"engines":{}}"#
    private let status = #"{"connected":true,"battery":{"level":82,"status":"good"},"recording":false,"mode":"Manual","recordableShots":120,"remainingRecordingSeconds":3600,"media":{"available":true,"totalBytes":1000,"freeBytes":800,"freeImages":123,"devices":1},"exposure":{"iso":"400","shutter":"1/50","aperture":"2.8","whiteBalance":"Auto"},"raw":{"transport":"usb","recordable":{"recordableshots":120,"remainingtime":3600}}}"#
    private let capabilities = #"{"profile":{"modelName":"Canon EOS R6 Mark III","family":"EOS_R","priority":"PRIMARY"},"supported":["CAMERA_IDENTITY","DESKTOP_BRIDGE","USB_DIAGNOSTICS","LIVE_VIEW","LIVE_VIEW_MAGNIFICATION","STILL_CAPTURE","BULB_EXPOSURE","AUTOFOCUS","SHUTTER_HALF_PRESS","VIDEO_RECORDING","TAP_FOCUS","CLICK_WHITE_BALANCE","FOCUS_DRIVE","EXPOSURE_CONTROL","DIRECTORY_CONTROL","SENSOR_CLEANING","CAMERA_SLEEP","MEDIA_BROWSER","MEDIA_THUMBNAIL","MEDIA_PREVIEW","MEDIA_DOWNLOAD","MEDIA_PROTECT","MEDIA_RATING","MEDIA_ROTATE","MEDIA_ARCHIVE","MEDIA_DELETE"],"planned":["LIVE_VIEW_RTP","STILL_CAPTURE"],"reasons":{"LIVE_VIEW_RTP":"No verified decoder."},"liveView":{"sources":["DESKTOP_BRIDGE_STREAM"],"defaultSource":"DESKTOP_BRIDGE_STREAM","sizes":["MEDIUM","LARGE"],"defaultSize":"MEDIUM","magnifications":[1,5],"currentMagnification":1,"minFps":1,"maxFps":12},"settings":[{"key":"iso","label":"ISO Speed","value":"400","values":["100","400","800"]},{"key":"directoryselection","label":"Capture directory","value":"100EOSXX","values":["100EOSXX","101EOSXX"]}],"evidence":{"source":"libgphoto2","protocolVersions":["gphoto2 2.5.33"],"advertisedCommands":["POST /capture?token=secret"],"writableSettings":["iso","directoryselection"],"observedFeatures":["BATTERY_STATUS"],"discoveryTrace":[{"endpoint":"GET /ccapi","outcome":"NO_API_LIST","httpStatus":200,"responseKeys":["value"],"protocolVersions":[],"advertisedOperationCount":0,"truncated":false}],"truncated":false}}"#

    func testDesktopBridgeUploadsExactFileWithRequiredHeadersAndVerifiesResult() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(path: "/health", body: health)
        await transport.enqueueJSON(
            method: "POST",
            path: "/v1/session",
            status: 201,
            body: #"{"id":"session_upload","engine":"libgphoto2","camera":{"id":"gphoto2:test","model":"Canon EOS R6 Mark III","port":"usb:001,007","engine":"libgphoto2"}}"#
        )
        let payload = Data("camera-upload-payload\n".utf8)
        let fileURL = FileManager.default.temporaryDirectory.appendingPathComponent("sample file.JPG")
        try payload.write(to: fileURL, options: .atomic)
        defer { try? FileManager.default.removeItem(at: fileURL) }
        await transport.enqueueUpload(
            path: "/v1/session/session_upload/media?filename=sample%20file.JPG",
            body: Data(#"{"id":"gphoto2:file","name":"sample file.JPG","kind":"image","sizeBytes":22,"previewAvailable":false}"#.utf8)
        )
        let client = try DesktopBridgeClient(
            baseURL: "http://192.168.1.10:18181",
            cameraID: "gphoto2:test",
            transport: transport
        )
        try await client.initialize()
        let progress = DownloadProgressRecorder()
        let result = try await client.uploadMedia(from: fileURL, progress: progress.record)

        XCTAssertEqual(result.name, "sample file.JPG")
        XCTAssertEqual(result.sizeBytes, Int64(payload.count))
        let requests = await transport.requests()
        let request = try XCTUnwrap(requests.last)
        XCTAssertEqual(request.method, "POST")
        XCTAssertEqual(request.path, "/v1/session/session_upload/media?filename=sample%20file.JPG")
        XCTAssertEqual(request.body, payload)
        XCTAssertEqual(request.headers.first { $0.key.caseInsensitiveCompare("Content-Length") == .orderedSame }?.value, "22")
        XCTAssertEqual(request.headers.first { $0.key.caseInsensitiveCompare("Content-Type") == .orderedSame }?.value, "image/jpeg")
        XCTAssertTrue(progress.values().contains { $0.bytesTransferred == Int64(payload.count) })
    }

    func testDesktopBridgeRejectsUploadWhenReturnedNameOrSizeDoesNotMatchLocalFile() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(path: "/health", body: health)
        await transport.enqueueJSON(
            method: "POST",
            path: "/v1/session",
            status: 201,
            body: #"{"id":"session_upload_invalid","engine":"libgphoto2","camera":{"id":"gphoto2:test","model":"Canon EOS R6 Mark III","port":"usb:001,007","engine":"libgphoto2"}}"#
        )
        let fileURL = FileManager.default.temporaryDirectory.appendingPathComponent("invalid-result.JPG")
        try Data("payload".utf8).write(to: fileURL, options: .atomic)
        defer { try? FileManager.default.removeItem(at: fileURL) }
        await transport.enqueueUpload(
            path: "/v1/session/session_upload_invalid/media?filename=invalid-result.JPG",
            body: Data(#"{"id":"gphoto2:file","name":"different.JPG","kind":"image","sizeBytes":999,"previewAvailable":false}"#.utf8)
        )
        let client = try DesktopBridgeClient(
            baseURL: "http://192.168.1.10:18181",
            cameraID: "gphoto2:test",
            transport: transport
        )
        try await client.initialize()

        do {
            _ = try await client.uploadMedia(from: fileURL)
            XCTFail("Expected the client to reject an unverifiable upload result")
        } catch let error as DesktopBridgeError {
            guard case .invalidResponse(let message) = error else {
                return XCTFail("Unexpected error: \(error)")
            }
            XCTAssertTrue(message.contains("upload verification failed"))
        }
    }

    func testDesktopBridgeMediaPlaybackReusesTicketAndRevokesAfterLastStreamCloses() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(path: "/health", body: health)
        await transport.enqueueJSON(
            method: "POST",
            path: "/v1/session",
            status: 201,
            body: #"{"id":"session_stream","engine":"libgphoto2","camera":{"id":"gphoto2:test","model":"Canon EOS R6 Mark III","port":"usb:001,007","engine":"libgphoto2"}}"#
        )
        await transport.enqueueJSON(
            method: "POST",
            path: "/v1/session/session_stream/media/video-1/playback",
            body: #"{"url":"/v1/media-playback/ticket_123","expiresInSeconds":900}"#
        )
        await transport.enqueue(
            path: "/v1/media-playback/ticket_123",
            status: 206,
            headers: [
                "content-type": "video/mp4",
                "content-length": "32",
                "content-range": "bytes 4096-4127/16384",
            ],
            body: Data(repeating: 0x24, count: 32)
        )
        await transport.enqueue(
            path: "/v1/media-playback/ticket_123",
            status: 206,
            headers: [
                "content-type": "video/mp4",
                "content-length": "16",
                "content-range": "bytes 8192-8207/16384",
            ],
            body: Data(repeating: 0x42, count: 16)
        )
        await transport.enqueue(method: "DELETE", path: "/v1/media-playback/ticket_123", status: 204)
        let client = try DesktopBridgeClient(
            baseURL: "http://192.168.1.10:18181",
            token: "bridge-secret",
            cameraID: "gphoto2:test",
            transport: transport
        )
        try await client.initialize()
        let item = CameraMediaItem(id: "video-1", name: "MVI_0001.MP4", kind: "video", sizeBytes: 16384)
        let playback = try await client.beginMediaPlayback(item)

        let first = try await playback.open(offset: 4096, length: 32)
        var firstData = Data()
        for try await chunk in first.chunks { firstData.append(chunk) }
        first.cancel()
        let second = try await playback.open(offset: 8192, length: 16)
        var secondData = Data()
        for try await chunk in second.chunks { secondData.append(chunk) }
        await playback.close()
        let requestsBeforeLastStreamClosed = await transport.requests()
        XCTAssertEqual(requestsBeforeLastStreamClosed.count, 5)
        second.cancel()
        second.cancel()
        await playback.close()
        let requests = await waitForRequests(6, on: transport)

        XCTAssertEqual(first.rangeStart, 4096)
        XCTAssertEqual(first.totalBytes, 16384)
        XCTAssertEqual(firstData.count, 32)
        XCTAssertEqual(second.rangeStart, 8192)
        XCTAssertEqual(secondData.count, 16)
        XCTAssertEqual(requests.filter { $0.method == "POST" && $0.path.hasSuffix("/playback") }.count, 1)
        XCTAssertEqual(requests[2].path, "/v1/session/session_stream/media/video-1/playback")
        XCTAssertEqual(requests[3].path, "/v1/media-playback/ticket_123")
        XCTAssertEqual(
            requests[3].headers.first { $0.key.caseInsensitiveCompare("Range") == .orderedSame }?.value,
            "bytes=4096-4127"
        )
        XCTAssertEqual(requests[4].path, "/v1/media-playback/ticket_123")
        XCTAssertEqual(
            requests[4].headers.first { $0.key.caseInsensitiveCompare("Range") == .orderedSame }?.value,
            "bytes=8192-8207"
        )
        XCTAssertEqual(requests[5].method, "DELETE")
        XCTAssertEqual(requests[5].path, "/v1/media-playback/ticket_123")
        XCTAssertTrue(requests[2...5].allSatisfy { request in
            request.headers.first { $0.key.caseInsensitiveCompare("Authorization") == .orderedSame }?.value
                == "Bearer bridge-secret"
        })
    }

    func testDesktopBridgeMediaPlaybackRejectsCrossOriginTicketURL() async throws {
        let transport = MockCameraHTTPTransport()
        await transport.enqueueJSON(path: "/health", body: health)
        await transport.enqueueJSON(
            method: "POST",
            path: "/v1/session",
            status: 201,
            body: #"{"id":"session_stream_invalid","engine":"libgphoto2","camera":{"id":"gphoto2:test","model":"Canon EOS R6 Mark III","port":"usb:001,007","engine":"libgphoto2"}}"#
        )
        await transport.enqueueJSON(
            method: "POST",
            path: "/v1/session/session_stream_invalid/media/video-1/playback",
            body: #"{"url":"https://example.invalid/v1/media-playback/stolen","expiresInSeconds":900}"#
        )
        let client = try DesktopBridgeClient(
            baseURL: "http://192.168.1.10:18181",
            cameraID: "gphoto2:test",
            transport: transport
        )
        try await client.initialize()

        do {
            _ = try await client.beginMediaPlayback(
                CameraMediaItem(id: "video-1", name: "MVI_0001.MP4", kind: "video", sizeBytes: 16384)
            )
            XCTFail("Expected a cross-origin playback ticket to be rejected")
        } catch let error as DesktopBridgeError {
            guard case .invalidResponse(let message) = error else {
                return XCTFail("Unexpected error: \(error)")
            }
            XCTAssertTrue(message.contains("outside its own origin"))
        }
        let requests = await transport.requests()
        XCTAssertEqual(requests.count, 3)
    }

    func testDesktopBridgeMediaPlaybackRedactsTicketFromHTTPError() async throws {
        let transport = MockCameraHTTPTransport()
        let secretTicket = "ticket_private_value"
        await transport.enqueueJSON(path: "/health", body: health)
        await transport.enqueueJSON(
            method: "POST",
            path: "/v1/session",
            status: 201,
            body: #"{"id":"session_stream_error","engine":"libgphoto2","camera":{"id":"gphoto2:test","model":"Canon EOS R6 Mark III","port":"usb:001,007","engine":"libgphoto2"}}"#
        )
        await transport.enqueueJSON(
            method: "POST",
            path: "/v1/session/session_stream_error/media/video-1/playback",
            body: #"{"url":"/v1/media-playback/ticket_private_value","expiresInSeconds":900}"#
        )
        await transport.enqueue(path: "/v1/media-playback/\(secretTicket)", status: 404)
        await transport.enqueue(method: "DELETE", path: "/v1/media-playback/\(secretTicket)", status: 204)
        let client = try DesktopBridgeClient(
            baseURL: "http://192.168.1.10:18181",
            cameraID: "gphoto2:test",
            transport: transport
        )
        try await client.initialize()
        let playback = try await client.beginMediaPlayback(
            CameraMediaItem(id: "video-1", name: "MVI_0001.MP4", kind: "video", sizeBytes: 16384)
        )

        do {
            _ = try await playback.open(offset: 0, length: 1024)
            XCTFail("Expected the playback request to fail")
        } catch let error as DesktopBridgeError {
            let description = error.localizedDescription
            XCTAssertFalse(description.contains(secretTicket))
            XCTAssertTrue(description.contains("/v1/media-playback/[redacted]"))
        }
        await playback.close()
        let requests = await waitForRequests(5, on: transport)
        XCTAssertEqual(requests.last?.method, "DELETE")
    }

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

    func testBridgeCapabilitiesParseDynamicLiveViewMagnifications() async throws {
        let transport = MockCameraHTTPTransport()
        let dynamicCapabilities = capabilities
            .replacingOccurrences(
                of: #""magnifications":[1,5],"currentMagnification":1"#,
                with: #""magnifications":[1,5,10],"currentMagnification":10"#
            )
        await transport.enqueueJSON(path: "/health", body: health)
        await transport.enqueueJSON(
            method: "POST",
            path: "/v1/session",
            status: 201,
            body: #"{"id":"session_dynamic","engine":"libgphoto2","camera":{"id":"gphoto2:dXNi","model":"Canon EOS R6 Mark III","port":"usb:001,007","engine":"libgphoto2"}}"#
        )
        await transport.enqueueJSON(
            path: "/v1/session/session_dynamic/capabilities",
            body: dynamicCapabilities
        )
        let client = try DesktopBridgeClient(
            baseURL: "http://192.168.1.10:18181",
            cameraID: "gphoto2:dXNi",
            transport: transport
        )

        try await client.initialize()
        let parsed = try await client.capabilities()

        XCTAssertEqual(parsed.liveView.magnifications, [.x1, .x5, .x10])
        XCTAssertEqual(parsed.liveView.currentMagnification, .x10)
    }

    func testBridgeParsesAndAppliesTextMetadataSettings() async throws {
        let transport = MockCameraHTTPTransport()
        let textCapabilities = capabilities.replacingOccurrences(
            of: #""settings":[{"#,
            with: #""settings":[{"key":"ownername","label":"Owner Name","value":" Studio A ","values":[],"inputKind":"text","maxLength":255},{"#
        )
        await transport.enqueueJSON(path: "/health", body: health)
        await transport.enqueueJSON(
            method: "POST",
            path: "/v1/session",
            status: 201,
            body: #"{"id":"session_text","engine":"libgphoto2","camera":{"id":"gphoto2:dXNi","model":"Canon EOS R6 Mark III","port":"usb:001,007","engine":"libgphoto2"}}"#
        )
        await transport.enqueueJSON(path: "/v1/session/session_text/capabilities", body: textCapabilities)

        let client = try DesktopBridgeClient(
            baseURL: "http://192.168.1.10:18181",
            cameraID: "gphoto2:dXNi",
            transport: transport
        )
        try await client.initialize()
        let parsed = try await client.capabilities()
        let owner = try XCTUnwrap(parsed.setting("ownername"))
        XCTAssertEqual(owner.inputKind, .text)
        XCTAssertEqual(owner.maxLength, 255)
        XCTAssertEqual(owner.value, " Studio A ")
        XCTAssertTrue(owner.values.isEmpty)
        XCTAssertTrue(owner.accepts("Studio B"))
        XCTAssertTrue(owner.accepts(""))
        XCTAssertFalse(owner.accepts("é"))
        XCTAssertFalse(owner.accepts(String(repeating: "x", count: 256)))

        await transport.enqueueJSON(
            method: "POST",
            path: "/v1/session/session_text/settings/ownername",
            body: status
        )
        _ = try await client.setSetting(key: "ownername", value: "Studio B")
        let requests = await transport.requests()
        let write = try XCTUnwrap(requests.last { $0.path.hasSuffix("/settings/ownername") })
        XCTAssertEqual(write.method, "POST")
        let body = try XCTUnwrap(write.body)
        XCTAssertEqual(
            try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: String]),
            ["value": "Studio B"]
        )
    }

    func testBridgeRejectsInvalidLiveViewMagnificationAdvertisementWithoutSendingCommand() async throws {
        let transport = MockCameraHTTPTransport()
        let invalidCapabilities = capabilities.replacingOccurrences(
            of: #""magnifications":[1,5],"currentMagnification":1"#,
            with: #""magnifications":[1,5],"currentMagnification":"1""#
        )
        await transport.enqueueJSON(path: "/health", body: health)
        await transport.enqueueJSON(
            method: "POST",
            path: "/v1/session",
            status: 201,
            body: #"{"id":"session_invalid","engine":"libgphoto2","camera":{"id":"gphoto2:dXNi","model":"Canon EOS R6 Mark III","port":"usb:001,007","engine":"libgphoto2"}}"#
        )
        await transport.enqueueJSON(
            path: "/v1/session/session_invalid/capabilities",
            body: invalidCapabilities
        )
        let client = try DesktopBridgeClient(
            baseURL: "http://192.168.1.10:18181",
            cameraID: "gphoto2:dXNi",
            transport: transport
        )

        try await client.initialize()
        let parsed = try await client.capabilities()

        XCTAssertFalse(parsed.matrix.supports(.liveViewMagnification))
        XCTAssertTrue(parsed.matrix.planned.contains(.liveViewMagnification))
        XCTAssertTrue(parsed.liveView.magnifications.isEmpty)
        do {
            _ = try await client.setLiveViewMagnification(.x5)
            XCTFail("Expected invalid Bridge magnification metadata to block the command")
        } catch {
            let sentMagnificationCommand = await transport.requests().contains {
                $0.path.hasSuffix("/liveview/magnification")
            }
            XCTAssertFalse(sentMagnificationCommand)
        }
    }

    func testFileNamingCapabilityAndUpdateUseBridgeContract() async throws {
        let transport = MockCameraHTTPTransport()
        let fileNaming = #"{"stillFilenameMode":"preset_code","stillFilenameModeOptions":["preset_code","usersetting1","usersetting2"],"stillUserSetting1":"IMG_","stillUserSetting2":"EOS","movieIndex":"A_","movieReelNumber":1,"movieReelRange":{"minimum":1,"maximum":9999,"step":1},"movieClipNumber":1,"movieClipRange":{"minimum":1,"maximum":999,"step":1},"movieUserDefined":"EOS01"}"#
        let updatedFileNaming = fileNaming.replacingOccurrences(
            of: #""stillUserSetting1":"IMG_""#,
            with: #""stillUserSetting1":"EOS_""#
        )
        let fileNamingCapabilities = """
        {
          "profile":{"modelName":"Canon EOS R6 Mark III","family":"EOS_R","priority":"PRIMARY"},
          "supported":["CAMERA_IDENTITY","DESKTOP_BRIDGE","FILE_NAMING_CONTROL"],
          "planned":[],
          "liveView":{"sources":[],"sizes":[],"minFps":1,"maxFps":1},
          "settings":[],
          "fileNaming":\(fileNaming),
          "evidence":{"source":"GET /ccapi","writableSettings":["still-user-setting-1"]}
        }
        """
        await transport.enqueueJSON(path: "/health", body: health)
        await transport.enqueueJSON(
            method: "POST",
            path: "/v1/session",
            status: 201,
            body: #"{"id":"session_filename","engine":"ccapi","camera":{"id":"ccapi:test","model":"Canon EOS R6 Mark III","port":"network","engine":"ccapi"}}"#
        )
        await transport.enqueueJSON(
            path: "/v1/session/session_filename/capabilities",
            body: fileNamingCapabilities
        )
        await transport.enqueueJSON(
            path: "/v1/session/session_filename/capabilities",
            body: fileNamingCapabilities
        )
        await transport.enqueueJSON(
            method: "PUT",
            path: "/v1/session/session_filename/file-naming/still-user-setting-1",
            body: updatedFileNaming
        )
        let client = try DesktopBridgeClient(
            baseURL: "http://192.168.1.10:18181",
            cameraID: "ccapi:test",
            transport: transport
        )

        try await client.initialize()
        let capabilities = try await client.capabilities()
        let updated = try await client.setFileNaming(field: .stillUserSetting1, value: "EOS_")

        XCTAssertTrue(capabilities.matrix.supports(.fileNamingControl))
        XCTAssertEqual(capabilities.fileNaming?.stillUserSetting1, "IMG_")
        XCTAssertEqual(updated.stillUserSetting1, "EOS_")
        let requests = await transport.requests()
        let write = try XCTUnwrap(requests.first {
            $0.method == "PUT" && $0.path.hasSuffix("/file-naming/still-user-setting-1")
        })
        let body = try XCTUnwrap(write.body)
        XCTAssertEqual(
            try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: String]),
            ["value": "EOS_"]
        )
        let remainingResponses = await transport.remainingResponses()
        XCTAssertEqual(remainingResponses, 0)
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
        await transport.enqueueJSON(
            method: "POST",
            path: "/v1/session/session_123/directories",
            body: #"{"name":"ABCDE"}"#
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
            path: "/v1/session/session_123/media?limit=1",
            body: #"{"items":[{"id":"gphoto2:YWJj","name":"IMG_0001.JPG","kind":"image","sizeBytes":6,"captureTime":"2026-07-21T10:08:24+08:00","contentType":"image/jpeg","widthPixels":6000,"heightPixels":4000,"previewAvailable":true,"protected":null,"rating":null,"rotationDegrees":null,"archived":false}]}"#
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
        await transport.enqueueJSON(
            path: "/v1/session/session_123/media/gphoto2:YWJj/info",
            body: #"{"id":"gphoto2:YWJj","name":"IMG_0001.JPG","kind":"image","sizeBytes":6,"captureTime":"2026-07-21T10:08:24+08:00","contentType":"image/jpeg","widthPixels":6000,"heightPixels":4000,"previewAvailable":true,"protected":false,"rating":0,"rotationDegrees":0,"archived":false}"#
        )
        await transport.enqueueJSON(
            method: "PUT",
            path: "/v1/session/session_123/media/gphoto2:YWJj/protection",
            body: #"{"id":"gphoto2:YWJj","name":"IMG_0001.JPG","kind":"image","sizeBytes":6,"contentType":"image/jpeg","widthPixels":6000,"heightPixels":4000,"previewAvailable":true,"protected":true,"rating":0,"rotationDegrees":0,"archived":false}"#
        )
        await transport.enqueueJSON(
            method: "PUT",
            path: "/v1/session/session_123/media/gphoto2:YWJj/rating",
            body: #"{"id":"gphoto2:YWJj","name":"IMG_0001.JPG","kind":"image","sizeBytes":6,"contentType":"image/jpeg","widthPixels":6000,"heightPixels":4000,"previewAvailable":true,"protected":true,"rating":4,"rotationDegrees":0,"archived":false}"#
        )
        await transport.enqueueJSON(
            method: "PUT",
            path: "/v1/session/session_123/media/gphoto2:YWJj/rotation",
            body: #"{"id":"gphoto2:YWJj","name":"IMG_0001.JPG","kind":"image","sizeBytes":6,"contentType":"image/jpeg","widthPixels":6000,"heightPixels":4000,"previewAvailable":true,"protected":true,"rating":4,"rotationDegrees":180,"archived":false}"#
        )
        await transport.enqueueJSON(
            method: "PUT",
            path: "/v1/session/session_123/media/gphoto2:YWJj/archive",
            body: #"{"id":"gphoto2:YWJj","name":"IMG_0001.JPG","kind":"image","sizeBytes":6,"contentType":"image/jpeg","widthPixels":6000,"heightPixels":4000,"previewAvailable":true,"protected":true,"rating":4,"rotationDegrees":180,"archived":true}"#
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
            cameraEngine: "edsdk",
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
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.mediaProtect))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.mediaRating))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.mediaRotate))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.mediaArchive))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.sensorCleaning))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.cameraSleep))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.directoryControl))
        XCTAssertFalse(snapshot.capabilities.matrix.planned.contains(.stillCapture))
        XCTAssertEqual(snapshot.capabilities.liveView.sources, [.desktopBridgeStream])
        XCTAssertEqual(snapshot.capabilities.liveView.magnifications, [.x1, .x5])
        XCTAssertEqual(snapshot.capabilities.liveView.currentMagnification, .x1)
        XCTAssertEqual(snapshot.capabilities.liveView.maximumFPS, 12)
        XCTAssertEqual(snapshot.capabilities.evidence.advertisedCommands, ["POST /capture"])
        XCTAssertTrue(snapshot.capabilities.evidence.observedFeatures.contains(.batteryStatus))
        XCTAssertEqual(snapshot.capabilities.evidence.discoveryTrace.count, 1)
        XCTAssertEqual(snapshot.capabilities.evidence.discoveryTrace.first?.endpoint, "GET /ccapi")
        XCTAssertEqual(snapshot.capabilities.evidence.discoveryTrace.first?.outcome, "NO_API_LIST")
        XCTAssertEqual(snapshot.capabilities.evidence.discoveryTrace.first?.responseKeys, ["value"])

        _ = try await client.setSetting(key: "iso", value: "800")
        _ = try await client.syncCameraClock()
        try await client.cleanSensor(autoPowerOff: false)
        let createdDirectory = try await client.createDirectory(name: "ABCDE")
        XCTAssertEqual(createdDirectory, "ABCDE")
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

        let mediaProgress = MediaListProgressRecorder()
        let media = try await client.listMedia(maximumItems: 1) { items in
            await mediaProgress.record(items)
        }
        let mediaSnapshots = await mediaProgress.values()
        XCTAssertEqual(mediaSnapshots, [media])
        let item = try XCTUnwrap(media.first)
        XCTAssertTrue(item.previewAvailable)
        XCTAssertEqual(item.contentType, "image/jpeg")
        XCTAssertEqual(item.widthPixels, 6000)
        XCTAssertEqual(item.heightPixels, 4000)
        let thumbnail = try await client.mediaThumbnail(item)
        XCTAssertEqual(thumbnail.data, thumbnailJPEG)
        XCTAssertEqual(thumbnail.contentType, "image/jpeg")
        let preview = try await client.mediaPreview(item)
        XCTAssertEqual(preview.data, previewJPEG)
        XCTAssertEqual(preview.contentType, "image/jpeg")
        let info = try await client.mediaInfo(item)
        XCTAssertEqual(info.widthPixels, 6000)
        XCTAssertEqual(info.heightPixels, 4000)
        XCTAssertEqual(info.protected, false)
        XCTAssertEqual(info.rating, 0)
        XCTAssertEqual(info.rotationDegrees, 0)
        let protected = try await client.setMediaProtection(info, enabled: true)
        let rated = try await client.setMediaRating(protected, rating: 4)
        let rotated = try await client.setMediaRotation(rated, degrees: 180)
        let archived = try await client.setMediaArchive(rotated, enabled: true)
        XCTAssertEqual(protected.protected, true)
        XCTAssertEqual(rated.rating, 4)
        XCTAssertEqual(rotated.rotationDegrees, 180)
        XCTAssertEqual(archived.archived, true)
        let archiveRequests = await transport.requests()
        let archiveRequest = try XCTUnwrap(
            archiveRequests.last { $0.method == "PUT" && $0.path.hasSuffix("/archive") }
        )
        let archivePayload = try XCTUnwrap(archiveRequest.body)
        XCTAssertEqual(
            try JSONSerialization.jsonObject(with: archivePayload) as? [String: Bool],
            ["enabled": true]
        )
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
        XCTAssertEqual(sessionJSON["engine"] as? String, "edsdk")
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
        let directoryBody = try XCTUnwrap(
            requests.first { $0.path.hasSuffix("/directories") && $0.method == "POST" }?.body
        )
        XCTAssertEqual(
            (try XCTUnwrap(JSONSerialization.jsonObject(with: directoryBody) as? [String: Any]))["name"] as? String,
            "ABCDE"
        )

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

    func testMediaListRejectsNonPositiveMaximumItems() async throws {
        let client = try DesktopBridgeClient(
            baseURL: "http://192.168.1.10:18181",
            transport: MockCameraHTTPTransport()
        )

        for maximumItems in [0, -1, 1_001] {
            do {
                _ = try await client.listMedia(maximumItems: maximumItems)
                XCTFail("Expected maximumItems=\(maximumItems) to be rejected")
            } catch {
                XCTAssertEqual(
                    error as? DesktopBridgeError,
                    .invalidResponse("maximumItems must be from 1 through 1000.")
                )
            }
        }
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

    private func waitForRequests(
        _ count: Int,
        on transport: MockCameraHTTPTransport
    ) async -> [RecordedRequest] {
        for _ in 0..<100 {
            let requests = await transport.requests()
            if requests.count >= count { return requests }
            try? await Task.sleep(nanoseconds: 10_000_000)
        }
        return await transport.requests()
    }
}
