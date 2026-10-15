import AVFoundation
import AudioToolbox
import Foundation
import OpenEOSCore

/// Identity of one explicit user enable. Session identity alone cannot revoke queued work.
struct IOSRTPAudioPlaybackToken: Equatable, Sendable {
    let owner: UUID
    let epoch: UUID
}

enum IOSRTPAudioOutputProgress: String, Equatable, Sendable {
    case inactive, waiting, unknown, rendering, stalled, failed
}

struct IOSCcapiRTPAudioPlaybackResult: Equatable {
    let decoderInputs: Int
    let decodedAccessUnits: Int
    let decodedSampleFrames: Int
    let submittedSampleFrames: Int
    let channels: Int
    let dropped: Bool
}

struct IOSRTPAudioRenderSnapshot {
    let decoderInputs: Int
    let decodedSampleFrames: Int
    let decodedAccessUnits: Int
    let lastDecodedAt: TimeInterval?
    let renderedSampleFrames: Int
    let lastRenderedAt: TimeInterval?
    let pendingBuffers: Int
    let supportsRenderProgress: Bool
    let expiredBuffers: Int
    let retiring: Bool
}

protocol IOSAACDecoding: AnyObject {
    func decode(_ accessUnit: CCAPIAACAccessUnit) throws -> AVAudioPCMBuffer?
    func reset()
    func drain() throws -> AVAudioPCMBuffer?
}

/// The completion means rendered by the player, never heard at the device/speaker.
enum IOSRTPAudioBufferCompletion: Equatable { case consumed, rendered }

protocol IOSRTPAudioOutput: AnyObject {
    var supportsRenderProgress: Bool { get }
    func prepare(format: AVAudioFormat) throws
    func schedule(_ buffer: AVAudioPCMBuffer, completion: @escaping (IOSRTPAudioBufferCompletion) -> Void)
    func stop()
}

final class IOSAACDecoder: IOSAACDecoding {
    private var converter: AVAudioConverter?
    private var configuredFormat: CCAPIAACStreamFormat?
    private var outputFormat: AVAudioFormat?

    func reset() {
        converter?.reset()
        converter = nil
        configuredFormat = nil
        outputFormat = nil
    }

    func decode(_ accessUnit: CCAPIAACAccessUnit) throws -> AVAudioPCMBuffer? {
        if accessUnit.discontinuity || configuredFormat != accessUnit.format {
            try configure(accessUnit.format)
        }
        guard let converter else {
            throw audioError("AAC decoder is not configured.")
        }

        let compressed = AVAudioCompressedBuffer(
            format: converter.inputFormat,
            packetCapacity: 1,
            maximumPacketSize: accessUnit.bytes.count
        )
        accessUnit.bytes.withUnsafeBytes { bytes in
            if let baseAddress = bytes.baseAddress {
                memcpy(compressed.data, baseAddress, bytes.count)
            }
        }
        compressed.byteLength = UInt32(accessUnit.bytes.count)
        compressed.packetCount = 1
        guard let packetDescription = compressed.packetDescriptions else {
            throw audioError("AAC decoder did not allocate a packet description.")
        }
        packetDescription.pointee = AudioStreamPacketDescription(
            mStartOffset: 0,
            mVariableFramesInPacket: UInt32(accessUnit.format.framesPerPacket),
            mDataByteSize: UInt32(accessUnit.bytes.count)
        )

        return try convert(compressed)
    }

    /// Poll delayed converter output without closing a live stream. No new RTP AU
    /// is required and no synthetic end-of-stream is sent during a packet gap.
    func drain() throws -> AVAudioPCMBuffer? {
        guard converter != nil else { return nil }
        return try convert(nil)
    }

    private func convert(_ compressed: AVAudioCompressedBuffer?) throws -> AVAudioPCMBuffer? {
        guard let converter, let outputFormat, let configuredFormat,
              let pcm = AVAudioPCMBuffer(pcmFormat: outputFormat,
                                        frameCapacity: AVAudioFrameCount(configuredFormat.framesPerPacket * 2)) else {
            throw audioError("AAC decoder could not allocate a PCM buffer.")
        }
        var supplied = false
        var conversionError: NSError?
        let status = converter.convert(to: pcm, error: &conversionError) { _, inputStatus in
            guard let compressed, !supplied else {
                inputStatus.pointee = .noDataNow
                return nil
            }
            supplied = true
            inputStatus.pointee = .haveData
            return compressed
        }
        if let conversionError { throw conversionError }
        switch status {
        case .error:
            throw audioError("AAC decoder returned an unspecified conversion error.")
        case .endOfStream, .haveData, .inputRanDry:
            return pcm.frameLength > 0 ? pcm : nil
        @unknown default:
            throw audioError("AAC decoder returned an unknown conversion status.")
        }
    }

