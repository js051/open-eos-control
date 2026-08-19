import AVFoundation
import CoreGraphics
import CoreVideo
import Foundation
import OpenEOSCore
import UniformTypeIdentifiers
import XCTest

@testable import OpenEOSControl

@MainActor
final class CameraAppTests: XCTestCase {
    func testDesktopBridgeFPSUpdateRequiresLiveViewRestart() async throws {
        let client = try DesktopBridgeClient(baseURL: "http://127.0.0.1:18181")
        let session = CameraSession.desktopBridge(client)

        let update = await session.setLiveViewTargetFPS(15)

        XCTAssertEqual(update, .restartRequired)
    }

    func testDirectCCAPIFPSUpdateDoesNotRequireLiveViewRestart() async throws {
        let client = try CCAPIClient(baseURL: "http://127.0.0.1:19090", mode: .camera)
        let session = CameraSession.ccapi(client)

        let update = await session.setLiveViewTargetFPS(15)

        XCTAssertEqual(update, .appliedInPlace)
    }

    func testRollingFrameRateUsesRecentWindow() {
        var tracker = LiveViewRateTracker(window: 1)

        XCTAssertEqual(tracker.record(0), 0)
        XCTAssertEqual(tracker.record(0.1), 10, accuracy: 0.001)
        XCTAssertEqual(tracker.record(0.2), 10, accuracy: 0.001)
        XCTAssertEqual(tracker.record(1.2), 1, accuracy: 0.001)
    }

    func testMediaPlaybackRejectsTruncatedRequestedRanges() {
        XCTAssertEqual(
            CameraMediaPlaybackValidation.expectedBytes(
                requestedLength: 64,
                totalBytes: 512,
                requestedOffset: 128
            ),
            64
        )
        XCTAssertFalse(
            CameraMediaPlaybackValidation.isComplete(
                deliveredBytes: 63,
                requestedLength: 64,
                totalBytes: 512,
                requestedOffset: 128
            )
        )
        XCTAssertTrue(
            CameraMediaPlaybackValidation.isComplete(
                deliveredBytes: 64,
                requestedLength: 64,
                totalBytes: 512,
                requestedOffset: 128
            )
        )
        XCTAssertFalse(
            CameraMediaPlaybackValidation.isComplete(
                deliveredBytes: 64,
                requestedLength: nil,
                totalBytes: nil,
                requestedOffset: 128
            )
        )
    }

    func testMediaPlaybackOnlyConfirmsExplicitMatchingByteRanges() {
        XCTAssertTrue(
            CameraMediaPlaybackValidation.rangeSupportConfirmed(
                statusCode: 206,
                responseRangeStart: 128,
                requestedOffset: 128
            )
        )
        XCTAssertFalse(
            CameraMediaPlaybackValidation.rangeSupportConfirmed(
                statusCode: 200,
                responseRangeStart: 0,
                requestedOffset: 128
            )
        )
        XCTAssertFalse(
            CameraMediaPlaybackValidation.rangeSupportConfirmed(
                statusCode: 206,
                responseRangeStart: 0,
                requestedOffset: 128
            )
        )
    }

    func testMediaPlaybackIgnoresCancelledTransportErrors() {
        XCTAssertTrue(CameraMediaPlaybackValidation.isCancellation(URLError(.cancelled)))
        XCTAssertTrue(CameraMediaPlaybackValidation.isCancellation(CancellationError()))
        XCTAssertFalse(CameraMediaPlaybackValidation.isCancellation(URLError(.networkConnectionLost)))
    }

    func testMediaPlaybackDoesNotDownloadAgainForUnsupportedCodec() {
        XCTAssertFalse(CameraMediaPlaybackValidation.shouldPrepareFallback(for: .unsupportedFormat))
        XCTAssertFalse(CameraMediaPlaybackValidation.shouldPrepareFallback(for: .storageUnavailable))
        XCTAssertTrue(CameraMediaPlaybackValidation.shouldPrepareFallback(for: .incompleteRange))
        XCTAssertTrue(CameraMediaPlaybackValidation.shouldPrepareFallback(for: .transport))
    }

    func testMediaPlaybackCapacityAllowsVideosLargerThanOneGiBWhenSpaceExists() {
        let twoGiB: Int64 = 2 * 1024 * 1024 * 1024

        XCTAssertTrue(
            CameraMediaPlaybackValidation.hasFallbackCapacity(
                mediaBytes: twoGiB,
                availableCapacity: twoGiB + 512 * 1024 * 1024
            )
        )
        XCTAssertFalse(
            CameraMediaPlaybackValidation.hasFallbackCapacity(
                mediaBytes: twoGiB,
                availableCapacity: twoGiB + 64 * 1024 * 1024
            )
        )
        XCTAssertEqual(
            CameraMediaPlaybackValidation.expectedBytes(
                requestedLength: 64,
                totalBytes: 150,
                requestedOffset: 128
            ),
            22
        )
        XCTAssertTrue(
            CameraMediaPlaybackValidation.isComplete(
                deliveredBytes: 22,
                requestedLength: 64,
                totalBytes: 150,
                requestedOffset: 128
            )
        )
    }

    func testMediaPlaybackUsesFilenameWhenCameraReturnsGenericContentType() throws {
        let expected = try XCTUnwrap(UTType(filenameExtension: "mp4")?.identifier)

        XCTAssertEqual(
            CameraMediaPlaybackContentType.identifier(
                responseContentType: "application/octet-stream; charset=binary",
                filename: "MVI_0001.MP4"
            ),
            expected
        )
        XCTAssertEqual(
            CameraMediaPlaybackContentType.identifier(
                responseContentType: "video/mp4",
                filename: "MVI_0001.BIN"
            ),
            expected
        )
        XCTAssertNil(
            CameraMediaPlaybackContentType.identifier(
                responseContentType: "application/octet-stream",
                filename: "MVI_0001.BIN"
            )
        )
    }

