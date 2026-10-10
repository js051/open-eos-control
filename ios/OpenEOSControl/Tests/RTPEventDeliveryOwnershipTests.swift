import Foundation
import OpenEOSCore
import XCTest

@testable import OpenEOSControl

/// Counterexample preparation: only the MainActor delivery boundary is held.
/// Real controller makeSession emits synthetic SDP status without bind/start.
/// No audio playback, renderer attachment, socket or camera command is requested.
@MainActor
final class RTPEventDeliveryOwnershipTests: XCTestCase {
    func testQueuedOldAudioStatusCannotOverwriteReplacementStatus() async throws {
        let fixture = RTPDeliveryFixture()
        addTeardownBlock { await fixture.cleanup() }
        let first = try await fixture.session(audioPort: 12010)
        let oldDelivery = try XCTUnwrap(fixture.queue.takeOnly())
        let second = try await fixture.session(audioPort: 12012)
        let newDelivery = try XCTUnwrap(fixture.queue.takeOnly())
        newDelivery()
        XCTAssertEqual(fixture.state.rtpAudioStatus.rtpPort, 12012)
        oldDelivery()
        XCTAssertEqual(fixture.state.rtpAudioStatus.rtpPort, 12012,
                       "A queued status must retain its retired producer identity")
        await first.close()
        await second.close()
        fixture.queue.drain()
    }

    func testQueuedAudioStatusCannotRestoreDisconnectedPresentation() async throws {
        let fixture = RTPDeliveryFixture()
        addTeardownBlock { await fixture.cleanup() }
        let session = try await fixture.session(audioPort: 12010)
        let oldDelivery = try XCTUnwrap(fixture.queue.takeOnly())
        await fixture.state.disconnect()
        XCTAssertEqual(fixture.state.rtpAudioStatus, .inactive)
        oldDelivery()
        XCTAssertEqual(fixture.state.rtpAudioStatus, .inactive,
                       "A delivery held before disconnect must not restore old audio metadata")
        await session.close()
        fixture.queue.discard()
    }

    func testCurrentDeliveryAndCurrentSessionCloseStillUpdatePresentation() async throws {
        let fixture = RTPDeliveryFixture()
        addTeardownBlock { await fixture.cleanup() }
        let session = try await fixture.session(audioPort: 12010)
        let currentDelivery = try XCTUnwrap(fixture.queue.takeOnly())
        currentDelivery()
        XCTAssertTrue(fixture.state.rtpAudioStatus.advertised)
        XCTAssertEqual(fixture.state.rtpAudioStatus.rtpPort, 12010)
        await session.close()
        fixture.queue.drain()
        XCTAssertEqual(fixture.state.rtpAudioStatus, .inactive)
    }
    func testQueuedInactiveFromClosedAStillCannotClearNewB() async throws {
        let fixture = RTPDeliveryFixture()
        addTeardownBlock { await fixture.cleanup() }
        let first = try await fixture.session(audioPort: 12010)
        try XCTUnwrap(fixture.queue.takeOnly())()
        await first.close()
        let oldInactive = try XCTUnwrap(fixture.queue.takeOnly())
        _ = try await fixture.session(audioPort: 12012)
        try XCTUnwrap(fixture.queue.takeOnly())()
        XCTAssertEqual(fixture.state.rtpAudioStatus.rtpPort, 12012)
        oldInactive()
        XCTAssertEqual(fixture.state.rtpAudioStatus.rtpPort, 12012)
    }

