import Foundation
import XCTest
@testable import OpenEOSCore

final class ForegroundJSONTests: XCTestCase {
    func testStrictDecoderRejectsDuplicatesAtEveryObjectDepthAndEscapedAliases() throws {
        let bodies = [
            #"{"pagenumber":2,"pagenumber":0}"#,
            #"{"path":[null],"path":[]}"#,
            #"{"filesize":9,"filesize":4}"#,
            #"{"pagenumber":0,"page\u006eumber":0}"#,
            #"{"outer":{"path":[],"path":[]}}"#,
            #"{"outer":[{"filesize":1,"filesize":2}]}"#,
            #"{"\uD83D\uDCF7":1,"📷":2}"#,
            #"{"é":1,"e\u0301":2}"#,
            #"{"":1,"":2}"#,
            #"{"a\\b":1,"a\u005cb":2}"#,
        ]
        for body in bodies {
            XCTAssertThrowsError(try decodeForegroundJSONObject(Data(body.utf8)), body) { error in
                XCTAssertEqual(error as? CameraForegroundMediaError, .invalidInventory)
            }
        }
    }

    func testValidObjectsRetainValuesAndSiblingObjectsMayReuseNames() throws {
        let body = #"{"pagenumber":0,"path":[],"nested":[{"id":1},{"id":2}],"text":"{\"path\":[],\"path\":[]} \\ / \uD83D\uDCF7","numbers":[-0,1.25,1e3,-2.5E-2],"flags":[true,false,null],"empty":{},"distinct":{"A":1,"a":2}}"#
        let strict = try decodeForegroundJSONObject(Data(body.utf8))
        let ordinary = try decodeJSONObject(Data(body.utf8))
        XCTAssertEqual(try encodeJSONObject(strict), try encodeJSONObject(ordinary))
        XCTAssertEqual((strict["nested"] as? [[String: Int]])?.compactMap { $0["id"] }, [1, 2])
        XCTAssertNoThrow(try decodeForegroundJSONObject(Data(" \r\n\t{} \r\n".utf8)))
    }

