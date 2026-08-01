import AVFoundation
import CoreGraphics
import Foundation
import OpenEOSCore
import XCTest

@testable import OpenEOSControl

@MainActor
final class CameraAppTests: XCTestCase {
    func testRollingFrameRateUsesRecentWindow() {
        var tracker = LiveViewRateTracker(window: 1)

        XCTAssertEqual(tracker.record(0), 0)
        XCTAssertEqual(tracker.record(0.1), 10, accuracy: 0.001)
        XCTAssertEqual(tracker.record(0.2), 10, accuracy: 0.001)
        XCTAssertEqual(tracker.record(1.2), 1, accuracy: 0.001)
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
            CameraSetting(key: "drivemode", label: "Drive", value: "single", values: ["single", "continuous"]),
            CameraSetting(key: "moviequality", label: "Movie", value: "4K", values: ["4K", "FHD"]),
            CameraSetting(key: "meteringmode", label: "Metering", value: "eval", values: ["eval", "spot"]),
            CameraSetting(key: "alomode", label: "ALO", value: "x3", values: ["x3"]),
            CameraSetting(
                key: "capturetarget",
                label: "Capture target",
                value: "Internal RAM",
                values: ["Internal RAM", "Memory card"]
            ),
            CameraSetting(key: "capturestorage", label: "Recording card", value: "CFe", values: ["CFe", "SD"]),
        ]

        XCTAssertEqual(
            advancedSettingsForMode(settings, mode: .photo).map(\.key),
            ["drivemode", "meteringmode", "capturetarget", "capturestorage"]
        )
        XCTAssertEqual(advancedSettingsForMode(settings, mode: .video).map(\.key), ["moviequality", "meteringmode"])
    }

    func testR6AdvancedSettingLocalizationKeepsProtocolValuesSeparate() {
        let expectedLabels = [
            "whitebalanceadjusta": "setting_white_balance_shift_a",
            "whitebalanceadjustb": "setting_white_balance_shift_b",
            "wbshift.ba": "setting_white_balance_shift_ba",
            "wbshift.mg": "setting_white_balance_shift_mg",
            "aspectratio": "setting_aspect_ratio",
            "zoomspeed": "setting_power_zoom_speed",
            "autopoweroff": "setting_auto_power_off",
            "alomode": "setting_auto_lighting_optimizer",
            "capturetarget": "setting_capture_target",
            "capturestorage": "setting_capture_storage",
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
            settingValueLocalizationKey(key: "stillimagequalitycf", value: "cRAW + Large Fine JPEG"),
            "camera_value_craw_large_fine_jpeg"
        )
        XCTAssertEqual(
            settingValueLocalizationKey(key: "stillimagequality.jpeg", value: "large_fine"),
            "camera_value_large_fine"
        )
        XCTAssertEqual(settingValueLocalizationKey(key: "continuousaf", value: "Off"), "camera_value_off")
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
        XCTAssertFalse(snapshot.capabilities.matrix.supports(.focusDrive))
        XCTAssertEqual(snapshot.capabilities.liveView.maximumFPS, 30)
        XCTAssertEqual(
            snapshot.capabilities.settings.first(where: { $0.key == "capturestorage" })?.values,
            ["CFe", "SD"]
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

    func testOfflinePreviewLiveViewMagnificationUpdatesLocally() async {
        let suite = "OpenEOSControlTests-\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suite)!
        defer { defaults.removePersistentDomain(forName: suite) }
        let state = CameraAppState(defaults: defaults)
        state.openOfflinePreview()

        await state.setLiveViewMagnification(.x5)

        XCTAssertEqual(state.liveViewMagnification, .x5)
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