    func testVideoSubmissionKeepsOwnerAcrossReplacementOldCloseAndCurrentClose() async throws {
        let fixture = RTPVideoFixture()
        addTeardownBlock { await fixture.cleanup() }
        let first = try await fixture.session()
        let firstTicket = try XCTUnwrap(fixture.deliveries.last)
        let oldVideo: @MainActor () -> Void = {
            fixture.submit(timestamp: 11, owner: firstTicket.owner)
        }
        let second = try await fixture.session()
        let secondTicket = try XCTUnwrap(fixture.deliveries.last)
        XCTAssertNotEqual(firstTicket.owner, secondTicket.owner)
        XCTAssertFalse(fixture.controller.accepts(firstTicket))
        XCTAssertTrue(fixture.controller.accepts(secondTicket))
        fixture.submit(timestamp: 22, owner: secondTicket.owner)
        oldVideo()
        XCTAssertEqual(fixture.rendered.timestamps, [22])
        await first.close()
        XCTAssertTrue(fixture.controller.accepts(secondTicket), "A close must not retire B")
        fixture.submit(timestamp: 23, owner: secondTicket.owner)
        XCTAssertEqual(fixture.rendered.timestamps, [22, 23])
        await second.close()
        fixture.submit(timestamp: 24, owner: secondTicket.owner)
        XCTAssertEqual(fixture.rendered.timestamps, [22, 23])
        XCTAssertFalse(fixture.controller.accepts(secondTicket))
        let inactive = try XCTUnwrap(fixture.deliveries.last)
        XCTAssertTrue(fixture.controller.accepts(inactive))
        if case let .audioStatus(status) = inactive.event {
            XCTAssertEqual(status, .inactive)
        } else { XCTFail("Current close must emit an accepted inactive status") }
    }

    func testSynchronousRetirementRejectsQueuedVideoAndAllowsNewSameDescriptionSession() async throws {
        let fixture = RTPVideoFixture()
        addTeardownBlock { await fixture.cleanup() }
        _ = try await fixture.session()
        let old = try XCTUnwrap(fixture.deliveries.last)
        fixture.controller.retireDelivery()
        XCTAssertFalse(fixture.controller.accepts(old))
        fixture.submit(timestamp: 1, owner: old.owner)
        XCTAssertTrue(fixture.rendered.timestamps.isEmpty)
        _ = try await fixture.session()
        let new = try XCTUnwrap(fixture.deliveries.last)
        XCTAssertNotEqual(old.owner, new.owner)
        fixture.submit(timestamp: 2, owner: new.owner)
        fixture.submit(timestamp: 3, owner: old.owner)
        XCTAssertEqual(fixture.rendered.timestamps, [2])
        fixture.controller.setRenderingEnabled(false)
        fixture.submit(timestamp: 4, owner: new.owner)
        XCTAssertEqual(fixture.rendered.timestamps, [2])
        fixture.controller.setRenderingEnabled(true)
        fixture.submit(timestamp: 5, owner: new.owner)
        XCTAssertEqual(fixture.rendered.timestamps, [2, 5])
    }

    func testVideoOnlySessionCanRenderUntilRetired() async throws {
        let fixture = RTPVideoFixture()
        addTeardownBlock { await fixture.cleanup() }
        _ = try await fixture.session(includeAudio: false)
        let current = try XCTUnwrap(fixture.deliveries.last)
        if case let .audioStatus(status) = current.event {
            XCTAssertFalse(status.advertised)
        } else { XCTFail("Expected video-only session status") }
        fixture.submit(timestamp: 10, owner: current.owner)
        XCTAssertEqual(fixture.rendered.timestamps, [10])
        fixture.controller.retireDelivery()
        fixture.submit(timestamp: 11, owner: current.owner)
        XCTAssertEqual(fixture.rendered.timestamps, [10])
        XCTAssertFalse(fixture.controller.accepts(current))
    }

}

@MainActor
private final class RTPDeliveryFixture {
    let queue: RTPControlledDeliveryQueue
    let state: CameraAppState
    private let suite = "RTPEventDeliveryOwnershipTests.\(UUID().uuidString)"
    private let defaults: UserDefaults
    private var sessions: [any CCAPIRTPSession] = []

    init() {
        defaults = UserDefaults(suiteName: suite)!
        let queue = RTPControlledDeliveryQueue()
        self.queue = queue
        state = CameraAppState(defaults: defaults, rtpEventDispatcher: { queue.append($0) })
    }

