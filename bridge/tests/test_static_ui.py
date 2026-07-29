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
        "live-magnification-button",
        "exposure-strip",
        "shutter-button",
        "bulb-indicator",
        "half-press-button",
        "focus-reticle",
        "focus-section",
        "tap-action-row",
        "tap-action-select",
        "live-source-select",
        "media-list",
        "diagnostics-output",
    }
    assert required_ids <= set(parser.ids)
    assert len(parser.ids) == len(set(parser.ids))
    assert parser.inline_script_text == []
    assert parser.assets
    assert all(asset.startswith("/app/") for asset in parser.assets)
    assert "/app/diagnostics.js" in parser.assets


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
        "zoomspeed",
        "autopoweroff",
        "capturetarget",
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
    assert 'large_fine: "imageQualityLargeFine"' in script
    assert '"internal ram": "valueInternalRam"' in script
    assert '"memory card": "valueMemoryCard"' in script

    assert "function settingValueLabel(settingOrKey, value)" in script
    assert "option.value = value" in script
    assert "option.textContent = settingValueLabel(setting, value)" in script
    assert '"capturetarget"' in script
    assert "settingMatchesCaptureMode(setting)" in script


def test_desktop_ui_uses_real_bridge_paths_and_never_persists_authentication() -> None:
    html = (STATIC / "index.html").read_text(encoding="utf-8")
    script = (STATIC / "app.js").read_text(encoding="utf-8")
    required_paths = {
        "/v1/cameras",
        "/v1/session",
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
    }

    assert all(path in script for path in required_paths)
    assert "featureSupported(FEATURES." in script
    assert 'MEDIA_DELETE: "MEDIA_DELETE"' in script
    assert 'MEDIA_THUMBNAIL: "MEDIA_THUMBNAIL"' in script
    assert 'MEDIA_PREVIEW: "MEDIA_PREVIEW"' in script
    assert "MAX_MEDIA_PREVIEW_BYTES" in script
    assert "item.previewAvailable === true" in script
    assert "await ui.mediaPreviewImage.decode()" in script
    assert 'data-view="media" data-i18n-aria="media"' in html
    assert 'CLICK_WHITE_BALANCE: "CLICK_WHITE_BALANCE"' in script
    assert 'SHUTTER_HALF_PRESS: "SHUTTER_HALF_PRESS"' in script
    assert 'BULB_EXPOSURE: "BULB_EXPOSURE"' in script
    assert 'LIVE_VIEW_MAGNIFICATION: "LIVE_VIEW_MAGNIFICATION"' in script
    assert "featureSupported(FEATURES.LIVE_VIEW_MAGNIFICATION)" in script
    assert "featureSupported(FEATURES.SHUTTER_HALF_PRESS)" in script
    assert "featureSupported(FEATURES.BULB_EXPOSURE)" in script
    assert "function pauseLivePolling()" in script
    assert "function resumeLivePolling()" in script
    assert "URL.revokeObjectURL" in script
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
    assert 'source: state.liveSource || "AUTO"' in script
    assert "CCAPI_RTP" in script
    assert "engine?.detail" in script
    report_source = script.split("function diagnosticReport()", 1)[1].split("\n  function ", 1)[0]
    assert "reportSchema: 1" in report_source
    assert "productVersion: state.health?.version" in report_source
    assert "validation: diagnostics.featureSummary(state.capabilities)" in report_source
    assert "return diagnostics.safeValue(report" in report_source
    assert "secrets: [state.token, ui.ccapiPasswordInput?.value, state.info?.serial]" in report_source
