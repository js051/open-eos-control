import Foundation

enum CCAPIMultipartLiveView {
    static let maximumFrameBytes = 12 * 1024 * 1024
    private static let maximumBoundaryCharacters = 200

    static func boundary(from contentType: String?) throws -> String {
        let parts = (contentType ?? "").split(separator: ";", omittingEmptySubsequences: false)
        guard parts.first?.trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased() == "multipart/x-mixed-replace" else {
            throw CCAPIError.invalidResponse(
                "Canon multipart Live View returned \(contentType ?? "no Content-Type"); expected multipart/x-mixed-replace."
            )
        }
        let rawBoundary = parts.dropFirst().compactMap { part -> String? in
            let components = part.split(separator: "=", maxSplits: 1, omittingEmptySubsequences: false)
            guard components.count == 2,
                  components[0].trimmingCharacters(in: .whitespacesAndNewlines).lowercased() == "boundary" else {
                return nil
            }
            return components[1].trimmingCharacters(in: .whitespacesAndNewlines)
        }.first ?? ""
        let unquoted = rawBoundary.hasPrefix("\"") && rawBoundary.hasSuffix("\"") && rawBoundary.count >= 2
            ? String(rawBoundary.dropFirst().dropLast())
            : rawBoundary
        let boundary = unquoted.hasPrefix("--") ? String(unquoted.dropFirst(2)) : unquoted
        guard !boundary.isEmpty,
              boundary.count <= maximumBoundaryCharacters,
              boundary.unicodeScalars.allSatisfy({ (33...126).contains($0.value) && $0.value != 34 }) else {
            throw CCAPIError.invalidResponse(
                "Canon multipart Live View returned a missing or invalid ASCII boundary."
            )
        }
        return boundary
    }
}

struct CCAPIMultipartParser {
    private static let maximumLineBytes = 8 * 1024
    private static let maximumHeaderBytes = 16 * 1024
    private static let maximumHeaderCount = 32

    private enum State {
        case boundary(scanned: Int)
        case headers(values: [String: String], bytes: Int)
        case body(length: Int)
        case closed
    }

    private let delimiter: String
    private let closingDelimiter: String
    private var state = State.boundary(scanned: 0)
    private var buffer = Data()

    init(boundary: String) {
        delimiter = "--\(boundary)"
        closingDelimiter = "--\(boundary)--"
    }

