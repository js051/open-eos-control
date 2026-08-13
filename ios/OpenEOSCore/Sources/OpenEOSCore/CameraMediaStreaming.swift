import Foundation

public struct CameraMediaStreamResponse: Sendable {
    public let item: CameraMediaItem
    public let statusCode: Int
    public let contentType: String?
    public let contentLength: Int64?
    public let totalBytes: Int64?
    public let rangeStart: Int64
    public let chunks: AsyncThrowingStream<Data, Error>
    private let cancelAction: @Sendable () -> Void

    init(
        item: CameraMediaItem,
        response: CameraHTTPStreamResponse,
        fallbackTotalBytes: Int64?
    ) throws {
        let range = try Self.parseContentRange(response.header("content-range"))
        let contentLength = response.header("content-length").flatMap(Int64.init).flatMap { $0 >= 0 ? $0 : nil }

        if response.statusCode == 206, range == nil {
            response.cancel()
            throw CameraMediaStreamValidationError.invalidContentRange
        }

        self.item = item
        statusCode = response.statusCode
        contentType = response.header("content-type")?
            .components(separatedBy: ";")
            .first?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        self.contentLength = range.map { $0.end - $0.start + 1 } ?? contentLength
        totalBytes = range?.totalBytes ?? (response.statusCode == 200 ? contentLength : nil) ?? fallbackTotalBytes
        rangeStart = range?.start ?? 0
        chunks = response.chunks
        cancelAction = response.cancel
    }

    public func cancel() {
        cancelAction()
    }

    private static func parseContentRange(_ value: String?) throws -> ParsedContentRange? {
        guard let value else { return nil }
        let parts = value.split(separator: " ", maxSplits: 1)
        guard parts.count == 2, parts[0].lowercased() == "bytes" else {
            throw CameraMediaStreamValidationError.invalidContentRange
        }
        let rangeAndTotal = parts[1].split(separator: "/", maxSplits: 1)
        guard rangeAndTotal.count == 2 else {
            throw CameraMediaStreamValidationError.invalidContentRange
        }
        let bounds = rangeAndTotal[0].split(separator: "-", maxSplits: 1)
        guard bounds.count == 2,
              let start = Int64(bounds[0]),
              let end = Int64(bounds[1]),
              start >= 0,
              end >= start else {
            throw CameraMediaStreamValidationError.invalidContentRange
        }
        let totalBytes: Int64?
        if rangeAndTotal[1] == "*" {
            totalBytes = nil
        } else if let parsedTotal = Int64(rangeAndTotal[1]), parsedTotal > 0 {
            totalBytes = parsedTotal
        } else {
            throw CameraMediaStreamValidationError.invalidContentRange
        }
        if let totalBytes, totalBytes <= end {
            throw CameraMediaStreamValidationError.invalidContentRange
        }
        return ParsedContentRange(start: start, end: end, totalBytes: totalBytes)
    }
}

enum CameraMediaStreamValidationError: Error {
    case invalidContentRange
}

private struct ParsedContentRange {
    let start: Int64
    let end: Int64
    let totalBytes: Int64?
}
