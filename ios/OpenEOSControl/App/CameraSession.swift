import Foundation
import OpenEOSCore

enum CameraSession: Sendable {
    case ccapi(CCAPIClient)
    case desktopBridge(DesktopBridgeClient)

    func connectSnapshot() async throws -> CameraSnapshot {
        switch self {
        case let .ccapi(client): return try await client.connectSnapshot()
        case let .desktopBridge(client): return try await client.connectSnapshot()
        }
    }

    func capabilities() async throws -> CameraCapabilities {
        switch self {
        case let .ccapi(client): return try await client.capabilities()
        case let .desktopBridge(client): return try await client.capabilities()
        }
    }

    func setSetting(key: String, value: String) async throws -> CameraStatus {
        switch self {
        case let .ccapi(client): return try await client.setSetting(key: key, value: value)
        case let .desktopBridge(client): return try await client.setSetting(key: key, value: value)
        }
    }

    func captureStill() async throws -> CameraStatus {
        switch self {
        case let .ccapi(client): return try await client.captureStill()
        case let .desktopBridge(client): return try await client.captureStill()
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
        case .ccapi:
            throw CCAPIError.unsupported(.liveViewMagnification)
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

    func currentNativeLiveViewSourceURL() async -> URL? {
        switch self {
        case let .ccapi(client): return await client.currentNativeLiveViewSourceURL()
        case .desktopBridge: return nil
        }
    }

    func setLiveViewTargetFPS(_ fps: Int) async {
        switch self {
        case let .ccapi(client): await client.setLiveViewTargetFPS(fps)
        case .desktopBridge: break
        }
    }

    func listMedia() async throws -> [CameraMediaItem] {
        switch self {
        case let .ccapi(client): return try await client.listMedia()
        case let .desktopBridge(client): return try await client.listMedia()
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

    func downloadMedia(_ item: CameraMediaItem, to destination: URL) async throws -> CameraMediaDownload {
        switch self {
        case let .ccapi(client): return try await client.downloadMedia(item, to: destination)
        case let .desktopBridge(client): return try await client.downloadMedia(item, to: destination)
        }
    }

    func deleteMedia(_ item: CameraMediaItem) async throws {
        switch self {
        case let .ccapi(client): try await client.deleteMedia(item)
        case let .desktopBridge(client): try await client.deleteMedia(item)
        }
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
        case let .ccapi(client): await client.stopLiveView()
        case let .desktopBridge(client): await client.close()
        }
    }
}
