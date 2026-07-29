import XCTest

@testable import OpenEOSCore

final class CameraMediaTransferProgressTests: XCTestCase {
    func testKnownTotalProducesBoundedFraction() {
        XCTAssertEqual(
            CameraMediaTransferProgress(bytesTransferred: 25, totalBytes: 100).fractionCompleted,
            0.25
        )
        XCTAssertEqual(
            CameraMediaTransferProgress(bytesTransferred: 125, totalBytes: 100).fractionCompleted,
            1
        )
    }

    func testInvalidCountsAreNormalized() {
        let progress = CameraMediaTransferProgress(bytesTransferred: -5, totalBytes: 0)

        XCTAssertEqual(progress.bytesTransferred, 0)
        XCTAssertNil(progress.totalBytes)
        XCTAssertNil(progress.fractionCompleted)
    }
}
