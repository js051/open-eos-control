#if canImport(ImageCaptureCore) && os(iOS)
import Foundation
import Dispatch
import CoreGraphics
import ImageCaptureCore

/// Isolated, opt-in source boundary. Apple branch NOT_COMPILED in the Linux receipt.
/// No product entry point uses this adapter. Construction never starts discovery or
/// requests authorization. DeviceInfo wire and reuse of a native object are unsupported.
/// Keep the port/coordinator alive until discovery stops and native cleanup settles.
/// All state and native calls are main-thread confined; callbacks carry immutable owners.
public final class ImageCapturePTPApplePort: ImageCapturePTPPort {
    public typealias PTP = ImageCapturePTP
    public enum FactoryError: Error { case adapterAlreadyCreated }
    /// Independent source-slice retention budget, not the reducer's cleanup budget.
    /// No evictions: at most four different native objects for the process lifetime.
    public static let maximumRetainedDevices = 4
    // Main-thread-only, deliberately strong and never reset. Dropping a caller's
    // references cannot drop native duties or permit a second owner to rebind objects.
    private static var lifetimeLease: (port: ImageCapturePTPApplePort, owner: ImageCapturePTPCoordinator)?
    public var receive: ((PTP.Event) -> Void)?
    private var browsers: [PTP.Discovery: BrowserContext] = [:]
    private var records: [ObjectIdentifier: DeviceRecord] = [:]
    private var handles: [PTP.Handle: DeviceRecord] = [:]
    private var sessions: [PTP.Session: DeviceContext] = [:]
    private var deadlines: [PTP.Request: DispatchWorkItem] = [:]

    private init() { precondition(Thread.isMainThread) }

    /// No native API starts here. This restricted source slice is process-single-use:
    /// a second make fails even after confirmed cleanup. The process retains both values
    /// until exit, never treating deinit as physical cleanup. Subsequent app inputs must
    /// be authorized by the host. Only this factory binds the main-thread owner.
    public static func make() throws -> (port: ImageCapturePTPApplePort, owner: ImageCapturePTPCoordinator) {
        precondition(Thread.isMainThread)
        guard lifetimeLease == nil else { throw FactoryError.adapterAlreadyCreated }
        let port = ImageCapturePTPApplePort()
        let owner = try ImageCapturePTPCoordinator(port: port, executor: .mainThread)
        lifetimeLease = (port, owner)
        return (port, owner)
    }

    public func perform(_ effect: PTP.Effect) {
        precondition(Thread.isMainThread)
        switch effect {
        case .startDiscovery(let discovery):
            guard browsers[discovery] == nil else { return }
            let context = BrowserContext(owner: discovery, port: self)
            browsers[discovery] = context
            context.browser.delegate = context
            context.browser.start()
            publishAuthorization(context.browser)
        case .stopDiscovery(let discovery):
            guard let context = browsers.removeValue(forKey: discovery) else { return }
            context.browser.stop() // Only this generation; session records stay retained.
        case .requestControlAuthorization(let request):
            guard let record = handles[request.session.handle],
                  let context = browsers[record.discovery] else {
                emit(.authorizationCompleted(request, .unknown)); return
            }
            context.browser.requestControlAuthorization { [weak self, context] status in
                Self.onMain {
                    _ = context // Retain the exact browser through its completion.
                    self?.emit(.authorizationCompleted(request, Self.authorization(status)))
                }
            }
        case .openSession(let request):
            open(request)
        case .readDeviceInfo(let request):
            emit(.deviceInfoCompleted(request, .init(responseData: Data(), ptpResponseData: Data()),
                                      .unsupportedWireContract))
        case .retireSession(let session):
            sessions[session]?.retired = true
        case .closeSession(let request):
            close(request)
        case .releaseSession(let session):
            guard let context = sessions[session], context.closeConfirmed else { return }
            context.retired = true
            // No nil-import assumption and no fallback delegate PTP path.
            context.record.device.ptpEventHandler = { _ in }
            context.record.device.delegate = nil
            sessions.removeValue(forKey: session)
            // Retain the used object/handle tombstone: an address may not be reminted
            // into a second first-session capability while this adapter lives.
        case .armDeadline(let request, let seconds):
            guard deadlines[request] == nil else { return }
            let work = DispatchWorkItem { [weak self] in
                guard let self, self.deadlines.removeValue(forKey: request) != nil else { return }
                self.emit(.deadlineExpired(request))
            }
            deadlines[request] = work
            DispatchQueue.main.asyncAfter(deadline: .now() + Double(seconds), execute: work)
        case .cancelDeadline(let request):
            deadlines.removeValue(forKey: request)?.cancel()
        }
    }

