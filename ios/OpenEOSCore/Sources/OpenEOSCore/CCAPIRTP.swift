import Foundation

public struct CCAPIRTPMediaDescription: Equatable, Sendable {
    public let kind: String
    public let port: UInt16
    public let payloadType: UInt8
    public let codec: String
    public let clockRate: Int
    public let channels: Int?
    public let formatParameters: [String: String]

    public init(
        kind: String,
        port: UInt16,
        payloadType: UInt8,
        codec: String,
        clockRate: Int,
        channels: Int? = nil,
        formatParameters: [String: String] = [:]
    ) {
        self.kind = kind
        self.port = port
        self.payloadType = payloadType
        self.codec = codec
        self.clockRate = clockRate
        self.channels = channels
        self.formatParameters = formatParameters
    }

    public func formatParameter(_ name: String) -> String? {
        formatParameters[name.lowercased()]
    }
}

public struct CCAPILatmAudioSupport: Equatable, Sendable {
    public let supported: Bool
    public let reason: String

    public init(supported: Bool, reason: String) {
        self.supported = supported
        self.reason = reason
    }
}

public extension CCAPIRTPSessionDescription {
    var latmAudioSupport: CCAPILatmAudioSupport {
        guard audio?.port != video.port else {
            return CCAPILatmAudioSupport(
                supported: false,
                reason: "Canon RTP audio and video share UDP port \(video.port); separate receivers are required."
            )
        }
        return audio.latmAudioSupport
    }
}

public extension Optional where Wrapped == CCAPIRTPMediaDescription {
    var latmAudioSupport: CCAPILatmAudioSupport {
        guard let media = self else {
            return CCAPILatmAudioSupport(
                supported: false,
                reason: "Canon RTP SDP does not advertise an audio stream."
            )
        }
        guard media.codec.caseInsensitiveCompare("MP4A-LATM") == .orderedSame else {
            return CCAPILatmAudioSupport(
                supported: false,
                reason: "Canon RTP audio codec \(media.codec) is unsupported; expected MP4A-LATM."
            )
        }
        guard media.clockRate == canonRTPAudioClockRate else {
            return CCAPILatmAudioSupport(
                supported: false,
                reason: "Canon RTP MP4A-LATM clock rate \(media.clockRate) is unsupported; expected \(canonRTPAudioClockRate)."
            )
        }
        switch media.formatParameter("cpresent") {
        case nil, "", "1":
            return CCAPILatmAudioSupport(
                supported: true,
                reason: "Canon RTP MP4A-LATM audio uses in-band StreamMuxConfig."
            )
        case "0":
            return CCAPILatmAudioSupport(
                supported: false,
                reason: "Out-of-band MP4A-LATM configuration (cpresent=0) is not supported."
            )
        case let value?:
            return CCAPILatmAudioSupport(
                supported: false,
                reason: "Canon RTP MP4A-LATM cpresent=\(value) is invalid."
            )
        }
    }
}

public struct CCAPIRTPSessionDescription: Equatable, Sendable {
    public let rawSDP: String
    public let video: CCAPIRTPMediaDescription
    public let audio: CCAPIRTPMediaDescription?

    public init(
        rawSDP: String,
        video: CCAPIRTPMediaDescription,
        audio: CCAPIRTPMediaDescription? = nil
    ) {
        self.rawSDP = rawSDP
        self.video = video
        self.audio = audio
    }
}

public enum CCAPIRTPError: Error, Equatable, LocalizedError, Sendable {
    case emptySessionDescription
    case missingH264Video
    case invalidVideoPort(Int)
    case invalidPayloadType(Int)
    case unsupportedH264ClockRate(Int)
    case invalidAudioPort(Int)
    case invalidAudioPayloadType(Int)

    public var errorDescription: String? {
        switch self {
        case .emptySessionDescription:
            "Canon RTP session description is empty."
        case .missingH264Video:
            "Canon RTP SDP does not advertise an H.264 video stream."
        case let .invalidVideoPort(port):
            "Canon RTP video port \(port) is invalid."
        case let .invalidPayloadType(payloadType):
            "Canon RTP video payload type \(payloadType) is invalid."
        case let .unsupportedH264ClockRate(clockRate):
            "Canon RTP H.264 clock rate \(clockRate) is unsupported; expected 90000."
        case let .invalidAudioPort(port):
            "Canon RTP audio port \(port) is invalid."
        case let .invalidAudioPayloadType(payloadType):
            "Canon RTP audio payload type \(payloadType) is invalid."
        }
    }
}

