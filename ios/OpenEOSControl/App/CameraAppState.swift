import Foundation
import OpenEOSCore

@MainActor
final class CameraAppState: ObservableObject {
    static let defaultCameraURL = "http://192.168.1.2:8080"
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
    @Published var username: String
    @Published var password = ""
    @Published var bridgeURL: String
    @Published var bridgeToken = ""
    @Published private(set) var bridgeCameras: [DesktopBridgeCamera] = []
    @Published var selectedBridgeCameraID: String?
    @Published private(set) var snapshot: CameraSnapshot?
    @Published private(set) var isPreview = false
    @Published var screen = AppScreen.control
    @Published var captureMode = AppCaptureMode.photo
    @Published var activeSheet: CameraSheet?
    @Published var hudVisible = true
    @Published var showGrid = false
    @Published var liveViewTapAction = LiveViewTapAction.focus
    @Published var autoRefresh = true
    @Published private(set) var requestedFPS: Int
    @Published private(set) var liveViewSize: LiveViewSize
    @Published private(set) var selectedLiveViewSource: LiveViewSource
    @Published private(set) var activeLiveViewSource: LiveViewSource?
    @Published private(set) var nativeLiveViewSize: CGSize?
    @Published private(set) var liveViewData: Data?
    @Published private(set) var liveViewMagnification: LiveViewMagnification?
    @Published private(set) var observedFPS = 0.0
    @Published private(set) var frameBytes = 0
    @Published private(set) var frameContentType: String?
    @Published private(set) var frameSourceURL: URL?
    @Published private(set) var lastFrameAt: Date?
    @Published private(set) var shutterFlash = false
    @Published private(set) var focusMarker: FocusMarker?
    @Published private(set) var mediaItems: [CameraMediaItem] = []
    @Published private(set) var mediaThumbnails: [String: Data] = [:]
    @Published private(set) var loadingMediaThumbnailIDs = Set<String>()
    @Published private(set) var mediaPreviewItem: CameraMediaItem?
    @Published private(set) var mediaPreviewData: Data?
    @Published private(set) var mediaPreviewLoading = false
    @Published private(set) var downloadedFileURL: URL?
    @Published private(set) var downloadedFileName: String?
    @Published private(set) var deletedMediaName: String?
    @Published private(set) var lastError: String?
    @Published private(set) var busyOperations = Set<CameraOperation>()

    private let defaults: UserDefaults
    private var session: CameraSession?
    private var liveViewTask: Task<Void, Never>?
    private var rateTracker = LiveViewRateTracker()
    private var downloadedMediaID: String?
    private var unavailableMediaThumbnailIDs = Set<String>()
    private var mediaThumbnailGeneration = 0
    let rtpController: IOSCcapiRTPController

