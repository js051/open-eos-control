import AVFoundation
import CoreMedia
import Darwin
import Foundation
import Network
import OpenEOSCore
import UIKit

enum IOSCcapiRTPEvent: Sendable {
    // Actual VideoToolbox output metrics; this event does not claim presentation.
    case frame(encodedBytes: Int, at: Date)
    case videoSize(width: Int32, height: Int32)
    case audioStatus(IOSCcapiRTPAudioStatus)
    case failed(String)
}

struct IOSCcapiRTPDelivery: Sendable {
    let owner: UUID
    let event: IOSCcapiRTPEvent
    var audioEpoch: UUID? = nil
}

struct IOSCcapiRTPAudioStatus: Equatable, Sendable {
    var advertised = false
    var available = false
    var enabled = false
    var requested = false
    var outputProgress = IOSRTPAudioOutputProgress.inactive
    var renderProgressSupported = false
    var retiringResources = false
    var codec: String?
    var rtpPort: UInt16?
    var rtpClockRate: Int?
    var channels: Int?
    var packetsReceived = 0
    var accessUnitsReceived = 0
    var decoderInputs = 0
    var decodedAccessUnits = 0
    var decodedSampleFrames = 0
    var submittedSampleFrames = 0
    var renderedSampleFrames = 0
    var droppedAccessUnits = 0
    var expiredOutputBuffers = 0
    var lastPacketAt: Date?
    var lastPCMAt: Date?
    var lastRenderAt: Date?
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
    private var eventHandler: (@Sendable (IOSCcapiRTPDelivery) -> Void)?
    private let audioStateLock = NSLock()
    private let audioQueue = DispatchQueue(label: "dev.openeos.control.ccapi-rtp-audio", qos: .userInitiated)
    private var audioStatus = IOSCcapiRTPAudioStatus.inactive
    private var audioRequested = false
    private var applicationActive = true
    private var monitoringActive = true
    private var queuedAudioAccessUnits = 0
    private var lastAudioStatusPublishedAt = Date.distantPast
    private var activeAudioSessionID: UUID?
    private var deliveryOwner = UUID()
    private var terminalVideoFailure: String?
    private let videoConsumer: (@MainActor @Sendable (CCAPIH264AccessUnit) -> Void)?
    private let flushRenderer: @MainActor @Sendable (AVSampleBufferDisplayLayer, Bool, @escaping @Sendable () -> Void) -> Void
    private let audioPipeline: IOSCcapiRTPAudioPipeline
    private let audioClock: () -> TimeInterval
    private var audioEpoch = UUID()
    private var activeAudioToken: IOSRTPAudioPlaybackToken?
    private var audioEnabledAt: TimeInterval?
    private var lastAudioPCMAt: TimeInterval?
    private var audioWatch: DispatchSourceTimer?
    private let audioWatchQueue = DispatchQueue(label: "dev.openeos.control.audio-watch")

    @MainActor private weak var displayLayer: AVSampleBufferDisplayLayer?
    @MainActor private var latestSPS: Data?
    @MainActor private var latestPPS: Data?
    @MainActor private var formatDescription: CMVideoFormatDescription?
    @MainActor private var needsKeyFrame = true
    @MainActor private var deliveryContinuity = IOSRTPVideoDecodeContinuity()
    @MainActor private var renderingEnabled = true
    @MainActor private var renderedSessionID: UUID?
    @MainActor private var pixelReadbackBarrier: UUID?
    @MainActor private(set) lazy var pixelMonitor = IOSRTPPixelMonitor(isOwnerActive: { [weak self] token in
        guard let self else { return false }
        return self.renderingEnabled && self.videoProcessingGeneration == token.epoch
            && self.lutRevision == token.lutRevision
            && self.audioStateLock.synchronized {
                self.deliveryOwner == token.owner && self.applicationActive && self.terminalVideoFailure == nil
            }
    })
    private let videoProcessor: IOSRTPVideoProcessor
    private let readinessDecoder: IOSRTPVideoProcessor
    @MainActor private var readinessProbeOwner: UUID?
    @MainActor private(set) lazy var videoPresentation = IOSNativeVideoPresentation()
    @MainActor private var videoProcessingOwner: UUID?
    @MainActor private var videoProcessingGeneration = UUID()
    @MainActor private var videoLut: CubeLut?
    @MainActor private var videoProcessingRequired = false
    @MainActor private var lutRevision = UUID()
    @MainActor private var frameSequence: UInt64 = 0
    @MainActor private var progress = IOSRTPPresentationProgress()
    @MainActor private var retainedOutputs: [(token: IOSRTPFrameToken, frame: IOSNativeProcessedFrame, outputAt: TimeInterval)] = []
    @MainActor private var presentationPoll: Task<Void, Never>?
    private let readDisplayedPixels: @MainActor @Sendable (AVSampleBufferDisplayLayer) -> CVPixelBuffer?
    @MainActor private var videoProcessingUnavailable = false
    @MainActor var isPixelReadbackSuspended: Bool { pixelReadbackBarrier != nil }

    init(audioPipeline: IOSCcapiRTPAudioPipeline = IOSCcapiRTPAudioPipeline(),
         audioClock: @escaping () -> TimeInterval = { ProcessInfo.processInfo.systemUptime },
         videoConsumer: (@MainActor @Sendable (CCAPIH264AccessUnit) -> Void)? = nil,
         videoProcessor: IOSRTPVideoProcessor = IOSRTPVideoProcessor(),
         readinessDecoder: IOSRTPVideoProcessor = IOSRTPVideoProcessor(),
         readDisplayedPixels: @escaping @MainActor @Sendable (AVSampleBufferDisplayLayer) -> CVPixelBuffer? = {
             if #available(iOS 17.4, *) { return $0.sampleBufferRenderer.displayedPixelBuffer() }
             return nil
         },
         flushRenderer: @escaping @MainActor @Sendable (AVSampleBufferDisplayLayer, Bool, @escaping @Sendable () -> Void) -> Void = {
             $0.sampleBufferRenderer.flush(removingDisplayedImage: $1, completionHandler: $2)
         }) {
        self.audioPipeline = audioPipeline
        self.audioClock = audioClock
        self.videoConsumer = videoConsumer
        self.videoProcessor = videoProcessor
        self.readinessDecoder = readinessDecoder
        self.readDisplayedPixels = readDisplayedPixels
        self.flushRenderer = flushRenderer
        audioPipeline.setRetirementObserver { [weak self] in self?.audioRetirementChanged() }
    }

