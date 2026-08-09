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
}
