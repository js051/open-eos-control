#if DEBUG
import Foundation
import OpenEOSCore

/// Opt-in synthetic peer only. The real OS document picker and app admission,
/// staging and Bridge client still run. No requests leave this fixture.
actor BridgeUploadJourneyFixture: CameraHTTPTransport {
    private let mode: String
    private var nextSession = 0
    private var uploads = 0
    init(mode: String) { self.mode = mode }

    static func seedPickerFile() throws -> URL {
        let documents = try FileManager.default.url(for: .documentDirectory, in: .userDomainMask,
                                                    appropriateFor: nil, create: true)
        let folder = documents.appendingPathComponent("Synthetic Upload Journey", isDirectory: true)
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        let file = folder.appendingPathComponent("SYNTHETIC-UPLOAD.JPG")
        try Data([0xff, 0xd8, 0xff, 0xd9]).write(to: file, options: .atomic)
        return file
    }

    func send(_ request: URLRequest) async throws -> CameraHTTPResponse {
        let path = request.url!.path, method = request.httpMethod ?? "GET"
        if path == "/health" { return json(["ok": true, "service": "open-eos-control-bridge", "version": "synthetic"]) }
        if path == "/v1/session", method == "POST" {
            nextSession += 1
            return json(["id": "upload-ui-\(nextSession)", "engine": "libgphoto2"], status: 201)
        }
        if method == "DELETE" { return json(["ok": true]) }
        if path.hasSuffix("/info") {
            return json(["connected": true, "model": "Synthetic upload camera \(nextSession)", "serial": "SYNTHETIC", "api": "fixture"])
        }
        if path.hasSuffix("/capabilities") {
            return json(["supported": ["MEDIA_BROWSER", "MEDIA_UPLOAD"], "settings": [], "liveView": ["sources": [], "sizes": []]])
        }
        if path.hasSuffix("/status") {
            return json(["connected": true, "recording": false, "mode": "Manual", "battery": [:], "media": [:], "exposure": [:]])
        }
        if path.hasSuffix("/media") {
            // An unrelated same-name/same-size file always precedes any receipt.
            return json(["items": [["id": "existing-other-folder", "name": "SYNTHETIC-UPLOAD.JPG", "kind": "photo",
                                     "sizeBytes": 4, "folderId": "other-folder", "folderLabel": "Other folder"]]])
        }
        throw URLError(.unsupportedURL)
    }
    func upload(_ request: URLRequest, from fileURL: URL,
                progress: @escaping CameraMediaProgressHandler) async throws -> CameraHTTPUploadResponse {
        uploads += 1
        let size = Int64(try Data(contentsOf: fileURL).count)
        progress(CameraMediaTransferProgress(bytesTransferred: size, totalBytes: size))
        if mode == "cancel" { try await Task.sleep(for: .seconds(60)); throw URLError(.timedOut) }
        if mode == "lost-ack" { throw URLError(.networkConnectionLost) }
        return CameraHTTPUploadResponse(statusCode: 201, body: try JSONSerialization.data(withJSONObject: [
            "id": "this-upload-\(uploads)", "name": "SYNTHETIC-UPLOAD.JPG", "kind": "photo", "sizeBytes": size,
        ]))
    }
    func download(_ request: URLRequest) async throws -> CameraHTTPDownloadResponse { throw URLError(.unsupportedURL) }
    private func json(_ object: [String: Any], status: Int = 200) -> CameraHTTPResponse {
        CameraHTTPResponse(statusCode: status, body: try! JSONSerialization.data(withJSONObject: object))
    }
}
#endif
