import Foundation

public enum JPEGFrameParser {
    public static let maximumScanBytes = 16 * 1024 * 1024
    public static let maximumFrameBytes = 12 * 1024 * 1024

    public static func firstJPEG(
        in data: Data,
        maximumScanBytes: Int = maximumScanBytes,
        maximumFrameBytes: Int = maximumFrameBytes
    ) throws -> Data {
        guard data.count <= maximumScanBytes else {
            throw CCAPIError.invalidResponse(
                "Live View response did not contain a complete JPEG within \(maximumScanBytes) bytes."
            )
        }

        let bytes = [UInt8](data)
        var start: Int?
        var index = 1
        while index < bytes.count {
            if start == nil, bytes[index - 1] == 0xFF, bytes[index] == 0xD8 {
                start = index - 1
            } else if let start, bytes[index - 1] == 0xFF, bytes[index] == 0xD9 {
                let end = index + 1
                guard end - start <= maximumFrameBytes else {
                    throw CCAPIError.invalidResponse("Live View JPEG exceeded \(maximumFrameBytes) bytes.")
                }
                return data.subdata(in: start..<end)
            }
            index += 1
        }

        if start != nil {
            throw CCAPIError.invalidResponse("Live View response ended before the JPEG was complete.")
        }
        throw CCAPIError.invalidResponse("Live View response did not contain a JPEG frame.")
    }

    public static func validatedImageData(_ data: Data, contentType: String?) throws -> Data {
        let normalized = contentType?.lowercased() ?? ""
        if normalized.contains("png") {
            let signature = Data([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A])
            guard data.count <= maximumFrameBytes, data.starts(with: signature) else {
                throw CCAPIError.invalidResponse("Live View returned an invalid PNG frame.")
            }
            return data
        }
        return try firstJPEG(in: data)
    }
}
