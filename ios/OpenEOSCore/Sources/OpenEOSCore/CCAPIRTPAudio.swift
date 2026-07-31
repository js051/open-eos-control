import Foundation

public struct CCAPILatmRTPAccessUnit: Equatable, Sendable {
    public let audioMuxElement: Data
    public let rtpTimestamp: UInt32
    public let discontinuity: Bool

    public init(audioMuxElement: Data, rtpTimestamp: UInt32, discontinuity: Bool = false) {
        self.audioMuxElement = audioMuxElement
        self.rtpTimestamp = rtpTimestamp
        self.discontinuity = discontinuity
    }
}

public final class CCAPILatmRTPDepacketizer {
    private let payloadType: UInt8
    private var timestamp: UInt32?
    private var expectedSequence: UInt16?
    private var valid = true
    private var pendingDiscontinuity = false
    private var payload = Data()

    public init(payloadType: UInt8) {
        self.payloadType = payloadType
    }

    public func accept(_ datagram: Data) -> CCAPILatmRTPAccessUnit? {
        guard let packet = RTPPacket.parse(datagram), packet.payloadType == payloadType else { return nil }

        if timestamp != packet.timestamp {
            if timestamp != nil, !payload.isEmpty { pendingDiscontinuity = true }
            reset(nextTimestamp: packet.timestamp)
        } else if let expectedSequence, packet.sequenceNumber != expectedSequence {
            valid = false
            pendingDiscontinuity = true
        }
        expectedSequence = packet.sequenceNumber &+ 1
        if valid, packet.payload.count <= maximumLatmAudioMuxBytes - payload.count {
            payload.append(packet.payload)
        } else if valid {
            valid = false
            pendingDiscontinuity = true
            payload.removeAll(keepingCapacity: false)
        }
        guard packet.marker else { return nil }

        let completed: CCAPILatmRTPAccessUnit? = if valid, !payload.isEmpty, let timestamp {
            CCAPILatmRTPAccessUnit(
                audioMuxElement: payload,
                rtpTimestamp: timestamp,
                discontinuity: pendingDiscontinuity
            )
        } else {
            nil
        }
        if completed != nil { pendingDiscontinuity = false }
        reset(nextTimestamp: nil)
        return completed
    }

    public func resetAfterDiscontinuity() {
        pendingDiscontinuity = true
        reset(nextTimestamp: nil)
    }

    private func reset(nextTimestamp: UInt32?) {
        timestamp = nextTimestamp
        expectedSequence = nil
        valid = true
        payload.removeAll(keepingCapacity: true)
    }
}

public struct CCAPIAACStreamFormat: Equatable, Sendable {
    public let sampleRate: Int
    public let channels: Int
    public let framesPerPacket: Int
    public let audioSpecificConfig: Data
    public let codec: String

    public init(
        sampleRate: Int,
        channels: Int,
        framesPerPacket: Int,
        audioSpecificConfig: Data,
        codec: String
    ) {
        self.sampleRate = sampleRate
        self.channels = channels
        self.framesPerPacket = framesPerPacket
        self.audioSpecificConfig = audioSpecificConfig
        self.codec = codec
    }
}

public struct CCAPIAACAccessUnit: Equatable, Sendable {
    public let bytes: Data
    public let presentationTimeMicroseconds: Int64
    public let format: CCAPIAACStreamFormat
    public let discontinuity: Bool

    public init(
        bytes: Data,
        presentationTimeMicroseconds: Int64,
        format: CCAPIAACStreamFormat,
        discontinuity: Bool
    ) {
        self.bytes = bytes
        self.presentationTimeMicroseconds = presentationTimeMicroseconds
        self.format = format
        self.discontinuity = discontinuity
    }
}

public enum CCAPILatmError: Error, Equatable, LocalizedError, Sendable {
    case truncated
    case missingStreamMuxConfig
    case unsupportedAudioMuxVersion(Int)
    case unsupportedTopology(programs: Int, layers: Int, subframes: Int)
    case unsupportedAudioObjectType(Int)
    case invalidSamplingFrequency(Int)
    case unsupportedChannelConfiguration(Int)
    case unsupportedFrameLengthType(Int)
    case invalidPayloadLength(Int)
    case excessiveOtherData(Int)