    deinit {
        audioWatch?.cancel()
        audioPipeline.retire()
        audioQueue.async { [audioPipeline] in audioPipeline.resetRetiredDecoder() }
    }

    func accepts(_ delivery: IOSCcapiRTPDelivery) -> Bool {
        audioStateLock.synchronized {
            guard deliveryOwner == delivery.owner else { return false }
            if case .failed = delivery.event { return true }
            if case .audioStatus = delivery.event, delivery.audioEpoch != audioEpoch { return false }
            return terminalVideoFailure == nil
        }
    }

    @MainActor
    func retireDelivery() {
        audioStateLock.synchronized {
            retireAudioLocked()
            activeAudioSessionID = nil
            deliveryOwner = UUID()
            audioStatus = .inactive
            audioStatus.retiringResources = audioPipeline.isRetiring
        }
        clearRendererSession()
        publishAudioStatus(force: true)
    }

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

    /// One shared probe across session replacement. Both prior physical decoders
    /// drain before admission; logical retirement alone cannot create a second slot.
    @MainActor
    func submitReadinessProbe(_ sample: CMSampleBuffer, format: CMVideoFormatDescription,
                                         sps: Data, sessionID: UUID,
                                         completion: @escaping @MainActor @Sendable (IOSNativeVideoResult) -> Void) async -> Bool {
        guard audioStateLock.synchronized({ deliveryOwner == sessionID && terminalVideoFailure == nil }) else { return false }
        readinessProbeOwner = sessionID
        await videoProcessor.retireAndWait()
        guard readinessProbeOwner == sessionID,
              audioStateLock.synchronized({ deliveryOwner == sessionID && terminalVideoFailure == nil }) else { return false }
        await readinessDecoder.retireAndWait()
        guard readinessProbeOwner == sessionID,
              audioStateLock.synchronized({ deliveryOwner == sessionID && terminalVideoFailure == nil }) else { return false }
        return readinessDecoder.submit(sample, format: format, lut: nil, sps: sps, decodeOnly: true) { [weak self] result in
            Task { @MainActor [weak self] in
                guard let self, self.readinessProbeOwner == sessionID else { return }
                await self.readinessDecoder.retireAndWait()
                guard self.readinessProbeOwner == sessionID,
                      self.audioStateLock.synchronized({ self.deliveryOwner == sessionID && self.terminalVideoFailure == nil }) else { return }
                self.readinessProbeOwner = nil
                completion(result)
            }
        }
    }

    fileprivate func cancelReadinessProbe(sessionID: UUID) {
        Task { @MainActor [weak self] in
            guard let self, self.readinessProbeOwner == sessionID else { return }
            self.readinessProbeOwner = nil
            self.readinessDecoder.invalidate()
        }
    }

    func setEventHandler(_ handler: @escaping @Sendable (IOSCcapiRTPDelivery) -> Void) {
        handlerLock.synchronized { eventHandler = handler }
    }

    @MainActor
    func configureVideoProcessing(settings: LiveViewMonitorSettings?, owner: UUID) {
        let lut = settings?.cubeLut
        let required = lut != nil || (!IOSRTPPixelMonitor.supported && settings?.needsPixelAnalysis == true)
        guard videoProcessingOwner != owner || videoLut != lut || videoProcessingRequired != required else { return }
        videoProcessingOwner = owner
        lutRevision = UUID()
        videoLut = lut
        videoProcessingRequired = required
        videoProcessingUnavailable = false
        videoPresentation.status = lut == nil ? .off : .waiting
        resetRenderer(removingImage: true)
    }

    @MainActor
    func deactivateVideoProcessing(owner: UUID) {
        guard videoProcessingOwner == owner else { return }
        configureVideoProcessing(settings: nil, owner: owner)
        videoProcessingOwner = nil
    }

    @MainActor
    func attach(_ layer: AVSampleBufferDisplayLayer) {
        // SwiftUI calls updateUIView for unrelated HUD/state updates. Reattaching
        // the same layer must not flush the decoder or require another IDR.
        guard displayLayer !== layer else { return }
        if let previous = displayLayer { detach(previous) }
        displayLayer = layer
        layer.backgroundColor = UIColor.black.cgColor
        // LiveViewSurface already computes the aspect-fit/desqueezed rectangle.
        layer.videoGravity = .resize
        resetRenderer(removingImage: true)
    }

    @MainActor
    func detach(_ layer: AVSampleBufferDisplayLayer) {
        guard displayLayer === layer else { return }
        resetRenderer(removingImage: true)
        displayLayer = nil
    }

    /// Decoding, renderer availability, and exact-identity presentation are separate.
    /// A nonempty readback and repeated old output must never renew the progress deadline.
    @MainActor
    func videoProgressFailure(at now: TimeInterval = ProcessInfo.processInfo.systemUptime) -> IOSRTPProgressFailure? {
        guard renderingEnabled, let renderedSessionID,
              audioStateLock.synchronized({ deliveryOwner == renderedSessionID && applicationActive && terminalVideoFailure == nil }) else { return nil }
        observePresentation(at: now)
        let renderer = displayLayer?.sampleBufferRenderer
        let failed = renderer?.status == .failed || renderer?.requiresFlushToResumeDecoding == true
        let failure = progress.failure(at: now, rendererFailed: failed)
        videoPresentation.progress = progress.state
        if failure != nil {
            videoPresentation.presentedFrame = nil
            pixelMonitor.presentationChanged(to: nil)
        }
        return failure
    }

