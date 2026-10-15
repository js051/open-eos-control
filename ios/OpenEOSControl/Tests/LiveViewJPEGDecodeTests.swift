import Foundation
import UIKit
import XCTest
@testable import OpenEOSControl

@MainActor
final class LiveViewJPEGDecodeTests: XCTestCase {
    private func image() throws -> Data {
        let image = UIGraphicsImageRenderer(size: CGSize(width: 2, height: 2)).image { context in
            UIColor.green.setFill(); context.fill(CGRect(x: 0, y: 0, width: 2, height: 2))
        }
        return try XCTUnwrap(image.jpegData(compressionQuality: 1))
    }

    func testActualBoundedRasterAcceptsImageAndRejectsEnvelopeOnly() throws {
        XCTAssertTrue(IOSLiveViewJPEGDecoder.validates(try image()))
        XCTAssertFalse(IOSLiveViewJPEGDecoder.validates(Data([0xff, 0xd8, 0xff, 0xd9])))
        XCTAssertFalse(IOSLiveViewJPEGDecoder.validates(Data()))
    }

    func testDimensionsAreRejectedBeforeRasterAllocation() throws {
        var bytes = [UInt8](try image())
        let start = try XCTUnwrap((0..<(bytes.count - 9)).first { bytes[$0] == 0xff && [0xc0, 0xc1, 0xc2].contains(bytes[$0 + 1]) })
        // SOF: length(2), precision(1), height(2), width(2).
        bytes[start + 5] = 0x7f; bytes[start + 6] = 0xff
        bytes[start + 7] = 0x7f; bytes[start + 8] = 0xff
        XCTAssertFalse(IOSLiveViewJPEGDecoder.validates(Data(bytes)))
    }

    func testEncodedByteBudgetRejectsBeforeImageSourceAllocation() {
        XCTAssertFalse(IOSLiveViewJPEGDecoder.validates(Data(repeating: 0xff,
            count: IOSLiveViewJPEGDecoder.maximumEncodedBytes + 1)))
    }
}
