import Foundation
import XCTest
@testable import OpenEOSCore

final class ImageCapturePTPStateMachineTests: XCTestCase {
    typealias PTP = ImageCapturePTP

    func testOnlyAnExactDiscoveredUSBPTPHandleCanOpen() throws {
        for camera in [camera(transport: .other), camera(transport: .unknown), camera(acceptsPTP: false)] {
            let port = FakeImageCapturePTPPort()
            port.discover(camera)
            port.send(.select(camera.handle, requestControlAuthorization: false))
            XCTAssertTrue(port.opens.isEmpty)
        }
        let port = FakeImageCapturePTPPort()
        port.send(.select(PTP.Handle(), requestControlAuthorization: true))
        XCTAssertEqual(port.state.phase, .blocked(.notDiscovered))
        XCTAssertTrue(port.opens.isEmpty)
        XCTAssertTrue(port.authorizationRequests.isEmpty)
    }

    func testEqualDescriptorsAndMissingUUIDDoNotAliasTwoObjects() throws {
        let first = camera(), second = camera()
        XCTAssertEqual(first.displayName, second.displayName)
        XCTAssertEqual(first.vendorID, second.vendorID)
        XCTAssertEqual(first.productID, second.productID)
        XCTAssertNil(first.reportedUUID)
        XCTAssertNotEqual(first.handle, second.handle)
        let port = FakeImageCapturePTPPort()
        port.discover(first)
        port.discover(second)
        port.send(.select(second.handle, requestControlAuthorization: false))
        XCTAssertEqual(try XCTUnwrap(port.opens.last).session.handle, second.handle)
        XCTAssertEqual(port.state.cameras.count, 2)
    }

    func testDeniedRestrictedUnknownAndUndeterminedDoNotAdmitWork() {
        for status in [PTP.Authorization.denied, .restricted, .unknown, .notDetermined] {
            let port = FakeImageCapturePTPPort(control: status, contents: .authorized)
            let device = camera()
            port.discover(device)
            port.send(.select(device.handle, requestControlAuthorization: false))
            port.send(.readDeviceInfo)
            XCTAssertEqual(port.state.phase, .blocked(.authorizationNotGranted(status)))
            XCTAssertTrue(port.opens.isEmpty)
            XCTAssertTrue(port.reads.isEmpty)
            XCTAssertTrue(port.authorizationRequests.isEmpty)
        }
    }

    func testDeniedAndRestrictedDoNotAutomaticallyReprompt() {
        for status in [PTP.Authorization.denied, .restricted, .unknown] {
            let port = FakeImageCapturePTPPort(control: status)
            let device = camera()
            port.discover(device)
            port.send(.select(device.handle, requestControlAuthorization: true))
            XCTAssertTrue(port.authorizationRequests.isEmpty)
            XCTAssertTrue(port.opens.isEmpty)
        }
    }

    func testExplicitAuthorizationRequestHasOneIdentityAndCancelledReplyCannotOpen() throws {
        let port = FakeImageCapturePTPPort(control: .notDetermined)
        let device = camera()
        port.discover(device)
        port.send(.select(device.handle, requestControlAuthorization: true))
        let request = try XCTUnwrap(port.authorizationRequests.last)
        port.send(.readDeviceInfo)
        XCTAssertTrue(port.reads.isEmpty)
        port.send(.cancel)
        port.emit(.authorizationCompleted(request, .authorized))
        XCTAssertTrue(port.opens.isEmpty)
        XCTAssertEqual(port.state.authorization.control, .notDetermined, "Stale grant must not overwrite current state")
        XCTAssertTrue(port.state.cleanup.isEmpty)
        port.send(.authorizationChanged(.init(control: .authorized, contents: .denied)))
        XCTAssertTrue(port.opens.isEmpty, "Refreshing permissions never resumes a cancelled selection")
    }

    func testAuthorizedPromptOpensOnceAndContentsCannotGrantControl() throws {
        let port = FakeImageCapturePTPPort(control: .notDetermined, contents: .authorized)
        let device = camera()
        port.discover(device)
        port.send(.select(device.handle, requestControlAuthorization: true))
        let request = try XCTUnwrap(port.authorizationRequests.last)
        XCTAssertTrue(port.opens.isEmpty)
        port.emit(.authorizationCompleted(request, .authorized))
        port.emit(.authorizationCompleted(request, .authorized))
        XCTAssertEqual(port.opens.count, 1)
        XCTAssertEqual(port.state.authorization.contents, .authorized)
    }