    @MainActor
    private func observePresentation(at now: TimeInterval = ProcessInfo.processInfo.systemUptime) {
        guard pixelReadbackBarrier == nil, renderingEnabled, let layer = displayLayer,
              let owner = renderedSessionID,
              audioStateLock.synchronized({ deliveryOwner == owner && applicationActive && terminalVideoFailure == nil }) else { return }
        let renderer = layer.sampleBufferRenderer
        guard renderer.status != .failed, !renderer.requiresFlushToResumeDecoding else {
            pixelMonitor.presentationChanged(to: nil)
            videoPresentation.presentedFrame = nil
            return
        }
        guard IOSRTPPixelMonitor.supported else {
            progress.recordUnverifiablePresentation(at: now, pixelsAvailable: true)
            videoPresentation.progress = .unknown
            videoPresentation.presentedFrame = nil
            pixelMonitor.presentationChanged(to: nil)
            return
        }
        guard let pixels = readDisplayedPixels(layer) else {
            progress.recordUnverifiablePresentation(at: now, pixelsAvailable: false)
            videoPresentation.progress = .unknown
            videoPresentation.presentedFrame = nil
            pixelMonitor.presentationChanged(to: nil)
            return
        }
        // Retain the actual objects, not addresses alone: pool reuse cannot make an
        // old frame look new. A renderer copy without exact identity remains unknown.
        guard let output = retainedOutputs.first(where: { $0.frame.pixelBuffer === pixels }),
              progress.owns(output.token) else {
            progress.recordUnverifiablePresentation(at: now, pixelsAvailable: true)
            videoPresentation.progress = .unknown
            videoPresentation.presentedFrame = nil
            pixelMonitor.presentationChanged(to: nil)
            return
        }
        _ = progress.recordPresented(output.token, at: now)
        videoPresentation.progress = progress.state
        // Do not overlay a confirmed older frame once a replacement was enqueued.
        guard output.token == progress.submitted else {
            videoPresentation.presentedFrame = nil
            pixelMonitor.presentationChanged(to: nil)
            return
        }
        videoPresentation.presentedFrame = output.token
        if !videoProcessingUnavailable { videoPresentation.status = videoLut == nil ? .off : .applied }
        pixelMonitor.presentationChanged(to: output.token)
    }

    @MainActor
    private func beginPresentationPolling() {
        guard presentationPoll == nil else { return }
        let generation = videoProcessingGeneration
        presentationPoll = Task { @MainActor [weak self] in
            while !Task.isCancelled {
                guard self?.videoProcessingGeneration == generation else { return }
                self?.observePresentation()
                do { try await Task.sleep(for: .milliseconds(33)) } catch { return }
            }
        }
    }

    @MainActor
    func setRenderingEnabled(_ enabled: Bool) {
        guard renderingEnabled != enabled else { return }
        renderingEnabled = enabled
        if enabled { progress = IOSRTPPresentationProgress() }
        // Discarding inter frames while paused breaks the prediction chain.
        resetRenderer(removingImage: enabled)
        setMonitoringActive(enabled)
    }

    func setAudioEnabled(_ enabled: Bool) {
        audioStateLock.synchronized { audioRequested = enabled }
        updateAudioActivation()
    }

    @MainActor
    func setApplicationActive(_ active: Bool) {
        audioStateLock.synchronized {
            applicationActive = active
            if !active { audioRequested = false }
        }
        if active { progress = IOSRTPPresentationProgress(); resetRenderer(removingImage: true) }
        if !active { resetRenderer(removingImage: true) }
        updateAudioActivation()
    }

    private func setMonitoringActive(_ active: Bool) {
        audioStateLock.synchronized {
            monitoringActive = active
            if !active { audioRequested = false }
        }
        updateAudioActivation()
    }

    func audioListenerReady(sessionID: UUID) {
        let updated = updateAudioStatus(sessionID: sessionID, force: true) { status in
            status.available = true
            status.reason = nil
            status.error = nil
        }
        guard updated else { return }
        updateAudioActivation()
    }

    func audioListenerFailed(sessionID: UUID, _ message: String) {
        let updated = audioStateLock.synchronized { () -> Bool in
            guard activeAudioSessionID == sessionID else { return false }
            retireAudioLocked(progress: .failed, error: message)
            audioStatus.available = false
            return true
        }
        if updated { publishAudioStatus(force: true, sessionID: sessionID) }
    }

    func recordAudioPacket(sessionID: UUID, at date: Date) {
        updateAudioStatus(sessionID: sessionID) { status in
            status.packetsReceived += 1
            status.lastPacketAt = date
        }
    }

    func submitAudio(_ accessUnit: CCAPIAACAccessUnit, sessionID: UUID) {
        let admission = audioStateLock.synchronized { () -> (IOSRTPAudioPlaybackToken, TimeInterval)? in
            guard activeAudioSessionID == sessionID else { return nil }
            audioStatus.accessUnitsReceived += 1
            audioStatus.channels = accessUnit.format.channels
            guard audioStatus.enabled, let token = activeAudioToken,
                  queuedAudioAccessUnits < maximumQueuedAudioAccessUnits else {
                if audioStatus.enabled { audioStatus.droppedAccessUnits += 1 }
                return nil
            }
            queuedAudioAccessUnits += 1
            return (token, audioClock() + maximumAudioAccessUnitAge)
        }
        publishAudioStatusIfNeeded(sessionID: sessionID)
        guard let (token, expiresAt) = admission else { return }
        audioQueue.async { [weak self] in
            guard let self else { return }
            defer {
                self.audioStateLock.synchronized {
                    self.queuedAudioAccessUnits = max(self.queuedAudioAccessUnits - 1, 0)
                }
            }
            guard self.isActiveAudioToken(token) else { return }
            do {
                guard let result = try self.audioPipeline.consume(accessUnit, token: token, expiresAt: expiresAt) else { return }
                self.recordAudioResult(result, token: token)
            } catch {
                self.failAudio(token: token, progress: .failed,
                               message: "Canon RTP AAC playback failed: \(error.localizedDescription)")
            }
        }
    }

