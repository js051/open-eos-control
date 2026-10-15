import Foundation
import CoreFoundation

/// One explicitly requested target. No relative rotation or toggles are replayed.
public enum CameraMediaMutation: Equatable, Sendable {
    case rating(Int), protection(Bool), rotation(Int), archive(Bool)

    public var feature: CameraFeature {
        switch self {
        case .rating: .mediaRating
        case .protection: .mediaProtect
        case .rotation: .mediaRotate
        case .archive: .mediaArchive
        }
    }
    public var valid: Bool {
        switch self {
        case let .rating(value): (0...5).contains(value)
        case let .rotation(value): [0, 90, 180, 270].contains(value)
        default: true
        }
    }
    public func isWritable(_ item: CameraMediaItem) -> Bool {
        guard item.writable != false else { return false }
        switch self {
        case .rating: return item.ratingWritable != false
        case .protection: return item.protectionWritable != false
        case .rotation: return item.rotationWritable != false
        case .archive: return item.archiveWritable != false
        }
    }
    public func confirms(_ updated: CameraMediaItem, original: CameraMediaItem) -> Bool {
        guard valid, updated.id.utf8.elementsEqual(original.id.utf8) else { return false }
        switch self {
        case let .rating(value): return updated.rating == value
        case let .protection(value): return updated.protected == value
        case let .rotation(value): return updated.rotationDegrees == value
        case let .archive(value): return updated.archived == value
        }
    }
}

/// Set synchronously immediately before transport dispatch, after local validation.
/// The marker is evidence of possible transmission, never evidence of success.
public final class CameraMediaMutationAttempt: @unchecked Sendable {
    private let lock = NSLock()
    private var sent = false
    private var retired = false
    public init() {}
    public var dispatched: Bool {
        lock.lock(); defer { lock.unlock() }
        return sent
    }
    public func cancel() { lock.lock(); retired = true; lock.unlock() }
    func markDispatched() throws {
        lock.lock(); defer { lock.unlock() }
        guard !retired, !sent else { throw CancellationError() }
        sent = true
    }
}

/// Scoped to media fields. Generic camera-setting coercion is deliberately untouched.
enum CameraMediaMetadataValue {
    static func integer(_ raw: Any?, allowed: [Int]) -> Int? {
        if let text = raw as? String {
            return allowed.first { text.utf8.elementsEqual(String($0).utf8) }
        }
        guard let number = raw as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID(),
              !["f", "d"].contains(String(cString: number.objCType)),
              let value = Int(number.stringValue), allowed.contains(value) else { return nil }
        return value
    }
    static func boolean(_ raw: Any?) -> Bool? {
        guard let number = raw as? NSNumber, CFGetTypeID(number) == CFBooleanGetTypeID() else { return nil }
        return number.boolValue
    }
    static func canonRating(_ raw: Any?) -> Int? {
        guard let text = raw as? String else { return nil }
        if text == "off" { return 0 }
        return (1...5).first { text.utf8.elementsEqual(String($0).utf8) }
    }
    static func canonRotation(_ raw: Any?) -> Int? {
        guard let text = raw as? String else { return nil }
        return [0, 90, 180, 270].first { text.utf8.elementsEqual(String($0).utf8) }
    }
    static func canonFlag(_ raw: Any?) -> Bool? {
        guard let text = raw as? String else { return nil }
        switch text { case "enable": return true; case "disable": return false; default: return nil }
    }
}

/// Foundation can collapse 3.0, 3e0 and rounded decimals into integer NSNumber.
/// Preserve that negative evidence before conversion, only for media numeric fields.
/// Other fields retain Foundation's normal types. Duplicate keys fail closed.
enum CameraMediaMetadataJSON {
    static func decode(_ data: Data) throws -> [String: Any] {
        guard data.count <= 64 * 1024 * 1024 else { throw CameraMediaMetadataError.invalidMetadata }
        _ = try decodeForegroundJSONObject(data)
        guard var text = String(data: data, encoding: .utf8) else { throw CameraMediaMetadataError.invalidMetadata }
        if text.unicodeScalars.first?.value == 0xFEFF { text.removeFirst() }
        var scanner = Scanner(bytes: Array(text.utf8))
        try scanner.value(key: nil, depth: 0)
        var bytes = scanner.bytes
        for range in scanner.invalid.reversed() { bytes.replaceSubrange(range, with: Array("null".utf8)) }
        guard let result = try JSONSerialization.jsonObject(with: Data(bytes)) as? [String: Any] else {
            throw CameraMediaMetadataError.invalidMetadata
        }
        return result
    }
    private struct Scanner {
        let bytes: [UInt8]
        var offset = 0
        var invalid: [Range<Int>] = []
        mutating func whitespace() { while offset < bytes.count && [9, 10, 13, 32].contains(bytes[offset]) { offset += 1 } }
        mutating func string() throws -> String {
            whitespace()
            let start = offset
            guard offset < bytes.count, bytes[offset] == 34 else { throw CameraMediaMetadataError.invalidMetadata }
            offset += 1
            while offset < bytes.count {
                let byte = bytes[offset]; offset += 1
                if byte == 34 {
                    return try JSONSerialization.jsonObject(with: Data(bytes[start..<offset]), options: [.fragmentsAllowed]) as! String
                }
                if byte == 92 { offset += 1 }
            }
            throw CameraMediaMetadataError.invalidMetadata
        }
        mutating func value(key: String?, depth: Int) throws {
            whitespace()
            guard depth <= 512, offset < bytes.count else { throw CameraMediaMetadataError.invalidMetadata }
            switch bytes[offset] {
            case 123:
                offset += 1; whitespace()
                if bytes[offset] == 125 { offset += 1; return }
                while true {
                    let name = try string(); whitespace(); offset += 1 // validated colon
                    try value(key: name, depth: depth + 1); whitespace()
                    if bytes[offset] == 125 { offset += 1; return }
                    offset += 1 // validated comma
                }
            case 91:
                offset += 1; whitespace()
                if bytes[offset] == 93 { offset += 1; return }
                while true {
                    try value(key: nil, depth: depth + 1); whitespace()
                    if bytes[offset] == 93 { offset += 1; return }
                    offset += 1
                }
            case 34: _ = try string()
            default:
                let start = offset
                while offset < bytes.count && ![9, 10, 13, 32, 44, 93, 125].contains(bytes[offset]) { offset += 1 }
                if let key, ["rating", "rotate", "rotationDegrees"].contains(key) {
                    let token = String(decoding: bytes[start..<offset], as: UTF8.self)
                    let allowed = key == "rating" ? ["0", "1", "2", "3", "4", "5"] : ["0", "90", "180", "270"]
                    if !allowed.contains(token) { invalid.append(start..<offset) }
                }
            }
        }
    }
}