    func session(audioPort: Int) async throws -> any CCAPIRTPSession {
        let description = try CCAPIRTPSessionDescriptionParser.parse("""
            v=0
            m=video 12000 RTP/AVP 103
            a=rtpmap:103 H264/90000
            m=audio \(audioPort) RTP/AVP 106
            a=rtpmap:106 MP4A-LATM/48000
            a=fmtp:106 cpresent=1
            """)
        let session = try await state.rtpController.makeSession(description: description,
                                                               destinationAddress: "127.0.0.1")
        sessions.append(session)
        return session
    }

    func cleanup() async {
        await state.disconnect()
        for session in sessions { await session.close() }
        sessions.removeAll()
        queue.discard()
        defaults.removePersistentDomain(forName: suite)
    }
}

private final class RTPControlledDeliveryQueue: @unchecked Sendable {
    private let lock = NSLock()
    private var actions: [@MainActor @Sendable () -> Void] = []

    func append(_ action: @escaping @MainActor @Sendable () -> Void) {
        lock.lock()
        actions.append(action)
        lock.unlock()
    }

    func takeOnly() -> (@MainActor @Sendable () -> Void)? {
        lock.lock()
        defer { lock.unlock() }
        guard actions.count == 1 else {
            XCTFail("Expected exactly one controlled delivery, received \(actions.count)")
            return nil
        }
        return actions.removeFirst()
    }

    @MainActor
    func drain() {
        for action in takeAll() { action() }
    }

    func discard() { _ = takeAll() }

    private func takeAll() -> [@MainActor @Sendable () -> Void] {
        lock.lock()
        defer { lock.unlock() }
        let pending = actions
        actions.removeAll()
        return pending
    }
}


@MainActor
private final class RTPRenderedFrames {
    var timestamps: [UInt32] = []
}

private final class RTPDeliveryRecorder: @unchecked Sendable {
    private let lock = NSLock()
    private var values: [IOSCcapiRTPDelivery] = []

    func append(_ value: IOSCcapiRTPDelivery) {
        lock.lock()
        values.append(value)
        lock.unlock()
    }

    var last: IOSCcapiRTPDelivery? {
        lock.lock()
        defer { lock.unlock() }
        return values.last
    }
}

@MainActor
private final class RTPVideoFixture {
    let rendered: RTPRenderedFrames
    let deliveries = RTPDeliveryRecorder()
    let controller: IOSCcapiRTPController
    private var sessions: [any CCAPIRTPSession] = []

    init() {
        let rendered = RTPRenderedFrames()
        self.rendered = rendered
        controller = IOSCcapiRTPController(videoConsumer: { rendered.timestamps.append($0.rtpTimestamp) })
        let deliveries = self.deliveries
        controller.setEventHandler { deliveries.append($0) }
    }

    func session(includeAudio: Bool = true) async throws -> any CCAPIRTPSession {
        let audio = includeAudio ? """
            m=audio 12010 RTP/AVP 106
            a=rtpmap:106 MP4A-LATM/48000
            a=fmtp:106 cpresent=1
            """ : ""
        let description = try CCAPIRTPSessionDescriptionParser.parse("""
            v=0
            m=video 12000 RTP/AVP 103
            a=rtpmap:103 H264/90000
            \(audio)
            """)
        let session = try await controller.makeSession(description: description,
                                                       destinationAddress: "127.0.0.1")
        sessions.append(session)
        return session
    }

    func submit(timestamp: UInt32, owner: UUID) {
        let frame = CCAPIH264AccessUnit(nalUnits: [Data([0x65, 0x01])], rtpTimestamp: timestamp,
                                       keyFrame: true, sequenceParameterSet: nil, pictureParameterSet: nil)
        controller.enqueue(frame, sequenceParameterSet: nil, pictureParameterSet: nil, sessionID: owner)
    }

    func cleanup() async {
        controller.retireDelivery()
        for session in sessions { await session.close() }
        sessions.removeAll()
    }
}
