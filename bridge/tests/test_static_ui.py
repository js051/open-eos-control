from __future__ import annotations

import re
from html.parser import HTMLParser
from pathlib import Path

STATIC = Path(__file__).parents[1] / "open_eos_bridge" / "static"


class UiDocumentParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.ids: list[str] = []
        self.assets: list[str] = []
        self.inline_script_text: list[str] = []
        self._inside_inline_script = False

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        values = dict(attrs)
        element_id = values.get("id")
        if element_id:
            self.ids.append(element_id)
        if tag in {"script", "img"} and values.get("src"):
            self.assets.append(values["src"] or "")
        if tag == "link" and values.get("href"):
            self.assets.append(values["href"] or "")
        if tag == "script" and not values.get("src"):
            self._inside_inline_script = True

    def handle_endtag(self, tag: str) -> None:
        if tag == "script":
            self._inside_inline_script = False

    def handle_data(self, data: str) -> None:
        if self._inside_inline_script and data.strip():
            self.inline_script_text.append(data)


def test_desktop_ui_document_has_stable_unique_controls_and_local_assets() -> None:
    markup = (STATIC / "index.html").read_text(encoding="utf-8")
    parser = UiDocumentParser()
    parser.feed(markup)

    required_ids = {
        "connection-view",
        "control-view",
        "camera-select",
        "ccapi-url-input",
        "ccapi-username-input",
        "ccapi-password-input",
        "connect-button",
        "live-image",
        "local-video",
        "monitor-pixel-overlay",
        "monitor-guides-overlay",
        "monitor-histogram",
        "monitor-waveform",
        "monitoring-button",
        "monitoring-dialog",
        "monitor-histogram-toggle",
        "monitor-waveform-toggle",
        "monitor-zebra-select",
        "monitor-false-color-toggle",
        "monitor-focus-peaking-toggle",
        "monitor-frame-guide-select",
        "monitor-safe-area-toggle",
        "monitor-desqueeze-select",
        "live-magnification-button",
        "rtp-audio-button",
        "exposure-strip",
        "shutter-button",
        "latest-media-button",
        "latest-media-thumbnail",
        "latest-media-label",
        "bulb-indicator",
        "half-press-button",
        "focus-reticle",
        "focus-section",
        "tap-action-row",
        "tap-action-select",
        "live-source-select",
        "preview-input-select",
        "local-video-device-row",
        "local-video-device-select",
        "local-video-support",
        "media-list",
        "media-filter-control",
        "media-sort-select",
        "media-preview-dialog",
        "media-preview-stage",
        "media-preview-meta",
        "media-preview-image",
        "media-preview-video",
        "media-preview-previous",
        "media-preview-next",
        "media-preview-download",
        "media-preview-details",
        "media-preview-reset-zoom",
        "media-details-dialog",
        "media-details-close",
        "media-protect-button",
        "media-unprotect-button",
        "media-rating-control",
        "media-rotation-control",
        "media-archive-section",
        "media-archive-button",
        "media-unarchive-button",
        "media-details-download",
        "media-details-delete",
        "media-transfer",
        "media-transfer-name",
        "media-transfer-status",
        "media-transfer-progress",
        "media-transfer-cancel",
        "media-pagination",
        "media-page-previous",
        "media-page-status",
        "media-page-next",
        "physical-validation-title",
        "physical-validation-status",
        "physical-validation-list",
        "copy-physical-validation-button",
        "diagnostics-output",
    }
    assert required_ids <= set(parser.ids)
    assert len(parser.ids) == len(set(parser.ids))
    assert parser.inline_script_text == []
    assert parser.assets
    assert all(asset.startswith("/app/") for asset in parser.assets)
    assert "/app/diagnostics.js" in parser.assets
    assert "/app/monitoring.js" in parser.assets
    assert "/app/local-video.js" in parser.assets
    assert "/app/rtp-audio.js" in parser.assets
    assert "/app/media-transfer.js" in parser.assets
    assert "/app/media-library.js" in parser.assets


