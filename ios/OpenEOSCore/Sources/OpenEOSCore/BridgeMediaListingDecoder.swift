import Foundation
import CoreFoundation

/// Browsing snapshots only. A terminal record never proves a strict inventory.
struct BridgeMediaListingDecoder {
    static let mediaType = "application/vnd.open-eos.media-list.v1+ndjson"
    static let maximumLineBytes = 65_536
    static let maximumSnapshotBytes = 64 * 1024 * 1024
    static let maximumItems = 100_000
    private let sessionID: String
    private let limit: Int?
    private var line = Data()
    private var requestID: String?
    private var nextSequence = 0
    private var revision = 0
    private var pendingTotal: Int?
    private var pending = [CameraMediaItem]()
    private var identities = Set<Data>()
    private var snapshotBytes = 0
    private var latest = [CameraMediaItem]()
    private var complete = false
    private var terminalError: DesktopBridgeError?

    init(sessionID: String, limit: Int?) {
        self.sessionID = sessionID
        self.limit = limit
    }

    mutating func consume(
        _ byte: UInt8,
        parseItem: ([String: Any]) -> CameraMediaItem?
    ) throws -> [CameraMediaItem]? {
        guard !complete else { throw invalid("Data followed the terminal record.") }
        guard line.count < Self.maximumLineBytes else { throw invalid("Listing record exceeded its byte limit.") }
        line.append(byte)
        guard byte == 10 else { return nil }
        let recordBytes = line.count
        let data = line
        line.removeAll(keepingCapacity: true)
        guard String(data: data, encoding: .utf8) != nil else { throw invalid("Listing record is not UTF-8.") }
        let object: [String: Any]
        do {
            // Reuse grammar/duplicate-key validation only, without inventory evidence.
            object = try CameraMediaMetadataJSON.decode(data)
        } catch { throw invalid("Listing record is not valid JSON.") }
        guard let type = object["type"] as? String,
              let id = object["requestId"] as? String, !id.isEmpty, id.utf8.count <= 256,
              let session = object["sessionId"] as? String, session.utf8.elementsEqual(sessionID.utf8),
              object["scope"] as? String == (limit == nil ? "full" : "recent"),
              integer(object["seq"]) == nextSequence else { throw invalid("Listing record binding or sequence changed.") }
        nextSequence += 1
        if requestID == nil {
            guard type == "start", nextSequence == 1,
                  limit.map({ integer(object["limit"]) == $0 }) ?? (object["limit"] is NSNull) else {
                throw invalid("Listing start record is invalid.")
            }
            requestID = id
            return nil
        }
        guard id.utf8.elementsEqual(requestID!.utf8) else { throw invalid("Listing request changed.") }
        switch type {
        case "snapshot":
            guard integer(object["revision"]) == revision + 1,
                  let offset = integer(object["offset"]), offset == pending.count,
                  let total = integer(object["total"]), total <= Self.maximumItems,
                  limit.map({ total <= $0 }) ?? true,
                  pendingTotal == nil || pendingTotal == total,
                  let raw = object["items"] as? [[String: Any]], raw.count <= 128,
                  raw.count <= total - offset,
                  let last = boolean(object["last"]),
                  last == (offset + raw.count == total),
                  !raw.isEmpty || (last && total == 0),
                  recordBytes <= Self.maximumSnapshotBytes - snapshotBytes else {
                throw invalid("Listing snapshot is malformed or exceeds its resource limit.")
            }
            snapshotBytes += recordBytes
            pendingTotal = total
            for value in raw {
                guard let id = value["id"] as? String, !id.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                      let name = value["name"] as? String, !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
                      let item = parseItem(value), item.id.utf8.elementsEqual(id.utf8),
                      identities.insert(Data(id.utf8)).inserted else { throw invalid("Listing item or identity is invalid.") }
                pending.append(item)
            }
            guard last else { return nil }
            revision += 1
            latest = pending
            pending = []
            identities.removeAll(keepingCapacity: true)
            pendingTotal = nil
            snapshotBytes = 0
            return latest
        case "complete":
            guard revision > 0, pendingTotal == nil,
                  integer(object["revision"]) == revision,
                  integer(object["count"]) == latest.count,
                  boolean(object["scopeComplete"]) == true else { throw invalid("Listing completion is invalid.") }
            complete = true
            return nil
        case "error":
            guard let code = object["code"] as? String, !code.isEmpty,
                  let message = object["message"] as? String, !message.isEmpty else { throw invalid("Listing error record is invalid.") }
            complete = true
            terminalError = invalid("Desktop Bridge listing failed [\(code)]: \(message)")
            return nil
        default:
            throw invalid("Unexpected listing record.")
        }
    }

    func finish() throws -> [CameraMediaItem] {
        guard complete, line.isEmpty else { throw invalid("Listing ended without a complete terminal record.") }
        if let terminalError { throw terminalError }
        guard pendingTotal == nil else { throw invalid("Listing snapshot is incomplete.") }
        return latest
    }

    private func integer(_ value: Any?) -> Int? {
        guard let number = value as? NSNumber, CFGetTypeID(number) != CFBooleanGetTypeID(),
              let result = Int(number.stringValue), result >= 0, result <= 9_007_199_254_740_991 else { return nil }
        return result
    }

    private func boolean(_ value: Any?) -> Bool? {
        guard let number = value as? NSNumber, CFGetTypeID(number) == CFBooleanGetTypeID() else { return nil }
        return number.boolValue
    }

    private func invalid(_ message: String) -> DesktopBridgeError { .invalidResponse(message) }
}
