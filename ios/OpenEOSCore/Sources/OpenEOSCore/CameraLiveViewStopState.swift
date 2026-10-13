import Foundation

/// Session-bound cleanup responsibility. This is not physical camera-state evidence.
public struct CameraLiveViewStopState: Equatable, Sendable {
    public let stopRequired: Bool
    public let stopUnconfirmed: Bool
    public let source: LiveViewSource?

    public init(stopRequired: Bool = false, stopUnconfirmed: Bool = false, source: LiveViewSource? = nil) {
        self.stopRequired = stopRequired
        self.stopUnconfirmed = stopUnconfirmed
        self.source = source
    }

    public static let idle = CameraLiveViewStopState()
}
