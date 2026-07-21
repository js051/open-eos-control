import Foundation

public enum CCAPIError: Error, Equatable, Sendable {
    case invalidBaseURL(String)
    case notInitialized
    case discoveryFailed([String])
    case http(statusCode: Int, method: String, url: String, body: String)
    case invalidResponse(String)
    case unsupported(CameraFeature)
    case invalidSetting(key: String, value: String)
    case outsideCameraOrigin(String)
    case destinationExists(String)
    case operationAndReleaseFailed(operation: String, release: String)
}

extension CCAPIError: LocalizedError {
    public var errorDescription: String? {
        switch self {
        case let .invalidBaseURL(value):
            "Invalid camera URL: \(value)"
        case .notInitialized:
            "The CCAPI client has not finished discovery."
        case let .discoveryFailed(errors):
            "Camera CCAPI discovery failed.\n" + errors.map { "- \($0)" }.joined(separator: "\n")
        case let .http(statusCode, method, url, body):
            "Camera request failed: \(method) \(url) returned HTTP \(statusCode).\nBody: \(body)"
        case let .invalidResponse(message):
            message
        case let .unsupported(feature):
            "The camera did not advertise \(feature.rawValue)."
        case let .invalidSetting(key, value):
            "The camera did not advertise value '\(value)' for setting '\(key)'."
        case let .outsideCameraOrigin(value):
            "The camera returned a resource outside the active camera origin: \(value)"
        case let .destinationExists(value):
            "The media destination already exists: \(value)"
        case let .operationAndReleaseFailed(operation, release):
            "The camera operation failed (\(operation)); releasing the shutter also failed (\(release))."
        }
    }
}