    private func open(_ request: PTP.Request) {
        guard let record = handles[request.session.handle] else {
            emit(.openNotDispatched(request, .nativeFailure)); return
        }
        guard !record.used else {
            emit(.openNotDispatched(request, .unsupportedSessionReuse)); return
        }
        // Do not replace an existing external delegate/session, even on a first attempt.
        guard record.device.delegate == nil, !record.device.hasOpenSession else {
            emit(.openNotDispatched(request, .nativeFailure)); return
        }
        let context = DeviceContext(session: request.session, record: record, port: self)
        sessions[request.session] = context
        record.device.delegate = context
        record.device.ptpEventHandler = { [weak context] bytes in
            Self.onMain {
                guard let context, !context.retired else { return }
                context.port?.emit(.ptpEvent(context.session, bytes))
            }
        }
        record.used = true // Exactly at native dispatch, not when an effect was queued.
        record.device.requestOpenSession(options: nil) { [weak self, context] error in
            let failure: PTP.Failure? = error == nil ? nil : .nativeFailure
            Self.onMain { self?.emit(.openCompleted(request, failure)); _ = context }
        }
    }

    private func close(_ request: PTP.Request) {
        guard let context = sessions[request.session] else {
            // A missing owned context cannot justify a physical close acknowledgment.
            emit(.closeCompleted(request, .nativeFailure)); return
        }
        guard context.pendingClose == nil else { return }
        context.pendingClose = request
        context.record.device.requestCloseSession(options: nil) { [weak self, context] error in
            let failure: PTP.Failure? = error == nil ? nil : .nativeFailure
            Self.onMain {
                guard context.pendingClose == request else { return }
                context.pendingClose = nil
                context.closeConfirmed = failure == nil
                self?.emit(.closeCompleted(request, failure))
            }
        }
    }

    private func publishAuthorization(_ browser: ICDeviceBrowser) {
        emit(.authorizationChanged(.init(control: Self.authorization(browser.controlAuthorizationStatus),
                                         contents: Self.authorization(browser.contentsAuthorizationStatus))))
    }
    private static func authorization(_ status: ICAuthorizationStatus) -> PTP.Authorization {
        switch status {
        case .authorized: return .authorized
        case .notDetermined: return .notDetermined
        case .denied: return .denied
        case .restricted: return .restricted
        default: return .unknown
        }
    }
    private func emit(_ event: PTP.Event) {
        precondition(Thread.isMainThread)
        receive?(event)
    }
    private static func onMain(_ action: @escaping () -> Void) {
        if Thread.isMainThread { action() } else { DispatchQueue.main.async(execute: action) }
    }
    private func discovered(_ device: ICCameraDevice, discovery: PTP.Discovery) {
        guard browsers[discovery] != nil else { return }
        let key = ObjectIdentifier(device)
        let record: DeviceRecord
        if let existing = records[key] { record = existing; record.discovery = discovery }
        else {
            guard records.count < Self.maximumRetainedDevices else { return }
            record = DeviceRecord(device: device, discovery: discovery)
            records[key] = record
            handles[record.handle] = record
        }
        emit(.discovered(discovery, record.camera))
    }

    private final class DeviceRecord {
        let device: ICCameraDevice
        let handle = PTP.Handle()
        var discovery: PTP.Discovery
        var used = false
        init(device: ICCameraDevice, discovery: PTP.Discovery) {
            self.device = device; self.discovery = discovery
        }
        var camera: PTP.Camera {
            .init(handle: handle, displayName: device.name, reportedUUID: device.uuidString,
                  vendorID: device.usbVendorID, productID: device.usbProductID,
                  transport: device.transportType == ICDeviceTransport.transportTypeUSB.rawValue ? .usb :
                    (device.transportType == nil ? .unknown : .other),
                  acceptsPTP: device.capabilities.contains(ICDeviceCapability.cameraDeviceCanAcceptPTPCommands.rawValue))
        }
    }

