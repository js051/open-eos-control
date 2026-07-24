import AVFoundation
import CoreMedia
import Darwin
import Foundation
import Network
import OpenEOSCore
import UIKit

enum IOSCcapiRTPEvent: Sendable {
    case frame(encodedBytes: Int, at: Date)
    case videoSize(width: Int32, height: Int32)
    case failed(String)
}

enum CameraRTPNetworkAddress {
    static func destinationAddress(cameraURL: String) -> String? {
        guard let cameraHost = URLComponents(string: cameraURL)?.host,
              ipv4Value(cameraHost) != nil else { return nil }

        var interfaces: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&interfaces) == 0, let first = interfaces else { return nil }
        defer { freeifaddrs(interfaces) }

        var current: UnsafeMutablePointer<ifaddrs>? = first
        while let interface = current {
            defer { current = interface.pointee.ifa_next }
            let flags = Int32(interface.pointee.ifa_flags)
            guard flags & IFF_UP != 0,
                  flags & IFF_LOOPBACK == 0,
                  let address = interface.pointee.ifa_addr,
                  let netmask = interface.pointee.ifa_netmask,
                  address.pointee.sa_family == UInt8(AF_INET),
                  netmask.pointee.sa_family == UInt8(AF_INET),
                  let localAddress = numericHost(address),
                  let mask = numericHost(netmask),
                  sameIPv4Subnet(cameraAddress: cameraHost, localAddress: localAddress, netmask: mask) else {
                continue
            }
            return localAddress
        }
        return nil
    }

    static func sameIPv4Subnet(cameraAddress: String, localAddress: String, netmask: String) -> Bool {
        guard let camera = ipv4Value(cameraAddress),
              let local = ipv4Value(localAddress),
              let mask = ipv4Value(netmask) else { return false }
        return camera & mask == local & mask
    }

    private static func ipv4Value(_ value: String) -> UInt32? {
        var address = in_addr()
        guard inet_pton(AF_INET, value, &address) == 1 else { return nil }
        return address.s_addr
    }

    private static func numericHost(_ address: UnsafePointer<sockaddr>) -> String? {
        var buffer = [CChar](repeating: 0, count: Int(NI_MAXHOST))
        let result = getnameinfo(
            address,
            socklen_t(address.pointee.sa_len),
            &buffer,
            socklen_t(buffer.count),
            nil,
            0,
            NI_NUMERICHOST
        )
        guard result == 0 else { return nil }
        return String(cString: buffer)
    }
}

final class IOSCcapiRTPController: CCAPIRTPSessionFactory, @unchecked Sendable {
    private let handlerLock = NSLock()
    private var eventHandler: (@Sendable (IOSCcapiRTPEvent) -> Void)?

    @MainActor private weak var displayLayer: AVSampleBufferDisplayLayer?
    @MainActor private var latestSPS: Data?
    @MainActor private var latestPPS: Data?
    @MainActor private var formatDescription: CMVideoFormatDescription?
    @MainActor private var needsKeyFrame = true
    @MainActor private var renderingEnabled = true

    func makeSession(
        description: CCAPIRTPSessionDescription,
        destinationAddress: String
    ) async throws -> any CCAPIRTPSession {
        IOSCcapiRTPSession(
            description: description,
            destinationAddress: destinationAddress,
            sink: self
        )
    }

    func setEventHandler(_ handler: @escaping @Sendable (IOSCcapiRTPEvent) -> Void) {
        handlerLock.synchronized { eventHandler = handler }
    }

    @MainActor
    func attach(_ layer: AVSampleBufferDisplayLayer) {
        displayLayer = layer
        layer.backgroundColor = UIColor.black.cgColor
        layer.videoGravity = .resizeAspect
        resetRenderer(removingImage: true)
    }

    @MainActor
    func detach(_ layer: AVSampleBufferDisplayLayer) {
        guard displayLayer === layer else { return }
        resetRenderer(removingImage: true)
        displayLayer = nil
    }

    @MainActor
    func setRenderingEnabled(_ enabled: Bool) {
        renderingEnabled = enabled
    }