    public var errorDescription: String? {
        switch self {
        case .truncated:
            "Canon LATM audioMuxElement is truncated."
        case .missingStreamMuxConfig:
            "Canon LATM audio did not provide an in-band StreamMuxConfig."
        case let .unsupportedAudioMuxVersion(version):
            "Canon LATM audioMuxVersion \(version) is unsupported."
        case let .unsupportedTopology(programs, layers, subframes):
            "Canon LATM topology is unsupported (programs=\(programs), layers=\(layers), subframes=\(subframes))."
        case let .unsupportedAudioObjectType(type):
            "Canon LATM AudioSpecificConfig object type \(type) is unsupported; expected AAC-LC."
        case let .invalidSamplingFrequency(frequency):
            "Canon LATM sampling frequency \(frequency) is invalid."
        case let .unsupportedChannelConfiguration(configuration):
            "Canon LATM channel configuration \(configuration) is unsupported; expected mono or stereo."
        case let .unsupportedFrameLengthType(type):
            "Canon LATM frameLengthType \(type) is unsupported; expected 0."
        case let .invalidPayloadLength(length):
            "Canon LATM AAC payload length \(length) is invalid."
        case let .excessiveOtherData(bits):
            "Canon LATM otherData length \(bits) bits exceeds the parser limit."
        }
    }
}

/// Parses the bounded AAC-LC subset of ISO/IEC 14496-3 LATM used by Canon's in-band RTP stream.
public final class CCAPILatmSampleExtractor {
    private var streamConfiguration: StreamMuxConfiguration?

    public init() {}

    public func reset() {
        streamConfiguration = nil
    }

    public func consume(
        _ accessUnit: CCAPILatmRTPAccessUnit,
        presentationTimeMicroseconds: Int64
    ) throws -> CCAPIAACAccessUnit {
        guard (1...maximumLatmAudioMuxBytes).contains(accessUnit.audioMuxElement.count) else {
            throw CCAPILatmError.invalidPayloadLength(accessUnit.audioMuxElement.count)
        }
        var bits = LatmBitReader(data: accessUnit.audioMuxElement)
        let useSameStreamMux = try bits.readBool()
        if !useSameStreamMux {
            streamConfiguration = try Self.parseStreamMuxConfiguration(&bits)
        }
        guard let streamConfiguration else { throw CCAPILatmError.missingStreamMuxConfig }

        var payloadLength = 0
        var byteLength: Int
        repeat {
            byteLength = Int(try bits.readBits(8))
            payloadLength += byteLength
            guard payloadLength <= maximumRawAACAccessUnitBytes else {
                throw CCAPILatmError.invalidPayloadLength(payloadLength)
            }
        } while byteLength == 255
        guard payloadLength > 0 else { throw CCAPILatmError.invalidPayloadLength(payloadLength) }
        let payload = try bits.readData(bitCount: payloadLength * 8)
        if streamConfiguration.otherDataLengthBits > 0 {
            try bits.skipBits(streamConfiguration.otherDataLengthBits)
        }
        return CCAPIAACAccessUnit(
            bytes: payload,
            presentationTimeMicroseconds: presentationTimeMicroseconds,
            format: streamConfiguration.format,
            discontinuity: accessUnit.discontinuity
        )
    }

    private static func parseStreamMuxConfiguration(
        _ bits: inout LatmBitReader
    ) throws -> StreamMuxConfiguration {
        let audioMuxVersion = Int(try bits.readBits(1))
        let audioMuxVersionA = audioMuxVersion == 1 ? Int(try bits.readBits(1)) : 0
        guard audioMuxVersion == 0, audioMuxVersionA == 0 else {
            throw CCAPILatmError.unsupportedAudioMuxVersion(audioMuxVersion * 2 + audioMuxVersionA)
        }
        guard try bits.readBool() else { throw CCAPILatmError.unsupportedTopology(programs: 0, layers: 0, subframes: 0) }
        let subframes = Int(try bits.readBits(6))
        let programs = Int(try bits.readBits(4))
        let layers = Int(try bits.readBits(3))
        guard subframes == 0, programs == 0, layers == 0 else {
            throw CCAPILatmError.unsupportedTopology(
                programs: programs,
                layers: layers,
                subframes: subframes
            )
        }

        let configStart = bits.position
        let parsedAudio = try parseAudioSpecificConfig(&bits)
        let configBits = bits.position - configStart
        let audioSpecificConfig = try bits.dataSlice(from: configStart, bitCount: configBits)

        let frameLengthType = Int(try bits.readBits(3))
        guard frameLengthType == 0 else { throw CCAPILatmError.unsupportedFrameLengthType(frameLengthType) }
        try bits.skipBits(8) // latmBufferFullness

        var otherDataLengthBits = 0
        if try bits.readBool() {
            var escaped: Bool
            repeat {
                escaped = try bits.readBool()
                let next = Int(try bits.readBits(8))
                guard otherDataLengthBits <= maximumLatmOtherDataBits >> 8 else {
                    throw CCAPILatmError.excessiveOtherData(otherDataLengthBits)
                }
                otherDataLengthBits = (otherDataLengthBits << 8) + next
            } while escaped
            guard otherDataLengthBits <= maximumLatmOtherDataBits else {
                throw CCAPILatmError.excessiveOtherData(otherDataLengthBits)
            }
        }
        if try bits.readBool() { try bits.skipBits(8) } // crcCheckSum

        return StreamMuxConfiguration(
            format: CCAPIAACStreamFormat(
                sampleRate: parsedAudio.sampleRate,
                channels: parsedAudio.channels,
                framesPerPacket: parsedAudio.framesPerPacket,
                audioSpecificConfig: audioSpecificConfig,
                codec: "mp4a.40.2"
            ),
            otherDataLengthBits: otherDataLengthBits
        )
    }

