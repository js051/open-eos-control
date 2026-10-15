import Foundation
import XCTest

@testable import OpenEOSControl

/// Authored native tests. Source validation cannot substitute for XCTest.
final class CubeLutImportReadTests: XCTestCase {
    private struct Contract: Decodable {
        let maximumBytes: Int
        let vectors: [Vector]
        let sizeBoundaryCases: [SizeBoundary]
    }
    private struct Vector: Decodable {
        let id: String
        let base64: String
        let accepted: Bool
        let normalizedText: String?
    }
    private struct SizeBoundary: Decodable { let bytes: Int }

    private func contract() throws -> Contract {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "utf8-vectors", withExtension: "json"))
        return try JSONDecoder().decode(Contract.self, from: Data(contentsOf: url))
    }

    func testSharedBytesPassThroughProductionBoundedDecoderAndExistingParser() throws {
        let fixture = try contract()
        XCTAssertEqual(fixture.maximumBytes, maximumCubeLutBytes)
        XCTAssertEqual(fixture.vectors.count, 16)
        for vector in fixture.vectors {
            let bytes = try XCTUnwrap(Data(base64Encoded: vector.base64))
            // One-byte chunks split every multibyte scalar and BOM boundary.
            var offset = 0
            let decode = {
                try readBoundedCubeLutText { count in
                    let end = min(bytes.count, offset + min(1, count))
                    defer { offset = end }
                    return bytes.subdata(in: offset..<end)
                }
            }
            if vector.accepted {
                let text = try decode()
                XCTAssertEqual(text, vector.normalizedText, vector.id)
                let lut = try parseCubeLut(text, fallbackName: vector.id)
                XCTAssertEqual(lut.size, 2, vector.id)
                XCTAssertEqual(lut.sample(red: 0.25, green: 0.5, blue: 0.75), [0.25, 0.5, 0.75], vector.id)
            } else {
                XCTAssertThrowsError(try decode(), vector.id)
            }
        }
    }

    func testExactLimitAndGrowingUnknownSizeStopAfterOneOverflowByte() throws {
        let fixture = try contract()
        XCTAssertEqual(fixture.sizeBoundaryCases.map(\.bytes), [maximumCubeLutBytes, maximumCubeLutBytes + 1])
        // No size metadata exists: the producer can keep growing forever.
        for length in fixture.sizeBoundaryCases.map(\.bytes) + [maximumCubeLutBytes * 20] {
            var total = 0
            var largestRequest = 0
            let read = { (count: Int) -> Data in
                largestRequest = max(largestRequest, count)
                let size = min(count, length - total)
                total += size
                return Data(repeating: 0x20, count: size)
            }
            if length == maximumCubeLutBytes {
                XCTAssertEqual(try readBoundedCubeLutText(read: read).utf8.count, maximumCubeLutBytes)
            } else {
                XCTAssertThrowsError(try readBoundedCubeLutText(read: read))
            }
            XCTAssertEqual(total, min(length, maximumCubeLutBytes + 1))
            XCTAssertLessThanOrEqual(largestRequest, 64 * 1024)
        }
    }

    func testByteLimitCountsBOMBeforeNormalization() throws {
        var offset = 0
        let length = maximumCubeLutBytes + 1
        XCTAssertThrowsError(try readBoundedCubeLutText { count in
            let size = min(count, length - offset)
            var chunk = Data(repeating: 0x20, count: size)
            if offset == 0 { chunk.replaceSubrange(0..<3, with: [0xEF, 0xBB, 0xBF]) }
            offset += size
            return chunk
        })
        XCTAssertEqual(offset, maximumCubeLutBytes + 1)
    }

    func testReadFailureAndCancellationDoNotContinueReading() throws {
        var reads = 0
        XCTAssertThrowsError(try readBoundedCubeLutText { _ in
            reads += 1
            throw CocoaError(.fileReadNoPermission)
        })
        XCTAssertEqual(reads, 1)
        reads = 0
        XCTAssertThrowsError(try readBoundedCubeLutText(read: { _ in
            reads += 1
            return Data([0x20])
        }, checkCancellation: {
            if reads == 1 { throw CancellationError() }
        })) { XCTAssertTrue($0 is CancellationError) }
        XCTAssertEqual(reads, 1)
        reads = 0
        XCTAssertThrowsError(try readBoundedCubeLutText(read: { _ in
            reads += 1
            return Data()
        }, checkCancellation: { throw CancellationError() }))
        XCTAssertEqual(reads, 0)
    }

    func testPhysicalPumpKeepsOneActiveAndOnlyLatestPendingAcrossFortyReplacements() async throws {
        let started = expectation(description: "First physical read entered")
        let release = DispatchSemaphore(value: 0)
        let reads = ReadNames()
        let reader = CubeLutFileReader { url, check in
            reads.append(url.lastPathComponent)
            if url.lastPathComponent == "first" {
                started.fulfill()
                guard release.wait(timeout: .now() + 10) == .success else { throw URLError(.timedOut) }
            }
            try check()
            return try parseCubeLut(Self.identity, fallbackName: url.lastPathComponent)
        }
        defer { release.signal() }
        let first = Task { try await reader.load(URL(fileURLWithPath: "/synthetic/first")) }
        await fulfillment(of: [started], timeout: 5)
        first.cancel()
        var latest = Task { try await reader.load(URL(fileURLWithPath: "/synthetic/replacement-0")) }
        try await waitForPending(reader)
        for index in 1...40 {
            let prior = latest
            latest = Task { try await reader.load(URL(fileURLWithPath: "/synthetic/replacement-\(index)")) }
            switch await prior.result {
            case .success: XCTFail("Superseded pending read must not execute")
            case let .failure(error): XCTAssertTrue(error is CancellationError)
            }
            XCTAssertEqual(reader.bufferedRequestCounts.active, 1)
            XCTAssertEqual(reader.bufferedRequestCounts.pending, 1)
        }
        XCTAssertEqual(reads.names, ["first"])
        release.signal()
        switch await first.result {
        case .success: XCTFail("Canceled active read must not publish")
        case let .failure(error): XCTAssertTrue(error is CancellationError)
        }
        let imported = try await latest.value
        XCTAssertEqual(imported.name, "replacement-40")
        XCTAssertEqual(reads.names, ["first", "replacement-40"])
        XCTAssertEqual(reader.bufferedRequestCounts.active, 0)
        XCTAssertEqual(reader.bufferedRequestCounts.pending, 0)
    }

    @MainActor
    func testCancelShowsPhysicalRetirementAndLatestSelectionWaitsForThatRead() async throws {
        let entered = expectation(description: "Blocked provider entered")
        let release = DispatchSemaphore(value: 0)
        let reader = CubeLutFileReader { url, check in
            if url.lastPathComponent == "blocked" {
                entered.fulfill()
                guard release.wait(timeout: .now() + 10) == .success else { throw URLError(.timedOut) }
            }
            try check()
            return try parseCubeLut(Self.identity, fallbackName: url.lastPathComponent)
        }
        let suite = "CubeLutImportReadTests.retirement.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { release.signal(); defaults.removePersistentDomain(forName: suite) }
        let state = CameraAppState(defaults: defaults, cubeLutFileReader: reader)
        let seed = try parseCubeLut(Self.identity, fallbackName: "seed")
        state.monitorSettings.cubeLut = seed
        let old = Task { await state.importCubeLut(from: URL(fileURLWithPath: "/synthetic/blocked")) }
        await fulfillment(of: [entered], timeout: 5)
        state.cancelCubeLutImport()
        XCTAssertFalse(state.isImportingCubeLut)
        XCTAssertTrue(state.isRetiringCubeLutRead, "Cancellation does not claim OS read has closed")
        XCTAssertEqual(state.monitorSettings.cubeLut, seed)
        let latest = Task { await state.importCubeLut(from: URL(fileURLWithPath: "/synthetic/latest")) }
        try await waitForPending(reader)
        XCTAssertTrue(state.isImportingCubeLut)
        XCTAssertTrue(state.isRetiringCubeLutRead)
        release.signal()
        await old.value
        await latest.value
        XCTAssertEqual(state.monitorSettings.cubeLut?.name, "latest")
        XCTAssertFalse(state.isImportingCubeLut)
        XCTAssertFalse(state.isRetiringCubeLutRead)
    }

    private func waitForPending(_ reader: CubeLutFileReader) async throws {
        let clock = ContinuousClock()
        let deadline = clock.now.advanced(by: .seconds(5))
        while reader.bufferedRequestCounts.pending == 0 {
            if clock.now >= deadline { throw URLError(.timedOut) }
            await Task.yield()
        }
    }

    private static let identity = "LUT_3D_SIZE 2\n0 0 0\n1 0 0\n0 1 0\n1 1 0\n0 0 1\n1 0 1\n0 1 1\n1 1 1\n"
}

private final class ReadNames: @unchecked Sendable {
    private let lock = NSLock()
    private var values: [String] = []
    func append(_ name: String) { lock.lock(); defer { lock.unlock() }; values.append(name) }
    var names: [String] { lock.lock(); defer { lock.unlock() }; return values }
}
