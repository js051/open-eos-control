import Foundation

typealias JSONDictionary = [String: Any]

func decodeJSONObject(_ data: Data) throws -> JSONDictionary {
    guard !data.isEmpty else { return [:] }
    let value = try JSONSerialization.jsonObject(with: data)
    guard let object = value as? JSONDictionary else {
        throw CCAPIError.invalidResponse("Camera returned JSON that was not an object.")
    }
    return object
}

// Foreground completeness evidence must retain every member until duplicate keys
// have been rejected. Ordinary manual reads keep their existing decoder semantics.
func decodeForegroundJSONObject(_ data: Data) throws -> JSONDictionary {
    guard !data.isEmpty else { throw CameraForegroundMediaError.invalidInventory }
    let object = try decodeJSONObject(data) // Foundation remains the JSON grammar validator.
    var scanner = try ForegroundJSONMembers(data)
    try scanner.validate()
    return object
}

// Internal for direct depth-boundary controls; callers must validate JSON grammar first.
struct ForegroundJSONMembers {
    private let bytes: [UInt8]
    private var index = 0

    init(_ data: Data) throws {
        let prefix = Array(data.prefix(4))
        let encoding: String.Encoding
        if prefix.starts(with: [0x00, 0x00, 0xFE, 0xFF]) {
            encoding = .utf32BigEndian
        } else if prefix.starts(with: [0xFF, 0xFE, 0x00, 0x00]) {
            encoding = .utf32LittleEndian
        } else if prefix.starts(with: [0xFE, 0xFF]) {
            encoding = .utf16BigEndian
        } else if prefix.starts(with: [0xFF, 0xFE]) {
            encoding = .utf16LittleEndian
        } else if prefix.count == 4 && prefix[0] == 0 && prefix[1] == 0 && prefix[2] == 0 {
            encoding = .utf32BigEndian
        } else if prefix.count == 4 && prefix[1] == 0 && prefix[2] == 0 && prefix[3] == 0 {
            encoding = .utf32LittleEndian
        } else if prefix.count >= 2 && prefix[0] == 0 {
            encoding = .utf16BigEndian
        } else if prefix.count >= 2 && prefix[1] == 0 {
            encoding = .utf16LittleEndian
        } else {
            encoding = .utf8
        }
        guard var text = String(data: data, encoding: encoding) else {
            throw CameraForegroundMediaError.invalidInventory
        }
        if text.unicodeScalars.first?.value == 0xFEFF { text.removeFirst() }
        bytes = Array(text.utf8)
    }

    mutating func validate() throws {
        try value(depth: 0)
        whitespace()
        guard index == bytes.count else { throw CameraForegroundMediaError.invalidInventory }
    }

    private mutating func whitespace() {
        while index < bytes.count && [9, 10, 13, 32].contains(bytes[index]) { index += 1 }
    }

    private mutating func consume(_ byte: UInt8) throws {
        whitespace()
        guard index < bytes.count, bytes[index] == byte else {
            throw CameraForegroundMediaError.invalidInventory
        }
        index += 1
    }

    private mutating func stringToken() throws -> Range<Int> {
        whitespace()
        let start = index
        try consume(34)
        while index < bytes.count {
            let byte = bytes[index]
            index += 1
            if byte == 34 { return start..<index }
            // Foundation already validates escape syntax and surrogate pairs.
            // Skip the escaped byte so an escaped quote cannot end this token.
            if byte == 92 {
                guard index < bytes.count else { throw CameraForegroundMediaError.invalidInventory }
                index += 1
            }
        }
        throw CameraForegroundMediaError.invalidInventory
    }

    private mutating func value(depth: Int) throws {
        whitespace()
        // Match Foundation's bounded JSON nesting rather than recurse unboundedly.
        guard depth <= 512, index < bytes.count else { throw CameraForegroundMediaError.invalidInventory }
        switch bytes[index] {
        case 123: // object
            index += 1
            whitespace()
            if index < bytes.count, bytes[index] == 125 { index += 1; return }
            var keys = Set<String>()
            while true {
                let token = try stringToken()
                guard let key = try JSONSerialization.jsonObject(
                    with: Data(bytes[token]), options: [.fragmentsAllowed]
                ) as? String, keys.insert(key).inserted else {
                    throw CameraForegroundMediaError.invalidInventory
                }
                // Swift dictionaries also equate canonically equivalent Unicode
                // spellings. Reject these aliases before the converted object is trusted.
                try consume(58)
                try value(depth: depth + 1)
                whitespace()
                guard index < bytes.count else { throw CameraForegroundMediaError.invalidInventory }
                if bytes[index] == 125 { index += 1; return }
                try consume(44)
            }
        case 91: // array
            index += 1
            whitespace()
            if index < bytes.count, bytes[index] == 93 { index += 1; return }
            while true {
                try value(depth: depth + 1)
                whitespace()
                guard index < bytes.count else { throw CameraForegroundMediaError.invalidInventory }
                if bytes[index] == 93 { index += 1; return }
                try consume(44)
            }
        case 34:
            _ = try stringToken()
        default:
            // A primitive's grammar has already been checked by Foundation.
            let start = index
            while index < bytes.count && ![9, 10, 13, 32, 44, 93, 125].contains(bytes[index]) { index += 1 }
            guard index > start else { throw CameraForegroundMediaError.invalidInventory }
        }
    }
}

func encodeJSONObject(_ object: JSONDictionary) throws -> Data {
    try JSONSerialization.data(withJSONObject: object, options: [.sortedKeys])
}

func JSONString(_ value: Any?) -> String {
    guard let value, JSONSerialization.isValidJSONObject(value) else { return "null" }
    guard let data = try? JSONSerialization.data(withJSONObject: value, options: [.sortedKeys]) else { return "null" }
    return String(data: data, encoding: .utf8) ?? "null"
}

extension Dictionary where Key == String, Value == Any {
    func object(_ key: String) -> JSONDictionary? {
        self[key] as? JSONDictionary
    }

    func array(_ key: String) -> [Any]? {
        self[key] as? [Any]
    }

    func string(_ key: String, default fallback: String = "") -> String {
        if let value = self[key] as? String { return value }
        if let value = self[key] as? NSNumber { return value.stringValue }
        return fallback
    }

    func bool(_ key: String) -> Bool? {
        if let value = self[key] as? Bool { return value }
        if let value = self[key] as? NSNumber { return value.boolValue }
        if let value = self[key] as? String {
            switch value.lowercased() {
            case "true", "yes", "1", "on": return true
            case "false", "no", "0", "off": return false
            default: return nil
            }
        }
        return nil
    }

    func integer(_ key: String) -> Int? {
        if let value = self[key] as? NSNumber { return value.intValue }
        if let value = self[key] as? String { return Int(value) }
        return nil
    }

    func integer64(_ key: String) -> Int64? {
        if let value = self[key] as? NSNumber { return value.int64Value }
        if let value = self[key] as? String {
            let trimmed = value.trimmingCharacters(in: .whitespacesAndNewlines)
            if let exact = Int64(trimmed) { return exact }
            let sign = trimmed.hasPrefix("-") ? "-" : ""
            let digits = trimmed.filter { $0.isNumber }
            return digits.isEmpty ? nil : Int64(sign + digits)
        }
        return nil
    }
}

extension Array where Element == Any {
    var strings: [String] {
        compactMap { value in
            if let value = value as? String { return value }
            if let value = value as? NSNumber { return value.stringValue }
            return nil
        }
    }

    var objects: [JSONDictionary] {
        compactMap { $0 as? JSONDictionary }
    }
}