    private func recordAudioResult(_ result: IOSCcapiRTPAudioPlaybackResult, token: IOSRTPAudioPlaybackToken) {
        updateAudioStatus(sessionID: token.owner, token: token) { status in
            status.submittedSampleFrames += result.submittedSampleFrames
            status.channels = result.channels
            if result.dropped { status.droppedAccessUnits += 1 }
            if status.renderedSampleFrames == 0 { status.outputProgress = .unknown }
        }
        pollAudioProgress(token: token)
    }

    /// Real watchdog drain reaches AVAudioConverter even after the final AU. It
    /// shares the bounded decode queue and can never run a second decoder in parallel.
    private func queueAudioDrain() {
        let token = audioStateLock.synchronized { () -> IOSRTPAudioPlaybackToken? in
            guard audioStatus.enabled, let token = activeAudioToken, queuedAudioAccessUnits == 0 else { return nil }
            queuedAudioAccessUnits += 1
            return token
        }
        guard let token else { return }
        audioQueue.async { [weak self] in
            guard let self else { return }
            defer { self.audioStateLock.synchronized { self.queuedAudioAccessUnits -= 1 } }
            guard self.isActiveAudioToken(token) else { return }
            do {
                if let result = try self.audioPipeline.drain(token: token) {
                    self.recordAudioResult(result, token: token)
                }
            } catch {
                self.failAudio(token: token, progress: .failed,
                               message: "Canon RTP AAC drain failed: \(error.localizedDescription)")
            }
        }
    }

    func recordAudioParseFailure(sessionID: UUID, _ message: String) {
        updateAudioStatus(sessionID: sessionID) { status in
            status.droppedAccessUnits += 1
            status.reason = message
        }
    }

    fileprivate func endAudioSession(sessionID: UUID) {
        let owner = audioStateLock.synchronized { () -> UUID? in
            guard activeAudioSessionID == sessionID else { return nil }
            retireAudioLocked()
            activeAudioSessionID = nil
            deliveryOwner = UUID()
            audioStatus = .inactive
            audioStatus.retiringResources = audioPipeline.isRetiring
            return deliveryOwner
        }
        guard let owner else { return }
        publishAudioStatus(force: true)
        Task { @MainActor [weak self] in
            guard let self, self.audioStateLock.synchronized({ self.deliveryOwner == owner }) else { return }
            self.clearRendererSession()
        }
    }