    func testSupportedUnicodeEncodingsPreserveDuplicateEvidenceAndValidObjects() throws {
        let encodings: [(String, String.Encoding, [UInt8], [UInt8])] = [
            ("UTF-8", .utf8, [0xEF, 0xBB, 0xBF], [0x20, 0x7B]),
            ("UTF-16LE", .utf16LittleEndian, [0xFF, 0xFE], [0x20, 0, 0x7B, 0]),
            ("UTF-16BE", .utf16BigEndian, [0xFE, 0xFF], [0, 0x20, 0, 0x7B]),
            ("UTF-32LE", .utf32LittleEndian, [0xFF, 0xFE, 0, 0], [0x20, 0, 0, 0, 0x7B, 0, 0, 0]),
            ("UTF-32BE", .utf32BigEndian, [0, 0, 0xFE, 0xFF], [0, 0, 0, 0x20, 0, 0, 0, 0x7B]),
        ]
        for (name, encoding, bom, leadingBytes) in encodings {
            for withBOM in [false, true] {
                let prefix = withBOM ? Data(bom) : Data()
                let label = "\(name), BOM=\(withBOM)"
                let valid = try prefix + XCTUnwrap(#" {"path":[],"label":"📷"}"#.data(using: encoding))
                let duplicate = try prefix + XCTUnwrap(#" {"path":[],"pa\u0074h":[]}"#.data(using: encoding))
                let expectedPrefix = prefix + Data(leadingBytes)
                XCTAssertTrue(valid.starts(with: expectedPrefix), label)
                XCTAssertTrue(duplicate.starts(with: expectedPrefix), label)
                print("Foreground JSON fixture \(label), firstBytes=\(Array(valid.prefix(16)))")

                // Fixed scanner expectations for all ten valid encodings. The
                // whole-document Foundation gate cannot mask scanner failures.
                var validScanner = try ForegroundJSONMembers(valid)
                XCTAssertNoThrow(try validScanner.validate(), label)
                var duplicateScanner = try ForegroundJSONMembers(duplicate)
                XCTAssertThrowsError(try duplicateScanner.validate(), label) { error in
                    XCTAssertEqual(error as? CameraForegroundMediaError, .invalidInventory, label)
                }

                // The public-path contract retains this platform's ordinary
                // Foundation acceptance. Capture each result independently so a
                // strict failure cannot be mistaken for a baseline rejection.
                let ordinary = Result { try decodeJSONObject(valid) }
                let strict = Result { try decodeForegroundJSONObject(valid) }
                switch (ordinary, strict) {
                case let (.success(baseline), .success(actual)):
                    XCTAssertEqual(try encodeJSONObject(actual), try encodeJSONObject(baseline), label)
                    XCTAssertEqual(actual["label"] as? String, "📷", label)
                    print("Foreground JSON compatibility \(label): ordinary=accepted strict=accepted")
                case let (.failure(baseline), .failure(actual)):
                    let baselineError = baseline as NSError
                    let strictError = actual as NSError
                    XCTAssertEqual(strictError.domain, baselineError.domain, label)
                    XCTAssertEqual(strictError.code, baselineError.code, label)
                    print("Foreground JSON compatibility \(label): ordinary=\(baselineError.domain)/\(baselineError.code) strict=\(strictError.domain)/\(strictError.code)")
                case (.success, .failure(let error)):
                    XCTFail("\(label): strict rejected ordinary-accepted JSON: \(error)")
                case (.failure(let error), .success):
                    XCTFail("\(label): strict accepted ordinary-rejected JSON: \(error)")
                }

                let ordinaryDuplicate = Result { try decodeJSONObject(duplicate) }
                let strictDuplicate = Result { try decodeForegroundJSONObject(duplicate) }
                switch (ordinaryDuplicate, strictDuplicate) {
                case (.success, .failure(let error)):
                    XCTAssertEqual(error as? CameraForegroundMediaError, .invalidInventory, label)
                case let (.failure(baseline), .failure(actual)):
                    XCTAssertEqual((actual as NSError).domain, (baseline as NSError).domain, label)
                    XCTAssertEqual((actual as NSError).code, (baseline as NSError).code, label)
                default:
                    XCTFail("\(label): strict decoder accepted duplicate members")
                }
            }
        }
    }

    func testDeepValidObjectsAndDuplicatesRemainBounded() throws {
        func nested(_ value: String, count: Int) -> Data {
            Data((String(repeating: #"{"level":"#, count: count)
                + value + String(repeating: "}", count: count)).utf8)
        }
        XCTAssertNoThrow(try decodeForegroundJSONObject(nested(#"{"path":[]}"#, count: 32)))
        XCTAssertThrowsError(try decodeForegroundJSONObject(nested(#"{"path":[],"path":[]}"#, count: 32))) {
            XCTAssertEqual($0 as? CameraForegroundMediaError, .invalidInventory)
        }
        // Either Foundation's syntax/depth guard or the scanner must reject it.
        // Do not promise that Foundation accepts every depth up to our ceiling.
        XCTAssertThrowsError(try decodeForegroundJSONObject(nested("{}", count: 513)))

        // Reach the scanner's exact boundary independently of Foundation's
        // whole-document nesting limit. These constructed inputs are valid JSON.
        var atLimit = try ForegroundJSONMembers(nested("0", count: 512))
        XCTAssertNoThrow(try atLimit.validate())
        var beyondLimit = try ForegroundJSONMembers(nested("0", count: 513))
        XCTAssertThrowsError(try beyondLimit.validate()) {
            XCTAssertEqual($0 as? CameraForegroundMediaError, .invalidInventory)
        }
        var deepDuplicate = try ForegroundJSONMembers(nested(#"{"key":1,"key":2}"#, count: 511))
        XCTAssertThrowsError(try deepDuplicate.validate()) {
            XCTAssertEqual($0 as? CameraForegroundMediaError, .invalidInventory)
        }
    }

    func testMalformedJSONAndNonObjectsStillFailAndManualDecoderIsUnchanged() throws {
        for body in ["", "[]", "null", #"{"a":}"#, #"{"a":"\q"}"#, #"{"a":1} trailing"#] {
            XCTAssertThrowsError(try decodeForegroundJSONObject(Data(body.utf8)), body)
        }
        // Existing manual semantics stay available; do not assert which duplicate wins.
        XCTAssertNoThrow(try decodeJSONObject(Data(#"{"pagenumber":2,"pagenumber":0}"#.utf8)))
        XCTAssertTrue(try decodeJSONObject(Data()).isEmpty)
    }
}
