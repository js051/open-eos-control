import Foundation
import XCTest
@testable import OpenEOSCore

final class CCAPIRTPTests: XCTestCase {
    func testParsesCanonH264AndLATMSessionDescription() throws {
        let description = try CCAPIRTPSessionDescriptionParser.parse(Self.canonSDP)

        XCTAssertEqual(description.video.port, 12_000)
        XCTAssertEqual(description.video.payloadType, 103)
        XCTAssertEqual(description.video.codec, "H264")
        XCTAssertEqual(description.video.clockRate, 90_000)
        XCTAssertEqual(description.audio?.port, 12_010)
        XCTAssertEqual(description.audio?.codec, "MP4A-LATM")
        XCTAssertEqual(description.audio?.clockRate, 48_000)
    }

    func testRejectsSessionWithoutH264Video() {
        XCTAssertThrowsError(
            try CCAPIRTPSessionDescriptionParser.parse(
                Self.canonSDP.replacingOccurrences(of: "H264/90000", with: "JPEG/90000")
            )
        ) { error in
            XCTAssertEqual(error as? CCAPIRTPError, .missingH264Video)
        }
    }

    func testParsesHeaderWithCSRCHeaderExtensionAndPadding() throws {
        let depacketizer = CCAPIH264RTPDepacketizer(payloadType: 103)
        let unit = depacketizer.accept(
            rtpPacket(
                sequence: 0x1234,
                timestamp: 0xF123_4567,
                payload: Data([0x65, 1, 2]),
                marker: true,
                csrc: true,
                extensionHeader: true,
                padding: 4
            )
        )

        XCTAssertEqual(unit?.rtpTimestamp, 0xF123_4567)
        XCTAssertEqual(unit?.nalUnits, [Data([0x65, 1, 2])])
    }

    func testEmitsSingleNALAccessUnit() {
        let depacketizer = CCAPIH264RTPDepacketizer(payloadType: 103)

        let unit = depacketizer.accept(
            rtpPacket(sequence: 1, timestamp: 9_000, payload: Data([0x65, 1, 2, 3]), marker: true)
        )

        XCTAssertEqual(unit?.nalUnits, [Data([0x65, 1, 2, 3])])
        XCTAssertEqual(unit?.keyFrame, true)
    }

    func testReassemblesFragmentedFUAAccessUnit() {
        let depacketizer = CCAPIH264RTPDepacketizer(payloadType: 103)
        XCTAssertNil(
            depacketizer.accept(
                rtpPacket(sequence: 20, timestamp: 18_000, payload: Data([0x7C, 0x85, 10, 11]))
            )
        )

        let unit = depacketizer.accept(
            rtpPacket(sequence: 21, timestamp: 18_000, payload: Data([0x7C, 0x45, 12, 13]), marker: true)
        )

        XCTAssertEqual(unit?.nalUnits, [Data([0x65, 10, 11, 12, 13])])
        XCTAssertEqual(unit?.keyFrame, true)
    }

    func testExtractsParameterSetsFromSTAPA() {
        let depacketizer = CCAPIH264RTPDepacketizer(payloadType: 103)
        let sps = Data([0x67, 0x42, 0x00, 0x1F])
        let pps = Data([0x68, 0x01, 0x02])
        let idr = Data([0x65, 0x09])
        let payload = Data([0x78]) + sizedNAL(sps) + sizedNAL(pps) + sizedNAL(idr)

        let unit = depacketizer.accept(
            rtpPacket(sequence: 30, timestamp: 27_000, payload: payload, marker: true)
        )

        XCTAssertEqual(unit?.sequenceParameterSet, sps)
        XCTAssertEqual(unit?.pictureParameterSet, pps)
        XCTAssertEqual(unit?.keyFrame, true)
        XCTAssertEqual(unit?.nalUnits, [sps, pps, idr])
    }

    func testDiscardsFragmentWhenSequenceIsMissing() {
        let depacketizer = CCAPIH264RTPDepacketizer(payloadType: 103)
        _ = depacketizer.accept(
            rtpPacket(sequence: 40, timestamp: 36_000, payload: Data([0x7C, 0x85, 1]))
        )

        let unit = depacketizer.accept(
            rtpPacket(sequence: 42, timestamp: 36_000, payload: Data([0x7C, 0x45, 3]), marker: true)
        )

        XCTAssertNil(unit)
    }

    private func sizedNAL(_ nal: Data) -> Data {
        Data([UInt8(nal.count >> 8), UInt8(nal.count & 0xFF)]) + nal
    }

    private func rtpPacket(
        sequence: UInt16,
        timestamp: UInt32,
        payload: Data,
        marker: Bool = false,
        csrc: Bool = false,
        extensionHeader: Bool = false,
        padding: Int = 0
    ) -> Data {
        let csrcBytes = csrc ? Data([0, 0, 0, 1]) : Data()
        let extensionBytes = extensionHeader ? Data([0x10, 0x00, 0x00, 0x01, 9, 8, 7, 6]) : Data()
        var paddingBytes = Data(repeating: 0, count: padding)
        if padding > 0 { paddingBytes[paddingBytes.count - 1] = UInt8(padding) }
        let first = UInt8(0x80 | (padding > 0 ? 0x20 : 0) | (extensionHeader ? 0x10 : 0) | (csrc ? 1 : 0))
        let second = UInt8((marker ? 0x80 : 0) | 103)
        let header = Data([
            first,
            second,
            UInt8(sequence >> 8),
            UInt8(sequence & 0xFF),
            UInt8(timestamp >> 24),
            UInt8((timestamp >> 16) & 0xFF),
            UInt8((timestamp >> 8) & 0xFF),
            UInt8(timestamp & 0xFF),
            0, 0, 0, 1,
        ])
        return header + csrcBytes + extensionBytes + payload + paddingBytes
    }

    private static let canonSDP = """
    v=0
    o=- 0 0 IN IP4 192.168.11.4
    s=RTP Session
    c=IN IP4 0.0.0.0
    t=0 0
    a=control *
    m=video 12000 RTP/AVP 103
    a=rtpmap:103 H264/90000
    m=audio 12010 RTP/AVP 106
    a=rtpmap:106 MP4A-LATM/48000
    """
}
