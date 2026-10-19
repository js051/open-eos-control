import Foundation
import XCTest
@testable import OpenEOSCore

#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Source regressions over the production clients. Gated HTTP responses deliberately
/// ignore caller cancellation. They do not represent physical-camera validation.
final class LiveViewStopOwnershipTests: XCTestCase {
    func testJPEGFailedStopRetainsExactDeleteOrPostOffAndExplicitRetryOnlyStops() async throws {
        for delete in [true, false] {
            let peer = LiveViewOwnershipPeer()
            await peer.plan("GET", "/ccapi", body: discovery(delete: delete))
            await peer.plan("POST", root)
            await peer.plan(delete ? "DELETE" : "POST", root, status: 503)
            let client = try direct(peer)
            try await client.startLiveView(.init(source: .ccapiJPEGPolling))
            await client.stopLiveView()
            let retained = await client.liveViewStopState()
            XCTAssertEqual(retained, .init(stopRequired: true, stopUnconfirmed: true, source: .ccapiJPEGPolling))
            let stoppedSource = await client.currentLiveViewSource()
            XCTAssertNil(stoppedSource)
            do { try await client.startLiveView(.init(source: .auto)); XCTFail("Unconfirmed stop must block Start") }
            catch { XCTAssertEqual(error as? CCAPIError, .liveViewStopUnconfirmed) }
            await peer.plan(delete ? "DELETE" : "POST", root)
            try await client.retryLiveViewStop()
            let after = await client.liveViewStopState()
            XCTAssertEqual(after, .idle)
            let requests = await peer.requests().filter { $0.url?.path.hasSuffix("/liveview/flip") != true }
            XCTAssertEqual(requests.map { $0.httpMethod! }, ["GET", "POST", delete ? "DELETE" : "POST", delete ? "DELETE" : "POST"])
            XCTAssertEqual(requests[2].url, requests[3].url)
            XCTAssertEqual(requests[2].httpBody, requests[3].httpBody)
            if !delete {
                let payload = try XCTUnwrap(JSONSerialization.jsonObject(with: XCTUnwrap(requests[3].httpBody)) as? [String: String])
                XCTAssertEqual(payload, ["liveviewsize": "off", "cameradisplay": "on"])
            }
        }
    }

    func testMultipartFailedStartAndFailedGeneralStopBlockAUTOFallbackEvenWhenReaderDeleteSucceeds() async throws {
        let peer = LiveViewOwnershipPeer()
        await peer.plan("GET", "/ccapi", body: discovery(multipart: true))
        await peer.plan("POST", root)
        await peer.plan("GET", root + "/multipart", body: "not multipart")
        await peer.plan("DELETE", root + "/multipart")
        await peer.plan("DELETE", root, status: 503)
        let client = try direct(peer)
        do { try await client.startLiveView(.init(source: .auto)); XCTFail("Must retain multipart stop") }
        catch { XCTAssertEqual(error as? CCAPIError, .liveViewStopUnconfirmed) }
        let state = await client.liveViewStopState()
        XCTAssertEqual(state.source, .ccapiMultipart)
        XCTAssertTrue(state.stopUnconfirmed)
        await peer.plan("DELETE", root + "/multipart", status: 503)
        await peer.plan("DELETE", root)
        try await client.retryLiveViewStop()
        let requests = await peer.requests().filter { $0.url?.path.hasSuffix("/liveview/flip") != true }
        XCTAssertEqual(requests.filter { $0.httpMethod == "POST" }.count, 1, "No JPEG fallback or replay")
        let final = await client.liveViewStopState()
        XCTAssertEqual(final, .idle, "Authoritative general stop succeeds despite reader 503")
    }

