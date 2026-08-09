import Foundation
import OpenEOSCore

@MainActor
final class CameraAppState: ObservableObject {
    static var defaultCameraURL: String {
        #if DEBUG
        if let override = ProcessInfo.processInfo.environment["OEC_HTTP_PRESET_URL"]?
            .trimmingCharacters(in: .whitespacesAndNewlines),
            !override.isEmpty
        {
            return override
        }
        #endif
        return "http://192.168.1.2:8080"
    }
    static let defaultSecureCameraURL = "https://192.168.1.2:443"
    static let simulatorURL = "http://127.0.0.1:18080"
    static let defaultBridgeURL = "http://192.168.1.100:18181"

    private enum DefaultsKey {
        static let baseURL = "camera-base-url"
        static let username = "camera-username"
        static let connectionMode = "camera-connection-mode"
        static let bridgeURL = "desktop-bridge-url"
        static let requestedFPS = "live-view-requested-fps"
        static let liveViewSize = "live-view-size"
        static let liveViewSource = "live-view-source"
    }

    @Published var connectionMode: AppConnectionMode
    @Published var baseURL: String
    @Published private(set) var ccapiConnectionMode = CCAPIConnectionMode.automatic
    @Published var username: String
    @Published var password = ""
    @Published var bridgeURL: String
    @Published var bridgeToken = ""
    @Published private(set) var bridgeCameras: [DesktopBridgeCamera] = []
    @Published var selectedBridgeCameraID: String?
    @Published private(set) var snapshot: CameraSnapshot? {
        didSet {
            if let setting = snapshot.flatMap({ captureModeSetting($0.capabilities.settings) }),
               let mode = appCaptureMode(for: setting) {
                captureMode = mode
            } else if snapshot?.status.recording == true {
                captureMode = .video
            }
        }
    }
    @Published private(set) var isPreview = false
    @Published var screen = AppScreen.control
    @Published var captureMode = AppCaptureMode.photo
    @Published var activeSheet: CameraSheet?
    @Published var hudVisible = true
    @Published var showGrid = false
    @Published var monitorSettings = LiveViewMonitorSettings()
    @Published var liveViewTapAction = LiveViewTapAction.focus
    private var lastPhotoShootingMode: String?
    @Published var autoRefresh = true
    @Published private(set) var requestedFPS: Int
    @Published private(set) var liveViewSize: LiveViewSize
    @Published private(set) var selectedLiveViewSource: LiveViewSource
    @Published private(set) var activeLiveViewSource: LiveViewSource?
    @Published private(set) var nativeLiveViewSize: CGSize?
    @Published private(set) var rtpAudioRequested = false
    @Published private(set) var rtpAudioStatus = IOSCcapiRTPAudioStatus.inactive
    @Published private(set) var liveViewData: Data?
    @Published private(set) var liveViewMagnification: LiveViewMagnification?
    @Published private(set) var observedFPS = 0.0
    @Published private(set) var frameBytes = 0
    @Published private(set) var frameContentType: String?
    @Published private(set) var frameSourceURL: URL?
    @Published private(set) var lastFrameAt: Date?
    @Published private(set) var shutterFlash = false
    @Published private(set) var bulbStartedAt: Date?
    @Published private(set) var focusMarker: FocusMarker?
    @Published private(set) var mediaItems: [CameraMediaItem] = []
    @Published private(set) var mediaThumbnails: [String: Data] = [:]
    @Published private(set) var loadingMediaThumbnailIDs = Set<String>()
    @Published private(set) var mediaPreviewItem: CameraMediaItem?
    @Published private(set) var mediaPreviewData: Data?
    @Published private(set) var mediaPreviewLoading = false
    @Published private(set) var downloadedFileURL: URL?
    @Published private(set) var downloadedFileName: String?
    @Published private(set) var activeMediaDownloadID: String?
    @Published private(set) var mediaDownloadProgress: CameraMediaTransferProgress?
    @Published private(set) var deletedMediaName: String?
    @Published private(set) var lastClockSyncAt: Date?
    @Published private(set) var lastCreatedDirectoryName: String?
    @Published private(set) var operatorConfirmedFeatures = Set<CameraFeature>()
    @Published private(set) var lastError: String?
    @Published private(set) var busyOperations = Set<CameraOperation>()

    private let defaults: UserDefaults
    private var session: CameraSession?
    private var liveViewTask: Task<Void, Never>?
    private var eventTask: Task<Void, Never>?
    private var eventGeneration = UUID()
    private var operationRevision: UInt64 = 0
    private var mediaDownloadTask: Task<Void, Never>?
    private var mediaDownloadToken: UUID?
    private var rateTracker = LiveViewRateTracker()
    private var downloadedMediaID: String?
    private var unavailableMediaThumbnailIDs = Set<String>()
    private var mediaThumbnailGeneration = 0
    let rtpController: IOSCcapiRTPController

