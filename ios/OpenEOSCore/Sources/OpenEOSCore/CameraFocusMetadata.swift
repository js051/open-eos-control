import Foundation
import CoreFoundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public enum CameraFocusStatus: String, Sendable, CaseIterable {
    case standby = "STANDBY", focused = "FOCUSED", unfocused = "UNFOCUSED"
    case servoOff = "SERVO_OFF", focusing = "FOCUSING", unknown = "UNKNOWN"
}

public enum CameraFocusFrameKind: String, Sendable {
    case generic = "GENERIC", normal = "NORMAL", face = "FACE", tracking = "TRACKING", unknown = "UNKNOWN"
}

public struct CameraFocusFrame: Equatable, Sendable {
    public let left: Double
    public let top: Double
    public let right: Double
    public let bottom: Double
    public let status: CameraFocusStatus
    public let kind: CameraFocusFrameKind
}

/// Camera telemetry only. A successful AF command never constructs these frames.
public struct CameraFocusInfo: Equatable, Sendable {
    public let frames: [CameraFocusFrame]
    public let magnification: Int
    public let imageAspectRatio: Double

    public func matchesImage(aspectRatio: Double) -> Bool {
        magnification == 1 && aspectRatio.isFinite && aspectRatio > 0
            && abs(imageAspectRatio / aspectRatio - 1) <= 0.02
    }

    public static func isFresh(sampledAt: Date, now: Date) -> Bool {
        let age = now.timeIntervalSince(sampledAt)
        return age >= 0 && age < 1
    }
}

enum CameraFocusMetadata {
    static let maximumBytes = 256 * 1024
    static let maximumFrames = 512

    static func canonPacket(_ payload: Data) throws -> CameraFocusInfo? {
        guard (9...maximumBytes).contains(payload.count) else { throw invalid() }
        let bytes = [UInt8](payload)
        let length = bytes[3...6].reduce(UInt32(0)) { ($0 << 8) | UInt32($1) }
        guard bytes[0] == 0xff, bytes[1] == 0, bytes[2] == 1,
              length > 0, Int(length) == bytes.count - 9,
              bytes[bytes.count - 2] == 0xff, bytes[bytes.count - 1] == 0xff,
              let root = try utf8JSON(payload.subdata(in: 7..<(7 + Int(length)))) as? [String: Any]
        else { throw invalid() }
        return try canonJSON(root)
    }

    static func canonJSON(_ root: [String: Any]) throws -> CameraFocusInfo? {
        guard let data = root["liveviewdata"] as? [String: Any],
              let image = data["image"] as? [String: Any],
              let ox = integer(image["positionx"]), ox >= 0,
              let oy = integer(image["positiony"]), oy >= 0,
              let width = integer(image["positionwidth"]), width > 0,
              let height = integer(image["positionheight"]), height > 0,
              let zoom = data["zoom"] as? [String: Any],
              let magnification = integer(zoom["magnification"]), magnification > 0,
              let rawFrames = data["afframe"] as? [Any] else { return nil }
        guard rawFrames.count <= maximumFrames else { throw invalid() }
        let frames: [CameraFocusFrame] = magnification == 1 ? rawFrames.compactMap { raw in
            guard let frame = raw as? [String: Any], integer(frame["select"]) == 1,
                  let status = integer(frame["status"]), (0...255).contains(status),
                  let x = integer(frame["x"]), let y = integer(frame["y"]),
                  let w = integer(frame["width"]), w > 0,
                  let h = integer(frame["height"]), h > 0 else { return nil }
            let left = clamp((Double(x) - Double(ox)) / Double(width))
            let top = clamp((Double(y) - Double(oy)) / Double(height))
            let right = clamp((Double(x) + Double(w) - Double(ox)) / Double(width))
            let bottom = clamp((Double(y) + Double(h) - Double(oy)) / Double(height))
            guard right > left, bottom > top else { return nil }
            let kind: CameraFocusFrameKind = [0: .generic, 0x10: .normal, 0x20: .face, 0x30: .tracking][status & 0xf0] ?? .unknown
            let state: CameraFocusStatus = kind == .unknown ? .unknown
                : [0: .standby, 1: .focused, 2: .unfocused, 3: .servoOff, 4: .focusing][status & 0x0f] ?? .unknown
            return CameraFocusFrame(left: left, top: top, right: right, bottom: bottom, status: state, kind: kind)
        } : []
        return CameraFocusInfo(frames: frames, magnification: magnification, imageAspectRatio: Double(width) / Double(height))
    }

