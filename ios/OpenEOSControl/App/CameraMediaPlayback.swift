import AVFoundation
import Combine
import Foundation
import OpenEOSCore
import UniformTypeIdentifiers

@MainActor
final class CameraMediaPlayback: ObservableObject {
    @Published private(set) var player: AVPlayer
    @Published private(set) var failure: CameraMediaPlaybackFailure?
    @Published private(set) var isPreparingFallback = false

    private let item: CameraMediaItem
    private let session: CameraSession
    private let resourceLoader: CameraMediaResourceLoader
    private var itemCancellables = Set<AnyCancellable>()
    private var fallbackTask: Task<Void, Never>?
    private var fallbackFileURL: URL?
    private var hasStartedFallback = false
    private var isClosed = false

    init(item: CameraMediaItem, session: CameraSession) {
        self.item = item
        self.session = session
        Self.removeStalePlaybackDirectories()
        resourceLoader = CameraMediaResourceLoader(item: item) { offset, length in
            try await session.openMediaStream(item, offset: offset, length: length)
        }
        let asset = AVURLAsset(url: resourceLoader.assetURL)
        asset.resourceLoader.setDelegate(resourceLoader, queue: resourceLoader.delegateQueue)
        let playerItem = AVPlayerItem(asset: asset)
        player = AVPlayer(playerItem: playerItem)
        observe(playerItem)
        resourceLoader.onError = { [weak self] error in
            Task { @MainActor in
                self?.handlePlaybackFailure(error)
            }
        }
    }

    func play() {
        guard !isClosed else { return }
        player.play()
    }

    func pause() {
        player.pause()
    }

    func close() {
        guard !isClosed else { return }
        isClosed = true
        fallbackTask?.cancel()
        fallbackTask = nil
        itemCancellables.removeAll()
        resourceLoader.invalidate()
        player.pause()
        player.replaceCurrentItem(with: nil)
        removeFallbackFile()
    }

    private func observe(_ item: AVPlayerItem) {
        itemCancellables.removeAll()
        item.publisher(for: \AVPlayerItem.status)
            .receive(on: DispatchQueue.main)
            .sink { [weak self, weak item] status in
                guard let self, let item else { return }
                switch status {
                case .readyToPlay:
                    self.isPreparingFallback = false
                case .failed:
                    self.handlePlaybackFailure(item.error)
                default:
                    break
                }
            }
            .store(in: &itemCancellables)

        NotificationCenter.default.publisher(
            for: AVPlayerItem.failedToPlayToEndTimeNotification,
            object: item
        )
        .receive(on: DispatchQueue.main)
        .sink { [weak self] notification in
            let error = notification.userInfo?[AVPlayerItemFailedToPlayToEndTimeErrorKey] as? Error
            self?.handlePlaybackFailure(error)
        }
        .store(in: &itemCancellables)
    }

    private func handlePlaybackFailure(_ error: Error?) {
        guard !isClosed, !CameraMediaPlaybackValidation.isCancellation(error) else { return }
        let classifiedFailure = CameraMediaPlaybackValidation.failure(for: error)
        if fallbackFileURL != nil {
            isPreparingFallback = false
            failure = classifiedFailure
            return
        }
        if !CameraMediaPlaybackValidation.shouldPrepareFallback(for: classifiedFailure) {
            failure = classifiedFailure
            return
        }
        guard !hasStartedFallback else { return }
        hasStartedFallback = true
        isPreparingFallback = true
        fallbackTask = Task { [weak self] in
            await self?.prepareFallback()
        }
    }