    func testRTPFailedCameraStartAndStopBlockAUTOAndRetryOriginalPayload() async throws {
        let peer = LiveViewOwnershipPeer()
        let native = OwnershipRTPSession()
        await peer.plan("GET", "/ccapi", body: discovery(rtp: true))
        await peer.plan("GET", root + "/rtpsessiondesc", body: sdp)
        await peer.plan("POST", root + "/rtp", lost: true)
        await peer.plan("POST", root + "/rtp", status: 503)
        let client = try direct(peer, factory: OwnershipRTPFactory(native))
        do { try await client.startLiveView(.init(source: .auto)); XCTFail("No fallback with failed RTP stop") }
        catch { XCTAssertEqual(error as? CCAPIError, .liveViewStopUnconfirmed) }
        let closed = await native.closed
        XCTAssertTrue(closed)
        await peer.plan("POST", root + "/rtp")
        try await client.retryLiveViewStop()
        let requests = await peer.requests().filter { $0.url?.path.hasSuffix("/liveview/flip") != true }
        XCTAssertEqual(requests.count, 5)
        let payloads = try requests.suffix(2).map { try JSONSerialization.jsonObject(with: XCTUnwrap($0.httpBody)) as? [String: String] }
        XCTAssertEqual(payloads, [["action": "stop", "ipaddress": ""], ["action": "stop", "ipaddress": ""]])
    }

    func testCallerCancellationKeepsCancellationWhenCompensationLosesAcknowledgement() async throws {
        for lateFailure in [false, true] {
            let peer = LiveViewOwnershipPeer()
            await peer.plan("GET", "/ccapi", body: discovery())
            await peer.plan("POST", root, lost: lateFailure, gate: "start")
            await peer.plan("DELETE", root, status: 503)
            let client = try direct(peer)
            let start = Task { try await client.startLiveView(.init(source: .auto)) }
            defer { start.cancel(); Task { await peer.releaseAll() } }
            try await wait { await peer.waiting("start") }
            start.cancel()
            await peer.release("start")
            do { try await start.value; XCTFail("Caller cancellation must survive") }
            catch { XCTAssertTrue(error is CancellationError) }
            let state = await client.liveViewStopState()
            XCTAssertTrue(state.stopRequired)
            XCTAssertTrue(state.stopUnconfirmed)
            let requests = await peer.requests().filter { $0.url?.path.hasSuffix("/liveview/flip") != true }
            XCTAssertEqual(requests.count, 3)
        }
    }

    func testStopWaitsForLateStartAndConcurrentCallsShareOneCleanup() async throws {
        let peer = LiveViewOwnershipPeer()
        await peer.plan("GET", "/ccapi", body: discovery())
        await peer.plan("POST", root, gate: "start")
        await peer.plan("DELETE", root, gate: "stop")
        let client = try direct(peer)
        let start = Task { try await client.startLiveView(.init(source: .auto)) }
        defer { start.cancel(); Task { await peer.releaseAll() } }
        try await wait { await peer.waiting("start") }
        do { try await client.startLiveView(); XCTFail("Concurrent start must be excluded") } catch {}
        let stop = Task { try await client.retryLiveViewStop() }
        // Cancellation cannot make this fixture's held Start return early.
        await Task.yield()
        let before = await peer.requests()
        XCTAssertEqual(before.count, 2)
        await peer.release("start")
        try await wait { await peer.waiting("stop") }
        let secondStop = Task { try await client.retryLiveViewStop() }
        await peer.release("stop")
        try await stop.value
        try await secondStop.value
        _ = await start.result
        let requests = await peer.requests().filter { $0.url?.path.hasSuffix("/liveview/flip") != true }
        XCTAssertEqual(requests.filter { $0.httpMethod == "DELETE" }.count, 1)
        let state = await client.liveViewStopState()
        XCTAssertEqual(state, .idle)
    }

