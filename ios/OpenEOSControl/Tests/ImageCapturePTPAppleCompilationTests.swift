import XCTest
import OpenEOSCore

/// SDK linkage only. Never constructs an adapter or native device, starts
/// discovery, requests permissions, or sends a camera command.
final class ImageCapturePTPAppleCompilationTests: XCTestCase {
    func testIOSSDKIncludesTheConcreteIsolatedPort() throws {
        #if canImport(ImageCaptureCore) && os(iOS)
        requirePortConformance(ImageCapturePTPApplePort.self)
        XCTAssertEqual(ImageCapturePTPApplePort.maximumRetainedDevices, 4)
        #else
        throw XCTSkip("This SDK does not expose ImageCaptureCore; native branch compilation remains unverified")
        #endif
    }

    private func requirePortConformance<Port: ImageCapturePTPPort>(_ type: Port.Type) {}
}
