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
