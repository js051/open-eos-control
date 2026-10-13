import Foundation
import OpenEOSCore

/// Narrow reply-publication seam for controlled cancellation/retirement tests.
enum MediaDeliveryReplyStage: Sendable, Equatable { case batchStarted, batchPlanned, itemStarted, originalRetained, snapshot, shareAcquired }

#if DEBUG
/// Opt-in UI-test evidence contains only closed stage names, never camera data.
struct MediaDownloadStageTrace {
    enum Stage: String, CaseIterable, Equatable {
        case request, started, history, validated, published, cancelled, failed
        case rejectedUnsupported = "rejected-unsupported"
        case rejectedBusy = "rejected-busy"
        case requestOriginal = "request-original"
        case replyOriginal = "reply-original"
    }
    let enabled: Bool
    private(set) var entries: [Stage] = []
    init(environment: [String: String] = ProcessInfo.processInfo.environment) {
        enabled = environment["OEC_MEDIA_DOWNLOAD_TRACE"] == "1"
    }
    mutating func record(_ stage: Stage) {
        guard enabled else { return }
        entries.append(stage)
        if entries.count > 16 { entries.removeFirst(entries.count - 16) }
    }
    var accessibilityValue: String? { enabled ? entries.map(\.rawValue).joined(separator: "|") : nil }
}
#endif

/// Prepared text retains its admitting connection through the final UI publish.
struct CameraSessionExport {
    let text: String
    fileprivate let owner: UUID
}

@MainActor
final class CameraAppState: ObservableObject {
    static let latestMediaRequestItemCount = 8
    private static let latestMediaRetryDelays: [UInt64] = [250_000_000, 750_000_000, 1_500_000_000]
    private static let recentMediaItemCount = 60
    private static let recentMediaRequestCount = recentMediaItemCount + 1

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

    @Published var connectionMode: AppConnectionMode {
        didSet {
            if connectionMode != oldValue {
                invalidateConnectionGuidance()
                invalidateBridgeScan()
            }
        }
    }
    @Published var baseURL: String {
        didSet { if baseURL != oldValue { invalidateConnectionGuidance() } }
    }
    @Published private(set) var ccapiConnectionMode = CCAPIConnectionMode.automatic
    @Published var username: String {
        didSet { if username != oldValue { invalidateConnectionGuidance() } }
    }
    @Published var password = "" {
        didSet { if password != oldValue { invalidateConnectionGuidance() } }
    }
    @Published var bridgeURL: String {
        didSet {
            if bridgeURL != oldValue {
                invalidateConnectionGuidance()
                invalidateBridgeScan()
            }
        }
    }
    @Published var bridgeToken = "" {
        didSet {
            if bridgeToken != oldValue {
                invalidateConnectionGuidance()
                invalidateBridgeScan()
            }
        }
    }
    @Published private(set) var connectionRecovery: ConnectionRecovery?
    @Published private(set) var bridgeScanState = BridgeScanState.notScanned
    @Published private(set) var bridgeCameras: [DesktopBridgeCamera] = []
    @Published var selectedBridgeCameraID: String? {
        didSet { if selectedBridgeCameraID != oldValue { invalidateConnectionGuidance() } }
    }
    @Published private(set) var snapshot: CameraSnapshot? {
        didSet {
            liveViewMagnification = snapshot?.capabilities.liveView.currentMagnification
            if recordingRecovery != nil {
                captureMode = .video
            } else if let setting = snapshot.flatMap({ captureModeSetting($0.capabilities.settings) }),
               let mode = appCaptureMode(for: setting) {
                captureMode = mode
            } else if snapshot?.status.recording == true {
                captureMode = .video
            }
        }
    }
    @Published private(set) var isPreview = false
    @Published var screen = AppScreen.control {
        didSet {
            if screen != oldValue {
                invalidateCameraFocusInfo()
                if screen != .control { releaseHeldAutofocus() }
            }
        }
    }
    @Published var captureMode = AppCaptureMode.photo {
        didSet {
            if captureMode != oldValue { releaseHeldAutofocus(); invalidateCameraFocusInfo() }
        }
    }
    struct RecordingReadbackRecovery: Equatable {
        let sessionGeneration: UUID
        let commandID: UUID
        let starting: Bool
    }
    @Published private(set) var recordingReadbackRecovery: RecordingReadbackRecovery?
    // A missing command response is not an ACK. Cached status cannot settle it.
    @Published private(set) var recordingCommandRecovery: RecordingReadbackRecovery?
    var recordingRecovery: RecordingReadbackRecovery? { recordingCommandRecovery ?? recordingReadbackRecovery }
    @Published var activeSheet: CameraSheet? {
        didSet { if activeSheet != nil { releaseHeldAutofocus(); invalidateCameraFocusInfo() } }
    }
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
    @Published private(set) var shutterAutofocus = true
    @Published private(set) var shutterFlash = false
    @Published private(set) var bulbStartedAt: Date?
    @Published private(set) var shutterReleaseState = CameraShutterReleaseState.idle {
        didSet { shutterReleaseObservationRevision &+= 1 }
    }
    @Published private(set) var previousShutterReleaseWarnings = Set<UUID>()
    @Published private(set) var previousRecordingWarnings = Set<UUID>()
    @Published private(set) var liveViewStopState = CameraLiveViewStopState.idle
    @Published private(set) var previousLiveViewWarnings = Set<UUID>()
    private var acknowledgedPreviousLiveViewWarnings = Set<UUID>()
    var canRetryLiveViewStop: Bool { session != nil && liveViewStopState.stopUnconfirmed && !isBusy(.liveView) && foregroundLiveViewRestartOwner == nil && foregroundMediaIOOwner == nil }
    private var acknowledgedPreviousRecordingWarnings = Set<UUID>()
    @Published private(set) var focusMarker: FocusMarker?
    @Published private(set) var heldAutofocusPressed = false
    @Published private(set) var cameraFocusInfo: CameraFocusInfo?
    @Published private(set) var cameraFocusInfoAt: Date?
    @Published private(set) var cameraFocusInfoError = false
    @Published private(set) var mediaItems: [CameraMediaItem] = []
    @Published private(set) var latestMediaItem: CameraMediaItem?
    @Published private(set) var latestMediaThumbnail: Data?
    @Published private(set) var latestMediaThumbnailLoading = false
    @Published private(set) var captureReviewState = CaptureReviewState.idle
    @Published private(set) var captureReviewKind = CaptureReviewKind.photo
    @Published private(set) var mediaLibraryLoading = false
    @Published private(set) var mediaLibraryLoadCancellable = false
    @Published private(set) var mediaLibraryLoadStatus = MediaLibraryLoadStatus.notLoaded
    @Published private(set) var mediaLibraryScope = MediaLibraryScope.recent
    @Published private(set) var mediaLibraryHasMore = false
    @Published private(set) var mediaThumbnails: [String: Data] = [:]
    @Published private(set) var loadingMediaThumbnailIDs = Set<String>()
    @Published private(set) var mediaPreviewItem: CameraMediaItem?
    @Published private(set) var mediaPreviewData: Data?
    @Published private(set) var mediaVideoPlayback: CameraMediaPlayback?
    @Published private(set) var mediaPreviewLoading = false
    #if DEBUG
    // Opted-in publication can change UI scheduling; it is evidence, not a fix.
    @Published private(set) var mediaDownloadTrace = MediaDownloadStageTrace()
    private func traceMediaDownload(_ stage: MediaDownloadStageTrace.Stage) {
        // Disabled diagnostics must not publish an otherwise unused view update.
        guard mediaDownloadTrace.enabled else { return }
        mediaDownloadTrace.record(stage)
    }
    #endif
    @Published private(set) var downloadedFileURL: URL?
    @Published private(set) var downloadedFileName: String?
    @Published private(set) var activeMediaDownloadID: String?
    @Published private(set) var mediaDownloadProgress: CameraMediaTransferProgress?
    @Published private(set) var selectedMediaItems: [CameraMediaItem] = []
    @Published private(set) var mediaBatchResults: [MediaBatchResult] = []
    @Published private(set) var mediaBatchState = MediaBatchState.idle
    @Published private(set) var mediaBatchProgress: CameraMediaTransferProgress?
    @Published private(set) var mediaWorklistContinuation: MediaWorklistContinuationDraft?
    @Published private(set) var mediaWorklistContinuationUnavailable = false
    private var mediaBatchContinuationActive = false
    @Published private(set) var mediaBatchCancellationRequested = false
    @Published private(set) var mediaDeliverySnapshot = MediaDeliverySnapshot(records: [], retainedBytes: 0, warning: nil)
    @Published private(set) var mediaDeliveryHistoryLoading = false
    @Published private(set) var mediaDeliveryHistoryWarning = false
    @Published private(set) var mediaBatchWorklistWarning = false
    let foregroundMedia: ForegroundMediaController
    @Published private(set) var foregroundTemporaryResults: [MediaBatchResult] = []
    private var foregroundMediaOwner = UUID()
    private var foregroundMediaIOOwner: UUID?
    private var foregroundLiveViewRestartOwner: UUID?
    private var foregroundMediaTicket: MediaDeliveryTicket?
    private var mediaDeliveryRecordingWarning = false
    private var mediaDeliveryHistoryOwner = UUID()
    private var mediaDeliveryClearGeneration = UUID()
    private let mediaDeliveryStore: MediaDeliveryStore?
    private let mediaDeliveryReplyDelay: (@Sendable (MediaDeliveryReplyStage) async -> Void)?
    @Published private(set) var activeMediaUploadName: String?
    @Published private(set) var mediaUploadProgress: CameraMediaTransferProgress?
    @Published private(set) var uploadedMediaName: String?
    @Published private(set) var mediaUploadError: String?
    @Published private(set) var deletedMediaName: String?
    @Published private(set) var lastClockSyncAt: Date?
    @Published private(set) var lastCreatedDirectoryName: String?
    @Published private(set) var operatorConfirmedFeatures = Set<CameraFeature>()
    @Published private(set) var lastError: String?
    @Published private(set) var busyOperations = Set<CameraOperation>()

    private let defaults: UserDefaults
    @Published private(set) var mediaLibrarySessionID = UUID()
    @Published private(set) var mediaDateRange: MediaDateRange?
    @Published private(set) var mediaRatingFilter = MediaRatingFilter.all
    @Published private(set) var mediaFolderFilter = MediaFolderFilter.all
    private var session: CameraSession? {
        didSet {
            resetMediaBatchState()
            selectedMediaItems = []
            exportGeneration = UUID()
            mediaLibrarySessionID = UUID()
            mediaDateRange = nil
            mediaRatingFilter = .all
            mediaFolderFilter = .all
        }
    }
    private var exportGeneration = UUID()
    private let sessionFactory: (@MainActor () throws -> CameraSession)?
    private let bridgeCameraDiscovery: (@Sendable (String, String) async throws -> [DesktopBridgeCamera])?
    private let cubeLutLoader: (@Sendable (URL) async throws -> CubeLut)?
    private let focusMarkerDelay: (@Sendable () async throws -> Void)?
    private let heldAutofocusDelay: (@Sendable () async throws -> Void)?
    private var bridgeScanOwner = UUID()
    private var connectionConfigurationOwner = UUID()
    private var cubeLutOwner = UUID()
    private var focusMarkerOwner = UUID()
    private var heldAutofocusOwner: UUID?
    private var heldAutofocusTask: Task<Void, Error>?
    private var heldAutofocusWatchdog: Task<Void, Never>?
    private var focusInfoOwner = UUID()
    private var focusInfoTask: Task<Void, Never>?
    private var focusInfoReadTask: Task<CameraFocusInfo?, Error>?
    private var focusInfoReadOwner: UUID?
    private(set) var focusMarkerExpiryTask: Task<Void, Never>?
    private var sessionGeneration = UUID()
    private var shutterReleaseObservationRevision: UInt64 = 0
    private var disconnectTask: Task<Void, Never>?
    private var pendingBulbStartID: UUID?
    private var pendingBulbStartTask: Task<CameraStatus, Error>?
    private var applicationActive = true
    private var acknowledgedPreviousReleaseWarnings = Set<UUID>()
    private var liveViewTask: Task<Void, Never>?
    private var pendingLiveViewStartTask: Task<Void, Never>?
    private var pendingLiveViewStartID: UUID?
    private var liveViewFPSUpdateTask: Task<Void, Never>?
    private var liveViewFPSUpdateRevision: UInt64 = 0
    private var eventTask: Task<Void, Never>?
    private var eventGeneration = UUID()
    private var operationRevision: UInt64 = 0
    private(set) var mediaDownloadTask: Task<Void, Never>?
    private var mediaDownloadToken: UUID?
    private var mediaDownloadCancellationRequested = false
    private(set) var mediaBatchTask: Task<Void, Never>?
    private var mediaBatchToken: UUID?
    private var mediaBatchItemToken: UUID?
    private(set) var mediaUploadTask: Task<Void, Never>?
    private var mediaUploadToken: UUID?
    private var mediaLibraryTask: Task<Void, Never>?
    private var mediaEventDemandGeneration = UUID()
    private var eventMediaListingTask: Task<[CameraMediaItem], Error>?
    private var mediaLibraryGeneration = UUID() {
        didSet {
            // Every inventory replacement retires explicit bindings, including
            // automatic event refresh and scope changes that reuse loaded data.
            if mediaLibraryGeneration != oldValue { cancelMediaWorklistContinuation() }
        }
    }
    private(set) var latestMediaTask: Task<Void, Never>?
    private var latestMediaGeneration = UUID()
    private var observedMediaIDs = Set<String>()
    private var pendingCaptureReview: CaptureReviewAttempt?
    private var mediaPreviewToken: UUID?
    private var mediaPreviewImageTask: Task<CameraMediaPreview, Error>?
    private var rateTracker = LiveViewRateTracker()
    private var downloadedMediaID: String?
    private var downloadedOwnedFile: MediaBatchOwnedFile?
    private var unavailableMediaThumbnailIDs = Set<String>()
    private var mediaThumbnailGeneration = 0
    private var mediaThumbnailCache = MediaThumbnailCache()
    let rtpController: IOSCcapiRTPController

    var connected: Bool { snapshot?.status.connected == true }
    var canOpenLatestMedia: Bool {
        guard let item = latestMediaItem, !isPreview else { return false }
        if mediaIsVideo(item) { return supports(.mediaDownload) }
        return item.previewAvailable && supports(.mediaPreview)
    }
    var latestMediaIsPrevious: Bool { latestMediaItem != nil && pendingCaptureReview != nil }
    var canRetryCaptureMediaReview: Bool {
        (captureReviewState == .notReady || captureReviewState == .readFailed)
            && pendingCaptureReview != nil && session != nil
            && !isPreview && connected && supports(.mediaBrowser) && busyOperations.isEmpty && !shutterReleaseRequired
    }
    var canRetryMediaPreview: Bool {
        guard let item = mediaPreviewItem else { return false }
        return !mediaIsVideo(item) && item.previewAvailable && supports(.mediaPreview)
            && !isPreview && !mediaPreviewLoading && !isBusy(.media) && !shutterReleaseRequired
    }

    func isMediaDownloaded(_ item: CameraMediaItem) -> Bool {
        downloadedMediaID.map { Data($0.utf8) } == mediaItemIdentity(item)
    }

    func downloadedFile(for item: CameraMediaItem) -> URL? {
        isMediaDownloaded(item) ? downloadedFileURL : nil
    }

    func makeMediaDownloadExportPayload(for item: CameraMediaItem) -> MediaBatchExportPayload? {
        guard isMediaDownloaded(item), let downloadedOwnedFile else { return nil }
        return MediaBatchExportPayload(ownedFile: downloadedOwnedFile)
    }

    var recording: Bool { snapshot?.status.recording == true }
    var recordingStopRequired: Bool { recording || recordingRecovery != nil }
    var shutterAutofocusAllowed: Bool { shutterAutofocus || capabilities?.shutterAutofocusSupported == true }
    var showShutterAutofocus: Bool {
        connected && !isPreview && captureMode == .photo && !bulbMode && supports(.stillCapture)
            && (capabilities?.shutterAutofocusSupported == true || !shutterAutofocus)
    }
    var canChangeShutterAutofocus: Bool {
        showShutterAutofocus && busyOperations.isEmpty && !recordingStopRequired && !shutterReleaseRequired
    }

    func setShutterAutofocus(_ enabled: Bool) {
        guard canChangeShutterAutofocus, enabled || capabilities?.shutterAutofocusSupported == true else { return }
        shutterAutofocus = enabled
    }

    var bulbExposureActive: Bool { shutterReleaseState.bulbExposureActive == true }
    var shutterReleaseRequired: Bool { shutterReleaseState.releaseRequired }
    var shutterReleaseUnconfirmed: Bool { shutterReleaseState.releaseUnconfirmed }
    var previousShutterReleaseUnconfirmed: Bool { !previousShutterReleaseWarnings.isEmpty }
    var canRetryShutterRelease: Bool {
        (session != nil || isPreview) && shutterReleaseRequired && heldAutofocusOwner == nil
            && (!busyOperations.contains(.capture) || pendingBulbStartID != nil)
    }
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