    func testAuthorizationTimeoutAndNewSelectionIgnoreOldGrant() throws {
        let port = FakeImageCapturePTPPort(control: .notDetermined)
        let first = camera(), second = camera()
        port.discover(first)
        port.discover(second)
        port.send(.select(first.handle, requestControlAuthorization: true))
        let old = try XCTUnwrap(port.authorizationRequests.last)
        port.emit(.deadlineExpired(old))
        XCTAssertEqual(port.state.phase, .retired(.authorizationTimedOut))
        port.send(.select(second.handle, requestControlAuthorization: true))
        let current = try XCTUnwrap(port.authorizationRequests.last)
        port.emit(.authorizationCompleted(old, .authorized))
        XCTAssertEqual(port.state.phase, .awaitingAuthorization(current))
        XCTAssertTrue(port.opens.isEmpty)
        port.emit(.authorizationCompleted(current, .denied))
        XCTAssertEqual(port.state.phase, .blocked(.authorizationNotGranted(.denied)))
    }

    func testBothOpenReadyOrdersRequireBothSignals() throws {
        for readyFirst in [false, true] {
            let port = FakeImageCapturePTPPort()
            let request = try port.begin(camera())
            if readyFirst {
                port.emit(.becameReady(request.session))
            } else {
                port.emit(.openCompleted(request, nil))
            }
            port.send(.readDeviceInfo)
            XCTAssertTrue(port.reads.isEmpty)
            if readyFirst {
                port.emit(.openCompleted(request, nil))
            } else {
                port.emit(.becameReady(request.session))
            }
            XCTAssertEqual(port.state.phase, .ready)
            port.send(.readDeviceInfo)
            XCTAssertEqual(port.reads.count, 1)
        }
    }

    func testReadyThenOpenFailureRetainsCloseObligation() throws {
        let port = FakeImageCapturePTPPort()
        let request = try port.begin(camera())
        port.emit(.becameReady(request.session))
        port.emit(.openCompleted(request, .nativeFailure))
        XCTAssertEqual(port.state.phase, .retired(.openFailed))
        XCTAssertEqual(port.closes.count, 1)
        XCTAssertEqual(port.closes.first?.session, request.session)
        port.send(.readDeviceInfo)
        XCTAssertTrue(port.reads.isEmpty)
        XCTAssertFalse(port.state.cleanup.isEmpty)
    }

    func testCancelledOpeningQuarantinesExactObjectAndLateSuccessClosesOnlyOldSession() throws {
        let port = FakeImageCapturePTPPort()
        let first = camera(), second = camera()
        let old = try port.begin(first)
        port.send(.cancel)
        XCTAssertEqual(port.state.cleanup[old.session], .awaitingOpen(old))
        XCTAssertTrue(port.closes.isEmpty, "Wait for the pending open rather than racing it with close")
        port.send(.select(first.handle, requestControlAuthorization: false))
        XCTAssertEqual(port.state.phase, .blocked(.cleanupPending))
        XCTAssertEqual(port.opens.count, 1)
        port.discover(second)
        port.send(.select(second.handle, requestControlAuthorization: false))
        let current = try XCTUnwrap(port.opens.last)
        port.emit(.openCompleted(old, nil))
        XCTAssertEqual(port.closes.last?.session, old.session)
        XCTAssertEqual(port.state.session, current.session)
        port.emit(.becameReady(old.session))
        XCTAssertEqual(port.state.phase, .opening(current, didOpen: false, didBecomeReady: false))
        let close = try XCTUnwrap(port.closes.last)
        port.emit(.closeCompleted(close, nil))
        XCTAssertNil(port.state.cleanup[old.session])
        XCTAssertTrue(port.performed.contains(.releaseSession(old.session)))
        XCTAssertFalse(port.closes.contains(where: { $0.session == current.session }))
    }

    func testTimedOutOpeningStillClosesAfterLateErrorAndReadinessCannotReviveIt() throws {
        let port = FakeImageCapturePTPPort()
        let open = try port.begin(camera())
        port.emit(.deadlineExpired(open))
        port.emit(.becameReady(open.session))
        port.send(.readDeviceInfo)
        XCTAssertEqual(port.state.phase, .retired(.openTimedOut))
        XCTAssertTrue(port.reads.isEmpty)
        port.emit(.openCompleted(open, .nativeFailure))
        XCTAssertEqual(port.closes.last?.session, open.session)
        port.emit(.openCompleted(open, nil))
        XCTAssertEqual(port.closes.count, 1, "Duplicate open callbacks cannot start another close")
    }

