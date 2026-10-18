import Foundation
import Dispatch
import XCTest
@testable import OpenEOSCore

final class ImageCapturePTPCoordinatorTests: XCTestCase {
    typealias PTP = ImageCapturePTP

    func testNilPortBindsAndSecondOwnerCannotStealCallbacks() throws {
        let port = Port()
        let owner = try owner(port)
        XCTAssertNotNil(port.receive)
        XCTAssertThrowsError(try self.owner(port)) { error in
            guard case ImageCapturePTPCoordinator.BindingError.portAlreadyBound = error else {
                return XCTFail("Wrong binding failure: \(error)")
            }
        }
        owner.send(.startDiscovery)
        let discovery = try XCTUnwrap(owner.snapshot.discovery)
        let camera = camera()
        port.receive?(.discovered(discovery, camera))
        XCTAssertEqual(owner.snapshot.cameras[camera.handle], camera)
    }

    func testExistingCallbackIsNeverOverwritten() {
        let port = Port()
        var observed = false
        port.receive = { _ in observed = true }
        XCTAssertThrowsError(try owner(port))
        port.receive?(.cancel)
        XCTAssertTrue(observed)
    }

    func testForeignThreadCallbackReturnsWhilePerformIsStillOnExecutor() throws {
        let port = Port()
        let owner = try owner(port)
        let camera = camera()
        try discover(camera, owner)
        port.onPerform = { effect in
            if case .armDeadline = effect {
                let returned = DispatchSemaphore(value: 0)
                DispatchQueue.global().async {
                    port.receive?(.cancel)
                    returned.signal()
                }
                // Test only: emulate a native API waiting for callback submission.
                // Production ports should return promptly, without waiting on native work.
                XCTAssertEqual(returned.wait(timeout: .now() + 1), .success)
            }
        }
        defer { port.onPerform = nil }
        owner.send(.select(camera.handle, requestControlAuthorization: false))
        XCTAssertEqual(owner.snapshot.phase, .retired(.cancelled))
        XCTAssertFalse(port.effects.contains { if case .openSession = $0 { return true }; return false })
        XCTAssertTrue(owner.snapshot.cleanup.isEmpty)
    }

    func testReentrantQueuedReadCancellationRemainsNotDispatched() throws {
        let port = Port()
        let owner = try owner(port)
        let camera = camera()
        try ready(camera, owner, port)
        port.onPerform = { effect in
            if case .armDeadline = effect { port.receive?(.cancel) }
        }
        defer { port.onPerform = nil }
        owner.send(.readDeviceInfo)
        guard case .notDispatched(_, .cancelled) = owner.snapshot.deviceInfoOutcome else {
            return XCTFail("Queued read must not become an unknown dispatched command")
        }
        XCTAssertFalse(port.effects.contains { if case .readDeviceInfo = $0 { return true }; return false })
        XCTAssertEqual(port.maximumDepth, 1, "Reentrant callbacks must not recursively perform effects")
    }

    func testSnapshotReducesReentrantInputBeforeReturning() throws {
        let port = Port()
        let owner = try owner(port)
        let camera = camera()
        try discover(camera, owner)
        port.onPerform = { effect in
            if case .armDeadline = effect {
                port.receive?(.cancel)
                XCTAssertEqual(owner.snapshot.phase, .retired(.cancelled))
            }
        }
        defer { port.onPerform = nil }
        owner.send(.select(camera.handle, requestControlAuthorization: false))
        XCTAssertEqual(owner.snapshot.phase, .retired(.cancelled))
        XCTAssertEqual(port.maximumDepth, 1)
    }

    func testReentrantSendThenSnapshotCancelsReadWithoutNestedEffects() throws {
        let port = Port()
        let owner = try owner(port)
        try ready(camera(), owner, port)
        var cancelled = false
        port.onPerform = { effect in
            guard case .armDeadline = effect, !cancelled else { return }
            cancelled = true
            let count = port.effects.count
            owner.send(.cancel)
            guard case .notDispatched(_, .cancelled) = owner.snapshot.deviceInfoOutcome else {
                return XCTFail("Snapshot must reduce the reentrant cancellation")
            }
            XCTAssertEqual(port.effects.count, count, "Snapshot must not recursively perform")
        }
        defer { port.onPerform = nil }
        owner.send(.readDeviceInfo)
        XCTAssertFalse(port.effects.contains { if case .readDeviceInfo = $0 { return true }; return false })
        XCTAssertEqual(port.maximumDepth, 1)
    }

