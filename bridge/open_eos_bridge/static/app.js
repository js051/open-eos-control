(() => {
  "use strict";

  const diagnostics = globalThis.OpenEOSDiagnostics;
  if (!diagnostics) throw new Error("Open EOS diagnostics module is unavailable.");

  const FEATURES = {
    LIVE_VIEW: "LIVE_VIEW",
    LIVE_VIEW_MAGNIFICATION: "LIVE_VIEW_MAGNIFICATION",
    STILL_CAPTURE: "STILL_CAPTURE",
    AUTOFOCUS: "AUTOFOCUS",
    SHUTTER_HALF_PRESS: "SHUTTER_HALF_PRESS",
    VIDEO_RECORDING: "VIDEO_RECORDING",
    TAP_FOCUS: "TAP_FOCUS",
    CLICK_WHITE_BALANCE: "CLICK_WHITE_BALANCE",
    FOCUS_DRIVE: "FOCUS_DRIVE",
    MEDIA_BROWSER: "MEDIA_BROWSER",
    MEDIA_THUMBNAIL: "MEDIA_THUMBNAIL",
    MEDIA_DOWNLOAD: "MEDIA_DOWNLOAD",
    MEDIA_DELETE: "MEDIA_DELETE",
  };
  const CORE_SETTINGS = ["iso", "shutter", "aperture", "whitebalance"];
  const LANGUAGE_KEY = "open-eos-control-language";
  const CCAPI_URL_KEY = "open-eos-control-ccapi-url";
  const CCAPI_USERNAME_KEY = "open-eos-control-ccapi-username";
  const MAX_MEDIA_THUMBNAIL_BYTES = 8 * 1024 * 1024;

  const messages = {
    en: {
      desktopControl: "Desktop control",
      language: "Language",
      auto: "Auto",
      connectCamera: "Connect camera",
      checkingBridge: "Checking bridge",
      bearerToken: "Bridge Bearer token",
      tokenOptional: "Optional on loopback",
      connectionType: "Connection type",
      usbCamera: "USB camera",
      wirelessCcapi: "Wireless CCAPI",
      cameraUrl: "Camera URL",
      cameraUrlPlaceholder: "http://192.168.1.2:8080",
      cameraCredentials: "Camera credentials",
      username: "Username",
      password: "Password",
      camera: "Camera",
      scanFirst: "Scan for a camera first",
      scan: "Scan",
      connect: "Connect",
      connected: "Connected",
      control: "Control",
      views: "Views",
      cameraStatus: "Camera status",
      exposureControls: "Exposure controls",
      captureMode: "Capture mode",
      focusStep: "Focus step",
      focusStepSmall: "Small focus step",
      focusStepMedium: "Medium focus step",
      focusStepLarge: "Large focus step",
      media: "Media",
      diagnostics: "Diagnostics",
      refresh: "Refresh",
      disconnect: "Disconnect",
      startLiveView: "Start Live View",
      stopLiveView: "Stop Live View",
      photo: "Photo",
      video: "Video",
      capture: "Capture",
      record: "Record",
      stopRecording: "Stop recording",
      ready: "Ready",
      busy: "Working",
      autofocus: "AF-ON",
      halfPress: "Half-press",
      liveView: "Live View",
      manualFocus: "Manual focus",
      liveViewMagnification: "Set Live View magnification to {value}x",
      liveViewMagnificationChanged: "Live View magnification set to {value}x",
      near: "Near",
      far: "Far",
      liveViewSettings: "Live View",
      liveViewSource: "Source",
      liveViewSourceAuto: "Auto",
      liveViewSourceRtp: "RTP H.264",
      liveViewSourceJpeg: "JPEG polling",
      liveViewSourceBridge: "Desktop preview",
      frameRate: "Frame rate",
      moreSettings: "More settings",
      diagnosticSafe: "Authentication secrets and camera serial are excluded",
      copy: "Copy",
      close: "Close",
      bridgeReady: "{engine} ready",
      bridgeUnavailable: "Camera engine unavailable",
      bridgeError: "Bridge is unavailable",
      camerasFound: "{count} camera(s) found",
      noCameras: "No camera detected",
      selectCamera: "Select a camera",
      scanning: "Scanning for cameras",
      connecting: "Connecting to camera",
      refreshing: "Refreshing camera state",
      disconnected: "Camera disconnected",
      captureComplete: "Photo captured",
      autofocusComplete: "Autofocus complete",
      halfPressComplete: "Shutter half-press complete",
      recordingStarted: "Recording started",
      recordingStopped: "Recording stopped",
      liveViewStarted: "Live View started",
      liveViewStopped: "Live View stopped",
      focusMoved: "Focus moved {direction}",
      focusAccepted: "Focus point accepted",
      tapToFocus: "Select a point to focus",
      clickWhiteBalanceAccepted: "White balance sampled",
      tapToWhiteBalance: "Select a neutral point for white balance",
      tapAction: "Viewfinder tap",
      tapActionFocus: "Focus",
      tapActionWhiteBalance: "White balance",
      settingUpdated: "{label} set to {value}",
      exposure: "Exposure",
      cameraSetting: "Camera setting",
      unsupported: "Not supported by this camera",
      liveViewRequired: "Start Live View to move focus",
      iso: "ISO",
      shutter: "Tv",
      aperture: "Av",
      whitebalance: "WB",
      exposurecompensation: "Exposure compensation",
      afoperation: "Focus mode",
      afmethod: "AF method",
      drivemode: "Drive mode",
      meteringmode: "Metering mode",
      picturestyle: "Picture style",
      stillimagequality: "Image quality",
      "stillimagequality.raw": "RAW quality",
      "stillimagequality.jpeg": "JPEG quality",
      "stillimagequality.heif": "HEIF quality",
      stillimagequalitysd: "SD image quality",
      stillimagequalitycf: "CF/CFexpress image quality",
      shootingmode: "Shooting mode",
      colortemperature: "Color temperature",
      whitebalanceadjusta: "White balance shift A",
      whitebalanceadjustb: "White balance shift B",
      "wbshift.ba": "WB shift B/A",
      "wbshift.mg": "WB shift M/G",
      colorspace: "Color space",
      aspectratio: "Aspect ratio",
      zoomspeed: "Power zoom speed",
      autopoweroff: "Auto power off",
      capturetarget: "Capture target",
      highisonr: "High ISO noise reduction",
      continuousaf: "Continuous AF",
      movieservoaf: "Movie Servo AF",
      aeb: "Auto exposure bracketing",
      valueAuto: "Auto",
      valueOn: "On",
      valueOff: "Off",
      valueLow: "Low",
      valueNormal: "Normal",
      valueHigh: "High",
      valueDisable: "Disable",
      valueInternalRam: "Computer",
      valueMemoryCard: "Memory card",
      duration15Seconds: "15 seconds",
      duration30Seconds: "30 seconds",
      duration1Minute: "1 minute",
      duration3Minutes: "3 minutes",
      duration5Minutes: "5 minutes",
      duration10Minutes: "10 minutes",
      duration30Minutes: "30 minutes",
      imageQualityLargeFineJpeg: "Large Fine JPEG",
      imageQualityLargeNormalJpeg: "Large Normal JPEG",
      imageQualitySmallerJpeg: "Smaller JPEG",
      imageQualityCrawLargeFineJpeg: "cRAW + Large Fine JPEG",
      imageQualityCrawLargeNormalJpeg: "cRAW + Large Normal JPEG",
      imageQualityRawLargeFineJpeg: "RAW + Large Fine JPEG",
      imageQualityRawLargeNormalJpeg: "RAW + Large Normal JPEG",
      imageQualityCrawSmallerJpeg: "cRAW + Smaller JPEG",
      imageQualityRawSmallerJpeg: "RAW + Smaller JPEG",
      imageQualityRaw: "RAW",
      imageQualityCraw: "cRAW",
      imageQualityNone: "None",
      imageQualityLarge: "Large",
      imageQualityMedium1: "Medium 1",
      imageQualityMedium2: "Medium 2",
      imageQualitySmall: "Small",
      imageQualityLargeFine: "Large/Fine",
      imageQualityLargeNormal: "Large/Normal",
      imageQualityMediumFine: "Medium/Fine",
      imageQualityMediumNormal: "Medium/Normal",
      imageQualitySmall1Fine: "Small 1/Fine",
      imageQualitySmall1Normal: "Small 1/Normal",
      imageQualitySmall2: "Small 2",
      mediaCount: "{count} media item(s)",
      mediaEmpty: "No media was reported by the camera",
      mediaThumbnail: "Thumbnail for {name}",
      download: "Download",
      downloaded: "Downloaded {name}",
      delete: "Delete",
      deleteConfirm: "Permanently delete {name} from the camera card? This cannot be undone.",
      deleted: "Deleted {name}",
      copied: "Diagnostic report copied",
      copyFailed: "Could not copy the diagnostic report",
      operationFailed: "Operation failed",
      battery: "Battery",
      storage: "Storage",
      freeImages: "{count} shots",
      notAvailable: "Not available",
      liveViewImage: "Camera Live View",
      authRequired: "This bridge requires a Bearer token",
    },
    "zh-TW": {
      desktopControl: "電腦相機控制",
      language: "語言",
      auto: "自動",
      connectCamera: "連接相機",
      checkingBridge: "正在檢查 Bridge",
      bearerToken: "Bridge Bearer token",
      tokenOptional: "本機連線可留空",
      connectionType: "連線方式",
      usbCamera: "USB 相機",
      wirelessCcapi: "無線 CCAPI",
      cameraUrl: "相機網址",
      cameraUrlPlaceholder: "http://192.168.1.2:8080",
      cameraCredentials: "相機帳號",
      username: "使用者名稱",
      password: "密碼",
      camera: "相機",
      scanFirst: "請先掃描相機",
      scan: "掃描",
      connect: "連線",
      connected: "已連線",
      control: "控制",
      views: "檢視頁面",
      cameraStatus: "相機狀態",
      exposureControls: "曝光控制",
      captureMode: "拍攝類型",
      focusStep: "對焦移動幅度",
      focusStepSmall: "小幅移動焦點",
      focusStepMedium: "中幅移動焦點",
      focusStepLarge: "大幅移動焦點",
      media: "媒體",
      diagnostics: "診斷",
      refresh: "重新整理",
      disconnect: "中斷連線",
      startLiveView: "啟動即時預覽",
      stopLiveView: "停止即時預覽",
      photo: "拍照",
      video: "錄影",
      capture: "拍攝",
      record: "開始錄影",
      stopRecording: "停止錄影",
      ready: "就緒",
      busy: "處理中",
      autofocus: "AF-ON",
      halfPress: "半按快門",
      liveView: "即時預覽",
      manualFocus: "手動對焦",
      liveViewMagnification: "將即時預覽對焦放大設為 {value} 倍",
      liveViewMagnificationChanged: "即時預覽對焦放大已設為 {value} 倍",
      near: "近",
      far: "遠",
      liveViewSettings: "即時預覽",
      liveViewSource: "畫面來源",
      liveViewSourceAuto: "自動",
      liveViewSourceRtp: "RTP H.264",
      liveViewSourceJpeg: "JPEG 輪詢",
      liveViewSourceBridge: "電腦預覽",
      frameRate: "影格率",
      moreSettings: "更多設定",
      diagnosticSafe: "診斷內容不包含驗證機密與相機序號",
      copy: "複製",
      close: "關閉",
      bridgeReady: "{engine} 已就緒",
      bridgeUnavailable: "相機引擎無法使用",
      bridgeError: "無法連接 Bridge",
      camerasFound: "找到 {count} 台相機",
      noCameras: "未偵測到相機",
      selectCamera: "選擇相機",
      scanning: "正在掃描相機",
      connecting: "正在連接相機",
      refreshing: "正在更新相機狀態",
      disconnected: "相機已中斷連線",
      captureComplete: "拍攝完成",
      autofocusComplete: "自動對焦完成",
      halfPressComplete: "快門半按完成",
      recordingStarted: "已開始錄影",
      recordingStopped: "已停止錄影",
      liveViewStarted: "即時預覽已啟動",
      liveViewStopped: "即時預覽已停止",
      focusMoved: "焦點已向{direction}移動",
      focusAccepted: "已設定對焦點",
      tapToFocus: "點選畫面設定對焦點",
      clickWhiteBalanceAccepted: "已取樣白平衡",
      tapToWhiteBalance: "點選中性色彩位置設定白平衡",
      tapAction: "取景畫面點按",
      tapActionFocus: "對焦",
      tapActionWhiteBalance: "白平衡",
      settingUpdated: "{label} 已設為 {value}",
      exposure: "曝光",
      cameraSetting: "相機設定",
      unsupported: "此相機不支援",
      liveViewRequired: "請先啟動即時預覽再移動焦點",
      iso: "ISO",
      shutter: "快門",
      aperture: "光圈",
      whitebalance: "白平衡",
      exposurecompensation: "曝光補償",
      afoperation: "對焦模式",
      afmethod: "自動對焦方式",
      drivemode: "驅動模式",
      meteringmode: "測光模式",
      picturestyle: "相片風格",
      stillimagequality: "影像品質",
      "stillimagequality.raw": "RAW 畫質",
      "stillimagequality.jpeg": "JPEG 畫質",
      "stillimagequality.heif": "HEIF 畫質",
      stillimagequalitysd: "SD 卡影像品質",
      stillimagequalitycf: "CF／CFexpress 卡影像品質",
      shootingmode: "拍攝模式",
      colortemperature: "色溫",
      whitebalanceadjusta: "白平衡偏移 A",
      whitebalanceadjustb: "白平衡偏移 B",
      "wbshift.ba": "白平衡偏移 B/A",
      "wbshift.mg": "白平衡偏移 M/G",
      colorspace: "色彩空間",
      aspectratio: "畫面比例",
      zoomspeed: "電動變焦速度",
      autopoweroff: "自動關閉電源",
      capturetarget: "拍攝儲存位置",
      highisonr: "高 ISO 降噪",
      continuousaf: "連續自動對焦",
      movieservoaf: "短片伺服自動對焦",
      aeb: "自動包圍曝光",
      valueAuto: "自動",
      valueOn: "開啟",
      valueOff: "關閉",
      valueLow: "低",
      valueNormal: "標準",
      valueHigh: "高",
      valueDisable: "停用",
      valueInternalRam: "電腦",
      valueMemoryCard: "記憶卡",
      duration15Seconds: "15 秒",
      duration30Seconds: "30 秒",
      duration1Minute: "1 分鐘",
      duration3Minutes: "3 分鐘",
      duration5Minutes: "5 分鐘",
      duration10Minutes: "10 分鐘",
      duration30Minutes: "30 分鐘",
      imageQualityLargeFineJpeg: "大型精細 JPEG",
      imageQualityLargeNormalJpeg: "大型一般 JPEG",
      imageQualitySmallerJpeg: "較小型 JPEG",
      imageQualityCrawLargeFineJpeg: "cRAW ＋大型精細 JPEG",
      imageQualityCrawLargeNormalJpeg: "cRAW ＋大型一般 JPEG",
      imageQualityRawLargeFineJpeg: "RAW ＋大型精細 JPEG",
      imageQualityRawLargeNormalJpeg: "RAW ＋大型一般 JPEG",
      imageQualityCrawSmallerJpeg: "cRAW ＋較小型 JPEG",
      imageQualityRawSmallerJpeg: "RAW ＋較小型 JPEG",
      imageQualityRaw: "RAW",
      imageQualityCraw: "cRAW",
      imageQualityNone: "無",
      imageQualityLarge: "大型",
      imageQualityMedium1: "中型 1",
      imageQualityMedium2: "中型 2",
      imageQualitySmall: "小型",
      imageQualityLargeFine: "大型／精細",
      imageQualityLargeNormal: "大型／一般",
      imageQualityMediumFine: "中型／精細",
      imageQualityMediumNormal: "中型／一般",
      imageQualitySmall1Fine: "小型 1／精細",
      imageQualitySmall1Normal: "小型 1／一般",
      imageQualitySmall2: "小型 2",
      mediaCount: "共 {count} 個媒體檔案",
      mediaEmpty: "相機未回報任何媒體檔案",
      mediaThumbnail: "{name} 的縮圖",
      download: "下載",
      downloaded: "已下載 {name}",
      delete: "刪除",
      deleteConfirm: "確定要從相機儲存卡永久刪除「{name}」嗎？此操作無法復原。",
      deleted: "已刪除 {name}",
      copied: "已複製診斷報告",
      copyFailed: "無法複製診斷報告",
      operationFailed: "操作失敗",
      battery: "電池",
      storage: "儲存空間",
      freeImages: "可拍 {count} 張",
      notAvailable: "無資料",
      liveViewImage: "相機即時預覽",
      authRequired: "此 Bridge 需要 Bearer token",
    },
  };

  const commonSettingValueKeys = {
    auto: "valueAuto",
    on: "valueOn",
    off: "valueOff",
    low: "valueLow",
    normal: "valueNormal",
    high: "valueHigh",
  };
  const autoPowerOffValueKeys = {
    0: "valueDisable",
    15: "duration15Seconds",
    30: "duration30Seconds",
    60: "duration1Minute",
    180: "duration3Minutes",
    300: "duration5Minutes",
    600: "duration10Minutes",
    1800: "duration30Minutes",
  };
  const imageQualityValueKeys = {
    none: "imageQualityNone",
    raw: "imageQualityRaw",
    craw: "imageQualityCraw",
    large: "imageQualityLarge",
    medium1: "imageQualityMedium1",
    medium2: "imageQualityMedium2",
    small: "imageQualitySmall",
    large_fine: "imageQualityLargeFine",
    large_normal: "imageQualityLargeNormal",
    medium_fine: "imageQualityMediumFine",
    medium_normal: "imageQualityMediumNormal",
    small1_fine: "imageQualitySmall1Fine",
    small1_normal: "imageQualitySmall1Normal",
    small2: "imageQualitySmall2",
    "Large Fine JPEG": "imageQualityLargeFineJpeg",
    "Large Normal JPEG": "imageQualityLargeNormalJpeg",
    "Smaller JPEG": "imageQualitySmallerJpeg",
    "cRAW + Large Fine JPEG": "imageQualityCrawLargeFineJpeg",
    "cRAW + Large Normal JPEG": "imageQualityCrawLargeNormalJpeg",
    "RAW + Large Fine JPEG": "imageQualityRawLargeFineJpeg",
    "RAW + Large Normal JPEG": "imageQualityRawLargeNormalJpeg",
    "cRAW + Smaller JPEG": "imageQualityCrawSmallerJpeg",
    "RAW + Smaller JPEG": "imageQualityRawSmallerJpeg",
    RAW: "imageQualityRaw",
    cRAW: "imageQualityCraw",
  };
  const captureTargetValueKeys = {
    "internal ram": "valueInternalRam",
    sdram: "valueInternalRam",
    "memory card": "valueMemoryCard",
    card: "valueMemoryCard",
  };

  function readLanguagePreference() {
    try {
      const stored = localStorage.getItem(LANGUAGE_KEY);
      return ["auto", "en", "zh-TW"].includes(stored) ? stored : "auto";
    } catch (_) {
      return "auto";
    }
  }

  function writeLanguagePreference(language) {
    try {
      localStorage.setItem(LANGUAGE_KEY, language);
    } catch (_) {
      // The selected language still applies for this page when storage is blocked.
    }
  }

  function readCameraPreference(key, fallback = "") {
    try {
      return localStorage.getItem(key) || fallback;
    } catch (_) {
      return fallback;
    }
  }

  function writeCameraPreference(key, value) {
    try {
      if (value) localStorage.setItem(key, value);
      else localStorage.removeItem(key);
    } catch (_) {
      // Connection still works when browser storage is unavailable.
    }
  }

  const state = {
    language: readLanguagePreference(),
    connectionMode: "usb",
    token: "",
    health: null,
    cameras: [],
    session: null,
    info: null,
    status: null,
    capabilities: null,
    activeView: "live",
    captureMode: "photo",
    liveActive: false,
    liveMagnification: 1,
    liveGeneration: 0,
    requestedFps: 1,
    liveSource: "AUTO",
    activeLiveSource: null,
    frameTimes: [],
    observedFps: 0,
    frameBytes: 0,
    frameContentType: null,
    lastFrameAt: null,
    liveObjectUrl: null,
    focusStep: "MEDIUM",
    tapAction: "focus",
    media: [],
    mediaLoaded: false,
    mediaThumbnailUrls: new Map(),
    mediaThumbnailLoads: new Set(),
    mediaThumbnailFailures: new Set(),
    mediaGeneration: 0,
    busy: false,
    lastError: null,
    toastTimer: null,
  };
  let mediaThumbnailObserver = null;

  const byId = (id) => document.getElementById(id);
  const ui = {
    connectionView: byId("connection-view"),
    controlView: byId("control-view"),
    engineState: byId("engine-state"),
    healthDot: byId("health-dot"),
    tokenInput: byId("token-input"),
    connectionMode: byId("connection-mode"),
    usbModeButton: byId("usb-mode-button"),
    ccapiModeButton: byId("ccapi-mode-button"),
    usbConnectionFields: byId("usb-connection-fields"),
    ccapiConnectionFields: byId("ccapi-connection-fields"),
    ccapiUrlInput: byId("ccapi-url-input"),
    ccapiUsernameInput: byId("ccapi-username-input"),
    ccapiPasswordInput: byId("ccapi-password-input"),
    cameraSelect: byId("camera-select"),
    scanButton: byId("scan-button"),
    connectButton: byId("connect-button"),
    connectionError: byId("connection-error"),
    cameraName: byId("camera-name"),
    batteryValue: byId("battery-value"),
    storageValue: byId("storage-value"),
    refreshButton: byId("refresh-button"),
    disconnectButton: byId("disconnect-button"),
    livePanel: byId("live-panel"),
    mediaPanel: byId("media-panel"),
    diagnosticsPanel: byId("diagnostics-panel"),
    viewfinder: byId("viewfinder"),
    liveImage: byId("live-image"),
    viewfinderPlaceholder: byId("viewfinder-placeholder"),
    liveToggleButton: byId("live-toggle-button"),
    railLiveButton: byId("rail-live-button"),
    modeIndicator: byId("mode-indicator"),
    frameIndicator: byId("frame-indicator"),
    recordIndicator: byId("record-indicator"),
    liveMagnificationButton: byId("live-magnification-button"),
    liveMagnificationLabel: byId("live-magnification-label"),
    focusReticle: byId("focus-reticle"),
    captureFlash: byId("capture-flash"),
    exposureStrip: byId("exposure-strip"),
    photoModeButton: byId("photo-mode-button"),
    videoModeButton: byId("video-mode-button"),
    shutterButton: byId("shutter-button"),
    shutterLabel: byId("shutter-label"),
    operationState: byId("operation-state"),
    autofocusButton: byId("autofocus-button"),
    halfPressButton: byId("half-press-button"),
    focusSection: byId("focus-section"),
    focusNearButton: byId("focus-near-button"),
    focusFarButton: byId("focus-far-button"),
    fpsSelect: byId("fps-select"),
    liveSourceRow: byId("live-source-row"),
    liveSourceSelect: byId("live-source-select"),
    tapActionRow: byId("tap-action-row"),
    tapActionSelect: byId("tap-action-select"),
    advancedSettings: byId("advanced-settings"),
    mediaRefreshButton: byId("media-refresh-button"),
    mediaSummary: byId("media-summary"),
    mediaList: byId("media-list"),
    diagnosticsRefreshButton: byId("diagnostics-refresh-button"),
    copyDiagnosticsButton: byId("copy-diagnostics-button"),
    diagnosticsOutput: byId("diagnostics-output"),
    settingDialog: byId("setting-dialog"),
    settingDialogGroup: byId("setting-dialog-group"),
    settingDialogTitle: byId("setting-dialog-title"),
    settingDialogClose: byId("setting-dialog-close"),
    settingOptions: byId("setting-options"),
    toast: byId("toast"),
  };

  class ApiError extends Error {
    constructor(message, { code = "HTTP_ERROR", status = 0, feature = null, engine = null } = {}) {
      super(message);
      this.name = "ApiError";
      this.code = code;
      this.status = status;
      this.feature = feature;
      this.engine = engine;
    }
  }

  function resolvedLanguage() {
    if (state.language !== "auto") return state.language;
    const preferred = navigator.languages || [navigator.language || "en"];
    return preferred.some((language) => language.toLowerCase().startsWith("zh")) ? "zh-TW" : "en";
  }

  function t(key, values = {}) {
    const language = resolvedLanguage();
    let value = messages[language][key] || messages.en[key] || key;
    Object.entries(values).forEach(([name, replacement]) => {
      value = value.replaceAll(`{${name}}`, String(replacement));
    });
    return value;
  }

  function applyLanguage() {
    const language = resolvedLanguage();
    document.documentElement.lang = language === "zh-TW" ? "zh-Hant-TW" : "en";
    document.querySelectorAll(".language-select").forEach((select) => {
      select.value = state.language;
      select.setAttribute("aria-label", t("language"));
      const automatic = select.querySelector('option[value="auto"]');
      if (automatic) automatic.textContent = t("auto");
    });
    document.querySelectorAll("[data-i18n]").forEach((element) => {
      element.textContent = t(element.dataset.i18n);
    });
    document.querySelectorAll("[data-i18n-placeholder]").forEach((element) => {
      element.placeholder = t(element.dataset.i18nPlaceholder);
    });
    document.querySelectorAll("[data-i18n-aria]").forEach((element) => {
      const label = t(element.dataset.i18nAria);
      element.setAttribute("aria-label", label);
      if (element.hasAttribute("data-tooltip")) element.dataset.tooltip = label;
    });
    ui.liveImage.alt = t("liveViewImage");
    document.querySelector(".camera-metrics > span:first-child")?.setAttribute("title", t("battery"));
    document.querySelector(".camera-metrics > span:last-child")?.setAttribute("title", t("storage"));
    renderConnectionMode();
    renderHealth();
    renderCameras();
    renderSession();
    renderLiveState();
    renderMedia();
    renderDiagnostics();
  }

  async function api(path, { method = "GET", json, responseType = "json", keepalive = false } = {}) {
    const headers = new Headers();
    if (state.token) headers.set("Authorization", `Bearer ${state.token}`);
    if (json !== undefined) headers.set("Content-Type", "application/json");
    let response;
    try {
      response = await fetch(path, {
        method,
        headers,
        body: json === undefined ? undefined : JSON.stringify(json),
        cache: "no-store",
        keepalive,
      });
    } catch (error) {
      throw new ApiError(error instanceof Error ? error.message : t("bridgeError"), { code: "NETWORK_ERROR" });
    }
    if (!response.ok) {
      let detail = null;
      try {
        detail = await response.json();
      } catch (_) {
        // Keep the stable fallback when an intermediary returns non-JSON content.
      }
      const error = detail?.error || {};
      throw new ApiError(error.message || `${response.status} ${response.statusText}`, {
        code: error.code || "HTTP_ERROR",
        status: response.status,
        feature: error.feature,
        engine: error.engine,
      });
    }
    if (response.status === 204) return null;
    if (responseType === "blob") return response.blob();
    if (responseType === "text") return response.text();
    return response.json();
  }

  function captureError(error) {
    const normalized = error instanceof ApiError
      ? error
      : new ApiError(error instanceof Error ? error.message : String(error));
    state.lastError = {
      at: new Date().toISOString(),
      code: normalized.code,
      status: normalized.status,
      message: normalized.message,
      feature: normalized.feature,
      engine: normalized.engine,
    };
    renderDiagnostics();
    return normalized;
  }

  function showConnectionError(error) {
    const normalized = captureError(error);
    ui.connectionError.textContent = normalized.message;
    ui.connectionError.hidden = false;
  }

  function clearConnectionError() {
    ui.connectionError.textContent = "";
    ui.connectionError.hidden = true;
  }

  function showToast(message, error = false) {
    clearTimeout(state.toastTimer);
    ui.toast.textContent = message;
    ui.toast.classList.toggle("error", error);
    ui.toast.hidden = false;
    state.toastTimer = window.setTimeout(() => {
      ui.toast.hidden = true;
    }, error ? 6000 : 3200);
  }

  function renderHealth() {
    if (!state.health) {
      ui.engineState.textContent = t("checkingBridge");
      ui.healthDot.className = "status-dot warning";
      return;
    }
    const engineName = state.connectionMode === "ccapi" ? "ccapi" : "libgphoto2";
    const engine = state.health.engines?.[engineName] || null;
    if (engine?.available) {
      const display = engine.version || engineName;
      ui.engineState.textContent = t("bridgeReady", { engine: display });
      ui.engineState.title = engine.detail || "";
      ui.healthDot.className = "status-dot success";
    } else {
      ui.engineState.textContent = engine?.detail || t("bridgeUnavailable");
      ui.engineState.title = "";
      ui.healthDot.className = "status-dot warning";
    }
  }

  function validCcapiUrl() {
    try {
      const value = new URL(ui.ccapiUrlInput.value.trim());
      return (
        ["http:", "https:"].includes(value.protocol) && Boolean(value.hostname) &&
        !value.username && !value.password && value.pathname === "/" && !value.search && !value.hash
      );
    } catch (_) {
      return false;
    }
  }

  function renderConnectionMode() {
    const network = state.connectionMode === "ccapi";
    ui.usbModeButton.classList.toggle("active", !network);
    ui.ccapiModeButton.classList.toggle("active", network);
    ui.usbModeButton.setAttribute("aria-pressed", String(!network));
    ui.ccapiModeButton.setAttribute("aria-pressed", String(network));
    ui.usbConnectionFields.hidden = network;
    ui.ccapiConnectionFields.hidden = !network;
    ui.scanButton.hidden = network;
    renderAvailability();
  }

  function selectConnectionMode(mode) {
    if (!["usb", "ccapi"].includes(mode) || state.busy) return;
    state.connectionMode = mode;
    clearConnectionError();
    renderConnectionMode();
    renderHealth();
    if (mode === "usb" && !state.cameras.length && !state.health?.authRequired) scanCameras();
  }

  function renderCameras() {
    const selected = ui.cameraSelect.value;
    ui.cameraSelect.replaceChildren();
    const placeholder = document.createElement("option");
    placeholder.value = "";
    if (!state.cameras.length) {
      placeholder.textContent = t("scanFirst");
      ui.cameraSelect.append(placeholder);
      ui.cameraSelect.disabled = true;
      renderAvailability();
      return;
    }
    placeholder.textContent = t("selectCamera");
    ui.cameraSelect.append(placeholder);
    state.cameras.forEach((camera) => {
      const option = document.createElement("option");
      option.value = camera.id;
      option.textContent = `${camera.model} (${camera.port})`;
      ui.cameraSelect.append(option);
    });
    ui.cameraSelect.disabled = false;
    const fallback = state.cameras.length === 1 ? state.cameras[0].id : "";
    ui.cameraSelect.value = state.cameras.some((camera) => camera.id === selected) ? selected : fallback;
    renderAvailability();
  }

  async function refreshHealth() {
    try {
      state.health = await api("/health");
      renderHealth();
      if (state.health.authRequired) ui.tokenInput.placeholder = t("authRequired");
    } catch (error) {
      state.health = null;
      captureError(error);
      ui.engineState.textContent = t("bridgeError");
      ui.healthDot.className = "status-dot warning";
    }
  }

  async function scanCameras() {
    if (state.connectionMode !== "usb") return;
    clearConnectionError();
    state.token = ui.tokenInput.value.trim();
    ui.scanButton.disabled = true;
    ui.connectButton.disabled = true;
    ui.engineState.textContent = t("scanning");
    try {
      const response = await api("/v1/cameras");
      state.cameras = response.cameras || [];
      renderCameras();
      ui.engineState.textContent = state.cameras.length
        ? t("camerasFound", { count: state.cameras.length })
        : t("noCameras");
    } catch (error) {
      state.cameras = [];
      renderCameras();
      showConnectionError(error);
      renderHealth();
    } finally {
      ui.scanButton.disabled = false;
    }
  }

  async function connectCamera() {
    const network = state.connectionMode === "ccapi";
    const cameraId = ui.cameraSelect.value;
    const ccapiUrl = ui.ccapiUrlInput.value.trim();
    if ((!network && !cameraId) || (network && !validCcapiUrl())) return;
    clearConnectionError();
    state.token = ui.tokenInput.value.trim();
    state.busy = true;
    ui.engineState.textContent = t("connecting");
    renderAvailability();
    try {
      const sessionPayload = network
        ? {
            engine: "ccapi",
            ccapiUrl,
            ccapiUsername: ui.ccapiUsernameInput.value.trim(),
            ccapiPassword: ui.ccapiPasswordInput.value,
          }
        : { engine: "auto", cameraId };
      state.session = await api("/v1/session", {
        method: "POST",
        json: sessionPayload,
      });
      const sessionId = encodeURIComponent(state.session.id);
      [state.info, state.status, state.capabilities] = await Promise.all([
        api(`/v1/session/${sessionId}/info`),
        api(`/v1/session/${sessionId}/status`),
        api(`/v1/session/${sessionId}/capabilities`),
      ]);
      state.requestedFps = clampFps(Math.min(15, state.capabilities.liveView?.maxFps || 1));
      state.captureMode = state.status.recording ? "video" : "photo";
      state.lastError = null;
      setOperationState(t("ready"));
      if (network) {
        writeCameraPreference(CCAPI_URL_KEY, ccapiUrl);
        writeCameraPreference(CCAPI_USERNAME_KEY, ui.ccapiUsernameInput.value.trim());
      }
      ui.tokenInput.value = "";
      ui.ccapiPasswordInput.value = "";
      ui.connectionView.hidden = true;
      ui.controlView.hidden = false;
      renderSession();
      showToast(t("connected"));
    } catch (error) {
      if (state.session?.id) {
        try {
          await api(`/v1/session/${encodeURIComponent(state.session.id)}`, { method: "DELETE" });
        } catch (_) {
          // The original connection error is more useful than cleanup failure.
        }
      }
      state.session = null;
      showConnectionError(error);
      renderHealth();
    } finally {
      state.busy = false;
      renderAvailability();
    }
  }

  async function refreshSession({ quiet = false } = {}) {
    if (!state.session) return;
    if (!quiet) setOperationState(t("refreshing"));
    const sessionId = encodeURIComponent(state.session.id);
    try {
      [state.info, state.status, state.capabilities] = await Promise.all([
        api(`/v1/session/${sessionId}/info`),
        api(`/v1/session/${sessionId}/status`),
        api(`/v1/session/${sessionId}/capabilities`),
      ]);
      state.requestedFps = clampFps(state.requestedFps);
      state.lastError = null;
      renderSession();
      if (!quiet) setOperationState(t("ready"));
    } catch (error) {
      const normalized = captureError(error);
      if (!quiet) {
        setOperationState(normalized.message, true);
        showToast(normalized.message, true);
      }
    }
  }

  async function refreshCapabilityEvidence() {
    if (!state.session) return;
    try {
      state.capabilities = await api(`/v1/session/${encodeURIComponent(state.session.id)}/capabilities`);
    } catch (_) {
      // Evidence refresh must not turn an already successful camera operation into a failure.
    }
  }

  async function disconnectCamera() {
    if (!state.session) return;
    const sessionId = state.session.id;
    state.busy = true;
    renderAvailability();
    stopLiveLoop();
    try {
      await api(`/v1/session/${encodeURIComponent(sessionId)}`, { method: "DELETE" });
    } catch (error) {
      captureError(error);
    }
    resetSession();
    showToast(t("disconnected"));
  }

  function resetSession() {
    stopLiveLoop();
    clearMediaThumbnails();
    state.session = null;
    state.info = null;
    state.status = null;
    state.capabilities = null;
    state.media = [];
    state.mediaLoaded = false;
    state.captureMode = "photo";
    state.liveSource = "AUTO";
    state.activeLiveSource = null;
    state.liveMagnification = 1;
    state.busy = false;
    state.token = "";
    ui.connectionView.hidden = false;
    ui.controlView.hidden = true;
    ui.tokenInput.value = "";
    ui.ccapiPasswordInput.value = "";
    renderAvailability();
    renderHealth();
  }

  function featureSupported(feature) {
    return Boolean(state.capabilities?.supported?.includes(feature));
  }

  function settingByKey(key) {
    return state.capabilities?.settings?.find((setting) => setting.key === key) || null;
  }

  function settingLabel(settingOrKey) {
    const key = typeof settingOrKey === "string" ? settingOrKey : settingOrKey.key;
    return messages[resolvedLanguage()][key] || messages.en[key] || settingOrKey.label || key;
  }

  function settingValueLabel(settingOrKey, value) {
    const key = typeof settingOrKey === "string" ? settingOrKey : settingOrKey.key;
    const rawValue = String(value);
    let messageKey = commonSettingValueKeys[rawValue.toLowerCase()];
    if (key === "autopoweroff") messageKey = autoPowerOffValueKeys[rawValue];
    if (key === "capturetarget") messageKey = captureTargetValueKeys[rawValue.toLowerCase()];
    if (
      [
        "stillimagequality",
        "stillimagequality.raw",
        "stillimagequality.jpeg",
        "stillimagequality.heif",
        "stillimagequalitysd",
        "stillimagequalitycf",
      ].includes(key)
    ) {
      messageKey = imageQualityValueKeys[rawValue];
    }
    return messageKey ? t(messageKey) : rawValue;
  }

  function currentSettingValue(setting) {
    const exposureKey = {
      iso: "iso",
      shutter: "shutter",
      aperture: "aperture",
      whitebalance: "whiteBalance",
    }[setting.key];
    return state.status?.exposure?.[exposureKey] || setting.value || "-";
  }

  function renderSession() {
    if (!state.session || !state.capabilities || !state.status) return;
    ui.cameraName.textContent = state.info?.model || state.session.camera?.model || "Canon EOS";
    const battery = state.status.battery || {};
    ui.batteryValue.textContent = battery.level == null ? (battery.status || "-") : `${battery.level}%`;
    const storage = state.status.media || {};
    ui.storageValue.textContent = storage.freeImages != null
      ? t("freeImages", { count: storage.freeImages })
      : storage.freeBytes != null
        ? formatBytes(storage.freeBytes)
        : "-";
    ui.modeIndicator.textContent = state.status.mode && state.status.mode !== "unknown" ? state.status.mode : "-";
    renderExposure();
    renderAdvancedSettings();
    renderLiveSource();
    renderFps();
    renderTapAction();
    renderCaptureMode();
    renderAvailability();
    renderDiagnostics();
  }

  function renderExposure() {
    ui.exposureStrip.replaceChildren();
    CORE_SETTINGS.forEach((key) => {
      const setting = settingByKey(key);
      const button = document.createElement("button");
      button.type = "button";
      button.className = "exposure-control";
      button.dataset.settingKey = key;
      button.disabled = !setting || state.busy;
      button.title = setting ? settingLabel(setting) : t("unsupported");
      const label = document.createElement("span");
      label.textContent = settingLabel(setting || key);
      const value = document.createElement("strong");
      value.textContent = setting ? settingValueLabel(setting, currentSettingValue(setting)) : "-";
      button.append(label, value);
      if (setting) button.addEventListener("click", () => openSettingDialog(setting));
      ui.exposureStrip.append(button);
    });
  }

  function renderAdvancedSettings() {
    ui.advancedSettings.replaceChildren();
    const settings = (state.capabilities?.settings || []).filter(
      (setting) => !CORE_SETTINGS.includes(setting.key) && settingMatchesCaptureMode(setting),
    );
    if (!settings.length) {
      const empty = document.createElement("p");
      empty.className = "supporting";
      empty.textContent = t("notAvailable");
      ui.advancedSettings.append(empty);
      return;
    }
    settings.forEach((setting) => {
      const label = document.createElement("label");
      const text = document.createElement("span");
      text.textContent = settingLabel(setting);
      const select = document.createElement("select");
      select.disabled = state.busy;
      setting.values.forEach((value) => {
        const option = document.createElement("option");
        option.value = value;
        option.textContent = settingValueLabel(setting, value);
        select.append(option);
      });
      select.value = setting.value;
      select.addEventListener("change", () => updateSetting(setting, select.value, select));
      label.append(text, select);
      ui.advancedSettings.append(label);
    });
  }

  function settingMatchesCaptureMode(setting) {
    const key = setting.key.toLowerCase();
    const videoTokens = ["movie", "video", "frame", "codec", "record", "sound"];
    const photoTokens = ["still", "photo", "drive", "imagequality", "capturetarget"];
    if (state.captureMode === "photo") return !videoTokens.some((token) => key.includes(token));
    return !photoTokens.some((token) => key.includes(token));
  }

  function openSettingDialog(setting) {
    ui.settingDialogGroup.textContent = CORE_SETTINGS.includes(setting.key) ? t("exposure") : t("cameraSetting");
    ui.settingDialogTitle.textContent = settingLabel(setting);
    ui.settingOptions.replaceChildren();
    const current = currentSettingValue(setting);
    setting.values.forEach((value) => {
      const button = document.createElement("button");
      button.type = "button";
      button.className = "setting-option";
      button.textContent = settingValueLabel(setting, value);
      button.classList.toggle("active", value === current);
      button.setAttribute("aria-pressed", String(value === current));
      button.addEventListener("click", async () => {
        await updateSetting(setting, value, button);
        if (!state.lastError) ui.settingDialog.close();
      });
      ui.settingOptions.append(button);
    });
    ui.settingDialog.showModal();
    requestAnimationFrame(() => ui.settingOptions.querySelector(".active")?.scrollIntoView({ block: "center" }));
  }

  async function updateSetting(setting, value, source) {
    if (!state.session || state.busy) return;
    state.busy = true;
    state.lastError = null;
    source.disabled = true;
    setOperationState(t("busy"));
    renderAvailability();
    try {
      state.status = await api(
        `/v1/session/${encodeURIComponent(state.session.id)}/settings/${encodeURIComponent(setting.key)}`,
        { method: "POST", json: { value } },
      );
      setting.value = value;
      setOperationState(t("ready"));
      showToast(t("settingUpdated", { label: settingLabel(setting), value: settingValueLabel(setting, value) }));
    } catch (error) {
      const normalized = captureError(error);
      setOperationState(normalized.message, true);
      showToast(normalized.message, true);
    } finally {
      state.busy = false;
      source.disabled = false;
      renderSession();
    }
  }

  function renderCaptureMode() {
    const recording = Boolean(state.status?.recording);
    if (!featureSupported(FEATURES.VIDEO_RECORDING) && state.captureMode === "video") state.captureMode = "photo";
    ui.photoModeButton.classList.toggle("active", state.captureMode === "photo");
    ui.videoModeButton.classList.toggle("active", state.captureMode === "video");
    ui.photoModeButton.setAttribute("aria-pressed", String(state.captureMode === "photo"));
    ui.videoModeButton.setAttribute("aria-pressed", String(state.captureMode === "video"));
    ui.shutterButton.classList.toggle("video", state.captureMode === "video");
    ui.shutterButton.classList.toggle("recording", state.captureMode === "video" && recording);
    ui.recordIndicator.hidden = !recording;
    const labelKey = state.captureMode === "photo" ? "capture" : recording ? "stopRecording" : "record";
    ui.shutterLabel.textContent = t(labelKey);
    ui.shutterButton.setAttribute("aria-label", t(labelKey));
    replaceButtonIcon(ui.shutterButton, state.captureMode === "video" ? (recording ? "square" : "circle") : "camera");
  }

  function selectCaptureMode(mode) {
    if (mode === "video" && !featureSupported(FEATURES.VIDEO_RECORDING)) return;
    if (state.status?.recording && mode !== "video") return;
    state.captureMode = mode;
    renderCaptureMode();
    renderAdvancedSettings();
    renderAvailability();
  }

  async function operateShutter() {
    if (!state.session || state.busy) return;
    const isPhoto = state.captureMode === "photo";
    const supported = isPhoto
      ? featureSupported(FEATURES.STILL_CAPTURE)
      : featureSupported(FEATURES.VIDEO_RECORDING);
    if (!supported) return;
    state.busy = true;
    state.lastError = null;
    setOperationState(t("busy"));
    renderAvailability();
    try {
      if (isPhoto) {
        state.status = await api(`/v1/session/${encodeURIComponent(state.session.id)}/capture/still`, {
          method: "POST",
        });
        flashCapture();
        setOperationState(t("captureComplete"));
        showToast(t("captureComplete"));
      } else {
        const wasRecording = Boolean(state.status?.recording);
        state.status = await api(
          `/v1/session/${encodeURIComponent(state.session.id)}/recording/${wasRecording ? "stop" : "start"}`,
          { method: "POST" },
        );
        const result = wasRecording ? t("recordingStopped") : t("recordingStarted");
        setOperationState(result);
        showToast(result);
      }
    } catch (error) {
      const normalized = captureError(error);
      setOperationState(normalized.message, true);
      showToast(normalized.message, true);
    } finally {
      state.busy = false;
      renderSession();
    }
  }

  async function autofocus() {
    if (!state.session || state.busy || !featureSupported(FEATURES.AUTOFOCUS)) return;
    state.busy = true;
    setOperationState(t("busy"));
    renderAvailability();
    try {
      state.status = await api(`/v1/session/${encodeURIComponent(state.session.id)}/focus/auto`, {
        method: "POST",
      });
      setOperationState(t("autofocusComplete"));
      showToast(t("autofocusComplete"));
    } catch (error) {
      const normalized = captureError(error);
      setOperationState(normalized.message, true);
      showToast(normalized.message, true);
    } finally {
      state.busy = false;
      renderSession();
    }
  }

  async function halfPressShutter() {
    if (!state.session || state.busy || !featureSupported(FEATURES.SHUTTER_HALF_PRESS)) return;
    state.busy = true;
    setOperationState(t("busy"));
    renderAvailability();
    try {
      state.status = await api(`/v1/session/${encodeURIComponent(state.session.id)}/shutter/half-press`, {
        method: "POST",
      });
      setOperationState(t("halfPressComplete"));
      showToast(t("halfPressComplete"));
    } catch (error) {
      const normalized = captureError(error);
      setOperationState(normalized.message, true);
      showToast(normalized.message, true);
    } finally {
      state.busy = false;
      renderSession();
    }
  }

  function flashCapture() {
    ui.captureFlash.classList.remove("active");
    void ui.captureFlash.offsetWidth;
    ui.captureFlash.classList.add("active");
  }

  function liveCapabilities() {
    return state.capabilities?.liveView || {};
  }

  function clampFps(value) {
    const minimum = liveCapabilities().minFps || 1;
    const maximum = liveCapabilities().maxFps || 1;
    return Math.max(minimum, Math.min(maximum, Number(value) || minimum));
  }

  function renderFps() {
    const capabilities = liveCapabilities();
    const minimum = capabilities.minFps || 1;
    const maximum = capabilities.maxFps || 1;
    state.requestedFps = clampFps(state.requestedFps);
    ui.fpsSelect.replaceChildren();
    const preferred = [1, 2, 3, 4, 5, 6, 8, 10, 12, 15, 20, 24, 25, 30];
    const values = preferred.filter((fps) => fps >= minimum && fps <= maximum);
    if (!values.includes(minimum)) values.push(minimum);
    if (!values.includes(maximum)) values.push(maximum);
    values.sort((left, right) => left - right).forEach((fps) => {
      const option = document.createElement("option");
      option.value = String(fps);
      option.textContent = `${fps} FPS`;
      ui.fpsSelect.append(option);
    });
    ui.fpsSelect.value = String(state.requestedFps);
    ui.fpsSelect.disabled = state.busy || !featureSupported(FEATURES.LIVE_VIEW);
  }

  function renderLiveSource() {
    const sources = liveCapabilities().sources || [];
    if (state.liveSource !== "AUTO" && !sources.includes(state.liveSource)) state.liveSource = "AUTO";
    const options = sources.length > 1 ? ["AUTO", ...sources] : sources;
    const labels = {
      AUTO: "liveViewSourceAuto",
      CCAPI_RTP: "liveViewSourceRtp",
      CCAPI_JPEG_POLLING: "liveViewSourceJpeg",
      DESKTOP_BRIDGE_STREAM: "liveViewSourceBridge",
    };
    ui.liveSourceSelect.replaceChildren();
    options.forEach((source) => {
      const option = document.createElement("option");
      option.value = source;
      option.textContent = t(labels[source] || "liveViewSource");
      ui.liveSourceSelect.append(option);
    });
    if (sources.length === 1) state.liveSource = sources[0];
    ui.liveSourceSelect.value = state.liveSource;
    ui.liveSourceRow.hidden = sources.length <= 1;
    ui.liveSourceSelect.disabled = state.busy || !featureSupported(FEATURES.LIVE_VIEW);
  }

  function effectiveTapAction() {
    if (state.tapAction === "whiteBalance" && featureSupported(FEATURES.CLICK_WHITE_BALANCE)) {
      return "whiteBalance";
    }
    if (featureSupported(FEATURES.TAP_FOCUS)) return "focus";
    if (featureSupported(FEATURES.CLICK_WHITE_BALANCE)) return "whiteBalance";
    return null;
  }

  function renderTapAction() {
    const clickWhiteBalanceSupported = featureSupported(FEATURES.CLICK_WHITE_BALANCE);
    ui.tapActionRow.hidden = !clickWhiteBalanceSupported;
    const focusOption = ui.tapActionSelect.querySelector('option[value="focus"]');
    focusOption.hidden = !featureSupported(FEATURES.TAP_FOCUS);
    const effective = effectiveTapAction();
    if (effective) state.tapAction = effective;
    ui.tapActionSelect.value = state.tapAction;
    ui.tapActionSelect.disabled = state.busy || !state.liveActive;
  }

  async function toggleLiveView() {
    if (state.liveActive) await stopLiveView();
    else await startLiveView();
  }

  async function startLiveView({ announce = true } = {}) {
    if (!state.session || state.busy || !featureSupported(FEATURES.LIVE_VIEW)) return;
    state.busy = true;
    state.lastError = null;
    setOperationState(t("busy"));
    renderAvailability();
    const capabilities = liveCapabilities();
    try {
      const response = await api(`/v1/session/${encodeURIComponent(state.session.id)}/liveview/start`, {
        method: "POST",
        json: {
          fps: clampFps(state.requestedFps),
          size: capabilities.defaultSize || capabilities.sizes?.[0] || "MEDIUM",
          source: state.liveSource || "AUTO",
        },
      });
      state.requestedFps = response.requestedFps || clampFps(state.requestedFps);
      state.activeLiveSource = response.source || state.liveSource;
      state.liveActive = true;
      state.liveMagnification = 1;
      state.frameTimes = [];
      state.observedFps = 0;
      state.liveGeneration += 1;
      pollLiveView(state.liveGeneration);
      setOperationState(t("liveViewStarted"));
      if (announce) showToast(t("liveViewStarted"));
    } catch (error) {
      const normalized = captureError(error);
      state.liveActive = false;
      setOperationState(normalized.message, true);
      showToast(normalized.message, true);
    } finally {
      state.busy = false;
      renderLiveState();
      renderAvailability();
      renderFps();
    }
  }

  async function stopLiveView({ announce = true, remote = true } = {}) {
    if (!state.session) return;
    stopLiveLoop();
    if (remote) {
      state.busy = true;
      renderAvailability();
      try {
        await api(`/v1/session/${encodeURIComponent(state.session.id)}/liveview/stop`, { method: "POST" });
        setOperationState(t("liveViewStopped"));
        if (announce) showToast(t("liveViewStopped"));
      } catch (error) {
        const normalized = captureError(error);
        setOperationState(normalized.message, true);
        if (announce) showToast(normalized.message, true);
      } finally {
        state.busy = false;
      }
    }
    renderLiveState();
    renderAvailability();
  }

  function stopLiveLoop() {
    state.liveActive = false;
    state.liveGeneration += 1;
    state.frameTimes = [];
    state.observedFps = 0;
    state.frameBytes = 0;
    state.frameContentType = null;
    state.activeLiveSource = null;
    state.liveMagnification = 1;
    state.lastFrameAt = null;
    if (state.liveObjectUrl) URL.revokeObjectURL(state.liveObjectUrl);
    state.liveObjectUrl = null;
    ui.liveImage.removeAttribute("src");
    renderLiveState();
  }

  async function pollLiveView(generation) {
    while (state.liveActive && generation === state.liveGeneration && state.session) {
      const started = performance.now();
      try {
        const blob = await api(`/v1/session/${encodeURIComponent(state.session.id)}/liveview/frame`, {
          responseType: "blob",
        });
        if (!state.liveActive || generation !== state.liveGeneration) return;
        const url = URL.createObjectURL(blob);
        const previous = state.liveObjectUrl;
        state.liveObjectUrl = url;
        ui.liveImage.src = url;
        ui.liveImage.hidden = false;
        ui.viewfinderPlaceholder.hidden = true;
        if (previous) window.setTimeout(() => URL.revokeObjectURL(previous), 1000);
        const now = performance.now();
        state.frameTimes.push(now);
        state.frameTimes = state.frameTimes.filter((time) => now - time <= 2000);
        if (state.frameTimes.length > 1) {
          const duration = state.frameTimes.at(-1) - state.frameTimes[0];
          state.observedFps = duration > 0 ? ((state.frameTimes.length - 1) * 1000) / duration : 0;
        }
        state.frameBytes = blob.size;
        state.frameContentType = blob.type || "image/jpeg";
        state.lastFrameAt = new Date().toISOString();
        renderFrameIndicator();
      } catch (error) {
        if (!state.liveActive || generation !== state.liveGeneration) return;
        const normalized = captureError(error);
        stopLiveLoop();
        setOperationState(normalized.message, true);
        showToast(normalized.message, true);
        try {
          await api(`/v1/session/${encodeURIComponent(state.session.id)}/liveview/stop`, { method: "POST" });
        } catch (_) {
          // The frame error remains the primary diagnostic.
        }
        return;
      }
      const elapsed = performance.now() - started;
      const delay = Math.max(0, 1000 / state.requestedFps - elapsed);
      if (delay > 0) await sleep(delay);
    }
  }

  async function changeFps() {
    state.requestedFps = clampFps(ui.fpsSelect.value);
    if (!state.liveActive) {
      renderFrameIndicator();
      return;
    }
    await stopLiveView({ announce: false });
    await startLiveView({ announce: false });
    showToast(`${state.requestedFps} FPS`);
  }

  async function changeLiveSource() {
    state.liveSource = ui.liveSourceSelect.value;
    if (!state.liveActive) return;
    await stopLiveView({ announce: false });
    await startLiveView({ announce: false });
  }

  function renderLiveState() {
    ui.liveImage.hidden = !state.liveActive || !state.liveObjectUrl;
    ui.viewfinderPlaceholder.hidden = state.liveActive && Boolean(state.liveObjectUrl);
    const labelKey = state.liveActive ? "stopLiveView" : "startLiveView";
    [ui.liveToggleButton, ui.railLiveButton].forEach((button) => {
      const label = button.querySelector("span[data-i18n]");
      if (label) {
        label.dataset.i18n = labelKey;
        label.textContent = t(labelKey);
      }
      button.setAttribute("aria-label", t(labelKey));
      replaceButtonIcon(button, state.liveActive ? "square" : "play");
    });
    if (!state.liveActive) ui.focusReticle.hidden = true;
    renderLiveMagnification();
    renderFrameIndicator();
  }

  function renderLiveMagnification() {
    const supported = featureSupported(FEATURES.LIVE_VIEW_MAGNIFICATION);
    const target = state.liveMagnification === 5 ? 1 : 5;
    ui.liveMagnificationButton.hidden = !supported;
    ui.liveMagnificationButton.disabled = state.busy || !state.liveActive || !supported;
    ui.liveMagnificationLabel.textContent = `${target}x`;
    const description = t("liveViewMagnification", { value: target });
    ui.liveMagnificationButton.setAttribute("aria-label", description);
    ui.liveMagnificationButton.title = description;
    replaceButtonIcon(ui.liveMagnificationButton, target === 5 ? "zoom-in" : "zoom-out");
  }

  async function setLiveViewMagnification() {
    if (
      !state.session || state.busy || !state.liveActive ||
      !featureSupported(FEATURES.LIVE_VIEW_MAGNIFICATION)
    ) return;
    const target = state.liveMagnification === 5 ? 1 : 5;
    state.busy = true;
    setOperationState(t("busy"));
    renderAvailability();
    try {
      const result = await api(
        `/v1/session/${encodeURIComponent(state.session.id)}/liveview/magnification`,
        { method: "POST", json: { value: target } },
      );
      if (result.accepted) state.liveMagnification = result.value;
      const message = t("liveViewMagnificationChanged", { value: state.liveMagnification });
      setOperationState(message);
      showToast(message);
    } catch (error) {
      const normalized = captureError(error);
      setOperationState(normalized.message, true);
      showToast(normalized.message, true);
    } finally {
      state.busy = false;
      renderAvailability();
      renderLiveMagnification();
    }
  }

  function renderFrameIndicator() {
    const fps = state.observedFps ? state.observedFps.toFixed(1) : "0";
    ui.frameIndicator.textContent = `${fps} / ${state.requestedFps} FPS`;
    renderDiagnostics();
  }

  async function driveFocus(direction) {
    if (!state.session || state.busy || !featureSupported(FEATURES.FOCUS_DRIVE)) return;
    if (!state.liveActive) {
      showToast(t("liveViewRequired"), true);
      return;
    }
    state.busy = true;
    setOperationState(t("busy"));
    renderAvailability();
    try {
      await api(`/v1/session/${encodeURIComponent(state.session.id)}/focus/drive`, {
        method: "POST",
        json: { direction, step: state.focusStep },
      });
      const localizedDirection = direction === "NEAR" ? t("near") : t("far");
      setOperationState(t("focusMoved", { direction: localizedDirection }));
    } catch (error) {
      const normalized = captureError(error);
      setOperationState(normalized.message, true);
      showToast(normalized.message, true);
    } finally {
      state.busy = false;
      renderAvailability();
    }
  }

  function focusPointFromClient(clientX, clientY) {
    const bounds = ui.viewfinder.getBoundingClientRect();
    const naturalWidth = ui.liveImage.naturalWidth || bounds.width;
    const naturalHeight = ui.liveImage.naturalHeight || bounds.height;
    const scale = Math.min(bounds.width / naturalWidth, bounds.height / naturalHeight);
    const imageWidth = naturalWidth * scale;
    const imageHeight = naturalHeight * scale;
    const imageLeft = bounds.left + (bounds.width - imageWidth) / 2;
    const imageTop = bounds.top + (bounds.height - imageHeight) / 2;
    if (
      clientX < imageLeft || clientX > imageLeft + imageWidth ||
      clientY < imageTop || clientY > imageTop + imageHeight
    ) return null;
    return {
      x: (clientX - imageLeft) / imageWidth,
      y: (clientY - imageTop) / imageHeight,
      displayX: clientX - bounds.left,
      displayY: clientY - bounds.top,
    };
  }

  async function tapFocus(point) {
    const action = effectiveTapAction();
    if (
      !point || !state.session || state.busy || !state.liveActive ||
      !action
    ) return;
    state.busy = true;
    ui.focusReticle.style.left = `${point.displayX}px`;
    ui.focusReticle.style.top = `${point.displayY}px`;
    ui.focusReticle.className = "focus-reticle focusing";
    ui.focusReticle.hidden = false;
    setOperationState(t("busy"));
    renderAvailability();
    try {
      if (action === "whiteBalance") {
        state.status = await api(`/v1/session/${encodeURIComponent(state.session.id)}/whitebalance/click`, {
          method: "POST",
          json: { x: point.x, y: point.y },
        });
      } else {
        await api(`/v1/session/${encodeURIComponent(state.session.id)}/focus/tap`, {
          method: "POST",
          json: { x: point.x, y: point.y },
        });
      }
      ui.focusReticle.className = "focus-reticle success";
      setOperationState(t(action === "whiteBalance" ? "clickWhiteBalanceAccepted" : "focusAccepted"));
      window.setTimeout(() => { ui.focusReticle.hidden = true; }, 900);
    } catch (error) {
      const normalized = captureError(error);
      ui.focusReticle.className = "focus-reticle failure";
      setOperationState(normalized.message, true);
      showToast(normalized.message, true);
      window.setTimeout(() => { ui.focusReticle.hidden = true; }, 1300);
    } finally {
      state.busy = false;
      renderSession();
    }
  }

  function tapFocusFromPointer(event) {
    if (event.target.closest?.("button")) return;
    tapFocus(focusPointFromClient(event.clientX, event.clientY));
  }

  function renderAvailability() {
    const connected = Boolean(state.session);
    const videoSupported = featureSupported(FEATURES.VIDEO_RECORDING);
    ui.scanButton.disabled = state.busy;
    const connectionReady = state.connectionMode === "ccapi" ? validCcapiUrl() : Boolean(ui.cameraSelect.value);
    ui.connectButton.disabled = state.busy || !connectionReady;
    ui.refreshButton.disabled = !connected || state.busy;
    ui.disconnectButton.disabled = !connected || state.busy;
    ui.photoModeButton.disabled = state.busy || Boolean(state.status?.recording);
    ui.videoModeButton.disabled = state.busy || !videoSupported;
    ui.videoModeButton.hidden = !videoSupported;
    ui.videoModeButton.parentElement.classList.toggle("single", !videoSupported);
    const shutterSupported = state.captureMode === "photo"
      ? featureSupported(FEATURES.STILL_CAPTURE)
      : videoSupported;
    ui.shutterButton.disabled = state.busy || !shutterSupported;
    ui.shutterButton.title = shutterSupported ? ui.shutterLabel.textContent : t("unsupported");
    const autofocusSupported = featureSupported(FEATURES.AUTOFOCUS);
    ui.autofocusButton.hidden = !autofocusSupported;
    ui.autofocusButton.disabled = state.busy || !autofocusSupported;
    const halfPressSupported = featureSupported(FEATURES.SHUTTER_HALF_PRESS);
    ui.halfPressButton.hidden = !halfPressSupported;
    ui.halfPressButton.disabled = state.busy || !halfPressSupported;
    const liveSupported = featureSupported(FEATURES.LIVE_VIEW);
    [ui.liveToggleButton, ui.railLiveButton].forEach((button) => {
      button.hidden = !liveSupported;
      button.disabled = state.busy || !liveSupported;
    });
    const quickActionCount = [autofocusSupported, halfPressSupported, liveSupported].filter(Boolean).length;
    ui.railLiveButton.parentElement.classList.toggle("single", quickActionCount === 1);
    ui.railLiveButton.parentElement.classList.toggle("three", quickActionCount === 3);
    document.querySelector(".live-settings").hidden = !liveSupported;
    const focusSupported = featureSupported(FEATURES.FOCUS_DRIVE);
    ui.focusSection.hidden = !focusSupported;
    ui.focusNearButton.disabled = state.busy || !state.liveActive;
    ui.focusFarButton.disabled = state.busy || !state.liveActive;
    ui.fpsSelect.disabled = state.busy || !liveSupported;
    ui.liveSourceSelect.disabled = state.busy || !liveSupported;
    ui.tapActionSelect.disabled = state.busy || !state.liveActive;
    renderLiveMagnification();
    document.querySelectorAll("#exposure-strip .exposure-control").forEach((button) => {
      button.disabled = state.busy || !settingByKey(button.dataset.settingKey);
    });
    document.querySelectorAll("#advanced-settings select").forEach((select) => {
      select.disabled = state.busy;
    });
    document.querySelectorAll("#focus-step-control button").forEach((button) => {
      button.disabled = state.busy || !state.liveActive;
    });
    const tapAction = effectiveTapAction();
    const tapFocusEnabled = Boolean(tapAction) && state.liveActive && !state.busy;
    ui.viewfinder.classList.toggle("tap-focus-enabled", tapFocusEnabled);
    const tapDescription = tapAction === "whiteBalance" ? t("tapToWhiteBalance") : t("tapToFocus");
    ui.viewfinder.title = tapFocusEnabled ? tapDescription : "";
    if (tapFocusEnabled) {
      ui.viewfinder.setAttribute("role", "button");
      ui.viewfinder.setAttribute("aria-label", tapDescription);
      ui.viewfinder.tabIndex = 0;
    } else {
      ui.viewfinder.removeAttribute("role");
      ui.viewfinder.removeAttribute("aria-label");
      ui.viewfinder.removeAttribute("tabindex");
    }
    const mediaTab = document.querySelector('.tab[data-view="media"]');
    mediaTab.hidden = !featureSupported(FEATURES.MEDIA_BROWSER);
  }

  function setOperationState(message, error = false) {
    ui.operationState.textContent = message;
    ui.operationState.classList.toggle("error-text", error);
  }

  function selectView(view) {
    if (view === "media" && !featureSupported(FEATURES.MEDIA_BROWSER)) return;
    state.activeView = view;
    document.querySelectorAll(".view-panel").forEach((panel) => {
      panel.hidden = panel.id !== `${view}-panel`;
    });
    document.querySelectorAll(".tab").forEach((button) => {
      const active = button.dataset.view === view;
      button.classList.toggle("active", active);
      button.setAttribute("aria-current", active ? "page" : "false");
    });
    if (view === "media" && !state.mediaLoaded) refreshMedia();
    if (view === "diagnostics") {
      refreshCapabilityEvidence().then(renderDiagnostics);
    }
  }

  async function refreshMedia() {
    if (!state.session || !featureSupported(FEATURES.MEDIA_BROWSER)) return;
    ui.mediaRefreshButton.disabled = true;
    try {
      const response = await api(`/v1/session/${encodeURIComponent(state.session.id)}/media`);
      clearMediaThumbnails();
      state.media = response.items || [];
      state.mediaLoaded = true;
      renderMedia();
    } catch (error) {
      const normalized = captureError(error);
      showToast(normalized.message, true);
    } finally {
      ui.mediaRefreshButton.disabled = false;
    }
  }

  function renderMedia() {
    if (!ui.mediaList) return;
    mediaThumbnailObserver?.disconnect();
    ui.mediaSummary.textContent = t("mediaCount", { count: state.media.length });
    ui.mediaList.replaceChildren();
    if (!state.media.length) {
      const empty = document.createElement("div");
      empty.className = "empty-state";
      empty.textContent = t("mediaEmpty");
      ui.mediaList.append(empty);
      return;
    }
    state.media.forEach((item) => {
      const row = document.createElement("div");
      row.className = "media-row";
      const thumbnail = document.createElement("span");
      thumbnail.className = "media-thumbnail";
      thumbnail.dataset.mediaId = item.id;
      renderMediaThumbnail(thumbnail, item, state.mediaThumbnailUrls.get(item.id));
      const copy = document.createElement("div");
      copy.className = "media-copy";
      const name = document.createElement("strong");
      name.textContent = item.name;
      const time = document.createElement("span");
      time.textContent = formatDate(item.captureTime) || item.contentType;
      copy.append(name, time);
      const size = document.createElement("span");
      size.className = "media-size";
      size.textContent = formatBytes(item.sizeBytes);
      const actions = document.createElement("div");
      actions.className = "media-actions";
      if (featureSupported(FEATURES.MEDIA_DELETE)) {
        const remove = document.createElement("button");
        remove.type = "button";
        remove.className = "icon-button danger";
        remove.dataset.tooltip = t("delete");
        remove.setAttribute("aria-label", `${t("delete")} ${item.name}`);
        const removeIcon = document.createElement("span");
        removeIcon.className = "icon";
        removeIcon.dataset.icon = "trash-2";
        remove.append(removeIcon);
        remove.addEventListener("click", () => deleteMedia(item, remove));
        actions.append(remove);
      }
      const download = document.createElement("button");
      download.type = "button";
      download.className = "icon-button";
      download.dataset.tooltip = t("download");
      download.setAttribute("aria-label", `${t("download")} ${item.name}`);
      const downloadIcon = document.createElement("span");
      downloadIcon.className = "icon";
      downloadIcon.dataset.icon = "download";
      download.append(downloadIcon);
      download.disabled = !featureSupported(FEATURES.MEDIA_DOWNLOAD);
      download.addEventListener("click", () => downloadMedia(item, download));
      actions.append(download);
      row.append(thumbnail, copy, size, actions);
      ui.mediaList.append(row);
      if (featureSupported(FEATURES.MEDIA_THUMBNAIL) && !state.mediaThumbnailUrls.has(item.id)) {
        observeMediaThumbnail(thumbnail);
      }
    });
    window.OpenEosIcons?.render(ui.mediaList);
  }

  function renderMediaThumbnail(container, item, url = null) {
    container.replaceChildren();
    container.classList.toggle("loading", state.mediaThumbnailLoads.has(item.id));
    if (url) {
      const image = document.createElement("img");
      image.src = url;
      image.alt = t("mediaThumbnail", { name: item.name });
      container.append(image);
      return;
    }
    const icon = document.createElement("span");
    icon.className = "icon";
    icon.dataset.icon = item.kind === "video" ? "video" : "images";
    container.append(icon);
    window.OpenEosIcons?.render(container);
  }

  async function loadMediaThumbnail(item) {
    if (
      !state.session ||
      !featureSupported(FEATURES.MEDIA_THUMBNAIL) ||
      state.mediaThumbnailUrls.has(item.id) ||
      state.mediaThumbnailLoads.has(item.id) ||
      state.mediaThumbnailFailures.has(item.id)
    ) return;
    const generation = state.mediaGeneration;
    state.mediaThumbnailLoads.add(item.id);
    updateVisibleMediaThumbnail(item);
    try {
      const blob = await api(
        `/v1/session/${encodeURIComponent(state.session.id)}/media/${encodeURIComponent(item.id)}/thumbnail`,
        { responseType: "blob" },
      );
      if (!blob.type.startsWith("image/") || blob.size <= 0 || blob.size > MAX_MEDIA_THUMBNAIL_BYTES) {
        throw new ApiError("Invalid media thumbnail", { code: "INVALID_MEDIA_THUMBNAIL" });
      }
      if (generation !== state.mediaGeneration || !state.media.some((candidate) => candidate.id === item.id)) return;
      const url = URL.createObjectURL(blob);
      const previous = state.mediaThumbnailUrls.get(item.id);
      if (previous) URL.revokeObjectURL(previous);
      state.mediaThumbnailUrls.set(item.id, url);
    } catch (_) {
      if (generation === state.mediaGeneration) state.mediaThumbnailFailures.add(item.id);
    } finally {
      if (generation === state.mediaGeneration) {
        state.mediaThumbnailLoads.delete(item.id);
        updateVisibleMediaThumbnail(item);
      }
    }
  }

  function observeMediaThumbnail(container) {
    if (!("IntersectionObserver" in window)) {
      const item = state.media.find((candidate) => candidate.id === container.dataset.mediaId);
      if (item) loadMediaThumbnail(item);
      return;
    }
    if (!mediaThumbnailObserver) {
      mediaThumbnailObserver = new IntersectionObserver(
        (entries, observer) => {
          entries.forEach((entry) => {
            if (!entry.isIntersecting) return;
            observer.unobserve(entry.target);
            const item = state.media.find((candidate) => candidate.id === entry.target.dataset.mediaId);
            if (item) loadMediaThumbnail(item);
          });
        },
        { rootMargin: "160px 0px" },
      );
    }
    mediaThumbnailObserver.observe(container);
  }

  function updateVisibleMediaThumbnail(item) {
    const container = Array.from(ui.mediaList.querySelectorAll(".media-thumbnail"))
      .find((candidate) => candidate.dataset.mediaId === item.id);
    if (container) renderMediaThumbnail(container, item, state.mediaThumbnailUrls.get(item.id));
  }

  function clearMediaThumbnails() {
    mediaThumbnailObserver?.disconnect();
    state.mediaThumbnailUrls.forEach((url) => URL.revokeObjectURL(url));
    state.mediaThumbnailUrls.clear();
    state.mediaThumbnailLoads.clear();
    state.mediaThumbnailFailures.clear();
    state.mediaGeneration += 1;
  }

  async function downloadMedia(item, button) {
    if (!state.session || !featureSupported(FEATURES.MEDIA_DOWNLOAD)) return;
    button.disabled = true;
    try {
      const blob = await api(
        `/v1/session/${encodeURIComponent(state.session.id)}/media/${encodeURIComponent(item.id)}`,
        { responseType: "blob" },
      );
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement("a");
      anchor.href = url;
      anchor.download = item.name;
      document.body.append(anchor);
      anchor.click();
      anchor.remove();
      window.setTimeout(() => URL.revokeObjectURL(url), 1000);
      showToast(t("downloaded", { name: item.name }));
    } catch (error) {
      const normalized = captureError(error);
      showToast(normalized.message, true);
    } finally {
      button.disabled = false;
    }
  }

  async function deleteMedia(item, button) {
    if (!state.session || !featureSupported(FEATURES.MEDIA_DELETE)) return;
    if (!window.confirm(t("deleteConfirm", { name: item.name }))) return;
    button.disabled = true;
    try {
      await api(
        `/v1/session/${encodeURIComponent(state.session.id)}/media/${encodeURIComponent(item.id)}`,
        { method: "DELETE" },
      );
      state.media = state.media.filter((candidate) => candidate.id !== item.id);
      const thumbnailUrl = state.mediaThumbnailUrls.get(item.id);
      if (thumbnailUrl) URL.revokeObjectURL(thumbnailUrl);
      state.mediaThumbnailUrls.delete(item.id);
      state.mediaThumbnailLoads.delete(item.id);
      state.mediaThumbnailFailures.delete(item.id);
      renderMedia();
      showToast(t("deleted", { name: item.name }));
    } catch (error) {
      const normalized = captureError(error);
      showToast(normalized.message, true);
      button.disabled = false;
    }
  }

  function diagnosticReport() {
    const report = {
      product: "Open EOS Control Desktop",
      reportSchema: 1,
      generatedAt: new Date().toISOString(),
      productVersion: state.health?.version || "unknown",
      bridge: state.health,
      camera: state.session?.camera || null,
      info: state.info,
      status: state.status,
      capabilities: state.capabilities,
      validation: diagnostics.featureSummary(state.capabilities),
      liveView: {
        active: state.liveActive,
        requestedSource: state.liveSource,
        activeSource: state.activeLiveSource,
        requestedFps: state.requestedFps,
        observedFps: Number(state.observedFps.toFixed(1)),
        frameBytes: state.frameBytes,
        contentType: state.frameContentType,
        lastFrameAt: state.lastFrameAt,
      },
      lastError: state.lastError,
    };
    return diagnostics.safeValue(report, {
      secrets: [state.token, ui.ccapiPasswordInput?.value, state.info?.serial],
    });
  }

  function renderDiagnostics() {
    if (!ui.diagnosticsOutput) return;
    ui.diagnosticsOutput.textContent = JSON.stringify(diagnosticReport(), null, 2);
  }

  async function copyDiagnostics() {
    await refreshCapabilityEvidence();
    renderDiagnostics();
    const report = ui.diagnosticsOutput.textContent;
    try {
      await navigator.clipboard.writeText(report);
      showToast(t("copied"));
    } catch (_) {
      try {
        const area = document.createElement("textarea");
        area.value = report;
        area.style.position = "fixed";
        area.style.opacity = "0";
        document.body.append(area);
        area.select();
        const copied = document.execCommand("copy");
        area.remove();
        if (!copied) throw new Error("copy failed");
        showToast(t("copied"));
      } catch (error) {
        captureError(error);
        showToast(t("copyFailed"), true);
      }
    }
  }

  function replaceButtonIcon(button, iconName) {
    const current = button.querySelector("svg.icon, span.icon");
    if (!current || current.dataset.renderedIcon === iconName) return;
    const placeholder = document.createElement("span");
    placeholder.className = current.getAttribute("class") || "icon";
    placeholder.dataset.icon = iconName;
    placeholder.dataset.renderedIcon = iconName;
    current.replaceWith(placeholder);
    window.OpenEosIcons?.render(button);
    const rendered = button.querySelector("svg.icon");
    if (rendered) rendered.dataset.renderedIcon = iconName;
  }

  function formatBytes(value) {
    const bytes = Number(value);
    if (!Number.isFinite(bytes) || bytes <= 0) return bytes === 0 ? "0 B" : "-";
    const units = ["B", "KB", "MB", "GB", "TB"];
    const index = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1);
    const amount = bytes / 1024 ** index;
    return `${amount >= 10 || index === 0 ? amount.toFixed(0) : amount.toFixed(1)} ${units[index]}`;
  }

  function formatDate(value) {
    if (!value) return "";
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) return value;
    return new Intl.DateTimeFormat(resolvedLanguage(), { dateStyle: "medium", timeStyle: "short" }).format(date);
  }

  function sleep(milliseconds) {
    return new Promise((resolve) => window.setTimeout(resolve, milliseconds));
  }

  function bindEvents() {
    document.querySelectorAll(".language-select").forEach((select) => {
      select.addEventListener("change", () => {
        state.language = select.value;
        writeLanguagePreference(state.language);
        clearTimeout(state.toastTimer);
        ui.toast.hidden = true;
        applyLanguage();
      });
    });
    ui.scanButton.addEventListener("click", scanCameras);
    ui.connectButton.addEventListener("click", connectCamera);
    ui.usbModeButton.addEventListener("click", () => selectConnectionMode("usb"));
    ui.ccapiModeButton.addEventListener("click", () => selectConnectionMode("ccapi"));
    ui.cameraSelect.addEventListener("change", renderAvailability);
    ui.ccapiUrlInput.addEventListener("input", renderAvailability);
    ui.tokenInput.addEventListener("keydown", (event) => {
      if (event.key === "Enter") {
        if (state.connectionMode === "ccapi") connectCamera();
        else scanCameras();
      }
    });
    [ui.ccapiUrlInput, ui.ccapiUsernameInput, ui.ccapiPasswordInput].forEach((input) => {
      input.addEventListener("keydown", (event) => {
        if (event.key === "Enter") connectCamera();
      });
    });
    ui.refreshButton.addEventListener("click", () => refreshSession());
    ui.disconnectButton.addEventListener("click", disconnectCamera);
    document.querySelectorAll(".tab").forEach((button) => {
      button.addEventListener("click", () => selectView(button.dataset.view));
    });
    ui.photoModeButton.addEventListener("click", () => selectCaptureMode("photo"));
    ui.videoModeButton.addEventListener("click", () => selectCaptureMode("video"));
    ui.shutterButton.addEventListener("click", operateShutter);
    ui.autofocusButton.addEventListener("click", autofocus);
    ui.halfPressButton.addEventListener("click", halfPressShutter);
    ui.liveToggleButton.addEventListener("click", toggleLiveView);
    ui.railLiveButton.addEventListener("click", toggleLiveView);
    ui.liveMagnificationButton.addEventListener("click", setLiveViewMagnification);
    ui.fpsSelect.addEventListener("change", changeFps);
    ui.liveSourceSelect.addEventListener("change", changeLiveSource);
    ui.tapActionSelect.addEventListener("change", () => {
      state.tapAction = ui.tapActionSelect.value;
      renderAvailability();
    });
    ui.focusNearButton.addEventListener("click", () => driveFocus("NEAR"));
    ui.focusFarButton.addEventListener("click", () => driveFocus("FAR"));
    ui.viewfinder.addEventListener("click", tapFocusFromPointer);
    ui.viewfinder.addEventListener("keydown", (event) => {
      if (!["Enter", " "].includes(event.key) || !ui.viewfinder.classList.contains("tap-focus-enabled")) return;
      event.preventDefault();
      const bounds = ui.viewfinder.getBoundingClientRect();
      tapFocus(focusPointFromClient(bounds.left + bounds.width / 2, bounds.top + bounds.height / 2));
    });
    document.querySelectorAll("#focus-step-control button").forEach((button) => {
      button.addEventListener("click", () => {
        state.focusStep = button.dataset.step;
        document.querySelectorAll("#focus-step-control button").forEach((candidate) => {
          candidate.classList.toggle("active", candidate === button);
          candidate.setAttribute("aria-pressed", String(candidate === button));
        });
      });
    });
    ui.mediaRefreshButton.addEventListener("click", refreshMedia);
    ui.diagnosticsRefreshButton.addEventListener("click", () => refreshSession({ quiet: true }));
    ui.copyDiagnosticsButton.addEventListener("click", copyDiagnostics);
    ui.settingDialogClose.addEventListener("click", () => ui.settingDialog.close());
    ui.settingDialog.addEventListener("click", (event) => {
      if (event.target === ui.settingDialog) ui.settingDialog.close();
    });
    window.addEventListener("beforeunload", () => {
      clearMediaThumbnails();
      if (!state.session) return;
      api(`/v1/session/${encodeURIComponent(state.session.id)}`, { method: "DELETE", keepalive: true }).catch(() => {});
    });
  }

  async function initialize() {
    window.OpenEosIcons?.render();
    ui.ccapiUrlInput.value = readCameraPreference(CCAPI_URL_KEY, "http://192.168.1.2:8080");
    ui.ccapiUsernameInput.value = readCameraPreference(CCAPI_USERNAME_KEY);
    bindEvents();
    applyLanguage();
    renderLiveState();
    renderAvailability();
    await refreshHealth();
    const engine = state.health?.engines?.libgphoto2;
    if (engine?.available && !state.health.authRequired) await scanCameras();
  }

  initialize();
})();