    func testOneOutstandingReadAndDistinctBuffersStayUnvalidated() throws {
        let port = FakeImageCapturePTPPort()
        try port.ready(camera())
        port.send(.readDeviceInfo)
        let request = try XCTUnwrap(port.reads.last)
        port.send(.readDeviceInfo)
        XCTAssertEqual(port.reads.count, 1)
        let buffers = PTP.CompletionBuffers(responseData: Data([0x11, 0x22]), ptpResponseData: Data([0x33]))
        port.emit(.deviceInfoCompleted(request, buffers, nil))
        guard case .unvalidatedBuffers(let receipt) = port.state.deviceInfoOutcome else {
            return XCTFail("A framework callback must remain an unvalidated receipt")
        }
        XCTAssertEqual(receipt.request, request)
        XCTAssertEqual(receipt.buffers.responseData, Data([0x11, 0x22]))
        XCTAssertEqual(receipt.buffers.ptpResponseData, Data([0x33]))
        XCTAssertEqual(port.state.phase, .ready)
        port.send(.readDeviceInfo)
        let next = try XCTUnwrap(port.reads.last)
        XCTAssertNotEqual(request, next)
        port.emit(.deviceInfoCompleted(request, buffers, nil))
        XCTAssertEqual(port.state.phase, .readingDeviceInfo(next))
        XCTAssertNil(port.state.deviceInfoOutcome)
    }

    func testEmptyOrMalformedBuffersNeverBecomeDeviceInfoSuccess() throws {
        for buffers in [PTP.CompletionBuffers(responseData: Data(), ptpResponseData: Data()),
                        PTP.CompletionBuffers(responseData: Data([0xff]), ptpResponseData: Data([0x00]))] {
            let port = FakeImageCapturePTPPort()
            try port.ready(camera())
            port.send(.readDeviceInfo)
            let request = try XCTUnwrap(port.reads.last)
            port.emit(.deviceInfoCompleted(request, buffers, nil))
            guard case .unvalidatedBuffers(let receipt) = port.state.deviceInfoOutcome else {
                return XCTFail("There is deliberately no semantic success case without a verified codec")
            }
            XCTAssertEqual(receipt.buffers, buffers)
        }
    }

    func testFrameworkErrorPreservesBuffersAndRetiresWithoutRetry() throws {
        let port = FakeImageCapturePTPPort()
        try port.ready(camera())
        port.send(.readDeviceInfo)
        let request = try XCTUnwrap(port.reads.last)
        let buffers = PTP.CompletionBuffers(responseData: Data([1]), ptpResponseData: Data([2]))
        port.emit(.deviceInfoCompleted(request, buffers, .unsupportedWireContract))
        guard case .frameworkFailure(let receipt) = port.state.deviceInfoOutcome else {
            return XCTFail("Unsupported native framing must fail closed")
        }
        XCTAssertEqual(receipt.buffers, buffers)
        XCTAssertEqual(receipt.failure, .unsupportedWireContract)
        XCTAssertEqual(port.state.phase, .retired(.exchangeFailed))
        port.send(.readDeviceInfo)
        XCTAssertEqual(port.reads.count, 1)
        XCTAssertEqual(port.closes.last?.session, request.session)
    }

    func testTimeoutRetiresEpochAndLateReadCannotAffectNewCamera() throws {
        let port = FakeImageCapturePTPPort()
        try port.ready(camera())
        port.send(.readDeviceInfo)
        let old = try XCTUnwrap(port.reads.last)
        port.emit(.deadlineExpired(old))
        XCTAssertEqual(port.state.deviceInfoOutcome, .outcomeUnknown(old, .exchangeTimedOut))
        port.send(.readDeviceInfo)
        XCTAssertEqual(port.reads.count, 1)
        try port.ready(camera())
        port.send(.readDeviceInfo)
        let current = try XCTUnwrap(port.reads.last)
        port.emit(.deviceInfoCompleted(old, .init(responseData: Data([1]), ptpResponseData: Data([2])), nil))
        port.emit(.deadlineExpired(old))
        XCTAssertEqual(port.state.phase, .readingDeviceInfo(current))
        XCTAssertNil(port.state.deviceInfoOutcome)
    }

    func testEventsAreExactSessionScopedAndNeverCompleteARead() throws {
        let port = FakeImageCapturePTPPort()
        let old = try port.begin(camera())
        port.emit(.ptpEvent(old.session, Data([1])))
        XCTAssertNil(port.state.lastEvent, "Opening is not event-ready")
        port.emit(.openCompleted(old, nil))
        port.emit(.becameReady(old.session))
        port.send(.readDeviceInfo)
        let request = try XCTUnwrap(port.reads.last)
        port.emit(.ptpEvent(old.session, Data([2])))
        XCTAssertEqual(port.state.lastEvent?.bytes, Data([2]))
        XCTAssertEqual(port.state.phase, .readingDeviceInfo(request))
        XCTAssertNil(port.state.deviceInfoOutcome)
        try port.ready(camera())
        port.emit(.ptpEvent(old.session, Data([3])))
        XCTAssertNil(port.state.lastEvent)
    }

