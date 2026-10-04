import XCTest
@testable import OpenEOSCore

final class CameraHTTPTransportTests: XCTestCase {
    func testResponseBodyAccumulatorAcceptsExactly32KiB() {
        var accumulator = CameraHTTPResponseBodyAccumulator()

        XCTAssertTrue(accumulator.append(Data(repeating: 0x01, count: 32 * 1024)))
        XCTAssertEqual(accumulator.data.count, 32 * 1024)
    }

    func testResponseBodyAccumulatorRejectsChunkThatWouldExceed32KiB() {
        var accumulator = CameraHTTPResponseBodyAccumulator()

        XCTAssertTrue(accumulator.append(Data(repeating: 0x02, count: 32 * 1024 - 1)))
        XCTAssertFalse(accumulator.append(Data([0x03, 0x04])))
        XCTAssertEqual(accumulator.data.count, 32 * 1024 - 1)
        XCTAssertEqual(accumulator.data.last, 0x02)
    }

    func testResponseBodyAccumulatorRejectsAdditionalDataAfterLimit() {
        var accumulator = CameraHTTPResponseBodyAccumulator()

        XCTAssertTrue(accumulator.append(Data(repeating: 0x05, count: 32 * 1024)))
        XCTAssertFalse(accumulator.append(Data([0x06])))
        XCTAssertEqual(accumulator.data.count, 32 * 1024)
        XCTAssertEqual(accumulator.data.last, 0x05)
    }

    func testResponseIntegrityAcceptsExactAndEmptyIdentityBodies() {
        for (length, body) in [("2", "{}"), ("0", ""), (" 002\t", "{}"), ("2, 2", "{}")] {
            let response = CameraHTTPResponse(
                statusCode: 200, headers: ["Content-Length": length], body: Data(body.utf8)
            )
            XCTAssertNoThrow(try CameraHTTPResponseIntegrity.validate(response, requestMethod: "PUT"), length)
        }
        let identity = CameraHTTPResponse(
            statusCode: 200, headers: ["Content-Length": "2", "Content-Encoding": " Identity\t"],
            body: Data("{}".utf8)
        )
        XCTAssertNoThrow(try CameraHTTPResponseIntegrity.validate(identity, requestMethod: "POST"))
    }

    func testResponseIntegrityRejectsShortAndExcessIdentityBodies() {
        for (length, body) in [("64", "{"), ("2", ""), ("0", "{}"), ("1", "{}")] {
            let response = CameraHTTPResponse(
                statusCode: 200, headers: ["content-length": length], body: Data(body.utf8)
            )
            XCTAssertThrowsError(try CameraHTTPResponseIntegrity.validate(response, requestMethod: "PUT"), length) {
                guard case CCAPIError.invalidResponse = $0 else { return XCTFail("Expected framing error, got \($0)") }
            }
        }
        let identity = CameraHTTPResponse(
            statusCode: 200, headers: ["content-length": "64", "content-encoding": "identity"], body: Data("{".utf8)
        )
        XCTAssertThrowsError(try CameraHTTPResponseIntegrity.validate(identity, requestMethod: "POST"))
    }

    func testResponseIntegrityRejectsInvalidConflictingAndOverflowingLengths() {
        let lengths = ["", " ", "+2", "-2", "2.0", "2e0", "0x2", "２", "2\n", "2 2", "2,3", "2,", ",2",
                       "9223372036854775808", "18446744073709551616"]
        for length in lengths {
            let response = CameraHTTPResponse(statusCode: 200, headers: ["content-length": length], body: Data("{}".utf8))
            XCTAssertThrowsError(try CameraHTTPResponseIntegrity.validate(response, requestMethod: "GET"), length)
        }
        let maximum = CameraHTTPResponse(statusCode: 200, headers: ["content-length": "9223372036854775807"])
        XCTAssertThrowsError(try CameraHTTPResponseIntegrity.validate(maximum, requestMethod: "GET"))
    }

    func testResponseIntegrityDoesNotCompareMetadataLengthsForBodylessResponses() {
        for (method, status) in [("HEAD", 200), ("head", 200), ("GET", 100), ("GET", 103),
                                 ("PUT", 204), ("GET", 304), ("CONNECT", 200), ("CONNECT", 201)] {
            let response = CameraHTTPResponse(statusCode: status, headers: ["content-length": "64"])
            XCTAssertNoThrow(try CameraHTTPResponseIntegrity.validate(response, requestMethod: method), "\(method) \(status)")
        }
        // A failed CONNECT is an ordinary HTTP response, not a successful tunnel.
        let failure = CameraHTTPResponse(statusCode: 403, headers: ["content-length": "64"])
        XCTAssertThrowsError(try CameraHTTPResponseIntegrity.validate(failure, requestMethod: "CONNECT"))
    }

    func testResponseIntegrityLeavesEncodedAndTransferDecodedBodiesToURLSession() {
        for encoding in ["gzip", "br", "deflate", "gzip, br"] {
            let response = CameraHTTPResponse(
                statusCode: 200, headers: ["content-length": "32", "content-encoding": encoding],
                body: Data("decoded body has a different byte count".utf8)
            )
            XCTAssertNoThrow(try CameraHTTPResponseIntegrity.validate(response, requestMethod: "GET"), encoding)
        }
        // Even if both fields survived URLSession, Transfer-Encoding determines framing.
        let transferred = CameraHTTPResponse(
            statusCode: 200, headers: ["content-length": "64", "transfer-encoding": "chunked"], body: Data("{}".utf8)
        )
        XCTAssertNoThrow(try CameraHTTPResponseIntegrity.validate(transferred, requestMethod: "PUT"))
    }

    func testResponseIntegrityAcceptsResponsesWithoutADeclaredLength() {
        let response = CameraHTTPResponse(statusCode: 200, body: Data("close-delimited content".utf8))
        XCTAssertNoThrow(try CameraHTTPResponseIntegrity.validate(response, requestMethod: "GET"))
    }
}
