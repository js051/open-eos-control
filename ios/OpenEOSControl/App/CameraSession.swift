import Foundation
import OpenEOSCore

enum LiveViewTargetFPSUpdate: Equatable {
    case appliedInPlace
    case restartRequired
}

enum CameraSession: Sendable {
    case ccapi(CCAPIClient)
    case desktopBridge(DesktopBridgeClient)

    func foregroundMediaReadsAvailable() async -> Bool {
        switch self {
        case let .ccapi(client): return await client.foregroundMediaReadsAvailable()
        case .desktopBridge: return false
        }
    }

    func foregroundMediaInventory() async throws -> [CameraMediaItem] {
        switch self {
        case let .ccapi(client): return try await client.foregroundMediaInventory()
        case .desktopBridge: throw CameraForegroundMediaError.unsupported
        }
    }

    func foregroundMediaInfo(_ item: CameraMediaItem) async throws -> CameraMediaItem {
        switch self {
        case let .ccapi(client): return try await client.foregroundMediaInfo(item)
        case .desktopBridge: throw CameraForegroundMediaError.unsupported
        }
    }

    func downloadForegroundOriginal(_ item: CameraMediaItem, expectedBytes: Int64, to destination: URL,
                                    progress: @escaping CameraMediaProgressHandler) async throws -> CameraMediaDownload {
        switch self {
        case let .ccapi(client):
            return try await client.downloadForegroundOriginal(item, expectedBytes: expectedBytes, to: destination, progress: progress)
        case .desktopBridge: throw CameraForegroundMediaError.unsupported
        }
    }

    func connectSnapshot() async throws -> CameraSnapshot {
        switch self {
        case let .ccapi(client): return try await client.connectSnapshot()
        case let .desktopBridge(client): return try await client.connectSnapshot()
        }
    }

    func status() async throws -> CameraStatus {
        switch self {
        case let .ccapi(client): return try await client.status()
        case let .desktopBridge(client): return try await client.status()
        }
    }

    func shutterReleaseState() async -> CameraShutterReleaseState {
        switch self {
        case let .ccapi(client): return await client.shutterReleaseState()
        case let .desktopBridge(client): return await client.shutterReleaseState()
        }
    }

    func retryShutterRelease(cancelling pendingStart: Task<CameraStatus, Error>? = nil) async throws {
        // Also owns the interval before the Start reaches its client actor. A Stop
        // there must cancel that task before it can dispatch a later full_press.
        let interruptedStart: Result<CameraStatus, Error>?
        if let pendingStart {
            pendingStart.cancel()
            interruptedStart = await pendingStart.result
        } else {
            interruptedStart = nil
        }
        switch self {
        case let .ccapi(client):
            // Direct Start already attempts its owned release when cancelled.
            // Surface lost proof from that attempt instead of silently retrying.
            if let interruptedStart, case let .failure(error) = interruptedStart {
                let release = await client.shutterReleaseState()
                if release.releaseRequired { throw error }
            }
            try await client.retryShutterRelease()
        case let .desktopBridge(client): try await client.retryShutterRelease()
        }
    }

    func closeWithShutterReleaseState() async -> CameraShutterReleaseState {
        switch self {
        case let .ccapi(client): return await client.closeWithShutterReleaseState()
        case let .desktopBridge(client): return await client.closeWithShutterReleaseState()
        }
    }

    func connectionRetirementUnconfirmed() async -> Bool {
        switch self {
        case .ccapi: return false // No server-side session allocation in CCAPI discovery.
        case let .desktopBridge(client): return await client.connectionRetirementUnconfirmed
        }
    }

    func connectionRetirementSafetyWarnings() async -> Set<DesktopBridgeClient.ConnectionRetirementSafetyWarning> {
        switch self {
        case .ccapi: return []
        case let .desktopBridge(client): return await client.connectionRetirementSafetyWarnings
        }
    }