    func testOldDeadlineAndLateOpenOnlyCloseOriginalOwner() throws {
        let port = Port()
        let owner = try owner(port)
        let first = camera(), second = camera()
        try discover(first, owner)
        try discover(second, owner)
        owner.send(.select(first.handle, requestControlAuthorization: false))
        let old = try XCTUnwrap(port.opens.last)
        owner.send(.select(second.handle, requestControlAuthorization: false))
        let current = try XCTUnwrap(port.opens.last)
        port.receive?(.deadlineExpired(old))
        port.receive?(.openCompleted(old, nil))
        let snapshot = owner.snapshot
        XCTAssertEqual(snapshot.phase, .opening(current, didOpen: false, didBecomeReady: false))
        XCTAssertEqual(port.closes.map(\.session), [old.session])
        let close = try XCTUnwrap(port.closes.last)
        port.receive?(.deadlineExpired(close))
        XCTAssertEqual(owner.snapshot.cleanup[old.session], .closing(close, deadlineExpired: true))
        XCTAssertFalse(port.effects.contains { if case .releaseSession(old.session) = $0 { return true }; return false })
    }

    func testConcurrentExternalCallbacksAreSerializedWithoutLosingInputs() throws {
        let port = Port()
        let owner = try owner(port)
        owner.send(.startDiscovery)
        let discovery = try XCTUnwrap(owner.snapshot.discovery)
        let cameras = (0..<64).map { _ in camera() }
        DispatchQueue.concurrentPerform(iterations: cameras.count) { index in
            port.receive?(.discovered(discovery, cameras[index]))
        }
        XCTAssertEqual(owner.snapshot.cameras.count, cameras.count)
        for camera in cameras { XCTAssertEqual(owner.snapshot.cameras[camera.handle], camera) }
        XCTAssertEqual(port.maximumDepth, 1)
    }

    private func owner(_ port: Port) throws -> ImageCapturePTPCoordinator {
        try ImageCapturePTPCoordinator(port: port,
            authorization: .init(control: .authorized, contents: .unknown))
    }
    private func camera() -> PTP.Camera { .init(handle: .init(), transport: .usb, acceptsPTP: true) }
    private func discover(_ camera: PTP.Camera, _ owner: ImageCapturePTPCoordinator) throws {
        if owner.snapshot.discovery == nil { owner.send(.startDiscovery) }
        owner.send(.discovered(try XCTUnwrap(owner.snapshot.discovery), camera))
    }
    private func ready(_ camera: PTP.Camera, _ owner: ImageCapturePTPCoordinator, _ port: Port) throws {
        try discover(camera, owner)
        owner.send(.select(camera.handle, requestControlAuthorization: false))
        let request = try XCTUnwrap(port.opens.last)
        owner.send(.openCompleted(request, nil))
        owner.send(.becameReady(request.session))
        XCTAssertEqual(owner.snapshot.phase, .ready)
    }
}

private final class Port: ImageCapturePTPPort, @unchecked Sendable {
    var receive: ((ImageCapturePTP.Event) -> Void)?
    // Effects and onPerform are inspected/changed only while the coordinator is idle,
    // or synchronously on its executor. Callback threads only read the fixed receive slot.
    var effects: [ImageCapturePTP.Effect] = []
    var onPerform: ((ImageCapturePTP.Effect) -> Void)?
    private var depth = 0
    var maximumDepth = 0
    func perform(_ effect: ImageCapturePTP.Effect) {
        depth += 1
        maximumDepth = max(maximumDepth, depth)
        defer { depth -= 1 }
        effects.append(effect)
        onPerform?(effect)
    }
    var opens: [ImageCapturePTP.Request] { effects.compactMap { if case .openSession(let r) = $0 { return r }; return nil } }
    var closes: [ImageCapturePTP.Request] { effects.compactMap { if case .closeSession(let r) = $0 { return r }; return nil } }
}