    func testMediaPlaybackRecoveryAndContainerLabels() {
        XCTAssertEqual(cameraVideoContainerLabel("MVI_0001.MP4"), "MP4")
        XCTAssertEqual(cameraVideoContainerLabel("clip.mov"), "QuickTime MOV")
        XCTAssertEqual(cameraVideoContainerLabel("clip.m4v"), "M4V")
        XCTAssertEqual(cameraVideoContainerLabel("clip.avi"), "AVI")
        XCTAssertEqual(cameraVideoContainerLabel("clip.mkv"), "Matroska MKV")
        XCTAssertEqual(cameraVideoContainerLabel("clip.bin"), "BIN")
        XCTAssertEqual(cameraVideoContainerLabel("clip"), "VIDEO")
        XCTAssertTrue(CameraMediaPlaybackFailure.transport.retryable)
        XCTAssertTrue(CameraMediaPlaybackFailure.incompleteRange.retryable)
        XCTAssertTrue(CameraMediaPlaybackFailure.storageUnavailable.retryable)
        XCTAssertFalse(CameraMediaPlaybackFailure.unsupportedFormat.retryable)
    }

    func testMediaResourceLoaderDecodesBaselineH264FromCameraRanges() async throws {
        let fixtureURL = try XCTUnwrap(
            Bundle(for: Self.self).url(
                forResource: "valid-h264-baseline",
                withExtension: "mp4"
            )
        )
        let fixture = try Data(contentsOf: fixtureURL)
        let media = CameraMediaItem(
            id: "fixture-video",
            name: "MVI_FIXTURE.MP4",
            kind: "video",
            sizeBytes: Int64(fixture.count)
        )
        let requests = MediaPlaybackRequestRecorder()
        let loader = CameraMediaResourceLoader(item: media) { offset, length in
            await requests.record(offset: offset, length: length)
            let available = max(0, Int64(fixture.count) - offset)
            let count = min(length ?? available, available)
            let start = Int(offset)
            let end = start + Int(count)
            let payload = start <= fixture.count && end <= fixture.count
                ? Data(fixture[start..<end])
                : Data()
            return CameraMediaPlaybackResource(
                statusCode: 206,
                contentType: "application/octet-stream",
                totalBytes: Int64(fixture.count),
                rangeStart: offset,
                chunks: AsyncThrowingStream { continuation in
                    if !payload.isEmpty { continuation.yield(payload) }
                    continuation.finish()
                }
            )
        }
        defer { loader.invalidate() }
        let asset = AVURLAsset(url: loader.assetURL)
        asset.resourceLoader.setDelegate(loader, queue: loader.delegateQueue)

        let isPlayable = try await asset.load(.isPlayable)
        XCTAssertTrue(isPlayable)
        let videoTracks = try await asset.loadTracks(withMediaType: .video)
        let track = try XCTUnwrap(videoTracks.first)
        let reader = try AVAssetReader(asset: asset)
        let output = AVAssetReaderTrackOutput(
            track: track,
            outputSettings: [
                kCVPixelBufferPixelFormatTypeKey as String: Int(kCVPixelFormatType_32BGRA),
            ]
        )
        XCTAssertTrue(reader.canAdd(output))
        reader.add(output)
        XCTAssertTrue(reader.startReading())
        let sample = try XCTUnwrap(output.copyNextSampleBuffer())
        let imageBuffer = try XCTUnwrap(CMSampleBufferGetImageBuffer(sample))

        XCTAssertGreaterThan(CVPixelBufferGetWidth(imageBuffer), 0)
        XCTAssertGreaterThan(CVPixelBufferGetHeight(imageBuffer), 0)
        let recordedRequests = await requests.values()
        XCTAssertFalse(recordedRequests.isEmpty)
        XCTAssertNotEqual(reader.status, .failed, reader.error?.localizedDescription ?? "decoder failed")
    }

    func testMediaPlaybackClassifiesStorageExhaustion() {
        XCTAssertEqual(
            CameraMediaPlaybackValidation.failure(
                for: CocoaError(.fileWriteOutOfSpace)
            ),
            .storageUnavailable
        )
        XCTAssertEqual(
            CameraMediaPlaybackValidation.failure(
                for: POSIXError(.ENOSPC)
            ),
            .storageUnavailable
        )
    }

    func testCameraStatusReplacementPreservesDeviceStatus() {
        let original = CameraStatus(
            recording: false,
            exposure: ExposureState(iso: "100", shutter: "1/125", aperture: "4.0", whiteBalance: "auto"),
            recordableShots: 2_418,
            remainingRecordingSeconds: 7_200,
            lens: LensStatus(mounted: true, name: "RF24-105mm F4 L IS USM"),
            temperature: .warning
        )

        let replaced = original.replacing(
            exposure: ExposureState(iso: "200", shutter: "1/125", aperture: "4.0", whiteBalance: "auto"),
            recording: true
        )

        XCTAssertEqual(replaced.lens, original.lens)
        XCTAssertEqual(replaced.temperature, .warning)
        XCTAssertEqual(replaced.recordableShots, 2_418)
        XCTAssertEqual(replaced.remainingRecordingSeconds, 7_200)
        XCTAssertEqual(replaced.exposure.iso, "200")
        XCTAssertEqual(replaced.recording, true)
    }

    func testAspectFitRectPreservesImageAndLetterboxes() {
        let rect = aspectFitRect(
            contentSize: CGSize(width: 3_000, height: 2_000),
            containerSize: CGSize(width: 400, height: 400)
        )

        XCTAssertEqual(rect.minX, 0, accuracy: 0.001)
        XCTAssertEqual(rect.width, 400, accuracy: 0.001)
        XCTAssertEqual(rect.height, 266.666, accuracy: 0.01)
        XCTAssertEqual(rect.midY, 200, accuracy: 0.001)
    }