    var connected: Bool { snapshot?.status.connected == true }
    var recording: Bool { snapshot?.status.recording == true }
    var capabilities: CameraCapabilities? { snapshot?.capabilities }
    var status: CameraStatus? { snapshot?.status }
    var info: CameraInfo? { snapshot?.info }
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
        busyOperations.contains(operation)
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
        setBaseURL(Self.defaultCameraURL)
    }

    func useHTTPSPreset() {
        setBaseURL(Self.defaultSecureCameraURL)
    }

    func useSimulatorPreset() {
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
        do {
            let newSession: CameraSession
            switch connectionMode {
            case .ccapi:
                let rtpAddress = CameraRTPNetworkAddress.destinationAddress(cameraURL: baseURL)
                newSession = .ccapi(
                    try CCAPIClient(
                        baseURL: baseURL,
                        mode: .automatic,
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
            removeDownloadedFile()
            deletedMediaName = nil
            lastError = nil
            clampLiveViewRequest()
            if newSnapshot.capabilities.matrix.supports(.liveView), autoRefresh {
                await startLiveView()
            }
        } catch {
            record(error)
        }
    }

    func openOfflinePreview() {
        stopLiveViewLoop()
        session = nil
        snapshot = Self.makeOfflinePreviewSnapshot()
        isPreview = true
        screen = .control
        mediaItems = Self.previewMedia
        resetMediaThumbnails()
        resetMediaPreview()
        removeDownloadedFile()
        deletedMediaName = nil
        lastError = nil
        liveViewData = nil
        liveViewMagnification = nil
        activeLiveViewSource = nil
        nativeLiveViewSize = nil
        resetLiveViewMetrics()
    }

    func disconnect() async {
        stopLiveViewLoop()
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
        focusMarker = nil
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

    func startLiveView() async {
        guard let session, supports(.liveView), begin(.liveView) else { return }
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
            } else if autoRefresh {
                beginLiveViewLoop(session: session)
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
        guard supports(.stillCapture), begin(.capture) else { return }
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
        guard supports(.videoRecording), begin(.recording) else { return }
        defer { end(.recording) }
        if isPreview {
            guard let snapshot else { return }
            self.snapshot = snapshot.replacing(status: snapshot.status.replacing(recording: !recording))
            return
        }
        guard let session else { return }
        do {
            let newStatus = recording ? try await session.stopRecording() : try await session.startRecording()
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

    func setSetting(key: String, value: String) async {
        guard let setting = capabilities?.setting(key), setting.values.contains(value), begin(.setting) else { return }
        defer { end(.setting) }
        if isPreview {
            guard let snapshot else { return }
            let exposure = snapshot.status.exposure.replacing(key: key, value: value)
            self.snapshot = snapshot.replacing(status: snapshot.status.replacing(exposure: exposure))
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
        let previewable = item.kind.caseInsensitiveCompare("image") == .orderedSame
            || item.kind.caseInsensitiveCompare("raw") == .orderedSame
        guard
            !isPreview,
            previewable,
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

    func downloadMedia(_ item: CameraMediaItem) async {
        guard supports(.mediaDownload), begin(.media) else { return }
        defer { end(.media) }
        deletedMediaName = nil
        if isPreview {
            downloadedMediaID = item.id
            downloadedFileName = item.name
            return
        }
        guard let session else { return }
        do {
            removeDownloadedFile()
            let directory = FileManager.default.temporaryDirectory
                .appendingPathComponent("OpenEOSControl", isDirectory: true)
                .appendingPathComponent(UUID().uuidString, isDirectory: true)
            try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            let destination = directory.appendingPathComponent(item.name)
            let result = try await session.downloadMedia(item, to: destination)
            downloadedFileURL = result.fileURL
            downloadedMediaID = result.item.id
            downloadedFileName = result.item.name
            lastError = nil
        } catch {
            record(error)
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
        if let session {
            return await session.diagnosticReport(snapshot: snapshot, liveView: metrics, lastError: lastError)
        }
        if connectionMode == .desktopBridge, let url = URL(string: bridgeURL) {
            return DesktopBridgeDiagnosticReport.make(
                baseURL: url,
                snapshot: snapshot,
                liveView: metrics,
                lastError: lastError
            )
        }
        let url = URL(string: baseURL) ?? URL(string: Self.defaultCameraURL)!
        return CCAPIDiagnosticReport.make(
            baseURL: url,
            mode: isPreview ? .simulator : .automatic,
            versions: isPreview ? ["offline-preview"] : [],
            snapshot: snapshot,
            liveView: metrics,
            lastError: lastError
        )
    }

    func clearError() {
        lastError = nil
    }

    private func begin(_ operation: CameraOperation) -> Bool {
        busyOperations.insert(operation).inserted
    }

    private func end(_ operation: CameraOperation) {
        busyOperations.remove(operation)
    }

    private func updateStatus(_ status: CameraStatus) {
        guard let snapshot else { return }
        self.snapshot = snapshot.replacing(status: status)
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

    private func resetLiveViewMetrics() {
        rateTracker.reset()
        observedFPS = 0
        frameBytes = 0
        frameContentType = nil
        frameSourceURL = nil
        lastFrameAt = nil
    }

    private func handleRTPEvent(_ event: IOSCcapiRTPEvent) {
        guard activeLiveViewSource == .ccapiRTP else { return }
        switch event {
        case let .frame(encodedBytes, at):
            frameBytes = encodedBytes
            frameContentType = "video/H264"
            lastFrameAt = at
            observedFPS = rateTracker.record(at.timeIntervalSinceReferenceDate)
            if lastError?.contains("RTP") == true { lastError = nil }
        case let .videoSize(width, height):
            nativeLiveViewSize = CGSize(width: CGFloat(width), height: CGFloat(height))
        case let .failed(message):
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
            CameraSetting(key: "afmethod", label: "AF method", value: "face+tracking", values: ["face+tracking", "1-point", "zone"]),
            CameraSetting(key: "afoperation", label: "AF operation", value: "servo", values: ["one-shot", "servo"]),
            CameraSetting(key: "drivemode", label: "Drive mode", value: "single", values: ["single", "high-speed", "timer"]),
            CameraSetting(key: "meteringmode", label: "Metering", value: "evaluative", values: ["evaluative", "partial", "spot"]),
            CameraSetting(key: "picturestyle", label: "Picture style", value: "standard", values: ["standard", "portrait", "landscape", "neutral"]),
            CameraSetting(key: "stillimagequality", label: "Image quality", value: "RAW+L", values: ["RAW+L", "RAW", "C-RAW", "L"]),
            CameraSetting(key: "moviequality", label: "Movie quality", value: "4K", values: ["4K", "FHD"]),
            CameraSetting(key: "framerate", label: "Frame rate", value: "59.94p", values: ["23.98p", "29.97p", "59.94p"]),
        ]
        let supported: Set<CameraFeature> = [
            .cameraIdentity, .batteryStatus, .storageStatus, .liveView, .liveViewJPEGPolling,
            .stillCapture, .autofocus, .shutterHalfPress, .videoRecording, .tapFocus, .clickWhiteBalance,
            .liveViewMagnification,
            .exposureControl, .whiteBalanceControl, .advancedSettings, .mediaBrowser, .mediaDownload,
            .mediaDelete,
        ]
        let capabilities = CameraCapabilities(
            settings: settings,
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
        CameraMediaItem(id: "preview-001", name: "R6M3_0001.CR3", kind: "raw", sizeBytes: 31_457_280, captureTime: "2026-07-21T10:08:24+08:00"),
        CameraMediaItem(id: "preview-002", name: "R6M3_0001.JPG", kind: "image", sizeBytes: 8_912_384, captureTime: "2026-07-21T10:08:24+08:00"),
        CameraMediaItem(id: "preview-003", name: "R6M3_0002.MP4", kind: "video", sizeBytes: 128_450_560, captureTime: "2026-07-21T10:10:02+08:00"),
    ]
}
