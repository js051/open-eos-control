import Foundation

struct MediaThumbnailCache {
    static let defaultCapacity = 96

    let capacity: Int
    private(set) var values: [String: Data] = [:]
    private var insertionOrder: [String] = []

    init(capacity: Int = defaultCapacity) {
        precondition(capacity > 0)
        self.capacity = capacity
    }

    mutating func insert(_ value: Data, for id: String) {
        insertionOrder.removeAll { $0 == id }
        insertionOrder.append(id)
        values[id] = value
        while insertionOrder.count > capacity {
            values.removeValue(forKey: insertionOrder.removeFirst())
        }
    }

    mutating func touch(_ id: String) -> Bool {
        guard values[id] != nil else { return false }
        insertionOrder.removeAll { $0 == id }
        insertionOrder.append(id)
        return true
    }

    mutating func removeValue(for id: String) {
        insertionOrder.removeAll { $0 == id }
        values.removeValue(forKey: id)
    }

    mutating func removeAll() {
        insertionOrder.removeAll(keepingCapacity: true)
        values.removeAll(keepingCapacity: true)
    }
}
