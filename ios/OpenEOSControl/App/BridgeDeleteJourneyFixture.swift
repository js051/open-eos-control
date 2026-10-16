#if DEBUG
import Foundation
import OpenEOSCore

/// Synthetic only: no file deletion, sockets, or camera commands escape this peer.
actor BridgeDeleteJourneyFixture: CameraHTTPTransport {
    private var generation = 0
    private var deleted = Set<String>()
    private var writes = 0
    func send(_ request: URLRequest) async throws -> CameraHTTPResponse {
        let url = request.url!, path = request.url!.path, method = request.httpMethod ?? "GET"
        if path == "/health" { return json(["ok": true, "service": "open-eos-control-bridge", "version": "synthetic"]) }
        if path == "/v1/session" { generation += 1; return json(["id": "delete-ui-\(generation)", "engine": "libgphoto2"], status: 201) }
        if method == "DELETE", path.contains("/media/") {
            writes += 1
            let id = url.lastPathComponent
            if id.hasPrefix("gphoto2-host:") { throw URLError(.networkConnectionLost) }
            deleted.insert(id)
            return CameraHTTPResponse(statusCode: 204)
        }
        if method == "DELETE" { return CameraHTTPResponse(statusCode: 204) }
        if path.hasSuffix("/events") { throw CancellationError() }
        let rows: [[String: Any]] = [
            ["id": "gphoto2:card", "name": "CARD-SYNTHETIC.JPG", "kind": "image", "protected": false, "writable": true],
            ["id": "gphoto2-host:aG9zdA", "name": "HOST-SYNTHETIC.JPG", "kind": "image"],
            ["id": "gphoto2:protected", "name": "PROTECTED-SYNTHETIC.JPG", "kind": "image", "protected": true]
        ].filter { !deleted.contains($0["id"] as! String) }
        if path.hasSuffix("/media") { return json(["items": rows]) }
        if path.contains("/media/"), path.hasSuffix("/info") {
            return json(rows.first { ($0["id"] as? String) == url.deletingLastPathComponent().lastPathComponent } ?? [:])
        }
        if path.hasSuffix("/capabilities") { return json(["supported": ["MEDIA_BROWSER", "MEDIA_DELETE"]]) }
        if path.hasSuffix("/info") { return json(["connected": true, "model": "Synthetic delete \(generation)", "serial": "SYNTHETIC", "api": "fixture"]) }
        if path.hasSuffix("/status") { return json(["connected": true, "recording": false, "mode": "Manual", "battery": [:], "media": [:], "exposure": [:]]) }
        return json([:])
    }
    func download(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse { throw URLError(.unsupportedURL) }
    private func json(_ object: [String: Any], status: Int = 200) -> CameraHTTPResponse {
        CameraHTTPResponse(statusCode: status, body: try! JSONSerialization.data(withJSONObject: object))
    }
}
#endif