    func testCameraRTPAddressRequiresTheSameIPv4Subnet() {
        XCTAssertTrue(
            CameraRTPNetworkAddress.sameIPv4Subnet(
                cameraAddress: "192.168.1.2",
                localAddress: "192.168.1.37",
                netmask: "255.255.255.0"
            )
        )
        XCTAssertFalse(
            CameraRTPNetworkAddress.sameIPv4Subnet(
                cameraAddress: "192.168.1.2",
                localAddress: "192.168.2.37",
                netmask: "255.255.255.0"
            )
        )
        XCTAssertFalse(
            CameraRTPNetworkAddress.sameIPv4Subnet(
                cameraAddress: "camera.local",
                localAddress: "192.168.1.37",
                netmask: "255.255.255.0"
            )
        )
    }

    func testRealLatmFixtureDecodesToPCMWithAppleAACDecoder() throws {
        let extractor = CCAPILatmSampleExtractor()
        let decoder = IOSAACDecoder()
        let firstMux = Data(base64Encoded: "IAARkB/gvvAQAmMLsxmxkXGRwXGJgZACEQBGCMHA")!
        let repeatedMux = Data(base64Encoded: "gxCIAjBGDgA=")!
        var decodedFrames = 0

        for index in 0..<32 {
            let sample = try extractor.consume(
                CCAPILatmRTPAccessUnit(
                    audioMuxElement: index == 0 ? firstMux : repeatedMux,
                    rtpTimestamp: UInt32(index * 1_024)
                ),
                presentationTimeMicroseconds: Int64(index * 21_333)
            )
            let pcm = try decoder.decode(sample)
            if let pcm {
                XCTAssertEqual(Int(pcm.format.sampleRate.rounded()), 48_000)
                XCTAssertEqual(pcm.format.channelCount, 2)
                decodedFrames += Int(pcm.frameLength)
            }
        }

        XCTAssertGreaterThan(decodedFrames, 0)
    }

    func testCameraAudioStartsMutedAndIsNotPersisted() {
        let suite = "OpenEOSControlTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }

        let state = CameraAppState(defaults: defaults)
        XCTAssertFalse(state.rtpAudioRequested)
        XCTAssertFalse(state.rtpAudioStatus.enabled)
        XCTAssertNil(defaults.object(forKey: "rtp-audio-enabled"))
    }

    func testRequestedDisconnectClearsVisibleStateSynchronously() {
        let suite = "OpenEOSControlTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let state = CameraAppState(defaults: defaults)
        state.openOfflinePreview()
        state.activeSheet = .actions

        state.requestDisconnect()

        XCTAssertFalse(state.connected)
        XCTAssertFalse(state.isPreview)
        XCTAssertNil(state.activeSheet)
        XCTAssertEqual(state.screen, .control)
        XCTAssertTrue(state.mediaItems.isEmpty)
        XCTAssertEqual(state.mediaLibraryLoadStatus, .notLoaded)
    }

    func testCCAPIPresetsCarryExplicitConnectionIntent() {
        let suite = "OpenEOSControlTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let state = CameraAppState(defaults: defaults)

        XCTAssertEqual(state.ccapiConnectionMode, .automatic)

        state.useSimulatorPreset()
        XCTAssertEqual(state.ccapiConnectionMode, .simulator)
        state.setBaseURL("http://127.0.0.1:19090")
        XCTAssertEqual(state.ccapiConnectionMode, .simulator)

        state.useHTTPPreset()
        XCTAssertEqual(state.ccapiConnectionMode, .camera)
        state.setBaseURL(CameraAppState.simulatorURL)
        XCTAssertEqual(state.ccapiConnectionMode, .camera)

        state.useHTTPSPreset()
        XCTAssertEqual(state.ccapiConnectionMode, .camera)
    }

    func testClosingReplacedRTPSessionDoesNotClearCurrentAudioStatus() async throws {
        let recorder = RTPAudioStatusRecorder()
        let controller = IOSCcapiRTPController()
        controller.setEventHandler { event in
            if case let .audioStatus(status) = event { recorder.record(status) }
        }
        let description = try CCAPIRTPSessionDescriptionParser.parse(
            """
            v=0
            m=video 12000 RTP/AVP 103
            a=rtpmap:103 H264/90000
            m=audio 12010 RTP/AVP 106
            a=rtpmap:106 MP4A-LATM/48000
            a=fmtp:106 cpresent=1
            """
        )

        let first = try await controller.makeSession(
            description: description,
            destinationAddress: "127.0.0.1"
        )
        let second = try await controller.makeSession(
            description: description,
            destinationAddress: "127.0.0.1"
        )

        XCTAssertEqual(recorder.last?.advertised, true)
        XCTAssertEqual(recorder.last?.available, false)
        XCTAssertEqual(recorder.last?.enabled, false)
        await first.close()
        XCTAssertEqual(recorder.last?.advertised, true)
        XCTAssertEqual(recorder.last?.codec, "MP4A-LATM")

        await second.close()
        XCTAssertEqual(recorder.last, .inactive)
    }