    private func prepareFallback() async {
        var retainedDirectory = false
        var directory: URL?
        defer {
            if !retainedDirectory, let directory {
                try? FileManager.default.removeItem(at: directory)
            }
        }
        do {
            if let sizeBytes = item.sizeBytes, sizeBytes > 0,
               let availableCapacity = Self.availablePlaybackCapacity(),
               !CameraMediaPlaybackValidation.hasFallbackCapacity(
                   mediaBytes: sizeBytes,
                   availableCapacity: availableCapacity
               ) {
                throw CameraMediaPlaybackError.insufficientStorage(
                    required: sizeBytes,
                    available: availableCapacity
                )
            }
            let createdDirectory = FileManager.default.temporaryDirectory
                .appendingPathComponent("OpenEOSControl", isDirectory: true)
                .appendingPathComponent("VideoPlayback", isDirectory: true)
                .appendingPathComponent(UUID().uuidString, isDirectory: true)
            directory = createdDirectory
            try FileManager.default.createDirectory(at: createdDirectory, withIntermediateDirectories: true)
            let destination = createdDirectory.appendingPathComponent(safeFilename)
            let result = try await session.downloadMedia(item, to: destination)
            try Task.checkCancellation()
            if let expected = item.sizeBytes, expected > 0, result.bytesTransferred != expected {
                throw CameraMediaPlaybackError.incompleteFile(
                    expected: expected,
                    actual: result.bytesTransferred
                )
            }
            guard !isClosed else {
                return
            }
            fallbackFileURL = result.fileURL
            retainedDirectory = true
            replacePlayer(with: result.fileURL)
            isPreparingFallback = false
        } catch {
            if !CameraMediaPlaybackValidation.isCancellation(error), !isClosed {
                isPreparingFallback = false
                failure = CameraMediaPlaybackValidation.failure(for: error)
            }
        }
        fallbackTask = nil
    }

    private func replacePlayer(with url: URL) {
        itemCancellables.removeAll()
        resourceLoader.invalidate()
        player.pause()
        player.replaceCurrentItem(with: nil)
        player = AVPlayer(url: url)
        if let currentItem = player.currentItem {
            observe(currentItem)
        }
        player.play()
    }

    private var safeFilename: String {
        let name = URL(fileURLWithPath: item.name).lastPathComponent
        return name.isEmpty ? "media-\(item.id)" : name
    }

    private static func availablePlaybackCapacity() -> Int64? {
        let keys: Set<URLResourceKey> = [.volumeAvailableCapacityForImportantUsageKey]
        guard let values = try? FileManager.default.temporaryDirectory.resourceValues(forKeys: keys) else {
            return nil
        }
        return values.volumeAvailableCapacityForImportantUsage
    }

    private static func removeStalePlaybackDirectories() {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent("OpenEOSControl", isDirectory: true)
            .appendingPathComponent("VideoPlayback", isDirectory: true)
        guard let children = try? FileManager.default.contentsOfDirectory(
            at: root,
            includingPropertiesForKeys: nil
        ) else { return }
        for child in children {
            try? FileManager.default.removeItem(at: child)
        }
    }

    private func removeFallbackFile() {
        guard let fallbackFileURL else { return }
        let directory = fallbackFileURL.deletingLastPathComponent()
        try? FileManager.default.removeItem(at: directory)
        self.fallbackFileURL = nil
    }
}

enum CameraMediaPlaybackFailure: Equatable {
    case unsupportedFormat
    case incompleteRange
    case storageUnavailable
    case transport
}

enum CameraMediaPlaybackValidation {
    static func isCancellation(_ error: Error?) -> Bool {
        guard let error else { return false }
        if error is CancellationError { return true }
        return (error as? URLError)?.code == .cancelled
    }

    static func rangeSupportConfirmed(
        statusCode: Int,
        responseRangeStart: Int64,
        requestedOffset: Int64
    ) -> Bool {
        statusCode == 206 && responseRangeStart == requestedOffset
    }

    static func expectedBytes(
        requestedLength: Int64?,
        totalBytes: Int64?,
        requestedOffset: Int64
    ) -> Int64? {
        if let requestedLength, requestedLength >= 0 {
            return requestedLength
        }
        guard let totalBytes, totalBytes >= requestedOffset else { return nil }
        return totalBytes - requestedOffset
    }

    static func isComplete(
        deliveredBytes: Int64,
        requestedLength: Int64?,
        totalBytes: Int64?,
        requestedOffset: Int64
    ) -> Bool {
        guard let expected = expectedBytes(
            requestedLength: requestedLength,
            totalBytes: totalBytes,
            requestedOffset: requestedOffset
        ) else { return false }
        return deliveredBytes == expected
    }