public enum CCAPIRTPSessionDescriptionParser {
    public static func parse(_ sdp: String) throws -> CCAPIRTPSessionDescription {
        guard !sdp.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw CCAPIRTPError.emptySessionDescription
        }

        let lines = sdp
            .components(separatedBy: .newlines)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
        var mappings: [Int: RTPMapping] = [:]
        for line in lines where line.lowercased().hasPrefix("a=rtpmap:") {
            guard let separator = line.firstIndex(of: " ") else { continue }
            let payloadText = line[line.index(line.startIndex, offsetBy: 9)..<separator]
            let fields = line[line.index(after: separator)...].split(separator: "/")
            guard fields.count >= 2,
                  let payloadType = Int(payloadText),
                  let clockRate = Int(fields[1]) else { continue }
            mappings[payloadType] = RTPMapping(
                codec: String(fields[0]),
                clockRate: clockRate,
                channels: fields.count > 2 ? Int(fields[2]) : nil
            )
        }
        var formatParameters: [Int: [String: String]] = [:]
        for line in lines where line.lowercased().hasPrefix("a=fmtp:") {
            guard let separator = line.firstIndex(of: " ") else { continue }
            let payloadText = line[line.index(line.startIndex, offsetBy: 7)..<separator]
            guard let payloadType = Int(payloadText) else { continue }
            let parameters = line[line.index(after: separator)...]
                .split(separator: ";")
                .reduce(into: [String: String]()) { result, entry in
                    let parts = entry.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
                    let key = parts.first?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() ?? ""
                    guard !key.isEmpty else { return }
                    result[key] = parts.count > 1
                        ? parts[1].trimmingCharacters(in: .whitespacesAndNewlines)
                        : ""
                }
            formatParameters[payloadType] = parameters
        }

        var media: [(kind: String, port: Int, payloadType: Int, mapping: RTPMapping)] = []
        for line in lines where line.lowercased().hasPrefix("m=") {
            let fields = line.dropFirst(2).split(whereSeparator: { $0.isWhitespace })
            guard fields.count >= 4,
                  String(fields[2]).caseInsensitiveCompare("RTP/AVP") == .orderedSame,
                  let port = Int(fields[1].split(separator: "/").first ?? "") else { continue }
            let kind = fields[0].lowercased()
            let candidates = fields.dropFirst(3).compactMap { Int($0) }
            let payloadType: Int?
            if kind == "video" {
                payloadType = candidates.first {
                    mappings[$0]?.codec.caseInsensitiveCompare("H264") == .orderedSame
                }
            } else if kind == "audio" {
                payloadType = candidates.first {
                    mappings[$0]?.codec.caseInsensitiveCompare("MP4A-LATM") == .orderedSame
                } ?? candidates.first { mappings[$0] != nil }
            } else {
                payloadType = candidates.first { mappings[$0] != nil }
            }
            guard let payloadType, let mapping = mappings[payloadType] else { continue }
            media.append((String(kind), port, payloadType, mapping))
        }

        guard let video = media.first(where: {
            $0.kind == "video" && $0.mapping.codec.caseInsensitiveCompare("H264") == .orderedSame
        }) else {
            throw CCAPIRTPError.missingH264Video
        }
        guard (1...65_535).contains(video.port) else { throw CCAPIRTPError.invalidVideoPort(video.port) }
        guard (0...127).contains(video.payloadType) else {
            throw CCAPIRTPError.invalidPayloadType(video.payloadType)
        }
        guard video.mapping.clockRate == h264ClockRate else {
            throw CCAPIRTPError.unsupportedH264ClockRate(video.mapping.clockRate)
        }

        let audioDescription: CCAPIRTPMediaDescription?
        if let audio = media.first(where: { $0.kind == "audio" }) {
            guard (1...65_535).contains(audio.port) else { throw CCAPIRTPError.invalidAudioPort(audio.port) }
            guard (0...127).contains(audio.payloadType) else {
                throw CCAPIRTPError.invalidAudioPayloadType(audio.payloadType)
            }
            audioDescription = CCAPIRTPMediaDescription(
                kind: audio.kind,
                port: UInt16(audio.port),
                payloadType: UInt8(audio.payloadType),
                codec: audio.mapping.codec,
                clockRate: audio.mapping.clockRate,
                channels: audio.mapping.channels,
                formatParameters: formatParameters[audio.payloadType] ?? [:]
            )
        } else {
            audioDescription = nil
        }
        return CCAPIRTPSessionDescription(
            rawSDP: sdp,
            video: CCAPIRTPMediaDescription(
                kind: video.kind,
                port: UInt16(video.port),
                payloadType: UInt8(video.payloadType),
                codec: video.mapping.codec,
                clockRate: video.mapping.clockRate,
                channels: video.mapping.channels,
                formatParameters: formatParameters[video.payloadType] ?? [:]
            ),
            audio: audioDescription
        )
    }

    private struct RTPMapping {
        let codec: String
        let clockRate: Int
        let channels: Int?
    }
}

