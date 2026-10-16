"""Source wiring safeguards, not Android/MediaCodec runtime evidence."""
from pathlib import Path
import json

ROOT = Path(__file__).resolve().parents[3]
DATA = ROOT / "android/app/src/main/java/dev/openeos/control/data"
UI = ROOT / "android/app/src/main/java/dev/openeos/control/ui"


def section(path, begin, end):
    text = path.read_text()
    start = text.index(begin)
    return text[start:text.index(end, start)]


def test_magnification_retires_geometry_before_dispatch_and_on_every_exit():
    body = section(DATA / "CcapiClient.kt", "    suspend fun setLiveViewMagnification(", "    suspend fun listMedia(")
    assert body.index("beginMutation(owner)") < body.index("putOk(")
    assert "liveViewGeometry.finishMutation(change)" in body[body.index("} finally {"):]
    assert "checkOwner = { liveViewGeometry.requireRevision(change) }" in body
    assert "getJson(operations.first.path, fresh = true)" in body


def test_coordinate_dispatch_preserves_snapshot_after_io_scheduling():
    text = (DATA / "CcapiClient.kt").read_text()
    assert text.count("checkOwner = { liveViewGeometry.requireSnapshot(geometryOwner) }") == 2
    command = section(DATA / "CcapiClient.kt", "    private suspend fun commandOk(", "    private suspend fun withRecoverableFocusRelease(")
    assert "requestOk(request, recordingEvidence = recordingEvidence, checkOwner = checkOwner)" in command
    dispatch = section(DATA / "CcapiClient.kt", "    private suspend fun requestOk(", "    private suspend fun requestLiveViewFrame(")
    assert "checkOwner?.invoke()\n            call.execute()" in dispatch
    assert "recordingIo || checkOwner != null" in dispatch


def test_frame_publication_is_bound_before_io_and_complete_envelope_required():
    body = section(DATA / "CcapiClient.kt", "    private suspend fun requestLiveViewFrame(", "    private fun parseDetailedLiveView(")
    assert body.index("val owner = liveViewGeometry.owner()") < body.index("withContext(Dispatchers.IO)")
    assert body.rindex("liveViewGeometry.requireOwner(owner)") < body.index("liveViewGeometry.publish(")
    parser = section(DATA / "CcapiClient.kt", "    private fun parseDetailedLiveView(", "    private fun parseLiveViewGeometry(")
    assert parser.index("check(offset == payload.size)") < parser.index("return CcapiDetailedLiveView(")


def test_ui_retirement_and_same_owner_recovery_preserve_pending_presentation():
    zoom = section(UI / "CameraViewModel.kt", "    fun setLiveViewMagnification(", "    fun refreshMedia()")
    assert zoom.index("setRenderingEnabled(false)") < zoom.index("runCamera(CameraOperation.LIVE_VIEW)")
    assert "liveViewCoordinateRefreshPending = true, liveViewBitmap = null, liveViewFrameUrl = null" in zoom
    final = zoom[zoom.index("} finally {"):]
    assert "coroutineContext.ensureActive()" not in final
    assert "if (ownsMagnification())" in final
    assert "generation == cameraSessionGeneration && _uiState.value.info === connection" in zoom
    assert "presentationGeneration == liveViewGeneration && repository.ownsLiveView(liveViewOwner)" in zoom
    assert "startLiveViewLoopIfNeeded()" in final
    assert "liveViewCoordinateRefreshPending = false" not in zoom
    text = (UI / "CameraViewModel.kt").read_text()
    for begin, end in [("    fun tapFocus(", "    fun setLiveViewTapAction("), ("    fun clickWhiteBalance(", "    private fun updateStatus(")]:
        assert "if (_uiState.value.liveViewCoordinateRefreshPending) return" in section(UI / "CameraViewModel.kt", begin, end)
    assert "!state.liveViewCoordinateRefreshPending" in (UI / "CameraComponents.kt").read_text()


def test_native_ingress_identity_survives_codec_and_presentation():
    receive = section(DATA / "AndroidCcapiRtpSession.kt", "    private suspend fun receivePackets(", "    private fun readinessFailureMessage(")
    assert receive.index("val inputGeneration = renderingGeneration.get()") < receive.index("datagramSocket.receive(packet)")
    assert "depacketizer = H264RtpDepacketizer(description.video.payloadType)" in receive
    assert "if (inputGeneration != renderingGeneration.get()) continue" in receive
    assert "NativeRtpInputIdentity(sequence, inputGeneration) to accessUnit" in receive
    text = (DATA / "AndroidCcapiRtpSession.kt").read_text()
    assert "Channel<Pair<NativeRtpInputIdentity, H264AccessUnit>>" in text
    assert "linkedMapOf<Long, Pair<Int, NativeRtpInputIdentity>>()" in text
    assert "bytesByPresentationTime[presentationTimeUs] = encoded.size to identity" in text
    assert "next.first.ownsRendering(renderingGeneration.get())" in text
    assert "decoded?.second?.ownsRendering(renderingGeneration.get()) == true" in text
    assert "formatGeneration, requireNotNull(decoded).second.renderingGeneration" in text
    assert "formatGeneration, renderingGeneration.get(), encodedBytes" not in text


def test_reported_rectangle_is_exact_bounded_and_not_zoom_derived():
    body = section(DATA / "CcapiClient.kt", "    private fun parseLiveViewGeometry(", "    private suspend fun cameraLiveViewPosition(")
    for key in ("positionx", "positiony", "positionwidth", "positionheight"):
        assert f'image.opt("{key}").toExactJsonInt()' in body
    assert "x >= 0 && y >= 0 && width > 0 && height > 0" in body
    assert "x.toLong() + width <= Int.MAX_VALUE" in body


def test_shared_authoritative_rectangles_cover_full_zoom_journey():
    fixture = json.loads((ROOT / "contracts/magnification-geometry/v1/geometry-transition-vectors.json").read_text())
    assert fixture["sequence"] == ["initial-1x", "after-5x", "after-10x", "return-1x"]
    assert len(fixture["vectors"]) == 12
    assert fixture["coordinateActions"] == ["Touch AF", "Click WB"]


def test_multipart_mailbox_and_reader_are_retired_on_both_zoom_boundaries():
    zoom = section(DATA / "CcapiClient.kt", "    suspend fun setLiveViewMagnification(", "    suspend fun listMedia(")
    assert zoom.count("multipart?.discardPendingFrames()") == 2
    text = (DATA / "CcapiMultipartLiveView.kt").read_text()
    assert "consumedGeneration = producedGeneration" in text
    assert text.index("val epoch = synchronized(monitor) { frameEpoch }") < text.index("val frame = reader.nextFrame()")
    assert "if (!closed && epoch == frameEpoch)" in text


def test_maintenance_retirement_revokes_geometry_before_suspend():
    body = section(DATA / "CcapiClient.kt", "    suspend fun retireMaintenanceLocally() {", "    suspend fun closeMaintenanceSession()")
    assert body.split("{", 1)[1].lstrip().startswith("liveViewGeometry.retire()")
    assert body.index("liveViewGeometry.retire()") < body.index("pendingLiveViewLocalClose?.invoke()")
    assert "stopLiveView(" not in body
