(() => {
  "use strict";

  const diagnostics = globalThis.OpenEOSDiagnostics;
  if (!diagnostics) throw new Error("Open EOS diagnostics module is unavailable.");
  const monitoring = globalThis.OpenEOSMonitoring;
  if (!monitoring) throw new Error("Open EOS monitoring module is unavailable.");
  const lut = globalThis.OpenEOSLut;
  if (!lut) throw new Error("Open EOS LUT module is unavailable.");
  const localVideo = globalThis.OpenEOSLocalVideo;
  if (!localVideo) throw new Error("Open EOS local video module is unavailable.");
  const rtpAudio = globalThis.OpenEOSRtpAudio;
  if (!rtpAudio) throw new Error("Open EOS RTP audio module is unavailable.");
  const mediaTransfer = globalThis.OpenEOSMediaTransfer;
  if (!mediaTransfer) throw new Error("Open EOS media transfer module is unavailable.");

  const FEATURES = {
    RECORDABLE_STATUS: "RECORDABLE_STATUS",
    LENS_STATUS: "LENS_STATUS",
    TEMPERATURE_STATUS: "TEMPERATURE_STATUS",
    EVENT_POLLING: "EVENT_POLLING",
    LIVE_VIEW: "LIVE_VIEW",
    LIVE_VIEW_MAGNIFICATION: "LIVE_VIEW_MAGNIFICATION",
    STILL_CAPTURE: "STILL_CAPTURE",
    BULB_EXPOSURE: "BULB_EXPOSURE",
    AUTOFOCUS: "AUTOFOCUS",
    SHUTTER_HALF_PRESS: "SHUTTER_HALF_PRESS",
    MOVIE_MODE_CONTROL: "MOVIE_MODE_CONTROL",
    CARD_SELECTION_CONTROL: "CARD_SELECTION_CONTROL",
    SOUND_RECORDING_CONTROL: "SOUND_RECORDING_CONTROL",
    SOUND_RECORDING_LEVEL_CONTROL: "SOUND_RECORDING_LEVEL_CONTROL",
    FOCUS_BRACKETING_CONTROL: "FOCUS_BRACKETING_CONTROL",
    MOVIE_SETTINGS_CONTROL: "MOVIE_SETTINGS_CONTROL",
    VIDEO_RECORDING: "VIDEO_RECORDING",
    TAP_FOCUS: "TAP_FOCUS",
    CLICK_WHITE_BALANCE: "CLICK_WHITE_BALANCE",
    FOCUS_DRIVE: "FOCUS_DRIVE",
    MEDIA_BROWSER: "MEDIA_BROWSER",
    MEDIA_THUMBNAIL: "MEDIA_THUMBNAIL",
    MEDIA_PREVIEW: "MEDIA_PREVIEW",
    MEDIA_DOWNLOAD: "MEDIA_DOWNLOAD",
    MEDIA_DELETE: "MEDIA_DELETE",
    CAMERA_CLOCK_SYNC: "CAMERA_CLOCK_SYNC",
    SENSOR_CLEANING: "SENSOR_CLEANING",
    CAMERA_SLEEP: "CAMERA_SLEEP",
  };
  const CORE_SETTINGS = ["iso", "shutter", "aperture", "whitebalance"];
  const LANGUAGE_KEY = "open-eos-control-language";
  const CCAPI_URL_KEY = "open-eos-control-ccapi-url";
  const CCAPI_USERNAME_KEY = "open-eos-control-ccapi-username";
  const MAX_MEDIA_THUMBNAIL_BYTES = 8 * 1024 * 1024;
  const MAX_MEDIA_PREVIEW_BYTES = 32 * 1024 * 1024;
  const LOCAL_VIDEO_RENDER_INTERVAL_MILLIS = 100;

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
      previewInput: "Preview input",
      cameraLiveView: "Camera Live View",
      localVideoInput: "Local UVC / HDMI input",
      videoDevice: "Video device",
      systemDefault: "System default",
      videoInputNumber: "Video input {index}",
      liveViewSource: "Source",
      liveViewSourceAuto: "Auto",
      liveViewSourceRtp: "RTP H.264",
      liveViewSourceJpeg: "JPEG polling",
      liveViewSourceBridge: "Desktop preview",
      frameRate: "Frame rate",
      cameraAudioUnmute: "Unmute camera audio",
      cameraAudioMute: "Mute camera audio",
      cameraAudioUnavailable: "Camera RTP audio is unavailable",
      cameraAudioStarted: "Camera audio enabled",
      cameraAudioStopped: "Camera audio muted",
      monitoringAssists: "Monitoring assists",
      histogram: "Histogram",
      lumaWaveform: "Luma waveform",
      zebra: "Zebra",
      off: "Off",
      falseColor: "False color",
      focusPeaking: "Focus peaking",
      frameGuide: "Frame guide",
      safeArea: "Action and title safe areas",
      anamorphicDesqueeze: "Anamorphic desqueeze",
      moreSettings: "More settings",
      syncCameraClock: "Camera date and time",
      syncCameraClockHint: "Set the camera clock and time zone from this computer, then verify the camera readback.",
      cameraClockSyncedAt: "Verified at {time}",
      syncNow: "Sync now",
      cameraClockSynced: "Camera date and time verified",
      sensorCleaning: "Clean camera sensor",
      sensorCleaningHint: "Runs the camera's built-in sensor-cleaning cycle. Live View pauses while cleaning is in progress.",
      sensorCleaningNow: "Clean now",
      sensorCleaningPowerOff: "Clean and turn off",
      sensorCleaningConfirm: "Run the camera's built-in sensor-cleaning cycle now? Shooting and recording are unavailable during cleaning.",
      sensorCleaningPowerOffConfirm: "Clean the sensor and turn off the camera afterward? Live View and this connection will end.",
      sensorCleaningComplete: "Sensor cleaning completed",
      cameraSleep: "Put camera to sleep",
      cameraSleepHint: "Ends CCAPI and immediately puts the camera into power-saving sleep.",
      cameraSleepNow: "Sleep now",
      cameraSleepConfirm: "Put the camera to sleep now? Live View and this connection will end. Wake the camera physically before reconnecting.",
      cameraSleepComplete: "Camera entered sleep mode",
      diagnosticSafe: "Authentication secrets and camera serial are excluded",
      physicalValidation: "Physical validation",
      physicalValidationHint: "Only camera-advertised features observed successfully in this connection can be confirmed. Check a feature after seeing the physical camera perform it.",
      physicalValidationNoObserved: "Use a supported control first. Eligible features appear after the operation succeeds.",
      physicalValidationSimulator: "Simulator sessions cannot produce physical-camera evidence.",
      physicalValidationDisconnected: "Connect a physical camera to begin validation.",
      physicalValidationCryptoUnavailable: "Open this app from localhost or HTTPS to create a SHA-256 validation record.",
      physicalValidationConfirmed: "Confirmed on camera",
      physicalValidationNotConfirmed: "Not yet confirmed on camera",
      copyPhysicalValidation: "Copy physical validation record",
      physicalValidationCopied: "Physical validation record copied",
      physicalValidationCopyFailed: "Could not copy the physical validation record",
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
      startBulb: "Start Bulb exposure",
      stopBulb: "Stop Bulb exposure",
      bulbStarted: "Bulb exposure started",
      bulbStopped: "Bulb exposure stopped",
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
      moviemode: "Movie mode",
      colortemperature: "Color temperature",
      whitebalanceadjusta: "White balance shift A",
      whitebalanceadjustb: "White balance shift B",
      "wbshift.ba": "WB shift B/A",
      "wbshift.mg": "WB shift M/G",
      colorspace: "Color space",
      aspectratio: "Aspect ratio",
      zoom: "Zoom",
      soundrecording: "Sound recording",
      soundrecordinglevel: "Sound recording level",
      windfilter: "Wind filter",
      attenuator: "Attenuator",
      focusbracketing: "Focus bracketing",
      focusbracketingnumberofshots: "Number of shots",
      focusbracketingfocusincrement: "Focus increment",
      focusbracketingexposuresmoothing: "Exposure smoothing",
      moviequality: "Movie quality",
      highframerate: "High frame rate",
      moviecropping: "Movie cropping",
      movieformat: "Movie recording format",
      movieQualityLight: "Light",
      movieQualityCrop: "Crop",
      zoomspeed: "Power zoom speed",
      autopoweroff: "Auto power off",
      beep: "Beep",
      displayoff: "Auto display off",
      capturetarget: "Capture target",
      capturestorage: "Recording card",
      cardselectionstillimage: "Still-image card",
      cardselectionmovie: "Movie card",
      highisonr: "High ISO noise reduction",
      alomode: "Auto Lighting Optimizer",
      continuousaf: "Continuous AF",
      movieservoaf: "Movie Servo AF",
      aeb: "Auto exposure bracketing",
      valueAuto: "Auto",
      valueManual: "Manual",
      valueEnable: "Enable",
      valueOn: "On",
      valueOff: "Off",
      valueLow: "Low",
      valueNormal: "Normal",
      valueHigh: "High",
      valueStandard: "Standard",
      valueStandardDisabledManual: "Standard (disabled in manual exposure)",
      valueLowDisabledManual: "Low (disabled in manual exposure)",
      valueHighDisabledManual: "High (disabled in manual exposure)",
      valueOffDisabledManual: "Off (disabled in manual exposure)",
      valueDisable: "Disable",
      valueDisableTouch: "Touch sounds off",
      valueInternalRam: "Computer",
      valueMemoryCard: "Memory card",
      valueCard1: "Card 1",
      valueCard2: "Card 2",
      duration15Seconds: "15 seconds",
      duration10Seconds: "10 seconds",
      duration20Seconds: "20 seconds",
      duration30Seconds: "30 seconds",
      duration1Minute: "1 minute",
      duration2Minutes: "2 minutes",
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
      previewMedia: "Preview {name}",
      closeMediaPreview: "Close media preview",
      loadingPreview: "Loading camera preview",
      previewUnavailable: "The camera preview could not be displayed.",
      download: "Download",
      preparingDownload: "Preparing camera download",
      downloading: "Downloading {name}",
      downloadProgress: "{transferred} of {total} ({percent}%)",
      downloadProgressUnknown: "{transferred} transferred",
      cancelDownload: "Cancel download",
      cancellingDownload: "Cancelling download",
      downloadCancelled: "Download cancelled",
      downloaded: "Downloaded {name}",
      delete: "Delete",
      deleteConfirm: "Permanently delete {name} from the camera card? This cannot be undone.",
      deleted: "Deleted {name}",
      copied: "Diagnostic report copied",
      copyFailed: "Could not copy the diagnostic report",
      operationFailed: "Operation failed",
      battery: "Battery",
      storage: "Storage",
      lens: "Lens",
      noLensMounted: "No lens mounted",
      cameraTemperatureWarning: "Camera temperature warning",
      temperatureFrameRateReduced: "Frame rate reduced by the camera",
      temperatureLiveViewUnavailable: "Live View unavailable due to camera temperature",
      temperatureShutterUnavailable: "Shutter unavailable due to camera temperature",
      temperatureMovieRestricted: "Movie recording restricted due to camera temperature",
      temperatureStillQualityWarning: "Still image quality may be reduced",
      freeImages: "{count} shots",
      notAvailable: "Not available",
      liveViewImage: "Camera Live View",
      localVideoImage: "Local video input",
      startLocalVideo: "Start video input",
      stopLocalVideo: "Stop video input",
      localVideoStarted: "Local video input started",
      localVideoStopped: "Local video input stopped",
      localVideoEnded: "The local video input was disconnected",
      localVideoInsecure: "Local video requires HTTPS or a loopback address such as localhost.",
      localVideoUnavailable: "This browser does not expose local video devices.",
      localVideoPermissionDenied: "Camera permission was not granted for the local video input.",
      localVideoNotFound: "The selected local video input is unavailable.",
      localVideoNotReadable: "The local video input is already in use or could not be opened.",
      localVideoConstraints: "The local video input could not satisfy the requested video format.",
      localVideoSecurity: "The browser blocked access to the local video input.",
      localVideoPlaybackBlocked: "The browser could not start local video playback.",
      localVideoAborted: "Opening the local video input was interrupted.",
      localVideoError: "The local video input could not be started.",
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
      previewInput: "監看輸入",
      cameraLiveView: "相機即時預覽",
      localVideoInput: "本機 UVC／HDMI 輸入",
      videoDevice: "視訊裝置",
      systemDefault: "系統預設",
      videoInputNumber: "視訊輸入 {index}",
      liveViewSource: "畫面來源",
      liveViewSourceAuto: "自動",
      liveViewSourceRtp: "RTP H.264",
      liveViewSourceJpeg: "JPEG 輪詢",
      liveViewSourceBridge: "電腦預覽",
      frameRate: "影格率",
      cameraAudioUnmute: "開啟相機音訊",
      cameraAudioMute: "將相機音訊靜音",
      cameraAudioUnavailable: "相機 RTP 音訊無法使用",
      cameraAudioStarted: "已開啟相機音訊",
      cameraAudioStopped: "相機音訊已靜音",
      monitoringAssists: "監看輔助",
      histogram: "直方圖",
      lumaWaveform: "亮度波形圖",
      zebra: "斑馬紋",
      off: "關閉",
      falseColor: "偽色",
      focusPeaking: "峰值對焦",
      frameGuide: "畫幅框線",
      safeArea: "動作與標題安全區域",
      anamorphicDesqueeze: "變形鏡頭反擠壓",
      moreSettings: "更多設定",
      syncCameraClock: "相機日期與時間",
      syncCameraClockHint: "使用此電腦的時間與時區設定相機，並回讀確認結果。",
      cameraClockSyncedAt: "已於 {time} 驗證",
      syncNow: "立即同步",
      cameraClockSynced: "已驗證相機日期與時間",
      sensorCleaning: "清潔相機感光元件",
      sensorCleaningHint: "執行相機內建的感光元件清潔程序；清潔期間會暫停 Live View。",
      sensorCleaningNow: "立即清潔",
      sensorCleaningPowerOff: "清潔後關機",
      sensorCleaningConfirm: "要立即執行相機內建的感光元件清潔程序嗎？清潔期間無法拍照或錄影。",
      sensorCleaningPowerOffConfirm: "要清潔感光元件並在完成後關閉相機嗎？Live View 與目前連線將會結束。",
      sensorCleaningComplete: "感光元件清潔已完成",
      cameraSleep: "讓相機進入休眠",
      cameraSleepHint: "結束 CCAPI 連線並立即讓相機進入省電休眠。",
      cameraSleepNow: "立即休眠",
      cameraSleepConfirm: "要立即讓相機進入休眠嗎？Live View 與目前連線將會結束；再次連線前，請先在相機上將其喚醒。",
      cameraSleepComplete: "相機已進入休眠",
      diagnosticSafe: "診斷內容不包含驗證機密與相機序號",
      physicalValidation: "真機驗證",
      physicalValidationHint: "僅能確認相機已公告，且在本次連線中成功觀測的功能。請在看見實體相機確實執行後再勾選。",
      physicalValidationNoObserved: "請先操作一項支援功能；成功執行後，該功能會出現在此處。",
      physicalValidationSimulator: "模擬器工作階段不能產生真機證據。",
      physicalValidationDisconnected: "請先連接實體相機再開始驗證。",
      physicalValidationCryptoUnavailable: "請從 localhost 或 HTTPS 開啟此應用程式，才能建立 SHA-256 驗證紀錄。",
      physicalValidationConfirmed: "已在相機上確認",
      physicalValidationNotConfirmed: "尚未在相機上確認",
      copyPhysicalValidation: "複製真機驗證紀錄",
      physicalValidationCopied: "已複製真機驗證紀錄",
      physicalValidationCopyFailed: "無法複製真機驗證紀錄",
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
      startBulb: "開始 Bulb 長曝光",
      stopBulb: "停止 Bulb 長曝光",
      bulbStarted: "Bulb 長曝光已開始",
      bulbStopped: "Bulb 長曝光已停止",
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
      moviemode: "影片模式",
      colortemperature: "色溫",
      whitebalanceadjusta: "白平衡偏移 A",
      whitebalanceadjustb: "白平衡偏移 B",
      "wbshift.ba": "白平衡偏移 B/A",
      "wbshift.mg": "白平衡偏移 M/G",
      colorspace: "色彩空間",
      aspectratio: "畫面比例",
      zoom: "變焦",
      soundrecording: "錄音模式",
      soundrecordinglevel: "錄音音量",
      windfilter: "風聲抑制",
      attenuator: "衰減器",
      focusbracketing: "對焦包圍拍攝",
      focusbracketingnumberofshots: "拍攝張數",
      focusbracketingfocusincrement: "對焦增量",
      focusbracketingexposuresmoothing: "曝光平滑",
      moviequality: "短片畫質",
      highframerate: "高格率",
      moviecropping: "短片裁切",
      movieformat: "短片錄影格式",
      movieQualityLight: "輕量",
      movieQualityCrop: "裁切",
      zoomspeed: "電動變焦速度",
      autopoweroff: "自動關閉電源",
      beep: "提示音",
      displayoff: "螢幕自動關閉",
      capturetarget: "拍攝儲存位置",
      capturestorage: "拍攝記錄卡",
      cardselectionstillimage: "相片記錄卡",
      cardselectionmovie: "影片記錄卡",
      highisonr: "高 ISO 降噪",
      continuousaf: "連續自動對焦",
      movieservoaf: "短片伺服自動對焦",
      aeb: "自動包圍曝光",
      valueAuto: "自動",
      valueManual: "手動",
      valueEnable: "啟用",
      valueOn: "開啟",
      valueOff: "關閉",
      valueLow: "低",
      valueNormal: "標準",
      valueHigh: "高",
      valueDisable: "停用",
      valueDisableTouch: "關閉觸控提示音",
      valueInternalRam: "電腦",
      valueMemoryCard: "記憶卡",
      valueCard1: "記憶卡 1",
      valueCard2: "記憶卡 2",
      duration15Seconds: "15 秒",
      duration10Seconds: "10 秒",
      duration20Seconds: "20 秒",
      duration30Seconds: "30 秒",
      duration1Minute: "1 分鐘",
      duration2Minutes: "2 分鐘",
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
      previewMedia: "預覽 {name}",
      closeMediaPreview: "關閉媒體預覽",
      loadingPreview: "正在載入相機預覽",
      previewUnavailable: "無法顯示相機提供的預覽影像。",
      download: "下載",
      preparingDownload: "正在準備相機檔案下載",
      downloading: "正在下載 {name}",
      downloadProgress: "已傳輸 {transferred}／{total}（{percent}%）",
      downloadProgressUnknown: "已傳輸 {transferred}",
      cancelDownload: "取消下載",
      cancellingDownload: "正在取消下載",
      downloadCancelled: "已取消下載",
      downloaded: "已下載 {name}",
      delete: "刪除",
      deleteConfirm: "確定要從相機儲存卡永久刪除「{name}」嗎？此操作無法復原。",
      deleted: "已刪除 {name}",
      copied: "已複製診斷報告",
      copyFailed: "無法複製診斷報告",
      operationFailed: "操作失敗",
      battery: "電池",
      storage: "儲存空間",
      lens: "鏡頭",
      noLensMounted: "未安裝鏡頭",
      cameraTemperatureWarning: "相機溫度警告",
      temperatureFrameRateReduced: "相機已降低畫面更新率",
      temperatureLiveViewUnavailable: "相機過熱，即時預覽暫時無法使用",
      temperatureShutterUnavailable: "相機過熱，快門暫時無法使用",
      temperatureMovieRestricted: "相機過熱，短片錄影受限",
      temperatureStillQualityWarning: "靜態影像畫質可能降低",
      freeImages: "可拍 {count} 張",
      notAvailable: "無資料",
      liveViewImage: "相機即時預覽",
      localVideoImage: "本機視訊輸入",
      startLocalVideo: "啟動視訊輸入",
      stopLocalVideo: "停止視訊輸入",
      localVideoStarted: "已啟動本機視訊輸入",
      localVideoStopped: "已停止本機視訊輸入",
      localVideoEnded: "本機視訊輸入已中斷連線",
      localVideoInsecure: "本機視訊需要 HTTPS 或 localhost 等回送位址。",
      localVideoUnavailable: "此瀏覽器未提供本機視訊裝置介面。",
      localVideoPermissionDenied: "未授權使用本機視訊輸入。",
      localVideoNotFound: "找不到選取的本機視訊輸入。",
      localVideoNotReadable: "本機視訊輸入正在使用中或無法開啟。",
      localVideoConstraints: "本機視訊輸入無法符合要求的視訊格式。",
      localVideoSecurity: "瀏覽器已封鎖本機視訊輸入。",
      localVideoPlaybackBlocked: "瀏覽器無法開始播放本機視訊。",
      localVideoAborted: "開啟本機視訊輸入時遭到中斷。",
      localVideoError: "無法啟動本機視訊輸入。",
      authRequired: "此 Bridge 需要 Bearer token",
    },
  };

  Object.assign(messages.en, {
    recordingTimeRemaining: "{time} remaining",
    lutPreview: "3D LUT preview",
    loadCubeLut: "Load .cube LUT",
    removeLut: "Remove LUT",
    noLutLoaded: "No LUT loaded",
    lutSummary: "{name} · {size}³",
    lutLoaded: "3D LUT loaded",
    lutRemoved: "3D LUT removed",
    lutLoadFailed: "Could not load 3D LUT",
  });
  Object.assign(messages["zh-TW"], {
    recordingTimeRemaining: "剩餘 {time}",
    lutPreview: "3D LUT 預覽",
    loadCubeLut: "載入 .cube LUT",
    removeLut: "移除 LUT",
    noLutLoaded: "尚未載入 LUT",
    lutSummary: "{name} · {size}³",
    lutLoaded: "已載入 3D LUT",
    lutRemoved: "已移除 3D LUT",
    lutLoadFailed: "無法載入 3D LUT",
    alomode: "自動亮度優化",
    valueStandard: "標準",
    valueStandardDisabledManual: "標準（手動曝光時停用）",
    valueLowDisabledManual: "低（手動曝光時停用）",
    valueHighDisabledManual: "高（手動曝光時停用）",
    valueOffDisabledManual: "關閉（手動曝光時停用）",
  });

  const commonSettingValueKeys = {
    auto: "valueAuto",
    manual: "valueManual",
    enable: "valueEnable",
    disable: "valueDisable",
    on: "valueOn",
    off: "valueOff",
    low: "valueLow",
    normal: "valueNormal",
    high: "valueHigh",
    standard: "valueStandard",
  };
  const autoLightingOptimizerValueKeys = {
    "standard (disabled in manual exposure)": "valueStandardDisabledManual",
    "low (disabled in manual exposure)": "valueLowDisabledManual",
    "high (disabled in manual exposure)": "valueHighDisabledManual",
    "off (disabled in manual exposure)": "valueOffDisabledManual",
  };
  const autoPowerOffValueKeys = {
    0: "valueDisable",
    15: "duration15Seconds",
    30: "duration30Seconds",
    60: "duration1Minute",
    120: "duration2Minutes",
    180: "duration3Minutes",
    300: "duration5Minutes",
    600: "duration10Minutes",
    1800: "duration30Minutes",
  };
  const displayOffValueKeys = {
    10: "duration10Seconds",
    20: "duration20Seconds",
    30: "duration30Seconds",
    60: "duration1Minute",
    120: "duration2Minutes",
    180: "duration3Minutes",
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
  const captureStorageValueKeys = {
    "card 1": "valueCard1",
    "card 2": "valueCard2",
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
    operatorConfirmedFeatures: new Set(),
    lastClockSyncAt: null,
    activeView: "live",
    captureMode: "photo",
    liveActive: false,
    previewInput: "CAMERA",
    localVideoSupport: localVideo.supportState({
      secureContext: Boolean(globalThis.isSecureContext),
      mediaDevices: navigator.mediaDevices,
    }),
    localVideoInputs: [],
    localVideoDeviceId: "",
    localVideoStream: null,
    localVideoTrack: null,
    localVideoSettings: null,
    localVideoActive: false,
    localVideoBusy: false,
    localVideoGeneration: 0,
    localVideoFrameHandle: null,
    localVideoFrameTimes: [],
    localVideoFrameSamples: [],
    localVideoLastRenderAt: 0,
    localVideoError: null,
    localVideoMuted: false,
    liveMagnification: 1,
    liveGeneration: 0,
    liveFrameAbortController: null,
    eventGeneration: 0,
    eventController: null,
    requestedFps: 1,
    liveSource: "AUTO",
    activeLiveSource: null,
    frameTimes: [],
    observedFps: 0,
    frameBytes: 0,
    frameContentType: null,
    lastFrameAt: null,
    liveObjectUrl: null,
    livePollingSuspended: false,
    rtpAudioEnabled: false,
    rtpAudioBusy: false,
    rtpAudioLoopGeneration: 0,
    rtpAudioAfterGeneration: 0,
    rtpAudioContext: null,
    rtpAudioAbortController: null,
    rtpAudioSources: new Set(),
    rtpAudioNextStart: Number.NaN,
    rtpAudioBytes: 0,
    rtpAudioSampleRate: null,
    rtpAudioChannels: null,
    rtpAudioLastAt: null,
    rtpAudioError: null,
    monitorAnalysisError: null,
    monitorSettings: {
      histogramVisible: false,
      waveformVisible: false,
      zebraThresholdPercent: null,
      falseColorEnabled: false,
      focusPeakingEnabled: false,
      frameGuide: "",
      safeAreaVisible: false,
      desqueeze: 1,
      cubeLut: null,
    },
    bulbStartedAt: null,
    bulbTimer: null,
    focusStep: "MEDIUM",
    tapAction: "focus",
    media: [],
    mediaLoaded: false,
    mediaRefreshPromise: null,
    mediaThumbnailUrls: new Map(),
    mediaThumbnailLoads: new Set(),
    mediaThumbnailFailures: new Set(),
    mediaGeneration: 0,
    mediaPreviewUrl: null,
    mediaPreviewItem: null,
    mediaPreviewGeneration: 0,
    mediaDownloadPreparing: false,
    mediaDownload: null,
    busy: false,
    refreshGeneration: 0,
    lastError: null,
    toastTimer: null,
  };
  let mediaThumbnailObserver = null;
  let mediaTransferRenderTimer = null;

  function releaseObjectUrl(url) {
    if (!url) return;
    window.setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

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
    localVideo: byId("local-video"),
    monitorPixelOverlay: byId("monitor-pixel-overlay"),
    monitorLutPreview: byId("monitor-lut-preview"),
    monitorGuidesOverlay: byId("monitor-guides-overlay"),
    monitorHistogram: byId("monitor-histogram"),
    monitorWaveform: byId("monitor-waveform"),
    viewfinderPlaceholder: byId("viewfinder-placeholder"),
    temperatureWarning: byId("temperature-warning"),
    temperatureWarningText: byId("temperature-warning-text"),
    liveToggleButton: byId("live-toggle-button"),
    railLiveButton: byId("rail-live-button"),
    modeIndicator: byId("mode-indicator"),
    previewSourceIndicator: byId("preview-source-indicator"),
    frameIndicator: byId("frame-indicator"),
    recordIndicator: byId("record-indicator"),
    bulbIndicator: byId("bulb-indicator"),
    liveMagnificationButton: byId("live-magnification-button"),
    liveMagnificationLabel: byId("live-magnification-label"),
    rtpAudioButton: byId("rtp-audio-button"),
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
    fpsRow: byId("fps-row"),
    previewInputSelect: byId("preview-input-select"),
    localVideoDeviceRow: byId("local-video-device-row"),
    localVideoDeviceSelect: byId("local-video-device-select"),
    localVideoSupport: byId("local-video-support"),
    liveSourceRow: byId("live-source-row"),
    liveSourceSelect: byId("live-source-select"),
    tapActionRow: byId("tap-action-row"),
    tapActionSelect: byId("tap-action-select"),
    monitoringButton: byId("monitoring-button"),
    monitoringDialog: byId("monitoring-dialog"),
    monitoringDialogClose: byId("monitoring-dialog-close"),
    monitorHistogramToggle: byId("monitor-histogram-toggle"),
    monitorWaveformToggle: byId("monitor-waveform-toggle"),
    monitorLutSummary: byId("monitor-lut-summary"),
    monitorLutFile: byId("monitor-lut-file"),
    monitorLutLoad: byId("monitor-lut-load"),
    monitorLutRemove: byId("monitor-lut-remove"),
    monitorZebraSelect: byId("monitor-zebra-select"),
    monitorFalseColorToggle: byId("monitor-false-color-toggle"),
    monitorFocusPeakingToggle: byId("monitor-focus-peaking-toggle"),
    monitorFrameGuideSelect: byId("monitor-frame-guide-select"),
    monitorSafeAreaToggle: byId("monitor-safe-area-toggle"),
    monitorDesqueezeSelect: byId("monitor-desqueeze-select"),
    advancedSettings: byId("advanced-settings"),
    mediaRefreshButton: byId("media-refresh-button"),
    mediaSummary: byId("media-summary"),
    mediaList: byId("media-list"),
    mediaTransfer: byId("media-transfer"),
    mediaTransferName: byId("media-transfer-name"),
    mediaTransferStatus: byId("media-transfer-status"),
    mediaTransferProgress: byId("media-transfer-progress"),
    mediaTransferCancel: byId("media-transfer-cancel"),
    mediaPreviewDialog: byId("media-preview-dialog"),
    mediaPreviewClose: byId("media-preview-close"),
    mediaPreviewTitle: byId("media-preview-title"),
    mediaPreviewKind: byId("media-preview-kind"),
    mediaPreviewImage: byId("media-preview-image"),
    mediaPreviewLoading: byId("media-preview-loading"),
    mediaPreviewUnavailable: byId("media-preview-unavailable"),
    diagnosticsRefreshButton: byId("diagnostics-refresh-button"),
    copyDiagnosticsButton: byId("copy-diagnostics-button"),
    diagnosticsOutput: byId("diagnostics-output"),
    physicalValidationStatus: byId("physical-validation-status"),
    physicalValidationList: byId("physical-validation-list"),
    copyPhysicalValidationButton: byId("copy-physical-validation-button"),
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
    ui.localVideo.setAttribute("aria-label", t("localVideoImage"));
    document.querySelector(".camera-metrics > span:first-child")?.setAttribute("title", t("battery"));
    document.querySelector(".camera-metrics > span:last-child")?.setAttribute("title", t("storage"));
    renderConnectionMode();
    renderHealth();
    renderCameras();
    renderSession();
    renderPreviewInput();
    renderLocalVideoDevices();
    renderLiveState();
    renderMedia();
    renderMediaTransfer();
    renderDiagnostics();
  }

  async function api(
    path,
    { method = "GET", json, responseType = "json", keepalive = false, signal = null } = {},
  ) {
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
        signal,
      });
    } catch (error) {
      if (mediaTransfer.isAbortError(error)) throw error;
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
    if (responseType === "response") return response;
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
    state.operatorConfirmedFeatures.clear();
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
      state.captureMode = captureModeFromCamera() || (state.status.recording ? "video" : "photo");
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
      startEventLoop();
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
    if (!state.session) return false;
    if (!quiet) setOperationState(t("refreshing"));
    const rawSessionId = state.session.id;
    const sessionId = encodeURIComponent(rawSessionId);
    const refreshGeneration = state.refreshGeneration;
    try {
      const [info, status, capabilities] = await Promise.all([
        api(`/v1/session/${sessionId}/info`),
        api(`/v1/session/${sessionId}/status`),
        api(`/v1/session/${sessionId}/capabilities`),
      ]);
      if (
        state.session?.id !== rawSessionId ||
        state.refreshGeneration !== refreshGeneration
      ) return false;
      [state.info, state.status, state.capabilities] = [info, status, capabilities];
      state.requestedFps = clampFps(state.requestedFps);
      state.lastError = null;
      renderSession();
      if (!quiet) setOperationState(t("ready"));
      return true;
    } catch (error) {
      if (
        state.session?.id !== rawSessionId ||
        state.refreshGeneration !== refreshGeneration
      ) return false;
      const normalized = captureError(error);
      if (!quiet) {
        setOperationState(normalized.message, true);
        showToast(normalized.message, true);
      }
      return false;
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
    beginCameraInteraction();
    cancelMediaDownload({ silent: true });
    renderAvailability();
    stopLiveLoop();
    stopLocalVideo({ announce: false });
    await stopEventLoop(sessionId);
    try {
      await api(`/v1/session/${encodeURIComponent(sessionId)}`, { method: "DELETE" });
    } catch (error) {
      captureError(error);
    }
    resetSession();
    showToast(t("disconnected"));
  }

  function resetSession() {
    state.refreshGeneration += 1;
    stopLiveLoop();
    stopLocalVideo({ announce: false });
    cancelEventLoop();
    cancelMediaDownload({ silent: true });
    clearScheduledMediaTransferRender();
    clearMediaThumbnails();
    closeMediaPreview();
    state.session = null;
    state.info = null;
    state.status = null;
    state.capabilities = null;
    state.operatorConfirmedFeatures.clear();
    state.lastClockSyncAt = null;
    state.media = [];
    state.mediaLoaded = false;
    state.mediaDownloadPreparing = false;
    state.mediaDownload = null;
    state.captureMode = "photo";
    state.previewInput = "CAMERA";
    state.liveSource = "AUTO";
    state.activeLiveSource = null;
    state.liveMagnification = 1;
    clearBulbTimer();
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

  function startEventLoop() {
    cancelEventLoop();
    if (!state.session || !featureSupported(FEATURES.EVENT_POLLING)) return;
    const generation = state.eventGeneration;
    const sessionId = encodeURIComponent(state.session.id);
    void (async () => {
      let failures = 0;
      while (
        state.session &&
        generation === state.eventGeneration &&
        featureSupported(FEATURES.EVENT_POLLING)
      ) {
        const controller = new AbortController();
        state.eventController = controller;
        try {
          const event = await api(`/v1/session/${sessionId}/events`, { signal: controller.signal });
          failures = 0;
          if (event?.changedKeys?.length && generation === state.eventGeneration) {
            const contentsChanged = event.changedKeys.some((key) =>
              String(key).toLowerCase().includes("content"),
            );
            while (
              cameraInteractionBusy() &&
              state.session &&
              generation === state.eventGeneration
            ) await sleep(25);
            if (!state.session || generation !== state.eventGeneration) break;
            const refreshed = await refreshSession({ quiet: true });
            if (
              refreshed && contentsChanged && state.mediaLoaded &&
              generation === state.eventGeneration
            ) {
              await refreshMedia();
            }
          }
        } catch (error) {
          if (mediaTransfer.isAbortError(error) || generation !== state.eventGeneration) break;
          captureError(error);
          failures += 1;
          await sleep([1000, 2000, 5000][Math.min(failures - 1, 2)]);
        } finally {
          if (state.eventController === controller) state.eventController = null;
        }
      }
    })();
  }

  function cancelEventLoop() {
    state.eventGeneration += 1;
    state.eventController?.abort();
    state.eventController = null;
  }

  async function stopEventLoop(sessionId) {
    const supported = featureSupported(FEATURES.EVENT_POLLING);
    cancelEventLoop();
    if (!supported) return;
    try {
      await api(`/v1/session/${encodeURIComponent(sessionId)}/events`, { method: "DELETE" });
    } catch (error) {
      captureError(error);
    }
  }

  function mediaTransferActive() {
    return state.mediaDownloadPreparing || Boolean(state.mediaDownload);
  }

  function cameraInteractionBusy() {
    return state.busy || mediaTransferActive();
  }

  function beginCameraInteraction() {
    state.refreshGeneration += 1;
    state.busy = true;
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
    if (key === "moviequality") return movieQualityDisplayValue(rawValue) || rawValue;
    if (key === "zoom") return `${rawValue}%`;
    let messageKey = commonSettingValueKeys[rawValue.toLowerCase()];
    if (key === "alomode") {
      messageKey = autoLightingOptimizerValueKeys[rawValue.toLowerCase()] || messageKey;
    }
    if (key === "autopoweroff") messageKey = autoPowerOffValueKeys[rawValue] || messageKey;
    if (key === "beep" && rawValue.toLowerCase() === "disabletouch") messageKey = "valueDisableTouch";
    if (key === "displayoff") messageKey = displayOffValueKeys[rawValue];
    if (key === "capturetarget") messageKey = captureTargetValueKeys[rawValue.toLowerCase()];
    if (key === "capturestorage") messageKey = captureStorageValueKeys[rawValue.toLowerCase()];
    if (["cardselectionstillimage", "cardselectionmovie"].includes(key)) {
      messageKey = { none: "imageQualityNone", card1: "valueCard1", card2: "valueCard2" }[rawValue.toLowerCase()];
    }
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

  function movieQualityDisplayValue(rawValue) {
    const parts = String(rawValue).split("_");
    if (parts.length < 4 || parts.length > 5 || parts.some((part) => !part)) return null;
    const size = { "4k": "4K", fhd: "FHD", hd: "HD" }[parts[0].toLowerCase()] || parts[0];
    if (!/^\d{4,5}$/.test(parts[1])) return null;
    const frameRateHundredths = Number(parts[1]);
    const frameRate = `${Math.floor(frameRateHundredths / 100)}.${String(frameRateHundredths % 100).padStart(2, "0")}p`;
    const compression = { raw: "RAW", alli: "ALL-I", ipb: "IPB" }[parts[2].toLowerCase()];
    if (!frameRate || !compression) return null;
    const labels = [size, frameRate, compression];
    if (parts[3].toLowerCase() === "light") labels.push(t("movieQualityLight"));
    else if (parts[3].toLowerCase() !== "standard") return null;
    if (parts.length === 5) {
      if (parts[4].toLowerCase() !== "crop") return null;
      labels.push(t("movieQualityCrop"));
    }
    return labels.join(" / ");
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

  function isBulbMode() {
    const setting = settingByKey("shootingmode") || settingByKey("autoexposuremode");
    const value = setting?.value || state.status?.mode || "";
    return String(value).trim().toLowerCase().replace(/[^a-z0-9]/g, "") === "bulb";
  }

  function captureModeFromCamera() {
    const value = settingByKey("moviemode")?.value;
    if (value === "on") return "video";
    if (value === "off") return "photo";
    return null;
  }

  function clearBulbTimer() {
    if (state.bulbTimer != null) window.clearInterval(state.bulbTimer);
    state.bulbTimer = null;
    state.bulbStartedAt = null;
    if (ui.bulbIndicator) ui.bulbIndicator.hidden = true;
  }

  function formatBulbElapsed(milliseconds) {
    const totalSeconds = Math.max(0, Math.floor(milliseconds / 1000));
    const hours = Math.floor(totalSeconds / 3600);
    const minutes = Math.floor((totalSeconds % 3600) / 60);
    const seconds = totalSeconds % 60;
    const clock = [minutes, seconds].map((value) => String(value).padStart(2, "0")).join(":");
    return hours > 0 ? `${hours}:${clock}` : clock;
  }

  function renderBulbIndicator() {
    const active = Boolean(state.status?.bulbExposureActive);
    ui.bulbIndicator.hidden = !active;
    if (active) {
      const startedAt = state.bulbStartedAt || Date.now();
      ui.bulbIndicator.textContent = `BULB ${formatBulbElapsed(Date.now() - startedAt)}`;
    }
  }

  function syncBulbTimer() {
    const active = Boolean(state.status?.bulbExposureActive);
    if (!active) {
      clearBulbTimer();
      return;
    }
    if (state.bulbStartedAt == null) state.bulbStartedAt = Date.now();
    if (state.bulbTimer == null) {
      state.bulbTimer = window.setInterval(renderBulbIndicator, 250);
    }
    renderBulbIndicator();
  }

  function renderSession() {
    if (!state.session || !state.capabilities || !state.status) return;
    renderCaptureMode();
    ui.cameraName.textContent = state.info?.model || state.session.camera?.model || "Canon EOS";
    const battery = state.status.battery || {};
    ui.batteryValue.textContent = battery.level == null ? (battery.status || "-") : `${battery.level}%`;
    const storage = state.status.media || {};
    if (state.captureMode === "video" && state.status.remainingRecordingSeconds != null) {
      ui.storageValue.textContent = t("recordingTimeRemaining", {
        time: formatBulbElapsed(Number(state.status.remainingRecordingSeconds) * 1000),
      });
    } else {
      const recordableShots = state.status.recordableShots ?? storage.freeImages;
      ui.storageValue.textContent = recordableShots != null
        ? t("freeImages", { count: recordableShots })
        : storage.freeBytes != null
          ? formatBytes(storage.freeBytes)
          : "-";
    }
    ui.modeIndicator.textContent = state.status.mode && state.status.mode !== "unknown" ? state.status.mode : "-";
    renderExposure();
    renderAdvancedSettings();
    renderPreviewInput();
    renderLiveSource();
    renderFps();
    renderTapAction();
    renderTemperatureStatus();
    syncBulbTimer();
    renderAvailability();
    renderDiagnostics();
  }

  function temperatureAllows(kind) {
    const status = String(state.status?.temperature || "");
    if (!status) return true;
    if (kind === "liveview") return !status.includes("disableliveview");
    if (kind === "release") return !status.includes("disablerelease");
    if (kind === "movie") return !status.includes("restrictionmovierecording");
    return true;
  }

  function renderTemperatureStatus() {
    const status = String(state.status?.temperature || "");
    if (!status || status === "normal") {
      ui.temperatureWarning.hidden = true;
      ui.temperatureWarningText.textContent = "";
      return;
    }
    const messages = [];
    if (status === "warning" || status.startsWith("warning_and_")) messages.push(t("cameraTemperatureWarning"));
    if (status.startsWith("frameratedown")) messages.push(t("temperatureFrameRateReduced"));
    if (!temperatureAllows("liveview")) messages.push(t("temperatureLiveViewUnavailable"));
    if (!temperatureAllows("release")) messages.push(t("temperatureShutterUnavailable"));
    if (!temperatureAllows("movie")) messages.push(t("temperatureMovieRestricted"));
    if (status.startsWith("stillqualitywarning")) messages.push(t("temperatureStillQualityWarning"));
    ui.temperatureWarningText.textContent = (messages.length ? messages : [t("cameraTemperatureWarning")]).join(" · ");
    ui.temperatureWarning.hidden = false;
  }

  function renderExposure() {
    ui.exposureStrip.replaceChildren();
    CORE_SETTINGS.forEach((key) => {
      const setting = settingByKey(key);
      const button = document.createElement("button");
      button.type = "button";
      button.className = "exposure-control";
      button.dataset.settingKey = key;
      button.disabled = !setting || cameraInteractionBusy();
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
    const clockSupported = featureSupported(FEATURES.CAMERA_CLOCK_SYNC);
    const sensorCleaningSupported = featureSupported(FEATURES.SENSOR_CLEANING);
    const sleepSupported = featureSupported(FEATURES.CAMERA_SLEEP);
    if (clockSupported) {
      const row = document.createElement("div");
      row.className = "settings-command";
      const copy = document.createElement("span");
      const title = document.createElement("strong");
      title.textContent = t("syncCameraClock");
      const detail = document.createElement("small");
      detail.textContent = state.lastClockSyncAt
        ? t("cameraClockSyncedAt", {
            time: new Date(state.lastClockSyncAt).toLocaleTimeString(resolvedLanguage(), {
              hour: "2-digit",
              minute: "2-digit",
            }),
          })
        : t("syncCameraClockHint");
      copy.append(title, detail);
      const button = document.createElement("button");
      button.type = "button";
      button.className = "button secondary";
      button.dataset.cameraCommand = "clock";
      button.textContent = t("syncNow");
      button.disabled = cameraInteractionBusy();
      button.addEventListener("click", () => syncCameraClock(button));
      row.append(copy, button);
      ui.advancedSettings.append(row);
    }
    if (sensorCleaningSupported) {
      const row = document.createElement("div");
      row.className = "settings-command";
      const copy = document.createElement("span");
      const title = document.createElement("strong");
      title.textContent = t("sensorCleaning");
      const detail = document.createElement("small");
      detail.textContent = t("sensorCleaningHint");
      copy.append(title, detail);
      const actions = document.createElement("div");
      actions.className = "settings-command-actions";
      const cleanButton = document.createElement("button");
      cleanButton.type = "button";
      cleanButton.className = "button secondary";
      cleanButton.dataset.cameraCommand = "sensor-cleaning";
      cleanButton.textContent = t("sensorCleaningNow");
      cleanButton.disabled = cameraInteractionBusy() || Boolean(state.status?.recording) || Boolean(state.status?.bulbExposureActive);
      cleanButton.addEventListener("click", () => {
        if (window.confirm(t("sensorCleaningConfirm"))) void cleanSensor(cleanButton, false);
      });
      const powerOffButton = document.createElement("button");
      powerOffButton.type = "button";
      powerOffButton.className = "button danger";
      powerOffButton.dataset.cameraCommand = "sensor-cleaning";
      powerOffButton.textContent = t("sensorCleaningPowerOff");
      powerOffButton.disabled = cleanButton.disabled;
      powerOffButton.addEventListener("click", () => {
        if (window.confirm(t("sensorCleaningPowerOffConfirm"))) void cleanSensor(powerOffButton, true);
      });
      actions.append(cleanButton, powerOffButton);
      row.append(copy, actions);
      ui.advancedSettings.append(row);
    }
    if (sleepSupported) {
      const row = document.createElement("div");
      row.className = "settings-command";
      const copy = document.createElement("span");
      const title = document.createElement("strong");
      title.textContent = t("cameraSleep");
      const detail = document.createElement("small");
      detail.textContent = t("cameraSleepHint");
      copy.append(title, detail);
      const button = document.createElement("button");
      button.type = "button";
      button.className = "button danger";
      button.dataset.cameraCommand = "sleep";
      button.disabled = cameraInteractionBusy() || Boolean(state.status?.recording) || Boolean(state.status?.bulbExposureActive);
      const icon = document.createElement("span");
      icon.className = "icon";
      icon.dataset.icon = "power";
      icon.setAttribute("aria-hidden", "true");
      const label = document.createElement("span");
      label.textContent = t("cameraSleepNow");
      button.append(icon, label);
      button.addEventListener("click", () => {
        if (window.confirm(t("cameraSleepConfirm"))) void sleepCamera(button);
      });
      row.append(copy, button);
      ui.advancedSettings.append(row);
      window.OpenEosIcons?.render(row);
    }
    if (!settings.length && !clockSupported && !sensorCleaningSupported && !sleepSupported) {
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
      if ([
        "zoom",
        "soundrecordinglevel",
        "focusbracketingnumberofshots",
        "focusbracketingfocusincrement",
      ].includes(setting.key)) {
        const control = document.createElement("span");
        control.className = "range-setting";
        const range = document.createElement("input");
        range.type = "range";
        range.min = "0";
        range.max = String(setting.values.length - 1);
        range.step = "1";
        range.value = String(Math.max(0, setting.values.indexOf(setting.value)));
        range.dataset.settingKey = setting.key;
        range.disabled = cameraInteractionBusy();
        range.setAttribute("aria-label", settingLabel(setting));
        const output = document.createElement("output");
        const updateOutput = () => {
          output.textContent = settingValueLabel(setting, setting.values[Number(range.value)]);
        };
        updateOutput();
        range.addEventListener("input", updateOutput);
        range.addEventListener("change", () => {
          const value = setting.values[Number(range.value)];
          if (value !== setting.value) updateSetting(setting, value, range);
        });
        control.append(range, output);
        label.append(text, control);
        ui.advancedSettings.append(label);
        return;
      }
      const select = document.createElement("select");
      select.dataset.settingKey = setting.key;
      select.disabled = cameraInteractionBusy();
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

  async function syncCameraClock(source) {
    if (!state.session || cameraInteractionBusy() || !featureSupported(FEATURES.CAMERA_CLOCK_SYNC)) return;
    beginCameraInteraction();
    state.lastError = null;
    source.disabled = true;
    setOperationState(t("busy"));
    renderAvailability();
    try {
      state.status = await api(
        `/v1/session/${encodeURIComponent(state.session.id)}/clock/sync`,
        { method: "POST", json: {} },
      );
      state.lastClockSyncAt = Date.now();
      await refreshCapabilityEvidence();
      setOperationState(t("ready"));
      showToast(t("cameraClockSynced"));
    } catch (error) {
      const normalized = captureError(error);
      setOperationState(normalized.message, true);
      showToast(normalized.message, true);
    } finally {
      state.busy = false;
      renderSession();
    }
  }

  async function sleepCamera(source) {
    if (
      !state.session ||
      cameraInteractionBusy() ||
      !featureSupported(FEATURES.CAMERA_SLEEP) ||
      state.status?.recording ||
      state.status?.bulbExposureActive
    ) return;
    const sessionId = state.session.id;
    const wasLive = state.liveActive;
    beginCameraInteraction();
    state.lastError = null;
    source.disabled = true;
    setOperationState(t("busy"));
    stopLiveLoop();
    await stopEventLoop(sessionId);
    renderAvailability();
    try {
      await api(`/v1/session/${encodeURIComponent(sessionId)}/power/sleep`, {
        method: "POST",
        json: {},
      });
      try {
        await api(`/v1/session/${encodeURIComponent(sessionId)}`, { method: "DELETE" });
      } catch (_) {
        // The camera may already be unreachable; the accepted sleep command remains authoritative.
      }
      resetSession();
      showToast(t("cameraSleepComplete"));
    } catch (error) {
      const normalized = captureError(error);
      state.busy = false;
      startEventLoop();
      if (wasLive) await startLiveView({ announce: false });
      state.lastError = normalized.message;
      setOperationState(normalized.message, true);
      showToast(normalized.message, true);
      renderSession();
    }
  }

  async function cleanSensor(source, autoPowerOff) {
    if (
      !state.session ||
      cameraInteractionBusy() ||
      !featureSupported(FEATURES.SENSOR_CLEANING) ||
      state.status?.recording ||
      state.status?.bulbExposureActive
    ) return;
    const sessionId = state.session.id;
    const wasLive = state.liveActive;
    beginCameraInteraction();
    state.lastError = null;
    source.disabled = true;
    setOperationState(t("busy"));
    stopLiveLoop();
    await stopEventLoop(sessionId);
    renderAvailability();
    try {
      await api(`/v1/session/${encodeURIComponent(sessionId)}/maintenance/sensor-cleaning`, {
        method: "POST",
        json: { autoPowerOff },
      });
      if (autoPowerOff) {
        try {
          await api(`/v1/session/${encodeURIComponent(sessionId)}`, { method: "DELETE" });
        } catch (_) {
          // The camera can become unreachable after the accepted cleaning command.
        }
        resetSession();
      } else {
        state.status = await api(`/v1/session/${encodeURIComponent(sessionId)}/status`);
        await refreshCapabilityEvidence();
        state.busy = false;
        startEventLoop();
        if (wasLive) await startLiveView({ announce: false });
        setOperationState(t("ready"));
        renderSession();
      }
      showToast(t("sensorCleaningComplete"));
    } catch (error) {
      const normalized = captureError(error);
      state.busy = false;
      startEventLoop();
      if (wasLive) await startLiveView({ announce: false });
      state.lastError = normalized.message;
      setOperationState(normalized.message, true);
      showToast(normalized.message, true);
      renderSession();
    }
  }

  function settingMatchesCaptureMode(setting) {
    const key = setting.key.toLowerCase();
    if (key === "moviemode") return false;
    const videoTokens = ["movie", "video", "frame", "codec", "record", "sound"];
    const videoOnlyKeys = new Set(["windfilter", "attenuator"]);
    const photoTokens = ["still", "photo", "drive", "imagequality", "capturetarget", "capturestorage"];
    if (state.captureMode === "photo") {
      return !videoOnlyKeys.has(key) && !videoTokens.some((token) => key.includes(token));
    }
    return !key.startsWith("focusbracketing") && !photoTokens.some((token) => key.includes(token));
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
    if (!state.session || cameraInteractionBusy()) return false;
    beginCameraInteraction();
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
      return true;
    } catch (error) {
      const normalized = captureError(error);
      setOperationState(normalized.message, true);
      showToast(normalized.message, true);
      return false;
    } finally {
      state.busy = false;
      source.disabled = false;
      renderSession();
    }
  }

  function renderCaptureMode() {
    const recording = Boolean(state.status?.recording);
    const cameraMode = captureModeFromCamera();
    if (recording) state.captureMode = "video";
    else if (cameraMode) state.captureMode = cameraMode;
    const bulb = state.captureMode === "photo" && isBulbMode();
    const bulbActive = bulb && Boolean(state.status?.bulbExposureActive);
    if (!featureSupported(FEATURES.VIDEO_RECORDING) && !recording && state.captureMode === "video") {
      state.captureMode = "photo";
    }
    ui.photoModeButton.classList.toggle("active", state.captureMode === "photo");
    ui.videoModeButton.classList.toggle("active", state.captureMode === "video");
    ui.photoModeButton.setAttribute("aria-pressed", String(state.captureMode === "photo"));
    ui.videoModeButton.setAttribute("aria-pressed", String(state.captureMode === "video"));
    ui.shutterButton.classList.toggle("video", state.captureMode === "video");
    ui.shutterButton.classList.toggle("recording", state.captureMode === "video" && recording);
    ui.shutterButton.classList.toggle("bulb", bulb);
    ui.shutterButton.classList.toggle("bulb-active", bulbActive);
    ui.recordIndicator.hidden = !recording;
    const labelKey = bulb
      ? (bulbActive ? "stopBulb" : "startBulb")
      : state.captureMode === "photo" ? "capture" : recording ? "stopRecording" : "record";
    ui.shutterLabel.textContent = t(labelKey);
    ui.shutterButton.setAttribute("aria-label", t(labelKey));
    replaceButtonIcon(
      ui.shutterButton,
      bulbActive ? "square" : state.captureMode === "video" ? (recording ? "square" : "circle") : "camera",
    );
  }

  async function selectCaptureMode(mode) {
    if (mode === "video" && !featureSupported(FEATURES.VIDEO_RECORDING)) return;
    if (state.status?.recording && mode !== "video") return;
    const movieMode = settingByKey("moviemode");
    if (movieMode) {
      const desired = mode === "video" ? "on" : "off";
      if (!movieMode.values.includes(desired)) return;
      if (movieMode.value !== desired) {
        const source = mode === "video" ? ui.videoModeButton : ui.photoModeButton;
        const updated = await updateSetting(movieMode, desired, source);
        if (!updated) return;
      }
    }
    state.captureMode = mode;
    renderCaptureMode();
    renderAdvancedSettings();
    renderAvailability();
  }

  async function operateShutter() {
    if (!state.session || cameraInteractionBusy()) return;
    const isPhoto = state.captureMode === "photo";
    const bulb = isPhoto && isBulbMode();
    const bulbWasActive = bulb && Boolean(state.status?.bulbExposureActive);
    const recordingWasActive = !isPhoto && Boolean(state.status?.recording);
    const supported = bulbWasActive || recordingWasActive || (bulb
      ? featureSupported(FEATURES.BULB_EXPOSURE)
      : isPhoto ? featureSupported(FEATURES.STILL_CAPTURE)
      : featureSupported(FEATURES.VIDEO_RECORDING));
    if (!supported) return;
    beginCameraInteraction();
    state.lastError = null;
    setOperationState(t("busy"));
    renderAvailability();
    try {
      if (bulb) {
        if (!bulbWasActive) pauseLivePolling();
        const bulbPath = bulbWasActive ? "/bulb/stop" : "/bulb/start";
        state.status = await api(
          `/v1/session/${encodeURIComponent(state.session.id)}${bulbPath}`,
          { method: "POST" },
        );
        const result = bulbWasActive ? t("bulbStopped") : t("bulbStarted");
        if (bulbWasActive && state.status?.bulbExposureActive !== true) {
          flashCapture();
          resumeLivePolling();
        }
        setOperationState(result);
        showToast(result);
      } else if (isPhoto) {
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
      if (bulb && !bulbWasActive) resumeLivePolling();
      const normalized = captureError(error);
      setOperationState(normalized.message, true);
      showToast(normalized.message, true);
    } finally {
      state.busy = false;
      renderSession();
    }
  }

  async function autofocus() {
    if (!state.session || cameraInteractionBusy() || !featureSupported(FEATURES.AUTOFOCUS)) return;
    beginCameraInteraction();
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
    if (!state.session || cameraInteractionBusy() || !featureSupported(FEATURES.SHUTTER_HALF_PRESS)) return;
    beginCameraInteraction();
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

  function localPreviewSelected() {
    return state.previewInput === "LOCAL_VIDEO";
  }

  function previewActive() {
    return localPreviewSelected() ? state.localVideoActive : state.liveActive;
  }

  function activePreviewElement() {
    if (localPreviewSelected() && state.localVideoActive) return ui.localVideo;
    if (!localPreviewSelected() && state.liveActive && state.liveObjectUrl) return ui.liveImage;
    return null;
  }

  function previewDimensions(element = activePreviewElement()) {
    if (!element) return { width: 0, height: 0 };
    if (element === ui.localVideo) {
      return { width: element.videoWidth || 0, height: element.videoHeight || 0 };
    }
    return { width: element.naturalWidth || 0, height: element.naturalHeight || 0 };
  }

  function renderPreviewInput() {
    const available = state.localVideoSupport.available;
    const localOption = ui.previewInputSelect.querySelector('option[value="LOCAL_VIDEO"]');
    if (localOption) localOption.disabled = !available;
    if (localPreviewSelected() && !available) state.previewInput = "CAMERA";
    ui.previewInputSelect.value = state.previewInput;
    ui.previewInputSelect.disabled = state.localVideoBusy || cameraInteractionBusy();
    ui.localVideoDeviceRow.hidden = !localPreviewSelected() || !available;
    ui.localVideoSupport.hidden = available;
    ui.localVideoSupport.textContent = state.localVideoSupport.reason === "INSECURE_CONTEXT"
      ? t("localVideoInsecure")
      : t("localVideoUnavailable");
    ui.liveSourceRow.hidden = localPreviewSelected() || (liveCapabilities().sources || []).length <= 1;
    ui.fpsRow.hidden = localPreviewSelected();
    renderLocalVideoDevices();
  }

  function renderLocalVideoDevices() {
    const selected = state.localVideoDeviceId;
    ui.localVideoDeviceSelect.replaceChildren();
    const fallback = document.createElement("option");
    fallback.value = "";
    fallback.textContent = t("systemDefault");
    ui.localVideoDeviceSelect.append(fallback);
    const seen = new Set();
    state.localVideoInputs.forEach((input) => {
      if (!input.deviceId || seen.has(input.deviceId)) return;
      seen.add(input.deviceId);
      const option = document.createElement("option");
      option.value = input.deviceId;
      option.textContent = input.label || t("videoInputNumber", { index: input.index });
      ui.localVideoDeviceSelect.append(option);
    });
    if (selected && !seen.has(selected) && !state.localVideoActive) state.localVideoDeviceId = "";
    ui.localVideoDeviceSelect.value = state.localVideoDeviceId;
    ui.localVideoDeviceSelect.disabled = !state.localVideoSupport.available || state.localVideoBusy;
  }

  async function refreshLocalVideoInputs({ quiet = true } = {}) {
    if (!state.localVideoSupport.available) return;
    try {
      state.localVideoInputs = await localVideo.enumerateInputs(navigator.mediaDevices);
      renderLocalVideoDevices();
    } catch (error) {
      state.localVideoError = {
        at: new Date().toISOString(),
        code: localVideo.errorCode(error),
      };
      if (!quiet) showToast(t("localVideoError"), true);
      renderDiagnostics();
    }
  }

  function localVideoErrorMessage(code) {
    const keys = {
      ABORT: "localVideoAborted",
      NOT_ALLOWED: "localVideoPermissionDenied",
      NOT_FOUND: "localVideoNotFound",
      NOT_READABLE: "localVideoNotReadable",
      OVERCONSTRAINED: "localVideoConstraints",
      PLAYBACK: "localVideoPlaybackBlocked",
      SECURITY: "localVideoSecurity",
    };
    return t(keys[code] || "localVideoError");
  }

  function captureLocalVideoError(error) {
    const code = localVideo.errorCode(error);
    const normalized = new ApiError(localVideoErrorMessage(code), {
      code: `LOCAL_VIDEO_${code}`,
      feature: "LOCAL_VIDEO_INPUT",
      engine: "browser-media-devices",
    });
    state.localVideoError = {
      at: new Date().toISOString(),
      code: normalized.code,
      message: normalized.message,
    };
    return captureError(normalized);
  }

  async function startLocalVideo({ announce = true } = {}) {
    if (!state.session || !localPreviewSelected() || !state.localVideoSupport.available || state.localVideoBusy) return;
    const generation = state.localVideoGeneration + 1;
    state.localVideoGeneration = generation;
    state.localVideoBusy = true;
    state.localVideoError = null;
    setOperationState(t("busy"));
    renderAvailability();
    let opened = null;
    try {
      opened = await localVideo.start(navigator.mediaDevices, state.localVideoDeviceId);
      if (generation !== state.localVideoGeneration || !state.session || !localPreviewSelected()) {
        localVideo.stop(opened.stream);
        return;
      }
      state.localVideoStream = opened.stream;
      state.localVideoTrack = opened.track;
      state.localVideoSettings = opened.settings;
      state.localVideoMuted = Boolean(opened.track.muted);
      ui.localVideo.srcObject = opened.stream;
      try {
        await ui.localVideo.play();
      } catch (error) {
        const playbackError = new Error(error?.message || "Local video playback failed.");
        playbackError.name = "LocalVideoPlaybackError";
        throw playbackError;
      }
      if (generation !== state.localVideoGeneration || !state.session || !localPreviewSelected()) {
        localVideo.stop(opened.stream);
        if (ui.localVideo.srcObject === opened.stream) {
          ui.localVideo.pause();
          ui.localVideo.srcObject = null;
        }
        return;
      }
      state.localVideoActive = true;
      state.localVideoFrameTimes = [];
      state.localVideoFrameSamples = [];
      state.localVideoLastRenderAt = 0;
      state.observedFps = 0;
      state.frameBytes = 0;
      state.frameContentType = "video/MediaStream";
      state.lastFrameAt = null;
      opened.track.addEventListener("ended", () => handleLocalVideoEnded(generation), { once: true });
      opened.track.addEventListener("mute", () => {
        if (generation !== state.localVideoGeneration) return;
        state.localVideoMuted = true;
        renderDiagnostics();
      });
      opened.track.addEventListener("unmute", () => {
        if (generation !== state.localVideoGeneration) return;
        state.localVideoMuted = false;
        renderDiagnostics();
      });
      scheduleLocalVideoFrame(generation);
      await refreshLocalVideoInputs();
      setOperationState(t("localVideoStarted"));
      if (announce) showToast(t("localVideoStarted"));
    } catch (error) {
      const currentGeneration = generation === state.localVideoGeneration;
      if (opened?.stream) {
        if (currentGeneration && state.localVideoStream === opened.stream) {
          stopLocalVideo({ announce: false });
        } else {
          localVideo.stop(opened.stream);
          if (ui.localVideo.srcObject === opened.stream) {
            ui.localVideo.pause();
            ui.localVideo.srcObject = null;
          }
        }
      }
      if (!currentGeneration) return;
      const normalized = captureLocalVideoError(error);
      setOperationState(normalized.message, true);
      if (announce) showToast(normalized.message, true);
    } finally {
      if (generation === state.localVideoGeneration) state.localVideoBusy = false;
      renderLiveState();
      renderAvailability();
    }
  }

  function cancelLocalVideoFrame() {
    const handle = state.localVideoFrameHandle;
    state.localVideoFrameHandle = null;
    if (!handle) return;
    if (handle.kind === "video" && typeof ui.localVideo.cancelVideoFrameCallback === "function") {
      ui.localVideo.cancelVideoFrameCallback(handle.id);
    } else if (handle.kind === "timer") {
      window.clearTimeout(handle.id);
    }
  }

  function stopLocalVideo({ announce = true } = {}) {
    const wasActive = state.localVideoActive || Boolean(state.localVideoStream);
    state.localVideoGeneration += 1;
    cancelLocalVideoFrame();
    ui.localVideo.pause();
    localVideo.stop(state.localVideoStream);
    ui.localVideo.srcObject = null;
    state.localVideoStream = null;
    state.localVideoTrack = null;
    state.localVideoSettings = null;
    state.localVideoActive = false;
    state.localVideoBusy = false;
    state.localVideoMuted = false;
    state.localVideoFrameTimes = [];
    state.localVideoFrameSamples = [];
    state.localVideoLastRenderAt = 0;
    state.observedFps = 0;
    state.frameBytes = 0;
    state.frameContentType = null;
    state.lastFrameAt = null;
    clearMonitoringLayers();
    if (wasActive && announce) {
      setOperationState(t("localVideoStopped"));
      showToast(t("localVideoStopped"));
    }
    renderLiveState();
    renderAvailability();
  }

  function handleLocalVideoEnded(generation) {
    if (generation !== state.localVideoGeneration || !state.localVideoActive) return;
    stopLocalVideo({ announce: false });
    state.localVideoError = {
      at: new Date().toISOString(),
      code: "LOCAL_VIDEO_ENDED",
      message: t("localVideoEnded"),
    };
    setOperationState(t("localVideoEnded"), true);
    showToast(t("localVideoEnded"), true);
    renderDiagnostics();
    void refreshLocalVideoInputs();
  }

  function scheduleLocalVideoFrame(generation) {
    if (!state.localVideoActive || generation !== state.localVideoGeneration) return;
    if (typeof ui.localVideo.requestVideoFrameCallback === "function") {
      const id = ui.localVideo.requestVideoFrameCallback((now) => handleLocalVideoFrame(now, generation, true));
      state.localVideoFrameHandle = { kind: "video", id };
    } else {
      const id = window.setTimeout(
        () => handleLocalVideoFrame(performance.now(), generation, false),
        LOCAL_VIDEO_RENDER_INTERVAL_MILLIS,
      );
      state.localVideoFrameHandle = { kind: "timer", id };
    }
  }

  function handleLocalVideoFrame(now, generation, presentedCallback) {
    if (!state.localVideoActive || generation !== state.localVideoGeneration) return;
    state.localVideoFrameHandle = null;
    if (presentedCallback) {
      const rolling = localVideo.rollingFps(state.localVideoFrameTimes, now);
      state.localVideoFrameTimes = rolling.timestamps;
      state.observedFps = rolling.fps || null;
    } else {
      const rolling = localVideo.rollingFrameCount(
        state.localVideoFrameSamples,
        now,
        localVideo.presentedFrameCount(ui.localVideo),
      );
      state.localVideoFrameSamples = rolling.samples;
      state.observedFps = rolling.fps;
    }
    if (now - state.localVideoLastRenderAt >= LOCAL_VIDEO_RENDER_INTERVAL_MILLIS) {
      state.localVideoLastRenderAt = now;
      state.lastFrameAt = new Date().toISOString();
      renderMonitoringFrame();
      renderFrameIndicator();
    }
    scheduleLocalVideoFrame(generation);
  }

  async function changePreviewInput() {
    const next = ui.previewInputSelect.value;
    if (next === state.previewInput || !["CAMERA", "LOCAL_VIDEO"].includes(next)) return;
    if (next === "LOCAL_VIDEO" && !state.localVideoSupport.available) {
      renderPreviewInput();
      return;
    }
    const wasActive = previewActive();
    if (state.liveActive) await stopLiveView({ announce: false });
    if (state.localVideoActive) stopLocalVideo({ announce: false });
    state.previewInput = next;
    renderPreviewInput();
    renderLiveState();
    renderAvailability();
    if (!wasActive) return;
    if (localPreviewSelected()) await startLocalVideo({ announce: false });
    else await startLiveView({ announce: false });
  }

  async function changeLocalVideoDevice() {
    const deviceId = ui.localVideoDeviceSelect.value;
    if (deviceId === state.localVideoDeviceId) return;
    const wasActive = state.localVideoActive;
    if (wasActive) stopLocalVideo({ announce: false });
    state.localVideoDeviceId = deviceId;
    renderLocalVideoDevices();
    if (wasActive) await startLocalVideo({ announce: false });
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
    ui.fpsRow.hidden = localPreviewSelected();
    ui.fpsSelect.disabled = localPreviewSelected() || cameraInteractionBusy() ||
      !featureSupported(FEATURES.LIVE_VIEW);
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
    ui.liveSourceRow.hidden = localPreviewSelected() || sources.length <= 1;
    ui.liveSourceSelect.disabled = localPreviewSelected() || cameraInteractionBusy() ||
      !featureSupported(FEATURES.LIVE_VIEW);
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
    ui.tapActionRow.hidden = localPreviewSelected() || !clickWhiteBalanceSupported;
    const focusOption = ui.tapActionSelect.querySelector('option[value="focus"]');
    focusOption.hidden = !featureSupported(FEATURES.TAP_FOCUS);
    const effective = effectiveTapAction();
    if (effective) state.tapAction = effective;
    ui.tapActionSelect.value = state.tapAction;
    ui.tapActionSelect.disabled = localPreviewSelected() || cameraInteractionBusy() || !state.liveActive;
  }

  async function toggleLiveView() {
    if (localPreviewSelected()) {
      if (state.localVideoActive) stopLocalVideo();
      else await startLocalVideo();
      return;
    }
    if (state.liveActive) await stopLiveView();
    else await startLiveView();
  }

  async function startLiveView({ announce = true } = {}) {
    if (
      localPreviewSelected() || !state.session || cameraInteractionBusy() ||
      !featureSupported(FEATURES.LIVE_VIEW)
    ) return;
    beginCameraInteraction();
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
      state.livePollingSuspended = false;
      state.liveMagnification = 1;
      state.frameTimes = [];
      state.observedFps = 0;
      state.liveGeneration += 1;
      pollLiveView(state.liveGeneration);
      void refreshRtpAudioStatus();
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
      beginCameraInteraction();
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
    state.liveFrameAbortController?.abort();
    state.liveFrameAbortController = null;
    stopRtpAudio({ announce: false });
    state.liveActive = false;
    state.livePollingSuspended = false;
    state.liveGeneration += 1;
    state.frameTimes = [];
    state.observedFps = 0;
    state.frameBytes = 0;
    state.frameContentType = null;
    state.activeLiveSource = null;
    state.liveMagnification = 1;
    state.lastFrameAt = null;
    state.monitorAnalysisError = null;
    ui.liveImage.removeAttribute("src");
    releaseObjectUrl(state.liveObjectUrl);
    state.liveObjectUrl = null;
    clearMonitoringLayers();
    renderLiveState();
  }

  async function pollLiveView(generation) {
    while (
      state.liveActive && !state.livePollingSuspended &&
      generation === state.liveGeneration && state.session
    ) {
      const started = performance.now();
      const controller = new AbortController();
      state.liveFrameAbortController = controller;
      try {
        const blob = await api(`/v1/session/${encodeURIComponent(state.session.id)}/liveview/frame`, {
          responseType: "blob",
          signal: controller.signal,
        });
        if (!state.liveActive || state.livePollingSuspended || generation !== state.liveGeneration) return;
        const url = URL.createObjectURL(blob);
        const previous = state.liveObjectUrl;
        ui.liveImage.src = url;
        await ui.liveImage.decode();
        if (!state.liveActive || state.livePollingSuspended || generation !== state.liveGeneration) {
          if (previous) ui.liveImage.src = previous;
          else ui.liveImage.removeAttribute("src");
          releaseObjectUrl(url);
          return;
        }
        state.liveObjectUrl = url;
        ui.liveImage.hidden = false;
        ui.viewfinderPlaceholder.hidden = true;
        releaseObjectUrl(previous);
        renderMonitoringFrame();
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
        if (controller.signal.aborted || !state.liveActive || generation !== state.liveGeneration) return;
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
      } finally {
        if (state.liveFrameAbortController === controller) state.liveFrameAbortController = null;
      }
      const elapsed = performance.now() - started;
      const delay = Math.max(0, 1000 / state.requestedFps - elapsed);
      if (delay > 0) await sleep(delay);
    }
  }

  function pauseLivePolling() {
    if (!state.liveActive) return;
    state.livePollingSuspended = true;
    state.liveGeneration += 1;
    state.frameTimes = [];
    state.observedFps = 0;
    renderFrameIndicator();
  }

  function resumeLivePolling() {
    if (!state.liveActive || !state.livePollingSuspended || !state.session) return;
    state.livePollingSuspended = false;
    state.liveGeneration += 1;
    pollLiveView(state.liveGeneration);
  }

  async function changeFps() {
    if (localPreviewSelected()) return;
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
    if (localPreviewSelected()) return;
    state.liveSource = ui.liveSourceSelect.value;
    if (!state.liveActive) return;
    await stopLiveView({ announce: false });
    await startLiveView({ announce: false });
  }

  function renderLiveState() {
    const local = localPreviewSelected();
    const active = previewActive();
    ui.liveImage.hidden = local || !state.liveActive || !state.liveObjectUrl;
    ui.localVideo.hidden = !local || !state.localVideoActive;
    ui.viewfinderPlaceholder.hidden = active && (local ? ui.localVideo.readyState >= 1 : Boolean(state.liveObjectUrl));
    if (ui.liveImage.hidden && ui.localVideo.hidden) clearMonitoringLayers();
    const labelKey = local
      ? (active ? "stopLocalVideo" : "startLocalVideo")
      : (active ? "stopLiveView" : "startLiveView");
    [ui.liveToggleButton, ui.railLiveButton].forEach((button) => {
      const label = button.querySelector("span[data-i18n]");
      if (label) {
        label.dataset.i18n = labelKey;
        label.textContent = t(labelKey);
      }
      button.setAttribute("aria-label", t(labelKey));
      replaceButtonIcon(button, active ? "square" : "play");
    });
    if (!state.liveActive || local) ui.focusReticle.hidden = true;
    renderPreviewInput();
    renderLiveMagnification();
    renderRtpAudio();
    renderFrameIndicator();
  }

  function currentRtpAudioStatus() {
    const value = state.status?.raw?.rtpAudio;
    return value && typeof value === "object" ? value : null;
  }

  async function refreshRtpAudioStatus() {
    if (!state.session || !state.liveActive || state.activeLiveSource !== "CCAPI_RTP") {
      renderRtpAudio();
      return;
    }
    try {
      state.status = await api(`/v1/session/${encodeURIComponent(state.session.id)}/status`);
    } catch (_) {
      // Video remains usable even when this optional status refresh fails.
    }
    renderRtpAudio();
  }

  function renderRtpAudio() {
    const status = currentRtpAudioStatus();
    const rtpActive = state.liveActive && state.activeLiveSource === "CCAPI_RTP" && !localPreviewSelected();
    ui.rtpAudioButton.hidden = !rtpActive || !status?.advertised;
    ui.rtpAudioButton.disabled = state.rtpAudioBusy || !status?.available;
    ui.rtpAudioButton.classList.toggle("active", state.rtpAudioEnabled);
    ui.rtpAudioButton.setAttribute("aria-pressed", String(state.rtpAudioEnabled));
    const label = state.rtpAudioEnabled ? t("cameraAudioMute") : t("cameraAudioUnmute");
    const unavailable = status?.reason || status?.lastError || t("cameraAudioUnavailable");
    ui.rtpAudioButton.setAttribute("aria-label", status?.available ? label : unavailable);
    ui.rtpAudioButton.title = status?.available ? label : unavailable;
    replaceButtonIcon(ui.rtpAudioButton, state.rtpAudioEnabled ? "volume-2" : "volume-x");
  }

  async function toggleRtpAudio() {
    if (state.rtpAudioEnabled) {
      stopRtpAudio({ announce: true });
      return;
    }
    const status = currentRtpAudioStatus();
    if (!state.session || !state.liveActive || state.activeLiveSource !== "CCAPI_RTP" || !status?.available) {
      showToast(status?.reason || t("cameraAudioUnavailable"), true);
      return;
    }
    const AudioContextClass = window.AudioContext || window.webkitAudioContext;
    if (!AudioContextClass) {
      showToast(t("cameraAudioUnavailable"), true);
      return;
    }
    state.rtpAudioBusy = true;
    state.rtpAudioError = null;
    renderRtpAudio();
    let context = null;
    try {
      context = new AudioContextClass({ latencyHint: "interactive", sampleRate: 48_000 });
      await context.resume();
      state.rtpAudioContext = context;
      state.rtpAudioEnabled = true;
      state.rtpAudioAfterGeneration = 0;
      state.rtpAudioNextStart = Number.NaN;
      state.rtpAudioLoopGeneration += 1;
      void pollRtpAudio(state.rtpAudioLoopGeneration);
      showToast(t("cameraAudioStarted"));
    } catch (error) {
      state.rtpAudioError = error instanceof Error ? error.message : String(error);
      if (context) void context.close();
      showToast(state.rtpAudioError, true);
    } finally {
      state.rtpAudioBusy = false;
      renderRtpAudio();
    }
  }

  async function pollRtpAudio(generation) {
    while (
      state.rtpAudioEnabled && generation === state.rtpAudioLoopGeneration &&
      state.liveActive && state.activeLiveSource === "CCAPI_RTP" && state.session
    ) {
      const controller = new AbortController();
      state.rtpAudioAbortController = controller;
      try {
        const response = await api(
          `/v1/session/${encodeURIComponent(state.session.id)}/liveview/audio` +
            `?after=${state.rtpAudioAfterGeneration}&timeoutMs=1000`,
          { responseType: "response", signal: controller.signal },
        );
        if (!state.rtpAudioEnabled || generation !== state.rtpAudioLoopGeneration) return;
        const chunk = await rtpAudio.readPcmResponse(response);
        if (!state.rtpAudioEnabled || generation !== state.rtpAudioLoopGeneration) return;
        if (!chunk) continue;
        state.rtpAudioAfterGeneration = chunk.generation;
        scheduleRtpAudioChunk(chunk);
        state.rtpAudioBytes = chunk.byteLength;
        state.rtpAudioSampleRate = chunk.sampleRate;
        state.rtpAudioChannels = chunk.channels;
        state.rtpAudioLastAt = new Date().toISOString();
        state.rtpAudioError = null;
      } catch (error) {
        if (!state.rtpAudioEnabled || generation !== state.rtpAudioLoopGeneration || error?.name === "AbortError") {
          return;
        }
        const normalized = captureError(error);
        state.rtpAudioError = normalized.message;
        stopRtpAudio({ announce: false });
        showToast(normalized.message, true);
        return;
      } finally {
        if (state.rtpAudioAbortController === controller) state.rtpAudioAbortController = null;
      }
    }
  }

  function scheduleRtpAudioChunk(chunk) {
    const context = state.rtpAudioContext;
    if (!context) return;
    const duration = chunk.sampleFrames / chunk.sampleRate;
    const timing = rtpAudio.scheduleTiming(
      context.currentTime,
      state.rtpAudioNextStart,
      duration,
      chunk.discontinuity,
    );
    if (timing.reset) stopRtpAudioSources();
    const buffer = context.createBuffer(chunk.channels, chunk.sampleFrames, chunk.sampleRate);
    chunk.channelData.forEach((samples, channel) => buffer.copyToChannel(samples, channel));
    const source = context.createBufferSource();
    source.buffer = buffer;
    source.connect(context.destination);
    source.addEventListener("ended", () => state.rtpAudioSources.delete(source), { once: true });
    state.rtpAudioSources.add(source);
    source.start(timing.startTime);
    state.rtpAudioNextStart = timing.nextStart;
  }

  function stopRtpAudioSources() {
    state.rtpAudioSources.forEach((source) => {
      try {
        source.stop();
      } catch (_) {
        // A source that already ended requires no cleanup.
      }
    });
    state.rtpAudioSources.clear();
  }

  function stopRtpAudio({ announce = false } = {}) {
    const wasEnabled = state.rtpAudioEnabled;
    state.rtpAudioEnabled = false;
    state.rtpAudioBusy = false;
    state.rtpAudioLoopGeneration += 1;
    state.rtpAudioAbortController?.abort();
    state.rtpAudioAbortController = null;
    stopRtpAudioSources();
    const context = state.rtpAudioContext;
    state.rtpAudioContext = null;
    state.rtpAudioNextStart = Number.NaN;
    if (context) void context.close().catch(() => {});
    renderRtpAudio();
    if (announce && wasEnabled) showToast(t("cameraAudioStopped"));
  }

  const monitorAnalysisCanvas = document.createElement("canvas");
  let lutPreviewRenderer = null;

  function monitorNeedsPixelAnalysis() {
    const settings = state.monitorSettings;
    return settings.histogramVisible || settings.waveformVisible || settings.zebraThresholdPercent !== null ||
      settings.falseColorEnabled || settings.focusPeakingEnabled;
  }

  function liveContentDisplayRect() {
    const media = activePreviewElement();
    const dimensions = previewDimensions(media);
    const naturalWidth = dimensions.width;
    const naturalHeight = dimensions.height;
    const width = ui.viewfinder.clientWidth;
    const height = ui.viewfinder.clientHeight;
    if (!naturalWidth || !naturalHeight || !width || !height) return null;
    return monitoring.fitRect(
      naturalWidth * state.monitorSettings.desqueeze,
      naturalHeight,
      width,
      height,
    );
  }

  function positionMonitorLayer(element, rect) {
    element.style.left = `${rect.left}px`;
    element.style.top = `${rect.top}px`;
    element.style.width = `${rect.width}px`;
    element.style.height = `${rect.height}px`;
  }

  function applyLiveViewLayout() {
    const media = activePreviewElement();
    const rect = liveContentDisplayRect();
    if (!rect) return null;
    [media, ui.monitorLutPreview, ui.monitorPixelOverlay, ui.monitorGuidesOverlay].forEach((element) => {
      positionMonitorLayer(element, rect);
    });
    drawMonitorGuides(rect);
    positionHistogram(rect);
    return rect;
  }

  function renderMonitoringFrame() {
    const media = activePreviewElement();
    const sourceDimensions = previewDimensions(media);
    const rect = applyLiveViewLayout();
    if (!rect) {
      clearMonitoringLayers();
      return;
    }
    const lutRendered = renderLutPreview(media);
    if (!monitorNeedsPixelAnalysis()) {
      ui.monitorPixelOverlay.hidden = true;
      ui.monitorHistogram.hidden = true;
      ui.monitorWaveform.hidden = true;
      state.monitorAnalysisError = null;
      return;
    }
    try {
      const dimensions = monitoring.analysisDimensions(sourceDimensions.width, sourceDimensions.height);
      monitorAnalysisCanvas.width = dimensions.width;
      monitorAnalysisCanvas.height = dimensions.height;
      const analysisContext = monitorAnalysisCanvas.getContext("2d", { willReadFrequently: true });
      analysisContext.drawImage(lutRendered ? ui.monitorLutPreview : media, 0, 0, dimensions.width, dimensions.height);
      const frame = analysisContext.getImageData(0, 0, dimensions.width, dimensions.height);
      const analysis = monitoring.analyzePixels(frame.data, dimensions.width, dimensions.height, {
        waveformVisible: state.monitorSettings.waveformVisible,
        zebraThresholdPercent: state.monitorSettings.zebraThresholdPercent,
        falseColorEnabled: state.monitorSettings.falseColorEnabled,
        focusPeakingEnabled: state.monitorSettings.focusPeakingEnabled,
      });
      renderMonitorPixelOverlay(analysis);
      renderMonitorHistogram(analysis.histogram);
      renderMonitorWaveform(analysis.waveform);
      state.monitorAnalysisError = null;
    } catch (error) {
      state.monitorAnalysisError = error instanceof Error ? `${error.name}: ${error.message}` : String(error);
      ui.monitorPixelOverlay.hidden = true;
      ui.monitorHistogram.hidden = true;
      ui.monitorWaveform.hidden = true;
    }
  }

  function renderLutPreview(media) {
    const cubeLut = state.monitorSettings.cubeLut;
    if (!cubeLut) {
      ui.monitorLutPreview.hidden = true;
      return false;
    }
    try {
      lutPreviewRenderer ||= lut.createWebGLRenderer(ui.monitorLutPreview);
      lutPreviewRenderer.render(media, cubeLut);
      ui.monitorLutPreview.hidden = false;
      return true;
    } catch (error) {
      state.monitorAnalysisError = error instanceof Error ? `${error.name}: ${error.message}` : String(error);
      ui.monitorLutPreview.hidden = true;
      return false;
    }
  }

  function renderMonitorPixelOverlay(analysis) {
    if (!analysis.overlay) {
      ui.monitorPixelOverlay.hidden = true;
      return;
    }
    ui.monitorPixelOverlay.width = analysis.width;
    ui.monitorPixelOverlay.height = analysis.height;
    const context = ui.monitorPixelOverlay.getContext("2d");
    const image = context.createImageData(analysis.width, analysis.height);
    image.data.set(analysis.overlay);
    context.putImageData(image, 0, 0);
    ui.monitorPixelOverlay.hidden = false;
  }

  function renderMonitorHistogram(histogram) {
    if (!state.monitorSettings.histogramVisible) {
      ui.monitorHistogram.hidden = true;
      return;
    }
    const width = 160;
    const height = 72;
    const padding = 7;
    ui.monitorHistogram.width = width;
    ui.monitorHistogram.height = height;
    const context = ui.monitorHistogram.getContext("2d");
    context.clearRect(0, 0, width, height);
    const peak = Math.max(1, ...histogram);
    context.beginPath();
    context.moveTo(padding, height - padding);
    histogram.forEach((count, index) => {
      const x = padding + (width - padding * 2) * index / (histogram.length - 1);
      const y = height - padding - (height - padding * 2) * count / peak;
      context.lineTo(x, y);
    });
    context.lineTo(width - padding, height - padding);
    context.closePath();
    context.fillStyle = "rgb(255 255 255 / 36%)";
    context.fill();
    context.strokeStyle = "rgb(255 255 255 / 92%)";
    context.lineWidth = 1;
    context.stroke();
    ui.monitorHistogram.hidden = false;
  }

  function renderMonitorWaveform(waveform) {
    if (!state.monitorSettings.waveformVisible || !waveform) {
      ui.monitorWaveform.hidden = true;
      return;
    }
    const width = 160;
    const height = 88;
    const padding = 7;
    ui.monitorWaveform.width = width;
    ui.monitorWaveform.height = height;
    const context = ui.monitorWaveform.getContext("2d");
    context.clearRect(0, 0, width, height);
    context.strokeStyle = "rgb(255 255 255 / 16%)";
    context.lineWidth = 0.5;
    for (let guide = 0; guide <= 4; guide += 1) {
      const y = Math.min(height - padding, padding + (height - padding * 2) * guide / 4);
      context.beginPath();
      context.moveTo(padding, y);
      context.lineTo(width - padding, y);
      context.stroke();
    }
    const peak = Math.max(1, ...waveform.density);
    const cellWidth = (width - padding * 2) / waveform.width;
    const cellHeight = (height - padding * 2) / waveform.height;
    waveform.density.forEach((count, index) => {
      if (!count) return;
      const x = index % waveform.width;
      const y = Math.floor(index / waveform.width);
      const alpha = Math.min(1, Math.max(0.16, Math.sqrt(count / peak)));
      context.fillStyle = `rgb(51 198 216 / ${alpha})`;
      context.fillRect(
        padding + x * cellWidth,
        padding + y * cellHeight,
        Math.max(1, cellWidth),
        Math.max(1, cellHeight),
      );
    });
    ui.monitorWaveform.hidden = false;
  }

  function positionHistogram(rect) {
    const width = Math.min(160, Math.max(120, rect.width * 0.34));
    const histogramHeight = width * 72 / 160;
    const waveformHeight = width * 88 / 160;
    ui.monitorHistogram.style.width = `${width}px`;
    ui.monitorHistogram.style.height = `${histogramHeight}px`;
    ui.monitorHistogram.style.left = `${rect.left + 10}px`;
    ui.monitorHistogram.style.top = `${Math.max(rect.top + 10, rect.top + rect.height - histogramHeight - 10)}px`;
    ui.monitorWaveform.style.width = `${width}px`;
    ui.monitorWaveform.style.height = `${waveformHeight}px`;
    ui.monitorWaveform.style.left = `${rect.left + 10}px`;
    ui.monitorWaveform.style.top = `${Math.max(rect.top + 10, rect.top + rect.height - waveformHeight - 10)}px`;
  }

  function drawMonitorGuides(rect) {
    const hasGuides = Boolean(state.monitorSettings.frameGuide) || state.monitorSettings.safeAreaVisible;
    if (!hasGuides) {
      ui.monitorGuidesOverlay.hidden = true;
      return;
    }
    const scale = Math.min(2, window.devicePixelRatio || 1);
    ui.monitorGuidesOverlay.width = Math.max(1, Math.round(rect.width * scale));
    ui.monitorGuidesOverlay.height = Math.max(1, Math.round(rect.height * scale));
    const context = ui.monitorGuidesOverlay.getContext("2d");
    context.setTransform(scale, 0, 0, scale, 0, 0);
    context.clearRect(0, 0, rect.width, rect.height);
    const guideRatios = { "16:9": 16 / 9, "2.39:1": 2.39, "1:1": 1, "4:3": 4 / 3 };
    const guideRatio = guideRatios[state.monitorSettings.frameGuide];
    if (guideRatio) {
      const guide = monitoring.fitRect(guideRatio, 1, rect.width, rect.height);
      context.strokeStyle = "rgb(255 255 255 / 85%)";
      context.lineWidth = 1.2;
      context.setLineDash([7, 5]);
      context.strokeRect(guide.left + 1, guide.top + 1, guide.width - 2, guide.height - 2);
    }
    if (state.monitorSettings.safeAreaVisible) {
      context.lineWidth = 1;
      context.setLineDash([5, 4]);
      context.strokeStyle = "rgb(51 198 216 / 76%)";
      context.strokeRect(rect.width * 0.05, rect.height * 0.05, rect.width * 0.9, rect.height * 0.9);
      context.setLineDash([3, 4]);
      context.strokeStyle = "rgb(240 180 41 / 76%)";
      context.strokeRect(rect.width * 0.1, rect.height * 0.1, rect.width * 0.8, rect.height * 0.8);
    }
    ui.monitorGuidesOverlay.hidden = false;
  }

  function clearMonitoringLayers() {
    ui.monitorLutPreview.hidden = true;
    ui.monitorPixelOverlay.hidden = true;
    ui.monitorGuidesOverlay.hidden = true;
    ui.monitorHistogram.hidden = true;
    ui.monitorWaveform.hidden = true;
  }

  function renderMonitoringControls() {
    const settings = state.monitorSettings;
    ui.monitorHistogramToggle.checked = settings.histogramVisible;
    ui.monitorWaveformToggle.checked = settings.waveformVisible;
    if (settings.cubeLut) {
      delete ui.monitorLutSummary.dataset.i18n;
      ui.monitorLutSummary.textContent = t("lutSummary", {
        name: settings.cubeLut.name,
        size: settings.cubeLut.size,
      });
      ui.monitorLutRemove.hidden = false;
    } else {
      ui.monitorLutSummary.dataset.i18n = "noLutLoaded";
      ui.monitorLutSummary.textContent = t("noLutLoaded");
      ui.monitorLutRemove.hidden = true;
    }
    ui.monitorZebraSelect.value = settings.zebraThresholdPercent === null
      ? ""
      : String(settings.zebraThresholdPercent);
    ui.monitorFalseColorToggle.checked = settings.falseColorEnabled;
    ui.monitorFocusPeakingToggle.checked = settings.focusPeakingEnabled;
    ui.monitorFrameGuideSelect.value = settings.frameGuide;
    ui.monitorSafeAreaToggle.checked = settings.safeAreaVisible;
    ui.monitorDesqueezeSelect.value = String(settings.desqueeze);
  }

  function openMonitoringDialog() {
    renderMonitoringControls();
    ui.monitoringDialog.showModal();
  }

  function changeMonitoringSettings() {
    state.monitorSettings = {
      histogramVisible: ui.monitorHistogramToggle.checked,
      waveformVisible: ui.monitorWaveformToggle.checked,
      zebraThresholdPercent: ui.monitorZebraSelect.value ? Number(ui.monitorZebraSelect.value) : null,
      falseColorEnabled: ui.monitorFalseColorToggle.checked,
      focusPeakingEnabled: ui.monitorFocusPeakingToggle.checked,
      frameGuide: ui.monitorFrameGuideSelect.value,
      safeAreaVisible: ui.monitorSafeAreaToggle.checked,
      desqueeze: Number(ui.monitorDesqueezeSelect.value) || 1,
      cubeLut: state.monitorSettings.cubeLut,
    };
    renderMonitoringFrame();
    renderDiagnostics();
  }

  async function importCubeLutFile() {
    const file = ui.monitorLutFile.files?.[0];
    ui.monitorLutFile.value = "";
    if (!file) return;
    try {
      if (file.size > lut.MAX_CUBE_BYTES) throw new RangeError("3D LUT exceeds the 16 MiB limit.");
      const parsed = lut.parseCubeLut(await file.text(), file.name);
      lutPreviewRenderer ||= lut.createWebGLRenderer(ui.monitorLutPreview);
      state.monitorSettings = { ...state.monitorSettings, cubeLut: parsed };
      state.monitorAnalysisError = null;
      renderMonitoringControls();
      renderMonitoringFrame();
      renderDiagnostics();
      showToast(t("lutLoaded"));
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      state.monitorAnalysisError = `LUT: ${message}`;
      showToast(`${t("lutLoadFailed")}: ${message}`, true);
      renderDiagnostics();
    }
  }

  function removeCubeLut() {
    state.monitorSettings = { ...state.monitorSettings, cubeLut: null };
    ui.monitorLutPreview.hidden = true;
    state.monitorAnalysisError = null;
    renderMonitoringControls();
    renderMonitoringFrame();
    renderDiagnostics();
    showToast(t("lutRemoved"));
  }

  function renderLiveMagnification() {
    const supported = featureSupported(FEATURES.LIVE_VIEW_MAGNIFICATION);
    const cameraPreview = !localPreviewSelected();
    const bulbActive = Boolean(state.status?.bulbExposureActive);
    const target = state.liveMagnification === 5 ? 1 : 5;
    ui.liveMagnificationButton.hidden = !supported || !cameraPreview;
    ui.liveMagnificationButton.disabled = !cameraPreview || cameraInteractionBusy() || bulbActive ||
      !state.liveActive || !supported;
    ui.liveMagnificationLabel.textContent = `${target}x`;
    const description = t("liveViewMagnification", { value: target });
    ui.liveMagnificationButton.setAttribute("aria-label", description);
    ui.liveMagnificationButton.title = description;
    replaceButtonIcon(ui.liveMagnificationButton, target === 5 ? "zoom-in" : "zoom-out");
  }

  async function setLiveViewMagnification() {
    if (
      localPreviewSelected() || !state.session || cameraInteractionBusy() || !state.liveActive ||
      state.status?.bulbExposureActive ||
      !featureSupported(FEATURES.LIVE_VIEW_MAGNIFICATION)
    ) return;
    const target = state.liveMagnification === 5 ? 1 : 5;
    beginCameraInteraction();
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
    const fps = Number.isFinite(state.observedFps) && state.observedFps > 0
      ? state.observedFps.toFixed(1)
      : (localPreviewSelected() ? "--" : "0");
    ui.frameIndicator.textContent = localPreviewSelected()
      ? `${fps} FPS`
      : `${fps} / ${state.requestedFps} FPS`;
    ui.previewSourceIndicator.textContent = localPreviewSelected() ? "UVC" : "CAM";
    renderDiagnostics();
  }

  async function driveFocus(direction) {
    if (!state.session || cameraInteractionBusy() || !featureSupported(FEATURES.FOCUS_DRIVE)) return;
    if (!state.liveActive) {
      showToast(t("liveViewRequired"), true);
      return;
    }
    beginCameraInteraction();
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
    if (localPreviewSelected()) return null;
    const imageBounds = ui.liveImage.getBoundingClientRect();
    const viewfinderBounds = ui.viewfinder.getBoundingClientRect();
    const imageWidth = imageBounds.width;
    const imageHeight = imageBounds.height;
    const imageLeft = imageBounds.left;
    const imageTop = imageBounds.top;
    if (!imageWidth || !imageHeight || ui.liveImage.hidden) return null;
    if (
      clientX < imageLeft || clientX > imageLeft + imageWidth ||
      clientY < imageTop || clientY > imageTop + imageHeight
    ) return null;
    return {
      x: (clientX - imageLeft) / imageWidth,
      y: (clientY - imageTop) / imageHeight,
      displayX: clientX - viewfinderBounds.left,
      displayY: clientY - viewfinderBounds.top,
    };
  }

  async function tapFocus(point) {
    const action = effectiveTapAction();
    if (
      !point || !state.session || cameraInteractionBusy() || !state.liveActive ||
      localPreviewSelected() || !action
    ) return;
    beginCameraInteraction();
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
    const bulbActive = Boolean(state.status?.bulbExposureActive);
    const interactionBusy = cameraInteractionBusy();
    const videoSupported = featureSupported(FEATURES.VIDEO_RECORDING);
    const recording = Boolean(state.status?.recording);
    const videoAvailable = videoSupported || recording;
    ui.scanButton.disabled = state.busy;
    const connectionReady = state.connectionMode === "ccapi" ? validCcapiUrl() : Boolean(ui.cameraSelect.value);
    ui.connectButton.disabled = state.busy || !connectionReady;
    ui.refreshButton.disabled = !connected || interactionBusy || bulbActive;
    ui.disconnectButton.disabled = !connected || state.busy;
    ui.photoModeButton.disabled = interactionBusy || bulbActive || Boolean(state.status?.recording);
    ui.videoModeButton.disabled = interactionBusy || bulbActive || !videoAvailable;
    ui.videoModeButton.hidden = !videoAvailable;
    ui.videoModeButton.parentElement.classList.toggle("single", !videoAvailable);
    const bulbActiveStop = state.captureMode === "photo" && isBulbMode() && bulbActive;
    const shutterSupported = bulbActiveStop || recording || (state.captureMode === "photo" && isBulbMode()
      ? featureSupported(FEATURES.BULB_EXPOSURE)
      : state.captureMode === "photo" ? featureSupported(FEATURES.STILL_CAPTURE)
      : videoSupported);
    const temperatureAllowed = bulbActiveStop || recording || (
      state.captureMode === "photo" ? temperatureAllows("release") : temperatureAllows("movie")
    );
    ui.shutterButton.disabled = interactionBusy || !shutterSupported || !temperatureAllowed;
    ui.shutterButton.title = shutterSupported && temperatureAllowed ? ui.shutterLabel.textContent : t("unsupported");
    const autofocusSupported = featureSupported(FEATURES.AUTOFOCUS);
    ui.autofocusButton.hidden = !autofocusSupported;
    ui.autofocusButton.disabled = interactionBusy || bulbActive || !autofocusSupported;
    const halfPressSupported = featureSupported(FEATURES.SHUTTER_HALF_PRESS);
    ui.halfPressButton.hidden = !halfPressSupported;
    ui.halfPressButton.disabled = interactionBusy || bulbActive || !halfPressSupported;
    const cameraLiveSupported = featureSupported(FEATURES.LIVE_VIEW);
    const localLiveSupported = state.localVideoSupport.available;
    const selectedLiveSupported = localPreviewSelected() ? localLiveSupported : cameraLiveSupported;
    const cameraLiveTemperatureAllowed = state.liveActive || temperatureAllows("liveview");
    [ui.liveToggleButton, ui.railLiveButton].forEach((button) => {
      button.hidden = !cameraLiveSupported && !localLiveSupported;
      button.disabled = localPreviewSelected()
        ? state.localVideoBusy || !localLiveSupported
        : interactionBusy || bulbActive || !selectedLiveSupported || !cameraLiveTemperatureAllowed;
    });
    const quickActionCount = [
      autofocusSupported,
      halfPressSupported,
      cameraLiveSupported || localLiveSupported,
    ].filter(Boolean).length;
    ui.railLiveButton.parentElement.classList.toggle("single", quickActionCount === 1);
    ui.railLiveButton.parentElement.classList.toggle("three", quickActionCount === 3);
    document.querySelector(".live-settings").hidden = !connected || (!cameraLiveSupported && !localLiveSupported);
    const focusSupported = featureSupported(FEATURES.FOCUS_DRIVE);
    ui.focusSection.hidden = !focusSupported;
    ui.focusNearButton.disabled = interactionBusy || bulbActive || !state.liveActive;
    ui.focusFarButton.disabled = interactionBusy || bulbActive || !state.liveActive;
    ui.previewInputSelect.disabled = interactionBusy || state.localVideoBusy;
    ui.localVideoDeviceSelect.disabled = !localPreviewSelected() || !localLiveSupported || state.localVideoBusy;
    ui.fpsSelect.disabled = localPreviewSelected() || interactionBusy || bulbActive || !cameraLiveSupported || !temperatureAllows("liveview");
    ui.liveSourceSelect.disabled = localPreviewSelected() || interactionBusy || bulbActive || !cameraLiveSupported || !temperatureAllows("liveview");
    ui.tapActionSelect.disabled = localPreviewSelected() || interactionBusy || bulbActive || !state.liveActive;
    ui.monitoringButton.disabled = !connected;
    renderLiveMagnification();
    document.querySelectorAll("#exposure-strip .exposure-control").forEach((button) => {
      button.disabled = interactionBusy || bulbActive || !settingByKey(button.dataset.settingKey);
    });
    document.querySelectorAll("#advanced-settings select, #advanced-settings input[type=range]").forEach((control) => {
      control.disabled = interactionBusy || bulbActive;
    });
    document.querySelectorAll("#advanced-settings .settings-command button").forEach((button) => {
      const command = button.dataset.cameraCommand;
      const supported = command === "sleep"
        ? featureSupported(FEATURES.CAMERA_SLEEP)
        : command === "sensor-cleaning"
          ? featureSupported(FEATURES.SENSOR_CLEANING)
          : featureSupported(FEATURES.CAMERA_CLOCK_SYNC);
      button.disabled = interactionBusy || bulbActive || !supported ||
        ((command === "sleep" || command === "sensor-cleaning") && recording);
    });
    document.querySelectorAll("#focus-step-control button").forEach((button) => {
      button.disabled = interactionBusy || bulbActive || !state.liveActive;
    });
    const tapAction = effectiveTapAction();
    const tapFocusEnabled = !localPreviewSelected() && Boolean(tapAction) && state.liveActive &&
      !interactionBusy && !bulbActive;
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
    ui.mediaRefreshButton.disabled = !connected || interactionBusy;
    renderMediaTransfer();
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

  function refreshMedia() {
    if (state.mediaRefreshPromise) return state.mediaRefreshPromise;
    const promise = refreshMediaWhenCurrent();
    state.mediaRefreshPromise = promise;
    return promise.finally(() => {
      if (state.mediaRefreshPromise === promise) state.mediaRefreshPromise = null;
      renderAvailability();
    });
  }

  async function refreshMediaWhenCurrent() {
    while (state.session && featureSupported(FEATURES.MEDIA_BROWSER)) {
      while (cameraInteractionBusy() && state.session) await sleep(25);
      if (!state.session || !featureSupported(FEATURES.MEDIA_BROWSER)) return false;
      const rawSessionId = state.session.id;
      const interactionGeneration = state.refreshGeneration;
      ui.mediaRefreshButton.disabled = true;
      closeMediaPreview();
      try {
        const response = await api(`/v1/session/${encodeURIComponent(rawSessionId)}/media`);
        if (
          state.session?.id !== rawSessionId ||
          state.refreshGeneration !== interactionGeneration
        ) continue;
        clearMediaThumbnails();
        state.media = response.items || [];
        state.mediaLoaded = true;
        renderMedia();
        return true;
      } catch (error) {
        if (state.session?.id !== rawSessionId) return false;
        if (state.refreshGeneration !== interactionGeneration) continue;
        const normalized = captureError(error);
        showToast(normalized.message, true);
        return false;
      }
    }
    return false;
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
      const previewSupported = item.previewAvailable === true && featureSupported(FEATURES.MEDIA_PREVIEW);
      const thumbnail = document.createElement(previewSupported ? "button" : "span");
      thumbnail.className = "media-thumbnail";
      thumbnail.dataset.mediaId = item.id;
      if (previewSupported) {
        thumbnail.type = "button";
        thumbnail.setAttribute("aria-label", t("previewMedia", { name: item.name }));
        thumbnail.addEventListener("click", () => openMediaPreview(item));
      }
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
        remove.disabled = cameraInteractionBusy();
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
      download.disabled = !featureSupported(FEATURES.MEDIA_DOWNLOAD) || cameraInteractionBusy();
      download.addEventListener("click", () => downloadMedia(item));
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
      state.mediaThumbnailUrls.set(item.id, url);
      releaseObjectUrl(previous);
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
    ui.mediaList.querySelectorAll("img[src]").forEach((image) => image.removeAttribute("src"));
    state.mediaThumbnailUrls.forEach((url) => releaseObjectUrl(url));
    state.mediaThumbnailUrls.clear();
    state.mediaThumbnailLoads.clear();
    state.mediaThumbnailFailures.clear();
    state.mediaGeneration += 1;
  }

  function clearMediaPreview() {
    state.mediaPreviewGeneration += 1;
    ui.mediaPreviewImage.removeAttribute("src");
    releaseObjectUrl(state.mediaPreviewUrl);
    state.mediaPreviewUrl = null;
    state.mediaPreviewItem = null;
    ui.mediaPreviewImage.alt = "";
    ui.mediaPreviewImage.hidden = true;
    ui.mediaPreviewLoading.hidden = false;
    ui.mediaPreviewUnavailable.hidden = true;
  }

  function closeMediaPreview() {
    if (ui.mediaPreviewDialog.open) ui.mediaPreviewDialog.close();
    clearMediaPreview();
  }

  async function openMediaPreview(item) {
    if (!state.session || item.previewAvailable !== true || !featureSupported(FEATURES.MEDIA_PREVIEW)) return;
    clearMediaPreview();
    const generation = state.mediaPreviewGeneration;
    state.mediaPreviewItem = item;
    ui.mediaPreviewTitle.textContent = item.name;
    ui.mediaPreviewKind.textContent = String(item.kind).toUpperCase();
    ui.mediaPreviewDialog.showModal();
    try {
      const blob = await api(
        `/v1/session/${encodeURIComponent(state.session.id)}/media/${encodeURIComponent(item.id)}/preview`,
        { responseType: "blob" },
      );
      if (!blob.type.startsWith("image/") || blob.size <= 0 || blob.size > MAX_MEDIA_PREVIEW_BYTES) {
        throw new ApiError("Invalid media preview", { code: "INVALID_MEDIA_PREVIEW" });
      }
      if (generation !== state.mediaPreviewGeneration || state.mediaPreviewItem?.id !== item.id) return;
      state.mediaPreviewUrl = URL.createObjectURL(blob);
      ui.mediaPreviewImage.src = state.mediaPreviewUrl;
      ui.mediaPreviewImage.alt = t("previewMedia", { name: item.name });
      await ui.mediaPreviewImage.decode();
      if (generation !== state.mediaPreviewGeneration || state.mediaPreviewItem?.id !== item.id) return;
      ui.mediaPreviewImage.hidden = false;
      ui.mediaPreviewLoading.hidden = true;
    } catch (error) {
      if (generation !== state.mediaPreviewGeneration) return;
      ui.mediaPreviewImage.removeAttribute("src");
      releaseObjectUrl(state.mediaPreviewUrl);
      state.mediaPreviewUrl = null;
      ui.mediaPreviewImage.hidden = true;
      const normalized = captureError(error);
      ui.mediaPreviewLoading.hidden = true;
      ui.mediaPreviewUnavailable.hidden = false;
      showToast(normalized.message, true);
    }
  }

  function scheduleMediaTransferRender() {
    if (mediaTransferRenderTimer !== null) return;
    mediaTransferRenderTimer = window.setTimeout(() => {
      mediaTransferRenderTimer = null;
      renderMediaTransfer();
    }, 150);
  }

  function clearScheduledMediaTransferRender() {
    if (mediaTransferRenderTimer === null) return;
    window.clearTimeout(mediaTransferRenderTimer);
    mediaTransferRenderTimer = null;
  }

  function renderMediaTransfer() {
    const transfer = state.mediaDownload;
    ui.mediaTransfer.hidden = !transfer;
    ui.mediaRefreshButton.disabled = !state.session || cameraInteractionBusy();
    if (!transfer) return;
    ui.mediaTransferName.textContent = transfer.name;
    if (transfer.cancelling) {
      ui.mediaTransferStatus.textContent = t("cancellingDownload");
    } else {
      const transferred = formatBytes(transfer.bytesTransferred);
      const total = transfer.totalBytes;
      ui.mediaTransferStatus.textContent = total
        ? t("downloadProgress", {
          transferred,
          total: formatBytes(total),
          percent: Math.min(100, Math.floor((transfer.bytesTransferred / total) * 100)),
        })
        : t("downloadProgressUnknown", { transferred });
    }
    if (transfer.totalBytes) {
      ui.mediaTransferProgress.value = Math.min(1, transfer.bytesTransferred / transfer.totalBytes);
    } else {
      ui.mediaTransferProgress.removeAttribute("value");
    }
    ui.mediaTransferCancel.disabled = transfer.cancelling;
  }

  async function chooseMediaWritable(item) {
    const pickerAvailable = typeof window.showSaveFilePicker === "function";
    if (!mediaTransfer.shouldUseDirectWriter(item.sizeBytes, pickerAvailable)) {
      return { writable: null, cancelled: false };
    }
    try {
      const handle = await window.showSaveFilePicker({
        suggestedName: mediaTransfer.safeDownloadName(item.name),
      });
      return { writable: await handle.createWritable(), cancelled: false };
    } catch (error) {
      if (mediaTransfer.isAbortError(error)) return { writable: null, cancelled: true };
      throw error;
    }
  }

  function saveMediaBlob(blob, name) {
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = mediaTransfer.safeDownloadName(name);
    document.body.append(anchor);
    anchor.click();
    anchor.remove();
    releaseObjectUrl(url);
  }

  async function downloadMedia(item) {
    if (!state.session || !featureSupported(FEATURES.MEDIA_DOWNLOAD) || cameraInteractionBusy()) return;
    state.mediaDownloadPreparing = true;
    setOperationState(t("preparingDownload"));
    renderMedia();
    renderAvailability();
    let writable = null;
    let writableClosed = false;
    let transfer = null;
    try {
      const destination = await chooseMediaWritable(item);
      if (destination.cancelled) {
        setOperationState(t("ready"));
        return;
      }
      writable = destination.writable;
      const controller = new AbortController();
      state.mediaDownloadPreparing = false;
      transfer = {
        itemId: item.id,
        name: item.name,
        bytesTransferred: 0,
        totalBytes: Number(item.sizeBytes) > 0 ? Number(item.sizeBytes) : null,
        destination: writable ? "FILE_SYSTEM_ACCESS" : "BLOB_FALLBACK",
        controller,
        cancelling: false,
        silent: false,
      };
      state.mediaDownload = transfer;
      setOperationState(t("downloading", { name: item.name }));
      renderMedia();
      renderMediaTransfer();
      const response = await api(
        `/v1/session/${encodeURIComponent(state.session.id)}/media/${encodeURIComponent(item.id)}`,
        { responseType: "response", signal: controller.signal },
      );
      const result = await mediaTransfer.readResponse(response, {
        signal: controller.signal,
        expectedBytes: item.sizeBytes,
        writeChunk: writable ? (chunk) => writable.write(chunk) : null,
        onProgress: (progress) => {
          if (state.mediaDownload !== transfer) return;
          transfer.bytesTransferred = progress.bytesTransferred;
          transfer.totalBytes = progress.totalBytes;
          scheduleMediaTransferRender();
        },
      });
      if (controller.signal.aborted) throw mediaTransfer.cancellationError();
      if (writable) {
        await writable.close();
        writableClosed = true;
      } else if (result.blob) {
        saveMediaBlob(result.blob, item.name);
      } else {
        throw new Error("Media download completed without a file destination.");
      }
      showToast(t("downloaded", { name: item.name }));
      setOperationState(t("ready"));
    } catch (error) {
      if (writable && !writableClosed) {
        try {
          await writable.abort(error);
        } catch (_) {
          // The browser may already have closed or discarded the temporary file.
        }
      }
      if (mediaTransfer.isAbortError(error) || transfer?.controller.signal.aborted) {
        if (!transfer?.silent) {
          showToast(t("downloadCancelled"));
          setOperationState(t("ready"));
        }
      } else {
        const normalized = captureError(error);
        setOperationState(normalized.message, true);
        showToast(normalized.message, true);
      }
    } finally {
      clearScheduledMediaTransferRender();
      state.mediaDownloadPreparing = false;
      if (state.mediaDownload === transfer) state.mediaDownload = null;
      renderMedia();
      renderAvailability();
    }
  }

  function cancelMediaDownload({ silent = false } = {}) {
    const transfer = state.mediaDownload;
    if (!transfer || transfer.controller.signal.aborted) return;
    transfer.silent = silent;
    transfer.cancelling = true;
    transfer.controller.abort();
    renderMediaTransfer();
  }

  async function deleteMedia(item, button) {
    if (!state.session || !featureSupported(FEATURES.MEDIA_DELETE) || cameraInteractionBusy()) return;
    if (!window.confirm(t("deleteConfirm", { name: item.name }))) return;
    button.disabled = true;
    try {
      await api(
        `/v1/session/${encodeURIComponent(state.session.id)}/media/${encodeURIComponent(item.id)}`,
        { method: "DELETE" },
      );
      state.media = state.media.filter((candidate) => candidate.id !== item.id);
      const thumbnailUrl = state.mediaThumbnailUrls.get(item.id);
      state.mediaThumbnailUrls.delete(item.id);
      state.mediaThumbnailLoads.delete(item.id);
      state.mediaThumbnailFailures.delete(item.id);
      if (state.mediaPreviewItem?.id === item.id) closeMediaPreview();
      renderMedia();
      releaseObjectUrl(thumbnailUrl);
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
      cameraClock: { lastSyncAt: state.lastClockSyncAt },
      liveView: {
        active: previewActive(),
        previewInput: state.previewInput,
        cameraLiveActive: state.liveActive,
        requestedSource: state.liveSource,
        activeSource: state.activeLiveSource,
        requestedFps: state.requestedFps,
        observedFps: Number.isFinite(state.observedFps)
          ? Number(state.observedFps.toFixed(1))
          : null,
        frameBytes: state.frameBytes,
        contentType: state.frameContentType,
        lastFrameAt: state.lastFrameAt,
        rtpAudio: {
          ...currentRtpAudioStatus(),
          enabled: state.rtpAudioEnabled,
          busy: state.rtpAudioBusy,
          playbackGeneration: state.rtpAudioAfterGeneration,
          lastBytes: state.rtpAudioBytes,
          sampleRate: state.rtpAudioSampleRate,
          channels: state.rtpAudioChannels,
          lastPlayedAt: state.rtpAudioLastAt,
          playbackError: state.rtpAudioError,
        },
        monitoring: {
          histogramVisible: state.monitorSettings.histogramVisible,
          waveformVisible: state.monitorSettings.waveformVisible,
          zebraThresholdPercent: state.monitorSettings.zebraThresholdPercent,
          falseColorEnabled: state.monitorSettings.falseColorEnabled,
          focusPeakingEnabled: state.monitorSettings.focusPeakingEnabled,
          frameGuide: state.monitorSettings.frameGuide,
          safeAreaVisible: state.monitorSettings.safeAreaVisible,
          desqueeze: state.monitorSettings.desqueeze,
          lut: state.monitorSettings.cubeLut
            ? { loaded: true, size: state.monitorSettings.cubeLut.size }
            : { loaded: false },
          analysisError: state.monitorAnalysisError,
        },
        localVideo: {
          available: state.localVideoSupport.available,
          unavailableReason: state.localVideoSupport.reason,
          active: state.localVideoActive,
          busy: state.localVideoBusy,
          muted: state.localVideoMuted,
          deviceCount: state.localVideoInputs.length,
          selection: state.localVideoDeviceId ? "explicit" : "system-default",
          trackState: state.localVideoTrack?.readyState || null,
          settings: state.localVideoSettings,
          error: state.localVideoError,
        },
      },
      mediaTransfer: state.mediaDownload ? {
        active: true,
        bytesTransferred: state.mediaDownload.bytesTransferred,
        totalBytes: state.mediaDownload.totalBytes,
        destination: state.mediaDownload.destination,
        cancelling: state.mediaDownload.cancelling,
      } : { active: false },
      lastError: state.lastError,
    };
    return diagnostics.safeValue(report, {
      secrets: [state.token, ui.ccapiPasswordInput?.value, state.info?.serial],
    });
  }

  function currentPhysicalValidation() {
    const summary = diagnostics.physicalValidationSummary(state.capabilities, {
      connected: Boolean(state.session && state.status?.connected),
      info: state.info,
      confirmedFeatures: [...state.operatorConfirmedFeatures],
    });
    state.operatorConfirmedFeatures = new Set(summary.operatorConfirmedFeatures);
    return summary;
  }

  function physicalValidationTransport() {
    if (state.connectionMode === "ccapi") return "CCAPI_NETWORK";
    return state.session?.engine === "ccapi"
      ? "DESKTOP_BRIDGE_CCAPI"
      : "DESKTOP_BRIDGE_LIBGPHOTO2";
  }

  function renderPhysicalValidation() {
    if (!ui.physicalValidationList || !ui.copyPhysicalValidationButton) return;
    const summary = currentPhysicalValidation();
    ui.physicalValidationList.replaceChildren();
    const cryptoAvailable = Boolean(globalThis.crypto?.subtle);
    const statusMessage = summary.sessionStatus === "SIMULATOR"
      ? t("physicalValidationSimulator")
      : summary.sessionStatus !== "READY"
        ? t("physicalValidationDisconnected")
        : summary.eligibleFeatures.length === 0
          ? t("physicalValidationNoObserved")
          : !cryptoAvailable
            ? t("physicalValidationCryptoUnavailable")
            : "";
    ui.physicalValidationStatus.textContent = statusMessage;
    ui.physicalValidationStatus.classList.toggle(
      "warning",
      summary.sessionStatus !== "READY" || !cryptoAvailable,
    );
    summary.eligibleFeatures.forEach((feature) => {
      const row = document.createElement("label");
      row.className = "physical-validation-row";
      const content = document.createElement("span");
      const confirmed = state.operatorConfirmedFeatures.has(feature);
      content.textContent = `${feature} · ${t(confirmed ? "physicalValidationConfirmed" : "physicalValidationNotConfirmed")}`;
      const checkbox = document.createElement("input");
      checkbox.type = "checkbox";
      checkbox.checked = confirmed;
      checkbox.dataset.feature = feature;
      checkbox.setAttribute("aria-label", content.textContent);
      checkbox.addEventListener("change", () => {
        if (checkbox.checked) state.operatorConfirmedFeatures.add(feature);
        else state.operatorConfirmedFeatures.delete(feature);
        renderPhysicalValidation();
      });
      row.append(content, checkbox);
      ui.physicalValidationList.append(row);
    });
    ui.copyPhysicalValidationButton.disabled = summary.sessionStatus !== "READY"
      || summary.eligibleFeatures.length === 0
      || !cryptoAvailable;
  }

  function renderDiagnostics(report = diagnosticReport()) {
    if (!ui.diagnosticsOutput) return;
    ui.diagnosticsOutput.textContent = JSON.stringify(report, null, 2);
    renderPhysicalValidation();
  }

  async function copyText(value, successMessage, failureMessage) {
    try {
      await navigator.clipboard.writeText(value);
      showToast(successMessage);
      return;
    } catch (_) {
      try {
        const area = document.createElement("textarea");
        area.value = value;
        area.style.position = "fixed";
        area.style.opacity = "0";
        document.body.append(area);
        area.select();
        const copied = document.execCommand("copy");
        area.remove();
        if (!copied) throw new Error("copy failed");
        showToast(successMessage);
        return;
      } catch (error) {
        captureError(error);
        showToast(failureMessage, true);
      }
    }
  }

  async function copyDiagnostics() {
    if (cameraInteractionBusy()) return;
    beginCameraInteraction();
    renderAvailability();
    let report = null;
    try {
      await refreshCapabilityEvidence();
      report = diagnosticReport();
      const diagnosticText = JSON.stringify(report, null, 2);
      renderDiagnostics(report);
      await copyText(diagnosticText, t("copied"), t("copyFailed"));
    } finally {
      state.busy = false;
      if (report) renderDiagnostics(report);
      renderAvailability();
    }
  }

  async function copyPhysicalValidation() {
    if (cameraInteractionBusy()) return;
    beginCameraInteraction();
    renderAvailability();
    let report = null;
    try {
      await refreshCapabilityEvidence();
      report = diagnosticReport();
      const diagnosticText = JSON.stringify(report, null, 2);
      renderDiagnostics(report);
      const summary = currentPhysicalValidation();
      if (summary.sessionStatus !== "READY" || summary.eligibleFeatures.length === 0) return;
      const record = await diagnostics.physicalValidationRecord({
        summary,
        cameraModel: state.info?.model || state.session?.camera?.model || "unknown",
        transport: physicalValidationTransport(),
        generatedAt: report.generatedAt,
        productVersion: report.productVersion,
        diagnosticReport: diagnosticText,
      });
      await copyText(record, t("physicalValidationCopied"), t("physicalValidationCopyFailed"));
    } catch (error) {
      captureError(error);
      showToast(t("physicalValidationCopyFailed"), true);
    } finally {
      state.busy = false;
      if (report) renderDiagnostics(report);
      renderAvailability();
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
    ui.rtpAudioButton.addEventListener("click", toggleRtpAudio);
    ui.previewInputSelect.addEventListener("change", changePreviewInput);
    ui.localVideoDeviceSelect.addEventListener("change", changeLocalVideoDevice);
    ui.fpsSelect.addEventListener("change", changeFps);
    ui.liveSourceSelect.addEventListener("change", changeLiveSource);
    ui.tapActionSelect.addEventListener("change", () => {
      state.tapAction = ui.tapActionSelect.value;
      renderAvailability();
    });
    ui.monitoringButton.addEventListener("click", openMonitoringDialog);
    ui.monitoringDialogClose.addEventListener("click", () => ui.monitoringDialog.close());
    ui.monitoringDialog.addEventListener("click", (event) => {
      if (event.target === ui.monitoringDialog) ui.monitoringDialog.close();
    });
    ui.monitorHistogramToggle.addEventListener("change", () => {
      if (ui.monitorHistogramToggle.checked) ui.monitorWaveformToggle.checked = false;
      changeMonitoringSettings();
    });
    ui.monitorWaveformToggle.addEventListener("change", () => {
      if (ui.monitorWaveformToggle.checked) ui.monitorHistogramToggle.checked = false;
      changeMonitoringSettings();
    });
    ui.monitorLutLoad.addEventListener("click", () => ui.monitorLutFile.click());
    ui.monitorLutFile.addEventListener("change", () => void importCubeLutFile());
    ui.monitorLutRemove.addEventListener("click", removeCubeLut);
    [
      ui.monitorZebraSelect,
      ui.monitorFalseColorToggle,
      ui.monitorFocusPeakingToggle,
      ui.monitorFrameGuideSelect,
      ui.monitorSafeAreaToggle,
      ui.monitorDesqueezeSelect,
    ].forEach((control) => control.addEventListener("change", changeMonitoringSettings));
    ui.focusNearButton.addEventListener("click", () => driveFocus("NEAR"));
    ui.focusFarButton.addEventListener("click", () => driveFocus("FAR"));
    ui.viewfinder.addEventListener("click", tapFocusFromPointer);
    ui.viewfinder.addEventListener("keydown", (event) => {
      if (!["Enter", " "].includes(event.key) || !ui.viewfinder.classList.contains("tap-focus-enabled")) return;
      event.preventDefault();
      const bounds = ui.viewfinder.getBoundingClientRect();
      tapFocus(focusPointFromClient(bounds.left + bounds.width / 2, bounds.top + bounds.height / 2));
    });
    ui.localVideo.addEventListener("loadedmetadata", () => {
      if (!state.localVideoActive) return;
      state.localVideoSettings = {
        ...state.localVideoSettings,
        width: ui.localVideo.videoWidth || state.localVideoSettings?.width,
        height: ui.localVideo.videoHeight || state.localVideoSettings?.height,
      };
      renderLiveState();
      renderMonitoringFrame();
    });
    navigator.mediaDevices?.addEventListener?.("devicechange", () => {
      void refreshLocalVideoInputs();
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
    ui.mediaTransferCancel.addEventListener("click", () => cancelMediaDownload());
    ui.mediaPreviewClose.addEventListener("click", closeMediaPreview);
    ui.mediaPreviewDialog.addEventListener("close", clearMediaPreview);
    ui.mediaPreviewDialog.addEventListener("click", (event) => {
      if (event.target === ui.mediaPreviewDialog) closeMediaPreview();
    });
    ui.diagnosticsRefreshButton.addEventListener("click", () => refreshSession({ quiet: true }));
    ui.copyDiagnosticsButton.addEventListener("click", copyDiagnostics);
    ui.copyPhysicalValidationButton.addEventListener("click", copyPhysicalValidation);
    ui.settingDialogClose.addEventListener("click", () => ui.settingDialog.close());
    ui.settingDialog.addEventListener("click", (event) => {
      if (event.target === ui.settingDialog) ui.settingDialog.close();
    });
    window.addEventListener("resize", () => {
      if (previewActive()) applyLiveViewLayout();
    });
    window.addEventListener("beforeunload", () => {
      lutPreviewRenderer?.dispose();
      lutPreviewRenderer = null;
      cancelMediaDownload({ silent: true });
      clearMediaThumbnails();
      closeMediaPreview();
      stopLocalVideo({ announce: false });
      stopRtpAudio({ announce: false });
      if (!state.session) return;
      cancelEventLoop();
      if (featureSupported(FEATURES.EVENT_POLLING)) {
        api(`/v1/session/${encodeURIComponent(state.session.id)}/events`, {
          method: "DELETE",
          keepalive: true,
        }).catch(() => {});
      }
      api(`/v1/session/${encodeURIComponent(state.session.id)}`, { method: "DELETE", keepalive: true }).catch(() => {});
    });
  }

  async function initialize() {
    window.OpenEosIcons?.render();
    ui.ccapiUrlInput.value = readCameraPreference(CCAPI_URL_KEY, "http://192.168.1.2:8080");
    ui.ccapiUsernameInput.value = readCameraPreference(CCAPI_USERNAME_KEY);
    bindEvents();
    renderMonitoringControls();
    applyLanguage();
    renderLiveState();
    renderAvailability();
    await refreshLocalVideoInputs();
    await refreshHealth();
    const engine = state.health?.engines?.libgphoto2;
    if (engine?.available && !state.health.authRequired) await scanCameras();
  }

  initialize();
})();
