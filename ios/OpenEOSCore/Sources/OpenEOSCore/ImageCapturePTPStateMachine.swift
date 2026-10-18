import Foundation

/// Pure lifecycle/admission logic. No Apple objects, clocks, tasks, I/O, or wire codec.
/// Control authorization is an app-side prerequisite for DeviceInfo admission, not
/// a claim that it is sufficient for ImageCaptureCore on real iOS hardware.
public struct ImageCapturePTPStateMachine {
    /// Active native session plus unresolved old sessions. Missing callbacks never
    /// cause unbounded retention, and reaching the cap never drops an old obligation.
    public static let maximumUnresolvedSessions = 4
    public private(set) var discovery: ImageCapturePTP.Discovery?
    public private(set) var cameras: [ImageCapturePTP.Handle: ImageCapturePTP.Camera] = [:]
    public private(set) var authorization: ImageCapturePTP.AuthorizationSnapshot
    public private(set) var session: ImageCapturePTP.Session?
    public private(set) var phase: ImageCapturePTP.Phase = .idle
    public private(set) var cleanup: [ImageCapturePTP.Session: ImageCapturePTP.Cleanup] = [:]
    public private(set) var deviceInfoOutcome: ImageCapturePTP.DeviceInfoOutcome?
    /// Keep the most recent interrupted receipt visible across a selection change.
    /// It is owned by its old request; it is never a result for the new session.
    public private(set) var lastInterruptedDeviceInfoOutcome: ImageCapturePTP.DeviceInfoOutcome?
    public private(set) var lastEvent: ImageCapturePTP.ReceivedEvent?
    private var dispatched: Set<ImageCapturePTP.Request> = []
    // Evidence that contradicts a preflight refusal. Limited to admitted open requests:
    // one current owner plus the bounded unresolved cleanup table. No timeout discards it.
    private var readyObserved: Set<ImageCapturePTP.Request> = []

    public init(authorization: ImageCapturePTP.AuthorizationSnapshot = .init(
        control: .notDetermined, contents: .notDetermined)) {
        self.authorization = authorization
    }

