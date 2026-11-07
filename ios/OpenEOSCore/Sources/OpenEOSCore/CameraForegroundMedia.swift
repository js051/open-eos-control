import Foundation

#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// A separate, opt-in contract for unattended foreground reads. The ordinary
/// media browser and manual download APIs deliberately retain their compatibility behavior.
public protocol CameraForegroundMediaReading: Sendable {
    func foregroundMediaReadsAvailable() async -> Bool
    /// A returned array proves the traversal finished, including every advertised
    /// page and discovered container, within 1,000 identities and the resource bounds.
    /// A partial inventory is never returned. Identity UTF-8 bytes are preserved.
    func foregroundMediaInventory() async throws -> [CameraMediaItem]
    /// Requires a positive, exact filesize in this response; listing metadata is
    /// never used as a substitute for missing or malformed fresh metadata.
    func foregroundMediaInfo(_ item: CameraMediaItem) async throws -> CameraMediaItem
    /// The caller supplies filesize from foregroundMediaInfo immediately before
    /// this attempt. Exactly one canonical original request, with no redirects,
    /// fallback variants, or retries. Exact size, response MIME, bounded JPEG
    /// structure and native decode must pass before original bytes are published.
    func downloadForegroundOriginal(
        _ item: CameraMediaItem,
        expectedBytes: Int64,
        to destination: URL,
        progress: @escaping CameraMediaProgressHandler
    ) async throws -> CameraMediaDownload
}

public extension CameraForegroundMediaReading {
    func foregroundMediaReadsAvailable() async -> Bool { false }
    func foregroundMediaInventory() async throws -> [CameraMediaItem] {
        throw CameraForegroundMediaError.unsupported
    }
    func foregroundMediaInfo(_ item: CameraMediaItem) async throws -> CameraMediaItem {
        throw CameraForegroundMediaError.unsupported
    }
    func downloadForegroundOriginal(
        _ item: CameraMediaItem,
        expectedBytes: Int64,
        to destination: URL,
        progress: @escaping CameraMediaProgressHandler = { _ in }
    ) async throws -> CameraMediaDownload {
        throw CameraForegroundMediaError.unsupported
    }
}

public enum CameraForegroundMediaError: Error, Equatable, LocalizedError, Sendable {
    case unsupported
    case inventoryLimitExceeded
    case deadlineExceeded
    case invalidInventory
    case freshSizeUnavailable
    case originalSizeMismatch

    public var errorDescription: String? {
        switch self {
        case .unsupported: "This connection does not support strict foreground media reads."
        case .inventoryLimitExceeded: "The complete camera inventory exceeded the foreground read limits."
        case .deadlineExceeded: "The foreground camera read exceeded its time limit."
        case .invalidInventory: "The camera did not provide a valid, complete media inventory."
        case .freshSizeUnavailable: "The camera did not provide a fresh positive original file size."
        case .originalSizeMismatch: "The original download did not match the fresh file size."
        }
    }
}

// Bridge support requires the independently deployed strict inventory, fresh-info,
// and single-attempt contracts. Ordinary Bridge endpoints cannot establish them.
extension DesktopBridgeClient: CameraForegroundMediaReading {}

/// Transports must explicitly opt in, reject redirects, make only one request,
/// bound streamed decoded bytes, honor the request's total resource timeout, and
/// cooperate with task cancellation. Legacy transports (including mocks) cannot
/// silently inherit a data()/download() implementation that follows redirects.
public protocol CameraForegroundHTTPTransport: CameraHTTPTransport {
    func sendForeground(
        _ request: URLRequest,
        maximumResponseBytes: Int
    ) async throws -> CameraHTTPResponse
    func downloadForeground(
        _ request: URLRequest,
        maximumBytes: Int64,
        progress: @escaping CameraMediaProgressHandler
    ) async throws -> CameraHTTPDownloadResponse
}

struct CameraForegroundReadBudget {
    static let maximumItems = 1_000
    static let maximumRequests = 512
    static let maximumContainers = 512
    static let maximumJSONBytes = 2 * 1024 * 1024
    let deadline: ContinuousClock.Instant
    private(set) var requests = 0

    init(timeout: Duration = .seconds(30)) {
        deadline = ContinuousClock.now.advanced(by: timeout)
    }

    func remaining() throws -> TimeInterval {
        try Task.checkCancellation()
        let remaining = ContinuousClock.now.duration(to: deadline)
        guard remaining > .zero else { throw CameraForegroundMediaError.deadlineExceeded }
        let value = remaining.components
        return Double(value.seconds) + Double(value.attoseconds) / 1e18
    }

    mutating func nextRequestTimeout() throws -> TimeInterval {
        let remaining = try remaining()
        guard requests < Self.maximumRequests else { throw CameraForegroundMediaError.inventoryLimitExceeded }
        requests += 1
        return min(10, remaining)
    }
}
