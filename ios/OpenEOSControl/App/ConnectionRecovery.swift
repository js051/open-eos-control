import OpenEOSCore

enum BridgeScanState: Equatable {
    case notScanned, scanning, failed, completed
}

/// Presentation-only evidence. No endpoint, credentials, response text, or persistence.
struct ConnectionRecovery: Equatable {
    enum Operation: Equatable { case connect, scan }

    let mode: AppConnectionMode
    let operation: Operation
    let reason: CameraConnectionFailureReason

    var needsCameraAuthentication: Bool {
        mode == .ccapi && reason == .authenticationRejected
    }

    var messageKey: String {
        let prefix = mode == .ccapi ? "connection_camera_" : "connection_bridge_"
        switch reason {
        case .invalidAddress: return prefix + "invalid_address"
        case .authenticationRejected: return prefix + "authentication"
        case .endpointNotFound: return prefix + "endpoint_missing"
        case .tlsError: return "connection_tls_error"
        case .hostNotFound: return "connection_host_not_found"
        case .timeout: return "connection_timeout"
        case .unreachable: return prefix + "unreachable"
        case .httpError: return "connection_http_error"
        case .discoveryFailed: return "connection_discovery_failed"
        case .unknown: return "connection_unknown_error"
        }
    }

    var retryKey: String { operation == .scan ? "connection_retry_scan" : "connection_retry_connect" }
}