    @discardableResult
    public mutating func receive(_ event: ImageCapturePTP.Event) -> [ImageCapturePTP.Effect] {
        switch event {
        case .startDiscovery:
            var effects = retire(.discoveryStopped)
            if let discovery { effects.append(.stopDiscovery(discovery)) }
            cameras.removeAll()
            let next = ImageCapturePTP.Discovery()
            discovery = next
            effects.append(.startDiscovery(next))
            return effects
        case .stopDiscovery:
            return stopDiscovery(reason: .discoveryStopped)
        case .suspend:
            authorization = .init(control: .unknown, contents: .unknown)
            return stopDiscovery(reason: .suspended)
        case .discovered(let owner, let camera):
            guard owner == discovery else { return [] }
            cameras[camera.handle] = camera
            // Losing capability on the current exact object closes admission too.
            if session?.handle == camera.handle, (!camera.acceptsPTP || camera.transport != .usb) {
                return retire(.removed)
            }
            return []
        case .removed(let owner, let handle):
            guard owner == discovery else { return [] }
            cameras.removeValue(forKey: handle)
            return session?.handle == handle ? retire(.removed) : []
        case .select(let handle, let requestAuthorization):
            var effects = retire(.selectionChanged)
            let next = ImageCapturePTP.Session(handle: handle)
            session = next
            deviceInfoOutcome = nil
            lastEvent = nil
            guard let camera = cameras[handle], discovery != nil else {
                phase = .blocked(.notDiscovered)
                return effects
            }
            guard camera.transport == .usb else {
                phase = .blocked(.notUSB)
                return effects
            }
            guard camera.acceptsPTP else {
                phase = .blocked(.ptpUnavailable)
                return effects
            }
            guard !cleanup.keys.contains(where: { $0.handle == handle }) else {
                phase = .blocked(.cleanupPending)
                return effects
            }
            guard cleanup.count < Self.maximumUnresolvedSessions else {
                phase = .blocked(.cleanupCapacityReached)
                return effects
            }
            switch authorization.control {
            case .authorized:
                effects += open(next)
            case .notDetermined where requestAuthorization:
                let request = ImageCapturePTP.Request(session: next)
                phase = .awaitingAuthorization(request)
                effects += [.armDeadline(request, seconds: 30), .requestControlAuthorization(request)]
            default:
                phase = .blocked(.authorizationNotGranted(authorization.control))
            }
            return effects
        case .authorizationChanged(let snapshot):
            authorization = snapshot
            // A status refresh cannot resurrect a cancelled prompt or start opening.
            if snapshot.control != .authorized {
                return retire(.authorizationLost)
            }
            return []
        case .authorizationCompleted(let request, let status):
            guard phase == .awaitingAuthorization(request), session == request.session,
                  dispatched.remove(request) != nil else { return [] }
            authorization.control = status
            if status == .authorized {
                return [.cancelDeadline(request)] + open(request.session)
            }
            phase = .blocked(.authorizationNotGranted(status))
            return [.cancelDeadline(request)]
        case .openNotDispatched(let request, let failure):
            guard dispatched.contains(request), !readyObserved.contains(request) else { return [] }
            if cleanup[request.session] == .awaitingOpen(request) {
                dispatched.remove(request)
                cleanup.removeValue(forKey: request.session)
                return [.cancelDeadline(request)]
            }
            guard session == request.session,
                  phase == .opening(request, didOpen: false, didBecomeReady: false) else { return [] }
            dispatched.remove(request)
            phase = .blocked(.openNotDispatched(failure))
            lastEvent = nil
            return [.cancelDeadline(request)]
        case .sessionInvalidated(let owner, let reason):
            guard session == owner else { return [] }
            return retire(.sessionInvalidated(reason))
        case .openCompleted(let request, let failure):
            // Retired opening retains its own object until the callback arrives.
            // Even a failed open gets close cleanup: an error is not proof that no
            // framework session was established, especially with prior ready events.
            if cleanup[request.session] == .awaitingOpen(request), dispatched.remove(request) != nil {
                readyObserved.remove(request)
                return close(request.session)
            }
            guard case .opening(let current, let didOpen, let ready) = phase,
                  current == request, session == request.session, !didOpen,
                  dispatched.remove(request) != nil else { return [] }
            readyObserved.remove(request)
            if failure != nil {
                phase = .opening(request, didOpen: true, didBecomeReady: ready)
                return retire(.openFailed)
            }
            if ready {
                phase = .ready
                return [.cancelDeadline(request)]
            }
            phase = .opening(request, didOpen: true, didBecomeReady: false)
            return []
        case .becameReady(let owner):
            // Late readiness is evidence of native work even after retirement. Keep
            // that evidence on the exact bounded awaiting-open obligation; never revive it.
            if case .awaitingOpen(let request) = cleanup[owner], dispatched.contains(request) {
                readyObserved.insert(request)
                return []
            }
            guard session == owner, case .opening(let request, let opened, _) = phase,
                  opened || dispatched.contains(request) else { return [] }
            if dispatched.contains(request) { readyObserved.insert(request) }
            if opened {
                phase = .ready
                return [.cancelDeadline(request)]
            }
            phase = .opening(request, didOpen: false, didBecomeReady: true)
            return []
        case .sessionClosed(let owner):
            guard session == owner else { return [] }
            return retire(.sessionClosed)
        case .readDeviceInfo:
            guard phase == .ready, let session, authorization.control == .authorized,
                  let camera = cameras[session.handle], camera.transport == .usb, camera.acceptsPTP,
                  !cleanup.keys.contains(where: { $0.handle == session.handle }) else { return [] }
            let request = ImageCapturePTP.Request(session: session)
            phase = .readingDeviceInfo(request)
            deviceInfoOutcome = nil
            return [.armDeadline(request, seconds: 15), .readDeviceInfo(request)]
        case .deviceInfoCompleted(let request, let buffers, let failure):
            guard phase == .readingDeviceInfo(request), session == request.session,
                  dispatched.remove(request) != nil else { return [] }
            let receipt = ImageCapturePTP.Receipt(request: request, buffers: buffers, failure: failure)
            if failure != nil {
                // Preserve both opaque buffers even on a framework error. Retire the
                // epoch; do not issue another command after a transport failure.
                deviceInfoOutcome = .frameworkFailure(receipt)
                phase = .ready
                return [.cancelDeadline(request)] + retire(.exchangeFailed)
            }
            deviceInfoOutcome = .unvalidatedBuffers(receipt)
            phase = .ready
            return [.cancelDeadline(request)]
        case .ptpEvent(let owner, let bytes):
            guard session == owner, authorization.control == .authorized else { return [] }
            switch phase {
            case .ready, .readingDeviceInfo:
                lastEvent = .init(session: owner, bytes: bytes)
            default:
                break
            }
            return []
        case .cancel:
            return retire(.cancelled)
        case .deadlineExpired(let request):
            if case .closing(let current, _) = cleanup[request.session], current == request {
                cleanup[request.session] = .closing(request, deadlineExpired: true)
                return []
            }
            guard session == request.session else { return [] }
            switch phase {
            case .awaitingAuthorization(let current) where current == request:
                return retire(.authorizationTimedOut)
            case .opening(let current, _, _) where current == request:
                return retire(.openTimedOut)
            case .readingDeviceInfo(let current) where current == request:
                return retire(.exchangeTimedOut)
            default:
                return []
            }
        case .retryFailedClose(let owner):
            guard case .closeFailed = cleanup[owner] else { return [] }
            return close(owner)
        case .closeCompleted(let request, let failure):
            guard case .closing(let current, _) = cleanup[request.session], current == request,
                  dispatched.remove(request) != nil else { return [] }
            if failure != nil {
                cleanup[request.session] = .closeFailed(request)
                return [.cancelDeadline(request)]
            }
            cleanup.removeValue(forKey: request.session)
            return [.cancelDeadline(request), .releaseSession(request.session)]
        }
    }

