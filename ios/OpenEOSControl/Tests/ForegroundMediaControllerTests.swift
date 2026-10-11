import Foundation
import OpenEOSCore
import XCTest

@testable import OpenEOSControl

/// Synthetic inventories and retained-copy results only; no camera, file, or
/// network access. Gates deliberately ignore cancellation. Clock advancement
/// controls polling policy, while named gates and captured tasks prove that
/// work has entered or completed. Cleanup releases every gate and drains every
/// task, including retired runs whose handles the controller no longer owns.
@MainActor
final class ForegroundMediaControllerTests: XCTestCase {
    func testUnsupportedSourceStopsBeforeInventoryWithoutRetry() async throws {
        try await withFixture { fixture in
            let source = fixture.source("unsupported")
            source.available = false
            let task = try fixture.start(source)
            await task.value

            assertFinished(fixture.controller, state: .failed, reason: .unsupported)
            XCTAssertEqual(source.availabilityReads, 1)
            await assertRequests(source, inventories: 0, fresh: 0, preparations: 0)
            let delays = await fixture.delay.requests()
            XCTAssertTrue(delays.isEmpty)
        }
    }

    func testTwoCompleteBaselinesThenNewJPEGWithStableFreshSizePreparesExactlyOnce() async throws {
        try await withFixture { fixture in
            let source = fixture.source("once")
            let listed = item("new", name: "NEW.JPG", size: 999)
            let fresh = item("new", name: "NEW.JPG", size: 4)
            let task = try await holdPreparation(fixture, source: source, listed: listed, fresh: fresh)
            XCTAssertEqual(fixture.controller.state, .preparing)
            XCTAssertEqual(fixture.controller.preparedCount, 0)
            XCTAssertEqual(fixture.controller.pendingCount, 1)
            let preparations = await source.preparation.requests()
            XCTAssertEqual(preparations, [ForegroundPreparationRequest(item: fresh, size: 4)])

            await fixture.delay.enqueue("after-prepare", outcome: .success(()))
            await source.preparation.release(source.gate("prepare"))
            try await waitForGate("after-prepare", in: fixture.delay)
            XCTAssertEqual(fixture.controller.preparedCount, 1)
            XCTAssertEqual(fixture.controller.pendingCount, 0)
            XCTAssertNil(fixture.controller.currentName)

            // A later complete inventory still contains the same camera item.
            // Its stale listing size must neither trigger a second copy nor
            // replace the fresh size used for the one retained preparation.
            await source.inventory.enqueue(source.gate("repeat"), outcome: .success(oldItems + [listed]))
            await fixture.delay.enqueue("after-repeat", outcome: .success(()))
            fixture.clock.instant = 11
            await fixture.delay.release("after-prepare")
            try await waitForGate(source.gate("repeat"), in: source.inventory)
            await source.inventory.release(source.gate("repeat"))
            try await waitForGate("after-repeat", in: fixture.delay)
            XCTAssertEqual(fixture.controller.state, .watching)
            XCTAssertEqual(fixture.controller.preparedCount, 1)
            await assertRequests(source, inventories: 4, fresh: 2, preparations: 1)

            fixture.controller.stop()
            await fixture.delay.release("after-repeat")
            await task.value
            assertFinished(fixture.controller, state: .stopped, reason: .user)
            XCTAssertEqual(fixture.controller.preparedCount, 1)
            let delays = await fixture.delay.requests()
            XCTAssertEqual(delays, Array(repeating: 1_000_000_000, count: 5))
        }
    }

    func testBusyAdmissionWaitsWithoutInventoryAndResumesTheSameRun() async throws {
        try await withFixture { fixture in
            let source = fixture.source("busy")
            source.admission = .busy
            await fixture.delay.enqueue("busy-wait", outcome: .success(()))
            let task = try fixture.start(source)
            try await waitForGate("busy-wait", in: fixture.delay)

            XCTAssertEqual(fixture.controller.state, .waiting)
            XCTAssertTrue(fixture.controller.isRunning)
            try fixture.assertStartRejected(source)
            await assertRequests(source, inventories: 0, fresh: 0, preparations: 0)
            let delays = await fixture.delay.requests()
            XCTAssertEqual(delays, [250_000_000])

            source.admission = .ready
            await source.inventory.enqueue(source.gate("resumed"), outcome: .success(oldItems))
            fixture.clock.instant = 0.25
            await fixture.delay.release("busy-wait")
            try await waitForGate(source.gate("resumed"), in: source.inventory)
            XCTAssertEqual(fixture.controller.state, .baseline)
            XCTAssertEqual(source.availabilityReads, 1)
            fixture.controller.stop()
            await source.inventory.release(source.gate("resumed"))
            await task.value
            assertFinished(fixture.controller, state: .stopped, reason: .user)
            await assertRequests(source, inventories: 1, fresh: 0, preparations: 0)
        }
    }

