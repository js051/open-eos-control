import Foundation

public enum CCAPIError: Error, Equatable, Sendable {
    case invalidBaseURL(String)
    case liveViewStopUnconfirmed
    case notInitialized
    case discoveryFailed([String])
    // The legacy case remains available to existing callers and fixtures.
    case discoveryFailedWithReasons([String], Set<CameraConnectionFailureReason>)
    case connectionReadFailed([String], Set<CameraConnectionFailureReason>)
    case http(statusCode: Int, method: String, url: String, body: String)
    case invalidResponse(String)
    case unsupported(CameraFeature)
    case temperatureRestriction(CameraFeature)
    case invalidSetting(key: String, value: String)
    case outsideCameraOrigin(String)
    case destinationExists(String)
    case operationAndReleaseFailed(operation: String, release: String)
    case captureStatusReadbackFailed
    case recordingStatusReadbackFailed(starting: Bool)
    case recordingCommandUnconfirmed(starting: Bool)
}

extension CCAPIError: LocalizedError {
    public var errorDescription: String? {
        switch self {
        case let .invalidBaseURL(value):
            "Invalid camera URL: \(value)"
        case .liveViewStopUnconfirmed:
            "Live View stop is not confirmed. Retry Stop before starting Live View again."
        case .notInitialized:
            "The CCAPI client has not finished discovery."
        case let .discoveryFailed(errors), let .discoveryFailedWithReasons(errors, _):
            "Camera CCAPI discovery failed.\n" + errors.map { "- \($0)" }.joined(separator: "\n")
        case let .connectionReadFailed(errors, _):
            "Camera JSON request failed.\n" + errors.map { "- \($0)" }.joined(separator: "\n")
        case let .http(statusCode, method, url, body):
            "Camera request failed: \(method) \(url) returned HTTP \(statusCode).\nBody: \(body)"
        case let .invalidResponse(message):
            message
        case let .unsupported(feature):
            "The camera did not advertise \(feature.rawValue)."
        case let .temperatureRestriction(feature):
            "The camera reported a temperature restriction for \(feature.rawValue)."
        case let .invalidSetting(key, value):
            "The camera did not advertise value '\(value)' for setting '\(key)'."
        case let .outsideCameraOrigin(value):
            "The camera returned a resource outside the active camera origin: \(value)"
        case let .destinationExists(value):
            "The media destination already exists: \(value)"
        case .recordingCommandUnconfirmed:
            "The recording command result is unknown. Check the camera and explicitly stop recording; refreshing cached status cannot confirm it stopped."
        case .captureStatusReadbackFailed:
            "The shutter command was acknowledged, but camera status could not be read. Check recent media without taking another photo."
        case .recordingStatusReadbackFailed:
            "The recording command was acknowledged, but its status could not be read. Refresh status or explicitly stop recording."
        case let .operationAndReleaseFailed(operation, release):
            "The camera operation failed (\(operation)); releasing the shutter also failed (\(release))."
        }
    }
}
