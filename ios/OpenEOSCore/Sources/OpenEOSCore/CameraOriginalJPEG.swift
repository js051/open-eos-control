import Foundation
#if canImport(Darwin)
import Darwin
#elseif canImport(Glibc)
import Glibc
#endif
#if canImport(ImageIO)
import ImageIO
import CoreGraphics
#endif

/// Automatic original-import policy only. Manual RAW/media downloads do not use it.
/// Structure plus a bounded native decode is stronger evidence than SOI/EOI or
/// metadata alone, but is not a camera checksum or proof of all entropy semantics.
enum CameraOriginalJPEG {
    static let maximumBytes = 64 * 1024 * 1024
    static let maximumMIMEBytes = 4_096
    static let maximumPixels: Int64 = 64_000_000
    static let maximumSide = 16_384
    static let maximumMarkers = 4_096
    static let maximumScans = 256
    static let decodeLongestSide = 1_024
    static let maximumDecodedSide = 2_048

    enum Failure: Error, Equatable {
        case responseMIME, resourceLimit, structure, unsupported, decode, sourceChanged
    }
    struct Dimensions: Equatable { let width: Int; let height: Int }

    /// The transport has closed this attempt's private file before returning it.
    /// Read a bounded, non-mapped snapshot through a no-follow regular-file FD;
    /// keep the same inode/size/timestamps bound through validation. No rewrite.
    static func validate(fileURL: URL, expectedBytes: Int64, contentTypeValues: [String]) throws {
        try Task.checkCancellation()
        try validateContentTypes(contentTypeValues)
        guard expectedBytes > 0, expectedBytes <= Int64(maximumBytes) else { throw Failure.resourceLimit }
        let descriptor = fileURL.path.withCString { open($0, O_RDONLY | O_NOFOLLOW | O_CLOEXEC) }
        guard descriptor >= 0 else { throw Failure.sourceChanged }
        let handle = FileHandle(fileDescriptor: descriptor, closeOnDealloc: true)
        defer { try? handle.close() }
        var before = stat()
        guard fstat(descriptor, &before) == 0, before.st_mode & mode_t(S_IFMT) == mode_t(S_IFREG),
              before.st_size == expectedBytes else { throw Failure.sourceChanged }
        var data = Data()
        data.reserveCapacity(Int(expectedBytes))
        while data.count < Int(expectedBytes) {
            try Task.checkCancellation()
            let part = try handle.read(upToCount: min(64 * 1024, Int(expectedBytes) - data.count)) ?? Data()
            guard !part.isEmpty else { throw Failure.sourceChanged }
            data.append(part)
        }
        guard (try handle.read(upToCount: 1) ?? Data()).isEmpty else { throw Failure.sourceChanged }
        let dimensions = try validateStructure(data)
        try decode(data, dimensions: dimensions)
        try Task.checkCancellation()
        var after = stat()
        var path = stat()
        guard fstat(descriptor, &after) == 0, lstat(fileURL.path, &path) == 0,
              sameFile(before, after), sameFile(before, path) else { throw Failure.sourceChanged }
    }

    private static func sameFile(_ a: stat, _ b: stat) -> Bool {
        guard a.st_dev == b.st_dev, a.st_ino == b.st_ino, a.st_mode == b.st_mode,
              a.st_size == b.st_size else { return false }
        #if canImport(Darwin)
        return a.st_mtimespec.tv_sec == b.st_mtimespec.tv_sec && a.st_mtimespec.tv_nsec == b.st_mtimespec.tv_nsec
            && a.st_ctimespec.tv_sec == b.st_ctimespec.tv_sec && a.st_ctimespec.tv_nsec == b.st_ctimespec.tv_nsec
        #else
        return a.st_mtim.tv_sec == b.st_mtim.tv_sec && a.st_mtim.tv_nsec == b.st_mtim.tv_nsec
            && a.st_ctim.tv_sec == b.st_ctim.tv_sec && a.st_ctim.tv_nsec == b.st_ctim.tv_nsec
        #endif
    }