    func testBusyAfterAwaitedInventoryPreventsMetadataUntilAdmissionReturns() async throws {
        try await withFixture { fixture in
            let source = fixture.source("inventory-busy")
            let task = try await holdDiscovery(fixture, source: source, listed: item("new"))
            source.admission = .busy
            await fixture.delay.enqueue("busy-after-inventory", outcome: .success(()))
            await source.inventory.release(source.gate("discovery"))
            try await waitForGate("busy-after-inventory", in: fixture.delay)

            XCTAssertEqual(fixture.controller.state, .waiting)
            XCTAssertEqual(fixture.controller.pendingCount, 1)
            await assertRequests(source, inventories: 3, fresh: 0, preparations: 0)

            await source.fresh.enqueue(source.gate("resumed-fresh"), outcome: .success(item("new")))
            source.admission = .ready
            fixture.clock.instant = 6.25
            await fixture.delay.release("busy-after-inventory")
            try await waitForGate(source.gate("resumed-fresh"), in: source.fresh)
            XCTAssertEqual(fixture.controller.state, .stabilizing)
            fixture.controller.stop()
            await source.fresh.release(source.gate("resumed-fresh"))
            await task.value
            assertFinished(fixture.controller, state: .stopped, reason: .user)
            await assertRequests(source, inventories: 3, fresh: 1, preparations: 0)
        }
    }

    func testBusyAfterStableFreshResultPreventsPreparationUntilFreshIsCheckedAgain() async throws {
        try await withFixture { fixture in
            let source = fixture.source("fresh-busy")
            let task = try await holdStableFresh(fixture, source: source, listed: item("new"), fresh: item("new"))
            source.admission = .busy
            await fixture.delay.enqueue("busy-after-fresh", outcome: .success(()))
            await source.fresh.release(source.gate("fresh-stable"))
            try await waitForGate("busy-after-fresh", in: fixture.delay)

            XCTAssertEqual(fixture.controller.state, .waiting)
            XCTAssertEqual(fixture.controller.preparedCount, 0)
            await assertRequests(source, inventories: 3, fresh: 2, preparations: 0)

            await source.fresh.enqueue(source.gate("fresh-rechecked"), outcome: .success(item("new")))
            await source.preparation.enqueue(source.gate("prepare"), outcome: .success(()))
            source.admission = .ready
            fixture.clock.instant = 7.25
            await fixture.delay.release("busy-after-fresh")
            try await waitForGate(source.gate("fresh-rechecked"), in: source.fresh)
            await source.fresh.release(source.gate("fresh-rechecked"))
            try await waitForGate(source.gate("prepare"), in: source.preparation)
            await assertRequests(source, inventories: 3, fresh: 3, preparations: 1)

            fixture.controller.stop()
            await source.preparation.release(source.gate("prepare"))
            await task.value
            assertFinished(fixture.controller, state: .stopped, reason: .user)
            XCTAssertEqual(fixture.controller.preparedCount, 1)
        }
    }

    func testMetadataFailureOrInvalidFreshIdentityStopsWithoutRetryOrPreparation() async throws {
        let outcomes: [ForegroundControllerOutcome<CameraMediaItem>] = [
            .failure(.request), .success(item("new", size: nil)),
            .success(item("new", size: 0)), .success(item("other")),
        ]
        for outcome in outcomes {
            try await withFixture { fixture in
                let source = fixture.source("bad-metadata")
                let task = try await holdFirstFresh(fixture, source: source, listed: item("new"), outcome: outcome)
                await source.fresh.release(source.gate("fresh-first"))
                await task.value

                assertFinished(fixture.controller, state: .failed, reason: .metadata)
                XCTAssertEqual(fixture.controller.preparedCount, 0)
                XCTAssertEqual(fixture.controller.pendingCount, 1)
                await assertRequests(source, inventories: 3, fresh: 1, preparations: 0)
                let delays = await fixture.delay.requests()
                XCTAssertEqual(delays.count, 2, "A metadata failure must not schedule a retry")
            }
        }
    }