    static func bridgeJSON(_ value: Any) throws -> CameraFocusInfo? {
        if value is NSNull { return nil }
        guard let root = value as? [String: Any], let zoom = integer(root["magnification"]), zoom > 0,
              let aspect = number(root["imageAspectRatio"]), aspect > 0,
              let rawFrames = root["frames"] as? [Any], rawFrames.count <= maximumFrames else { throw invalid() }
        if zoom != 1 { return CameraFocusInfo(frames: [], magnification: zoom, imageAspectRatio: aspect) }
        let frames = try rawFrames.map { raw -> CameraFocusFrame in
            guard let frame = raw as? [String: Any], let left = number(frame["left"]),
                  let top = number(frame["top"]), let right = number(frame["right"]), let bottom = number(frame["bottom"]),
                  (0...1).contains(left), (0...1).contains(top), (0...1).contains(right), (0...1).contains(bottom),
                  right > left, bottom > top, let status = frame["status"] as? String, let kind = frame["kind"] as? String
            else { throw invalid() }
            let parsedKind = CameraFocusFrameKind(rawValue: kind) ?? .unknown
            return CameraFocusFrame(left: left, top: top, right: right, bottom: bottom,
                status: parsedKind == .unknown ? .unknown : CameraFocusStatus(rawValue: status) ?? .unknown, kind: parsedKind)
        }
        return CameraFocusInfo(frames: frames, magnification: zoom, imageAspectRatio: aspect)
    }

    static func bridgePacket(_ payload: Data) throws -> CameraFocusInfo? {
        try bridgeJSON(utf8JSON(payload))
    }

    private static func utf8JSON(_ data: Data) throws -> Any {
        // Foundation's decoder auto-detects UTF-16/32, including ASCII documents
        // whose NUL bytes also happen to be valid UTF-8. Literal NUL is never
        // valid JSON; an escaped JSON NUL remains permitted inside a string.
        guard data.count <= maximumBytes, !data.contains(0), String(data: data, encoding: .utf8) != nil else {
            throw invalid()
        }
        return try JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])
    }

    static func boundedRead(_ request: URLRequest, transport: any CameraHTTPTransport) async throws -> Data {
        try Task.checkCancellation()
        let stream = try await transport.openStream(request)
        defer { stream.cancel() }
        guard stream.statusCode == 200 else { throw invalid() }
        if let raw = stream.headers["content-length"], let count = Int(raw), count > maximumBytes { throw invalid() }
        return try await withTaskCancellationHandler {
            var data = Data()
            for try await chunk in stream.chunks {
                try Task.checkCancellation()
                guard chunk.count <= maximumBytes - data.count else { throw invalid() }
                data.append(chunk)
            }
            try Task.checkCancellation()
            return data
        } onCancel: { stream.cancel() }
    }

    private static func integer(_ value: Any?) -> Int? {
        guard let number = number(value), number.rounded(.towardZero) == number,
              number >= Double(Int32.min), number <= Double(Int32.max) else { return nil }
        return Int(number)
    }

    private static func number(_ value: Any?) -> Double? {
        guard let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID(), number.doubleValue.isFinite else { return nil }
        return number.doubleValue
    }

    private static func clamp(_ value: Double) -> Double { min(1, max(0, value)) }
    private static func invalid() -> CCAPIError { .invalidResponse("Invalid or oversized camera focus information.") }
}
