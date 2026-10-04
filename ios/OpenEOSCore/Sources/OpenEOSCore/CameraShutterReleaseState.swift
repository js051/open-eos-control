import Foundation

/// Local, session-bound responsibility for a possibly held shutter.
/// `releaseRequired` is not evidence that an exposure is physically active.
public struct CameraShutterReleaseState: Equatable, Sendable {
    /// Whether this live connection still owns a retryable release command.
    /// A retired connection may be false here while `releaseUnconfirmed` stays true.
    public let releaseRequired: Bool
    /// A safety warning; false `releaseRequired` alone never proves physical release.
    public let releaseUnconfirmed: Bool
    public let bulbExposureActive: Bool?

    public init(
        releaseRequired: Bool = false,
        releaseUnconfirmed: Bool = false,
        bulbExposureActive: Bool? = false
    ) {
        self.releaseRequired = releaseRequired
        self.releaseUnconfirmed = releaseUnconfirmed
        self.bulbExposureActive = bulbExposureActive
    }

    public static let idle = CameraShutterReleaseState()
}
