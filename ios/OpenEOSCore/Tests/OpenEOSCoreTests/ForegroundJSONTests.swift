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
        let encodings: [(String.Encoding, [UInt8])] = [
            (.utf8, [0xEF, 0xBB, 0xBF]),
            (.utf16LittleEndian, [0xFF, 0xFE]), (.utf16BigEndian, [0xFE, 0xFF]),
            (.utf32LittleEndian, [0xFF, 0xFE, 0, 0]), (.utf32BigEndian, [0, 0, 0xFE, 0xFF]),
        ]
        for (encoding, bom) in encodings {
            for prefix in [Data(), Data(bom)] {
                let valid = try prefix + XCTUnwrap(#" {"path":[],"label":"📷"}"#.data(using: encoding))
                XCTAssertEqual(try decodeForegroundJSONObject(valid)["label"] as? String, "📷")
                let invalid = try prefix + XCTUnwrap(#" {"path":[],"pa\u0074h":[]}"#.data(using: encoding))
                XCTAssertThrowsError(try decodeForegroundJSONObject(invalid))
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
