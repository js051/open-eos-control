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
        XCTAssertEqual(description.audio?.formatParameter("cpresent"), "1")
        XCTAssertEqual(description.audio.latmAudioSupport.supported, true)
    }

    func testRejectsOutOfBandLatmWithoutGuessingConfiguration() throws {
        let description = try CCAPIRTPSessionDescriptionParser.parse(
            Self.canonSDP.replacingOccurrences(of: "cpresent=1", with: "cpresent=0")
        )

        XCTAssertEqual(description.audio.latmAudioSupport.supported, false)
        XCTAssertTrue(description.audio.latmAudioSupport.reason.contains("cpresent=0"))
    }

    func testRejectsInvalidAudioPayloadTypeWithoutTrapping() {
        XCTAssertThrowsError(
            try CCAPIRTPSessionDescriptionParser.parse(
                Self.canonSDP
                    .replacingOccurrences(of: "106 MP4A-LATM", with: "999 MP4A-LATM")
                    .replacingOccurrences(of: "audio 12010 RTP/AVP 106", with: "audio 12010 RTP/AVP 999")
                    .replacingOccurrences(of: "fmtp:106", with: "fmtp:999")
            )
        ) { error in
            XCTAssertEqual(error as? CCAPIRTPError, .invalidAudioPayloadType(999))
        }
    }

    func testRejectsSharedAudioAndVideoPortForSeparateListeners() throws {
        let description = try CCAPIRTPSessionDescriptionParser.parse(
            Self.canonSDP.replacingOccurrences(of: "audio 12010", with: "audio 12000")
        )

        XCTAssertFalse(description.latmAudioSupport.supported)
        XCTAssertTrue(description.latmAudioSupport.reason.contains("share UDP port"))
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

    func testReassemblesFragmentedLatmAndMarksRecoveryAfterPacketLoss() {
        let depacketizer = CCAPILatmRTPDepacketizer(payloadType: 106)
        XCTAssertNil(
            depacketizer.accept(
                rtpPacket(
                    sequence: 10,
                    timestamp: 900,
                    payload: Data("audio-".utf8),
                    payloadType: 106
                )
            )
        )
        let complete = depacketizer.accept(
            rtpPacket(
                sequence: 11,
                timestamp: 900,
                payload: Data("mux".utf8),
                marker: true,
                payloadType: 106
            )
        )
        XCTAssertEqual(complete?.audioMuxElement, Data("audio-mux".utf8))
        XCTAssertEqual(complete?.discontinuity, false)

        _ = depacketizer.accept(
            rtpPacket(sequence: 20, timestamp: 901, payload: Data([1]), payloadType: 106)
        )
        XCTAssertNil(
            depacketizer.accept(
                rtpPacket(
                    sequence: 22,
                    timestamp: 901,
                    payload: Data([2]),
                    marker: true,
                    payloadType: 106
                )
            )
        )
        let recovered = depacketizer.accept(
            rtpPacket(
                sequence: 23,
                timestamp: 902,
                payload: Data([3]),
                marker: true,
                payloadType: 106
            )
        )
        XCTAssertEqual(recovered?.discontinuity, true)
    }

    func testDropsOversizedLatmAndRecoversAtTheNextAccessUnit() {
        let depacketizer = CCAPILatmRTPDepacketizer(payloadType: 106)
        XCTAssertNil(
            depacketizer.accept(
                rtpPacket(
                    sequence: 1,
                    timestamp: 100,
                    payload: Data(repeating: 0xA5, count: maximumLatmAudioMuxBytes + 1),
                    payloadType: 106
                )
            )
        )
        XCTAssertNil(
            depacketizer.accept(
                rtpPacket(
                    sequence: 2,
                    timestamp: 100,
                    payload: Data(repeating: 0x5A, count: maximumLatmAudioMuxBytes),
                    marker: true,
                    payloadType: 106
                )
            )
        )

        let recovered = depacketizer.accept(
            rtpPacket(
                sequence: 3,
                timestamp: 101,
                payload: Data([0x01, 0x02]),
                marker: true,
                payloadType: 106
            )
        )
        XCTAssertEqual(recovered?.audioMuxElement, Data([0x01, 0x02]))
        XCTAssertEqual(recovered?.discontinuity, true)
    }

    func testExtractsRawAACAndInBandFormatFromRealLatmFixture() throws {
        let extractor = CCAPILatmSampleExtractor()
        let first = try extractor.consume(
            CCAPILatmRTPAccessUnit(
                audioMuxElement: Data(base64Encoded: Self.firstLatmMuxBase64)!,
                rtpTimestamp: 0
            ),
            presentationTimeMicroseconds: 12_345
        )

        XCTAssertFalse(first.bytes.isEmpty)
        XCTAssertEqual(first.presentationTimeMicroseconds, 12_345)
        XCTAssertEqual(first.format.sampleRate, 48_000)
        XCTAssertEqual(first.format.channels, 2)
        XCTAssertEqual(first.format.framesPerPacket, 1_024)
        XCTAssertEqual(first.format.audioSpecificConfig, Data([0x11, 0x90]))
        XCTAssertEqual(first.format.codec, "mp4a.40.2")
        XCTAssertEqual(first.discontinuity, false)

        let repeated = try extractor.consume(
            CCAPILatmRTPAccessUnit(
                audioMuxElement: Data(base64Encoded: Self.repeatedLatmMuxBase64)!,
                rtpTimestamp: 1_024
            ),
            presentationTimeMicroseconds: 21_333
        )
        XCTAssertFalse(repeated.bytes.isEmpty)
        XCTAssertEqual(repeated.format, first.format)
    }

    func testLatmExtractorSkipsEscapedOtherDataAfterTheAACPayload() throws {
        let extractor = CCAPILatmSampleExtractor()
        let payload = Data([0xA5, 0x5A, 0xC3])
        let otherData = Data(repeating: 0xD3, count: 32)

        let sample = try extractor.consume(
            CCAPILatmRTPAccessUnit(
                audioMuxElement: latmMux(payload: payload, otherData: otherData),
                rtpTimestamp: 0
            ),
            presentationTimeMicroseconds: 42
        )

        XCTAssertEqual(sample.bytes, payload)
        XCTAssertEqual(sample.presentationTimeMicroseconds, 42)
        XCTAssertEqual(sample.format.sampleRate, 48_000)
        XCTAssertEqual(sample.format.channels, 2)
        XCTAssertEqual(sample.format.audioSpecificConfig, Data([0x11, 0x90]))
    }

    func testLatmExtractorRequiresFreshInBandConfigAfterReset() throws {
        let extractor = CCAPILatmSampleExtractor()
        _ = try extractor.consume(
            CCAPILatmRTPAccessUnit(
                audioMuxElement: Data(base64Encoded: Self.firstLatmMuxBase64)!,
                rtpTimestamp: 0
            ),
            presentationTimeMicroseconds: 0
        )
        extractor.reset()

        XCTAssertThrowsError(
            try extractor.consume(
                CCAPILatmRTPAccessUnit(
                    audioMuxElement: Data(base64Encoded: Self.repeatedLatmMuxBase64)!,
                    rtpTimestamp: 1_024,
                    discontinuity: true
                ),
                presentationTimeMicroseconds: 21_333
            )
        ) { error in
            XCTAssertEqual(error as? CCAPILatmError, .missingStreamMuxConfig)
        }
    }

    private func sizedNAL(_ nal: Data) -> Data {
        Data([UInt8(nal.count >> 8), UInt8(nal.count & 0xFF)]) + nal
    }

    private func latmMux(payload: Data, otherData: Data) -> Data {
        precondition(payload.count < 255)
        precondition(otherData.count * 8 <= 0xFFFF)
        var bits = LatmTestBitWriter()
        bits.append(0, count: 1) // useSameStreamMux
        bits.append(0, count: 1) // audioMuxVersion
        bits.append(1, count: 1) // allStreamsSameTimeFraming
        bits.append(0, count: 6) // numSubFrames
        bits.append(0, count: 4) // numProgram
        bits.append(0, count: 3) // numLayer
        bits.append(2, count: 5) // AAC-LC
        bits.append(3, count: 4) // 48 kHz
        bits.append(2, count: 4) // stereo
        bits.append(0, count: 1) // frameLengthFlag
        bits.append(0, count: 1) // dependsOnCoreCoder
        bits.append(0, count: 1) // extensionFlag
        bits.append(0, count: 3) // frameLengthType
        bits.append(0xFF, count: 8) // latmBufferFullness
        bits.append(1, count: 1) // otherDataPresent
        let otherDataBits = otherData.count * 8
        if otherDataBits > 0xFF {
            bits.append(1, count: 1) // otherDataLenEsc
            bits.append(UInt64(otherDataBits >> 8), count: 8)
        }
        bits.append(0, count: 1) // final otherDataLenEsc
        bits.append(UInt64(otherDataBits & 0xFF), count: 8)
        bits.append(0, count: 1) // crcCheckPresent
        bits.append(UInt64(payload.count), count: 8)
        bits.append(payload)
        bits.append(otherData)
        return bits.data
    }

    private func rtpPacket(
        sequence: UInt16,
        timestamp: UInt32,
        payload: Data,
        marker: Bool = false,
        csrc: Bool = false,
        extensionHeader: Bool = false,
        padding: Int = 0,
        payloadType: UInt8 = 103
    ) -> Data {
        let csrcBytes = csrc ? Data([0, 0, 0, 1]) : Data()
        let extensionBytes = extensionHeader ? Data([0x10, 0x00, 0x00, 0x01, 9, 8, 7, 6]) : Data()
        var paddingBytes = Data(repeating: 0, count: padding)
        if padding > 0 { paddingBytes[paddingBytes.count - 1] = UInt8(padding) }
        let first = UInt8(0x80 | (padding > 0 ? 0x20 : 0) | (extensionHeader ? 0x10 : 0) | (csrc ? 1 : 0))
        let second = UInt8((marker ? 0x80 : 0) | payloadType)
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
    a=fmtp:106 cpresent=1
    """
    private static let firstLatmMuxBase64 = "IAARkB/gvvAQAmMLsxmxkXGRwXGJgZACEQBGCMHA"
    private static let repeatedLatmMuxBase64 = "gxCIAjBGDgA="
}

private struct LatmTestBitWriter {
    private var storage: [UInt8] = []

    mutating func append(_ value: UInt64, count: Int) {
        precondition((0...64).contains(count))
        for shift in stride(from: count - 1, through: 0, by: -1) {
            storage.append(UInt8((value >> UInt64(shift)) & 1))
        }
    }

    mutating func append(_ data: Data) {
        for byte in data { append(UInt64(byte), count: 8) }
    }

    var data: Data {
        var bytes = [UInt8](repeating: 0, count: (storage.count + 7) / 8)
        for (index, bit) in storage.enumerated() {
            bytes[index >> 3] |= bit << UInt8(7 - (index & 7))
        }
        return Data(bytes)
    }
}
