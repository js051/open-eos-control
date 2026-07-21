import Foundation
import XCTest

@testable import OpenEOSCore

final class JPEGFrameParserTests: XCTestCase {
    func testExtractsFirstJPEGFromMultipartBytes() throws {
        let jpeg = Data([0xFF, 0xD8, 0x01, 0x02, 0xFF, 0xD9])
        let multipart = Data("--frame\r\nContent-Type: image/jpeg\r\n\r\n".utf8) + jpeg + Data("\r\n--frame\r\n".utf8)

        XCTAssertEqual(try JPEGFrameParser.firstJPEG(in: multipart), jpeg)
    }

    func testRejectsIncompleteAndOversizedFrames() {
        XCTAssertThrowsError(try JPEGFrameParser.firstJPEG(in: Data([0xFF, 0xD8, 0x01])))
        XCTAssertThrowsError(
            try JPEGFrameParser.firstJPEG(
                in: Data([0xFF, 0xD8, 0x01, 0x02, 0xFF, 0xD9]),
                maximumFrameBytes: 5
            )
        )
    }

    func testAcceptsSimulatorPNGWithValidSignature() throws {
        let png = Data([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x01])
        XCTAssertEqual(try JPEGFrameParser.validatedImageData(png, contentType: "image/png"), png)
    }
}