    func capabilities() async throws -> CameraCapabilities {
        switch self {
        case let .ccapi(client): return try await client.capabilities()
        case let .desktopBridge(client): return try await client.capabilities()
        }
    }

    func pollEvent() async throws -> CameraEvent {
        switch self {
        case let .ccapi(client): return try await client.pollEvent()
        case let .desktopBridge(client): return try await client.pollEvent()
        }
    }

    func stopEventPolling() async {
        switch self {
        case let .ccapi(client): await client.stopEventPolling()
        case let .desktopBridge(client): await client.stopEventPolling()
        }
    }

    func applyShootingSetting(key: String, value: String, attempt: CameraShootingSettingAttempt) async throws {
        switch self {
        case let .ccapi(client): try await client.applyShootingSetting(key: key, value: value, attempt: attempt)
        case let .desktopBridge(client): try await client.applyShootingSetting(key: key, value: value, attempt: attempt)
        }
    }

    func refreshShootingSetting(_ attempt: CameraShootingSettingAttempt) async throws {
        switch self {
        case let .ccapi(client): try await client.refreshShootingSetting(attempt)
        case let .desktopBridge(client): try await client.refreshShootingSetting(attempt)
        }
    }

    func setSetting(key: String, value: String) async throws -> CameraStatus {
        switch self {
        case let .ccapi(client): return try await client.setSetting(key: key, value: value)
        case let .desktopBridge(client): return try await client.setSetting(key: key, value: value)
        }
    }

    func createDirectory(name: String, attempt: CameraCaptureConfigurationAttempt? = nil) async throws -> String {
        switch self {
        case let .ccapi(client): return try await client.createDirectory(name: name, attempt: attempt)
        case let .desktopBridge(client): return try await client.createDirectory(name: name, attempt: attempt)
        }
    }

    func setFileNaming(field: CameraFileNamingField, value: String,
                       attempt: CameraCaptureConfigurationAttempt? = nil) async throws -> CameraFileNaming {
        switch self {
        case let .ccapi(client): return try await client.setFileNaming(field: field, value: value, attempt: attempt)
        case let .desktopBridge(client): return try await client.setFileNaming(field: field, value: value, attempt: attempt)
        }
    }

    func refreshCaptureConfiguration(_ attempt: CameraCaptureConfigurationAttempt) async throws {
        switch self {
        case let .ccapi(client): try await client.refreshCaptureConfiguration(attempt)
        case let .desktopBridge(client): try await client.refreshCaptureConfiguration(attempt)
        }
    }

    func performMaintenance(_ attempt: CameraMaintenanceAttempt) async throws {
        switch self {
        case let .ccapi(client): try await client.performMaintenance(attempt)
        case let .desktopBridge(client): try await client.performMaintenance(attempt)
        }
    }

    func restoreMaintenanceLiveView(_ attempt: CameraMaintenanceAttempt) async throws {
        switch self {
        case let .ccapi(client): try await client.restoreMaintenanceLiveView(attempt)
        case let .desktopBridge(client): try await client.restoreMaintenanceLiveView(attempt)
        }
    }

    func observeMaintenance(_ attempt: CameraMaintenanceAttempt) async throws {
        switch self {
        case let .ccapi(client): try await client.observeMaintenance(attempt)
        case let .desktopBridge(client): try await client.observeMaintenance(attempt)
        }
    }

    func syncCameraClock() async throws -> CameraStatus {
        switch self {
        case let .ccapi(client): return try await client.syncCameraClock()
        case let .desktopBridge(client): return try await client.syncCameraClock()
        }
    }

    func cleanSensor(autoPowerOff: Bool) async throws {
        switch self {
        case let .ccapi(client): try await client.cleanSensor(autoPowerOff: autoPowerOff)
        case let .desktopBridge(client): try await client.cleanSensor(autoPowerOff: autoPowerOff)
        }
    }

