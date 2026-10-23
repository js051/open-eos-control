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
    func testStreamResponseRejectsUnrepresentableWildcardRangeLength() throws {
        let item = CameraMediaItem(id: "range-overflow", name: "RANGE.MP4", kind: "video")
        for status in [200, 206] {
            let response = CameraHTTPStreamResponse(
                statusCode: status,
                headers: ["content-type": "video/mp4", "content-length": "1",
                          "content-range": "bytes 0-9223372036854775807/*"],
                chunks: AsyncThrowingStream { $0.finish() }
            )
            XCTAssertThrowsError(try CameraMediaStreamResponse(
                item: item, response: response, fallbackTotalBytes: 16
            )) { error in
                guard case CameraMediaStreamValidationError.invalidContentRange = error else {
                    return XCTFail("Unrepresentable range length must use the existing typed validation error: \(error)")
                }
            }
        }
    }

    func testStreamResponseAcceptsLargestRepresentableZeroBasedRangeLength() throws {
        let item = CameraMediaItem(id: "range-zero", name: "RANGE.MP4", kind: "video")
        let response = CameraHTTPStreamResponse(
            statusCode: 206,
            headers: ["content-type": "video/mp4", "content-length": "1",
                      "content-range": "bytes 0-9223372036854775806/*"],
            chunks: AsyncThrowingStream { $0.finish() }
        )
        let stream = try CameraMediaStreamResponse(item: item, response: response, fallbackTotalBytes: nil)

        XCTAssertEqual(stream.statusCode, 206)
        XCTAssertEqual(stream.rangeStart, 0)
        XCTAssertEqual(stream.contentLength, Int64.max)
        XCTAssertNil(stream.totalBytes)
    }

    func testStreamResponseAcceptsLargestRepresentableNonzeroBasedRangeLength() throws {
        let item = CameraMediaItem(id: "range-one", name: "RANGE.MP4", kind: "video")
        let response = CameraHTTPStreamResponse(
            statusCode: 206,
            headers: ["content-type": "application/octet-stream",
                      "content-range": "bytes 1-9223372036854775807/*"],
            chunks: AsyncThrowingStream { $0.finish() }
        )
        let stream = try CameraMediaStreamResponse(item: item, response: response, fallbackTotalBytes: nil)

        XCTAssertEqual(stream.rangeStart, 1)
        XCTAssertEqual(stream.contentLength, Int64.max)
        XCTAssertNil(stream.totalBytes)
    }

    func testStreamResponseAcceptsSingleByteAtMaximumRangeEndpoint() throws {
        let item = CameraMediaItem(id: "range-last", name: "RANGE.MP4", kind: "video")
        let response = CameraHTTPStreamResponse(
            statusCode: 206,
            headers: ["content-type": "video/mp4",
                      "content-range": "bytes 9223372036854775807-9223372036854775807/*"],
            chunks: AsyncThrowingStream { $0.finish() }
        )
        let stream = try CameraMediaStreamResponse(item: item, response: response, fallbackTotalBytes: nil)

        XCTAssertEqual(stream.rangeStart, Int64.max)
        XCTAssertEqual(stream.contentLength, 1)
        XCTAssertNil(stream.totalBytes)
    }

    func testStreamResponsePreservesKnownTotalBoundaryValidation() throws {
        let item = CameraMediaItem(id: "range-total", name: "RANGE.MP4", kind: "video")
        let valid = CameraHTTPStreamResponse(
            statusCode: 206,
            headers: ["content-type": "video/mp4",
                      "content-range": "bytes 0-9223372036854775806/9223372036854775807"],
            chunks: AsyncThrowingStream { $0.finish() }
        )
        let stream = try CameraMediaStreamResponse(item: item, response: valid, fallbackTotalBytes: 8)
        XCTAssertEqual(stream.contentLength, Int64.max)
        XCTAssertEqual(stream.totalBytes, Int64.max)
        for range in [
            "bytes 1-9223372036854775807/9223372036854775807",
            "bytes 0-9223372036854775806/9223372036854775806",
            "bytes 0-0/0",
            "bytes 2-1/*",
            "bytes 0-9223372036854775808/*",
        ] {
            let invalid = CameraHTTPStreamResponse(
                statusCode: 206, headers: ["content-type": "video/mp4", "content-range": range],
                chunks: AsyncThrowingStream { $0.finish() }
            )
            XCTAssertThrowsError(try CameraMediaStreamResponse(
                item: item, response: invalid, fallbackTotalBytes: 8
            )) { error in
                guard case CameraMediaStreamValidationError.invalidContentRange = error else {
                    return XCTFail("Invalid bounds or total must preserve the typed error: \(error)")
                }
            }
        }
    }

    func testStreamResponsePreservesOrdinaryRangesAndLengthFallbacks() throws {
        let item = CameraMediaItem(id: "range-ordinary", name: "RANGE.MP4", kind: "video")
        let cases: [(range: String?, status: Int, length: String?, fallback: Int64?,
                     expectedStart: Int64, expectedLength: Int64?, expectedTotal: Int64?)] = [
            ("bytes 0-0/*", 206, "9", nil, 0, 1, nil),
            ("bytes 1024-1039/8192", 206, "16", 4096, 1024, 16, 8192),
            ("bytes 4-7/*", 206, nil, 16, 4, 4, 16),
            (nil, 200, "32", 64, 0, 32, 32),
            (nil, 200, nil, 64, 0, nil, 64),
        ]
        for row in cases {
            var headers = ["content-type": "video/mp4"]
            headers["content-range"] = row.range
            headers["content-length"] = row.length
            let response = CameraHTTPStreamResponse(
                statusCode: row.status, headers: headers,
                chunks: AsyncThrowingStream { $0.finish() }
            )
            let stream = try CameraMediaStreamResponse(
                item: item, response: response, fallbackTotalBytes: row.fallback
            )
            XCTAssertEqual(stream.statusCode, row.status)
            XCTAssertEqual(stream.rangeStart, row.expectedStart)
            XCTAssertEqual(stream.contentLength, row.expectedLength)
            XCTAssertEqual(stream.totalBytes, row.expectedTotal)
        }
        let missingRange = CameraHTTPStreamResponse(
            statusCode: 206, headers: ["content-type": "video/mp4", "content-length": "32"],
            chunks: AsyncThrowingStream { $0.finish() }
        )
        XCTAssertThrowsError(try CameraMediaStreamResponse(
            item: item, response: missingRange, fallbackTotalBytes: 64
        )) { error in
            guard case CameraMediaStreamValidationError.invalidContentRange = error else {
                return XCTFail("Partial response without Content-Range must still be rejected: \(error)")
            }
        }
    }
}