    private func configure(_ format: CCAPIAACStreamFormat) throws {
        reset()
        guard format.sampleRate > 0, (1...2).contains(format.channels), format.framesPerPacket > 0 else {
            throw audioError("Canon AAC format is invalid.")
        }
        var inputDescription = AudioStreamBasicDescription(
            mSampleRate: Float64(format.sampleRate),
            mFormatID: kAudioFormatMPEG4AAC,
            mFormatFlags: AudioFormatFlags(MPEG4ObjectID.AAC_LC.rawValue),
            mBytesPerPacket: 0,
            mFramesPerPacket: UInt32(format.framesPerPacket),
            mBytesPerFrame: 0,
            mChannelsPerFrame: UInt32(format.channels),
            mBitsPerChannel: 0,
            mReserved: 0
        )
        guard let inputFormat = AVAudioFormat(streamDescription: &inputDescription),
              let outputFormat = AVAudioFormat(
                  commonFormat: .pcmFormatFloat32,
                  sampleRate: Float64(format.sampleRate),
                  channels: AVAudioChannelCount(format.channels),
                  interleaved: false
              ),
              let converter = AVAudioConverter(from: inputFormat, to: outputFormat) else {
            throw audioError("iOS does not provide an AAC-LC decoder for Canon RTP audio.")
        }
        converter.magicCookie = format.audioSpecificConfig
        converter.primeMethod = .none
        self.converter = converter
        self.configuredFormat = format
        self.outputFormat = outputFormat
    }
}

/// Decode calls/reset are confined to the controller's serial audio queue. The output
/// lock is independent: mute/background can retire the player while decode is in flight.
final class IOSCcapiRTPAudioPipeline {
    private let decoder: any IOSAACDecoding
    private let output: any IOSRTPAudioOutput
    private let now: () -> TimeInterval
    private let outputLock = NSLock()
    private let callbackQueue = DispatchQueue(label: "dev.openeos.control.audio-render-callback")
    // Native calls never hold outputLock. A hung device operation must not block
    // logical revocation/watchdog; all replacement devices wait behind retirement.
    private let nativeQueue = DispatchQueue(label: "dev.openeos.control.audio-native-output")
    private var nativeOutputInUse = false
    private var retirementPending = false
    private var decoderOwner: IOSRTPAudioPlaybackToken?
    private var decoderRetirementPending = false
    private var retirementObserver: (() -> Void)?
    private var token: IOSRTPAudioPlaybackToken?
    private var decoderToken: IOSRTPAudioPlaybackToken?
    private var outputIncarnation = UUID()
    private var playbackFormat: AVAudioFormat? // nativeQueue only
    private var pendingBuffers: [UUID: TimeInterval] = [:]
    private var outputExpiryWatch: DispatchSourceTimer?
    private var expiredBuffers = 0
    // Converter input provenance stays on the serial decode queue. Delayed PCM
    // inherits the oldest contributing input deadline, not the time of draining.
    private var pendingDecoderInputs: [(remainingFrames: Int, expiresAt: TimeInterval)] = []
    private var decoderFormat: CCAPIAACStreamFormat?
    private var observedDecoderInputs = 0
    private var decodedSampleFrames = 0
    private var decodedAccessUnits = 0
    private var lastDecodedAt: TimeInterval?
    private var renderedSampleFrames = 0
    private var lastRenderedAt: TimeInterval?

    init(decoder: any IOSAACDecoding = IOSAACDecoder(),
         output: any IOSRTPAudioOutput = IOSAVAudioEngineOutput(),
         now: @escaping () -> TimeInterval = { ProcessInfo.processInfo.systemUptime }) {
        self.decoder = decoder
        self.output = output
        self.now = now
    }

    func activate(_ token: IOSRTPAudioPlaybackToken) {
        outputLock.synchronized {
            retireOutputLocked()
            self.token = token
            decoderRetirementPending = decoderOwner != nil && decoderOwner != token
            observedDecoderInputs = 0
            decodedSampleFrames = 0
            decodedAccessUnits = 0
            lastDecodedAt = nil
            renderedSampleFrames = 0
            expiredBuffers = 0
            lastRenderedAt = nil
        }
    }

