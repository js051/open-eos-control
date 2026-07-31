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
    case audioStatus(IOSCcapiRTPAudioStatus)
    case failed(String)
}

struct IOSCcapiRTPAudioStatus: Equatable, Sendable {
    var advertised = false
    var available = false
    var enabled = false
    var codec: String?
    var rtpPort: UInt16?
    var rtpClockRate: Int?
    var channels: Int?
    var packetsReceived = 0
    var accessUnitsReceived = 0
    var decodedAccessUnits = 0
    var playedSampleFrames = 0
    var droppedAccessUnits = 0
    var lastPacketAt: Date?
    var lastPCMAt: Date?
    var reason: String?
    var error: String?

    static let inactive = IOSCcapiRTPAudioStatus()
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
    private let audioStateLock = NSLock()
    private let audioQueue = DispatchQueue(label: "dev.openeos.control.ccapi-rtp-audio", qos: .userInitiated)
    private var audioStatus = IOSCcapiRTPAudioStatus.inactive
    private var audioRequested = false
    private var applicationActive = true
    private var monitoringActive = true
    private var queuedAudioAccessUnits = 0
    private var lastAudioStatusPublishedAt = Date.distantPast
    private var activeAudioSessionID: UUID?
    private let audioPipeline = IOSCcapiRTPAudioPipeline()

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
        let sessionID = UUID()
        configureAudio(
            description.audio,
            support: description.latmAudioSupport,
            sessionID: sessionID
        )
        return IOSCcapiRTPSession(
            description: description,
            destinationAddress: destinationAddress,
            sink: self,
            sessionID: sessionID
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
        setMonitoringActive(enabled)
    }

    func setAudioEnabled(_ enabled: Bool) {
        audioStateLock.synchronized { audioRequested = enabled }
        updateAudioActivation()
    }

    func setApplicationActive(_ active: Bool) {
        audioStateLock.synchronized { applicationActive = active }
        updateAudioActivation()
    }

    private func setMonitoringActive(_ active: Bool) {
        audioStateLock.synchronized { monitoringActive = active }
        updateAudioActivation()
    }

    fileprivate func audioListenerReady(sessionID: UUID) {
        let updated = updateAudioStatus(sessionID: sessionID, force: true) { status in
            status.available = true
            status.reason = nil
            status.error = nil
        }
        guard updated else { return }
        updateAudioActivation()
    }

    fileprivate func audioListenerFailed(sessionID: UUID, _ message: String) {
        let updated = updateAudioStatus(sessionID: sessionID, force: true) { status in
            audioRequested = false
            status.available = false
            status.enabled = false
            status.error = message
        }
        if updated { stopAudioPipeline(unlessSessionReplaced: sessionID) }
    }

    fileprivate func recordAudioPacket(sessionID: UUID, at date: Date) {
        updateAudioStatus(sessionID: sessionID) { status in
            status.packetsReceived += 1
            status.lastPacketAt = date
        }
    }

    fileprivate func submitAudio(_ accessUnit: CCAPIAACAccessUnit, sessionID: UUID) {
        let shouldDecode = audioStateLock.synchronized { () -> Bool in
            guard activeAudioSessionID == sessionID else { return false }
            audioStatus.accessUnitsReceived += 1
            audioStatus.channels = accessUnit.format.channels
            guard audioStatus.enabled, queuedAudioAccessUnits < maximumQueuedAudioAccessUnits else {
                if audioStatus.enabled { audioStatus.droppedAccessUnits += 1 }
                return false
            }
            queuedAudioAccessUnits += 1
            return true
        }
        publishAudioStatusIfNeeded(sessionID: sessionID)
        guard shouldDecode else { return }
        audioQueue.async { [weak self] in
            guard let self, self.isActiveAudioSession(sessionID) else { return }
            defer {
                self.audioStateLock.synchronized {
                    guard self.activeAudioSessionID == sessionID else { return }
                    self.queuedAudioAccessUnits = max(self.queuedAudioAccessUnits - 1, 0)
                }
            }
            do {
                let result = try self.audioPipeline.consume(accessUnit)
                self.updateAudioStatus(sessionID: sessionID) { status in
                    status.decodedAccessUnits += result.decodedAccessUnits
                    status.playedSampleFrames += result.playedSampleFrames
                    status.channels = result.channels
                    if result.playedSampleFrames > 0 { status.lastPCMAt = Date() }
                    if result.droppedForBackpressure { status.droppedAccessUnits += 1 }
                    status.error = nil
                }
            } catch {
                let updated = self.updateAudioStatus(sessionID: sessionID, force: true) { status in
                    self.audioRequested = false
                    status.enabled = false
                    status.error = "Canon RTP AAC playback failed: \(error.localizedDescription)"
                }
                if updated { self.stopAudioPipeline(unlessSessionReplaced: sessionID) }
            }
        }
    }