    func testPreparationFailureStopsWithoutRetryOrCountingARetainedCopy() async throws {
        let cases: [(ForegroundControllerFailure, ForegroundMediaController.StopReason)] = [
            (.request, .transfer), (.storage, .storage),
        ]
        for (failure, reason) in cases {
            try await withFixture { fixture in
                let source = fixture.source("bad-prepare")
                let task = try await holdPreparation(fixture, source: source, listed: item("new"),
                                                     fresh: item("new"), outcome: .failure(failure))
                await source.preparation.release(source.gate("prepare"))
                await task.value

                assertFinished(fixture.controller, state: .failed, reason: reason)
                XCTAssertEqual(fixture.controller.preparedCount, 0)
                XCTAssertEqual(fixture.controller.pendingCount, 1)
                await assertRequests(source, inventories: 3, fresh: 2, preparations: 1)
                let delays = await fixture.delay.requests()
                XCTAssertEqual(delays.count, 3, "A preparation failure must not schedule a retry")
            }
        }
    }

    func testStopDuringNoncooperativeInventoryCannotAdmitMetadataOrAnotherRun() async throws {
        try await withFixture { fixture in
            let source = fixture.source("stop-inventory")
            let task = try await holdDiscovery(fixture, source: source, listed: item("new"))
            fixture.controller.stop()
            try assertStopping(fixture, source: source, reason: .user)

            await source.inventory.release(source.gate("discovery"))
            await task.value

            assertFinished(fixture.controller, state: .stopped, reason: .user)
            XCTAssertEqual(fixture.controller.preparedCount, 0)
            await assertRequests(source, inventories: 3, fresh: 0, preparations: 0)
        }
    }

    func testStopDuringEitherNoncooperativeFreshObservationCannotAdmitPreparation() async throws {
        for stable in [false, true] {
            try await withFixture { fixture in
                let source = fixture.source("stop-fresh")
                let task: Task<Void, Never>
                let gate: String
                if stable {
                    task = try await holdStableFresh(fixture, source: source, listed: item("new"), fresh: item("new"))
                    gate = source.gate("fresh-stable")
                } else {
                    task = try await holdFirstFresh(fixture, source: source, listed: item("new"),
                                                    outcome: .success(item("new")))
                    gate = source.gate("fresh-first")
                }
                fixture.controller.stop(reason: .background)
                try assertStopping(fixture, source: source, reason: .background)
                await source.fresh.release(gate)
                await task.value

                assertFinished(fixture.controller, state: .stopped, reason: .background)
                XCTAssertEqual(fixture.controller.preparedCount, 0)
                await assertRequests(source, inventories: 3, fresh: stable ? 2 : 1, preparations: 0)
            }
        }
    }

    func testStopDuringSuccessfulRetainedPreparationCountsTrueResultThenStopsBeforeNextItem() async throws {
        for reason in [ForegroundMediaController.StopReason.user, .background] {
            try await withFixture { fixture in
                let source = fixture.source("stop-prepare")
                let task = try await holdPreparation(fixture, source: source, listed: item("first"),
                                                     fresh: item("first"), additional: [item("second")])
                XCTAssertEqual(fixture.controller.pendingCount, 2)
                fixture.controller.stop(reason: reason)
                try assertStopping(fixture, source: source, reason: reason)
                XCTAssertEqual(fixture.controller.preparedCount, 0, "Held work has not retained a copy yet")

                await source.preparation.release(source.gate("prepare"))
                await task.value

                assertFinished(fixture.controller, state: .stopped, reason: reason)
                XCTAssertEqual(fixture.controller.preparedCount, 1)
                XCTAssertEqual(fixture.controller.pendingCount, 1)
                await assertRequests(source, inventories: 3, fresh: 2, preparations: 1)
                let delays = await fixture.delay.requests()
                XCTAssertEqual(delays.count, 3, "Stop must not start the next polling cycle")
            }
        }
    }