    @MainActor
    fileprivate func enqueue(
        _ accessUnit: CCAPIH264AccessUnit,
        sequenceParameterSet: Data?,
        pictureParameterSet: Data?
    ) {
        guard renderingEnabled, let displayLayer else { return }
        let parameterSetsChanged = sequenceParameterSet != nil && pictureParameterSet != nil &&
            (sequenceParameterSet != latestSPS || pictureParameterSet != latestPPS)
        if parameterSetsChanged {
            latestSPS = sequenceParameterSet
            latestPPS = pictureParameterSet
            formatDescription = nil
            resetRenderer(removingImage: false)
        }
        if formatDescription == nil, let latestSPS, let latestPPS {
            do {
                formatDescription = try Self.makeFormatDescription(sps: latestSPS, pps: latestPPS)
                if let formatDescription {
                    let dimensions = CMVideoFormatDescriptionGetDimensions(formatDescription)
                    if dimensions.width > 0, dimensions.height > 0 {
                        emit(.videoSize(width: dimensions.width, height: dimensions.height))
                    }
                }
            } catch {
                emit(.failed("Canon H.264 format setup failed: \(error.localizedDescription)"))
                return
            }
        }
        guard let formatDescription else { return }

        let renderer = displayLayer.sampleBufferRenderer
        if renderer.status == .failed || renderer.requiresFlushToResumeDecoding {
            resetRenderer(removingImage: false)
        }
        if needsKeyFrame && !accessUnit.keyFrame { return }
        do {
            let sampleBuffer = try Self.makeSampleBuffer(
                accessUnit: accessUnit,
                formatDescription: formatDescription
            )
            renderer.enqueue(sampleBuffer)
            needsKeyFrame = false
            emit(.frame(encodedBytes: accessUnit.encodedByteCount, at: Date()))
        } catch {
            resetRenderer(removingImage: false)
            emit(.failed("Canon H.264 sample creation failed: \(error.localizedDescription)"))
        }
    }

    fileprivate func emit(_ event: IOSCcapiRTPEvent) {
        let handler = handlerLock.synchronized { eventHandler }
        handler?(event)
    }

    @MainActor
    private func resetRenderer(removingImage: Bool) {
        displayLayer?.sampleBufferRenderer.flush(
            removingDisplayedImage: removingImage,
            completionHandler: nil
        )
        needsKeyFrame = true
    }

    private static func makeFormatDescription(sps: Data, pps: Data) throws -> CMVideoFormatDescription {
        var result: CMFormatDescription?
        let status = sps.withUnsafeBytes { spsBytes in
            pps.withUnsafeBytes { ppsBytes in
                guard let spsBase = spsBytes.baseAddress?.assumingMemoryBound(to: UInt8.self),
                      let ppsBase = ppsBytes.baseAddress?.assumingMemoryBound(to: UInt8.self) else {
                    return kCMFormatDescriptionError_InvalidParameter
                }
                var pointers = [spsBase, ppsBase]
                var sizes = [sps.count, pps.count]
                return pointers.withUnsafeBufferPointer { pointerBuffer in
                    sizes.withUnsafeBufferPointer { sizeBuffer in
                        CMVideoFormatDescriptionCreateFromH264ParameterSets(
                            allocator: kCFAllocatorDefault,
                            parameterSetCount: 2,
                            parameterSetPointers: pointerBuffer.baseAddress!,
                            parameterSetSizes: sizeBuffer.baseAddress!,
                            nalUnitHeaderLength: 4,
                            formatDescriptionOut: &result
                        )
                    }
                }
            }
        }
        guard status == noErr, let result else { throw statusError("H.264 format", status) }
        return result
    }

