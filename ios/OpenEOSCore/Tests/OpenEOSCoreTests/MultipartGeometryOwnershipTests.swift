import Foundation
import XCTest
@testable import OpenEOSCore

/// Authored native tests for the existing multipart parser; no transport or camera.
final class MultipartGeometryOwnershipTests: XCTestCase {
    private let oldJPEG = Data([0xff, 0xd8, 0x0a, 0x00, 0x0d, 0x0a, 0xff, 0xd9])
    private let freshJPEG = Data([0xff, 0xd8, 0x42, 0xff, 0xd9])

    func testEveryPartialBoundaryHeaderAndBodyRetainsFramingButDropsOldPart() throws {
        let old = part(oldJPEG)
        for split in 1..<old.count {
            var parser = CCAPIMultipartGeometryParser(boundary: "crop")
            let initial = parser.revision
            XCTAssertEqual(try parser.append(Data(old.prefix(split)), revision: initial), [])
            parser.retire()
            let fresh = parser.revision
            let tail = Data(old.dropFirst(split)) + Data("\r\n".utf8) + part(freshJPEG)
            XCTAssertEqual(try parser.append(tail, revision: fresh), [freshJPEG], "split \(split)")
            _ = try parser.append(Data("\r\n--crop--\r\n".utf8), revision: fresh)
            try parser.finish()
        }
    }

    func testReceiveAdmittedBeforeRetirementCannotPublishOrLeaveRelabeledPartialPart() throws {
        var parser = CCAPIMultipartGeometryParser(boundary: "crop")
        let old = parser.revision
        parser.retire()
        let fresh = parser.revision
        let next = part(oldJPEG)
        let split = next.count - 3
        let staleChunk = part(oldJPEG) + Data("\r\n".utf8) + Data(next.prefix(split))
        XCTAssertEqual(try parser.append(staleChunk, revision: old), [])
        let tail = Data(next.dropFirst(split)) + Data("\r\n".utf8) + part(freshJPEG)
        XCTAssertEqual(try parser.append(tail, revision: fresh), [freshJPEG])
    }

    func testCompletedMailboxRetirementDoesNotDiscardNextNewPart() throws {
        var parser = CCAPIMultipartGeometryParser(boundary: "crop")
        let old = parser.revision
        XCTAssertEqual(try parser.append(part(oldJPEG), revision: old), [oldJPEG])
        parser.retire()
        let fresh = parser.revision
        XCTAssertEqual(try parser.append(Data("\r\n".utf8) + part(freshJPEG), revision: fresh), [freshJPEG])
    }

    func testRetiredPartStillEnforcesJPEGAndTerminalIntegrity() throws {
        var invalid = CCAPIMultipartGeometryParser(boundary: "crop")
        let old = invalid.revision
        let malformed = part(Data([0xff, 0xd8, 0x01, 0x02]))
        _ = try invalid.append(Data(malformed.dropLast(2)), revision: old)
        invalid.retire()
        let current = invalid.revision
        XCTAssertThrowsError(try invalid.append(Data(malformed.suffix(2)), revision: current))
        var truncated = CCAPIMultipartGeometryParser(boundary: "crop")
        let initial = truncated.revision
        _ = try truncated.append(Data(part(oldJPEG).dropLast()), revision: initial)
        truncated.retire()
        XCTAssertThrowsError(try truncated.finish())
    }

    private func part(_ jpeg: Data) -> Data {
        Data("--crop\r\nContent-Type: image/jpeg\r\nContent-Length: \(jpeg.count)\r\n\r\n".utf8) + jpeg
    }
}