def test_static_labels_exist_in_both_supported_languages() -> None:
    markup = (STATIC / "index.html").read_text(encoding="utf-8")
    script = (STATIC / "app.js").read_text(encoding="utf-8")
    keys = set(re.findall(r'data-i18n(?:-placeholder|-aria)?="([A-Za-z0-9]+)"', markup))

    assert 'option value="auto"' in markup
    assert 'option value="en"' in markup
    assert 'option value="zh-TW"' in markup
    for key in keys:
        declarations = re.findall(rf"^\s+{re.escape(key)}:\s+\"", script, flags=re.MULTILINE)
        assert len(declarations) >= 2, f"{key} must be declared in English and Traditional Chinese"

    setting_keys = {
        "whitebalanceadjusta",
        "whitebalanceadjustb",
        "aspectratio",
        "moviemode",
        "zoom",
        "zoomspeed",
        "autopoweroff",
        "alomode",
        "capturetarget",
        "capturestorage",
        "cardselectionstillimage",
        "cardselectionmovie",
        "directoryselection",
        "stillimagequalitysd",
        "stillimagequalitycf",
    }
    for key in setting_keys:
        declarations = re.findall(rf"^\s+{re.escape(key)}:\s+\"", script, flags=re.MULTILINE)
        assert len(declarations) == 2, f"{key} must have exactly one label in each supported language"

    for key in {
        "stillimagequality.raw",
        "stillimagequality.jpeg",
        "stillimagequality.heif",
        "wbshift.ba",
        "wbshift.mg",
    }:
        declarations = re.findall(rf'^\s+"{re.escape(key)}":\s+"', script, flags=re.MULTILINE)
        assert len(declarations) == 2, f"{key} must have exactly one label in each supported language"
    for key in {"ownername", "artist", "copyright", "nickname", "textMetadataRule"}:
        declarations = re.findall(rf"^\s+{re.escape(key)}:\s+\"", script, flags=re.MULTILINE)
        assert len(declarations) == 2, f"{key} must be declared in English and Traditional Chinese"
    for key in {
        "fileNaming",
        "fileNamingPhotoSummary",
        "fileNamingVideoSummary",
        "fileNamingPresetCode",
        "stillUserSetting1",
        "stillUserSetting2",
        "movieIndex",
        "movieReelNumber",
        "movieClipNumber",
        "movieUserDefined",
        "fileNamingRule",
        "apply",
        "fileNamingUpdated",
    }:
        declarations = re.findall(rf"^\s+{re.escape(key)}:\s+\"", script, flags=re.MULTILINE)
        assert len(declarations) == 2, f"{key} must have exactly one label in each supported language"
    assert 'large_fine: "imageQualityLargeFine"' in script
    assert '"internal ram": "valueInternalRam"' in script
    assert '"memory card": "valueMemoryCard"' in script
    assert '"card 1": "valueCard1"' in script
    assert '"card 2": "valueCard2"' in script
    assert '"high (disabled in manual exposure)": "valueHighDisabledManual"' in script
    assert 'if (key === "alomode")' in script

    assert "function settingValueLabel(settingOrKey, value)" in script
    assert 'setting.inputKind === "text"' in script
    assert "settingDrafts" in script
    assert "textSettingValueValid" in script
    assert 'input.className = "text-metadata-input"' in script
    assert 'method: "POST", json: { value }' in script
    assert "function diagnosticCapabilities()" in script
    assert "choiceCount:" in script
    assert "option.value = value" in script
    assert "option.textContent = settingValueLabel(setting, value)" in script
    assert '"capturetarget"' in script
    assert "settingMatchesCaptureMode(setting)" in script