    private static func parseAudioSpecificConfig(
        _ bits: inout LatmBitReader
    ) throws -> ParsedAudioSpecificConfig {
        let audioObjectType = try readAudioObjectType(&bits)
        guard audioObjectType == aacLowComplexityObjectType else {
            throw CCAPILatmError.unsupportedAudioObjectType(audioObjectType)
        }
        let sampleRate = try readSamplingFrequency(&bits)
        let channelConfiguration = Int(try bits.readBits(4))
        guard channelConfiguration == 1 || channelConfiguration == 2 else {
            throw CCAPILatmError.unsupportedChannelConfiguration(channelConfiguration)
        }

        let frameLengthFlag = try bits.readBool()
        if try bits.readBool() { try bits.skipBits(14) } // dependsOnCoreCoder/coreCoderDelay
        _ = try bits.readBool() // extensionFlag; AAC-LC has no extension payload here.
        return ParsedAudioSpecificConfig(
            sampleRate: sampleRate,
            channels: channelConfiguration,
            framesPerPacket: frameLengthFlag ? 960 : 1_024
        )
    }

    private static func readAudioObjectType(_ bits: inout LatmBitReader) throws -> Int {
        let value = Int(try bits.readBits(5))
        return value == 31 ? 32 + Int(try bits.readBits(6)) : value
    }

    private static func readSamplingFrequency(_ bits: inout LatmBitReader) throws -> Int {
        let index = Int(try bits.readBits(4))
        if index == 15 {
            let explicit = Int(try bits.readBits(24))
            guard explicit > 0 else { throw CCAPILatmError.invalidSamplingFrequency(explicit) }
            return explicit
        }
        guard samplingFrequencyTable.indices.contains(index) else {
            throw CCAPILatmError.invalidSamplingFrequency(index)
        }
        return samplingFrequencyTable[index]
    }
}

private struct StreamMuxConfiguration {
    let format: CCAPIAACStreamFormat
    let otherDataLengthBits: Int
}

private struct ParsedAudioSpecificConfig {
    let sampleRate: Int
    let channels: Int
    let framesPerPacket: Int
}

private struct LatmBitReader {
    private let bytes: [UInt8]
    private(set) var position = 0

    init(data: Data) {
        bytes = [UInt8](data)
    }

    mutating func readBool() throws -> Bool {
        try readBits(1) == 1
    }

    mutating func readBits(_ count: Int) throws -> UInt64 {
        guard count >= 0, count <= 32, position + count <= bytes.count * 8 else {
            throw CCAPILatmError.truncated
        }
        var result: UInt64 = 0
        for _ in 0..<count {
            let byte = bytes[position >> 3]
            let shift = 7 - (position & 7)
            result = (result << 1) | UInt64((byte >> shift) & 1)
            position += 1
        }
        return result
    }

    mutating func skipBits(_ count: Int) throws {
        guard count >= 0, position + count <= bytes.count * 8 else { throw CCAPILatmError.truncated }
        position += count
    }

    mutating func readData(bitCount: Int) throws -> Data {
        let data = try dataSlice(from: position, bitCount: bitCount)
        position += bitCount
        return data
    }

    func dataSlice(from start: Int, bitCount: Int) throws -> Data {
        guard start >= 0, bitCount >= 0, start + bitCount <= bytes.count * 8 else {
            throw CCAPILatmError.truncated
        }
        var output = [UInt8](repeating: 0, count: (bitCount + 7) / 8)
        for outputBit in 0..<bitCount {
            let sourcePosition = start + outputBit
            let source = (bytes[sourcePosition >> 3] >> (7 - (sourcePosition & 7))) & 1
            output[outputBit >> 3] |= source << (7 - (outputBit & 7))
        }
        return Data(output)
    }
}

public let maximumLatmAudioMuxBytes = 0x1FFF
private let maximumRawAACAccessUnitBytes = 512 * 1024
private let maximumLatmOtherDataBits = 8 * 1024 * 1024
private let aacLowComplexityObjectType = 2
private let samplingFrequencyTable = [
    96_000, 88_200, 64_000, 48_000, 44_100, 32_000, 24_000,
    22_050, 16_000, 12_000, 11_025, 8_000, 7_350,
]