    init(
        defaults: UserDefaults = .standard,
        sessionFactory: (@MainActor () throws -> CameraSession)? = nil,
        bridgeCameraDiscovery: (@Sendable (String, String) async throws -> [DesktopBridgeCamera])? = nil,
        cubeLutLoader: (@Sendable (URL) async throws -> CubeLut)? = nil,
        focusMarkerDelay: (@Sendable () async throws -> Void)? = nil,
        heldAutofocusDelay: (@Sendable () async throws -> Void)? = nil,
        rtpEventDispatcher: (@Sendable (@escaping @MainActor @Sendable () -> Void) -> Void)? = nil,
        mediaDeliveryStore: MediaDeliveryStore? = nil,
        mediaDeliveryReplyDelay: (@Sendable (MediaDeliveryReplyStage) async -> Void)? = nil,
        foregroundMedia: ForegroundMediaController? = nil
    ) {
        self.defaults = defaults
        self.foregroundMedia = foregroundMedia ?? ForegroundMediaController()
        self.mediaDeliveryStore = mediaDeliveryStore
        self.mediaDeliveryReplyDelay = mediaDeliveryReplyDelay
        self.sessionFactory = sessionFactory
        self.bridgeCameraDiscovery = bridgeCameraDiscovery
        self.cubeLutLoader = cubeLutLoader
        self.focusMarkerDelay = focusMarkerDelay
        self.heldAutofocusDelay = heldAutofocusDelay
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
        let dispatchRTPEvent = rtpEventDispatcher ?? { action in
            Task { @MainActor in action() }
        }
        rtpController.setEventHandler { [weak self] delivery in
            dispatchRTPEvent { [weak self] in
                guard let self, self.rtpController.accepts(delivery) else { return }
                self.handleRTPEvent(delivery.event)
            }
        }
    }

    func supports(_ feature: CameraFeature) -> Bool {
        capabilities?.matrix.supports(feature) == true
    }

    func isBusy(_ operation: CameraOperation) -> Bool {
        foregroundMediaIOOwner != nil || busyOperations.contains(operation) || shutterControlIsBusy(operation)
            || (shutterReleaseRequired && operation != .refresh)
    }

    private func shutterControlIsBusy(_ operation: CameraOperation) -> Bool {
        (operation == .capture || operation == .focus)
            && (busyOperations.contains(.capture) || busyOperations.contains(.focus))
    }

    func setBaseURL(_ value: String) {
        baseURL = value
        defaults.set(value, forKey: DefaultsKey.baseURL)
    }

    func setConnectionMode(_ value: AppConnectionMode) {
        connectionMode = value
        defaults.set(value.rawValue, forKey: DefaultsKey.connectionMode)
    }

    func setBridgeURL(_ value: String) {
        bridgeURL = value
        defaults.set(value, forKey: DefaultsKey.bridgeURL)
    }

    func setUsername(_ value: String) {
        username = value
        defaults.set(value, forKey: DefaultsKey.username)
    }

    func useHTTPPreset() {
        if ccapiConnectionMode != .camera { invalidateConnectionGuidance() }
        ccapiConnectionMode = .camera
        setBaseURL(Self.defaultCameraURL)
    }

    func useHTTPSPreset() {
        if ccapiConnectionMode != .camera { invalidateConnectionGuidance() }
        ccapiConnectionMode = .camera
        setBaseURL(Self.defaultSecureCameraURL)
    }

    func useSimulatorPreset() {
        if ccapiConnectionMode != .simulator { invalidateConnectionGuidance() }
        ccapiConnectionMode = .simulator
        setBaseURL(Self.simulatorURL)
    }

    private func invalidateConnectionGuidance() {
        connectionConfigurationOwner = UUID()
        connectionRecovery = nil
    }

    private func invalidateBridgeScan() {
        bridgeScanOwner = UUID()
        end(.scan)
        bridgeCameras = []
        selectedBridgeCameraID = nil
        bridgeScanState = .notScanned
    }

    func scanBridgeCameras() async {
        // Both operations publish connection guidance; admission is exclusive
        // even for callers that do not go through the disabled UI controls.
        guard !Task.isCancelled, !busyOperations.contains(.connect), begin(.scan) else { return }
        let owner = UUID()
        bridgeScanOwner = owner
        let previousScanState = bridgeScanState
        let previousRecovery = connectionRecovery
        let requestURL = bridgeURL
        let requestToken = bridgeToken
        bridgeScanState = .scanning
        connectionRecovery = nil
        defer {
            if bridgeScanOwner == owner {
                // Cancellation retains the last completed result and its guidance.
                if bridgeScanState == .scanning {
                    bridgeScanState = previousScanState
                    connectionRecovery = previousRecovery
                }
                end(.scan)
            }
        }
        do {
            let cameras: [DesktopBridgeCamera]
            if let bridgeCameraDiscovery {
                cameras = try await bridgeCameraDiscovery(requestURL, requestToken)
            } else {
                let probe = try DesktopBridgeClient(baseURL: requestURL, token: requestToken)
                cameras = try await probe.discoverCameras()
            }
            guard bridgeScanOwner == owner, !Task.isCancelled else { return }
            bridgeCameras = cameras
            if let selectedBridgeCameraID, cameras.contains(where: { $0.id == selectedBridgeCameraID }) {
                self.selectedBridgeCameraID = selectedBridgeCameraID
            } else {
                selectedBridgeCameraID = cameras.first?.id
            }
            bridgeScanState = .completed
            connectionRecovery = nil
            lastError = nil
        } catch {
            guard bridgeScanOwner == owner, !Task.isCancelled,
                  let recovery = ConnectionRecovery.classify(error, mode: .desktopBridge, operation: .scan) else { return }
            bridgeCameras = []
            selectedBridgeCameraID = nil
            bridgeScanState = .failed
            connectionRecovery = recovery
        }
    }

    func connect() async {
        guard !Task.isCancelled, session == nil, !isPreview,
              !busyOperations.contains(.scan), begin(.connect) else { return }
        exportGeneration = UUID()
        let generation = sessionGeneration
        let configurationOwner = connectionConfigurationOwner
        let requestedMode = connectionMode
        connectionRecovery = nil
        defer { end(.connect, generation: generation) }
        await disconnectTask?.value
        guard generation == sessionGeneration, configurationOwner == connectionConfigurationOwner,
              !Task.isCancelled else { return }
        cancelLiveViewFPSUpdate()
        shutterAutofocus = true
        operatorConfirmedFeatures.removeAll()
        do {
            let newSession: CameraSession
            if let sessionFactory {
                newSession = try sessionFactory()
            } else {
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
            }
            // Retain a connecting session so a disconnect also closes a suspended connect.
            session = newSession
            let newSnapshot: CameraSnapshot
            do {
                newSnapshot = try await newSession.connectSnapshot()
            } catch {
                if generation == sessionGeneration {
                    let release = await newSession.closeWithShutterReleaseState()
                    guard generation == sessionGeneration else { return }
                    preservePreviousReleaseWarning(release, generation: generation)
                    session = nil
                }
                throw error
            }
            let release = await newSession.shutterReleaseState()
            let liveViewStop = await newSession.liveViewStopState()
            guard generation == sessionGeneration else { return }
            if Task.isCancelled || configurationOwner != connectionConfigurationOwner {
                let release = await newSession.closeWithShutterReleaseState()
                guard generation == sessionGeneration else { return }
                preservePreviousReleaseWarning(release, generation: generation)
                session = nil
                return
            }
            snapshot = newSnapshot
            liveViewStopState = liveViewStop
            applyShutterReleaseState(release)
            isPreview = false
            screen = .control
            mediaItems = []
            mediaLibraryHasMore = false
            invalidateMediaLibraryLoad()
            invalidateLatestMedia()
            resetMediaThumbnails()
            resetMediaPreview()
            resetMediaDownloadState()
            resetMediaUploadState()
            removeDownloadedFile()
            deletedMediaName = nil
            lastClockSyncAt = nil
            lastCreatedDirectoryName = nil
            lastError = nil
            connectionRecovery = nil
            clampLiveViewRequest()
            beginEventLoop(session: newSession)
            startLatestMediaRefresh(session: newSession)
            if newSnapshot.capabilities.matrix.supports(.liveView), autoRefresh {
                await startLiveView()
            }
        } catch {
            guard generation == sessionGeneration, configurationOwner == connectionConfigurationOwner,
                  !Task.isCancelled,
                  let recovery = ConnectionRecovery.classify(error, mode: requestedMode, operation: .connect) else { return }
            connectionRecovery = recovery
        }
    }

    @discardableResult
    func setMediaDateRange(_ range: MediaDateRange?, owner: UUID) -> Bool {
        guard owner == mediaLibrarySessionID else { return false }
        mediaDateRange = range
        return true
    }

    @discardableResult
    func setMediaRatingFilter(_ filter: MediaRatingFilter, owner: UUID) -> Bool {
        guard owner == mediaLibrarySessionID else { return false }
        mediaRatingFilter = filter
        return true
    }

    @discardableResult
    func setMediaFolderFilter(_ filter: MediaFolderFilter, owner: UUID) -> Bool {
        guard owner == mediaLibrarySessionID else { return false }
        mediaFolderFilter = filter
        return true
    }