    mutating func append(_ data: Data) throws -> [Data] {
        buffer.append(data)
        guard buffer.count <= CCAPIMultipartLiveView.maximumFrameBytes + Self.maximumHeaderBytes else {
            throw CCAPIError.invalidResponse("Canon multipart Live View buffer exceeded the safety limit.")
        }
        var frames: [Data] = []
        parsing: while true {
            switch state {
            case let .boundary(scanned):
                guard let line = try readLine() else { break parsing }
                let total = scanned + line.utf8.count
                guard total <= Self.maximumHeaderBytes else {
                    throw CCAPIError.invalidResponse("Canon multipart Live View preamble exceeded the safety limit.")
                }
                if line == delimiter {
                    state = .headers(values: [:], bytes: 0)
                } else if line == closingDelimiter {
                    state = .closed
                } else {
                    state = .boundary(scanned: total)
                }
            case let .headers(values, bytes):
                guard let line = try readLine() else { break parsing }
                if line.isEmpty {
                    let contentType = values["content-type"]?.split(separator: ";", maxSplits: 1).first?
                        .trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
                    guard contentType == "image/jpeg" else {
                        throw CCAPIError.invalidResponse("Canon multipart Live View part is not image/jpeg.")
                    }
                    let lengthText = values["content-length"] ?? ""
                    guard !lengthText.isEmpty,
                          lengthText.unicodeScalars.allSatisfy({ (48...57).contains($0.value) }),
                          let length = Int(lengthText),
                          (1...CCAPIMultipartLiveView.maximumFrameBytes).contains(length) else {
                        throw CCAPIError.invalidResponse(
                            "Canon multipart Live View returned an invalid or unsafe Content-Length."
                        )
                    }
                    state = .body(length: length)
                    continue
                }
                let total = bytes + line.utf8.count
                guard total <= Self.maximumHeaderBytes, values.count < Self.maximumHeaderCount,
                      let separator = line.firstIndex(of: ":") else {
                    throw CCAPIError.invalidResponse(
                        "Canon multipart Live View returned malformed or oversized part headers."
                    )
                }
                let name = String(line[..<separator])
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                    .lowercased()
                let value = String(line[line.index(after: separator)...])
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                guard !name.isEmpty, values[name] == nil else {
                    throw CCAPIError.invalidResponse("Canon multipart Live View returned a duplicate part header.")
                }
                var updated = values
                updated[name] = value
                state = .headers(values: updated, bytes: total)
            case let .body(length):
                guard buffer.count >= length else { break parsing }
                let frame = Data(buffer.prefix(length))
                buffer.removeFirst(length)
                guard frame.count >= 4,
                      frame.prefix(2) == Data([0xFF, 0xD8]),
                      frame.suffix(2) == Data([0xFF, 0xD9]) else {
                    throw CCAPIError.invalidResponse(
                        "Canon multipart Live View part did not contain a complete JPEG frame."
                    )
                }
                frames.append(frame)
                state = .boundary(scanned: 0)
            case .closed:
                guard buffer.allSatisfy({ $0 == 0x0D || $0 == 0x0A }) else {
                    throw CCAPIError.invalidResponse("Canon multipart Live View returned data after its closing boundary.")
                }
                buffer.removeAll(keepingCapacity: false)
                break parsing
            }
        }
        return frames
    }

    mutating func finish() throws {
        _ = try append(Data())
        guard case .closed = state, buffer.isEmpty else {
            throw CCAPIError.invalidResponse("Canon multipart Live View ended before a complete closing boundary.")
        }
    }

    private mutating func readLine() throws -> String? {
        guard let newline = buffer.firstIndex(of: 0x0A) else {
            guard buffer.count <= Self.maximumLineBytes else {
                throw CCAPIError.invalidResponse("Canon multipart Live View line exceeded the safety limit.")
            }
            return nil
        }
        let count = buffer.distance(from: buffer.startIndex, to: newline)
        guard count <= Self.maximumLineBytes else {
            throw CCAPIError.invalidResponse("Canon multipart Live View line exceeded the safety limit.")
        }
        var line = Data(buffer.prefix(count))
        buffer.removeFirst(count + 1)
        if line.last == 0x0D { line.removeLast() }
        return String(decoding: line, as: Unicode.UTF8.self)
    }
}

final class CCAPIMultipartLiveViewSession: @unchecked Sendable {
    private var iterator: AsyncThrowingStream<Data, Error>.Iterator
    private let continuation: AsyncThrowingStream<Data, Error>.Continuation
    private let worker: Task<Void, Never>
    private let response: CameraHTTPStreamResponse

    init(response: CameraHTTPStreamResponse, boundary: String) {
        self.response = response
        let output = AsyncThrowingStream<Data, Error>.makeStream(bufferingPolicy: .bufferingNewest(1))
        iterator = output.stream.makeAsyncIterator()
        continuation = output.continuation
        worker = Task {
            var parser = CCAPIMultipartParser(boundary: boundary)
            do {
                for try await chunk in response.chunks {
                    try Task.checkCancellation()
                    for frame in try parser.append(chunk) {
                        output.continuation.yield(frame)
                    }
                }
                try parser.finish()
                output.continuation.finish()
            } catch {
                output.continuation.finish(throwing: error)
            }
        }
    }

    func nextFrame() async throws -> Data {
        guard let frame = try await iterator.next() else {
            throw CCAPIError.invalidResponse("Canon multipart Live View stream ended before the next frame.")
        }
        return frame
    }

    func close() {
        worker.cancel()
        response.cancel()
        continuation.finish(throwing: CancellationError())
    }
}