    func testRemovalAndReappearanceDoNotReuseOldObjectOrDiscoveryCallbacks() throws {
        let port = FakeImageCapturePTPPort()
        let first = camera()
        let old = try port.begin(first)
        let discovery = try XCTUnwrap(port.state.discovery)
        port.emit(.removed(discovery, first.handle))
        XCTAssertEqual(port.state.cleanup[old.session], .awaitingOpen(old))
        port.send(.startDiscovery)
        let nextDiscovery = try XCTUnwrap(port.state.discovery)
        let replacement = camera()
        port.emit(.discovered(discovery, first))
        XCTAssertTrue(port.state.cameras.isEmpty)
        port.emit(.discovered(nextDiscovery, replacement))
        port.emit(.removed(discovery, replacement.handle))
        XCTAssertNotNil(port.state.cameras[replacement.handle])
        port.send(.select(replacement.handle, requestControlAuthorization: false))
        let current = try XCTUnwrap(port.opens.last)
        port.emit(.openCompleted(old, nil))
        XCTAssertEqual(port.closes.last?.session, old.session)
        XCTAssertEqual(port.state.session, current.session)
    }

    func testPermissionRevocationDuringReadRetiresAdmissionAndRecordsUnknownOutcome() throws {
        let port = FakeImageCapturePTPPort()
        try port.ready(camera())
        port.send(.readDeviceInfo)
        let request = try XCTUnwrap(port.reads.last)
        port.send(.authorizationChanged(.init(control: .denied, contents: .authorized)))
        XCTAssertEqual(port.state.phase, .retired(.authorizationLost))
        XCTAssertEqual(port.state.deviceInfoOutcome, .outcomeUnknown(request, .authorizationLost))
        port.send(.readDeviceInfo)
        port.emit(.ptpEvent(request.session, Data([9])))
        XCTAssertNil(port.state.lastEvent)
        XCTAssertEqual(port.reads.count, 1)
    }

    func testPermissionRevocationWhileOpeningPreservesLateCleanup() throws {
        let port = FakeImageCapturePTPPort()
        let request = try port.begin(camera())
        port.send(.authorizationChanged(.init(control: .unknown, contents: .authorized)))
        XCTAssertEqual(port.state.cleanup[request.session], .awaitingOpen(request))
        port.emit(.openCompleted(request, nil))
        XCTAssertEqual(port.closes.last?.session, request.session)
        XCTAssertEqual(port.state.phase, .retired(.authorizationLost))
    }

    func testSuspensionRetiresReadAndRequiresFreshAuthorizationSnapshot() throws {
        let port = FakeImageCapturePTPPort()
        let device = camera()
        try port.ready(device)
        port.send(.readDeviceInfo)
        let request = try XCTUnwrap(port.reads.last)
        port.send(.suspend)
        XCTAssertNil(port.state.discovery)
        XCTAssertTrue(port.state.cameras.isEmpty)
        XCTAssertEqual(port.state.authorization.control, .unknown)
        XCTAssertEqual(port.state.deviceInfoOutcome, .outcomeUnknown(request, .suspended))
        port.discover(camera())
        let next = try XCTUnwrap(port.state.cameras.values.first)
        port.send(.select(next.handle, requestControlAuthorization: false))
        XCTAssertEqual(port.state.phase, .blocked(.authorizationNotGranted(.unknown)))
    }

    func testCloseTimeoutIsUnresolvedAndLateCloseSuccessReleasesOnlyItsOwner() throws {
        let port = FakeImageCapturePTPPort()
        let device = camera()
        try port.ready(device)
        port.send(.cancel)
        let close = try XCTUnwrap(port.closes.last)
        port.emit(.deadlineExpired(close))
        XCTAssertEqual(port.state.cleanup[close.session], .closing(close, deadlineExpired: true))
        XCTAssertFalse(port.performed.contains(.releaseSession(close.session)))
        port.send(.select(device.handle, requestControlAuthorization: false))
        XCTAssertEqual(port.state.phase, .blocked(.cleanupPending))
        port.emit(.closeCompleted(close, nil))
        port.send(.select(device.handle, requestControlAuthorization: false))
        let current = try XCTUnwrap(port.opens.last)
        XCTAssertNotEqual(current.session, close.session)
        port.emit(.closeCompleted(close, nil))
        port.emit(.sessionClosed(close.session))
        XCTAssertEqual(port.state.phase, .opening(current, didOpen: false, didBecomeReady: false))
    }

