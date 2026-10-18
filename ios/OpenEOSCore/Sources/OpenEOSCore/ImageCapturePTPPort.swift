import Foundation

/// The value-only boundary for a future ImageCaptureCore adapter. This is not a
/// selectable camera provider and contains no PTP encoder or Apple framework calls.
public enum ImageCapturePTP {
    public struct Discovery: Hashable, Sendable {
        private let nonce = UUID()
        public init() {}
    }

    /// One retained ICCameraDevice object, not a name, VID/PID, serial, or UUID string.
    /// A port must not mint a second handle for an object with unresolved cleanup,
    /// including when the browser restarts. A newly attached object gets a new handle.
    public struct Handle: Hashable, Sendable {
        private let nonce = UUID()
        public init() {}
    }

    public struct Camera: Equatable, Sendable {
        public enum Transport: Equatable, Sendable { case usb, other, unknown }
        public let handle: Handle
        public let displayName: String?
        public let reportedUUID: String?
        public let vendorID: Int32
        public let productID: Int32
        public let transport: Transport
        public let acceptsPTP: Bool

        public init(handle: Handle, displayName: String? = nil, reportedUUID: String? = nil,
                    vendorID: Int32 = 0, productID: Int32 = 0,
                    transport: Transport, acceptsPTP: Bool) {
            self.handle = handle
            self.displayName = displayName
            self.reportedUUID = reportedUUID
            self.vendorID = vendorID
            self.productID = productID
            self.transport = transport
            self.acceptsPTP = acceptsPTP
        }
    }

    public enum Authorization: Equatable, Sendable {
        case authorized, notDetermined, denied, restricted, unknown
    }

    /// Contents authorization is deliberately separate. It cannot grant control.
    public struct AuthorizationSnapshot: Equatable, Sendable {
        public var control: Authorization
        public var contents: Authorization
        public init(control: Authorization, contents: Authorization) {
            self.control = control
            self.contents = contents
        }
    }

    public struct Session: Hashable, Sendable {
        public let handle: Handle
        private let generation = UUID()
        init(handle: Handle) { self.handle = handle }
    }

    /// Correlation only. This ID is NOT a PTP transaction ID or an ICC session ID.
    public struct Request: Hashable, Sendable {
        public let session: Session
        private let nonce = UUID()
        init(session: Session) { self.session = session }
    }

    public enum Failure: Equatable, Sendable {
        case nativeFailure
        case unsupportedWireContract
    }

    /// Names/order follow Apple's Objective-C completion declaration. No interpretation
    /// of these buffers, response code, packet layout, or transaction ownership is made.
    public struct CompletionBuffers: Equatable, Sendable {
        public let responseData: Data
        public let ptpResponseData: Data
        public init(responseData: Data, ptpResponseData: Data) {
            self.responseData = responseData
            self.ptpResponseData = ptpResponseData
        }
    }

    public struct Receipt: Equatable, Sendable {
        public let request: Request
        public let buffers: CompletionBuffers
        public let failure: Failure?
    }

    public enum RetirementReason: Equatable, Sendable {
        case selectionChanged, cancelled, suspended, discoveryStopped, removed
        case authorizationLost, authorizationTimedOut, openFailed, openTimedOut
        case exchangeTimedOut, exchangeFailed, sessionClosed
    }

    public enum DeviceInfoOutcome: Equatable, Sendable {
        /// Even empty/malformed bytes cannot become a successful DeviceInfo result here.
        /// Parsing requires a separately verified native wire contract.
        case unvalidatedBuffers(Receipt)
        case frameworkFailure(Receipt)
        /// No physical cancel/Stop/Release acknowledgment is implied.
        case outcomeUnknown(Request, RetirementReason)
        case notDispatched(Request, RetirementReason)
    }

    public enum Block: Equatable, Sendable {
        case notDiscovered, notUSB, ptpUnavailable, cleanupPending, cleanupCapacityReached
        case authorizationNotGranted(Authorization)
    }

    public enum Phase: Equatable, Sendable {
        case idle
        case awaitingAuthorization(Request)
        case opening(Request, didOpen: Bool, didBecomeReady: Bool)
        case ready
        case readingDeviceInfo(Request)
        case blocked(Block)
        case retired(RetirementReason)
    }

    public enum Cleanup: Equatable, Sendable {
        /// Never discard this when a local open deadline expires.
        case awaitingOpen(Request)
        case closing(Request, deadlineExpired: Bool)
        /// The object remains quarantined; a failed close does not release ownership.
        case closeFailed(Request)
    }

    public struct ReceivedEvent: Equatable, Sendable {
        public let session: Session
        public let bytes: Data
    }

    /// Inputs must be serialized. Native callbacks capture the original handle,
    /// session and request before dispatching to that executor, never look up "current".
    public enum Event: Equatable, Sendable {
        case startDiscovery
        case stopDiscovery
        case suspend
        case discovered(Discovery, Camera)
        case removed(Discovery, Handle)
        case select(Handle, requestControlAuthorization: Bool)
        case authorizationChanged(AuthorizationSnapshot)
        case authorizationCompleted(Request, Authorization)
        case openCompleted(Request, Failure?)
        case becameReady(Session)
        case sessionClosed(Session)
        case readDeviceInfo
        case deviceInfoCompleted(Request, CompletionBuffers, Failure?)
        case ptpEvent(Session, Data)
        case cancel
        case deadlineExpired(Request)
        case closeCompleted(Request, Failure?)
        case retryFailedClose(Session)
    }

    public enum Effect: Equatable, Sendable {
        case startDiscovery(Discovery)
        /// Stop only this retained browser generation, never whichever browser is current.
        case stopDiscovery(Discovery)
        case requestControlAuthorization(Request)
        /// The port installs its device delegate and exactly one event path BEFORE
        /// opening. Use ptpEventHandler only; do not also forward delegate PTP events.
        case openSession(Request)
        /// Semantic read-only operation. No arbitrary packet/outData escape hatch.
        /// A native implementation must fail with unsupportedWireContract until its
        /// DeviceInfo encoding and the Apple completion contract have been validated.
        case readDeviceInfo(Request)
        /// Disable event admission, but retain the exact object and pending open callback.
        case retireSession(Session)
        case closeSession(Request)
        /// Release only this old session's callbacks/retention after confirmed close.
        /// Never close or remove a handle binding now owned by a newer session.
        /// Only emitted after confirmed close completion, never after a local deadline.
        case releaseSession(Session)
        /// Local waiting budgets, not promises made by ImageCaptureCore or the camera.
        case armDeadline(Request, seconds: UInt)
        case cancelDeadline(Request)
    }
}

/// Injected adapter interface. No concrete native adapter is enabled by this slice.
///
/// The owner processes each emitted effect once and serializes inputs and effects.
/// Non-request cleanup, discovery, and deadline operations must be idempotent and
/// owner-scoped: replaying old cleanup must never affect a new session/browser.
/// Recheck
/// `admit(_:)` immediately before performing an effect (including after any
/// reentrant callback). Native objects and callback marshalling belong inside the
/// adapter. The adapter must preserve nil versus empty outbound data internally;
/// this public interface intentionally cannot supply either as arbitrary PTP bytes.
/// It must not issue raw OpenSession/CloseSession packets in addition to the ICC APIs.
/// No method here authorizes permission prompts or physical-device access by itself.
public protocol ImageCapturePTPPort: AnyObject {
    var receive: ((ImageCapturePTP.Event) -> Void)? { get set }
    func perform(_ effect: ImageCapturePTP.Effect)
}