    static func failure(for error: Error?) -> CameraMediaPlaybackFailure {
        if let error = error as? CameraMediaPlaybackError,
           case .incompleteRange = error {
            return .incompleteRange
        }
        if let error = error as? CameraMediaPlaybackError,
           case .incompleteFile = error {
            return .incompleteRange
        }
        if let error = error as? CameraMediaPlaybackError,
           case .insufficientStorage = error {
            return .storageUnavailable
        }
        if let error, isOutOfSpace(error) {
            return .storageUnavailable
        }
        if let nsError = error as NSError?, nsError.domain == AVFoundationErrorDomain {
            switch AVError.Code(rawValue: nsError.code) {
            case .fileFormatNotRecognized, .fileFailedToParse, .decoderNotFound,
                 .decodeFailed, .formatUnsupported, .incompatibleAsset:
                return .unsupportedFormat
            default:
                break
            }
        }
        return .transport
    }

    static func shouldPrepareFallback(for failure: CameraMediaPlaybackFailure) -> Bool {
        failure != .unsupportedFormat && failure != .storageUnavailable
    }

    static func hasFallbackCapacity(mediaBytes: Int64, availableCapacity: Int64) -> Bool {
        guard mediaBytes > 0, availableCapacity > 0 else { return false }
        let reserve = min(128 * 1024 * 1024, availableCapacity / 10)
        return mediaBytes <= availableCapacity - reserve
    }

    private static func isOutOfSpace(_ error: Error) -> Bool {
        var current: NSError? = error as NSError
        for _ in 0..<8 {
            guard let value = current else { return false }
            if value.domain == NSCocoaErrorDomain,
               value.code == CocoaError.fileWriteOutOfSpace.rawValue {
                return true
            }
            if value.domain == NSPOSIXErrorDomain,
               value.code == POSIXErrorCode.ENOSPC.rawValue {
                return true
            }
            current = value.userInfo[NSUnderlyingErrorKey] as? NSError
        }
        return false
    }
}

private final class CameraMediaResourceLoader: NSObject, AVAssetResourceLoaderDelegate, @unchecked Sendable {
    typealias StreamProvider = @Sendable (Int64, Int64?) async throws -> CameraMediaStreamResponse

    let assetURL: URL
    let delegateQueue = DispatchQueue(label: "dev.openeos.control.media-resource-loader")
    var onError: (@Sendable (Error) -> Void)?

    private let item: CameraMediaItem
    private let streamProvider: StreamProvider
    private let lock = NSLock()
    private var tasks: [ObjectIdentifier: Task<Void, Never>] = [:]

    init(item: CameraMediaItem, streamProvider: @escaping StreamProvider) {
        self.item = item
        self.streamProvider = streamProvider
        assetURL = URL(string: "open-eos-media://stream/\(UUID().uuidString)/\(item.name.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? "media")")!
    }

    func resourceLoader(
        _ resourceLoader: AVAssetResourceLoader,
        shouldWaitForLoadingOfRequestedResource loadingRequest: AVAssetResourceLoadingRequest
    ) -> Bool {
        let identifier = ObjectIdentifier(loadingRequest)
        let task = Task { [weak self] in
            guard let self else { return }
            do {
                try await self.fulfill(loadingRequest)
            } catch {
                if CameraMediaPlaybackValidation.isCancellation(error) {
                    loadingRequest.finishLoading()
                } else {
                    self.onError?(error)
                    loadingRequest.finishLoading(with: error)
                }
            }
            self.removeTask(identifier)
        }
        synchronized { tasks[identifier] = task }
        return true
    }

    func resourceLoader(
        _ resourceLoader: AVAssetResourceLoader,
        didCancel loadingRequest: AVAssetResourceLoadingRequest
    ) {
        let identifier = ObjectIdentifier(loadingRequest)
        let task = synchronized { tasks.removeValue(forKey: identifier) }
        task?.cancel()
    }

    func invalidate() {
        let pending = synchronized {
            let pending = Array(tasks.values)
            tasks.removeAll()
            return pending
        }
        pending.forEach { $0.cancel() }
    }