    /// Preserve response declarations separately from fresh metadata. Present
    /// empty fields fail closed because a network stack may have stripped OWS.
    /// Multiple exposed values (including identical ones) are never collapsed.
    static func validateContentTypes(_ values: [String]) throws {
        guard values.count <= 1 else { throw Failure.responseMIME }
        guard let value = values.first else { return }
        guard !value.isEmpty, value.utf8.count <= maximumMIMEBytes else { throw Failure.responseMIME }
        let bytes = Array(value.utf8)
        guard bytes.allSatisfy({ (32...126).contains($0) }) else { throw Failure.responseMIME }
        var index = 0
        func spaces() { while index < bytes.count && bytes[index] == 32 { index += 1 } }
        func token() -> String? {
            let start = index
            while index < bytes.count && isToken(bytes[index]) { index += 1 }
            return start < index ? String(decoding: bytes[start..<index], as: UTF8.self) : nil
        }
        spaces()
        guard let type = token(), index < bytes.count, bytes[index] == 47 else { throw Failure.responseMIME }
        index += 1
        guard let subtype = token(), ["image/jpeg", "application/octet-stream"].contains((type + "/" + subtype).lowercased()) else {
            throw Failure.responseMIME
        }
        spaces()
        while index < bytes.count {
            guard bytes[index] == 59 else { throw Failure.responseMIME }
            index += 1
            spaces()
            guard token() != nil else { throw Failure.responseMIME }
            spaces()
            guard index < bytes.count, bytes[index] == 61 else { throw Failure.responseMIME }
            index += 1
            spaces()
            if index < bytes.count && bytes[index] == 34 {
                index += 1
                var closed = false
                while index < bytes.count {
                    let byte = bytes[index]
                    index += 1
                    if byte == 34 { closed = true; break }
                    if byte == 92 {
                        guard index < bytes.count else { throw Failure.responseMIME }
                        index += 1
                    }
                }
                guard closed else { throw Failure.responseMIME }
            } else if token() == nil { throw Failure.responseMIME }
            spaces()
        }
    }

    private static func isToken(_ byte: UInt8) -> Bool {
        (48...57).contains(byte) || (65...90).contains(byte) || (97...122).contains(byte)
            || Array("!#$%&'*+-.^_`|~".utf8).contains(byte)
    }

    static func validateStructure(_ data: Data) throws -> Dimensions {
        guard data.count > 0, data.count <= maximumBytes else { throw Failure.resourceLimit }
        return try data.withUnsafeBytes { raw in
            var parser = Parser(bytes: raw.bindMemory(to: UInt8.self))
            return try parser.parse()
        }
    }