    func sleepCamera() async throws {
        switch self {
        case let .ccapi(client): try await client.sleepCamera()
        case let .desktopBridge(client): try await client.sleepCamera()
        }
    }

    func captureStill(autofocus: Bool = true) async throws -> CameraStatus {
        switch self {
        case let .ccapi(client): return try await client.captureStill(autofocus: autofocus)
        case let .desktopBridge(client): return try await client.captureStill(autofocus: autofocus)
        }
    }

    func startBulbExposure() async throws -> CameraStatus {
        switch self {
        case let .ccapi(client): return try await client.startBulbExposure()
        case let .desktopBridge(client): return try await client.startBulbExposure()
        }
    }

    func stopBulbExposure() async throws -> CameraStatus {
        switch self {
        case let .ccapi(client): return try await client.stopBulbExposure()
        case let .desktopBridge(client): return try await client.stopBulbExposure()
        }
    }

    func startHeldAutofocus(owner: UUID) async throws {
        switch self {
        case let .ccapi(client): try await client.startHeldAutofocus(owner: owner)
        case let .desktopBridge(client): try await client.startHeldAutofocus(owner: owner)
        }
    }

    func stopHeldAutofocus(owner: UUID) async throws {
        switch self {
        case let .ccapi(client): try await client.stopHeldAutofocus(owner: owner)
        case let .desktopBridge(client): try await client.stopHeldAutofocus(owner: owner)
        }
    }

    func liveViewFocusInfo() async throws -> CameraFocusInfo? {
        switch self {
        case let .ccapi(client): return try await client.liveViewFocusInfo()
        case let .desktopBridge(client): return try await client.liveViewFocusInfo()
        }
    }

    func autofocus() async throws -> CameraStatus {
        switch self {
        case let .ccapi(client): return try await client.autofocus()
        case let .desktopBridge(client): return try await client.autofocus()
        }
    }

    func halfPressShutter() async throws -> CameraStatus {
        switch self {
        case let .ccapi(client): return try await client.halfPressShutter()
        case let .desktopBridge(client): return try await client.halfPressShutter()
        }
    }

    func recordingOutcome(starting: Bool, attempt: CameraRecordingAttempt) async throws -> CameraRecordingOutcome {
        switch self {
        case let .ccapi(client): return try await client.recordingOutcome(starting: starting, attempt: attempt)
        case let .desktopBridge(client): return try await client.recordingOutcome(starting: starting, attempt: attempt)
        }
    }

    func recordingObservation() async throws -> CameraRecordingObservation {
        switch self {
        case let .ccapi(client): return try await client.recordingObservation()
        case let .desktopBridge(client): return try await client.recordingObservation()
        }
    }

    func recordingCommandEvidence() async -> CameraRecordingCommandEvidence? {
        switch self {
        case let .ccapi(client): return await client.recordingCommandEvidence
        case let .desktopBridge(client): return await client.recordingCommandEvidence
        }
    }

    func startRecording() async throws -> CameraStatus {
        switch self {
        case let .ccapi(client): return try await client.startRecording()
        case let .desktopBridge(client): return try await client.startRecording()
        }
    }

    func stopRecording() async throws -> CameraStatus {
        switch self {
        case let .ccapi(client): return try await client.stopRecording()
        case let .desktopBridge(client): return try await client.stopRecording()
        }
    }

    func tapFocus(x: Double, y: Double) async throws -> FocusResult {
        switch self {
        case let .ccapi(client): return try await client.tapFocus(x: x, y: y)
        case let .desktopBridge(client): return try await client.tapFocus(x: x, y: y)
        }
    }

    func clickWhiteBalance(x: Double, y: Double) async throws -> CameraStatus {
        switch self {
        case let .ccapi(client): return try await client.clickWhiteBalance(x: x, y: y)
        case let .desktopBridge(client): return try await client.clickWhiteBalance(x: x, y: y)
        }
    }