    var isRetiring: Bool { outputLock.synchronized { retirementPending || decoderRetirementPending } }

    func setRetirementObserver(_ observer: @escaping () -> Void) {
        outputLock.synchronized { retirementObserver = observer }
    }

    /// Synchronous logical revocation precedes serial native retirement. If a native
    /// call is blocked, retain it as retiring; never pretend physical silence/closure.
    func retire() {
        outputLock.synchronized {
            token = nil
            decoderRetirementPending = decoderOwner != nil
            retireOutputLocked()
        }
    }

    /// Runs after the in-flight converter returns, before any subsequent queued decode.
    /// A newer epoch's decoder must not be reset by an old retirement barrier.
    func resetRetiredDecoder() {
        let retired = outputLock.synchronized { decoderToken != token || token == nil }
        if retired {
            decoder.reset(); decoderToken = nil; decoderFormat = nil
            pendingDecoderInputs.removeAll()
            let observer = outputLock.synchronized { () -> (() -> Void)? in
                decoderOwner = nil
                decoderRetirementPending = false
                return retirementObserver
            }
            observer?()
        }
    }

    func consume(_ accessUnit: CCAPIAACAccessUnit, token expected: IOSRTPAudioPlaybackToken,
                 expiresAt: TimeInterval) throws -> IOSCcapiRTPAudioPlaybackResult? {
        guard outputLock.synchronized({ token == expected }) else { return nil }
        guard now() < expiresAt else { return result(accessUnit.format, dropped: true) }
        if accessUnit.discontinuity || decoderFormat != accessUnit.format {
            outputLock.synchronized {
                if token == expected { retireOutputLocked() }
            }
        }
        let admitted = outputLock.synchronized { () -> Bool in
            guard token == expected else { return false }
            decoderOwner = expected
            return true
        }
        guard admitted else { return nil }
        if decoderToken != expected || decoderFormat != accessUnit.format || accessUnit.discontinuity
            || pendingDecoderInputs.first.map({ now() >= $0.expiresAt }) == true {
            decoder.reset()
            pendingDecoderInputs.removeAll()
        }
        guard outputLock.synchronized({ token == expected }), now() < expiresAt else { return nil }
        decoderToken = expected
        decoderFormat = accessUnit.format
        // Bound even a converter that accepts every AU but never produces PCM.
        guard pendingDecoderInputs.count < maximumPendingDecoderInputs else {
            return result(accessUnit.format, dropped: true)
        }
        pendingDecoderInputs.append((accessUnit.format.framesPerPacket, expiresAt))
        let mayDecode = outputLock.synchronized { () -> Bool in
            guard token == expected else { return false }
            observedDecoderInputs += 1
            return true
        }
        guard mayDecode else { return nil }
        let pcm = try decoder.decode(accessUnit)
        return try submitDecodedPCM(pcm, format: accessUnit.format, inputs: 1,
                                    discontinuity: accessUnit.discontinuity, token: expected)
    }

    func drain(token expected: IOSRTPAudioPlaybackToken) throws -> IOSCcapiRTPAudioPlaybackResult? {
        guard outputLock.synchronized({ token == expected }), decoderToken == expected,
              let format = decoderFormat, let oldest = pendingDecoderInputs.first else { return nil }
        guard now() < oldest.expiresAt else {
            decoder.reset(); pendingDecoderInputs.removeAll()
            return result(format, dropped: true)
        }
        return try submitDecodedPCM(decoder.drain(), format: format, inputs: 0,
                                    discontinuity: false, token: expected)
    }