    func openOfflinePreview() {
        requestDisconnect()
        stopLiveViewLoop()
        cancelLiveViewFPSUpdate()
        stopEventLoop()
        resetMediaDownloadState()
        resetMediaUploadState()
        session = nil
        invalidateMediaLibraryLoad()
        invalidateLatestMedia()
        snapshot = Self.makeOfflinePreviewSnapshot()
        isPreview = true
        screen = .control
        mediaItems = Self.previewMedia
        latestMediaItem = nil
        latestMediaThumbnail = nil
        mediaLibraryHasMore = false
        mediaLibraryLoadStatus = .complete
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

    func requestDisconnect() {
        let generation = sessionGeneration
        let release = shutterReleaseState
        if recordingStopRequired { preservePreviousRecordingWarning(generation: generation) }
        let closingSession = prepareDisconnect()
        guard let closingSession else { return }
        preservePreviousReleaseWarning(release, generation: generation)
        let precedingClose = disconnectTask
        disconnectTask = Task { [weak self] in
            await precedingClose?.value
            let result = await closingSession.closeWithShutterReleaseState()
            // Closing first prevents another REC admission. A Start suspended in
            // temperature preflight may otherwise send between sampling and close.
            // Only the retired client's evidence can become this old-camera warning.
            if (await closingSession.recordingCommandEvidence()) != nil {
                self?.preservePreviousRecordingWarning(generation: generation)
            }
            // This is a warning about the old connection, never state for its replacement.
            self?.preservePreviousReleaseWarning(result, generation: generation)
            let liveView = await closingSession.liveViewStopState()
            if liveView.stopUnconfirmed { self?.preservePreviousLiveViewWarning(generation: generation) }
        }
    }

    func disconnect() async {
        requestDisconnect()
        await disconnectTask?.value
    }

    func confirmPreviousShutterReleased() {
        // Operator acknowledgement never sends a command to any camera.
        acknowledgedPreviousReleaseWarnings.formUnion(previousShutterReleaseWarnings)
        previousShutterReleaseWarnings.removeAll()
    }

    func confirmPreviousRecordingStopped() {
        acknowledgedPreviousRecordingWarnings.formUnion(previousRecordingWarnings)
        previousRecordingWarnings.removeAll()
    }

    func confirmPreviousLiveViewStopped() {
        acknowledgedPreviousLiveViewWarnings.formUnion(previousLiveViewWarnings)
        previousLiveViewWarnings.removeAll()
    }

    private func preservePreviousLiveViewWarning(generation: UUID) {
        guard !acknowledgedPreviousLiveViewWarnings.contains(generation) else { return }
        previousLiveViewWarnings.insert(generation)
    }

    private func preservePreviousRecordingWarning(generation: UUID) {
        guard !acknowledgedPreviousRecordingWarnings.contains(generation) else { return }
        previousRecordingWarnings.insert(generation)
    }

    private func preservePreviousReleaseWarning(_ state: CameraShutterReleaseState, generation: UUID) {
        if state.releaseRequired || state.releaseUnconfirmed {
            guard !acknowledgedPreviousReleaseWarnings.contains(generation) else { return }
            previousShutterReleaseWarnings.insert(generation)
        } else {
            previousShutterReleaseWarnings.remove(generation)
        }
    }

    private func prepareDisconnect() -> CameraSession? {
        invalidateConnectionGuidance()
        foregroundMedia.retire()
        foregroundMediaOwner = UUID()
        foregroundMediaIOOwner = nil
        foregroundLiveViewRestartOwner = nil
        foregroundMediaTicket = nil
        foregroundTemporaryResults = []
        rtpController.retireDelivery()
        invalidateBridgeScan()
        retireFocusMarker()
        stopCameraFocusInfoLoop()
        releaseHeldAutofocus()
        heldAutofocusOwner = nil
        heldAutofocusTask = nil
        heldAutofocusPressed = false
        sessionGeneration = UUID()
        recordingReadbackRecovery = nil
        recordingCommandRecovery = nil
        pendingLiveViewStartTask?.cancel()
        pendingLiveViewStartTask = nil
        pendingLiveViewStartID = nil
        pendingBulbStartTask?.cancel()
        pendingBulbStartTask = nil
        pendingBulbStartID = nil
        operationRevision &+= 1
        shutterReleaseState = .idle
        liveViewStopState = .idle
        stopLiveViewLoop()
        cancelLiveViewFPSUpdate()
        stopEventLoop()
        resetMediaDownloadState()
        resetMediaUploadState()
        let closingSession = session
        session = nil
        invalidateMediaLibraryLoad()
        invalidateLatestMedia()
        snapshot = nil
        isPreview = false
        screen = .control
        activeSheet = nil
        password = ""
        bridgeToken = ""
        mediaItems = []
        latestMediaItem = nil
        latestMediaThumbnail = nil
        mediaLibraryHasMore = false
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
        shutterAutofocus = true
        shutterFlash = false
        lastClockSyncAt = nil
        lastCreatedDirectoryName = nil
        operatorConfirmedFeatures.removeAll()
        lastError = nil
        busyOperations.removeAll()
        resetLiveViewMetrics()
        return closingSession
    }

    func refresh() async {
        guard !busyOperations.contains(.recording), let session, begin(.refresh) else { return }
        let generation = sessionGeneration
        let revision = operationRevision
        let recordingRecoveryID = recordingRecovery?.commandID
        let wasReleaseRequired = shutterReleaseRequired
        defer { end(.refresh, generation: generation) }
        do {
            if let recordingRecoveryID {
                // Recovery is a status read only. Refreshing capabilities here
                // could clamp FPS and restart Live View while REC is uncertain.
                let status = try await session.status()
                let release = await session.shutterReleaseState()
                guard generation == sessionGeneration, revision == operationRevision,
                      recordingRecovery?.commandID == recordingRecoveryID else { return }
                snapshot = snapshot?.replacing(status: status)
                applyShutterReleaseState(release, pauseLiveView: false)
                lastError = nil
                settleRecordingReadback(status, expectedCommandID: recordingRecoveryID, session: session)
                return
            }
            let refreshed = try await session.connectSnapshot()
            let release = await session.shutterReleaseState()
            let liveViewStop = await session.liveViewStopState()
            guard generation == sessionGeneration, revision == operationRevision else { return }
            snapshot = refreshed
            liveViewStopState = liveViewStop
            if liveViewStop.stopUnconfirmed { activeLiveViewSource = nil; stopLiveViewLoop() }
            applyShutterReleaseState(release)
            if wasReleaseRequired, !shutterReleaseRequired {
                resumeLiveViewAfterBulb(session: session, generation: generation)
            }
            lastError = nil
            clampLiveViewRequest()
        } catch {
            guard generation == sessionGeneration, revision == operationRevision else { return }
            record(error)
        }
    }

    func setRequestedFPS(_ value: Int) {
        let limits = capabilities?.liveView
        let minimum = limits?.minimumFPS ?? 1
        let maximum = limits?.maximumFPS ?? 30
        let clampedFPS = min(max(value, minimum), maximum)
        guard clampedFPS != requestedFPS else { return }
        requestedFPS = clampedFPS
        defaults.set(requestedFPS, forKey: DefaultsKey.requestedFPS)
        scheduleLiveViewFPSUpdate()
    }

    private func scheduleLiveViewFPSUpdate() {
        cancelLiveViewFPSUpdate()
        guard !shutterReleaseRequired, let session, activeLiveViewSource != nil else {
            return
        }
        let fps = requestedFPS
        let revision = liveViewFPSUpdateRevision
        liveViewFPSUpdateTask = Task { [weak self] in
            do {
                try await Task.sleep(nanoseconds: 200_000_000)
            } catch {
                return
            }
            guard !Task.isCancelled,
                  let self,
                  self.liveViewFPSUpdateRevision == revision else { return }
            let update = await session.setLiveViewTargetFPS(fps)
            guard !Task.isCancelled,
                  self.liveViewFPSUpdateRevision == revision else { return }
            if update == .restartRequired, self.activeLiveViewSource != nil {
                await self.restartLiveView()
            }
            if self.liveViewFPSUpdateRevision == revision {
                self.liveViewFPSUpdateTask = nil
            }
        }
    }

    private func cancelLiveViewFPSUpdate() {
        liveViewFPSUpdateRevision &+= 1
        liveViewFPSUpdateTask?.cancel()
        liveViewFPSUpdateTask = nil
    }

    func setLiveViewSize(_ value: LiveViewSize) async {
        guard foregroundMediaIOOwner == nil, foregroundLiveViewRestartOwner == nil, !usesRTPLiveView,
              capabilities?.liveView.sizes.contains(value) == true else { return }
        liveViewSize = value
        defaults.set(value.rawValue, forKey: DefaultsKey.liveViewSize)
        if session != nil, autoRefresh { await restartLiveView() }
    }

    func setLiveViewSource(_ value: LiveViewSource) async {
        guard foregroundMediaIOOwner == nil, foregroundLiveViewRestartOwner == nil else { return }
        let available = capabilities?.liveView.sources ?? []
        guard value == .auto || available.contains(value) else { return }
        selectedLiveViewSource = value
        defaults.set(value.rawValue, forKey: DefaultsKey.liveViewSource)
        if session != nil { await restartLiveView() }
    }

    func setAutoRefresh(_ enabled: Bool) async {
        autoRefresh = enabled
        if !enabled { invalidateCameraFocusInfo() }
        let mayRender = enabled && applicationActive && !shutterReleaseRequired
        rtpController.setRenderingEnabled(mayRender)
        if mayRender {
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
        applicationActive = active
        rtpController.setApplicationActive(active)
        if !active {
            releaseHeldAutofocus()
            invalidateCameraFocusInfo()
            cancelMediaWorklistContinuation()
            if mediaBatchContinuationActive { cancelMediaBatchDownload() }
            foregroundMedia.stop(reason: .background)
            stopLiveViewLoop()
        } else if let session {
            resumeLiveViewAfterBulb(session: session, generation: sessionGeneration)
        }
    }

    func startLiveView() async {
        guard applicationActive, let session, supports(.liveView), liveViewTemperatureAllowed,
              !liveViewStopState.stopUnconfirmed, begin(.liveView) else { return }
        let generation = sessionGeneration
        let id = UUID()
        pendingLiveViewStartID = id
        let task = Task { await self.performLiveViewStart(session: session, generation: generation, id: id) }
        pendingLiveViewStartTask = task
        await withTaskCancellationHandler {
            await task.value
        } onCancel: { task.cancel() }
    }

    private func performLiveViewStart(session: CameraSession, generation: UUID, id: UUID) async {
        defer {
            end(.liveView, generation: generation)
            if pendingLiveViewStartID == id {
                pendingLiveViewStartID = nil
                pendingLiveViewStartTask = nil
            }
        }
        do {
            try await session.startLiveView(
                LiveViewRequest(fps: requestedFPS, size: liveViewSize, source: effectiveRequestedLiveViewSource())
            )
            let source = await session.currentLiveViewSource()
            let activeSize = await session.currentLiveViewSize()
            let nativeURL = await session.currentNativeLiveViewSourceURL()
            let stopState = await session.liveViewStopState()
            guard generation == sessionGeneration else { return }
            if Task.isCancelled {
                await session.stopLiveView()
                let cancelledState = await session.liveViewStopState()
                guard generation == sessionGeneration else { return }
                liveViewStopState = cancelledState
                activeLiveViewSource = nil
                return
            }
            guard !shutterReleaseRequired else { return }
            activeLiveViewSource = source
            startCameraFocusInfoLoop(session: session)
            liveViewStopState = stopState
            if let activeSize {
                liveViewSize = activeSize
                defaults.set(activeSize.rawValue, forKey: DefaultsKey.liveViewSize)
            }
            lastError = nil
            if activeLiveViewSource == .ccapiRTP {
                resetLiveViewMetrics()
                nativeLiveViewSize = nil
                liveViewData = nil
                frameContentType = "video/H264"
                frameSourceURL = nativeURL
                rtpController.setRenderingEnabled(autoRefresh && applicationActive)
            } else {
                rtpAudioRequested = false
                rtpAudioStatus = .inactive
                rtpController.setAudioEnabled(false)
                if autoRefresh { beginLiveViewLoop(session: session) }
            }
        } catch {
            let stopState = await session.liveViewStopState()
            guard generation == sessionGeneration else { return }
            liveViewStopState = stopState
            if stopState.stopUnconfirmed { activeLiveViewSource = nil; stopLiveViewLoop() }
            record(error)
        }
    }

    func restartLiveView() async {
        if liveViewStopState.stopUnconfirmed { await retryLiveViewStop(); return }
        guard !shutterReleaseRequired, foregroundMediaIOOwner == nil,
              foregroundLiveViewRestartOwner == nil else { return }
        let restartOwner = UUID()
        foregroundLiveViewRestartOwner = restartOwner
        defer {
            if foregroundLiveViewRestartOwner == restartOwner { foregroundLiveViewRestartOwner = nil }
        }
        let generation = sessionGeneration
        let pendingStart = pendingLiveViewStartTask
        // Own the interval before this App task reaches the Core client, too.
        pendingStart?.cancel()
        stopLiveViewLoop()
        if let session {
            do {
                try await session.retryLiveViewStop()
                await pendingStart?.value
            } catch {
                await pendingStart?.value
                let stopState = await session.liveViewStopState()
                guard generation == sessionGeneration else { return }
                liveViewStopState = stopState
                activeLiveViewSource = nil
                record(error)
                return
            }
        }
        guard generation == sessionGeneration, !shutterReleaseRequired else { return }
        activeLiveViewSource = nil
        liveViewStopState = .idle
        nativeLiveViewSize = nil
        await startLiveView()
    }

    /// Recovery is deliberately Stop-only; the user starts a new view separately.
    func retryLiveViewStop() async {
        guard let session, canRetryLiveViewStop, busyOperations.insert(.liveView).inserted else { return }
        // Cleanup remains available even while another shutter recovery blocks new commands.
        operationRevision &+= 1
        let generation = sessionGeneration
        defer { end(.liveView, generation: generation) }
        stopLiveViewLoop()
        do {
            try await session.retryLiveViewStop()
            let stopState = await session.liveViewStopState()
            guard generation == sessionGeneration else { return }
            liveViewStopState = stopState
            activeLiveViewSource = nil
            nativeLiveViewSize = nil
            lastError = nil
        } catch {
            let stopState = await session.liveViewStopState()
            guard generation == sessionGeneration else { return }
            liveViewStopState = stopState
            record(error)
        }
    }

    func captureStill() async {
        guard captureMode == .photo, !bulbMode, !recordingStopRequired,
              supports(.stillCapture), stillCaptureTemperatureAllowed else { return }
        guard shutterAutofocusAllowed else {
            lastError = NSLocalizedString("shutter_af_unavailable", comment: "")
            return
        }
        guard begin(.capture) else { return }
        let generation = sessionGeneration
        let autofocus = shutterAutofocus
        defer { end(.capture, generation: generation) }
        if isPreview {
            showShutterFlash()
            return
        }
        guard let session else { return }
        prepareCaptureMediaReview(kind: .photo)
        do {
            let captured = try await session.captureStill(autofocus: autofocus)
            guard generation == sessionGeneration else { return }
            let observation = shutterReleaseObservationRevision
            let release = await session.shutterReleaseState()
            guard generation == sessionGeneration else { return }
            if observation == shutterReleaseObservationRevision, !busyOperations.contains(.focus) {
                applyShortControlReleaseState(release, session: session, generation: generation)
                updateStatus(captured.withShutterReleaseState(release))
                lastError = nil
            }
            showShutterFlash()
            if let review = pendingCaptureReview {
                startLatestMediaRefresh(session: session, review: review)
            }
        } catch DesktopBridgeError.captureStatusReadbackFailed, CCAPIError.captureStatusReadbackFailed {
            guard generation == sessionGeneration else { return }
            // The command was acknowledged; keep this capture's existing read-only owner.
            // A status-read failure does not establish an exposure or justify replaying the shutter command.
            lastError = NSLocalizedString("capture_status_readback_failed", comment: "")
            if let review = pendingCaptureReview {
                startLatestMediaRefresh(session: session, review: review)
            }
        } catch {
            guard generation == sessionGeneration else { return }
            let observation = shutterReleaseObservationRevision
            let release = await session.shutterReleaseState()
            guard generation == sessionGeneration else { return }
            if observation == shutterReleaseObservationRevision, !busyOperations.contains(.focus) {
                applyShortControlReleaseState(release, session: session, generation: generation)
                record(error)
            }
            pendingCaptureReview = nil
            captureReviewState = .idle
        }
    }

    func retryCaptureMediaReview() {
        guard canRetryCaptureMediaReview, let session, let review = pendingCaptureReview else { return }
        startLatestMediaRefresh(session: session, review: review)
    }

    func openLatestMedia() async {
        guard let item = latestMediaItem, canOpenLatestMedia, !isBusy(.media) else { return }
        if captureReviewState == .available { captureReviewState = .idle }
        if !mediaItems.contains(where: { $0.id == item.id }) {
            mediaItems.insert(item, at: 0)
        }
        screen = .media
        await openMediaPreview(item)
    }

    func syncCameraClock() async {
        guard !isPreview, supports(.cameraClockSync), begin(.clock) else { return }
        let generation = sessionGeneration
        defer { end(.clock, generation: generation) }
        guard let session else { return }
        do {
            let status = try await session.syncCameraClock()
            guard generation == sessionGeneration else { return }
            updateStatus(status)
            lastClockSyncAt = Date()
            lastError = nil
        } catch {
            guard generation == sessionGeneration else { return }
            record(error)
        }
    }

    func createDirectory(name: String) async {
        guard !isPreview, supports(.directoryControl), begin(.directory) else { return }
        let generation = sessionGeneration
        defer { end(.directory, generation: generation) }
        guard let session else { return }
        do {
            let directoryName = try await session.createDirectory(name: name)
            guard generation == sessionGeneration else { return }
            lastCreatedDirectoryName = directoryName
            let capabilities = try await session.capabilities()
            guard generation == sessionGeneration else { return }
            if let snapshot {
                self.snapshot = CameraSnapshot(info: snapshot.info, status: snapshot.status, capabilities: capabilities)
            }
            lastError = nil
        } catch {
            guard generation == sessionGeneration else { return }
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
        let generation = sessionGeneration
        defer { end(.setting, generation: generation) }
        guard let session else { return }
        do {
            _ = try await session.setFileNaming(field: field, value: value)
            guard generation == sessionGeneration else { return }
            let capabilities = try await session.capabilities()
            guard generation == sessionGeneration else { return }
            if let snapshot {
                self.snapshot = CameraSnapshot(
                    info: snapshot.info,
                    status: snapshot.status,
                    capabilities: capabilities
                )
            }
            lastError = nil
        } catch {
            guard generation == sessionGeneration else { return }
            record(error)
        }
    }

    func sleepCamera() async {
        guard
            !isPreview,
            supports(.cameraSleep),
            !recordingStopRequired,
            !bulbExposureActive,
            busyOperations.isEmpty,
            begin(.power)
        else { return }
        let generation = sessionGeneration
        defer { end(.power, generation: generation) }
        guard let session else { return }

        let wasLiveViewActive = activeLiveViewSource != nil
        stopLiveViewLoop()
        stopEventLoop()
        resetMediaDownloadState()
        resetMediaUploadState()
        do {
            try await session.sleepCamera()
            guard generation == sessionGeneration else { return }
            await disconnect()
        } catch {
            let stopState = await session.liveViewStopState()
            guard generation == sessionGeneration else { return }
            liveViewStopState = stopState
            if stopState.stopUnconfirmed { activeLiveViewSource = nil }
            record(error)
            beginEventLoop(session: session)
            if wasLiveViewActive, !liveViewStopState.stopUnconfirmed { await startLiveView() }
        }
    }

    func cleanSensor(autoPowerOff: Bool) async {
        guard
            !isPreview,
            supports(.sensorCleaning),
            !recordingStopRequired,
            !bulbExposureActive,
            busyOperations.isEmpty,
            begin(.maintenance)
        else { return }
        let generation = sessionGeneration
        defer { end(.maintenance, generation: generation) }
        guard let session else { return }

        let wasLiveViewActive = activeLiveViewSource != nil
        stopLiveViewLoop()
        stopEventLoop()
        do {
            try await session.cleanSensor(autoPowerOff: autoPowerOff)
            let stopState = await session.liveViewStopState()
            guard generation == sessionGeneration else { return }
            liveViewStopState = stopState
            if autoPowerOff {
                await disconnect()
            } else {
                let refreshedSnapshot = try await session.connectSnapshot()
                guard generation == sessionGeneration else { return }
                snapshot = refreshedSnapshot
                clampLiveViewRequest()
                beginEventLoop(session: session)
                activeLiveViewSource = nil
                if wasLiveViewActive, !liveViewStopState.stopUnconfirmed { await startLiveView() }
                guard generation == sessionGeneration else { return }
                lastError = nil
            }
        } catch {
            let stopState = await session.liveViewStopState()
            guard generation == sessionGeneration else { return }
            liveViewStopState = stopState
            if stopState.stopUnconfirmed { activeLiveViewSource = nil }
            record(error)
            beginEventLoop(session: session)
            activeLiveViewSource = nil
            if wasLiveViewActive, !liveViewStopState.stopUnconfirmed { await startLiveView() }
        }
    }

    func toggleBulbExposure() async {
        if shutterReleaseRequired {
            await retryShutterRelease()
            return
        }
        guard bulbMode, supports(.bulbExposure), stillCaptureTemperatureAllowed,
              busyOperations.isEmpty, begin(.capture) else { return }
        let generation = sessionGeneration
        let startID = UUID()
        pendingBulbStartID = startID
        defer {
            // Stop takes over capture ownership before cancelling the pending Start.
            // Its cleanup, or a replacement connection, must not be unlocked here.
            if pendingBulbStartID == startID {
                pendingBulbStartID = nil
                pendingBulbStartTask = nil
                end(.capture, generation: generation)
            }
        }
        if isPreview {
            shutterReleaseState = CameraShutterReleaseState(
                releaseRequired: true, releaseUnconfirmed: false, bulbExposureActive: true
            )
            if let snapshot { updateStatus(snapshot.status.withBulbExposureActive(true)) }
            return
        }
        guard let session else { return }
        // The request may reach the camera even if its acknowledgement never reaches us.
        shutterReleaseState = CameraShutterReleaseState(
            releaseRequired: true, releaseUnconfirmed: true, bulbExposureActive: nil
        )
        pauseLiveViewForBulb()
        let startTask = Task { try await session.startBulbExposure() }
        pendingBulbStartTask = startTask
        do {
            let status = try await withTaskCancellationHandler {
                try await startTask.value
            } onCancel: {
                startTask.cancel()
            }
            let release = await session.shutterReleaseState()
            guard generation == sessionGeneration, pendingBulbStartID == startID else { return }
            applyShutterReleaseState(release)
            updateStatus(status.withShutterReleaseState(release))
            lastError = nil
        } catch {
            let release = await session.shutterReleaseState()
            guard generation == sessionGeneration, pendingBulbStartID == startID else { return }
            applyShutterReleaseState(release)
            if !release.releaseRequired {
                resumeLiveViewAfterBulb(session: session, generation: generation)
            }
            record(error)
        }
    }

    func retryShutterRelease() async {
        guard canRetryShutterRelease else { return }
        let completedKnownExposure = bulbExposureActive && !shutterReleaseUnconfirmed
        let generation = sessionGeneration
        let pendingStart = pendingBulbStartTask
        pendingBulbStartTask = nil
        pendingBulbStartID = nil
        busyOperations.insert(.capture)
        operationRevision &+= 1
        defer { end(.capture, generation: generation) }
        if isPreview {
            applyShutterReleaseState(.idle)
            if completedKnownExposure { showShutterFlash() }
            return
        }
        guard let session else { return }
        pauseLiveViewForBulb()
        do {
            try await session.retryShutterRelease(cancelling: pendingStart)
            guard generation == sessionGeneration else { return }
            // A release ACK is authoritative, even if the subsequent status GET fails.
            applyShutterReleaseState(.idle)
            if completedKnownExposure { showShutterFlash() }
            lastError = nil
            resumeLiveViewAfterBulb(session: session, generation: generation)
            do {
                let status = try await session.status()
                guard generation == sessionGeneration else { return }
                updateStatus(status)
            } catch {
                guard generation == sessionGeneration else { return }
                record(error)
            }
        } catch {
            let release = await session.shutterReleaseState()
            guard generation == sessionGeneration else { return }
            applyShutterReleaseState(release)
            record(error)
        }
    }

    var canStartHeldAutofocus: Bool {
        applicationActive && screen == .control && activeSheet == nil && !isPreview && session != nil
            && capabilities?.heldAutofocusSupported == true && heldAutofocusOwner == nil
            && busyOperations.isEmpty && !shutterReleaseRequired && !recordingStopRequired
            && foregroundMediaIOOwner == nil
    }

    func ownsHeldAutofocus(_ owner: UUID) -> Bool { heldAutofocusOwner == owner }

    @discardableResult
    func beginHeldAutofocus() -> UUID? {
        guard canStartHeldAutofocus, let session, begin(.focus) else { return nil }
        let owner = UUID()
        let generation = sessionGeneration
        heldAutofocusOwner = owner
        heldAutofocusPressed = true
        let task = Task { try await session.startHeldAutofocus(owner: owner) }
        heldAutofocusTask = task
        Task { [weak self] in
            let result = await task.result
            guard let self, generation == self.sessionGeneration, self.heldAutofocusOwner == owner else { return }
            let release = await session.shutterReleaseState()
            guard generation == self.sessionGeneration, self.heldAutofocusOwner == owner else { return }
            self.applyShutterReleaseState(release, pauseLiveView: false)
            if case .success = result, self.heldAutofocusPressed {
                let delay = self.heldAutofocusDelay
                self.heldAutofocusWatchdog = Task { [weak self] in
                    do {
                        if let delay { try await delay() }
                        else { try await Task.sleep(nanoseconds: 30_000_000_000) }
                    } catch { return }
                    guard !Task.isCancelled, let self, self.sessionGeneration == generation,
                          self.heldAutofocusOwner == owner, self.heldAutofocusPressed else { return }
                    self.releaseHeldAutofocus(owner: owner)
                }
            }
            if case let .failure(error) = result, self.heldAutofocusPressed {
                self.heldAutofocusPressed = false
                self.heldAutofocusOwner = nil
                self.heldAutofocusTask = nil
                self.end(.focus, generation: generation)
                if !CameraConnectionFailureReason.isCancellation(error) { self.record(error) }
            }
        }
        return owner
    }

    /// Synchronous retirement captures the original session before Disconnect replaces it.
    func releaseHeldAutofocus(owner requestedOwner: UUID? = nil) {
        guard let owner = heldAutofocusOwner, requestedOwner == nil || requestedOwner == owner,
              let session else { return }
        let generation = sessionGeneration
        let start = heldAutofocusTask
        heldAutofocusWatchdog?.cancel()
        heldAutofocusWatchdog = nil
        heldAutofocusPressed = false
        invalidateCameraFocusInfo()
        start?.cancel()
        Task { [weak self] in
            var failure: Error?
            if let start, case let .failure(error) = await start.result {
                if await session.shutterReleaseState().releaseRequired { failure = error }
            }
            if failure == nil {
                do { try await session.stopHeldAutofocus(owner: owner) } catch { failure = error }
            }
            let release = await session.shutterReleaseState()
            guard let self, generation == self.sessionGeneration, self.heldAutofocusOwner == owner else { return }
            self.heldAutofocusOwner = nil
            self.heldAutofocusTask = nil
            self.end(.focus, generation: generation)
            self.applyShortControlReleaseState(release, session: session, generation: generation)
            if let failure { self.record(failure) }
        }
    }

    private var canReadCameraFocusInfo: Bool {
        connected && !isPreview && applicationActive && screen == .control && activeSheet == nil && autoRefresh
            && activeLiveViewSource != nil && liveViewTemperatureAllowed && !liveViewStopState.stopUnconfirmed
            && !busyOperations.contains(.capture) && !busyOperations.contains(.liveView)
            && capabilities?.liveView.focusInfoSupported == true
            && (!shutterReleaseRequired || heldAutofocusPressed)
    }

    private func invalidateCameraFocusInfo() {
        focusInfoOwner = UUID()
        cameraFocusInfo = nil
        cameraFocusInfoAt = nil
        cameraFocusInfoError = false
    }

    private func stopCameraFocusInfoLoop() {
        focusInfoTask?.cancel()
        focusInfoReadTask?.cancel()
        focusInfoTask = nil
        invalidateCameraFocusInfo()
    }

    private func readCameraFocusInfo(session: CameraSession) async throws -> CameraFocusInfo? {
        let owner = UUID()
        let read = Task { try await session.liveViewFocusInfo() }
        focusInfoReadOwner = owner
        focusInfoReadTask = read
        defer {
            if focusInfoReadOwner == owner { focusInfoReadOwner = nil; focusInfoReadTask = nil }
        }
        // Keep the global reservation until settlement, even across a replacement
        // loop/session whose retired transport ignores cancellation.
        return try await withTaskCancellationHandler { try await read.value } onCancel: { read.cancel() }
    }

    private func startCameraFocusInfoLoop(session: CameraSession) {
        stopCameraFocusInfoLoop()
        guard capabilities?.liveView.focusInfoSupported == true else { return }
        let generation = sessionGeneration
        focusInfoTask = Task { [weak self] in
            guard let self else { return }
            while !Task.isCancelled, generation == sessionGeneration {
                let owner = focusInfoOwner
                var delay: UInt64 = 250_000_000
                if canReadCameraFocusInfo, focusInfoReadTask == nil {
                    do {
                        let info = try await readCameraFocusInfo(session: session)
                        guard !Task.isCancelled, generation == sessionGeneration else { return }
                        if info == nil { delay = 2_000_000_000 }
                        if owner == focusInfoOwner, canReadCameraFocusInfo {
                            cameraFocusInfo = info
                            cameraFocusInfoAt = info == nil ? nil : Date()
                            cameraFocusInfoError = false
                        }
                    } catch {
                        if Task.isCancelled || generation != sessionGeneration { return }
                        delay = 1_000_000_000
                        if owner == focusInfoOwner {
                            cameraFocusInfo = nil
                            cameraFocusInfoAt = nil
                            cameraFocusInfoError = true
                        }
                    }
                } else if cameraFocusInfo != nil { invalidateCameraFocusInfo() }
                do { try await Task.sleep(nanoseconds: delay) } catch { return }
            }
        }
    }

    func autofocus() async {
        guard supports(.autofocus), begin(.focus) else { return }
        let generation = sessionGeneration
        defer { end(.focus, generation: generation) }
        if isPreview {
            showFocusMarker(x: 0.5, y: 0.5, accepted: true)
            return
        }
        guard let session else { return }
        do {
            let status = try await session.autofocus()
            guard generation == sessionGeneration else { return }
            let observation = shutterReleaseObservationRevision
            let release = await session.shutterReleaseState()
            guard generation == sessionGeneration else { return }
            if observation == shutterReleaseObservationRevision, !busyOperations.contains(.capture) {
                applyShortControlReleaseState(release, session: session, generation: generation)
                updateStatus(status.withShutterReleaseState(release))
                lastError = nil
            }
            showFocusMarker(x: 0.5, y: 0.5, accepted: true)
        } catch {
            guard generation == sessionGeneration else { return }
            let observation = shutterReleaseObservationRevision
            let release = await session.shutterReleaseState()
            guard generation == sessionGeneration else { return }
            if observation == shutterReleaseObservationRevision, !busyOperations.contains(.capture) {
                applyShortControlReleaseState(release, session: session, generation: generation)
                record(error)
            }
            showFocusMarker(x: 0.5, y: 0.5, accepted: false)
        }
    }

    func halfPressShutter() async {
        guard supports(.shutterHalfPress), begin(.focus) else { return }
        let generation = sessionGeneration
        defer { end(.focus, generation: generation) }
        if isPreview {
            showFocusMarker(x: 0.5, y: 0.5, accepted: true)
            return
        }
        guard let session else { return }
        do {
            let status = try await session.halfPressShutter()
            guard generation == sessionGeneration else { return }
            let observation = shutterReleaseObservationRevision
            let release = await session.shutterReleaseState()
            guard generation == sessionGeneration else { return }
            if observation == shutterReleaseObservationRevision, !busyOperations.contains(.capture) {
                applyShortControlReleaseState(release, session: session, generation: generation)
                updateStatus(status.withShutterReleaseState(release))
                lastError = nil
            }
            showFocusMarker(x: 0.5, y: 0.5, accepted: true)
        } catch {
            guard generation == sessionGeneration else { return }
            let observation = shutterReleaseObservationRevision
            let release = await session.shutterReleaseState()
            guard generation == sessionGeneration else { return }
            if observation == shutterReleaseObservationRevision, !busyOperations.contains(.capture) {
                applyShortControlReleaseState(release, session: session, generation: generation)
                record(error)
            }
            showFocusMarker(x: 0.5, y: 0.5, accepted: false)
        }
    }

    func toggleRecording() async {
        guard !Task.isCancelled else { return }
        let wasRecording = recordingStopRequired
        guard (wasRecording || supports(.videoRecording)), begin(.recording) else { return }
        let generation = sessionGeneration
        defer { end(.recording, generation: generation) }
        guard wasRecording || movieRecordingTemperatureAllowed else { return }
        if isPreview {
            guard let snapshot else { return }
            self.snapshot = snapshot.replacing(status: snapshot.status.replacing(recording: !recording))
            return
        }
        guard let session else { return }
        if wasRecording, recordingRecovery == nil || pendingCaptureReview?.kind != .video {
            prepareCaptureMediaReview(kind: .video)
        }
        let commandID = UUID()
        do {
            let newStatus = wasRecording ? try await session.stopRecording() : try await session.startRecording()
            guard generation == sessionGeneration else { return }
            updateStatus(newStatus)
            lastError = nil
            recordingCommandRecovery = nil
            if newStatus.recording == nil {
                retainRecordingReadback(starting: !wasRecording, commandID: commandID)
            } else {
                recordingReadbackRecovery = nil
                if !wasRecording, newStatus.recording == false {
                    lastError = NSLocalizedString("recording_start_not_confirmed", comment: "")
                }
            }
            if wasRecording {
                // Review begins only after an explicit stopped status. Unknown
                // status keeps the bounded attempt for a later read-only refresh.
                if newStatus.recording == false, let review = pendingCaptureReview {
                    startLatestMediaRefresh(session: session, review: review)
                } else if newStatus.recording != nil {
                    pendingCaptureReview = nil
                    captureReviewState = .idle
                } else {
                    captureReviewState = .idle
                }
            }
        } catch {
            guard generation == sessionGeneration else { return }
            let evidence: CameraRecordingCommandEvidence?
            if let error = error as? CCAPIError, case let .recordingStatusReadbackFailed(starting) = error {
                evidence = .acknowledged(starting: starting)
            } else if let error = error as? DesktopBridgeError, case let .recordingStatusReadbackFailed(starting) = error {
                evidence = .acknowledged(starting: starting)
            } else if let error = error as? CCAPIError, case let .recordingCommandUnconfirmed(starting) = error {
                evidence = .unconfirmed(starting: starting)
            } else if let error = error as? DesktopBridgeError, case let .recordingCommandUnconfirmed(starting) = error {
                evidence = .unconfirmed(starting: starting)
            } else if error is CancellationError || (error as? URLError)?.code == .cancelled {
                // Cancellation remains cancellation in Core. Consult the exact
                // client's send-boundary evidence without replaying any request.
                evidence = await session.recordingCommandEvidence()
            } else {
                evidence = nil
            }
            guard generation == sessionGeneration else { return }
            switch evidence {
            case let .some(.acknowledged(starting)) where starting == !wasRecording:
                retainRecordingReadback(starting: starting, commandID: commandID)
            case let .some(.unconfirmed(starting)) where starting == !wasRecording:
                recordingReadbackRecovery = nil
                recordingCommandRecovery = RecordingReadbackRecovery(
                    sessionGeneration: generation, commandID: commandID, starting: starting
                )
                captureMode = .video
                record(error)
            default:
                record(error)
            }
            if wasRecording {
                if recordingRecovery == nil { pendingCaptureReview = nil }
                captureReviewState = .idle
            }
        }
    }

    private func retainRecordingReadback(starting: Bool, commandID: UUID) {
        recordingCommandRecovery = nil
        recordingReadbackRecovery = RecordingReadbackRecovery(
            sessionGeneration: sessionGeneration, commandID: commandID, starting: starting
        )
        captureMode = .video
        lastError = nil
    }

    private func settleRecordingReadback(_ status: CameraStatus, expectedCommandID: UUID?, session: CameraSession) {
        // Canonical CCAPI/Bridge status may merely echo the last ACK cache.
        // No boolean read can settle a command whose response was not received.
        guard recordingCommandRecovery == nil, let recovery = recordingReadbackRecovery,
              recovery.sessionGeneration == sessionGeneration,
              recovery.commandID == expectedCommandID,
              !busyOperations.contains(.recording), let recording = status.recording else { return }
        recordingReadbackRecovery = nil
        if recovery.starting, !recording {
            lastError = NSLocalizedString("recording_start_not_confirmed", comment: "")
        }
        if !recovery.starting, !recording, let review = pendingCaptureReview {
            startLatestMediaRefresh(session: session, review: review)
        } else if !recovery.starting {
            pendingCaptureReview = nil
            captureReviewState = .idle
        }
    }

    func tapFocus(x: Double, y: Double) async {
        guard supports(.tapFocus), begin(.focus) else { return }
        let generation = sessionGeneration
        defer { end(.focus, generation: generation) }
        let normalizedX = min(max(x, 0), 1)
        let normalizedY = min(max(y, 0), 1)
        if isPreview {
            showFocusMarker(x: normalizedX, y: normalizedY, accepted: true)
            return
        }
        guard let session else { return }
        do {
            let result = try await session.tapFocus(x: normalizedX, y: normalizedY)
            guard generation == sessionGeneration else { return }
            showFocusMarker(x: result.x, y: result.y, accepted: result.accepted)
            lastError = nil
        } catch {
            guard generation == sessionGeneration else { return }
            record(error)
            showFocusMarker(x: normalizedX, y: normalizedY, accepted: false)
        }
    }

    func clickWhiteBalance(x: Double, y: Double) async {
        guard supports(.clickWhiteBalance), begin(.setting) else { return }
        let generation = sessionGeneration
        defer { end(.setting, generation: generation) }
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
            let status = try await session.clickWhiteBalance(x: normalizedX, y: normalizedY)
            guard generation == sessionGeneration else { return }
            updateStatus(status)
            showFocusMarker(x: normalizedX, y: normalizedY, accepted: true)
            lastError = nil
        } catch {
            guard generation == sessionGeneration else { return }
            record(error)
            showFocusMarker(x: normalizedX, y: normalizedY, accepted: false)
        }
    }

    func driveFocus(direction: FocusDriveDirection, step: FocusDriveStep) async {
        guard supports(.focusDrive), begin(.focus) else { return }
        let generation = sessionGeneration
        defer { end(.focus, generation: generation) }
        if isPreview {
            showFocusMarker(x: direction == .near ? 0.4 : 0.6, y: 0.5, accepted: true)
            return
        }
        guard let session else { return }
        do {
            let result = try await session.driveFocus(direction: direction, step: step)
            guard generation == sessionGeneration else { return }
            showFocusMarker(x: result.direction == .near ? 0.4 : 0.6, y: 0.5, accepted: result.accepted)
            lastError = nil
        } catch {
            guard generation == sessionGeneration else { return }
            record(error)
        }
    }

    func setLiveViewMagnification(_ magnification: LiveViewMagnification) async {
        guard captureMode == .photo,
              let liveView = capabilities?.liveView,
              capabilities?.matrix.supports(.liveViewMagnification) == true,
              liveView.magnifications.contains(magnification),
              begin(.liveView) else { return }
        let generation = sessionGeneration
        defer { end(.liveView, generation: generation) }
        if isPreview {
            liveViewMagnification = magnification
            return
        }
        guard let session, activeLiveViewSource != nil else { return }
        do {
            let result = try await session.setLiveViewMagnification(magnification)
            guard generation == sessionGeneration else { return }
            if result.accepted {
                liveViewMagnification = result.magnification
            }
            lastError = nil
        } catch {
            guard generation == sessionGeneration else { return }
            record(error)
        }
    }

    func setCaptureMode(_ mode: AppCaptureMode) async {
        guard captureMode != mode,
              !recordingStopRequired,
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
        guard let setting = capabilities?.setting(key), setting.accepts(value), begin(.setting) else { return }
        let generation = sessionGeneration
        defer { end(.setting, generation: generation) }
        lastError = nil
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
            guard generation == sessionGeneration else { return }
            let capabilities = try await session.capabilities()
            guard generation == sessionGeneration else { return }
            if let snapshot {
                self.snapshot = CameraSnapshot(info: snapshot.info, status: status, capabilities: capabilities)
            }
            lastError = nil
        } catch {
            guard generation == sessionGeneration else { return }
            record(error)
        }
    }

    private func prepareCaptureMediaReview(kind: CaptureReviewKind) {
        // Retire any old list/thumbnail before awaiting the command. Snapshot only
        // loaded observations: neither a shot nor recording stop waits for a card scan.
        var previousIDs = observedMediaIDs.union(mediaItems.map(\.id))
        if let item = latestMediaItem { previousIDs.insert(item.id) }
        invalidateLatestMedia(clearItem: false)
        pendingCaptureReview = supports(.mediaBrowser)
            ? CaptureReviewAttempt(previousIDs: previousIDs, kind: kind) : nil
        captureReviewKind = kind
        captureReviewState = pendingCaptureReview == nil ? .idle : .capturing
    }

    private func startLatestMediaRefresh(
        session: CameraSession,
        review: CaptureReviewAttempt? = nil
    ) {
        guard !isPreview, supports(.mediaBrowser),
              review != nil || (pendingCaptureReview == nil && captureReviewState == .idle) else { return }
        invalidateLatestMedia(clearItem: false)
        let generation = latestMediaGeneration
        let previousReadFailed = captureReviewState == .readFailed
        captureReviewState = review == nil ? .idle : .searching
        latestMediaTask = Task { [weak self] in
            await self?.loadLatestMedia(
                session: session, review: review,
                previousReadFailed: previousReadFailed, generation: generation
            )
        }
    }

    private func loadLatestMedia(
        session: CameraSession,
        review: CaptureReviewAttempt?,
        previousReadFailed: Bool,
        generation: UUID
    ) async {
        var lastReadFailed = previousReadFailed
        defer {
            if generation == latestMediaGeneration {
                latestMediaTask = nil
                latestMediaThumbnailLoading = false
                if review != nil, captureReviewState == .searching {
                    captureReviewState = lastReadFailed ? .readFailed : .notReady
                }
            }
        }
        let attempts = review == nil ? 1 : Self.latestMediaRetryDelays.count + 1
        for attempt in 0..<attempts {
            guard generation == latestMediaGeneration, !Task.isCancelled else { return }
            // A later media operation can own the camera while this read is backing off.
            guard !isBusy(.media), !mediaLibraryLoading else { return }
            do {
                let items = try await session.listMedia(maximumItems: Self.latestMediaRequestItemCount)
                guard generation == latestMediaGeneration, !Task.isCancelled else { return }
                lastReadFailed = false
                observedMediaIDs.formUnion(items.map(\.id))
                let selected: CameraMediaItem?
                if let review {
                    selected = selectLatestMediaItem(
                        afterCaptureFrom: items, previousIDs: review.previousIDs, kind: review.kind
                    )
                } else {
                    selected = selectLatestMediaItem(items)
                }
                if let item = selected {
                    if review != nil {
                        pendingCaptureReview = nil
                        captureReviewState = .available
                    }
                    await publishLatestMedia(item, session: session, generation: generation)
                    return
                }
            } catch is CancellationError {
                return
            } catch {
                // An unread list is different from a successful list with no new item.
                // Keep this failure local to review; a later successful read clears it.
                lastReadFailed = true
            }

            guard attempt + 1 < attempts else { return }
            do {
                try await Task.sleep(nanoseconds: Self.latestMediaRetryDelays[attempt])
            } catch {
                return
            }
        }
    }

    private func publishLatestMedia(
        _ item: CameraMediaItem,
        session: CameraSession,
        generation: UUID
    ) async {
        guard generation == latestMediaGeneration, !Task.isCancelled else { return }
        latestMediaItem = item
        latestMediaThumbnail = nil
        latestMediaThumbnailLoading = supports(.mediaThumbnail)
        guard supports(.mediaThumbnail) else { return }
        do {
            let thumbnail = try await session.mediaThumbnail(item)
            guard generation == latestMediaGeneration, !Task.isCancelled else { return }
            latestMediaThumbnail = thumbnail.data.isEmpty ? nil : thumbnail.data
        } catch is CancellationError {
            return
        } catch {
            // A thumbnail is optional; the latest item remains available to the viewer.
        }
        if generation == latestMediaGeneration {
            latestMediaThumbnailLoading = false
        }
    }

    private func invalidateLatestMedia(clearItem: Bool = true) {
        latestMediaGeneration = UUID()
        latestMediaTask?.cancel()
        latestMediaTask = nil
        latestMediaThumbnailLoading = false
        if clearItem {
            latestMediaItem = nil
            latestMediaThumbnail = nil
            observedMediaIDs.removeAll()
            pendingCaptureReview = nil
            captureReviewState = .idle
            captureReviewKind = .photo
        }
    }

    func startMediaLibraryLoad() {
        guard supports(.mediaBrowser), !mediaLibraryLoading, !isBusy(.media), begin(.mediaLibrary) else { return }
        let scope = mediaLibraryScope
        let generation = UUID()
        mediaLibraryGeneration = generation
        mediaLibraryLoading = true
        mediaLibraryLoadCancellable = true
        mediaLibraryLoadStatus = .loading
        deletedMediaName = nil
        resetMediaThumbnails()
        resetMediaPreview()
        if isPreview {
            mediaItems = Self.previewMedia
            mediaLibraryHasMore = false
            finishMediaLibraryLoad(generation: generation)
            return
        }
        guard let session else {
            mediaLibraryLoadStatus = .failed
            finishMediaLibraryLoad(generation: generation)
            return
        }
        mediaLibraryTask = Task { [weak self] in
            await self?.performMediaLibraryLoad(session: session, scope: scope, generation: generation)
        }
    }

    func setMediaLibraryScope(_ scope: MediaLibraryScope) {
        guard scope != mediaLibraryScope else { return }
        let previousItems = mediaItems
        let canReuseCompleteLibrary = scope == .recent && mediaLibraryLoadStatus == .complete
        if mediaLibraryLoading { invalidateMediaLibraryLoad() }
        else { mediaLibraryGeneration = UUID() }
        mediaLibraryScope = scope
        resetMediaThumbnails()
        resetMediaPreview()

        if isPreview {
            mediaItems = Self.previewMedia
            mediaLibraryHasMore = false
            mediaLibraryLoadStatus = .complete
            return
        }
        if canReuseCompleteLibrary {
            applyMediaBatch(previousItems, scope: scope)
            mediaLibraryLoadStatus = .complete
            return
        }

        mediaLibraryHasMore = false
        mediaLibraryLoadStatus = .notLoaded
        if connected, supports(.mediaBrowser) { startMediaLibraryLoad() }
    }

    func cancelMediaLibraryLoad() {
        guard mediaLibraryLoading else { return }
        invalidateMediaLibraryLoad(status: .cancelled)
    }

    private func performMediaLibraryLoad(
        session: CameraSession,
        scope: MediaLibraryScope,
        generation: UUID
    ) async {
        defer { finishMediaLibraryLoad(generation: generation) }
        do {
            let items = try await session.listMedia(maximumItems: maximumMediaItems(for: scope)) { [weak self] partialItems in
                await self?.applyMediaListProgress(partialItems, scope: scope, generation: generation)
            }
            guard generation == mediaLibraryGeneration, !Task.isCancelled else { return }
            applyMediaBatch(items, scope: scope)
            mediaLibraryLoadStatus = .complete
            lastError = nil
        } catch is CancellationError {
            if generation == mediaLibraryGeneration {
                mediaLibraryLoadStatus = .cancelled
            }
        } catch {
            guard generation == mediaLibraryGeneration else { return }
            retireBridgeMediaEventDemand()
            mediaLibraryLoadStatus = .failed
            record(error)
        }
    }

    var mediaListingDefersThumbnails: Bool {
        guard let session, case .desktopBridge = session else { return false }
        return mediaLibraryLoading || mediaLibraryLoadStatus != .complete
    }

    func loadMediaThumbnail(_ item: CameraMediaItem) async {
        if mediaThumbnailCache.touch(item.id) {
            mediaThumbnails = mediaThumbnailCache.values
            return
        }
        guard
            !isPreview,
            !mediaListingDefersThumbnails,
            foregroundMediaIOOwner == nil,
            supports(.mediaThumbnail),
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
            mediaThumbnailCache.insert(thumbnail.data, for: item.id)
            mediaThumbnails = mediaThumbnailCache.values
        } catch is CancellationError {
            return
        } catch {
            if generation == mediaThumbnailGeneration {
                unavailableMediaThumbnailIDs.insert(item.id)
            }
        }
    }