public struct CCAPIH264AccessUnit: Equatable, Sendable {
    public let nalUnits: [Data]
    public let rtpTimestamp: UInt32
    public let keyFrame: Bool
    public let sequenceParameterSet: Data?
    public let pictureParameterSet: Data?

    public var encodedByteCount: Int {
        nalUnits.reduce(0) { $0 + $1.count }
    }

    public init(
        nalUnits: [Data],
        rtpTimestamp: UInt32,
        keyFrame: Bool,
        sequenceParameterSet: Data?,
        pictureParameterSet: Data?
    ) {
        self.nalUnits = nalUnits
        self.rtpTimestamp = rtpTimestamp
        self.keyFrame = keyFrame
        self.sequenceParameterSet = sequenceParameterSet
        self.pictureParameterSet = pictureParameterSet
    }
}

public final class CCAPIH264RTPDepacketizer {
    private let payloadType: UInt8
    private var timestamp: UInt32?
    private var expectedSequence: UInt16?
    private var validAccessUnit = true
    private var fragmentedNAL: Data?
    private var keyFrame = false
    private var sequenceParameterSet: Data?
    private var pictureParameterSet: Data?
    private var nalUnits: [Data] = []
    private var accessUnitBytes = 0

    public init(payloadType: UInt8) {
        self.payloadType = payloadType
    }

    public func accept(_ datagram: Data) -> CCAPIH264AccessUnit? {
        guard let packet = RTPPacket.parse(datagram), packet.payloadType == payloadType else { return nil }

        if timestamp != packet.timestamp {
            reset(nextTimestamp: packet.timestamp)
        } else if let expectedSequence, packet.sequenceNumber != expectedSequence {
            validAccessUnit = false
            fragmentedNAL = nil
        }
        expectedSequence = packet.sequenceNumber &+ 1

        guard let first = packet.payload.first else { return nil }
        switch first & nalTypeMask {
        case singleNALMinimum...singleNALMaximum:
            appendNAL(packet.payload)
        case stapAType:
            appendSTAPA(packet.payload)
        case fuaType:
            appendFUA(packet.payload)
        default:
            validAccessUnit = false
        }

        guard packet.marker else { return nil }
        let completed: CCAPIH264AccessUnit? = if validAccessUnit,
                                                 fragmentedNAL == nil,
                                                 !nalUnits.isEmpty,
                                                 let timestamp {
            CCAPIH264AccessUnit(
                nalUnits: nalUnits,
                rtpTimestamp: timestamp,
                keyFrame: keyFrame,
                sequenceParameterSet: sequenceParameterSet,
                pictureParameterSet: pictureParameterSet
            )
        } else {
            nil
        }
        reset(nextTimestamp: nil)
        return completed
    }

    private func appendSTAPA(_ payload: Data) {
        let bytes = [UInt8](payload)
        var offset = 1
        while offset + 2 <= bytes.count {
            let size = (Int(bytes[offset]) << 8) | Int(bytes[offset + 1])
            offset += 2
            guard size > 0, offset + size <= bytes.count else {
                validAccessUnit = false
                return
            }
            appendNAL(Data(bytes[offset..<(offset + size)]))
            offset += size
        }
        if offset != bytes.count { validAccessUnit = false }
    }

    private func appendFUA(_ payload: Data) {
        let bytes = [UInt8](payload)
        guard bytes.count >= 3 else {
            validAccessUnit = false
            return
        }
        let fuIndicator = bytes[0]
        let fuHeader = bytes[1]
        let start = fuHeader & fuStartMask != 0
        let end = fuHeader & fuEndMask != 0
        let type = fuHeader & nalTypeMask
        guard !(start && end), fuHeader & fuReservedMask == 0 else {
            validAccessUnit = false
            fragmentedNAL = nil
            return
        }

        if start {
            guard fragmentedNAL == nil else {
                validAccessUnit = false
                return
            }
            let reconstructedHeader = (fuIndicator & nalPrefixMask) | type
            fragmentedNAL = Data([reconstructedHeader]) + Data(bytes.dropFirst(2))
            observeNALType(type, nal: nil)
        } else {
            guard fragmentedNAL != nil else {
                validAccessUnit = false
                return
            }
            fragmentedNAL?.append(contentsOf: bytes.dropFirst(2))
        }
        if let fragmentedNAL, fragmentedNAL.count > maximumAccessUnitBytes {
            validAccessUnit = false
        }
        if end, let fragmentedNAL {
            self.fragmentedNAL = nil
            appendNAL(fragmentedNAL)
        }
    }