    private func submitDecodedPCM(_ pcm: AVAudioPCMBuffer?, format: CCAPIAACStreamFormat,
                                  inputs: Int, discontinuity: Bool,
                                  token expected: IOSRTPAudioPlaybackToken) throws -> IOSCcapiRTPAudioPlaybackResult? {
        guard let pcm, pcm.frameLength > 0 else { return result(format, inputs: inputs) }
        guard let oldest = pendingDecoderInputs.first else { return result(format, inputs: inputs, dropped: true) }
        let expiresAt = oldest.expiresAt
        var remaining = Int(pcm.frameLength)
        while remaining > 0, !pendingDecoderInputs.isEmpty {
            let consumed = min(remaining, pendingDecoderInputs[0].remainingFrames)
            pendingDecoderInputs[0].remainingFrames -= consumed
            remaining -= consumed
            if pendingDecoderInputs[0].remainingFrames == 0 { pendingDecoderInputs.removeFirst() }
        }
        guard remaining == 0 else { return result(format, inputs: inputs, dropped: true) }
        let fresh = outputLock.synchronized { () -> Bool in
            guard token == expected, now() < expiresAt else { return false }
            decodedSampleFrames += Int(pcm.frameLength)
            decodedAccessUnits += 1
            lastDecodedAt = now()
            return true
        }
        guard fresh else { return result(format, inputs: inputs, dropped: true) }
        return try nativeQueue.sync {
            let admitted = outputLock.synchronized { () -> UUID? in
                guard token == expected, now() < expiresAt else { return nil }
                // Expired native buffers retire before another physical output.
                if pendingBuffers.values.contains(where: { now() >= $0 }) {
                    expiredBuffers += pendingBuffers.count
                    retireOutputLocked()
                    return nil
                }
                nativeOutputInUse = true
                return outputIncarnation
            }
            guard var incarnation = admitted else { return result(format, inputs: inputs, dropped: true) }
            if discontinuity || playbackFormat != pcm.format {
                incarnation = outputLock.synchronized {
                    invalidateOutputLocked()
                    return outputIncarnation
                }
                output.stop()
                playbackFormat = nil
                let mayPrepare = outputLock.synchronized { () -> Bool in
                    nativeOutputInUse = false
                    guard token == expected, outputIncarnation == incarnation, now() < expiresAt else {
                        retireOutputLocked()
                        return false
                    }
                    retirementPending = false
                    nativeOutputInUse = true
                    return true
                }
                guard mayPrepare else { return nil }
                do { try output.prepare(format: pcm.format) }
                catch {
                    outputLock.synchronized { retireOutputLocked() }
                    throw error
                }
                playbackFormat = pcm.format
                let observer = outputLock.synchronized { () -> (() -> Void)? in
                    guard token == expected, outputIncarnation == incarnation else { return nil }
                    retirementPending = false
                    return retirementObserver
                }
                observer?()
            }
            let bufferID = UUID()
            let frames = Int(pcm.frameLength)
            let reserved = outputLock.synchronized { () -> Bool in
                guard token == expected, outputIncarnation == incarnation, now() < expiresAt,
                      pendingBuffers.count < maximumScheduledAudioBuffers else { return false }
                pendingBuffers[bufferID] = expiresAt
                armOutputExpiryLocked()
                return true
            }
            guard reserved else {
                let current = outputLock.synchronized { () -> Bool in
                    guard token == expected, outputIncarnation == incarnation, now() < expiresAt else {
                        retireOutputLocked()
                        return false
                    }
                    return true
                }
                return result(format, inputs: inputs, pcm: current ? frames : 0, dropped: true)
            }
            let scheduledIncarnation = incarnation
            output.schedule(pcm) { [weak self] completion in
                // Never take outputLock on the native render callback thread: stop()
                // may wait for that callback. An old callback cannot decrement B's queue.
                guard let self else { return }
                let renderedAt = self.now()
                self.callbackQueue.async { [weak self] in
                    guard let self else { return }
                    self.outputLock.synchronized {
                        guard self.token == expected, self.outputIncarnation == scheduledIncarnation,
                              let deadline = self.pendingBuffers.removeValue(forKey: bufferID) else { return }
                        if completion == .rendered && self.output.supportsRenderProgress && renderedAt < deadline {
                            self.renderedSampleFrames += frames
                            self.lastRenderedAt = max(self.lastRenderedAt ?? renderedAt, renderedAt)
                        }
                        self.armOutputExpiryLocked()
                    }
                }
            }
            // A mute may arrive while schedule itself is blocked. Its pending stop
            // owns retirement; no later epoch's native prepare can overtake that stop.
            guard outputLock.synchronized({ token == expected && outputIncarnation == scheduledIncarnation }) else { return nil }
            guard now() < expiresAt else {
                outputLock.synchronized { retireOutputLocked() }
                return result(format, inputs: inputs, dropped: true)
            }
            return result(format, inputs: inputs, pcm: frames, submitted: frames)
        }
    }