    func testAdvancedSettingsAreFilteredByCaptureMode() {
        let settings = [
            CameraSetting(key: "iso", label: "ISO", value: "100", values: ["100"]),
            CameraSetting(key: "moviemode", label: "Movie mode", value: "off", values: ["off", "on"]),
            CameraSetting(key: "drivemode", label: "Drive", value: "single", values: ["single", "continuous"]),
            CameraSetting(key: "moviequality", label: "Movie", value: "4K", values: ["4K", "FHD"]),
            CameraSetting(key: "highframerate", label: "High frame rate", value: "disable", values: ["enable", "disable"]),
            CameraSetting(key: "moviecropping", label: "Movie cropping", value: "disable", values: ["enable", "disable"]),
            CameraSetting(key: "movieformat", label: "Movie format", value: "mp4", values: ["raw", "mp4"]),
            CameraSetting(key: "meteringmode", label: "Metering", value: "eval", values: ["eval", "spot"]),
            CameraSetting(key: "alomode", label: "ALO", value: "x3", values: ["x3"]),
            CameraSetting(
                key: "capturetarget",
                label: "Capture target",
                value: "Internal RAM",
                values: ["Internal RAM", "Memory card"]
            ),
            CameraSetting(key: "capturestorage", label: "Recording card", value: "CFe", values: ["CFe", "SD"]),
            CameraSetting(key: "cardselectionstillimage", label: "Still-image card", value: "card1", values: ["none", "card1", "card2"]),
            CameraSetting(key: "cardselectionmovie", label: "Movie card", value: "card2", values: ["none", "card1", "card2"]),
            CameraSetting(key: "soundrecording", label: "Sound recording", value: "manual", values: ["auto", "manual", "disable"]),
            CameraSetting(key: "soundrecordinglevel", label: "Sound recording level", value: "32", values: (0...63).map(String.init)),
            CameraSetting(key: "soundrecordingmodeintmic", label: "Internal mic", value: "auto", values: ["auto", "manual"]),
            CameraSetting(key: "soundrecordinglevelintmic", label: "Internal mic level", value: "32", values: (0...63).map(String.init)),
            CameraSetting(key: "windfilter", label: "Wind filter", value: "auto", values: ["auto", "enable", "disable"]),
            CameraSetting(key: "windfilterintmic", label: "Internal wind filter", value: "enable", values: ["enable", "disable"]),
            CameraSetting(key: "attenuator", label: "Attenuator", value: "disable", values: ["enable", "disable", "auto", "manual"]),
            CameraSetting(key: "attenuatoracc", label: "Accessory attenuator", value: "disable", values: ["enable", "disable"]),
            CameraSetting(key: "beep", label: "Beep", value: "enable", values: ["enable", "disable", "disabletouch"]),
            CameraSetting(key: "displayoff", label: "Auto display off", value: "60", values: ["10", "20", "30", "60", "120", "180"]),
            CameraSetting(key: "focusbracketing", label: "Focus bracketing", value: "disable", values: ["enable", "disable"]),
            CameraSetting(key: "focusbracketingnumberofshots", label: "Number of shots", value: "100", values: (2...999).map(String.init)),
            CameraSetting(key: "focusbracketingfocusincrement", label: "Focus increment", value: "4", values: (1...10).map(String.init)),
            CameraSetting(key: "focusbracketingexposuresmoothing", label: "Exposure smoothing", value: "disable", values: ["enable", "disable"]),
        ]

        XCTAssertEqual(
            advancedSettingsForMode(settings, mode: .photo).map(\.key),
            [
                "drivemode", "meteringmode", "capturetarget", "capturestorage", "cardselectionstillimage",
                "beep", "displayoff",
                "focusbracketing", "focusbracketingnumberofshots", "focusbracketingfocusincrement",
                "focusbracketingexposuresmoothing",
            ]
        )
        XCTAssertEqual(
            advancedSettingsForMode(settings, mode: .video).map(\.key),
            [
                "moviequality", "highframerate", "moviecropping", "movieformat", "meteringmode",
                "cardselectionmovie", "soundrecording",
                "soundrecordinglevel", "soundrecordingmodeintmic", "soundrecordinglevelintmic",
                "windfilter", "windfilterintmic", "attenuator", "attenuatoracc", "beep", "displayoff",
            ]
        )

        let movieMode = try! XCTUnwrap(captureModeSetting(settings))
        XCTAssertEqual(appCaptureMode(for: movieMode), .photo)
        XCTAssertEqual(captureModeValue(for: .video, setting: movieMode, preferredPhotoValue: nil), "on")
    }

    func testOfflinePreviewSupportsTextMetadataDraftValues() async {
        let suite = "OpenEOSControlTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let state = CameraAppState(defaults: defaults)
        state.openOfflinePreview()

        let owner = try! XCTUnwrap(state.capabilities?.setting("ownername"))
        XCTAssertEqual(owner.inputKind, .text)
        XCTAssertEqual(owner.maxLength, 255)
        XCTAssertTrue(owner.accepts(" Studio A "))
        XCTAssertTrue(owner.accepts(""))
        XCTAssertFalse(owner.accepts("é"))
        XCTAssertFalse(owner.accepts(String(repeating: "x", count: 256)))

        await state.setSetting(key: "ownername", value: " Studio A ")
        XCTAssertEqual(state.capabilities?.setting("ownername")?.value, " Studio A ")
        XCTAssertNil(state.lastError)
    }