    func testRetiredRunLateSuccessOrFailureAndDeferCannotAlterOrUnlockReplacement() async throws {
        for phase in ForegroundHeldPhase.allCases {
            for fails in [false, true] {
                try await withFixture { fixture in
                    let old = fixture.source("old")
                    let oldTask: Task<Void, Never>
                    switch phase {
                    case .inventory:
                        oldTask = try await holdDiscovery(fixture, source: old, listed: item("old-new"),
                                                         fails: fails)
                    case .fresh:
                        oldTask = try await holdStableFresh(fixture, source: old, listed: item("old-new"),
                                                           fresh: item("old-new"), fails: fails)
                    case .preparation:
                        oldTask = try await holdPreparation(fixture, source: old, listed: item("old-new"),
                                                           fresh: item("old-new"),
                                                           outcome: fails ? .failure(.request) : .success(()))
                    }
                    fixture.controller.retire()
                    assertFinished(fixture.controller, state: .stopped, reason: .session)
                    XCTAssertTrue(oldTask.isCancelled)

                    let replacement = fixture.source("replacement")
                    // A replacement has its own baseline observation interval.
                    fixture.clock.instant = 100
                    let replacementTask = try await holdPreparation(
                        fixture, source: replacement,
                        listed: item("replacement-new", name: "REPLACEMENT.JPG"),
                        fresh: item("replacement-new", name: "REPLACEMENT.JPG"),
                        additional: [item("replacement-next")]
                    )
                    let saved = ForegroundPresentation(fixture.controller)
                    XCTAssertEqual(saved.state, .preparing)
                    XCTAssertEqual(saved.pending, 2)
                    XCTAssertEqual(saved.name, "REPLACEMENT.JPG")

                    switch phase {
                    case .inventory: await old.inventory.release(old.gate("discovery"))
                    case .fresh: await old.fresh.release(old.gate("fresh-stable"))
                    case .preparation: await old.preparation.release(old.gate("prepare"))
                    }
                    await oldTask.value

                    saved.assertPreserved(in: fixture.controller)
                    XCTAssertNotNil(fixture.controller.task, "The retired defer must not erase the replacement task")
                    XCTAssertFalse(replacementTask.isCancelled)
                    try fixture.assertStartRejected(old)
                    await assertRequests(old, inventories: 3, fresh: phase == .inventory ? 0 : 2,
                                         preparations: phase == .preparation ? 1 : 0)
                    await assertRequests(replacement, inventories: 3, fresh: 2, preparations: 1)

                    // Stop must still cancel the actual replacement handle.
                    fixture.controller.stop(reason: .background)
                    XCTAssertTrue(replacementTask.isCancelled)
                    await replacement.preparation.release(replacement.gate("prepare"))
                    await replacementTask.value
                    assertFinished(fixture.controller, state: .stopped, reason: .background)
                    XCTAssertEqual(fixture.controller.preparedCount, 1)
                    XCTAssertEqual(fixture.controller.pendingCount, 1)
                }
            }
        }
    }

    private var oldItems: [CameraMediaItem] {
        [item("baseline-jpeg", name: "OLD.JPG"), item("baseline-raw", name: "OLD.CR3", kind: "raw")]
    }

    private func item(_ id: String, name: String = "NEW.JPG", kind: String = "image",
                      size: Int64? = 4) -> CameraMediaItem {
        CameraMediaItem(id: id, name: name, kind: kind, sizeBytes: size,
                        contentType: kind == "raw" ? nil : "image/jpeg")
    }