    func openMediaPreview(_ item: CameraMediaItem) async {
        let video = mediaIsVideo(item)
        guard !isPreview, let session,
              video ? supports(.mediaDownload) : item.previewAvailable && supports(.mediaPreview),
              begin(.media) else { return }
        if case .desktopBridge = session, mediaLibraryLoading {
            invalidateMediaLibraryLoad(status: .cancelled)
        }
        resetMediaPreview()
        let token = UUID()
        let generation = sessionGeneration
        mediaPreviewToken = token
        // Image GETs can be cancelled without losing a server-side allocation.
        // Keep video allocation in the caller: a late ticket still needs close().
        let imageTask: Task<CameraMediaPreview, Error>? = video ? nil : Task {
            try Task.checkCancellation()
            let preview = try await session.mediaPreview(item)
            try Task.checkCancellation()
            return preview
        }
        mediaPreviewImageTask = imageTask
        mediaPreviewItem = item
        mediaPreviewLoading = true
        defer {
            if generation == sessionGeneration, mediaPreviewToken == token {
                mediaPreviewToken = nil
                mediaPreviewImageTask = nil
                mediaPreviewLoading = false
                end(.media)
            }
        }
        do {
            if video {
                let playbackStream = try await session.beginMediaPlayback(item)
                guard generation == sessionGeneration, mediaPreviewToken == token else {
                    await playbackStream.close()
                    return
                }
                let playbackItem = playbackStream.item
                mediaPreviewItem = playbackItem
                applyUpdatedMedia(playbackItem)
                mediaVideoPlayback = CameraMediaPlayback(item: playbackItem, session: session, playbackStream: playbackStream)
            } else if let imageTask {
                let preview = try await withTaskCancellationHandler {
                    try await imageTask.value
                } onCancel: {
                    imageTask.cancel()
                }
                try Task.checkCancellation()
                guard generation == sessionGeneration, mediaPreviewToken == token else { return }
                mediaPreviewData = preview.data
            }
            lastError = nil
        } catch {
            guard generation == sessionGeneration, mediaPreviewToken == token else { return }
            if imageTask != nil, Task.isCancelled || imageTask?.isCancelled == true { return }
            record(error)
        }
    }

