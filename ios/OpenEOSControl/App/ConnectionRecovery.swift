import OpenEOSCore

enum BridgeScanState: Equatable {
    case notScanned, scanning, failed, completed
}

/// Presentation-only evidence. No endpoint, credentials, response text, or persistence.
struct ConnectionRecovery: Equatable {
    enum Operation: Equatable { case connect, scan }
    enum Source: Equatable { case selectedEndpoint, cameraViaBridge }

    let mode: AppConnectionMode
    let operation: Operation
    let reason: CameraConnectionFailureReason
    let source: Source

    init(mode: AppConnectionMode, operation: Operation, reason: CameraConnectionFailureReason,
         source: Source = .selectedEndpoint) {
        self.mode = mode
        self.operation = operation
        self.reason = reason
        self.source = source
    }

    static func classify(_ error: Error, mode: AppConnectionMode, operation: Operation) -> Self? {
        guard let reason = CameraConnectionFailureReason.classify(error) else { return nil }
        return Self(mode: mode, operation: operation, reason: reason,
                    source: error is DesktopBridgeCameraConnectionError ? .cameraViaBridge : .selectedEndpoint)
    }

    var canEditLocalSettings: Bool { source != .cameraViaBridge }

    var needsCameraAuthentication: Bool {
        canEditLocalSettings && mode == .ccapi && reason == .authenticationRejected
    }

    var messageKey: String {
        if source == .cameraViaBridge {
            switch reason {
            case .authenticationRejected: return "connection_bridge_camera_authentication"
            case .tlsError: return "connection_bridge_camera_tls_error"
            case .hostNotFound: return "connection_bridge_camera_host_not_found"
            case .timeout: return "connection_bridge_camera_timeout"
            case .unreachable: return "connection_bridge_camera_unreachable"
            case .httpError: return "connection_bridge_camera_http_error"
            case .endpointNotFound: return "connection_bridge_camera_endpoint_missing"
            default: return "connection_bridge_camera_discovery_failed"
            }
        }
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

    var nextStepKey: String {
        if source == .cameraViaBridge { return "connection_bridge_camera_retry_help" }
        return operation == .scan ? "connection_edit_then_scan" : "connection_edit_then_connect"
    }

    var retryKey: String { operation == .scan ? "connection_retry_scan" : "connection_retry_connect" }
}