    func testReselectionBeforeOldReleaseEffectKeepsNewSessionOwnership() throws {
        let port = FakeImageCapturePTPPort()
        let device = camera()
        try port.ready(device)
        port.send(.cancel)
        let old = try XCTUnwrap(port.closes.last)
        defer { port.onPerform = nil }
        port.onPerform = { effect in
            if effect == .cancelDeadline(old) {
                port.send(.select(device.handle, requestControlAuthorization: false))
            }
        }
        port.emit(.closeCompleted(old, nil))
        let current = try XCTUnwrap(port.opens.last)
        XCTAssertNotEqual(current.session, old.session)
        XCTAssertEqual(current.session.handle, old.session.handle)
        XCTAssertEqual(port.state.session, current.session)
        XCTAssertTrue(port.performed.contains(.releaseSession(old.session)))
        XCTAssertFalse(port.performed.contains(.releaseSession(current.session)))
        XCTAssertEqual(port.state.phase, .opening(current, didOpen: false, didBecomeReady: false))
    }

    func testCloseFailureQuarantinesObjectAndExplicitRetryUsesNewRequest() throws {
        let port = FakeImageCapturePTPPort()
        let device = camera()
        try port.ready(device)
        port.send(.cancel)
        let old = try XCTUnwrap(port.closes.last)
        port.emit(.closeCompleted(old, .nativeFailure))
        XCTAssertEqual(port.state.cleanup[old.session], .closeFailed(old))
        port.send(.select(device.handle, requestControlAuthorization: false))
        XCTAssertEqual(port.state.phase, .blocked(.cleanupPending))
        port.send(.retryFailedClose(old.session))
        let current = try XCTUnwrap(port.closes.last)
        XCTAssertNotEqual(current, old)
        XCTAssertEqual(current.session, old.session)
        port.emit(.closeCompleted(old, nil))
        XCTAssertEqual(port.state.cleanup[old.session], .closing(current, deadlineExpired: false))
        port.emit(.closeCompleted(current, nil))
        XCTAssertNil(port.state.cleanup[old.session])
    }

    func testReentrantCancellationBeforeOpenDispatchDoesNotInventCleanupObligation() throws {
        let port = FakeImageCapturePTPPort()
        defer { port.onPerform = nil }
        port.onPerform = { effect in
            if case .armDeadline = effect { port.send(.cancel) }
        }
        let device = camera()
        port.discover(device)
        port.send(.select(device.handle, requestControlAuthorization: false))
        XCTAssertTrue(port.opens.isEmpty)
        XCTAssertTrue(port.state.cleanup.isEmpty)
        XCTAssertEqual(port.state.phase, .retired(.cancelled))
    }

    func testReentrantCancellationBeforeReadDispatchDoesNotClaimItWasSent() throws {
        let port = FakeImageCapturePTPPort()
        try port.ready(camera())
        var cancelled = false
        defer { port.onPerform = nil }
        port.onPerform = { effect in
            if case .armDeadline = effect, !cancelled {
                cancelled = true
                port.send(.cancel)
            }
        }
        port.send(.readDeviceInfo)
        XCTAssertTrue(port.reads.isEmpty)
        guard case .notDispatched(_, .cancelled) = port.state.deviceInfoOutcome else {
            return XCTFail("Unsent work must be distinguished from unknown camera outcome")
        }
        XCTAssertEqual(port.closes.count, 1)
    }

    func testDuplicateEffectsCannotDispatchTwiceAndUndispatchedCallbacksAreIgnored() throws {
        var state = ImageCapturePTPStateMachine(authorization: .init(control: .authorized, contents: .unknown))
        _ = state.receive(.startDiscovery)
        let discovery = try XCTUnwrap(state.discovery)
        let device = camera()
        _ = state.receive(.discovered(discovery, device))
        let effects = state.receive(.select(device.handle, requestControlAuthorization: false))
        let open = try XCTUnwrap(effects.compactMap { effect -> PTP.Request? in
            if case .openSession(let request) = effect { return request }; return nil
        }.first)
        _ = state.receive(.openCompleted(open, nil))
        _ = state.receive(.becameReady(open.session))
        XCTAssertEqual(state.phase, .opening(open, didOpen: false, didBecomeReady: false))
        XCTAssertTrue(state.admit(.openSession(open)))
        XCTAssertFalse(state.admit(.openSession(open)))
        _ = state.receive(.openCompleted(open, nil))
        _ = state.receive(.becameReady(open.session))
        XCTAssertEqual(state.phase, .ready)
    }