    private func fulfill(_ loadingRequest: AVAssetResourceLoadingRequest) async throws {
        let dataRequest = loadingRequest.dataRequest
        let requestedOffset = max(0, dataRequest?.currentOffset ?? dataRequest?.requestedOffset ?? 0)
        let requestedLength: Int64? = {
            guard let dataRequest, !dataRequest.requestsAllDataToEndOfResource else { return nil }
            return max(1, Int64(dataRequest.requestedLength))
        }()
        let streamLength: Int64? = dataRequest == nil ? 1 : requestedLength
        let stream = try await streamProvider(requestedOffset, streamLength)
        defer { stream.cancel() }

        if let information = loadingRequest.contentInformationRequest {
            let type = stream.contentType.flatMap { UTType(mimeType: $0) }
                ?? UTType(filenameExtension: item.name.pathExtension)
            information.contentType = type?.identifier
            if let totalBytes = stream.totalBytes ?? item.sizeBytes, totalBytes > 0 {
                information.contentLength = totalBytes
            }
            information.isByteRangeAccessSupported = CameraMediaPlaybackValidation.rangeSupportConfirmed(
                statusCode: stream.statusCode,
                responseRangeStart: stream.rangeStart,
                requestedOffset: requestedOffset
            )
        }
        guard let dataRequest else {
            loadingRequest.finishLoading()
            return
        }
        guard stream.rangeStart <= requestedOffset else {
            throw CameraMediaPlaybackError.invalidRange
        }
        guard CameraMediaPlaybackValidation.expectedBytes(
            requestedLength: requestedLength,
            totalBytes: stream.totalBytes ?? item.sizeBytes,
            requestedOffset: requestedOffset
        ) != nil else {
            throw CameraMediaPlaybackError.incompleteRange
        }

        var discardBytes = requestedOffset - stream.rangeStart
        var remaining = requestedLength
        var deliveredBytes: Int64 = 0
        let totalBytes = stream.totalBytes ?? item.sizeBytes
        for try await chunk in stream.chunks {
            try Task.checkCancellation()
            var output = chunk
            if discardBytes > 0 {
                let discarded = min(discardBytes, Int64(output.count))
                output.removeFirst(Int(discarded))
                discardBytes -= discarded
            }
            if let remaining {
                guard remaining > 0 else { break }
                output = Data(output.prefix(Int(min(remaining, Int64(output.count)))))
            }
            if !output.isEmpty {
                dataRequest.respond(with: output)
                deliveredBytes += Int64(output.count)
                if let currentRemaining = remaining {
                    remaining = currentRemaining - Int64(output.count)
                }
            }
            if remaining == 0 { break }
        }
        guard discardBytes == 0,
              CameraMediaPlaybackValidation.isComplete(
                  deliveredBytes: deliveredBytes,
                  requestedLength: requestedLength,
                  totalBytes: totalBytes,
                  requestedOffset: requestedOffset
              ) else {
            throw CameraMediaPlaybackError.incompleteRange
        }
        loadingRequest.finishLoading()
    }

    private func removeTask(_ identifier: ObjectIdentifier) {
        synchronized { tasks.removeValue(forKey: identifier) }
    }

    private func synchronized<T>(_ operation: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return operation()
    }
}

private enum CameraMediaPlaybackError: LocalizedError {
    case invalidRange
    case incompleteRange
    case incompleteFile(expected: Int64, actual: Int64)
    case insufficientStorage(required: Int64, available: Int64)

    var errorDescription: String? {
        switch self {
        case .invalidRange:
            return "The camera returned an invalid media byte range."
        case .incompleteRange:
            return "The camera returned an incomplete media byte range."
        case let .incompleteFile(expected, actual):
            return "The camera returned an incomplete media file (expected \(expected), received \(actual))."
        case let .insufficientStorage(required, available):
            return "Video playback needs \(required) bytes, but only \(available) bytes are available."
        }
    }
}

private extension String {
    var pathExtension: String {
        (self as NSString).pathExtension
    }
}