    func driveFocus(direction: FocusDriveDirection, step: FocusDriveStep) async throws -> FocusDriveResult {
        switch self {
        case let .ccapi(client):
            return try await client.driveFocus(direction: direction, step: step)
        case let .desktopBridge(client):
            return try await client.driveFocus(direction: direction, step: step)
        }
    }

    func setLiveViewMagnification(
        _ magnification: LiveViewMagnification
    ) async throws -> LiveViewMagnificationResult {
        switch self {
        case let .ccapi(client):
            return try await client.setLiveViewMagnification(magnification)
        case let .desktopBridge(client):
            return try await client.setLiveViewMagnification(magnification)
        }
    }

    func startLiveView(_ request: LiveViewRequest) async throws {
        switch self {
        case let .ccapi(client): try await client.startLiveView(request)
        case let .desktopBridge(client): try await client.startLiveView(request)
        }
    }

    func stopLiveView() async {
        switch self {
        case let .ccapi(client): await client.stopLiveView()
        case let .desktopBridge(client): await client.stopLiveView()
        }
    }

    func maintenanceRetirementUnconfirmed() async -> Bool {
        switch self {
        case .ccapi: return false
        case let .desktopBridge(client): return await client.maintenanceRetirementUnconfirmed
        }
    }

    func liveViewStopState() async -> CameraLiveViewStopState {
        switch self {
        case let .ccapi(client): return await client.liveViewStopState()
        case let .desktopBridge(client): return await client.liveViewStopState()
        }
    }

    /// Local receiver teardown only. Never changes the original camera Stop duty.
    func retireLiveViewReader() async {
        if case let .ccapi(client) = self { await client.retireLiveViewReader() }
    }

    func retryLiveViewStop() async throws {
        switch self {
        case let .ccapi(client): try await client.retryLiveViewStop()
        case let .desktopBridge(client): try await client.retryLiveViewStop()
        }
    }

    func liveViewFrame(cacheKey: Int64) async throws -> LiveViewFrame {
        switch self {
        case let .ccapi(client): return try await client.liveViewFrame(cacheKey: cacheKey)
        case let .desktopBridge(client): return try await client.liveViewFrame(cacheKey: cacheKey)
        }
    }

    func currentLiveViewSource() async -> LiveViewSource? {
        switch self {
        case let .ccapi(client): return await client.currentLiveViewSource()
        case .desktopBridge: return .desktopBridgeStream
        }
    }

    func currentLiveViewSize() async -> LiveViewSize? {
        switch self {
        case let .ccapi(client): return await client.currentLiveViewSize()
        case .desktopBridge: return nil
        }
    }

    func currentNativeLiveViewSourceURL() async -> URL? {
        switch self {
        case let .ccapi(client): return await client.currentNativeLiveViewSourceURL()
        case .desktopBridge: return nil
        }
    }

    func setLiveViewTargetFPS(_ fps: Int) async -> LiveViewTargetFPSUpdate {
        switch self {
        case let .ccapi(client):
            await client.setLiveViewTargetFPS(fps)
            return .appliedInPlace
        case .desktopBridge:
            return .restartRequired
        }
    }

    func listMedia(
        maximumItems: Int? = nil,
        onProgress: CameraMediaListProgressHandler = { _ in }
    ) async throws -> [CameraMediaItem] {
        switch self {
        case let .ccapi(client):
            return try await client.listMedia(maximumItems: maximumItems, onProgress: onProgress)
        case let .desktopBridge(client):
            return try await client.listMedia(maximumItems: maximumItems, onProgress: onProgress)
        }
    }

    func mediaThumbnail(_ item: CameraMediaItem) async throws -> CameraMediaThumbnail {
        switch self {
        case let .ccapi(client): return try await client.mediaThumbnail(item)
        case let .desktopBridge(client): return try await client.mediaThumbnail(item)
        }
    }

