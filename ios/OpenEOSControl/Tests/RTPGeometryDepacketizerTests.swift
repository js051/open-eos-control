import Foundation
import OpenEOSCore
import XCTest
@testable import OpenEOSControl

/// Native authored test of local packet assembly; no socket, decoder or camera.
final class RTPGeometryDepacketizerTests: XCTestCase {
    func testPartialOldAccessUnitCannotBeRelabeledByNewCropFinalFragment() {
        let parser = IOSRTPGeometryDepacketizer(payloadType: 103), old = UUID(), current = UUID()
        XCTAssertNil(parser.accept(packet(20, [0x7c, 0x85, 10, 11]), revision: old, currentRevision: { old }))
        XCTAssertNil(parser.accept(packet(21, [0x7c, 0x45, 12, 13], marker: true), revision: current, currentRevision: { current }))
        XCTAssertNil(parser.accept(packet(22, [0x7c, 0x85, 20, 21]), revision: current, currentRevision: { current }))
        let fresh = parser.accept(packet(23, [0x7c, 0x45, 22, 23], marker: true), revision: current, currentRevision: { current })
        XCTAssertEqual(fresh?.nalUnits, [Data([0x65, 20, 21, 22, 23])])
    }

    func testReceiveAdmittedBeforeZoomIsDiscardedAndFreshReceiveRecovers() {
        let parser = IOSRTPGeometryDepacketizer(payloadType: 103), old = UUID(), current = UUID()
        XCTAssertNil(parser.accept(packet(1, [0x65, 1], marker: true), revision: old, currentRevision: { current }))
        let fresh = parser.accept(packet(2, [0x65, 2], marker: true), revision: current, currentRevision: { current })
        XCTAssertEqual(fresh?.nalUnits, [Data([0x65, 2])])
    }

    func testBoundaryCrossingDuringAssemblyCannotReturnCompletedOldUnit() {
        let parser = IOSRTPGeometryDepacketizer(payloadType: 103), old = UUID(), current = UUID()
        var reads = 0
        let unit = parser.accept(packet(1, [0x65, 1], marker: true), revision: old) {
            reads += 1
            return reads == 1 ? old : current
        }
        XCTAssertNil(unit)
        let fresh = parser.accept(packet(2, [0x65, 2], marker: true), revision: current, currentRevision: { current })
        XCTAssertEqual(fresh?.nalUnits, [Data([0x65, 2])])
    }

    private func packet(_ sequence: UInt16, _ payload: [UInt8], marker: Bool = false) -> Data {
        Data([0x80, (marker ? 0x80 : 0) | 103, UInt8(sequence >> 8), UInt8(sequence & 255),
              0, 0, 0x46, 0x50, 0, 0, 0, 1] + payload)
    }
}