    /// Returns parked in the discovery inventory, after two complete matching
    /// baselines have been observed a full injected second apart.
    private func holdDiscovery(_ fixture: ForegroundControllerFixture, source: ForegroundControllerSource,
                               listed: CameraMediaItem, additional: [CameraMediaItem] = [],
                               fails: Bool = false) async throws -> Task<Void, Never> {
        let startedAt = fixture.clock.instant
        await source.inventory.enqueue(source.gate("baseline-first"), outcome: .success(oldItems))
        await source.inventory.enqueue(source.gate("baseline-second"), outcome: .success(Array(oldItems.reversed())))
        await fixture.delay.enqueue(source.gate("baseline-observation"), outcome: .success(()))
        await fixture.delay.enqueue(source.gate("baseline-complete"), outcome: .success(()))
        let task = try fixture.start(source)
        try await waitForGate(source.gate("baseline-first"), in: source.inventory)
        XCTAssertEqual(fixture.controller.state, .baseline)
        await source.inventory.release(source.gate("baseline-first"))
        try await waitForGate(source.gate("baseline-observation"), in: fixture.delay)
        await assertRequests(source, inventories: 1, fresh: 0, preparations: 0)

        fixture.clock.instant = startedAt + 1
        await fixture.delay.release(source.gate("baseline-observation"))
        try await waitForGate(source.gate("baseline-second"), in: source.inventory)
        XCTAssertEqual(fixture.controller.state, .baseline)
        await source.inventory.release(source.gate("baseline-second"))
        try await waitForGate(source.gate("baseline-complete"), in: fixture.delay)
        XCTAssertEqual(fixture.controller.state, .watching)
        XCTAssertEqual(fixture.controller.pendingCount, 0)
        XCTAssertEqual(fixture.controller.preparedCount, 0)
        await assertRequests(source, inventories: 2, fresh: 0, preparations: 0)

        await source.inventory.enqueue(source.gate("discovery"), outcome: fails
                                       ? .failure(.request) : .success(oldItems + [listed] + additional))
        fixture.clock.instant = startedAt + 6
        await fixture.delay.release(source.gate("baseline-complete"))
        try await waitForGate(source.gate("discovery"), in: source.inventory)
        return task
    }

    private func holdFirstFresh(_ fixture: ForegroundControllerFixture, source: ForegroundControllerSource,
                               listed: CameraMediaItem, additional: [CameraMediaItem] = [],
                               outcome: ForegroundControllerOutcome<CameraMediaItem>) async throws -> Task<Void, Never> {
        let task = try await holdDiscovery(fixture, source: source, listed: listed, additional: additional)
        await source.fresh.enqueue(source.gate("fresh-first"), outcome: outcome)
        await source.inventory.release(source.gate("discovery"))
        try await waitForGate(source.gate("fresh-first"), in: source.fresh)
        XCTAssertEqual(fixture.controller.state, .stabilizing)
        XCTAssertEqual(fixture.controller.currentName, listed.name)
        return task
    }

    private func holdStableFresh(_ fixture: ForegroundControllerFixture, source: ForegroundControllerSource,
                                listed: CameraMediaItem, fresh: CameraMediaItem,
                                additional: [CameraMediaItem] = [], fails: Bool = false) async throws -> Task<Void, Never> {
        let task = try await holdFirstFresh(fixture, source: source, listed: listed,
                                           additional: additional, outcome: .success(fresh))
        await fixture.delay.enqueue(source.gate("fresh-observation"), outcome: .success(()))
        await source.fresh.release(source.gate("fresh-first"))
        try await waitForGate(source.gate("fresh-observation"), in: fixture.delay)
        XCTAssertEqual(fixture.controller.preparedCount, 0)
        await assertRequests(source, inventories: 3, fresh: 1, preparations: 0)

        await source.fresh.enqueue(source.gate("fresh-stable"), outcome: fails ? .failure(.request) : .success(fresh))
        fixture.clock.instant += 1
        await fixture.delay.release(source.gate("fresh-observation"))
        try await waitForGate(source.gate("fresh-stable"), in: source.fresh)
        return task
    }

    private func holdPreparation(_ fixture: ForegroundControllerFixture, source: ForegroundControllerSource,
                                 listed: CameraMediaItem, fresh: CameraMediaItem,
                                 additional: [CameraMediaItem] = [],
                                 outcome: ForegroundControllerOutcome<Void> = .success(())) async throws -> Task<Void, Never> {
        let task = try await holdStableFresh(fixture, source: source, listed: listed,
                                            fresh: fresh, additional: additional)
        await source.preparation.enqueue(source.gate("prepare"), outcome: outcome)
        await source.fresh.release(source.gate("fresh-stable"))
        try await waitForGate(source.gate("prepare"), in: source.preparation)
        return task
    }

    private func assertStopping(_ fixture: ForegroundControllerFixture, source: ForegroundControllerSource,
                                reason: ForegroundMediaController.StopReason,
                                file: StaticString = #filePath, line: UInt = #line) throws {
        let controller = fixture.controller
        XCTAssertEqual(controller.state, .stopping, file: file, line: line)
        XCTAssertEqual(controller.reason, reason, file: file, line: line)
        XCTAssertTrue(controller.isRunning, file: file, line: line)
        XCTAssertNotNil(controller.task, file: file, line: line)
        try fixture.assertStartRejected(source, file: file, line: line)
    }