    func mediaPreview(_ item: CameraMediaItem) async throws -> CameraMediaPreview {
        switch self {
        case let .ccapi(client): return try await client.mediaPreview(item)
        case let .desktopBridge(client): return try await client.mediaPreview(item)
        }
    }

    func beginMediaPlayback(_ item: CameraMediaItem) async throws -> CameraMediaPlaybackStream {
        guard CameraMediaType.classify(item) == .video else { throw URLError(.unsupportedURL) }
        let playbackItem: CameraMediaItem
        if item.sizeBytes?.isPositive == true {
            playbackItem = item
        } else {
            do {
                playbackItem = try await mediaInfo(item)
            } catch is CancellationError {
                throw CancellationError()
            } catch let error as CameraMediaMetadataError {
                throw error
            } catch let error as DesktopBridgeError {
                // A malformed/mismatched metadata reply must not silently turn
                // into a playback allocation using stale listing evidence.
                if case .invalidResponse = error { throw error }
                playbackItem = item
            } catch {
                playbackItem = item
            }
        }
        guard item.id.utf8.elementsEqual(playbackItem.id.utf8),
              item.name.utf8.elementsEqual(playbackItem.name.utf8),
              CameraMediaType.classify(playbackItem) == .video else { throw URLError(.unsupportedURL) }
        switch self {
        case let .ccapi(client):
            return CameraMediaPlaybackStream(item: playbackItem, backend: .ccapi(client))
        case let .desktopBridge(client):
            return CameraMediaPlaybackStream(
                item: playbackItem,
                backend: .desktopBridge(try await client.beginMediaPlayback(playbackItem))
            )
        }
    }

    func mediaInfo(_ item: CameraMediaItem) async throws -> CameraMediaItem {
        switch self {
        case let .ccapi(client): return try await client.mediaInfo(item)
        case let .desktopBridge(client): return try await client.mediaInfo(item)
        }
    }

    func mutateMedia(_ item: CameraMediaItem, target: CameraMediaMutation,
                     attempt: CameraMediaMutationAttempt) async throws -> CameraMediaItem {
        switch (self, target) {
        case let (.ccapi(client), .rating(value)): return try await client.setMediaRating(item, rating: value, attempt: attempt)
        case let (.ccapi(client), .protection(value)): return try await client.setMediaProtection(item, enabled: value, attempt: attempt)
        case let (.ccapi(client), .rotation(value)): return try await client.setMediaRotation(item, degrees: value, attempt: attempt)
        case let (.ccapi(client), .archive(value)): return try await client.setMediaArchive(item, enabled: value, attempt: attempt)
        case let (.desktopBridge(client), .rating(value)): return try await client.setMediaRating(item, rating: value, attempt: attempt)
        case let (.desktopBridge(client), .protection(value)): return try await client.setMediaProtection(item, enabled: value, attempt: attempt)
        case let (.desktopBridge(client), .rotation(value)): return try await client.setMediaRotation(item, degrees: value, attempt: attempt)
        case let (.desktopBridge(client), .archive(value)): return try await client.setMediaArchive(item, enabled: value, attempt: attempt)
        }
    }

    func setMediaProtection(_ item: CameraMediaItem, enabled: Bool) async throws -> CameraMediaItem {
        switch self {
        case let .ccapi(client): return try await client.setMediaProtection(item, enabled: enabled)
        case let .desktopBridge(client): return try await client.setMediaProtection(item, enabled: enabled)
        }
    }

    func setMediaRating(_ item: CameraMediaItem, rating: Int) async throws -> CameraMediaItem {
        switch self {
        case let .ccapi(client): return try await client.setMediaRating(item, rating: rating)
        case let .desktopBridge(client): return try await client.setMediaRating(item, rating: rating)
        }
    }

    func setMediaRotation(_ item: CameraMediaItem, degrees: Int) async throws -> CameraMediaItem {
        switch self {
        case let .ccapi(client): return try await client.setMediaRotation(item, degrees: degrees)
        case let .desktopBridge(client): return try await client.setMediaRotation(item, degrees: degrees)
        }
    }