    private func appendNAL(_ nal: Data) {
        guard let first = nal.first, !nal.isEmpty else {
            validAccessUnit = false
            return
        }
        nalUnits.append(nal)
        accessUnitBytes += nal.count
        observeNALType(first & nalTypeMask, nal: nal)
        if accessUnitBytes > maximumAccessUnitBytes { validAccessUnit = false }
    }

    private func observeNALType(_ type: UInt8, nal: Data?) {
        switch type {
        case idrType: keyFrame = true
        case spsType: sequenceParameterSet = nal
        case ppsType: pictureParameterSet = nal
        default: break
        }
    }

    private func reset(nextTimestamp: UInt32?) {
        timestamp = nextTimestamp
        validAccessUnit = true
        fragmentedNAL = nil
        keyFrame = false
        sequenceParameterSet = nil
        pictureParameterSet = nil
        nalUnits = []
        accessUnitBytes = 0
    }
}

public protocol CCAPIRTPSession: Sendable {
    var sourceURL: URL { get }
    func start() async throws
    func setTargetFPS(_ fps: Int) async
    func close() async
}

public protocol CCAPIRTPSessionFactory: Sendable {
    func makeSession(
        description: CCAPIRTPSessionDescription,
        destinationAddress: String
    ) async throws -> any CCAPIRTPSession
}

struct RTPPacket {
    let marker: Bool
    let payloadType: UInt8
    let sequenceNumber: UInt16
    let timestamp: UInt32
    let payload: Data

    static func parse(_ datagram: Data) -> RTPPacket? {
        let bytes = [UInt8](datagram)
        guard bytes.count >= minimumRTPHeaderBytes, bytes[0] >> 6 == rtpVersion else { return nil }
        let csrcCount = Int(bytes[0] & 0x0F)
        let hasExtension = bytes[0] & 0x10 != 0
        let hasPadding = bytes[0] & 0x20 != 0
        var payloadStart = minimumRTPHeaderBytes + csrcCount * 4
        guard payloadStart <= bytes.count else { return nil }

        if hasExtension {
            guard payloadStart + 4 <= bytes.count else { return nil }
            let extensionWords = (Int(bytes[payloadStart + 2]) << 8) | Int(bytes[payloadStart + 3])
            payloadStart += 4 + extensionWords * 4
            guard payloadStart <= bytes.count else { return nil }
        }

        let paddingBytes = hasPadding ? Int(bytes[bytes.count - 1]) : 0
        guard !hasPadding || paddingBytes > 0,
              paddingBytes <= bytes.count - payloadStart else { return nil }
        let payloadEnd = bytes.count - paddingBytes
        guard payloadStart < payloadEnd else { return nil }
        let sequence = (UInt16(bytes[2]) << 8) | UInt16(bytes[3])
        let timestamp = (UInt32(bytes[4]) << 24) |
            (UInt32(bytes[5]) << 16) |
            (UInt32(bytes[6]) << 8) |
            UInt32(bytes[7])
        return RTPPacket(
            marker: bytes[1] & 0x80 != 0,
            payloadType: bytes[1] & 0x7F,
            sequenceNumber: sequence,
            timestamp: timestamp,
            payload: Data(bytes[payloadStart..<payloadEnd])
        )
    }
}

private let h264ClockRate = 90_000
let canonRTPAudioClockRate = 48_000
private let rtpVersion: UInt8 = 2
private let minimumRTPHeaderBytes = 12
private let nalTypeMask: UInt8 = 0x1F
private let nalPrefixMask: UInt8 = 0xE0
private let singleNALMinimum: UInt8 = 1
private let singleNALMaximum: UInt8 = 23
private let idrType: UInt8 = 5
private let spsType: UInt8 = 7
private let ppsType: UInt8 = 8
private let stapAType: UInt8 = 24
private let fuaType: UInt8 = 28
private let fuStartMask: UInt8 = 0x80
private let fuEndMask: UInt8 = 0x40
private let fuReservedMask: UInt8 = 0x20
private let maximumAccessUnitBytes = 8 * 1024 * 1024
