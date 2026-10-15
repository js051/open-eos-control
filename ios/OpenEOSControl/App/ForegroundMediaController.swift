import Foundation
import OpenEOSCore

@MainActor
final class ForegroundMediaController: ObservableObject {
    enum State: String { case idle, checking, baseline, watching, waiting, stabilizing, preparing, copying, stopping, stopped, failed }
    enum StopReason: String { case user, background, session, unsupported, inventory, metadata, storage, transfer, delivery, limit }
    enum Admission { case ready, busy, retired }
    enum Failure: Error { case unavailable, retired, storage, transfer }

    struct Actions {
        let admission: @MainActor () -> Admission
        let available: @MainActor () async -> Bool
        let inventory: @MainActor () async throws -> [CameraMediaItem]
        let freshInfo: @MainActor (CameraMediaItem) async throws -> CameraMediaItem
        /// Returns only after an owned App copy has been retained. The caller
        /// separately preserves any valid temporary original if retention fails.
        let prepare: @MainActor (CameraMediaItem, Int64) async throws -> Void
        var deliver: (@MainActor (CameraMediaItem) async throws -> ForegroundFilesDeliveryResult)? = nil
    }

    @Published private(set) var state: State = .idle
    @Published private(set) var reason: StopReason?
    @Published private(set) var preparedCount = 0
    @Published private(set) var copiedCount = 0
    @Published private(set) var unconfirmedCount = 0
    @Published private(set) var lastDelivery: ForegroundFilesOutcome?
    @Published private(set) var destinationLabel: String?
    @Published private(set) var pendingCount = 0
    @Published private(set) var currentName: String?
    @Published private(set) var isRunning = false
    private(set) var task: Task<Void, Never>?
    private var owner: UUID?
    private let now: @MainActor () -> TimeInterval
    private let sleep: @Sendable (UInt64) async throws -> Void

    init(now: @escaping @MainActor () -> TimeInterval = { ProcessInfo.processInfo.systemUptime },
         sleep: @escaping @Sendable (UInt64) async throws -> Void = { try await Task.sleep(nanoseconds: $0) }) {
        self.now = now
        self.sleep = sleep
    }

    @discardableResult
    func start(_ actions: Actions, destinationLabel: String? = nil) -> Bool {
        guard !isRunning else { return false }
        let token = UUID()
        owner = token
        state = .checking
        reason = nil
        preparedCount = 0
        copiedCount = 0
        unconfirmedCount = 0
        lastDelivery = nil
        self.destinationLabel = destinationLabel
        pendingCount = 0
        currentName = nil
        isRunning = true
        task = Task { [weak self] in await self?.run(actions, token: token) }
        return true
    }

    func stop(reason: StopReason = .user) {
        guard isRunning else { return }
        self.reason = reason
        state = .stopping
        task?.cancel()
        // Do not admit another run until non-cooperative I/O actually settles.
    }

    /// Connection retirement is synchronous. The old task may clean up its
    /// owned files but may neither publish into nor unlock a replacement run.
    func retire() {
        owner = nil
        task?.cancel()
        task = nil
        isRunning = false
        state = .stopped
        reason = .session
        currentName = nil
        pendingCount = 0
    }

    private func checkOwner(_ token: UUID) throws {
        try Task.checkCancellation()
        guard owner == token else { throw Failure.retired }
    }

    private func run(_ actions: Actions, token: UUID) async {
        defer {
            if owner == token {
                task = nil
                isRunning = false
                currentName = nil
            }
        }
        var phaseReason: StopReason = .unsupported
        do {
            try checkOwner(token)
            guard await actions.available() else { throw Failure.unavailable }
            try checkOwner(token)
            var plan = ForegroundMediaPlan()
            var nextInventory: TimeInterval = 0
            while true {
                try checkOwner(token)
                try plan.checkPendingAge(at: now())
                switch actions.admission() {
                case .retired: throw Failure.retired
                case .busy:
                    state = .waiting
                    try await sleep(250_000_000)
                    continue
                case .ready: break
                }
                if !plan.baselineEstablished || now() >= nextInventory {
                    phaseReason = .inventory
                    state = plan.baselineEstablished ? .watching : .baseline
                    let items = try await actions.inventory()
                    try checkOwner(token)
                    try plan.observeInventory(items, at: now())
                    nextInventory = now() + ForegroundMediaPlan.inventoryInterval
                }
                pendingCount = plan.pending.count
                if plan.baselineEstablished, let pending = plan.pending.first {
                    // Busy can change during the preceding awaited inventory.
                    switch actions.admission() {
                    case .retired: throw Failure.retired
                    case .busy: continue
                    case .ready: break
                    }
                    phaseReason = .metadata
                    state = .stabilizing
                    currentName = pending.item.name
                    let fresh: CameraMediaItem
                    do {
                        fresh = try await actions.freshInfo(pending.item)
                    } catch CameraForegroundMediaError.freshSizeUnavailable {
                        try checkOwner(token)
                        try plan.observeUnreadySize(at: now())
                        try await sleep(1_000_000_000)
                        continue
                    }
                    try checkOwner(token)
                    if let size = try plan.observeFresh(fresh, at: now()) {
                        switch actions.admission() {
                        case .retired: throw Failure.retired
                        case .busy: continue
                        case .ready: break
                        }
                        phaseReason = .transfer
                        state = .preparing
                        try await actions.prepare(fresh, size)
                        // A completed retained copy remains a true result even
                        // if Stop arrived while its completion was returning.
                        guard owner == token else { throw Failure.retired }
                        try plan.recordPrepared(fresh)
                        preparedCount = plan.preparedCount
                        pendingCount = plan.pending.count
                        try Task.checkCancellation()
                        if let deliver = actions.deliver {
                            phaseReason = .delivery
                            state = .copying
                            let result = try await deliver(fresh)
                            guard owner == token else { throw Failure.retired }
                            lastDelivery = result.outcome
                            if result.outcome.status == .copied { copiedCount += 1 }
                            if result.outcome.status == .unconfirmed { unconfirmedCount += 1 }
                            // Results remain true even if Stop raced completion.
                            // History failure must stop later writes, too.
                            if result.outcome.status != .copied || result.historyWarning {
                                state = .stopped
                                if reason == nil { reason = .delivery }
                                return
                            }
                            try Task.checkCancellation()
                        }
                        currentName = nil
                        if preparedCount == ForegroundMediaPlan.resultLimit {
                            state = .stopped
                            reason = .limit
                            return
                        }
                    }
                } else if plan.baselineEstablished {
                    state = .watching
                    currentName = nil
                }
                // Polling is an explicit foreground policy, never a retry of
                // a failed request or a delay used to assume I/O has completed.
                try await sleep(1_000_000_000)
            }
        } catch {
            guard owner == token else { return }
            if Task.isCancelled || error is CancellationError || (error as? URLError)?.code == .cancelled {
                state = .stopped
                if reason == nil { reason = .user }
            } else if case Failure.retired = error {
                state = .stopped
                reason = .session
            } else {
                state = .failed
                if case Failure.storage = error { reason = .storage }
                else if let failure = error as? ForegroundMediaPlan.Failure {
                    switch failure {
                    case .invalidInventory, .unstableBaseline: reason = .inventory
                    case .invalidMetadata, .expiredItem: reason = .metadata
                    default: reason = .limit
                    }
                }
                else { reason = phaseReason }
            }
        }
    }
}