def test_desktop_ui_uses_real_bridge_paths_and_never_persists_authentication() -> None:
    html = (STATIC / "index.html").read_text(encoding="utf-8")
    script = (STATIC / "app.js").read_text(encoding="utf-8")
    local_video_script = (STATIC / "local-video.js").read_text(encoding="utf-8")
    required_paths = {
        "/v1/cameras",
        "/v1/session",
        "/events",
        "/capture/still",
        "/bulb/start",
        "/bulb/stop",
        "/focus/auto",
        "/shutter/half-press",
        "/recording/",
        "/focus/drive",
        "/focus/tap",
        "/whitebalance/click",
        "/liveview/start",
        "/liveview/frame",
        "/liveview/magnification",
        "/media",
        "/thumbnail",
        "/preview",
        "/info",
    }

    assert all(path in script for path in required_paths)
    assert "featureSupported(FEATURES." in script
    assert "FEATURES.MEDIA_PROTECT" in script
    assert "FEATURES.MEDIA_RATING" in script
    assert "FEATURES.MEDIA_ROTATE" in script
    assert "FEATURES.MEDIA_ARCHIVE" in script
    assert '{ method: "PUT", json }' in script
    assert 'FEATURES.MEDIA_PROTECT, "protection", { enabled: true }' in script
    assert 'FEATURES.MEDIA_PROTECT, "protection", { enabled: false }' in script
    assert 'FEATURES.MEDIA_RATING, "rating", { value:' in script
    assert 'FEATURES.MEDIA_ROTATE, "rotation", { degrees:' in script
    assert 'FEATURES.MEDIA_ARCHIVE, "archive", { enabled: true }' in script
    assert 'FEATURES.MEDIA_ARCHIVE, "archive", { enabled: false }' in script
    assert 'MEDIA_DELETE: "MEDIA_DELETE"' in script
    assert 'EVENT_POLLING: "EVENT_POLLING"' in script
    assert "function startEventLoop()" in script
    assert "function cancelEventLoop()" in script
    assert "await refreshSession({ quiet: true })" in script
    assert 'String(key).toLowerCase().includes("content")' in script
    assert "contentsChanged && state.mediaLoaded" in script
    assert "await refreshMedia()" in script
    assert "mediaRefreshPromise: null" in script
    assert 'mediaLoadStatus: "NOT_LOADED"' in script
    assert "function refreshMediaWhenCurrent()" in script
    assert "const LATEST_MEDIA_LIMIT = 8" in script
    assert "?limit=${LATEST_MEDIA_LIMIT}" in script
    assert "function refreshLatestMedia({ previousId = null } = {})" in script
    assert "function cancelLatestMediaRefresh()" in script
    assert "function openLatestMedia()" in script
    assert "void refreshLatestMedia({ previousId: previousLatestId })" in script
    assert 'mediaScope: "recent"' in script
    assert 'mediaHasMore: false' in script
    assert 'const limitQuery = mediaScope === "recent" ? "?limit=61" : ""' in script
    assert 'querySelectorAll("[data-media-scope]")' in script
    assert 'id="media-scope-control"' in html
    assert 'state.mediaLoadStatus = "LOADING"' in script
    assert 'state.mediaLoadStatus = "COMPLETE"' in script
    assert 'state.mediaLoadStatus = "FAILED"' in script
    assert "function renderMediaSummary(" in script
    assert "ui.mediaSummary.dataset.loadStatus = state.mediaLoadStatus" in script
    for key in {
        "mediaLoadingCount",
        "mediaFailedCount",
        "mediaNotLoadedCount",
        "mediaRecentCount",
        "mediaRecentMoreCount",
    }:
        declarations = re.findall(rf"^\s+{key}:\s+\"", script, flags=re.MULTILINE)
        assert len(declarations) == 2, f"{key} must be declared in both supported languages"
    assert "state.refreshGeneration !== interactionGeneration" in script
    assert 'MEDIA_THUMBNAIL: "MEDIA_THUMBNAIL"' in script
    assert 'MEDIA_PREVIEW: "MEDIA_PREVIEW"' in script
    assert "MAX_MEDIA_PREVIEW_BYTES" in script
    assert 'src="/app/lut.js"' in html
    assert 'id="monitor-lut-preview"' in html
    assert 'id="monitor-lut-file"' in html
    assert "lut.createWebGLRenderer" in script
    assert "item.previewAvailable === true" in script
    assert "await ui.mediaPreviewImage.decode()" in script
    assert '/media/${encodeURIComponent(item.id)}/playback`' in script
    assert 'querySelectorAll("[data-media-filter]")' in script
    assert "state.mediaPreviewTicketUrl = ticket.url" in script
    assert "ui.mediaPreviewVideo.src = ticket.url" in script
    assert "failMediaVideoPreview" in script
    assert "videoPlaybackStorageUnavailable" in script
    assert "videoPlaybackTransportUnavailable" in script
    assert "mediaLibrary.videoContainerLabel" in script
    assert 'normalized.code === "MEDIA_PLAYBACK_STORAGE_UNAVAILABLE"' in script
    assert "ui.mediaPreviewDownload.hidden" in script
    assert 'id="media-preview-retry"' in html
    assert 'id="media-preview-failure-download"' in html
    assert "openAdjacentMedia(-1)" in script
    assert "openAdjacentMedia(1)" in script
    assert 'data-view="media" data-i18n-aria="media"' in html
    assert 'CLICK_WHITE_BALANCE: "CLICK_WHITE_BALANCE"' in script
    assert 'SHUTTER_HALF_PRESS: "SHUTTER_HALF_PRESS"' in script
    assert 'BULB_EXPOSURE: "BULB_EXPOSURE"' in script
    assert 'MOVIE_MODE_CONTROL: "MOVIE_MODE_CONTROL"' in script
    assert 'CARD_SELECTION_CONTROL: "CARD_SELECTION_CONTROL"' in script
    assert 'SOUND_RECORDING_LEVEL_CONTROL: "SOUND_RECORDING_LEVEL_CONTROL"' in script
    assert 'SOUND_RECORDING_CONTROL: "SOUND_RECORDING_CONTROL"' in script
    assert 'FOCUS_BRACKETING_CONTROL: "FOCUS_BRACKETING_CONTROL"' in script
    assert 'LIVE_VIEW_MAGNIFICATION: "LIVE_VIEW_MAGNIFICATION"' in script
    assert 'FILE_NAMING_CONTROL: "FILE_NAMING_CONTROL"' in script
    assert "featureSupported(FEATURES.FILE_NAMING_CONTROL)" in script
    assert "function renderFileNamingSettings()" in script
    assert "function fileNamingValueValid(field, value)" in script
    assert "function updateFileNaming(field, value, source)" in script
    assert "fileNamingDrafts: {}" in script
    assert "state.fileNamingDrafts[field] ?? current" in script
    assert "delete state.fileNamingDrafts[field]" in script
    assert 'button.dataset.cameraCommand = "file-naming"' in script
    assert 'file-naming/${encodeURIComponent(field)}' in script
    assert '{ method: "PUT", json: { value } }' in script
    assert "featureSupported(FEATURES.LIVE_VIEW_MAGNIFICATION)" in script
    assert "featureSupported(FEATURES.SHUTTER_HALF_PRESS)" in script
    assert "featureSupported(FEATURES.BULB_EXPOSURE)" in script
    assert "function pauseLivePolling()" in script
    assert "function resumeLivePolling()" in script
    assert "URL.revokeObjectURL" in script
    assert "mediaTransfer.readResponse" in script
    assert "new AbortController()" in script
    assert "mediaTransfer.shouldUseDirectWriter" in script
    assert "window.showSaveFilePicker" in script
    assert "await writable.abort(error)" in script
    assert "cancelMediaDownload({ silent: true })" in script
    assert "cameraInteractionBusy()" in script
    assert '"focusbracketingnumberofshots",' in script
    assert '"focusbracketingfocusincrement",' in script
    assert 'return !key.startsWith("focusbracketing")' in script
    assert '"#advanced-settings select, #advanced-settings input[type=range]"' in script
    assert 'if (key === "moviemode") return false' in script
    assert 'const movieMode = settingByKey("moviemode")' in script
    assert 'range.type = "range"' in script
    assert "scheduleMediaTransferRender()" in script
    assert script.count("cancelDownload:") == 2
    assert script.count("deleteConfirm:") == 2
    assert '{ method: "DELETE" }' in script
    assert "Bearer ${state.token}" in script
    assert "writeLanguagePreference(state.language)" in script
    assert "writeCameraPreference(CCAPI_URL_KEY" in script
    assert "writeCameraPreference(CCAPI_USERNAME_KEY" in script
    storage_lines = [line.casefold() for line in script.splitlines() if "localstorage." in line.casefold()]
    assert storage_lines
    assert all("password" not in line and "token" not in line for line in storage_lines)
    assert "ccapi_password_key" not in script.casefold()
    assert "Math.min(15, state.capabilities.liveView?.maxFps || 1)" in script
    assert "monitoring.analysisDimensions" in script
    assert "monitoring.analyzePixels" in script
    assert "function liveContentDisplayRect()" in script
    assert "function activePreviewElement()" in script
    assert "const imageBounds = ui.liveImage.getBoundingClientRect()" in script
    assert "const viewfinderBounds = ui.viewfinder.getBoundingClientRect()" in script
    assert 'source: state.liveSource || "AUTO"' in script
    assert "CCAPI_RTP" in script
    assert "engine?.detail" in script
    assert 'state.previewInput === "LOCAL_VIDEO"' in script
    assert "await localVideo.start(navigator.mediaDevices" in script
    assert "localVideo.stop(state.localVideoStream)" in script
    assert "requestVideoFrameCallback" in script
    assert "navigator.mediaDevices?.addEventListener?.(\"devicechange\"" in script
    assert '<video id="local-video"' in html
    assert all(attribute in html for attribute in ("autoplay", "muted", "playsinline"))
    assert "getUserMedia" in local_video_script
    assert "enumerateDevices" in local_video_script
    assert "audio: false" in local_video_script
    assert "localStorage" not in local_video_script
    report_source = script.split("function diagnosticReport()", 1)[1].split("\n  function ", 1)[0]
    assert "reportSchema: 1" in report_source
    assert "productVersion: state.health?.version" in report_source
    assert "validation: diagnostics.featureSummary(state.capabilities)" in report_source
    assert "monitoring: {" in report_source
    assert "analysisError: state.monitorAnalysisError" in report_source
    assert "itemCount: state.media.length" in report_source
    assert "loadStatus: state.mediaLoadStatus" in report_source
    assert "selection: state.localVideoDeviceId ? \"explicit\" : \"system-default\"" in report_source
    assert "deviceId:" not in report_source
    assert "label:" not in report_source
    assert "return diagnostics.safeValue(report" in report_source
    assert "secrets: [state.token, ui.ccapiPasswordInput?.value, state.info?.serial]" in report_source
    assert "operatorConfirmedFeatures: new Set()" in script
    assert script.count("state.operatorConfirmedFeatures.clear()") >= 2
    assert "diagnostics.physicalValidationSummary" in script
    assert "diagnostics.physicalValidationRecord" in script
    assert "globalThis.crypto?.subtle" in script
    assert "diagnosticReport: diagnosticText" in script
    assert "const diagnosticText = JSON.stringify(report, null, 2)" in script
    assert "state.refreshGeneration !== refreshGeneration" in script
    assert "function beginCameraInteraction()" in script
    confirmation_source = script.split("operatorConfirmedFeatures: new Set()", 1)[1]
    assert "localStorage" not in "\n".join(
        line for line in confirmation_source.splitlines() if "operatorConfirmedFeatures" in line
    )


def test_physical_validation_controls_are_accessible_and_unframed() -> None:
    markup = (STATIC / "index.html").read_text(encoding="utf-8")
    styles = (STATIC / "styles.css").read_text(encoding="utf-8")

    assert 'aria-labelledby="physical-validation-title"' in markup
    assert 'data-icon="clipboard-check"' in markup
    row_rule = styles.split(".physical-validation-row {", 1)[1].split("}", 1)[0]
    assert "min-height: 48px" in row_rule
    section_rule = styles.split(".diagnostic-validation {", 1)[1].split("}", 1)[0]
    assert "border-radius" not in section_rule
    assert "box-shadow" not in section_rule