    fileprivate func recordAudioParseFailure(sessionID: UUID, _ message: String) {
        updateAudioStatus(sessionID: sessionID, force: true) { status in
            status.droppedAccessUnits += 1
            status.error = message
        }
    }

    fileprivate func endAudioSession(sessionID: UUID) {
        let ended = audioStateLock.synchronized { () -> Bool in
            guard activeAudioSessionID == sessionID else { return false }
            activeAudioSessionID = nil
            audioRequested = false
            queuedAudioAccessUnits = 0
            audioStatus = .inactive
            return true
        }
        guard ended else { return }
        stopAudioPipeline(unlessSessionReplaced: sessionID)
        emit(.audioStatus(.inactive))
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

    private func configureAudio(
        _ media: CCAPIRTPMediaDescription?,
        support: CCAPILatmAudioSupport,
        sessionID: UUID
    ) {
        audioStateLock.synchronized {
            activeAudioSessionID = sessionID
            audioRequested = false
            queuedAudioAccessUnits = 0
            lastAudioStatusPublishedAt = .distantPast
            audioStatus = IOSCcapiRTPAudioStatus(
                advertised: media != nil,
                available: false,
                enabled: false,
                codec: media?.codec,
                rtpPort: media?.port,
                rtpClockRate: media?.clockRate,
                channels: media?.channels,
                reason: support.reason,
                error: support.supported || media == nil ? nil : support.reason
            )
        }
        audioQueue.async { [audioPipeline] in audioPipeline.stop() }
        emit(.audioStatus(audioStateLock.synchronized { audioStatus }))
    }

    private func updateAudioActivation() {
        let enabled = audioStateLock.synchronized { () -> Bool in
            let enabled = audioRequested && applicationActive && monitoringActive && audioStatus.available
            audioStatus.enabled = enabled
            return enabled
        }
        if !enabled {
            audioQueue.async { [weak self] in
                guard let self, !self.audioStateLock.synchronized({ self.audioStatus.enabled }) else { return }
                self.audioPipeline.stop()
            }
        }
        publishAudioStatus(force: true)
    }

    @discardableResult
    private func updateAudioStatus(
        sessionID: UUID? = nil,
        force: Bool = false,
        _ update: (inout IOSCcapiRTPAudioStatus) -> Void
    ) -> Bool {
        let updated = audioStateLock.synchronized { () -> Bool in
            if let sessionID, activeAudioSessionID != sessionID { return false }
            update(&audioStatus)
            return true
        }
        if updated { publishAudioStatus(force: force, sessionID: sessionID) }
        return updated
    }

    private func publishAudioStatus(force: Bool = false, sessionID: UUID? = nil) {
        let status = audioStateLock.synchronized { () -> IOSCcapiRTPAudioStatus? in
            if let sessionID, activeAudioSessionID != sessionID { return nil }
            let now = Date()
            guard force || now.timeIntervalSince(lastAudioStatusPublishedAt) >= audioStatusInterval else {
                return nil
            }
            lastAudioStatusPublishedAt = now
            return audioStatus
        }
        if let status { emit(.audioStatus(status)) }
    }

    private func publishAudioStatusIfNeeded(sessionID: UUID) {
        publishAudioStatus(sessionID: sessionID)
    }

    private func isActiveAudioSession(_ sessionID: UUID) -> Bool {
        audioStateLock.synchronized { activeAudioSessionID == sessionID }
    }

    private func stopAudioPipeline(unlessSessionReplaced sessionID: UUID) {
        audioQueue.async { [weak self] in
            guard let self else { return }
            let shouldStop = self.audioStateLock.synchronized {
                self.activeAudioSessionID == nil || self.activeAudioSessionID == sessionID
            }
            if shouldStop { self.audioPipeline.stop() }
        }
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
    private let sessionID: UUID
    private let queue = DispatchQueue(label: "dev.openeos.control.ccapi-rtp", qos: .userInteractive)
    private let stateLock = NSLock()
    private var videoListener: NWListener?
    private var audioListener: NWListener?
    private var connections: [NWConnection] = []
    private var targetFPS = 30
    private var closed = false
    private var lastSubmittedAt: UInt64 = 0
    private var latestSPS: Data?
    private var latestPPS: Data?
    private lazy var depacketizer = CCAPIH264RTPDepacketizer(payloadType: description.video.payloadType)
    private var audioDepacketizer: CCAPILatmRTPDepacketizer?
    private var audioExtractor: CCAPILatmSampleExtractor?
    private var firstAudioTimestamp: UInt32?

    init(
        description: CCAPIRTPSessionDescription,
        destinationAddress: String,
        sink: IOSCcapiRTPController,
        sessionID: UUID
    ) {
        self.description = description
        self.destinationAddress = destinationAddress
        self.sink = sink
        self.sessionID = sessionID
        sourceURL = URL(string: "rtp://\(destinationAddress):\(description.video.port)")!
        if description.latmAudioSupport.supported, let audio = description.audio {
            audioDepacketizer = CCAPILatmRTPDepacketizer(payloadType: audio.payloadType)
            audioExtractor = CCAPILatmSampleExtractor()
        }
    }

    func start() async throws {
        try await withTaskCancellationHandler {
            let video = try makeListener(port: description.video.port)
            stateLock.synchronized { videoListener = video }
            try await startListener(
                video,
                kind: "video",
                onFailure: { [weak self] error in
                    self?.sink.emit(.failed("Canon RTP video listener failed: \(error.localizedDescription)"))
                },
                onConnection: { [weak self] connection in self?.acceptVideo(connection) }
            )

            if description.latmAudioSupport.supported, let audio = description.audio {
                do {
                    let listener = try makeListener(port: audio.port)
                    stateLock.synchronized { audioListener = listener }
                    try await startListener(
                        listener,
                        kind: "audio",
                        onFailure: { [weak self] error in
                            guard let self else { return }
                            self.sink.audioListenerFailed(
                                sessionID: self.sessionID,
                                "Canon RTP audio listener failed: \(error.localizedDescription)"
                            )
                        },
                        onConnection: { [weak self] connection in self?.acceptAudio(connection) }
                    )
                    sink.audioListenerReady(sessionID: sessionID)
                } catch {
                    let listener = stateLock.synchronized { () -> NWListener? in
                        let listener = audioListener
                        audioListener = nil
                        return listener
                    }
                    listener?.cancel()
                    sink.audioListenerFailed(
                        sessionID: sessionID,
                        "Canon RTP audio listener failed: \(error.localizedDescription)"
                    )
                }
            }
        } onCancel: {
            self.closeSynchronously()
        }
    }

    private func makeListener(port rawPort: UInt16) throws -> NWListener {
        let port = NWEndpoint.Port(rawValue: rawPort)!
        let parameters = NWParameters.udp
        parameters.requiredInterfaceType = .wifi
        parameters.requiredLocalEndpoint = .hostPort(
            host: NWEndpoint.Host(destinationAddress),
            port: port
        )
        parameters.allowLocalEndpointReuse = true
        return try NWListener(using: parameters, on: port)
    }

    private func startListener(
        _ listener: NWListener,
        kind: String,
        onFailure: @escaping @Sendable (NWError) -> Void,
        onConnection: @escaping @Sendable (NWConnection) -> Void
    ) async throws {
        guard !stateLock.synchronized({ closed }) else {
            listener.cancel()
            throw CancellationError()
        }
        let gate = ListenerReadyGate()
        listener.stateUpdateHandler = { state in
            switch state {
            case .ready:
                gate.resume()
            case let .failed(error):
                gate.resume(throwing: error)
                onFailure(error)
            case .cancelled:
                gate.resume(throwing: CancellationError())
            default:
                break
            }
        }
        listener.newConnectionHandler = onConnection
        queue.asyncAfter(deadline: .now() + listenerBindTimeout) {
            gate.resume(
                throwing: NSError(
                    domain: "OpenEOSControl.CCAPIRTP",
                    code: -2,
                    userInfo: [NSLocalizedDescriptionKey: "Timed out while binding the Canon RTP \(kind) listener."]
                )
            )
        }
        try await withCheckedThrowingContinuation { continuation in
            gate.install(continuation)
            listener.start(queue: queue)
        }
    }

    func setTargetFPS(_ fps: Int) async {
        stateLock.synchronized { targetFPS = min(max(fps, 1), 30) }
    }

    func close() async {
        closeSynchronously()
    }

    private func closeSynchronously() {
        let resources = stateLock.synchronized { () -> (NWListener?, NWListener?, [NWConnection]) in
            if closed { return (nil, nil, []) }
            closed = true
            let resources = (videoListener, audioListener, connections)
            videoListener = nil
            audioListener = nil
            connections = []
            return resources
        }
        resources.0?.cancel()
        resources.1?.cancel()
        resources.2.forEach { $0.cancel() }
        sink.endAudioSession(sessionID: sessionID)
    }

    private func acceptVideo(_ connection: NWConnection) {
        guard !stateLock.synchronized({ closed }) else {
            connection.cancel()
            return
        }
        stateLock.synchronized { connections.append(connection) }
        connection.stateUpdateHandler = { [weak self, weak connection] state in
            guard let self, let connection else { return }
            switch state {
            case .ready:
                self.receiveVideo(on: connection)
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

    private func receiveVideo(on connection: NWConnection) {
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
            if !self.stateLock.synchronized({ self.closed }) { self.receiveVideo(on: connection) }
        }
    }

    private func acceptAudio(_ connection: NWConnection) {
        guard !stateLock.synchronized({ closed }) else {
            connection.cancel()
            return
        }
        stateLock.synchronized { connections.append(connection) }
        connection.stateUpdateHandler = { [weak self, weak connection] state in
            guard let self, let connection else { return }
            switch state {
            case .ready:
                self.receiveAudio(on: connection)
            case let .failed(error):
                if !self.stateLock.synchronized({ self.closed }) {
                    self.sink.audioListenerFailed(
                        sessionID: self.sessionID,
                        "Canon RTP audio receive failed: \(error.localizedDescription)"
                    )
                }
            default:
                break
            }
        }
        connection.start(queue: queue)
    }

    private func receiveAudio(on connection: NWConnection) {
        connection.receiveMessage { [weak self, weak connection] data, _, _, error in
            guard let self, let connection else { return }
            if let data, data.count <= maximumRTPDatagramBytes {
                self.sink.recordAudioPacket(sessionID: self.sessionID, at: Date())
                do {
                    if let mux = self.audioDepacketizer?.accept(data),
                       let audio = self.description.audio,
                       let extractor = self.audioExtractor {
                        if mux.discontinuity { extractor.reset() }
                        let firstTimestamp = self.firstAudioTimestamp ?? mux.rtpTimestamp
                        if self.firstAudioTimestamp == nil { self.firstAudioTimestamp = firstTimestamp }
                        let delta = mux.rtpTimestamp &- firstTimestamp
                        let presentationTime = Int64(delta) * 1_000_000 / Int64(audio.clockRate)
                        let accessUnit = try extractor.consume(
                            mux,
                            presentationTimeMicroseconds: presentationTime
                        )
                        self.sink.submitAudio(accessUnit, sessionID: self.sessionID)
                    }
                } catch {
                    self.audioDepacketizer?.resetAfterDiscontinuity()
                    self.audioExtractor?.reset()
                    self.sink.recordAudioParseFailure(
                        sessionID: self.sessionID,
                        "Canon RTP LATM parse failed: \(error.localizedDescription)"
                    )
                }
            }
            if let error {
                if !self.stateLock.synchronized({ self.closed }) {
                    self.sink.audioListenerFailed(
                        sessionID: self.sessionID,
                        "Canon RTP audio receive failed: \(error.localizedDescription)"
                    )
                }
                return
            }
            if !self.stateLock.synchronized({ self.closed }) { self.receiveAudio(on: connection) }
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

private let listenerBindTimeout: TimeInterval = 5
private let audioStatusInterval: TimeInterval = 0.5
private let maximumQueuedAudioAccessUnits = 8
private let maximumRTPDatagramBytes = 65_535