    func testR6AdvancedSettingLocalizationKeepsProtocolValuesSeparate() {
        let expectedLabels = [
            "whitebalanceadjusta": "setting_white_balance_shift_a",
            "whitebalanceadjustb": "setting_white_balance_shift_b",
            "wbshift.ba": "setting_white_balance_shift_ba",
            "wbshift.mg": "setting_white_balance_shift_mg",
            "aspectratio": "setting_aspect_ratio",
            "zoom": "setting_zoom",
            "soundrecording": "setting_sound_recording",
            "soundrecordinglevel": "setting_sound_recording_level",
            "windfilter": "setting_wind_filter",
            "attenuator": "setting_attenuator",
            "focusbracketing": "setting_focus_bracketing",
            "focusbracketingnumberofshots": "setting_focus_bracketing_shots",
            "focusbracketingfocusincrement": "setting_focus_bracketing_increment",
            "focusbracketingexposuresmoothing": "setting_focus_bracketing_exposure_smoothing",
            "highframerate": "setting_high_frame_rate",
            "moviecropping": "setting_movie_cropping",
            "movieformat": "setting_movie_format",
            "zoomspeed": "setting_power_zoom_speed",
            "autopoweroff": "setting_auto_power_off",
            "beep": "setting_beep",
            "displayoff": "setting_display_off",
            "alomode": "setting_auto_lighting_optimizer",
            "capturetarget": "setting_capture_target",
            "capturestorage": "setting_capture_storage",
            "cardselectionstillimage": "setting_still_image_card",
            "cardselectionmovie": "setting_movie_card",
            "stillimagequality.raw": "setting_image_quality_raw",
            "stillimagequality.jpeg": "setting_image_quality_jpeg",
            "stillimagequalitysd": "setting_image_quality_sd",
            "stillimagequalitycf": "setting_image_quality_cf",
        ]
        for (key, localizationKey) in expectedLabels {
            XCTAssertEqual(settingLabelLocalizationKey(key), localizationKey)
        }

        XCTAssertEqual(
            settingValueLocalizationKey(key: "autopoweroff", value: "1800"),
            "camera_value_30_minutes"
        )
        XCTAssertEqual(
            settingValueLocalizationKey(key: "autopoweroff", value: "120"),
            "camera_value_2_minutes"
        )
        XCTAssertNil(settingValueLocalizationKey(key: "autopoweroff", value: "4294967295"))
        XCTAssertEqual(
            settingValueLocalizationKey(key: "capturetarget", value: "Internal RAM"),
            "camera_value_internal_ram"
        )
        XCTAssertEqual(
            settingValueLocalizationKey(key: "capturetarget", value: "Memory card"),
            "camera_value_memory_card"
        )
        XCTAssertEqual(
            settingValueLocalizationKey(key: "capturestorage", value: "Card 2"),
            "camera_value_card_2"
        )
        XCTAssertEqual(
            settingValueLocalizationKey(key: "cardselectionstillimage", value: "card1"),
            "camera_value_card_1"
        )
        XCTAssertEqual(
            settingValueLocalizationKey(key: "cardselectionmovie", value: "none"),
            "camera_value_none"
        )
        XCTAssertEqual(
            settingValueLocalizationKey(key: "stillimagequalitycf", value: "cRAW + Large Fine JPEG"),
            "camera_value_craw_large_fine_jpeg"
        )
        XCTAssertEqual(
            settingValueLocalizationKey(key: "stillimagequality.jpeg", value: "large_fine"),
            "camera_value_large_fine"
        )
        XCTAssertEqual(settingValueLocalizationKey(key: "continuousaf", value: "Off"), "camera_value_off")
        XCTAssertEqual(settingValueLocalizationKey(key: "soundrecording", value: "manual"), "camera_value_manual")
        XCTAssertEqual(settingValueLocalizationKey(key: "windfilter", value: "enable"), "camera_value_enable")
        XCTAssertEqual(settingValueLocalizationKey(key: "attenuator", value: "disable"), "camera_value_disable")
        XCTAssertEqual(settingValueLocalizationKey(key: "beep", value: "disabletouch"), "camera_value_disable_touch")
        XCTAssertEqual(settingValueLocalizationKey(key: "displayoff", value: "20"), "camera_value_20_seconds")
        XCTAssertEqual(settingValueLocalizationKey(key: "displayoff", value: "120"), "camera_value_2_minutes")
        XCTAssertEqual(settingValueLocalizationKey(key: "alomode", value: "Standard"), "camera_value_standard")
        XCTAssertEqual(
            settingValueLocalizationKey(key: "alomode", value: "High (disabled in manual exposure)"),
            "camera_value_high_disabled_manual"
        )
    }

    func testEnglishAndTraditionalChineseResourcesHaveMatchingKeys() throws {
        let projectRoot = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        let english = try String(
            contentsOf: projectRoot.appendingPathComponent("App/Resources/en.lproj/Localizable.strings"),
            encoding: .utf8
        )
        let traditionalChinese = try String(
            contentsOf: projectRoot.appendingPathComponent("App/Resources/zh-Hant.lproj/Localizable.strings"),
            encoding: .utf8
        )

        func resourceKeys(_ source: String) -> Set<String> {
            Set(source.split(separator: "\n").compactMap { line in
                guard line.first == "\"" else { return nil }
                let remainder = line.dropFirst()
                guard let closingQuote = remainder.firstIndex(of: "\"") else { return nil }
                return String(remainder[..<closingQuote])
            })
        }

        XCTAssertEqual(resourceKeys(english), resourceKeys(traditionalChinese))
    }