    func snapshot(for expected: IOSRTPAudioPlaybackToken) -> IOSRTPAudioRenderSnapshot? {
        outputLock.synchronized {
            guard token == expected else { return nil }
            expireOutputIfNeededLocked()
            return IOSRTPAudioRenderSnapshot(decoderInputs: observedDecoderInputs,
                                             decodedSampleFrames: decodedSampleFrames,
                                             decodedAccessUnits: decodedAccessUnits, lastDecodedAt: lastDecodedAt,
                                             renderedSampleFrames: renderedSampleFrames,
                                             lastRenderedAt: lastRenderedAt,
                                             pendingBuffers: pendingBuffers.count,
                                             supportsRenderProgress: output.supportsRenderProgress, expiredBuffers: expiredBuffers,
                                             retiring: retirementPending || decoderRetirementPending)
        }
    }

    private func expireOutputIfNeededLocked() {
        if pendingBuffers.values.contains(where: { now() >= $0 }) {
            expiredBuffers += pendingBuffers.count
            retireOutputLocked()
        }
    }

    private func armOutputExpiryLocked() {
        outputExpiryWatch?.cancel()
        outputExpiryWatch = nil
        guard let deadline = pendingBuffers.values.min() else { return }
        let incarnation = outputIncarnation
        let timer = DispatchSource.makeTimerSource(queue: callbackQueue)
        timer.schedule(deadline: .now() + max(0, deadline - now()))
        timer.setEventHandler { [weak self] in
            guard let self else { return }
            self.outputLock.synchronized {
                guard self.outputIncarnation == incarnation else { return }
                self.expireOutputIfNeededLocked()
            }
        }
        outputExpiryWatch = timer
        timer.resume()
    }

    private func invalidateOutputLocked() {
        outputIncarnation = UUID()
        outputExpiryWatch?.cancel()
        outputExpiryWatch = nil
        pendingBuffers.removeAll()
        retirementPending = retirementPending || nativeOutputInUse
    }

    private func retireOutputLocked() {
        invalidateOutputLocked()
        let incarnation = outputIncarnation
        nativeQueue.async { [self] in
            // May block in the OS. Control state/snapshot never waits on this queue.
            output.stop()
            playbackFormat = nil
            let observer = outputLock.synchronized { () -> (() -> Void)? in
                nativeOutputInUse = false
                if outputIncarnation == incarnation { retirementPending = false }
                return retirementObserver
            }
            observer?()
        }
    }

    private func result(_ format: CCAPIAACStreamFormat, inputs: Int = 0, pcm: Int = 0,
                        submitted: Int = 0, dropped: Bool = false) -> IOSCcapiRTPAudioPlaybackResult {
        IOSCcapiRTPAudioPlaybackResult(decoderInputs: inputs, decodedAccessUnits: pcm > 0 ? 1 : 0,
                                      decodedSampleFrames: pcm, submittedSampleFrames: submitted,
                                      channels: format.channels, dropped: dropped)
    }
}

final class IOSAVAudioEngineOutput: IOSRTPAudioOutput {
    let supportsRenderProgress = true
    private var engine: AVAudioEngine?
    private var player: AVAudioPlayerNode?
    private var ownsAudioSession = false

    func prepare(format: AVAudioFormat) throws {
        stop()
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playback, mode: .moviePlayback)
            try session.setActive(true)
            ownsAudioSession = true
            let engine = AVAudioEngine()
            let player = AVAudioPlayerNode()
            self.engine = engine
            self.player = player
            engine.attach(player)
            engine.connect(player, to: engine.mainMixerNode, format: format)
            engine.prepare()
            try engine.start()
        } catch {
            stop()
            throw error
        }
    }

    func schedule(_ buffer: AVAudioPCMBuffer, completion: @escaping (IOSRTPAudioBufferCompletion) -> Void) {
        guard let player else { return }
        // Apple documents dataRendered as player rendering, excluding downstream
        // processing/device latency. This is deliberately not an audibility claim.
        player.scheduleBuffer(buffer, completionCallbackType: .dataRendered) { type in
            if type == .dataRendered { completion(.rendered) }
        }
        if !player.isPlaying { player.play() }
    }

    func stop() {
        player?.stop()
        engine?.stop()
        if let player { engine?.detach(player) }
        player = nil
        engine = nil
        if ownsAudioSession {
            ownsAudioSession = false
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        }
    }
}

private func audioError(_ description: String) -> NSError {
    NSError(
        domain: "OpenEOSControl.CCAPIRTPAudio",
        code: -1,
        userInfo: [NSLocalizedDescriptionKey: description]
    )
}

private extension NSLock {
    func synchronized<T>(_ operation: () throws -> T) rethrows -> T {
        lock()
        defer { unlock() }
        return try operation()
    }
}

private let maximumScheduledAudioBuffers = 8

private let maximumPendingDecoderInputs = 64