    func downloadMedia(
        _ item: CameraMediaItem,
        to destination: URL,
        progress: @escaping CameraMediaProgressHandler = { _ in }
    ) async throws -> CameraMediaDownload {
        switch self {
        case let .ccapi(client):
            return try await client.downloadMedia(item, to: destination, progress: progress)
        case let .desktopBridge(client):
            return try await client.downloadMedia(item, to: destination, progress: progress)
        }
    }

    func setMediaArchive(_ item: CameraMediaItem, enabled: Bool) async throws -> CameraMediaItem {
        switch self {
        case let .ccapi(client): return try await client.setMediaArchive(item, enabled: enabled)
        case let .desktopBridge(client): return try await client.setMediaArchive(item, enabled: enabled)
        }
    }

    func uploadMedia(
        from fileURL: URL,
        contentType: String? = nil,
        attempt: CameraMediaUploadAttempt,
        progress: @escaping CameraMediaProgressHandler = { _ in }
    ) async throws -> CameraMediaItem {
        switch self {
        case let .ccapi(client):
            return try await client.uploadMedia(from: fileURL, contentType: contentType, attempt: attempt, progress: progress)
        case let .desktopBridge(client):
            return try await client.uploadMedia(from: fileURL, contentType: contentType, attempt: attempt, progress: progress)
        }
    }

    func deleteMedia(_ item: CameraMediaItem, attempt: CameraMediaDeleteAttempt) async throws {
        switch self {
        case let .ccapi(client): try await client.deleteMedia(item, attempt: attempt)
        case let .desktopBridge(client): try await client.deleteMedia(item, attempt: attempt)
        }
    }

    func mediaDeleteBridgeEndpoint() async -> String? {
        if case let .desktopBridge(client) = self { return await client.sanitizedBaseURL().absoluteString }
        return nil
    }

    func mediaDeleteEngine() async -> String? {
        switch self {
        case .ccapi: return nil
        case let .desktopBridge(client): return await client.mediaDeleteEngine()
        }
    }

    var mediaDeleteUsesBridge: Bool {
        if case .desktopBridge = self { return true }
        return false
    }

    func diagnosticReport(
        snapshot: CameraSnapshot?,
        liveView: CCAPILiveViewMetrics,
        lastError: String?
    ) async -> String {
        switch self {
        case let .ccapi(client):
            return await client.diagnosticReport(snapshot: snapshot, liveView: liveView, lastError: lastError)
        case let .desktopBridge(client):
            return await client.diagnosticReport(snapshot: snapshot, liveView: liveView, lastError: lastError)
        }
    }

    func close() async {
        switch self {
        case let .ccapi(client): await client.close()
        case let .desktopBridge(client): await client.close()
        }
    }
}

actor CameraMediaPlaybackStream {
    enum Backend: Sendable {
        case ccapi(CCAPIClient)
        case desktopBridge(DesktopBridgeMediaPlayback)
    }

    nonisolated let item: CameraMediaItem
    private let backend: Backend
    private var closed = false

    init(item: CameraMediaItem, backend: Backend) {
        self.item = item
        self.backend = backend
    }

    func open(offset: Int64, length: Int64?) async throws -> CameraMediaStreamResponse {
        guard !closed else { throw CancellationError() }
        let response: CameraMediaStreamResponse
        switch backend {
        case let .ccapi(client):
            response = try await client.openMediaStream(item, offset: offset, length: length)
        case let .desktopBridge(playback):
            response = try await playback.open(offset: offset, length: length)
        }
        guard !closed else {
            response.cancel()
            throw CancellationError()
        }
        return response
    }

    func close() async {
        guard !closed else { return }
        closed = true
        if case let .desktopBridge(playback) = backend {
            await playback.close()
        }
    }
}

private extension Int64 {
    var isPositive: Bool { self > 0 }
}