    private func assertFinished(_ controller: ForegroundMediaController, state: ForegroundMediaController.State,
                                reason: ForegroundMediaController.StopReason,
                                file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(controller.state, state, file: file, line: line)
        XCTAssertEqual(controller.reason, reason, file: file, line: line)
        XCTAssertFalse(controller.isRunning, file: file, line: line)
        XCTAssertNil(controller.task, file: file, line: line)
        XCTAssertNil(controller.currentName, file: file, line: line)
    }

    private func assertRequests(_ source: ForegroundControllerSource, inventories: Int, fresh: Int, preparations: Int,
                                file: StaticString = #filePath, line: UInt = #line) async {
        let inventoryRequests = await source.inventory.requests()
        let freshRequests = await source.fresh.requests()
        let preparationRequests = await source.preparation.requests()
        XCTAssertEqual(inventoryRequests.count, inventories, file: file, line: line)
        XCTAssertEqual(freshRequests.count, fresh, file: file, line: line)
        XCTAssertEqual(preparationRequests.count, preparations, file: file, line: line)
    }

    private func waitForGate<Input: Sendable, Value: Sendable>(
        _ name: String, in gate: ForegroundControllerGate<Input, Value>
    ) async throws {
        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: .seconds(5))
        repeat {
            if await gate.waiting(name) { return }
            await Task.yield()
        } while clock.now < deadline
        XCTFail("Expected foreground operation did not enter gate \(name)")
        throw URLError(.timedOut)
    }

    private func withFixture(body: (ForegroundControllerFixture) async throws -> Void) async throws {
        let fixture = ForegroundControllerFixture()
        do {
            try await body(fixture)
        } catch {
            await fixture.cleanup()
            throw error
        }
        await fixture.cleanup()
    }
}

private enum ForegroundHeldPhase: CaseIterable { case inventory, fresh, preparation }

private struct ForegroundPreparationRequest: Equatable, Sendable {
    let item: CameraMediaItem
    let size: Int64
}

@MainActor
private final class ForegroundControllerClock {
    var instant: TimeInterval = 0
}

@MainActor
private struct ForegroundPresentation {
    let state: ForegroundMediaController.State
    let reason: ForegroundMediaController.StopReason?
    let prepared: Int
    let pending: Int
    let name: String?
    let running: Bool

    init(_ controller: ForegroundMediaController) {
        state = controller.state
        reason = controller.reason
        prepared = controller.preparedCount
        pending = controller.pendingCount
        name = controller.currentName
        running = controller.isRunning
    }

    func assertPreserved(in controller: ForegroundMediaController,
                         file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(controller.state, state, file: file, line: line)
        XCTAssertEqual(controller.reason, reason, file: file, line: line)
        XCTAssertEqual(controller.preparedCount, prepared, file: file, line: line)
        XCTAssertEqual(controller.pendingCount, pending, file: file, line: line)
        XCTAssertEqual(controller.currentName, name, file: file, line: line)
        XCTAssertEqual(controller.isRunning, running, file: file, line: line)
    }
}

@MainActor
private final class ForegroundControllerSource {
    let name: String
    var available = true
    var admission: ForegroundMediaController.Admission = .ready
    private(set) var availabilityReads = 0
    let inventory = ForegroundControllerGate<Void, [CameraMediaItem]>()
    let fresh = ForegroundControllerGate<CameraMediaItem, CameraMediaItem>()
    let preparation = ForegroundControllerGate<ForegroundPreparationRequest, Void>()

    init(_ name: String) { self.name = name }
    func gate(_ phase: String) -> String { "\(name)-\(phase)" }

    var actions: ForegroundMediaController.Actions {
        ForegroundMediaController.Actions(
            admission: { self.admission },
            available: { self.availabilityReads += 1; return self.available },
            inventory: { try await self.inventory.run(()) },
            freshInfo: { try await self.fresh.run($0) },
            prepare: { try await self.preparation.run(ForegroundPreparationRequest(item: $0, size: $1)) }
        )
    }

    func drain() async {
        await inventory.drain()
        await fresh.drain()
        await preparation.drain()
    }

