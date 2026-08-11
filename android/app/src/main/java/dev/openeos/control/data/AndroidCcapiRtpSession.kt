package dev.openeos.control.data

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.Network
import android.os.Build
import android.os.SystemClock
import android.view.Surface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class AndroidCcapiRtpSessionFactory(
    private val network: Network,
) : CcapiRtpSessionFactory {
    override fun create(
        description: CcapiRtpSessionDescription,
        destinationAddress: String,
    ): NativeLiveViewSession = AndroidCcapiRtpSession(
        description = description,
        destinationAddress = destinationAddress,
        socketBinder = { socket -> network.bindSocket(socket) },
    )
}

internal fun interface DatagramSocketBinder {
    fun bind(socket: DatagramSocket)
}

internal class AndroidCcapiRtpSession(
    private val description: CcapiRtpSessionDescription,
    private val destinationAddress: String,
    private val socketBinder: DatagramSocketBinder,
    private val latmExtractorFactory: () -> LatmSampleExtractor = ::Media3LatmSampleExtractor,
    private val audioPlayerFactory: () -> RtpAudioPlayer = ::AndroidRtpAudioPlayer,
) : NativeLiveViewSession {
    override val source: LiveViewSource = LiveViewSource.CCAPI_RTP
    override val sourceUrl: String = "rtp://$destinationAddress:${description.video.port}"
    override val contentType: String = "video/H264"
    override val audioStatus: NativeLiveViewAudioStatus
        get() = audioStatusState.get()
    override val videoStatus: NativeLiveViewVideoStatus
        get() = NativeLiveViewVideoStatus(
            rtpPort = description.video.port,
            datagramsReceived = videoDatagramsReceived.get(),
            accessUnitsReceived = videoAccessUnitsReceived.get(),
            keyFramesReceived = videoKeyFramesReceived.get(),
            lastDatagramAtMillis = videoLastDatagramAtMillis.get().takeIf { it > 0 },
            lastAccessUnitAtMillis = videoLastAccessUnitAtMillis.get().takeIf { it > 0 },
            hasSequenceParameterSet = latestSps != null,
            hasPictureParameterSet = latestPps != null,
            ready = videoReady.get(),
            error = videoError.get(),
        )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val accessUnits = Channel<H264AccessUnit>(
        capacity = RTP_ACCESS_UNIT_QUEUE_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val audioAccessUnits = Channel<AacAccessUnit>(
        capacity = RTP_AUDIO_ACCESS_UNIT_QUEUE_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
        onUndeliveredElement = { recordDroppedAudioAccessUnit() },
    )
    private val listener = AtomicReference<((NativeLiveViewEvent) -> Unit)?>(null)
    private val targetFps = AtomicInteger(DEFAULT_RTP_RENDER_FPS)
    private val renderingEnabled = AtomicBoolean(true)
    private val started = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val firstDecodableVideo = CompletableDeferred<Unit>()
    private val videoDatagramsReceived = AtomicLong()
    private val videoAccessUnitsReceived = AtomicLong()
    private val videoKeyFramesReceived = AtomicLong()
    private val videoLastDatagramAtMillis = AtomicLong()
    private val videoLastAccessUnitAtMillis = AtomicLong()
    private val videoReady = AtomicBoolean(false)
    private val videoError = AtomicReference<String?>(null)
    private val audioEnabled = AtomicBoolean(false)
    private val surface = AtomicReference<Surface?>(null)
    private val decoderGuard = Any()
    private val audioPlayerGuard = Any()
    private val audioSupport = description.audio.latmAudioSupport()
    private val audioStatusState = AtomicReference(
        NativeLiveViewAudioStatus(
            advertised = description.audio != null,
            codec = description.audio?.codec,
            rtpPort = description.audio?.port,
            rtpClockRate = description.audio?.clockRate,
            channels = description.audio?.channels,
            error = audioSupport.reason.takeIf { description.audio != null && !audioSupport.supported },
        )
    )

    @Volatile
    private var videoSocket: DatagramSocket? = null
    @Volatile
    private var audioSocket: DatagramSocket? = null
    private var receiverJob: Job? = null
    private var decoderJob: Job? = null
    private var audioReceiverJob: Job? = null
    private var audioPlayerJob: Job? = null
    private val sdpParameterSets = description.video.h264ParameterSets()
    @Volatile
    private var latestSps: ByteArray? = sdpParameterSets?.sequenceParameterSet
    @Volatile
    private var latestPps: ByteArray? = sdpParameterSets?.pictureParameterSet

    override fun start() {
        check(!closed.get()) { "RTP Live View session is closed." }
        if (!started.compareAndSet(false, true)) return

        val datagramSocket = DatagramSocket(null).apply {
            reuseAddress = true
            receiveBufferSize = RTP_RECEIVE_BUFFER_BYTES
            soTimeout = RTP_SOCKET_TIMEOUT_MILLIS
        }
        try {
            socketBinder.bind(datagramSocket)
            datagramSocket.bind(InetSocketAddress(description.video.port))
        } catch (exception: Exception) {
            datagramSocket.close()
            started.set(false)
            throw IllegalStateException(
                "Unable to bind Canon RTP video port ${description.video.port} to the camera Wi-Fi network.",
                exception,
            )
        }
        videoSocket = datagramSocket
        receiverJob = scope.launch { receivePackets(datagramSocket) }
        startAudioReceiverIfSupported()
    }

    override suspend fun awaitReady(timeoutMillis: Long) {
        require(timeoutMillis > 0) { "RTP readiness timeout must be positive." }
        try {
            withTimeout(timeoutMillis) { firstDecodableVideo.await() }
        } catch (exception: TimeoutCancellationException) {
            val message = readinessFailureMessage(timeoutMillis)
            videoError.compareAndSet(null, message)
            throw IllegalStateException(message, exception)
        }
    }

    override fun attachSurface(surface: Surface) {
        if (closed.get() || !surface.isValid) return
        val previous = this.surface.getAndSet(surface)
        synchronized(decoderGuard) {
            if (previous === surface && decoderJob?.isActive == true) return
            decoderJob?.cancel()
            decoderJob = scope.launch { decodeTo(surface) }
        }
    }

    override fun detachSurface(surface: Surface) {
        if (!this.surface.compareAndSet(surface, null)) return
        synchronized(decoderGuard) {
            decoderJob?.cancel()
            decoderJob = null
        }
    }

    override fun setTargetFps(fps: Int) {
        targetFps.set(fps.coerceIn(MIN_RTP_RENDER_FPS, MAX_RTP_RENDER_FPS))
    }

    override fun setRenderingEnabled(enabled: Boolean) {
        renderingEnabled.set(enabled)
    }

    override fun setAudioEnabled(enabled: Boolean) {
        if (closed.get()) return
        if (!enabled) {
            stopAudioPlayer()
            return
        }
        if (!audioStatusState.get().available || !audioEnabled.compareAndSet(false, true)) return
        while (audioAccessUnits.tryReceive().isSuccess) Unit
        updateAudioStatus { it.copy(enabled = true, error = null) }
        synchronized(audioPlayerGuard) {
            audioPlayerJob?.cancel()
            audioPlayerJob = scope.launch {
                try {
                    audioPlayerFactory().play(audioAccessUnits, ::recordAudioPlaybackStats)
                } catch (_: CancellationException) {
                    throw CancellationException()
                } catch (exception: Exception) {
                    audioEnabled.set(false)
                    updateAudioStatus {
                        it.copy(
                            enabled = false,
                            error = "Canon RTP audio playback failed: ${exception.message ?: exception.javaClass.simpleName}",
                        )
                    }
                }
            }
        }
    }

    override fun setListener(listener: ((NativeLiveViewEvent) -> Unit)?) {
        this.listener.set(listener)
        listener?.invoke(NativeLiveViewEvent.AudioStatusChanged(audioStatusState.get()))
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        firstDecodableVideo.completeExceptionally(
            IllegalStateException("Canon RTP session closed before receiving a decodable H.264 key frame.")
        )
        listener.set(null)
        audioEnabled.set(false)
        videoSocket?.close()
        videoSocket = null
        audioSocket?.close()
        audioSocket = null
        receiverJob?.cancel()
        audioReceiverJob?.cancel()
        synchronized(audioPlayerGuard) {
            audioPlayerJob?.cancel()
            audioPlayerJob = null
        }
        synchronized(decoderGuard) {
            decoderJob?.cancel()
            decoderJob = null
        }
        accessUnits.close()
        audioAccessUnits.close()
        scope.cancel()
    }

    private fun startAudioReceiverIfSupported() {
        val audio = description.audio ?: return
        if (!audioSupport.supported) return
        if (audio.port == description.video.port) {
            updateAudioStatus {
                it.copy(error = "Canon RTP audio shares the video UDP port; RTP multiplexing is unsupported.")
            }
            return
        }
        val datagramSocket = DatagramSocket(null).apply {
            reuseAddress = true
            receiveBufferSize = RTP_AUDIO_RECEIVE_BUFFER_BYTES
            soTimeout = RTP_SOCKET_TIMEOUT_MILLIS
        }
        try {
            socketBinder.bind(datagramSocket)
            datagramSocket.bind(InetSocketAddress(audio.port))
        } catch (exception: Exception) {
            datagramSocket.close()
            updateAudioStatus {
                it.copy(
                    available = false,
                    error = "Unable to bind Canon RTP audio port ${audio.port}: ${exception.message ?: exception.javaClass.simpleName}",
                )
            }
            return
        }
        audioSocket = datagramSocket
        updateAudioStatus { it.copy(available = true, error = null) }
        audioReceiverJob = scope.launch { receiveAudioPackets(datagramSocket, audio) }
    }

    private suspend fun receivePackets(datagramSocket: DatagramSocket) {
        val depacketizer = H264RtpDepacketizer(description.video.payloadType)
        val buffer = ByteArray(MAX_RTP_DATAGRAM_BYTES)
        val packet = DatagramPacket(buffer, buffer.size)

        while (currentCoroutineContext().isActive && !closed.get()) {
            try {
                packet.length = buffer.size
                datagramSocket.receive(packet)
                val nowMillis = System.currentTimeMillis()
                videoDatagramsReceived.incrementAndGet()
                videoLastDatagramAtMillis.set(nowMillis)
                depacketizer.accept(packet.data, packet.length)?.let { accessUnit ->
                    videoAccessUnitsReceived.incrementAndGet()
                    videoLastAccessUnitAtMillis.set(nowMillis)
                    if (accessUnit.keyFrame) videoKeyFramesReceived.incrementAndGet()
                    accessUnit.sequenceParameterSet?.let { latestSps = it }
                    accessUnit.pictureParameterSet?.let { latestPps = it }
                    accessUnits.trySend(accessUnit)
                    if (accessUnit.keyFrame && latestSps != null && latestPps != null) {
                        videoReady.set(true)
                        videoError.set(null)
                        firstDecodableVideo.complete(Unit)
                    }
                }
            } catch (_: SocketTimeoutException) {
                currentCoroutineContext().ensureActive()
            } catch (_: CancellationException) {
                throw CancellationException()
            } catch (exception: Exception) {
                if (!closed.get()) {
                    val failure = IllegalStateException(
                        "Canon RTP receive failed: ${exception.message ?: exception.javaClass.simpleName}",
                        exception,
                    )
                    videoError.set(failure.message)
                    firstDecodableVideo.completeExceptionally(failure)
                    reportFailure("Canon RTP receive failed", exception)
                }
                return
            }
        }
    }

    private fun readinessFailureMessage(timeoutMillis: Long): String =
        "Canon RTP video did not receive a decodable H.264 key frame within ${timeoutMillis} ms " +
            "(UDP datagrams=${videoDatagramsReceived.get()}, " +
            "H.264 access units=${videoAccessUnitsReceived.get()}, " +
            "key frames=${videoKeyFramesReceived.get()}, " +
            "SPS=${latestSps != null}, PPS=${latestPps != null})."

    private suspend fun receiveAudioPackets(
        datagramSocket: DatagramSocket,
        audio: RtpMediaDescription,
    ) {
        val depacketizer = LatmRtpDepacketizer(audio.payloadType)
        var extractor = latmExtractorFactory()
        val buffer = ByteArray(MAX_RTP_DATAGRAM_BYTES)
        val packet = DatagramPacket(buffer, buffer.size)
        var firstTimestamp: Long? = null
        var lastPublishedAtMillis = 0L

        while (currentCoroutineContext().isActive && !closed.get()) {
            try {
                packet.length = buffer.size
                datagramSocket.receive(packet)
            } catch (_: SocketTimeoutException) {
                currentCoroutineContext().ensureActive()
                continue
            } catch (_: CancellationException) {
                throw CancellationException()
            } catch (exception: Exception) {
                if (!closed.get() && datagramSocket.isClosed.not()) {
                    stopAudioPlayer()
                    updateAudioStatus {
                        it.copy(
                            available = false,
                            enabled = false,
                            error = "Canon RTP audio receive failed: ${exception.message ?: exception.javaClass.simpleName}",
                        )
                    }
                }
                return
            }

            val nowMillis = System.currentTimeMillis()
            updateAudioStatus(notify = false) {
                it.copy(
                    packetsReceived = it.packetsReceived + 1,
                    lastPacketAtMillis = nowMillis,
                )
            }
            try {
                val mux = depacketizer.accept(packet.data, packet.length)
                if (mux != null) {
                    if (mux.discontinuity) extractor.reset()
                    val baseTimestamp = firstTimestamp ?: mux.rtpTimestamp.also { firstTimestamp = it }
                    val presentationTimeUs = rtpTimestampDelta(baseTimestamp, mux.rtpTimestamp) * 1_000_000L /
                        audio.clockRate
                    val samples = extractor.consume(mux, presentationTimeUs)
                    updateAudioStatus(notify = false) {
                        it.copy(
                            accessUnitsReceived = it.accessUnitsReceived + samples.size,
                            error = if (samples.isNotEmpty()) null else it.error,
                        )
                    }
                    if (audioEnabled.get()) {
                        samples.forEach { audioAccessUnits.trySend(it) }
                    }
                }
                if (lastPublishedAtMillis == 0L || nowMillis - lastPublishedAtMillis >= AUDIO_STATS_INTERVAL_MILLIS) {
                    lastPublishedAtMillis = nowMillis
                    publishAudioStatus()
                }
            } catch (_: CancellationException) {
                throw CancellationException()
            } catch (exception: Exception) {
                depacketizer.resetAfterDiscontinuity()
                extractor = latmExtractorFactory().also { it.reset() }
                recordDroppedAudioAccessUnit()
                updateAudioStatus(notify = false) {
                    it.copy(
                        error = "Canon RTP LATM parse failed: ${exception.message ?: exception.javaClass.simpleName}",
                    )
                }
                publishAudioStatus()
            }
        }
    }

    private suspend fun decodeTo(targetSurface: Surface) {
        while (accessUnits.tryReceive().isSuccess) Unit
        var codec: MediaCodec? = null
        try {
            codec = MediaCodec.createDecoderByType(MIME_AVC)
            val format = MediaFormat.createVideoFormat(MIME_AVC, DEFAULT_VIDEO_WIDTH, DEFAULT_VIDEO_HEIGHT).apply {
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_H264_DECODER_INPUT_BYTES)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(MediaFormat.KEY_OPERATING_RATE, MAX_RTP_RENDER_FPS)
                if (
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    runCatching {
                        codec.codecInfo.getCapabilitiesForType(MIME_AVC)
                            .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
                    }.getOrDefault(false)
                ) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
            }
            codec.configure(format, targetSurface, null, 0)
            codec.start()

            var needsKeyFrame = true
            var firstTimestamp: Long? = null
            var lastRenderedAtNanos = 0L
            var videoWidth = DEFAULT_VIDEO_WIDTH
            var videoHeight = DEFAULT_VIDEO_HEIGHT
            val bytesByPresentationTime = linkedMapOf<Long, Int>()
            val bufferInfo = MediaCodec.BufferInfo()

            while (currentCoroutineContext().isActive && surface.get() === targetSurface && targetSurface.isValid) {
                val accessUnit = accessUnits.receive()
                if (needsKeyFrame && !accessUnit.keyFrame) continue

                val encoded = if (needsKeyFrame) {
                    concatenateParameterSets(latestSps, latestPps, accessUnit.bytes)
                } else {
                    accessUnit.bytes
                }
                val baseTimestamp = firstTimestamp ?: accessUnit.rtpTimestamp.also { firstTimestamp = it }
                val presentationTimeUs = rtpTimestampDelta(baseTimestamp, accessUnit.rtpTimestamp) * 1_000_000L /
                    description.video.clockRate

                val queued = queueDecoderInput(codec, encoded, presentationTimeUs)
                if (queued) {
                    bytesByPresentationTime[presentationTimeUs] = encoded.size
                    while (bytesByPresentationTime.size > MAX_PENDING_FRAME_STATS) {
                        bytesByPresentationTime.remove(bytesByPresentationTime.keys.first())
                    }
                    needsKeyFrame = false
                }

                while (true) {
                    val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                    when {
                        outputIndex >= 0 -> {
                            val nowNanos = SystemClock.elapsedRealtimeNanos()
                            val frameInterval = 1_000_000_000L / targetFps.get().coerceAtLeast(1)
                            val render = renderingEnabled.get() &&
                                (lastRenderedAtNanos == 0L || nowNanos - lastRenderedAtNanos >= frameInterval)
                            codec.releaseOutputBuffer(outputIndex, render)
                            val encodedBytes = bytesByPresentationTime.remove(bufferInfo.presentationTimeUs) ?: encoded.size
                            if (render) {
                                lastRenderedAtNanos = nowNanos
                                listener.get()?.invoke(
                                    NativeLiveViewEvent.FrameRendered(
                                        encodedBytes = encodedBytes,
                                        atMillis = System.currentTimeMillis(),
                                        width = videoWidth,
                                        height = videoHeight,
                                    )
                                )
                            }
                        }

                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outputFormat = codec.outputFormat
                            videoWidth = outputFormat.getIntegerOrDefault(MediaFormat.KEY_WIDTH, videoWidth)
                            videoHeight = outputFormat.getIntegerOrDefault(MediaFormat.KEY_HEIGHT, videoHeight)
                            listener.get()?.invoke(NativeLiveViewEvent.VideoSizeChanged(videoWidth, videoHeight))
                        }

                        else -> break
                    }
                }
            }
        } catch (_: CancellationException) {
            throw CancellationException()
        } catch (exception: Exception) {
            if (!closed.get() && surface.get() === targetSurface) reportFailure("Canon H.264 decode failed", exception)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
        }
    }

    private fun queueDecoderInput(codec: MediaCodec, encoded: ByteArray, presentationTimeUs: Long): Boolean {
        val inputIndex = codec.dequeueInputBuffer(DECODER_INPUT_TIMEOUT_US)
        if (inputIndex < 0) return false
        val input = codec.getInputBuffer(inputIndex) ?: error("H.264 decoder returned no input buffer.")
        if (encoded.size > input.remaining()) {
            error("Canon H.264 access unit (${encoded.size} bytes) exceeds decoder input capacity (${input.remaining()} bytes).")
        }
        input.put(encoded)
        codec.queueInputBuffer(inputIndex, 0, encoded.size, presentationTimeUs, 0)
        return true
    }

    private fun reportFailure(prefix: String, exception: Exception) {
        videoError.set("$prefix: ${exception.message ?: exception.javaClass.simpleName}")
        listener.get()?.invoke(
            NativeLiveViewEvent.Failed(
                "$prefix: ${exception.message ?: exception.javaClass.simpleName}"
            )
        )
    }

    private fun stopAudioPlayer() {
        audioEnabled.set(false)
        synchronized(audioPlayerGuard) {
            audioPlayerJob?.cancel()
            audioPlayerJob = null
        }
        while (audioAccessUnits.tryReceive().isSuccess) Unit
        updateAudioStatus { it.copy(enabled = false) }
    }

    private fun recordAudioPlaybackStats(stats: RtpAudioPlaybackStats) {
        updateAudioStatus {
            it.copy(
                decodedAccessUnits = stats.decodedAccessUnits,
                playedSampleFrames = stats.playedSampleFrames,
                sampleRate = stats.sampleRate,
                channels = stats.channels ?: it.channels,
                underruns = stats.underruns,
                lastPcmAtMillis = stats.lastPcmAtMillis,
                error = null,
            )
        }
    }

    private fun recordDroppedAudioAccessUnit() {
        updateAudioStatus(notify = false) { it.copy(droppedAccessUnits = it.droppedAccessUnits + 1) }
    }

    private fun updateAudioStatus(
        notify: Boolean = true,
        transform: (NativeLiveViewAudioStatus) -> NativeLiveViewAudioStatus,
    ) {
        while (true) {
            val current = audioStatusState.get()
            val updated = transform(current)
            if (audioStatusState.compareAndSet(current, updated)) {
                if (notify) listener.get()?.invoke(NativeLiveViewEvent.AudioStatusChanged(updated))
                return
            }
        }
    }

    private fun publishAudioStatus() {
        listener.get()?.invoke(NativeLiveViewEvent.AudioStatusChanged(audioStatusState.get()))
    }
}