    @MainActor
    func enqueue(
        _ accessUnit: CCAPIH264AccessUnit,
        sequenceParameterSet: Data?,
        pictureParameterSet: Data?,
        sessionID: UUID,
        presentFrame: Bool = true,
        deliverySequence: UInt64? = nil
    ) {
        guard audioStateLock.synchronized({ activeAudioSessionID == sessionID && deliveryOwner == sessionID && applicationActive && terminalVideoFailure == nil }),
              renderingEnabled else { return }
        if renderedSessionID != sessionID {
            clearRendererSession()
            renderedSessionID = sessionID
            progress.begin(owner: sessionID, epoch: videoProcessingGeneration, lutRevision: lutRevision,
                           at: ProcessInfo.processInfo.systemUptime)
        }
        if let deliverySequence,
           !deliveryContinuity.admit(sequence: deliverySequence, keyFrame: accessUnit.keyFrame) { return }
        if let videoConsumer {
            videoConsumer(accessUnit)
            return
        }
        guard let displayLayer else { return }
        let parameterSetsChanged = sequenceParameterSet != nil && pictureParameterSet != nil &&
            (sequenceParameterSet != latestSPS || pictureParameterSet != latestPPS)
        if parameterSetsChanged {
            latestSPS = sequenceParameterSet
            latestPPS = pictureParameterSet
            formatDescription = nil
            resetRenderer(removingImage: true)
        }
        if formatDescription == nil, let latestSPS, let latestPPS {
            do {
                formatDescription = try Self.makeFormatDescription(sps: latestSPS, pps: latestPPS)
                if let formatDescription {
                    let dimensions = CMVideoFormatDescriptionGetPresentationDimensions(
                        formatDescription, usePixelAspectRatio: true, useCleanAperture: true
                    )
                    if dimensions.width.isFinite, dimensions.height.isFinite,
                       dimensions.width > 0, dimensions.height > 0,
                       dimensions.width <= 32_768, dimensions.height <= 32_768 {
                        emit(.videoSize(width: Int32(dimensions.width), height: Int32(dimensions.height)), owner: sessionID)
                    }
                }
            } catch {
                emit(.failed("Canon H.264 format setup failed: \(error.localizedDescription)"), owner: sessionID)
                return
            }
        }
        guard let formatDescription else { return }

        let renderer = displayLayer.sampleBufferRenderer
        if renderer.status == .failed || renderer.requiresFlushToResumeDecoding {
            emit(.failed("Canon RTP renderer failed. Confirm Stop before starting again."), owner: sessionID)
            return
        }
        if needsKeyFrame && !accessUnit.keyFrame { return }
        do {
            let sampleBuffer = try Self.makeSampleBuffer(
                accessUnit: accessUnit,
                formatDescription: formatDescription
            )
            guard let latestSPS else { return }
            let generation = videoProcessingGeneration
            let attachment = ObjectIdentifier(displayLayer)
            frameSequence &+= 1
            let token = IOSRTPFrameToken(owner: sessionID, epoch: generation,
                                        lutRevision: lutRevision, sequence: frameSequence)
            let accepted = videoProcessor.submit(sampleBuffer, format: formatDescription,
                lut: videoProcessingUnavailable ? nil : videoLut,
                sps: latestSPS, present: presentFrame) { [weak self] result in
                    guard let self, self.videoProcessingGeneration == generation,
                          let layer = self.displayLayer, ObjectIdentifier(layer) == attachment,
                          self.renderingEnabled,
                          self.audioStateLock.synchronized({ self.deliveryOwner == sessionID && self.applicationActive && self.terminalVideoFailure == nil }) else { return }
                    let now = ProcessInfo.processInfo.systemUptime
                    switch result {
                    case let .frame(frame):
                        guard self.progress.recordDecoded(token, at: frame.decodedAt) else { return }
                        self.emit(.frame(encodedBytes: accessUnit.encodedByteCount, at: Date()), owner: sessionID)
                        let output = layer.sampleBufferRenderer
                        guard self.pixelReadbackBarrier == nil else { return }
                        guard output.status != .failed, !output.requiresFlushToResumeDecoding else {
                            self.emit(.failed("Canon RTP renderer failed. Confirm Stop before starting again."), owner: sessionID)
                            return
                        }
                        self.observePresentation(at: now)
                        guard output.isReadyForMoreMediaData else { return }
                        // Clear old overlays before enqueue can change the native displayed image.
                        self.pixelMonitor.presentationChanged(to: nil)
                        self.videoPresentation.presentedFrame = nil
                        guard self.progress.recordSubmitted(token, at: now) else { return }
                        // The LUT output pool is capped at three buffers. Keep at most two
                        // retained samples, leaving one slot for the next decoder output.
                        self.retainedOutputs.append((token, frame, frame.decodedAt))
                        if self.retainedOutputs.count > 2 { self.retainedOutputs.removeFirst() }
                        let enqueued = self.audioStateLock.synchronized { () -> Bool in
                            guard self.deliveryOwner == sessionID, self.applicationActive,
                                  self.terminalVideoFailure == nil else { return false }
                            output.enqueue(frame.sampleBuffer)
                            return true
                        }
                        guard enqueued else { return }
                        if !self.videoProcessingUnavailable {
                            self.videoPresentation.status = self.videoLut == nil ? .off : .processed
                            self.pixelMonitor.frameAvailable(token: token, format: frame.format,
                                buffer: frame.pixelBuffer, outputAt: frame.decodedAt)
                        }
                        self.beginPresentationPolling()
                    case let .decoded(decodedAt):
                        if self.progress.recordDecoded(token, at: decodedAt) {
                            self.emit(.frame(encodedBytes: accessUnit.encodedByteCount, at: Date()), owner: sessionID)
                        }
                    case .backpressure:
                        break // No actual decoder output; never refresh progress for input alone.
                    case .unsupportedColor, .unavailable, .failed:
                        if self.videoLut != nil && !self.videoProcessingUnavailable {
                            self.videoProcessingUnavailable = true
                            switch result {
                            case .unsupportedColor: self.videoPresentation.status = .unsupportedColor
                            case .unavailable: self.videoPresentation.status = .unavailable
                            default: self.videoPresentation.status = .failed
                            }
                            // Same-session unfiltered VT output; no camera command or retry loop.
                            self.resetRenderer(removingImage: true)
                        } else {
                            self.emit(.failed("Canon RTP decoder output failed. Confirm Stop before starting again."), owner: sessionID)
                        }
                    }
                }
            // A physically busy decoder does not create a second queue. Skipped
            // reference AUs require a fresh IDR, never replay or reorder old input.
            needsKeyFrame = !accepted
        } catch {
            resetRenderer(removingImage: true)
            emit(.failed("Canon H.264 sample creation failed: \(error.localizedDescription)"), owner: sessionID)
        }
    }

    func currentVideoFailure() -> String? { audioStateLock.synchronized { terminalVideoFailure } }

    func emit(_ event: IOSCcapiRTPEvent, owner: UUID, audioEpoch: UUID? = nil) {
        let delivery = IOSCcapiRTPDelivery(owner: owner, event: event, audioEpoch: audioEpoch)
        let accepted = audioStateLock.synchronized { () -> Bool in
            guard deliveryOwner == owner, terminalVideoFailure == nil else { return false }
            if case .audioStatus = event, audioEpoch != self.audioEpoch { return false }
            if case let .failed(message) = event {
                terminalVideoFailure = message
                retireAudioLocked()
            }
            return true
        }
        guard accepted else { return }
        let handler = handlerLock.synchronized { eventHandler }
        handler?(delivery)
    }

    private func configureAudio(
        _ media: CCAPIRTPMediaDescription?,
        support: CCAPILatmAudioSupport,
        sessionID: UUID
    ) {
        audioStateLock.synchronized {
            retireAudioLocked()
            activeAudioSessionID = sessionID
            deliveryOwner = sessionID
            terminalVideoFailure = nil
            audioRequested = false
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
            audioStatus.retiringResources = audioPipeline.isRetiring
        }
        publishAudioStatus(force: true, sessionID: sessionID)
    }