    func testCancellationDuringLateRTPAllocationClosesListenerWithoutInventingCameraStop() async throws {
        let peer = LiveViewOwnershipPeer()
        let native = OwnershipRTPSession()
        let factory = OwnershipRTPFactory(native, hold: true)
        await peer.plan("GET", "/ccapi", body: discovery(rtp: true))
        await peer.plan("GET", root + "/rtpsessiondesc", body: sdp)
        let client = try direct(peer, factory: factory)
        let start = Task { try await client.startLiveView(.init(source: .auto)) }
        defer { start.cancel(); Task { await factory.release() } }
        try await wait { await factory.waiting }
        start.cancel()
        await factory.release()
        do { try await start.value; XCTFail("Late allocation must be retired") } catch { XCTAssertTrue(error is CancellationError) }
        let closed = await native.closed
        let state = await client.liveViewStopState()
        let requests = await peer.requests().filter { $0.url?.path.hasSuffix("/liveview/flip") != true }
        XCTAssertTrue(closed)
        XCTAssertEqual(state, .idle)
        XCTAssertEqual(requests.count, 2, "No camera Start was dispatched, so there is no camera Stop")
    }

    func testUnsupportedSourceDoesNotCreateStopAndCloseKeepsOldWarningIsolated() async throws {
        let peer = LiveViewOwnershipPeer()
        await peer.plan("GET", "/ccapi", body: discovery())
        let client = try direct(peer)
        do { try await client.startLiveView(.init(source: .ccapiRTP)); XCTFail("RTP is unsupported") } catch {}
        let noObligation = await client.liveViewStopState()
        XCTAssertEqual(noObligation, .idle)
        await peer.plan("POST", root)
        await peer.plan("DELETE", root, status: 503)
        try await client.startLiveView(.init(source: .ccapiJPEGPolling))
        await client.close()
        let retired = await client.liveViewStopState()
        XCTAssertFalse(retired.stopRequired)
        XCTAssertTrue(retired.stopUnconfirmed)
        let replacementPeer = LiveViewOwnershipPeer()
        await replacementPeer.plan("GET", "/ccapi", body: discovery())
        await replacementPeer.plan("POST", root)
        let replacement = try direct(replacementPeer)
        try await replacement.startLiveView(.init(source: .ccapiJPEGPolling))
        await client.stopLiveView()
        let replacementRequests = await replacementPeer.requests().filter { $0.url?.path.hasSuffix("/liveview/flip") != true }
        XCTAssertEqual(replacementRequests.count, 2)
        let replacementSource = await replacement.currentLiveViewSource()
        XCTAssertEqual(replacementSource, .ccapiJPEGPolling)
    }