private fun concatenateParameterSets(sps: ByteArray?, pps: ByteArray?, frame: ByteArray): ByteArray {
    val prefixSize = (sps?.size ?: 0) + (pps?.size ?: 0)
    if (prefixSize == 0) return frame
    return ByteArray(prefixSize + frame.size).also { result ->
        var offset = 0
        sps?.let {
            it.copyInto(result, offset)
            offset += it.size
        }
        pps?.let {
            it.copyInto(result, offset)
            offset += it.size
        }
        frame.copyInto(result, offset)
    }
}

private fun rtpTimestampDelta(first: Long, current: Long): Long = (current - first) and 0xFFFF_FFFFL

private fun MediaFormat.getIntegerOrDefault(key: String, fallback: Int): Int =
    runCatching { getInteger(key) }.getOrDefault(fallback)

private const val MIME_AVC = "video/avc"
private const val DEFAULT_VIDEO_WIDTH = 1920
private const val DEFAULT_VIDEO_HEIGHT = 1080
private const val DEFAULT_RTP_RENDER_FPS = 30
private const val MIN_RTP_RENDER_FPS = 1
private const val MAX_RTP_RENDER_FPS = 30
private const val RTP_ACCESS_UNIT_QUEUE_CAPACITY = 3
private const val RTP_AUDIO_ACCESS_UNIT_QUEUE_CAPACITY = 24
private const val MAX_RTP_DATAGRAM_BYTES = 65_535
private const val RTP_RECEIVE_BUFFER_BYTES = 2 * 1024 * 1024
private const val RTP_AUDIO_RECEIVE_BUFFER_BYTES = 512 * 1024
private const val RTP_SOCKET_TIMEOUT_MILLIS = 1_000
private const val MAX_H264_DECODER_INPUT_BYTES = 8 * 1024 * 1024
private const val MAX_PENDING_FRAME_STATS = 16
private const val DECODER_INPUT_TIMEOUT_US = 20_000L
private const val AUDIO_STATS_INTERVAL_MILLIS = 500L