    private func updateAudioActivation() {
        audioStateLock.synchronized {
            let enabled = audioRequested && applicationActive && monitoringActive && audioStatus.available
                && activeAudioSessionID != nil && terminalVideoFailure == nil
            guard enabled != audioStatus.enabled else {
                if !enabled { audioRequested = false; audioStatus.requested = false }
                return
            }
            if enabled, let owner = activeAudioSessionID {
                audioEpoch = UUID()
                let token = IOSRTPAudioPlaybackToken(owner: owner, epoch: audioEpoch)
                activeAudioToken = token
                audioStatus.enabled = true
                audioStatus.requested = true
                audioStatus.outputProgress = .waiting
                audioStatus.error = nil
                audioStatus.reason = nil
                audioStatus.decoderInputs = 0
                audioStatus.decodedAccessUnits = 0
                audioStatus.decodedSampleFrames = 0
                audioStatus.submittedSampleFrames = 0
                audioStatus.renderedSampleFrames = 0
                audioStatus.expiredOutputBuffers = 0
                audioStatus.lastPCMAt = nil
                audioStatus.lastRenderAt = nil
                audioEnabledAt = audioClock()
                lastAudioPCMAt = nil
                audioPipeline.activate(token)
                audioStatus.retiringResources = audioPipeline.isRetiring
                audioStatus.renderProgressSupported = audioPipeline.snapshot(for: token)?.supportsRenderProgress ?? false
                beginAudioWatchLocked(token: token)
            } else { retireAudioLocked() }
        }
        publishAudioStatus(force: true)
    }

    /// Call only while holding audioStateLock. Revokes output synchronously, then
    /// drains decoder resources on their serial queue; no replacement decoder overlaps.
    private func retireAudioLocked(progress: IOSRTPAudioOutputProgress = .inactive, error: String? = nil) {
        audioEpoch = UUID()
        activeAudioToken = nil
        audioRequested = false
        audioStatus.requested = false
        audioStatus.enabled = false
        audioStatus.outputProgress = progress
        audioStatus.error = error
        audioEnabledAt = nil
        lastAudioPCMAt = nil
        audioWatch?.cancel()
        audioWatch = nil
        audioPipeline.retire()
        audioStatus.retiringResources = audioPipeline.isRetiring
        audioQueue.async { [audioPipeline] in audioPipeline.resetRetiredDecoder() }
    }

    private func audioRetirementChanged() {
        let changed = audioStateLock.synchronized { () -> Bool in
            let retiring = audioPipeline.isRetiring
            guard audioStatus.retiringResources != retiring else { return false }
            audioStatus.retiringResources = retiring
            return true
        }
        if changed { publishAudioStatus(force: true) }
    }

    private func beginAudioWatchLocked(token: IOSRTPAudioPlaybackToken) {
        audioWatch?.cancel()
        let timer = DispatchSource.makeTimerSource(queue: audioWatchQueue)
        timer.schedule(deadline: .now() + .milliseconds(250), repeating: .milliseconds(250))
        timer.setEventHandler { [weak self] in self?.pollAudioProgress(token: token) }
        audioWatch = timer
        timer.resume()
    }

    /// Shared by the real watchdog and deterministic tests; packet/input/PCM activity
    /// cannot renew either deadline. Only current player-render callbacks can do so.
    func pollAudioProgress(token expected: IOSRTPAudioPlaybackToken? = nil) {
        let changed = audioStateLock.synchronized { () -> Bool in
            guard let token = activeAudioToken, expected == nil || token == expected,
                  let began = audioEnabledAt, let snapshot = audioPipeline.snapshot(for: token) else { return false }
            let now = audioClock()
            audioStatus.decoderInputs = snapshot.decoderInputs
            audioStatus.decodedAccessUnits = snapshot.decodedAccessUnits
            audioStatus.decodedSampleFrames = snapshot.decodedSampleFrames
            lastAudioPCMAt = snapshot.lastDecodedAt
            audioStatus.lastPCMAt = snapshot.lastDecodedAt.map { Date(timeIntervalSinceNow: $0 - now) }
            if snapshot.renderedSampleFrames > audioStatus.renderedSampleFrames {
                audioStatus.renderedSampleFrames = snapshot.renderedSampleFrames
                audioStatus.lastRenderAt = snapshot.lastRenderedAt.map { Date(timeIntervalSinceNow: $0 - now) }
                audioStatus.outputProgress = .rendering
            }
            audioStatus.renderProgressSupported = snapshot.supportsRenderProgress
            audioStatus.expiredOutputBuffers = snapshot.expiredBuffers
            audioStatus.retiringResources = snapshot.retiring
            if let last = snapshot.lastRenderedAt {
                audioStatus.outputProgress = now - last < maximumAudioAccessUnitAge ? .rendering : .unknown
            }
            if now - (lastAudioPCMAt ?? began) >= audioPCMStallTimeout {
                retireAudioLocked(progress: .stalled,
                                  error: "Camera audio decoder produced no fresh PCM for 5 seconds. Enable audio again to retry.")
            } else if !snapshot.supportsRenderProgress {
                audioStatus.outputProgress = .unknown
            } else if let last = snapshot.lastRenderedAt {
                if now - last >= audioRenderStallTimeout {
                    retireAudioLocked(progress: .stalled,
                                      error: "Camera audio render progress stopped. Enable audio again to retry.")
                }
            } else if now - began >= audioFirstRenderTimeout {
                retireAudioLocked(progress: .stalled,
                                  error: "Camera audio output is unverified: no player render progress. Enable audio again to retry.")
            }
            return true
        }
        if changed {
            queueAudioDrain()
            publishAudioStatus(force: true)
        }
    }

    private func failAudio(token: IOSRTPAudioPlaybackToken, progress: IOSRTPAudioOutputProgress, message: String) {
        let changed = audioStateLock.synchronized { () -> Bool in
            guard activeAudioToken == token else { return false }
            retireAudioLocked(progress: progress, error: message)
            return true
        }
        if changed { publishAudioStatus(force: true, sessionID: token.owner) }
    }

    @discardableResult
    private func updateAudioStatus(
        sessionID: UUID? = nil,
        token: IOSRTPAudioPlaybackToken? = nil,
        force: Bool = false,
        _ update: (inout IOSCcapiRTPAudioStatus) -> Void
    ) -> Bool {
        let updated = audioStateLock.synchronized { () -> Bool in
            if let sessionID, activeAudioSessionID != sessionID { return false }
            if let token, activeAudioToken != token { return false }
            update(&audioStatus)
            return true
        }
        if updated { publishAudioStatus(force: force, sessionID: sessionID) }
        return updated
    }