    func assertNoUnexpectedRequests() async {
        let inventoryCount = await inventory.unexpectedRequestCount()
        let freshCount = await fresh.unexpectedRequestCount()
        let preparationCount = await preparation.unexpectedRequestCount()
        XCTAssertEqual(inventoryCount, 0, "Unexpected \(name) inventory")
        XCTAssertEqual(freshCount, 0, "Unexpected \(name) fresh metadata")
        XCTAssertEqual(preparationCount, 0, "Unexpected \(name) preparation")
    }
}

@MainActor
private final class ForegroundControllerFixture {
    let clock: ForegroundControllerClock
    let delay: ForegroundControllerGate<UInt64, Void>
    let controller: ForegroundMediaController
    private var sources: [ForegroundControllerSource] = []
    private var tasks: [Task<Void, Never>] = []

    init() {
        let clock = ForegroundControllerClock()
        let delay = ForegroundControllerGate<UInt64, Void>()
        self.clock = clock
        self.delay = delay
        controller = ForegroundMediaController(now: { clock.instant }, sleep: { try await delay.run($0) })
    }

    func source(_ name: String) -> ForegroundControllerSource {
        let source = ForegroundControllerSource(name)
        sources.append(source)
        return source
    }

    func start(_ source: ForegroundControllerSource) throws -> Task<Void, Never> {
        guard controller.start(source.actions) else {
            XCTFail("Expected foreground run was not admitted")
            throw URLError(.unknown)
        }
        let task = try XCTUnwrap(controller.task)
        tasks.append(task)
        return task
    }

    func assertStartRejected(_ source: ForegroundControllerSource,
                             file: StaticString = #filePath, line: UInt = #line) throws {
        guard controller.start(source.actions) else { return }
        // Keep even an incorrectly admitted task drainable on assertion failure.
        if let task = controller.task { tasks.append(task) }
        XCTFail("An active or stopping run must keep foreground admission locked", file: file, line: line)
        throw URLError(.unknown)
    }

    func cleanup() async {
        tasks.forEach { $0.cancel() }
        controller.retire()
        for source in sources { await source.drain() }
        await delay.drain()
        for task in tasks { await task.value }
        for source in sources { await source.assertNoUnexpectedRequests() }
        let unexpectedDelays = await delay.unexpectedRequestCount()
        XCTAssertEqual(unexpectedDelays, 0, "Unexpected foreground polling or retry")
    }
}

private enum ForegroundControllerFailure: Error, Sendable { case request, storage }

private enum ForegroundControllerOutcome<Value: Sendable>: Sendable {
    case success(Value)
    case failure(ForegroundControllerFailure)

    func get() throws -> Value {
        switch self {
        case let .success(value): return value
        case .failure(.request): throw URLError(.networkConnectionLost)
        case .failure(.storage): throw ForegroundMediaController.Failure.storage
        }
    }
}

/// A cancelled caller remains held until the test explicitly releases it.
/// Draining also disables future suspension, so an assertion failure cannot
/// leave either an old owner or a newly entered request hanging in cleanup.
private actor ForegroundControllerGate<Input: Sendable, Value: Sendable> {
    private struct Plan {
        let name: String
        let outcome: ForegroundControllerOutcome<Value>
    }
    private var plans: [Plan] = []
    private var held: [String: CheckedContinuation<Void, Never>] = [:]
    private var received: [Input] = []
    private var draining = false
    private var unexpected = 0

    func enqueue(_ name: String, outcome: ForegroundControllerOutcome<Value>) {
        plans.append(Plan(name: name, outcome: outcome))
    }

    func run(_ input: Input) async throws -> Value {
        guard !draining else { throw CancellationError() }
        received.append(input)
        guard !plans.isEmpty else {
            unexpected += 1
            throw ForegroundControllerFailure.request
        }
        let plan = plans.removeFirst()
        await withCheckedContinuation { held[plan.name] = $0 }
        return try plan.outcome.get()
    }

    func waiting(_ name: String) -> Bool { held[name] != nil }
    func requests() -> [Input] { received }
    func unexpectedRequestCount() -> Int { unexpected }

    func release(_ name: String) { held.removeValue(forKey: name)?.resume() }

    func drain() {
        draining = true
        plans.removeAll()
        let continuations = Array(held.values)
        held.removeAll()
        for continuation in continuations { continuation.resume() }
    }
}
