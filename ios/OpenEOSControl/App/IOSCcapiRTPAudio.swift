import AVFoundation
import AudioToolbox
import Foundation
import OpenEOSCore

struct IOSCcapiRTPAudioPlaybackResult: Equatable {
    let decodedAccessUnits: Int
    let playedSampleFrames: Int
    let sampleRate: Int
    let channels: Int
    let droppedForBackpressure: Bool
}

final class IOSAACDecoder {
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
        guard let converter, let outputFormat else {
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

        guard let pcm = AVAudioPCMBuffer(
            pcmFormat: outputFormat,
            frameCapacity: AVAudioFrameCount(accessUnit.format.framesPerPacket * 2)
        ) else {
            throw audioError("AAC decoder could not allocate a PCM buffer.")
        }
        var supplied = false
        var conversionError: NSError?
        let status = converter.convert(to: pcm, error: &conversionError) { _, inputStatus in
            if supplied {
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
        case .endOfStream:
            return pcm.frameLength > 0 ? pcm : nil
        case .haveData, .inputRanDry:
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

final class IOSCcapiRTPAudioPipeline {
    private let decoder: IOSAACDecoder
    private let scheduleLock = NSLock()
    private var scheduledBuffers = 0
    private var engine: AVAudioEngine?
    private var player: AVAudioPlayerNode?
    private var playbackFormat: AVAudioFormat?

    init(decoder: IOSAACDecoder = IOSAACDecoder()) {
        self.decoder = decoder
    }

    func consume(_ accessUnit: CCAPIAACAccessUnit) throws -> IOSCcapiRTPAudioPlaybackResult {
        guard let pcm = try decoder.decode(accessUnit) else {
            return IOSCcapiRTPAudioPlaybackResult(
                decodedAccessUnits: 1,
                playedSampleFrames: 0,
                sampleRate: accessUnit.format.sampleRate,
                channels: accessUnit.format.channels,
                droppedForBackpressure: false
            )
        }
        try preparePlayback(format: pcm.format)
        let canSchedule = scheduleLock.synchronized { () -> Bool in
            guard scheduledBuffers < maximumScheduledAudioBuffers else { return false }
            scheduledBuffers += 1
            return true
        }
        guard canSchedule, let player else {
            return IOSCcapiRTPAudioPlaybackResult(
                decodedAccessUnits: 1,
                playedSampleFrames: 0,
                sampleRate: accessUnit.format.sampleRate,
                channels: accessUnit.format.channels,
                droppedForBackpressure: true
            )
        }
        player.scheduleBuffer(pcm, completionCallbackType: .dataConsumed) { [weak self] _ in
            self?.scheduleLock.synchronized {
                self?.scheduledBuffers = max((self?.scheduledBuffers ?? 1) - 1, 0)
            }
        }
        if !player.isPlaying { player.play() }
        return IOSCcapiRTPAudioPlaybackResult(
            decodedAccessUnits: 1,
            playedSampleFrames: Int(pcm.frameLength),
            sampleRate: Int(pcm.format.sampleRate.rounded()),
            channels: Int(pcm.format.channelCount),
            droppedForBackpressure: false
        )
    }

    func stop() {
        stopPlayback()
        decoder.reset()
    }

    private func stopPlayback() {
        player?.stop()
        engine?.stop()
        if let player { engine?.detach(player) }
        player = nil
        engine = nil
        playbackFormat = nil
        scheduleLock.synchronized { scheduledBuffers = 0 }
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    private func preparePlayback(format: AVAudioFormat) throws {
        if engine != nil, playbackFormat == format { return }
        stopPlayback()
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playback, mode: .moviePlayback)
        try session.setActive(true)

        let engine = AVAudioEngine()
        let player = AVAudioPlayerNode()
        engine.attach(player)
        engine.connect(player, to: engine.mainMixerNode, format: format)
        engine.prepare()
        try engine.start()
        player.play()
        self.engine = engine
        self.player = player
        playbackFormat = format
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