    func testUnresolvedCleanupBudgetBlocksNewOpensWithoutDroppingOldObligations() throws {
        let port = FakeImageCapturePTPPort()
        var pending: [PTP.Request] = []
        for _ in 0..<ImageCapturePTPStateMachine.maximumUnresolvedSessions {
            let request = try port.begin(camera())
            pending.append(request)
            port.emit(.deadlineExpired(request))
        }
        XCTAssertEqual(port.state.cleanup.count, ImageCapturePTPStateMachine.maximumUnresolvedSessions)
        let next = camera()
        port.discover(next)
        port.send(.select(next.handle, requestControlAuthorization: false))
        XCTAssertEqual(port.state.phase, .blocked(.cleanupCapacityReached))
        XCTAssertEqual(port.opens.count, pending.count)
        for request in pending { XCTAssertEqual(port.state.cleanup[request.session], .awaitingOpen(request)) }
        let oldest = try XCTUnwrap(pending.first)
        port.emit(.openCompleted(oldest, nil))
        let close = try XCTUnwrap(port.closes.last)
        port.emit(.deadlineExpired(close))
        port.send(.select(next.handle, requestControlAuthorization: false))
        XCTAssertEqual(port.state.phase, .blocked(.cleanupCapacityReached), "Local deadline cannot free capacity")
        port.emit(.closeCompleted(close, nil))
        XCTAssertEqual(port.state.cleanup.count, pending.count - 1)
        port.send(.select(next.handle, requestControlAuthorization: false))
        XCTAssertEqual(port.opens.count, pending.count + 1)
        XCTAssertEqual(port.opens.last?.session.handle, next.handle)
        for request in pending.dropFirst() {
            XCTAssertEqual(port.state.cleanup[request.session], .awaitingOpen(request))
        }
    }

    func testCancelStopRemovalAndSelectionRetainInterruptedReadOwnership() throws {
        let reasons: [PTP.RetirementReason] = [.cancelled, .discoveryStopped, .removed, .selectionChanged]
        for reason in reasons {
            let port = FakeImageCapturePTPPort()
            let device = camera()
            try port.ready(device)
            port.send(.readDeviceInfo)
            let request = try XCTUnwrap(port.reads.last)
            switch reason {
            case .cancelled: port.send(.cancel)
            case .discoveryStopped: port.send(.stopDiscovery)
            case .removed: port.emit(.removed(try XCTUnwrap(port.state.discovery), device.handle))
            case .selectionChanged:
                let replacement = camera()
                port.discover(replacement)
                port.send(.select(replacement.handle, requestControlAuthorization: false))
                XCTAssertNil(port.state.deviceInfoOutcome, "Old result cannot become new session's result")
            default: XCTFail("Unexpected fixture")
            }
            XCTAssertEqual(port.state.lastInterruptedDeviceInfoOutcome, .outcomeUnknown(request, reason))
            XCTAssertEqual(port.closes.last?.session, request.session)
        }
    }

    func testUnexpectedSessionClosureRetiresOutstandingReadWithoutPhysicalAcknowledgment() throws {
        let port = FakeImageCapturePTPPort()
        try port.ready(camera())
        port.send(.readDeviceInfo)
        let request = try XCTUnwrap(port.reads.last)
        port.emit(.sessionClosed(request.session))
        XCTAssertEqual(port.state.deviceInfoOutcome, .outcomeUnknown(request, .sessionClosed))
        XCTAssertEqual(port.state.phase, .retired(.sessionClosed))
        port.send(.readDeviceInfo)
        XCTAssertEqual(port.reads.count, 1)
    }

    func testPreflightOpenRejectionHasNoNativeCleanupAndKeepsReason() throws {
        let port = FakeImageCapturePTPPort()
        port.onPerform = { effect in
            if case .openSession(let request) = effect {
                port.emit(.openNotDispatched(request, .unsupportedSessionReuse))
            }
        }
        defer { port.onPerform = nil }
        let request = try port.begin(camera())
        XCTAssertEqual(port.state.phase, .blocked(.openNotDispatched(.unsupportedSessionReuse)))
        XCTAssertTrue(port.state.cleanup.isEmpty)
        XCTAssertTrue(port.closes.isEmpty)
        XCTAssertTrue(port.performed.contains(.cancelDeadline(request)))
        port.emit(.openCompleted(request, nil))
        port.emit(.becameReady(request.session))
        XCTAssertEqual(port.state.phase, .blocked(.openNotDispatched(.unsupportedSessionReuse)))
        XCTAssertFalse(port.performed.contains(.releaseSession(request.session)))
    }

