#if DEBUG
import Foundation
import OpenEOSCore

// Opt-in deterministic transport for the UI test process. It exercises the real
// Bridge adapter and app connection path, including strict release proof.
actor BridgeShutterRecoveryFixture: CameraHTTPTransport {
    private struct State {
        var unknown: Bool
        var active: Bool
    }
    private let initiallyUnknown: Bool
    private var failFirstClose: Bool
    private var nextID = 0
    private var sessions: [String: State] = [:]
    private var requestLog: [String] = []
    private(set) var stopCount = 0

    init(unknown: Bool, failFirstClose: Bool = false) {
        initiallyUnknown = unknown
        self.failFirstClose = failFirstClose
    }

    func mutations() -> [String] { requestLog.filter { !$0.hasPrefix("GET ") } }

    func send(_ request: URLRequest) async throws -> CameraHTTPResponse {
        let path = request.url!.path
        let method = request.httpMethod ?? "GET"
        let key = "\(method) \(path)"
        requestLog.append(key)
        if key == "GET /health" {
            return response(#"{"ok":true,"service":"open-eos-control-bridge","version":"0.10.0"}"#)
        }
        if key == "POST /v1/session" {
            nextID += 1
            let id = "shutter-fixture-\(nextID)"
            sessions[id] = State(unknown: initiallyUnknown, active: !initiallyUnknown)
            return response(#"{"id":"\#(id)","engine":"libgphoto2"}"#)
        }
        let components = path.split(separator: "/").map(String.init)
        guard components.count >= 3, components[0] == "v1", components[1] == "session",
              let state = sessions[components[2]] else {
            throw URLError(.unsupportedURL)
        }
        let id = components[2]
        let route = components.dropFirst(3).joined(separator: "/")
        switch (method, route) {
        case ("GET", "info"):
            return response(#"{"connected":true,"model":"Shutter recovery fixture \#(id)","serial":"SYNTHETIC","api":"simulated-shutter-recovery"}"#)
        case ("GET", "capabilities"):
            return response(#"{"profile":{"modelName":"Shutter recovery fixture","family":"UNKNOWN","priority":"RESEARCH"},"supported":[],"settings":[],"liveView":{"sources":[],"sizes":[]}}"#)
        case ("POST", "bulb/stop"):
            stopCount += 1
            let released = State(unknown: false, active: false)
            sessions[id] = released
            return response(statusJSON(released))
        case ("GET", "status"):
            return response(statusJSON(state))
        case ("DELETE", ""):
            sessions.removeValue(forKey: id)
            if failFirstClose {
                failFirstClose = false
                return response(#"{"error":{"code":"SHUTTER_RELEASE_UNCONFIRMED","message":"Fixture cleanup release was not confirmed"}}"#, status: 502)
            }
            return response(#"{"ok":true}"#)
        default:
            // Wrong methods, old IDs and unrequested capture commands must fail.
            throw URLError(.unsupportedURL)
        }
    }

    func download(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse {
        throw URLError(.unsupportedURL)
    }

    private func response(_ json: String, status: Int = 200) -> CameraHTTPResponse {
        CameraHTTPResponse(statusCode: status, headers: ["Content-Type": "application/json"], body: Data(json.utf8))
    }

    private func statusJSON(_ state: State) -> String {
        let active = state.unknown ? "null" : String(state.active)
        return #"{"connected":true,"mode":"Manual","recording":false,"bulbExposureActive":\#(active),"shutterReleaseUnconfirmed":\#(state.unknown),"temperature":"disablerelease","battery":{},"media":{},"exposure":{}}"#
    }
}
#endif
