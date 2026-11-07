import Foundation

/// Display families are evidence, never transport capabilities or automatic
/// delivery permission. Conflicting evidence cannot enter an image/video path.
public enum CameraMediaClassification: String, Sendable, Hashable {
    case photo, video, audio, other, unknown, conflict
}

public enum CameraMediaMetadataError: Error, Equatable, Sendable {
    case invalidMetadata
}

public enum CameraMediaType {
    public static func classify(_ item: CameraMediaItem) -> CameraMediaClassification {
        guard !item.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return .unknown }
        guard !containsMIMEControl(item.contentType) else { return .unknown }
        let mime = normalizedMIME(item.contentType)
        guard validMIME(mime) else { return .unknown }
        let families = Set([kindFamily(item.kind), suffixFamily(suffix(item.name)), mimeFamily(mime)].compactMap { $0 })
        if families.count > 1 { return .conflict }
        return families.first ?? .unknown
    }

    /// Conservative intersection of the existing automatic-JPEG gates. Display
    /// aliases, generic MIME and normalized kind values do not grant delivery.
    public static func isAutomaticJPEG(_ item: CameraMediaItem) -> Bool {
        guard !item.id.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              !item.name.contains("/"), !item.name.contains("\\"),
              item.kind == "image", let dot = item.name.lastIndex(of: "."), dot > item.name.startIndex,
              ["jpg", "jpeg"].contains(suffix(item.name)),
              classify(item) == .photo else { return false }
        guard let mime = item.contentType, !mime.isEmpty else { return true }
        return normalizedMIME(mime) == "image/jpeg"
    }

    public static func admitsFreshAutomaticJPEG(
        original: CameraMediaItem, metadata: CameraMediaItem, fresh: Bool
    ) -> Bool {
        fresh && original.id.utf8.elementsEqual(metadata.id.utf8)
            && original.name.utf8.elementsEqual(metadata.name.utf8)
            && isAutomaticJPEG(original) && isAutomaticJPEG(metadata)
    }

    public static func admitsVideoStream(_ item: CameraMediaItem, contentType: String?) -> Bool {
        let mime = normalizedMIME(contentType)
        return classify(item) == .video && !containsMIMEControl(contentType) && validMIME(mime)
            && (mimeFamily(mime) == nil || mimeFamily(mime) == .video)
    }

    /// Raw declarations must agree byte-for-byte; null differs from empty.
    /// Missing/null return nil, never a cached guess. Normalization belongs only
    /// to classification, never to the raw declarations in an adapter.
    public static func declaredMIME(in fields: [String: Any]) throws -> String? {
        var present = false
        var result: String?
        for key in ["content_type", "contentType", "contenttype", "mimeType", "mimetype"] {
            guard let raw = fields[key] else { continue }
            let value: String?
            if raw is NSNull { value = nil }
            else if let string = raw as? String { value = string }
            else { throw CameraMediaMetadataError.invalidMetadata }
            if present, result.map({ Data($0.utf8) }) != value.map({ Data($0.utf8) }) {
                throw CameraMediaMetadataError.invalidMetadata
            }
            present = true
            result = value
        }
        return result
    }

    private static func containsMIMEControl(_ value: String?) -> Bool {
        value?.unicodeScalars.contains { $0.value <= 31 || (127...159).contains($0.value) } ?? false
    }

    private static func asciiLowercase(_ value: String) -> String {
        String(String.UnicodeScalarView(value.unicodeScalars.map { scalar in
            (65...90).contains(scalar.value) ? UnicodeScalar(scalar.value + 32)! : scalar
        }))
    }

    private static func suffix(_ name: String) -> String {
        guard let dot = name.lastIndex(of: ".") else { return "" }
        return asciiLowercase(String(name[name.index(after: dot)...]))
    }

    private static func normalizedMIME(_ value: String?) -> String {
        let raw = value ?? ""
        let prefix = raw[..<(raw.firstIndex(of: ";") ?? raw.endIndex)]
        return asciiLowercase(String(prefix).trimmingCharacters(in: .whitespacesAndNewlines))
    }

    private static func validMIME(_ value: String) -> Bool {
        guard !value.isEmpty else { return true }
        let parts = value.split(separator: "/", omittingEmptySubsequences: false)
        let token = Set("abcdefghijklmnopqrstuvwxyz0123456789!#$&^_.+-".utf8)
        return parts.count == 2 && parts.allSatisfy { !$0.isEmpty && $0.utf8.allSatisfy { token.contains($0) } }
    }

    private static func kindFamily(_ value: String) -> CameraMediaClassification? {
        switch asciiLowercase(value.trimmingCharacters(in: .whitespacesAndNewlines)) {
        case "image", "photo", "jpeg", "jpg", "raw": .photo
        case "video", "movie": .video
        case "audio": .audio
        case "text", "document": .other
        default: nil
        }
    }

    private static func suffixFamily(_ value: String) -> CameraMediaClassification? {
        switch value {
        case "jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif", "tif", "tiff", "avif", "cr2", "cr3", "crw", "dng": .photo
        case "mp4", "mov", "m4v", "avi", "mkv": .video
        case "wav", "mp3", "aac", "m4a", "flac", "ogg", "opus", "aif", "aiff", "wma": .audio
        default: nil
        }
    }

    private static func mimeFamily(_ value: String) -> CameraMediaClassification? {
        if value.isEmpty || value == "application/octet-stream" { return nil }
        switch value.components(separatedBy: "/")[0] {
        case "image": return .photo
        case "video": return .video
        case "audio": return .audio
        default: return .other
        }
    }
}