    private static func makeSampleBuffer(
        accessUnit: CCAPIH264AccessUnit,
        formatDescription: CMVideoFormatDescription
    ) throws -> CMSampleBuffer {
        var encoded = Data()
        encoded.reserveCapacity(accessUnit.encodedByteCount + accessUnit.nalUnits.count * 4)
        for nal in accessUnit.nalUnits {
            var length = UInt32(nal.count).bigEndian
            withUnsafeBytes(of: &length) { encoded.append(contentsOf: $0) }
            encoded.append(nal)
        }
        guard !encoded.isEmpty else { throw statusError("empty H.264 access unit", -1) }

        var blockBuffer: CMBlockBuffer?
        var status = CMBlockBufferCreateWithMemoryBlock(
            allocator: kCFAllocatorDefault,
            memoryBlock: nil,
            blockLength: encoded.count,
            blockAllocator: kCFAllocatorDefault,
            customBlockSource: nil,
            offsetToData: 0,
            dataLength: encoded.count,
            flags: 0,
            blockBufferOut: &blockBuffer
        )
        guard status == kCMBlockBufferNoErr, let blockBuffer else {
            throw statusError("H.264 block buffer", status)
        }
        status = encoded.withUnsafeBytes { bytes in
            guard let baseAddress = bytes.baseAddress else { return kCMBlockBufferBadPointerParameterErr }
            return CMBlockBufferReplaceDataBytes(
                with: baseAddress,
                blockBuffer: blockBuffer,
                offsetIntoDestination: 0,
                dataLength: encoded.count
            )
        }
        guard status == kCMBlockBufferNoErr else { throw statusError("H.264 block copy", status) }

        var timing = CMSampleTimingInfo(
            duration: .invalid,
            presentationTimeStamp: .invalid,
            decodeTimeStamp: .invalid
        )
        var sampleSize = encoded.count
        var sampleBuffer: CMSampleBuffer?
        status = CMSampleBufferCreateReady(
            allocator: kCFAllocatorDefault,
            dataBuffer: blockBuffer,
            formatDescription: formatDescription,
            sampleCount: 1,
            sampleTimingEntryCount: 1,
            sampleTimingArray: &timing,
            sampleSizeEntryCount: 1,
            sampleSizeArray: &sampleSize,
            sampleBufferOut: &sampleBuffer
        )
        guard status == noErr, let sampleBuffer else { throw statusError("H.264 sample buffer", status) }
        if let attachments = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, createIfNecessary: true) as? NSArray,
           let attachment = attachments.firstObject as? NSMutableDictionary {
            attachment[kCMSampleAttachmentKey_DisplayImmediately] = kCFBooleanTrue
            if !accessUnit.keyFrame { attachment[kCMSampleAttachmentKey_NotSync] = kCFBooleanTrue }
        }
        return sampleBuffer
    }

    private static func statusError(_ operation: String, _ status: OSStatus) -> NSError {
        NSError(
            domain: "OpenEOSControl.CCAPIRTP",
            code: Int(status),
            userInfo: [NSLocalizedDescriptionKey: "\(operation) returned OSStatus \(status)."]
        )
    }
}

private final class IOSCcapiRTPSession: CCAPIRTPSession, @unchecked Sendable {
    let sourceURL: URL

    private let description: CCAPIRTPSessionDescription
    private let destinationAddress: String
    private let sink: IOSCcapiRTPController
    private let queue = DispatchQueue(label: "dev.openeos.control.ccapi-rtp", qos: .userInteractive)
    private let stateLock = NSLock()
    private var listener: NWListener?
    private var connections: [NWConnection] = []
    private var targetFPS = 30
    private var closed = false
    private var lastSubmittedAt: UInt64 = 0
    private var latestSPS: Data?
    private var latestPPS: Data?
    private lazy var depacketizer = CCAPIH264RTPDepacketizer(payloadType: description.video.payloadType)

    init(
        description: CCAPIRTPSessionDescription,
        destinationAddress: String,
        sink: IOSCcapiRTPController
    ) {
        self.description = description
        self.destinationAddress = destinationAddress
        self.sink = sink
        sourceURL = URL(string: "rtp://\(destinationAddress):\(description.video.port)")!
    }