    func testLatePreflightRejectionClearsOnlyCancelledOrTimedOutOpen() throws {
        for timeout in [false, true] {
            let port = FakeImageCapturePTPPort()
            let request = try port.begin(camera())
            port.send(timeout ? .deadlineExpired(request) : .cancel)
            let retired = port.state.phase
            XCTAssertEqual(port.state.cleanup[request.session], .awaitingOpen(request))
            port.emit(.openNotDispatched(request, .unsupportedSessionReuse))
            XCTAssertTrue(port.state.cleanup.isEmpty)
            XCTAssertEqual(port.state.phase, retired)
            XCTAssertTrue(port.closes.isEmpty)
        }
    }

    func testLatePreflightRejectionCannotAffectReplacementOwner() throws {
        let port = FakeImageCapturePTPPort()
        let old = try port.begin(camera())
        let current = try port.begin(camera())
        let phase = port.state.phase
        port.emit(.openNotDispatched(old, .unsupportedSessionReuse))
        XCTAssertNil(port.state.cleanup[old.session])
        XCTAssertEqual(port.state.session, current.session)
        XCTAssertEqual(port.state.phase, phase)
        let effects = port.performed.count
        port.emit(.openNotDispatched(old, .unsupportedSessionReuse))
        port.emit(.openNotDispatched(.init(session: current.session), .nativeFailure))
        XCTAssertEqual(port.performed.count, effects)
        XCTAssertEqual(port.state.phase, phase)
    }

    func testReadyEvidencePreventsPreflightRejectionFromClearingObligation() throws {
        for retireFirst in [false, true] {
            let port = FakeImageCapturePTPPort()
            let request = try port.begin(camera())
            port.emit(.becameReady(request.session))
            if retireFirst { port.send(.cancel) }
            let phase = port.state.phase
            let cleanup = port.state.cleanup
            port.emit(.openNotDispatched(request, .unsupportedSessionReuse))
            XCTAssertEqual(port.state.phase, phase)
            XCTAssertEqual(port.state.cleanup, cleanup)
            port.emit(.openCompleted(request, nil))
            if !retireFirst { port.send(.cancel) }
            XCTAssertEqual(port.closes.count, 1)
            XCTAssertEqual(port.closes.first?.session, request.session)
        }
    }

    func testCompletedOpenCannotBeUndispatched() throws {
        let port = FakeImageCapturePTPPort()
        let request = try port.begin(camera())
        port.emit(.openCompleted(request, nil))
        port.emit(.openNotDispatched(request, .unsupportedSessionReuse))
        port.send(.cancel)
        XCTAssertEqual(port.closes.count, 1)
        XCTAssertEqual(port.closes.first?.session, request.session)
    }

    func testExactSessionInvalidationDuringOpeningPreservesLateCleanup() throws {
        let port = FakeImageCapturePTPPort()
        let request = try port.begin(camera())
        port.emit(.sessionInvalidated(request.session, .accessRestricted))
        XCTAssertEqual(port.state.phase, .retired(.sessionInvalidated(.accessRestricted)))
        XCTAssertEqual(port.state.authorization.control, .authorized)
        XCTAssertEqual(port.state.cleanup[request.session], .awaitingOpen(request))
        port.emit(.openCompleted(request, nil))
        XCTAssertEqual(port.closes.count, 1)
        XCTAssertEqual(port.closes.first?.session, request.session)
    }

    func testInvalidationDuringReadIsUnknownNotConfirmedClose() throws {
        let port = FakeImageCapturePTPPort()
        try port.ready(camera())
        port.send(.readDeviceInfo)
        let read = try XCTUnwrap(port.reads.last)
        port.emit(.sessionInvalidated(read.session, .capabilityLost))
        XCTAssertEqual(port.state.deviceInfoOutcome, .outcomeUnknown(read, .sessionInvalidated(.capabilityLost)))
        let close = try XCTUnwrap(port.closes.last)
        XCTAssertEqual(port.state.cleanup[read.session], .closing(close, deadlineExpired: false))
        port.emit(.sessionInvalidated(read.session, .nativeError))
        XCTAssertEqual(port.closes.count, 1)
        XCTAssertFalse(port.performed.contains(.releaseSession(read.session)))
    }

    func testOldSessionInvalidationCannotRevokeReplacement() throws {
        let port = FakeImageCapturePTPPort()
        let old = try port.begin(camera())
        let current = try port.begin(camera())
        let phase = port.state.phase
        port.emit(.sessionInvalidated(old.session, .deviceRemoved))
        XCTAssertEqual(port.state.phase, phase)
        XCTAssertEqual(port.state.session, current.session)
        XCTAssertEqual(port.state.authorization.control, .authorized)
    }

