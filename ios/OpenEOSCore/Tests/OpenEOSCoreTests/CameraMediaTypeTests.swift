import Foundation
import XCTest
@testable import OpenEOSCore

final class CameraMediaTypeTests: XCTestCase {
    func testUnknownConflictingAndAudioNeverReachNativeImageOrStreamRequests() async throws {
        let transport = MockCameraHTTPTransport()
        let ccapi = try CCAPIClient(baseURL: "http://192.0.2.1:8080", mode: .camera, transport: transport)
        let bridge = try DesktopBridgeClient(baseURL: "http://192.0.2.2:18181", transport: transport)
        for item in [CameraMediaItem(id: "unknown", name: "FILE.bin", kind: "other"),
                     CameraMediaItem(id: "conflict", name: "FRAME.JPG", kind: "video"),
                     CameraMediaItem(id: "audio", name: "SOUND.WAV", kind: "audio"),
                     CameraMediaItem(id: "malformed", name: "FRAME.JPG", kind: "image", contentType: "image/")] {
            do { _ = try await ccapi.mediaThumbnail(item); XCTFail(item.id) } catch {}
            do { _ = try await bridge.mediaThumbnail(item); XCTFail(item.id) } catch {}
            do { _ = try await ccapi.mediaPreview(item); XCTFail(item.id) } catch {}
            do { _ = try await bridge.mediaPreview(item); XCTFail(item.id) } catch {}
            do { _ = try await ccapi.openMediaStream(item, offset: 0); XCTFail(item.id) } catch {}
            do { _ = try await bridge.beginMediaPlayback(item); XCTFail(item.id) } catch {}
        }
        let requests = await transport.requests()
        XCTAssertTrue(requests.isEmpty, "Rejected metadata cannot issue discovery, image GET or playback allocation")
    }

    func testStreamResponseRejectsConflictingMIMEAndKeepsGenericVideoSupport() throws {
        let item = CameraMediaItem(id: "video", name: "CLIP.MP4", kind: "video", contentType: "video/mp4")
        for mime in ["image/jpeg", "audio/wav", "text/plain", "application/json", "video/", "not-a-mime"] {
            let response = CameraHTTPStreamResponse(statusCode: 200, headers: ["content-type": mime],
                chunks: AsyncThrowingStream { $0.finish() })
            XCTAssertThrowsError(try CameraMediaStreamResponse(item: item, response: response, fallbackTotalBytes: 4))
        }
        for mime in ["video/mp4", " VIDEO/QUICKTIME ; codecs=avc1", "application/octet-stream"] {
            let response = CameraHTTPStreamResponse(statusCode: 200, headers: ["content-type": mime],
                chunks: AsyncThrowingStream { $0.finish() })
            XCTAssertNoThrow(try CameraMediaStreamResponse(item: item, response: response, fallbackTotalBytes: 4))
        }
    }

    func testAutomaticGatePreservesExactUnicodeBytesAndDoesNotNormalizeLookalikeMIME() {
        let original = CameraMediaItem(id: "caf\u{00e9}", name: "caf\u{00e9}.JPG", kind: "image")
        let changedID = CameraMediaItem(id: "cafe\u{0301}", name: original.name, kind: "image")
        let changedName = CameraMediaItem(id: original.id, name: "cafe\u{0301}.JPG", kind: "image")
        for metadata in [changedID, changedName] {
            XCTAssertFalse(CameraMediaType.admitsFreshAutomaticJPEG(original: original, metadata: metadata, fresh: true))
        }
        XCTAssertEqual(CameraMediaType.classify(CameraMediaItem(id: "unicode", name: "FRAME.JPG", kind: "image",
                                                                contentType: "image/\u{212a}")), .unknown)
        XCTAssertFalse(CameraMediaType.isAutomaticJPEG(CameraMediaItem(id: " ", name: "FRAME.JPG", kind: "image")))
        for name in [".jpg", ".JPEG", "prefix/.jpg", "prefix/FRAME.JPG", "prefix\\FRAME.JPG"] {
            let hidden = CameraMediaItem(id: "hidden", name: name, kind: "image", contentType: "image/jpeg")
            XCTAssertEqual(CameraMediaType.classify(hidden), .photo)
            XCTAssertFalse(CameraMediaType.isAutomaticJPEG(hidden), "A suffix without a basename must not widen the old gate")
        }
    }

    func testRawMIMEAliasesPreserveNullBlankAndRejectAnyDisagreement() throws {
        for key in ["content_type", "contentType", "contenttype", "mimeType", "mimetype"] {
            XCTAssertEqual(try CameraMediaType.declaredMIME(in: [key: " "]), " ")
            XCTAssertEqual(try CameraMediaType.declaredMIME(in: [key: ""]), "")
            XCTAssertNil(try CameraMediaType.declaredMIME(in: [key: NSNull()]))
            XCTAssertThrowsError(try CameraMediaType.declaredMIME(in: [key: 1]))
        }
        let conflicts: [[String: Any]] = [
            ["content_type": "image/jpeg", "contentType": "IMAGE/JPEG"],
            ["contentType": "image/jpeg", "contenttype": "image/jpeg; quality=90"],
            ["mimeType": NSNull(), "mimetype": ""],
            ["content_type": "image/jpeg", "mimetype": "video/mp4"],
        ]
        for fields in conflicts {
            XCTAssertThrowsError(try CameraMediaType.declaredMIME(in: fields))
        }
        XCTAssertEqual(try CameraMediaType.declaredMIME(in: ["content_type": "image/jpeg", "mimeType": "image/jpeg"]),
                       "image/jpeg")
        XCTAssertNil(try CameraMediaType.declaredMIME(in: [:]))
    }

    func testControlsAnywhereInMIMEIncludingParametersFailClosed() {
        for code in Array(0...31) + Array(127...159) {
            let control = String(UnicodeScalar(code)!)
            for mime in ["image/jpeg" + control, "image/jpeg; note=" + control] {
                let item = CameraMediaItem(id: "photo", name: "FRAME.JPG", kind: "image", contentType: mime)
                XCTAssertEqual(CameraMediaType.classify(item), .unknown)
                XCTAssertFalse(CameraMediaType.isAutomaticJPEG(item))
            }
        }
    }

    func testSimulatorListingPreservesBlankMIMEAndRejectsMalformedAliases() async throws {
        let transport = MockCameraHTTPTransport()
        let client = try CCAPIClient(baseURL: "http://127.0.0.1:18080", mode: .simulator, transport: transport)
        for declaration in [#""content_type":7"#, #""contentType":true"#,
                            #""content_type":"image/jpeg","mimetype":"video/mp4""#] {
            let body = #"{"items":[{"id":"photo","name":"FRAME.JPG","kind":"image","# + declaration + "}]}"
            await transport.enqueueJSON(path: "/ccapi/media", body: body)
            do { _ = try await client.listMedia(); XCTFail("Malformed MIME declaration was erased") }
            catch { XCTAssertEqual(error as? CameraMediaMetadataError, .invalidMetadata) }
        }
        await transport.enqueueJSON(path: "/ccapi/media",
            body: #"{"items":[{"id":"photo","name":"FRAME.JPG","kind":"image","content_type":" "}]}"#)
        let items = try await client.listMedia()
        let item = try XCTUnwrap(items.first)
        XCTAssertEqual(item.contentType, " ")
        XCTAssertEqual(CameraMediaType.classify(item), .photo)
        XCTAssertFalse(CameraMediaType.isAutomaticJPEG(item))
    }
}