    func start() async throws {
        let port = NWEndpoint.Port(rawValue: description.video.port)!
        let parameters = NWParameters.udp
        parameters.requiredInterfaceType = .wifi
        parameters.requiredLocalEndpoint = .hostPort(
            host: NWEndpoint.Host(destinationAddress),
            port: port
        )
        parameters.allowLocalEndpointReuse = true
        let listener = try NWListener(using: parameters, on: port)
        let gate = ListenerReadyGate()
        stateLock.synchronized {
            guard !closed else { return }
            self.listener = listener
        }
        guard !stateLock.synchronized({ closed }) else {
            listener.cancel()
            throw CancellationError()
        }

        listener.stateUpdateHandler = { [weak self] state in
            switch state {
            case .ready:
                gate.resume()
            case let .failed(error):
                gate.resume(throwing: error)
                self?.sink.emit(.failed("Canon RTP listener failed: \(error.localizedDescription)"))
            case .cancelled:
                gate.resume(throwing: CancellationError())
            default:
                break
            }
        }
        listener.newConnectionHandler = { [weak self] connection in
            self?.accept(connection)
        }
        queue.asyncAfter(deadline: .now() + 5) {
            gate.resume(
                throwing: NSError(
                    domain: "OpenEOSControl.CCAPIRTP",
                    code: -2,
                    userInfo: [NSLocalizedDescriptionKey: "Timed out while binding the Canon RTP UDP listener."]
                )
            )
        }
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { continuation in
                gate.install(continuation)
                listener.start(queue: queue)
            }
        } onCancel: {
            self.closeSynchronously()
        }
    }

    func setTargetFPS(_ fps: Int) async {
        stateLock.synchronized { targetFPS = min(max(fps, 1), 30) }
    }

    func close() async {
        closeSynchronously()
    }

    private func closeSynchronously() {
        let resources = stateLock.synchronized { () -> (NWListener?, [NWConnection]) in
            if closed { return (nil, []) }
            closed = true
            let resources = (listener, connections)
            listener = nil
            connections = []
            return resources
        }
        resources.0?.cancel()
        resources.1.forEach { $0.cancel() }
    }

    private func accept(_ connection: NWConnection) {
        guard !stateLock.synchronized({ closed }) else {
            connection.cancel()
            return
        }
        stateLock.synchronized { connections.append(connection) }
        connection.stateUpdateHandler = { [weak self, weak connection] state in
            guard let self, let connection else { return }
            switch state {
            case .ready:
                self.receive(on: connection)
            case let .failed(error):
                if !self.stateLock.synchronized({ self.closed }) {
                    self.sink.emit(.failed("Canon RTP receive failed: \(error.localizedDescription)"))
                }
            default:
                break
            }
        }
        connection.start(queue: queue)
    }

    private func receive(on connection: NWConnection) {
        connection.receiveMessage { [weak self, weak connection] data, _, _, error in
            guard let self, let connection else { return }
            if let data, data.count <= 65_535, let accessUnit = self.depacketizer.accept(data) {
                if let sps = accessUnit.sequenceParameterSet { self.latestSPS = sps }
                if let pps = accessUnit.pictureParameterSet { self.latestPPS = pps }
                let now = DispatchTime.now().uptimeNanoseconds
                let targetFPS = self.stateLock.synchronized { self.targetFPS }
                let interval = 1_000_000_000 / UInt64(max(targetFPS, 1))
                if accessUnit.keyFrame || self.lastSubmittedAt == 0 || now - self.lastSubmittedAt >= interval {
                    self.lastSubmittedAt = now
                    let latestSPS = self.latestSPS
                    let latestPPS = self.latestPPS
                    Task { @MainActor [weak self] in
                        self?.sink.enqueue(
                            accessUnit,
                            sequenceParameterSet: latestSPS,
                            pictureParameterSet: latestPPS
                        )
                    }
                }
            }
            if let error {
                if !self.stateLock.synchronized({ self.closed }) {
                    self.sink.emit(.failed("Canon RTP receive failed: \(error.localizedDescription)"))
                }
                return
            }
            if !self.stateLock.synchronized({ self.closed }) { self.receive(on: connection) }
        }
    }
}

private final class ListenerReadyGate: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<Void, Error>?
    private var result: Result<Void, Error>?

    func install(_ continuation: CheckedContinuation<Void, Error>) {
        let pending = lock.synchronized { () -> Result<Void, Error>? in
            if let result { return result }
            self.continuation = continuation
            return nil
        }
        pending?.resume(continuation)
    }

    func resume(throwing error: Error? = nil) {
        let value: Result<Void, Error> = error.map(Result.failure) ?? .success(())
        let continuation = lock.synchronized { () -> CheckedContinuation<Void, Error>? in
            guard result == nil else { return nil }
            result = value
            let continuation = self.continuation
            self.continuation = nil
            return continuation
        }
        continuation.map { value.resume($0) }
    }
}

private extension Result where Success == Void, Failure == Error {
    func resume(_ continuation: CheckedContinuation<Void, Error>) {
        switch self {
        case .success: continuation.resume()
        case let .failure(error): continuation.resume(throwing: error)
        }
    }
}

private extension NSLock {
    func synchronized<T>(_ operation: () throws -> T) rethrows -> T {
        lock()
        defer { unlock() }
        return try operation()
    }
}