    func testBridgeLostStopBlocksStartAndRetryUsesOriginalSession() async throws {
        let peer = LiveViewOwnershipPeer()
        await bridgeOpen(peer)
        let startPath = "/v1/session/synthetic-old/liveview/start"
        let stopPath = "/v1/session/synthetic-old/liveview/stop"
        await peer.plan("POST", startPath, body: #"{"active":true}"#)
        await peer.plan("POST", stopPath, body: #"{"code":"LIVE_VIEW_STOP_UNCONFIRMED"}"#, status: 502)
        let client = try DesktopBridgeClient(baseURL: "http://192.0.2.1:18181", transport: peer)
        try await client.initialize()
        try await client.startLiveView()
        await client.stopLiveView()
        let state = await client.liveViewStopState()
        XCTAssertTrue(state.stopUnconfirmed)
        do { try await client.startLiveView(); XCTFail("No Start during recovery") }
        catch { XCTAssertEqual(error as? DesktopBridgeError, .liveViewStopUnconfirmed) }
        await peer.plan("POST", stopPath, body: #"{"active":false,"liveViewStopUnconfirmed":false}"#)
        try await client.retryLiveViewStop()
        let requests = await peer.requests().filter { $0.url?.path.hasSuffix("/liveview/flip") != true }
        XCTAssertEqual(requests.suffix(2).map { $0.url!.path }, [stopPath, stopPath])
        XCTAssertEqual(requests.filter { $0.url!.path == startPath }.count, 1)
        let final = await client.liveViewStopState()
        XCTAssertEqual(final, .idle)
    }

    func testBridgeCancellationAndLateCloseNeverInstallAStartInReplacementSession() async throws {
        let peer = LiveViewOwnershipPeer()
        await bridgeOpen(peer)
        await peer.plan("POST", "/v1/session/synthetic-old/liveview/start", body: #"{"active":true}"#, gate: "start")
        await peer.plan("POST", "/v1/session/synthetic-old/liveview/stop", body: #"{"active":false,"liveViewStopUnconfirmed":false}"#)
        await peer.plan("DELETE", "/v1/session/synthetic-old", body: "", status: 204)
        let client = try DesktopBridgeClient(baseURL: "http://192.0.2.1:18181", transport: peer)
        try await client.initialize()
        let start = Task { try await client.startLiveView() }
        defer { start.cancel(); Task { await peer.releaseAll() } }
        try await wait { await peer.waiting("start") }
        let close = Task { await client.close() }
        try await wait {
            do { try await client.initialize(); return false }
            catch { return error as? DesktopBridgeError == .sessionChanged }
        }
        await peer.release("start")
        await close.value
        let unconfirmed = await client.connectionRetirementUnconfirmed
        XCTAssertFalse(unconfirmed)
        do { try await start.value; XCTFail("Closing Start cannot install") } catch {}
        let requests = await peer.requests().filter { $0.url?.path.hasSuffix("/liveview/flip") != true }
        XCTAssertEqual(requests.suffix(2).map { $0.url!.path }, ["/v1/session/synthetic-old/liveview/stop", "/v1/session/synthetic-old"])
        await bridgeOpen(peer, id: "synthetic-new")
        try await client.initialize()
        let state = await client.liveViewStopState()
        XCTAssertEqual(state, .idle)
        let countBeforeStop = await peer.requests().count
        try await client.retryLiveViewStop()
        let countAfterStop = await peer.requests().count
        XCTAssertEqual(countBeforeStop, countAfterStop, "A retired stop must never be reconstructed for the replacement")
    }

    func testLegacyBridgeStopOnlyAcceptedAfterAcknowledgedStartAndNeverAfterUncertainty() async throws {
        for legacyReply in [#"{"active":false}"#, #"{"active":false,"liveViewStopUnconfirmed":null}"#] {
            for ambiguousStart in [false, true] {
                let peer = LiveViewOwnershipPeer()
                await bridgeOpen(peer)
                let startPath = "/v1/session/synthetic-old/liveview/start"
                let stopPath = "/v1/session/synthetic-old/liveview/stop"
                await peer.plan("POST", startPath, body: #"{"active":true}"#, lost: ambiguousStart)
                await peer.plan("POST", stopPath, body: legacyReply)
                let client = try DesktopBridgeClient(baseURL: "http://192.0.2.1:18181", transport: peer)
                try await client.initialize()
                if ambiguousStart {
                    do { try await client.startLiveView(); XCTFail("Legacy false cannot discharge ambiguous Start") }
                    catch { XCTAssertEqual(error as? DesktopBridgeError, .liveViewStopUnconfirmed) }
                    await peer.plan("POST", stopPath, body: legacyReply)
                    do { try await client.retryLiveViewStop(); XCTFail("Legacy false cannot discharge failed Stop") }
                    catch { XCTAssertEqual(error as? DesktopBridgeError, .liveViewStopUnconfirmed) }
                    await peer.plan("GET", "/v1/session/synthetic-old/status", body: #"{"connected":true,"liveViewStopUnconfirmed":false}"#)
                    _ = try await client.status()
                    let pending = await client.liveViewStopState()
                    XCTAssertTrue(pending.stopUnconfirmed, "Status does not acknowledge the retained stop")
                    await peer.plan("POST", stopPath, body: #"{"active":false,"liveViewStopUnconfirmed":false}"#)
                    try await client.retryLiveViewStop()
                } else {
                    try await client.startLiveView()
                    try await client.retryLiveViewStop()
                }
                let state = await client.liveViewStopState()
                XCTAssertEqual(state, .idle)
            }
        }
    }

    func testMultipartSuccessfulCleanupStillAllowsAUTOFallback() async throws {
        let peer = LiveViewOwnershipPeer()
        await peer.plan("GET", "/ccapi", body: discovery(multipart: true))
        await peer.plan("POST", root)
        await peer.plan("GET", root + "/multipart", body: "bad boundary")
        await peer.plan("DELETE", root + "/multipart", status: 503)
        await peer.plan("DELETE", root)
        await peer.plan("POST", root)
        let client = try direct(peer)
        try await client.startLiveView(.init(source: .auto))
        let source = await client.currentLiveViewSource()
        XCTAssertEqual(source, .ccapiJPEGPolling)
        let requests = await peer.requests().filter { $0.url?.path.hasSuffix("/liveview/flip") != true }
        XCTAssertEqual(requests.map { $0.httpMethod! }, ["GET", "POST", "GET", "DELETE", "DELETE", "POST"])
    }

    func testMaintenanceNeverDispatchesAfterFailedStopForDirectAndBridge() async throws {
        for bridge in [false, true] {
            for cleaning in [false, true] {
                let peer = LiveViewOwnershipPeer()
                if bridge {
                    await bridgeOpen(peer)
                    await peer.plan("POST", "/v1/session/synthetic-old/liveview/start", body: #"{"active":true}"#)
                    await peer.plan("POST", "/v1/session/synthetic-old/liveview/stop", status: 503)
                    let client = try DesktopBridgeClient(baseURL: "http://192.0.2.1:18181", transport: peer)
                    try await client.initialize()
                    try await client.startLiveView()
                    do {
                        if cleaning { try await client.cleanSensor(autoPowerOff: false) }
                        else { try await client.sleepCamera() }
                        XCTFail("Maintenance must require confirmed Stop")
                    } catch { XCTAssertEqual(error as? DesktopBridgeError, .liveViewStopUnconfirmed) }
                } else {
                    let discovery = discovery().replacingOccurrences(of: "]}", with: ",{\"path\":\"/functions/sensorcleaning\",\"post\":true}]}")
                    await peer.plan("GET", "/ccapi", body: discovery)
                    await peer.plan("POST", root)
                    await peer.plan("DELETE", root, status: 503)
                    let client = try direct(peer)
                    try await client.startLiveView(.init(source: .ccapiJPEGPolling))
                    do {
                        if cleaning { try await client.cleanSensor(autoPowerOff: false) }
                        else { try await client.sleepCamera() }
                        XCTFail("Maintenance must require confirmed Stop")
                    } catch { XCTAssertEqual(error as? CCAPIError, .liveViewStopUnconfirmed) }
                }
                let requests = await peer.requests().filter { $0.url?.path.hasSuffix("/liveview/flip") != true }
                XCTAssertFalse(requests.contains { $0.url!.path.contains("sensorcleaning") || $0.url!.path.contains("sensor-cleaning") || $0.url!.path.contains("power/sleep") || $0.url!.path.contains("autopoweroff") })
            }
        }
    }

    func testHealthyCleaningDischargesOldOwnerAndAllowsANewStartForDirectAndBridge() async throws {
        for bridge in [false, true] {
            let peer = LiveViewOwnershipPeer()
            if bridge {
                await bridgeOpen(peer)
                await peer.plan("POST", "/v1/session/synthetic-old/liveview/start", body: #"{"active":true}"#)
                await peer.plan("POST", "/v1/session/synthetic-old/liveview/stop", body: #"{"active":false,"liveViewStopUnconfirmed":false}"#)
                await peer.plan("POST", "/v1/session/synthetic-old/maintenance/sensor-cleaning")
                await peer.plan("POST", "/v1/session/synthetic-old/liveview/start", body: #"{"active":true}"#)
                let client = try DesktopBridgeClient(baseURL: "http://192.0.2.1:18181", transport: peer)
                try await client.initialize()
                try await client.startLiveView()
                try await client.cleanSensor(autoPowerOff: false)
                try await client.startLiveView()
            } else {
                let discovery = discovery().replacingOccurrences(of: "]}", with: ",{\"path\":\"/functions/sensorcleaning\",\"post\":true}]}")
                await peer.plan("GET", "/ccapi", body: discovery)
                await peer.plan("POST", root)
                await peer.plan("DELETE", root)
                await peer.plan("POST", "/ccapi/ver130/functions/sensorcleaning")
                await peer.plan("POST", root)
                let client = try direct(peer)
                try await client.startLiveView(.init(source: .ccapiJPEGPolling))
                try await client.cleanSensor(autoPowerOff: false)
                try await client.startLiveView(.init(source: .ccapiJPEGPolling))
            }
            let requests = await peer.requests().filter { $0.url?.path.hasSuffix("/liveview/flip") != true }
            XCTAssertEqual(requests.suffix(3).map { $0.httpMethod! }, bridge ? ["POST", "POST", "POST"] : ["DELETE", "POST", "POST"])
        }
    }

    func testStatusArrivingDuringStopRevokesLegacyProofButExplicitFalseStillDischarges() async throws {
        let cases: [(String, Bool)] = [
            (#"{"active":false}"#, false),
            (#"{"active":false,"liveViewStopUnconfirmed":null}"#, false),
            (#"{"active":false,"liveViewStopUnconfirmed":false}"#, true),
        ]
        for (reply, explicitProof) in cases {
            let peer = LiveViewOwnershipPeer()
            await bridgeOpen(peer)
            let stopPath = "/v1/session/synthetic-old/liveview/stop"
            await peer.plan("POST", "/v1/session/synthetic-old/liveview/start", body: #"{"active":true}"#)
            await peer.plan("POST", stopPath, body: reply, gate: "stop")
            let client = try DesktopBridgeClient(baseURL: "http://192.0.2.1:18181", transport: peer)
            try await client.initialize()
            try await client.startLiveView()
            let stop = Task { try await client.retryLiveViewStop() }
            defer { Task { await peer.releaseAll() } }
            try await wait { await peer.waiting("stop") }
            await peer.plan("GET", "/v1/session/synthetic-old/status", body: #"{"connected":true,"liveViewStopUnconfirmed":true}"#)
            _ = try await client.status()
            let during = await client.liveViewStopState()
            XCTAssertTrue(during.stopUnconfirmed)
            await peer.release("stop")
            if explicitProof {
                try await stop.value
            } else {
                do { try await stop.value; XCTFail("Status revoked the in-flight Stop's legacy permission") }
                catch { XCTAssertEqual(error as? DesktopBridgeError, .liveViewStopUnconfirmed) }
                let retained = await client.liveViewStopState()
                XCTAssertTrue(retained.stopRequired)
                await peer.plan("POST", stopPath, body: #"{"active":false,"liveViewStopUnconfirmed":false}"#)
                try await client.retryLiveViewStop()
            }
            let final = await client.liveViewStopState()
            XCTAssertEqual(final, .idle)
        }
    }

    func testLateStatusCannotRecreateAcknowledgedStopAndObservedOwnerBlocksStart() async throws {
        for acknowledgeBeforeStatus in [false, true] {
            let peer = LiveViewOwnershipPeer()
            await bridgeOpen(peer)
            let client = try DesktopBridgeClient(baseURL: "http://192.0.2.1:18181", transport: peer)
            try await client.initialize()
            if acknowledgeBeforeStatus {
                await peer.plan("POST", "/v1/session/synthetic-old/liveview/start", body: #"{"active":true}"#)
                try await client.startLiveView()
            }
            await peer.plan("GET", "/v1/session/synthetic-old/status", body: #"{"connected":true,"liveViewStopUnconfirmed":true}"#, gate: "status")
            let status = Task { try await client.status() }
            defer { Task { await peer.releaseAll() } }
            try await wait { await peer.waiting("status") }
            if acknowledgeBeforeStatus {
                await peer.plan("POST", "/v1/session/synthetic-old/liveview/stop", body: #"{"active":false,"liveViewStopUnconfirmed":false}"#)
                try await client.retryLiveViewStop()
            }
            await peer.release("status")
            _ = try await status.value
            let state = await client.liveViewStopState()
            if acknowledgeBeforeStatus {
                XCTAssertEqual(state, .idle, "An old status read must not recreate the acknowledged owner")
            } else {
                XCTAssertTrue(state.stopUnconfirmed)
                do { try await client.startLiveView(); XCTFail("Status-adopted responsibility cannot be overwritten by Start") }
                catch { XCTAssertEqual(error as? DesktopBridgeError, .liveViewStopUnconfirmed) }
                let requests = await peer.requests().filter { $0.url?.path.hasSuffix("/liveview/flip") != true }
                XCTAssertFalse(requests.contains { $0.url!.path.hasSuffix("/liveview/start") })
            }
        }
    }

    func testLateStartAcknowledgementCannotRestoreLegacyEligibilityRevokedByNewerStatus() async throws {
        let peer = LiveViewOwnershipPeer()
        await bridgeOpen(peer)
        await peer.plan("POST", "/v1/session/synthetic-old/liveview/start", body: #"{"active":true}"#, gate: "start")
        let client = try DesktopBridgeClient(baseURL: "http://192.0.2.1:18181", transport: peer)
        try await client.initialize()
        let start = Task { try await client.startLiveView() }
        defer { start.cancel(); Task { await peer.releaseAll() } }
        try await wait { await peer.waiting("start") }
        await peer.plan("GET", "/v1/session/synthetic-old/status", body: #"{"connected":true,"liveViewStopUnconfirmed":true}"#)
        _ = try await client.status()
        await peer.release("start")
        try await start.value
        let retained = await client.liveViewStopState()
        XCTAssertTrue(retained.stopUnconfirmed)
        let stopPath = "/v1/session/synthetic-old/liveview/stop"
        await peer.plan("POST", stopPath, body: #"{"active":false}"#)
        do { try await client.retryLiveViewStop(); XCTFail("Late Start ACK cannot restore revoked legacy proof") }
        catch { XCTAssertEqual(error as? DesktopBridgeError, .liveViewStopUnconfirmed) }
        await peer.plan("POST", stopPath, body: #"{"active":false,"liveViewStopUnconfirmed":false}"#)
        try await client.retryLiveViewStop()
        let final = await client.liveViewStopState()
        XCTAssertEqual(final, .idle)
    }

    private let root = "/ccapi/ver130/shooting/liveview"
    private let sdp = "v=0\no=- 0 0 IN IP4 192.0.2.1\ns=fixture\nc=IN IP4 0.0.0.0\nt=0 0\nm=video 12000 RTP/AVP 96\na=rtpmap:96 H264/90000\n"
    private func direct(_ peer: LiveViewOwnershipPeer, factory: OwnershipRTPFactory? = nil) throws -> CCAPIClient {
        try CCAPIClient(baseURL: "http://192.0.2.1:8080", mode: .camera,
            rtpDestinationAddress: factory == nil ? nil : "192.0.2.2", rtpSessionFactory: factory, transport: peer)
    }
    private func discovery(delete: Bool = true, multipart: Bool = false, rtp: Bool = false) -> String {
        """
        {"ver130":[{"path":"/shooting/liveview","post":true,"delete":\(delete)},
        {"path":"/shooting/liveview/flip","get":true}
        \(multipart ? ",{\"path\":\"/shooting/liveview/multipart\",\"get\":true,\"delete\":true}" : "")
        \(rtp ? ",{\"path\":\"/shooting/liveview/rtpsessiondesc\",\"get\":true},{\"path\":\"/shooting/liveview/rtp\",\"post\":true}" : "")]}
        """
    }
    private func bridgeOpen(_ peer: LiveViewOwnershipPeer, id: String = "synthetic-old") async {
        await peer.plan("GET", "/health", body: #"{"ok":true,"service":"open-eos-control-bridge","version":"0.13.0"}"#)
        await peer.plan("POST", "/v1/session", body: "{\"id\":\"\(id)\",\"engine\":\"ccapi\"}")
    }
    private func wait(_ predicate: () async -> Bool) async throws {
        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: .seconds(3))
        repeat { if await predicate() { return }; await Task.yield() } while clock.now < deadline
        XCTFail("Expected test gate")
        throw URLError(.timedOut)
    }
}

private actor LiveViewOwnershipPeer: CameraHTTPTransport {
    private struct Step: Sendable { let method: String; let path: String; let body: String; let status: Int; let lost: Bool; let gate: String? }
    private var steps: [Step] = []
    private var recorded: [URLRequest] = []
    private var gates: [String: CheckedContinuation<Void, Never>] = [:]
    private var drained = false
    func plan(_ method: String, _ path: String, body: String = "{}", status: Int = 200, lost: Bool = false, gate: String? = nil) {
        steps.append(Step(method: method, path: path, body: body, status: status, lost: lost, gate: gate))
    }
    func requests() -> [URLRequest] { recorded }
    func waiting(_ name: String) -> Bool { gates[name] != nil }
    func release(_ name: String) { gates.removeValue(forKey: name)?.resume() }
    func releaseAll() { drained = true; let pending = gates; gates.removeAll(); pending.values.forEach { $0.resume() } }
    func send(_ request: URLRequest) async throws -> CameraHTTPResponse {
        recorded.append(request)
        if request.httpMethod == "GET", request.url?.path.hasSuffix("/liveview/flip") == true {
            return CameraHTTPResponse(statusCode: 200, headers: ["content-type": "image/jpeg"],
                body: Data([0xff, 0xd8, 1, 0xff, 0xd9]))
        }
        guard !steps.isEmpty else { throw URLError(.unsupportedURL) }
        let step = steps.removeFirst()
        guard request.httpMethod == step.method, request.url?.path == step.path else {
            throw MockTransportError.unexpectedRequest(expected: "\(step.method) \(step.path)", actual: "\(request.httpMethod ?? "") \(request.url?.path ?? "")")
        }
        if let gate = step.gate, !drained { await withCheckedContinuation { gates[gate] = $0 } }
        if step.lost { throw URLError(.networkConnectionLost) }
        return CameraHTTPResponse(statusCode: step.status, headers: ["content-type": "application/json"], body: Data(step.body.utf8))
    }
    func download(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse { throw URLError(.unsupportedURL) }
}

private actor OwnershipRTPSession: CCAPIRTPSession {
    nonisolated let sourceURL = URL(string: "rtp://192.0.2.2:12000")!
    private(set) var closed = false
    func start() async throws {}
    func waitUntilReady() async throws { try Task.checkCancellation() }
    func setTargetFPS(_ fps: Int) async {}
    func close() async { closed = true }
}

private actor OwnershipRTPFactory: CCAPIRTPSessionFactory {
    private let session: OwnershipRTPSession
    private let hold: Bool
    private var gate: CheckedContinuation<Void, Never>?
    var waiting: Bool { gate != nil }
    init(_ session: OwnershipRTPSession, hold: Bool = false) { self.session = session; self.hold = hold }
    func release() { gate?.resume(); gate = nil }
    func makeSession(description: CCAPIRTPSessionDescription, destinationAddress: String) async throws -> any CCAPIRTPSession {
        if hold { await withCheckedContinuation { gate = $0 } }
        return session
    }
}
