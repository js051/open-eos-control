import Foundation
import OpenEOSCore

/// Receiver-queue-local crop boundary. A partial AU and a receive admitted before
/// the boundary must not acquire the new crop revision from its final fragment.
/// This says nothing about camera/network buffering before local receive admission.
final class IOSRTPGeometryDepacketizer {
    private let payloadType: UInt8
    private var revision: UUID?
    private var depacketizer: CCAPIH264RTPDepacketizer

    init(payloadType: UInt8) {
        self.payloadType = payloadType
        depacketizer = CCAPIH264RTPDepacketizer(payloadType: payloadType)
    }

    func accept(_ data: Data, revision admitted: UUID, currentRevision: () -> UUID) -> CCAPIH264AccessUnit? {
        let current = currentRevision()
        guard admitted == current else {
            retire(to: current)
            return nil
        }
        if revision != current { retire(to: current) }
        let unit = depacketizer.accept(data)
        let settled = currentRevision()
        guard admitted == settled else {
            retire(to: settled)
            return nil
        }
        return unit
    }

    private func retire(to revision: UUID) {
        self.revision = revision
        depacketizer = CCAPIH264RTPDepacketizer(payloadType: payloadType)
    }
}