    private final class BrowserContext: NSObject, ICDeviceBrowserDelegate {
        let owner: PTP.Discovery
        let browser = ICDeviceBrowser()
        weak var port: ImageCapturePTPApplePort?
        init(owner: PTP.Discovery, port: ImageCapturePTPApplePort) { self.owner = owner; self.port = port }
        func deviceBrowser(_ browser: ICDeviceBrowser, didAdd device: ICDevice, moreComing: Bool) {
            guard browser === self.browser, let camera = device as? ICCameraDevice else { return }
            onMain { [self] in port?.discovered(camera, discovery: owner) }
        }
        func deviceBrowser(_ browser: ICDeviceBrowser, didRemove device: ICDevice, moreGoing: Bool) {
            guard browser === self.browser else { return }
            onMain { [self] in
                guard let port, let record = port.records[ObjectIdentifier(device)] else { return }
                port.emit(.removed(owner, record.handle))
            }
        }
    }

    private final class DeviceContext: NSObject, ICCameraDeviceDelegate {
        let session: PTP.Session
        let discovery: PTP.Discovery
        let record: DeviceRecord
        weak var port: ImageCapturePTPApplePort?
        var retired = false
        var pendingClose: PTP.Request?
        var closeConfirmed = false
        init(session: PTP.Session, record: DeviceRecord, port: ImageCapturePTPApplePort) {
            self.session = session; self.record = record; discovery = record.discovery; self.port = port
        }
        func deviceDidBecomeReady(withCompleteContentCatalog device: ICCameraDevice) {
            guard device === record.device else { return }
            onMain { [self] in
                guard !retired else { return }
                port?.emit(.becameReady(session))
            }
        }
        // Open/close request completions are the sole request receipt path.
        func device(_ device: ICDevice, didOpenSessionWithError error: Error?) {}
        func device(_ device: ICDevice, didCloseSessionWithError error: Error?) {
            guard device === record.device else { return }
            let failed = error != nil
            onMain { [self] in
                guard pendingClose == nil, !closeConfirmed else { return }
                if failed { port?.emit(.sessionInvalidated(session, .nativeError)) }
                else { port?.emit(.sessionClosed(session)) }
            }
        }
        func didRemove(_ device: ICDevice) {
            guard device === record.device else { return }
            onMain { [self] in
                retired = true
                port?.emit(.removed(discovery, record.handle))
                port?.emit(.sessionInvalidated(session, .deviceRemoved))
            }
        }
        func cameraDevice(_ camera: ICCameraDevice, didAdd items: [ICCameraItem]) {}
        func cameraDevice(_ camera: ICCameraDevice, didRemove items: [ICCameraItem]) {}
        func cameraDevice(_ camera: ICCameraDevice, didRenameItems items: [ICCameraItem]) {}
        func cameraDevice(_ camera: ICCameraDevice, didReceiveMetadata metadata: [AnyHashable: Any]?,
                          for item: ICCameraItem, error: Error?) {}
        func cameraDevice(_ camera: ICCameraDevice, didReceiveThumbnail thumbnail: CGImage?,
                          for item: ICCameraItem, error: Error?) {}
        func cameraDevice(_ camera: ICCameraDevice, didReceivePTPEvent event: Data) {} // Handler only.
        func cameraDeviceDidChangeCapability(_ camera: ICCameraDevice) {
            guard camera === record.device else { return }
            onMain { [self] in
                if !record.camera.acceptsPTP { retired = true; port?.emit(.sessionInvalidated(session, .capabilityLost)) }
            }
        }
        func cameraDeviceDidEnableAccessRestriction(_ device: ICDevice) {
            guard device === record.device else { return }
            onMain { [self] in retired = true; port?.emit(.sessionInvalidated(session, .accessRestricted)) }
        }
        func cameraDeviceDidRemoveAccessRestriction(_ device: ICDevice) {} // No automatic revival.
    }
}
#endif
