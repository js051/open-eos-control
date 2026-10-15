package dev.openeos.control.data

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.Looper
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
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
    private val videoLut = AtomicReference<NativeVideoLut?>(null)
    private val videoLutState = MutableStateFlow(NativeVideoLutStatus.OFF)
    override val supportsVideoLut = true
    override val videoLutStatus = videoLutState.asStateFlow()
    private val renderedFrameState = MutableStateFlow<NativeLiveViewRenderedFrame?>(null)
    override val renderedFrames = renderedFrameState.asStateFlow()
    private val decoderGeneration = AtomicLong()
    private val renderingGeneration = AtomicLong()
    private val renderedSequence = AtomicLong()
    private val videoFormatGeneration = AtomicLong()
    private val lutRevision = AtomicLong()
    private val frameProgress = NativeRtpFrameProgress()
    private val monitorSamplingEnabled = AtomicBoolean(false)
    private val videoLastDecodedAtMillis = AtomicLong()
    private val videoLastPresentationAtMillis = AtomicLong()
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
            ready = videoReady.get() && !closed.get() && !terminalFailure.get(),
            error = videoError.get(),
            decodedFrames = frameProgress.decodedCount,
            presentationFrames = frameProgress.presentedCount,
            lastDecodedAtMillis = videoLastDecodedAtMillis.get().takeIf { it > 0 },
            lastPresentationAtMillis = videoLastPresentationAtMillis.get().takeIf { it > 0 },
        )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val accessUnits = Channel<Pair<NativeRtpInputIdentity, H264AccessUnit>>(
        capacity = RTP_ACCESS_UNIT_QUEUE_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
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
    private val terminalFailure = AtomicBoolean(false)
    private val inputDeadline = LiveViewInactivityDeadline(RTP_VIDEO_INACTIVITY_NANOS)
    private val presentationDeadline = LiveViewInactivityDeadline(RTP_VIDEO_INACTIVITY_NANOS)
    private val decodedDeadline = LiveViewInactivityDeadline(RTP_VIDEO_INACTIVITY_NANOS)
    private val surfaceWait = NativeRtpSurfaceWait(RTP_VIDEO_INACTIVITY_NANOS)
    private val surface = AtomicReference<Surface?>(null)
    private val decoderGuard = Any()
    private val audioSupport = description.audio.latmAudioSupport()
    private val audioParseDrops = AtomicLong()
    private val audioStatusState = AtomicReference(
        NativeLiveViewAudioStatus(
            advertised = description.audio != null,
            state = if (description.audio != null && audioSupport.supported) "muted" else "unavailable",
            codec = description.audio?.codec,
            rtpPort = description.audio?.port,
            rtpClockRate = description.audio?.clockRate,
            channels = description.audio?.channels,
            error = audioSupport.reason.takeIf { description.audio != null && !audioSupport.supported },
        )
    )

    private val audioPlayback = RtpAudioPlaybackOwner(scope, audioPlayerFactory, ::recordAudioOwnerStatus)

    @Volatile
    private var videoSocket: DatagramSocket? = null
    @Volatile
    private var audioSocket: DatagramSocket? = null
    private var receiverJob: Job? = null
    private var decoderJob: Job? = null
    private var audioReceiverJob: Job? = null
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
        inputDeadline.arm(System.nanoTime())
        receiverJob = scope.launch { receivePackets(datagramSocket) }
        scope.launch {
            while (isActive && !closed.get()) {
                delay(RTP_WATCHDOG_INTERVAL_MILLIS)
                val now = System.nanoTime()
                // Timeout ownership is decided atomically with attach/pause/retirement transitions.
                synchronized(decoderGuard) {
                    when {
                        inputDeadline.expired(now) -> reportFailure("Canon RTP video stopped", IllegalStateException(
                            "No complete H.264 picture arrived within 5 seconds."))
                        decodedDeadline.expired(now) -> reportFailure("Canon RTP decoder stopped", IllegalStateException(
                            "No decoded H.264 picture arrived within 5 seconds of decoder start or the previous output."))
                        surfaceWait.expired(now) -> reportFailure("RTP preview surface unavailable", IllegalStateException(
                            "No usable preview Surface was attached within 5 seconds of decoded readiness, detach, or resume. Preview stopped; no display presentation is claimed."))
                        presentationDeadline.expired(now) -> reportFailure("Canon RTP presentation stopped", IllegalStateException(
                            "No native presentation progress within 5 seconds of surface attachment, resume, or the previous frame."))
                    }
                }
            }
        }
        startAudioReceiverIfSupported()
    }

    override suspend fun awaitReady(timeoutMillis: Long) {
        require(timeoutMillis > 0) { "RTP readiness timeout must be positive." }
        try {
            withTimeout(timeoutMillis) { firstDecodableVideo.await() }
            check(!closed.get() && !terminalFailure.get()) { videoError.get() ?: "Canon RTP session retired before readiness publication." }
        } catch (exception: TimeoutCancellationException) {
            val message = readinessFailureMessage(timeoutMillis)
            reportFailure("Canon RTP readiness failed", IllegalStateException(message, exception))
            throw IllegalStateException(message, exception)
        }
    }

    override fun attachSurface(surface: Surface): Unit = synchronized(decoderGuard) {
        if (closed.get() || !surface.isValid) return
        val previous = this.surface.getAndSet(surface)
        surfaceWait.onSurfaceAttached()
        if (previous === surface && decoderJob?.isActive == true) return
        // Surface wait ends here; restart arms the separate native-presentation watchdog.
        restartDecoder(surface)
    }

    override fun setVideoLut(lut: NativeVideoLut?): Unit = synchronized(decoderGuard) {
        if (closed.get() || videoLut.getAndSet(lut) === lut) return
        lutRevision.incrementAndGet()
        renderedFrameState.value = null
        videoLutState.value = if (lut == null) NativeVideoLutStatus.OFF else NativeVideoLutStatus.WAITING
        surface.get()?.takeIf { it.isValid }?.let(::restartDecoder)
    }

    override fun setMonitorSamplingEnabled(enabled: Boolean) {
        monitorSamplingEnabled.set(enabled && !closed.get())
    }

    private fun restartDecoder(target: Surface?) = synchronized(decoderGuard) {
        val generation = decoderGeneration.incrementAndGet()
        frameProgress.reset(generation)
        videoReady.set(false)
        decodedDeadline.arm(System.nanoTime())
        if (target != null && renderingEnabled.get() && !closed.get()) presentationDeadline.arm(System.nanoTime())
        renderedFrameState.value = null
        val previous = decoderJob
        previous?.cancel()
        // Retired decoder/EGL writers must really drain before the replacement owns this Surface.
        decoderJob = rtpSurfaceWriters.launch(scope, target, previous) {
            if (!closed.get() && decoderGeneration.get() == generation && surface.get() === target) {
                decodeTo(target, generation)
            }
        }
    }

    override fun detachSurface(surface: Surface): Unit = synchronized(decoderGuard) {
        if (!this.surface.compareAndSet(surface, null)) return
        presentationDeadline.disarm()
        decodedDeadline.disarm()
        surfaceWait.onSurfaceDetached(System.nanoTime())
        decoderGeneration.incrementAndGet()
        renderedFrameState.value = null
        decoderJob?.cancel()
        // Keep the cancelled job as a drain dependency for a subsequent attach.
    }

    override fun setTargetFps(fps: Int) {
        targetFps.set(fps.coerceIn(MIN_RTP_RENDER_FPS, MAX_RTP_RENDER_FPS))
    }

    override fun setRenderingEnabled(enabled: Boolean): Unit = synchronized(decoderGuard) {
        if (closed.get()) return
        if (renderingEnabled.getAndSet(enabled) != enabled) {
            surfaceWait.setRenderingEnabled(enabled, System.nanoTime())
            renderingGeneration.incrementAndGet()
            if (enabled && surface.get()?.isValid == true) presentationDeadline.arm(System.nanoTime())
        }
        audioPlayback.setForeground(enabled)
        if (!enabled) {
            presentationDeadline.disarm()
            renderedFrameState.value = null
        }
    }

    override fun setAudioEnabled(enabled: Boolean) {
        if (closed.get()) return
        if (!enabled) audioPlayback.mute()
        else if (audioStatusState.get().available && renderingEnabled.get()) audioPlayback.enable()
    }

    override fun setListener(listener: ((NativeLiveViewEvent) -> Unit)?) {
        this.listener.set(listener)
        listener?.invoke(NativeLiveViewEvent.AudioStatusChanged(audioStatusState.get()))
        // Readiness may finish before the App subscribes. Never lose a terminal receiver error.
        if (terminalFailure.get()) videoError.get()?.let { listener?.invoke(NativeLiveViewEvent.Failed(it)) }
    }

    override fun close(): Unit = synchronized(decoderGuard) {
        if (!closed.compareAndSet(false, true)) return
        inputDeadline.disarm()
        presentationDeadline.disarm()
        decodedDeadline.disarm()
        surfaceWait.retire()
        videoReady.set(false)
        renderingEnabled.set(false)
        decoderGeneration.incrementAndGet()
        renderedFrameState.value = null
        firstDecodableVideo.completeExceptionally(
            IllegalStateException("Canon RTP session closed before producing a decoded H.264 picture.")
        )
        listener.set(null)
        videoSocket?.close()
        videoSocket = null
        audioSocket?.close()
        audioSocket = null
        receiverJob?.cancel()
        audioReceiverJob?.cancel()
        audioPlayback.close()
        synchronized(decoderGuard) {
            decoderJob?.cancel()
            decoderJob = null
        }
        accessUnits.close()
        scope.cancel()
    }

    private fun startAudioReceiverIfSupported() {
        val audio = description.audio ?: return
        if (!audioSupport.supported) return
        if (audio.port == description.video.port) {
            updateAudioStatus {
                it.copy(state = "unavailable", error = "Canon RTP audio shares the video UDP port; RTP multiplexing is unsupported.")
            }
            return
        }
        val datagramSocket = try { DatagramSocket(null) } catch (exception: Exception) {
            updateAudioStatus { it.copy(available = false, state = "unavailable",
                error = "Unable to create Canon RTP audio receiver: ${exception.message ?: exception.javaClass.simpleName}") }
            return
        }
        try {
            datagramSocket.reuseAddress = true
            datagramSocket.receiveBufferSize = RTP_AUDIO_RECEIVE_BUFFER_BYTES
            datagramSocket.soTimeout = RTP_SOCKET_TIMEOUT_MILLIS
            socketBinder.bind(datagramSocket)
            datagramSocket.bind(InetSocketAddress(audio.port))
        } catch (exception: Exception) {
            datagramSocket.close()
            updateAudioStatus {
                it.copy(
                    available = false,
                    state = "unavailable",
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
        var receiveGeneration = renderingGeneration.get()
        var depacketizer = H264RtpDepacketizer(description.video.payloadType)
        val buffer = ByteArray(MAX_RTP_DATAGRAM_BYTES)
        val packet = DatagramPacket(buffer, buffer.size)

        while (currentCoroutineContext().isActive && !closed.get()) {
            try {
                val inputGeneration = renderingGeneration.get()
                if (inputGeneration != receiveGeneration) {
                    // Discard partial pre-change NAL assembly as well as completed queued input.
                    depacketizer = H264RtpDepacketizer(description.video.payloadType)
                    receiveGeneration = inputGeneration
                }
                packet.length = buffer.size
                datagramSocket.receive(packet)
                if (inputGeneration != renderingGeneration.get()) continue
                val nowMillis = System.currentTimeMillis()
                videoDatagramsReceived.incrementAndGet()
                videoLastDatagramAtMillis.set(nowMillis)
                depacketizer.accept(packet.data, packet.length)?.let { accessUnit ->
                    val sequence = videoAccessUnitsReceived.incrementAndGet()
                    videoLastAccessUnitAtMillis.set(nowMillis)
                    if (accessUnit.hasPicture) inputDeadline.observe(System.nanoTime())
                    if (accessUnit.keyFrame) videoKeyFramesReceived.incrementAndGet()
                    accessUnit.sequenceParameterSet?.let { latestSps = it }
                    accessUnit.pictureParameterSet?.let { latestPps = it }
                    accessUnits.trySend(NativeRtpInputIdentity(sequence, inputGeneration) to accessUnit)
                    if (accessUnit.keyFrame && latestSps != null && latestPps != null) {
                        // Core cannot wait for a UI Surface created only after Start returns.
                        // Encoded admission starts one headless decoder; only its output is ready.
                        synchronized(decoderGuard) {
                            if (!closed.get() && decoderJob == null && surface.get() == null) restartDecoder(null)
                        }
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
        "Canon RTP video did not produce a decoded H.264 picture within ${timeoutMillis} ms " +
            "(UDP datagrams=${videoDatagramsReceived.get()}, " +
            "H.264 access units=${videoAccessUnitsReceived.get()}, " +
            "key frames=${videoKeyFramesReceived.get()}, decoded=${frameProgress.decodedCount}, " +
            "SPS=${latestSps != null}, PPS=${latestPps != null})."

    private suspend fun receiveAudioPackets(
        datagramSocket: DatagramSocket,
        audio: RtpMediaDescription,
    ) {
        val depacketizer = LatmRtpDepacketizer(audio.payloadType)
        var extractor = try { latmExtractorFactory() } catch (exception: Exception) {
            audioPlayback.setAvailable(false, error = "Canon RTP LATM parser initialization failed: ${exception.message ?: exception.javaClass.simpleName}")
            updateAudioStatus { it.copy(available = false) }
            return
        }
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
                    audioPlayback.setAvailable(false, error = "Canon RTP audio receive failed: ${exception.message ?: exception.javaClass.simpleName}")
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
                        )
                    }
                    audioPlayback.submit(samples)
                }
                if (lastPublishedAtMillis == 0L || nowMillis - lastPublishedAtMillis >= AUDIO_STATS_INTERVAL_MILLIS) {
                    lastPublishedAtMillis = nowMillis
                    publishAudioStatus()
                }
            } catch (_: CancellationException) {
                throw CancellationException()
            } catch (exception: Exception) {
                depacketizer.resetAfterDiscontinuity()
                recordDroppedAudioAccessUnit()
                audioPlayback.setAvailable(false,
                    error = "Canon RTP LATM parse failed: ${exception.message ?: exception.javaClass.simpleName}")
                updateAudioStatus(notify = false) { it.copy(available = false) }
                extractor = try { latmExtractorFactory().also { it.reset() } } catch (reset: Exception) {
                    updateAudioStatus { it.copy(available = false,
                        error = "Canon RTP LATM parser reset failed: ${reset.message ?: reset.javaClass.simpleName}") }
                    return
                }
                audioPlayback.setAvailable(true)
                updateAudioStatus { it.copy(available = true) }
            }
        }
    }

    private class LutFallback(val reason: NativeVideoLutStatus) : Exception()

    private suspend fun decodeTo(targetSurface: Surface?, generation: Long) {
        val selected = videoLut.get().takeIf { targetSurface != null }
        try {
            decodeSurface(targetSurface, generation, selected)
        } catch (fallback: LutFallback) {
            if (!closed.get() && decoderGeneration.get() == generation && surface.get() === targetSurface) {
                if (selected != null) videoLutState.value = fallback.reason
                // One same-session native fallback. No camera reconnect, command replay or retry loop.
                decodeSurface(targetSurface, generation, null, bypassGpu = true)
            }
        }
    }

    private suspend fun decodeSurface(targetSurface: Surface?, generation: Long, selectedLut: NativeVideoLut?, bypassGpu: Boolean = false) {
        // A replacement must start at a new IDR; the first headless decoder keeps pre-start input.
        if (targetSurface != null) while (accessUnits.tryReceive().isSuccess) Unit
        val revision = lutRevision.get()
        var codec: MediaCodec? = null
        var lutRenderer: NativeRtpLutRenderer? = null
        val renderedGuard = Any()
        data class PendingPresentation(
            val format: Long, val rendering: Long, val encodedBytes: Int, val width: Int, val height: Int, val frameId: Long,
        )
        val pendingRendered = linkedMapOf<Long, PendingPresentation>()
        var formatGeneration = 0L
        fun ownsPresentation(timeUs: Long): Boolean = synchronized(renderedGuard) {
            val metadata = pendingRendered[timeUs]
            metadata != null && metadata.format == formatGeneration && renderingEnabled.get() &&
                surface.get() === targetSurface && metadata.rendering == renderingGeneration.get() && revision == lutRevision.get() && targetSurface?.isValid == true &&
                LiveViewLifecycleEvidence(sameOwner = !closed.get(),
                    sameAttempt = decoderGeneration.get() == generation, terminal = terminalFailure.get(),
                    frameEvidence = "presented-frame").evaluate().canPublishPresented
        }
        fun publishPresentation(timeUs: Long, atNanos: Long, pixels: NativeRtpFramePixels? = null) = synchronized(decoderGuard) {
            synchronized(renderedGuard) publication@ {
                if (ownsPresentation(timeUs)) {
                    val metadata = pendingRendered.remove(timeUs)!!
                    if (!frameProgress.presented(generation, metadata.frameId)) return@publication
                    renderedFrameState.value = NativeLiveViewRenderedFrame(
                        requireNotNull(targetSurface), generation, metadata.format, renderedSequence.incrementAndGet(), atNanos,
                        NativeRtpFrameIdentity(generation, metadata.format, metadata.rendering, revision, metadata.frameId),
                        if (lutRenderer == null) NativeFrameEvidence.CODEC_RENDER_CALLBACK else NativeFrameEvidence.EGL_SURFACE_SUBMISSION,
                        pixels,
                    )
                    videoLastPresentationAtMillis.set(System.currentTimeMillis())
                    presentationDeadline.observe(System.nanoTime())
                    if (selectedLut != null) videoLutState.value = NativeVideoLutStatus.APPLIED
                    // Native callback / EGL submission is progress evidence, not physical display proof. Merely
                    // releasing an output buffer with render=true is not a rendered-frame event.
                    listener.get()?.invoke(NativeLiveViewEvent.FrameRendered(
                        metadata.encodedBytes, System.currentTimeMillis(), metadata.width, metadata.height,
                        if (lutRenderer == null) NativeFrameEvidence.CODEC_RENDER_CALLBACK else NativeFrameEvidence.EGL_SURFACE_SUBMISSION,
                        metadata.frameId,
                    ))
                }
            }
        }
        try {
            val codecSurface = if (targetSurface != null && !bypassGpu) {
                if (selectedLut != null) videoLutState.value = NativeVideoLutStatus.WAITING
                val renderer = NativeRtpLutRenderer(targetSurface, selectedLut, ::ownsPresentation,
                    { timeUs, pixels -> publishPresentation(timeUs, System.nanoTime(), pixels) }, {
                        if (!closed.get() && decoderGeneration.get() == generation && selectedLut != null) videoLutState.value = NativeVideoLutStatus.FAILED
                    }, { monitorSamplingEnabled.get() && renderingEnabled.get() })
                lutRenderer = renderer
                try { renderer.inputSurface() }
                catch (cancel: CancellationException) { throw cancel }
                catch (_: Exception) { throw LutFallback(NativeVideoLutStatus.UNSUPPORTED_GPU) }
            } else targetSurface
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
            codec.configure(format, codecSurface, null, 0)
            if (lutRenderer == null && targetSurface != null) {
                codec.setOnFrameRenderedListener({ _, timeUs, nanoTime ->
                    publishPresentation(timeUs, nanoTime)
                }, Handler(Looper.getMainLooper()))
            }
            codec.start()

            val continuity = NativeVideoDecodeContinuity()
            var firstTimestamp: Long? = null
            var lastRenderedAtNanos = 0L
            var videoWidth = DEFAULT_VIDEO_WIDTH
            var videoHeight = DEFAULT_VIDEO_HEIGHT
            val bytesByPresentationTime = linkedMapOf<Long, Pair<Int, NativeRtpInputIdentity>>()
            val codecTimeline = NativeRtpCodecTimeline()
            val bufferInfo = MediaCodec.BufferInfo()

            while (currentCoroutineContext().isActive && !closed.get() && decoderGeneration.get() == generation &&
                surface.get() === targetSurface && (targetSurface == null || targetSurface.isValid)) {
                if (lutRenderer?.hasFailed == true) throw LutFallback(NativeVideoLutStatus.FAILED)
                // Output is polled even when RTP has gone quiet; delayed decoder output must drain.
                val next = withTimeoutOrNull(DECODER_OUTPUT_POLL_MILLIS) { accessUnits.receive() }
                if (next != null && !next.first.ownsRendering(renderingGeneration.get())) continuity.dropped()
                if (next != null && next.first.ownsRendering(renderingGeneration.get()) &&
                    continuity.admit(next.first.frameId, next.second.keyFrame)) {
                    val (identity, accessUnit) = next
                    val encoded = if (continuity.needsKeyFrame) {
                        concatenateParameterSets(latestSps, latestPps, accessUnit.bytes)
                    } else {
                        accessUnit.bytes
                    }
                    val baseTimestamp = firstTimestamp ?: accessUnit.rtpTimestamp.also { firstTimestamp = it }
                    // Unique codec timestamps preserve callback identity through repeated/out-of-order RTP PTS.
                    val mediaTimeUs = rtpTimestampDelta(baseTimestamp, accessUnit.rtpTimestamp) * 1_000_000L /
                        description.video.clockRate
                    val presentationTimeUs = codecTimeline.next(mediaTimeUs)

                    val queued = queueDecoderInput(codec, encoded, presentationTimeUs)
                    if (queued) {
                        bytesByPresentationTime[presentationTimeUs] = encoded.size to identity
                        while (bytesByPresentationTime.size > MAX_PENDING_FRAME_STATS) {
                            bytesByPresentationTime.remove(bytesByPresentationTime.keys.first())
                        }
                        continuity.accepted()
                    } else {
                        continuity.dropped()
                    }
                }

                while (true) {
                    val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
                    when {
                        outputIndex >= 0 -> {
                            val nowNanos = SystemClock.elapsedRealtimeNanos()
                            val frameInterval = 1_000_000_000L / targetFps.get().coerceAtLeast(1)
                            val decoded = bytesByPresentationTime.remove(bufferInfo.presentationTimeUs)
                            val picture = decoded != null && bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 &&
                                (targetSurface != null || bufferInfo.size > 0)
                            val actualOutput = synchronized(decoderGuard) {
                                val accepted = picture && !closed.get() && !terminalFailure.get() && decoderGeneration.get() == generation &&
                                    requireNotNull(decoded).second.ownsRendering(renderingGeneration.get()) &&
                                    frameProgress.decoded(generation, decoded.second.frameId)
                                if (accepted) {
                                    videoLastDecodedAtMillis.set(System.currentTimeMillis())
                                    decodedDeadline.observe(System.nanoTime())
                                    surfaceWait.onDecodedOutput(System.nanoTime())
                                    videoReady.set(true)
                                    firstDecodableVideo.complete(Unit)
                                }
                                accepted
                            }
                            val render = actualOutput && targetSurface != null && renderingEnabled.get() &&
                                decoded?.second?.ownsRendering(renderingGeneration.get()) == true &&
                                (lastRenderedAtNanos == 0L || nowNanos - lastRenderedAtNanos >= frameInterval)
                            val encodedBytes = decoded?.first ?: 0
                            if (render) synchronized(renderedGuard) {
                                pendingRendered[bufferInfo.presentationTimeUs] = PendingPresentation(
                                    formatGeneration, requireNotNull(decoded).second.renderingGeneration, encodedBytes, videoWidth, videoHeight, decoded.second.frameId)
                                while (pendingRendered.size > MAX_PENDING_FRAME_STATS) {
                                    pendingRendered.remove(pendingRendered.keys.first())
                                }
                            }
                            codec.releaseOutputBuffer(outputIndex, render)
                            if (render) lastRenderedAtNanos = nowNanos
                        }

                        outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outputFormat = codec.outputFormat
                            if (lutRenderer != null) {
                                fun optional(key: String): Int? = if (outputFormat.containsKey(key)) outputFormat.getInteger(key) else null
                                val profile = optional(MediaFormat.KEY_PROFILE)
                                val highBitDepth = profile in setOf(
                                    MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10,
                                    MediaCodecInfo.CodecProfileLevel.AVCProfileHigh422,
                                    MediaCodecInfo.CodecProfileLevel.AVCProfileHigh444,
                                )
                                if (!nativeLutH264ProfileSupported(latestSps) || highBitDepth || !nativeLutColorSupported(
                                        optional(MediaFormat.KEY_COLOR_STANDARD), optional(MediaFormat.KEY_COLOR_TRANSFER), optional("bit-depth"))) {
                                    throw LutFallback(NativeVideoLutStatus.UNSUPPORTED_COLOR)
                                }
                            }
                            synchronized(renderedGuard) {
                                formatGeneration = videoFormatGeneration.incrementAndGet()
                                pendingRendered.clear()
                                if (decoderGeneration.get() == generation) renderedFrameState.value = null
                            }
                            val dimensions = nativeVideoDisplayDimensions(
                                outputFormat.getIntegerOrDefault(MediaFormat.KEY_WIDTH, videoWidth),
                                outputFormat.getIntegerOrDefault(MediaFormat.KEY_HEIGHT, videoHeight),
                                outputFormat.getIntegerOrDefault("crop-left", 0),
                                outputFormat.getIntegerOrDefault("crop-top", 0),
                                outputFormat.getIntegerOrDefault("crop-right", -1),
                                outputFormat.getIntegerOrDefault("crop-bottom", -1),
                                outputFormat.getIntegerOrDefault(MediaFormat.KEY_ROTATION, 0),
                            )
                            check(dimensions.first in 1..4096 && dimensions.second in 1..4096 &&
                                dimensions.first.toLong() * dimensions.second <= 8_388_608L) { "Decoded RTP dimensions exceed preview limits." }
                            videoWidth = dimensions.first
                            videoHeight = dimensions.second
                            listener.get()?.invoke(NativeLiveViewEvent.VideoSizeChanged(videoWidth, videoHeight))
                        }

                        else -> break
                    }
                }
            }
        } catch (fallback: LutFallback) {
            throw fallback
        } catch (_: CancellationException) {
            throw CancellationException()
        } catch (exception: Exception) {
            if (!closed.get() && decoderGeneration.get() == generation && surface.get() === targetSurface) {
                reportFailure("Canon H.264 decode failed", exception)
            }
        } finally {
            if (decoderGeneration.get() == generation) renderedFrameState.value = null
            val stopError = runCatching { codec?.stop() }.exceptionOrNull()
            val releaseError = runCatching { codec?.release() }.exceptionOrNull()
            val rendererError = runCatching { lutRenderer?.close() }.exceptionOrNull()
            val cleanupError = stopError ?: releaseError ?: rendererError
            if (cleanupError != null && !closed.get() &&
                (releaseError != null || rendererError != null || decoderGeneration.get() == generation)) {
                reportFailure("Canon RTP decoder retirement failed", IllegalStateException("Native resource drain failed", cleanupError))
            }
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
        if (closed.get() || !terminalFailure.compareAndSet(false, true)) return
        val message = "$prefix: ${exception.message ?: exception.javaClass.simpleName}"
        videoError.set(message)
        firstDecodableVideo.completeExceptionally(IllegalStateException(message, exception))
        renderedFrameState.value = null
        listener.get()?.invoke(NativeLiveViewEvent.Failed(message))
        // This only retires local sockets/decoder/audio; CCAPI's retained owner still owes Stop.
        close()
    }

    private fun recordAudioOwnerStatus(owner: RtpAudioOwnerStatus) {
        val stats = owner.stats
        updateAudioStatus {
            it.copy(
                enabled = owner.enabled,
                playbackEpoch = owner.epoch,
                retirementPending = owner.retirementPending,
                state = owner.state,
                evidenceLevel = owner.evidenceLevel,
                queuedAccessUnits = stats.queuedAccessUnits,
                decodedSampleFrames = stats.decodedSampleFrames,
                decodedAccessUnits = stats.decodedAccessUnits,
                submittedSampleFrames = stats.submittedSampleFrames,
                playedSampleFrames = stats.playedSampleFrames,
                sampleRate = stats.sampleRate,
                channels = stats.channels ?: description.audio?.channels,
                underruns = stats.underruns,
                lastPcmAtMillis = stats.lastPcmAtMillis,
                lastOutputAtMillis = stats.lastOutputAtMillis,
                pcmFresh = stats.pcmFresh,
                droppedAccessUnits = owner.droppedAccessUnits + audioParseDrops.get(),
                error = owner.error,
            )
        }
    }

    private fun recordDroppedAudioAccessUnit() {
        audioParseDrops.incrementAndGet()
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

private val rtpSurfaceWriters = NativeVideoSurfaceDrainRegistry()

private const val MIME_AVC = "video/avc"
private const val DEFAULT_VIDEO_WIDTH = 1920
private const val DEFAULT_VIDEO_HEIGHT = 1080
private const val DEFAULT_RTP_RENDER_FPS = 30
private const val MIN_RTP_RENDER_FPS = 1
private const val MAX_RTP_RENDER_FPS = 30
private const val RTP_ACCESS_UNIT_QUEUE_CAPACITY = 3
private const val MAX_RTP_DATAGRAM_BYTES = 65_535
private const val RTP_RECEIVE_BUFFER_BYTES = 2 * 1024 * 1024
private const val RTP_AUDIO_RECEIVE_BUFFER_BYTES = 512 * 1024
private const val RTP_SOCKET_TIMEOUT_MILLIS = 1_000
private const val MAX_H264_DECODER_INPUT_BYTES = 8 * 1024 * 1024
private const val MAX_PENDING_FRAME_STATS = 16
private const val DECODER_INPUT_TIMEOUT_US = 20_000L
private const val DECODER_OUTPUT_POLL_MILLIS = 10L
private const val AUDIO_STATS_INTERVAL_MILLIS = 500L

private const val RTP_VIDEO_INACTIVITY_NANOS = 5_000_000_000L
private const val RTP_WATCHDOG_INTERVAL_MILLIS = 250L