    func retryMediaPreview() async {
        guard canRetryMediaPreview, let item = mediaPreviewItem else { return }
        await openMediaPreview(item)
    }

    func closeMediaPreview() {
        resetMediaPreview()
    }

    func loadMediaInfo(_ item: CameraMediaItem) async {
        guard !isPreview, !mediaLibraryLoading, supports(.mediaBrowser), begin(.media) else { return }
        let generation = sessionGeneration
        defer { end(.media, generation: generation) }
        guard let session else { return }
        do {
            let updated = try await session.mediaInfo(item)
            guard generation == sessionGeneration else { return }
            applyUpdatedMedia(updated)
            lastError = nil
        } catch {
            guard generation == sessionGeneration else { return }
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
                protected: enabled, rating: item.rating, rotationDegrees: item.rotationDegrees,
                archived: item.archived, contentType: item.contentType,
                widthPixels: item.widthPixels, heightPixels: item.heightPixels, folder: item.folder
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
                protected: item.protected, rating: rating, rotationDegrees: item.rotationDegrees,
                archived: item.archived, contentType: item.contentType,
                widthPixels: item.widthPixels, heightPixels: item.heightPixels, folder: item.folder
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
                protected: item.protected, rating: item.rating, rotationDegrees: degrees,
                archived: item.archived, contentType: item.contentType,
                widthPixels: item.widthPixels, heightPixels: item.heightPixels, folder: item.folder
            )
        }
    }

    private func updateMediaMetadata(
        _ item: CameraMediaItem,
        feature: CameraFeature,
        update: (CameraSession) async throws -> CameraMediaItem,
        preview: () -> CameraMediaItem
    ) async {
        guard !mediaLibraryLoading, supports(feature), begin(.media) else { return }
        let generation = sessionGeneration
        defer { end(.media, generation: generation) }
        if isPreview {
            applyUpdatedMedia(preview())
            lastError = nil
            return
        }
        guard let session else { return }
        do {
            let updated = try await update(session)
            guard generation == sessionGeneration else { return }
            applyUpdatedMedia(updated)
            lastError = nil
        } catch {
            guard generation == sessionGeneration else { return }
            record(error)
        }
    }

    func startMediaDownload(_ item: CameraMediaItem) {
        #if DEBUG
        traceMediaDownload(.request)
        #endif
        guard supports(.mediaDownload) else {
            #if DEBUG
            traceMediaDownload(.rejectedUnsupported)
            #endif
            return
        }
        guard begin(.media) else {
            #if DEBUG
            traceMediaDownload(.rejectedBusy)
            #endif
            return
        }
        #if DEBUG
        traceMediaDownload(.started)
        #endif
        let token = UUID()
        mediaDownloadToken = token
        mediaDownloadCancellationRequested = false
        let generation = sessionGeneration
        let historyGeneration = mediaDeliveryClearGeneration
        activeMediaDownloadID = item.id
        mediaDownloadProgress = CameraMediaTransferProgress(
            bytesTransferred: 0,
            totalBytes: item.sizeBytes
        )
        deletedMediaName = nil
        mediaDownloadTask = Task { [weak self] in
            await self?.performMediaDownload(item, token: token, generation: generation,
                                             historyGeneration: historyGeneration)
        }
    }

    func cancelMediaDownload() {
        guard mediaDownloadToken != nil else { return }
        mediaDownloadCancellationRequested = true
        mediaDownloadTask?.cancel()
        // Keep MEDIA owned until an uncooperative request or store reply settles.
    }

    func isMediaSelected(_ item: CameraMediaItem) -> Bool {
        selectedMediaItems.contains { mediaItemIdentity($0) == mediaItemIdentity(item) }
    }

    func toggleMediaSelection(_ item: CameraMediaItem) {
        let identity = mediaItemIdentity(item)
        if let index = selectedMediaItems.firstIndex(where: { mediaItemIdentity($0) == identity }) {
            selectedMediaItems.remove(at: index)
        } else if let loaded = mediaItems.first(where: { mediaItemIdentity($0) == identity }) {
            selectedMediaItems.append(loaded)
        }
    }

    /// The caller supplies its displayed subset. This never requests more media.
    func selectMediaItems(_ displayedLoadedItems: [CameraMediaItem]) {
        var identities = Set(selectedMediaItems.map(mediaItemIdentity))
        var loadedByID: [Data: CameraMediaItem] = [:]
        for item in mediaItems { loadedByID[mediaItemIdentity(item)] = item }
        for displayed in displayedLoadedItems {
            let identity = mediaItemIdentity(displayed)
            guard let loaded = loadedByID[identity], identities.insert(identity).inserted else { continue }
            selectedMediaItems.append(loaded)
        }
    }

    func clearMediaSelection() { selectedMediaItems = [] }

    var canStartForegroundMedia: Bool {
        connected && !isPreview && connectionMode == .ccapi && session != nil
            && applicationActive && mediaDeliveryStore != nil && !foregroundMedia.isRunning
            && supports(.mediaBrowser) && supports(.mediaDownload) && !shutterReleaseRequired
    }

    var foregroundTemporaryCount: Int { foregroundTemporaryResults.filter { $0.completedFileURL != nil }.count }

    func makeForegroundMediaExportPayload() -> MediaBatchExportPayload? {
        MediaBatchExportPayload(results: foregroundTemporaryResults)
    }

    @discardableResult
    func startForegroundMedia() -> Bool {
        guard canStartForegroundMedia, let session else { return false }
        let owner = UUID()
        let generation = sessionGeneration
        foregroundMediaOwner = owner
        foregroundMediaTicket = nil
        foregroundTemporaryResults = []
        return foregroundMedia.start(.init(
            admission: { [weak self] in self?.foregroundAdmission(owner, generation: generation) ?? .retired },
            available: { await session.foregroundMediaReadsAvailable() },
            inventory: { [weak self] in
                guard let self else { throw ForegroundMediaController.Failure.retired }
                try self.beginForegroundRead(owner, generation: generation)
                defer { self.endForegroundRead(owner, generation: generation) }
                let result = try await session.foregroundMediaInventory()
                try self.checkForegroundOwner(owner, generation: generation)
                return result
            },
            freshInfo: { [weak self] item in
                guard let self else { throw ForegroundMediaController.Failure.retired }
                try self.beginForegroundRead(owner, generation: generation)
                defer { self.endForegroundRead(owner, generation: generation) }
                let result = try await session.foregroundMediaInfo(item)
                try self.checkForegroundOwner(owner, generation: generation)
                return result
            },
            prepare: { [weak self] item, bytes in
                guard let self else { throw ForegroundMediaController.Failure.retired }
                try await self.prepareForegroundOriginal(item, bytes: bytes, session: session,
                                                         owner: owner, generation: generation)
            }
        ))
    }

    func stopForegroundMedia() { foregroundMedia.stop() }

    private func foregroundAdmission(_ owner: UUID, generation: UUID) -> ForegroundMediaController.Admission {
        guard owner == foregroundMediaOwner, generation == sessionGeneration,
              applicationActive, connected, !isPreview,
              supports(.mediaBrowser), supports(.mediaDownload) else { return .retired }
        if !busyOperations.isEmpty || foregroundLiveViewRestartOwner != nil
            || recordingStopRequired || shutterReleaseRequired || bulbExposureActive
            || mediaLibraryLoading || mediaPreviewLoading || latestMediaTask != nil || latestMediaThumbnailLoading
            || !loadingMediaThumbnailIDs.isEmpty || captureReviewState == .searching {
            return .busy
        }
        return .ready
    }

    private func checkForegroundOwner(_ owner: UUID, generation: UUID) throws {
        try Task.checkCancellation()
        guard owner == foregroundMediaOwner, generation == sessionGeneration, applicationActive else {
            throw ForegroundMediaController.Failure.retired
        }
    }

    private func beginForegroundRead(_ owner: UUID, generation: UUID) throws {
        try checkForegroundOwner(owner, generation: generation)
        guard case .ready = foregroundAdmission(owner, generation: generation), begin(.media) else {
            throw ForegroundMediaController.Failure.unavailable
        }
        foregroundMediaIOOwner = owner
    }

    private func endForegroundRead(_ owner: UUID, generation: UUID) {
        guard foregroundMediaIOOwner == owner, foregroundMediaOwner == owner,
              sessionGeneration == generation else { return }
        foregroundMediaIOOwner = nil
        end(.media)
    }

    private func prepareForegroundOriginal(
        _ item: CameraMediaItem, bytes: Int64, session: CameraSession, owner: UUID, generation: UUID
    ) async throws {
        try beginForegroundRead(owner, generation: generation)
        defer { endForegroundRead(owner, generation: generation) }
        guard let mediaDeliveryStore else { throw ForegroundMediaController.Failure.storage }
        let ticket: MediaDeliveryTicket
        do {
            if let existing = foregroundMediaTicket { ticket = existing }
            else {
                ticket = try await mediaDeliveryStore.beginBatch()
                try checkForegroundOwner(owner, generation: generation)
                foregroundMediaTicket = ticket
            }
        } catch {
            try checkForegroundOwner(owner, generation: generation)
            throw ForegroundMediaController.Failure.storage
        }
        let receipt: UUID
        do {
            receipt = try await mediaDeliveryStore.beginItem(ticket, filename: item.name, expectedBytes: bytes)
        } catch {
            try checkForegroundOwner(owner, generation: generation)
            throw ForegroundMediaController.Failure.storage
        }
        var validOriginal = false
        do {
            await mediaDeliveryReplyDelay?(.itemStarted)
            try checkForegroundOwner(owner, generation: generation)
            let owned = try MediaBatchOwnedFile.create(filename: item.name)
            let result = try await session.downloadForegroundOriginal(item, expectedBytes: bytes, to: owned.fileURL) { _ in }
            try checkForegroundOwner(owner, generation: generation)
            guard result.fileURL == owned.fileURL, result.bytesTransferred == bytes,
                  (try owned.fileURL.resourceValues(forKeys: [.isRegularFileKey])).isRegularFile == true else {
                throw ForegroundMediaController.Failure.transfer
            }
            validOriginal = true
            var temporary = MediaBatchResult(item: item)
            temporary.ownedFile = owned
            temporary.status = .ready
            foregroundTemporaryResults = [temporary]
            do {
                try await mediaDeliveryStore.finishPrepared(ticket, receiptID: receipt, source: owned, actualBytes: bytes)
                await mediaDeliveryReplyDelay?(.originalRetained)
            } catch {
                if foregroundMediaOwner == owner, sessionGeneration == generation {
                    mediaDeliveryRecordingWarning = true
                    mediaDeliveryHistoryWarning = true
                }
                throw ForegroundMediaController.Failure.storage
            }
            // The durable copy now supplies future explicit shares. Keep at most
            // one valid temporary fallback when retention fails, not two copies
            // of every successful automatically prepared original.
            if foregroundMediaOwner == owner, sessionGeneration == generation {
                foregroundTemporaryResults = []
                await refreshMediaDeliveryHistory()
            }
        } catch {
            if !validOriginal {
                let cancelled = Task.isCancelled || error is CancellationError || (error as? URLError)?.code == .cancelled
                try? await mediaDeliveryStore.finishFailure(ticket, receiptID: receipt, cancelled: cancelled)
            }
            // finishPrepared already records an honest prepared-but-not-retained
            // fact on failure. Do not replace that true fact with downloadFailed.
            throw error
        }
    }

    var canBeginMediaWorklistContinuation: Bool {
        !isPreview && applicationActive && connected && session != nil && mediaDeliveryStore != nil
            && !mediaLibraryLoading && !mediaDeliveryHistoryLoading
            && supports(.mediaDownload) && !isBusy(.media) && !shutterReleaseRequired
            && mediaDeliverySnapshot.warning == nil && mediaDeliverySnapshot.epoch != nil
    }

    /// Selection is explicit and carries no reconstructed camera identity.
    @discardableResult
    func beginMediaWorklistContinuation(origins: [MediaDeliveryOrigin]) -> Bool {
        guard canBeginMediaWorklistContinuation, let epoch = mediaDeliverySnapshot.epoch,
              !origins.isEmpty, Set(origins).count == origins.count,
              Set(origins.map(\.batchID)).count == 1 else { return false }
        var rows: [MediaWorklistContinuationRow] = []
        for origin in origins {
            guard let batch = mediaDeliverySnapshot.plannedBatches.first(where: { $0.id == origin.batchID }),
                  let row = batch.rows.first(where: { $0.id == origin.rowID }), row.canContinue else { return false }
            rows.append(MediaWorklistContinuationRow(origin: origin, filename: row.filename,
                status: row.receipt?.status, requiresUnknownAcknowledgement: row.requiresUnknownAcknowledgement))
        }
        mediaWorklistContinuationUnavailable = false
        mediaWorklistContinuation = MediaWorklistContinuationDraft(epoch: epoch,
            sessionGeneration: sessionGeneration, librarySessionID: mediaLibrarySessionID,
            libraryGeneration: mediaLibraryGeneration, historyGeneration: mediaDeliveryClearGeneration, rows: rows)
        return true
    }

    private func ownsMediaWorklistDraft(_ draft: MediaWorklistContinuationDraft) -> Bool {
        applicationActive && draft.sessionGeneration == sessionGeneration && draft.librarySessionID == mediaLibrarySessionID
            && draft.libraryGeneration == mediaLibraryGeneration && draft.historyGeneration == mediaDeliveryClearGeneration
            && draft.epoch == mediaDeliverySnapshot.epoch
    }

    @discardableResult
    func bindMediaWorklistOriginal(origin: MediaDeliveryOrigin, identity: Data?) -> Bool {
        guard var draft = mediaWorklistContinuation, ownsMediaWorklistDraft(draft),
              let index = draft.rows.firstIndex(where: { $0.origin == origin }) else { return false }
        if let identity {
            // Resolve only an explicit picker choice in the current loaded list.
            // Never search old filename, size, date, URL or a previous session.
            guard let item = mediaItems.first(where: { mediaItemIdentity($0) == identity }),
                  !draft.rows.enumerated().contains(where: { offset, row in
                      offset != index && row.item.map(mediaItemIdentity) == identity
                  }) else { return false }
            draft.rows[index].item = item
        } else { draft.rows[index].item = nil }
        mediaWorklistContinuation = draft
        return true
    }

    func cancelMediaWorklistContinuation() {
        mediaWorklistContinuation = nil
    }

    var canConfirmMediaWorklistContinuation: Bool {
        guard canBeginMediaWorklistContinuation, let draft = mediaWorklistContinuation,
              ownsMediaWorklistDraft(draft), !draft.rows.isEmpty else { return false }
        let loaded = Set(mediaItems.map(mediaItemIdentity))
        let bound = draft.rows.compactMap { $0.item.map(mediaItemIdentity) }
        return bound.count == draft.rows.count && Set(bound).count == bound.count
            && bound.allSatisfy { loaded.contains($0) }
    }

    /// The UI confirms every mapping and the existing prepare-then-share meaning
    /// before calling this. Durable admission is mandatory for continuation.
    @discardableResult
    func confirmMediaWorklistContinuation(confirmedMappingAndShare: Bool, acknowledgedUnknown: Bool) -> Bool {
        guard confirmedMappingAndShare, canConfirmMediaWorklistContinuation,
              let draft = mediaWorklistContinuation,
              !draft.requiresUnknownAcknowledgement || acknowledgedUnknown,
              let session, begin(.media) else { return false }
        let results = draft.rows.compactMap { $0.item.map { MediaBatchResult(item: $0) } }
        let token = UUID()
        mediaWorklistContinuation = nil // This authorization can be consumed once.
        mediaBatchContinuationActive = true
        mediaBatchToken = token
        mediaBatchItemToken = nil
        mediaBatchResults = results
        mediaBatchProgress = nil
        mediaBatchCancellationRequested = false
        mediaDeliveryRecordingWarning = false
        mediaBatchWorklistWarning = false
        mediaWorklistContinuationUnavailable = false
        mediaBatchState = .running
        mediaBatchTask = Task { [weak self] in
            await self?.performMediaBatchDownload(results, session: session, token: token,
                generation: draft.sessionGeneration, continuation: draft, acknowledgedUnknown: acknowledgedUnknown)
        }
        return true
    }

    var canStartMediaBatchDownload: Bool {
        !isPreview && connected && session != nil && !selectedMediaItems.isEmpty
            && !mediaLibraryLoading && supports(.mediaDownload) && !isBusy(.media)
            && !shutterReleaseRequired
    }

    @discardableResult
    func startMediaBatchDownload() -> Bool {
        guard canStartMediaBatchDownload, let session, begin(.media) else { return false }
        cancelMediaWorklistContinuation()
        mediaBatchContinuationActive = false
        let token = UUID()
        let generation = sessionGeneration
        // Selection, metadata, session object and generation are frozen together.
        let results = selectedMediaItems.map { MediaBatchResult(item: $0) }
        mediaBatchToken = token
        mediaBatchItemToken = nil
        mediaBatchResults = results
        mediaBatchProgress = nil
        mediaBatchCancellationRequested = false
        mediaDeliveryRecordingWarning = mediaDeliveryStore == nil
        mediaBatchWorklistWarning = mediaDeliveryStore == nil
        mediaDeliveryHistoryWarning = mediaDeliveryRecordingWarning || mediaDeliverySnapshot.warning != nil
        mediaBatchState = .running
        mediaBatchTask = Task { [weak self] in
            await self?.performMediaBatchDownload(results, session: session, token: token, generation: generation)
        }
        return true
    }

    func cancelMediaBatchDownload() {
        guard mediaBatchToken != nil else { return }
        mediaBatchCancellationRequested = true
        mediaBatchItemToken = nil
        mediaBatchTask?.cancel()
        // Keep MEDIA owned until a non-cooperative request has actually settled.
    }

    func makeMediaBatchExportPayload() -> MediaBatchExportPayload? {
        MediaBatchExportPayload(results: mediaBatchResults)
    }

    /// History belongs to this App installation, independently of any camera session.
    func refreshMediaDeliveryHistory() async {
        let owner = UUID()
        mediaDeliveryHistoryOwner = owner
        mediaDeliveryHistoryLoading = true
        defer { if mediaDeliveryHistoryOwner == owner { mediaDeliveryHistoryLoading = false } }
        guard let mediaDeliveryStore else {
            mediaDeliveryHistoryWarning = true
            return
        }
        let snapshot = await mediaDeliveryStore.snapshot()
        await mediaDeliveryReplyDelay?(.snapshot)
        guard mediaDeliveryHistoryOwner == owner else { return }
        mediaDeliverySnapshot = snapshot
        mediaDeliveryHistoryWarning = mediaDeliveryRecordingWarning || snapshot.warning != nil
    }

    func makeStoredMediaExportPayload(ids: [UUID]) async -> MediaBatchExportPayload? {
        guard !mediaDeliveryHistoryLoading, let mediaDeliveryStore else { return nil }
        let owner = UUID()
        mediaDeliveryHistoryOwner = owner
        mediaDeliveryHistoryLoading = true
        defer { if mediaDeliveryHistoryOwner == owner { mediaDeliveryHistoryLoading = false } }
        do {
            let files = try await mediaDeliveryStore.acquireShare(receiptIDs: ids)
            await mediaDeliveryReplyDelay?(.shareAcquired)
            guard mediaDeliveryHistoryOwner == owner else { return nil }
            return MediaBatchExportPayload(storedFiles: files)
        } catch {
            guard mediaDeliveryHistoryOwner == owner else { return nil }
            let snapshot = await mediaDeliveryStore.snapshot()
            guard mediaDeliveryHistoryOwner == owner else { return nil }
            mediaDeliveryHistoryWarning = true
            mediaDeliverySnapshot = snapshot
            return nil
        }
    }

    /// Called only after the UI confirms removal of records and retained App copies.
    func clearMediaDeliveryHistory() async {
        guard !mediaDeliveryHistoryLoading, let mediaDeliveryStore else { return }
        foregroundMedia.stop()
        cancelMediaWorklistContinuation()
        if mediaBatchContinuationActive { cancelMediaBatchDownload() }
        mediaDeliveryClearGeneration = UUID()
        let owner = UUID()
        mediaDeliveryHistoryOwner = owner
        mediaDeliveryHistoryLoading = true
        defer { if mediaDeliveryHistoryOwner == owner { mediaDeliveryHistoryLoading = false } }
        do {
            try await mediaDeliveryStore.clear()
            guard mediaDeliveryHistoryOwner == owner else { return }
            mediaDeliveryRecordingWarning = false
            mediaBatchWorklistWarning = false
        } catch {
            guard mediaDeliveryHistoryOwner == owner else { return }
            mediaDeliveryRecordingWarning = true
        }
        let snapshot = await mediaDeliveryStore.snapshot()
        await mediaDeliveryReplyDelay?(.snapshot)
        guard mediaDeliveryHistoryOwner == owner else { return }
        mediaDeliverySnapshot = snapshot
        mediaDeliveryHistoryWarning = mediaDeliveryRecordingWarning || snapshot.warning != nil
    }

    private func ownsMediaBatch(token: UUID, generation: UUID) -> Bool {
        mediaBatchToken == token && sessionGeneration == generation
    }

    private func performMediaBatchDownload(
        _ results: [MediaBatchResult], session: CameraSession, token: UUID, generation: UUID,
        continuation: MediaWorklistContinuationDraft? = nil, acknowledgedUnknown: Bool = false
    ) async {
        guard ownsMediaBatch(token: token, generation: generation) else { return }
        defer {
            if ownsMediaBatch(token: token, generation: generation) {
                mediaBatchTask = nil
                mediaBatchToken = nil
                mediaBatchItemToken = nil
                mediaBatchProgress = nil
                mediaBatchContinuationActive = false
                end(.media)
            }
        }
        func checkContinuationOwner() throws {
            try Task.checkCancellation()
            guard ownsMediaBatch(token: token, generation: generation), !mediaBatchCancellationRequested else {
                throw CancellationError()
            }
            if let continuation {
                guard ownsMediaWorklistDraft(continuation),
                      continuation.rows.allSatisfy({ row in
                          guard let item = row.item else { return false }
                          return mediaItems.contains { mediaItemIdentity($0) == mediaItemIdentity(item) }
                      }) else { throw CancellationError() }
            }
        }
        var deliveryTicket: MediaDeliveryTicket?
        var plannedRowIDs: [UUID]?
        if let mediaDeliveryStore {
            do {
                try checkContinuationOwner()
                let plan: MediaDeliveryPlannedBatch
                if let continuation {
                    plan = try await mediaDeliveryStore.beginContinuationBatch(
                        origins: continuation.rows.map(\.origin), filenames: results.map { $0.item.name },
                        expectedEpoch: continuation.epoch, acknowledgedUnknown: acknowledgedUnknown)
                } else {
                    plan = try await mediaDeliveryStore.beginPlannedBatch(filenames: results.map { $0.item.name })
                }
                deliveryTicket = plan.ticket
                plannedRowIDs = plan.rowIDs
                await mediaDeliveryReplyDelay?(.batchPlanned)
            }
            catch {
                if ownsMediaBatch(token: token, generation: generation) {
                    mediaDeliveryRecordingWarning = true
                    mediaDeliveryHistoryWarning = true
                    mediaBatchWorklistWarning = true
                    if continuation != nil {
                        mediaWorklistContinuationUnavailable = true
                        mediaBatchState = Task.isCancelled || mediaBatchCancellationRequested ? .cancelled : .failed
                    }
                }
                if continuation != nil { return }
            }
        } else if continuation != nil {
            mediaWorklistContinuationUnavailable = true
            mediaBatchState = .failed
            return
        }
        if continuation != nil, ownsMediaBatch(token: token, generation: generation) {
            await refreshMediaDeliveryHistory()
        }
        for (index, entry) in results.enumerated() {
            guard ownsMediaBatch(token: token, generation: generation) else { return }
            do { try checkContinuationOwner() }
            catch { mediaBatchState = .cancelled; return }
            var deliveryReceiptID: UUID?
            if let mediaDeliveryStore, let ticket = deliveryTicket, let rowIDs = plannedRowIDs {
                do {
                    guard rowIDs.indices.contains(index) else { throw MediaDeliveryStoreError.invalidReceipt }
                    deliveryReceiptID = try await mediaDeliveryStore.beginPlannedItem(ticket, rowID: rowIDs[index])
                    await mediaDeliveryReplyDelay?(.itemStarted)
                }
                catch {
                    guard ownsMediaBatch(token: token, generation: generation) else { return }
                    mediaDeliveryRecordingWarning = true
                    mediaDeliveryHistoryWarning = true
                    mediaBatchWorklistWarning = true
                    if Task.isCancelled || mediaBatchCancellationRequested {
                        mediaBatchState = .cancelled
                        return
                    }
                    if continuation != nil {
                        mediaWorklistContinuationUnavailable = true
                        mediaBatchState = .failed
                        return
                    } else if (error as? MediaDeliveryStoreError) == .invalidTicket {
                        // A confirmed clear retires the entire plan. Preserve
                        // the existing manual operation without rebuilding it.
                        deliveryTicket = nil
                        plannedRowIDs = nil
                    } else {
                        // The plan still describes this row as unstarted. Do
                        // not perform an unrecorded GET that would make it lie.
                        mediaBatchState = .failed
                        return
                    }
                }
            }
            guard ownsMediaBatch(token: token, generation: generation) else {
                // Admission may have committed before a session replacement
                // retired this reply. No GET was sent: settle only the captured
                // old row so its live store cannot remain permanently started.
                if continuation != nil, let mediaDeliveryStore, let deliveryTicket, let deliveryReceiptID {
                    try? await mediaDeliveryStore.finishFailure(deliveryTicket, receiptID: deliveryReceiptID, cancelled: true)
                }
                return
            }
            let itemToken = UUID()
            mediaBatchItemToken = itemToken
            mediaBatchResults[index].status = .downloading
            mediaBatchProgress = CameraMediaTransferProgress(bytesTransferred: 0, totalBytes: entry.item.sizeBytes)
            do {
                // The local lease also retains an incomplete destination until
                // the request settles, including after disconnect or cancellation.
                let ownedFile = try MediaBatchOwnedFile.create(filename: entry.item.name)
                try checkContinuationOwner()
                if continuation != nil {
                    guard let mediaDeliveryStore, let deliveryTicket, let deliveryReceiptID else {
                        throw MediaDeliveryStoreError.invalidTicket
                    }
                    try await mediaDeliveryStore.validatePlannedDownload(deliveryTicket, rowID: deliveryReceiptID)
                    try checkContinuationOwner()
                }
                let download = try await session.downloadMedia(entry.item, to: ownedFile.fileURL) { [weak self] progress in
                    Task { @MainActor in
                        guard let self, self.ownsMediaBatch(token: token, generation: generation),
                              self.mediaBatchItemToken == itemToken,
                              !self.mediaBatchCancellationRequested else { return }
                        self.mediaBatchProgress = progress
                    }
                }
                try checkContinuationOwner()
                let values = try ownedFile.fileURL.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey])
                guard download.fileURL == ownedFile.fileURL,
                      mediaItemIdentity(download.item) == mediaItemIdentity(entry.item),
                      values.isRegularFile == true, values.isSymbolicLink != true,
                      download.bytesTransferred > 0, values.fileSize.map(Int64.init) == download.bytesTransferred else {
                    throw CocoaError(.fileReadCorruptFile)
                }
                mediaBatchItemToken = nil
                mediaBatchResults[index].ownedFile = ownedFile
                mediaBatchResults[index].status = .ready
                mediaBatchProgress = nil
                // Optional retention has its own error boundary. The validated
                // temporary original remains ready even if history or disk fails.
                if let mediaDeliveryStore, let deliveryTicket, let deliveryReceiptID {
                    do {
                        try await mediaDeliveryStore.finishPrepared(deliveryTicket, receiptID: deliveryReceiptID,
                            source: ownedFile, actualBytes: download.bytesTransferred)
                        await mediaDeliveryReplyDelay?(.originalRetained)
                    } catch {
                        if ownsMediaBatch(token: token, generation: generation) {
                            mediaDeliveryRecordingWarning = true
                            mediaDeliveryHistoryWarning = true
                        }
                    }
                    if ownsMediaBatch(token: token, generation: generation) {
                        await refreshMediaDeliveryHistory()
                    }
                }
            } catch {
                let cancelled = Task.isCancelled || mediaBatchCancellationRequested || error is CancellationError
                    || (error as? URLError)?.code == .cancelled
                if let mediaDeliveryStore, let deliveryTicket, let deliveryReceiptID {
                    do { try await mediaDeliveryStore.finishFailure(deliveryTicket, receiptID: deliveryReceiptID, cancelled: cancelled) }
                    catch {
                        if ownsMediaBatch(token: token, generation: generation) {
                            mediaDeliveryRecordingWarning = true
                            mediaDeliveryHistoryWarning = true
                        }
                    }
                }
                guard ownsMediaBatch(token: token, generation: generation) else { return }
                mediaBatchItemToken = nil
                mediaBatchResults[index].status = cancelled ? .cancelledOrUnknown : .failed
                mediaBatchResults[index].errorMessage = cancelled ? nil : error.localizedDescription
                mediaBatchState = cancelled ? .cancelled : .failed
                if continuation != nil { await refreshMediaDeliveryHistory() }
                return // Earlier ready files survive. Later entries remain not started.
            }
            await Task.yield()
        }
        guard ownsMediaBatch(token: token, generation: generation) else { return }
        if continuation != nil {
            do { try checkContinuationOwner() }
            catch { mediaBatchState = .cancelled; return }
        }
        mediaBatchState = .completed
    }

    private func resetMediaBatchState() {
        cancelMediaWorklistContinuation()
        mediaWorklistContinuationUnavailable = false
        mediaBatchContinuationActive = false
        let ownedMedia = mediaBatchToken != nil
        mediaBatchToken = nil
        mediaBatchItemToken = nil
        mediaBatchTask?.cancel()
        mediaBatchTask = nil
        mediaBatchResults = []
        mediaBatchState = .idle
        mediaBatchProgress = nil
        mediaBatchCancellationRequested = false
        if ownedMedia { end(.media) }
    }

    private func refreshSelectedMediaMetadata(from items: [CameraMediaItem]) {
        var updated: [Data: CameraMediaItem] = [:]
        for item in items { updated[mediaItemIdentity(item)] = item }
        selectedMediaItems = selectedMediaItems.map { updated[mediaItemIdentity($0)] ?? $0 }
    }

    @discardableResult
    func startMediaUpload(_ fileURL: URL, securityScoped: Bool = false) -> Bool {
        guard !isPreview, let session, !mediaLibraryLoading, supports(.mediaUpload), begin(.media) else { return false }
        let generation = sessionGeneration
        let filename = fileURL.lastPathComponent
        let token = UUID()
        mediaUploadToken = token
        activeMediaUploadName = filename
        mediaUploadProgress = CameraMediaTransferProgress(
            bytesTransferred: 0,
            totalBytes: Int64((try? fileURL.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
        )
        mediaUploadError = nil
        uploadedMediaName = nil
        mediaUploadTask = Task { [weak self] in
            // The accepted task owns the picker grant even if the state is gone
            // or this queued upload is retired before its body can enter.
            defer { if securityScoped { fileURL.stopAccessingSecurityScopedResource() } }
            await self?.performMediaUpload(fileURL, session: session, token: token, generation: generation)
        }
        return true
    }

    func cancelMediaUpload() {
        mediaUploadTask?.cancel()
    }

    private func ownsMediaUpload(_ token: UUID, generation: UUID) -> Bool {
        mediaUploadToken == token && sessionGeneration == generation
    }

    private func performMediaUpload(
        _ fileURL: URL, session: CameraSession, token: UUID, generation: UUID
    ) async {
        defer {
            if ownsMediaUpload(token, generation: generation) {
                mediaUploadTask = nil
                mediaUploadToken = nil
                activeMediaUploadName = nil
                mediaUploadProgress = nil
                end(.media)
            }
        }
        guard ownsMediaUpload(token, generation: generation), !Task.isCancelled else { return }
        do {
            let result = try await session.uploadMedia(
                from: fileURL,
                progress: { [weak self] progress in
                    Task { @MainActor in
                        guard self?.ownsMediaUpload(token, generation: generation) == true else { return }
                        self?.mediaUploadProgress = progress
                    }
                }
            )
            guard ownsMediaUpload(token, generation: generation) else { return }
            if Task.isCancelled {
                await reconcileCancelledMediaUpload(
                    session: session,
                    token: token, generation: generation,
                    name: result.name,
                    sizeBytes: result.sizeBytes
                )
                return
            }
            mediaLibraryLoadStatus = .loading
            let scope = mediaLibraryScope
            let libraryGeneration = mediaLibraryGeneration
            do {
                let items = try await session.listMedia(maximumItems: maximumMediaItems(for: scope))
                guard ownsMediaUpload(token, generation: generation) else { return }
                if libraryGeneration == mediaLibraryGeneration, scope == mediaLibraryScope {
                    applyMediaBatch(items, scope: scope)
                    mediaLibraryLoadStatus = .complete
                    resetMediaThumbnails()
                    resetMediaPreview()
                }
            } catch {
                guard ownsMediaUpload(token, generation: generation) else { return }
                if libraryGeneration == mediaLibraryGeneration, scope == mediaLibraryScope {
                    mediaLibraryLoadStatus = .failed
                    throw error
                }
            }
            uploadedMediaName = result.name
            mediaUploadError = nil
            lastError = nil
            if let snapshot {
                self.snapshot = CameraSnapshot(
                    info: snapshot.info,
                    status: snapshot.status,
                    capabilities: snapshot.capabilities.observing(.mediaUpload)
                )
            }
        } catch is CancellationError {
            await reconcileCancelledMediaUpload(session: session, token: token, generation: generation, fileURL: fileURL)
        } catch let error as URLError where error.code == .cancelled {
            await reconcileCancelledMediaUpload(session: session, token: token, generation: generation, fileURL: fileURL)
        } catch {
            if ownsMediaUpload(token, generation: generation), !Task.isCancelled {
                mediaUploadError = error.localizedDescription
                record(error)
            }
        }
    }

    func setMediaArchive(_ item: CameraMediaItem, enabled: Bool) async {
        await updateMediaMetadata(item, feature: .mediaArchive) { session in
            try await session.setMediaArchive(item, enabled: enabled)
        } preview: {
            CameraMediaItem(
                id: item.id, name: item.name, kind: item.kind, sizeBytes: item.sizeBytes,
                captureTime: item.captureTime, previewAvailable: item.previewAvailable,
                protected: item.protected, rating: item.rating, rotationDegrees: item.rotationDegrees,
                archived: enabled, contentType: item.contentType,
                widthPixels: item.widthPixels, heightPixels: item.heightPixels, folder: item.folder
            )
        }
    }

    private func reconcileCancelledMediaUpload(
        session: CameraSession,
        token: UUID, generation: UUID,
        fileURL: URL
    ) async {
        let size = Int64((try? fileURL.resourceValues(forKeys: [.fileSizeKey]).fileSize) ?? 0)
        await reconcileCancelledMediaUpload(
            session: session,
            token: token, generation: generation,
            name: fileURL.lastPathComponent,
            sizeBytes: size > 0 ? size : nil
        )
    }

    private func reconcileCancelledMediaUpload(
        session: CameraSession,
        token: UUID, generation: UUID,
        name: String,
        sizeBytes: Int64?
    ) async {
        guard ownsMediaUpload(token, generation: generation), case .desktopBridge = session else { return }
        mediaLibraryLoadStatus = .loading
        let scope = mediaLibraryScope
        let libraryGeneration = mediaLibraryGeneration
        let maximumItems = maximumMediaItems(for: scope)
        let items = await Task.detached {
            try? await session.listMedia(maximumItems: maximumItems)
        }.value
        guard ownsMediaUpload(token, generation: generation) else { return }
        guard let items else {
            if libraryGeneration == mediaLibraryGeneration, scope == mediaLibraryScope {
                mediaLibraryLoadStatus = .failed
            }
            return
        }
        if libraryGeneration == mediaLibraryGeneration, scope == mediaLibraryScope {
            applyMediaBatch(items, scope: scope)
            mediaLibraryLoadStatus = .complete
            resetMediaThumbnails()
            resetMediaPreview()
        }
        guard let uploaded = items.first(where: { item in
            item.name.caseInsensitiveCompare(name) == .orderedSame &&
                (sizeBytes == nil || item.sizeBytes == sizeBytes)
        }) else { return }
        uploadedMediaName = uploaded.name
        mediaUploadError = nil
        lastError = nil
        if let snapshot {
            self.snapshot = CameraSnapshot(
                info: snapshot.info,
                status: snapshot.status,
                capabilities: snapshot.capabilities.observing(.mediaUpload)
            )
        }
    }

    private func ownsMediaDownload(token: UUID, generation: UUID) -> Bool {
        mediaDownloadToken == token && sessionGeneration == generation
    }

    private func performMediaDownload(
        _ item: CameraMediaItem, token: UUID, generation: UUID, historyGeneration: UUID
    ) async {
        guard ownsMediaDownload(token: token, generation: generation) else { return }
        defer {
            if ownsMediaDownload(token: token, generation: generation) {
                mediaDownloadTask = nil
                mediaDownloadToken = nil
                mediaDownloadCancellationRequested = false
                activeMediaDownloadID = nil
                mediaDownloadProgress = nil
                end(.media)
            }
        }
        guard !Task.isCancelled else {
            #if DEBUG
            traceMediaDownload(.cancelled)
            #endif
            return
        }
        if isPreview {
            downloadedMediaID = item.id
            downloadedFileName = item.name
            lastError = nil
            return
        }
        guard let session else {
            #if DEBUG
            traceMediaDownload(.failed)
            #endif
            return
        }
        var ticket: MediaDeliveryTicket?
        var receiptID: UUID?
        func warnAboutHistory() {
            guard ownsMediaDownload(token: token, generation: generation),
                  mediaDeliveryClearGeneration == historyGeneration else { return }
            mediaDeliveryRecordingWarning = true
            mediaDeliveryHistoryWarning = true
        }
        do {
            removeDownloadedFile()
            #if DEBUG
            traceMediaDownload(.history)
            #endif
            // Single-item history is optional. Listing size is only a progress
            // hint: admit an unknown size and account for validated real bytes.
            if let mediaDeliveryStore, mediaDeliveryClearGeneration == historyGeneration {
                do {
                    let admitted = try await mediaDeliveryStore.beginBatch()
                    ticket = admitted
                    await mediaDeliveryReplyDelay?(.batchStarted)
                    try Task.checkCancellation()
                    guard ownsMediaDownload(token: token, generation: generation) else { throw CancellationError() }
                    if mediaDeliveryClearGeneration == historyGeneration {
                        receiptID = try await mediaDeliveryStore.beginItem(admitted, filename: item.name, expectedBytes: nil)
                        await mediaDeliveryReplyDelay?(.itemStarted)
                    }
                } catch {
                    if Task.isCancelled || error is CancellationError || (error as? URLError)?.code == .cancelled
                        || (error as? MediaDeliveryStoreError) == .cancelled { throw CancellationError() }
                    warnAboutHistory()
                }
            } else if mediaDeliveryStore == nil { warnAboutHistory() }
            try Task.checkCancellation()
            guard ownsMediaDownload(token: token, generation: generation) else { throw CancellationError() }
            // The request and any presented exporter hold independent references
            // to this lease. Replacement releases only the App's reference.
            let ownedFile = try MediaBatchOwnedFile.create(filename: item.name)
            #if DEBUG
            traceMediaDownload(.requestOriginal)
            #endif
            let result = try await session.downloadMedia(item, to: ownedFile.fileURL) { [weak self] progress in
                Task { @MainActor in
                    guard let self, self.ownsMediaDownload(token: token, generation: generation),
                          !self.mediaDownloadCancellationRequested, self.mediaDownloadTask?.isCancelled != true else { return }
                    self.mediaDownloadProgress = progress
                }
            }
            #if DEBUG
            traceMediaDownload(.replyOriginal)
            #endif
            try Task.checkCancellation()
            guard ownsMediaDownload(token: token, generation: generation) else { throw CancellationError() }
            let values = try ownedFile.fileURL.resourceValues(forKeys: [.isRegularFileKey, .isSymbolicLinkKey, .fileSizeKey])
            guard result.fileURL == ownedFile.fileURL, mediaItemIdentity(result.item) == mediaItemIdentity(item),
                  values.isRegularFile == true, values.isSymbolicLink != true,
                  result.bytesTransferred > 0, values.fileSize.map(Int64.init) == result.bytesTransferred else {
                throw CocoaError(.fileReadCorruptFile)
            }
            #if DEBUG
            traceMediaDownload(.validated)
            #endif
            downloadedOwnedFile = ownedFile
            downloadedFileURL = ownedFile.fileURL
            downloadedMediaID = result.item.id
            downloadedFileName = result.item.name
            lastError = nil
            #if DEBUG
            traceMediaDownload(.published)
            #endif
            // Once published, cancellation or a failed optional copy must never
            // turn the valid temporary original into a failed download.
            if let mediaDeliveryStore, let ticket, let receiptID {
                do {
                    try await mediaDeliveryStore.finishPrepared(ticket, receiptID: receiptID,
                        source: ownedFile, actualBytes: result.bytesTransferred)
                    await mediaDeliveryReplyDelay?(.originalRetained)
                } catch { warnAboutHistory() }
                if ownsMediaDownload(token: token, generation: generation),
                   mediaDeliveryClearGeneration == historyGeneration {
                    await refreshMediaDeliveryHistory()
                }
            }
        } catch {
            let cancelled = Task.isCancelled || error is CancellationError || (error as? URLError)?.code == .cancelled
            #if DEBUG
            traceMediaDownload(cancelled ? .cancelled : .failed)
            #endif
            if let mediaDeliveryStore, let ticket, let receiptID {
                do { try await mediaDeliveryStore.finishFailure(ticket, receiptID: receiptID, cancelled: cancelled) }
                catch { warnAboutHistory() }
            }
            guard ownsMediaDownload(token: token, generation: generation) else { return }
            if !cancelled, !Task.isCancelled { record(error) }
            if receiptID != nil, mediaDeliveryClearGeneration == historyGeneration {
                await refreshMediaDeliveryHistory()
            }
        }
    }

    func deleteMedia(_ item: CameraMediaItem) async {
        guard !mediaLibraryLoading, supports(.mediaDelete), begin(.media) else { return }
        let generation = sessionGeneration
        defer { end(.media, generation: generation) }
        if isPreview {
            applyDeletedMedia(item)
            lastError = nil
            return
        }
        guard let session else { return }
        do {
            try await session.deleteMedia(item)
            guard generation == sessionGeneration else { return }
            applyDeletedMedia(item)
            lastError = nil
        } catch {
            guard generation == sessionGeneration else { return }
            record(error)
        }
    }

    func diagnosticReport() async throws -> CameraSessionExport {
        try Task.checkCancellation()
        let owner = exportGeneration
        let metrics = CCAPILiveViewMetrics(
            requestedFPS: requestedFPS,
            observedFPS: observedFPS,
            frameBytes: frameBytes,
            contentType: frameContentType,
            source: activeLiveViewSource,
            sourceURL: frameSourceURL,
            lastFrameAt: lastFrameAt
        )
        let monitoring = [
            "mediaItemCount=\(mediaItems.count)",
            "mediaLoadStatus=\(mediaLibraryLoadStatus.rawValue)",
            "mediaLibraryScope=\(mediaLibraryScope.rawValue)",
            "mediaLibraryHasMore=\(mediaLibraryHasMore)",
            "mediaLibraryRequestLimit=\(maximumMediaItems(for: mediaLibraryScope).map { String($0) } ?? "none")",
            "latestMediaRequestLimit=\(Self.latestMediaRequestItemCount)",
            "latestMedia=\(latestMediaItem == nil ? "none" : "available")",
            "latestMediaThumbnail=\(latestMediaThumbnail == nil ? "none" : "available")",
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
        try Task.checkCancellation()
        guard owner == exportGeneration else { throw CancellationError() }
        return CameraSessionExport(text: "\(report)\n\(monitoring)", owner: owner)
    }

    /// Call on the main actor immediately before publishing, without an await
    /// between this check and the clipboard write or copied-success UI update.
    func canPublishExport(_ export: CameraSessionExport) -> Bool {
        export.owner == exportGeneration && !Task.isCancelled
    }

    func setOperatorConfirmation(_ feature: CameraFeature, confirmed: Bool) {
        let eligible = physicalValidation.eligibleFeatures.contains(feature)
        if confirmed, eligible {
            operatorConfirmedFeatures.insert(feature)
        } else if !confirmed {
            operatorConfirmedFeatures.remove(feature)
        }
    }

    func physicalValidationRecord() async throws -> CameraSessionExport {
        try Task.checkCancellation()
        let owner = exportGeneration
        let summary = physicalValidation
        let cameraInfo = info
        let transport = transportIdentifier
        guard summary.sessionStatus == .ready else {
            throw PhysicalValidationRecord.ValidationError.physicalCameraRequired
        }
        let report = try await diagnosticReport()
        guard owner == exportGeneration, canPublishExport(report) else { throw CancellationError() }
        let text = try PhysicalValidationRecord.make(
            summary: summary,
            info: cameraInfo,
            transport: transport,
            diagnosticReport: report.text
        )
        return CameraSessionExport(text: text, owner: owner)
    }

    func clearError() {
        lastError = nil
    }

    func importCubeLut(from url: URL) async {
        guard !Task.isCancelled else { return }
        let owner = UUID()
        cubeLutOwner = owner
        let loader = cubeLutLoader
        let task = Task.detached(priority: .userInitiated) { () async throws -> CubeLut in
            if let loader { return try await loader(url) }
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
            let lut = try await task.value
            guard cubeLutOwner == owner, !Task.isCancelled else { return }
            monitorSettings.cubeLut = lut
            lastError = nil
        } catch {
            guard cubeLutOwner == owner, !Task.isCancelled else { return }
            lastError = String(
                format: NSLocalizedString("lut_import_failed", comment: ""),
                error.localizedDescription
            )
        }
    }

    func clearCubeLut() {
        cubeLutOwner = UUID()
        monitorSettings.cubeLut = nil
    }

    func reportCubeLutImportError(_ error: Error) {
        cubeLutOwner = UUID()
        lastError = String(
            format: NSLocalizedString("lut_import_failed", comment: ""),
            error.localizedDescription
        )
    }

    func reportCubeLutRenderFailure() {
        lastError = NSLocalizedString("lut_render_failed", comment: "")
    }

    private func begin(_ operation: CameraOperation) -> Bool {
        if foregroundMediaIOOwner != nil { return false }
        if heldAutofocusOwner != nil, operation != .refresh { return false }
        if shutterControlIsBusy(operation) { return false }
        if recordingRecovery != nil, operation == .capture { return false }
        if shutterReleaseRequired && operation != .refresh { return false }
        let inserted = busyOperations.insert(operation).inserted
        if inserted {
            operationRevision &+= 1
            if operation == .focus || operation == .capture || operation == .liveView || operation == .setting {
                invalidateCameraFocusInfo()
            }
        }
        return inserted
    }

    private func end(_ operation: CameraOperation) {
        busyOperations.remove(operation)
    }

    private func end(_ operation: CameraOperation, generation: UUID) {
        guard generation == sessionGeneration else { return }
        end(operation)
    }

    private func applyShortControlReleaseState(_ state: CameraShutterReleaseState, session: CameraSession, generation: UUID) {
        // A concurrent status/event read may already have surfaced this owner's
        // pending stop. Only current Core state, not an ordinary status flag,
        // can clear that warning after the original short control settles.
        let wasReleaseRequired = shutterReleaseRequired
        applyShutterReleaseState(state)
        if wasReleaseRequired, !state.releaseRequired {
            resumeLiveViewAfterBulb(session: session, generation: generation)
        }
    }

    private func applyShutterReleaseState(_ state: CameraShutterReleaseState, pauseLiveView: Bool = true) {
        shutterReleaseState = state
        if let snapshot { self.snapshot = snapshot.replacing(status: snapshot.status.withShutterReleaseState(state)) }
        bulbStartedAt = state.bulbExposureActive == true ? bulbStartedAt ?? Date() : nil
        if state.releaseRequired, pauseLiveView, !heldAutofocusPressed { pauseLiveViewForBulb() }
    }

    private func adoptReportedShutterState(_ status: CameraStatus) {
        // Ordinary reads must not erase an unresolved release. Only a release ACK can.
        if status.shutterReleaseUnconfirmed == true {
            shutterReleaseState = CameraShutterReleaseState(
                releaseRequired: true, releaseUnconfirmed: true, bulbExposureActive: nil
            )
        } else if status.bulbExposureActive == true, !shutterReleaseRequired {
            shutterReleaseState = CameraShutterReleaseState(
                releaseRequired: true, releaseUnconfirmed: false, bulbExposureActive: true
            )
        }
        if shutterReleaseRequired, !heldAutofocusPressed { pauseLiveViewForBulb() }
        bulbStartedAt = bulbExposureActive ? bulbStartedAt ?? Date() : nil
    }

    private func updateStatus(_ status: CameraStatus) {
        guard let snapshot else { return }
        self.snapshot = snapshot.replacing(status: status)
        adoptReportedShutterState(status)
        if bulbExposureActive {
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
        guard applicationActive, !shutterReleaseRequired || heldAutofocusPressed else { return }
        let generation = sessionGeneration
        stopLiveViewLoop()
        resetLiveViewMetrics()
        liveViewTask = Task { [weak self] in
            guard let self else { return }
            var cacheKey: Int64 = 0
            while !Task.isCancelled {
                let started = Date().timeIntervalSinceReferenceDate
                do {
                    let frame = try await session.liveViewFrame(cacheKey: cacheKey)
                    guard generation == sessionGeneration, !Task.isCancelled,
                          applicationActive, !shutterReleaseRequired || heldAutofocusPressed else { break }
                    cacheKey &+= 1
                    liveViewData = frame.data
                    frameBytes = frame.data.count
                    frameContentType = frame.contentType
                    frameSourceURL = frame.sourceURL
                    let activeSize = await session.currentLiveViewSize()
                    guard generation == sessionGeneration, !Task.isCancelled else { break }
                    if let activeSize, activeSize != liveViewSize {
                        liveViewSize = activeSize
                        defaults.set(activeSize.rawValue, forKey: DefaultsKey.liveViewSize)
                    }
                    let now = Date()
                    lastFrameAt = now
                    observedFPS = rateTracker.record(now.timeIntervalSinceReferenceDate)
                    if lastError?.contains("Live View") == true { lastError = nil }
                } catch {
                    if Task.isCancelled || generation != sessionGeneration { break }
                    let stopState = await session.liveViewStopState()
                    guard generation == sessionGeneration else { break }
                    liveViewStopState = stopState
                    record(error)
                    if stopState.stopUnconfirmed { activeLiveViewSource = nil; break }
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
        invalidateCameraFocusInfo()
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
            var pendingMediaDemand = mediaEventDemandGeneration
            while !Task.isCancelled, generation == eventGeneration {
                do {
                    if pendingKeys.isEmpty {
                        pendingMediaDemand = mediaEventDemandGeneration
                        let event = try await session.pollEvent()
                        guard generation == eventGeneration, !Task.isCancelled else { break }
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
                    guard generation == eventGeneration, !Task.isCancelled else { break }
                    guard refreshed.revision == operationRevision, busyOperations.isEmpty else { continue }
                    let wasReleaseRequired = shutterReleaseRequired
                    snapshot = refreshed.snapshot
                    let recoveringRecording = refreshed.recordingRecoveryID != nil
                    applyShutterReleaseState(refreshed.release, pauseLiveView: !recoveringRecording)
                    if recoveringRecording {
                        lastError = nil
                        settleRecordingReadback(refreshed.snapshot.status, expectedCommandID: refreshed.recordingRecoveryID, session: session)
                    } else {
                        if wasReleaseRequired, !shutterReleaseRequired {
                            resumeLiveViewAfterBulb(session: session, generation: sessionGeneration)
                        }
                        clampLiveViewRequest()
                    }
                    let contentChanged = pendingMediaDemand == mediaEventDemandGeneration && pendingKeys.contains { key in
                        let normalized = key.lowercased()
                        return normalized.contains("content") || normalized.contains("media")
                    }
                    let bridgeGalleryRefresh: Bool
                    if case .desktopBridge = session { bridgeGalleryRefresh = screen == .media }
                    else { bridgeGalleryRefresh = false }
                    if contentChanged, !bridgeGalleryRefresh, latestMediaTask == nil,
                       pendingCaptureReview == nil, captureReviewState == .idle {
                        startLatestMediaRefresh(session: session)
                    }
                    if screen == .media, contentChanged {
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
        if eventMediaListingTask != nil { invalidateMediaLibraryLoad(status: .cancelled) }
        eventGeneration = UUID()
        eventTask?.cancel()
        eventTask = nil
    }

    private func stableEventSnapshot(
        session: CameraSession,
        generation: UUID
    ) async throws -> (snapshot: CameraSnapshot, release: CameraShutterReleaseState, revision: UInt64, recordingRecoveryID: UUID?)? {
        while generation == eventGeneration, !Task.isCancelled {
            while !busyOperations.isEmpty, generation == eventGeneration, !Task.isCancelled {
                try await Task.sleep(nanoseconds: 50_000_000)
            }
            guard generation == eventGeneration, !Task.isCancelled else { return nil }

            let revision = operationRevision
            let recordingRecoveryID = recordingRecovery?.commandID
            let refreshed: CameraSnapshot
            if recordingRecoveryID != nil, let current = snapshot {
                refreshed = current.replacing(status: try await session.status())
            } else {
                refreshed = try await session.connectSnapshot()
            }
            let release = await session.shutterReleaseState()
            guard generation == eventGeneration, !Task.isCancelled else { return nil }
            if revision == operationRevision, busyOperations.isEmpty {
                return (refreshed, release, revision, recordingRecoveryID)
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
            if mediaLibraryLoading || busyOperations.contains(.media) || shutterReleaseRequired {
                try await Task.sleep(nanoseconds: 50_000_000)
                continue
            }
            guard begin(.mediaLibrary) else { continue }
            let mediaGeneration = UUID()
            let scope = mediaLibraryScope
            mediaLibraryGeneration = mediaGeneration
            mediaLibraryLoading = true
            if case .desktopBridge = session { mediaLibraryLoadCancellable = true }
            else { mediaLibraryLoadCancellable = false }
            mediaLibraryLoadStatus = .loading
            defer {
                if mediaGeneration == mediaLibraryGeneration {
                    eventMediaListingTask = nil
                    mediaLibraryLoadCancellable = false
                }
            }
            do {
                resetMediaThumbnails()
                // An event updates the library, not the user's open viewer or transfer.
                let maximumItems = maximumMediaItems(for: scope)
                let progress: CameraMediaListProgressHandler = { [weak self] partialItems in
                    await self?.applyMediaListProgress(partialItems, scope: scope, generation: mediaGeneration)
                }
                let items: [CameraMediaItem]
                if case .desktopBridge = session {
                    let listing = Task { try await session.listMedia(maximumItems: maximumItems, onProgress: progress) }
                    eventMediaListingTask = listing
                    items = try await listing.value
                } else {
                    items = try await session.listMedia(maximumItems: maximumItems, onProgress: progress)
                }
                guard mediaGeneration == mediaLibraryGeneration else { return true }
                mediaLibraryLoading = false
                mediaLibraryLoadStatus = .complete
                end(.mediaLibrary)
                guard generation == eventGeneration, !Task.isCancelled else { return false }
                applyMediaBatch(items, scope: scope)
                if mediaPreviewItem == nil { lastError = nil }
                return true
            } catch {
                if mediaGeneration == mediaLibraryGeneration {
                    mediaLibraryLoading = false
                    mediaLibraryLoadStatus = (error is CancellationError) ? .cancelled : .failed
                    end(.mediaLibrary)
                } else {
                    return true
                }
                if error is CancellationError { return true }
                if case .desktopBridge = session {
                    // This content hint has been consumed. A malformed/failed stream
                    // must not replay the traversal through the event retry loop.
                    retireBridgeMediaEventDemand()
                    record(error)
                    return true
                }
                throw error
            }
        }
        return false
    }

    private func pauseLiveViewForBulb() {
        cancelLiveViewFPSUpdate()
        stopLiveViewLoop()
        rtpController.setRenderingEnabled(false)
    }

    private func resumeLiveViewAfterBulb(session: CameraSession, generation: UUID) {
        guard generation == sessionGeneration, applicationActive, !shutterReleaseRequired,
              liveViewTemperatureAllowed else { return }
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
        let generation = sessionGeneration
        shutterFlash = true
        Task { [weak self] in
            try? await Task.sleep(nanoseconds: 130_000_000)
            guard self?.sessionGeneration == generation else { return }
            self?.shutterFlash = false
        }
    }

    private func retireFocusMarker() {
        focusMarkerOwner = UUID()
        focusMarkerExpiryTask?.cancel()
        focusMarkerExpiryTask = nil
        focusMarker = nil
    }

    private func showFocusMarker(x: Double, y: Double, accepted: Bool) {
        retireFocusMarker()
        let owner = focusMarkerOwner
        let delay = focusMarkerDelay
        focusMarker = FocusMarker(x: x, y: y, accepted: accepted)
        focusMarkerExpiryTask = Task { [weak self] in
            do {
                if let delay { try await delay() }
                else { try await Task.sleep(nanoseconds: 1_200_000_000) }
            } catch { return }
            guard !Task.isCancelled, self?.focusMarkerOwner == owner else { return }
            self?.focusMarker = nil
            self?.focusMarkerExpiryTask = nil
        }
    }

    private func record(_ error: Error) {
        lastError = error.localizedDescription
    }

    private func removeDownloadedFile() {
        downloadedOwnedFile = nil
        downloadedFileURL = nil
        downloadedMediaID = nil
        downloadedFileName = nil
    }

    private func resetMediaDownloadState() {
        mediaDownloadTask?.cancel()
        mediaDownloadTask = nil
        mediaDownloadToken = nil
        mediaDownloadCancellationRequested = false
        activeMediaDownloadID = nil
        mediaDownloadProgress = nil
        busyOperations.remove(.media)
    }

    private func resetMediaUploadState() {
        mediaUploadTask?.cancel()
        mediaUploadTask = nil
        mediaUploadToken = nil
        activeMediaUploadName = nil
        mediaUploadProgress = nil
        mediaUploadError = nil
        uploadedMediaName = nil
        busyOperations.remove(.media)
    }

    private func maximumMediaItems(for scope: MediaLibraryScope) -> Int? {
        scope == .recent ? Self.recentMediaRequestCount : nil
    }

    private func applyMediaBatch(_ items: [CameraMediaItem], scope: MediaLibraryScope) {
        refreshSelectedMediaMetadata(from: items)
        observedMediaIDs.formUnion(items.map(\.id))
        let batch = mediaLibraryBatch(
            items,
            scope: scope,
            recentItemCount: Self.recentMediaItemCount
        )
        mediaItems = batch.items
        mediaLibraryHasMore = batch.hasMore
    }

    private func applyMediaListProgress(
        _ items: [CameraMediaItem],
        scope: MediaLibraryScope,
        generation: UUID
    ) {
        guard generation == mediaLibraryGeneration, !Task.isCancelled else { return }
        applyMediaBatch(items, scope: scope)
    }

    private func retireBridgeMediaEventDemand() {
        if let session, case .desktopBridge = session { mediaEventDemandGeneration = UUID() }
    }

    private func invalidateMediaLibraryLoad(status: MediaLibraryLoadStatus = .notLoaded) {
        retireBridgeMediaEventDemand()
        mediaLibraryGeneration = UUID()
        eventMediaListingTask?.cancel()
        eventMediaListingTask = nil
        mediaLibraryTask?.cancel()
        mediaLibraryTask = nil
        mediaLibraryLoading = false
        mediaLibraryLoadCancellable = false
        mediaLibraryLoadStatus = status
        if status == .notLoaded { mediaLibraryHasMore = false }
        busyOperations.remove(.mediaLibrary)
    }

    private func finishMediaLibraryLoad(generation: UUID) {
        guard generation == mediaLibraryGeneration else { return }
        mediaLibraryTask = nil
        mediaLibraryLoading = false
        mediaLibraryLoadCancellable = false
        end(.mediaLibrary)
    }

    private func applyDeletedMedia(_ item: CameraMediaItem) {
        let identity = mediaItemIdentity(item)
        selectedMediaItems.removeAll { mediaItemIdentity($0) == identity }
        if downloadedMediaID.map({ Data($0.utf8) }) == identity {
            removeDownloadedFile()
        }
        mediaItems.removeAll { mediaItemIdentity($0) == identity }
        mediaThumbnailCache.removeValue(for: item.id)
        mediaThumbnails = mediaThumbnailCache.values
        loadingMediaThumbnailIDs.remove(item.id)
        unavailableMediaThumbnailIDs.remove(item.id)
        if latestMediaItem.map(mediaItemIdentity) == identity {
            latestMediaItem = nil
            latestMediaThumbnail = nil
        }
        if mediaPreviewItem.map(mediaItemIdentity) == identity { resetMediaPreview() }
        deletedMediaName = item.name
    }

    private func applyUpdatedMedia(_ item: CameraMediaItem) {
        let identity = mediaItemIdentity(item)
        refreshSelectedMediaMetadata(from: [item])
        mediaItems = mediaItems.map { mediaItemIdentity($0) == identity ? item : $0 }
        if latestMediaItem.map(mediaItemIdentity) == identity { latestMediaItem = item }
        if mediaPreviewItem.map(mediaItemIdentity) == identity { mediaPreviewItem = item }
    }

    private func resetMediaThumbnails() {
        mediaThumbnailGeneration &+= 1
        mediaThumbnailCache.removeAll()
        mediaThumbnails = mediaThumbnailCache.values
        loadingMediaThumbnailIDs = []
        unavailableMediaThumbnailIDs = []
    }

    private func resetMediaPreview() {
        let imageTask = mediaPreviewImageTask
        mediaPreviewImageTask = nil
        // Closing a pending viewer releases only its own operation. A completed
        // preview must not clear a download or metadata operation's MEDIA busy.
        if mediaPreviewToken != nil {
            mediaPreviewToken = nil
            imageTask?.cancel()
            end(.media)
        }
        mediaVideoPlayback?.close()
        mediaVideoPlayback = nil
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
            CameraSetting(key: "ownername", label: "Owner name", value: "Open EOS", values: [], inputKind: .text, maxLength: 255),
            CameraSetting(key: "artist", label: "Artist", value: "Jason", values: [], inputKind: .text, maxLength: 255),
            CameraSetting(key: "copyright", label: "Copyright", value: "2026 Open EOS", values: [], inputKind: .text, maxLength: 255),
            CameraSetting(key: "nickname", label: "Nickname", value: "R6M3", values: [], inputKind: .text, maxLength: 255),
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
            .mediaProtect, .mediaRating, .mediaRotate, .mediaArchive, .mediaDelete,
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
                magnifications: [.x1, .x5, .x10],
                currentMagnification: .x1,
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

    private static let previewMediaFolder = CameraMediaFolder(id: "synthetic:preview-folder", label: "card1/DCIM/100CANON")

    static let previewMedia = [
        CameraMediaItem(id: "preview-001", name: "R6M3_0001.CR3", kind: "raw", sizeBytes: 31_457_280, captureTime: "2026-07-21T10:08:24+08:00", previewAvailable: true, protected: true, rating: 5, rotationDegrees: 0, archived: false, contentType: "image/x-canon-raw", widthPixels: 6000, heightPixels: 4000, folder: previewMediaFolder),
        CameraMediaItem(id: "preview-002", name: "R6M3_0001.JPG", kind: "image", sizeBytes: 8_912_384, captureTime: "2026-07-21T10:08:24+08:00", previewAvailable: true, protected: false, rating: 3, rotationDegrees: 90, archived: true, contentType: "image/jpeg", widthPixels: 6000, heightPixels: 4000, folder: previewMediaFolder),
        CameraMediaItem(id: "preview-003", name: "R6M3_0002.MP4", kind: "video", sizeBytes: 128_450_560, captureTime: "2026-07-21T10:10:02+08:00", protected: false, rating: 0, rotationDegrees: 0, archived: false, contentType: "video/mp4", widthPixels: 3840, heightPixels: 2160),
    ]
}