    /// Atomically admit and mark an effect immediately before calling the port.
    /// The owner must not yield between this method and `perform`. This separates
    /// queued work from dispatched work when a reentrant callback cancels the queue.
    public mutating func admit(_ effect: ImageCapturePTP.Effect) -> Bool {
        guard isAdmitted(effect) else { return false }
        switch effect {
        case .requestControlAuthorization(let request), .openSession(let request),
             .readDeviceInfo(let request), .closeSession(let request):
            dispatched.insert(request)
        default: break
        }
        return true
    }

    /// Check directly before dispatch. A synchronous/reentrant port callback can
    /// retire a session while earlier effects are still in the owner's queue.
    public func isAdmitted(_ effect: ImageCapturePTP.Effect) -> Bool {
        switch effect {
        case .startDiscovery(let owner):
            return discovery == owner
        case .stopDiscovery(let owner):
            return discovery != owner
        case .requestControlAuthorization(let request):
            return session == request.session && phase == .awaitingAuthorization(request)
                && !dispatched.contains(request)
        case .openSession(let request):
            guard session == request.session, authorization.control == .authorized,
                  case .opening(let current, let opened, _) = phase else { return false }
            return current == request && !opened && cleanup[request.session] == nil
                && cleanup.count < Self.maximumUnresolvedSessions && !dispatched.contains(request)
        case .readDeviceInfo(let request):
            return session == request.session && authorization.control == .authorized
                && phase == .readingDeviceInfo(request) && !dispatched.contains(request)
        case .retireSession(let owner):
            return session != owner || !isActive
        case .closeSession(let request):
            guard case .closing(let current, _) = cleanup[request.session] else { return false }
            return current == request && !dispatched.contains(request)
        case .releaseSession(let owner):
            return cleanup[owner] == nil && (session != owner || !isActive)
        case .armDeadline(let request, _):
            if case .closing(let current, _) = cleanup[request.session] { return current == request }
            guard session == request.session else { return false }
            switch phase {
            case .awaitingAuthorization(let current), .opening(let current, _, _), .readingDeviceInfo(let current):
                return current == request
            default:
                return false
            }
        case .cancelDeadline:
            return true
        }
    }

    private var isActive: Bool {
        switch phase {
        case .awaitingAuthorization, .opening, .ready, .readingDeviceInfo: return true
        default: return false
        }
    }

    private mutating func open(_ owner: ImageCapturePTP.Session) -> [ImageCapturePTP.Effect] {
        let request = ImageCapturePTP.Request(session: owner)
        phase = .opening(request, didOpen: false, didBecomeReady: false)
        return [.armDeadline(request, seconds: 30), .openSession(request)]
    }

    private mutating func close(_ owner: ImageCapturePTP.Session) -> [ImageCapturePTP.Effect] {
        let request = ImageCapturePTP.Request(session: owner)
        cleanup[owner] = .closing(request, deadlineExpired: false)
        return [.armDeadline(request, seconds: 10), .closeSession(request)]
    }

    private mutating func stopDiscovery(reason: ImageCapturePTP.RetirementReason) -> [ImageCapturePTP.Effect] {
        var effects = retire(reason)
        if let discovery { effects.append(.stopDiscovery(discovery)) }
        discovery = nil
        cameras.removeAll()
        return effects
    }

    private mutating func retire(_ reason: ImageCapturePTP.RetirementReason) -> [ImageCapturePTP.Effect] {
        guard let session, isActive else { return [] }
        var effects: [ImageCapturePTP.Effect] = []
        switch phase {
        case .awaitingAuthorization(let request):
            effects.append(.cancelDeadline(request))
            dispatched.remove(request)
            // No open was dispatched, therefore there is no native session to close.
        case .opening(let request, let opened, _):
            effects.append(.cancelDeadline(request))
            if opened {
                effects.append(.retireSession(session))
                effects += close(session)
            } else if dispatched.contains(request) {
                effects.append(.retireSession(session))
                cleanup[session] = .awaitingOpen(request)
            }
        case .ready:
            effects = [.retireSession(session)] + close(session)
        case .readingDeviceInfo(let request):
            deviceInfoOutcome = dispatched.remove(request) != nil
                ? .outcomeUnknown(request, reason) : .notDispatched(request, reason)
            lastInterruptedDeviceInfoOutcome = deviceInfoOutcome
            effects = [.cancelDeadline(request), .retireSession(session)] + close(session)
        default:
            break
        }
        phase = .retired(reason)
        lastEvent = nil
        return effects
    }
}
