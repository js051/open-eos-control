import Foundation
import Dispatch

/// Runs the production reducer and its admitted effects on one explicitly selected serial executor.
/// This is an internal integration boundary, not a native provider or permission grant.
///
/// `send` and `snapshot` synchronously join this executor. A port must perform effects
/// without blocking on another thread calling either API. Native callbacks must use
/// `receive`, which only enqueues across threads and never waits for this executor.
/// No lock is held while calling the port. Reentrant callback inputs are reduced before
/// the next effect is admitted. Deadlines remain exact-request effects owned by the port.
/// Keep this coordinator alive until native cleanup obligations have been settled;
/// releasing it does not acknowledge a close, cancel a camera operation, or release a device.
/// A port is exclusively bound once for this coordinator's lifetime. Do not replace its
/// receive closure. Binding is established before the port is shared with callback threads.
/// No deinit callback clearing is performed; a discarded owner cannot clear another owner.
/// Keep the port and coordinator together until discovery has stopped, no active session
/// remains, and cleanup is empty. A nonempty cleanup table is an unresolved native duty.
public final class ImageCapturePTPCoordinator: @unchecked Sendable {
    /// Only known serial execution modes are accepted. A main-confined port must use
    /// mainThread and call native APIs directly in perform, without another queue hop.
    public enum Executor: Sendable { case dedicated, mainThread }
    public enum BindingError: Error { case portAlreadyBound }
    private let port: ImageCapturePTPPort
    private let executor: DispatchQueue
    private let executionMode: Executor
    private let executorKey = DispatchSpecificKey<UInt8>()
    private let inboxLock = NSLock()
    private var inbox: [ImageCapturePTP.Event] = []
    // Accessed only on executor, never while holding inboxLock across a port call.
    private var state: ImageCapturePTPStateMachine
    private var effects: [ImageCapturePTP.Effect] = []
    private var draining = false

    public init(port: ImageCapturePTPPort,
                authorization: ImageCapturePTP.AuthorizationSnapshot = .init(
                    control: .notDetermined, contents: .notDetermined),
                executor executionMode: Executor = .dedicated) throws {
        // Construction/port binding must be exclusive, before exposing the port to
        // callbacks. Never steal an existing owner's callback or cleanup obligation.
        guard port.receive == nil else { throw BindingError.portAlreadyBound }
        self.port = port
        self.state = .init(authorization: authorization)
        self.executionMode = executionMode
        switch executionMode {
        case .dedicated:
            executor = DispatchQueue(label: "org.openeos.imagecapture-ptp")
            executor.setSpecific(key: executorKey, value: 1)
        case .mainThread:
            executor = .main
        }
        port.receive = { [weak self] event in self?.receive(event) }
    }

    /// Reduces inputs already enqueued before reading state. During a port callback,
    /// this does not recursively dispatch effects; the outer pump resumes afterward.
    public var snapshot: ImageCapturePTPStateMachine {
        onExecutor {
            applyInputs()
            drain()
            return state
        }
    }

    /// App input. Outside a port callback, joins the executor and drains pending effects.
    /// Reentrant calls enqueue and return; the outer pump reduces them before its next
    /// effect admission. Use snapshot to inspect reduced state inside that callback.
    /// Neither return path acknowledges asynchronous native completion or cancellation.
    public func send(_ event: ImageCapturePTP.Event) {
        onExecutor {
            append(event)
            drain()
        }
    }

    /// Thread-safe nonwaiting native callback ingress. The supplied event must already
    /// contain the original discovery/session/request identity captured by the adapter.
    public func receive(_ event: ImageCapturePTP.Event) {
        append(event)
        if isOnExecutor {
            drain()
        } else {
            executor.async { [weak self] in self?.drain() }
        }
    }

    private var isOnExecutor: Bool {
        switch executionMode {
        case .dedicated: return DispatchQueue.getSpecific(key: executorKey) != nil
        case .mainThread: return Thread.isMainThread
        }
    }

    private func onExecutor<T>(_ body: () -> T) -> T {
        if isOnExecutor { return body() }
        return executor.sync(execute: body)
    }

    private func append(_ event: ImageCapturePTP.Event) {
        inboxLock.lock()
        inbox.append(event)
        inboxLock.unlock()
    }

    private func takeInputs() -> [ImageCapturePTP.Event] {
        inboxLock.lock()
        let inputs = inbox
        inbox.removeAll(keepingCapacity: true)
        inboxLock.unlock()
        return inputs
    }

    private func applyInputs() {
        for event in takeInputs() { effects += state.receive(event) }
    }

    private func drain() {
        guard !draining else { return }
        draining = true
        defer { draining = false }
        while true {
            // A synchronous port callback may have cancelled or replaced the owner.
            // Apply it before admission, rather than appending it behind stale effects.
            applyInputs()
            guard !effects.isEmpty else { return }
            let effect = effects.removeFirst()
            guard state.admit(effect) else { continue }
            // No await, queue hop, observer callback or held lock between these calls.
            port.perform(effect)
        }
    }
}