    static func decode(_ data: Data, dimensions: Dimensions) throws {
        try Task.checkCancellation()
        #if canImport(ImageIO)
        let sourceOptions = [kCGImageSourceShouldCache: false] as CFDictionary
        guard let source = CGImageSourceCreateWithData(data as CFData, sourceOptions),
              CGImageSourceGetCount(source) == 1,
              CGImageSourceGetType(source).map({ $0 as String }) == "public.jpeg",
              CGImageSourceGetStatus(source) == .statusComplete,
              CGImageSourceGetStatusAtIndex(source, 0) == .statusComplete,
              let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              (properties[kCGImagePropertyPixelWidth] as? NSNumber)?.intValue == dimensions.width,
              (properties[kCGImagePropertyPixelHeight] as? NSNumber)?.intValue == dimensions.height else { throw Failure.decode }
        // Always derive from the original image, never an embedded EXIF thumbnail.
        let options = [kCGImageSourceCreateThumbnailFromImageAlways: true,
                       kCGImageSourceCreateThumbnailFromImageIfAbsent: false,
                       kCGImageSourceThumbnailMaxPixelSize: decodeLongestSide,
                       kCGImageSourceShouldCacheImmediately: true] as CFDictionary
        guard let image = CGImageSourceCreateThumbnailAtIndex(source, 0, options),
              image.width > 0, image.height > 0,
              image.width <= maximumDecodedSide, image.height <= maximumDecodedSide,
              let colorSpace = CGColorSpace(name: CGColorSpace.sRGB),
              let raster = CGContext(data: nil, width: image.width, height: image.height,
                  bitsPerComponent: 8, bytesPerRow: image.width * 4, space: colorSpace,
                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { throw Failure.decode }
        // Force raster consumption even if ImageIO returns a lazily backed image.
        raster.draw(image, in: CGRect(x: 0, y: 0, width: CGFloat(image.width), height: CGFloat(image.height)))
        guard raster.data != nil, CGImageSourceGetStatus(source) == .statusComplete,
              CGImageSourceGetStatusAtIndex(source, 0) == .statusComplete else { throw Failure.decode }
        #else
        // A header parser alone must never authorize an automatic original.
        throw Failure.unsupported
        #endif
        try Task.checkCancellation()
    }

    private struct Component { let quantization: Int; let horizontal: Int; let vertical: Int; let order: Int }
    private struct Parser {
        let bytes: UnsafeBufferPointer<UInt8>
        var position = 2
        var markers = 1
        var scans = 0
        var frame: UInt8?
        var dimensions: Dimensions?
        var components: [UInt8: Component] = [:]
        var quantization: [Int: Int] = [:]
        var huffman: Set<Int> = []
        var sequentialAC: Set<Int> = []
        var sequentialSeen: Set<UInt8> = []
        var progressive: [UInt8: [Int]] = [:]
        var restartInterval = 0

        mutating func parse() throws -> Dimensions {
            guard bytes.count >= 4, bytes[0] == 0xFF, bytes[1] == 0xD8 else { throw Failure.structure }
            while position < bytes.count {
                try Task.checkCancellation()
                let marker = try nextMarker()
                if marker == 0xD9 {
                    guard position == bytes.count, scans > 0, let dimensions,
                          components.keys.allSatisfy({ frame == 0xC2 ? (progressive[$0]?[0] ?? -1) >= 0 : sequentialSeen.contains($0) }) else {
                        throw Failure.structure
                    }
                    return dimensions
                }
                guard marker != 0xD8, marker != 0x00, !(0xD0...0xD7).contains(marker), marker != 0x01 else {
                    throw Failure.structure
                }
                let length = try word(position)
                guard length >= 2, length <= bytes.count - position else { throw Failure.structure }
                let start = position + 2
                let end = position + length
                position = end
                switch marker {
                case 0xE0...0xEF, 0xFE: break // Bounded APP and COM data, opaque to this policy.
                case 0xDB: try quantizationTables(start, end)
                case 0xC4: try huffmanTables(start, end)
                case 0xDD:
                    guard end - start == 2 else { throw Failure.structure }
                    restartInterval = try word(start)
                case 0xC0, 0xC1, 0xC2: try frameHeader(marker, start, end)
                case 0xDA:
                    try scanHeader(start, end)
                    try entropy()
                default: throw Failure.unsupported // Arithmetic, lossless, DNL and extensions are not admitted.
                }
            }
            throw Failure.structure
        }

        mutating func countMarker() throws {
            markers += 1
            guard markers <= maximumMarkers else { throw Failure.resourceLimit }
        }
        mutating func nextMarker() throws -> UInt8 {
            guard position < bytes.count, bytes[position] == 0xFF else { throw Failure.structure }
            while position < bytes.count && bytes[position] == 0xFF { position += 1 }
            guard position < bytes.count else { throw Failure.structure }
            let marker = bytes[position]
            position += 1
            try countMarker()
            return marker
        }
        func word(_ index: Int) throws -> Int {
            guard index >= 0, index < bytes.count - 1 else { throw Failure.structure }
            return Int(bytes[index]) * 256 + Int(bytes[index + 1])
        }

        mutating func quantizationTables(_ start: Int, _ end: Int) throws {
            var index = start
            guard index < end else { throw Failure.structure }
            while index < end {
                let precision = Int(bytes[index] >> 4)
                let table = Int(bytes[index] & 15)
                index += 1
                guard precision == 0 else { throw Failure.unsupported }
                guard table <= 3, 64 <= end - index else { throw Failure.structure }
                for _ in 0..<64 {
                    let value = Int(bytes[index])
                    guard value > 0 else { throw Failure.structure }
                    index += 1
                }
                quantization[table] = precision
            }
        }

        mutating func huffmanTables(_ start: Int, _ end: Int) throws {
            var index = start
            guard index < end else { throw Failure.structure }
            while index < end {
                guard end - index >= 17 else { throw Failure.structure }
                let key = Int(bytes[index])
                let kind = key >> 4
                guard kind <= 1, key & 15 <= 3 else { throw Failure.structure }
                index += 1
                var count = 0
                var available = 1
                for offset in 0..<16 {
                    let amount = Int(bytes[index + offset])
                    count += amount
                    available = available * 2 - amount
                    // JPEG forbids the all-ones code, hence strictly positive space.
                    guard available > 0 else { throw Failure.structure }
                }
                index += 16
                guard count > 0, count <= 256, count <= end - index else { throw Failure.structure }
                var symbols: Set<UInt8> = []
                var validSequential = true
                for offset in 0..<count {
                    let symbol = bytes[index + offset]
                    guard symbols.insert(symbol).inserted,
                          (kind == 0 ? symbol <= 11 : symbol & 15 <= 10) else { throw Failure.structure }
                    if kind == 1, symbol & 15 == 0, symbol != 0 && symbol != 0xF0 { validSequential = false }
                }
                index += count
                huffman.insert(key)
                if validSequential { sequentialAC.insert(key) } else { sequentialAC.remove(key) }
            }
        }

        mutating func frameHeader(_ marker: UInt8, _ start: Int, _ end: Int) throws {
            guard frame == nil, end - start >= 6 else { throw Failure.structure }
            guard bytes[start] == 8 else { throw Failure.unsupported }
            let height = try word(start + 1)
            let width = try word(start + 3)
            let count = Int(bytes[start + 5])
            guard width > 0, height > 0 else { throw Failure.structure }
            guard width <= maximumSide, height <= maximumSide, Int64(width) * Int64(height) <= maximumPixels else {
                throw Failure.resourceLimit
            }
            guard [1, 3, 4].contains(count) else { throw Failure.unsupported }
            guard end - start == 6 + count * 3 else { throw Failure.structure }
            for offset in 0..<count {
                let index = start + 6 + offset * 3
                let id = bytes[index]
                let horizontal = Int(bytes[index + 1] >> 4)
                let vertical = Int(bytes[index + 1] & 15)
                let table = Int(bytes[index + 2])
                guard components[id] == nil, (1...4).contains(horizontal), (1...4).contains(vertical), table <= 3 else {
                    throw Failure.structure
                }
                components[id] = Component(quantization: table, horizontal: horizontal, vertical: vertical, order: offset)
                progressive[id] = Array(repeating: -1, count: 64)
            }
            frame = marker
            dimensions = Dimensions(width: width, height: height)
        }

        mutating func scanHeader(_ start: Int, _ end: Int) throws {
            guard let frame, end - start >= 4 else { throw Failure.structure }
            scans += 1
            guard scans <= maximumScans else { throw Failure.resourceLimit }
            let count = Int(bytes[start])
            guard count > 0, count <= components.count, count <= 4, end - start == 4 + 2 * count else {
                throw Failure.structure
            }
            let ss = Int(bytes[end - 3]), se = Int(bytes[end - 2])
            let ah = Int(bytes[end - 1] >> 4), al = Int(bytes[end - 1] & 15)
            if frame == 0xC2 {
                guard ss <= se, se <= 63, ah <= 13, al <= 13, ah == 0 || ah == al + 1,
                      (ss == 0 ? se == 0 : count == 1) else { throw Failure.structure }
            } else {
                guard ss == 0, se == 63, ah == 0, al == 0 else { throw Failure.structure }
            }
            var selected: Set<UInt8> = []
            var sampling = 0
            var lastOrder = -1
            for offset in 0..<count {
                let index = start + 1 + 2 * offset
                let id = bytes[index], selectors = Int(bytes[index + 1])
                let dc = selectors >> 4, ac = selectors & 15
                guard let component = components[id], selected.insert(id).inserted, component.order > lastOrder,
                      dc <= 3, ac <= 3, let precision = quantization[component.quantization] else { throw Failure.structure }
                lastOrder = component.order
                guard precision == 0 else { throw Failure.unsupported }
                sampling += component.horizontal * component.vertical
                if count > 1, sampling > 10 { throw Failure.unsupported }
                if frame == 0xC0, dc > 1 || ac > 1 { throw Failure.structure }
                if frame == 0xC2 {
                    guard (ss == 0 ? ac == 0 : dc == 0) else { throw Failure.structure }
                    if ss == 0, ah == 0, !huffman.contains(dc) { throw Failure.structure }
                    if ss > 0, !huffman.contains(0x10 | ac) { throw Failure.structure }
                    var coefficients = progressive[id]!
                    if ss > 0, coefficients[0] < 0 { throw Failure.structure }
                    for coefficient in ss...se {
                        guard (ah == 0 ? coefficients[coefficient] == -1 : coefficients[coefficient] == ah) else { throw Failure.structure }
                        coefficients[coefficient] = al
                    }
                    progressive[id] = coefficients
                } else {
                    guard sequentialSeen.insert(id).inserted, huffman.contains(dc), huffman.contains(0x10 | ac),
                          sequentialAC.contains(0x10 | ac) else { throw Failure.structure }
                }
            }
        }

        mutating func entropy() throws {
            var count = 0
            var sinceRestart = 0
            var nextRestart: UInt8 = 0xD0
            while position < bytes.count {
                if position & 0xFFFF == 0 { try Task.checkCancellation() }
                if bytes[position] != 0xFF {
                    count += 1; sinceRestart += 1; position += 1
                    continue
                }
                let markerStart = position
                position += 1
                guard position < bytes.count else { throw Failure.structure }
                if bytes[position] == 0x00 {
                    count += 1; sinceRestart += 1; position += 1
                    continue
                }
                while position < bytes.count && bytes[position] == 0xFF { position += 1 }
                guard position < bytes.count, bytes[position] != 0x00 else { throw Failure.structure }
                let marker = bytes[position]
                if (0xD0...0xD7).contains(marker) {
                    guard restartInterval > 0, sinceRestart > 0, marker == nextRestart else { throw Failure.structure }
                    try countMarker()
                    nextRestart = marker == 0xD7 ? 0xD0 : marker + 1
                    sinceRestart = 0
                    position += 1
                } else {
                    guard count > 0, sinceRestart > 0 else { throw Failure.structure }
                    position = markerStart
                    return
                }
            }
            throw Failure.structure
        }
    }
}