    private func publishAudioStatus(force: Bool = false, sessionID: UUID? = nil) {
        let delivery = audioStateLock.synchronized { () -> IOSCcapiRTPDelivery? in
            if let sessionID, activeAudioSessionID != sessionID { return nil }
            let now = Date()
            guard force || now.timeIntervalSince(lastAudioStatusPublishedAt) >= audioStatusInterval else {
                return nil
            }
            lastAudioStatusPublishedAt = now
            return IOSCcapiRTPDelivery(owner: deliveryOwner, event: .audioStatus(audioStatus), audioEpoch: audioEpoch)
        }
        if let delivery { emit(delivery.event, owner: delivery.owner, audioEpoch: delivery.audioEpoch) }
    }

    private func publishAudioStatusIfNeeded(sessionID: UUID) {
        publishAudioStatus(sessionID: sessionID)
    }

    private func isActiveAudioToken(_ token: IOSRTPAudioPlaybackToken) -> Bool {
        audioStateLock.synchronized {
            activeAudioToken == token && audioStatus.enabled && applicationActive && monitoringActive
        }
    }

    @MainActor
    private func clearRendererSession() {
        videoProcessingUnavailable = false
        videoPresentation.status = videoLut == nil ? .off : .waiting
        renderedSessionID = nil
        deliveryContinuity = IOSRTPVideoDecodeContinuity()
        latestSPS = nil
        latestPPS = nil
        formatDescription = nil
        resetRenderer(removingImage: true)
    }

    @MainActor
    private func resetRenderer(removingImage: Bool) {
        videoProcessingGeneration = UUID()
        presentationPoll?.cancel(); presentationPoll = nil
        retainedOutputs.removeAll()
        videoPresentation.presentedFrame = nil
        videoPresentation.progress = .waiting
        if let owner = renderedSessionID {
            progress.begin(owner: owner, epoch: videoProcessingGeneration, lutRevision: lutRevision,
                           at: ProcessInfo.processInfo.systemUptime)
        }
        videoProcessor.invalidate()
        pixelMonitor.invalidate()
        needsKeyFrame = true
        let barrier = UUID()
        pixelReadbackBarrier = barrier
        guard let displayLayer else {
            pixelReadbackBarrier = nil
            return
        }
        // Removal is asynchronous. No readback may attribute pre-flush pixels
        // to a new owner/format; only this exact attachment/reset can reopen it.
        let attachmentID = ObjectIdentifier(displayLayer)
        flushRenderer(displayLayer, removingImage) { [weak self] in
            Task { @MainActor [weak self] in
                guard let self, let displayLayer = self.displayLayer, ObjectIdentifier(displayLayer) == attachmentID,
                      self.pixelReadbackBarrier == barrier else { return }
                self.pixelReadbackBarrier = nil
            }
        }
    }

