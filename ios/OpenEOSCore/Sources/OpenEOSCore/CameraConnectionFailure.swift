import Foundation

/// Bounded evidence for connection guidance. Never retains addresses, credentials,
/// server text, or an underlying error. Use only at connect/discovery boundaries.
public enum CameraConnectionFailureReason: String, CaseIterable, Hashable, Sendable {
    case invalidAddress
    case authenticationRejected
    case endpointNotFound
    case httpError
    case tlsError
    case hostNotFound
    case timeout
    case unreachable
    case discoveryFailed
    case unknown

    public static func classify(_ error: Error) -> Self? {
        if isCancellation(error) { return nil }
        switch error {
        case CCAPIError.invalidBaseURL, DesktopBridgeError.invalidBaseURL:
            return .invalidAddress
        case let CCAPIError.http(status, _, _, _):
            return httpStatus(status)
        case let DesktopBridgeError.http(status, _, _, _, _, _, _):
            return httpStatus(status)
        case let CCAPIError.discoveryFailedWithReasons(_, reasons),
             let CCAPIError.connectionReadFailed(_, reasons):
            return preferredDiscoveryReason(reasons)
        case CCAPIError.discoveryFailed:
            // Legacy diagnostic strings are never evidence of HTTP or transport status.
            return .discoveryFailed
        case let error as URLError:
            switch error.code {
            case .badURL, .unsupportedURL: return .invalidAddress
            case .secureConnectionFailed, .serverCertificateHasBadDate,
                 .serverCertificateUntrusted, .serverCertificateHasUnknownRoot,
                 .serverCertificateNotYetValid, .clientCertificateRejected,
                 .clientCertificateRequired: return .tlsError
            case .cannotFindHost, .dnsLookupFailed: return .hostNotFound
            case .timedOut: return .timeout
            case .cannotConnectToHost, .networkConnectionLost, .notConnectedToInternet,
                 .internationalRoamingOff, .callIsActive, .dataNotAllowed: return .unreachable
            default: return .unknown
            }
        default: return .unknown
        }
    }

    public static func isCancellation(_ error: Error) -> Bool {
        error is CancellationError || (error as? URLError)?.code == .cancelled
    }

    static func httpStatus(_ status: Int) -> Self {
        switch status {
        case 401, 403: .authenticationRejected
        case 404: .endpointNotFound
        case 200..<300: .unknown
        default: .httpError
        }
    }

    static func preferredDiscoveryReason(_ reasons: Set<Self>) -> Self {
        // A later missing endpoint must not erase stronger observed evidence.
        let priority: [Self] = [
            .invalidAddress, .authenticationRejected, .tlsError, .hostNotFound,
            .timeout, .unreachable, .httpError, .discoveryFailed, .unknown, .endpointNotFound,
        ]
        return priority.first(where: reasons.contains) ?? .discoveryFailed
    }
}