    func testOfflinePreviewHasRealisticCapabilityGating() {
        let snapshot = CameraAppState.makeOfflinePreviewSnapshot()

        XCTAssertEqual(snapshot.info.model, "Canon EOS R6 Mark III")
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.liveView))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.stillCapture))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.bulbExposure))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.mediaDownload))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.mediaDelete))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.clickWhiteBalance))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.liveViewMagnification))
        XCTAssertEqual(snapshot.capabilities.liveView.magnifications, [.x1, .x5, .x10])
        XCTAssertEqual(snapshot.capabilities.liveView.currentMagnification, .x1)
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.soundRecordingLevelControl))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.soundRecordingControl))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.focusBracketingControl))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.sensorCleaning))
        XCTAssertTrue(snapshot.capabilities.matrix.supports(.cameraSleep))
        XCTAssertFalse(snapshot.capabilities.matrix.supports(.focusDrive))
        XCTAssertEqual(snapshot.capabilities.liveView.maximumFPS, 30)
        XCTAssertEqual(
            snapshot.capabilities.settings.first(where: { $0.key == "capturestorage" })?.values,
            ["CFe", "SD"]
        )
        XCTAssertEqual(
            snapshot.capabilities.setting("soundrecording")?.values,
            ["auto", "manual", "disable"]
        )
        XCTAssertEqual(
            snapshot.capabilities.setting("soundrecordinglevel")?.values,
            (0...63).map(String.init)
        )
        XCTAssertEqual(
            snapshot.capabilities.setting("focusbracketingnumberofshots")?.values,
            (2...999).map(String.init)
        )
        XCTAssertEqual(
            snapshot.capabilities.setting("focusbracketingfocusincrement")?.values,
            (1...10).map(String.init)
        )
    }

    func testMonitoringAssistDefaultsDoNotAlterTheLiveView() {
        let settings = LiveViewMonitorSettings()

        XCTAssertFalse(settings.needsPixelAnalysis)
        XCTAssertEqual(settings.frameGuide, .off)
        XCTAssertEqual(settings.desqueeze.horizontalScale, 1)
        XCTAssertFalse(settings.safeAreaVisible)
    }

    func testDiagnosticReportIncludesMonitoringAssistState() async {
        let suite = "OpenEOSControlTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let state = CameraAppState(defaults: defaults)
        state.monitorSettings = LiveViewMonitorSettings(
            histogramVisible: true,
            waveformVisible: true,
            zebraThresholdPercent: 95,
            falseColorEnabled: true,
            focusPeakingEnabled: true,
            frameGuide: .ratio2x39,
            safeAreaVisible: true,
            desqueeze: .x1_5
        )

        let report = await state.diagnosticReport()

        XCTAssertTrue(report.contains("monitorHistogram=true"))
        XCTAssertTrue(report.contains("monitorWaveform=true"))
        XCTAssertTrue(report.contains("monitorZebra=95"))
        XCTAssertTrue(report.contains("monitorFalseColor=true"))
        XCTAssertTrue(report.contains("monitorFocusPeaking=true"))
        XCTAssertTrue(report.contains("monitorFrameGuide=ratio2x39"))
        XCTAssertTrue(report.contains("monitorSafeArea=true"))
        XCTAssertTrue(report.contains("monitorDesqueeze=x1_5"))
    }

    func testOfflinePreviewReportsCompleteMediaTraversal() async {
        let suite = "OpenEOSControlTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let state = CameraAppState(defaults: defaults)

        state.openOfflinePreview()
        let report = await state.diagnosticReport()

        XCTAssertEqual(state.mediaLibraryLoadStatus, .complete)
        XCTAssertEqual(state.mediaLibraryScope, .recent)
        XCTAssertFalse(state.mediaLibraryHasMore)
        XCTAssertTrue(report.contains("mediaItemCount=3"))
        XCTAssertTrue(report.contains("mediaLoadStatus=COMPLETE"))
        XCTAssertTrue(report.contains("mediaLibraryScope=recent"))
        XCTAssertTrue(report.contains("mediaLibraryRequestLimit=61"))
    }

    func testPhysicalValidationRequiresAdvertisedAndObservedEvidence() {
        let summary = PhysicalValidationSummary(
            connected: true,
            isPreview: false,
            info: physicalCameraInfo(),
            capabilities: physicalValidationCapabilities(),
            operatorConfirmedFeatures: [.stillCapture, .liveView, .usbDiagnostics]
        )

        XCTAssertEqual(summary.sessionStatus, .ready)
        XCTAssertEqual(summary.eligibleFeatures, [.stillCapture])
        XCTAssertEqual(summary.operatorConfirmedFeatures, [.stillCapture])
    }

    func testPhysicalValidationRejectsSimulatorAndOfflinePreview() {
        let simulator = PhysicalValidationSummary(
            connected: true,
            isPreview: false,
            info: CameraInfo(
                model: "Canon EOS R6 Mark III",
                serial: "sim-r6m3",
                api: "simulated-ccapi"
            ),
            capabilities: physicalValidationCapabilities(),
            operatorConfirmedFeatures: [.stillCapture]
        )
        let preview = PhysicalValidationSummary(
            connected: true,
            isPreview: true,
            info: physicalCameraInfo(),
            capabilities: physicalValidationCapabilities(),
            operatorConfirmedFeatures: [.stillCapture]
        )

        XCTAssertEqual(simulator.sessionStatus, .simulator)
        XCTAssertTrue(simulator.eligibleFeatures.isEmpty)
        XCTAssertEqual(preview.sessionStatus, .offlinePreview)
        XCTAssertTrue(preview.operatorConfirmedFeatures.isEmpty)
        XCTAssertThrowsError(
            try PhysicalValidationRecord.make(
                summary: simulator,
                info: physicalCameraInfo(),
                transport: "CCAPI_NETWORK",
                diagnosticReport: "generatedAt=2026-08-01T00:00:00Z\nproductVersion=0.1.8"
            )
        )
    }

    func testPhysicalValidationRecordOmitsPrivateDiagnosticFields() throws {
        let privateSerial = "PRIVATE-CAMERA-SERIAL"
        let privatePassword = "camera-password"
        let localPath = "C:/Us" + "ers/private/capture.jpg"
        let summary = PhysicalValidationSummary(
            connected: true,
            isPreview: false,
            info: physicalCameraInfo(serial: privateSerial),
            capabilities: physicalValidationCapabilities(),
            operatorConfirmedFeatures: [.stillCapture]
        )
        let diagnostic = [
            "Open EOS Control iOS diagnostic report",
            "generatedAt=2026-08-01T00:00:00Z",
            "productVersion=0.1.8-test",
            "serial=[redacted]",
            "baseUrl=http://camera-user:\(privatePassword)@192.168.1.2:8080",
            "lastError=Failed at \(localPath)",
        ].joined(separator: "\n")

        let record = try PhysicalValidationRecord.make(
            summary: summary,
            info: physicalCameraInfo(serial: privateSerial),
            transport: "CCAPI_NETWORK",
            diagnosticReport: diagnostic
        )

        XCTAssertTrue(record.contains("Record schema: 1"))
        XCTAssertTrue(record.contains("| STILL_CAPTURE | true | true | true |"))
        XCTAssertTrue(record.contains("| LIVE_VIEW | true | false | false |"))
        XCTAssertTrue(record.contains(
            "Diagnostic SHA-256: `9c1fac7afb55865781eb29f8c08bb562766749749b8cb031e5a7f53dacbefc6b`"
        ))
        XCTAssertFalse(record.contains(privateSerial))
        XCTAssertFalse(record.contains(privatePassword))
        XCTAssertFalse(record.contains("192.168.1.2"))
        XCTAssertFalse(record.contains("C:/Us" + "ers"))
        XCTAssertFalse(record.localizedCaseInsensitiveContains("baseUrl"))
    }

    func testOfflinePreviewCannotCreatePhysicalConfirmation() {
        let suite = "OpenEOSControlTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let state = CameraAppState(defaults: defaults)
        state.openOfflinePreview()

        state.setOperatorConfirmation(.stillCapture, confirmed: true)

        XCTAssertTrue(state.operatorConfirmedFeatures.isEmpty)
        XCTAssertEqual(state.physicalValidation.sessionStatus, .offlinePreview)
    }

    func testOfflinePreviewCannotExecuteCameraSleep() async {
        let suite = "OpenEOSControlTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let state = CameraAppState(defaults: defaults)
        state.openOfflinePreview()

        XCTAssertTrue(state.supports(.cameraSleep))
        await state.sleepCamera()

        XCTAssertTrue(state.connected)
        XCTAssertTrue(state.isPreview)
        XCTAssertFalse(state.isBusy(.power))
    }

    func testOfflinePreviewCannotExecuteSensorCleaning() async {
        let suite = "OpenEOSControlTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let state = CameraAppState(defaults: defaults)
        state.openOfflinePreview()

        XCTAssertTrue(state.supports(.sensorCleaning))
        await state.cleanSensor(autoPowerOff: true)

        XCTAssertTrue(state.connected)
        XCTAssertTrue(state.isPreview)
        XCTAssertFalse(state.isBusy(.maintenance))
    }

    func testOfflinePreviewShowsButCannotCreateCaptureDirectory() async {
        let suite = "OpenEOSControlTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let state = CameraAppState(defaults: defaults)
        state.openOfflinePreview()

        XCTAssertTrue(state.supports(.directoryControl))
        XCTAssertEqual(state.snapshot?.capabilities.setting("directoryselection")?.value, "100EOSXX")
        await state.createDirectory(name: "ABCDE")

        XCTAssertNil(state.lastCreatedDirectoryName)
        XCTAssertFalse(state.isBusy(.directory))
    }

    func testOfflineMediaDownloadCompletesAndClearsActiveTransferState() async throws {
        let suite = "OpenEOSControlTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let state = CameraAppState(defaults: defaults)
        state.openOfflinePreview()
        let item = try XCTUnwrap(state.mediaItems.first)

        state.startMediaDownload(item)
        for _ in 0..<20 where state.isBusy(.media) {
            await Task.yield()
        }

        XCTAssertEqual(state.downloadedFileName, item.name)
        XCTAssertNil(state.activeMediaDownloadID)
        XCTAssertNil(state.mediaDownloadProgress)
        XCTAssertFalse(state.isBusy(.media))
        XCTAssertNil(state.lastError)
    }

    func testOfflinePreviewRejectsLiveViewMagnificationInVideoMode() async {
        let suite = "OpenEOSControlTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let state = CameraAppState(defaults: defaults)
        state.openOfflinePreview()
        await state.setCaptureMode(.video)

        await state.setLiveViewMagnification(.x10)

        XCTAssertNotEqual(state.liveViewMagnification, .x10)
        XCTAssertNil(state.lastError)
    }

    func testOfflinePreviewLiveViewMagnificationUpdatesLocally() async {
        let suite = "OpenEOSControlTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let state = CameraAppState(defaults: defaults)
        state.openOfflinePreview()

        await state.setLiveViewMagnification(.x10)

        XCTAssertEqual(state.liveViewMagnification, .x10)
        XCTAssertNil(state.lastError)
    }

    func testOfflinePreviewCaptureModeUsesAdvertisedMovieModeControl() async {
        let suite = "OpenEOSControlTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let state = CameraAppState(defaults: defaults)
        state.openOfflinePreview()

        await state.setCaptureMode(.video)
        XCTAssertEqual(state.captureMode, .video)
        XCTAssertEqual(state.capabilities?.setting("moviemode")?.value, "on")
        XCTAssertEqual(state.capabilities?.setting("shootingmode")?.value, "Manual")

        await state.setCaptureMode(.photo)
        XCTAssertEqual(state.captureMode, .photo)
        XCTAssertEqual(state.capabilities?.setting("moviemode")?.value, "off")
    }

    func testOfflinePreviewUpdatesSoundRecordingLevelWithoutCameraHardware() async {
        let suite = "OpenEOSControlTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let state = CameraAppState(defaults: defaults)
        state.openOfflinePreview()

        await state.setCaptureMode(.video)
        await state.setSetting(key: "soundrecordinglevel", value: "48")

        XCTAssertEqual(state.capabilities?.setting("soundrecordinglevel")?.value, "48")
        XCTAssertNil(state.lastError)
    }

    func testOfflinePreviewUpdatesDeviceFunctionSettingsWithoutCameraHardware() async {
        let suite = "OpenEOSControlTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let state = CameraAppState(defaults: defaults)
        state.openOfflinePreview()

        await state.setSetting(key: "beep", value: "disabletouch")
        await state.setSetting(key: "displayoff", value: "120")

        XCTAssertEqual(state.capabilities?.setting("beep")?.value, "disabletouch")
        XCTAssertEqual(state.capabilities?.setting("displayoff")?.value, "120")
        XCTAssertNil(state.lastError)
    }

    func testOfflinePreviewUpdatesFocusBracketingWithoutCameraHardware() async {
        let suite = "OpenEOSControlTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let state = CameraAppState(defaults: defaults)
        state.openOfflinePreview()

        await state.setSetting(key: "focusbracketing", value: "enable")
        await state.setSetting(key: "focusbracketingnumberofshots", value: "250")
        await state.setSetting(key: "focusbracketingfocusincrement", value: "7")
        await state.setSetting(key: "focusbracketingexposuresmoothing", value: "enable")

        XCTAssertEqual(state.capabilities?.setting("focusbracketing")?.value, "enable")
        XCTAssertEqual(state.capabilities?.setting("focusbracketingnumberofshots")?.value, "250")
        XCTAssertEqual(state.capabilities?.setting("focusbracketingfocusincrement")?.value, "7")
        XCTAssertEqual(state.capabilities?.setting("focusbracketingexposuresmoothing")?.value, "enable")
        XCTAssertNil(state.lastError)
    }

    func testOfflinePreviewClickWhiteBalanceUpdatesTheVisibleValue() async {
        let suite = "OpenEOSControlTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let state = CameraAppState(defaults: defaults)
        state.openOfflinePreview()
        state.liveViewTapAction = .whiteBalance

        await state.clickWhiteBalance(x: 0.4, y: 0.6)

        XCTAssertEqual(state.snapshot?.status.exposure.whiteBalance, "click")
        XCTAssertEqual(state.focusMarker, FocusMarker(x: 0.4, y: 0.6, accepted: true))
        XCTAssertNil(state.lastError)
    }

    func testOfflinePreviewBulbModeStartsAndStopsFromTheCaptureState() async {
        let suite = "OpenEOSControlTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let state = CameraAppState(defaults: defaults)
        state.openOfflinePreview()

        await state.setSetting(key: "shootingmode", value: "Bulb")
        XCTAssertTrue(state.bulbMode)

        await state.toggleBulbExposure()
        XCTAssertTrue(state.bulbExposureActive)
        XCTAssertNotNil(state.bulbStartedAt)

        await state.toggleBulbExposure()
        XCTAssertFalse(state.bulbExposureActive)
        XCTAssertNil(state.bulbStartedAt)
        XCTAssertNil(state.lastError)
    }

    func testOfflinePreviewDeletionRemovesOnlyConfirmedItem() async {
        let suite = "OpenEOSControlTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let state = CameraAppState(defaults: defaults)
        state.openOfflinePreview()
        let item = state.mediaItems[1]

        await state.deleteMedia(item)

        XCTAssertEqual(state.mediaItems.count, 2)
        XCTAssertFalse(state.mediaItems.contains { $0.id == item.id })
        XCTAssertEqual(state.deletedMediaName, item.name)
        XCTAssertNil(state.lastError)
    }

    func testLanguageSelectionPersistsWithoutChangingPasswordState() {
        let suite = "OpenEOSControlTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let store = AppLanguageStore(defaults: defaults)

        store.select(.traditionalChinese)

        XCTAssertEqual(store.selection, .traditionalChinese)
        XCTAssertEqual(defaults.string(forKey: AppLanguageStore.defaultsKey), AppLanguage.traditionalChinese.rawValue)
        XCTAssertTrue(store.locale.identifier.lowercased().contains("zh"))
    }

    func testBridgePreferencesPersistWithoutPersistingBearerToken() {
        let suite = "OpenEOSControlTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let state = CameraAppState(defaults: defaults)

        state.setConnectionMode(.desktopBridge)
        state.setBridgeURL("http://192.168.1.20:18181")
        state.bridgeToken = "memory-only-secret"

        let restored = CameraAppState(defaults: defaults)
        XCTAssertEqual(restored.connectionMode, .desktopBridge)
        XCTAssertEqual(restored.bridgeURL, "http://192.168.1.20:18181")
        XCTAssertTrue(restored.bridgeToken.isEmpty)
        XCTAssertFalse(restored.canConnect)
    }

    private func physicalCameraInfo(serial: String = "TEST-SERIAL-0001") -> CameraInfo {
        CameraInfo(
            model: "Canon EOS R6 Mark III",
            serial: serial,
            api: "ccapi"
        )
    }

    private func physicalValidationCapabilities() -> CameraCapabilities {
        CameraCapabilities(
            settings: [],
            matrix: CapabilityMatrix(supported: [.stillCapture, .liveView]),
            liveView: LiveViewCapabilities(),
            profile: CameraProfile.from(modelName: "Canon EOS R6 Mark III"),
            evidence: CameraCapabilityEvidence(
                source: "GET /ccapi",
                observedFeatures: [.stillCapture, .usbDiagnostics]
            )
        )
    }

    func testMovieQualityTokenUsesReadableCameraStyleSummary() {
        XCTAssertEqual(
            movieQualityDisplayValue("3840x2160_5994_ipb_standard"),
            "3840x2160 / 59.94p / IPB"
        )
        XCTAssertEqual(
            movieQualityDisplayValue("fhd_2997_ipb_light_crop", lightLabel: "Lite", cropLabel: "Cropped"),
            "FHD / 29.97p / IPB / Lite / Cropped"
        )
        XCTAssertEqual(
            movieQualityDisplayValue("4096x2160_12000_alli_standard"),
            "4096x2160 / 120.00p / ALL-I"
        )
        XCTAssertEqual(
            movieQualityDisplayValue("4096x2160_5994_longgop_standard_fine"),
            "4096x2160 / 59.94p / Long GOP / Fine"
        )
        XCTAssertEqual(movieFormatDisplayValue("xfhevcs-ycc422-10bit"), "XF-HEVC S / 4:2:2 / 10-bit")
        XCTAssertEqual(movieFormatDisplayValue("xfavcs-ycc420-8bit"), "XF-AVC S / 4:2:0 / 8-bit")
        XCTAssertEqual(movieFormatDisplayValue("raw"), "RAW")
        XCTAssertEqual(movieFormatDisplayValue("mp4"), "MP4")
        XCTAssertNil(movieQualityDisplayValue("4K Fine 59.94p"))
    }

}

private actor MediaPlaybackRequestRecorder {
    struct Request: Equatable {
        let offset: Int64
        let length: Int64?
    }

    private var requests: [Request] = []

    func record(offset: Int64, length: Int64?) {
        requests.append(Request(offset: offset, length: length))
    }

    func values() -> [Request] {
        requests
    }
}

private final class RTPAudioStatusRecorder: @unchecked Sendable {
    private let lock = NSLock()
    private var statuses: [IOSCcapiRTPAudioStatus] = []

    var last: IOSCcapiRTPAudioStatus? {
        lock.lock()
        defer { lock.unlock() }
        return statuses.last
    }

    func record(_ status: IOSCcapiRTPAudioStatus) {
        lock.lock()
        statuses.append(status)
        lock.unlock()
    }
}