    static func makeFormatDescription(sps: Data, pps: Data) throws -> CMVideoFormatDescription {
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

    static func makeSampleBuffer(
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
    private let readiness = CCAPIRTPReadinessGate()
    private var readinessProbeSubmitted = false
    private var decodedFirstFrame = false
    private var lastValidVideoAt: UInt64?
    private var videoFailureReported = false
    private var videoDelivery = IOSRTPVideoDeliveryGate()
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
                    guard let self else { return }
                    self.failVideo("Canon RTP video listener failed: \(error.localizedDescription)")
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

    func waitUntilReady() async throws {
        try await withTaskCancellationHandler {
            try await readiness.wait()
            // A failed candidate is distinct from cancellation, so confirmed
            // original cleanup can still permit AUTO to select the next source.
            let terminal = stateLock.synchronized { (closed, videoFailureReported) }
            if terminal.1 {
                throw CCAPIError.invalidResponse("Canon RTP source failed before first-frame readiness could be published.")
            }
            if terminal.0 { throw CancellationError() }
        } onCancel: { self.closeSynchronously() }
    }

    func setTargetFPS(_ fps: Int) async {
        stateLock.synchronized { targetFPS = min(max(fps, 1), 30) }
    }

    func close() async {
        closeSynchronously()
    }

    private func closeSynchronously(retireDelivery: Bool = true) {
        let resources = stateLock.synchronized { () -> (NWListener?, NWListener?, [NWConnection]) in
            if closed { return (nil, nil, []) }
            closed = true
            let resources = (videoListener, audioListener, connections)
            videoListener = nil
            audioListener = nil
            connections = []
            return resources
        }
        readiness.fail(CancellationError())
        sink.cancelReadinessProbe(sessionID: sessionID)
        resources.0?.cancel()
        resources.1?.cancel()
        resources.2.forEach { $0.cancel() }
        if retireDelivery { sink.endAudioSession(sessionID: sessionID) }
    }

    private func acceptVideo(_ connection: NWConnection) {
        let accepted = stateLock.synchronized { () -> Bool in
            guard !closed else { return false }
            connections.append(connection)
            return true
        }
        guard accepted else { connection.cancel(); return }
        connection.stateUpdateHandler = { [weak self, weak connection] state in
            guard let self, let connection else { return }
            switch state {
            case .ready:
                self.receiveVideo(on: connection)
            case let .failed(error):
                if !self.stateLock.synchronized({ self.closed }) {
                    self.failVideo("Canon RTP receive failed: \(error.localizedDescription)")
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
                // Parameter-set-only/SEI traffic is not a fresh video picture.
                if accessUnit.nalUnits.contains(where: { nal in
                    guard let first = nal.first, nal.count > 1 else { return false }
                    return [UInt8(1), UInt8(5)].contains(first & 0x1f)
                }) {
                    let now = DispatchTime.now().uptimeNanoseconds
                    let probe = self.stateLock.synchronized { () -> Bool in
                        guard !self.closed, !self.videoFailureReported else { return false }
                        if self.decodedFirstFrame { self.lastValidVideoAt = now }
                        guard !self.readinessProbeSubmitted, accessUnit.keyFrame,
                              self.latestSPS != nil, self.latestPPS != nil else { return false }
                        self.readinessProbeSubmitted = true
                        return true
                    }
                    if probe, let sps = self.latestSPS, let pps = self.latestPPS {
                        Task { @MainActor [weak self] in
                            await self?.decodeFirstFrame(accessUnit, sps: sps, pps: pps)
                        }
                    }
                    let targetFPS = self.stateLock.synchronized { self.targetFPS }
                    let interval = 1_000_000_000 / UInt64(max(targetFPS, 1))
                    let present = accessUnit.keyFrame || self.lastSubmittedAt == 0 || now - self.lastSubmittedAt >= interval
                    let ticket = self.stateLock.synchronized { () -> IOSRTPVideoDeliveryGate.Ticket? in
                        guard !self.closed, self.decodedFirstFrame else { return nil }
                        return self.videoDelivery.admit(keyFrame: accessUnit.keyFrame)
                    }
                    if let ticket {
                        if present { self.lastSubmittedAt = now }
                        let latestSPS = self.latestSPS
                        let latestPPS = self.latestPPS
                        Task { @MainActor [weak self] in
                            guard let self else { return }
                            defer { self.stateLock.synchronized { self.videoDelivery.complete() } }
                            guard !self.stateLock.synchronized({ self.closed }) else { return }
                            self.sink.enqueue(
                                accessUnit,
                                sequenceParameterSet: latestSPS,
                                pictureParameterSet: latestPPS,
                                sessionID: self.sessionID,
                                presentFrame: present,
                                deliverySequence: ticket.sequence
                            )
                        }
                    }
                }
            }
            if let error {
                if !self.stateLock.synchronized({ self.closed }) {
                    self.failVideo("Canon RTP receive failed: \(error.localizedDescription)")
                }
                return
            }
            if !self.stateLock.synchronized({ self.closed }) { self.receiveVideo(on: connection) }
        }
    }

    @MainActor
    private func decodeFirstFrame(_ accessUnit: CCAPIH264AccessUnit, sps: Data, pps: Data) async {
        guard !stateLock.synchronized({ closed || videoFailureReported }) else { return }
        do {
            let format = try IOSCcapiRTPController.makeFormatDescription(sps: sps, pps: pps)
            let sample = try IOSCcapiRTPController.makeSampleBuffer(accessUnit: accessUnit, formatDescription: format)
            let accepted = await sink.submitReadinessProbe(sample, format: format, sps: sps, sessionID: sessionID) { [weak self] result in
                guard let self, !self.stateLock.synchronized({ self.closed || self.videoFailureReported }) else { return }
                guard case .decoded = result else {
                    self.failVideo("Canon RTP could not decode the first video frame.")
                    return
                }
                // The shared probe has physically retired before this callback.
                // Reuse the startup AU once; an already-attached surface can prime
                // its continuous decoder without waiting for another IDR.
                let ticket = self.stateLock.synchronized { self.videoDelivery.admit(keyFrame: true) }
                if let ticket {
                    self.sink.enqueue(accessUnit, sequenceParameterSet: sps, pictureParameterSet: pps,
                        sessionID: self.sessionID, deliverySequence: ticket.sequence)
                    self.stateLock.synchronized { self.videoDelivery.complete() }
                }
                self.stateLock.synchronized {
                    self.decodedFirstFrame = true
                    self.lastValidVideoAt = DispatchTime.now().uptimeNanoseconds
                }
                self.readiness.decodedFrame()
                self.scheduleVideoWatchdog()
            }
            if !accepted { failVideo("Canon RTP first-frame decoder was unavailable.") }
        } catch { failVideo("Canon RTP first-frame decode failed: \(error.localizedDescription)") }
    }

    private func scheduleVideoWatchdog() {
        queue.asyncAfter(deadline: .now() + 1) { [weak self] in
            guard let self else { return }
            let state = self.stateLock.synchronized { (self.closed, self.videoFailureReported, self.lastValidVideoAt) }
            guard !state.0, !state.1, let last = state.2 else { return }
            if DispatchTime.now().uptimeNanoseconds - last >= 5_000_000_000 {
                self.failVideo("Canon RTP stopped returning valid video frames. Start Live View again after Stop is confirmed.")
            } else { self.scheduleVideoWatchdog() }
        }
    }

    private func failVideo(_ message: String) {
        let report = stateLock.synchronized { () -> Bool in
            guard !closed, !videoFailureReported else { return false }
            videoFailureReported = true
            return true
        }
        guard report else { return }
        readiness.fail(CCAPIError.invalidResponse(message))
        sink.cancelReadinessProbe(sessionID: sessionID)
        // Keep this exact delivery identity until the failure is consumed, while
        // retiring sockets immediately even if camera Stop must be deferred.
        closeSynchronously(retireDelivery: false)
        sink.emit(.failed(message), owner: sessionID)
    }

    private func acceptAudio(_ connection: NWConnection) {
        let accepted = stateLock.synchronized { () -> Bool in
            guard !closed else { return false }
            connections.append(connection)
            return true
        }
        guard accepted else { connection.cancel(); return }
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

// Monotonic bounds are independent of packet rate and wall-clock changes.
private let maximumAudioAccessUnitAge: TimeInterval = 1
private let audioPCMStallTimeout: TimeInterval = 5
private let audioFirstRenderTimeout: TimeInterval = 5
private let audioRenderStallTimeout: TimeInterval = 5