    var connected: Bool { snapshot?.status.connected == true }
    var recording: Bool { snapshot?.status.recording == true }
    var bulbExposureActive: Bool { snapshot?.status.bulbExposureActive == true }
    var bulbMode: Bool {
        guard captureMode == .photo else { return false }
        let setting = capabilities?.settings.first { ["shootingmode", "autoexposuremode", "ae"].contains($0.key.lowercased()) }
        return (setting?.value ?? status?.mode ?? "")
            .lowercased()
            .filter { $0.isLetter }
            == "bulb"
    }
    var capabilities: CameraCapabilities? { snapshot?.capabilities }
    var status: CameraStatus? { snapshot?.status }
    var info: CameraInfo? { snapshot?.info }
    var liveViewTemperatureAllowed: Bool { status?.temperature?.liveViewAllowed != false }
    var stillCaptureTemperatureAllowed: Bool { status?.temperature?.stillCaptureAllowed != false }
    var movieRecordingTemperatureAllowed: Bool { status?.temperature?.movieRecordingAllowed != false }
    var effectiveLiveViewTapAction: LiveViewTapAction? {
        if liveViewTapAction == .whiteBalance, supports(.clickWhiteBalance) { return .whiteBalance }
        if supports(.tapFocus) { return .focus }
        if supports(.clickWhiteBalance) { return .whiteBalance }
        return nil
    }
    var connectionEndpoint: String { connectionMode == .ccapi ? baseURL : bridgeURL }
    var usesRTPLiveView: Bool {
        guard capabilities?.liveView.sources.contains(.ccapiRTP) == true else { return false }
        if activeLiveViewSource == .ccapiRTP || selectedLiveViewSource == .ccapiRTP { return true }
        return selectedLiveViewSource == .auto && capabilities?.liveView.defaultSource == .ccapiRTP
    }
    var transportIdentifier: String {
        if isPreview { return "OFFLINE_PREVIEW" }
        return connectionMode == .ccapi ? "CCAPI_NETWORK" : "DESKTOP_BRIDGE"
    }
    var physicalValidation: PhysicalValidationSummary {
        PhysicalValidationSummary(
            connected: connected,
            isPreview: isPreview,
            info: info,
            capabilities: capabilities,
            operatorConfirmedFeatures: operatorConfirmedFeatures
        )
    }
    var canConnect: Bool {
        switch connectionMode {
        case .ccapi:
            !baseURL.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        case .desktopBridge:
            !bridgeURL.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                && selectedBridgeCameraID != nil
        }
    }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        if CommandLine.arguments.contains("-resetState") {
            [
                DefaultsKey.baseURL,
                DefaultsKey.username,
                DefaultsKey.connectionMode,
                DefaultsKey.bridgeURL,
                DefaultsKey.requestedFPS,
                DefaultsKey.liveViewSize,
                DefaultsKey.liveViewSource,
            ]
                .forEach { defaults.removeObject(forKey: $0) }
        }
        connectionMode = defaults.string(forKey: DefaultsKey.connectionMode)
            .flatMap(AppConnectionMode.init(rawValue:)) ?? .ccapi
        baseURL = defaults.string(forKey: DefaultsKey.baseURL) ?? Self.defaultCameraURL
        username = defaults.string(forKey: DefaultsKey.username) ?? ""
        bridgeURL = defaults.string(forKey: DefaultsKey.bridgeURL) ?? Self.defaultBridgeURL
        let storedFPS = defaults.integer(forKey: DefaultsKey.requestedFPS)
        requestedFPS = storedFPS == 0 ? 6 : min(max(storedFPS, 1), 30)
        liveViewSize = defaults.string(forKey: DefaultsKey.liveViewSize).flatMap(LiveViewSize.init(rawValue:)) ?? .medium
        selectedLiveViewSource = defaults.string(forKey: DefaultsKey.liveViewSource)
            .flatMap(LiveViewSource.init(rawValue:)) ?? .auto
        activeLiveViewSource = nil
        nativeLiveViewSize = nil
        rtpController = IOSCcapiRTPController()
        rtpController.setEventHandler { [weak self] event in
            Task { @MainActor [weak self] in self?.handleRTPEvent(event) }
        }
    }

    func supports(_ feature: CameraFeature) -> Bool {
        capabilities?.matrix.supports(feature) == true
    }

    func isBusy(_ operation: CameraOperation) -> Bool {
        busyOperations.contains(operation) || (bulbExposureActive && operation != .capture)
    }

    func setBaseURL(_ value: String) {
        baseURL = value
        defaults.set(value, forKey: DefaultsKey.baseURL)
    }

    func setConnectionMode(_ value: AppConnectionMode) {
        connectionMode = value
        defaults.set(value.rawValue, forKey: DefaultsKey.connectionMode)
        lastError = nil
    }

    func setBridgeURL(_ value: String) {
        if bridgeURL != value {
            bridgeCameras = []
            selectedBridgeCameraID = nil
        }
        bridgeURL = value
        defaults.set(value, forKey: DefaultsKey.bridgeURL)
    }

    func setUsername(_ value: String) {
        username = value
        defaults.set(value, forKey: DefaultsKey.username)
    }

    func useHTTPPreset() {
        ccapiConnectionMode = .camera
        setBaseURL(Self.defaultCameraURL)
    }

    func useHTTPSPreset() {
        ccapiConnectionMode = .camera
        setBaseURL(Self.defaultSecureCameraURL)
    }

    func useSimulatorPreset() {
        ccapiConnectionMode = .simulator
        setBaseURL(Self.simulatorURL)
    }

    func scanBridgeCameras() async {
        guard begin(.scan) else { return }
        defer { end(.scan) }
        do {
            let probe = try DesktopBridgeClient(baseURL: bridgeURL, token: bridgeToken)
            let cameras = try await probe.discoverCameras()
            bridgeCameras = cameras
            if let selectedBridgeCameraID, cameras.contains(where: { $0.id == selectedBridgeCameraID }) {
                self.selectedBridgeCameraID = selectedBridgeCameraID
            } else {
                selectedBridgeCameraID = cameras.first?.id
            }
            lastError = nil
        } catch {
            bridgeCameras = []
            selectedBridgeCameraID = nil
            record(error)
        }
    }

    func connect() async {
        guard begin(.connect) else { return }
        defer { end(.connect) }
        operatorConfirmedFeatures.removeAll()
        do {
            let newSession: CameraSession
            switch connectionMode {
            case .ccapi:
                let rtpAddress = CameraRTPNetworkAddress.destinationAddress(cameraURL: baseURL)
                newSession = .ccapi(
                    try CCAPIClient(
                        baseURL: baseURL,
                        mode: ccapiConnectionMode,
                        username: username,
                        password: password,
                        rtpDestinationAddress: rtpAddress,
                        rtpSessionFactory: rtpAddress == nil ? nil : rtpController
                    )
                )
            case .desktopBridge:
                guard
                    let selectedBridgeCameraID,
                    let camera = bridgeCameras.first(where: { $0.id == selectedBridgeCameraID })
                else {
                    throw DesktopBridgeError.invalidResponse("Select a scanned Desktop Bridge camera before connecting.")
                }
                newSession = .desktopBridge(
                    try DesktopBridgeClient(
                        baseURL: bridgeURL,
                        token: bridgeToken,
                        cameraID: selectedBridgeCameraID,
                        cameraEngine: camera.engine,
                        profileHint: camera.model
                    )
                )
            }
            let newSnapshot: CameraSnapshot
            do {
                newSnapshot = try await newSession.connectSnapshot()
            } catch {
                await newSession.close()
                throw error
            }
            session = newSession
            snapshot = newSnapshot
            liveViewMagnification = nil
            isPreview = false
            screen = .control
            mediaItems = []
            resetMediaThumbnails()
            resetMediaPreview()
            resetMediaDownloadState()
            removeDownloadedFile()
            deletedMediaName = nil
            lastClockSyncAt = nil
            lastCreatedDirectoryName = nil
            lastError = nil
            clampLiveViewRequest()
            beginEventLoop(session: newSession)
            if newSnapshot.capabilities.matrix.supports(.liveView), autoRefresh {
                await startLiveView()
            }
        } catch {
            record(error)
        }
    }

    func openOfflinePreview() {
        stopLiveViewLoop()
        stopEventLoop()
        resetMediaDownloadState()
        session = nil
        snapshot = Self.makeOfflinePreviewSnapshot()
        isPreview = true
        screen = .control
        mediaItems = Self.previewMedia
        resetMediaThumbnails()
        resetMediaPreview()
        removeDownloadedFile()
        deletedMediaName = nil
        lastClockSyncAt = nil
        lastCreatedDirectoryName = nil
        operatorConfirmedFeatures.removeAll()
        lastError = nil
        liveViewData = nil
        liveViewMagnification = nil
        bulbStartedAt = nil
        activeLiveViewSource = nil
        nativeLiveViewSize = nil
        rtpAudioRequested = false
        rtpAudioStatus = .inactive
        rtpController.setAudioEnabled(false)
        resetLiveViewMetrics()
    }

    func disconnect() async {
        stopLiveViewLoop()
        stopEventLoop()
        resetMediaDownloadState()
        if let session { await session.close() }
        session = nil
        snapshot = nil
        isPreview = false
        screen = .control
        activeSheet = nil
        password = ""
        bridgeToken = ""
        mediaItems = []
        resetMediaThumbnails()
        resetMediaPreview()
        removeDownloadedFile()
        deletedMediaName = nil
        liveViewData = nil
        liveViewMagnification = nil
        activeLiveViewSource = nil
        nativeLiveViewSize = nil
        rtpAudioRequested = false
        rtpAudioStatus = .inactive
        rtpController.setAudioEnabled(false)
        focusMarker = nil
        bulbStartedAt = nil
        lastClockSyncAt = nil
        lastCreatedDirectoryName = nil
        operatorConfirmedFeatures.removeAll()
        lastError = nil
        busyOperations.removeAll()
        resetLiveViewMetrics()
    }

    func refresh() async {
        guard let session, begin(.refresh) else { return }
        defer { end(.refresh) }
        do {
            snapshot = try await session.connectSnapshot()
            lastError = nil
            clampLiveViewRequest()
        } catch {
            record(error)
        }
    }

    func setRequestedFPS(_ value: Int) {
        let limits = capabilities?.liveView
        let minimum = limits?.minimumFPS ?? 1
        let maximum = limits?.maximumFPS ?? 30
        requestedFPS = min(max(value, minimum), maximum)
        defaults.set(requestedFPS, forKey: DefaultsKey.requestedFPS)
        if let session {
            let fps = requestedFPS
            Task { await session.setLiveViewTargetFPS(fps) }
        }
    }

    func setLiveViewSize(_ value: LiveViewSize) async {
        guard !usesRTPLiveView, capabilities?.liveView.sizes.contains(value) == true else { return }
        liveViewSize = value
        defaults.set(value.rawValue, forKey: DefaultsKey.liveViewSize)
        if session != nil, autoRefresh { await restartLiveView() }
    }

    func setLiveViewSource(_ value: LiveViewSource) async {
        let available = capabilities?.liveView.sources ?? []
        guard value == .auto || available.contains(value) else { return }
        selectedLiveViewSource = value
        defaults.set(value.rawValue, forKey: DefaultsKey.liveViewSource)
        if session != nil { await restartLiveView() }
    }

    func setAutoRefresh(_ enabled: Bool) async {
        autoRefresh = enabled
        rtpController.setRenderingEnabled(enabled)
        if enabled {
            if activeLiveViewSource == .ccapiRTP {
                return
            }
            if let session, activeLiveViewSource != nil {
                beginLiveViewLoop(session: session)
            } else {
                await startLiveView()
            }
        } else {
            stopLiveViewLoop()
        }
    }

    func setRTPAudioEnabled(_ enabled: Bool) {
        guard activeLiveViewSource == .ccapiRTP,
              !enabled || rtpAudioStatus.available else { return }
        rtpAudioRequested = enabled
        rtpController.setAudioEnabled(enabled)
    }

    func setApplicationActive(_ active: Bool) {
        rtpController.setApplicationActive(active)
    }

    func startLiveView() async {
        guard let session, supports(.liveView), liveViewTemperatureAllowed, begin(.liveView) else { return }
        defer { end(.liveView) }
        do {
            try await session.startLiveView(
                LiveViewRequest(fps: requestedFPS, size: liveViewSize, source: effectiveRequestedLiveViewSource())
            )
            activeLiveViewSource = await session.currentLiveViewSource()
            lastError = nil
            if activeLiveViewSource == .ccapiRTP {
                resetLiveViewMetrics()
                nativeLiveViewSize = nil
                liveViewData = nil
                frameContentType = "video/H264"
                frameSourceURL = await session.currentNativeLiveViewSourceURL()
                rtpController.setRenderingEnabled(autoRefresh)
            } else {
                rtpAudioRequested = false
                rtpAudioStatus = .inactive
                rtpController.setAudioEnabled(false)
                if autoRefresh { beginLiveViewLoop(session: session) }
            }
        } catch {
            record(error)
        }
    }

    func restartLiveView() async {
        stopLiveViewLoop()
        if let session { await session.stopLiveView() }
        activeLiveViewSource = nil
        nativeLiveViewSize = nil
        liveViewMagnification = nil
        await startLiveView()
    }

    func captureStill() async {
        guard supports(.stillCapture), stillCaptureTemperatureAllowed, begin(.capture) else { return }
        defer { end(.capture) }
        if isPreview {
            showShutterFlash()
            return
        }
        guard let session else { return }
        do {
            updateStatus(try await session.captureStill())
            showShutterFlash()
            lastError = nil
        } catch {
            record(error)
        }
    }

    func syncCameraClock() async {
        guard !isPreview, supports(.cameraClockSync), begin(.clock) else { return }
        defer { end(.clock) }
        guard let session else { return }
        do {
            updateStatus(try await session.syncCameraClock())
            lastClockSyncAt = Date()
            lastError = nil
        } catch {
            record(error)
        }
    }

    func createDirectory(name: String) async {
        guard !isPreview, supports(.directoryControl), begin(.directory) else { return }
        defer { end(.directory) }
        guard let session else { return }
        do {
            lastCreatedDirectoryName = try await session.createDirectory(name: name)
            let capabilities = try await session.capabilities()
            if let snapshot {
                self.snapshot = CameraSnapshot(info: snapshot.info, status: snapshot.status, capabilities: capabilities)
            }
            lastError = nil
        } catch {
            record(error)
        }
    }

    func setFileNaming(field: CameraFileNamingField, value: String) async {
        guard
            !isPreview,
            supports(.fileNamingControl),
            snapshot?.capabilities.fileNaming?.accepts(field, value: value) == true,
            begin(.setting)
        else { return }
        defer { end(.setting) }
        guard let session else { return }
        do {
            _ = try await session.setFileNaming(field: field, value: value)
            let capabilities = try await session.capabilities()
            if let snapshot {
                self.snapshot = CameraSnapshot(
                    info: snapshot.info,
                    status: snapshot.status,
                    capabilities: capabilities
                )
            }
            lastError = nil
        } catch {
            record(error)
        }
    }

    func sleepCamera() async {
        guard
            !isPreview,
            supports(.cameraSleep),
            !recording,
            !bulbExposureActive,
            busyOperations.isEmpty,
            begin(.power)
        else { return }
        defer { end(.power) }
        guard let session else { return }

        let wasLiveViewActive = activeLiveViewSource != nil
        stopLiveViewLoop()
        stopEventLoop()
        resetMediaDownloadState()
        do {
            try await session.sleepCamera()
            await disconnect()
        } catch {
            record(error)
            beginEventLoop(session: session)
            if wasLiveViewActive { await startLiveView() }
        }
    }

    func cleanSensor(autoPowerOff: Bool) async {
        guard
            !isPreview,
            supports(.sensorCleaning),
            !recording,
            !bulbExposureActive,
            busyOperations.isEmpty,
            begin(.maintenance)
        else { return }
        defer { end(.maintenance) }
        guard let session else { return }

        let wasLiveViewActive = activeLiveViewSource != nil
        stopLiveViewLoop()
        stopEventLoop()
        do {
            try await session.cleanSensor(autoPowerOff: autoPowerOff)
            if autoPowerOff {
                await disconnect()
            } else {
                snapshot = try await session.connectSnapshot()
                clampLiveViewRequest()
                beginEventLoop(session: session)
                activeLiveViewSource = nil
                if wasLiveViewActive { await startLiveView() }
                lastError = nil
            }
        } catch {
            record(error)
            beginEventLoop(session: session)
            activeLiveViewSource = nil
            if wasLiveViewActive { await startLiveView() }
        }
    }

    func toggleBulbExposure() async {
        let wasActive = bulbExposureActive
        guard bulbMode, (wasActive || supports(.bulbExposure)), begin(.capture) else { return }
        defer { end(.capture) }
        guard wasActive || stillCaptureTemperatureAllowed else { return }
        if isPreview {
            guard let snapshot else { return }
            updateStatus(snapshot.status.withBulbExposureActive(!wasActive))
            if wasActive { showShutterFlash() }
            return
        }
        guard let session else { return }
        pauseLiveViewForBulb()
        do {
            let newStatus = wasActive
                ? try await session.stopBulbExposure()
                : try await session.startBulbExposure()
            updateStatus(newStatus)
            if wasActive && newStatus.bulbExposureActive != true {
                showShutterFlash()
                resumeLiveViewAfterBulb(session: session)
            }
            lastError = nil
        } catch {
            if !wasActive { resumeLiveViewAfterBulb(session: session) }
            record(error)
        }
    }

    func autofocus() async {
        guard supports(.autofocus), begin(.focus) else { return }
        defer { end(.focus) }
        if isPreview {
            showFocusMarker(x: 0.5, y: 0.5, accepted: true)
            return
        }
        guard let session else { return }
        do {
            updateStatus(try await session.autofocus())
            showFocusMarker(x: 0.5, y: 0.5, accepted: true)
            lastError = nil
        } catch {
            record(error)
            showFocusMarker(x: 0.5, y: 0.5, accepted: false)
        }
    }

    func halfPressShutter() async {
        guard supports(.shutterHalfPress), begin(.focus) else { return }
        defer { end(.focus) }
        if isPreview {
            showFocusMarker(x: 0.5, y: 0.5, accepted: true)
            return
        }
        guard let session else { return }
        do {
            updateStatus(try await session.halfPressShutter())
            showFocusMarker(x: 0.5, y: 0.5, accepted: true)
            lastError = nil
        } catch {
            record(error)
            showFocusMarker(x: 0.5, y: 0.5, accepted: false)
        }
    }

    func toggleRecording() async {
        let wasRecording = recording
        guard (wasRecording || supports(.videoRecording)), begin(.recording) else { return }
        defer { end(.recording) }
        guard wasRecording || movieRecordingTemperatureAllowed else { return }
        if isPreview {
            guard let snapshot else { return }
            self.snapshot = snapshot.replacing(status: snapshot.status.replacing(recording: !recording))
            return
        }
        guard let session else { return }
        do {
            let newStatus = wasRecording ? try await session.stopRecording() : try await session.startRecording()
            updateStatus(newStatus)
            lastError = nil
        } catch {
            record(error)
        }
    }

    func tapFocus(x: Double, y: Double) async {
        guard supports(.tapFocus), begin(.focus) else { return }
        defer { end(.focus) }
        let normalizedX = min(max(x, 0), 1)
        let normalizedY = min(max(y, 0), 1)
        if isPreview {
            showFocusMarker(x: normalizedX, y: normalizedY, accepted: true)
            return
        }
        guard let session else { return }
        do {
            let result = try await session.tapFocus(x: normalizedX, y: normalizedY)
            showFocusMarker(x: result.x, y: result.y, accepted: result.accepted)
            lastError = nil
        } catch {
            record(error)
            showFocusMarker(x: normalizedX, y: normalizedY, accepted: false)
        }
    }

    func clickWhiteBalance(x: Double, y: Double) async {
        guard supports(.clickWhiteBalance), begin(.setting) else { return }
        defer { end(.setting) }
        let normalizedX = min(max(x, 0), 1)
        let normalizedY = min(max(y, 0), 1)
        if isPreview {
            if let status {
                updateStatus(status.replacing(exposure: status.exposure.replacing(key: "whitebalance", value: "click")))
            }
            showFocusMarker(x: normalizedX, y: normalizedY, accepted: true)
            return
        }
        guard let session else { return }
        do {
            updateStatus(try await session.clickWhiteBalance(x: normalizedX, y: normalizedY))
            showFocusMarker(x: normalizedX, y: normalizedY, accepted: true)
            lastError = nil
        } catch {
            record(error)
            showFocusMarker(x: normalizedX, y: normalizedY, accepted: false)
        }
    }

    func driveFocus(direction: FocusDriveDirection, step: FocusDriveStep) async {
        guard supports(.focusDrive), begin(.focus) else { return }
        defer { end(.focus) }
        if isPreview {
            showFocusMarker(x: direction == .near ? 0.4 : 0.6, y: 0.5, accepted: true)
            return
        }
        guard let session else { return }
        do {
            let result = try await session.driveFocus(direction: direction, step: step)
            showFocusMarker(x: result.direction == .near ? 0.4 : 0.6, y: 0.5, accepted: result.accepted)
            lastError = nil
        } catch {
            record(error)
        }
    }

    func setLiveViewMagnification(_ magnification: LiveViewMagnification) async {
        guard supports(.liveViewMagnification), begin(.liveView) else { return }
        defer { end(.liveView) }
        if isPreview {
            liveViewMagnification = magnification
            return
        }
        guard let session, activeLiveViewSource != nil else { return }
        do {
            let result = try await session.setLiveViewMagnification(magnification)
            if result.accepted {
                liveViewMagnification = result.magnification
            }
            lastError = nil
        } catch {
            record(error)
        }
    }

    func setCaptureMode(_ mode: AppCaptureMode) async {
        guard captureMode != mode,
              !recording,
              !bulbExposureActive,
              !isBusy(.setting),
              !isBusy(.capture),
              !isBusy(.recording) else { return }
        guard let setting = capabilities.flatMap({ captureModeSetting($0.settings) }) else {
            captureMode = mode
            return
        }
        if setting.key.lowercased() != "moviemode", appCaptureMode(for: setting) == .photo {
            lastPhotoShootingMode = setting.value
        }
        guard let target = captureModeValue(
            for: mode,
            setting: setting,
            preferredPhotoValue: lastPhotoShootingMode
        ) else {
            captureMode = mode
            return
        }
        if target == setting.value {
            captureMode = mode
            return
        }
        await setSetting(key: setting.key, value: target)
    }

    func setSetting(key: String, value: String) async {
        guard let setting = capabilities?.setting(key), setting.values.contains(value), begin(.setting) else { return }
        defer { end(.setting) }
        if isPreview {
            guard let snapshot else { return }
            let exposure = snapshot.status.exposure.replacing(key: key, value: value)
            self.snapshot = CameraSnapshot(
                info: snapshot.info,
                status: snapshot.status.replacing(exposure: exposure),
                capabilities: snapshot.capabilities.replacingSetting(key: key, value: value)
            )
            return
        }
        guard let session else { return }
        do {
            let status = try await session.setSetting(key: key, value: value)
            let capabilities = try await session.capabilities()
            if let snapshot {
                self.snapshot = CameraSnapshot(info: snapshot.info, status: status, capabilities: capabilities)
            }
            lastError = nil
        } catch {
            record(error)
        }
    }

    func loadMedia() async {
        guard supports(.mediaBrowser), begin(.media) else { return }
        defer { end(.media) }
        deletedMediaName = nil
        resetMediaThumbnails()
        resetMediaPreview()
        if isPreview {
            mediaItems = Self.previewMedia
            return
        }
        guard let session else { return }
        do {
            mediaItems = try await session.listMedia()
            lastError = nil
        } catch {
            record(error)
        }
    }

    func loadMediaThumbnail(_ item: CameraMediaItem) async {
        guard
            !isPreview,
            supports(.mediaThumbnail),
            mediaThumbnails[item.id] == nil,
            !loadingMediaThumbnailIDs.contains(item.id),
            !unavailableMediaThumbnailIDs.contains(item.id),
            let session
        else { return }

        let generation = mediaThumbnailGeneration
        loadingMediaThumbnailIDs.insert(item.id)
        defer {
            if generation == mediaThumbnailGeneration {
                loadingMediaThumbnailIDs.remove(item.id)
            }
        }
        do {
            let thumbnail = try await session.mediaThumbnail(item)
            guard
                generation == mediaThumbnailGeneration,
                mediaItems.contains(where: { $0.id == item.id })
            else { return }
            guard !thumbnail.data.isEmpty else {
                unavailableMediaThumbnailIDs.insert(item.id)
                return
            }
            mediaThumbnails[item.id] = thumbnail.data
        } catch is CancellationError {
            return
        } catch {
            if generation == mediaThumbnailGeneration {
                unavailableMediaThumbnailIDs.insert(item.id)
            }
        }
    }

    func openMediaPreview(_ item: CameraMediaItem) async {
        guard
            !isPreview,
            item.previewAvailable,
            supports(.mediaPreview),
            let session,
            begin(.media)
        else { return }

        mediaPreviewItem = item
        mediaPreviewData = nil
        mediaPreviewLoading = true
        defer {
            end(.media)
            if mediaPreviewItem?.id == item.id { mediaPreviewLoading = false }
        }
        do {
            let preview = try await session.mediaPreview(item)
            guard mediaPreviewItem?.id == item.id else { return }
            mediaPreviewData = preview.data
            lastError = nil
        } catch {
            guard mediaPreviewItem?.id == item.id else { return }
            record(error)
        }
    }

    func closeMediaPreview() {
        resetMediaPreview()
    }

    func loadMediaInfo(_ item: CameraMediaItem) async {
        guard !isPreview, supports(.mediaBrowser), begin(.media) else { return }
        defer { end(.media) }
        guard let session else { return }
        do {
            applyUpdatedMedia(try await session.mediaInfo(item))
            lastError = nil
        } catch {
            record(error)
        }
    }

    func setMediaProtection(_ item: CameraMediaItem, enabled: Bool) async {
        await updateMediaMetadata(item, feature: .mediaProtect) { session in
            try await session.setMediaProtection(item, enabled: enabled)
        } preview: {
            CameraMediaItem(
                id: item.id, name: item.name, kind: item.kind, sizeBytes: item.sizeBytes,
                captureTime: item.captureTime, previewAvailable: item.previewAvailable,
                protected: enabled, rating: item.rating, rotationDegrees: item.rotationDegrees
            )
        }
    }

    func setMediaRating(_ item: CameraMediaItem, rating: Int) async {
        guard (0...5).contains(rating) else { return }
        await updateMediaMetadata(item, feature: .mediaRating) { session in
            try await session.setMediaRating(item, rating: rating)
        } preview: {
            CameraMediaItem(
                id: item.id, name: item.name, kind: item.kind, sizeBytes: item.sizeBytes,
                captureTime: item.captureTime, previewAvailable: item.previewAvailable,
                protected: item.protected, rating: rating, rotationDegrees: item.rotationDegrees
            )
        }
    }

    func setMediaRotation(_ item: CameraMediaItem, degrees: Int) async {
        guard [0, 90, 180, 270].contains(degrees) else { return }
        await updateMediaMetadata(item, feature: .mediaRotate) { session in
            try await session.setMediaRotation(item, degrees: degrees)
        } preview: {
            CameraMediaItem(
                id: item.id, name: item.name, kind: item.kind, sizeBytes: item.sizeBytes,
                captureTime: item.captureTime, previewAvailable: item.previewAvailable,
                protected: item.protected, rating: item.rating, rotationDegrees: degrees
            )
        }
    }

    private func updateMediaMetadata(
        _ item: CameraMediaItem,
        feature: CameraFeature,
        update: (CameraSession) async throws -> CameraMediaItem,
        preview: () -> CameraMediaItem
    ) async {
        guard supports(feature), begin(.media) else { return }
        defer { end(.media) }
        if isPreview {
            applyUpdatedMedia(preview())
            lastError = nil
            return
        }
        guard let session else { return }
        do {
            applyUpdatedMedia(try await update(session))
            lastError = nil
        } catch {
            record(error)
        }
    }

    func startMediaDownload(_ item: CameraMediaItem) {
        guard supports(.mediaDownload), begin(.media) else { return }
        let token = UUID()
        mediaDownloadToken = token
        activeMediaDownloadID = item.id
        mediaDownloadProgress = CameraMediaTransferProgress(
            bytesTransferred: 0,
            totalBytes: item.sizeBytes
        )
        deletedMediaName = nil
        mediaDownloadTask = Task { [weak self] in
            await self?.performMediaDownload(item, token: token)
        }
    }

    func cancelMediaDownload() {
        mediaDownloadTask?.cancel()
    }

    private func performMediaDownload(_ item: CameraMediaItem, token: UUID) async {
        defer {
            if mediaDownloadToken == token {
                mediaDownloadTask = nil
                mediaDownloadToken = nil
                activeMediaDownloadID = nil
                mediaDownloadProgress = nil
                end(.media)
            }
        }
        if isPreview {
            downloadedMediaID = item.id
            downloadedFileName = item.name
            lastError = nil
            return
        }
        guard let session else { return }
        var downloadDirectory: URL?
        var completed = false
        defer {
            if !completed, let downloadDirectory {
                try? FileManager.default.removeItem(at: downloadDirectory)
            }
        }
        do {
            removeDownloadedFile()
            let directory = FileManager.default.temporaryDirectory
                .appendingPathComponent("OpenEOSControl", isDirectory: true)
                .appendingPathComponent(UUID().uuidString, isDirectory: true)
            downloadDirectory = directory
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let destination = directory.appendingPathComponent(item.name)
            let result = try await session.downloadMedia(
                item,
                to: destination,
                progress: { [weak self] progress in
                    Task { @MainActor in
                        guard self?.mediaDownloadToken == token else { return }
                        self?.mediaDownloadProgress = progress
                    }
                }
            )
            try Task.checkCancellation()
            guard mediaDownloadToken == token else { throw CancellationError() }
            downloadedFileURL = result.fileURL
            downloadedMediaID = result.item.id
            downloadedFileName = result.item.name
            lastError = nil
            completed = true
        } catch is CancellationError {
            // User cancellation is an expected control action, not a camera error.
        } catch let error as URLError where error.code == .cancelled {
            // URLSession reports an explicitly cancelled download as URLError.cancelled.
        } catch {
            if !Task.isCancelled { record(error) }
        }
    }

    func deleteMedia(_ item: CameraMediaItem) async {
        guard supports(.mediaDelete), begin(.media) else { return }
        defer { end(.media) }
        if isPreview {
            applyDeletedMedia(item)
            lastError = nil
            return
        }
        guard let session else { return }
        do {
            try await session.deleteMedia(item)
            applyDeletedMedia(item)
            lastError = nil
        } catch {
            record(error)
        }
    }

    func diagnosticReport() async -> String {
        let metrics = CCAPILiveViewMetrics(
            requestedFPS: requestedFPS,
            observedFPS: observedFPS,
            frameBytes: frameBytes,
            contentType: frameContentType,
            source: activeLiveViewSource,
            sourceURL: frameSourceURL,
            lastFrameAt: lastFrameAt
        )
        let report: String
        if let session {
            report = await session.diagnosticReport(snapshot: snapshot, liveView: metrics, lastError: lastError)
        } else if connectionMode == .desktopBridge, let url = URL(string: bridgeURL) {
            report = DesktopBridgeDiagnosticReport.make(
                baseURL: url,
                snapshot: snapshot,
                liveView: metrics,
                lastError: lastError
            )
        } else {
            let url = URL(string: baseURL) ?? URL(string: Self.defaultCameraURL)!
            report = CCAPIDiagnosticReport.make(
                baseURL: url,
                mode: isPreview ? .simulator : .automatic,
                versions: isPreview ? ["offline-preview"] : [],
                snapshot: snapshot,
                liveView: metrics,
                lastError: lastError
            )
        }
        let monitoring = [
            "lastClockSyncAt=\(lastClockSyncAt.map { ISO8601DateFormatter().string(from: $0) } ?? "none")",
            "monitorHistogram=\(monitorSettings.histogramVisible)",
            "monitorWaveform=\(monitorSettings.waveformVisible)",
            "monitorZebra=\(monitorSettings.zebraThresholdPercent.map { String($0) } ?? "off")",
            "monitorFalseColor=\(monitorSettings.falseColorEnabled)",
            "monitorFocusPeaking=\(monitorSettings.focusPeakingEnabled)",
            "monitorFrameGuide=\(monitorSettings.frameGuide.rawValue)",
            "monitorSafeArea=\(monitorSettings.safeAreaVisible)",
            "monitorDesqueeze=\(monitorSettings.desqueeze.rawValue)",
            "monitorLut=\(monitorSettings.cubeLut.map { "loaded (\($0.size)x\($0.size)x\($0.size))" } ?? "off")",
            "rtpAudioAdvertised=\(rtpAudioStatus.advertised)",
            "rtpAudioAvailable=\(rtpAudioStatus.available)",
            "rtpAudioRequested=\(rtpAudioRequested)",
            "rtpAudioEnabled=\(rtpAudioStatus.enabled)",
            "rtpAudioCodec=\(rtpAudioStatus.codec ?? "none")",
            "rtpAudioPort=\(rtpAudioStatus.rtpPort.map { String($0) } ?? "none")",
            "rtpAudioClockRate=\(rtpAudioStatus.rtpClockRate.map { String($0) } ?? "none")",
            "rtpAudioChannels=\(rtpAudioStatus.channels.map { String($0) } ?? "unknown")",
            "rtpAudioPackets=\(rtpAudioStatus.packetsReceived)",
            "rtpAudioAccessUnits=\(rtpAudioStatus.accessUnitsReceived)",
            "rtpAudioDecoded=\(rtpAudioStatus.decodedAccessUnits)",
            "rtpAudioPlayedFrames=\(rtpAudioStatus.playedSampleFrames)",
            "rtpAudioDropped=\(rtpAudioStatus.droppedAccessUnits)",
            "rtpAudioLastPacket=\(rtpAudioStatus.lastPacketAt.map { ISO8601DateFormatter().string(from: $0) } ?? "none")",
            "rtpAudioLastPCM=\(rtpAudioStatus.lastPCMAt.map { ISO8601DateFormatter().string(from: $0) } ?? "none")",
            "rtpAudioReason=\(rtpAudioStatus.reason ?? "none")",
            "rtpAudioError=\(rtpAudioStatus.error ?? "none")",
        ].joined(separator: "\n")
        return "\(report)\n\(monitoring)"
    }

    func setOperatorConfirmation(_ feature: CameraFeature, confirmed: Bool) {
        let eligible = physicalValidation.eligibleFeatures.contains(feature)
        if confirmed, eligible {
            operatorConfirmedFeatures.insert(feature)
        } else if !confirmed {
            operatorConfirmedFeatures.remove(feature)
        }
    }

    func physicalValidationRecord() async throws -> String {
        let report = await diagnosticReport()
        return try PhysicalValidationRecord.make(
            summary: physicalValidation,
            info: info,
            transport: transportIdentifier,
            diagnosticReport: report
        )
    }

    func clearError() {
        lastError = nil
    }

    func importCubeLut(from url: URL) async {
        let task = Task.detached(priority: .userInitiated) { () throws -> CubeLut in
            let accessed = url.startAccessingSecurityScopedResource()
            defer { if accessed { url.stopAccessingSecurityScopedResource() } }
            let fileSize = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize
            guard fileSize == nil || fileSize! <= maximumCubeLutBytes else {
                throw CubeLutError.invalid("3D LUT exceeds the 16 MiB limit.")
            }
            let data = try Data(contentsOf: url, options: .mappedIfSafe)
            guard data.count <= maximumCubeLutBytes else {
                throw CubeLutError.invalid("3D LUT exceeds the 16 MiB limit.")
            }
            guard let text = String(data: data, encoding: .utf8) else {
                throw CubeLutError.invalid("The selected LUT is not valid UTF-8 text.")
            }
            return try parseCubeLut(text, fallbackName: url.lastPathComponent)
        }
        do {
            monitorSettings.cubeLut = try await task.value
            lastError = nil
        } catch {
            lastError = String(
                format: NSLocalizedString("lut_import_failed", comment: ""),
                error.localizedDescription
            )
        }
    }

    func clearCubeLut() {
        monitorSettings.cubeLut = nil
    }

    func reportCubeLutImportError(_ error: Error) {
        lastError = String(
            format: NSLocalizedString("lut_import_failed", comment: ""),
            error.localizedDescription
        )
    }

    func reportCubeLutRenderFailure() {
        lastError = NSLocalizedString("lut_render_failed", comment: "")
    }

    private func begin(_ operation: CameraOperation) -> Bool {
        if bulbExposureActive && operation != .capture { return false }
        let inserted = busyOperations.insert(operation).inserted
        if inserted { operationRevision &+= 1 }
        return inserted
    }

    private func end(_ operation: CameraOperation) {
        busyOperations.remove(operation)
    }

    private func updateStatus(_ status: CameraStatus) {
        guard let snapshot else { return }
        self.snapshot = snapshot.replacing(status: status)
        if status.bulbExposureActive == true {
            bulbStartedAt = bulbStartedAt ?? Date()
        } else {
            bulbStartedAt = nil
        }
    }

    private func clampLiveViewRequest() {
        guard let liveView = capabilities?.liveView else { return }
        setRequestedFPS(requestedFPS)
        if !liveView.sizes.contains(liveViewSize) { liveViewSize = liveView.defaultSize }
        if selectedLiveViewSource != .auto, !liveView.sources.contains(selectedLiveViewSource) {
            selectedLiveViewSource = .auto
            defaults.set(LiveViewSource.auto.rawValue, forKey: DefaultsKey.liveViewSource)
        }
    }

    private func effectiveRequestedLiveViewSource() -> LiveViewSource {
        guard let liveView = capabilities?.liveView else { return .auto }
        return selectedLiveViewSource == .auto || liveView.sources.contains(selectedLiveViewSource)
            ? selectedLiveViewSource
            : .auto
    }

    private func beginLiveViewLoop(session: CameraSession) {
        stopLiveViewLoop()
        resetLiveViewMetrics()
        liveViewTask = Task { [weak self] in
            guard let self else { return }
            var cacheKey: Int64 = 0
            while !Task.isCancelled {
                let started = Date().timeIntervalSinceReferenceDate
                do {
                    let frame = try await session.liveViewFrame(cacheKey: cacheKey)
                    cacheKey &+= 1
                    liveViewData = frame.data
                    frameBytes = frame.data.count
                    frameContentType = frame.contentType
                    frameSourceURL = frame.sourceURL
                    let now = Date()
                    lastFrameAt = now
                    observedFPS = rateTracker.record(now.timeIntervalSinceReferenceDate)
                    if lastError?.contains("Live View") == true { lastError = nil }
                } catch {
                    if Task.isCancelled { break }
                    record(error)
                }

                let elapsed = Date().timeIntervalSinceReferenceDate - started
                let interval = 1 / Double(max(requestedFPS, 1))
                let remaining = max(0.001, interval - elapsed)
                do {
                    try await Task.sleep(nanoseconds: UInt64(remaining * 1_000_000_000))
                } catch {
                    break
                }
            }
        }
    }

    private func stopLiveViewLoop() {
        liveViewTask?.cancel()
        liveViewTask = nil
    }

    private func beginEventLoop(session: CameraSession) {
        stopEventLoop()
        guard supports(.eventPolling), !isPreview else { return }
        let generation = eventGeneration
        eventTask = Task { [weak self] in
            guard let self else { return }
            var failures = 0
            var pendingKeys = Set<String>()
            while !Task.isCancelled, generation == eventGeneration {
                do {
                    if pendingKeys.isEmpty {
                        let event = try await session.pollEvent()
                        pendingKeys.formUnion(event.changedKeys)
                    }
                    guard !pendingKeys.isEmpty else {
                        failures = 0
                        continue
                    }
                    guard let refreshed = try await stableEventSnapshot(
                        session: session,
                        generation: generation
                    ) else { break }
                    snapshot = refreshed
                    clampLiveViewRequest()
                    if screen == .media,
                       pendingKeys.contains(where: { $0.lowercased().contains("content") }) {
                        guard try await refreshMediaAfterEvent(
                            session: session,
                            generation: generation
                        ) else { break }
                    }
                    pendingKeys.removeAll()
                    failures = 0
                } catch is CancellationError {
                    break
                } catch {
                    guard generation == eventGeneration, !Task.isCancelled else { break }
                    failures += 1
                    let delays: [UInt64] = [1_000_000_000, 2_000_000_000, 5_000_000_000]
                    try? await Task.sleep(nanoseconds: delays[min(failures - 1, delays.count - 1)])
                }
            }
        }
    }

    private func stopEventLoop() {
        eventGeneration = UUID()
        eventTask?.cancel()
        eventTask = nil
    }

    private func stableEventSnapshot(
        session: CameraSession,
        generation: UUID
    ) async throws -> CameraSnapshot? {
        while generation == eventGeneration, !Task.isCancelled {
            while !busyOperations.isEmpty, generation == eventGeneration, !Task.isCancelled {
                try await Task.sleep(nanoseconds: 50_000_000)
            }
            guard generation == eventGeneration, !Task.isCancelled else { return nil }

            let revision = operationRevision
            let refreshed = try await session.connectSnapshot()
            if revision == operationRevision, busyOperations.isEmpty {
                return refreshed
            }
        }
        return nil
    }

    private func refreshMediaAfterEvent(
        session: CameraSession,
        generation: UUID
    ) async throws -> Bool {
        while generation == eventGeneration, !Task.isCancelled {
            if screen != .media { return true }
            if busyOperations.contains(.media) || bulbExposureActive {
                try await Task.sleep(nanoseconds: 50_000_000)
                continue
            }
            guard begin(.media) else { continue }
            do {
                let items = try await session.listMedia()
                end(.media)
                guard generation == eventGeneration, !Task.isCancelled else { return false }
                resetMediaThumbnails()
                resetMediaPreview()
                mediaItems = items
                lastError = nil
                return true
            } catch {
                end(.media)
                throw error
            }
        }
        return false
    }

    private func pauseLiveViewForBulb() {
        stopLiveViewLoop()
        rtpController.setRenderingEnabled(false)
    }

    private func resumeLiveViewAfterBulb(session: CameraSession) {
        rtpController.setRenderingEnabled(autoRefresh)
        guard autoRefresh, activeLiveViewSource != nil else { return }
        if activeLiveViewSource != .ccapiRTP {
            beginLiveViewLoop(session: session)
        }
    }

    private func resetLiveViewMetrics() {
        rateTracker.reset()
        observedFPS = 0
        frameBytes = 0
        frameContentType = nil
        frameSourceURL = nil
        lastFrameAt = nil
    }

    private func handleRTPEvent(_ event: IOSCcapiRTPEvent) {
        switch event {
        case let .audioStatus(status):
            rtpAudioStatus = status
        case let .frame(encodedBytes, at):
            guard activeLiveViewSource == .ccapiRTP else { return }
            frameBytes = encodedBytes
            frameContentType = "video/H264"
            lastFrameAt = at
            observedFPS = rateTracker.record(at.timeIntervalSinceReferenceDate)
            if lastError?.contains("RTP") == true { lastError = nil }
        case let .videoSize(width, height):
            guard activeLiveViewSource == .ccapiRTP else { return }
            nativeLiveViewSize = CGSize(width: CGFloat(width), height: CGFloat(height))
        case let .failed(message):
            guard activeLiveViewSource == .ccapiRTP else { return }
            lastError = message
        }
    }

    private func showShutterFlash() {
        shutterFlash = true
        Task { [weak self] in
            try? await Task.sleep(nanoseconds: 130_000_000)
            self?.shutterFlash = false
        }
    }

    private func showFocusMarker(x: Double, y: Double, accepted: Bool) {
        focusMarker = FocusMarker(x: x, y: y, accepted: accepted)
        Task { [weak self] in
            try? await Task.sleep(nanoseconds: 1_200_000_000)
            if self?.focusMarker == FocusMarker(x: x, y: y, accepted: accepted) {
                self?.focusMarker = nil
            }
        }
    }

    private func record(_ error: Error) {
        lastError = error.localizedDescription
    }

    private func removeDownloadedFile() {
        guard let file = downloadedFileURL else {
            downloadedMediaID = nil
            downloadedFileName = nil
            return
        }
        try? FileManager.default.removeItem(at: file.deletingLastPathComponent())
        downloadedFileURL = nil
        downloadedMediaID = nil
        downloadedFileName = nil
    }

    private func resetMediaDownloadState() {
        mediaDownloadTask?.cancel()
        mediaDownloadTask = nil
        mediaDownloadToken = nil
        activeMediaDownloadID = nil
        mediaDownloadProgress = nil
        busyOperations.remove(.media)
    }

    private func applyDeletedMedia(_ item: CameraMediaItem) {
        if downloadedMediaID == item.id {
            removeDownloadedFile()
        }
        mediaItems.removeAll { $0.id == item.id }
        mediaThumbnails.removeValue(forKey: item.id)
        loadingMediaThumbnailIDs.remove(item.id)
        unavailableMediaThumbnailIDs.remove(item.id)
        if mediaPreviewItem?.id == item.id { resetMediaPreview() }
        deletedMediaName = item.name
    }

    private func applyUpdatedMedia(_ item: CameraMediaItem) {
        mediaItems = mediaItems.map { $0.id == item.id ? item : $0 }
        if mediaPreviewItem?.id == item.id { mediaPreviewItem = item }
    }

    private func resetMediaThumbnails() {
        mediaThumbnailGeneration &+= 1
        mediaThumbnails = [:]
        loadingMediaThumbnailIDs = []
        unavailableMediaThumbnailIDs = []
    }

    private func resetMediaPreview() {
        mediaPreviewItem = nil
        mediaPreviewData = nil
        mediaPreviewLoading = false
    }

    static func makeOfflinePreviewSnapshot() -> CameraSnapshot {
        let settings = [
            CameraSetting(key: "iso", label: "ISO", value: "800", values: ["Auto", "100", "200", "400", "800", "1600", "3200", "6400", "12800"]),
            CameraSetting(key: "shutter", label: "Shutter speed", value: "1/125", values: ["1/30", "1/50", "1/60", "1/100", "1/125", "1/250", "1/500", "1/1000"]),
            CameraSetting(key: "aperture", label: "Aperture", value: "2.8", values: ["1.8", "2.0", "2.8", "4.0", "5.6", "8.0", "11"]),
            CameraSetting(key: "whitebalance", label: "White balance", value: "auto", values: ["auto", "daylight", "shade", "cloudy", "tungsten", "fluorescent", "flash"]),
            CameraSetting(key: "moviemode", label: "Movie mode", value: "off", values: ["off", "on"]),
            CameraSetting(key: "shootingmode", label: "Shooting mode", value: "Manual", values: ["P", "TV", "AV", "Manual", "Bulb", "Movie", "Fv"]),
            CameraSetting(key: "afmethod", label: "AF method", value: "face+tracking", values: ["face+tracking", "1-point", "zone"]),
            CameraSetting(key: "afoperation", label: "AF operation", value: "servo", values: ["one-shot", "servo"]),
            CameraSetting(key: "drivemode", label: "Drive mode", value: "single", values: ["single", "high-speed", "timer"]),
            CameraSetting(key: "meteringmode", label: "Metering", value: "evaluative", values: ["evaluative", "partial", "spot"]),
            CameraSetting(key: "picturestyle", label: "Picture style", value: "standard", values: ["standard", "portrait", "landscape", "neutral"]),
            CameraSetting(key: "stillimagequality", label: "Image quality", value: "RAW+L", values: ["RAW+L", "RAW", "C-RAW", "L"]),
            CameraSetting(key: "capturestorage", label: "Recording card", value: "CFe", values: ["CFe", "SD"]),
            CameraSetting(key: "cardselectionstillimage", label: "Still-image card", value: "card1", values: ["none", "card1", "card2"]),
            CameraSetting(key: "cardselectionmovie", label: "Movie card", value: "card2", values: ["none", "card1", "card2"]),
            CameraSetting(key: "directoryselection", label: "Capture directory", value: "100EOSXX", values: ["100EOSXX", "101EOSXX"]),
            CameraSetting(key: "soundrecording", label: "Sound recording", value: "manual", values: ["auto", "manual", "disable"]),
            CameraSetting(key: "soundrecordinglevel", label: "Sound recording level", value: "32", values: (0...63).map(String.init)),
            CameraSetting(key: "windfilter", label: "Wind filter", value: "auto", values: ["auto", "enable", "disable"]),
            CameraSetting(key: "attenuator", label: "Attenuator", value: "disable", values: ["enable", "disable", "auto", "manual"]),
            CameraSetting(key: "beep", label: "Beep", value: "enable", values: ["enable", "disable", "disabletouch"]),
            CameraSetting(key: "displayoff", label: "Auto display off", value: "60", values: ["10", "20", "30", "60", "120", "180"]),
            CameraSetting(key: "autopoweroff", label: "Auto power off", value: "180", values: ["30", "60", "120", "180", "300", "600", "disable"]),
            CameraSetting(key: "focusbracketing", label: "Focus bracketing", value: "disable", values: ["enable", "disable"]),
            CameraSetting(key: "focusbracketingnumberofshots", label: "Focus bracketing shots", value: "100", values: (2...999).map(String.init)),
            CameraSetting(key: "focusbracketingfocusincrement", label: "Focus increment", value: "4", values: (1...10).map(String.init)),
            CameraSetting(key: "focusbracketingexposuresmoothing", label: "Exposure smoothing", value: "disable", values: ["enable", "disable"]),
            CameraSetting(key: "highframerate", label: "High frame rate", value: "disable", values: ["enable", "disable"]),
            CameraSetting(key: "moviecropping", label: "Movie cropping", value: "disable", values: ["enable", "disable"]),
            CameraSetting(key: "movieformat", label: "Movie recording format", value: "mp4", values: ["raw", "mp4"]),
            CameraSetting(key: "zoom", label: "Zoom", value: "50", values: (0...100).map(String.init)),
            CameraSetting(
                key: "moviequality",
                label: "Movie quality",
                value: "3840x2160_5994_ipb_standard",
                values: ["3840x2160_5994_ipb_standard", "1920x1080_2997_ipb_standard"]
            ),
            CameraSetting(key: "framerate", label: "Frame rate", value: "59.94p", values: ["23.98p", "29.97p", "59.94p"]),
        ]
        let supported: Set<CameraFeature> = [
            .cameraIdentity, .batteryStatus, .storageStatus, .recordableStatus, .liveView, .liveViewJPEGPolling,
            .stillCapture, .bulbExposure, .autofocus, .shutterHalfPress, .movieModeControl, .videoRecording,
            .tapFocus, .clickWhiteBalance,
            .liveViewMagnification,
            .exposureControl, .whiteBalanceControl, .zoomControl, .cardSelectionControl, .directoryControl,
            .fileNamingControl,
            .soundRecordingControl, .soundRecordingLevelControl, .focusBracketingControl,
            .movieSettingsControl,
            .advancedSettings, .sensorCleaning, .cameraSleep, .mediaBrowser, .mediaDownload,
            .mediaProtect, .mediaRating, .mediaRotate, .mediaDelete,
        ]
        let capabilities = CameraCapabilities(
            settings: settings,
            fileNaming: CameraFileNaming(
                stillFilenameMode: "preset_code",
                stillFilenameModeOptions: ["preset_code", "usersetting1", "usersetting2"],
                stillUserSetting1: "IMG_",
                stillUserSetting2: "IMG",
                movieIndex: "A_",
                movieReelNumber: 1,
                movieReelRange: CameraIntegerRange(minimum: 1, maximum: 9999, step: 1),
                movieClipNumber: 1,
                movieClipRange: CameraIntegerRange(minimum: 1, maximum: 999, step: 1),
                movieUserDefined: "CANON"
            ),
            matrix: CapabilityMatrix(supported: supported, planned: [.liveViewRTP, .focusDrive]),
            liveView: LiveViewCapabilities(
                sources: [.ccapiJPEGPolling],
                defaultSource: .ccapiJPEGPolling,
                sizes: LiveViewSize.allCases,
                defaultSize: .medium,
                minimumFPS: 1,
                maximumFPS: 30
            ),
            profile: CameraProfile.from(modelName: "Canon EOS R6 Mark III")
        )
        let status = CameraStatus(
            batteryLevel: 82,
            batteryStatus: "good",
            recording: false,
            mode: "photo",
            mediaAvailable: true,
            remainingMinutes: 118,
            exposure: ExposureState(iso: "800", shutter: "1/125", aperture: "2.8", whiteBalance: "auto"),
            storageTotalBytes: 128_000_000_000,
            storageFreeBytes: 84_000_000_000,
            storageFreeImages: 2_418,
            storageDeviceCount: 2,
            recordableShots: 2_418,
            remainingRecordingSeconds: 7_080,
            rawBatteryJSON: #"{"kind":"battery","level":82,"quality":"good"}"#,
            rawStorageJSON: #"{"name":"card1","status":"ready"}"#
        )
        return CameraSnapshot(
            info: CameraInfo(model: "Canon EOS R6 Mark III", serial: "offline-preview", api: "offline-preview"),
            status: status,
            capabilities: capabilities
        )
    }

    static let previewMedia = [
        CameraMediaItem(id: "preview-001", name: "R6M3_0001.CR3", kind: "raw", sizeBytes: 31_457_280, captureTime: "2026-07-21T10:08:24+08:00", previewAvailable: true, protected: true, rating: 5, rotationDegrees: 0),
        CameraMediaItem(id: "preview-002", name: "R6M3_0001.JPG", kind: "image", sizeBytes: 8_912_384, captureTime: "2026-07-21T10:08:24+08:00", previewAvailable: true, protected: false, rating: 3, rotationDegrees: 90),
        CameraMediaItem(id: "preview-003", name: "R6M3_0002.MP4", kind: "video", sizeBytes: 128_450_560, captureTime: "2026-07-21T10:10:02+08:00", protected: false, rating: 0, rotationDegrees: 0),
    ]
}
