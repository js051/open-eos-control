import AVFoundation
import Combine
import Foundation
import OpenEOSCore
import UniformTypeIdentifiers

@MainActor
final class CameraMediaPlayback: ObservableObject {
    let player: AVPlayer
    @Published private(set) var errorMessage: String?

    private let resourceLoader: CameraMediaResourceLoader

    init(item: CameraMediaItem, session: CameraSession) {
        resourceLoader = CameraMediaResourceLoader(item: item) { offset, length in
            try await session.openMediaStream(item, offset: offset, length: length)
        }
        let asset = AVURLAsset(url: resourceLoader.assetURL)
        asset.resourceLoader.setDelegate(resourceLoader, queue: resourceLoader.delegateQueue)
        player = AVPlayer(playerItem: AVPlayerItem(asset: asset))
        resourceLoader.onError = { [weak self] error in
            Task { @MainActor in
                self?.errorMessage = error.localizedDescription
            }
        }
    }

    func play() {
        player.play()
    }

    func pause() {
        player.pause()
    }

    func close() {
        player.pause()
        player.replaceCurrentItem(with: nil)
        resourceLoader.invalidate()
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
            } catch is CancellationError {
                loadingRequest.finishLoading()
            } catch {
                self.onError?(error)
                loadingRequest.finishLoading(with: error)
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
            information.contentLength = stream.totalBytes ?? item.sizeBytes ?? 0
            information.isByteRangeAccessSupported = true
        }
        guard let dataRequest else {
            loadingRequest.finishLoading()
            return
        }
        guard stream.rangeStart <= requestedOffset else {
            throw CameraMediaPlaybackError.invalidRange
        }

        var discardBytes = requestedOffset - stream.rangeStart
        var remaining = requestedLength
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
                if let currentRemaining = remaining {
                    remaining = currentRemaining - Int64(output.count)
                }
            }
            if remaining == 0 { break }
        }
        guard discardBytes == 0 else { throw CameraMediaPlaybackError.invalidRange }
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

    var errorDescription: String? {
        "The camera returned an invalid media byte range."
    }
}

private extension String {
    var pathExtension: String {
        (self as NSString).pathExtension
    }
}