    func testCancelledOpenLateReadyPreventsContradictoryPreflightRefusal() throws {
        let port = FakeImageCapturePTPPort()
        let old = try port.begin(camera())
        port.send(.cancel)
        let phase = port.state.phase
        port.emit(.becameReady(old.session))
        XCTAssertEqual(port.state.phase, phase)
        port.emit(.openNotDispatched(old, .unsupportedSessionReuse))
        XCTAssertEqual(port.state.cleanup[old.session], .awaitingOpen(old))
        XCTAssertEqual(port.state.phase, phase)
        port.send(.readDeviceInfo)
        XCTAssertTrue(port.reads.isEmpty)
        port.emit(.openCompleted(old, nil))
        XCTAssertEqual(port.closes.map(\.session), [old.session])
    }

    func testReplacedOpenLateReadyCannotClearOldDebtOrReviveOwner() throws {
        let port = FakeImageCapturePTPPort()
        let old = try port.begin(camera())
        let current = try port.begin(camera())
        let phase = port.state.phase
        port.emit(.becameReady(old.session))
        port.emit(.openNotDispatched(old, .unsupportedSessionReuse))
        XCTAssertEqual(port.state.cleanup[old.session], .awaitingOpen(old))
        XCTAssertEqual(port.state.phase, phase)
        XCTAssertEqual(port.state.session, current.session)
        port.send(.readDeviceInfo)
        XCTAssertTrue(port.reads.isEmpty)
        port.emit(.openCompleted(old, nil))
        XCTAssertEqual(port.closes.map(\.session), [old.session])
        XCTAssertEqual(port.state.phase, phase)
    }

    private func camera(transport: PTP.Camera.Transport = .usb, acceptsPTP: Bool = true) -> PTP.Camera {
        .init(handle: .init(), displayName: "Synthetic camera", reportedUUID: nil,
              vendorID: 0x1234, productID: 0x5678, transport: transport, acceptsPTP: acceptsPTP)
    }
}

/// Scripted callbacks exercise the production coordinator, reducer and admission.
/// This fake has no lifecycle model, effect pump, packet encoder, timer, or native I/O.
private final class FakeImageCapturePTPPort {
    typealias PTP = ImageCapturePTP
    private let port: RecordingImageCapturePTPPort
    private let coordinator: ImageCapturePTPCoordinator
    var state: ImageCapturePTPStateMachine { coordinator.snapshot }
    var performed: [PTP.Effect] { port.performed }
    var onPerform: ((PTP.Effect) -> Void)? {
        get { port.onPerform }
        set { port.onPerform = newValue }
    }

    init(control: PTP.Authorization = .authorized, contents: PTP.Authorization = .unknown) {
        let port = RecordingImageCapturePTPPort()
        self.port = port
        self.coordinator = try! ImageCapturePTPCoordinator(port: port,
            authorization: .init(control: control, contents: contents))
    }

    func emit(_ event: PTP.Event) {
        port.receive?(event)
        _ = coordinator.snapshot // Join callback processing for deterministic assertions.
    }

    func send(_ event: PTP.Event) { coordinator.send(event) }

    func discover(_ camera: PTP.Camera) {
        if state.discovery == nil { send(.startDiscovery) }
        if let discovery = state.discovery { emit(.discovered(discovery, camera)) }
    }

    func begin(_ camera: PTP.Camera) throws -> PTP.Request {
        discover(camera)
        send(.select(camera.handle, requestControlAuthorization: false))
        return try XCTUnwrap(opens.last)
    }

    func ready(_ camera: PTP.Camera) throws {
        let request = try begin(camera)
        emit(.openCompleted(request, nil))
        emit(.becameReady(request.session))
        XCTAssertEqual(state.phase, .ready)
    }

    var opens: [PTP.Request] { requests { if case .openSession(let r) = $0 { return r }; return nil } }
    var reads: [PTP.Request] { requests { if case .readDeviceInfo(let r) = $0 { return r }; return nil } }
    var closes: [PTP.Request] { requests { if case .closeSession(let r) = $0 { return r }; return nil } }
    var authorizationRequests: [PTP.Request] {
        requests { if case .requestControlAuthorization(let r) = $0 { return r }; return nil }
    }
    private func requests(_ extract: (PTP.Effect) -> PTP.Request?) -> [PTP.Request] {
        performed.compactMap(extract)
    }
}

private final class RecordingImageCapturePTPPort: ImageCapturePTPPort {
    var receive: ((ImageCapturePTP.Event) -> Void)?
    var performed: [ImageCapturePTP.Effect] = []
    var onPerform: ((ImageCapturePTP.Effect) -> Void)?
    func perform(_ effect: ImageCapturePTP.Effect) {
        performed.append(effect)
        onPerform?(effect)
    }
}
